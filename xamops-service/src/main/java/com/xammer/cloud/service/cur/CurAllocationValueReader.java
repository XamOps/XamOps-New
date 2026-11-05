package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the values environment allocation rules can match on, so the rule form can offer them
 * instead of asking the user to type them.
 *
 * <p>Each value is read from the same column, with the same fallbacks, that
 * {@link CurAllocationLineReader} matches against, so a value picked here is a value a rule will
 * find. Values are ranked by unblended cost, largest first, which puts what is worth a rule at the
 * top of the list.
 *
 * <p>Lives in this package for the same reason as the line reader: the partition predicate and tag
 * columns are package-private here, so no listing can scan the whole table or read a tag the
 * report does not carry.
 */
@Service
public class CurAllocationValueReader {

    private static final Logger logger = LoggerFactory.getLogger(CurAllocationValueReader.class);

    /** Values returned per list. A longer list is cut and marked truncated; search narrows it. */
    public static final int MAX_VALUES = 500;

    /** Longest search text accepted. Values on the bill are rarely longer. */
    public static final int MAX_SEARCH_LENGTH = 128;

    /** Prefix CUR 2.0 puts on user-defined keys inside {@code resource_tags}. */
    private static final String MAP_USER_PREFIX = "user_";

    /** The bill columns a rule can match on directly. */
    public enum Column { SERVICE, USAGE_TYPE, REGION, CHARGE_TYPE, RESOURCE_ID }

    /** One value and the unblended cost behind it this month; cost is null when not measured. */
    public record Value(String value, BigDecimal cost) {
    }

    /**
     * @param values    largest cost first
     * @param truncated true when more values exist than were returned
     * @param message   why the list is empty when the report cannot answer, else null
     */
    public record Values(List<Value> values, boolean truncated, String message) {
        static Values unavailable(String message) {
            return new Values(List.of(), false, message);
        }
    }

    private final CurAthenaQueryService athena;
    private final CurTagColumns tagColumns;
    private final CurColumnCatalog columnCatalog;

    public CurAllocationValueReader(CurAthenaQueryService athena, CurTagColumns tagColumns,
                                    CurColumnCatalog columnCatalog) {
        this.athena = athena;
        this.tagColumns = tagColumns;
        this.columnCatalog = columnCatalog;
    }

    /** Distinct values of one bill column, optionally narrowed to those containing {@code search}. */
    public Values column(CurSource source, CloudAccount account, LocalDate start, LocalDate end,
                         Column column, String search) {
        String expr = switch (column) {
            case SERVICE -> columnCatalog.has(source, account, "product_product_name")
                    ? "product_product_name"
                    : "product_servicecode";
            case USAGE_TYPE -> "line_item_usage_type";
            case REGION -> "product_region";
            case CHARGE_TYPE -> "line_item_line_item_type";
            case RESOURCE_ID -> "line_item_resource_id";
        };
        if (column == Column.RESOURCE_ID && !columnCatalog.has(source, account, expr)) {
            return Values.unavailable("This report has no resource ids. Turn on \"Include resource IDs\""
                    + " in the report's settings to match on them.");
        }
        // The line reader reads a blank region as "Global", so the picker offers the same word.
        String valueExpr = column == Column.REGION
                ? "COALESCE(NULLIF(" + expr + ", ''), 'Global')"
                : expr;
        return distinct(source, account, start, end, valueExpr, search, column.name());
    }

    /**
     * Distinct values of one tag. Empty with a message when the report does not carry the tag,
     * because it was never activated as a cost allocation tag.
     */
    public Values tagValues(CurSource source, CloudAccount account, LocalDate start, LocalDate end,
                            String tagKey, String search) {
        Map<String, String> exprs = tagColumns.exprsFor(source, account, List.of(tagKey));
        String expr = exprs.get(tagKey);
        if (expr == null) {
            return Values.unavailable("Tag " + tagKey + " is not in this Cost and Usage Report. Activate it"
                    + " as a cost allocation tag in the payer account's billing console.");
        }
        return distinct(source, account, start, end, expr, search, "tag " + tagKey);
    }

