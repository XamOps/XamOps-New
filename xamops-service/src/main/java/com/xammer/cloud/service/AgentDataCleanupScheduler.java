package com.xammer.cloud.service;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.service.spot.SpotInterruptionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes agent-pushed data so {@code agent_data_cache} and {@code agent_event} stay bounded.
 *
 * {@link AgentIngestService#cleanupStaleData(int)} already existed but nothing ever called
 * it, so both tables grew for the lifetime of the deployment. They live on the same Postgres
 * instance whose connection pool has already been a production bottleneck, and unbounded
 * table growth makes every query against them progressively slower — so this is housekeeping
 * that has to actually run, not just be available.
 *
 * Scheduled at 04:20, in a gap between the nightly dashboard-sync jobs (3:00–4:55) and the
 * forecast jobs (05:30). Prod runs scheduling on a single thread
 * ({@code spring.task.scheduling.pool.size=1}), so overlapping a heavy job would delay it.
 */
@Component
public class AgentDataCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentDataCleanupScheduler.class);

    /**
     * Days of agent cache/event history to keep.
     *
     * This is a short-lived ingest cache read by the dashboards, not a historical record, so
     * it does not need to match the OpenObserve retention window. Overridable in case a
     * deployment wants to trade disk for a longer debugging trail.
     */
    @Value("${xamops.agent.data.retention-days:14}")
    private int retentionDays;

    /** BP-13 (design §2.1, F-26): onboarding audit events are kept for a year's audit by default. */
    @Value("${xamops.k8s.onboarding.event-retention-days:400}")
    private int onboardingEventRetentionDays;

    private final AgentIngestService agentIngestService;
    private final SchedulingEnvironmentConfig envConfig;
    private final SpotInterruptionTracker spotInterruptionTracker;

    /** Optional so tests that build the scheduler without the onboarding layer keep working. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xammer.cloud.repository.ClusterOnboardingEventRepository onboardingEventRepository;

    public AgentDataCleanupScheduler(AgentIngestService agentIngestService,
                                     SchedulingEnvironmentConfig envConfig,
                                     SpotInterruptionTracker spotInterruptionTracker) {
        this.agentIngestService = agentIngestService;
        this.envConfig = envConfig;
        this.spotInterruptionTracker = spotInterruptionTracker;
    }

    /** Delete agent cache rows and events older than the retention window. Daily 04:20. */
    @Scheduled(cron = "0 20 4 * * *")
    public void cleanupStaleAgentData() {
        if (!envConfig.isSchedulingEnabled()) {
            log.info("⚠️ Scheduling disabled — skipping agent data cleanup");
            return;
        }

        long start = System.currentTimeMillis();
        log.info("--- [04:20] Starting agent data cleanup (retention={}d) ---", retentionDays);
        try {
            int deleted = agentIngestService.cleanupStaleData(retentionDays);
            log.info("✅ Agent data cleanup done: {} rows deleted in {} ms",
                    deleted, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // Never let a failed cleanup break the scheduler thread for other jobs.
            log.error("❌ Agent data cleanup failed", e);
        }
        // Spot interruption history has its own 365-day retention. Its own try/catch, so it can
        // never affect the cleanup above, and no new cron slot on prod's single scheduling thread.
        try {
            spotInterruptionTracker.purgeExpired();
        } catch (Exception e) {
            log.error("❌ Spot interruption history purge failed", e);
        }
        // BP-13 (F-26): prune onboarding audit events past the long (default 400-day) retention.
        purgeOldOnboardingEvents();
    }

    /**
     * BP-13 (design §2.1, F-26): delete {@code cluster_onboarding_event} rows older than the
     * retention window. Its own try/catch so it never breaks the scheduler thread. The
     * server-owned {@code autoscaling-policy} agent-cache row is excluded from the cache retention
     * above by {@link AgentIngestService#cleanupStaleData(int)} (BP-19); this step only prunes the
     * audit log.
     */
    void purgeOldOnboardingEvents() {
        if (onboardingEventRepository == null) {
            return;
        }
        try {
            java.time.Instant cutoff = java.time.Instant.now()
                    .minus(java.time.Duration.ofDays(onboardingEventRetentionDays));
            int deleted = onboardingEventRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("✅ Pruned {} onboarding event(s) older than {}d", deleted, onboardingEventRetentionDays);
            }
        } catch (Exception e) {
            log.error("❌ Onboarding event retention purge failed", e);
        }
    }
}
