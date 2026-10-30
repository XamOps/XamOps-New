package collector

import (
	"context"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/eks"
	ekstypes "github.com/aws/aws-sdk-go-v2/service/eks/types"
)

// fakeEKS drives buildCloudNodegroupsBatch without AWS (design §8.1 test: fake).
type fakeEKS struct {
	names []string
	byName map[string]*ekstypes.Nodegroup
	listErr error
}

func (f *fakeEKS) ListNodegroups(ctx context.Context, in *eks.ListNodegroupsInput, opts ...func(*eks.Options)) (*eks.ListNodegroupsOutput, error) {
	if f.listErr != nil {
		return nil, f.listErr
	}
	return &eks.ListNodegroupsOutput{Nodegroups: f.names}, nil
}

func (f *fakeEKS) DescribeNodegroup(ctx context.Context, in *eks.DescribeNodegroupInput, opts ...func(*eks.Options)) (*eks.DescribeNodegroupOutput, error) {
	return &eks.DescribeNodegroupOutput{Nodegroup: f.byName[*in.NodegroupName]}, nil
}

func ng(name string, min, desired, max int32, asg string) *ekstypes.Nodegroup {
	return &ekstypes.Nodegroup{
		NodegroupName: aws.String(name),
		Status:        ekstypes.NodegroupStatusActive,
		CapacityType:  ekstypes.CapacityTypesOnDemand,
		AmiType:       ekstypes.AMITypesAl2X8664,
		InstanceTypes: []string{"m5.large"},
		ScalingConfig: &ekstypes.NodegroupScalingConfig{
			MinSize:     aws.Int32(min),
			DesiredSize: aws.Int32(desired),
			MaxSize:     aws.Int32(max),
		},
		Resources: &ekstypes.NodegroupResources{
			AutoScalingGroups: []ekstypes.AutoScalingGroup{{Name: aws.String(asg)}},
		},
	}
}

func TestBuildCloudNodegroupsBatch_mapsScalingConfig(t *testing.T) {
	fake := &fakeEKS{
		names: []string{"ng-ondemand-1"},
		byName: map[string]*ekstypes.Nodegroup{
			"ng-ondemand-1": ng("ng-ondemand-1", 1, 4, 4, "eks-ng-ondemand-1-abc"),
		},
	}
	batch, err := buildCloudNodegroupsBatch(context.Background(), fake, "xamops-test-1", "arn:aws:sts::1:assumed-role/r/s")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if batch.Collector != cloudNodegroupsCollectorName {
		t.Fatalf("collector = %q", batch.Collector)
	}
	payload, ok := batch.Items.(CloudNodegroupsPayload)
	if !ok {
		t.Fatalf("items type = %T", batch.Items)
	}
	if payload.IdentityArn != "arn:aws:sts::1:assumed-role/r/s" {
		t.Fatalf("identityArn = %q", payload.IdentityArn)
	}
	if len(payload.Nodegroups) != 1 {
		t.Fatalf("nodegroups = %d", len(payload.Nodegroups))
	}
	got := payload.Nodegroups[0]
	if got.Name != "ng-ondemand-1" || got.Min != 1 || got.Desired != 4 || got.Max != 4 {
		t.Fatalf("scaling mapped wrong: %+v", got)
	}
	if got.CapacityType != "ON_DEMAND" || got.AMIType != "AL2_x86_64" {
		t.Fatalf("type fields wrong: %+v", got)
	}
	if len(got.ASGNames) != 1 || got.ASGNames[0] != "eks-ng-ondemand-1-abc" {
		t.Fatalf("asg names wrong: %+v", got.ASGNames)
	}
}

func TestBuildCloudNodegroupsBatch_emptyList(t *testing.T) {
	fake := &fakeEKS{names: nil, byName: map[string]*ekstypes.Nodegroup{}}
	batch, err := buildCloudNodegroupsBatch(context.Background(), fake, "c", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	payload := batch.Items.(CloudNodegroupsPayload)
	if len(payload.Nodegroups) != 0 {
		t.Fatalf("expected 0 nodegroups, got %d", len(payload.Nodegroups))
	}
}
