package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import org.mockito.Mockito;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeLifecycleHooksRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeLifecycleHooksResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeCapacityReservationsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeCapacityReservationsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion;
import software.amazon.awssdk.services.ec2.model.MemoryInfo;
import software.amazon.awssdk.services.ec2.model.NetworkInfo;
import software.amazon.awssdk.services.ec2.model.ProcessorInfo;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResponseLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.ec2.model.VCpuInfo;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeCapacityProvidersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeCapacityProvidersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupAttributesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupAttributesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupAttribute;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * A fake AWS for the AutoSpotting read-path tests: Mockito mocks of the AWS SDK clients with a
 * recorded-fixture-shaped "happy path". No test makes a real AWS call (AWS TEST SAFETY): every client
 * is a mock, and {@link #assertReadOnly()} proves a test made no mutating call (AC-PLN-01).
 */
final class FakeAws {

    static final String REGION = "ap-south-1";
    static final String AZ_A = "ap-south-1a";
    static final String AZ_B = "ap-south-1b";
    static final String ROLE_ARN = "arn:aws:iam::111122223333:role/XamOpsReadWrite";

    final AutoScalingClient asg = sdkMock(AutoScalingClient.class);
    final Ec2Client ec2 = sdkMock(Ec2Client.class);
    final ElasticLoadBalancingV2Client elbv2 = sdkMock(ElasticLoadBalancingV2Client.class);
    final CloudFormationClient cfn = sdkMock(CloudFormationClient.class);
    final EventBridgeClient events = sdkMock(EventBridgeClient.class);
    final ServiceQuotasClient quotas = sdkMock(ServiceQuotasClient.class);
    final CloudWatchClient cloudWatch = sdkMock(CloudWatchClient.class);
    final EcsClient ecs = sdkMock(EcsClient.class);
    final IamClient iam = sdkMock(IamClient.class);

    final List<AutoScalingGroup> groups = new ArrayList<>();

    /** A mock whose Consumer-builder default overloads run for real, delegating to the stubbed request overloads. */
    @SuppressWarnings("unchecked")
    static <T> T sdkMock(Class<T> type) {
        return Mockito.mock(type, inv -> {
            if (inv.getMethod().isDefault() && inv.getArguments().length == 1
                    && inv.getArguments()[0] instanceof Consumer) {
                return inv.callRealMethod();
            }
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        });
    }

    AwsReadOnlyView view() {
        return new AwsReadOnlyView(REGION, asg, ec2, elbv2, cfn, events, quotas, cloudWatch, ecs, iam);
    }

    // ---- accounts and groups ----

    static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setAwsAccountId("111122223333");
        a.setRoleArn(ROLE_ARN);
        a.setExternalId("ext-123");
        a.setAccessType("read-write");
        a.setStackTemplateVersion("rw-2026.11.1");
        a.setProvider("AWS");
        return a;
    }

    static TagDescription tag(String key, String value) {
        return TagDescription.builder().key(key).value(value).propagateAtLaunch(true)
                .resourceId("web-asg").resourceType("auto-scaling-group").build();
    }

    static Instance instance(String id, boolean protectedFromScaleIn) {
        return Instance.builder().instanceId(id).instanceType("m5.large").availabilityZone(AZ_A)
                .lifecycleState("InService").healthStatus("Healthy").protectedFromScaleIn(protectedFromScaleIn).build();
    }

    /** A plain launch-template group: 4 of 2..6, ELB health, warm-up set, in two AZs, no tags. */
    static AutoScalingGroup.Builder asg(String name) {
        return AutoScalingGroup.builder()
                .autoScalingGroupName(name)
                .autoScalingGroupARN("arn:aws:autoscaling:" + REGION + ":111122223333:autoScalingGroup:uuid:autoScalingGroupName/" + name)
                .launchTemplate(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc").version("$Default").build())
                .minSize(2).maxSize(8).desiredCapacity(4)
                .availabilityZones(AZ_A, AZ_B)
                .vpcZoneIdentifier("subnet-a,subnet-b")
                .healthCheckType("ELB").healthCheckGracePeriod(300).defaultInstanceWarmup(300)
                .instances(instance("i-1", false), instance("i-2", false), instance("i-3", false), instance("i-4", false));
    }

    FakeAws groups(AutoScalingGroup... gs) {
        groups.clear();
        groups.addAll(List.of(gs));
        doAnswer(inv -> {
            DescribeAutoScalingGroupsRequest r = inv.getArgument(0);
            List<AutoScalingGroup> match = r.autoScalingGroupNames().isEmpty() ? new ArrayList<>(groups)
                    : groups.stream().filter(g -> r.autoScalingGroupNames().contains(g.autoScalingGroupName())).toList();
            return DescribeAutoScalingGroupsResponse.builder().autoScalingGroups(match).build();
        }).when(asg).describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class));
        return this;
    }

    // ---- the happy path: every read a PLAN needs returns a clean answer ----

    FakeAws happyPath(AutoScalingGroup... gs) {
        groups(gs);
        doReturn(DescribeScalingActivitiesResponse.builder().build()).when(asg)
                .describeScalingActivities(any(DescribeScalingActivitiesRequest.class));
        doReturn(DescribeInstanceRefreshesResponse.builder().build()).when(asg)
                .describeInstanceRefreshes(any(DescribeInstanceRefreshesRequest.class));
        doReturn(DescribeLifecycleHooksResponse.builder().build()).when(asg)
                .describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class));
        // Launch template and AMI.
        doReturn(DescribeLaunchTemplateVersionsResponse.builder().launchTemplateVersions(
                LaunchTemplateVersion.builder().versionNumber(7L).launchTemplateData(
                        ResponseLaunchTemplateData.builder().imageId("ami-0aaa").instanceType("m5.large").build())
                        .build()).build()).when(ec2).describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class));
        doReturn(DescribeImagesResponse.builder().images(Image.builder().imageId("ami-0aaa").architecture("x86_64")
                .enaSupport(true).platformDetails("Linux/UNIX").usageOperation("RunInstances").build()).build())
                .when(ec2).describeImages(any(DescribeImagesRequest.class));
        // Instance types: the original, then the candidates of the same vCPU.
        List<InstanceTypeInfo> candidates = List.of(
                type("m5a.large", 2, 8192, true, true), type("m6i.large", 2, 8192, true, true),
                type("m5n.large", 2, 8192, true, true), type("m6a.large", 2, 8192, true, true),
                type("r5.large", 2, 16384, true, true), type("t3.large", 2, 8192, true, true, true),
                type("c5.large", 2, 4096, true, true));
        doAnswer(inv -> {
            DescribeInstanceTypesRequest r = inv.getArgument(0);
            if (!r.instanceTypesAsStrings().isEmpty()) {
                return DescribeInstanceTypesResponse.builder().instanceTypes(
                        r.instanceTypesAsStrings().stream().map(t -> type(t, 2, 8192, true, true)).toList()).build();
            }
            return DescribeInstanceTypesResponse.builder().instanceTypes(candidates).build();
        }).when(ec2).describeInstanceTypes(any(DescribeInstanceTypesRequest.class));
        List<String> all = new ArrayList<>(List.of("m5.large"));
        candidates.forEach(c -> all.add(c.instanceTypeAsString()));
        List<InstanceTypeOffering> offerings = new ArrayList<>();
        for (String t : all) {
            for (String az : List.of(AZ_A, AZ_B)) {
                offerings.add(InstanceTypeOffering.builder().instanceType(t).location(az).locationType("availability-zone").build());
            }
        }
        doReturn(DescribeInstanceTypeOfferingsResponse.builder().instanceTypeOfferings(offerings).build())
                .when(ec2).describeInstanceTypeOfferings(any(DescribeInstanceTypeOfferingsRequest.class));
        // Spot prices: 0.03 for m5.large, a little cheaper or dearer for the others.
        doAnswer(inv -> {
            DescribeSpotPriceHistoryRequest r = inv.getArgument(0);
            List<SpotPrice> prices = new ArrayList<>();
            int i = 0;
            for (String t : r.instanceTypesAsStrings()) {
                for (String az : List.of(AZ_A, AZ_B)) {
                    prices.add(SpotPrice.builder().instanceType(t).availabilityZone(az)
                            .spotPrice(String.format("0.0%d", 30 + (i % 5))).productDescription("Linux/UNIX")
                            .timestamp(Instant.parse("2026-11-17T00:00:00Z")).build());
                }
                i++;
            }
            return DescribeSpotPriceHistoryResponse.builder().spotPriceHistory(prices).build();
        }).when(ec2).describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class));
        doReturn(DescribeCapacityReservationsResponse.builder().build()).when(ec2)
                .describeCapacityReservations(any(DescribeCapacityReservationsRequest.class));
        doReturn(DescribeInstancesResponse.builder().reservations(Reservation.builder()
                .instances(software.amazon.awssdk.services.ec2.model.Instance.builder().instanceId("i-1").build()).build()).build())
                .when(ec2).describeInstances(any(DescribeInstancesRequest.class));
        doReturn(DescribeRegionsResponse.builder().regions(Region.builder().regionName(REGION).build()).build())
                .when(ec2).describeRegions();
        // Other reads.
        doReturn(DescribeCapacityProvidersResponse.builder().build()).when(ecs)
                .describeCapacityProviders(any(DescribeCapacityProvidersRequest.class));
        doReturn(DescribeTargetGroupAttributesResponse.builder().attributes(TargetGroupAttribute.builder()
                .key("deregistration_delay.timeout_seconds").value("30").build()).build())
                .when(elbv2).describeTargetGroupAttributes(any(DescribeTargetGroupAttributesRequest.class));
        doReturn(GetServiceQuotaResponse.builder().quota(ServiceQuota.builder().value(1000.0).build()).build())
                .when(quotas).getServiceQuota(any(GetServiceQuotaRequest.class));
        doReturn(GetMetricDataResponse.builder().metricDataResults(MetricDataResult.builder().id("u")
                .values(10.0).build()).build()).when(cloudWatch).getMetricData(any(GetMetricDataRequest.class));
        doAnswer(inv -> {
            SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().evaluationResults(r.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a).evalDecision("allowed").build()).toList()).build();
        }).when(iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
        return this;
    }

    static InstanceTypeInfo type(String name, int vcpu, long memMib, boolean spot, boolean nitro) {
        return type(name, vcpu, memMib, spot, nitro, false);
    }

    static InstanceTypeInfo type(String name, int vcpu, long memMib, boolean spot, boolean nitro, boolean burstable) {
        return InstanceTypeInfo.builder().instanceType(name)
                .vCpuInfo(VCpuInfo.builder().defaultVCpus(vcpu).build())
                .memoryInfo(MemoryInfo.builder().sizeInMiB(memMib).build())
                .processorInfo(ProcessorInfo.builder().supportedArchitecturesWithStrings("x86_64").build())
                .supportedUsageClassesWithStrings(spot ? List.of("on-demand", "spot") : List.of("on-demand"))
                .burstablePerformanceSupported(burstable).bareMetal(false).hypervisor(nitro ? "nitro" : "xen")
                .supportedBootModesWithStrings("legacy-bios", "uefi")
                .networkInfo(NetworkInfo.builder().maximumNetworkInterfaces(3).efaSupported(false).build())
                .hibernationSupported(false).currentGeneration(true).build();
    }

    // ---- AC-PLN-01: no mutating call ----

    /** Every call recorded on every client is a Describe/Get/List/Simulate read. */
    void assertReadOnly() {
        for (Object client : List.of(asg, ec2, elbv2, cfn, events, quotas, cloudWatch, ecs, iam)) {
            for (var inv : Mockito.mockingDetails(client).getInvocations()) {
                String n = inv.getMethod().getName();
                boolean read = n.startsWith("describe") || n.startsWith("get") || n.startsWith("list")
                        || n.startsWith("simulate") || n.equals("close") || n.equals("serviceName")
                        || n.equals("serviceClientConfiguration");
                if (!read) {
                    throw new AssertionError("mutating or unexpected call on "
                            + client.getClass().getInterfaces()[0].getSimpleName() + ": " + n);
                }
            }
        }
    }

    /** The number of calls to {@code method} across the clients. */
    int calls(String method) {
        int n = 0;
        for (Object client : List.of(asg, ec2, elbv2, cfn, events, quotas, cloudWatch, ecs, iam)) {
            for (var inv : Mockito.mockingDetails(client).getInvocations()) {
                if (inv.getMethod().getName().equals(method)
                        && inv.getArguments().length == 1 && !(inv.getArguments()[0] instanceof Consumer)) {
                    n++;
                }
            }
        }
        return n;
    }
}
