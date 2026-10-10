package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/policy"
)

// Rightsizer analyses workload resource usage via Prometheus and recommends
// or applies adjustments to resource requests. Mirrors CAST.ai's Workload Autoscaler.
type Rightsizer struct {
	k8s           kubernetes.Interface
	prometheusURL string
	httpClient    *http.Client
	dryRun        bool
}

func NewRightsizer(k8s kubernetes.Interface, prometheusURL string, dryRun bool) *Rightsizer {
	return &Rightsizer{
		k8s:           k8s,
		prometheusURL: prometheusURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
		dryRun:        dryRun,
	}
}

// Run analyses all Deployments and StatefulSets in the cluster.
// Returns a list of rightsizing results (recommendations + applied changes).
func (r *Rightsizer) Run(ctx context.Context, p policy.WorkloadRightsizerPolicy, guards policy.SafetyGuards) ([]policy.WorkloadRightsizingResult, error) {
	if !p.Enabled {
		log.Println("Rightsizer: disabled in policy, skipping")
		return nil, nil
	}

	results := make([]policy.WorkloadRightsizingResult, 0)

	// ── Deployments ──────────────────────────────────────────────────────
	deployments, err := r.k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list deployments: %w", err)
	}
	for _, dep := range deployments.Items {
		if guards.ProtectCriticalNamespaces && isCriticalNS(dep.Namespace, guards.CriticalNamespaces) {
			continue
		}
		res, err := r.analyzeDeployment(ctx, dep, p, guards)
		if err != nil {
			log.Printf("Rightsizer: skip deployment %s/%s: %v", dep.Namespace, dep.Name, err)
			continue
		}
		results = append(results, res...)
	}

	// ── StatefulSets ─────────────────────────────────────────────────────
	if !guards.ProtectStatefulSets {
		ssets, err := r.k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
		if err == nil {
			for _, ss := range ssets.Items {
				if isCriticalNS(ss.Namespace, guards.CriticalNamespaces) {
					continue
				}
				res, err := r.analyzeStatefulSet(ctx, ss, p, guards)
				if err != nil {
					continue
				}
				results = append(results, res...)
			}
		}
	}

	// Sort by estimated savings descending
	sort.Slice(results, func(i, j int) bool {
		return results[i].EstimatedSavings > results[j].EstimatedSavings
	})

	log.Printf("Rightsizer: analysed workloads, %d recommendations generated (%d applied)",
		len(results), countApplied(results))

	return results, nil
}

// analyzeDeployment examines each container in the deployment.
func (r *Rightsizer) analyzeDeployment(ctx context.Context, dep appsv1.Deployment, p policy.WorkloadRightsizerPolicy, guards policy.SafetyGuards) ([]policy.WorkloadRightsizingResult, error) {
	// Single-replica protection
	replicas := int32(1)
	if dep.Spec.Replicas != nil {
		replicas = *dep.Spec.Replicas
	}
	if replicas == 1 && guards.ProtectSingleReplicas {
		return nil, nil // skip: single replica in strict mode
	}

	results := make([]policy.WorkloadRightsizingResult, 0)
	for _, container := range dep.Spec.Template.Spec.Containers {
		result := r.analyzeContainer(ctx, dep.Namespace, dep.Name, "Deployment", container, p)
		if result == nil {
			continue
		}

		// Apply if applyMode == "immediate" and threshold exceeded
		if r.shouldApply(*result, p) {
			if err := r.patchDeploymentContainer(ctx, dep, container.Name, result.RecommendedCPU, result.RecommendedMem); err != nil {
				log.Printf("Rightsizer: patch deployment %s/%s/%s failed: %v", dep.Namespace, dep.Name, container.Name, err)
				result.Reason = "patch failed: " + err.Error()
			} else {
				result.Applied = true
				result.AppliedAt = time.Now().UTC().Format(time.RFC3339)
				log.Printf("Rightsizer: patched deployment %s/%s/%s: CPU=%s Mem=%s",
					dep.Namespace, dep.Name, container.Name, result.RecommendedCPU, result.RecommendedMem)
			}
		} else if p.ApplyMode == "deferred" && r.meetsThreshold(*result, p) {
			result.Reason = "deferred — will apply on next pod restart"
		}

		results = append(results, *result)
	}
	return results, nil
}

func (r *Rightsizer) analyzeStatefulSet(ctx context.Context, ss appsv1.StatefulSet, p policy.WorkloadRightsizerPolicy, guards policy.SafetyGuards) ([]policy.WorkloadRightsizingResult, error) {
	results := make([]policy.WorkloadRightsizingResult, 0)
	for _, container := range ss.Spec.Template.Spec.Containers {
		result := r.analyzeContainer(ctx, ss.Namespace, ss.Name, "StatefulSet", container, p)
		if result == nil {
			continue
		}
		results = append(results, *result)
	}
	return results, nil
}

