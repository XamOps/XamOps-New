package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily ingest quota check for the ingest auth path. Source of truth for "bytes today" is the ClickHouse
 * {@code xp_log_volume_1m} rollup (the same one {@code UsageMeteringService} reads), summed from 00:00 UTC to now and
 * cached per tenant for {@code xampulse.quota.cache-seconds} so the hot ingest path stays cheap. The limit is
 * {@code XpAccountConfig.ingestQuotaGbDay} in GiB (1024^3 bytes); null or 0 means unlimited.
 *
 * <p>Fails open: if ClickHouse errors the request is allowed (and a WARN with the exception class only is logged),
 * because losing telemetry is worse than overshooting a soft quota and agents queue on disk anyway.
 */
@Service("xpIngestQuotaService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class IngestQuotaService {

    private static final Logger log = LoggerFactory.getLogger(IngestQuotaService.class);
    private static final BigDecimal GIB = BigDecimal.valueOf(1024L * 1024L * 1024L);

    private record Cached(long bytes, long atMs, long dayEpoch) {}

    private final ClickHouseExecutor executor;
    private final XpAccountConfigRepository configs;
    private final XamPulseProperties props;
    private final Clock clock;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public IngestQuotaService(ClickHouseExecutor executor, XpAccountConfigRepository configs, XamPulseProperties props) {
        this(executor, configs, props, Clock.systemUTC());
    }

    IngestQuotaService(ClickHouseExecutor executor, XpAccountConfigRepository configs, XamPulseProperties props, Clock clock) {
        this.executor = executor;
        this.configs = configs;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @return seconds the caller should wait before retrying when the tenant is at or over today's quota; empty when
     *         ingest is allowed (under quota, unlimited, disabled, or ClickHouse unavailable).
     */
    public OptionalLong retryAfterIfExceeded(long tenantId) {
        if (!props.getQuota().isEnabled()) {
            return OptionalLong.empty();
        }
        BigDecimal quotaGb = configs.findByCloudAccountId(tenantId).map(XpAccountConfig::getIngestQuotaGbDay).orElse(null);
        if (quotaGb == null || quotaGb.signum() <= 0) {
            return OptionalLong.empty();
        }
        long limitBytes = quotaGb.multiply(GIB).longValue();
        Instant now = clock.instant();
        long bytes;
        try {
            bytes = bytesToday(tenantId, now);
        } catch (RuntimeException e) {
            log.warn("XamPulse ingest quota check failed open for account {}: {}", tenantId, e.getClass().getSimpleName());
            return OptionalLong.empty();
        }
        if (bytes < limitBytes) {
            return OptionalLong.empty();
        }
        long untilMidnight = Math.max(1, nextMidnightUtc(now).getEpochSecond() - now.getEpochSecond());
        long cap = Math.max(1, props.getQuota().getMaxRetryAfterSeconds());
        return OptionalLong.of(Math.min(untilMidnight, cap));
    }

    private long bytesToday(long tenantId, Instant now) {
        long day = now.getEpochSecond() / 86_400L;
        long ttlMs = Math.max(0, props.getQuota().getCacheSeconds()) * 1000L;
        Cached hit = cache.get(tenantId);
        if (hit != null && hit.dayEpoch() == day && now.toEpochMilli() - hit.atMs() < ttlMs) {
            return hit.bytes();
        }
        Instant start = Instant.ofEpochSecond(day * 86_400L);
        QueryScope scope = QueryScope.of(List.of(tenantId), start, now.plusSeconds(60));
        long total = 0;
        for (Map<String, Object> row : executor.query(builder.usageByDay(scope.forTenant(tenantId)))) {
            Object b = row.get("b");
            if (b instanceof Number n) {
                total += n.longValue();
            }
        }
        cache.put(tenantId, new Cached(total, now.toEpochMilli(), day));
        return total;
    }

    static Instant nextMidnightUtc(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
