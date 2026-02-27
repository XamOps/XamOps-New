package collector

import (
	"context"
	"time"

	"k8s.io/client-go/dynamic"
)

func collectTrivy(ctx context.Context, dc dynamic.Interface) (DataBatch, error) {
	items, err := listCRD(ctx, dc, "aquasecurity.github.io", "v1alpha1", "vulnerabilityreports")
	if err != nil {
		return DataBatch{}, err
	}

	reports := make([]TrivyVulnerabilityReport, 0, len(items))
	for _, item := range items {
		meta := safeMap(item, "metadata")
		report := safeMap(item, "report")

		r := TrivyVulnerabilityReport{
			Name:      safeString(meta, "name"),
			Namespace: safeString(meta, "namespace"),
		}

		// Labels
		if labels, ok := meta["labels"].(map[string]interface{}); ok {
			r.Labels = make(map[string]string)
			for k, v := range labels {
				r.Labels[k] = safeString(meta, "labels", k)
				_ = k
				_ = v
				// build correctly
			}
			// rebuild properly
			r.Labels = map[string]string{}
			for k, v := range labels {
				if s, ok := v.(string); ok {
					r.Labels[k] = s
				}
			}
		}

		// Summary
		if report != nil {
			summary := safeMap(report, "summary")
			if summary != nil {
				r.Report.Summary = TrivySummary{
					CriticalCount: int(safeInt64(summary["criticalCount"])),
					HighCount:     int(safeInt64(summary["highCount"])),
					MediumCount:   int(safeInt64(summary["mediumCount"])),
					LowCount:      int(safeInt64(summary["lowCount"])),
					UnknownCount:  int(safeInt64(summary["unknownCount"])),
				}
			}

			// Vulnerabilities (cap at 50 per report to limit payload size)
			if vulns, ok := report["vulnerabilities"].([]interface{}); ok {
				limit := 50
				if len(vulns) < limit {
					limit = len(vulns)
				}
				for _, v := range vulns[:limit] {
					vm, ok := v.(map[string]interface{})
					if !ok {
						continue
					}
					r.Report.Vulnerabilities = append(r.Report.Vulnerabilities, TrivyVulnerability{
						VulnerabilityID:  safeString(vm, "vulnerabilityID"),
						Severity:         safeString(vm, "severity"),
						PkgName:          safeString(vm, "pkgName"),
						InstalledVersion: safeString(vm, "installedVersion"),
						FixedVersion:     safeString(vm, "fixedVersion"),
						Title:            safeString(vm, "title"),
						PrimaryLink:      safeString(vm, "primaryLink"),
					})
				}
			}
		}

		reports = append(reports, r)
	}

	return DataBatch{
		Collector:   "trivy-vulnerabilities",
		Items:       reports,
		Count:       len(reports),
		CollectedAt: time.Now().UTC(),
	}, nil
}
