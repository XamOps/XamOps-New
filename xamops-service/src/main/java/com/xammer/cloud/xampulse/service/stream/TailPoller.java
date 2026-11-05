package com.xammer.cloud.xampulse.service.stream;

import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Stateless-per-poll tail over {@code ingest_ts}. One instance per WebSocket session; it holds only the cursor.
 *
 * <ul>
 *   <li>Rows are fetched with {@code ingest_ts > cursor - 1 ms} and rows already delivered at the cursor millisecond
 *       are dropped, so batches that share a millisecond are neither lost nor repeated.</li>
 *   <li>At most {@link #MAX_ROWS_PER_POLL} rows per poll (about 2000 per second at the 1 s cadence). Beyond that the
 *       overflow is dropped and counted, never silently: the caller sends RATE_CAPPED and the dropped count.</li>
 * </ul>
 */
public final class TailPoller {

    public static final int MAX_ROWS_PER_POLL = 2000;

    public record Batch(List<XpLogRow> rows, long cursorMs, long dropped, boolean capped) {}

    private final Function<ScopedSql, List<Map<String, Object>>> runner;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();
    private final List<Long> accountIds;
    private final Clock clock;
    private FilterAst filter;
    private long cursorMs;
    private final Set<String> seenAtCursor = new HashSet<>();

    public TailPoller(Function<ScopedSql, List<Map<String, Object>>> runner, List<Long> accountIds, FilterAst filter, Clock clock) {
        this.runner = runner;
        this.accountIds = List.copyOf(accountIds);
        this.filter = filter;
        this.clock = clock;
        this.cursorMs = clock.millis() - 2_000;
    }

    public synchronized void setFilter(FilterAst newFilter) {
        this.filter = newFilter;
    }

    public synchronized long cursor() {
        return cursorMs;
    }

    public synchronized Batch poll() {
        Instant now = clock.instant();
        List<XpLogRow> collected = new ArrayList<>();
        List<long[]> ingest = new ArrayList<>();
        for (Long tenant : accountIds) {
            QueryScope scope = new QueryScope(List.of(tenant), now.minus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(1)),
                    List.of(), List.of(), List.of(), List.of());
            List<Map<String, Object>> rows = runner.apply(builder.tailPoll(scope, filter, cursorMs - 1, MAX_ROWS_PER_POLL + 1));
            for (Map<String, Object> r : rows) {
                long ing = Rows.l(r, "ingest_ms");
                XpLogRow row = Rows.logRow(r);
                if (ing < cursorMs || (ing == cursorMs && seenAtCursor.contains(row.id()))) {
                    continue;
                }
                collected.add(row);
                ingest.add(new long[] {ing, collected.size() - 1});
            }
        }
        ingest.sort(Comparator.comparingLong((long[] a) -> a[0]).thenComparingLong(a -> a[1]));
        List<XpLogRow> ordered = new ArrayList<>();
        List<Long> orderedIngest = new ArrayList<>();
        for (long[] i : ingest) {
            ordered.add(collected.get((int) i[1]));
            orderedIngest.add(i[0]);
        }
        long dropped = 0;
        boolean capped = false;
        if (ordered.size() > MAX_ROWS_PER_POLL) {
            dropped = ordered.size() - MAX_ROWS_PER_POLL;
            capped = true;
            ordered = new ArrayList<>(ordered.subList(0, MAX_ROWS_PER_POLL));
            orderedIngest = new ArrayList<>(orderedIngest.subList(0, MAX_ROWS_PER_POLL));
            // Skipped rows are intentionally not revisited: advance past the whole fetched window.
            cursorMs = Math.max(cursorMs, ingest.get(ingest.size() - 1)[0]);
            seenAtCursor.clear();
        } else if (!ordered.isEmpty()) {
            long newCursor = orderedIngest.get(orderedIngest.size() - 1);
            if (newCursor > cursorMs) {
                seenAtCursor.clear();
                cursorMs = newCursor;
            }
            for (int i = 0; i < ordered.size(); i++) {
                if (orderedIngest.get(i) == cursorMs) {
                    seenAtCursor.add(ordered.get(i).id());
                }
            }
        }
        return new Batch(ordered, cursorMs, dropped, capped);
    }
}
