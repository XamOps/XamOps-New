package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CurSource;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * SQL fragments shared by every CUR query.
 *
 * <p>Centralised because the partition predicate is not optional detail — it is
 * the whole cost model for the feature. A query without it scans the entire
 * table, and a year of hourly resource-level CUR is gigabytes per query rather
 * than the tens of megabytes a pruned monthly aggregate touches. Having exactly
 * one implementation means a new query cannot quietly omit it.
 */
final class CurSql {

    private CurSql() {
    }

    /**
     * Restricts the scan to the partitions covering {@code start}..{@code end}.
     *
     * <p>Legacy CUR crawled by Glue partitions on {@code year}/{@code month};
     * Data Exports (CUR 2.0) partitions on {@code billing_period} (YYYY-MM).
     *
     * <p>The range is widened by one month at each end deliberately: usage sitting
     * on a month boundary is billed to the adjacent period, so a partition filter
     * matching the requested months exactly would drop rows that the usage-date
     * predicate would otherwise have kept.
     */
    static String partitionPredicate(CurSource source, LocalDate start, LocalDate end) {
        return partitionPredicate(source, YearMonth.from(start).minusMonths(1),
                YearMonth.from(end).plusMonths(1));
    }

    /** Partition predicate over an explicit month range, inclusive at both ends. */
    static String partitionPredicate(CurSource source, YearMonth from, YearMonth to) {
        if ("cur2".equalsIgnoreCase(source.getCurVersion())) {
            return "billing_period BETWEEN '" + from + "' AND '" + to + "'";
        }

        // Glue's crawler types year/month as strings, and month is not zero-padded.
        // Comparing a zero-padded concatenation keeps this a simple range the
        // partition pruner can still use.
        String fromKey = String.format("%04d%02d", from.getYear(), from.getMonthValue());
        String toKey = String.format("%04d%02d", to.getYear(), to.getMonthValue());
        return "concat(year, lpad(month, 2, '0')) BETWEEN '" + fromKey + "' AND '" + toKey + "'";
    }

    /**
     * Usage start as a timestamp, in whatever form this report's table holds it.
     *
     * <p>A Parquet table declares the column {@code timestamp}, because the manifest
     * types it TIMESTAMP — so it is already one and needs no conversion. The earlier
     * {@code from_iso8601_timestamp(CAST(col AS varchar))} was written for a string
     * column and fails outright on a real timestamp: casting one to varchar in Trino
     * yields {@code 2026-09-03 07:00:00.000}, with a space where ISO-8601 wants a T, and
     * the parse rejects it —
     * {@code INVALID_FUNCTION_ARGUMENT: Invalid format ... malformed at " 07:00:00.000"}.
     *
     * <p>A CSV table is all-string under OpenCSVSerde, where CUR does write ISO-8601, so
     * that form still needs parsing. Normalised back to a plain {@code timestamp} so both
     * branches compare against the same literal type.
     */
    static String usageDateExpr(CurSource source) {
        if (isCsv(source)) {
            return "CAST(from_iso8601_timestamp(line_item_usage_start_date) AS timestamp)";
        }
        return "line_item_usage_start_date";
    }

    /**
     * Usage falling within {@code start}..{@code end}, inclusive of the end date.
     *
     * <p>Half-open on the upper bound so the final day is included whole whatever
     * the report's time unit — an HOURLY report has rows at 23:00 on the end date.
     *
     * <p>Bounds are {@code TIMESTAMP} literals rather than parsed strings: both sides of
     * the comparison are then plain timestamps, with no implicit coercion between
     * {@code timestamp} and {@code timestamp with time zone} to reason about.
     */
    /**
     * <p>The end date is honoured as given, deliberately — it is <em>not</em> capped at
     * today for a current-month range.
     *
     * <p>Capping looks right and is wrong. Not every line item is dated on the day the
     * charge is incurred: reservation and Savings Plan recurring fees, support charges
     * and tax are written with usage dates spanning the whole billing period as soon as
     * that period opens. They are already on the bill. Excluding them because their date
     * is in the future therefore understates what the account has actually been charged.
     *
     * <p>Measured on a live report for 1–8 September: the full-period range returned
     * $2,254, the range capped at today returned $934, and $2,254 is the figure that
     * matches the bill. The missing $1,320 was period-spanning fee rows, not charges
     * that had yet to happen.
     *
     * <p>This is a real and intended difference from the Cost Explorer path, which
     * returns only completed days regardless of the end date passed
     * ({@code CostService.normalizeEndDate} relies on that). The two sources will not
     * agree to the cent on an open month; only a closed month is a fair comparison,
     * which is why {@code CurReconciliationService} refuses to run on the current one.
     */
    static String usageDatePredicate(CurSource source, LocalDate start, LocalDate end) {
        String expr = usageDateExpr(source);
        return expr + " >= TIMESTAMP '" + start + " 00:00:00'"
                + " AND " + expr + " < TIMESTAMP '" + end.plusDays(1) + " 00:00:00'";
    }

    /**
     * A numeric column, cast when the report's serde cannot type it.
     *
     * <p>Parquet carries real types, so a cost column arrives as a double and
     * {@code SUM} works directly. A CSV report is read through OpenCSVSerde, which
     * has no type support at all and returns every column as a string — summing one
     * without a cast fails outright. Routing every numeric reference through here is
     * what lets one set of queries serve both formats.
     */
    static String numericExpr(CurSource source, String column) {
        if (isCsv(source)) {
            // try_cast, not cast: a CSV CUR has empty strings where a value is absent,
            // and a hard cast would fail the whole query on the first one.
            return "COALESCE(try_cast(" + column + " AS double), 0)";
        }
        return column;
    }

    /** True when the report is delivered as CSV rather than Parquet. */
    static boolean isCsv(CurSource source) {
        return source.getFormat() != null && source.getFormat().toLowerCase().startsWith("csv");
    }
}
