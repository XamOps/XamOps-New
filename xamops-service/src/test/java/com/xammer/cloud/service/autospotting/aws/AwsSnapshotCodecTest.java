package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceRequirements;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** plan-phase-12.md 12.2, design-aws.md §5.1: payload schema v1, read side. Recorded fixture in {@code TR/autospotting/aws/}. */
class AwsSnapshotCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "ap-south-1";

    private static SnapshotPayload encode(AutoScalingGroup g) {
        return AwsSnapshotCodec.encode(REGION, g, AwsSnapshotCodec.Context.none(), false);
    }

    @Test
    void aLaunchTemplateGroupKeepsTheVersionStringAsIs() throws IOException {
        SnapshotPayload p = encode(FakeAws.asg("web-asg").build());
        JsonNode root = JSON.readTree(p.payloadJson());
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.at("/owned/shape").asText()).isEqualTo("LAUNCH_TEMPLATE");
        assertThat(root.at("/owned/launchTemplate/version").asText()).isEqualTo("$Default");
        assertThat(root.at("/owned/launchTemplate/id").asText()).isEqualTo("lt-0abc");
        assertThat(root.path("capturedFrom").asText()).isEqualTo("LIVE");
        assertThat(p.exact()).isTrue();
        assertThat(p.inexactReasons()).isEmpty();
        assertThat(p.payloadSha256()).isEqualTo(AwsFingerprint.sha256(p.payloadJson()));
    }

    @Test
    void theSnapshotMatchesTheRecordedFixture() throws IOException {
        SnapshotPayload p = AwsSnapshotCodec.encode(REGION, FakeAws.asg("web-asg")
                .tags(FakeAws.tag("team", "payments")).build(),
                new AwsSnapshotCodec.Context(7, "abc123", 0, List.of("drain"), List.of(), List.of()), false);
        String golden = new String(getClass().getResourceAsStream("/autospotting/aws/snapshot-lt-asg.json")
                .readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(JSON.readTree(p.payloadJson())).isEqualTo(JSON.readTree(golden));
    }

    @Test
    void anExistingMixedPolicyIsCapturedWhole() throws IOException {
        AutoScalingGroup g = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder()
                                .launchTemplateName("web").version("3").build())
                        .overrides(LaunchTemplateOverrides.builder().instanceType("m5.large").weightedCapacity("1").build(),
                                LaunchTemplateOverrides.builder().instanceType("m5a.large").weightedCapacity("2").build())
                        .build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandBaseCapacity(2)
                                .onDemandPercentageAboveBaseCapacity(100).spotAllocationStrategy("lowest-price")
                                .spotInstancePools(3).spotMaxPrice("0.5").build()).build())
                .capacityRebalance(true).build();
        SnapshotPayload p = encode(g);
        JsonNode owned = JSON.readTree(p.payloadJson()).path("owned");
        assertThat(owned.path("shape").asText()).isEqualTo("MIXED_INSTANCES_POLICY");
        assertThat(owned.path("capacityRebalance").asBoolean()).isTrue();
        assertThat(owned.at("/mixedInstancesPolicy/launchTemplate/spec/name").asText()).isEqualTo("web");
        assertThat(owned.at("/mixedInstancesPolicy/launchTemplate/overrides")).hasSize(2);
        assertThat(owned.at("/mixedInstancesPolicy/launchTemplate/overrides/1/weight").asText()).isEqualTo("2");
        assertThat(owned.at("/mixedInstancesPolicy/instancesDistribution/spotInstancePools").asInt()).isEqualTo(3);
        assertThat(owned.at("/mixedInstancesPolicy/instancesDistribution/spotMaxPrice").asText()).isEqualTo("0.5");
        assertThat(p.exact()).isTrue();
    }

    @Test
    void attributeBasedOverridesAreNotCapturedSoTheSnapshotIsInexact() {
        AutoScalingGroup g = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder()
                                .launchTemplateId("lt-1").version("$Latest").build())
                        .overrides(LaunchTemplateOverrides.builder().instanceRequirements(InstanceRequirements.builder().build())
                                .build()).build()).build()).build();
        SnapshotPayload p = encode(g);
        assertThat(p.exact()).isFalse();
        assertThat(p.inexactReasons()).contains("INSTANCE_REQUIREMENTS_NOT_CAPTURED");
    }

    @Test
    void aReconstructedLegacySnapshotIsNeverExact() throws IOException {
        SnapshotPayload p = AwsSnapshotCodec.encode(REGION, FakeAws.asg("web-asg").build(),
                AwsSnapshotCodec.Context.none(), true);
        assertThat(p.exact()).isFalse();
        assertThat(p.inexactReasons()).contains("RECONSTRUCTED_FROM_LEGACY_STATE");
        assertThat(JSON.readTree(p.payloadJson()).path("capturedFrom").asText()).isEqualTo("RECONSTRUCTED");
    }

    @Test
    void tagsAreCappedAtOneHundredWithATruncatedFlag() throws IOException {
        List<TagDescription> tags = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            tags.add(FakeAws.tag("k" + i, "v"));
        }
        JsonNode ctx = JSON.readTree(encode(FakeAws.asg("web-asg").tags(tags).build()).payloadJson()).path("context");
        assertThat(ctx.path("tags")).hasSize(100);
        assertThat(ctx.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void ownedFieldsRoundTripThroughDecode() {
        SnapshotPayload p = encode(FakeAws.asg("web-asg").capacityRebalance(true).build());
        AwsSnapshotCodec.Owned owned = AwsSnapshotCodec.decodeOwned(p.payloadJson()).orElseThrow();
        assertThat(owned.shape()).isEqualTo("LAUNCH_TEMPLATE");
        assertThat(owned.launchTemplate().id()).isEqualTo("lt-0abc");
        assertThat(owned.launchTemplate().version()).isEqualTo("$Default");
        assertThat(owned.capacityRebalance()).isTrue();
        assertThat(owned.ownedFingerprint()).isEqualTo(AwsFingerprint.of(AwsGroupFacts.from(REGION,
                FakeAws.asg("web-asg").capacityRebalance(true).build())));
        assertThat(AwsSnapshotCodec.decodeOwned("not json")).isEmpty();
        assertThat(AwsSnapshotCodec.decodeOwned(null)).isEmpty();
    }

    @Test
    void theEncodingIsDeterministic() {
        AutoScalingGroup g = FakeAws.asg("web-asg").build();
        assertThat(encode(g).payloadSha256()).isEqualTo(encode(g).payloadSha256());
    }
}
