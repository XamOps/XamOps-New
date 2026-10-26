package removal

import (
	"context"
	"fmt"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/autoscaling"
	autoscalingtypes "github.com/aws/aws-sdk-go-v2/service/autoscaling/types"
	"github.com/aws/aws-sdk-go-v2/service/eks"
)

// Tag keys an EKS managed nodegroup's ASG carries.
const (
	tagEKSClusterName   = "eks:cluster-name"
	tagEKSNodegroupName = "eks:nodegroup-name"
)

// autoscalingAPI and eksAPI are the narrow slices of the AWS SDK clients the
// provider uses (kept as interfaces so the SDK calls are isolated here).
type autoscalingAPI interface {
	DescribeAutoScalingInstances(ctx context.Context, in *autoscaling.DescribeAutoScalingInstancesInput, optFns ...func(*autoscaling.Options)) (*autoscaling.DescribeAutoScalingInstancesOutput, error)
	DescribeAutoScalingGroups(ctx context.Context, in *autoscaling.DescribeAutoScalingGroupsInput, optFns ...func(*autoscaling.Options)) (*autoscaling.DescribeAutoScalingGroupsOutput, error)
	TerminateInstanceInAutoScalingGroup(ctx context.Context, in *autoscaling.TerminateInstanceInAutoScalingGroupInput, optFns ...func(*autoscaling.Options)) (*autoscaling.TerminateInstanceInAutoScalingGroupOutput, error)
}

type eksAPI interface {
	DescribeNodegroup(ctx context.Context, in *eks.DescribeNodegroupInput, optFns ...func(*eks.Options)) (*eks.DescribeNodegroupOutput, error)
}

// AWSProvider removes nodes through the EC2 Auto Scaling and EKS APIs. It always
// terminates with ShouldDecrementDesiredCapacity=true and never launches a
// replacement. The cluster name scopes the managed-nodegroup check.
type AWSProvider struct {
	asg         autoscalingAPI
	eks         eksAPI
	clusterName string
}

// LoadAWSConfig loads the default AWS config pinned to region (e.g. ap-south-1).
// The controller calls it only when the removal path is reachable
// (ENABLE_CONSOLIDATION=true), so the default-OFF deployment never resolves AWS
// credentials.
func LoadAWSConfig(ctx context.Context, region string) (aws.Config, error) {
	return awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(region))
}

// NewAWSProvider builds an AWSProvider from a loaded AWS config. It is constructed
// only when the removal path is reachable (ENABLE_CONSOLIDATION=true); the
// default-OFF deployment never imports AWS credentials.
func NewAWSProvider(cfg aws.Config, clusterName string) *AWSProvider {
	return &AWSProvider{
		asg:         autoscaling.NewFromConfig(cfg),
		eks:         eks.NewFromConfig(cfg),
		clusterName: clusterName,
	}
}

// Lookup resolves the instance's ASG, that ASG's tags/sizes, and the managed
// nodegroup it belongs to (empty unless the ASG carries both EKS cluster and
// nodegroup tags and the nodegroup exists).
func (p *AWSProvider) Lookup(ctx context.Context, instanceID string) (NodeFacts, error) {
	di, err := p.asg.DescribeAutoScalingInstances(ctx, &autoscaling.DescribeAutoScalingInstancesInput{
		InstanceIds: []string{instanceID},
	})
	if err != nil {
		return NodeFacts{}, fmt.Errorf("describe asg instance %s: %w", instanceID, err)
	}
	if len(di.AutoScalingInstances) == 0 || di.AutoScalingInstances[0].AutoScalingGroupName == nil {
		return NodeFacts{InstanceID: instanceID}, nil // not in any ASG — unmanaged.
	}
	asgName := aws.ToString(di.AutoScalingInstances[0].AutoScalingGroupName)

	dg, err := p.asg.DescribeAutoScalingGroups(ctx, &autoscaling.DescribeAutoScalingGroupsInput{
		AutoScalingGroupNames: []string{asgName},
	})
	if err != nil {
		return NodeFacts{}, fmt.Errorf("describe asg %s: %w", asgName, err)
	}
	if len(dg.AutoScalingGroups) == 0 {
		return NodeFacts{InstanceID: instanceID, ASG: asgName}, nil
	}
	g := dg.AutoScalingGroups[0]
	facts := NodeFacts{
		InstanceID:      instanceID,
		ASG:             asgName,
		Tags:            tagMap(g.Tags),
		MinSize:         int(aws.ToInt32(g.MinSize)),
		DesiredCapacity: int(aws.ToInt32(g.DesiredCapacity)),
	}

	// Managed only when the ASG carries both EKS tags, the cluster matches, and the
	// nodegroup actually exists in the cluster inventory.
	cluster := facts.Tags[tagEKSClusterName]
	ng := facts.Tags[tagEKSNodegroupName]
	if ng != "" && cluster != "" && (p.clusterName == "" || cluster == p.clusterName) {
		if _, err := p.eks.DescribeNodegroup(ctx, &eks.DescribeNodegroupInput{
			ClusterName: aws.String(cluster), NodegroupName: aws.String(ng),
		}); err == nil {
			facts.ManagedNodegroup = ng
		}
	}
	return facts, nil
}

// TerminateAndDecrement terminates the instance with decrement=true. An
// already-terminating instance is treated as success (idempotent).
func (p *AWSProvider) TerminateAndDecrement(ctx context.Context, instanceID, asg string) error {
	_, err := p.asg.TerminateInstanceInAutoScalingGroup(ctx, &autoscaling.TerminateInstanceInAutoScalingGroupInput{
		InstanceId:                     aws.String(instanceID),
		ShouldDecrementDesiredCapacity: aws.Bool(true), // always decrement, never launch a replacement (I11)
	})
	if err != nil {
		if isAlreadyTerminating(err) {
			return nil
		}
		return fmt.Errorf("terminate instance %s in %s: %w", instanceID, asg, err)
	}
	return nil
}

func tagMap(tags []autoscalingtypes.TagDescription) map[string]string {
	m := make(map[string]string, len(tags))
	for _, t := range tags {
		m[aws.ToString(t.Key)] = aws.ToString(t.Value)
	}
	return m
}

// isAlreadyTerminating reports an error that means the instance is already leaving
// the ASG, which makes a repeat terminate a no-op rather than a failure.
func isAlreadyTerminating(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "not in in-service") ||
		strings.Contains(msg, "terminating") ||
		strings.Contains(msg, "does not exist")
}
