package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpUsageDaily;
import com.xammer.cloud.xampulse.dto.XpUsage;
import com.xammer.cloud.xampulse.dto.XpUsageDay;
import com.xammer.cloud.xampulse.dto.XpUsageSource;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpUsageDailyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usage view. Ingested bytes are real (log volume rollup). Stored (compressed) bytes come from {@code xp_usage_daily}
 * when a metering job has written them and are 0 until then; archive size and projected customer AWS cost are not
 * measured yet and are reported as 0 / null rather than estimated.
 */
@Service("xpUsageMeteringService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class UsageMeteringService {

    private static final Pattern RANGE = Pattern.compile("^(\\d{1,2})d$");

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final XpUsageDailyRepository usage;
    private final XpAccountConfigRepository configs;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public UsageMeteringService(QueryScopeFactory scopes, ClickHouseExecutor executor, XpUsageDailyRepository usage,
                                XpAccountConfigRepository configs) {
        this.scopes = scopes;
        this.executor = executor;
        this.usage = usage;
        this.configs = configs;
    }

    public XpUsage usage(ClientUserDetails user, Long accountId, String range) {
        Matcher m = RANGE.matcher(range == null ? "7d" : range);
        if (!m.matches() || Integer.parseInt(m.group(1)) < 1 || Integer.parseInt(m.group(1)) > 30) {
            throw XamPulseException.invalid("Range must look like 7d (1 to 30 days).");
        }
        int days = Integer.parseInt(m.group(1));
        Instant now = Instant.now();
        QueryScope scope = scopes.build(user, List.of(accountId), now.minus(Duration.ofDays(days)), now);

        Map<String, Long> stored = new HashMap<>();
        for (XpUsageDaily u : usage.findByCloudAccountIdAndDayGreaterThanEqual(accountId,
                LocalDate.now(ZoneOffset.UTC).minusDays(days))) {
            if ("LOGS".equals(u.getSignal())) {
                stored.merge(u.getDay().toString(), u.getBytesStored(), Long::sum);
            }
        }
        List<XpUsageDay> byDay = executor.query(builder.usageByDay(scope.forTenant(accountId))).stream()
                .map(r -> new XpUsageDay(Rows.s(r, "d"), "LOGS", Rows.l(r, "b"), stored.getOrDefault(Rows.s(r, "d"), 0L)))
                .toList();
        long total = executor.query(builder.usageBySource(scope.forTenant(accountId))).stream().mapToLong(r -> Rows.l(r, "b")).sum();
        List<XpUsageSource> bySource = executor.query(builder.usageBySource(scope.forTenant(accountId))).stream()
                .map(r -> new XpUsageSource(Rows.s(r, "s"), Rows.l(r, "b"), total == 0 ? 0 : Rows.l(r, "b") * 100.0 / total))
                .toList();
        long hot = byDay.stream().mapToLong(XpUsageDay::bytesIn).sum();
        Double quota = configs.findByCloudAccountId(accountId).map(c -> c.getIngestQuotaGbDay() == null ? null : c.getIngestQuotaGbDay().doubleValue()).orElse(null);
        return new XpUsage(days + "d", byDay, bySource, hot, 0L, null, quota);
    }
}
