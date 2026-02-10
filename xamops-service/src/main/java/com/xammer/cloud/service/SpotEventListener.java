package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
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

    @Value("${autospotting.sqs.queue-url}")
    private String queueUrl;

    @Value("${autospotting.enabled:true}")
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

    @Scheduled(fixedDelayString = "${autospotting.sqs.poll-interval:5000}")
    public void pollEvents() {
        if (!globalEnabled || queueUrl == null || queueUrl.isEmpty()) {
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

        // EventBridge events wrapped in SQS usually have a 'detail' section
        JsonNode detail = root.path("detail");
        String eventSource = root.path("source").asText();
        String accountId = root.path("account").asText();
        String region = root.path("region").asText();

        if (detail.isMissingNode()) {
            logger.warn("Received SQS message without 'detail' section. Body: {}", messageBody);
            return;
        }

        logger.info("Processing scaling event from account {} in region {}: {}", accountId, region, eventSource);

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

        // 3. Delegate to AutoSpottingService for swap logic
        // The service will check ASG tags and hourly pricing
        autoSpottingService.handleScalingEvent(account, region, detail);
    }
}
