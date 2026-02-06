package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EksDashboardCache;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for syncing and caching EKS dashboard data.
 *
 * Features:
 * - Cache warmup on application startup (instant page loads from day 1)
 * - Scheduled sync at 4 AM daily for all clusters (fresh data every morning)
 * - On-demand sync when user clicks "Sync Data" button (live data on demand)
 * - Database-backed caching for instant page loads
 *
 * Flow:
 * 1. On app startup: warmCacheOnStartup() populates cache for all clusters
 * 2. Every 6 hours: scheduledSyncAllClusters() refreshes cache with fresh data
 * 3. On page load: Returns cached data from database (instant)
 * 4. On "Sync Data" click: Returns cached data immediately, fetches fresh data in background
 */
@Service
public class EksDashboardSyncService {

    private static final Logger logger = LoggerFactory.getLogger(EksDashboardSyncService.class);

    private final EksDashboardCacheRepository cacheRepository;
    private final KubernetesClusterRepository clusterRepository;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final EksDashboardService eksDashboardService;
    private final ObjectMapper objectMapper;

    // Self-injection to allow @Transactional to work on internal calls
    // Without this, calling saveDashboardToCache() from within this class bypasses the proxy
    @Autowired
    @Lazy
    private EksDashboardSyncService self;

    public EksDashboardSyncService(
            EksDashboardCacheRepository cacheRepository,
            KubernetesClusterRepository clusterRepository,
            KubernetesClusterConfigRepository clusterConfigRepository,
            CloudAccountRepository cloudAccountRepository,
            EksDashboardService eksDashboardService) {
        this.cacheRepository = cacheRepository;
        this.clusterRepository = clusterRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.eksDashboardService = eksDashboardService;

        // Configure ObjectMapper for proper serialization
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Warm up cache on application startup.
     * This ensures cached data is available immediately when users visit EKS dashboards.
     * Runs in a separate thread to avoid blocking application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        logger.info("🚀 Starting EKS dashboard cache warming on application startup...");

        // Run in separate thread to avoid blocking application startup
        new Thread(() -> {
            try {
                // Wait a few seconds for other services to be ready
                Thread.sleep(5000);
                syncAllClusters();
                logger.info("✅ EKS dashboard cache warming completed successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("⚠️ EKS cache warming interrupted");
            } catch (Exception e) {
                logger.error("❌ Error during startup EKS cache warming", e);
            }
        }, "eks-cache-warmup").start();
    }

    /**
     * Scheduled job to sync all EKS clusters every 6 hours.
     * Cron: 0 0 *\/6 * * ? = At minute 0 past every 6th hour (00:00, 06:00, 12:00, 18:00)
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void scheduledSyncAllClusters() {
        logger.info("🕐 Starting scheduled EKS dashboard sync (every 6 hours)...");
        syncAllClusters();
        logger.info("✅ Scheduled EKS dashboard sync completed");
    }

    /**
     * Sync dashboard data for all registered EKS clusters.
     * Uses KubernetesClusterConfig to get cluster details including region.
     * Multi-tenant aware - syncs clusters across all accounts.
     */
    @Transactional
    public void syncAllClusters() {
        logger.info("📦 Syncing all EKS clusters (multi-tenant)...");

        // Get all cluster configs (these have kubeconfig + region)
        List<KubernetesClusterConfig> allConfigs = clusterConfigRepository.findAll();
        logger.info("Found {} cluster configs to sync", allConfigs.size());

        int successCount = 0;
        int failCount = 0;

        for (KubernetesClusterConfig config : allConfigs) {
            try {
                // Skip if no kubeconfig (can't fetch K8s data)
                if (config.getKubeconfigYaml() == null || config.getKubeconfigYaml().isEmpty()) {
                    logger.warn("⚠️ Cluster {} has no kubeconfig, skipping", config.getClusterName());
                    continue;
                }

                // Get CloudAccount by ID
                Optional<CloudAccount> accountOpt = cloudAccountRepository.findById(config.getCloudAccountId());
                if (accountOpt.isEmpty()) {
                    logger.warn("⚠️ CloudAccount not found for ID: {}, skipping cluster: {}",
                            config.getCloudAccountId(), config.getClusterName());
                    continue;
                }

                CloudAccount account = accountOpt.get();
                String region = config.getRegion(); // Use region from config

                logger.info("🔄 Syncing cluster: {} (Account: {}, Region: {})",
                        config.getClusterName(), account.getAwsAccountId(), region);

                syncClusterDashboard(account, config.getClusterName(), region);
                successCount++;

            } catch (Exception e) {
                failCount++;
                logger.error("❌ Failed to sync cluster: {}", config.getClusterName(), e);
            }
        }

        logger.info("📊 Sync complete: {} succeeded, {} failed", successCount, failCount);
    }

    /**
     * Sync dashboard data for a single cluster and save to database.
     * Called by scheduled job or manual sync button.
     *
     * NOTE: Database save failures are non-critical - the dashboard data is still
     * returned successfully. Redis cache in EksDashboardService provides backup caching.
     * NOTE: No @Transactional here - saveDashboardToCache handles its own transaction.
     */
    public EksCompleteDashboardDto syncClusterDashboard(CloudAccount account, String clusterName, String region) {
        logger.info("🔄 Syncing dashboard for cluster: {} (Account: {}, Region: {})",
                clusterName, account.getAwsAccountId(), region);

        try {
            // Fetch fresh data from Prometheus/K8s (forceRefresh=true bypasses Redis cache)
            EksCompleteDashboardDto dashboard = eksDashboardService
                    .getCompleteDashboard(account, clusterName, region, "1.33", "ACTIVE", true)
                    .get(); // Block and wait for result

            if (dashboard != null) {
                // Save to database using self-injection to ensure @Transactional works
                // Direct call would bypass Spring's proxy and transaction won't be created
                logger.info("💾 Saving dashboard to cache with params: clusterName='{}', accountId='{}', region='{}'",
                        clusterName, account.getAwsAccountId(), region);
                boolean saved = self.saveDashboardToCache(clusterName, account.getAwsAccountId(), region, dashboard);
                if (saved) {
                    logger.info("✅ Dashboard synced and saved to database for cluster: {}", clusterName);
                } else {
                    logger.warn("⚠️ Dashboard synced but database save failed for cluster: {} (Redis cache still active)",
                            clusterName);
                }
            }

            return dashboard;

        } catch (Exception e) {
            logger.error("❌ Error fetching dashboard for cluster: {}", clusterName, e);
            throw new RuntimeException("Failed to fetch dashboard for cluster: " + clusterName, e);
        }
    }

    /**
     * Async background sync for a single cluster.
     * Called when user clicks "Sync Data" - runs in background while UI shows cached data.
     * Returns CompletableFuture that completes when sync is done.
     */
    @Async
    public CompletableFuture<EksCompleteDashboardDto> syncClusterDashboardAsync(CloudAccount account, String clusterName, String region) {
        logger.info("🔄 Starting background sync for cluster: {}", clusterName);
        try {
            EksCompleteDashboardDto dashboard = syncClusterDashboard(account, clusterName, region);
            logger.info("✅ Background sync completed for cluster: {}", clusterName);
            return CompletableFuture.completedFuture(dashboard);
        } catch (Exception e) {
            logger.error("❌ Background sync failed for cluster: {}", clusterName, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Save dashboard data to database cache.
     * Uses REQUIRES_NEW to run in its own transaction - failures won't affect calling code.
     * Returns true if save succeeded, false otherwise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveDashboardToCache(String clusterName, String accountId, String region,
            EksCompleteDashboardDto dashboard) {
        try {
            String jsonData = objectMapper.writeValueAsString(dashboard);

            // Debug logging
            logger.info("🐛 DEBUG: jsonData length = {}, first 200 chars = {}",
                    jsonData.length(),
                    jsonData.length() > 200 ? jsonData.substring(0, 200) : jsonData);

            Optional<EksDashboardCache> existingCache = cacheRepository
                    .findByClusterNameAndAccountIdAndRegion(clusterName, accountId, region);

            EksDashboardCache cache;
            if (existingCache.isPresent()) {
                // Update existing cache
                cache = existingCache.get();
                cache.setDashboardData(jsonData);
                cache.setLastSyncedAt(LocalDateTime.now());
            } else {
                // Create new cache entry
                cache = new EksDashboardCache(clusterName, accountId, region, jsonData);
            }

            // Debug: Log what we're about to save
            logger.info("🐛 DEBUG: About to save cache with dashboardData length = {}, first 200 chars = {}",
                    cache.getDashboardData().length(),
                    cache.getDashboardData().length() > 200 ? cache.getDashboardData().substring(0, 200) : cache.getDashboardData());

            cacheRepository.save(cache);

            // Debug: Verify what was saved by reading it back
            Optional<EksDashboardCache> savedCache = cacheRepository
                    .findByClusterNameAndAccountIdAndRegion(clusterName, accountId, region);
            if (savedCache.isPresent()) {
                String savedData = savedCache.get().getDashboardData();
                logger.info("🐛 DEBUG: After save, read back dashboardData length = {}, first 200 chars = {}",
                        savedData.length(),
                        savedData.length() > 200 ? savedData.substring(0, 200) : savedData);
            }

            logger.info("💾 Saved dashboard cache for cluster: {} (size: {} bytes)",
                    clusterName, jsonData.length());
            return true;

        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to serialize dashboard data for cluster: {}", clusterName, e);
            return false;
        } catch (Exception e) {
            logger.error("❌ Failed to save dashboard to database for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
            logger.debug("Database save error details:", e);
            return false;
        }
    }

    /**
     * Get cached dashboard data from database.
     * Returns null if no cache exists or on database errors.
     *
     * NOTE: Database errors are handled gracefully - returns null to allow
     * fallback to fresh data fetch (which uses Redis cache as backup).
     */
    @Transactional(readOnly = true)
    public EksCompleteDashboardDto getCachedDashboard(String clusterName, String accountId, String region) {
        try {
            // Debug: Log the lookup parameters
            logger.info("🔍 Looking up cache for: clusterName='{}', accountId='{}', region='{}'",
                    clusterName, accountId, region);

            Optional<EksDashboardCache> cached = cacheRepository
                    .findByClusterNameAndAccountIdAndRegion(clusterName, accountId, region);

            if (cached.isEmpty()) {
                logger.info("📭 No cached dashboard found for cluster: {} (accountId={}, region={})",
                        clusterName, accountId, region);
                // Debug: List all cached entries to see what's available
                var allCaches = cacheRepository.findAll();
                logger.info("📦 Available caches in DB: {}", allCaches.size());
                allCaches.forEach(c -> logger.info("   - cluster='{}', accountId='{}', region='{}'",
                        c.getClusterName(), c.getAccountId(), c.getRegion()));
                return null;
            }

            EksDashboardCache cache = cached.get();
            EksCompleteDashboardDto dashboard = objectMapper.readValue(
                    cache.getDashboardData(), EksCompleteDashboardDto.class);

            logger.info("📦 Returning cached dashboard for cluster: {} (synced: {})",
                    clusterName, cache.getLastSyncedAt());

            return dashboard;

        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to deserialize cached dashboard for cluster: {}", clusterName, e);
            return null;
        } catch (Exception e) {
            // Handle database permission errors or connection issues gracefully
            logger.warn("⚠️ Database cache read failed for cluster: {} (will fetch fresh data). Error: {}",
                    clusterName, e.getMessage());
            logger.debug("Database read error details:", e);
            return null;
        }
    }

    /**
     * Get the last sync timestamp for a cluster.
     * Returns null on database errors.
     */
    @Transactional(readOnly = true)
    public LocalDateTime getLastSyncedAt(String clusterName, String accountId, String region) {
        try {
            return cacheRepository
                    .findByClusterNameAndAccountIdAndRegion(clusterName, accountId, region)
                    .map(EksDashboardCache::getLastSyncedAt)
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to get last sync time from database for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
            return null;
        }
    }

    /**
     * Check if cache exists for a cluster.
     * Returns false on database errors.
     */
    @Transactional(readOnly = true)
    public boolean hasCachedDashboard(String clusterName, String accountId, String region) {
        try {
            return cacheRepository.existsByClusterNameAndAccountIdAndRegion(clusterName, accountId, region);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to check cache existence in database for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
            return false;
        }
    }

    /**
     * Delete cached dashboard for a cluster.
     * Fails silently on database errors.
     */
    @Transactional
    public void deleteCachedDashboard(String clusterName, String accountId, String region) {
        try {
            cacheRepository.deleteByClusterNameAndAccountIdAndRegion(clusterName, accountId, region);
            logger.info("🗑️ Deleted cached dashboard for cluster: {}", clusterName);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to delete cache from database for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
        }
    }
}