// analyzeContainer queries Prometheus for CPU/memory usage and builds a recommendation.
func (r *Rightsizer) analyzeContainer(
	ctx context.Context,
	namespace, workload, kind string,
	container corev1.Container,
	p policy.WorkloadRightsizerPolicy,
) *policy.WorkloadRightsizingResult {
	lookback := fmt.Sprintf("%dh", p.LookbackHours)

	cpuQuery := fmt.Sprintf(
		`quantile_over_time(%g, rate(container_cpu_usage_seconds_total{namespace="%s",pod=~"%s-.*",container="%s"}[5m])[%s:])*1000`,
		float64(p.CpuPercentile)/100.0, namespace, workload, container.Name, lookback,
	)
	memQuery := fmt.Sprintf(
		`max_over_time(container_memory_working_set_bytes{namespace="%s",pod=~"%s-.*",container="%s"}[%s:])`,
		namespace, workload, container.Name, lookback,
	)

	p80CPU, cpuErr := r.promInstantQuery(ctx, cpuQuery)  // in millicores
	p100Mem, memErr := r.promInstantQuery(ctx, memQuery) // in bytes

	if cpuErr != nil && memErr != nil {
		return nil // no data
	}

	// Apply overhead
	overhead := func(val float64, pct int) float64 {
		return val * (1 + float64(pct)/100.0)
	}

	recommendedCPU := overhead(p80CPU, p.CpuOverheadPercent)     // millicores
	recommendedMem := overhead(p100Mem, p.MemoryOverheadPercent) // bytes

	// Current requests
	currentCPU := container.Resources.Requests.Cpu().MilliValue()
	currentMem := container.Resources.Requests.Memory().Value()

	// Estimated savings (% reduction in request vs current)
	var savingsPct float64
	if currentCPU > 0 && recommendedCPU < float64(currentCPU) {
		savingsPct = (float64(currentCPU) - recommendedCPU) / float64(currentCPU) * 100
	}

	return &policy.WorkloadRightsizingResult{
		Namespace:         namespace,
		WorkloadName:      workload,
		WorkloadKind:      kind,
		ContainerName:     container.Name,
		CurrentCPURequest: fmt.Sprintf("%dm", currentCPU),
		CurrentMemRequest: bytesToMiB(currentMem),
		RecommendedCPU:    fmt.Sprintf("%dm", int64(math.Ceil(recommendedCPU))),
		RecommendedMem:    bytesToMiB(int64(math.Ceil(recommendedMem))),
		P80CPU:            p80CPU,
		P100Mem:           p100Mem,
		EstimatedSavings:  math.Max(0, savingsPct),
	}
}

func (r *Rightsizer) shouldApply(result policy.WorkloadRightsizingResult, p policy.WorkloadRightsizerPolicy) bool {
	return p.ApplyMode == "immediate" && r.meetsThreshold(result, p) && !r.dryRun
}

func (r *Rightsizer) meetsThreshold(result policy.WorkloadRightsizingResult, p policy.WorkloadRightsizerPolicy) bool {
	return result.EstimatedSavings >= float64(p.ApplyThresholdPercent)
}

// patchDeploymentContainer updates resource requests on a single container via strategic merge patch
func (r *Rightsizer) patchDeploymentContainer(ctx context.Context, dep appsv1.Deployment, containerName, cpuStr, memStr string) error {
	if r.dryRun {
		log.Printf("DRY-RUN: would patch %s/%s container %s CPU=%s Mem=%s",
			dep.Namespace, dep.Name, containerName, cpuStr, memStr)
		return nil
	}

	cpuQ, err := resource.ParseQuantity(cpuStr)
	if err != nil {
		return fmt.Errorf("parse CPU %s: %w", cpuStr, err)
	}
	memQ, err := resource.ParseQuantity(memStr)
	if err != nil {
		return fmt.Errorf("parse Mem %s: %w", memStr, err)
	}

	// Build a strategic merge patch for just the one container
	patch := map[string]interface{}{
		"spec": map[string]interface{}{
			"template": map[string]interface{}{
				"spec": map[string]interface{}{
					"containers": []map[string]interface{}{
						{
							"name": containerName,
							"resources": map[string]interface{}{
								"requests": map[string]interface{}{
									"cpu":    cpuQ.String(),
									"memory": memQ.String(),
								},
							},
						},
					},
				},
			},
		},
	}

	patchBytes, err := json.Marshal(patch)
	if err != nil {
		return err
	}

	_, err = r.k8s.AppsV1().Deployments(dep.Namespace).Patch(
		ctx, dep.Name, types.StrategicMergePatchType, patchBytes, metav1.PatchOptions{},
	)
	return err
}

// ── Prometheus helpers ──────────────────────────────────────────────────────

func (r *Rightsizer) promInstantQuery(ctx context.Context, query string) (float64, error) {
	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s", r.prometheusURL, url.QueryEscape(query))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, err
	}

	resp, err := r.httpClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, err
	}

	var pr struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Value []interface{} `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &pr); err != nil {
		return 0, err
	}
	if pr.Status != "success" || len(pr.Data.Result) == 0 || len(pr.Data.Result[0].Value) < 2 {
		return 0, fmt.Errorf("no data")
	}

	s, ok := pr.Data.Result[0].Value[1].(string)
	if !ok {
		return 0, fmt.Errorf("unexpected type")
	}
	return strconv.ParseFloat(s, 64)
}

// ── Helpers ─────────────────────────────────────────────────────────────────

func isCriticalNS(ns string, critical []string) bool {
	for _, c := range critical {
		if c == ns {
			return true
		}
	}
	return false
}

func bytesToMiB(bytes int64) string {
	mib := bytes / (1024 * 1024)
	if mib < 1 {
		mib = 1
	}
	return fmt.Sprintf("%dMi", mib)
}

func countApplied(results []policy.WorkloadRightsizingResult) int {
	n := 0
	for _, r := range results {
		if r.Applied {
			n++
		}
	}
	return n
}
