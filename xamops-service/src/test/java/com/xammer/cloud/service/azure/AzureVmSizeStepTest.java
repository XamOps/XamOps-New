package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the downsize step so it only proposes sizes Azure actually sells.
 *
 * <p>Halving the vCPU count arithmetically produced names that do not exist —
 * {@code Standard_D1s_v5}, {@code Standard_E1s_v5} — and an odd count halved to something that
 * never existed at all. Each of those became a recommendation whose resize call fails at apply
 * time, which is worse than no recommendation.
 */
class AzureVmSizeStepTest {

    @Test
    @DisplayName("a standard halving that lands on a real size is proposed")
    void realHalvingIsProposed() {
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D8s_v5"))
                .contains("Standard_D4s_v5");
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D4s_v5"))
                .contains("Standard_D2s_v5");
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_E16ds_v5"))
                .contains("Standard_E8ds_v5");
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D64as_v5"))
                .contains("Standard_D32as_v5");
    }

    @Test
    @DisplayName("modifiers and version are preserved across the step")
    void preservesModifiersAndVersion() {
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D8as_v5"))
                .contains("Standard_D8as_v5".replace("8", "4"));
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_E32ads_v5"))
                .contains("Standard_E16ads_v5");
    }

    @Test
    @DisplayName("a 2 vCPU size has no smaller step in these families")
    void twoVcpuIsTheFloor() {
        // Halving 2 gives 1, and there is no Standard_D1s_v5.
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D2s_v5")).isEmpty();
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_E2s_v5")).isEmpty();
    }

    @Test
    @DisplayName("a halving that lands on a size Azure does not publish is refused")
    void unpublishedStepIsRefused() {
        // 3 halves to 1 — not a purchasable size.
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D3_v2")).isEmpty();
        // 6 halves to 3, which is not in Azure's vCPU steps.
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D6_v3")).isEmpty();
    }

    @Test
    @DisplayName("40 halves to 20, which Azure does publish")
    void nonPowerOfTwoStepThatExists() {
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_D40s_v5"))
                .contains("Standard_D20s_v5");
    }

    @Test
    @DisplayName("unparseable or non-Standard names are refused")
    void malformedNamesAreRefused() {
        assertThat(AzureRightsizingService.nextSmallerSku(null)).isEmpty();
        assertThat(AzureRightsizingService.nextSmallerSku("")).isEmpty();
        assertThat(AzureRightsizingService.nextSmallerSku("Basic_A1")).isEmpty();
        assertThat(AzureRightsizingService.nextSmallerSku("Standard_NoDigits")).isEmpty();
    }

    @Test
    @DisplayName("every proposed size differs from the input")
    void neverProposesTheSameSize() {
        for (String size : new String[] {
                "Standard_D8s_v5", "Standard_D4s_v5", "Standard_E16ds_v5", "Standard_D64as_v5" }) {
            Optional<String> smaller = AzureRightsizingService.nextSmallerSku(size);
            assertThat(smaller).isPresent();
            assertThat(smaller.get()).isNotEqualTo(size);
        }
    }
}
