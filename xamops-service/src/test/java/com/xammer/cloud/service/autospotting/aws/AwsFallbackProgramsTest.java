package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The shape of the fallback programs, the one request FALLBACK_ENTER sends, and the dark rule: with the AWS
 * mutation switches false (every shipped profile) no fallback program is built, so nothing can reach AWS.
 */
class AwsFallbackProgramsTest {
    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\"],\"instancesDistribution\":{"
            + "\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true}";

    private static AutoScalingGroup converted() {
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .desiredCapacity(5)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(LaunchTemplateOverrides.builder().instanceType("m5.large").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").spotMaxPrice("0.05").build())
                        .build()).build();
    }

    private static List<String> names(StepProgram p) {
        return p.steps().stream().map(Step::name).toList();
    }

    @Test
    void theEnterProgramHasFourStepsAndOnlyTheUpdateMutates() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), true);
        StepProgram p = f.provider.executeConversion(f.context(OperationKind.FALLBACK_ENTER, null, null));
        assertThat(names(p)).containsExactly("VERIFY_PRECONDITIONS", "UPDATE_DISTRIBUTION",
                "VERIFY_FALLBACK_APPLIED", "WAIT_CAPACITY");
        assertThat(p.steps().stream().filter(Step::mutates).map(Step::name)).containsExactly("UPDATE_DISTRIBUTION");
    }

    @Test
    void theExitProgramIsTheConversionTailAndItsCompensationRestoresOnDemand() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), true);
        StepProgram exit = f.provider.executeConversion(f.context(OperationKind.FALLBACK_EXIT, DESIRED, null));
        assertThat(names(exit)).containsExactly("UPDATE_DISTRIBUTION", "WAIT_ROLLOUT", "VERIFY_TARGET");
        OperationContext comp = new OperationContext(f.opId, OperationKind.FALLBACK_EXIT, f.spotGroup(), DESIRED, null,
                OperationPhase.COMPENSATING, null, CompensationTarget.FALLBACK);
        assertThat(names(f.provider.executeConversion(comp))).containsExactly("UPDATE_DISTRIBUTION", "WAIT_CAPACITY");
    }

    @Test
    void anExitWithoutADesiredConfigurationHasNoProgram() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), true);
        assertThatThrownBy(() -> f.provider.executeConversion(f.context(OperationKind.FALLBACK_EXIT, null, null)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PLAN_TARGET_MISSING"));
    }

    @Test
    void theOneUpdateFallbackSendsRaisesOnlyTheOnDemandShare() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), true);
        StepProgram p = f.provider.executeConversion(f.context(OperationKind.FALLBACK_ENTER, null, null));
        StepContext ctx = new StepContext(f.opId, 1, mock(LeaseHandle.class), null, Duration.ofSeconds(30));
        StepOutcome out = p.at(1).execute(ctx);
        assertThat(out).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.updates).hasSize(1);
        var r = f.updates.get(0);
        assertThat(r.autoScalingGroupName()).isEqualTo("web-asg");
        assertThat(r.launchTemplate()).isNull();
        assertThat(r.capacityRebalance()).as("CapacityRebalance is not in the request").isNull();
        assertThat(r.desiredCapacity()).isNull();
        assertThat(r.minSize()).isNull();
        assertThat(r.maxSize()).isNull();
        assertThat(r.mixedInstancesPolicy().launchTemplate()).as("no overrides in the request").isNull();
        InstancesDistribution d = r.mixedInstancesPolicy().instancesDistribution();
        assertThat(d.onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(d.onDemandBaseCapacity()).isEqualTo(1);
        assertThat(d.onDemandAllocationStrategy()).isEqualTo("prioritized");
        assertThat(d.spotAllocationStrategy()).isEqualTo("price-capacity-optimized");
        // Running it again changes nothing: the share is already 100.
        assertThat(p.at(1).execute(ctx)).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.updates).hasSize(1);
        assertThat(p.at(1).probe(ctx)).isInstanceOf(StepOutcome.Done.class);
    }

    @Test
    void withTheAwsMutationSwitchesFalseNoFallbackProgramIsBuilt() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), false);
        for (OperationKind kind : List.of(OperationKind.FALLBACK_ENTER, OperationKind.FALLBACK_EXIT)) {
            assertThatThrownBy(() -> f.provider.executeConversion(f.context(kind, DESIRED, null)))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        }
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aFallbackEnterNeverCompensatesTowardSpot() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), true);
        OperationContext comp = new OperationContext(f.opId, OperationKind.FALLBACK_ENTER, f.spotGroup(), null, null,
                OperationPhase.COMPENSATING, null, CompensationTarget.NONE);
        assertThatThrownBy(() -> f.provider.executeConversion(comp)).isInstanceOf(IllegalStateException.class);
    }
}
