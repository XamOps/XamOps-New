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

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/telemetry"
)

// Rightsizer analyses workload resource usage via Prometheus and recommends new
// resource requests.
//
// Scope (plan.md D1/D9): this controller is a hard safety clamp plus a default-off,
// AND-gated apply. Recommendations never go to 0m CPU, never decrease below the
// configurable floor (default 25m / 250Mi), never raise a workload to the floor, and
// containers with an unset or zero request are skipped. Measurement, hysteresis,
// requests-unset/idle findings, HPA coexistence, throttle and QoS-preserving limit
// handling belong to the Java vertical-rightsizer engine.
//
// Apply happens only when the gate.RightsizerApply decision is Live
// (ENABLE_RIGHTSIZER_APPLY=true ∧ applyMode=immediate ∧ DRY_RUN=false) and the
// existing savings threshold is met.
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

const mib = int64(1024 * 1024)

// Row reasons for the apply decision.
const (
	reasonSimulated    = "dry-run — simulated, not applied (would patch the Deployment template)"
	reasonApplyOff     = "apply disabled (ENABLE_RIGHTSIZER_APPLY=false)"
	reasonDeferred     = "deferred — not applied"
	reasonFloorNoRaise = "no change: at or below the floor; never raised to the floor"
	reasonUnsetSkipped = "requests unset — not rightsized by this controller (handed to the Java engine)"
)

// rightsizerFloors returns the clamp floors (CPU millicores, memory bytes); ≤0 → default.
func rightsizerFloors(p policy.WorkloadRightsizerPolicy) (cpuMilli, memBytes int64) {
	cpuMilli, memMiB := int64(p.MinCpuMillicores), int64(p.MinMemoryMib)
	if cpuMilli <= 0 {
		cpuMilli = policy.DefaultMinCpuMillicores
	}
	if memMiB <= 0 {
		memMiB = policy.DefaultMinMemoryMib
	}
	return cpuMilli, memMiB * mib
}

// clampTarget implements plan.md D9 for one resource (CPU millicores or memory bytes).
// demand is the measured value with overhead, already rounded up to the unit.
//   - current ≤ 0: the request is unset — no change (callers skip such containers).
//   - demand > current: increase to demand; the floor is not applied.
//   - otherwise target = max(demand, floor); target ≥ current → no change, so a
//     workload is never raised to the floor.
//
// The target is never below 1 (CPU never 0m).
func clampTarget(current, demand, floor int64) (target int64, changed bool, reason string) {
	if current <= 0 {
		return current, false, "request unset — not rightsized"
	}
	if floor < 1 {
		floor = 1
	}
	if demand > current {
		return demand, true, "increase to measured demand (floor not applied)"
	}
	t := demand
	if t < floor {
		t = floor
	}
	if t >= current {
		if floor >= current {
			return current, false, reasonFloorNoRaise
		}
		return current, false, "no change: demand equals the current request"
	}
	if t == floor && demand < floor {
		return t, true, "decrease clamped to the floor"
	}
	return t, true, "decrease to measured demand"
}

// containerAnalysis is one container's row plus the clamped targets behind it.
type containerAnalysis struct {
	result                 policy.WorkloadRightsizingResult
	currentCPU, currentMem int64 // millicores, bytes
	cpuTarget, memTarget   int64 // millicores, bytes (== current when unchanged)
	cpuChanged, memChanged bool
	clampNote              string
}

// Run analyses all Deployments (and StatefulSets when not protected) and returns the
// rows plus one action record per simulated or attempted patch.
func (r *Rightsizer) Run(ctx context.Context, p policy.WorkloadRightsizerPolicy, guards policy.SafetyGuards, apply gate.Decision) ([]policy.WorkloadRightsizingResult, []policy.ActionRecord, error) {
	if !p.Enabled {
		log.Println("Rightsizer: disabled in policy, skipping")
		return nil, nil, nil
	}

	results := make([]policy.WorkloadRightsizingResult, 0)
	var records []policy.ActionRecord

	// ── Deployments ──────────────────────────────────────────────────────
	deployments, err := r.k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, nil, fmt.Errorf("list deployments: %w", err)
	}
	for _, dep := range deployments.Items {
		if guards.ProtectCriticalNamespaces && isCriticalNS(dep.Namespace, guards.CriticalNamespaces) {
			continue
		}
		res, recs := r.analyzeDeployment(ctx, dep, p, guards, apply)
		results = append(results, res...)
		records = append(records, recs...)
	}

	// ── StatefulSets (analysis only: this controller patches Deployments only) ──
	if !guards.ProtectStatefulSets {
		ssets, err := r.k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
		if err == nil {
			for _, ss := range ssets.Items {
				if isCriticalNS(ss.Namespace, guards.CriticalNamespaces) {
					continue
				}
				results = append(results, r.analyzeStatefulSet(ctx, ss, p)...)
			}
		}
	}

	// Sort by estimated savings descending
	sort.SliceStable(results, func(i, j int) bool {
		return results[i].EstimatedSavings > results[j].EstimatedSavings
	})

	log.Printf("Rightsizer: analysed workloads, %d recommendations generated (%d applied, apply=%s)",
		len(results), countApplied(results), apply)

	return results, records, nil
}

