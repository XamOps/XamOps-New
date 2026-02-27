package collector

import (
	"context"
	"time"

	"k8s.io/client-go/dynamic"
)

func collectKarpenter(ctx context.Context, dc dynamic.Interface) (DataBatch, error) {
	data := KarpenterData{Installed: false}

	// NodePools (karpenter.sh/v1beta1 or v1)
	pools, err := listCRD(ctx, dc, "karpenter.sh", "v1beta1", "nodepools")
	if err != nil {
		// Try v1
		pools, err = listCRD(ctx, dc, "karpenter.sh", "v1", "nodepools")
	}
	if err == nil {
		data.Installed = true
		for _, p := range pools {
			pool := KarpenterPool{
				Name: safeString(safeMap(p, "metadata"), "name"),
			}
			if spec, ok := p["spec"].(map[string]interface{}); ok {
				pool.Spec = spec
			}
			if status, ok := p["status"].(map[string]interface{}); ok {
				pool.Status = status
			}
			// Extract version from annotations if present
			if ann := safeMap(safeMap(p, "metadata"), "annotations"); ann != nil {
				if v, ok := ann["karpenter.sh/version"].(string); ok {
					data.Version = v
				}
			}
			data.NodePools = append(data.NodePools, pool)
		}
	}

	// NodeClaims
	claims, err := listCRD(ctx, dc, "karpenter.sh", "v1beta1", "nodeclaims")
	if err != nil {
		claims, _ = listCRD(ctx, dc, "karpenter.sh", "v1", "nodeclaims")
	}
	for _, c := range claims {
		meta := safeMap(c, "metadata")
		claim := KarpenterClaim{
			Name:     safeString(meta, "name"),
			Phase:    safeString(safeMap(c, "status"), "phase"),
			NodeName: safeString(safeMap(c, "status"), "nodeName"),
		}
		// nodePool label
		if labels, ok := meta["labels"].(map[string]interface{}); ok {
			if np, ok := labels["karpenter.sh/nodepool"].(string); ok {
				claim.NodePool = np
			}
		}
		data.NodeClaims = append(data.NodeClaims, claim)
	}

	// EC2NodeClasses (AWS-specific)
	classes, _ := listCRD(ctx, dc, "karpenter.k8s.aws", "v1beta1", "ec2nodeclasses")
	if len(classes) == 0 {
		classes, _ = listCRD(ctx, dc, "karpenter.k8s.aws", "v1", "ec2nodeclasses")
	}
	for _, c := range classes {
		meta := safeMap(c, "metadata")
		class := KarpenterClass{
			Name: safeString(meta, "name"),
		}
		if spec, ok := c["spec"].(map[string]interface{}); ok {
			class.Spec = spec
		}
		data.EC2NodeClasses = append(data.EC2NodeClasses, class)
	}

	if !data.Installed {
		data.Error = "Karpenter CRDs not found"
	}

	return DataBatch{
		Collector:   "karpenter-autoscaling",
		Items:       []KarpenterData{data},
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}
