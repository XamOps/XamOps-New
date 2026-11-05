package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tenant-scoped reads through the normal executor, cached for 30 s because the checklist stream polls every 3 s. */
@Component("xpClickHouseTelemetryProbe")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ClickHouseTelemetryProbe implements TelemetryProbe {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseTelemetryProbe.class);
    static final Duration WINDOW = Duration.ofHours(6);

    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();
    private final Cache<Long, Landed> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(2000).build();

    public ClickHouseTelemetryProbe(ClickHouseExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Landed landed(long tenantId) {
        Landed hit = cache.getIfPresent(tenantId);
        if (hit != null) {
            return hit;
        }
        try {
            Instant now = Instant.now();
            QueryScope scope = QueryScope.of(java.util.List.of(tenantId), now.minus(WINDOW), now.plusSeconds(60));
            Set<String> logRegions = keys(executor.query(builder.landedLogRegions(scope)));
            Set<String> metricInstances = keys(executor.query(builder.landedMetricInstances(scope)));
            Landed out = new Landed(logRegions, metricInstances, true);
            cache.put(tenantId, out);
            return out;
        } catch (RuntimeException e) {
            log.info("XamPulse telemetry probe unavailable for account {}: {}", tenantId, e.getClass().getSimpleName());
            return Landed.unavailable();
        }
    }

    private static Set<String> keys(java.util.List<Map<String, Object>> rows) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : rows) {
            if (Rows.l(r, "v") > 0) {
                out.add(Rows.s(r, "g0"));
            }
        }
        return out;
    }
}