// analyzeDeployment examines each container in the deployment.
func (r *Rightsizer) analyzeDeployment(ctx context.Context, dep appsv1.Deployment, p policy.WorkloadRightsizerPolicy, guards policy.SafetyGuards, apply gate.Decision) ([]policy.WorkloadRightsizingResult, []policy.ActionRecord) {
	// Single-replica protection
	replicas := int32(1)
	if dep.Spec.Replicas != nil {
		replicas = *dep.Spec.Replicas
	}
	if replicas == 1 && guards.ProtectSingleReplicas {
		return nil, nil // skip: single replica in strict mode
	}

	results := make([]policy.WorkloadRightsizingResult, 0)
	var records []policy.ActionRecord
	for _, container := range dep.Spec.Template.Spec.Containers {
		a := r.analyzeContainer(ctx, dep.Namespace, dep.Name, "Deployment", container, p)
		if a == nil {
			continue
		}
		if rec := r.decideApply(ctx, dep, container.Name, a, p, apply); rec != nil {
			records = append(records, *rec)
		}
		results = append(results, a.result)
	}
	return results, records
}

// decideApply fills the row reason (and Applied) and returns the action record of a
// simulated or attempted patch, nil when nothing would be patched.
func (r *Rightsizer) decideApply(ctx context.Context, dep appsv1.Deployment, containerName string, a *containerAnalysis, p policy.WorkloadRightsizerPolicy, apply gate.Decision) *policy.ActionRecord {
	res := &a.result
	switch {
	case !a.cpuChanged && !a.memChanged:
		res.Reason = "no change — " + a.clampNote
		return nil
	case !r.meetsThreshold(*res, p):
		res.Reason = fmt.Sprintf("below applyThresholdPercent (%.1f%% < %d%%) — not applied | %s",
			res.EstimatedSavings, p.ApplyThresholdPercent, a.clampNote)
		return nil
	}

	target := dep.Namespace + "/" + dep.Name + "/" + containerName
	newRec := func(dryRun bool) policy.ActionRecord {
		rec := telemetry.NewAction(policy.ControllerRightsizer, "patch-requests", target, dryRun)
		telemetry.SetDetail(&rec, "workload", dep.Namespace+"/"+dep.Name)
		telemetry.SetDetail(&rec, "cpu", res.CurrentCPURequest+" -> "+res.RecommendedCPU)
		telemetry.SetDetail(&rec, "memory", res.CurrentMemRequest+" -> "+res.RecommendedMem)
		return rec
	}

	switch apply.Mode {
	case gate.Simulate:
		res.Reason = reasonSimulated + " | " + a.clampNote
		rec := telemetry.Finish(newRec(true), policy.ActionSimulated, false,
			"dry-run — would patch the Deployment template (rolling restart)")
		return &rec
	case gate.Live:
		cpuFloor, memFloor := rightsizerFloors(p)
		rec := newRec(false)
		patched, err := r.patchDeploymentContainer(ctx, dep, containerName, a.cpuTarget, a.memTarget, cpuFloor, memFloor)
		if err != nil {
			log.Printf("Rightsizer: patch deployment %s refused/failed: %v", target, err)
			res.Reason = "patch not applied: " + err.Error() + " | " + a.clampNote
			status := policy.ActionFailed
			if _, ok := err.(refusal); ok {
				status = policy.ActionRefused
			}
			rec = telemetry.Finish(rec, status, false, err.Error())
			return &rec
		}
		if templateCarries(patched, containerName, a.cpuTarget, a.memTarget) {
			res.Applied = true
			res.AppliedAt = time.Now().UTC().Format(time.RFC3339)
			res.Reason = "Deployment template patched — rollout pending | " + a.clampNote
			rec.Verification = "Deployment template carries the new requests; rollout pending"
			rec = telemetry.Finish(rec, policy.ActionSucceeded, true, "")
			log.Printf("Rightsizer: patched deployment %s: CPU=%s Mem=%s (template verified, rollout pending)",
				target, res.RecommendedCPU, res.RecommendedMem)
		} else {
			res.Reason = "patch issued; the returned template does not carry the new requests | " + a.clampNote
			rec = telemetry.Finish(rec, policy.ActionSucceeded, false, "returned template does not carry the new requests")
		}
		return &rec
	default:
		if apply.Reason == "ENABLE_RIGHTSIZER_APPLY=false" {
			res.Reason = reasonApplyOff + " | " + a.clampNote
		} else if p.ApplyMode != "immediate" {
			res.Reason = reasonDeferred + " | " + a.clampNote
		} else {
			res.Reason = "apply disabled (" + apply.Reason + ") | " + a.clampNote
		}
		return nil
	}
}

