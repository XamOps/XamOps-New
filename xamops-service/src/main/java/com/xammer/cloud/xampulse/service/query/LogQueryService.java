package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Log search and histogram. Multi-account views are fanned out per tenant and merged. */
@Service("xpLogQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class LogQueryService {

    private static final Pattern CURSOR = Pattern.compile("^(\\d{1,19})\\.(\\d{1,20})$");
    private static final long[] NICE_BUCKETS = {1, 5, 10, 15, 30, 60, 300, 600, 900, 1800, 3600, 10800, 21600, 43200, 86400};

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();
    private final XamPulseProperties props;

    public LogQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor, XamPulseProperties props) {
        this.scopes = scopes;
        this.executor = executor;
        this.props = props;
    }

    public XpLogSearchResponse search(ClientUserDetails user, XpLogSearchRequest req) {
        long started = System.nanoTime();
        QueryScope scope = scopes.build(user, req.scope());
        FilterAst filter = FilterDslParser.parse(req.query());
        boolean desc = !"TS_ASC".equalsIgnoreCase(req.sort());
        int limit = Math.min(req.limit() == null ? 200 : req.limit(), props.getLimits().getMaxRows());
        TenantScopedQueryBuilder.LogCursor cursor = decodeCursor(req.cursor());

        List<XpLogRow> merged = new ArrayList<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope,
                s -> builder.search(s, filter, desc, limit + 1, cursor))) {
            for (Map<String, Object> r : tr.rows()) {
                merged.add(Rows.logRow(r));
            }
        }
        Comparator<XpLogRow> cmp = Comparator.comparing(XpLogRow::ts).thenComparing(XpLogRow::id);
        merged.sort(desc ? cmp.reversed() : cmp);
        boolean truncated = merged.size() > limit;
        List<XpLogRow> page = truncated ? new ArrayList<>(merged.subList(0, limit)) : merged;
        String next = null;
        if (truncated && !page.isEmpty()) {
            XpLogRow last = page.get(page.size() - 1);
            LogRowIdCodec.RowRef ref = LogRowIdCodec.decode(last.id());
            next = encodeCursor(ref.tsMs(), ref.bodyHash());
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        return new XpLogSearchResponse(page, next, new XpScanned(page.size(), 0), tookMs, truncated,
                new XpParsed(FilterDslParser.normalise(filter)));
    }

    public XpLogHistogramResponse histogram(ClientUserDetails user, XpLogHistogramRequest req) {
        QueryScope scope = scopes.build(user, req.scope());
        FilterAst filter = FilterDslParser.parse(req.query());
        long bucketSec = bucketFor(scope.rangeSeconds(), req.buckets() == null ? 60 : req.buckets());
        Map<Long, Map<String, Long>> byTime = new LinkedHashMap<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.histogram(s, filter, bucketSec))) {
            for (Map<String, Object> r : tr.rows()) {
                byTime.computeIfAbsent(Rows.l(r, "t"), k -> new LinkedHashMap<>())
                        .merge(Rows.s(r, "sev"), Rows.l(r, "c"), Long::sum);
            }
        }
        List<XpHistogramBucket> out = byTime.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new XpHistogramBucket(Instant.ofEpochSecond(e.getKey()), e.getValue()))
                .toList();
        return new XpLogHistogramResponse(bucketSec, out);
    }

    /** Smallest "nice" bucket that keeps the bucket count at or below the request. */
    static long bucketFor(long rangeSec, int wanted) {
        int w = Math.max(1, Math.min(wanted, 300));
        for (long b : NICE_BUCKETS) {
            if (rangeSec / b <= w) {
                return b;
            }
        }
        return NICE_BUCKETS[NICE_BUCKETS.length - 1];
    }

    static String encodeCursor(long tsMs, String hash) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((tsMs + "." + hash).getBytes(StandardCharsets.UTF_8));
    }

    static TenantScopedQueryBuilder.LogCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = CURSOR.matcher(raw);
            if (!m.matches()) {
                throw XamPulseException.invalid("Invalid cursor.");
            }
            return new TenantScopedQueryBuilder.LogCursor(Long.parseLong(m.group(1)), m.group(2));
        } catch (IllegalArgumentException e) {
            throw XamPulseException.invalid("Invalid cursor.");
        }
    }
}
