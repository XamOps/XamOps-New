package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
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
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache key prefix defined in AzureBillingDataIngestionService
    private static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";

    @Autowired
    private com.xammer.cloud.service.azure.AzureCostManagementService costManagementService;

    @Autowired
    public AzureCostService(RedisCacheService redisCache, CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository) {
        this.redisCache = redisCache;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Retrieves the total month-to-date cost for an Azure subscription.
     * Cache strategy: Redis (24h TTL, set by ingestion job) → DB snapshot → 0.0.
     */
    public CompletableFuture<Double> getTotalMonthToDateCost(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
            String summaryCacheKey = "azure:billing-summary:" + subscriptionId;
            String dbKey = AzureBillingDataIngestionService.AZURE_BILLING_SUMMARY_CACHE_PREFIX.equals("azure:billing-summary:")
                    ? "AZURE_COST_BILLING_SUMMARY::" + subscriptionId
                    : "AZURE_COST_BILLING_SUMMARY::" + subscriptionId;

            // 1. Try Redis
            try {
                Optional<List<AzureDashboardData.BillingSummary>> billingSummaryOpt = redisCache
                        .get(summaryCacheKey, new TypeReference<List<AzureDashboardData.BillingSummary>>() {});
                if (billingSummaryOpt.isPresent()) {
                    double totalSpend = billingSummaryOpt.get().stream()
                            .mapToDouble(AzureDashboardData.BillingSummary::getCost)
                            .sum();
                    logger.info("✅ Calculated Azure MTD cost for {} from Redis: ${}", subscriptionId, totalSpend);
                    return totalSpend;
                }
            } catch (Exception e) {
                logger.warn("Redis miss for Azure MTD cost {}: {}", subscriptionId, e.getMessage());
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<AzureDashboardData.BillingSummary> summary = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(),
                            new TypeReference<List<AzureDashboardData.BillingSummary>>() {});
                    double totalSpend = summary.stream()
                            .mapToDouble(AzureDashboardData.BillingSummary::getCost)
                            .sum();
                    logger.info("✅ Calculated Azure MTD cost for {} from DB: ${} — re-warming Redis", subscriptionId, totalSpend);
                    redisCache.put(summaryCacheKey, summary, 60 * 24);
                    return totalSpend;
                }
            } catch (Exception e) {
                logger.warn("DB snapshot load failed for Azure MTD cost {}: {}", subscriptionId, e.getMessage());
            }

            // Zero here is a fallback, not a measurement, and this method's return type cannot
            // express "unknown". Callers that can represent the difference should ask
            // isCostReportingConfigured first; the log says which case this is so a $0.00
            // dashboard can be diagnosed without guessing.
            logger.warn("❌ No Azure MTD cost data found for {} in Redis or DB — returning 0.0 as a "
                    + "fallback. Cost reporting configured for this subscription: {}",
                    subscriptionId, isCostReportingConfigured(subscriptionId));
            return 0.0;
        });
    }

    /**
     * Whether this subscription has an Azure cost export wired up at all.
     *
     * <p>
     * Onboarding deliberately saves the account even when {@code setupCostExports} fails, so that
     * a bad billing parameter does not cost the customer their credentials. The consequence is
     * that a subscription can be fully "connected" with no billing source, and every cost figure
     * then reads {@code 0.00} — indistinguishable from a subscription that genuinely spent
     * nothing. The billing container is only recorded once the export exists, so its absence is
     * the signal that no spend will ever arrive.
     *
     * <p>
     * Exposed so callers that <em>can</em> represent an unknown — the dashboard payload, with its
     * data warnings — say "not configured" instead of showing a confident zero.
     */
    public boolean isCostReportingConfigured(String subscriptionId) {
        try {
            return cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                    .map(a -> a.getAzureBillingContainer() != null && !a.getAzureBillingContainer().isBlank())
                    .orElse(false);
        } catch (Exception e) {
            logger.debug("Could not determine cost-export configuration for {}: {}",
                    subscriptionId, e.getMessage());
            return false;
        }
    }

    /**
     * Retrieves the historical cost for an Azure account from the Redis cache.
     * This data is populated by AzureBillingDataIngestionService.
     */
    @Async("awsTaskExecutor") // Using the same executor as the main CostService
    public CompletableFuture<HistoricalCostDto> getHistoricalCost(String subscriptionId, int days,
            boolean forceRefresh) {

        logger.info("📊 Fetching Azure historical cost for account: {}", subscriptionId);

        String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
        String dbKey = "AZURE_COST_HISTORY::" + subscriptionId;

        // 1. Try Redis
        Optional<AzureDashboardData.CostHistory> costHistoryOpt = redisCache.get(historyCacheKey,
                AzureDashboardData.CostHistory.class);

        if (costHistoryOpt.isPresent()) {
            logger.info("✅ Found Azure historical cost in Redis for {}", subscriptionId);
            AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();
            return CompletableFuture.completedFuture(
                    new HistoricalCostDto(costHistory.getLabels(), costHistory.getCosts()));
        }

        // 2. Try DB snapshot
        try {
            Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
            if (dbSnapshot.isPresent()) {
                AzureDashboardData.CostHistory costHistory = objectMapper.readValue(
                        dbSnapshot.get().getJsonData(), AzureDashboardData.CostHistory.class);
                logger.info("✅ Found Azure historical cost in DB for {} — re-warming Redis", subscriptionId);
                redisCache.put(historyCacheKey, costHistory, 60 * 24);
                return CompletableFuture.completedFuture(
                        new HistoricalCostDto(costHistory.getLabels(), costHistory.getCosts()));
            }
        } catch (Exception e) {
            logger.warn("DB snapshot load failed for Azure cost history {}: {}", subscriptionId, e.getMessage());
        }

        logger.warn("❌ No Azure historical cost found for {}. Forecast will be empty.", subscriptionId);
        return CompletableFuture
                .completedFuture(new HistoricalCostDto(Collections.emptyList(), Collections.emptyList()));
    }
}