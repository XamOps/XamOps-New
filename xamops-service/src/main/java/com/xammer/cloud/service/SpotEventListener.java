package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.Optional;

/**
 * SpotEventListener
 *
 * Polls the centralized SQS FIFO queue for Scaling Events (Launch/Terminate)
 * and triggers AutoSpotting optimization logic.
 */
@Service
public class SpotEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SpotEventListener.class);
    private final SqsClient sqsClient;
    private final AutoSpottingService autoSpottingService;
    private final CloudAccountRepository cloudAccountRepository;
    private final AutoSpottingSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;

    // No base or local default: a blank URL means the legacy listener never polls (XC-11,
    // AC-SPN-11). Only application-prod.properties names the prod queue. plan-phase-05.md task 5.2.
    @Value("${autospotting.sqs.queue-url:}")
    private String queueUrl;

    // Code default false: autospotting.enabled is true only in application-prod.properties, so the
    // legacy listener is off on laptops, UAT and preprod (AC-SPN-11). plan-phase-05.md task 5.2.
    @Value("${autospotting.enabled:false}")
    private boolean globalEnabled;

    public SpotEventListener(SqsClient sqsClient,
            AutoSpottingService autoSpottingService,
            CloudAccountRepository cloudAccountRepository,
            AutoSpottingSettingsRepository settingsRepository,
            ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.autoSpottingService = autoSpottingService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
    }

    /** Whether the legacy SQS listener will poll: AutoSpotting on and a non-blank queue URL. */
    private boolean pollingEnabled() {
        return globalEnabled && queueUrl != null && !queueUrl.isEmpty();
    }

    /**
     * Log once at startup when the legacy listener will not poll, so a laptop, UAT or preprod node
     * that no longer names the prod queue (XC-11) says so plainly. Prod keeps polling the same queue
     * (AC-SPN-11).
     */
    @PostConstruct
    void logListenerState() {
        if (pollingEnabled()) {
            logger.info("AutoSpotting legacy SQS listener active (autospotting.enabled=true, queue configured).");
        } else {
            logger.info("AutoSpotting legacy SQS listener idle: it will not poll (autospotting.enabled={}, "
                    + "queue-url {}). This is expected outside prod.",
                    globalEnabled, (queueUrl == null || queueUrl.isEmpty()) ? "blank" : "set");
        }
    }

    @Scheduled(fixedDelayString = "${autospotting.sqs.poll-interval:5000}")
    public void pollEvents() {
        if (!pollingEnabled()) {
            return;
        }

        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20) // Long polling
                    .build();

            List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

            for (Message message : messages) {
                try {
                    processEvent(message.body());

                    // Delete message after successful processing
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                } catch (Exception e) {
                    logger.error("Failed to process scaling event: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error polling SQS for AutoSpotting events: {}", e.getMessage());
        }
    }

    private void processEvent(String messageBody) throws Exception {
        JsonNode root = objectMapper.readTree(messageBody);

        // EventBridge events wrapped in SQS have 'source', 'detail-type', and 'detail'
        JsonNode detail = root.path("detail");
        String eventSource = root.path("source").asText();
        String detailType = root.path("detail-type").asText();
        String accountId = root.path("account").asText();
        String region = root.path("region").asText();

        if (detail.isMissingNode()) {
            logger.warn("Received SQS message without 'detail' section. Body: {}", messageBody);
            return;
        }

        logger.info("Processing event from account={} region={} source={} type={}",
                accountId, region, eventSource, detailType);

        // 1. Find the CloudAccount in our database
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountId);
        if (accountOpt.isEmpty()) {
            logger.warn("Received event for unknown AWS account: {}", accountId);
            return;
        }
        CloudAccount account = accountOpt.get();

        // 2. Check if AutoSpotting is enabled for this account
        Optional<AutoSpottingSettings> settingsOpt = settingsRepository.findByAccountId(account.getId());
        if (settingsOpt.isEmpty() || !settingsOpt.get().isEnabled()) {
            logger.debug("AutoSpotting is disabled for account {}", accountId);
            return;
        }

        // 3. Route by event type
        if ("EC2 Spot Instance Interruption Warning".equals(detailType)) {
            // 2-minute warning before Spot termination — handle immediately
            autoSpottingService.handleSpotInterruption(account, region, detail);
        } else {
            // ASG lifecycle events (Launch/Terminate)
            autoSpottingService.handleScalingEvent(account, region, detail);
        }
    }
}
