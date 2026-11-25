package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.AccountFacts;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.CommitmentInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.HookInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.Inputs;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.ProfileChoice;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.QuotaInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.TypeInfo;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.WarmPoolConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plan-phase-12.md 12.3: one fixture per blocker and per warning (design-aws.md §7.2, §7.3), driven at
 * the pure rules layer. The same rules serve LIST, PLAN and CONFIRM (AC-ELG-05).
 */
class AwsEligibilityRulesTest {

    // ---- the all-clear baseline ----

    static AwsLaunchInfo launch(Function<LaunchBuilder, LaunchBuilder> f) {
        return f.apply(new LaunchBuilder()).build();
    }

    static final class LaunchBuilder {
        boolean marketOptions;
        String marketType;
        String tenancy = "default";
        String hostGroup;
        String placementGroup;
        String crPreference;
        boolean crTarget;
        String platform = "Linux/UNIX";

        AwsLaunchInfo build() {
            return new AwsLaunchInfo(7, "sha", "m5.large", "ami-1", marketOptions, marketType, tenancy, hostGroup,
                    placementGroup, crPreference, crTarget, null, null, 0, false, 0, false, "x86_64", "legacy-bios",
                    true, platform, "RunInstances");
        }
    }

    static Inputs clean(AutoScalingGroup g) {
        return new Inputs(Sig.ok(Optional.of(AwsGroupFacts.from("ap-south-1", g))),
                new AccountFacts(false, "rw-2026.11.1", true), null,
                Sig.ok(launch(b -> b)), Sig.ok(false), Sig.ok(false), Sig.ok(false), Sig.ok(List.of()),
                Sig.ok(new QuotaInfo("L-34B43A08", 1000, 10, 4, true, "cloudwatch")),
                Sig.ok(new QuotaInfo("L-1216C47A", 1000, 10, 4, false, "cloudwatch")),
                Sig.ok(List.of()), Sig.ok(30), Sig.ok(new CommitmentInfo(null, 80)),
                Sig.ok(new TypeInfo(new AwsTypeSelector.Selection(List.of("m5.large", "m5a.large", "m6i.large", "m6a.large"), 4), false)),
                Sig.ok(false));
    }

    static AutoScalingGroup.Builder asg() {
        return FakeAws.asg("web-asg");
    }

    /** A customer policy that already keeps full capacity: XamOps leaves it, with no warning (AD-08). */
    static InstanceMaintenancePolicy safePolicy() {
        return InstanceMaintenancePolicy.builder().minHealthyPercentage(100).maxHealthyPercentage(150).build();
    }

    private static List<String> warningCodes(Inputs in) {
        return AwsEligibilityRules.evaluate(in).warnings().stream().map(EligibilityResult.Check::code).toList();
    }

