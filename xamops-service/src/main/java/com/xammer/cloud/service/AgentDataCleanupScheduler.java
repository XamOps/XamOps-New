package com.xammer.cloud.service;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
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

    private final AgentIngestService agentIngestService;
    private final SchedulingEnvironmentConfig envConfig;

    public AgentDataCleanupScheduler(AgentIngestService agentIngestService,
                                     SchedulingEnvironmentConfig envConfig) {
        this.agentIngestService = agentIngestService;
        this.envConfig = envConfig;
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
    }
}