func (r *Rightsizer) analyzeStatefulSet(ctx context.Context, ss appsv1.StatefulSet, p policy.WorkloadRightsizerPolicy) []policy.WorkloadRightsizingResult {
	results := make([]policy.WorkloadRightsizingResult, 0)
	for _, container := range ss.Spec.Template.Spec.Containers {
		a := r.analyzeContainer(ctx, ss.Namespace, ss.Name, "StatefulSet", container, p)
		if a == nil {
			continue
		}
		a.result.Reason = "analysis only (StatefulSet) — not applied | " + a.clampNote
		results = append(results, a.result)
	}
	return results
}

// analyzeContainer queries Prometheus for CPU/memory usage and builds a clamped
// recommendation. It returns nil (no row) when the container has an unset or zero CPU
// or memory request, or when Prometheus has no data for either resource.
func (r *Rightsizer) analyzeContainer(
	ctx context.Context,
	namespace, workload, kind string,
	container corev1.Container,
	p policy.WorkloadRightsizerPolicy,
) *containerAnalysis {
	// Unset requests are detected by map presence: Requests.Cpu() would read a
	// missing key as 0 and the old code then recommended a request out of nothing.
	cpuReq, hasCPU := container.Resources.Requests[corev1.ResourceCPU]
	memReq, hasMem := container.Resources.Requests[corev1.ResourceMemory]
	if !hasCPU || !hasMem || cpuReq.IsZero() || memReq.IsZero() {
		log.Printf("Rightsizer: %s/%s/%s: %s", namespace, workload, container.Name, reasonUnsetSkipped)
		return nil
	}
	currentCPU := cpuReq.MilliValue()
	currentMem := memReq.Value()

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

	cpuFloor, memFloor := rightsizerFloors(p)
	a := &containerAnalysis{currentCPU: currentCPU, currentMem: currentMem, cpuTarget: currentCPU, memTarget: currentMem}

	// A resource without data is left unchanged (missing data is not zero demand).
	cpuReason, memReason := "no data — unchanged", "no data — unchanged"
	if cpuErr == nil {
		demand := int64(math.Ceil(overhead(p80CPU, p.CpuOverheadPercent))) // millicores
		a.cpuTarget, a.cpuChanged, cpuReason = clampTarget(currentCPU, demand, cpuFloor)
	}
	if memErr == nil {
		demandBytes := math.Ceil(overhead(p100Mem, p.MemoryOverheadPercent))
		demand := int64(math.Ceil(demandBytes/float64(mib))) * mib // whole Mi, rounded up
		a.memTarget, a.memChanged, memReason = clampTarget(currentMem, demand, memFloor)
	}
	a.clampNote = "cpu: " + cpuReason + "; memory: " + memReason

	// Estimated savings (% reduction of the CPU request), from the clamped target.
	var savingsPct float64
	if a.cpuChanged && a.cpuTarget < currentCPU {
		savingsPct = float64(currentCPU-a.cpuTarget) / float64(currentCPU) * 100
	}

	a.result = policy.WorkloadRightsizingResult{
		Namespace:         namespace,
		WorkloadName:      workload,
		WorkloadKind:      kind,
		ContainerName:     container.Name,
		CurrentCPURequest: formatMilliCPU(currentCPU),
		CurrentMemRequest: formatMemBytes(currentMem),
		RecommendedCPU:    formatMilliCPU(a.cpuTarget),
		RecommendedMem:    formatMemBytes(a.memTarget),
		P80CPU:            p80CPU,
		P100Mem:           p100Mem,
		EstimatedSavings:  math.Max(0, savingsPct),
	}
	return a
}

