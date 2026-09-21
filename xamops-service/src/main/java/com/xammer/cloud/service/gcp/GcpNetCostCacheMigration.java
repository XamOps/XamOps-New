package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Discards GCP cost caches computed before credits were netted, once.
 *
 * <p>Every GCP cost query now subtracts credits (see {@link GcpBillingSql#NET_COST_SUM}), but
 * the two cache layers are still full of the old gross figures. Without this, the result is
 * worse than either number on its own:
 *
 * <ul>
 *   <li>Redis entries live up to 24h, so a dashboard would show some sections net and others
 *       gross at the same time, and the tiles would not add up to the total.
 *   <li>The DB snapshots have no TTL and are read as authoritative, so closed months would
 *       stay gross <em>permanently</em>. The 6-month history chart composes closed months from
 *       {@code GCP_MONTHLY_TOTAL::} snapshots and queries only the current month live — so it
 *       would render five gross bars beside one net bar, which looks exactly like the customer
 *       suddenly spending less.
 * </ul>
 *
 * <p>Versioning the cache keys instead was rejected: {@code CspBillingIngestionService},
 * {@code BillOpsClientsCacheWarmingService}, {@code BillOpsAdminController} and BillOps'
 * {@code AdminDashboardService} all write or read these keys as string literals across two
 * Maven modules. Renaming the constants in one class would leave those five writing keys
 * nobody reads, which fails silently — the precise failure mode this whole change exists to
 * remove.
 *
 * <p>Everything deleted here is derived data, regenerable from the billing export. Nothing is
 * a source of truth. The history path already handles absent snapshots by falling back to a
 * full BigQuery range query, and the nightly snapshot job rebuilds the monthly rows, so the
 * gap is covered rather than visible.
 *
 * <p>Idempotent via a marker row, so it runs once per deployment rather than on every boot,
 * and never blocks startup.
 */
@Component
@Slf4j
public class GcpNetCostCacheMigration {

    /**
     * Bump the suffix if a future change alters what a stored GCP cost figure means; that is
     * what makes this re-run.
     */
    static final String MARKER_KEY = "GCP_NET_COST_MIGRATION::v1-credits-netted";

    /**
     * Redis prefixes holding GCP cost figures.
     *
     * <p>{@code gcp:currency:} and {@code gcp:billing-status:} are deliberately absent — a
     * currency code and a billing-source state are unaffected by how cost is summed, and
     * dropping them would only cost needless BigQuery calls.
     */
    private static final List<String> REDIS_PREFIXES = List.of(
            "gcp:cost-by-tag:",
            "gcp:unfiltered-mtd-spend:",
            "gcp:billing-summary:",
            "gcp:historical-costs:",
            "gcp:cost-by-region:",
            "gcp:daily-costs-forecast:",
            "gcp:cost-breakdown:",
            "gcp:cost-by-service-region:");

    /**
     * DB snapshot prefixes holding GCP cost figures. Includes the {@code gcp:*} keys that are
     * also persisted, and the {@code GCP_MONTHLY_*} snapshots that back the history chart.
     *
     * <p>Deleting {@code GCP_MONTHLY_BILLING_SVC::} matters beyond its own rows: it is the
     * sentinel {@code GcpCostDataSnapshotService.isSnapshotComplete} checks, so removing it is
     * what makes the snapshot job recompute those closed months on net cost instead of
     * considering them already done.
     */
    private static final List<String> DB_PREFIXES = List.of(
            "GCP_FINOPS_BILLING_SVC::",
            "GCP_FINOPS_COST_REGION::",
            "GCP_FINOPS_COST_LABEL::",
            "GCP_FINOPS_ANOMALIES::",
            "GCP_COST_BREAKDOWN::",
            "GCP_COST_BREAKDOWN_DATE::",
            "GCP_COST_HISTORY::",
            "GCP_COST_BY_REGION::",
            "GCP_COST_DAILY_FORECAST::",
            "GCP_COST_BY_TAG::",
            "GCP_COST_SVC_REGION::",
            "GCP_MONTHLY_TOTAL::",
            "GCP_MONTHLY_BILLING_SVC::",
            "gcp:billing-summary:",
            "gcp:unfiltered-mtd-spend:",
            "gcp:historical-costs:",
            "gcp:cost-by-region:",
            "gcp:cost-by-tag:");

    private final CachedDataRepository cachedDataRepository;
    private final RedisCacheService redisCacheService;

    /** Escape hatch so an operator can control when the flush happens. */
    @Value("${gcp.billing.net-cost-cache-migration.enabled:true}")
    private boolean enabled;

    public GcpNetCostCacheMigration(CachedDataRepository cachedDataRepository,
            RedisCacheService redisCacheService) {
        this.cachedDataRepository = cachedDataRepository;
        this.redisCacheService = redisCacheService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void flushGrossCostCachesOnce() {
        if (!enabled) {
            log.info("[GCP net-cost migration] disabled by configuration — skipping");
            return;
        }

        try {
            if (cachedDataRepository.existsById(MARKER_KEY)) {
                log.debug("[GCP net-cost migration] already applied — skipping");
                return;
            }

            log.info("[GCP net-cost migration] clearing GCP cost caches computed before "
                    + "credits were netted");

            long redisRemoved = 0L;
            for (String prefix : REDIS_PREFIXES) {
                try {
                    redisRemoved += redisCacheService.evictByPattern(prefix + "*");
                } catch (Exception e) {
                    log.warn("[GCP net-cost migration] Redis eviction failed for {}*: {}",
                            prefix, e.getMessage());
                }
            }

            int dbRemoved = 0;
            for (String prefix : DB_PREFIXES) {
                try {
                    dbRemoved += cachedDataRepository.deleteByCacheKeyPrefix(prefix);
                } catch (Exception e) {
                    log.warn("[GCP net-cost migration] DB snapshot deletion failed for {}: {}",
                            prefix, e.getMessage());
                }
            }

            markApplied();

            log.info("[GCP net-cost migration] done — {} Redis keys and {} DB snapshot rows "
                    + "dropped. GCP cost figures now net credits and will be recomputed on "
                    + "next read.", redisRemoved, dbRemoved);
        } catch (Exception e) {
            // A cache flush must never stop the application from starting. Worst case the
            // marker is not written and the flush is retried on the next boot, which is
            // harmless because it is idempotent.
            log.error("[GCP net-cost migration] failed — GCP cost figures may mix gross and "
                    + "net values until caches expire or a force refresh is run", e);
        }
    }

    /**
     * Written last, and only after the deletions, so a crash mid-flush retries rather than
     * leaving half the caches gross with the migration recorded as complete.
     */
    private void markApplied() {
        CachedData marker = new CachedData();
        marker.setCacheKey(MARKER_KEY);
        marker.setJsonData("{\"appliedAt\":\"" + LocalDateTime.now(ZoneId.of("Asia/Kolkata")) + "\"}");
        marker.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        cachedDataRepository.save(marker);
    }
}
