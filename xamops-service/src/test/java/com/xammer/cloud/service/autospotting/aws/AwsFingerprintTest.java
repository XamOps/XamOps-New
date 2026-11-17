package com.xammer.cloud.service.autospotting.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/** plan-phase-12.md 12.2, design-aws.md §5.2: the fingerprint covers the managed fields only. */
class AwsFingerprintTest {

    private static AutoScalingGroup.Builder mixed(String version, String... types) {
        LaunchTemplateOverrides[] overrides = new LaunchTemplateOverrides[types.length];
        for (int i = 0; i < types.length; i++) {
            overrides[i] = LaunchTemplateOverrides.builder().instanceType(types[i]).build();
        }
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(LaunchTemplate.builder()
                                .launchTemplateSpecification(LaunchTemplateSpecification.builder()
                                        .launchTemplateId("lt-0abc").version(version).build())
                                .overrides(overrides).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandBaseCapacity(1)
                                .onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized")
                                .onDemandAllocationStrategy("prioritized").build()).build());
    }

    private static String fp(AutoScalingGroup g) {
        return AwsFingerprint.of(AwsGroupFacts.from("ap-south-1", g));
    }

    @Test
    void aLaunchTemplateVersionChangeDoesNotMoveTheFingerprint() {
        assertThat(fp(mixed("$Default", "m5.large", "m5a.large").build()))
                .isEqualTo(fp(mixed("12", "m5.large", "m5a.large").build()));
    }

    @Test
    void overrideOrderDoesNotMatterButMembershipDoes() {
        assertThat(fp(mixed("$Default", "m5.large", "m5a.large").build()))
                .isEqualTo(fp(mixed("$Default", "m5a.large", "m5.large").build()));
        assertThat(fp(mixed("$Default", "m5.large", "m5a.large").build()))
                .isNotEqualTo(fp(mixed("$Default", "m5.large", "m6i.large").build()));
    }

    @Test
    void distributionAndCapacityRebalanceMoveIt() {
        AutoScalingGroup base = mixed("$Default", "m5.large").build();
        AutoScalingGroup rebalance = base.toBuilder().capacityRebalance(true).build();
        assertThat(fp(rebalance)).isNotEqualTo(fp(base));
        AutoScalingGroup otherPct = base.toBuilder().mixedInstancesPolicy(base.mixedInstancesPolicy().toBuilder()
                .instancesDistribution(base.mixedInstancesPolicy().instancesDistribution().toBuilder()
                        .onDemandPercentageAboveBaseCapacity(25).build()).build()).build();
        assertThat(fp(otherPct)).isNotEqualTo(fp(base));
    }

    @Test
    void scalingAndNonManagedFieldsDoNotMoveIt() {
        AutoScalingGroup base = mixed("$Default", "m5.large").build();
        AutoScalingGroup scaled = base.toBuilder().desiredCapacity(9).maxSize(20).healthCheckType("EC2")
                .tags(FakeAws.tag("team", "x")).build();
        assertThat(fp(scaled)).isEqualTo(fp(base));
    }

    @Test
    void theLaunchTemplateIdOrNameDoesMoveIt() {
        AutoScalingGroup a = FakeAws.asg("web-asg").build();
        AutoScalingGroup b = FakeAws.asg("web-asg").launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId("lt-0other").version("$Default").build()).build();
        assertThat(fp(a)).isNotEqualTo(fp(b));
    }

    @Test
    void theFingerprintIsAHexSha256() {
        assertThat(fp(FakeAws.asg("web-asg").build())).matches("[0-9a-f]{64}");
    }
}
