package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.ec2.model.CpuOptions;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ecs.model.DescribeCapacityProvidersRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.3 (AC-ELG-01..05, AC-CMP-04, AC-CMP-06): the evaluator over mocked SDK clients.
 * LIST reads only the list's describe, the template and AMI, and cached passes; PLAN and CONFIRM read
 * everything fresh. Same codes and shape in all three modes; a failed read is CHECK_FAILED.
 */
class AwsEligibilityEvaluatorTest {

    private final CloudAccount account = FakeAws.account();
    private final EligibilityCache cache = mock(EligibilityCache.class);
    private final GroupLookup lookup = mock(GroupLookup.class);
    private final AwsCommitmentUtilization commitments = mock(AwsCommitmentUtilization.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Profile conservative = new Profile("conservative", null);

    private FakeAws aws;
    private AwsEligibilityEvaluator evaluator;

    private void setUp(boolean regionCovered, FakeAws fake) {
        aws = fake;
        when(commitments.thresholdPercent()).thenReturn(80.0);
        when(commitments.utilization(any())).thenReturn(java.util.Optional.empty());
        AwsViewSource views = (a, r) -> aws.view();
        AwsEstimatePriceService prices = new AwsEstimatePriceService(mock(software.amazon.awssdk.services.pricing.PricingClient.class), views);
        AwsRegionCoverage coverage = new AwsRegionCoverage() {
            @Override
            public boolean covered(CloudAccount a, String region) {
                return regionCovered;
            }
        };
        evaluator = new AwsEligibilityEvaluator(views, new AwsGroupStatusReader(), new AwsPlanReader(prices), cache,
                commitments, coverage, lookup, new AutoSpottingMetrics(registry), 12);
    }

    private void setUp(AutoScalingGroupHolder g) {
        setUp(true, new FakeAws().happyPath(g.group));
    }

    private record AutoScalingGroupHolder(software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup group) {
    }

    private static AutoScalingGroupHolder group(software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup.Builder b) {
        return new AutoScalingGroupHolder(b.build());
    }

    private EligibilityResult eval(EvalMode mode) {
        return evaluator.evaluate(account, "ap-south-1/web-asg", conservative, mode).result();
    }

    private static Set<String> codes(List<Check> checks) {
        return checks.stream().map(Check::code).collect(Collectors.toSet());
    }

    @Test
    void aCleanGroupInACoveredRegionIsEligibleInPlanMode() {
        setUp(group(FakeAws.asg("web-asg")));
        EligibilityResult r = eval(EvalMode.PLAN);
        assertThat(r.blockers()).isEmpty();
        assertThat(r.deferred()).isEmpty();
        assertThat(r.eligible()).isTrue();
        aws.assertReadOnly();
    }

    @Test
    void aRegionWithoutVerifiedCoverageIsNotCovered() {
        setUp(false, new FakeAws().happyPath(FakeAws.asg("web-asg").build()));
        assertThat(codes(eval(EvalMode.PLAN).blockers())).containsExactly("REGION_NOT_COVERED");
        assertThat(new AwsRegionCoverage().covered(account, "ap-south-1")).isFalse();
    }

    @Test
    void kubernetesNodesBlocked() {
        setUp(group(FakeAws.asg("web-asg").tags(FakeAws.tag("eks:nodegroup-name", "ng-1"))));
        for (EvalMode mode : EvalMode.values()) {
            assertThat(codes(eval(mode).blockers())).as("%s", mode).contains("KUBERNETES_NODES");
        }
    }

    @Test
    void kubernetesCheckErrorIsCheckFailed() {
        setUp(true, new FakeAws().happyPath(FakeAws.asg("web-asg").build()));
        doThrow(AutoScalingException.builder().statusCode(403).message("denied").build())
                .when(aws.asg).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        for (EvalMode mode : EvalMode.values()) {
            EligibilityResult r = eval(mode);
            // Never proceeds on failure (today's EKS check does, F-15): the lock is fail-closed.
            assertThat(r.eligible()).isFalse();
            Check failed = r.blockers().stream().filter(b -> b.id().contains("KUBERNETES_NODES")).findFirst().orElseThrow();
            assertThat(failed.code()).isEqualTo("CHECK_FAILED");
            assertThat(failed.errorKind()).isEqualTo(ErrorKind.PERMISSION);
        }
    }

    @Test
    void aLoadBalancedGroupWithEc2HealthIsBlockedWithRemediationText() {
        setUp(group(FakeAws.asg("web-asg").healthCheckType("EC2").targetGroupARNs("arn:aws:elasticloadbalancing:tg/web")));
        for (EvalMode mode : EvalMode.values()) {
            EligibilityResult r = eval(mode);
            Check b = r.blockers().stream().filter(c -> c.code().equals("EC2_HEALTH_WITH_LB")).findFirst().orElseThrow();
            assertThat(b.text()).contains("HealthCheckType=ELB").contains("plan again");
            assertThat(r.eligible()).isFalse();
        }
    }

    @Test
    void sameCodesIdsAndShapeInAllThreeModes() {
        setUp(group(FakeAws.asg("web-asg").healthCheckType("EC2").targetGroupARNs("arn:tg")
                .tags(FakeAws.tag("elasticbeanstalk:environment-name", "e"), FakeAws.tag("aws:cloudformation:stack-name", "s"))
                .desiredCapacity(4).maxSize(4)));
        EligibilityResult list = eval(EvalMode.LIST);
        EligibilityResult plan = eval(EvalMode.PLAN);
        EligibilityResult confirm = eval(EvalMode.CONFIRM);
        assertThat(codes(list.blockers())).isEqualTo(codes(plan.blockers())).isEqualTo(codes(confirm.blockers()))
                .contains("EC2_HEALTH_WITH_LB", "ELASTIC_BEANSTALK");
        assertThat(list.blockers().stream().map(Check::id).toList()).isEqualTo(plan.blockers().stream().map(Check::id).toList());
        assertThat(codes(list.warnings())).containsAll(Set.of("IAC_OWNED", "NO_HEADROOM"));
        assertThat(list.warnings().stream().map(Check::id).toList()).containsAll(
                plan.warnings().stream().filter(w -> Set.of("IAC_OWNED", "NO_HEADROOM").contains(w.code())).map(Check::id).toList());
        assertThat(plan.deferred()).isEmpty();
        assertThat(confirm.deferred()).isEmpty();
        // LIST defers what it did not read, with the same shape.
        assertThat(codes(list.deferred())).contains("ECS_MANAGED_TERMINATION", "PERMISSION_MISSING", "SPOT_VCPU_QUOTA");
        assertThat(list.deferred()).allSatisfy(d -> assertThat(d.id()).startsWith("d-"));
    }

    @Test
    void listModeMakesNoExpensiveCallsAndUsesOnlyCachedPasses() {
        setUp(group(FakeAws.asg("web-asg")));
        when(cache.isPassCached(eq(7L), eq("ap-south-1/web-asg"), eq("PERMISSION_MISSING"), anyString())).thenReturn(true);
        EligibilityResult r = eval(EvalMode.LIST);
        assertThat(codes(r.deferred())).doesNotContain("PERMISSION_MISSING").contains("REFRESH_IN_PROGRESS");
        assertThat(aws.calls("simulatePrincipalPolicy")).isZero();
        assertThat(aws.calls("getServiceQuota")).isZero();
        assertThat(aws.calls("describeInstanceRefreshes")).isZero();
        assertThat(aws.calls("describeCapacityProviders")).isZero();
        assertThat(aws.calls("describeSpotPriceHistory")).isZero();
        // The one template read and the one AMI read per distinct version are made.
        assertThat(aws.calls("describeLaunchTemplateVersions")).isEqualTo(1);
        assertThat(aws.calls("describeImages")).isEqualTo(1);
        verify(cache, never()).recordPass(any(), anyString(), anyString(), anyString());
        aws.assertReadOnly();
    }

    @Test
    void planModeCachesPassesAndNeverCachesAFailure() {
        setUp(group(FakeAws.asg("web-asg")));
        doThrow(SdkClientException.create("boom")).when(aws.ecs).describeCapacityProviders(any(DescribeCapacityProvidersRequest.class));
        EligibilityResult r = eval(EvalMode.PLAN);
        Check failed = r.blockers().stream().filter(b -> b.id().contains("ECS_MANAGED_TERMINATION")).findFirst().orElseThrow();
        assertThat(failed.code()).isEqualTo("CHECK_FAILED");
        assertThat(failed.errorKind()).isEqualTo(ErrorKind.TRANSIENT);
        verify(cache, never()).recordPass(any(), anyString(), eq("ECS_MANAGED_TERMINATION"), anyString());
        verify(cache).recordPass(eq(7L), eq("ap-south-1/web-asg"), eq("REFRESH_IN_PROGRESS"), anyString());
        verify(cache).recordPass(eq(7L), eq("ap-south-1/web-asg"), eq("PERMISSION_MISSING"), anyString());
    }

    @Test
    void aBlockerIsNeverCachedAsAPass() {
        setUp(group(FakeAws.asg("web-asg")));
        when(lookup.ebsHoldsLease(any(), any(), anyString())).thenReturn(true);
        EligibilityResult r = eval(EvalMode.PLAN);
        assertThat(codes(r.blockers())).contains("OTHER_FEATURE_IN_FLIGHT");
        assertThat(r.blockers().stream().filter(b -> b.code().equals("OTHER_FEATURE_IN_FLIGHT")).findFirst().orElseThrow().text())
                .contains("EBS automation");
    }

    @Test
    void aSimulateFailureIsCheckFailedNamingThePermissionCheck() {
        setUp(group(FakeAws.asg("web-asg")));
        doThrow(software.amazon.awssdk.services.iam.model.IamException.builder().statusCode(403).message("no").build())
                .when(aws.iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
        Check failed = eval(EvalMode.PLAN).blockers().stream().filter(b -> b.id().contains("PERMISSION_MISSING")).findFirst().orElseThrow();
        assertThat(failed.code()).isEqualTo("CHECK_FAILED");
        assertThat(failed.errorKind()).isEqualTo(ErrorKind.PERMISSION);
    }

    @Test
    void listModeCountsBlockersOnTheEligibilityMetric() {
        setUp(group(FakeAws.asg("web-asg").healthCheckType("EC2").targetGroupARNs("arn:tg")));
        eval(EvalMode.LIST);
        eval(EvalMode.LIST);
        eval(EvalMode.PLAN); // not counted
        assertThat(registry.find(AutoSpottingMetrics.ELIGIBILITY_BLOCKED).tag("code", "EC2_HEALTH_WITH_LB").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find(AutoSpottingMetrics.ELIGIBILITY_BLOCKED).tag("provider", "aws").tag("code", "EC2_HEALTH_WITH_LB")
                .counter()).isNotNull();
    }

    // ---- quotas (design-aws.md §7.3) ----

    private void quota(double limit) {
        doReturn(GetServiceQuotaResponse.builder().quota(ServiceQuota.builder().value(limit).build()).build())
                .when(aws.quotas).getServiceQuota(any(GetServiceQuotaRequest.class));
    }

    @Test
    void aQuotaBelowTheNeedIsBlocked() {
        setUp(group(FakeAws.asg("web-asg")));
        quota(12); // usage 10 from the metric: 2 left, needed ceil(1 * 1.1) * 2 = 4
        assertThat(codes(eval(EvalMode.PLAN).blockers())).contains("SPOT_VCPU_QUOTA");
    }

    @Test
    void aFivePercentMarginGivesTheLowQuotaWarningNotABlocker() {
        setUp(group(FakeAws.asg("web-asg")));
        quota(100);
        doReturn(software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse.builder().metricDataResults(
                software.amazon.awssdk.services.cloudwatch.model.MetricDataResult.builder().id("u").values(91.0).build()).build())
                .when(aws.cloudWatch).getMetricData(any(GetMetricDataRequest.class));
        EligibilityResult r = eval(EvalMode.PLAN);
        assertThat(codes(r.blockers())).doesNotContain("SPOT_VCPU_QUOTA");
        assertThat(codes(r.warnings())).contains("SPOT_VCPU_QUOTA_LOW");
    }

    @Test
    void aGetServiceQuotaFaultIsCheckFailed() {
        setUp(group(FakeAws.asg("web-asg")));
        doThrow(software.amazon.awssdk.services.servicequotas.model.ServiceQuotasException.builder().statusCode(500).build())
                .when(aws.quotas).getServiceQuota(any(GetServiceQuotaRequest.class));
        Check failed = eval(EvalMode.PLAN).blockers().stream().filter(b -> b.id().contains("SPOT_VCPU_QUOTA")).findFirst().orElseThrow();
        assertThat(failed.code()).isEqualTo("CHECK_FAILED");
        assertThat(failed.errorKind()).isEqualTo(ErrorKind.TRANSIENT);
    }

    @Test
    void aCloudWatchFaultFallsBackToTheDescribeInstancesVcpuSum() {
        setUp(group(FakeAws.asg("web-asg")));
        quota(100);
        doThrow(SdkClientException.create("cw down")).when(aws.cloudWatch).getMetricData(any(GetMetricDataRequest.class));
        doReturn(DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(
                Instance.builder().instanceId("s-1").cpuOptions(CpuOptions.builder().coreCount(10).threadsPerCore(2).build()).build())
                .build()).build()).when(aws.ec2).describeInstances(any(DescribeInstancesRequest.class));
        AwsEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account, "ap-south-1/web-asg", conservative, EvalMode.PLAN);
        assertThat(ev.spotQuota().usage()).isEqualTo(20.0);
        assertThat(ev.spotQuota().source()).isEqualTo("describe-instances");
        assertThat(codes(ev.result().blockers())).doesNotContain("SPOT_VCPU_QUOTA");
    }

