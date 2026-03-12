package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.GkeDashboardCache;
import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.repository.GkeDashboardCacheRepository;
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
 * Service for syncing and caching GKE dashboard data.
 *
 * Mirrors EksDashboardSyncService for GCP/GKE clusters.
 *
 * Flow:
 * 1. On app startup: re-syncs all clusters already in the DB cache (warm-up).
 * 2. Every day at 3:15 AM: refreshes all known clusters from GCP API + Prometheus.
 * 3. On page load (cache hit): returns data instantly from DB.
 * 4. On page load (cache miss): fetches fresh data, saves to DB, returns result.
 */
@Service
public class GkeDashboardSyncService {

    private static final Logger logger = LoggerFactory.getLogger(GkeDashboardSyncService.class);

    private final GkeDashboardCacheRepository cacheRepository;
    private final GkeDashboardService gkeDashboardService;
    private final ObjectMapper objectMapper;

    // Self-injection so @Transactional(REQUIRES_NEW) on saveDashboardToCache() is honoured
    @Autowired
    @Lazy
    private GkeDashboardSyncService self;

    public GkeDashboardSyncService(GkeDashboardCacheRepository cacheRepository,
                                   GkeDashboardService gkeDashboardService) {
        this.cacheRepository = cacheRepository;
        this.gkeDashboardService = gkeDashboardService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Re-syncs all GKE clusters already recorded in the DB cache.
     * Runs in a background thread 5 s after app startup to avoid blocking boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        logger.info("Starting GKE dashboard cache warming on application startup...");
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                syncAllClusters();
                logger.info("GKE dashboard cache warming completed successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("GKE cache warming interrupted");
            } catch (Exception e) {
                logger.error("Error during startup GKE cache warming", e);
            }
        }, "gke-cache-warmup").start();
    }

    /**
     * Nightly job at 3:15 AM — refreshes all cached GKE cluster dashboards.
     */
    @Scheduled(cron = "0 15 3 * * ?")
    public void scheduledSyncAllClusters() {
        logger.info("Starting scheduled GKE dashboard sync (daily 3:15 AM)...");
        syncAllClusters();
        logger.info("Scheduled GKE dashboard sync completed");
    }

    /**
     * Iterates all entries in the gke_dashboard_cache table and re-fetches each.
     * The cache table naturally records every (clusterName, projectId, location) tuple
     * that was ever loaded, so no separate cluster discovery is needed.
     */
    public void syncAllClusters() {
        List<GkeDashboardCache> entries = cacheRepository.findAll();
        logger.info("GKE sync: found {} cached cluster(s) to refresh", entries.size());

        int success = 0, fail = 0;
        for (GkeDashboardCache entry : entries) {
            try {
                syncClusterDashboard(entry.getProjectId(), entry.getClusterName(), entry.getLocation());
                success++;
            } catch (Exception e) {
                fail++;
                logger.error("Failed to sync GKE cluster: {}/{} — {}",
                        entry.getProjectId(), entry.getClusterName(), e.getMessage());
            }
        }
        logger.info("GKE sync complete: {} succeeded, {} failed", success, fail);
    }

    /**
     * Fetches a fresh dashboard for the given cluster and saves it to the DB cache.
     *
     * @return the freshly fetched dashboard, or null on error
     */
    public GkeDashboardDto syncClusterDashboard(String projectId, String clusterName, String location) {
        logger.info("Syncing GKE dashboard for cluster {} in project {} ({})", clusterName, projectId, location);
        try {
            GkeDashboardDto dashboard = gkeDashboardService.getDashboard(projectId, clusterName, location);
            if (dashboard != null) {
                boolean saved = self.saveDashboardToCache(clusterName, projectId, location, dashboard);
                if (saved) {
                    logger.info("GKE dashboard synced and saved for cluster: {}", clusterName);
                } else {
                    logger.warn("GKE dashboard synced but DB save failed for cluster: {}", clusterName);
                }
            }
            return dashboard;
        } catch (Exception e) {
            logger.error("Error fetching GKE dashboard for cluster: {}/{}", projectId, clusterName, e);
            throw new RuntimeException("Failed to fetch GKE dashboard for cluster: " + clusterName, e);
        }
    }

    /**
     * Persists dashboard JSON to the DB cache.
     * Runs in its own transaction (REQUIRES_NEW) so failures never affect the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveDashboardToCache(String clusterName, String projectId, String location,
                                        GkeDashboardDto dashboard) {
        try {
            String json = objectMapper.writeValueAsString(dashboard);

            Optional<GkeDashboardCache> existing =
                    cacheRepository.findByClusterNameAndProjectIdAndLocation(clusterName, projectId, location);

            GkeDashboardCache entry;
            if (existing.isPresent()) {
                entry = existing.get();
                entry.setDashboardData(json);
                entry.setLastSyncedAt(LocalDateTime.now());
            } else {
                entry = new GkeDashboardCache(clusterName, projectId, location, json);
            }

            cacheRepository.save(entry);
            logger.info("Saved GKE dashboard cache for cluster: {} ({} bytes)", clusterName, json.length());
            return true;

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize GKE dashboard for cluster: {}", clusterName, e);
            return false;
        } catch (Exception e) {
            logger.error("Failed to save GKE dashboard to DB for cluster: {}. Error: {}",
                    clusterName, e.getMessage());
            return false;
        }
    }

    /**
     * Reads a cached dashboard from the DB.
     * Returns null if no entry exists or on any DB error.
     */
    @Transactional(readOnly = true)
    public GkeDashboardDto getCachedDashboard(String clusterName, String projectId, String location) {
        try {
            Optional<GkeDashboardCache> cached =
                    cacheRepository.findByClusterNameAndProjectIdAndLocation(clusterName, projectId, location);

            if (cached.isEmpty()) {
                logger.info("No cached GKE dashboard for cluster: {} (project={}, location={})",
                        clusterName, projectId, location);
                return null;
            }

            GkeDashboardDto dashboard =
                    objectMapper.readValue(cached.get().getDashboardData(), GkeDashboardDto.class);
            logger.info("Returning cached GKE dashboard for cluster: {} (synced: {})",
                    clusterName, cached.get().getLastSyncedAt());
            return dashboard;

        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize GKE dashboard cache for cluster: {}", clusterName, e);
            return null;
        } catch (Exception e) {
            logger.warn("DB read failed for GKE cluster: {} — {}", clusterName, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public LocalDateTime getLastSyncedAt(String clusterName, String projectId, String location) {
        try {
            return cacheRepository
                    .findByClusterNameAndProjectIdAndLocation(clusterName, projectId, location)
                    .map(GkeDashboardCache::getLastSyncedAt)
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to get last sync time for GKE cluster: {}", clusterName);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public boolean hasCachedDashboard(String clusterName, String projectId, String location) {
        try {
            return cacheRepository.existsByClusterNameAndProjectIdAndLocation(clusterName, projectId, location);
        } catch (Exception e) {
            return false;
        }
    }
}
