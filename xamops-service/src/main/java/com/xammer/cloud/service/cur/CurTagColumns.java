package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which tags a report carries, and how to read them.
 *
 * <p>A resource id is not a name, and a cost is not a chargeback. Both answers live in
 * tags, which a CUR exposes in two different shapes and only under a condition outside
 * XamOps' control:
 *
 * <ul>
 *   <li>Legacy CUR flattens each <em>activated</em> cost allocation tag into its own
 *       column, {@code resourceTags/user:Name} becoming {@code resource_tags_user_name}.
 *       A tag that has never been activated in the payer's billing console is not in the
 *       report at all, and no query can recover it.</li>
 *   <li>Data Exports (CUR 2.0) carries one {@code resource_tags} map instead.</li>
 * </ul>
 *
 * <p>So columns are asked for rather than assumed. Selecting a column that does not exist
 * does not return nulls — Athena rejects the whole query during analysis, which would take
 * a section down on exactly the accounts that never activated the tag.
 *
 * <p>The probe reads {@code information_schema}, which is catalog metadata: it scans no
 * data and so costs nothing beyond a round trip, and that round trip is cached.
 */
@Service
class CurTagColumns {

    private static final Logger logger = LoggerFactory.getLogger(CurTagColumns.class);

    /** Prefix legacy CUR gives every flattened user tag. */
    private static final String USER_TAG_PREFIX = "resource_tags_user_";

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
     * Short enough that a blip does not cost a day of tags, long enough that a permanent
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
        return Optional.ofNullable(schema(source, account).exprFor("Name"));
    }

    /**
     * The subset of {@code tagKeys} this report can actually answer for, in the order
     * given, each mapped to the expression that reads it.
     *
     * <p>A key that is missing is not an error and not a compliance failure: it means the
     * tag was never activated for cost allocation, so the report has no opinion on which
     * resources carry it. Presenting that as "0% compliant" would be a lie about the
     * infrastructure when it is really a fact about the billing console, which is why the
     * caller is told what was checkable rather than silently scoring the rest as missing.
     */
    Map<String, String> exprsFor(CurSource source, CloudAccount account, List<String> tagKeys) {
        Schema schema = schema(source, account);
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : tagKeys) {
            String expr = schema.exprFor(key);
            if (expr != null) {
                found.put(key, expr);
            }
        }
        return found;
    }

    /** Every user tag the report carries, by the key a reader would recognise. */
    List<String> availableTagKeys(CurSource source, CloudAccount account) {
        return schema(source, account).tagKeys();
    }

    private Schema schema(CurSource source, CloudAccount account) {
        Cached hit = cache.get(source.getId());
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.schema();
        }

        try {
            Schema schema = probe(source, account);
            cache.put(source.getId(), new Cached(schema, Instant.now().plus(TTL)));
            logger.info("[CUR] Tag columns for source {}: {}", source.getId(),
                    schema.isMap() ? "one resource_tags map" : schema.tagKeys());
            return schema;
        } catch (RuntimeException e) {
            cache.put(source.getId(), new Cached(Schema.empty(), Instant.now().plus(FAILURE_TTL)));
            logger.warn("[CUR] Could not read the column list for source {} — tags will be"
                    + " treated as absent: {}", source.getId(), e.getMessage());
            return Schema.empty();
        }
    }

    private Schema probe(CurSource source, CloudAccount account) {
        // LIKE rather than an IN list of guesses: the whole point is to learn which tags
        // exist, and an account may have activated dozens.
        String sql = "SELECT column_name, data_type FROM information_schema.columns"
                + " WHERE table_schema = " + literal(source.getAthenaDatabase())
                + " AND table_name = " + literal(source.getAthenaTable())
                + " AND (column_name LIKE " + literal(USER_TAG_PREFIX + "%")
                + " OR column_name = " + literal(TAG_MAP_COLUMN) + ")";

        Map<String, String> types = new HashMap<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String column = row.get("column_name");
            if (column != null) {
                types.put(column.toLowerCase(), row.getOrDefault("data_type", ""));
            }
        }

        // Only usable as a map. A report that happens to have a column of this name in
        // another shape is left alone rather than guessed at.
        String mapType = types.get(TAG_MAP_COLUMN);
        boolean isMap = mapType != null && mapType.toLowerCase().startsWith("map");

        Map<String, String> flat = new LinkedHashMap<>();
        for (String column : types.keySet()) {
            if (column.startsWith(USER_TAG_PREFIX)) {
                flat.put(column.substring(USER_TAG_PREFIX.length()), column);
            }
        }
        return new Schema(flat, isMap);
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

    /**
     * What one report holds.
     *
     * @param flatColumns tag key, normalised the way AWS names columns, to its column
     * @param isMap       true when tags live in a {@code resource_tags} map instead
     */
    private record Schema(Map<String, String> flatColumns, boolean isMap) {

        static Schema empty() {
            return new Schema(Map.of(), false);
        }

        /**
         * How to read one tag key, or null when this report cannot answer for it.
         *
         * <p>AWS lowercases and underscores tag keys when it builds columns, so "Name",
         * "name" and "NAME" are one column — which is why the lookup normalises the same
         * way rather than matching literally.
         */
        String exprFor(String tagKey) {
            if (tagKey == null || tagKey.isBlank()) {
                return null;
            }
            String normalised = normalise(tagKey);

            String column = flatColumns.get(normalised);
            if (column != null) {
                return column;
            }

            if (isMap) {
                // element_at, not the [] subscript: subscripting a missing key throws and
                // would fail the query for every untagged resource, which is most of them.
                // element_at returns null instead.
                //
                // Both key spellings are tried because the prefix differs by export
                // version, and a wrong guess here is silent — a null tag, not an error.
                // The map is keyed by the tag as written, so the caller's spelling is used
                // rather than the normalised form.
                return "COALESCE(element_at(" + TAG_MAP_COLUMN + ", '" + sqlSafe("user_" + tagKey) + "'),"
                        + " element_at(" + TAG_MAP_COLUMN + ", '" + sqlSafe(tagKey) + "'))";
            }

            return null;
        }

        /** The tag keys this report carries, as close to their original spelling as it kept. */
        List<String> tagKeys() {
            return List.copyOf(flatColumns.keySet());
        }

        private static String normalise(String tagKey) {
            return tagKey.toLowerCase().replaceAll("[^a-z0-9]", "_");
        }

        /**
         * A map key lands in value position, but it arrives from configuration and could
         * carry a quote. Doubling it is the correct escape there.
         */
        private static String sqlSafe(String value) {
            return value.replace("'", "''");
        }
    }

    private record Cached(Schema schema, Instant expiresAt) {
    }
}