    @Test
    void theOnDemandQuotaShortfallIsOnlyAWarning() {
        setUp(group(FakeAws.asg("web-asg")));
        doReturn(GetServiceQuotaResponse.builder().quota(ServiceQuota.builder().value(11.0).build()).build())
                .when(aws.quotas).getServiceQuota(any(GetServiceQuotaRequest.class));
        EligibilityResult r = eval(EvalMode.PLAN);
        assertThat(codes(r.warnings())).contains("OD_VCPU_QUOTA_LOW");
        assertThat(codes(r.blockers())).doesNotContain("OD_VCPU_QUOTA");
    }

    @Test
    void aGroupNotInTheCloudIsGroupNotFound() {
        setUp(true, new FakeAws().happyPath());
        assertThat(codes(eval(EvalMode.PLAN).blockers())).containsExactly("GROUP_NOT_FOUND");
    }

    @Test
    void aReadOnlyConnectionAndAnOldStackBlockInEveryMode() {
        CloudAccount readOnly = FakeAws.account();
        readOnly.setAccessType("read-only");
        readOnly.setStackTemplateVersion(null);
        setUp(group(FakeAws.asg("web-asg")));
        for (EvalMode mode : EvalMode.values()) {
            EligibilityResult r = evaluator.evaluate(readOnly, "ap-south-1/web-asg", conservative, mode).result();
            assertThat(codes(r.blockers())).contains("READ_ONLY_CONNECTION", "STACK_TOO_OLD");
        }
    }