func (r *Rightsizer) meetsThreshold(result policy.WorkloadRightsizingResult, p policy.WorkloadRightsizerPolicy) bool {
	return result.EstimatedSavings >= float64(p.ApplyThresholdPercent)
}

// refusal is a patch the re-validation refused (no API call was made).
type refusal string

func (e refusal) Error() string { return string(e) }

// patchDeploymentContainer updates the requests of one container via strategic merge
// patch and returns the patched Deployment. It re-validates the clamp first (defense in
// depth): never under DRY_RUN, never an unset current request, never CPU < 1m, never a
// decrease below the floor.
func (r *Rightsizer) patchDeploymentContainer(ctx context.Context, dep appsv1.Deployment, containerName string, cpuMilli, memBytes, cpuFloor, memFloor int64) (*appsv1.Deployment, error) {
	if r.dryRun {
		return nil, refusal("refused: DRY_RUN=true")
	}
	var current *corev1.Container
	for i := range dep.Spec.Template.Spec.Containers {
		if dep.Spec.Template.Spec.Containers[i].Name == containerName {
			current = &dep.Spec.Template.Spec.Containers[i]
		}
	}
	if current == nil {
		return nil, refusal("refused: container " + containerName + " not in the template")
	}
	curCPU, okC := current.Resources.Requests[corev1.ResourceCPU]
	curMem, okM := current.Resources.Requests[corev1.ResourceMemory]
	if !okC || !okM || curCPU.IsZero() || curMem.IsZero() {
		return nil, refusal("refused: current requests unset")
	}
	if cpuMilli < 1 {
		return nil, refusal(fmt.Sprintf("refused: CPU target %dm is below 1m", cpuMilli))
	}
	if memBytes < 1 {
		return nil, refusal("refused: memory target is not positive")
	}
	if cpuMilli < curCPU.MilliValue() && cpuMilli < cpuFloor {
		return nil, refusal(fmt.Sprintf("refused: CPU decrease to %dm is below the %dm floor", cpuMilli, cpuFloor))
	}
	if memBytes < curMem.Value() && memBytes < memFloor {
		return nil, refusal(fmt.Sprintf("refused: memory decrease to %s is below the %s floor", formatMemBytes(memBytes), formatMemBytes(memFloor)))
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
									"cpu":    resource.NewMilliQuantity(cpuMilli, resource.DecimalSI).String(),
									"memory": resource.NewQuantity(memBytes, resource.BinarySI).String(),
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
		return nil, err
	}

	return r.k8s.AppsV1().Deployments(dep.Namespace).Patch(
		ctx, dep.Name, types.StrategicMergePatchType, patchBytes, metav1.PatchOptions{},
	)
}

// templateCarries reports whether the returned Deployment's template has the new requests.
func templateCarries(dep *appsv1.Deployment, containerName string, cpuMilli, memBytes int64) bool {
	if dep == nil {
		return false
	}
	for _, c := range dep.Spec.Template.Spec.Containers {
		if c.Name != containerName {
			continue
		}
		cpu, okC := c.Resources.Requests[corev1.ResourceCPU]
		mem, okM := c.Resources.Requests[corev1.ResourceMemory]
		return okC && okM && cpu.MilliValue() == cpuMilli && mem.Value() == memBytes
	}
	return false
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
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, err
	}
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, fmt.Errorf("no data (%s)", s)
	}
	return v, nil
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

func formatMilliCPU(m int64) string { return fmt.Sprintf("%dm", m) }

// formatMemBytes renders whole MiB as "<n>Mi" (the backend's format) and anything
// else as an exact Kubernetes quantity — never a rounded-down or floored value.
func formatMemBytes(b int64) string {
	if b%mib == 0 {
		return fmt.Sprintf("%dMi", b/mib)
	}
	return resource.NewQuantity(b, resource.BinarySI).String()
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
