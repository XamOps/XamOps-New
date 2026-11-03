package collector

import (
	"context"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/eks"
	ekstypes "github.com/aws/aws-sdk-go-v2/service/eks/types"
	"github.com/aws/aws-sdk-go-v2/service/sts"
)

// cloudNodegroupsCollector pushes EKS nodegroup scaling config (design §8.1), behind
// COLLECTOR_CLOUD_NODEGROUPS. It uses only eks:ListNodegroups + eks:DescribeNodegroup (and
// sts:GetCallerIdentity once at start, for the identity log line). The default AWS config picks up
// Pod Identity or IRSA credentials automatically. On AccessDenied it logs WARN (throttled) and
// pushes nothing; the rest of the agent is unaffected.

const (
	cloudNodegroupsCollectorName = "cloud-nodegroups"
	maxNodegroups                = 50
)

// CloudNodegroup is one managed nodegroup's scaling config.
type CloudNodegroup struct {
	Name           string   `json:"name"`
	Status         string   `json:"status"`
	CapacityType   string   `json:"capacityType"`
	Min            int32    `json:"min"`
	Desired        int32    `json:"desired"`
	Max            int32    `json:"max"`
	ASGNames       []string `json:"asgNames"`
	AMIType        string   `json:"amiType"`
	ReleaseVersion string   `json:"releaseVersion"`
	InstanceTypes  []string `json:"instanceTypes"`
}

// CloudNodegroupsPayload is the single object pushed under data_type=cloud-nodegroups.
type CloudNodegroupsPayload struct {
	CollectedAt string           `json:"collectedAt"`
	Nodegroups  []CloudNodegroup `json:"nodegroups"`
	IdentityArn string           `json:"identityArn,omitempty"`
}

// eksAPI is the minimal EKS surface the collector uses, so tests can drive a fake.
type eksAPI interface {
	ListNodegroups(ctx context.Context, in *eks.ListNodegroupsInput, opts ...func(*eks.Options)) (*eks.ListNodegroupsOutput, error)
	DescribeNodegroup(ctx context.Context, in *eks.DescribeNodegroupInput, opts ...func(*eks.Options)) (*eks.DescribeNodegroupOutput, error)
}

// collectCloudNodegroups builds the batch using a live EKS client built from the default AWS config.
func collectCloudNodegroups(ctx context.Context, clusterName, region, identityArn string) (DataBatch, error) {
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	var optFns []func(*awsconfig.LoadOptions) error
	if region != "" {
		optFns = append(optFns, awsconfig.WithRegion(region))
	}
	cfg, err := awsconfig.LoadDefaultConfig(cctx, optFns...)
	if err != nil {
		return DataBatch{}, err
	}
	client := eks.NewFromConfig(cfg)
	return buildCloudNodegroupsBatch(cctx, client, clusterName, identityArn)
}

// buildCloudNodegroupsBatch is the pure-ish core: it drives the (injectable) EKS API and assembles
// the payload. Separated so a fake eksAPI can test it without AWS.
func buildCloudNodegroupsBatch(ctx context.Context, client eksAPI, clusterName, identityArn string) (DataBatch, error) {
	list, err := client.ListNodegroups(ctx, &eks.ListNodegroupsInput{ClusterName: aws.String(clusterName)})
	if err != nil {
		return DataBatch{}, err
	}

	payload := CloudNodegroupsPayload{
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
		IdentityArn: identityArn,
		Nodegroups:  []CloudNodegroup{},
	}

	names := list.Nodegroups
	if len(names) > maxNodegroups {
		names = names[:maxNodegroups]
	}
	for _, name := range names {
		desc, err := client.DescribeNodegroup(ctx, &eks.DescribeNodegroupInput{
			ClusterName:   aws.String(clusterName),
			NodegroupName: aws.String(name),
		})
		if err != nil {
			log.Printf("WARN: cloud-nodegroups: describe %s: %v", name, err)
			continue
		}
		payload.Nodegroups = append(payload.Nodegroups, toCloudNodegroup(desc.Nodegroup))
	}

	return DataBatch{
		Collector:   cloudNodegroupsCollectorName,
		Items:       payload,
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}

func toCloudNodegroup(ng *ekstypes.Nodegroup) CloudNodegroup {
	out := CloudNodegroup{}
	if ng == nil {
		return out
	}
	if ng.NodegroupName != nil {
		out.Name = *ng.NodegroupName
	}
	out.Status = string(ng.Status)
	out.CapacityType = string(ng.CapacityType)
	out.AMIType = string(ng.AmiType)
	if ng.ReleaseVersion != nil {
		out.ReleaseVersion = *ng.ReleaseVersion
	}
	out.InstanceTypes = ng.InstanceTypes
	if ng.ScalingConfig != nil {
		if ng.ScalingConfig.MinSize != nil {
			out.Min = *ng.ScalingConfig.MinSize
		}
		if ng.ScalingConfig.DesiredSize != nil {
			out.Desired = *ng.ScalingConfig.DesiredSize
		}
		if ng.ScalingConfig.MaxSize != nil {
			out.Max = *ng.ScalingConfig.MaxSize
		}
	}
	if ng.Resources != nil {
		for _, asg := range ng.Resources.AutoScalingGroups {
			if asg.Name != nil {
				out.ASGNames = append(out.ASGNames, *asg.Name)
			}
		}
	}
	return out
}

// logCallerIdentity logs the assumed-role ARN once at startup (design §8.1). Best effort.
func logCallerIdentity(ctx context.Context, region string) string {
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var optFns []func(*awsconfig.LoadOptions) error
	if region != "" {
		optFns = append(optFns, awsconfig.WithRegion(region))
	}
	cfg, err := awsconfig.LoadDefaultConfig(cctx, optFns...)
	if err != nil {
		log.Printf("WARN: cloud-nodegroups: could not load AWS config for identity: %v", err)
		return ""
	}
	out, err := sts.NewFromConfig(cfg).GetCallerIdentity(cctx, &sts.GetCallerIdentityInput{})
	if err != nil {
		log.Printf("WARN: cloud-nodegroups: sts:GetCallerIdentity failed: %v", err)
		return ""
	}
	if out.Arn != nil {
		log.Printf("INFO: cloud-nodegroups: aws identity: %s", *out.Arn)
		return *out.Arn
	}
	return ""
}
