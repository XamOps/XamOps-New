package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cores/GiB reads that feed the cost-weight split basis (FEAT-001 §3.3).
 *
 * <p>These are the {@code double} views used only as the {@code w_cpu : w_mem} ratio basis — passing
 * millis/bytes there would blow the memory term up by ~10^9 (the HIGH-1 trap). The point of a
 * dedicated conversion is that it keeps the same throw-on-unreadable contract as the millis/bytes
 * reads, so an unreadable {@code status.capacity} becomes {@code NODE_CAPACITY_UNKNOWN}, never a 0.
 */
class QuantitiesCoresGiBTest {

    @Test
    @DisplayName("cpuCores: whole cores and millicores both read as cores")
    void cpuCoresReadsCores() {
        assertThat(Quantities.cpuCores("2", "cpu")).isEqualTo(2.0);
        assertThat(Quantities.cpuCores("250m", "cpu")).isEqualTo(0.25);
    }

    @Test
    @DisplayName("memGiB: binary suffixes read as GiB")
    void memGiBReadsGiB() {
        assertThat(Quantities.memGiB("4Gi", "memory")).isEqualTo(4.0);
        assertThat(Quantities.memGiB("512Mi", "memory")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("blank, garbage and negative all throw rather than defaulting to 0")
    void unreadableThrows() {
        assertThatThrownBy(() -> Quantities.cpuCores("  ", "cpu"))
                .isInstanceOf(QuantityParseException.class);
        assertThatThrownBy(() -> Quantities.cpuCores("not-a-quantity", "cpu"))
                .isInstanceOf(QuantityParseException.class);
        assertThatThrownBy(() -> Quantities.cpuCores("-1", "cpu"))
                .isInstanceOf(QuantityParseException.class);
        assertThatThrownBy(() -> Quantities.memGiB("", "memory"))
                .isInstanceOf(QuantityParseException.class);
        assertThatThrownBy(() -> Quantities.memGiB("garbage", "memory"))
                .isInstanceOf(QuantityParseException.class);
        assertThatThrownBy(() -> Quantities.memGiB("-4Gi", "memory"))
                .isInstanceOf(QuantityParseException.class);
    }
}
