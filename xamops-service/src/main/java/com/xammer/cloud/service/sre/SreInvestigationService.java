package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.repository.SreInvestigationRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

import org.springframework.beans.factory.annotation.Value;
import javax.annotation.PostConstruct;

import java.time.Instant;
import java.util.*;

@Service
public class SreInvestigationService {

    private static final Logger logger = LoggerFactory.getLogger(SreInvestigationService.class);
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLLS = 100;

    private final SreInvestigationRepository investigationRepo;
    private final SreNotificationRepository notificationRepo;
    private final OpenSreClient openSreClient;
    private final CloudAccountRepository cloudAccountRepo;
    private final ObservabilityConfigRepository observabilityConfigRepo;
    private final SreNotificationService notificationService;
    private final AwsClientProvider awsClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String openObserveExternalUrl;

    // Self-injection via proxy to ensure @Async is honoured (Spring AOP self-invocation bypass)
    @Lazy
    @Autowired
    private SreInvestigationService self;

    public SreInvestigationService(SreInvestigationRepository investigationRepo,
                                   SreNotificationRepository notificationRepo,
                                   OpenSreClient openSreClient,
                                   CloudAccountRepository cloudAccountRepo,
                                   ObservabilityConfigRepository observabilityConfigRepo,
                                   SreNotificationService notificationService,
                                   AwsClientProvider awsClientProvider,
                                   @Value("${openobserve.external.url:http://openobserve:5080}") String openObserveExternalUrl) {
        this.investigationRepo = investigationRepo;
        this.notificationRepo = notificationRepo;
        this.openSreClient = openSreClient;
        this.cloudAccountRepo = cloudAccountRepo;
        this.observabilityConfigRepo = observabilityConfigRepo;
        this.notificationService = notificationService;
        this.awsClientProvider = awsClientProvider;
        this.openObserveExternalUrl = openObserveExternalUrl;
    }

    public SreInvestigation createInvestigation(Long clientId,
                                                TriggerInvestigationRequest req,
                                                SreInvestigation.TriggerType triggerType) {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(clientId);
        inv.setCloudAccountId(req.getCloudAccountId());
        inv.setTriggerType(triggerType);
        inv.setResourceType(req.getResourceType());
        inv.setResourceId(req.getResourceId());
        inv.setResourceName(req.getResourceName());
        inv.setAlertPayload(req.getAlertPayload());
        inv.setStatus(SreInvestigation.InvestigationStatus.PENDING);
        SreInvestigation saved = investigationRepo.save(inv);
        self.runInvestigationAsync(saved.getId(), clientId);
        return saved;
    }

    @PostConstruct
    public void resetStaleInvestigations() {
        List<SreInvestigation> stale = investigationRepo.findByStatus(SreInvestigation.InvestigationStatus.RUNNING);
        stale.addAll(investigationRepo.findByStatus(SreInvestigation.InvestigationStatus.PENDING));
        if (!stale.isEmpty()) {
            logger.warn("Resetting {} stale investigation(s) left RUNNING/PENDING from previous server instance", stale.size());
            for (SreInvestigation inv : stale) {
                inv.setStatus(SreInvestigation.InvestigationStatus.FAILED);
                inv.setErrorMessage("Interrupted by server restart.");
                inv.setCompletedAt(Instant.now());
                investigationRepo.save(inv);
            }
        }
    }

    public boolean hasRecentInvestigation(String resourceId, Instant since) {
        return investigationRepo.countRecentByResourceId(resourceId, since) > 0;
    }

