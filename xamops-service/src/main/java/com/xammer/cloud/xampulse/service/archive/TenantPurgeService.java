package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.platform.ClickHouseMutations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hot-store deletes and the retention hold. Both are bound mutations: the tenant is always a parameter and table
 * names come from a fixed list. ClickHouse runs mutations in the background, so "submitted" is the honest result.
 * The customer's archive is never touched here.
 */
@Service("xpTenantPurgeService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class TenantPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgeService.class);

    /** Every table that carries tenant_id. The landing tables are short-lived and hold no tenant column. */
    static final List<String> TABLES = List.of("xp_logs", "xp_metric_points", "xp_metrics_1m", "xp_metrics_5m", "xp_metrics_1h",
            "xp_events", "xp_log_volume_1m", "xp_pattern_map", "xp_baseline", "xp_health_1m");
    static final int HOLD_STEP_DAYS = 2;
    static final int HOLD_MAX_DAYS = 7;
    static final Duration HOLD_MIN_GAP = Duration.ofHours(24);

    private final ClickHouseMutations mutations;
    private final XpAccountConfigRepository configs;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TenantPurgeService(ClickHouseMutations mutations, XpAccountConfigRepository configs) {
        this(mutations, configs, Clock.systemUTC());
    }

    TenantPurgeService(ClickHouseMutations mutations, XpAccountConfigRepository configs, Clock clock) {
        this.mutations = mutations;
        this.configs = configs;
        this.clock = clock;
    }

    /** Submits one delete per table for this tenant; returns how many were accepted. Fails closed on the first error. */
    public int purge(long tenantId) {
        int n = 0;
        for (String table : TABLES) {
            try {
                mutations.execute("ALTER TABLE " + table + " DELETE WHERE tenant_id = ?", List.of(tenantId));
                n++;
            } catch (SQLException e) {
                log.warn("XamPulse purge failed on {} for account {}: {}", table, tenantId, e.getClass().getSimpleName());
                throw XamPulseException.storeUnavailable();
            }
        }
        return n;
    }

    /**
     * Extends the TTL of not-yet-archived rows by 2 days, at most once per 24 h and at most 7 days in total, so an
     * archive outage cannot silently expire data. Returns true when a mutation was submitted.
     */
    public boolean extendHold(XpAccountConfig cfg) {
        Instant now = clock.instant();
        if (cfg.getHoldExtendedDays() + HOLD_STEP_DAYS > HOLD_MAX_DAYS) {
            return false;
        }
        if (cfg.getHoldExtendedAt() != null && Duration.between(cfg.getHoldExtendedAt(), now).compareTo(HOLD_MIN_GAP) < 0) {
            return false;
        }
        long sinceMs = (cfg.getArchiveWatermark() == null ? now.minus(Duration.ofDays(Math.max(1, cfg.getHotDays()))) : cfg.getArchiveWatermark()).toEpochMilli();
        try {
            mutations.execute("ALTER TABLE xp_logs UPDATE retain_days = retain_days + " + HOLD_STEP_DAYS
                    + " WHERE tenant_id = ? AND ts >= fromUnixTimestamp64Milli(?) AND ts >= now() - toIntervalDay(retain_days + " + HOLD_STEP_DAYS + ")",
                    List.of(cfg.getCloudAccountId(), sinceMs));
        } catch (SQLException e) {
            log.warn("XamPulse extendHold failed for account {}: {}", cfg.getCloudAccountId(), e.getClass().getSimpleName());
            return false;
        }
        cfg.setHoldExtendedDays(cfg.getHoldExtendedDays() + HOLD_STEP_DAYS);
        cfg.setHoldExtendedAt(now);
        configs.save(cfg);
        return true;
    }
}
