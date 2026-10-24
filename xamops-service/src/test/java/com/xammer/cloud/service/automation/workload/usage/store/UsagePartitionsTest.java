package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Partition;
import com.xammer.cloud.service.automation.workload.usage.store.UsagePartitions.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UsagePartitionsTest {

    @Test
    @DisplayName("the parents are the three partitioned tables of the migration")
    void parents() {
        assertThat(Table.SAMPLE.parent()).isEqualTo("k8s_workload_usage_sample");
        assertThat(Table.HOURLY.parent()).isEqualTo("k8s_workload_usage_hourly");
        assertThat(Table.DAILY.parent()).isEqualTo("k8s_workload_usage_daily");
        assertThat(Table.SAMPLE.dailyPartitions()).isTrue();
        assertThat(Table.HOURLY.dailyPartitions()).isFalse();
        assertThat(Table.DAILY.dailyPartitions()).isFalse();
    }

    @Test
    @DisplayName("names: daily for samples, monthly for hourly/daily, any day of the month gives the month")
    void names() {
        assertThat(UsagePartitions.partitionName(Table.SAMPLE, LocalDate.of(2026, 10, 24)))
                .isEqualTo("k8s_workload_usage_sample_d20261024");
        assertThat(UsagePartitions.partitionName(Table.SAMPLE, LocalDate.of(2027, 1, 3)))
                .isEqualTo("k8s_workload_usage_sample_d20270103");
        assertThat(UsagePartitions.partitionName(Table.HOURLY, LocalDate.of(2026, 10, 24)))
                .isEqualTo("k8s_workload_usage_hourly_y2026m10");
        assertThat(UsagePartitions.partitionName(Table.HOURLY, LocalDate.of(2026, 10, 1)))
                .isEqualTo("k8s_workload_usage_hourly_y2026m10");
        assertThat(UsagePartitions.partitionName(Table.DAILY, LocalDate.of(2027, 2, 28)))
                .isEqualTo("k8s_workload_usage_daily_y2027m02");
    }

    @Test
    @DisplayName("monthly bounds cross the year boundary Dec -> Jan in UTC")
    void monthlyBoundsAcrossTheYear() {
        LocalDate december = LocalDate.of(2026, 12, 17);

        assertThat(UsagePartitions.lowerBound(Table.HOURLY, december)).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(UsagePartitions.upperBound(Table.HOURLY, december)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(UsagePartitions.lowerDate(Table.DAILY, december)).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(UsagePartitions.upperDate(Table.DAILY, december)).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("daily bounds: the last day of a month, Dec 31, and the leap day 2028-02-29")
    void dailyBoundsAtEdges() {
        assertThat(UsagePartitions.upperBound(Table.SAMPLE, LocalDate.of(2026, 10, 31)))
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(UsagePartitions.lowerBound(Table.SAMPLE, LocalDate.of(2026, 12, 31)))
                .isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
        assertThat(UsagePartitions.upperBound(Table.SAMPLE, LocalDate.of(2026, 12, 31)))
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));

        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        assertThat(UsagePartitions.partitionName(Table.SAMPLE, leapDay)).isEqualTo("k8s_workload_usage_sample_d20280229");
        assertThat(UsagePartitions.lowerBound(Table.SAMPLE, leapDay)).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        assertThat(UsagePartitions.upperBound(Table.SAMPLE, leapDay)).isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
        assertThat(UsagePartitions.upperBound(Table.SAMPLE, LocalDate.of(2028, 2, 28)))
                .isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        assertThat(UsagePartitions.upperDate(Table.DAILY, leapDay)).isEqualTo(LocalDate.of(2028, 3, 1));
    }

    @Test
    @DisplayName("periodOf reads the instant in UTC: 23:59:59Z is still the same day")
    void periodOfIsUtc() {
        assertThat(UsagePartitions.periodOf(Table.SAMPLE, Instant.parse("2026-10-24T23:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(UsagePartitions.periodOf(Table.SAMPLE, Instant.parse("2026-10-25T00:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(UsagePartitions.periodOf(Table.HOURLY, Instant.parse("2026-10-31T23:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("createSql: timestamptz bounds carry an explicit +00, daily bounds are plain dates")
    void createSql() {
        assertThat(UsagePartitions.createSql(Table.HOURLY, LocalDate.of(2026, 12, 5)))
                .isEqualTo("CREATE TABLE IF NOT EXISTS k8s_workload_usage_hourly_y2026m12"
                        + " PARTITION OF k8s_workload_usage_hourly"
                        + " FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00')");
        assertThat(UsagePartitions.createSql(Table.SAMPLE, LocalDate.of(2028, 2, 29)))
                .isEqualTo("CREATE TABLE IF NOT EXISTS k8s_workload_usage_sample_d20280229"
                        + " PARTITION OF k8s_workload_usage_sample"
                        + " FOR VALUES FROM ('2028-02-29 00:00:00+00') TO ('2028-03-01 00:00:00+00')");
        assertThat(UsagePartitions.createSql(Table.DAILY, LocalDate.of(2026, 10, 24)))
                .isEqualTo("CREATE TABLE IF NOT EXISTS k8s_workload_usage_daily_y2026m10"
                        + " PARTITION OF k8s_workload_usage_daily"
                        + " FOR VALUES FROM ('2026-10-01') TO ('2026-11-01')");
    }

    @Test
    @DisplayName("parse round-trips every name this class produces")
    void parseRoundTrip() {
        for (Table table : Table.values()) {
            for (LocalDate day : new LocalDate[] {
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1),
                    LocalDate.of(2028, 2, 29)}) {
                String name = UsagePartitions.partitionName(table, day);
                assertThat(UsagePartitions.parse(name))
                        .as(name)
                        .contains(new Partition(table, UsagePartitions.periodStart(table, day)));
                assertThat(UsagePartitions.parse(name).orElseThrow().name()).isEqualTo(name);
            }
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "k8s_workload_usage_hourly_old",
            "k8s_workload_usage_hourly_y2026m13",
            "k8s_workload_usage_hourly_y2026m00",
            "k8s_workload_usage_hourly_y2026m1",
            "k8s_workload_usage_hourly_y2026m10_old",
            "k8s_workload_usage_hourly",
            "k8s_workload_usage_sample_d20260230",
            "k8s_workload_usage_sample_d20270229",
            "k8s_workload_usage_sample_y2026m10",
            "k8s_workload_usage_daily_d20261024",
            "k8s_workload_usage_weekly_y2026m10",
            "K8S_WORKLOAD_USAGE_HOURLY_Y2026M10",
            "public.k8s_workload_usage_hourly_y2026m10",
            "k8s_workload_usage_hourly_y2026m10; DROP TABLE client"
    })
    @DisplayName("parse rejects every name it would not have produced, so foreign tables are never dropped")
    void parseRejectsForeignNames(String name) {
        assertThat(UsagePartitions.parse(name)).isEmpty();
    }
}
