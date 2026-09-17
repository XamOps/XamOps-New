package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Azure year-over-year calculation.
 *
 * <p>{@code yoyChange} was previously assigned a literal {@code 0.0} on every path, including the
 * success path, so the FinOps report always claimed spend was exactly flat year on year. These tests
 * pin the real comparison and, just as importantly, pin the refusal to report a number when there is
 * nothing to compare against — the common case, since the calculation needs thirteen months of
 * history.
 *
 * <p>{@code computeYoyChange} is a pure function of the cached history, so it is exercised directly;
 * the surrounding report method needs Redis and four collaborators.
 */
class AzureYoyChangeTest {

    /** The method under test touches no injected collaborator. */
    private final AzureFinOpsService service = new AzureFinOpsService();

    private static List<Boolean> notForecast(int n) {
        Boolean[] flags = new Boolean[n];
        Arrays.fill(flags, Boolean.FALSE);
        return Arrays.asList(flags);
    }

    @Test
    @DisplayName("compares the latest closed month against the same month a year earlier")
    void computesRealYoy() {
        List<String> labels = List.of("Jan 2025", "Feb 2025", "Jan 2026");
        List<Double> costs = List.of(100.0, 500.0, 150.0);

        Double yoy = service.computeYoyChange(labels, costs, notForecast(3));

        // Jan 2026 (150) vs Jan 2025 (100) = +50%. Feb 2025 is the largest value in the series and
        // must not be picked up as the comparison.
        assertThat(yoy).isEqualTo(50.0);
    }

    @Test
    @DisplayName("reports a decrease as a negative percentage")
    void computesDecrease() {
        List<String> labels = List.of("Mar 2025", "Mar 2026");
        List<Double> costs = List.of(200.0, 150.0);

        assertThat(service.computeYoyChange(labels, costs, notForecast(2))).isEqualTo(-25.0);
    }

    @Test
    @DisplayName("returns null when the year-earlier month is absent")
    void nullWithoutComparison() {
        List<String> labels = List.of("Nov 2025", "Dec 2025", "Jan 2026");
        List<Double> costs = List.of(100.0, 110.0, 120.0);

        // No Jan 2025, so there is no answer. Zero would read as "spend is flat".
        assertThat(service.computeYoyChange(labels, costs, notForecast(3))).isNull();
    }

    @Test
    @DisplayName("ignores forecast rows when choosing the latest closed month")
    void ignoresForecastRows() {
        List<String> labels = List.of("Jan 2025", "Jan 2026", "Feb 2026 (Forecast)");
        List<Double> costs = List.of(100.0, 130.0, 900.0);
        List<Boolean> flags = List.of(false, false, true);

        // The forecast row is both the latest and the largest; using it would report +800%.
        assertThat(service.computeYoyChange(labels, costs, flags)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("returns null when the year-earlier month was itself zero")
    void nullOnDivideByZero() {
        List<String> labels = List.of("Jan 2025", "Jan 2026");
        List<Double> costs = List.of(0.0, 150.0);

        // Percentage change from zero is undefined, not infinite and not 0.
        assertThat(service.computeYoyChange(labels, costs, notForecast(2))).isNull();
    }

    @Test
    @DisplayName("returns null on malformed history rather than guessing")
    void nullOnMalformedHistory() {
        assertThat(service.computeYoyChange(null, List.of(1.0), notForecast(1))).isNull();
        assertThat(service.computeYoyChange(List.of("Jan 2026"), null, notForecast(1))).isNull();
        // Mismatched lengths — the caller's own guard, asserted here too.
        assertThat(service.computeYoyChange(List.of("Jan 2025", "Jan 2026"), List.of(100.0),
                notForecast(2))).isNull();
    }

    @Test
    @DisplayName("unparseable month labels are skipped, not treated as the current month")
    void skipsUnparseableLabels() {
        List<String> labels = List.of("not-a-month", "Jan 2025", "Jan 2026");
        List<Double> costs = List.of(999.0, 100.0, 120.0);

        assertThat(service.computeYoyChange(labels, costs, notForecast(3))).isEqualTo(20.0);
    }
}
