package com.xammer.billops.service;

import com.xammer.billops.dto.azure.AzureBillingDashboardDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
public class AzureCostService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostService.class);
    private final RedisCacheService redisCache;

    public AzureCostService(RedisCacheService redisCache) {
        this.redisCache = redisCache;
    }

    /**
     * Retrieves the Billing Dashboard DTO from Redis.
     * The data is populated by AzureBillingDataIngestionService via scheduled CSV
     * exports.
     */
    public AzureBillingDashboardDto getBillingDashboard(String subscriptionId, Integer year, Integer month) {
        String cacheKey;

        // Determine which cache key to use
        if (year != null && month != null) {
            cacheKey = AzureBillingDataIngestionService.AZURE_DASHBOARD_CACHE_PREFIX + subscriptionId + ":" + year + ":"
                    + month;
        } else {
            // Default: try to get the current month's key
            LocalDate now = LocalDate.now();
            cacheKey = AzureBillingDataIngestionService.AZURE_DASHBOARD_CACHE_PREFIX + subscriptionId + ":"
                    + now.getYear() + ":" + now.getMonthValue();
        }

        log.info("Fetching Azure dashboard from cache key: {}", cacheKey);

        Optional<AzureBillingDashboardDto> cachedData = redisCache.get(cacheKey, AzureBillingDashboardDto.class);

        if (cachedData.isPresent()) {
            log.info("✅ [AZURE DEBUG] Found cached data for {}. Cost: {}", cacheKey, cachedData.get().getTotalCost());
            return cachedData.get();
        } else {
            log.warn("⚠️ [AZURE DEBUG] Cache MISS for {}. This usually means ingestion hasn't run or failed.",
                    cacheKey);
            return new AzureBillingDashboardDto();
        }
    }

    // Helper to manually trigger ingestion (e.g., from a "Refresh" button
    // controller endpoint)
    public void triggerManualIngestion(String subscriptionId) {
        // This would require fetching the CloudAccount entity first
        // implemented in BillopsController if needed.
    }
}