package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.platform.ClickHouseWrites;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.setup.SetupStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes per hour-of-week median/MAD baselines (xp_baseline) from the hourly rollup, one tenant and one metric
 * at a time. Runs on a cron; gated on {@link SchedulingEnvironmentConfig#isSchedulingEnabled()} and the baseline
 * enabled flag. A failure for one account never stops the others, and ClickHouse errors are swallowed (class name
 * only, never parameters).
 */
@Component("xpBaselineJob")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class BaselineJob {

    private static final Logger log = LoggerFactory.getLogger(BaselineJob.class);

    static final List<String> COLUMNS = List.of("tenant_id", "metric", "instance_id", "hour_of_week", "med", "mad", "updated_at");

    private final XpAccountConfigRepository configs;
    private final ClickHouseExecutor executor;
    private final ClickHouseWrites writes;
    private final SchedulingEnvironmentConfig scheduling;
    private final XamPulseProperties props;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public BaselineJob(XpAccountConfigRepository configs, ClickHouseExecutor executor, ClickHouseWrites writes,
                       SchedulingEnvironmentConfig scheduling, XamPulseProperties props) {
        this.configs = configs;
        this.executor = executor;
        this.writes = writes;
        this.scheduling = scheduling;
        this.props = props;
    }

    @Scheduled(cron = "${xampulse.baseline.cron}")
    public void runAll() {
        if (!scheduling.isSchedulingEnabled() || !props.getBaseline().isEnabled()) {
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
                log.warn("XamPulse baseline run failed for account {}: {}", cfg.getCloudAccountId(), e.getClass().getSimpleName());
            }
        }
    }

    void runForAccount(long tenantId, Instant now) {
        int windowDays = props.getBaseline().getWindowDays();
        for (String metric : props.getBaseline().getMetrics()) {
            // Single-tenant scope; forTenant keeps the executor's one-tenant-per-statement invariant explicit.
            QueryScope scope = QueryScope.of(List.of(tenantId), now.minusSeconds((long) windowDays * 86_400L + 60), now.plusSeconds(60));
            List<Map<String, Object>> samplesRows = executor.query(builder.baselineSamples(scope.forTenant(tenantId), metric, windowDays));

            Map<String, List<BaselineMath.Sample>> byInstance = new LinkedHashMap<>();
            for (Map<String, Object> r : samplesRows) {
                String instance = Rows.s(r, "instance_id");
                double v = Rows.d(r, "v");
                long epoch = Rows.l(r, "t");
                byInstance.computeIfAbsent(instance, k -> new ArrayList<>())
                        .add(new BaselineMath.Sample(Instant.ofEpochSecond(epoch), v));
            }

            List<List<Object>> rows = new ArrayList<>();
            Timestamp updatedAt = Timestamp.from(now);
            byInstance.forEach((instance, samples) -> {
                Map<Integer, BaselineMath.Baseline> baselines = BaselineMath.compute(samples);
                baselines.forEach((hour, b) ->
                        rows.add(List.of(tenantId, metric, instance, hour, b.median(), b.mad(), updatedAt)));
            });

            if (!rows.isEmpty()) {
                try {
                    writes.insert("xp_baseline", COLUMNS, rows);
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static boolean isActive(XpAccountConfig cfg) {
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        return state == XpAccountState.CONNECTED || state == XpAccountState.DEGRADED;
    }
}
