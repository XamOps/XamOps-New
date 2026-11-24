package com.xammer.cloud.service.autospotting.aws;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static com.xammer.cloud.service.autospotting.aws.StackCapabilities.AS_EVENTS_MULTI_REGION;
import static com.xammer.cloud.service.autospotting.aws.StackCapabilities.AS_PASSROLE_SCOPED;
import static com.xammer.cloud.service.autospotting.aws.StackCapabilities.AS_QUOTA_READS;
import static com.xammer.cloud.service.autospotting.aws.StackCapabilities.AS_SIMULATE_SELF;
class StackCapabilitiesTest {
    @Test
    void aNullOrUnreadableVersionLacksEveryRequiredCapability() {
        assertThat(StackCapabilities.missingForConversion(null))
                .containsExactly(AS_EVENTS_MULTI_REGION, AS_PASSROLE_SCOPED, AS_SIMULATE_SELF, AS_QUOTA_READS);
        assertThat(StackCapabilities.missingForConversion("garbage")).hasSize(4);
    }
    @Test
    void theOnboardingVersionHasEverythingButTheEventsStack() {
        assertThat(StackCapabilities.missingForConversion("rw-2026.10.1")).containsExactly(AS_EVENTS_MULTI_REGION);
    }
    @Test
    void theEventsVersionHasEverything() {
        assertThat(StackCapabilities.missingForConversion("rw-2026.11.1")).isEmpty();
        assertThat(StackCapabilities.missingForConversion("rw-2027.1.1")).isEmpty();
    }
    @Test
    void versionsCompareNumericallyNotAsText() {
        assertThat(StackCapabilities.atLeast("rw-2026.10.12", "rw-2026.10.2")).isTrue();
        assertThat(StackCapabilities.atLeast("rw-2026.9.1", "rw-2026.10.1")).isFalse();
    }
    @Test
    void aPreflightPassSatisfiesOnlyPassRoleAndQuotaReads() {
        List<StackCapabilities> missing = StackCapabilities.missingForConversion(null,
                EnumSet.of(AS_PASSROLE_SCOPED, AS_QUOTA_READS, AS_EVENTS_MULTI_REGION, AS_SIMULATE_SELF));
        assertThat(missing).containsExactly(AS_EVENTS_MULTI_REGION, AS_SIMULATE_SELF);
    }
    @Test
    void theLatestVersionIsTheNewestMinimum() {
        assertThat(StackCapabilities.latestVersion()).isEqualTo("rw-2026.11.1");
        assertThat(StackCapabilities.latestVersion()).isEqualTo(AwsEligibilityRules.MINIMUM_STACK_VERSION);
    }
}
