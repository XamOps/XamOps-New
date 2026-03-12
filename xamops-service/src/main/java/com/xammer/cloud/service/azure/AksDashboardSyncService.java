package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AksDashboardCache;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.repository.AksDashboardCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for syncing and caching AKS dashboard data.
 *
 * Mirrors EksDashboardSyncService for Azure/AKS clusters.
 *
 * Flow:
 * 1. On app startup: re-syncs all clusters already in the DB cache (warm-up).
 * 2. Every day at 4:55 AM: refreshes all known clusters from Azure SDK + Prometheus.
 * 3. On page load (cache hit): returns data instantly from DB.
 * 4. On page load (cache miss): fetches fresh data, saves to DB, returns result.
 */
@Service
public class AksDashboardSyncService {

    private static final Logger logger = LoggerFactory.getLogger(AksDashboardSyncService.class);

    private final AksDashboardCacheRepository cacheRepository;
    private final AksDashboardService aksDashboardService;
    private final ObjectMapper objectMapper;

    // Self-injection so @Transactional(REQUIRES_NEW) on saveDashboardToCache() is honoured
    @Autowired
    @Lazy
    private AksDashboardSyncService self;

    public AksDashboardSyncService(AksDashboardCacheRepository cacheRepository,
                                   AksDashboardService aksDashboardService) {
        this.cacheRepository = cacheRepository;
        this.aksDashboardService = aksDashboardService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Re-syncs all AKS clusters already recorded in the DB cache.
     * Runs in a background thread 7 s after app startup (staggered from GKE at 5 s).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        logger.info("Starting AKS dashboard cache warming on application startup...");
        new Thread(() -> {
            try {
                Thread.sleep(7000);
                syncAllClusters();
                logger.info("AKS dashboard cache warming completed successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("AKS cache warming interrupted");
            } catch (Exception e) {
                logger.error("Error during startup AKS cache warming", e);
            }
        }, "aks-cache-warmup").start();
    }

    /**
     * Nightly job at 4:55 AM — refreshes all cached AKS cluster dashboards.
     */
    @Scheduled(cron = "0 55 4 * * ?")
    public void scheduledSyncAllClusters() {
        logger.info("Starting scheduled AKS dashboard sync (daily 4:55 AM)...");
        syncAllClusters();
        logger.info("Scheduled AKS dashboard sync completed");
    }

    /**
     * Iterates all entries in the aks_dashboard_cache table and re-fetches each.
     */
    public void syncAllClusters() {
        List<AksDashboardCache> entries = cacheRepository.findAll();
        logger.info("AKS sync: found {} cached cluster(s) to refresh", entries.size());

        int success = 0, fail = 0;
        for (AksDashboardCache entry : entries) {
            try {
                syncClusterDashboard(entry.getSubscriptionId(), entry.getClusterName(), entry.getResourceGroup());
                success++;
            } catch (Exception e) {
                fail++;
                logger.error("Failed to sync AKS cluster: {}/{} — {}",
                        entry.getSubscriptionId(), entry.getClusterName(), e.getMessage());
            }
        }
        logger.info("AKS sync complete: {} succeeded, {} failed", success, fail);
    }

    /**
     * Fetches a fresh dashboard for the given cluster and saves it to the DB cache.
     *
     * @return the freshly fetched dashboard, or null on error
     */
    public AksDashboardDto syncClusterDashboard(String subscriptionId, String clusterName, String resourceGroup) {
        logger.info("Syncing AKS dashboard for cluster {} in resource group {} (subscription {})",
                clusterName, resourceGroup, subscriptionId);
        try {
            AksDashboardDto dashboard = aksDashboardService.getDashboard(subscriptionId, clusterName, resourceGroup);
            if (dashboard != null) {
                boolean saved = self.saveDashboardToCache(clusterName, subscriptionId, resourceGroup, dashboard);
                if (saved) {
                    logger.info("AKS dashboard synced and saved for cluster: {}", clusterName);
                } else {
                    logger.warn("AKS dashboard synced but DB save failed for cluster: {}", clusterName);
                }
            }
            return dashboard;
        } catch (Exception e) {
            logger.error("Error fetching AKS dashboard for cluster: {}/{}", subscriptionId, clusterName, e);
            throw new RuntimeException("Failed to fetch AKS dashboard for cluster: " + clusterName, e);
        }
    }

    /**
     * Persists dashboard JSON to the DB cache.
     * Runs in its own transaction (REQUIRES_NEW) so failures never affect the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveDashboardToCache(String clusterName, String subscriptionId, String resourceGroup,
                                        AksDashboardDto dashboard) {
        try {
            String json = objectMapper.writeValueAsString(dashboard);

            Optional<AksDashboardCache> existing =
                    cacheRepository.findByClusterNameAndSubscriptionIdAndResourceGroup(
                            clusterName, subscriptionId, resourceGroup);

            AksDashboardCache entry;
            if (existing.isPresent()) {
                entry = existing.get();
                entry.setDashboardData(json);
                entry.setLastSyncedAt(LocalDateTime.now());
            } else {
                entry = new AksDashboardCache(clusterName, subscriptionId, resourceGroup, json);
            }

            cacheRepository.save(entry);
            logger.info("Saved AKS dashboard cache for cluster: {} ({} bytes)", clusterName, json.length());
            return true;

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize AKS dashboard for cluster: {}", clusterName, e);
            return false;
        } catch (Exception e) {
            logger.error("Failed to save AKS dashboard to DB for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
            return false;
        }
    }

    /**
     * Reads a cached dashboard from the DB.
     * Returns null if no entry exists or on any DB error.
     */
    @Transactional(readOnly = true)
    public AksDashboardDto getCachedDashboard(String clusterName, String subscriptionId, String resourceGroup) {
        try {
            Optional<AksDashboardCache> cached =
                    cacheRepository.findByClusterNameAndSubscriptionIdAndResourceGroup(
                            clusterName, subscriptionId, resourceGroup);

            if (cached.isEmpty()) {
                logger.info("No cached AKS dashboard for cluster: {} (subscription={}, resourceGroup={})",
                        clusterName, subscriptionId, resourceGroup);
                return null;
            }

            AksDashboardDto dashboard =
                    objectMapper.readValue(cached.get().getDashboardData(), AksDashboardDto.class);
            logger.info("Returning cached AKS dashboard for cluster: {} (synced: {})",
                    clusterName, cached.get().getLastSyncedAt());
            return dashboard;

        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize AKS dashboard cache for cluster: {}", clusterName, e);
            return null;
        } catch (Exception e) {
            logger.warn("DB read failed for AKS cluster: {} — {}", clusterName, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public LocalDateTime getLastSyncedAt(String clusterName, String subscriptionId, String resourceGroup) {
        try {
            return cacheRepository
                    .findByClusterNameAndSubscriptionIdAndResourceGroup(clusterName, subscriptionId, resourceGroup)
                    .map(AksDashboardCache::getLastSyncedAt)
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to get last sync time for AKS cluster: {}", clusterName);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public boolean hasCachedDashboard(String clusterName, String subscriptionId, String resourceGroup) {
        try {
            return cacheRepository.existsByClusterNameAndSubscriptionIdAndResourceGroup(
                    clusterName, subscriptionId, resourceGroup);
        } catch (Exception e) {
            return false;
        }
    }
}
