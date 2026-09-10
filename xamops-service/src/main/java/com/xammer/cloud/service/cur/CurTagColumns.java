package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a report keeps a resource's {@code Name} tag — if it keeps it at all.
 *
 * <p>A resource id is not a name. {@code i-089ccf1b5a839880a} says nothing about what the
 * instance is for, and the answer almost always exists as the {@code Name} tag the console
 * shows. In a CUR that tag is reachable, but by two different shapes and only under a
 * condition outside XamOps' control:
 *
 * <ul>
 *   <li>Legacy CUR flattens each <em>activated</em> cost allocation tag into its own
 *       column, {@code resourceTags/user:Name} becoming {@code resource_tags_user_name}.
 *       A tag that has never been activated in the payer's billing console is not in the
 *       report at all, and no query can recover it.</li>
 *   <li>Data Exports (CUR 2.0) carries one {@code resource_tags} map instead.</li>
 * </ul>
 *
 * <p>So the column is asked for rather than assumed. Selecting a column that does not
 * exist does not return nulls — Athena rejects the whole query during analysis, which
 * would take the section down on exactly the accounts that never activated the tag.
 *
 * <p>The probe reads {@code information_schema}, which is catalog metadata: it scans no
 * data and so costs nothing beyond a round trip, and that round trip is cached.
 */
@Service
class CurTagColumns {

    private static final Logger logger = LoggerFactory.getLogger(CurTagColumns.class);

    /** Legacy CUR's flattened column for the {@code Name} tag. */
    private static final String FLAT_NAME_COLUMN = "resource_tags_user_name";

    /** CUR 2.0's single map of every tag. */
    private static final String TAG_MAP_COLUMN = "resource_tags";

    /**
     * A schema only changes when a tag is activated or the table is re-provisioned, and
     * an activated tag takes about a day to reach the report — so a long cache cannot
     * hide a change that has actually landed.
     */
    private static final Duration TTL = Duration.ofHours(6);

    /**
     * A failed probe is usually permanent (no permission on the catalog), but not always.
     * Short enough that a blip does not cost a day of names, long enough that a permanent
     * failure is not re-probed on every request.
     */
    private static final Duration FAILURE_TTL = Duration.ofMinutes(10);

    private final CurAthenaQueryService athena;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    CurTagColumns(CurAthenaQueryService athena) {
        this.athena = athena;
    }

    /**
     * A SQL expression yielding the resource's {@code Name} tag, or empty when this report
     * does not carry it. Callers must degrade rather than fail on empty — a report without
     * the tag activated is normal, not broken.
     */
    Optional<String> resourceNameExpr(CurSource source, CloudAccount account) {
        Cached hit = cache.get(source.getId());
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return Optional.ofNullable(hit.expr());
        }

        try {
            String expr = probe(source, account);
            cache.put(source.getId(), new Cached(expr, Instant.now().plus(TTL)));
            logger.info("[CUR] Resource name tag for source {}: {}",
                    source.getId(), expr == null ? "not present in this report" : expr);
            return Optional.ofNullable(expr);
        } catch (RuntimeException e) {
            cache.put(source.getId(), new Cached(null, Instant.now().plus(FAILURE_TTL)));
            logger.warn("[CUR] Could not read the column list for source {} — resources will be"
                    + " shown without names: {}", source.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String probe(CurSource source, CloudAccount account) {
        String sql = "SELECT column_name, data_type FROM information_schema.columns"
                + " WHERE table_schema = " + literal(source.getAthenaDatabase())
                + " AND table_name = " + literal(source.getAthenaTable())
                + " AND column_name IN (" + literal(FLAT_NAME_COLUMN)
                + ", " + literal(TAG_MAP_COLUMN) + ")";

        Map<String, String> types = new HashMap<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String column = row.get("column_name");
            if (column != null) {
                types.put(column.toLowerCase(), row.getOrDefault("data_type", ""));
            }
        }

        if (types.containsKey(FLAT_NAME_COLUMN)) {
            return FLAT_NAME_COLUMN;
        }

        // Only usable as a map. A report that happens to have a column of this name in
        // another shape is left alone rather than guessed at.
        String mapType = types.get(TAG_MAP_COLUMN);
        if (mapType != null && mapType.toLowerCase().startsWith("map")) {
            // element_at, not the [] subscript: subscripting a missing key throws and would
            // fail the query for every untagged resource, which is most of them. element_at
            // returns null instead.
            //
            // Both key spellings are tried because the prefix differs by export version,
            // and a wrong guess here is silent — a null name, not an error.
            return "COALESCE(element_at(" + TAG_MAP_COLUMN + ", 'user_Name'),"
                    + " element_at(" + TAG_MAP_COLUMN + ", 'Name'))";
        }

        return null;
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

    /** @param expr the resolved expression, or null for "this report has no name tag". */
    private record Cached(String expr, Instant expiresAt) {
    }
}
