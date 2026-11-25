package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.5 (AC-PLN-01, AC-PLN-02 AWS fields, AC-PLN-08, AC-PLN-09, AC-AWS-03, AC-PRC-01): a
 * plan fills every PlanBody field through a mocked, read-only AWS; the mocked SDK records zero mutating
 * calls. The mutating programs return AS_PROVIDER_NOT_READY.
 */
class AwsSpotProviderPlanTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRODUCT = "{\"terms\":{\"OnDemand\":{\"S.J\":{\"priceDimensions\":{\"S.J.6\":"
            + "{\"pricePerUnit\":{\"USD\":\"0.0960000000\"}}}}}},\"version\":\"20261101\","
            + "\"publicationDate\":\"2026-11-01T00:00:00Z\"}";

    private final CloudAccount account = FakeAws.account();
    private final PricingClient pricing = mock(PricingClient.class);
    private final AutoSpottingSwitchProperties switches = mock(AutoSpottingSwitchProperties.class);
    private FakeAws aws;
    private AwsSpotProvider provider;

    @BeforeEach
    void priceListWorks() {
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(PRODUCT).build());
    }

    private void setUp(AutoScalingGroup g) {
        aws = new FakeAws().happyPath(g);
        AwsViewSource views = (a, r) -> aws.view();
        AwsEstimatePriceService prices = new AwsEstimatePriceService(pricing, views);
        AwsCommitmentUtilization commitments = mock(AwsCommitmentUtilization.class);
        when(commitments.thresholdPercent()).thenReturn(80.0);
        when(commitments.utilization(any())).thenReturn(Optional.empty());
        AwsEligibilityEvaluator evaluator = new AwsEligibilityEvaluator(views, new AwsGroupStatusReader(),
                new AwsPlanReader(prices), mock(EligibilityCache.class), commitments, new AwsRegionCoverage() {
            @Override
            public boolean covered(CloudAccount a, String region) {
                return true;
            }
        }, mock(GroupLookup.class), new AutoSpottingMetrics(new SimpleMeterRegistry()), 12);
        CloudAccountRepository accounts = mock(CloudAccountRepository.class);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        provider = new AwsSpotProvider(accounts, views, evaluator, new AwsGroupStatusReader(), prices, switches,
                new AwsProviderGate(switches, new com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags(
                        false, false, false, false)),
                mock(AwsStepLibrary.class), mock(AwsRevertProgramFactory.class),
                new AwsRolloutEvaluator(AwsRolloutProperties.defaults()),
                new AwsFallbackRules(com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults()),
                mock(AwsFallbackPrograms.class), mock(AwsLaunchEventRecorder.class),
                Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC));
    }

    private static GroupRef ref() {
        return new GroupRef(Provider.AWS, 7L, "ap-south-1/web-asg", null);
    }

    private PlanBody plan(String profile) {
        return provider.createPlan(new PlanRequest(ref(), OperationKind.ENABLE, new Profile(profile, null)), aws.view());
    }

    @Test
    void aPlanFillsEveryPlanBodyField() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        PlanBody body = plan("conservative");

        JsonNode current = JSON.readTree(body.currentJson());
        assertThat(current.path("shape").asText()).isEqualTo("LAUNCH_TEMPLATE");
        assertThat(current.path("desired").asInt()).isEqualTo(4);
        JsonNode target = JSON.readTree(body.targetJson());
        assertThat(target.path("shape").asText()).isEqualTo("MIXED_INSTANCES_POLICY");
        assertThat(target.path("capacityRebalance").asBoolean()).isTrue();
        assertThat(target.at("/overrides/0").asText()).isEqualTo("m5.large");
        assertThat(target.path("overrides").size()).isBetween(2, 12);
        JsonNode dist = target.path("instancesDistribution");
        assertThat(dist.path("onDemandAllocationStrategy").asText()).isEqualTo("prioritized");
        assertThat(dist.path("spotAllocationStrategy").asText()).isEqualTo("price-capacity-optimized");
        assertThat(dist.path("onDemandBaseCapacity").asInt()).isEqualTo(1);
        assertThat(dist.path("onDemandPercentageAboveBaseCapacity").asInt()).isEqualTo(50);

        assertThat(JSON.readTree(body.diffJson()).findValuesAsText("field")).contains("shape", "overrides",
                "onDemandBaseCapacity", "onDemandPercentageAboveBaseCapacity", "capacityRebalance");
        assertThat(body.snapshotToStore()).isNotNull();
        assertThat(body.snapshotToStore().exact()).isTrue();
        assertThat(body.snapshotToStore().schemaVersion()).isEqualTo(1);
        assertThat(body.eligibility().blockers()).isEmpty();

        assertThat(body.rollout().method()).isNotBlank();
        assertThat(body.rollout().instancesToReplace()).isEqualTo(1);
        assertThat(body.rollout().surge()).isEqualTo(1);
        assertThat(body.rollout().headroom()).contains("max 8");
        JsonNode quota = JSON.readTree(body.rollout().quotaJson());
        assertThat(quota.at("/spot/quotaCode").asText()).isEqualTo("L-34B43A08");
        assertThat(quota.at("/onDemand/quotaCode").asText()).isEqualTo("L-1216C47A");
        assertThat(quota.path("asOf").asText()).isEqualTo("2026-11-17T10:00:00Z");

        assertThat(JSON.readTree(body.onDemandFloorJson()).path("baseCapacity").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(body.revertPathJson()).path("method").asText()).isEqualTo("RESTORE_SNAPSHOT");
        assertThat(body.feeBasisJson()).isNull(); // the core fills the fee basis (core §7.3)
        aws.assertReadOnly();
    }

    private PlanBody plan(String profile, PlanOptions options) {
        return provider.createPlan(new PlanRequest(ref(), OperationKind.ENABLE, new Profile(profile, null), options),
                aws.view());
    }

    private static final String OPTION_ON_DESIRED = "{\"options\":{\"allowBurstable\":true}}";

    /** F-23-04 / AD-09: a t3 group is blocked by default and the plan says which burstable types would fit. */
    @Test
    void aBurstableGroupIsBlockedWhileTheOptionIsOffAndTheDialogCanOfferTheAlternatives() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        aws.burstableWorld();
        PlanBody body = plan("conservative");
        assertThat(body.eligibility().blockers()).extracting(c -> c.code()).contains("NO_COMPATIBLE_TYPES");
        JsonNode options = JSON.readTree(body.optionsJson());
        assertThat(options.path("allowBurstable").asBoolean()).isFalse();
        assertThat(options.path("burstableApplicable").asBoolean()).isTrue();
        assertThat(options.path("burstableAlternatives")).extracting(JsonNode::asText)
                .containsExactly("t3a.micro", "t3a.small");
        assertThat(JSON.readTree(body.targetJson()).at("/options/allowBurstable").asBoolean()).isFalse();
    }

    @Test
    void allowingBurstableAddsOnlyBurstableTypesThatPassTheOtherRulesAndWarnsAboutCpuCredits() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        aws.burstableWorld();
        PlanBody body = plan("conservative", new PlanOptions(true, null, true));
        assertThat(body.eligibility().blockers()).isEmpty();
        JsonNode target = JSON.readTree(body.targetJson());
        assertThat(target.path("overrides")).extracting(JsonNode::asText)
                .containsExactly("t3.micro", "t3a.micro", "t3a.small");
        assertThat(target.at("/options/allowBurstable").asBoolean()).isTrue();
        assertThat(body.eligibility().warnings()).extracting(c -> c.code()).contains("BURSTABLE_CREDITS");
        JsonNode options = JSON.readTree(body.optionsJson());
        assertThat(options.path("allowBurstable").asBoolean()).isTrue();
        assertThat(options.path("burstableAdded")).extracting(JsonNode::asText).containsExactly("t3a.micro", "t3a.small");
    }

    @Test
    void theGroupsLastConfirmedBurstableChoiceStartsTheNextPlanAndTheRequestOverridesIt() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        aws.burstableWorld();
        PlanBody inherited = plan("conservative", new PlanOptions(null, OPTION_ON_DESIRED, true));
        assertThat(JSON.readTree(inherited.targetJson()).at("/options/allowBurstable").asBoolean()).isTrue();
        assertThat(inherited.eligibility().blockers()).isEmpty();
        PlanBody off = plan("conservative", new PlanOptions(false, OPTION_ON_DESIRED, true));
        assertThat(JSON.readTree(off.targetJson()).at("/options/allowBurstable").asBoolean()).isFalse();
        assertThat(off.eligibility().blockers()).extracting(c -> c.code()).contains("NO_COMPATIBLE_TYPES");
    }

    /** Review fix: turning the option off removes the types XamOps added for it, and only those. */
    @Test
    void turningBurstableOffRemovesTheTypesXamOpsAddedAndKeepsTheCustomersOwn() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        aws.burstableWorld();
        PlanBody on = plan("conservative", new PlanOptions(true, null, true));
        JsonNode onTarget = JSON.readTree(on.targetJson());
        assertThat(onTarget.at("/options/burstableAdded")).extracting(JsonNode::asText)
                .containsExactly("t3a.micro", "t3a.small");
        // The group now runs the converted overrides plus a type the customer added by hand.
        String remembered = "{\"options\":" + onTarget.get("options") + "}";
        setUp(FakeAws.asg("web-asg").launchTemplate((software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy.builder()
                        .launchTemplate(software.amazon.awssdk.services.autoscaling.model.LaunchTemplate.builder()
                                .launchTemplateSpecification(software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification.builder()
                                        .launchTemplateId("lt-0abc").version("$Default").build())
                                .overrides(java.util.stream.Stream.of("t3.micro", "t3a.micro", "t3a.small", "t3.small")
                                        .map(t -> software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides.builder().instanceType(t).build())
                                        .toList()).build()).build()).build());
        aws.burstableWorld();
        PlanBody off = plan("conservative", new PlanOptions(false, remembered, true));
        assertThat(JSON.readTree(off.targetJson()).path("overrides")).extracting(JsonNode::asText)
                .contains("t3.micro", "t3.small").doesNotContain("t3a.micro", "t3a.small");
    }
    /** Review fix: an owned policy a customer edited to 100/100 is corrected by the re-apply, not blocked by it. */
    @Test
    void anOwnedPolicyEditedToMax100DoesNotBlockTheCorrectiveReapplyButACustomersPolicyStillDoes() throws IOException {
        var edited = software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                .minHealthyPercentage(100).maxHealthyPercentage(100).build();
        String owned = "{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}";
        setUp(FakeAws.asg("web-asg").instanceMaintenancePolicy(edited).build());
        PlanBody fix = provider.createPlan(new PlanRequest(ref(), OperationKind.CONFIG_APPLY,
                new Profile("conservative", null), new PlanOptions(null, owned, true)), aws.view());
        assertThat(fix.eligibility().blockers()).extracting(c -> c.code()).doesNotContain("IMP_TERMINATE_FIRST");
        assertThat(JSON.readTree(fix.targetJson()).at("/instanceMaintenancePolicy/max").asInt()).isEqualTo(200);
        assertThat(provider.checkEligibility(ref(), aws.view(), new Profile("conservative", null), EvalMode.PLAN,
                new PlanOptions(null, owned, true)).blockers()).extracting(c -> c.code()).doesNotContain("IMP_TERMINATE_FIRST");
        PlanBody customer = provider.createPlan(new PlanRequest(ref(), OperationKind.CONFIG_APPLY,
                new Profile("conservative", null), new PlanOptions(null, null, true)), aws.view());
        assertThat(customer.eligibility().blockers()).extracting(c -> c.code()).contains("IMP_TERMINATE_FIRST");
    }
    @Test
    void theBurstableOptionIsRefusedForAGroupThatIsNotBurstable() {
        setUp(FakeAws.asg("web-asg").build());
        assertThatThrownBy(() -> plan("conservative", new PlanOptions(true, null, true)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("BURSTABLE_NOT_APPLICABLE");
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
        // A remembered choice on a group that is not burstable is simply not used.
        PlanBody body = plan("conservative", new PlanOptions(null, OPTION_ON_DESIRED, true));
        assertThat(body.eligibility().blockers()).isEmpty();
    }

    /** AD-08: a group with no policy gets 100/200 as an owned field; the diff shows the replacement-safety change. */
    @Test
    void aGroupWithNoMaintenancePolicyGetsTheOwnedPolicyInTheTargetAndTheDiff() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        PlanBody body = plan("conservative");
        JsonNode policy = JSON.readTree(body.targetJson()).path("instanceMaintenancePolicy");
        assertThat(policy.path("owned").asBoolean()).isTrue();
        assertThat(policy.path("min").asInt()).isEqualTo(100);
        assertThat(policy.path("max").asInt()).isEqualTo(200);
        JsonNode row = null;
        for (JsonNode d : JSON.readTree(body.diffJson())) {
            if ("instanceMaintenancePolicy".equals(d.path("field").asText())) {
                row = d;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("from").asText()).isEqualTo("none");
        assertThat(row.path("to").asText()).isEqualTo("min 100 / max 200");
        assertThat(body.eligibility().warnings()).extracting(c -> c.code()).contains("IMP_OVERSHOOT");
        // The snapshot records "absent" so revert can restore it exactly.
        assertThat(JSON.readTree(body.snapshotToStore().payloadJson()).at("/owned/instanceMaintenancePolicy/state")
                .asText()).isEqualTo("ABSENT");
    }

    @Test
    void aCustomerMaintenancePolicyIsNeverInTheTarget() throws IOException {
        setUp(FakeAws.asg("web-asg").instanceMaintenancePolicy(software.amazon.awssdk.services.autoscaling.model
                .InstanceMaintenancePolicy.builder().minHealthyPercentage(90).maxHealthyPercentage(150).build()).build());
        PlanBody body = plan("conservative");
        assertThat(JSON.readTree(body.targetJson()).has("instanceMaintenancePolicy")).isFalse();
        // The plan says the customer's policy stays: an informational row, "unchanged" as the target (ux 3.5).
        JsonNode row = null;
        for (JsonNode d : JSON.readTree(body.diffJson())) {
            if ("instanceMaintenancePolicy".equals(d.path("field").asText())) {
                row = d;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("from").asText()).isEqualTo("min 90 / max 150");
        assertThat(row.path("to").asText()).isEqualTo("unchanged");
        assertThat(body.eligibility().warnings()).extracting(c -> c.code()).contains("IMP_MIN_HEALTHY_LOW")
                .doesNotContain("IMP_OVERSHOOT");
        JsonNode saved = JSON.readTree(body.snapshotToStore().payloadJson()).at("/owned/instanceMaintenancePolicy");
        assertThat(saved.path("state").asText()).isEqualTo("SET");
        assertThat(saved.path("min").asInt()).isEqualTo(90);
        assertThat(saved.path("max").asInt()).isEqualTo(150);
    }

    @Test
    void aPolicyXamOpsAlreadyOwnsStaysOwnedInTheNextPlan() throws IOException {
        setUp(FakeAws.asg("web-asg").instanceMaintenancePolicy(software.amazon.awssdk.services.autoscaling.model
                .InstanceMaintenancePolicy.builder().minHealthyPercentage(100).maxHealthyPercentage(200).build()).build());
        String desired = "{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}";
        PlanBody owned = plan("conservative", new PlanOptions(null, desired, true));
        assertThat(JSON.readTree(owned.targetJson()).path("instanceMaintenancePolicy").path("owned").asBoolean()).isTrue();
        // The same live values with no ownership record are the customer's own.
        PlanBody theirs = plan("conservative");
        assertThat(JSON.readTree(theirs.targetJson()).has("instanceMaintenancePolicy")).isFalse();
    }

    /** AD-08: the live fingerprint carries the policy exactly when desired_config says XamOps owns it. */
    @Test
    void theLiveFingerprintIncludesTheMaintenancePolicyOnlyForAnOwnedOne() {
        AutoScalingGroup g = FakeAws.asg("web-asg").instanceMaintenancePolicy(software.amazon.awssdk.services.autoscaling
                .model.InstanceMaintenancePolicy.builder().minHealthyPercentage(100).maxHealthyPercentage(200).build()).build();
        setUp(g);
        AwsGroupFacts facts = AwsGroupFacts.from(FakeAws.REGION, g);
        SpotGroup row = new SpotGroup();
        row.setCloudAccountId(7L);
        row.setGroupKey(FakeAws.REGION + "/web-asg");
        assertThat(provider.getStatus(row, aws.view()).liveFingerprint()).isEqualTo(AwsFingerprint.of(facts, false));
        row.setState(com.xammer.cloud.domain.autospotting.GroupState.ACTIVE);
        row.setDesiredConfig("{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}");
        assertThat(provider.getStatus(row, aws.view()).liveFingerprint()).isEqualTo(AwsFingerprint.of(facts, true));
        // D-23b-01 review #8: status reads ownership through the planning gate, so a reverted row's stale marker
        // (written before the fix) does not count.
        row.setState(com.xammer.cloud.domain.autospotting.GroupState.REVERTED);
        assertThat(provider.getStatus(row, aws.view()).liveFingerprint()).isEqualTo(AwsFingerprint.of(facts, false));
        // A conversion writes desired_config and the fingerprint together: ask with the NEW config, not the old row.
        row.setDesiredConfig(null);
        assertThat(provider.liveFingerprintFor(row, "{\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,"
                + "\"max\":200}}", aws.view())).isEqualTo(AwsFingerprint.of(facts, true));
    }

    @Test
    void theMockedSdkRecordsZeroMutatingCallsDuringPlanAndEligibility() {
        setUp(FakeAws.asg("web-asg").build());
        plan("balanced");
        provider.checkEligibility(ref(), aws.view(), new Profile("conservative", null), EvalMode.PLAN);
        provider.checkEligibility(ref(), aws.view(), new Profile("conservative", null), EvalMode.LIST);
        provider.takeSnapshot(ref(), aws.view());
        aws.assertReadOnly();
    }

    @Test
    void theTargetNeverCarriesFieldsTheProductDoesNotOwn() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        String target = plan("conservative").targetJson();
        assertThat(target).doesNotContain("SpotMaxPrice", "spotMaxPrice", "desiredCapacity", "minSize", "maxSize",
                "healthCheck", "vpcZoneIdentifier", "tags");
    }

    @Test
    void estimatesAreOnePerProfileAndPricedFromOnDemandMinusMedianSpotTimes730() throws IOException {
        setUp(FakeAws.asg("web-asg").build());
        List<PlanBody.Estimate> estimates = plan("conservative").estimates();
        assertThat(estimates).extracting(PlanBody.Estimate::profile).containsExactly("conservative", "balanced", "aggressive");
        assertThat(estimates).allSatisfy(e -> assertThat(e.label()).isEqualTo("Estimate"));
        // OD 0.096, Spot 0.030: (0.096 - 0.030) * 730 = 48.18 per Spot instance; 1, 2 and 3 Spot instances at desired 4.
        assertThat(estimates.get(0).monthlySavings()).isEqualTo(48.18);
        assertThat(estimates.get(1).monthlySavings()).isEqualTo(96.36);
        assertThat(estimates.get(2).monthlySavings()).isEqualTo(144.54);
        JsonNode sources = JSON.readTree(estimates.get(0).priceSourcesJson());
        assertThat(sources.findValuesAsText("source")).containsExactly("aws-price-list", "ec2-spot-price-history");
        assertThat(sources.findValuesAsText("asOf")).allMatch(s -> s != null && !s.isBlank());
    }

    @Test
    void unpricedIsNotPricedNeverZero() {
        setUp(FakeAws.asg("web-asg").build());
        when(pricing.getProducts(any(GetProductsRequest.class))).thenThrow(SdkClientException.create("down"));
        List<PlanBody.Estimate> estimates = plan("conservative").estimates();
        assertThat(estimates).hasSize(3);
        assertThat(estimates).allSatisfy(e -> assertThat(e.monthlySavings()).isNull());
    }

    @Test
    void anUnmatchedSkuIsNotPricedNeverZero() {
        setUp(FakeAws.asg("web-asg").build());
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(List.of()).build());
        assertThat(plan("conservative").estimates()).allSatisfy(e -> assertThat(e.monthlySavings()).isNull());
    }

    @Test
    void desiredTwoGivesNoSpotForConservativeAndBalancedAndSavingsOfZeroOnlyForThose() {
        setUp(FakeAws.asg("web-asg").desiredCapacity(2).minSize(1).build());
        PlanBody body = plan("conservative");
        assertThat(body.eligibility().blockers()).extracting(c -> c.code()).contains("PROFILE_NO_SPOT");
        assertThat(body.estimates().get(0).monthlySavings()).isEqualTo(0.0);
        assertThat(body.estimates().get(1).monthlySavings()).isEqualTo(0.0);
        assertThat(body.estimates().get(2).monthlySavings()).isEqualTo(48.18);
    }

    @Test
    void aBlockedGroupStillReturnsAPlanBodyWithTheBlockers() {
        setUp(FakeAws.asg("web-asg").tags(FakeAws.tag("eks:cluster-name", "c")).build());
        PlanBody body = plan("conservative");
        assertThat(body.eligibility().blockers()).extracting(c -> c.code()).contains("KUBERNETES_NODES");
        assertThat(body.targetJson()).isNotNull();
    }

    @Test
    void aGroupNotInTheCloudYieldsAnEmptyBodyWithGroupNotFound() {
        setUp(FakeAws.asg("other-asg").build());
        PlanBody body = plan("conservative");
        assertThat(body.currentJson()).isNull();
        assertThat(body.eligibility().blockers()).extracting(c -> c.code()).containsExactly("GROUP_NOT_FOUND");
    }

    @Test
    void anExistingMixedPolicyKeepsItsOverridesAndALowestPriceStrategy() throws IOException {
        AutoScalingGroup g = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .tags(FakeAws.tag("xamops:autospotting:group-id", "g1"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc").version("$Default").build())
                        .overrides(LaunchTemplateOverrides.builder().instanceType("m5.large").build(),
                                LaunchTemplateOverrides.builder().instanceType("m5a.large").build(),
                                LaunchTemplateOverrides.builder().instanceType("m6i.large").build(),
                                LaunchTemplateOverrides.builder().instanceType("m6a.large").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandPercentageAboveBaseCapacity(100)
                                .spotAllocationStrategy("lowest-price").spotInstancePools(4).build()).build()).build();
        setUp(g);
        JsonNode target = JSON.readTree(plan("conservative").targetJson());
        assertThat(target.path("overrides")).hasSize(4);
        assertThat(target.at("/instancesDistribution/spotAllocationStrategy").asText()).isEqualTo("lowest-price");
        assertThat(target.at("/instancesDistribution/spotInstancePools").asInt()).isEqualTo(4);
    }

    @Test
    void thePlanIsDeterministicForTheSameInputs() {
        setUp(FakeAws.asg("web-asg").build());
        PlanBody a = plan("conservative");
        PlanBody b = plan("conservative");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aLegacyTaggedGroupSnapshotIsReconstructedAndInexact() {
        setUp(FakeAws.asg("web-asg").tags(FakeAws.tag("spot-enabled", "true")).build());
        assertThat(provider.takeSnapshot(ref(), aws.view()).exact()).isFalse();
    }

    @Test
    void statusDistinguishesFoundFromNotFound() {
        setUp(FakeAws.asg("web-asg").build());
        SpotGroup row = new SpotGroup();
        row.setCloudAccountId(7L);
        row.setGroupKey("ap-south-1/web-asg");
        GroupStatus s = provider.getStatus(row, aws.view());
        assertThat(s.found()).isTrue();
        row.setGroupKey("ap-south-1/missing");
        assertThat(provider.getStatus(row, aws.view()).found()).isFalse();
    }

    @Test
    void reconcileMakesHealthDecisionsAndNeverAFallbackForAHealthFailureWithoutASpotCause() {
        setUp(FakeAws.asg("web-asg").build());
        SpotGroup active = new SpotGroup();
        active.setState(GroupState.ACTIVE);
        GroupStatus unhealthy = new GroupStatus(true, "fp", "{\"inService\":2,\"recentFailedActivities\":3}",
                "{\"desired\":4}", false);
        // No Spot cause: DEGRADED with an alert (FR-HLT-04, AC-HLT-03), never a fallback.
        assertThat(provider.reconcile(active, unhealthy))
                .containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        SpotGroup degraded = new SpotGroup();
        degraded.setState(GroupState.DEGRADED);
        GroupStatus healthy = new GroupStatus(true, "fp", "{\"inService\":4,\"recentFailedActivities\":0}",
                "{\"desired\":4}", false);
        assertThat(provider.reconcile(degraded, healthy)).containsExactly(ReconcileAction.MARK_RECOVERED);
        assertThat(provider.reconcile(active, healthy)).isEmpty();
        assertThat(provider.reconcile(active, GroupStatus.notFound())).isEmpty();
        assertThat(provider.reconcile(active, unhealthy)).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void theMutatingProgramsReturnProviderNotReady() {
        setUp(FakeAws.asg("web-asg").build());
        OperationContext op = new OperationContext(UUID.randomUUID(), OperationKind.ENABLE, new SpotGroup(), null, null);
        for (Runnable call : List.<Runnable>of(() -> provider.executeConversion(op),
                () -> provider.restore(op, plan("conservative").snapshotToStore()))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(AutoSpottingApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        }
        aws.assertReadOnly();
    }

    @Test
    void theStrategyIsPreservedOnlyForLowestPriceAndCapacityOptimizedPrioritized() {
        assertThat(strategyOf("lowest-price")).isEqualTo("lowest-price");
        assertThat(strategyOf("capacity-optimized-prioritized")).isEqualTo("capacity-optimized-prioritized");
        assertThat(strategyOf("capacity-optimized")).isEqualTo("price-capacity-optimized");
        assertThat(strategyOf(null)).isEqualTo("price-capacity-optimized");
    }

    private static String strategyOf(String s) {
        AutoScalingGroup g = FakeAws.asg("x").launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().instancesDistribution(
                        InstancesDistribution.builder().spotAllocationStrategy(s).build()).build()).build();
        return AwsSpotProvider.preservedStrategy(AwsGroupFacts.from("ap-south-1", g));
    }
}
