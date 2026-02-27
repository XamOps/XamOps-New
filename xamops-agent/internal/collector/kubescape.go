package collector

import (
	"context"
	"time"

	"k8s.io/client-go/dynamic"
)

func collectKubescape(ctx context.Context, dc dynamic.Interface) (DataBatch, error) {
	// Try both API groups (spdx.softwarecomposition.kubescape.io and kubescape.io)
	items, err := listCRD(ctx, dc, "spdx.softwarecomposition.kubescape.io", "v1beta1", "workloadconfigurationscansummaries")
	if err != nil {
		// Fallback: try the other API group
		items, err = listCRD(ctx, dc, "kubescape.io", "v1", "workloadconfigurationscansummaries")
		if err != nil {
			return DataBatch{}, err
		}
	}

	scans := make([]KubescapeScan, 0, len(items))
	for _, item := range items {
		meta := safeMap(item, "metadata")
		spec := safeMap(item, "spec")

		s := KubescapeScan{
			Name:      safeString(meta, "name"),
			Namespace: safeString(meta, "namespace"),
		}

		// Labels
		if labels, ok := meta["labels"].(map[string]interface{}); ok {
			s.Labels = map[string]string{}
			for k, v := range labels {
				if str, ok := v.(string); ok {
					s.Labels[k] = str
				}
			}
		}

		// Severities from spec.severities or status.severities
		if spec != nil {
			sevMap := safeMap(spec, "severities")
			if sevMap == nil {
				sevMap = safeMap(item, "status", "severities")
			}
			if sevMap != nil {
				s.Severities = KubescapeSeverity{
					Critical: int(safeInt64(sevMap["critical"])),
					High:     int(safeInt64(sevMap["high"])),
					Medium:   int(safeInt64(sevMap["medium"])),
					Low:      int(safeInt64(sevMap["low"])),
				}
			}

			// Controls summary
			controlsMap := safeMap(spec, "controls")
			if controlsMap == nil {
				controlsMap = safeMap(item, "status", "controls")
			}
			if controlsMap != nil {
				passed := 0
				failed := 0
				for _, v := range controlsMap {
					cm, ok := v.(map[string]interface{})
					if !ok {
						continue
					}
					status := safeString(cm, "status", "status")
					switch status {
					case "passed":
						passed++
					case "failed":
						failed++
					}
				}
				s.Controls = KubescapeControls{
					Passed: passed,
					Failed: failed,
					Total:  passed + failed,
				}
			}
		}

		scans = append(scans, s)
	}

	return DataBatch{
		Collector:   "kubescape-compliance",
		Items:       scans,
		Count:       len(scans),
		CollectedAt: time.Now().UTC(),
	}, nil
}
