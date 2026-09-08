package com.xammer.cloud.service.cur;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates the Glue database and external table that Athena queries the CUR
 * through.
 *
 * <h2>Why generate DDL instead of running a Glue crawler</h2>
 * The CUR console offers a crawler, and it works, but it has to be scheduled, it
 * bills per crawl, and it adds a lag between AWS delivering a report and the
 * table seeing it. The manifest already lists every column with its type, so the
 * schema can be derived exactly — for a report XamOps created and equally for a
 * customer's existing one, where we may not be able to run a crawler at all.
 *
 * <h2>Partition projection</h2>
 * The table uses projection rather than registered partitions, so there is
 * nothing to repair: no {@code MSCK REPAIR TABLE} after each delivery, no
 * {@code ALTER TABLE ADD PARTITION} for a new month, and no window where the
 * current month exists in S3 but not in the catalog. Athena computes the
 * partition paths from the year/month ranges instead. This matters because AWS
 * rewrites the open month up to three times a day — a catalog that needed
 * updating per delivery would be wrong most of the time.
 */
@Service
public class CurTableProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(CurTableProvisioningService.class);

    /**
     * Lower bound for the projected year range. Nothing earlier can exist: a CUR
     * cannot be created retroactively and does not backfill.
     */
    private static final int PROJECTION_START_YEAR = 2020;

    /** Billing-period folder names look like 20260801-20260901. */
    private static final DateTimeFormatter PERIOD_FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CurAthenaQueryService athena;

    public CurTableProvisioningService(CurAthenaQueryService athena) {
        this.athena = athena;
    }

    /**
     * Creates (or replaces) the database and table for a source.
     *
     * <p>Drops and recreates the table rather than altering it. The table is pure
     * metadata over files in S3 — dropping an EXTERNAL table deletes nothing — and
     * a CUR gains columns over time as AWS adds product attributes and the customer
     * activates cost allocation tags, so recreating from the current manifest is
     * what keeps the schema honest.
     */
    public void provision(CurSource source, CloudAccount account, JsonNode manifest,
                          List<String> billingPeriodFolders) {
        List<Column> columns = columnsFrom(manifest);
        if (columns.isEmpty()) {
            throw new CurAthenaQueryService.CurQueryException(
                    "Manifest for CUR source " + source.getId() + " lists no columns");
        }

        String database = ddlIdent(source.getAthenaDatabase());
        String table = database + "." + ddlIdent(source.getAthenaTable());

        athena.query(source, account, "CREATE DATABASE IF NOT EXISTS " + database);
        athena.query(source, account, "DROP TABLE IF EXISTS " + table);

        String ddl = buildCreateTable(source, table, columns);
        logger.info("[CUR] Provisioning table {} with {} columns", table, columns.size());
        athena.query(source, account, ddl);

        if (CurSql.isCsv(source)) {
            registerCsvPartitions(source, account, table, billingPeriodFolders);
        }

        logger.info("✅ [CUR] Table {} created over {}", table, dataLocation(source));
    }

    /**
     * Registers one partition per billing-period folder, for CSV reports only.
     *
     * <p>Parquet CURs are delivered into {@code year=/month=} directories, which
     * partition projection can address without any catalog bookkeeping. CSV CURs are
     * not: each delivery goes into a {@code YYYYMMDD-YYYYMMDD} folder whose name is a
     * date <em>range</em>, and no projection type can generate that. So these
     * partitions are registered explicitly, mapping the same {@code year}/{@code month}
     * keys onto the range folder — which keeps
     * {@link CurSql#partitionPredicate} working unchanged for both formats.
     *
     * <p>Re-registered on every validation, so a new month is picked up by the daily
     * job rather than needing a manual repair.
     */
    private void registerCsvPartitions(CurSource source, CloudAccount account, String table,
                                       List<String> billingPeriodFolders) {
        if (billingPeriodFolders == null || billingPeriodFolders.isEmpty()) {
            logger.warn("[CUR] CSV source {} has no billing period folders to register", source.getId());
            return;
        }

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table).append(" ADD IF NOT EXISTS");
        for (String folder : billingPeriodFolders) {
            // "20260801-20260901" -> year 2026, month 8. Month is unpadded to match the
            // value Athena yields for a projected Parquet partition, so the shared
            // predicate compares like with like.
            LocalDate periodStart = LocalDate.parse(folder.substring(0, 8), PERIOD_FOLDER);
            sql.append("\n  PARTITION (year='").append(periodStart.getYear())
               .append("', month='").append(periodStart.getMonthValue()).append("')")
               .append(" LOCATION '").append(reportRootUri(source)).append(folder).append("/'");
        }

        athena.query(source, account, sql.toString());
        logger.info("[CUR] Registered {} CSV partitions on {}", billingPeriodFolders.size(), table);
    }

    /**
     * {@code s3://<bucket>/<prefix>/<report>/} — where CUR writes one folder per
     * billing period, and, for Parquet, the separate data directory alongside them.
     */
    String reportRootUri(CurSource source) {
        String prefix = source.getS3Prefix();
        String root = (prefix == null || prefix.isBlank())
                ? ""
                : prefix.replaceAll("^/+|/+$", "") + "/";
        return "s3://" + source.getS3Bucket() + "/" + root + source.getReportName() + "/";
    }

    /**
     * Table location, which differs by format.
     *
     * <p>A Parquet CUR delivered with OVERWRITE_REPORT writes its data to
     * {@code <prefix>/<report>/<report>/year=…/month=…/} — the report name appears
     * twice — while {@code <prefix>/<report>/YYYYMMDD-YYYYMMDD/} alongside it holds
     * only manifests. Pointing a Parquet table at the parent would make Athena try to
     * read the manifest JSON as Parquet.
     *
     * <p>A CSV CUR has no such data directory: each delivery lands directly in its
     * billing-period folder, so the table is rooted at the report and each period is
     * registered as a partition — see {@code registerCsvPartitions}.
     */
    String dataLocation(CurSource source) {
        if (CurSql.isCsv(source)) {
            return reportRootUri(source);
        }
        return reportRootUri(source) + source.getReportName() + "/";
    }

    private String buildCreateTable(CurSource source, String qualifiedTable, List<Column> columns) {
        boolean csv = CurSql.isCsv(source);
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE EXTERNAL TABLE ").append(qualifiedTable).append(" (\n");

        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            // OpenCSVSerde has no type support: it returns every column as a string
            // regardless of what the DDL declares, and declaring a double makes Athena
            // fail at read time rather than coerce. So a CSV table is all-string, and
            // the query layer casts instead — see CurSql.numericExpr.
            sql.append("  ").append(ddlIdent(c.name())).append(' ').append(csv ? "string" : c.type());
            if (i < columns.size() - 1) {
                sql.append(',');
            }
            sql.append('\n');
        }

        // year and month are the partition keys the CUR delivery itself writes. They are
        // declared string, not int, because Athena returns projected integer partitions
        // as their literal path value ("8", not "08") and the query layer compares them
        // as a zero-padded string — see CurSql.partitionPredicate.
        sql.append(")\nPARTITIONED BY (\n  year string,\n  month string\n)\n");
        if (csv) {
            sql.append("ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde'\n");
            sql.append("WITH SERDEPROPERTIES (\n");
            sql.append("  'separatorChar' = ',',\n");
            sql.append("  'quoteChar' = '\"',\n");
            sql.append("  'escapeChar' = '\\\\'\n");
            sql.append(")\n");
            sql.append("STORED AS TEXTFILE\n");
        } else {
            sql.append("STORED AS PARQUET\n");
        }
        sql.append("LOCATION '").append(dataLocation(source)).append("'\n");

        if (csv) {
            // No projection: a CSV delivery lands in a YYYYMMDD-YYYYMMDD folder whose name
            // is a date range, and no projection type can generate that. Those partitions
            // are registered explicitly after creation instead.
            //
            // skip.header.line.count matters more than it looks: a CSV CUR repeats the
            // column headers as the first line of every file, and without this they are
            // read as a data row where every numeric cast yields 0 — a small, silent,
            // permanent error in the total.
            sql.append("TBLPROPERTIES ('skip.header.line.count' = '1')");
        } else {
            sql.append("TBLPROPERTIES (\n");
            sql.append("  'projection.enabled' = 'true',\n");
            sql.append("  'projection.year.type' = 'integer',\n");
            sql.append("  'projection.year.range' = '")
               .append(PROJECTION_START_YEAR).append(",").append(Year.now().getValue() + 1).append("',\n");
            sql.append("  'projection.month.type' = 'integer',\n");
            sql.append("  'projection.month.range' = '1,12',\n");
            // No 'digits' property: CUR writes month=8, not month=08, so forcing two digits
            // would project paths that do not exist and every query would return nothing.
            sql.append("  'storage.location.template' = '")
               .append(dataLocation(source)).append("year=${year}/month=${month}'\n");
            sql.append(")");
        }

        return sql.toString();
    }

    /**
     * Builds the column list from the manifest, in manifest order.
     *
     * <p>Duplicate names are dropped rather than merged. AWS can emit two manifest
     * entries that collapse to the same Athena identifier — most often a cost
     * allocation tag whose key differs from another only by punctuation — and a
     * CREATE TABLE with a repeated column name fails outright, taking the whole
     * source down for one tag.
     */
    List<Column> columnsFrom(JsonNode manifest) {
        List<Column> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean prefixed = usesPrefixedNames(manifest);

        for (JsonNode column : manifest.path("columns")) {
            String category = column.path("category").asText("");
            String name = column.path("name").asText("");
            if (category.isBlank() || name.isBlank()) {
                continue;
            }

            String athenaName = toAthenaColumn(category, name, prefixed);
            // year and month are partition keys; declaring them as data columns too is
            // a CREATE TABLE error.
            if ("year".equals(athenaName) || "month".equals(athenaName)) {
                continue;
            }
            if (!seen.add(athenaName)) {
                logger.warn("[CUR] Duplicate column {} from {}/{} — keeping the first",
                        athenaName, category, name);
                continue;
            }
            columns.add(new Column(athenaName, toAthenaType(column.path("type").asText(""))));
        }
        return columns;
    }

    /**
     * Converts a manifest {@code category}/{@code name} pair to the Athena column
     * name, matching the convention AWS's own Glue crawler uses:
     * {@code lineItem/UnblendedCost} becomes {@code line_item_unblended_cost}.
     *
     * <p>The query layer hard-codes column names in this form, so this method and
     * {@link CurCostDataSource}'s column constants have to agree.
     */
    static String toAthenaColumn(String category, String name) {
        return toAthenaColumn(category, name, false);
    }

    /**
     * @param alreadyPrefixed true when this manifest's names already carry the category
     *                        prefix — decide it once per manifest with
     *                        {@link #usesPrefixedNames}, never per column
     */
    static String toAthenaColumn(String category, String name, boolean alreadyPrefixed) {
        String column = snake(name);
        return alreadyPrefixed ? column : snake(category) + "_" + column;
    }

    /**
     * Detects which of AWS's two manifest dialects this is.
     *
     * <pre>
     *   legacy : {"category":"lineItem", "name":"ResourceId"}
     *   current: {"category":"lineItem", "name":"line_item_resource_id"}
     * </pre>
     *
     * <p>The current dialect has already applied the category prefix, so prefixing again
     * yields {@code line_item_line_item_resource_id} — a table whose every column name is
     * wrong, created without error, failing on the first query. Verified against a live
     * manifest: AWS is shipping the second form today.
     *
     * <p>Decided by majority across the whole manifest rather than per column, because
     * per-column detection cannot work. Legacy names whose first word repeats the
     * category — {@code product/ProductName} → {@code product_name},
     * {@code lineItem/LineItemType} → {@code line_item_type} — look already-prefixed in
     * isolation and would be silently truncated. Across 355 columns the two dialects are
     * unambiguous; on any single column they are not.
     */
    static boolean usesPrefixedNames(JsonNode manifest) {
        int prefixed = 0;
        int total = 0;
        for (JsonNode column : manifest.path("columns")) {
            String category = column.path("category").asText("");
            String name = column.path("name").asText("");
            if (category.isBlank() || name.isBlank()) {
                continue;
            }
            total++;
            if (snake(name).startsWith(snake(category) + "_")) {
                prefixed++;
            }
        }
        return total > 0 && prefixed * 2 > total;
    }

    private static String snake(String value) {
        // Punctuation first: a tag key like "user:Environment" becomes "user_Environment"
        // before case boundaries are considered, so the colon does not survive as a
        // character Athena would reject in an identifier.
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "_");
        String spaced = cleaned.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return spaced.toLowerCase()
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Maps a CUR manifest type to an Athena type.
     *
     * <p>Costs must land on {@code double}: they arrive as BigDecimal in the
     * manifest, and typing them as string would make every {@code SUM} either fail
     * or coerce silently.
     */
    static String toAthenaType(String manifestType) {
        if (manifestType == null) {
            return "string";
        }
        // Covers both manifest dialects. The legacy one names Java-ish types
        // (BigDecimal, DateTime, OptionalString); the current one names Athena types
        // directly (DOUBLE, TIMESTAMP, STRING). Falling through to "string" for a cost
        // column is the dangerous case — SUM() on a string column fails the query — so
        // every numeric spelling is listed explicitly rather than relying on the default.
        return switch (manifestType.toLowerCase()) {
            case "bigdecimal", "optionalbigdecimal", "decimal", "double" -> "double";
            case "datetime", "optionaldatetime", "timestamp" -> "timestamp";
            case "bigint", "long", "int", "integer" -> "bigint";
            case "boolean" -> "boolean";
            // Interval is a "start/end" pair rendered as text, not a SQL interval.
            default -> "string";
        };
    }

    /** An Athena column. */
    record Column(String name, String type) {
    }

    /**
     * Quotes an identifier for an Athena DDL statement.
     *
     * <p>Backticks rather than the double quotes {@link CurSource#qualifiedTable()}
     * uses: Athena accepts double-quoted identifiers in SELECT but expects backticks
     * in DDL, and mixing them makes CREATE TABLE fail on names that query fine.
     *
     * <p>Identifiers here come from AWS's own manifest and from admin-entered
     * database and table names, but they are interpolated into DDL, so anything that
     * could terminate the quoting is rejected rather than escaped.
     */
    private static String ddlIdent(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_]{1,255}")) {
            throw new IllegalArgumentException("Unsafe Athena identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
