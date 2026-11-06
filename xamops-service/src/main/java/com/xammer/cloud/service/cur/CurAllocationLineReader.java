package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads one month of the bill as grouped lines, for dividing it up — today, by label
 * ({@code LabelSpendService}).
 *
 * <p>Lives in this package because the partition predicate, the basis expression and the tag
 * columns are package-private here on purpose — every CUR query goes through the same three, so
 * none can quietly scan the whole table or read a tag the report does not carry.
 *
 * <p>The grain is chosen by the caller. Resource id is only grouped on when asked for, because it
 * multiplies the row count by the number of resources; tag columns are only selected when asked for.
 */
@Service
public class CurAllocationLineReader {

    private static final Logger logger = LoggerFactory.getLogger(CurAllocationLineReader.class);

    /**
     * Ceiling on grouped lines in one month. Far above a normal account at service grain; reached
     * only at resource grain on a very large estate, where a silent cut would under-allocate the
     * month. Exceeding it fails the read with a sentence instead.
     */
    public static final int MAX_LINES = 250_000;

    private final CurAthenaQueryService athena;
    private final CurTagColumns tagColumns;
    private final CurColumnCatalog columnCatalog;

    public CurAllocationLineReader(CurAthenaQueryService athena, CurTagColumns tagColumns,
                                   CurColumnCatalog columnCatalog) {
        this.athena = athena;
        this.tagColumns = tagColumns;
        this.columnCatalog = columnCatalog;
    }

    /**
     * One grouped bill line.
     *
     * @param tags tag key as the caller asked for it, to the value; absent keys are not present
     */
    public record Line(String account, String service, String region, String usageType,
                       String chargeType, String resourceId, Map<String, String> tags,
                       BigDecimal cost) {
    }

    /**
     * @param missingTagKeys tag keys some rule reads that this report does not carry — never
     *                       activated as cost allocation tags, so no line can match on them
     */
    public record Lines(List<Line> lines, List<String> missingTagKeys) {
    }

    public Lines read(CurSource source, CloudAccount account, LocalDate start, LocalDate end,
                      CostBasis basis, Collection<String> tagKeys, boolean byResource) {
        String cost = costExpr(source, account, basis);
        String svc = columnCatalog.has(source, account, "product_product_name")
                ? "product_product_name"
                : "product_servicecode";

        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(tagKeys));
        Map<String, String> exprs = tagColumns.exprsFor(source, account, wanted);
        List<String> missing = wanted.stream().filter(k -> !exprs.containsKey(k)).toList();
        List<String> readable = new ArrayList<>(exprs.keySet());

        List<String> groupExprs = new ArrayList<>(List.of(
                "line_item_usage_account_id", svc, "product_region",
                "line_item_usage_type", "line_item_line_item_type"));
        StringBuilder select = new StringBuilder("SELECT line_item_usage_account_id AS account_id, ")
                .append(svc).append(" AS service, product_region AS region,")
                .append(" line_item_usage_type AS usage_type, line_item_line_item_type AS charge_type");
        if (byResource) {
            select.append(", line_item_resource_id AS resource_id");
            groupExprs.add("line_item_resource_id");
        }
        for (int i = 0; i < readable.size(); i++) {
            String expr = exprs.get(readable.get(i));
            select.append(", ").append(expr).append(" AS tag_").append(i);
            groupExprs.add(expr);
        }

        String sql = select
                + ", SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " GROUP BY " + String.join(", ", groupExprs)
                // Zero-sum groups carry nothing to allocate. Credits are negative and survive.
                + " HAVING SUM(" + cost + ") <> 0"
                + " LIMIT " + (MAX_LINES + 1);

        List<Map<String, String>> rows = athena.query(source, account, sql);
        if (rows.size() > MAX_LINES) {
            throw new IllegalStateException("This month has more than " + MAX_LINES
                    + " distinct bill lines at the grain these rules need. Resource and label"
                    + " rules read every resource separately; narrowing them to service, usage type"
                    + " or tag rules keeps the month readable.");
        }

        List<Line> lines = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> tags = new LinkedHashMap<>();
            for (int i = 0; i < readable.size(); i++) {
                String v = row.get("tag_" + i);
                if (v != null && !v.isBlank()) {
                    tags.put(readable.get(i), v);
                }
            }
            lines.add(new Line(
                    blankTo(row.get("account_id"), account.getAwsAccountId()),
                    blankTo(row.get("service"), "Other"),
                    blankTo(row.get("region"), "Global"),
                    blankTo(row.get("usage_type"), ""),
                    blankTo(row.get("charge_type"), ""),
                    byResource ? blankTo(row.get("resource_id"), "") : "",
                    tags,
                    parse(row.get("cost"))));
        }
        logger.info("✅ [CUR] Allocation lines — {} for account {} ({} to {}, basis {}, resource grain {})",
                lines.size(), account.getAwsAccountId(), start, end, basis.wire(), byResource);
        return new Lines(lines, missing);
    }

    /** Same refusal as the charges card: amortized on a report without the columns is an error. */
    private String costExpr(CurSource source, CloudAccount account, CostBasis basis) {
        if (basis == CostBasis.AMORTIZED) {
            List<String> missing = List.of("reservation_effective_cost",
                            "savings_plan_savings_plan_effective_cost").stream()
                    .filter(c -> !columnCatalog.has(source, account, c))
                    .toList();
            if (!missing.isEmpty()) {
                throw new ReportCannotAnswerException("the amortized cost basis", missing);
            }
            return CurAdvancedMetricsService.amortizedCostExpr(source);
        }
        return CurSql.numericExpr(source, "line_item_unblended_cost");
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

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