    @Test
    void aGroupWithNoMaintenancePolicyWarnsThatItCanBrieflyRunAtTwiceItsSize() {
        EligibilityResult r = AwsEligibilityRules.evaluate(clean(asg().build()));
        assertThat(r.blockers()).isEmpty();
        assertThat(r.warnings()).extracting(EligibilityResult.Check::code).contains("IMP_OVERSHOOT");
        assertThat(r.warnings().stream().filter(w -> w.code().equals("IMP_OVERSHOOT")).findFirst().orElseThrow().text())
                .contains("twice its desired size").contains("removes the setting when you revert");
        // AWS reports a cleared policy as -1/-1: it counts as none.
        assertThat(warningCodes(clean(asg().instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                .minHealthyPercentage(-1).maxHealthyPercentage(-1).build()).build()))).contains("IMP_OVERSHOOT");
    }

    @Test
    void aCustomerPolicyIsNeverOverwrittenAndWarnsOnlyWhenItAllowsACapacityDip() {
        assertThat(warningCodes(clean(asg().instanceMaintenancePolicy(safePolicy()).build())))
                .doesNotContain("IMP_OVERSHOOT", "IMP_MIN_HEALTHY_LOW");
        List<EligibilityResult.Check> low = AwsEligibilityRules.evaluate(clean(asg().instanceMaintenancePolicy(
                InstanceMaintenancePolicy.builder().minHealthyPercentage(90).maxHealthyPercentage(150).build()).build()))
                .warnings();
        assertThat(low).extracting(EligibilityResult.Check::code).contains("IMP_MIN_HEALTHY_LOW")
                .doesNotContain("IMP_OVERSHOOT");
        assertThat(low.stream().filter(w -> w.code().equals("IMP_MIN_HEALTHY_LOW")).findFirst().orElseThrow().text())
                .contains("90%").contains("does not change your policy");
        assertThat(AwsEligibilityRules.evaluate(clean(asg().instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                .minHealthyPercentage(100).maxHealthyPercentage(100).build()).build())).blockers())
                .extracting(EligibilityResult.Check::code).contains("IMP_TERMINATE_FIRST");
    }

    /** AWS API reference InstanceMaintenancePolicy: no documented default for an omitted Min, so none is assumed. */
    @Test
    void awsApiReferenceDocumentsNoDefaultSoACustomerPolicyWithMaxOnlyWarnsThatCapacityMayDip() {
        List<EligibilityResult.Check> maxOnly = AwsEligibilityRules.evaluate(clean(asg().instanceMaintenancePolicy(
                InstanceMaintenancePolicy.builder().maxHealthyPercentage(150).build()).build())).warnings();
        assertThat(maxOnly).extracting(EligibilityResult.Check::code).contains("IMP_MIN_HEALTHY_LOW")
                .doesNotContain("IMP_OVERSHOOT");
        assertThat(maxOnly.stream().filter(w -> w.code().equals("IMP_MIN_HEALTHY_LOW")).findFirst().orElseThrow().text())
                .contains("no minimum healthy percentage set").contains("capacity may dip")
                .doesNotContain("%").contains("does not change your policy");
        // Both absent and -1/-1 stay "no policy".
        assertThat(AwsMaintenancePolicy.classify(null, null)).isEqualTo(AwsMaintenancePolicy.Kind.ABSENT);
        assertThat(AwsMaintenancePolicy.classify(-1, -1)).isEqualTo(AwsMaintenancePolicy.Kind.ABSENT);
        assertThat(AwsMaintenancePolicy.classify(null, 150)).isEqualTo(AwsMaintenancePolicy.Kind.CUSTOMER_LOW_MIN);
        assertThat(AwsMaintenancePolicy.classify(100, null)).isEqualTo(AwsMaintenancePolicy.Kind.CUSTOMER_SAFE);
    }

    private static Inputs withTypes(Inputs base, TypeInfo types) {
        return new Inputs(base.group(), base.account(), null, base.launch(), base.ecsManagedTermination(),
                base.refreshInProgress(), base.otherFeatureInFlight(), base.missingPermissions(), base.spotQuota(),
                base.odQuota(), base.lifecycleHooks(), base.maxDeregistrationDelay(), base.commitment(), Sig.ok(types),
                base.openReservations());
    }

    @Test
    void aBurstableGroupWithNoTypesIsBlockedAndToldWhichBurstableTypesWouldFit() {
        Inputs in = withTypes(clean(asg().instanceMaintenancePolicy(safePolicy()).build()), new TypeInfo(
                new AwsTypeSelector.Selection(List.of("t3.micro"), 1), false, List.of("t3a.micro"), List.of()));
        EligibilityResult r = AwsEligibilityRules.evaluate(in);
        assertThat(r.blockers()).extracting(EligibilityResult.Check::code).containsExactly("NO_COMPATIBLE_TYPES");
        assertThat(r.blockers().get(0).text()).contains("burstable alternatives (t3a.micro)");
    }

    @Test
    void allowedBurstableTypesCarryAnAcknowledgedCpuCreditWarning() {
        Inputs in = withTypes(clean(asg().instanceMaintenancePolicy(safePolicy()).build()), new TypeInfo(
                new AwsTypeSelector.Selection(List.of("t3.micro", "t3a.micro", "m5.large", "m6i.large"), 4), false,
                List.of(), List.of("t3a.micro")));
        EligibilityResult r = AwsEligibilityRules.evaluate(in);
        assertThat(r.warnings()).extracting(EligibilityResult.Check::code).containsExactly("BURSTABLE_CREDITS");
        assertThat(r.warnings().get(0).text()).contains("CPU credits").contains("t3a.micro");
        // With the option off nothing burstable is selected, so there is no warning.
        assertThat(warningCodes(withTypes(clean(asg().instanceMaintenancePolicy(safePolicy()).build()),
                new TypeInfo(new AwsTypeSelector.Selection(List.of("m5.large", "m5a.large", "m6i.large", "m6a.large"), 4),
                        false)))).doesNotContain("BURSTABLE_CREDITS");
    }

    static Inputs withAsg(AutoScalingGroup g) {
        return clean(g);
    }

    @Test
    void aCleanGroupHasNoBlockerAndNoWarning() {
        EligibilityResult r = AwsEligibilityRules.evaluate(clean(asg().instanceMaintenancePolicy(safePolicy()).build()));
        assertThat(r.blockers()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.deferred()).isEmpty();
        assertThat(r.eligible()).isTrue();
    }

    // ---- one fixture per blocker ----

    record Case(String code, Inputs inputs) {
        @Override
        public String toString() {
            return code;
        }
    }

    private static Inputs with(Inputs in, Function<Inputs, Inputs> f) {
        return f.apply(in);
    }

    static Stream<Case> blockerCases() {
        Inputs base = clean(asg().build());
        AutoScalingGroup mixedSpot = asg().launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(
                                LaunchTemplateSpecification.builder().launchTemplateId("lt-1").version("$Default").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandPercentageAboveBaseCapacity(50).build()).build())
                .build();
        return Stream.of(
                new Case("GROUP_NOT_FOUND", new Inputs(Sig.ok(Optional.empty()), base.account(), null, base.launch(),
                        base.ecsManagedTermination(), base.refreshInProgress(), base.otherFeatureInFlight(),
                        base.missingPermissions(), base.spotQuota(), base.odQuota(), base.lifecycleHooks(),
                        base.maxDeregistrationDelay(), base.commitment(), base.types(), base.openReservations())),
                new Case("DESIRED_TOO_SMALL", clean(asg().desiredCapacity(1).minSize(1).build())),
                new Case("LAUNCH_CONFIGURATION", clean(asg().launchTemplate((LaunchTemplateSpecification) null)
                        .launchConfigurationName("lc-1").build())),
                new Case("KUBERNETES_NODES", clean(asg().tags(FakeAws.tag("eks:cluster-name", "prod")).build())),
                new Case("ELASTIC_BEANSTALK", clean(asg().tags(FakeAws.tag("elasticbeanstalk:environment-name", "e")).build())),
                new Case("WARM_POOL", clean(asg().warmPoolConfiguration(WarmPoolConfiguration.builder().build()).build())),
                new Case("EXTERNAL_SPOT", clean(mixedSpot)),
                new Case("CAPACITY_TYPE_UNSUPPORTED", clean(asg().desiredCapacityType("vcpu").build())),
                new Case("IMP_TERMINATE_FIRST", clean(asg().instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                        .minHealthyPercentage(90).maxHealthyPercentage(100).build()).build())),
                new Case("EC2_HEALTH_WITH_LB", clean(asg().healthCheckType("EC2").targetGroupARNs("arn:tg").build())),
                new Case("ECS_MANAGED_TERMINATION", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        Sig.ok(true), i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(), i.spotQuota(),
                        i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("REFRESH_IN_PROGRESS", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), Sig.ok(true), i.otherFeatureInFlight(), i.missingPermissions(), i.spotQuota(),
                        i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("OTHER_FEATURE_IN_FLIGHT", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), i.refreshInProgress(), Sig.ok(true), i.missingPermissions(), i.spotQuota(),
                        i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("PERMISSION_MISSING", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(),
                        Sig.ok(List.of("iam:PassRole")), i.spotQuota(), i.odQuota(), i.lifecycleHooks(),
                        i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("SPOT_VCPU_QUOTA", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(),
                        Sig.ok(new QuotaInfo("L-34B43A08", 100, 98, 4, true, "cloudwatch")), i.odQuota(), i.lifecycleHooks(),
                        i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("OD_VCPU_QUOTA", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(),
                        i.spotQuota(), Sig.ok(new QuotaInfo("L-1216C47A", 100, 99, 4, true, "cloudwatch")), i.lifecycleHooks(),
                        i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations()))),
                new Case("NO_COMPATIBLE_TYPES", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(),
                        i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(),
                        Sig.ok(new TypeInfo(new AwsTypeSelector.Selection(List.of("m5.large"), 1), false)), i.openReservations()))),
                new Case("REGION_NOT_COVERED", with(base, i -> new Inputs(i.group(), new AccountFacts(false, "rw-2026.11.1", false),
                        null, i.launch(), i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(),
                        i.missingPermissions(), i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(),
                        i.commitment(), i.types(), i.openReservations()))),
                new Case("READ_ONLY_CONNECTION", with(base, i -> new Inputs(i.group(), new AccountFacts(true, "rw-2026.11.1", true),
                        null, i.launch(), i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(),
                        i.missingPermissions(), i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(),
                        i.commitment(), i.types(), i.openReservations()))),
                new Case("STACK_TOO_OLD", with(base, i -> new Inputs(i.group(), new AccountFacts(false, "rw-2026.10.9", true),
                        null, i.launch(), i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(),
                        i.missingPermissions(), i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(),
                        i.commitment(), i.types(), i.openReservations()))),
                new Case("STACK_TOO_OLD", with(base, i -> new Inputs(i.group(), new AccountFacts(false, null, true),
                        null, i.launch(), i.ecsManagedTermination(), i.refreshInProgress(), i.otherFeatureInFlight(),
                        i.missingPermissions(), i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(),
                        i.commitment(), i.types(), i.openReservations()))),
                new Case("LT_MARKET_OPTIONS", launchCase(base, b -> {
                    b.marketOptions = true;
                    b.marketType = "spot";
                    return b;
                })),
                new Case("TENANCY_UNSUPPORTED", launchCase(base, b -> {
                    b.tenancy = "host";
                    return b;
                })),
                new Case("TENANCY_UNSUPPORTED", launchCase(base, b -> {
                    b.hostGroup = "arn:aws:resource-groups:...";
                    return b;
                })),
                new Case("CAPACITY_RESERVATION_ONLY", launchCase(base, b -> {
                    b.crPreference = "capacity-reservations-only";
                    return b;
                })),
                new Case("PROFILE_NO_SPOT", new Inputs(clean(asg().desiredCapacity(2).build()).group(), base.account(),
                        new ProfileChoice("conservative", AwsProfiles.byName(AwsProfiles.ProfileName.CONSERVATIVE)),
                        base.launch(), base.ecsManagedTermination(), base.refreshInProgress(), base.otherFeatureInFlight(),
                        base.missingPermissions(), base.spotQuota(), base.odQuota(), base.lifecycleHooks(),
                        base.maxDeregistrationDelay(), base.commitment(), base.types(), base.openReservations())),
                new Case("CHECK_FAILED", with(base, i -> new Inputs(i.group(), i.account(), null, i.launch(),
                        Sig.failed(ErrorKind.PERMISSION), i.refreshInProgress(), i.otherFeatureInFlight(),
                        i.missingPermissions(), i.spotQuota(), i.odQuota(), i.lifecycleHooks(), i.maxDeregistrationDelay(),
                        i.commitment(), i.types(), i.openReservations()))));
    }

    private static Inputs launchCase(Inputs base, Function<LaunchBuilder, LaunchBuilder> f) {
        return new Inputs(base.group(), base.account(), null, Sig.ok(launch(f)), base.ecsManagedTermination(),
                base.refreshInProgress(), base.otherFeatureInFlight(), base.missingPermissions(), base.spotQuota(),
                base.odQuota(), base.lifecycleHooks(), base.maxDeregistrationDelay(), base.commitment(), base.types(),
                base.openReservations());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blockerCases")
    void everyBlockerHasAFixture(Case c) {
        EligibilityResult r = AwsEligibilityRules.evaluate(c.inputs());
        assertThat(r.blockers()).extracting(EligibilityResult.Check::code).contains(c.code());
        assertThat(r.eligible()).isFalse();
        assertThat(r.blockers()).allSatisfy(b -> {
            assertThat(b.id()).isNotBlank();
            assertThat(b.text()).isNotBlank();
        });
    }

    // ---- one fixture per warning ----

    static Stream<Case> warningCases() {
        Inputs base = clean(asg().build());
        AutoScalingGroup cdk = asg().tags(FakeAws.tag("aws:cloudformation:stack-name", "stack")).build();
        return Stream.of(
                new Case("IAC_OWNED", clean(cdk)),
                new Case("IAC_OWNED", clean(asg().tags(FakeAws.tag("ManagedBy", "Terraform")).build())),
                new Case("LIFECYCLE_HOOKS", rebuild(base, null, Sig.ok(List.of(new HookInfo("drain", 300))), null, null, null)),
                new Case("DEREG_DELAY_LONG", rebuild(base, null, null, Sig.ok(600), null, null)),
                new Case("SCALE_IN_PROTECTION", clean(asg().newInstancesProtectedFromScaleIn(true).build())),
                new Case("NO_HEADROOM", clean(asg().maxSize(4).build())),
                new Case("SINGLE_AZ", clean(asg().availabilityZones(FakeAws.AZ_A).build())),
                new Case("FEW_TYPES", rebuild(base, null, null, null, Sig.ok(new TypeInfo(new AwsTypeSelector.Selection(List.of("m5.large", "m5a.large"), 3), false)), null)),
                new Case("XEN_TO_NITRO", rebuild(base, null, null, null, Sig.ok(new TypeInfo(new AwsTypeSelector.Selection(List.of("m5.large", "m6i.large", "m6a.large", "m5a.large"), 4), true)), null)),
                new Case("NON_LINUX", launchCase(base, b -> {
                    b.platform = "Windows";
                    return b;
                })),
                new Case("COMMITMENT_UTILIZATION", rebuild(base, Sig.ok(new CommitmentInfo(55.0, 80)), null, null, null, null)),
                new Case("CLUSTER_PLACEMENT", launchCase(base, b -> {
                    b.placementGroup = "hpc-pg";
                    return b;
                })),
                new Case("OPEN_CAPACITY_RESERVATION", rebuild(base, null, null, null, null, Sig.ok(true))),
                new Case("NO_INSTANCE_WARMUP", clean(asg().defaultInstanceWarmup((Integer) null).build())),
                new Case("SPOT_VCPU_QUOTA_LOW", withQuota(base, new QuotaInfo("L-34B43A08", 100, 87, 4, true, "cloudwatch"), null)),
                new Case("OD_VCPU_QUOTA_LOW", withQuota(base, null, new QuotaInfo("L-1216C47A", 100, 99, 4, false, "cloudwatch"))));
    }

    private static Inputs rebuild(Inputs i, Sig<CommitmentInfo> commit, Sig<List<HookInfo>> hooks, Sig<Integer> dereg,
                                  Sig<TypeInfo> types, Sig<Boolean> reservations) {
        return new Inputs(i.group(), i.account(), i.profile(), i.launch(), i.ecsManagedTermination(),
                i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(), i.spotQuota(), i.odQuota(),
                hooks != null ? hooks : i.lifecycleHooks(), dereg != null ? dereg : i.maxDeregistrationDelay(),
                commit != null ? commit : i.commitment(), types != null ? types : i.types(),
                reservations != null ? reservations : i.openReservations());
    }

    private static Inputs withQuota(Inputs i, QuotaInfo spot, QuotaInfo od) {
        return new Inputs(i.group(), i.account(), i.profile(), i.launch(), i.ecsManagedTermination(),
                i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(),
                spot != null ? Sig.ok(spot) : i.spotQuota(), od != null ? Sig.ok(od) : i.odQuota(),
                i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("warningCases")
    void everyWarningHasAFixture(Case c) {
        EligibilityResult r = AwsEligibilityRules.evaluate(c.inputs());
        assertThat(r.warnings()).extracting(EligibilityResult.Check::code).contains(c.code());
        assertThat(r.blockers()).as("a warning fixture has no blocker").isEmpty();
        assertThat(r.warnings()).allSatisfy(w -> assertThat(w.id()).startsWith("w-"));
    }

    // ---- specific behaviours ----

    @Test
    void ec2HealthWithLbHasRemediationTextAndNeedsNoAcknowledgment() {
        EligibilityResult r = AwsEligibilityRules.evaluate(clean(asg().healthCheckType("EC2")
                .instanceMaintenancePolicy(safePolicy()).loadBalancerNames("classic-lb").build()));
        assertThat(r.blockers()).extracting(EligibilityResult.Check::code).containsExactly("EC2_HEALTH_WITH_LB");
        assertThat(r.blockers().get(0).text()).contains("HealthCheckType=ELB").contains("plan again");
        assertThat(r.warnings()).isEmpty();
        // ELB health on the same group is fine.
        assertThat(AwsEligibilityRules.evaluate(clean(asg().healthCheckType("ELB").loadBalancerNames("classic-lb").build()))
                .blockers()).isEmpty();
    }

    @Test
    void aFailedReadIsCheckFailedNamingTheCheckAndKindNeverAPass() {
        Inputs base = clean(asg().build());
        EligibilityResult r = AwsEligibilityRules.evaluate(rebuildSig(base, Sig.failed(ErrorKind.THROTTLED)));
        assertThat(r.blockers()).hasSize(1);
        EligibilityResult.Check b = r.blockers().get(0);
        assertThat(b.code()).isEqualTo("CHECK_FAILED");
        assertThat(b.id()).contains("SPOT_VCPU_QUOTA");
        assertThat(b.errorKind()).isEqualTo(ErrorKind.THROTTLED);
        assertThat(r.eligible()).isFalse();
    }

    private static Inputs rebuildSig(Inputs i, Sig<QuotaInfo> spot) {
        return new Inputs(i.group(), i.account(), i.profile(), i.launch(), i.ecsManagedTermination(),
                i.refreshInProgress(), i.otherFeatureInFlight(), i.missingPermissions(), spot, i.odQuota(),
                i.lifecycleHooks(), i.maxDeregistrationDelay(), i.commitment(), i.types(), i.openReservations());
    }

    @Test
    void deferredChecksAreListedAndDoNotBlock() {
        Inputs base = clean(asg().build());
        Inputs deferred = new Inputs(base.group(), base.account(), null, base.launch(), Sig.deferred(), Sig.deferred(),
                base.otherFeatureInFlight(), Sig.deferred(), Sig.deferred(), Sig.deferred(), Sig.deferred(),
                Sig.deferred(), Sig.deferred(), Sig.deferred(), Sig.deferred());
        EligibilityResult r = AwsEligibilityRules.evaluate(deferred);
        assertThat(r.blockers()).isEmpty();
        assertThat(r.eligible()).isTrue();
        assertThat(r.deferred()).extracting(EligibilityResult.Check::code).contains("ECS_MANAGED_TERMINATION",
                "REFRESH_IN_PROGRESS", "PERMISSION_MISSING", "SPOT_VCPU_QUOTA", "NO_COMPATIBLE_TYPES");
    }

    @Test
    void anExistingMarkerOrLegacyTagMeansTheSpotShareIsXamOpsOwnNotExternal() {
        AutoScalingGroup.Builder spotShare = asg().launchTemplate((LaunchTemplateSpecification) null)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-1").version("1").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandPercentageAboveBaseCapacity(50).build()).build());
        assertThat(AwsEligibilityRules.evaluate(clean(spotShare.build())).blockers())
                .extracting(EligibilityResult.Check::code).contains("EXTERNAL_SPOT");
        assertThat(AwsEligibilityRules.evaluate(clean(spotShare.tags(FakeAws.tag("xamops:autospotting:group-id", "g1")).build()))
                .blockers()).extracting(EligibilityResult.Check::code).doesNotContain("EXTERNAL_SPOT");
        assertThat(AwsEligibilityRules.evaluate(clean(spotShare.tags(FakeAws.tag("spot-enabled", "true")).build()))
                .blockers()).extracting(EligibilityResult.Check::code).doesNotContain("EXTERNAL_SPOT");
    }

    @Test
    void aNullStackTemplateVersionIsOlderThanEveryCapability() {
        assertThat(AwsEligibilityRules.stackTooOld(null)).isTrue();
        assertThat(AwsEligibilityRules.stackTooOld("garbage")).isTrue();
        assertThat(AwsEligibilityRules.stackTooOld("rw-2026.10.12")).isTrue();
        assertThat(AwsEligibilityRules.stackTooOld("rw-2026.11.1")).isFalse();
        assertThat(AwsEligibilityRules.stackTooOld("rw-2026.11.2")).isFalse();
        assertThat(AwsEligibilityRules.stackTooOld("rw-2027.1.1")).isFalse();
    }

    @Test
    void warningIdsAreStablePerCodeAndDetail() {
        Inputs in = clean(asg().maxSize(4).build());
        List<String> first = AwsEligibilityRules.evaluate(in).warnings().stream().map(EligibilityResult.Check::id).toList();
        List<String> second = AwsEligibilityRules.evaluate(in).warnings().stream().map(EligibilityResult.Check::id).toList();
        assertThat(first).isEqualTo(second).isNotEmpty();
    }

    @Test
    void anUnknownProfileFailsClosed() {
        Inputs base = clean(asg().build());
        Inputs in = new Inputs(base.group(), base.account(), new ProfileChoice("turbo", null), base.launch(),
                base.ecsManagedTermination(), base.refreshInProgress(), base.otherFeatureInFlight(), base.missingPermissions(),
                base.spotQuota(), base.odQuota(), base.lifecycleHooks(), base.maxDeregistrationDelay(), base.commitment(),
                base.types(), base.openReservations());
        assertThat(AwsEligibilityRules.evaluate(in).blockers()).extracting(EligibilityResult.Check::code)
                .contains("CHECK_FAILED");
    }
}
