package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.aws.AwsProfiles.BlockReason;
import com.xammer.cloud.service.autospotting.aws.AwsProfiles.EvaluatedProfile;
import com.xammer.cloud.service.autospotting.aws.AwsProfiles.ProfileName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** plan-phase-12.md 12.4: the PO-18 table (design-aws.md §3.1) is read from one place and the counts match it. */
class AwsProfilesTest {

    private static EvaluatedProfile at(int desired, ProfileName name) {
        return AwsProfiles.evaluateNamed(desired).stream().filter(p -> p.name() == name).findFirst().orElseThrow();
    }

    @Test
    void theTableIsConservative50Balanced25Aggressive0WithOneOnDemandBase() {
        assertThat(AwsProfiles.byName(ProfileName.CONSERVATIVE).base()).isEqualTo(1);
        assertThat(AwsProfiles.byName(ProfileName.CONSERVATIVE).percentAboveBase()).isEqualTo(50);
        assertThat(AwsProfiles.byName(ProfileName.BALANCED).percentAboveBase()).isEqualTo(25);
        assertThat(AwsProfiles.byName(ProfileName.AGGRESSIVE).percentAboveBase()).isZero();
        assertThat(AwsProfiles.namedProfiles()).allMatch(p -> p.base() == 1 && !p.optIn());
        assertThat(AwsProfiles.fullSpot().base()).isZero();
        assertThat(AwsProfiles.fullSpot().optIn()).isTrue();
    }

    @Test
    void desiredTwoBlocksConservativeAndBalancedButNotAggressive() {
        assertThat(at(2, ProfileName.CONSERVATIVE).blockReason()).isEqualTo(BlockReason.PROFILE_NO_SPOT);
        assertThat(at(2, ProfileName.BALANCED).blockReason()).isEqualTo(BlockReason.PROFILE_NO_SPOT);
        assertThat(at(2, ProfileName.AGGRESSIVE).blockReason()).isEqualTo(BlockReason.NONE);
        assertThat(at(2, ProfileName.AGGRESSIVE).spotCount()).isEqualTo(1);
        // The default is blocked, so nothing is preselected (PD-03).
        assertThat(AwsProfiles.preselectedAt(2)).isEmpty();
    }

    @Test
    void spotCountsAtDesired4_10_40MatchTheTable() {
        assertThat(List.of(at(4, ProfileName.CONSERVATIVE).spotCount(), at(4, ProfileName.BALANCED).spotCount(),
                at(4, ProfileName.AGGRESSIVE).spotCount())).containsExactly(1, 2, 3);
        assertThat(List.of(at(10, ProfileName.CONSERVATIVE).spotCount(), at(10, ProfileName.BALANCED).spotCount(),
                at(10, ProfileName.AGGRESSIVE).spotCount())).containsExactly(4, 6, 9);
        assertThat(List.of(at(40, ProfileName.CONSERVATIVE).spotCount(), at(40, ProfileName.BALANCED).spotCount(),
                at(40, ProfileName.AGGRESSIVE).spotCount())).containsExactly(19, 29, 39);
    }

    @Test
    void desiredAtOrBelowOneBlocksEveryProfile() {
        for (int desired : new int[] {0, 1}) {
            assertThat(AwsProfiles.evaluateNamed(desired))
                    .allMatch(p -> p.blockReason() == BlockReason.DESIRED_TOO_SMALL && p.spotCount() == 0);
            assertThat(AwsProfiles.preselectedAt(desired)).isEmpty();
        }
    }

    @Test
    void conservativeIsPreselectedWhenNotBlocked() {
        assertThat(AwsProfiles.preselectedDefault()).isEqualTo(ProfileName.CONSERVATIVE);
        assertThat(AwsProfiles.preselectedAt(4)).contains(ProfileName.CONSERVATIVE);
        assertThat(at(4, ProfileName.CONSERVATIVE).preselected()).isTrue();
        assertThat(at(4, ProfileName.BALANCED).preselected()).isFalse();
    }

    @Test
    void spotPlusOnDemandEqualsDesired() {
        for (int d = 2; d <= 60; d++) {
            for (EvaluatedProfile p : AwsProfiles.evaluateNamed(d)) {
                assertThat(p.spotCount() + p.onDemandCount()).isEqualTo(d);
            }
        }
    }

    @Test
    void customClampsThePercent() {
        assertThat(AwsProfiles.custom(1, 150).percentAboveBase()).isEqualTo(100);
        assertThat(AwsProfiles.custom(1, -5).percentAboveBase()).isZero();
        assertThat(AwsProfiles.spotCount(10, 3, 0)).isEqualTo(7);
    }
}
