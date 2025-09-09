package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
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
    private final DatabaseCacheService dbCache;
    private final CloudListService cloudListService;

    @Autowired
    public CloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            DatabaseCacheService dbCache,
            @Lazy CloudListService cloudListService
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.dbCache = dbCache;
        this.cloudListService = cloudListService;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getVpcQuotaAlerts(String accountId, boolean forceRefresh) {
        String cacheKey = "vpcQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                logger.info("--- LOADING FROM DATABASE CACHE (TypeReference): {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);

        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account, forceRefresh);

        return activeRegionsFuture.thenCompose(activeRegions -> {
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
                                        double utilization = (serviceQuota.value() > 0) ? (currentCount / serviceQuota.value()) * 100.0 : 0.0;

                                        // Only create an alert if usage is above the threshold or there is at least one VPC.
                                        if (utilization > ALERT_THRESHOLD_PERCENTAGE || currentCount > 0) {
                                            return new DashboardData.ServiceQuotaInfo(
                                                    "VPC",
                                                    serviceQuota.quotaName(),
                                                    serviceQuota.value(),
                                                    currentCount,
                                                    region.getRegionId()
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
                    dbCache.put(cacheKey, allQuotaInfo);
                    return allQuotaInfo;
                } catch (Exception e) {
                    logger.error("Could not fetch VPC quota alerts for account {}.", accountId, e);
                    return Collections.emptyList();
                }
            });
        });
    }
}