package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The downsize target used to come from a 15-entry hardcoded map covering only B/D/E/F
 * v5 and v2 sizes; everything else produced a "Smaller SKU" placeholder with zero
 * savings. Derivation must preserve family, modifiers and version, because those encode
 * AMD vs Intel, premium storage support, and the generation.
 */
class AzureVmSizeDerivationTest {

    @ParameterizedTest
    @CsvSource({
            // Original hardcoded map entries must still behave identically
            "Standard_D8s_v5,    Standard_D4s_v5",
            "Standard_D16s_v5,   Standard_D8s_v5",
            "Standard_E16s_v5,   Standard_E8s_v5",
            "Standard_F16s_v2,   Standard_F8s_v2",
            "Standard_B4ms,      Standard_B2ms",
            // Generations the map never covered
            "Standard_D4s_v3,    Standard_D2s_v3",
            "Standard_D8s_v4,    Standard_D4s_v4",
            "Standard_D16s_v6,   Standard_D8s_v6",
            // AMD families the map never covered
            "Standard_D8as_v5,   Standard_D4as_v5",
            "Standard_E16as_v5,  Standard_E8as_v5",
            // Memory- and storage-optimised
            "Standard_M32ms,     Standard_M16ms",
            "Standard_L16s_v3,   Standard_L8s_v3",
            // Local-disk modifier preserved
            "Standard_D8ds_v5,   Standard_D4ds_v5",
            // No modifiers at all
            "Standard_D4_v3,     Standard_D2_v3"
    })
    @DisplayName("halves the vCPU count while preserving family, modifiers and version")
    void derivesNextSmallerSku(String current, String expected) {
        assertEquals(Optional.of(expected), AzureRightsizingService.nextSmallerSku(current));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Standard_D1_v2",   // already the smallest step
            "Standard_B1s",
            "Standard_B1ms"
    })
    @DisplayName("returns empty at the 1-vCPU floor rather than proposing a smaller size")
    void stopsAtSmallestSize(String size) {
        assertTrue(AzureRightsizingService.nextSmallerSku(size).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Basic_A0",          // not a Standard_ size
            "NotASize",
            "Standard_",
            "Standard_NoDigits",
            ""
    })
    @DisplayName("returns empty for unparseable names instead of guessing")
    void returnsEmptyForUnparseable(String size) {
        assertTrue(AzureRightsizingService.nextSmallerSku(size).isEmpty());
    }

    @Test
    @DisplayName("returns empty for null")
    void returnsEmptyForNull() {
        assertTrue(AzureRightsizingService.nextSmallerSku(null).isEmpty());
    }

    @Test
    @DisplayName("derivation is repeatable, so a family can be stepped down more than once")
    void supportsRepeatedStepDown() {
        Optional<String> first = AzureRightsizingService.nextSmallerSku("Standard_D16s_v5");
        assertEquals(Optional.of("Standard_D8s_v5"), first);
        Optional<String> second = AzureRightsizingService.nextSmallerSku(first.orElseThrow());
        assertEquals(Optional.of("Standard_D4s_v5"), second);
    }
}
