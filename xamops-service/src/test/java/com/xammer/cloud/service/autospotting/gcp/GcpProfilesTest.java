package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.gcp.GcpProfiles.Counts;
import com.xammer.cloud.service.autospotting.gcp.GcpProfiles.ProfileName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The floor N table of design-gcp.md §5.7. */
class GcpProfilesTest {
    private static int spotSharePermille(ProfileName p, int t) {
        return (t - GcpProfiles.floor(p, t, null)) * 1000 / t;
    }

    @Test
    void floorsFollowTheTableAtT4T10AndT40() {
        assertThat(GcpProfiles.floor(ProfileName.CONSERVATIVE, 4, null)).isEqualTo(2);
        assertThat(GcpProfiles.floor(ProfileName.CONSERVATIVE, 10, null)).isEqualTo(5);
        assertThat(GcpProfiles.floor(ProfileName.CONSERVATIVE, 40, null)).isEqualTo(20);
        assertThat(GcpProfiles.floor(ProfileName.BALANCED, 4, null)).isEqualTo(1);
        assertThat(GcpProfiles.floor(ProfileName.BALANCED, 10, null)).isEqualTo(3);
        assertThat(GcpProfiles.floor(ProfileName.BALANCED, 40, null)).isEqualTo(10);
        assertThat(GcpProfiles.floor(ProfileName.AGGRESSIVE, 4, null)).isEqualTo(1);
        assertThat(GcpProfiles.floor(ProfileName.AGGRESSIVE, 40, null)).isEqualTo(1);
        // Spot share: Conservative 50%, Balanced 70% at T = 10, Aggressive 97.5% at T = 40.
        assertThat(spotSharePermille(ProfileName.CONSERVATIVE, 10)).isEqualTo(500);
        assertThat(spotSharePermille(ProfileName.BALANCED, 10)).isEqualTo(700);
        assertThat(spotSharePermille(ProfileName.AGGRESSIVE, 40)).isEqualTo(975);
    }

    @Test
    void nIsCappedAtTMinusOneAndFullSpotIsZero() {
        assertThat(GcpProfiles.floor(ProfileName.CONSERVATIVE, 2, null)).isEqualTo(1);
        assertThat(GcpProfiles.floor(ProfileName.CUSTOM, 5, 9)).isEqualTo(4);
        assertThat(GcpProfiles.floor(ProfileName.FULL_SPOT, 8, null)).isZero();
    }

    @Test
    void customRejectsZeroAndTooLarge() {
        assertThat(GcpProfiles.customValid(6, 0)).isFalse();
        assertThat(GcpProfiles.customValid(6, 6)).isFalse();
        assertThat(GcpProfiles.customValid(6, 3)).isTrue();
        assertThat(GcpProfiles.evaluate(ProfileName.CUSTOM, 6, 0, null, null).blocked()).isTrue();
        assertThat(GcpProfiles.evaluate(ProfileName.CUSTOM, 6, 3, null, null).floorN()).isEqualTo(3);
    }

    @Test
    void sizeOneOrLessBlocksEveryProfile() {
        assertThat(GcpProfiles.evaluateNamed(1, null, null))
                .allMatch(e -> e.blocked() && "GCP_SIZE_TOO_SMALL".equals(e.blockReason()));
        assertThat(GcpProfiles.evaluateNamed(2, null, null)).noneMatch(GcpProfiles.Evaluated::blocked);
    }

    @Test
    void countsAreShownAtCurrentSizeAndAtAutoscalerMinAndMax() {
        GcpProfiles.Evaluated e = GcpProfiles.evaluateNamed(10, 2, 30).get(0); // Conservative: N = 5
        assertThat(e.floorN()).isEqualTo(5);
        assertThat(e.atCurrent()).isEqualTo(new Counts(5, 5));
        assertThat(e.atMin()).as("below N every VM is standard").isEqualTo(new Counts(2, 0));
        assertThat(e.atMax()).isEqualTo(new Counts(5, 25));
        assertThat(e.preselected()).isTrue();
        assertThat(GcpProfiles.evaluateNamed(10, null, null).get(0).atMin()).isNull();
    }

    @Test
    void conservativeStaysPreselectedAndUnknownProfilesFailClosed() {
        assertThat(GcpProfiles.preselectedDefault()).isEqualTo(ProfileName.CONSERVATIVE);
        assertThat(GcpProfiles.parse("full-spot")).isEqualTo(ProfileName.FULL_SPOT);
        assertThat(GcpProfiles.parse("nonsense")).isNull();
    }
}
