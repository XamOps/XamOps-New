package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit bug 3, in two halves: {@code Quantity.Value()} rounded {@code 250m} up to a whole core, and
 * {@code qty, _ := resource.ParseQuantity(...)} discarded the parse error so an unreadable metric
 * became zero.
 */
class QuantitiesTest {

    @ParameterizedTest
    @CsvSource({
            "250m, 250",     // the measured 4x error: Value() returned 1 core for this
            "1, 1000",
            "0.25, 250",
            "1500m, 1500",
            "2, 2000",
            "1m, 1",
            "0, 0",
            "100m, 100"
    })
    @DisplayName("CPU parses to exact millicores")
    void cpuParsesToMillicores(String quantity, int expectedMillis) {
        assertThat(Quantities.cpuMillis(quantity)).isEqualTo(expectedMillis);
    }

    @ParameterizedTest
    @CsvSource({
            "250Mi, 262144000",
            "1Gi, 1073741824",
            "64Mi, 67108864",
            "128Mi, 134217728",
            "32Mi, 33554432",
            "1Ki, 1024",
            "1000, 1000"
    })
    @DisplayName("memory parses to exact bytes")
    void memoryParsesToBytes(String quantity, long expectedBytes) {
        assertThat(Quantities.memBytes(quantity)).isEqualTo(expectedBytes);
    }

    @Test
    @DisplayName("250m round-trips exactly, which is the whole point")
    void subCoreValuesRoundTrip() {
        assertThat(Quantities.formatCpu(Quantities.cpuMillis("250m"))).isEqualTo("250m");
        assertThat(Quantities.formatMem(Quantities.memBytes("250Mi"))).isEqualTo("250Mi");
        assertThat(Quantities.formatMem(Quantities.memBytes("1Gi"))).isEqualTo("1Gi");
    }

    @Test
    @DisplayName("memory formats to the largest suffix that divides exactly, never an approximation")
    void memoryFormattingIsExactOrPlain() {
        assertThat(Quantities.formatMem(1073741824L)).isEqualTo("1Gi");
        assertThat(Quantities.formatMem(262144000L)).isEqualTo("250Mi");
        assertThat(Quantities.formatMem(1024L)).isEqualTo("1Ki");
        assertThat(Quantities.formatMem(1000L)).isEqualTo("1000");
        assertThat(Quantities.formatMem(0L)).isEqualTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "250x", "250 Mi", "m", "--5", "1.2.3", "Mi250"})
    @DisplayName("an unreadable quantity throws; there is no parseOrDefault and no zero fallback")
    void unreadableQuantitiesThrow(String quantity) {
        assertThatThrownBy(() -> Quantities.cpuMillis(quantity, "the CPU request"))
                .isInstanceOf(QuantityParseException.class)
                .hasMessageContaining("the CPU request");
        assertThatThrownBy(() -> Quantities.memBytes(quantity, "the memory request"))
                .isInstanceOf(QuantityParseException.class)
                .hasMessageContaining("the memory request");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("an absent quantity throws rather than becoming zero")
    void absentQuantitiesThrow(String quantity) {
        assertThatThrownBy(() -> Quantities.cpuMillis(quantity))
                .isInstanceOf(QuantityParseException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("a negative quantity throws: Kubernetes cannot express one")
    void negativeQuantitiesThrow() {
        assertThatThrownBy(() -> Quantities.cpuMillis("-100m"))
                .isInstanceOf(QuantityParseException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("the exception names the field and the offending value, so the message is actionable")
    void theExceptionIsActionable() {
        QuantityParseException failure = org.junit.jupiter.api.Assertions.assertThrows(
                QuantityParseException.class, () -> Quantities.memBytes("250x", "container api memory limit"));

        assertThat(failure.field()).isEqualTo("container api memory limit");
        assertThat(failure.offendingValue()).isEqualTo("250x");
        assertThat(failure).hasMessageContaining("250x");
    }

    @ParameterizedTest
    @CsvSource({
            "1234567n, 1.234567",   // metrics-server nanocores: cpuMillis would flatten this to 1
            "250m, 250.0",
            "2, 2000.0",
            "0.25, 250.0",
            "500u, 0.5",
            "0, 0.0"
    })
    @DisplayName("precise CPU keeps fractional millicores instead of rounding to whole ones")
    void cpuParsesToPreciseMillicores(String quantity, double expectedMillis) {
        assertThat(Quantities.cpuMillisPrecise(quantity, "the CPU usage")).isEqualTo(expectedMillis);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-100m", "abc", "250x"})
    @DisplayName("precise CPU keeps the throw contract: absent, negative and unreadable all throw")
    void preciseCpuThrowsLikeTheRest(String quantity) {
        assertThatThrownBy(() -> Quantities.cpuMillisPrecise(quantity, "the CPU usage"))
                .isInstanceOf(QuantityParseException.class)
                .hasMessageContaining("the CPU usage");
    }

    @Test
    @DisplayName("MiB conversion is used for the wire format and stays exact for whole MiB")
    void mebibyteConversion() {
        assertThat(Quantities.bytesToMiCeil(262144000L)).isEqualTo(250);
        assertThat(Quantities.bytesToMiCeil(1073741824L)).isEqualTo(1024);
        assertThat(Quantities.miToBytes(250)).isEqualTo(262144000L);
    }

    @Test
    @DisplayName("a positive sub-MiB request never publishes as 0")
    void subMebibyteValuesRoundUpRatherThanToZero() {
        // 419430B is the magnitude of the harness's measured p95 for overprovisioned-api. Half-up
        // sent it to 0, and the contract states a 0 never appears -- the UI reads one as a bug and
        // drops the container from savings, Simulate and Apply.
        assertThat(Quantities.bytesToMiCeil(419_430L)).isEqualTo(1);
        assertThat(Quantities.bytesToMiCeil(1L)).isEqualTo(1);
        assertThat(Quantities.bytesToMiCeil(0L)).isZero();

        // Rounding up, not to nearest: a published value is never below the real one, so a current
        // request is never understated and a recommendation is never under-provisioned.
        assertThat(Quantities.bytesToMiCeil(53_739_520L)).isEqualTo(52);
    }
}
