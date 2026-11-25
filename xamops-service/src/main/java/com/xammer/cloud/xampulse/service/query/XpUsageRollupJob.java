package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpUsageDaily;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpUsageDailyRepository;
import com.xammer.cloud.xampulse.service.setup.SetupStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Populates the Postgres {@code xp_usage_daily} table from the minute log-volume rollup so the Storage &amp; Retention
 * usage view reports real ingested bytes and a per-source breakdown instead of 0. {@code UsageMeteringService} READS
 * {@code bytes_stored}; this job WRITES it. One tenant per statement; gated on
 * {@link SchedulingEnvironmentConfig#isSchedulingEnabled()} and the usage enabled flag.
 *
 * <p>{@code bytes_stored} is a documented approximation: it is the rollup byte-sum (same as {@code bytes_in}), not the
 * true ClickHouse compressed on-disk size. A failure for one account never stops the others, and ClickHouse errors are
 * swallowed (class name only, never parameters).
 */
@Component("xpUsageRollupJob")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XpUsageRollupJob {

    private static final Logger log = LoggerFactory.getLogger(XpUsageRollupJob.class);

    /** The only signal this job writes; UsageMeteringService filters on it too. */
    static final String SIGNAL = "LOGS";

    private final XpAccountConfigRepository configs;
    private final ClickHouseExecutor executor;
    private final XpUsageDailyRepository usage;
    private final SchedulingEnvironmentConfig scheduling;
    private final XamPulseProperties props;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public XpUsageRollupJob(XpAccountConfigRepository configs, ClickHouseExecutor executor, XpUsageDailyRepository usage,
                            SchedulingEnvironmentConfig scheduling, XamPulseProperties props) {
        this.configs = configs;
        this.executor = executor;
        this.usage = usage;
        this.scheduling = scheduling;
        this.props = props;
    }

    @Scheduled(cron = "${xampulse.usage.cron}")
    public void runAll() {
        if (!scheduling.isSchedulingEnabled() || !props.getUsage().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (XpAccountConfig cfg : configs.findAll()) {
            if (cfg.getCloudAccountId() == null || !isActive(cfg)) {
                continue;
            }
            try {
                runForAccount(cfg.getCloudAccountId(), now);
            } catch (RuntimeException e) {
                log.warn("XamPulse usage rollup failed for account {}: {}", cfg.getCloudAccountId(), e.getClass().getSimpleName());
            }
        }
    }

    void runForAccount(long tenantId, Instant now) {
        int lookbackDays = Math.max(1, props.getUsage().getLookbackDays());
        // Single-tenant scope; forTenant keeps the executor's one-tenant-per-statement invariant explicit.
        QueryScope scope = QueryScope.of(List.of(tenantId), now.minusSeconds((long) lookbackDays * 86_400L), now);
        List<Map<String, Object>> rows = executor.query(builder.usageDailyBySource(scope.forTenant(tenantId)));
        for (Map<String, Object> r : rows) {
            String dayStr = Rows.s(r, "d");
            String source = Rows.s(r, "s");
            if (dayStr.isEmpty()) {
                continue;
            }
            LocalDate day = LocalDate.parse(dayStr);
            long bytes = Rows.l(r, "b");
            long rowCount = Rows.l(r, "r");
            XpUsageDaily entity = usage.findByCloudAccountIdAndDayAndSignalAndSource(tenantId, day, SIGNAL, source)
                    .orElseGet(() -> {
                        XpUsageDaily u = new XpUsageDaily();
                        u.setCloudAccountId(tenantId);
                        u.setDay(day);
                        u.setSignal(SIGNAL);
                        u.setSource(source);
                        return u;
                    });
            entity.setBytesIn(bytes);
            // Approximation: rollup byte-sum stands in for true ClickHouse compressed size until it is measured.
            entity.setBytesStored(bytes);
            entity.setRowCount(rowCount);
            usage.save(entity);
        }
    }

    private static boolean isActive(XpAccountConfig cfg) {
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        return state == XpAccountState.CONNECTED || state == XpAccountState.DEGRADED;
    }
}
