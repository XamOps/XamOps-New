package com.xammer.cloud.service.automation.workload.usage.store;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names, bounds and DDL of the usage-rollup partitions. Pure: no Spring, no database.
 *
 * <p>A partition is identified by its {@link Table} and a <em>period</em>, given as any
 * {@link LocalDate} inside it: the UTC day for {@link Table#SAMPLE}, the UTC month for
 * {@link Table#HOURLY} and {@link Table#DAILY} (normalised to its first day).
 *
 * <ul>
 *   <li>{@code k8s_workload_usage_sample_dYYYYMMDD}: one UTC day</li>
 *   <li>{@code k8s_workload_usage_hourly_yYYYYmMM} / {@code k8s_workload_usage_daily_yYYYYmMM}:
 *       one UTC month</li>
 * </ul>
 *
 * <p>Names are derived only from dates, never from input text, and {@link #parse(String)} accepts
 * only names this class would itself produce, so a partition somebody else attached (or a renamed
 * {@code _old} copy) is never mistaken for one of ours and never dropped.
 */
public final class UsagePartitions {

    /** The three partitioned parents. */
    public enum Table {
        SAMPLE("k8s_workload_usage_sample", true),
        HOURLY("k8s_workload_usage_hourly", false),
        DAILY("k8s_workload_usage_daily", false);

        private final String parent;
        private final boolean dailyPartitions;

        Table(String parent, boolean dailyPartitions) {
            this.parent = parent;
            this.dailyPartitions = dailyPartitions;
        }

        /** The partitioned parent table's name. */
        public String parent() {
            return parent;
        }

        /** True when each partition is one UTC day, false when it is one UTC month. */
        public boolean dailyPartitions() {
            return dailyPartitions;
        }

        /** True when the partition key is a {@code DATE} rather than a {@code TIMESTAMPTZ}. */
        public boolean dateKeyed() {
            return this == DAILY;
        }
    }

    /** A parsed partition: its table and the first day of its period. */
    public record Partition(Table table, LocalDate period) {
        public Partition {
            Objects.requireNonNull(table, "table");
            period = periodStart(table, period);
        }

        public String name() {
            return partitionName(table, period);
        }
    }

    private static final Pattern SAMPLE_NAME =
            Pattern.compile("k8s_workload_usage_sample_d(\\d{4})(\\d{2})(\\d{2})");
    private static final Pattern MONTHLY_NAME =
            Pattern.compile("k8s_workload_usage_(hourly|daily)_y(\\d{4})m(\\d{2})");

    private UsagePartitions() {
    }

    /** The first day of the period containing {@code day}. */
    public static LocalDate periodStart(Table table, LocalDate day) {
        Objects.requireNonNull(day, "period");
        return table.dailyPartitions() ? day : day.withDayOfMonth(1);
    }

    /** The first day after the period containing {@code day} (exclusive upper bound). */
    public static LocalDate periodEnd(Table table, LocalDate day) {
        LocalDate start = periodStart(table, day);
        return table.dailyPartitions() ? start.plusDays(1) : start.plusMonths(1);
    }

    /** The period (as a UTC day) containing {@code instant}. */
    public static LocalDate periodOf(Table table, Instant instant) {
        return periodStart(table, LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    public static String partitionName(Table table, LocalDate day) {
        LocalDate start = periodStart(table, day);
        if (table.dailyPartitions()) {
            return String.format("%s_d%04d%02d%02d", table.parent(),
                    start.getYear(), start.getMonthValue(), start.getDayOfMonth());
        }
        return String.format("%s_y%04dm%02d", table.parent(), start.getYear(), start.getMonthValue());
    }

    /** Inclusive lower bound as a UTC instant (midnight UTC of the period's first day). */
    public static Instant lowerBound(Table table, LocalDate day) {
        return periodStart(table, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive upper bound as a UTC instant. */
    public static Instant upperBound(Table table, LocalDate day) {
        return periodEnd(table, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Inclusive lower bound as a date (the bound {@link Table#DAILY} partitions are declared with). */
    public static LocalDate lowerDate(Table table, LocalDate day) {
        return periodStart(table, day);
    }

    /** Exclusive upper bound as a date. */
    public static LocalDate upperDate(Table table, LocalDate day) {
        return periodEnd(table, day);
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS <name> PARTITION OF <parent> FOR VALUES FROM (..) TO (..)},
     * with DATE bounds for {@link Table#DAILY} and explicit {@code +00} timestamptz bounds otherwise,
     * so the session time zone never shifts a boundary.
     */
    public static String createSql(Table table, LocalDate day) {
        String from;
        String to;
        if (table.dateKeyed()) {
            from = lowerDate(table, day).toString();
            to = upperDate(table, day).toString();
        } else {
            from = lowerDate(table, day) + " 00:00:00+00";
            to = upperDate(table, day) + " 00:00:00+00";
        }
        return "CREATE TABLE IF NOT EXISTS " + partitionName(table, day) + " PARTITION OF " + table.parent()
                + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')";
    }

    /**
     * The partition a name denotes, or empty when the name is not exactly one this class produces
     * (wrong prefix, suffix, digit count, or an impossible date such as month 13).
     */
    public static Optional<Partition> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            Matcher sample = SAMPLE_NAME.matcher(name);
            if (sample.matches()) {
                return roundTrip(name, new Partition(Table.SAMPLE, LocalDate.of(
                        Integer.parseInt(sample.group(1)),
                        Integer.parseInt(sample.group(2)),
                        Integer.parseInt(sample.group(3)))));
            }
            Matcher monthly = MONTHLY_NAME.matcher(name);
            if (monthly.matches()) {
                Table table = "hourly".equals(monthly.group(1)) ? Table.HOURLY : Table.DAILY;
                return roundTrip(name, new Partition(table, LocalDate.of(
                        Integer.parseInt(monthly.group(2)),
                        Integer.parseInt(monthly.group(3)),
                        1)));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Partition> roundTrip(String name, Partition partition) {
        return partition.name().equals(name) ? Optional.of(partition) : Optional.empty();
    }
}
