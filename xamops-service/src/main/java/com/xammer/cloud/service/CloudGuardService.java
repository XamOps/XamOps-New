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

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CloudListService cloudListService;
    private final FinOpsService finOpsService;
    private final EmailService emailService;

    @Autowired
    public CloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            RedisCacheService redisCache,
            @Lazy CloudListService cloudListService,
            FinOpsService finOpsService,
            EmailService emailService
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cloudListService = cloudListService;
        this.finOpsService = finOpsService;
        this.emailService = emailService;
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

    @Async("awsTaskExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<List<AlertDto>> getAlerts(String accountId, boolean forceRefresh) {
        logger.info("🔍 Fetching alerts for account: {}", accountId);

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

        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> quotasFuture = getVpcQuotaAlerts(accountId, forceRefresh);
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account, forceRefresh);

        return quotasFuture.thenCombine(anomaliesFuture, (quotas, anomalies) -> {
            List<AlertDto> alerts = new ArrayList<>();
            AtomicInteger counter = new AtomicInteger(1);

            // Process quota alerts
            if (quotas != null && !quotas.isEmpty()) {
                List<AlertDto> quotaAlerts = quotas.stream()
                        .map(q -> {
                            // Generate unique ID
                            String uniqueId = String.format("quota-%s-%s-%d",
                                    q.getQuotaName().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase(),
                                    q.getRegionId(),
                                    counter.getAndIncrement());

                            return new AlertDto(
                                    uniqueId,
                                    "VPC",
                                    q.getQuotaName(),
                                    String.format("Current usage: %.0f / %.0f (%.1f%%)",
                                            q.getUsage(), q.getLimit(), (q.getUsage() / q.getLimit() * 100)),
                                    determineQuotaStatus(q.getUsage(), q.getLimit()),
                                    q.getUsage(),
                                    q.getLimit(),
                                    "QUOTA",
                                    q.getRegionId()
                            );
                        })
                        .collect(Collectors.toList());
                alerts.addAll(quotaAlerts);
                logger.info("✅ Added {} quota alerts", quotaAlerts.size());
            }

            // Process anomaly alerts
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
                logger.info("✅ Added {} anomaly alerts", anomalyAlerts.size());
            }

            // Send emails
            if (email != null && !email.isEmpty() && !alerts.isEmpty()) {
                for (AlertDto alert : alerts) {
                    try {
                        emailService.sendEmail(email, "New Alert: " + alert.getName(), alert.getDescription());
                    } catch (Exception e) {
                        logger.error("Failed to send alert email: {}", e.getMessage());
                    }
                }
            }

            logger.info("✅ Returning {} total alerts for account {}", alerts.size(), accountId);
            return alerts;

        }).exceptionally(ex -> {
            logger.error("❌ Error fetching alerts for account {}: {}", accountId, ex.getMessage(), ex);
            return Collections.emptyList();
        });
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
