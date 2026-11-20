package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exact {@code UpdateAutoScalingGroup} body each group shape produces (design-aws.md §2.4,
 * AC-AWS-01, AC-AWS-07, AC-CMP-06 two golden rows). The target comes from a real plan over the stubbed
 * AWS, so a change to the plan's target format breaks these tests too. Golden files live in
 * {@code src/test/resources/autospotting/aws/golden/}; no real AWS call is made.
 */
class AwsUpdateRequestGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode golden(String name) throws IOException {
        try (InputStream in = AwsUpdateRequestGoldenTest.class.getResourceAsStream(
                "/autospotting/aws/golden/" + name + ".json")) {
            assertThat(in).as("golden file " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    private static UpdateAutoScalingGroupRequest request(AwsConversionFixture f, String profile) throws IOException {
        PlanBody body = f.plan(profile);
        return AwsUpdateRequestBuilder.build(f.group(), JSON.readTree(body.targetJson()));
    }

    private static AutoScalingGroup existingPolicyGroup(String strategy, Integer pools, String spotMaxPrice,
                                                        String... types) {
        List<LaunchTemplateOverrides> overrides = java.util.Arrays.stream(types)
                .map(t -> LaunchTemplateOverrides.builder().instanceType(t).weightedCapacity("1").build()).toList();
        InstancesDistribution.Builder d = InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                .onDemandBaseCapacity(0).onDemandPercentageAboveBaseCapacity(100).spotAllocationStrategy(strategy);
        if (pools != null) {
            d.spotInstancePools(pools);
        }
        if (spotMaxPrice != null) {
            d.spotMaxPrice(spotMaxPrice);
        }
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder()
                                .launchTemplateName("web-lt").version("$Latest").build())
                        .overrides(overrides).build()).instancesDistribution(d.build()).build()).build();
    }

    @Test
    void standaloneTemplateShape() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
        JsonNode actual = SdkJson.of(request(f, "conservative"));
        assertThat(actual).isEqualTo(golden("standalone-conservative"));
    }

    @Test
    void aggressiveProfileChangesOnlyTheDistribution() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
        JsonNode actual = SdkJson.of(request(f, "aggressive"));
        assertThat(actual).isEqualTo(golden("standalone-aggressive"));
    }

    @Test
    void existingPolicyOmitsLaunchTemplate() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(
                existingPolicyGroup("capacity-optimized", null, null, "m5.large", "m5a.large", "m6i.large", "m5n.large"),
                true);
        UpdateAutoScalingGroupRequest r = request(f, "conservative");
        JsonNode actual = SdkJson.of(r);
        assertThat(r.mixedInstancesPolicy().launchTemplate()).isNull();
        assertThat(actual).isEqualTo(golden("existing-policy"));
    }

    @Test
    void existingPolicyThatGainsTypesCarriesTheLiveSpecAndMergedOverrides() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(
                existingPolicyGroup("capacity-optimized", null, null, "m5.large"), true);
        UpdateAutoScalingGroupRequest r = request(f, "conservative");
        assertThat(r.mixedInstancesPolicy().launchTemplate()).isNotNull();
        JsonNode actual = SdkJson.of(r);
        // The customer's own override (with its weight) comes first and is carried unchanged.
        JsonNode first = actual.path("MixedInstancesPolicy").path("LaunchTemplate").path("Overrides").get(0);
        assertThat(first.path("InstanceType").asText()).isEqualTo("m5.large");
        assertThat(first.path("WeightedCapacity").asText()).isEqualTo("1");
        assertThat(actual.path("MixedInstancesPolicy").path("LaunchTemplate").path("LaunchTemplateSpecification"))
                .isEqualTo(JSON.readTree("{\"LaunchTemplateName\":\"web-lt\",\"Version\":\"$Latest\"}"));
    }

    @Test
    void neverSendsSpotMaxPrice() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(
                existingPolicyGroup("lowest-price", 3, "0.05", "m5.large", "m5a.large", "m6i.large", "m5n.large"), true);
        UpdateAutoScalingGroupRequest r = request(f, "conservative");
        assertThat(SdkJson.of(r).toString()).doesNotContain("SpotMaxPrice");
        assertThat(AwsUpdateRequestBuilder.forbiddenFields(r)).isEmpty();
        // lowest-price is kept (strategy preservation) and is the only strategy that sends SpotInstancePools.
        assertThat(r.mixedInstancesPolicy().instancesDistribution().spotAllocationStrategy()).isEqualTo("lowest-price");
        assertThat(r.mixedInstancesPolicy().instancesDistribution().spotInstancePools()).isEqualTo(3);
    }

    @Test
    void spotInstancePoolsOnlyGoWithLowestPrice() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(
                existingPolicyGroup("capacity-optimized", 3, null, "m5.large", "m5a.large", "m6i.large", "m5n.large"),
                true);
        UpdateAutoScalingGroupRequest r = request(f, "conservative");
        assertThat(r.mixedInstancesPolicy().instancesDistribution().spotAllocationStrategy())
                .isEqualTo("price-capacity-optimized");
        assertThat(r.mixedInstancesPolicy().instancesDistribution().spotInstancePools()).isNull();
    }

    @Test
    void neverSendsACapacityOrHealthField() throws IOException {
        for (AutoScalingGroup g : List.of(FakeAws.asg("web-asg").build(),
                existingPolicyGroup("capacity-optimized", null, null, "m5.large"))) {
            AwsConversionFixture f = new AwsConversionFixture(g, true);
            JsonNode actual = SdkJson.of(request(f, "conservative"));
            assertThat(actual.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("AutoScalingGroupName", "MixedInstancesPolicy", "CapacityRebalance");
            assertThat(AwsUpdateRequestBuilder.forbiddenFields(request(f, "conservative"))).isEmpty();
        }
    }

    @Test
    void theRequestAlwaysTurnsOnCapacityRebalanceAndKeepsTheOnDemandFloor() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
        UpdateAutoScalingGroupRequest r = request(f, "conservative");
        InstancesDistribution d = r.mixedInstancesPolicy().instancesDistribution();
        assertThat(r.capacityRebalance()).isTrue();
        assertThat(d.onDemandBaseCapacity()).isGreaterThanOrEqualTo(1);
        assertThat(d.onDemandAllocationStrategy()).isEqualTo("prioritized");
        assertThat(d.spotAllocationStrategy()).isEqualTo("price-capacity-optimized");
        // Several diversified types, the customer's own type first.
        List<LaunchTemplateOverrides> o = r.mixedInstancesPolicy().launchTemplate().overrides();
        assertThat(o.size()).isGreaterThanOrEqualTo(3);
        assertThat(o.get(0).instanceType()).isEqualTo("m5.large");
    }

    @Test
    void aLaunchConfigurationGroupBuildsNoRequest() {
        AutoScalingGroup lc = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .launchConfigurationName("old-lc").build();
        JsonNode target = JSON.createObjectNode().put("shape", "MIXED_INSTANCES_POLICY");
        assertThatThrownBy(() -> AwsUpdateRequestBuilder.build(lc, target)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOverrideThatWasNotInTheTargetIsNotInvented() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
        JsonNode target = JSON.readTree(f.plan("conservative").targetJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) target).putArray("overrides").add("m5.large");
        UpdateAutoScalingGroupRequest r = AwsUpdateRequestBuilder.build(f.group(), target);
        assertThat(r.mixedInstancesPolicy().launchTemplate().overrides()).hasSize(1);
    }
}
