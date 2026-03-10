package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);
    private static final double ALERT_THRESHOLD_PERCENTAGE = 75.0;

    // Network Quota Codes (AWS Service Quotas)
    private static final String QUOTA_VPC = "L-F678F1CE"; // VPCs per Region
    private static final String QUOTA_INTERNET_GATEWAY = "L-A4707A72"; // Internet Gateways per Region
    private static final String QUOTA_NAT_GATEWAY = "L-FE5A380F"; // NAT Gateways per AZ
    private static final String QUOTA_ELASTIC_IP = "L-0263D0A3"; // Elastic IPs per Region

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
            AlertEmailBuilder alertEmailBuilder) {
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
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountId);
        if (accountOpt.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }

        CloudAccount account = accountOpt.get();

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

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getVpcQuotaAlerts(String accountId,
            boolean forceRefresh) {
        String cacheKey = "vpcQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                logger.info("--- LOADING FROM REDIS CACHE (TypeReference): {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccount(account, forceRefresh);

        return activeRegionsFuture.thenCompose(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            CompletableFuture<List<ResourceDto>> vpcResourcesFuture = cloudListService.fetchVpcsForCloudlist(account,
                    activeRegions);

            // Use thenApply (not Async) to avoid creating extra thread
            return vpcResourcesFuture.thenApply(vpcResources -> {
                try {
                    Map<String, Long> vpcCountsByRegion = vpcResources.stream()
                            .collect(Collectors.groupingBy(ResourceDto::getRegion, Collectors.counting()));

                    // Process regions sequentially (we're already in async context)
                    // This prevents thread exhaustion from CompletableFuture.supplyAsync() creating
                    // unbounded threads
                    List<DashboardData.ServiceQuotaInfo> allQuotaInfo = new ArrayList<>();

                    for (DashboardData.RegionStatus region : activeRegions) {
                        try {
                            logger.debug("Fetching VPC quota for account {} in region {}", accountId,
                                    region.getRegionId());
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account,
                                    region.getRegionId());

                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode("vpc")
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);

                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals("L-F678F1CE"))
                                    .findFirst();

                            if (quota.isPresent()) {
                                ServiceQuota serviceQuota = quota.get();
                                double currentCount = vpcCountsByRegion.getOrDefault(region.getRegionId(), 0L)
                                        .doubleValue();
                                double usage = currentCount;
                                double utilization = (serviceQuota.value() > 0) ? (usage / serviceQuota.value()) * 100.0
                                        : 0.0;

                                if (utilization > ALERT_THRESHOLD_PERCENTAGE || usage > 0) {
                                    allQuotaInfo.add(new DashboardData.ServiceQuotaInfo(
                                            "VPC",
                                            serviceQuota.quotaName(),
                                            serviceQuota.value(),
                                            usage,
                                            region.getRegionId(),
                                            "Active"));
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get VPC quota for region {}: {}", region.getRegionId(),
                                    e.getMessage());
                        }
                    }

                    logger.info("Successfully fetched {} VPC quota alerts for account {}.", allQuotaInfo.size(),
                            accountId);
                    redisCache.put(cacheKey, allQuotaInfo, 60);
                    return allQuotaInfo;
                } catch (Exception e) {
                    logger.error("Could not fetch VPC quota alerts for account {}.", accountId, e);
                    return Collections.emptyList();
                }
            });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getNetworkQuotaAlerts(String accountId,
            boolean forceRefresh) {
        String cacheKey = "networkQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                logger.info("--- LOADING NETWORK QUOTA ALERTS FROM REDIS CACHE: {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccount(account, forceRefresh);

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
                    logger.warn("Failed to check network quotas in region {}: {}", region.getRegionId(),
                            e.getMessage());
                }
            }

            logger.info("Successfully fetched {} network quota alerts for account {}.", allQuotaInfo.size(), accountId);
            redisCache.put(cacheKey, allQuotaInfo, 60);
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
     * Check a specific quota and add to the list if usage is above threshold or
     * non-zero.
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
                            "Active"));
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to check quota {} in region {}: {}", quotaCode, regionId, e.getMessage());
        }
    }

    @Async("awsTaskExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<AlertsResponseDto> getAlerts(String accountId, boolean forceRefresh) {
        long startTime = System.currentTimeMillis();
        logger.info("🔍 Fetching alerts for account: {}", accountId);

        final String dbKey = "alerts-" + accountId;

        if (!forceRefresh) {
            // 1. Redis first — fast, no DB connection consumed
            Optional<AlertsResponseDto> redisResponse = redisCache.get(dbKey, AlertsResponseDto.class);
            if (redisResponse.isPresent() && redisResponse.get().getAlerts() != null
                    && !redisResponse.get().getAlerts().isEmpty()) {
                logger.info("✅ Serving {} alerts from Redis cache for account {}",
                        redisResponse.get().getAlerts().size(), accountId);
                return CompletableFuture.completedFuture(redisResponse.get());
            }

            // 2. DB fallback — only when Redis is cold (after restart/deployment)
            AlertsResponseDto cachedResponse = (AlertsResponseDto) dashboardDataService.getAlertsSnapshot(
                    dbKey, new TypeReference<AlertsResponseDto>() {
                    });

            if (cachedResponse != null && cachedResponse.getAlerts() != null && !cachedResponse.getAlerts().isEmpty()) {
                logger.info("✅ Serving {} alerts from DB — repopulating Redis for account {}",
                        cachedResponse.getAlerts().size(), accountId);

                if (cachedResponse.getMetadata() == null) {
                    cachedResponse.setMetadata(AlertsMetadataDto.builder()
                            .totalAlerts(cachedResponse.getAlerts().size())
                            .timestamp(Instant.now().toString())
                            .fromCache(true)
                            .build());
                }
                redisCache.put(dbKey, cachedResponse, 60); // re-warm Redis so next request skips DB
                return CompletableFuture.completedFuture(cachedResponse);
            }
        }

        // 2. Fetch fresh data from AWS API
        logger.info("Fetching fresh alerts from AWS API for account {}", accountId);
        CloudAccount account = getAccount(accountId);

        // CloudGuard is AWS-only — return empty response for GCP/Azure accounts
        if (account.getAwsAccountId() == null || account.getAwsAccountId().isEmpty()) {
            logger.info("CloudGuard: Skipping alerts for non-AWS account {}", accountId);
            return CompletableFuture.completedFuture(AlertsResponseDto.builder()
                    .alerts(Collections.emptyList())
                    .errors(Collections.emptyList())
                    .metadata(AlertsMetadataDto.builder()
                            .totalAlerts(0)
                            .timestamp(Instant.now().toString())
                            .fromCache(false)
                            .build())
                    .build());
        }

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
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotasFuture = getVpcQuotaAlerts(accountId,
                forceRefresh);
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> networkQuotasFuture = getNetworkQuotaAlerts(accountId,
                forceRefresh);
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account,
                forceRefresh);
        CompletableFuture<List<AlertDto>> securityFuture = securityHubService.getSecurityAlerts(accountId,
                forceRefresh);

        return CompletableFuture.allOf(vpcQuotasFuture, networkQuotasFuture, anomaliesFuture, securityFuture)
                .thenApply(v -> {
                    List<AlertDto> alerts = new ArrayList<>();
                    List<AlertErrorDto> errors = new ArrayList<>();
                    AtomicInteger counter = new AtomicInteger(1);

                    int vpcCount = 0;
                    int networkCount = 0;
                    int anomalyCount = 0;
                    int securityCount = 0;

                    // Process VPC quota alerts
                    try {
                        List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotasFuture.join();
                        if (vpcQuotas != null && !vpcQuotas.isEmpty()) {
                            List<AlertDto> quotaAlerts = vpcQuotas.stream()
                                    .map(q -> createQuotaAlert(q, "VPC", counter))
                                    .collect(Collectors.toList());
                            alerts.addAll(quotaAlerts);
                            vpcCount = quotaAlerts.size();
                            logger.info("Added {} VPC quota alerts", vpcCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining VPC quotas for {}: {}", accountId, e.getMessage());
                        errors.add(AlertErrorDto.builder()
                                .service("VPC")
                                .errorType("FETCH_ERROR")
                                .message(e.getMessage())
                                .timestamp(Instant.now().toString())
                                .build());
                    }

                    // Process network quota alerts (IGW, NAT, EIP)
                    try {
                        List<DashboardData.ServiceQuotaInfo> networkQuotas = networkQuotasFuture.join();
                        if (networkQuotas != null && !networkQuotas.isEmpty()) {
                            List<AlertDto> networkAlerts = networkQuotas.stream()
                                    .map(q -> createQuotaAlert(q, q.getServiceName(), counter))
                                    .collect(Collectors.toList());
                            alerts.addAll(networkAlerts);
                            networkCount = networkAlerts.size();
                            logger.info("Added {} network quota alerts", networkCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining network quotas for {}: {}", accountId, e.getMessage());
                        errors.add(AlertErrorDto.builder()
                                .service("Network")
                                .errorType("FETCH_ERROR")
                                .message(e.getMessage())
                                .timestamp(Instant.now().toString())
                                .build());
                    }

                    // Process anomaly alerts
                    try {
                        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                        if (anomalies != null && !anomalies.isEmpty()) {
                            List<AlertDto> anomalyAlerts = anomalies.stream()
                                    .map(a -> {
                                        String uniqueId = a.getAnomalyId() != null && !a.getAnomalyId().isEmpty()
                                                ? a.getAnomalyId()
                                                : String.format("anomaly-%s-%d", a.getService(),
                                                        counter.getAndIncrement());

                                        return new AlertDto(
                                                uniqueId,
                                                a.getService(),
                                                "Cost Anomaly Detected",
                                                String.format("Unexpected spend of $%.2f", a.getUnexpectedSpend()),
                                                "CRITICAL",
                                                a.getUnexpectedSpend(),
                                                0,
                                                "ANOMALY",
                                                "Global");
                                    })
                                    .collect(Collectors.toList());
                            alerts.addAll(anomalyAlerts);
                            anomalyCount = anomalyAlerts.size();
                            logger.info("Added {} anomaly alerts", anomalyCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining cost anomalies for {}: {}", accountId, e.getMessage());
                        errors.add(AlertErrorDto.builder()
                                .service("FinOps")
                                .errorType("FETCH_ERROR")
                                .message(e.getMessage())
                                .timestamp(Instant.now().toString())
                                .build());
                    }

                    // Process security alerts
                    try {
                        List<AlertDto> securityAlerts = securityFuture.join();
                        if (securityAlerts != null && !securityAlerts.isEmpty()) {
                            alerts.addAll(securityAlerts);
                            securityCount = securityAlerts.size();
                            logger.info("Added {} security alerts", securityCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining security hub findings for {}: {}", accountId, e.getMessage());
                        errors.add(AlertErrorDto.builder()
                                .service("SecurityHub")
                                .errorType("FETCH_ERROR")
                                .message(e.getMessage())
                                .timestamp(Instant.now().toString())
                                .build());
                    }

                    // Send HTML email digest
                    if (email != null && !email.isEmpty() && !alerts.isEmpty()) {
                        try {
                            String htmlBody = alertEmailBuilder.buildAlertDigestEmail(alerts, accountName, accountId);
                            emailService.sendHtmlEmail(
                                    email,
                                    "[XamOps] Alert Digest - " + alerts.size() + " alerts require attention",
                                    htmlBody);
                            logger.info("Sent alert digest email to {}", email);
                        } catch (Exception e) {
                            logger.error("Failed to send alert digest email: {}", e.getMessage());
                        }
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    AlertsMetadataDto metadata = AlertsMetadataDto.builder()
                            .totalAlerts(alerts.size())
                            .vpcQuotaCount(vpcCount)
                            .networkQuotaCount(networkCount)
                            .costAnomalyCount(anomalyCount)
                            .securityAlertCount(securityCount)
                            .fetchDurationMs(duration)
                            .timestamp(Instant.now().toString())
                            .fromCache(false)
                            .build();

                    logger.info("Returning {} total alerts for account {} in {}ms", alerts.size(), accountId, duration);
                    AlertsResponseDto response = AlertsResponseDto.builder()
                            .alerts(alerts)
                            .errors(errors)
                            .metadata(metadata)
                            .build();
                    redisCache.put(dbKey, response, 60); // save to Redis after fresh AWS fetch
                    return response;
                }).exceptionally(ex -> {
                    logger.error("Fatal error fetching alerts for account {}: {}", accountId, ex.getMessage(), ex);
                    return AlertsResponseDto.builder()
                            .alerts(Collections.emptyList())
                            .errors(List.of(AlertErrorDto.builder()
                                    .service("SYSTEM")
                                    .errorType("FATAL_ERROR")
                                    .message(ex.getMessage())
                                    .timestamp(Instant.now().toString())
                                    .build()))
                            .metadata(AlertsMetadataDto.builder()
                                    .timestamp(Instant.now().toString())
                                    .build())
                            .build();
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
                q.getRegionId());
    }

    /**
     * Determine quota status based on usage percentage
     */
    private String determineQuotaStatus(double usage, double limit) {
        if (limit == 0)
            return "UNKNOWN";
        double percentage = (usage / limit) * 100;
        if (percentage >= 90)
            return "CRITICAL";
        if (percentage >= 75)
            return "WARNING";
        return "OK";
    }
}
