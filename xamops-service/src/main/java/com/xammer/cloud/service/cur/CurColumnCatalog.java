package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which columns a report's table actually has.
 *
 * <p>A CUR is not one schema. Legacy CUR and Data Exports (CUR 2.0) name things
 * differently, a Data Export is created with a column selection the customer chooses, and
 * an existing table XamOps did not provision can be missing anything at all. Two columns
 * this matters for are {@code product_from_location} and {@code product_to_location} — the
 * only place the report says where traffic went — which are standard in both dialects and
 * still absent from plenty of real tables.
 *
 * <p>Asking rather than assuming is not defensive style, it is the only thing that works:
 * selecting a column Athena does not know about fails the query during analysis, so a
 * single missing column takes down the whole section instead of leaving one field blank.
 *
 * <p>The probe reads {@code information_schema}, which is catalog metadata — it scans no
 * data, so it costs a round trip and nothing else, and that round trip is cached.
 *
 * <p>{@link CurTagColumns} does the same thing for tags, where the answer is a mapping from
 * tag key to column rather than a plain set, and deliberately keeps its own probe.
 */
@Service
class CurColumnCatalog {

    private static final Logger logger = LoggerFactory.getLogger(CurColumnCatalog.class);

    /**
     * A table's column list changes only when it is re-provisioned or the export's column
     * selection is edited, neither of which is a per-request event.
     */
    private static final Duration TTL = Duration.ofHours(6);

    /**
     * A failed probe is usually permanent — no permission on the catalog — but not always.
     * Short enough that a blip does not disable route detail for a day, long enough that a
     * permanent failure is not re-probed on every request.
     */
    private static final Duration FAILURE_TTL = Duration.ofMinutes(10);

    private final CurAthenaQueryService athena;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    CurColumnCatalog(CurAthenaQueryService athena) {
        this.athena = athena;
    }

    /** True when the report carries {@code column}. False also covers "could not tell". */
    boolean has(CurSource source, CloudAccount account, String column) {
        return columns(source, account).contains(column.toLowerCase());
    }

    /**
     * False when the column list could not be read, so every "absent" answer above means
     * "unknown" rather than "no".
     *
     * <p>Needed because {@link #columns} caches the empty set for {@link #FAILURE_TTL} on a failed
     * probe, which makes "the column is absent" and "the catalog could not be read" byte-identical
     * to every caller. The two have different remedies — the first means the export has to be
     * recreated, the second means asking again in ten minutes — and telling someone to rebuild
     * their export when the real answer is "try again shortly" costs them an afternoon.
     *
     * <p>A real report always has columns, so an empty set is only ever a failed probe.
     */
    boolean readable(CurSource source, CloudAccount account) {
        return !columns(source, account).isEmpty();
    }

    /**
     * The subset of {@code candidates} the report carries, in the order given.
     *
     * <p>Order is the caller's preference order, so a COALESCE built from the result reads
     * the best available column first.
     */
    List<String> present(CurSource source, CloudAccount account, String... candidates) {
        Set<String> available = columns(source, account);
        List<String> found = new ArrayList<>();
        for (String candidate : candidates) {
            if (available.contains(candidate.toLowerCase())) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** Every column name in the table, lowercased. Empty when the catalog cannot be read. */
    private Set<String> columns(CurSource source, CloudAccount account) {
        Cached hit = cache.get(source.getId());
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.columns();
        }

        try {
            Set<String> columns = probe(source, account);
            cache.put(source.getId(), new Cached(columns, Instant.now().plus(TTL)));
            logger.info("[CUR] Column catalog for source {}: {} columns", source.getId(), columns.size());
            return columns;
        } catch (RuntimeException e) {
            // Every optional column is then reported absent, which degrades a section
            // rather than breaking it — the same trade CurTagColumns makes.
            cache.put(source.getId(), new Cached(Set.of(), Instant.now().plus(FAILURE_TTL)));
            logger.warn("[CUR] Could not read the column list for source {} — optional columns"
                    + " will be treated as absent: {}", source.getId(), e.getMessage());
            return Set.of();
        }
    }

    private Set<String> probe(CurSource source, CloudAccount account) {
        String sql = "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = " + literal(source.getAthenaDatabase())
                + " AND table_name = " + literal(source.getAthenaTable());

        Set<String> columns = new HashSet<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String column = row.get("column_name");
            if (column != null && !column.isBlank()) {
                columns.add(column.toLowerCase());
            }
        }
        return columns;
    }

    /**
     * Renders a value as a SQL literal.
     *
     * <p>These are database and table names from our own records rather than from a
     * request, but they land in value position here — inside a {@code WHERE} against
     * {@code information_schema} — so the ordinary escape is both correct and enough.
     */
    private String literal(String value) {
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private record Cached(Set<String> columns, Instant expiresAt) {
    }
}
