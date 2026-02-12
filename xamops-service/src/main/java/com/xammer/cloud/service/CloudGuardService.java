package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.AlertsResponse;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.dto.ServiceResult;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.NatGatewayState;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);
    private static final double ALERT_THRESHOLD_PERCENTAGE = 75.0;

    // Shared scheduler for timeout operations (reused across all calls to prevent thread exhaustion)
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
        Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "alerts-timeout-scheduler");
            t.setDaemon(true); // Daemon threads allow JVM to exit cleanly
            return t;
        });

    // Network Quota Codes (AWS Service Quotas)
    private static final String QUOTA_VPC = "L-F678F1CE";               // VPCs per Region
    private static final String QUOTA_INTERNET_GATEWAY = "L-A4707A72";  // Internet Gateways per Region
    private static final String QUOTA_NAT_GATEWAY = "L-FE5A380F";       // NAT Gateways per AZ
    private static final String QUOTA_ELASTIC_IP = "L-0263D0A3";        // Elastic IPs per Region

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CloudListService cloudListService;
    private final FinOpsService finOpsService;
    private final EmailService emailService;
    private final DashboardDataService dashboardDataService;
    private final AwsSecurityHubService securityHubService;
    private final AlertEmailBuilder alertEmailBuilder;

    @Autowired
    public CloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            RedisCacheService redisCache,
            @Lazy CloudListService cloudListService,
            FinOpsService finOpsService,
            EmailService emailService,
            @Lazy DashboardDataService dashboardDataService,
            AwsSecurityHubService securityHubService,
            AlertEmailBuilder alertEmailBuilder
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cloudListService = cloudListService;
        this.finOpsService = finOpsService;
        this.emailService = emailService;
        this.dashboardDataService = dashboardDataService;
        this.securityHubService = securityHubService;
        this.alertEmailBuilder = alertEmailBuilder;
    }

    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }

        CloudAccount account = accounts.get(0);

        // Force initialization of the Client proxy
        if (account.getClient() != null) {
            try {
                account.getClient().getEmail();
            } catch (Exception e) {
                logger.warn("Could not initialize client for account {}", accountId);
            }
        }

        return account;
    }

    // REMOVED @Async - already called from async context (getAlertsWithDetails)
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getVpcQuotaAlerts(String accountId, boolean forceRefresh) {
        String cacheKey = "vpcQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                logger.info("--- LOADING FROM REDIS CACHE (TypeReference): {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account, forceRefresh);

        return activeRegionsFuture.thenCompose(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            CompletableFuture<List<ResourceDto>> vpcResourcesFuture = cloudListService.fetchVpcsForCloudlist(account, activeRegions);

            // Use thenApply (not Async) to avoid creating extra thread
            return vpcResourcesFuture.thenApply(vpcResources -> {
                try {
                    Map<String, Long> vpcCountsByRegion = vpcResources.stream()
                            .collect(Collectors.groupingBy(ResourceDto::getRegion, Collectors.counting()));

                    // Process regions sequentially (we're already in async context)
                    // This prevents thread exhaustion from CompletableFuture.supplyAsync() creating unbounded threads
                    List<DashboardData.ServiceQuotaInfo> allQuotaInfo = new ArrayList<>();

                    for (DashboardData.RegionStatus region : activeRegions) {
                        try {
                            logger.debug("Fetching VPC quota for account {} in region {}", accountId, region.getRegionId());
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, region.getRegionId());

                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode("vpc")
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);

                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals("L-F678F1CE"))
                                    .findFirst();

                            if (quota.isPresent()) {
                                ServiceQuota serviceQuota = quota.get();
                                double currentCount = vpcCountsByRegion.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                double usage = currentCount;
                                double utilization = (serviceQuota.value() > 0) ? (usage / serviceQuota.value()) * 100.0 : 0.0;

                                if (utilization > ALERT_THRESHOLD_PERCENTAGE || usage > 0) {
                                    allQuotaInfo.add(new DashboardData.ServiceQuotaInfo(
                                            "VPC",
                                            serviceQuota.quotaName(),
                                            serviceQuota.value(),
                                            usage,
                                            region.getRegionId(),
                                            "Active"
                                    ));
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get VPC quota for region {}: {}", region.getRegionId(), e.getMessage());
                        }
                    }

                    logger.info("Successfully fetched {} VPC quota alerts for account {}.", allQuotaInfo.size(), accountId);
                    redisCache.put(cacheKey, allQuotaInfo, 10);
                    return allQuotaInfo;
                } catch (Exception e) {
                    logger.error("Could not fetch VPC quota alerts for account {}.", accountId, e);
                    return Collections.emptyList();
                }
            });
        });
    }

    /**
     * Get network quota alerts for Internet Gateways, NAT Gateways, and Elastic IPs.
     * REMOVED @Async - already called from async context (getAlertsWithDetails)
     */
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getNetworkQuotaAlerts(String accountId, boolean forceRefresh) {
        String cacheKey = "networkQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                logger.info("--- LOADING NETWORK QUOTA ALERTS FROM REDIS CACHE: {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account, forceRefresh);

        // Use thenApply (not Async) to avoid creating extra thread
        return activeRegionsFuture.thenApply(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                return Collections.<DashboardData.ServiceQuotaInfo>emptyList();
            }

            List<DashboardData.ServiceQuotaInfo> allQuotaInfo = new ArrayList<>();

            for (DashboardData.RegionStatus region : activeRegions) {
                try {
                    // Count resources in this region
                    long igwCount = countInternetGateways(account, region.getRegionId());
                    long natCount = countNatGateways(account, region.getRegionId());
                    long eipCount = countElasticIps(account, region.getRegionId());

                    // Check quotas
                    checkAndAddQuota(account, region.getRegionId(), QUOTA_INTERNET_GATEWAY,
                            "Internet Gateway", "Internet gateways per Region", igwCount, allQuotaInfo);
                    checkAndAddQuota(account, region.getRegionId(), QUOTA_NAT_GATEWAY,
                            "NAT Gateway", "NAT gateways per Availability Zone", natCount, allQuotaInfo);
                    checkAndAddQuota(account, region.getRegionId(), QUOTA_ELASTIC_IP,
                            "Elastic IP", "EC2-VPC Elastic IPs", eipCount, allQuotaInfo);

                } catch (Exception e) {
                    logger.warn("Failed to check network quotas in region {}: {}", region.getRegionId(), e.getMessage());
                }
            }

            logger.info("Successfully fetched {} network quota alerts for account {}.", allQuotaInfo.size(), accountId);
            redisCache.put(cacheKey, allQuotaInfo, 10);
            return allQuotaInfo;
        });
    }

    /**
     * Count Internet Gateways in a region.
     */
    private long countInternetGateways(CloudAccount account, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeInternetGateways().internetGateways().size();
        } catch (Exception e) {
            logger.warn("Failed to count Internet Gateways in region {}: {}", regionId, e.getMessage());
            return 0;
        }
    }

    /**
     * Count NAT Gateways in a region (excluding deleted ones).
     */
    private long countNatGateways(CloudAccount account, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeNatGateways().natGateways().stream()
                    .filter(ng -> ng.state() != NatGatewayState.DELETED)
                    .count();
        } catch (Exception e) {
            logger.warn("Failed to count NAT Gateways in region {}: {}", regionId, e.getMessage());
            return 0;
        }
    }

    /**
     * Count Elastic IPs in a region.
     */
    private long countElasticIps(CloudAccount account, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeAddresses().addresses().size();
        } catch (Exception e) {
            logger.warn("Failed to count Elastic IPs in region {}: {}", regionId, e.getMessage());
            return 0;
        }
    }

    /**
     * Check a specific quota and add to the list if usage is above threshold or non-zero.
     */
    private void checkAndAddQuota(CloudAccount account, String regionId, String quotaCode,
                                   String serviceName, String quotaName, long currentUsage,
                                   List<DashboardData.ServiceQuotaInfo> quotaList) {
        try {
            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, regionId);
            ListServiceQuotasRequest request = ListServiceQuotasRequest.builder()
                    .serviceCode("vpc")
                    .build();

            Optional<ServiceQuota> quota = sqClient.listServiceQuotas(request).quotas().stream()
                    .filter(q -> q.quotaCode().equals(quotaCode))
                    .findFirst();

            quota.ifPresent(q -> {
                double limit = q.value();
                double utilization = (limit > 0) ? (currentUsage / limit) * 100.0 : 0.0;

                if (utilization >= ALERT_THRESHOLD_PERCENTAGE || currentUsage > 0) {
                    quotaList.add(new DashboardData.ServiceQuotaInfo(
                            serviceName,
                            quotaName,
                            limit,
                            currentUsage,
                            regionId,
                            "Active"
                    ));
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to check quota {} in region {}: {}", quotaCode, regionId, e.getMessage());
        }
    }

    @Async("awsTaskExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<List<AlertDto>> getAlerts(String accountId, boolean forceRefresh) {
        logger.info("🔍 Fetching alerts for account: {}", accountId);

        if (!forceRefresh) {
            // 1. Check database cache first (persistent, survives restarts)
            String dbKey = "alerts-" + accountId;
            List<AlertDto> cachedAlerts = (List<AlertDto>) dashboardDataService.getAlertsSnapshot(
                dbKey, new TypeReference<List<AlertDto>>() {});
            if (cachedAlerts != null && !cachedAlerts.isEmpty()) {
                logger.info("✅ Serving {} alerts from database cache for account {}",
                    cachedAlerts.size(), accountId);
                return CompletableFuture.completedFuture(cachedAlerts);
            }
        }

        // 2. Fetch fresh data from AWS API
        logger.info("Fetching fresh alerts from AWS API for account {}", accountId);
        CloudAccount account = getAccount(accountId);

        // Eagerly fetch email before async
        String clientEmail = null;
        try {
            if (account.getClient() != null) {
                clientEmail = account.getClient().getEmail();
            }
        } catch (Exception e) {
            logger.warn("Could not fetch client email for account {}: {}", accountId, e.getMessage());
        }

        final String email = clientEmail;
        final String accountName = account.getAccountName() != null ? account.getAccountName() : accountId;

        // Fetch all alert types in parallel
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotasFuture = getVpcQuotaAlerts(accountId, forceRefresh);
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> networkQuotasFuture = getNetworkQuotaAlerts(accountId, forceRefresh);
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account, forceRefresh);
        CompletableFuture<List<AlertDto>> securityFuture = securityHubService.getSecurityAlerts(accountId, forceRefresh);

        return CompletableFuture.allOf(vpcQuotasFuture, networkQuotasFuture, anomaliesFuture, securityFuture)
                .thenApply(v -> {
                    List<AlertDto> alerts = new ArrayList<>();
                    AtomicInteger counter = new AtomicInteger(1);

                    // Process VPC quota alerts
                    List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotasFuture.join();
                    if (vpcQuotas != null && !vpcQuotas.isEmpty()) {
                        List<AlertDto> quotaAlerts = vpcQuotas.stream()
                                .map(q -> createQuotaAlert(q, "VPC", counter))
                                .collect(Collectors.toList());
                        alerts.addAll(quotaAlerts);
                        logger.info("Added {} VPC quota alerts", quotaAlerts.size());
                    }

                    // Process network quota alerts (IGW, NAT, EIP)
                    List<DashboardData.ServiceQuotaInfo> networkQuotas = networkQuotasFuture.join();
                    if (networkQuotas != null && !networkQuotas.isEmpty()) {
                        List<AlertDto> networkAlerts = networkQuotas.stream()
                                .map(q -> createQuotaAlert(q, q.getServiceName(), counter))
                                .collect(Collectors.toList());
                        alerts.addAll(networkAlerts);
                        logger.info("Added {} network quota alerts", networkAlerts.size());
                    }

                    // Process anomaly alerts
                    List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                    if (anomalies != null && !anomalies.isEmpty()) {
                        List<AlertDto> anomalyAlerts = anomalies.stream()
                                .map(a -> {
                                    String uniqueId = a.getAnomalyId() != null && !a.getAnomalyId().isEmpty()
                                            ? a.getAnomalyId()
                                            : String.format("anomaly-%s-%d", a.getService(), counter.getAndIncrement());

                                    return new AlertDto(
                                            uniqueId,
                                            a.getService(),
                                            "Cost Anomaly Detected",
                                            String.format("Unexpected spend of $%.2f", a.getUnexpectedSpend()),
                                            "CRITICAL",
                                            a.getUnexpectedSpend(),
                                            0,
                                            "ANOMALY",
                                            "Global"
                                    );
                                })
                                .collect(Collectors.toList());
                        alerts.addAll(anomalyAlerts);
                        logger.info("Added {} anomaly alerts", anomalyAlerts.size());
                    }

                    // Process security alerts
                    List<AlertDto> securityAlerts = securityFuture.join();
                    if (securityAlerts != null && !securityAlerts.isEmpty()) {
                        alerts.addAll(securityAlerts);
                        logger.info("Added {} security alerts", securityAlerts.size());
                    }

                    // Send HTML email digest
                    if (email != null && !email.isEmpty() && !alerts.isEmpty()) {
                        try {
                            String htmlBody = alertEmailBuilder.buildAlertDigestEmail(alerts, accountName, accountId);
                            emailService.sendHtmlEmail(
                                    email,
                                    "[XamOps] Alert Digest - " + alerts.size() + " alerts require attention",
                                    htmlBody
                            );
                            logger.info("Sent alert digest email to {}", email);
                        } catch (Exception e) {
                            logger.error("Failed to send alert digest email: {}", e.getMessage());
                        }
                    }

                    logger.info("Returning {} total alerts for account {}", alerts.size(), accountId);
                    return alerts;
                }).exceptionally(ex -> {
                    logger.error("Error fetching alerts for account {}: {}", accountId, ex.getMessage(), ex);
                    return Collections.emptyList();
                });
    }

    /**
     * Create an AlertDto from a ServiceQuotaInfo.
     */
    private AlertDto createQuotaAlert(DashboardData.ServiceQuotaInfo q, String serviceName, AtomicInteger counter) {
        String uniqueId = String.format("quota-%s-%s-%d",
                q.getQuotaName().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase(),
                q.getRegionId(),
                counter.getAndIncrement());

        double utilization = q.getLimit() > 0 ? (q.getUsage() / q.getLimit() * 100) : 0;

        return new AlertDto(
                uniqueId,
                serviceName,
                q.getQuotaName(),
                String.format("Current usage: %.0f / %.0f (%.1f%%)", q.getUsage(), q.getLimit(), utilization),
                determineQuotaStatus(q.getUsage(), q.getLimit()),
                q.getUsage(),
                q.getLimit(),
                "QUOTA",
                q.getRegionId()
        );
    }

    /**
     * Determine quota status based on usage percentage
     */
    private String determineQuotaStatus(double usage, double limit) {
        if (limit == 0) return "UNKNOWN";
        double percentage = (usage / limit) * 100;
        if (percentage >= 90) return "CRITICAL";
        if (percentage >= 75) return "WARNING";
        return "OK";
    }

    // ================= Enhanced Alert Fetching with Error Handling =================

    /**
     * Evict all alert-related caches for an account.
     * Called during force refresh to ensure fresh data.
     */
    private void evictAlertsCaches(String accountId) {
        logger.info("🗑️ Evicting alert caches for account {}", accountId);
        redisCache.evict("vpcQuotaAlerts-" + accountId);
        redisCache.evict("networkQuotaAlerts-" + accountId);
        redisCache.evict("costAnomalies-" + accountId);
        redisCache.evict("aws:securityhub:" + accountId);
        logger.info("✅ Cache eviction complete for account {}", accountId);
    }

    /**
     * Wrap a CompletableFuture with a timeout using shared scheduler.
     * If the future doesn't complete within the specified time, returns a failure result.
     * Uses TIMEOUT_SCHEDULER (shared, static) to prevent thread exhaustion.
     */
    private <T> CompletableFuture<ServiceResult<T>> withTimeout(
            CompletableFuture<T> future,
            long timeout,
            TimeUnit unit,
            String serviceName) {

        CompletableFuture<ServiceResult<T>> timeoutFuture = new CompletableFuture<>();

        // Schedule timeout using SHARED executor (prevents creating new thread pool each time)
        ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(() -> {
            if (!timeoutFuture.isDone()) {
                String error = String.format("Timeout after %d %s", timeout, unit.toString().toLowerCase());
                logger.warn("⏱️ Timeout for {} service", serviceName);
                timeoutFuture.complete(ServiceResult.failure(error, getEmptyResultForService(serviceName)));
            }
        }, timeout, unit);

        // Complete when original future completes
        future.handle((result, ex) -> {
            timeoutTask.cancel(false);
            // DO NOT call scheduler.shutdown() - it's a shared executor that must stay alive!

            if (ex != null) {
                String error = extractErrorMessage(ex);
                logger.error("❌ Error in {} service: {}", serviceName, error);
                timeoutFuture.complete(ServiceResult.failure(error, getEmptyResultForService(serviceName)));
            } else {
                timeoutFuture.complete(ServiceResult.success(result));
            }
            return null;
        });

        return timeoutFuture;
    }

    /**
     * Safely get result from a future without blocking indefinitely.
     */
    @SuppressWarnings("unchecked")
    private <T> ServiceResult<T> getResultSafely(
            CompletableFuture<ServiceResult<T>> future,
            String serviceName,
            List<AlertsResponse.AlertError> errors) {
        try {
            ServiceResult<T> result = future.getNow(
                ServiceResult.failure("Not completed", (T) getEmptyResultForService(serviceName))
            );

            if (!result.isSuccess() || result.isPartial()) {
                for (String errMsg : result.getErrors()) {
                    errors.add(new AlertsResponse.AlertError(
                        serviceName,
                        categorizeError(errMsg),
                        errMsg,
                        result.isPartial(),
                        LocalDateTime.now()
                    ));
                }
            }

            return result;
        } catch (Exception e) {
            String errorMsg = "Failed to retrieve result: " + e.getMessage();
            errors.add(new AlertsResponse.AlertError(
                serviceName,
                "UNKNOWN",
                errorMsg,
                false,
                LocalDateTime.now()
            ));
            return ServiceResult.failure(errorMsg, (T) getEmptyResultForService(serviceName));
        }
    }

    /**
     * Get empty result based on service type.
     */
    @SuppressWarnings("unchecked")
    private <T> T getEmptyResultForService(String serviceName) {
        return (T) Collections.emptyList();
    }

    /**
     * Categorize error based on error message.
     */
    private String categorizeError(String errorMessage) {
        if (errorMessage == null) return "UNKNOWN";

        String lowerMsg = errorMessage.toLowerCase();
        if (lowerMsg.contains("timeout") || lowerMsg.contains("timed out")) {
            return "TIMEOUT";
        } else if (lowerMsg.contains("access denied") || lowerMsg.contains("unauthorized") || lowerMsg.contains("permission")) {
            return "PERMISSION_DENIED";
        } else if (lowerMsg.contains("throttl") || lowerMsg.contains("rate")) {
            return "RATE_LIMIT";
        } else if (lowerMsg.contains("not enabled") || lowerMsg.contains("not found") || lowerMsg.contains("not subscribed")) {
            return "SERVICE_NOT_ENABLED";
        } else {
            return "API_ERROR";
        }
    }

    /**
     * Extract clean error message from throwable.
     */
    private String extractErrorMessage(Throwable ex) {
        if (ex instanceof CompletionException && ex.getCause() != null) {
            return ex.getCause().getMessage();
        }
        return ex.getMessage();
    }

    /**
     * Enhanced version of getAlerts that returns detailed error information.
     * Supports partial success where some services can fail while others succeed.
     */
    @Async("awsTaskExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<AlertsResponse> getAlertsWithDetails(String accountId, boolean forceRefresh) {
        long startTime = System.currentTimeMillis();
        logger.info("🔍 Fetching alerts for account: {} (forceRefresh: {})", accountId, forceRefresh);

        // PHASE 1: Cache Eviction (if force refresh)
        if (forceRefresh) {
            evictAlertsCaches(accountId);
        }

        // PHASE 2: Check database cache (unless force refresh)
        if (!forceRefresh) {
            String dbKey = "alerts-" + accountId;
            AlertsResponse cachedResponse = (AlertsResponse) dashboardDataService.getAlertsSnapshot(
                dbKey, new TypeReference<AlertsResponse>() {});
            if (cachedResponse != null && cachedResponse.getAlerts() != null && !cachedResponse.getAlerts().isEmpty()) {
                logger.info("✅ Serving {} alerts from database cache", cachedResponse.getAlerts().size());
                if (cachedResponse.getMetadata() != null) {
                    cachedResponse.getMetadata().setFromCache(true);
                }
                return CompletableFuture.completedFuture(cachedResponse);
            }
        }

        // PHASE 3: Fetch fresh data with error handling
        CloudAccount account = getAccount(accountId);

        // Eagerly fetch email before async
        String clientEmail = null;
        try {
            if (account.getClient() != null) {
                clientEmail = account.getClient().getEmail();
            }
        } catch (Exception e) {
            logger.warn("Could not fetch client email for account {}: {}", accountId, e.getMessage());
        }

        final String email = clientEmail;
        final String accountName = account.getAccountName() != null ? account.getAccountName() : accountId;

        // Create timeout-wrapped futures (120 seconds for quota services with sequential region processing)
        CompletableFuture<ServiceResult<List<DashboardData.ServiceQuotaInfo>>> vpcFuture =
            withTimeout(getVpcQuotaAlerts(accountId, forceRefresh), 120, TimeUnit.SECONDS, "VPC_QUOTA");

        CompletableFuture<ServiceResult<List<DashboardData.ServiceQuotaInfo>>> networkFuture =
            withTimeout(getNetworkQuotaAlerts(accountId, forceRefresh), 120, TimeUnit.SECONDS, "NETWORK_QUOTA");

        CompletableFuture<ServiceResult<List<DashboardData.CostAnomaly>>> costFuture =
            withTimeout(finOpsService.getCostAnomalies(account, forceRefresh), 60, TimeUnit.SECONDS, "COST_ANOMALY");

        CompletableFuture<ServiceResult<List<AlertDto>>> securityFuture =
            withTimeout(securityHubService.getSecurityAlerts(accountId, forceRefresh), 60, TimeUnit.SECONDS, "SECURITY");

        // PHASE 4: Combine results (allow partial success)
        return CompletableFuture.allOf(vpcFuture, networkFuture, costFuture, securityFuture)
            .handle((v, throwable) -> {
                List<AlertDto> alerts = new ArrayList<>();
                List<AlertsResponse.AlertError> errors = new ArrayList<>();
                AtomicInteger counter = new AtomicInteger(1);

                // Process VPC quotas
                ServiceResult<List<DashboardData.ServiceQuotaInfo>> vpcResult =
                    getResultSafely(vpcFuture, "VPC_QUOTA", errors);
                if (vpcResult.isSuccess() && vpcResult.getData().isPresent()) {
                    List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcResult.getData().get();
                    if (vpcQuotas != null && !vpcQuotas.isEmpty()) {
                        alerts.addAll(vpcQuotas.stream()
                            .map(q -> createQuotaAlert(q, "VPC", counter))
                            .collect(Collectors.toList()));
                        logger.info("✅ Added {} VPC quota alerts", vpcQuotas.size());
                    }
                }

                // Process Network quotas
                ServiceResult<List<DashboardData.ServiceQuotaInfo>> networkResult =
                    getResultSafely(networkFuture, "NETWORK_QUOTA", errors);
                if (networkResult.isSuccess() && networkResult.getData().isPresent()) {
                    List<DashboardData.ServiceQuotaInfo> networkQuotas = networkResult.getData().get();
                    if (networkQuotas != null && !networkQuotas.isEmpty()) {
                        alerts.addAll(networkQuotas.stream()
                            .map(q -> createQuotaAlert(q, q.getServiceName(), counter))
                            .collect(Collectors.toList()));
                        logger.info("✅ Added {} network quota alerts", networkQuotas.size());
                    }
                }

                // Process Cost anomalies
                ServiceResult<List<DashboardData.CostAnomaly>> costResult =
                    getResultSafely(costFuture, "COST_ANOMALY", errors);
                if (costResult.isSuccess() && costResult.getData().isPresent()) {
                    List<DashboardData.CostAnomaly> anomalies = costResult.getData().get();
                    if (anomalies != null && !anomalies.isEmpty()) {
                        alerts.addAll(anomalies.stream()
                            .map(a -> {
                                String uniqueId = a.getAnomalyId() != null && !a.getAnomalyId().isEmpty()
                                    ? a.getAnomalyId()
                                    : String.format("anomaly-%s-%d", a.getService(), counter.getAndIncrement());

                                return new AlertDto(
                                    uniqueId,
                                    a.getService(),
                                    "Cost Anomaly Detected",
                                    String.format("Unexpected spend of $%.2f", a.getUnexpectedSpend()),
                                    "CRITICAL",
                                    a.getUnexpectedSpend(),
                                    0,
                                    "ANOMALY",
                                    "Global"
                                );
                            })
                            .collect(Collectors.toList()));
                        logger.info("✅ Added {} anomaly alerts", anomalies.size());
                    }
                }

                // Process Security alerts
                ServiceResult<List<AlertDto>> securityResult =
                    getResultSafely(securityFuture, "SECURITY", errors);
                if (securityResult.isSuccess() && securityResult.getData().isPresent()) {
                    List<AlertDto> securityAlerts = securityResult.getData().get();
                    if (securityAlerts != null && !securityAlerts.isEmpty()) {
                        alerts.addAll(securityAlerts);
                        logger.info("✅ Added {} security alerts", securityAlerts.size());
                    }
                }

                // Build metadata
                long duration = System.currentTimeMillis() - startTime;
                AlertsResponse.AlertsMetadata metadata = new AlertsResponse.AlertsMetadata(
                    alerts.size(),
                    (int) alerts.stream().filter(a -> "VPC".equals(a.getService())).count(),
                    (int) alerts.stream().filter(a -> Arrays.asList("Internet Gateway", "NAT Gateway", "Elastic IP").contains(a.getService())).count(),
                    (int) alerts.stream().filter(a -> "ANOMALY".equals(a.getType())).count(),
                    (int) alerts.stream().filter(a -> "SECURITY".equals(a.getType())).count(),
                    duration,
                    LocalDateTime.now(),
                    false
                );

                AlertsResponse response = new AlertsResponse(alerts, errors, metadata);

                // Send HTML email digest (only if no critical errors and alerts present)
                if (email != null && !email.isEmpty() && !alerts.isEmpty()) {
                    try {
                        String htmlBody = alertEmailBuilder.buildAlertDigestEmail(alerts, accountName, accountId);
                        emailService.sendHtmlEmail(
                            email,
                            "[XamOps] Alert Digest - " + alerts.size() + " alerts require attention",
                            htmlBody
                        );
                        logger.info("📧 Sent alert digest email to {}", email);
                    } catch (Exception e) {
                        logger.error("Failed to send alert digest email: {}", e.getMessage());
                    }
                }

                // Save to database cache if we have some data (even with errors)
                if (!alerts.isEmpty() || errors.isEmpty()) {
                    try {
                        String dbKey = "alerts-" + accountId;
                        dashboardDataService.saveAlertsSnapshot(dbKey, response);
                        logger.debug("💾 Saved alerts snapshot to database cache");
                    } catch (Exception e) {
                        logger.warn("Failed to save alerts snapshot: {}", e.getMessage());
                    }
                }

                logger.info("✅ Returning {} alerts with {} errors for account {} ({}ms)",
                    alerts.size(), errors.size(), accountId, duration);

                return response;
            });
    }
}