    @Test
    void anOldStackBlocksEnableNamingTheMissingCapabilitiesButListsAndNeverNeedsOne() {
        // AC-ONB-01, AC-ONB-02: the old stack still lists and reads; only the ENABLE plan is blocked.
        CloudAccount old = FakeAws.account();
        old.setAccessType("read-write");
        old.setStackTemplateVersion("rw-2026.10.1");
        setUp(group(FakeAws.asg("web-asg")));
        EligibilityResult plan = evaluator.evaluate(old, "ap-south-1/web-asg", conservative, EvalMode.PLAN).result();
        assertThat(plan.blockers()).filteredOn(c -> "STACK_TOO_OLD".equals(c.code()))
                .singleElement().satisfies(c -> assertThat(c.text()).contains("events from every region"));
        EligibilityResult list = evaluator.evaluate(old, "ap-south-1/web-asg", conservative, EvalMode.LIST).result();
        assertThat(codes(list.blockers())).doesNotContain("GROUP_NOT_FOUND", "CHECK_FAILED");
    }
    @Test
    void aPreAutoSpottingStackNamesEveryMissingCapability() {
        CloudAccount old = FakeAws.account();
        old.setAccessType("read-write");
        old.setStackTemplateVersion(null);
        setUp(group(FakeAws.asg("web-asg")));
        EligibilityResult plan = evaluator.evaluate(old, "ap-south-1/web-asg", conservative, EvalMode.PLAN).result();
        assertThat(plan.blockers()).filteredOn(c -> "STACK_TOO_OLD".equals(c.code()))
                .singleElement().satisfies(c -> assertThat(c.text())
                        .contains("events from every region", "permission self-check"));
    }
    @Test
    void theSelectorHonoursTheConfiguredTargetCount() {
        setUp(group(FakeAws.asg("web-asg")));
        AwsEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account, "ap-south-1/web-asg", conservative, EvalMode.PLAN);
        assertThat(ev.typePlan().selection().types()).startsWith("m5.large").hasSizeLessThanOrEqualTo(12)
                .doesNotContain("t3.large", "c5.large");
    }
}
