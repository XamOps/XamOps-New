package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the offline logic only. The network lookups are not exercised here — they would
 * make the suite depend on a live public API.
 *
 * <p>Disk tier mapping matters because Azure bills managed disks per provisioned tier
 * (P10, E20, S30...) rather than per GB, so a per-GB rate multiplied by size is
 * structurally the wrong model.
 */
class AzureRetailPricingServiceTest {

    private final AzureRetailPricingService service = new AzureRetailPricingService();

    @ParameterizedTest
    @CsvSource({
            // Premium SSD rounds up to the next tier
            "Premium_LRS,      1,    P1 LRS",
            "Premium_LRS,      4,    P1 LRS",
            "Premium_LRS,      5,    P2 LRS",
            "Premium_LRS,      128,  P10 LRS",
            "Premium_LRS,      200,  P15 LRS",
            "Premium_LRS,      1024, P30 LRS",
            // Standard SSD uses the E series
            "StandardSSD_LRS,  128,  E10 LRS",
            "StandardSSD_LRS,  512,  E20 LRS",
            // Standard HDD uses the S series
            "Standard_LRS,     128,  S10 LRS",
            "Standard_LRS,     2048, S40 LRS",
            // Zone-redundant variants keep the tier but change the suffix
            "Premium_ZRS,      128,  P10 ZRS",
            "StandardSSD_ZRS,  256,  E15 ZRS"
    })
    @DisplayName("maps SKU and size onto the Azure billing tier, rounding up")
    void mapsDiskTiers(String sku, int sizeGb, String expectedTier) {
        assertEquals(expectedTier, service.managedDiskTier(sku, sizeGb));
    }

    @ParameterizedTest
    @CsvSource({
            // Billed on provisioned IOPS and throughput, so a capacity-only tier is wrong.
            "UltraSSD_LRS,   128",
            "Premium_V2_LRS, 128",
            "SomethingElse,  128"
    })
    @DisplayName("returns null for families that are not billed purely on capacity")
    void returnsNullForNonCapacityBilledFamilies(String sku, int sizeGb) {
        assertNull(service.managedDiskTier(sku, sizeGb));
    }

    @Test
    @DisplayName("returns null above the largest published tier rather than guessing")
    void returnsNullAboveLargestTier() {
        assertNull(service.managedDiskTier("Premium_LRS", 40000));
    }

    @Test
    @DisplayName("returns empty rather than a fabricated price for invalid input")
    void returnsEmptyForInvalidInput() {
        assertTrue(service.getVmMonthlyPriceUsd(null, "eastus").isEmpty());
        assertTrue(service.getVmMonthlyPriceUsd("Standard_D2s_v5", null).isEmpty());
        assertTrue(service.getVmMonthlyPriceUsd("  ", "eastus").isEmpty());
        assertTrue(service.getManagedDiskMonthlyPriceUsd("Premium_LRS", 0, "eastus").isEmpty());
        assertTrue(service.getManagedDiskMonthlyPriceUsd("Premium_LRS", -5, "eastus").isEmpty());
        assertTrue(service.getManagedDiskMonthlyPriceUsd(null, 128, "eastus").isEmpty());
    }

    @Test
    @DisplayName("uses a 730-hour month, matching how Azure bills")
    void usesCorrectHoursPerMonth() {
        assertEquals(730.0, AzureRetailPricingService.HOURS_PER_MONTH, 1e-9);
    }
}
