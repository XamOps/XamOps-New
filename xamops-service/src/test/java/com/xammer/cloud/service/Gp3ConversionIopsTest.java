package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Volume;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the IOPS carried across a gp2 to gp3 conversion.
 *
 * <p>gp3 provides 3,000 IOPS unless asked for more, while gp2 scales at 3 IOPS per GiB up to
 * 16,000. Converting with only a {@code volumeType} change therefore cut a large gp2 volume's
 * performance — a 5,334 GiB gp2 running at 16,000 IOPS landed on 3,000 — and it was presented
 * to the customer as a saving with no mention of the regression.
 */
class Gp3ConversionIopsTest {

    private static Volume gp2(int sizeGb, Integer reportedIops) {
        Volume.Builder b = Volume.builder().volumeId("vol-1").size(sizeGb).volumeType("gp2");
        if (reportedIops != null) {
            b.iops(reportedIops);
        }
        return b.build();
    }

    @Test
    @DisplayName("a small volume keeps the gp3 baseline, since gp2 gave it less")
    void smallVolumeKeepsBaseline() {
        // 100 GiB gp2 = 300 IOPS, below gp3's free 3,000, so no explicit request is needed.
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(100, 300))).isEqualTo(3000);
    }

    @Test
    @DisplayName("a volume above the baseline has its IOPS preserved")
    void largeVolumePreservesIops() {
        // 2,000 GiB gp2 = 6,000 IOPS. Without this the conversion would drop it to 3,000.
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(2000, 6000))).isEqualTo(6000);
    }

    @Test
    @DisplayName("IOPS are capped at gp3's maximum")
    void cappedAtGp3Maximum() {
        // 10,000 GiB gp2 would derive 30,000, but gp3 tops out at 16,000.
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(10000, 16000))).isEqualTo(16000);
    }

    @Test
    @DisplayName("size derives the rate when the reported value is missing or understated")
    void derivesFromSizeWhenReportedIsAbsent() {
        // gp2 does not always report provisioned IOPS usefully, so size is the fallback:
        // 4,000 GiB x 3 = 12,000.
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(4000, null))).isEqualTo(12000);
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(4000, 100))).isEqualTo(12000);
    }

    @Test
    @DisplayName("the higher of reported and derived wins")
    void higherOfReportedAndDerived() {
        // A volume whose reported IOPS exceed the size-derived figure keeps the reported one.
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(gp2(500, 9000))).isEqualTo(9000);
    }

    @Test
    @DisplayName("a null volume falls back to the baseline rather than throwing")
    void nullVolumeIsSafe() {
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(null)).isEqualTo(3000);
    }

    @Test
    @DisplayName("a volume with no size reported falls back to the baseline")
    void missingSizeIsSafe() {
        Volume noSize = Volume.builder().volumeId("vol-2").volumeType("gp2").build();
        assertThat(EbsDiskRightsizingService.gp3IopsToPreserve(noSize)).isEqualTo(3000);
    }
}
