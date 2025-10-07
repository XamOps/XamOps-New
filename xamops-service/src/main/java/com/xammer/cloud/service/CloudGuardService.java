package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);

    // Set a threshold for quota alerts (e.g., 75% utilization)
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
        // MODIFIED: Handle list of accounts to prevent crash
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0); // Return the first one found
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
            if (activeRegions == null) {
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
                                            .filter(q -> q.quotaCode().equals("L-F678F1CE")) // VPCs per Region quota code
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
                    redisCache.put(cacheKey, allQuotaInfo);
                    return allQuotaInfo;
                } catch (Exception e) {
                    logger.error("Could not fetch VPC quota alerts for account {}.", accountId, e);
                    return Collections.emptyList();
                }
            });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<AlertDto>> getAlerts(String accountId, boolean forceRefresh) {
        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> quotasFuture = getVpcQuotaAlerts(accountId, forceRefresh);
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account, forceRefresh);

        return quotasFuture.thenCombine(anomaliesFuture, (quotas, anomalies) -> {
            List<AlertDto> alerts = new ArrayList<>();
            List<AlertDto> quotaAlerts = quotas.stream()
                    .map(q -> new AlertDto(
                            q.getQuotaName() + "-" + q.getRegionId(),
                            "VPC",
                            q.getQuotaName(),
                            "Limit: " + q.getLimit(),
                            q.getStatus(),
                            q.getUsage(),
                            q.getLimit(),
                            "QUOTA",
                            q.getRegionId()
                    ))
                    .collect(Collectors.toList());
            if (anomalies == null) {
                anomalies = Collections.emptyList();
            }

            List<AlertDto> anomalyAlerts = anomalies.stream()
                    .map(a -> new AlertDto(
                            a.getAnomalyId(),
                            a.getService(),
                            "Cost Anomaly Detected",
                            "Unexpected spend of $" + String.format("%.2f", a.getUnexpectedSpend()),
                            "CRITICAL",
                            a.getUnexpectedSpend(),
                            0,
                            "ANOMALY",
                            "Global"
                    ))
                    .collect(Collectors.toList());
            alerts.addAll(quotaAlerts);
            alerts.addAll(anomalyAlerts);

            // Send email for each alert
            for (AlertDto alert : alerts) {
                String email = account.getClient().getEmail();
                if (email != null && !email.isEmpty()) {
                    emailService.sendEmail(email, "New Alert: " + alert.getName(), alert.getDescription());
                }
            }

            return alerts;
        });
    }
}