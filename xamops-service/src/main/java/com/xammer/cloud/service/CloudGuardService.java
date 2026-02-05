package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);
    private static final double ALERT_THRESHOLD_PERCENTAGE = 75.0;

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

    @Async("awsTaskExecutor")
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

            return vpcResourcesFuture.thenApplyAsync(vpcResources -> {
                try {
                    Map<String, Long> vpcCountsByRegion = vpcResources.stream()
                            .collect(Collectors.groupingBy(ResourceDto::getRegion, Collectors.counting()));

                    List<CompletableFuture<DashboardData.ServiceQuotaInfo>> futures = activeRegions.stream()
                            .map(region -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    logger.info("Fetching service quota info for account {} in region {}...", accountId, region.getRegionId());
                                    ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, region.getRegionId());
                                    ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                            .serviceCode("vpc")
                                            .build();
                                    ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);
                                    Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                            .filter(q -> q.quotaCode().equals("L-F678F1CE"))
                                            .findFirst();

                                    return quota.map(serviceQuota -> {
                                        double currentCount = vpcCountsByRegion.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                        double usage = currentCount;
                                        double utilization = (serviceQuota.value() > 0) ? (usage / serviceQuota.value()) * 100.0 : 0.0;

                                        if (utilization > ALERT_THRESHOLD_PERCENTAGE || usage > 0) {
                                            return new DashboardData.ServiceQuotaInfo(
                                                    "VPC",
                                                    serviceQuota.quotaName(),
                                                    serviceQuota.value(),
                                                    usage,
                                                    region.getRegionId(),
                                                    "Active"
                                            );
                                        }
                                        return null;
                                    }).orElse(null);
                                } catch (Exception e) {
                                    logger.error("Failed to get quota info for service vpc in region {}.", region.getRegionId(), e);
                                    return null;
                                }
                            }))
                            .collect(Collectors.toList());

                    List<DashboardData.ServiceQuotaInfo> allQuotaInfo = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

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
     */
    @Async("awsTaskExecutor")
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

        return activeRegionsFuture.thenApplyAsync(activeRegions -> {
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
}
