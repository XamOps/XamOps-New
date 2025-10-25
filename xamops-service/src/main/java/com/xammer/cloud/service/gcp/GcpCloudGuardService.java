// src/main/java/com/xammer/cloud/service/gcp/GcpCloudGuardService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
// Correct Compute Engine Region import
import com.google.cloud.compute.v1.Region;
// *** REMOVE THIS INCORRECT IMPORT ***
// import com.google.cloud.compute.v1.RegionName;
// *** ADD THIS IMPORT FOR THE REQUEST BUILDER ***
import com.google.cloud.compute.v1.GetRegionRequest;
// Other imports remain the same
import com.google.cloud.compute.v1.NetworksClient;
import com.google.cloud.compute.v1.Quota;
import com.google.cloud.compute.v1.RegionsClient;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpCloudGuardService {

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCache;
    private final GcpDataService gcpDataService;
    private final GcpCostService gcpCostService;
    private final GcpSecurityService gcpSecurityService;
    private final EmailService emailService;

    // Define Quota details
    private static final String NETWORK_QUOTA_NAME = "NETWORKS";
    private static final double NETWORK_QUOTA_THRESHOLD = 75.0;

    @Autowired
    public GcpCloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider,
            RedisCacheService redisCache,
            GcpDataService gcpDataService,
            GcpCostService gcpCostService,
            GcpSecurityService gcpSecurityService,
            EmailService emailService
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
        this.redisCache = redisCache;
        this.gcpDataService = gcpDataService;
        this.gcpCostService = gcpCostService;
        this.gcpSecurityService = gcpSecurityService;
        this.emailService = emailService;
    }

    private CloudAccount getAccount(String accountId) {
        // Use findByProviderAccountId which checks GCP Project ID as well
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));

        // Force initialization of the Client proxy if needed (optional, depends on usage)
        if (account.getClient() != null) {
            try {
                account.getClient().getEmail(); // Access a field to trigger initialization
            } catch (Exception e) {
                log.warn("Could not initialize client proxy for account {}", accountId, e);
            }
        }
        return account;
    }

    @Transactional(readOnly = true) // Add transactional annotation
    public CompletableFuture<List<AlertDto>> getAlerts(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = "gcpAlerts-" + gcpProjectId;
        log.info("🔍 Fetching alerts for GCP project: {}", gcpProjectId);

        if (!forceRefresh) {
            Optional<List<AlertDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                log.info("✅ Returning cached GCP alerts for project {}", gcpProjectId);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(gcpProjectId);

        // Eagerly fetch email before async operations
        String clientEmail = null;
        try {
            if (account.getClient() != null) {
                // Ensure the client object is fully loaded within the transaction
                clientEmail = account.getClient().getEmail();
                log.debug("Fetched client email: {}", clientEmail); // Debug log
            } else {
                log.warn("Client is null for account {}", gcpProjectId);
            }
        } catch (Exception e) {
            log.error("Error fetching client email for account {}: {}", gcpProjectId, e.getMessage(), e);
        }
        final String email = clientEmail; // Final variable for lambda

        CompletableFuture<List<AlertDto>> quotasFuture = checkQuotaAlerts(account);
        CompletableFuture<List<AlertDto>> anomaliesFuture = checkCostAnomalies(account);
        CompletableFuture<List<AlertDto>> securityFuture = checkSecurityFindings(account);

        return CompletableFuture.allOf(quotasFuture, anomaliesFuture, securityFuture)
                .thenApply(v -> {
                    List<AlertDto> alerts = new ArrayList<>();
                    alerts.addAll(quotasFuture.join());
                    alerts.addAll(anomaliesFuture.join());
                    alerts.addAll(securityFuture.join());

                    // Send emails if configured and alerts exist
                    if (email != null && !email.isEmpty() && !alerts.isEmpty()) {
                        log.info("Sending {} alert emails to {}", alerts.size(), email);
                        for (AlertDto alert : alerts) {
                            try {
                                emailService.sendEmail(email, "[GCP Alert] " + alert.getName(), alert.getDescription());
                            } catch (Exception e) {
                                log.error("Failed to send GCP alert email for alert '{}': {}", alert.getId(), e.getMessage());
                            }
                        }
                    } else if (!alerts.isEmpty()){
                        log.warn("No client email configured for account {}, cannot send alert emails.", gcpProjectId);
                    }

                    redisCache.put(cacheKey, alerts, 10); // Cache for 10 minutes
                    log.info("✅ Returning {} total GCP alerts for project {}", alerts.size(), gcpProjectId);
                    return alerts;
                })
                .exceptionally(ex -> {
                    log.error("❌ Error fetching GCP alerts for project {}: {}", gcpProjectId, ex.getMessage(), ex);
                    return Collections.emptyList();
                });
    }

    private CompletableFuture<List<AlertDto>> checkQuotaAlerts(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<AlertDto> alerts = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Quota alerts for GCP project {}", gcpProjectId);

            try {
                // 1. Get Network Quota Limit (Global)
                Optional<RegionsClient> regionsClientOpt = gcpClientProvider.getRegionsClient(gcpProjectId);
                if (regionsClientOpt.isEmpty()) {
                    log.warn("Could not get RegionsClient for project {}", gcpProjectId);
                    return alerts;
                }
                double networkLimit = 0;
                try (RegionsClient regionsClient = regionsClientOpt.get()) {
                    // Need a valid region to query project-wide quotas
                    String sampleRegion = gcpDataService.getAllKnownRegions().stream().findFirst().orElse("us-central1");

                    // --- FIX: Build GetRegionRequest ---
                    GetRegionRequest regionRequest = GetRegionRequest.newBuilder()
                            .setProject(gcpProjectId)
                            .setRegion(sampleRegion)
                            .build();
                    Region regionInfo = regionsClient.get(regionRequest); // Use the request object
                    // --- END FIX ---

                    Optional<Quota> networkQuotaOpt = regionInfo.getQuotasList().stream()
                            .filter(q -> NETWORK_QUOTA_NAME.equals(q.getMetric()))
                            .findFirst();
                    if (networkQuotaOpt.isPresent()) {
                        networkLimit = networkQuotaOpt.get().getLimit();
                    } else {
                        log.warn("Could not find '{}' quota for project {}. Using default limit.", NETWORK_QUOTA_NAME, gcpProjectId);
                        networkLimit = 5; // Default VPC limit
                    }
                }

                // 2. Get Current Network Count
                Optional<NetworksClient> networksClientOpt = gcpClientProvider.getNetworksClient(gcpProjectId);
                if(networksClientOpt.isEmpty()) {
                    log.warn("Could not get NetworksClient for project {}", gcpProjectId);
                    return alerts;
                }
                double networkUsage = 0;
                try (NetworksClient networksClient = networksClientOpt.get()) {
                    networkUsage = StreamSupport.stream(
                                    networksClient.list(gcpProjectId).iterateAll().spliterator(), false)
                            .count();
                }

                // 3. Compare and Create Alert
                if (networkLimit > 0) {
                    double utilization = (networkUsage / networkLimit) * 100.0;
                    if (utilization >= NETWORK_QUOTA_THRESHOLD) {
                        String status = determineQuotaStatus(networkUsage, networkLimit);
                        alerts.add(new AlertDto(
                                "quota-gcp-networks-global-" + account.getId(),
                                "VPC Network",
                                "VPC Network Limit Approaching",
                                String.format("Current VPC count: %.0f / %.0f (%.1f%%)", networkUsage, networkLimit, utilization),
                                status,
                                networkUsage,
                                networkLimit,
                                "QUOTA",
                                "global"
                        ));
                    }
                }
                log.info("Found {} GCP quota alerts for project {}", alerts.size(), gcpProjectId);

            } catch (Exception e) {
                log.error("Error checking GCP quotas for project {}: {}", gcpProjectId, e.getMessage(), e);
            }
            return alerts;
        });
    }


    private CompletableFuture<List<AlertDto>> checkCostAnomalies(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<AlertDto> alerts = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Cost Anomalies for GCP project {}", gcpProjectId);
            try {
                // Use the existing logic from GcpCostService which flags anomalies in historical data
                List<GcpCostDto> historicalCosts = gcpCostService.getHistoricalCostsSync(gcpProjectId);
                AtomicInteger counter = new AtomicInteger(1);

                historicalCosts.stream()
                        .filter(GcpCostDto::isAnomaly)
                        .forEach(anomaly -> {
                            String uniqueId = String.format("anomaly-gcp-%s-%d", anomaly.getName(), counter.getAndIncrement());
                            alerts.add(new AlertDto(
                                    uniqueId,
                                    "Billing", // Generic service for cost
                                    "Cost Anomaly Detected",
                                    String.format("Potential cost anomaly detected for period %s. Cost: $%.2f",
                                            anomaly.getName(), anomaly.getAmount()),
                                    "CRITICAL", // Treat flagged history as critical alert
                                    anomaly.getAmount(), // Usage can be the anomalous amount
                                    0, // Limit is not applicable
                                    "ANOMALY",
                                    "Global" // Costs are usually global or tied to billing account
                            ));
                        });
                log.info("Found {} GCP cost anomaly alerts for project {}", alerts.size(), gcpProjectId);
            } catch (Exception e) {
                log.error("Error checking GCP cost anomalies for project {}: {}", gcpProjectId, e.getMessage(), e);
            }
            return alerts;
        });
    }

    private CompletableFuture<List<AlertDto>> checkSecurityFindings(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<AlertDto> alerts = new ArrayList<>();
            String gcpProjectId = account.getGcpProjectId();
            log.info("Checking Security Findings for GCP project {}", gcpProjectId);
            try {
                // Fetch findings (will use cache if available)
                List<GcpSecurityFinding> findings = gcpSecurityService.getSecurityFindings(gcpProjectId).join();
                AtomicInteger counter = new AtomicInteger(1);

                findings.stream()
                        .filter(f -> "CRITICAL".equalsIgnoreCase(f.getSeverity()) || "HIGH".equalsIgnoreCase(f.getSeverity()))
                        .forEach(finding -> {
                            // Attempt to extract a more specific ID or use a counter
                            String resourceIdPart = finding.getResourceName() != null ? finding.getResourceName().substring(finding.getResourceName().lastIndexOf('/') + 1) : "unknown";
                            String uniqueId = String.format("security-%s-%s-%s-%d",
                                    finding.getCategory().toLowerCase().replaceAll("[^a-z0-9]", ""),
                                    resourceIdPart.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase(),
                                    finding.getSeverity().toLowerCase(),
                                    counter.getAndIncrement());

                            alerts.add(new AlertDto(
                                    uniqueId,
                                    finding.getCategory(),
                                    "High Severity Security Finding",
                                    finding.getDescription() + " (Resource: " + finding.getResourceName() + ")",
                                    finding.getSeverity().toUpperCase(),
                                    0, // Usage/Limit not applicable
                                    0,
                                    "SECURITY",
                                    "global" // Security findings might be regional, but often aggregated globally. Simplify for now.
                            ));
                        });
                log.info("Found {} CRITICAL/HIGH GCP security finding alerts for project {}", alerts.size(), gcpProjectId);

            } catch (Exception e) {
                log.error("Error checking GCP security findings for project {}: {}", gcpProjectId, e.getMessage(), e);
            }
            return alerts;
        });
    }

    private String determineQuotaStatus(double usage, double limit) {
        if (limit <= 0) return "UNKNOWN"; // Avoid division by zero
        double percentage = (usage / limit) * 100.0;
        if (percentage >= 90) return "CRITICAL";
        if (percentage >= NETWORK_QUOTA_THRESHOLD) return "WARNING"; // Use defined threshold
        return "OK";
    }
}