package com.xammer.cloud.service.azure;

import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class AzureCostService {

    private static final Logger logger = LoggerFactory.getLogger(AzureCostService.class);

    private final RedisCacheService redisCache;
    private final CloudAccountRepository cloudAccountRepository;

    // Cache key prefix defined in AzureBillingDataIngestionService
    private static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";

    @Autowired
    private com.xammer.cloud.service.azure.AzureCostManagementService costManagementService;

    @Autowired
    public AzureCostService(RedisCacheService redisCache, CloudAccountRepository cloudAccountRepository) {
        this.redisCache = redisCache;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Retrieves the total month-to-date cost for an Azure subscription from the
     * cache.
     */
    public CompletableFuture<Double> getTotalMonthToDateCost(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
            String summaryCacheKey = "azure:billing-summary:" + subscriptionId;
            try {
                Optional<List<com.xammer.cloud.dto.azure.AzureDashboardData.BillingSummary>> billingSummaryOpt = redisCache
                        .get(summaryCacheKey, new com.fasterxml.jackson.core.type.TypeReference<>() {
                        });

                if (billingSummaryOpt.isPresent()) {
                    double totalSpend = billingSummaryOpt.get().stream()
                            .mapToDouble(com.xammer.cloud.dto.azure.AzureDashboardData.BillingSummary::getCost)
                            .sum();
                    logger.info("✅ Calculated Azure MTD cost for {}: ${}", subscriptionId, totalSpend);
                    return totalSpend;
                }
            } catch (Exception e) {
                logger.error("Error fetching Azure MTD cost for {}: {}", subscriptionId, e.getMessage());
            }
            return 0.0;
        });
    }

    /**
     * Retrieves the historical cost for an Azure account from the Redis cache.
     * This data is populated by AzureBillingDataIngestionService.
     */
    @Async("awsTaskExecutor") // Using the same executor as the main CostService
    public CompletableFuture<HistoricalCostDto> getHistoricalCost(String subscriptionId, int days,
            boolean forceRefresh) {

        logger.info("📊 Fetching Azure historical cost for account: {}", subscriptionId);

        // We don't use the generic "historicalCost-" cache key here,
        // we use the specific key that the ingestion service uses.
        String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;

        // Note: forceRefresh is not implemented here because ingestion is handled by a
        // separate scheduled job.
        // We will just read whatever is in the cache.

        Optional<AzureDashboardData.CostHistory> costHistoryOpt = redisCache.get(historyCacheKey,
                AzureDashboardData.CostHistory.class);

        if (costHistoryOpt.isPresent()) {
            logger.info("✅ Found Azure historical cost in cache for {}", subscriptionId);
            AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();

            // Convert Azure-specific DTO to the generic HistoricalCostDto for the
            // forecasting service
            HistoricalCostDto historicalCost = new HistoricalCostDto(costHistory.getLabels(), costHistory.getCosts());

            return CompletableFuture.completedFuture(historicalCost);
        } else {
            logger.warn("❌ No Azure historical cost found in cache for {}. Forecast will be empty.", subscriptionId);
            return CompletableFuture
                    .completedFuture(new HistoricalCostDto(Collections.emptyList(), Collections.emptyList()));
        }
    }
}