    public List<SreInvestigation> listForClient(Long clientId) {
        return investigationRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    public Optional<SreInvestigation> getById(Long id, Long clientId) {
        return investigationRepo.findById(id)
            .filter(inv -> inv.getClientId().equals(clientId));
    }

    @Async
    public void runInvestigationAsync(Long investigationId, Long clientId) {
        logger.info("SRE [{}] async task started on thread {}", investigationId, Thread.currentThread().getName());
        SreInvestigation inv = null;
        try {
            inv = investigationRepo.findById(investigationId).orElse(null);
            if (inv == null) {
                logger.warn("SRE [{}] investigation not found in DB — aborting", investigationId);
                return;
            }

            inv.setStatus(SreInvestigation.InvestigationStatus.RUNNING);
            inv.setStartedAt(Instant.now());
            investigationRepo.save(inv);
            logger.info("SRE [{}] status set to RUNNING, building input for resourceType={} resourceId={}",
                    investigationId, inv.getResourceType(), inv.getResourceId());

            Map<String, Object> input = buildOpenSreInput(inv);
            logger.info("SRE [{}] input built, creating OpenSRE thread", investigationId);

            String threadId = openSreClient.createThread();
            logger.info("SRE [{}] thread created: {}", investigationId, threadId);
            inv.setOpensreThreadId(threadId);
            investigationRepo.save(inv);

            logger.info("SRE [{}] starting run on thread {}", investigationId, threadId);
            openSreClient.startRun(threadId, input);
            logger.info("SRE [{}] run started — polling every {}ms, max {} polls", investigationId, POLL_INTERVAL_MS, MAX_POLLS);

            int polls = 0;
            while (polls < MAX_POLLS) {
                Thread.sleep(POLL_INTERVAL_MS);
                polls++;

                // Single getState call per poll cycle
                Map<String, Object> state = openSreClient.getState(threadId);
                String status = (String) state.getOrDefault("status", "unknown");
                logger.debug("SRE [{}] poll {}/{} — status={}", investigationId, polls, MAX_POLLS, status);

                if ("idle".equals(status)) {
                    Object values = state.get("values");
                    if (values instanceof Map && !((Map<?, ?>) values).isEmpty()) {
                            Map<String, Object> valuesMap = (Map<String, Object>) values;
                        storeReport(inv, valuesMap);
                        inv.setStatus(SreInvestigation.InvestigationStatus.COMPLETED);
                        inv.setCompletedAt(Instant.now());
                        investigationRepo.save(inv);
                        logger.info("SRE [{}] COMPLETED after {} polls", investigationId, polls);
                        notificationService.notifyComplete(inv, clientId);
                        return;
                    }
                }

                if ("error".equals(status) || "interrupted".equals(status)) {
                    throw new RuntimeException("OpenSRE thread status: " + status);
                }
            }
            throw new RuntimeException("Investigation timed out after " + (MAX_POLLS * POLL_INTERVAL_MS / 1000) + "s");

        } catch (Exception e) {
            logger.error("SRE [{}] investigation FAILED: {}", investigationId, e.getMessage(), e);
            if (inv != null) {
                inv.setStatus(SreInvestigation.InvestigationStatus.FAILED);
                inv.setErrorMessage(e.getMessage());
                inv.setCompletedAt(Instant.now());
                try {
                    investigationRepo.save(inv);
                } catch (Exception saveEx) {
                    logger.error("SRE [{}] could not save FAILED status: {}", investigationId, saveEx.getMessage());
                }
                notificationService.notifyFailed(inv, clientId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void storeReport(SreInvestigation inv, Map<String, Object> values) {
        inv.setReportSummary((String) values.getOrDefault("summary", ""));
        inv.setReportRootCause((String) values.getOrDefault("root_cause", ""));
        inv.setReportFullMarkdown((String) values.getOrDefault("report", ""));

        Object recs = values.get("investigation_recommendations");
        if (recs instanceof List) {
            try {
                inv.setReportRecommendations(objectMapper.writeValueAsString(recs));
            } catch (Exception ignored) {}
        }
    }

    private Map<String, Object> buildOpenSreInput(SreInvestigation inv) {
        Map<String, Object> input = new HashMap<>();
        input.put("mode", "investigation");
        input.put("alert_name", inv.getResourceName() + " " + inv.getResourceType().name());
        input.put("pipeline_name", resolvePipelineName(inv.getResourceType()));
        input.put("severity", "critical");
        Map<String, Object> rawAlert = new HashMap<>();
        rawAlert.put("resourceType", inv.getResourceType().name());
        rawAlert.put("resourceId", inv.getResourceId());
        rawAlert.put("resourceName", inv.getResourceName() != null ? inv.getResourceName() : inv.getResourceId());
        if (inv.getAlertPayload() != null && !inv.getAlertPayload().isBlank()) {
            rawAlert.put("alert_payload", inv.getAlertPayload());
        }
        input.put("raw_alert", rawAlert);

        Map<String, Object> integrations = new HashMap<>();
        buildObservabilityIntegration(inv.getClientId(), integrations);
        if (inv.getCloudAccountId() != null) {
            buildCloudIntegration(inv.getCloudAccountId(), inv.getResourceType(), integrations);
        }
        input.put("resolved_integrations", integrations);
        return input;
    }

    private String resolvePipelineName(SreInvestigation.ResourceType resourceType) {
        return switch (resourceType) {
            case K8S_POD -> "kubernetes";
            case EC2, RDS -> "aws";
            case AZURE_VM -> "azure";
            case GCP_VM -> "gcp";
            case GENERIC -> "generic";
        };
    }

    private void buildObservabilityIntegration(Long clientId, Map<String, Object> integrations) {
        observabilityConfigRepo.findByClientId(clientId).ifPresent(obs -> {
            Map<String, Object> oo = new HashMap<>();
            oo.put("base_url", openObserveExternalUrl);
            oo.put("org", obs.getO2OrgId());
            oo.put("api_token", obs.getO2ApiKey());
            oo.put("connection_verified", true);
            integrations.put("openobserve", oo);
        });
    }

    private void buildCloudIntegration(Long cloudAccountId,
                                       SreInvestigation.ResourceType resourceType,
                                       Map<String, Object> integrations) {
        cloudAccountRepo.findById(cloudAccountId).ifPresent(account -> {
            String provider = account.getProvider();
            if ("AWS".equals(provider)) {
                buildAwsIntegration(account, resourceType, integrations);
            } else if ("Azure".equals(provider)) {
                buildAzureIntegration(account, integrations);
            }
        });
    }

    private void buildAwsIntegration(CloudAccount account,
                                     SreInvestigation.ResourceType resourceType,
                                     Map<String, Object> integrations) {
        try {
            AwsCredentials creds = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
            // CloudAccount has no region field — default to us-east-1
            String region = "us-east-1";

            if (resourceType == SreInvestigation.ResourceType.K8S_POD) {
                Map<String, Object> eks = new HashMap<>();
                eks.put("aws_access_key_id", creds.accessKeyId());
                eks.put("aws_secret_access_key", creds.secretAccessKey());
                eks.put("region", region);
                integrations.put("eks", eks);
            }

            Map<String, Object> cw = new HashMap<>();
            cw.put("aws_access_key_id", creds.accessKeyId());
            cw.put("aws_secret_access_key", creds.secretAccessKey());
            cw.put("region", region);
            integrations.put("cloudwatch", cw);
        } catch (Exception e) {
            logger.warn("Could not resolve AWS credentials for account {}: {}", account.getId(), e.getMessage());
        }
    }

    private void buildAzureIntegration(CloudAccount account, Map<String, Object> integrations) {
        if (account.getAzureSubscriptionId() != null) {
            Map<String, Object> azure = new HashMap<>();
            azure.put("subscription_id", account.getAzureSubscriptionId());
            azure.put("tenant_id", account.getAzureTenantId());
            azure.put("client_id", account.getAzureClientId());
            integrations.put("azure_monitor", azure);
        }
    }
}