    /**
     * The tag keys the report carries.
     *
     * <p>Legacy reports name each activated tag as a column, so the schema answers without reading
     * data, and the keys come back in the lowercase form AWS gives those columns. CUR 2.0 keeps
     * every tag in one map, so its keys have to be read from the month's rows; there each key also
     * carries the cost of the lines tagged with it.
     */
    public Values tagKeys(CurSource source, CloudAccount account, LocalDate start, LocalDate end,
                          String search) {
        if (!tagColumns.tagsInMap(source, account)) {
            String needle = normaliseSearch(search);
            List<Value> keys = tagColumns.availableTagKeys(source, account).stream()
                    .filter(k -> needle == null || k.toLowerCase().contains(needle))
                    .sorted()
                    .map(k -> new Value(k, null))
                    .toList();
            if (keys.isEmpty() && needle == null) {
                return Values.unavailable("No tags are activated for cost allocation in this report.");
            }
            return cut(keys);
        }

        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String map = CurTagColumns.tagMapColumn();
        StringBuilder where = new StringBuilder(baseWhere(source, account, start, end));
        String needle = normaliseSearch(search);
        if (needle != null) {
            where.append(" AND strpos(lower(tag_key), ").append(literal(needle)).append(") > 0");
        }
        String sql = "SELECT tag_key AS v, SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " CROSS JOIN UNNEST(map_keys(" + map + ")) AS t(tag_key)"
                + " WHERE " + where
                + " GROUP BY tag_key"
                + " ORDER BY SUM(" + cost + ") DESC"
                + " LIMIT " + (MAX_VALUES + 1);

        // A user key may be stored with or without the prefix; both spellings mean one key, and
        // CurTagColumns reads either when given the bare key.
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        List<Map<String, String>> rows = athena.query(source, account, sql);
        for (Map<String, String> row : rows) {
            String raw = row.get("v");
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.startsWith(MAP_USER_PREFIX) ? raw.substring(MAP_USER_PREFIX.length()) : raw;
            if (key.isBlank()) {
                continue;
            }
            merged.merge(key, parse(row.get("cost")), BigDecimal::add);
        }
        List<Value> keys = new ArrayList<>(merged.size());
        merged.forEach((k, c) -> keys.add(new Value(k, c)));
        boolean truncated = rows.size() > MAX_VALUES;
        logger.info("[CUR] Tag keys for account {} ({} to {}): {}", account.getAwsAccountId(), start, end,
                keys.size());
        return new Values(keys.size() > MAX_VALUES ? keys.subList(0, MAX_VALUES) : keys,
                truncated || keys.size() > MAX_VALUES, null);
    }

    private Values distinct(CurSource source, CloudAccount account, LocalDate start, LocalDate end,
                            String valueExpr, String search, String what) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        StringBuilder where = new StringBuilder(baseWhere(source, account, start, end))
                .append(" AND ").append(valueExpr).append(" IS NOT NULL")
                .append(" AND ").append(valueExpr).append(" <> ''");
        String needle = normaliseSearch(search);
        if (needle != null) {
            where.append(" AND strpos(lower(").append(valueExpr).append("), ")
                    .append(literal(needle)).append(") > 0");
        }
        String sql = "SELECT " + valueExpr + " AS v, SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + where
                + " GROUP BY " + valueExpr
                + " ORDER BY SUM(" + cost + ") DESC"
                + " LIMIT " + (MAX_VALUES + 1);

        List<Map<String, String>> rows = athena.query(source, account, sql);
        List<Value> values = new ArrayList<>(Math.min(rows.size(), MAX_VALUES));
        for (Map<String, String> row : rows) {
            String v = row.get("v");
            if (v != null && !v.isBlank()) {
                values.add(new Value(v, parse(row.get("cost"))));
            }
        }
        logger.info("[CUR] Rule values for {} on account {} ({} to {}): {}", what,
                account.getAwsAccountId(), start, end, values.size());
        return cut(values);
    }

    private String baseWhere(CurSource source, CloudAccount account, LocalDate start, LocalDate end) {
        return accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end);
    }

    private static Values cut(List<Value> values) {
        return values.size() > MAX_VALUES
                ? new Values(List.copyOf(values.subList(0, MAX_VALUES)), true, null)
                : new Values(List.copyOf(values), false, null);
    }

    /** Lowercased, trimmed search text, or null when there is nothing to search for. */
    private static String normaliseSearch(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        return s.length() > MAX_SEARCH_LENGTH ? s.substring(0, MAX_SEARCH_LENGTH) : s;
    }

    /**
     * Renders user text as a SQL string literal. Athena (Trino) string literals have no backslash
     * escapes, so doubling the quote is the complete escape.
     */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** The tenant boundary, kept local like every other CUR reader's copy. */
    private String accountPredicate(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                    "Refusing to query CUR without a valid 12-digit account id (got: " + accountId + ")");
        }
        return "line_item_usage_account_id = '" + accountId + "'";
    }

    private static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
