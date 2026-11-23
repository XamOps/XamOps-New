package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three-way check of a revert, one case per row of design-aws.md §5.4 (plan-phase-19.md task 19.2,
 * AC-REV-01, AC-REV-03): snapshot S, XamOps desired D, live L, {@code accepted_fields}.
 */
class AwsThreeWayDiffTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The original group: a standalone launch template lt-0abc at $Default. */
    private static AutoScalingGroup original() {
        return FakeAws.asg("web-asg").build();
    }

    private static AwsSnapshotCodec.Owned snapshotOf(AutoScalingGroup g, boolean reconstructed) {
        return AwsSnapshotCodec.decodeOwned(AwsSnapshotCodec.encode(FakeAws.REGION, g,
                AwsSnapshotCodec.Context.none(), reconstructed).payloadJson()).orElseThrow();
    }

    /** A converted group: a mixed policy over {@code template} at {@code version} with these overrides. */
    private static AutoScalingGroup converted(String template, String version, boolean rebalance, String... types) {
        List<LaunchTemplateOverrides> overrides = java.util.Arrays.stream(types)
                .map(t -> LaunchTemplateOverrides.builder().instanceType(t).build()).toList();
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .capacityRebalance(rebalance)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(
                                LaunchTemplateSpecification.builder().launchTemplateId(template).version(version).build())
                                .overrides(overrides).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build())
                .build();
    }

    private static AutoScalingGroup withBase(AutoScalingGroup g, int base) {
        MixedInstancesPolicy m = g.mixedInstancesPolicy();
        return g.toBuilder().mixedInstancesPolicy(m.toBuilder().instancesDistribution(
                m.instancesDistribution().toBuilder().onDemandBaseCapacity(base).build()).build()).build();
    }

    private static JsonNode desired(String template, String... types) throws IOException {
        StringBuilder t = new StringBuilder();
        for (String s : types) {
            t.append(t.length() == 0 ? "" : ",").append('"').append(s).append('"');
        }
        return JSON.readTree("{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"" + template
                + "\",\"version\":\"$Default\"},\"overrides\":[" + t + "],\"instancesDistribution\":"
                + "{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true}");
    }

    private static JsonNode accepted(String... fields) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"field\":\"").append(fields[i])
                    .append("\",\"value\":\"x\",\"acceptedAt\":\"2026-11-17T00:00:00Z\",\"planId\":\"p\"}");
        }
        return JSON.readTree(sb.append(']').toString());
    }

    @Test
    void liveEqualsDesiredWritesTheSnapshotAndNothingIsKept() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0abc", "m5.large", "m5a.large"),
                converted("lt-0abc", "$Default", true, "m5.large", "m5a.large"), null, false);
        assertThat(r.exact()).isTrue();
        assertThat(r.kept()).isEmpty();
        assertThat(r.acks()).isEmpty();
        assertThat(r.target().standalone()).isTrue();
        assertThat(r.target().template().id()).isEqualTo("lt-0abc");
        assertThat(r.target().capacityRebalance()).isFalse();
        assertThat(r.target().distribution()).isNull();
    }

    @Test
    void aTemplateSwappedByTheCustomerIsKeptListedAndMakesTheRestoreInexact() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0abc", "m5.large", "m5a.large"),
                converted("lt-0new", "3", true, "m5.large", "m5a.large"), null, false);
        assertThat(r.target().template().id()).as("the live template stays").isEqualTo("lt-0new");
        assertThat(r.target().template().version()).as("the live version string is never replaced").isEqualTo("3");
        assertThat(r.kept()).containsExactly(new AwsThreeWayDiff.Kept("launchTemplate", AwsThreeWayDiff.CHANGED_OUTSIDE));
        assertThat(r.inexactReasons()).containsExactly(AwsThreeWayDiff.TEMPLATE_REPLACED);
        assertThat(r.target().capacityRebalance()).as("Spot fields are always the snapshot's").isFalse();
    }

    @Test
    void aTemplateSwapAcceptedAsTheBaselineSurvivesALaterRevertAndTheSpotFieldsAreRestored() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0new", "m5.large", "m5a.large"),
                converted("lt-0new", "3", true, "m5.large", "m5a.large"), accepted("launchTemplate"), false);
        assertThat(r.target().template().id()).isEqualTo("lt-0new");
        assertThat(r.kept()).containsExactly(new AwsThreeWayDiff.Kept("launchTemplate", AwsThreeWayDiff.ACCEPTED_BASELINE));
        assertThat(r.inexactReasons()).containsExactly(AwsThreeWayDiff.TEMPLATE_REPLACED);
        assertThat(r.target().standalone()).isTrue();
        assertThat(r.target().capacityRebalance()).isFalse();
    }

    @Test
    void theSpotFieldsAreTheSnapshotsEvenWhenTheDistributionWasAccepted() throws IOException {
        AutoScalingGroup withSpot = converted("lt-0abc", "$Default", false, "m5.large", "m6i.large");
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(withSpot, false),
                desired("lt-0abc", "m5.large", "m6i.large", "m5a.large"),
                converted("lt-0abc", "$Default", true, "m5.large", "m6i.large", "m5a.large"),
                accepted("instancesDistribution", "capacityRebalance"), false);
        assertThat(r.target().distribution().percentAboveBase()).isEqualTo(50);
        assertThat(r.target().capacityRebalance()).isFalse();
        assertThat(r.target().overrides()).extracting(AwsThreeWayDiff.Override::type)
                .as("the type XamOps added is removed, the originals stay").containsExactly("m5.large", "m6i.large");
    }

    @Test
    void aLaunchTemplateVersionChangeIsContextOnlyAndTheLiveStringStays() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0abc", "m5.large"), converted("lt-0abc", "9", true, "m5.large"), null, false);
        assertThat(r.target().template().version()).isEqualTo("9");
        assertThat(r.versionDrift()).isTrue();
        assertThat(r.exact()).isTrue();
        assertThat(r.kept()).isEmpty();
    }

    @Test
    void aDeletedSnapshotTemplateVersionProposesTheLiveTemplateAtDefaultWithAnAck() throws IOException {
        AutoScalingGroup pinned = FakeAws.asg("web-asg").launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId("lt-0abc").version("4").build()).build();
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(pinned, false),
                desired("lt-0abc", "m5.large"), converted("lt-0abc", "4", true, "m5.large"), null, true);
        assertThat(r.target().template().id()).isEqualTo("lt-0abc");
        assertThat(r.target().template().version()).isEqualTo("$Default");
        assertThat(r.inexactReasons()).containsExactly(AwsThreeWayDiff.TEMPLATE_VERSION_DELETED);
        assertThat(r.acks()).containsExactly(AwsThreeWayDiff.TEMPLATE_VERSION_DELETED);
    }

    @Test
    void overridesTheCustomerAddedAreKeptWhenTheSnapshotWasAPolicy() throws IOException {
        AutoScalingGroup before = converted("lt-0abc", "$Default", false, "m5.large");
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(before, false),
                desired("lt-0abc", "m5.large", "m5a.large"),
                converted("lt-0abc", "$Default", true, "m5.large", "m5a.large", "c5.large"), null, false);
        assertThat(r.target().overrides()).extracting(AwsThreeWayDiff.Override::type)
                .containsExactly("m5.large", "c5.large");
        assertThat(r.kept()).containsExactly(new AwsThreeWayDiff.Kept("overrides", AwsThreeWayDiff.CHANGED_OUTSIDE));
        assertThat(r.acks()).isEmpty();
    }

    @Test
    void overridesTheCustomerAddedAreListedAndNeedAnAckWhenTheSnapshotWasStandalone() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0abc", "m5.large", "m5a.large"),
                converted("lt-0abc", "$Default", true, "m5.large", "m5a.large", "c5.large"), null, false);
        assertThat(r.customerOverrides()).containsExactly("c5.large");
        assertThat(r.acks()).containsExactly(AwsThreeWayDiff.ACK_CUSTOMER_OVERRIDES);
        assertThat(r.target().overrides()).isEmpty();
        assertThat(r.exact()).as("an ack, not an inexact restore").isTrue();
    }

    @Test
    void capacityTagsPoliciesAndHooksAreNeverPartOfTheTarget() throws IOException {
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(original(), false),
                desired("lt-0abc", "m5.large"), converted("lt-0abc", "$Default", true, "m5.large"), null, false);
        assertThat(r.target().toNode().fieldNames()).toIterable().containsExactlyInAnyOrder("shape",
                "launchTemplate", "overrides", "instancesDistribution", "capacityRebalance");
    }

    @Test
    void aLegacyGroupWithOneOverrideBecomesAStandaloneTemplateAlwaysInexactWithAnAck() {
        AutoScalingGroup legacy = withBase(converted("lt-0abc", "$Default", true, "m5.large"), 0);
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(legacy, true), null, legacy, null, false);
        assertThat(r.target().standalone()).isTrue();
        assertThat(r.inexactReasons()).containsExactly(AwsThreeWayDiff.LEGACY_RECONSTRUCTED);
        assertThat(r.acks()).containsExactly(AwsThreeWayDiff.ACK_LEGACY_TARGET);
    }

    @Test
    void aLegacyGroupWithSeveralOverridesKeepsThePolicyAtBaseZeroAndShareHundred() {
        AutoScalingGroup legacy = converted("lt-0abc", "$Default", true, "m5.large", "m5a.large");
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(snapshotOf(legacy, true), null, legacy, null, false);
        assertThat(r.target().standalone()).isFalse();
        assertThat(r.target().distribution().base()).isZero();
        assertThat(r.target().distribution().percentAboveBase()).isEqualTo(100);
        assertThat(r.target().overrides()).hasSize(2);
        assertThat(r.exact()).isFalse();
        assertThat(r.acks()).containsExactly(AwsThreeWayDiff.ACK_LEGACY_TARGET);
    }

    private static software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy imp(int min, int max) {
        return software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                .minHealthyPercentage(min).maxHealthyPercentage(max).build();
    }

    private static JsonNode ownedDesired() throws IOException {
        return JSON.readTree("{\"shape\":\"MIXED_INSTANCES_POLICY\",\"overrides\":[\"m5.large\"],"
                + "\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}");
    }

    /** AD-08: the saved original comes back exactly: "absent" is -1/-1, a recorded policy is its own values. */
    @Test
    void theOwnedPolicyIsRestoredToTheSnapshotValueIncludingAbsent() throws IOException {
        AutoScalingGroup live = converted("lt-0abc", "$Default", true, "m5.large").toBuilder()
                .instanceMaintenancePolicy(imp(100, 200)).build();
        JsonNode absent = AwsThreeWayDiff.compute(snapshotOf(original(), false), ownedDesired(), live, null, false)
                .target().maintenancePolicy();
        assertThat(absent.path("min").asInt()).isEqualTo(-1);
        assertThat(absent.path("max").asInt()).isEqualTo(-1);
        AutoScalingGroup hadOne = original().toBuilder().instanceMaintenancePolicy(imp(90, 150)).build();
        JsonNode set = AwsThreeWayDiff.compute(snapshotOf(hadOne, false), ownedDesired(), live, null, false)
                .target().maintenancePolicy();
        assertThat(set.path("min").asInt()).isEqualTo(90);
        assertThat(set.path("max").asInt()).isEqualTo(150);
    }

    @Test
    void aPolicyXamOpsDidNotOwnOrAnOldSnapshotIsLeftAlone() throws IOException {
        AutoScalingGroup live = converted("lt-0abc", "$Default", true, "m5.large").toBuilder()
                .instanceMaintenancePolicy(imp(100, 200)).build();
        assertThat(AwsThreeWayDiff.compute(snapshotOf(original(), false), desired("lt-0abc", "m5.large"), live, null,
                false).target().maintenancePolicy()).as("not owned").isNull();
        String payload = AwsSnapshotCodec.encode(FakeAws.REGION, original(), AwsSnapshotCodec.Context.none(), false)
                .payloadJson().replace(",\"instanceMaintenancePolicy\":{\"state\":\"ABSENT\"}", "");
        AwsSnapshotCodec.Owned old = AwsSnapshotCodec.decodeOwned(payload).orElseThrow();
        assertThat(old.hasMaintenancePolicy()).isFalse();
        assertThat(AwsThreeWayDiff.compute(old, ownedDesired(), live, null, false).target().maintenancePolicy())
                .as("older snapshot").isNull();
    }

    @Test
    void aSnapshotThatHadSpotIsRecognisedSoTheDrainIsSkipped() {
        assertThat(AwsThreeWayDiff.snapshotHadSpot(snapshotOf(converted("lt-0abc", "$Default", false, "m5.large"), false)))
                .isTrue();
        assertThat(AwsThreeWayDiff.snapshotHadSpot(snapshotOf(original(), false))).isFalse();
    }
}
