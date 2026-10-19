package com.xammer.cloud.service.automation.workload.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two confidence-volatility query builders, asserted as literal strings.
 *
 * <p>Pure string building, so the PromQL is a value: the mean and stddev must be taken over the
 * <strong>identical</strong> {@code rate(...[5m])} subquery the percentile uses, or the coefficient
 * of variation describes a differently-windowed rate.
 */
class PromQlTest {

    private static final PromQl.Selector SELECTOR = new PromQl.Selector(
            "team-a", List.of("api-abc-1"), List.of("api"));

    @Test
    @DisplayName("cpuMeanMillis is avg_over_time over the same rate subquery, times 1000")
    void cpuMeanMirrorsThePercentileSubquery() {
        String mean = PromQl.cpuMeanMillis(SELECTOR, 24, 120);

        assertThat(mean).isEqualTo(
                "max by (container) (avg_over_time(rate(container_cpu_usage_seconds_total{"
                        + "namespace=\"team-a\",pod=~\"api-abc-1\",container=~\"api\"}[5m])"
                        + "[24h:] offset 120s)) * 1000");
    }

    @Test
    @DisplayName("cpuStddevMillis is stddev_over_time over the same rate subquery, times 1000")
    void cpuStddevMirrorsThePercentileSubquery() {
        String stddev = PromQl.cpuStddevMillis(SELECTOR, 24, 120);

        assertThat(stddev).isEqualTo(
                "max by (container) (stddev_over_time(rate(container_cpu_usage_seconds_total{"
                        + "namespace=\"team-a\",pod=~\"api-abc-1\",container=~\"api\"}[5m])"
                        + "[24h:] offset 120s)) * 1000");
    }

    @Test
    @DisplayName("both share the percentile's rate window and subquery shape, and stay per-container")
    void bothShareThePercentileForm() {
        String percentile = PromQl.cpuPercentileMillis(SELECTOR, 95, 24, 120);
        String mean = PromQl.cpuMeanMillis(SELECTOR, 24, 120);
        String stddev = PromQl.cpuStddevMillis(SELECTOR, 24, 120);

        for (String query : List.of(mean, stddev)) {
            assertThat(query).contains("rate(container_cpu_usage_seconds_total{");
            assertThat(query).contains("[5m])[24h:] offset 120s");
            assertThat(query).contains("by (container)");
            assertThat(query).endsWith("* 1000");
        }
        // The subquery shape after the rate(...) is byte-identical to the percentile's.
        String subquery = "[5m])[24h:] offset 120s";
        assertThat(percentile).contains(subquery);
        assertThat(mean).contains(subquery);
        assertThat(stddev).contains(subquery);
    }

    @Test
    @DisplayName("requestRate renders the configured metric summed by container over the same subquery")
    void requestRateRendersTheConfiguredMetric() {
        String rps = PromQl.requestRate(SELECTOR, "http_requests_total", 24, 120);

        assertThat(rps).isEqualTo(
                "sum by (container) (rate(http_requests_total{"
                        + "namespace=\"team-a\",pod=~\"api-abc-1\",container=~\"api\"}[5m])"
                        + "[24h:] offset 120s)");
    }

    @Test
    @DisplayName("requestRate reuses the same selector/offset discipline as the other builders")
    void requestRateSharesTheSelectorAndOffset() {
        String rps = PromQl.requestRate(SELECTOR, "http_requests_total", 24, 120);

        assertThat(rps).contains("namespace=\"team-a\"");
        assertThat(rps).contains("pod=~\"api-abc-1\"");
        assertThat(rps).contains("container=~\"api\"");
        assertThat(rps).contains("by (container)");
        assertThat(rps).contains("[5m])[24h:] offset 120s");
        assertThat(rps)
                .as("a name-prefix regex is how one workload claims another's series")
                .doesNotContain(".*");
    }
}
