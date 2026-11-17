package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;

/**
 * AWS's read-only view of the cloud (design-aws.md §2.1, core §7.2, §9.1). Built by
 * {@link AwsAutoSpottingClientFactory} from a {@code READ}-mode session, it exposes the regional SDK
 * clients the read path needs — AutoScaling, EC2, ELBv2, CloudFormation, EventBridge, Service
 * Quotas, CloudWatch ({@code GetMetricData}) and the three IAM reads — and nothing else. No mutating
 * client is reachable from it.
 *
 * <p>Every accessor is read-shaped ({@code client()} / {@code region()}), so
 * {@code AutoSpottingArchitectureTest}'s AC-PLN-01 rule holds: no method on a
 * {@link ReadOnlyCloudView} implementation reads like a mutation, and a mutating call can't compile
 * in planning. The clients returned are the full SDK clients; callers in this package invoke only
 * {@code Describe*}/{@code Get*}/{@code List*} on them, enforced at runtime by the read-mode session
 * policy ({@link AwsSessionPolicies.Mode#READ}) which grants no write action.
 */
public final class AwsReadOnlyView implements ReadOnlyCloudView {

    private final String region;
    private final AutoScalingClient autoScaling;
    private final Ec2Client ec2;
    private final ElasticLoadBalancingV2Client elbv2;
    private final CloudFormationClient cloudFormation;
    private final EventBridgeClient eventBridge;
    private final ServiceQuotasClient serviceQuotas;
    private final CloudWatchClient cloudWatch;
    private final EcsClient ecs;
    private final IamClient iam;

    public AwsReadOnlyView(String region,
                           AutoScalingClient autoScaling,
                           Ec2Client ec2,
                           ElasticLoadBalancingV2Client elbv2,
                           CloudFormationClient cloudFormation,
                           EventBridgeClient eventBridge,
                           ServiceQuotasClient serviceQuotas,
                           CloudWatchClient cloudWatch,
                           EcsClient ecs,
                           IamClient iam) {
        this.region = region;
        this.autoScaling = autoScaling;
        this.ec2 = ec2;
        this.elbv2 = elbv2;
        this.cloudFormation = cloudFormation;
        this.eventBridge = eventBridge;
        this.serviceQuotas = serviceQuotas;
        this.cloudWatch = cloudWatch;
        this.ecs = ecs;
        this.iam = iam;
    }

    /** The region this view reads. */
    public String region() {
        return region;
    }

    public AutoScalingClient autoScaling() {
        return autoScaling;
    }

    public Ec2Client ec2() {
        return ec2;
    }

    public ElasticLoadBalancingV2Client elbv2() {
        return elbv2;
    }

    public CloudFormationClient cloudFormation() {
        return cloudFormation;
    }

    public EventBridgeClient eventBridge() {
        return eventBridge;
    }

    public ServiceQuotasClient serviceQuotas() {
        return serviceQuotas;
    }

    public CloudWatchClient cloudWatch() {
        return cloudWatch;
    }

    public EcsClient ecs() {
        return ecs;
    }

    public IamClient iam() {
        return iam;
    }
}
