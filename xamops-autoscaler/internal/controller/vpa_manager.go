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
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/telemetry"
)

// VPA GVR — VerticalPodAutoscaler CRD from k8s.io/autoscaler/vertical-pod-autoscaler
var vpaGVR = schema.GroupVersionResource{
	Group:    "autoscaling.k8s.io",
	Version:  "v1",
	Resource: "verticalpodautoscalers",
}

// VPAManager creates and manages VerticalPodAutoscaler objects based on XamOps
// policy recommendations. It also reads VPA status (recommendations) and pushes
// them back to the backend so the XamOps UI can display them.
// VPA must already be installed in the cluster (helm install vpa ...).
type VPAManager struct {
	k8s           kubernetes.Interface
	dynamicClient dynamic.Interface
	dryRun        bool

	// prometheusURL + httpClient back the post-apply OOM/throttle auto-revert watch
	// (§3.4). Optional: an empty URL disables the watch (no Prometheus ⇒ no revert,
	// which matches the rightsizer's "missing data never reverts" rule).
	prometheusURL string
	httpClient    *http.Client

	// promQueryFn is the test seam the auto-revert watch queries through. Production
	// leaves it nil and the watch calls promInstantQuery against prometheusURL; tests
	// inject OOM/throttle values and assert the emitted PromQL.
	promQueryFn func(ctx context.Context, query string) (float64, error)
}

func NewVPAManager(k8s kubernetes.Interface, dc dynamic.Interface, dryRun bool) *VPAManager {
	return &VPAManager{
		k8s:           k8s,
		dynamicClient: dc,
		dryRun:        dryRun,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
	}
}

// SetPrometheusURL configures the Prometheus endpoint the auto-revert watch queries.
func (v *VPAManager) SetPrometheusURL(u string) { v.prometheusURL = u }

// VPARecommendation is what VPA recommends for a single container.
// Pushed back as collector: "vpa-recommendations".
type VPARecommendation struct {
	Namespace     string `json:"namespace"`
	WorkloadName  string `json:"workloadName"`
	WorkloadKind  string `json:"workloadKind"` // Deployment | StatefulSet | DaemonSet
	ContainerName string `json:"containerName"`
	// VPA lower/target/upper bounds
	LowerCPU     string `json:"lowerCpu,omitempty"`
	TargetCPU    string `json:"targetCpu,omitempty"`
	UpperCPU     string `json:"upperCpu,omitempty"`
	LowerMemory  string `json:"lowerMemory,omitempty"`
	TargetMemory string `json:"targetMemory,omitempty"`
	UpperMemory  string `json:"upperMemory,omitempty"`
	// Current requests for comparison
	CurrentCPU    string `json:"currentCpu,omitempty"`
	CurrentMemory string `json:"currentMemory,omitempty"`
	// VPA update mode applied
	UpdateMode  string `json:"updateMode"` // Off | Initial | Recreate | Auto
	CollectedAt string `json:"collectedAt"`
}

// VPAApplyRequest is sent from the XamOps backend to the autoscaler.
type VPAApplyRequest struct {
	Namespace    string `json:"namespace"`
	WorkloadName string `json:"workloadName"`
	WorkloadKind string `json:"workloadKind"`
	UpdateMode   string `json:"updateMode"` // Off | Initial | Recreate | Auto
	Delete       bool   `json:"delete"`     // true = remove the VPA object
}

// CollectRecommendations reads all existing VPA objects and extracts their recommendations.
// Returns results to be pushed as "vpa-recommendations".
func (v *VPAManager) CollectRecommendations(ctx context.Context) ([]VPARecommendation, error) {
	if v.dynamicClient == nil {
		return nil, fmt.Errorf("dynamic client not available")
	}

	vpaList, err := v.dynamicClient.Resource(vpaGVR).Namespace("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list VPAs: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339)
	results := make([]VPARecommendation, 0, len(vpaList.Items))

	for _, vpa := range vpaList.Items {
		namespace := vpa.GetNamespace()
		name := vpa.GetName()

		// Get target ref
		targetName, _, _ := unstructured.NestedString(vpa.Object, "spec", "targetRef", "name")
		targetKind, _, _ := unstructured.NestedString(vpa.Object, "spec", "targetRef", "kind")
		updateMode, _, _ := unstructured.NestedString(vpa.Object, "spec", "updatePolicy", "updateMode")

		if targetName == "" {
			targetName = name
		}

		// Read recommendation from status
		containerRecs, found, err := unstructured.NestedSlice(vpa.Object, "status", "recommendation", "containerRecommendations")
		if err != nil || !found {
			// VPA installed but no recommendations yet
			results = append(results, VPARecommendation{
				Namespace:    namespace,
				WorkloadName: targetName,
				WorkloadKind: targetKind,
				UpdateMode:   updateMode,
				CollectedAt:  now,
			})
			continue
		}

		for _, crRaw := range containerRecs {
			cr, ok := crRaw.(map[string]interface{})
			if !ok {
				continue
			}
			containerName, _ := cr["containerName"].(string)

			rec := VPARecommendation{
				Namespace:     namespace,
				WorkloadName:  targetName,
				WorkloadKind:  targetKind,
				ContainerName: containerName,
				UpdateMode:    updateMode,
				CollectedAt:   now,
			}

			if lower, ok := cr["lowerBound"].(map[string]interface{}); ok {
				rec.LowerCPU, _ = lower["cpu"].(string)
				rec.LowerMemory, _ = lower["memory"].(string)
			}
			if target, ok := cr["target"].(map[string]interface{}); ok {
				rec.TargetCPU, _ = target["cpu"].(string)
				rec.TargetMemory, _ = target["memory"].(string)
			}
			if upper, ok := cr["upperBound"].(map[string]interface{}); ok {
				rec.UpperCPU, _ = upper["cpu"].(string)
				rec.UpperMemory, _ = upper["memory"].(string)
			}

			results = append(results, rec)
		}
	}
	return results, nil
}

// Apply creates or deletes a VPA object as directed by the XamOps backend.
func (v *VPAManager) Apply(ctx context.Context, req VPAApplyRequest, guards policy.SafetyGuards) error {
	if guards.ProtectCriticalNamespaces && isCriticalNS(req.Namespace, guards.CriticalNamespaces) {
		log.Printf("VPA-Manager: skipping %s/%s — critical namespace", req.Namespace, req.WorkloadName)
		return nil
	}

	vpaName := req.WorkloadName + "-xamops-vpa"

	if req.Delete {
		return v.deleteVPA(ctx, req.Namespace, vpaName)
	}
	return v.upsertVPA(ctx, req, vpaName)
}

func (v *VPAManager) upsertVPA(ctx context.Context, req VPAApplyRequest, vpaName string) error {
	updateMode := req.UpdateMode
	if updateMode == "" {
		updateMode = "Off" // safe default — only provides recommendations without changing pods
	}

	apiVersion := req.WorkloadKind
	switch req.WorkloadKind {
	case "Deployment", "StatefulSet", "DaemonSet":
		apiVersion = "apps/v1"
	default:
		apiVersion = "apps/v1"
	}

	vpaObj := &unstructured.Unstructured{
		Object: map[string]interface{}{
			"apiVersion": "autoscaling.k8s.io/v1",
			"kind":       "VerticalPodAutoscaler",
			"metadata": map[string]interface{}{
				"name":      vpaName,
				"namespace": req.Namespace,
				"labels": map[string]interface{}{
					"app.kubernetes.io/managed-by": "xamops-automation",
				},
				"annotations": map[string]interface{}{
					"xamops.io/managed":  "true",
					"xamops.io/modified": time.Now().UTC().Format(time.RFC3339),
				},
			},
			"spec": map[string]interface{}{
				"targetRef": map[string]interface{}{
					"apiVersion": apiVersion,
					"kind":       req.WorkloadKind,
					"name":       req.WorkloadName,
				},
				"updatePolicy": map[string]interface{}{
					"updateMode": updateMode,
				},
				"resourcePolicy": map[string]interface{}{
					"containerPolicies": []interface{}{
						map[string]interface{}{
							"containerName":       "*",
							"controlledResources": []interface{}{"cpu", "memory"},
						},
					},
				},
			},
		},
	}

	if v.dryRun {
		log.Printf("DRY-RUN: would upsert VPA %s/%s (mode=%s target=%s/%s)",
			req.Namespace, vpaName, updateMode, req.WorkloadKind, req.WorkloadName)
		return nil
	}

	_, err := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Get(ctx, vpaName, metav1.GetOptions{})
	if err != nil {
		// Create
		_, createErr := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Create(ctx, vpaObj, metav1.CreateOptions{})
		if createErr != nil {
			return fmt.Errorf("create VPA %s/%s: %w", req.Namespace, vpaName, createErr)
		}
		log.Printf("VPA created: %s/%s (mode=%s)", req.Namespace, vpaName, updateMode)
		return nil
	}

	// Update spec only
	existing, getErr := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Get(ctx, vpaName, metav1.GetOptions{})
	if getErr != nil {
		return getErr
	}
	if err := unstructured.SetNestedField(existing.Object, updateMode, "spec", "updatePolicy", "updateMode"); err != nil {
		return err
	}
	if existing.GetAnnotations() == nil {
		existing.SetAnnotations(map[string]string{})
	}
	ann := existing.GetAnnotations()
	ann["xamops.io/modified"] = time.Now().UTC().Format(time.RFC3339)
	existing.SetAnnotations(ann)

	_, err = v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Update(ctx, existing, metav1.UpdateOptions{})
	if err != nil {
		return fmt.Errorf("update VPA %s/%s: %w", req.Namespace, vpaName, err)
	}
	log.Printf("VPA updated: %s/%s (mode=%s)", req.Namespace, vpaName, updateMode)
	return nil
}

func (v *VPAManager) deleteVPA(ctx context.Context, namespace, vpaName string) error {
	if v.dryRun {
		log.Printf("DRY-RUN: would delete VPA %s/%s", namespace, vpaName)
		return nil
	}
	err := v.dynamicClient.Resource(vpaGVR).Namespace(namespace).Delete(ctx, vpaName, metav1.DeleteOptions{})
	if err != nil {
		// Not found is fine
		return fmt.Errorf("delete VPA %s/%s: %w", namespace, vpaName, err)
	}
	log.Printf("VPA deleted: %s/%s", namespace, vpaName)
	return nil
}

// ── Detect whether VPA CRD is installed ─────────────────────────────────────

// IsVPAInstalled checks whether the VPA CRD exists in this cluster.
// Returns false (not an error) if VPA is not installed.
func IsVPAInstalled(ctx context.Context, dc dynamic.Interface) bool {
	if dc == nil {
		return false
	}
	_, err := dc.Resource(vpaGVR).Namespace("").List(ctx, metav1.ListOptions{Limit: 1})
	return err == nil
}

// ═══════════════════════════════════════════════════════════════════════════
// TRUE in-place resize path (FEAT cloudk8s-vpa-apply). ApplyInPlace resizes
// workloads toward their VPA recommendation using the pods/resize subresource
// ONLY. It NEVER calls Pods().Delete, NEVER issues an Eviction, and NEVER patches a
// Deployment/StatefulSet template. A recommendation that cannot be applied in place
// (restart required) is REFUSED, not forced. The acting funcs below are pinned by an
// AST guard (vpa_manager_resize_test.go) to reference none of those escape hatches.
// ═══════════════════════════════════════════════════════════════════════════

// vpaMaxResizesPerCycle caps how many pods ApplyInPlace resizes in one cycle, so a
// large recommendation set rolls out gradually and a bad recommendation is caught by
// the watch before the next batch. A package-level const (no policy field / env var),
// so it needs no normalizer coercion, DTO or round-trip test.
const vpaMaxResizesPerCycle = 5

// Auto-revert annotation vocabulary — a distinct xamops.io/vpa-* prefix (NOT the
// rightsizer's xamops.io/rightsizer-* prefix) so the two controllers' undo state does
// not collide on a workload both touch (§3.4).
const (
	// annVpaPriorResources holds a JSON map {container: {cpu, memory, cpuLimit,
	// memLimit}} of the resources in force immediately BEFORE the in-place resize.
	annVpaPriorResources = "xamops.io/vpa-prior-resources"
	// annVpaResizedAt stamps the RFC3339 time of the resize (watch window start).
	annVpaResizedAt = "xamops.io/vpa-resized-at"
	// annVpaWatchRemaining is the number of watch cycles still to run.
	annVpaWatchRemaining = "xamops.io/vpa-watch-remaining"

	// vpaWatchWindowCycles mirrors the rightsizer's watchWindowCycles.
	vpaWatchWindowCycles = 3
	// vpaThrottleRegressionRatio mirrors the rightsizer's throttleRegressionRatio
	// (0.25) — the CPU-throttle fraction above which the watch treats the pod as
	// regressed. Reused unchanged; only the matcher is pod-scoped (§3.4).
	vpaThrottleRegressionRatio = 0.25
)

// VpaRun statuses (design 6.3). Frozen; the Java VpaRunDto mirrors them.
const (
	vpaStatusResized   = "resized"   // in-place, verified restart-free
	vpaStatusSimulated = "simulated" // Simulate / DRY_RUN — zero resize calls
	vpaStatusSkipped   = "skipped"   // per-cycle cap
	vpaStatusRefused   = "refused"   // classifier or not-in-place-eligible
	vpaStatusReverted  = "reverted"  // auto-revert after regression
	vpaStatusFailed    = "failed"    // API/transport error
)

// VpaRun is the per-run record pushed on collector "autoscaling-vpa-runs". Its JSON
// shape (schemaVersion=1, no omitempty, empty lists as []) is mirrored EXACTLY by the
// backend VpaRunDto (FEAT-002 depends on this shape).
type VpaRun struct {
	SchemaVersion int              `json:"schemaVersion"`
	RunID         string           `json:"runId"`
	GeneratedAt   string           `json:"generatedAt"`
	Mode          string           `json:"mode"`
	DryRun        bool             `json:"dryRun"`
	Summary       VpaRunSummary    `json:"summary"`
	Workloads     []VpaWorkloadRun `json:"workloads"`
}

// VpaRunSummary is the per-run tally.
type VpaRunSummary struct {
	Considered int `json:"considered"`
	Resized    int `json:"resized"`
	Simulated  int `json:"simulated"`
	Skipped    int `json:"skipped"`
	Refused    int `json:"refused"`
	Reverted   int `json:"reverted"`
	Failed     int `json:"failed"`
}

// VpaWorkloadRun is one container's outcome in a run.
type VpaWorkloadRun struct {
	Namespace     string `json:"namespace"`
	WorkloadName  string `json:"workloadName"`
	WorkloadKind  string `json:"workloadKind"`
	ContainerName string `json:"containerName"`
	Status        string `json:"status"`
	Reason        string `json:"reason"`
}

// vpaPriorResource is one container's undo snapshot (serialised into
// annVpaPriorResources).
type vpaPriorResource struct {
	CPU      string `json:"cpu"`
	Memory   string `json:"memory"`
	CPULimit string `json:"cpuLimit,omitempty"`
	MemLimit string `json:"memLimit,omitempty"`
}

// resizeOutcome is the result of a single resize PATCH attempt.
type resizeOutcome int

const (
	resizeSimulated       resizeOutcome = iota // zero API calls (Simulate / DRY_RUN)
	resizeDone                                 // one successful resize PATCH
	resizeRefusedByServer                      // non-2xx 4xx — not retried as a restart
	resizeFailed                               // 5xx / transport — retry next cycle
)

// vpaTarget is one container of one running pod to consider for an in-place resize.
type vpaTarget struct {
	pod       *corev1.Pod
	container string
	kind      string // owning workload kind (Deployment/StatefulSet/DaemonSet)
	workload  string // owning workload name
	reqCPU    *resource.Quantity
	reqMem    *resource.Quantity
}

// ApplyInPlace resizes workloads toward their VPA recommendation using the
// pods/resize subresource ONLY. Simulate issues ZERO resize calls. Returns the
// per-run record and one ActionRecord per container considered.
func (v *VPAManager) ApplyInPlace(
	ctx context.Context,
	p policy.VPAManagerPolicy,
	guards policy.SafetyGuards,
	d gate.Decision,
	budget *drain.Budget,
	mset *metrics.Set,
) (*VpaRun, []policy.ActionRecord, error) {
	now := time.Now().UTC()
	run := &VpaRun{
		SchemaVersion: 1,
		RunID:         fmt.Sprintf("vpa-%d", now.UnixNano()),
		GeneratedAt:   now.Format(time.RFC3339),
		Mode:          d.Mode.String(),
		DryRun:        d.Mode == gate.Simulate || v.dryRun,
		Workloads:     []VpaWorkloadRun{},
	}
	var records []policy.ActionRecord

	// ── Post-resize OOM/throttle watch first (auto-revert, §3.4). ──
	revertRecs := v.runVpaWatch(ctx, run, mset)
	records = append(records, revertRecs...)

	targets, err := v.collectResizeTargets(ctx)
	if err != nil {
		return run, records, err
	}
	// Deterministic namespace/name/container order so the per-cycle cap is stable.
	sort.Slice(targets, func(i, j int) bool {
		a, b := targets[i], targets[j]
		if a.pod.Namespace != b.pod.Namespace {
			return a.pod.Namespace < b.pod.Namespace
		}
		if a.pod.Name != b.pod.Name {
			return a.pod.Name < b.pod.Name
		}
		return a.container < b.container
	})

	resized := 0
	for i := range targets {
		t := &targets[i]
		run.Summary.Considered++
		rec := telemetry.NewAction(policy.ControllerVPAManager, "workload-resize",
			t.pod.Namespace+"/"+t.pod.Name+"/"+t.container, run.DryRun)
		telemetry.SetDetail(&rec, "workload", t.pod.Namespace+"/"+t.workload)

		if guards.ProtectCriticalNamespaces && isCriticalNS(t.pod.Namespace, guards.CriticalNamespaces) {
			v.recordOutcome(run, &records, rec, t, vpaStatusRefused,
				"refused: critical namespace", policy.ActionRefused, mset, nil)
			continue
		}

		// (A) stateful/DB fail-closed verdict — any non-empty code refuses.
		code, dberr := v.dbVerdict(ctx, t.pod)
		if dberr != nil {
			v.recordOutcome(run, &records, rec, t, vpaStatusFailed,
				"failed: "+dberr.Error(), policy.ActionFailed, mset, nil)
			continue
		}
		if code != "" {
			v.recordOutcome(run, &records, rec, t, vpaStatusRefused,
				"refused: "+code, policy.ActionRefused, mset, func() {
					mset.IncVpaRefusedByClassifier(code)
				})
			continue
		}

		// (B) in-place feasibility — mirror the Java refusalFor rules.
		req, lim, reason := v.inPlaceEligibility(t)
		if reason != "" {
			v.recordOutcome(run, &records, rec, t, vpaStatusRefused,
				"refused: "+reason, policy.ActionRefused, mset, func() {
					mset.IncVpaRefusedNotEligible(reason)
				})
			continue
		}

		// Per-cycle cap (deterministic order). Extras are retried next cycle.
		if d.Mode == gate.Live && !v.dryRun && resized >= vpaMaxResizesPerCycle {
			v.recordOutcome(run, &records, rec, t, vpaStatusSkipped,
				"skipped: vpa resize cap reached", policy.ActionSkipped, mset, nil)
			continue
		}

		// Stamp the undo snapshot (separate metadata PATCH) BEFORE the resize; a stamp
		// failure aborts the resize — fail-closed, no unverifiable undo.
		if d.Mode == gate.Live && !v.dryRun {
			if serr := v.stampPriorResources(ctx, t); serr != nil {
				v.recordOutcome(run, &records, rec, t, vpaStatusFailed,
					"failed: stamp prior resources: "+serr.Error(), policy.ActionFailed, mset, nil)
				continue
			}
		}

		outcome, oerr := v.resizeContainer(ctx, d.Mode, t.pod, t.container, req, lim)
		switch outcome {
		case resizeSimulated:
			rec.Reason = "simulated — would resize in place (pods/resize)"
			rec = telemetry.Finish(rec, policy.ActionSimulated, false, rec.Reason)
			run.Summary.Simulated++
			run.Workloads = append(run.Workloads, v.workloadRun(t, vpaStatusSimulated, rec.Reason))
			records = append(records, rec)
		case resizeDone:
			resized++
			rec.Verification = "same pod UID, same containerID, unchanged restartCount (in place)"
			rec = telemetry.Finish(rec, policy.ActionSucceeded, true, "")
			run.Summary.Resized++
			run.Workloads = append(run.Workloads, v.workloadRun(t, vpaStatusResized, "resized in place"))
			records = append(records, rec)
			mset.IncVpaResized()
		case resizeRefusedByServer:
			msg := "refused: in-place resize rejected by the server"
			if oerr != nil {
				msg = "refused: in-place resize rejected (" + oerr.Error() + ")"
			}
			v.recordOutcome(run, &records, rec, t, vpaStatusRefused, msg, policy.ActionRefused, mset, func() {
				mset.IncVpaRefusedNotEligible("server-rejected")
			})
		default: // resizeFailed
			msg := "failed: in-place resize"
			if oerr != nil {
				msg = "failed: " + oerr.Error()
			}
			v.recordOutcome(run, &records, rec, t, vpaStatusFailed, msg, policy.ActionFailed, mset, nil)
		}
	}

	return run, records, nil
}

// recordOutcome finalises a refused/skipped/failed record, appends a workload row and
// runs an optional metric hook (only on live outcomes — Simulate never counts metrics).
func (v *VPAManager) recordOutcome(run *VpaRun, records *[]policy.ActionRecord, rec policy.ActionRecord,
	t *vpaTarget, status, reason, actionStatus string, mset *metrics.Set, metricHook func()) {
	rec = telemetry.Finish(rec, actionStatus, false, reason)
	*records = append(*records, rec)
	run.Workloads = append(run.Workloads, v.workloadRun(t, status, reason))
	switch status {
	case vpaStatusRefused:
		run.Summary.Refused++
	case vpaStatusSkipped:
		run.Summary.Skipped++
	case vpaStatusFailed:
		run.Summary.Failed++
	}
	// Metrics count LIVE outcomes only: never under Simulate / DRY_RUN.
	if metricHook != nil && !run.DryRun {
		metricHook()
	}
}

func (v *VPAManager) workloadRun(t *vpaTarget, status, reason string) VpaWorkloadRun {
	return VpaWorkloadRun{
		Namespace:     t.pod.Namespace,
		WorkloadName:  t.workload,
		WorkloadKind:  t.kind,
		ContainerName: t.container,
		Status:        status,
		Reason:        reason,
	}
}

// collectResizeTargets reads VPA recommendations and maps each to its running pods'
// containers. A recommendation with no target container value is skipped. Pods are
// matched by the VPA target workload name as the owner/name prefix.
func (v *VPAManager) collectResizeTargets(ctx context.Context) ([]vpaTarget, error) {
	recs, err := v.CollectRecommendations(ctx)
	if err != nil {
		return nil, err
	}
	// Index recommendations by namespace/workload/container → merged requests.
	type key struct{ ns, wl, c string }
	wanted := map[key]VPARecommendation{}
	for _, r := range recs {
		if r.ContainerName == "" || r.TargetCPU == "" {
			continue
		}
		wanted[key{r.Namespace, r.WorkloadName, r.ContainerName}] = r
	}
	if len(wanted) == 0 {
		return nil, nil
	}

	podList, err := v.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
	}
	var targets []vpaTarget
	for i := range podList.Items {
		pod := &podList.Items[i]
		kind, workload := ownerWorkload(pod)
		for ci := range pod.Spec.Containers {
			c := &pod.Spec.Containers[ci]
			rec, ok := wanted[key{pod.Namespace, workload, c.Name}]
			if !ok {
				continue
			}
			t := vpaTarget{pod: pod, container: c.Name, kind: kind, workload: workload}
			if q, perr := resource.ParseQuantity(rec.TargetCPU); perr == nil {
				t.reqCPU = &q
			}
			if rec.TargetMemory != "" {
				if q, perr := resource.ParseQuantity(rec.TargetMemory); perr == nil {
					t.reqMem = &q
				}
			}
			targets = append(targets, t)
		}
	}
	return targets, nil
}

// ownerWorkload returns the pod's controller owner kind + workload name. For a
// ReplicaSet-owned pod the workload is the Deployment name (the RS name minus its
// hash suffix), so a VPA targeting the Deployment matches.
func ownerWorkload(pod *corev1.Pod) (kind, name string) {
	for _, ref := range pod.OwnerReferences {
		if ref.Controller == nil || !*ref.Controller {
			continue
		}
		switch ref.Kind {
		case "ReplicaSet":
			if i := lastDash(ref.Name); i > 0 {
				return "Deployment", ref.Name[:i]
			}
			return "Deployment", ref.Name
		default:
			return ref.Kind, ref.Name
		}
	}
	return "", pod.Name
}

func lastDash(s string) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == '-' {
			return i
		}
	}
	return -1
}

// dbVerdict reuses fitsim's EXACT classification chain (buildPod → resolveCR →
// stateful.Classify) by building a single-pod snapshot through the exported entry
// point. It returns "" iff the pod is stateful.ClassAllowed; any non-empty code is a
// refusal (Protected OR Unknown). The owner RS/STS read feeds ReadyReplicas; if it
// fails the snapshot leaves ReadyReplicas=-1, which still refuses a detected replica
// via db-lag-unknown (fail-closed).
func (v *VPAManager) dbVerdict(ctx context.Context, pod *corev1.Pod) (string, error) {
	crs, err := fitsim.ListOperatorCRs(ctx, v.dynamicClient)
	if err != nil {
		return "", err // fail-closed at the caller (recorded failed, pod untouched)
	}
	rss, stss := v.ownerReplicaSets(ctx, pod)
	snap := fitsim.FromObjectsWithCRs(nil, []corev1.Pod{*pod}, nil, rss, stss, nil, nil, crs)
	if len(snap.Pods) == 0 {
		return "", nil
	}
	return snap.Pods[0].DBVerdict, nil // "" ⇒ allowed; any code ⇒ refuse
}

// ownerReplicaSets reads the pod's controller owner RS/STS (best-effort). On any read
// failure the lists are empty, leaving ReadyReplicas=-1 in the snapshot (fail-closed).
func (v *VPAManager) ownerReplicaSets(ctx context.Context, pod *corev1.Pod) ([]appsv1.ReplicaSet, []appsv1.StatefulSet) {
	for _, ref := range pod.OwnerReferences {
		if ref.Controller == nil || !*ref.Controller {
			continue
		}
		switch ref.Kind {
		case "ReplicaSet":
			rs, err := v.k8s.AppsV1().ReplicaSets(pod.Namespace).Get(ctx, ref.Name, metav1.GetOptions{})
			if err != nil {
				return nil, nil
			}
			return []appsv1.ReplicaSet{*rs}, nil
		case "StatefulSet":
			ss, err := v.k8s.AppsV1().StatefulSets(pod.Namespace).Get(ctx, ref.Name, metav1.GetOptions{})
			if err != nil {
				return nil, nil
			}
			return nil, []appsv1.StatefulSet{*ss}
		}
	}
	return nil, nil
}

// inPlaceEligibility mirrors the Java DirectInPlaceResizeExecutor.refusalFor rules on
// the live pod. On success it returns the request/limit ResourceLists to send (request
// and its limit together, limit only when one exists) and an empty reason; otherwise
// a non-empty refusal reason and nil lists.
func (v *VPAManager) inPlaceEligibility(t *vpaTarget) (req, lim corev1.ResourceList, reason string) {
	var c *corev1.Container
	for i := range t.pod.Spec.Containers {
		if t.pod.Spec.Containers[i].Name == t.container {
			c = &t.pod.Spec.Containers[i]
			break
		}
	}
	if c == nil {
		return nil, nil, "container not found"
	}
	if t.reqCPU == nil {
		return nil, nil, "no cpu recommendation"
	}

	curCPUReq, hasCPUReq := c.Resources.Requests[corev1.ResourceCPU]
	curMemReq, hasMemReq := c.Resources.Requests[corev1.ResourceMemory]
	// BestEffort / no requests → the resize subresource cannot add a resource unset at
	// creation.
	if (!hasCPUReq || curCPUReq.IsZero()) && (!hasMemReq || curMemReq.IsZero()) {
		return nil, nil, "container has no requests to resize in place"
	}

	// resizePolicy=RestartContainer on a moved resource ⇒ restart required (out of v1).
	movesCPU := t.reqCPU != nil
	movesMem := t.reqMem != nil
	for _, rp := range c.ResizePolicy {
		if rp.RestartPolicy != corev1.RestartContainer {
			continue
		}
		if rp.ResourceName == corev1.ResourceCPU && movesCPU {
			return nil, nil, "resizePolicy=RestartContainer (restart required — out of v1)"
		}
		if rp.ResourceName == corev1.ResourceMemory && movesMem {
			return nil, nil, "resizePolicy=RestartContainer (restart required — out of v1)"
		}
	}

	// Build the would-be request/limit lists, sending a request WITH its limit when a
	// limit exists (so QoS cannot drift).
	req = corev1.ResourceList{}
	lim = corev1.ResourceList{}
	curCPULim, hasCPULim := c.Resources.Limits[corev1.ResourceCPU]
	curMemLim, hasMemLim := c.Resources.Limits[corev1.ResourceMemory]

	if movesCPU {
		req[corev1.ResourceCPU] = *t.reqCPU
		if hasCPULim {
			lim[corev1.ResourceCPU] = curCPULim
			if t.reqCPU.Cmp(curCPULim) > 0 {
				return nil, nil, "request would exceed limit"
			}
		}
	} else if hasCPUReq {
		req[corev1.ResourceCPU] = curCPUReq
		if hasCPULim {
			lim[corev1.ResourceCPU] = curCPULim
		}
	}
	if movesMem {
		req[corev1.ResourceMemory] = *t.reqMem
		if hasMemLim {
			lim[corev1.ResourceMemory] = curMemLim
			if t.reqMem.Cmp(curMemLim) > 0 {
				return nil, nil, "request would exceed limit"
			}
		}
	} else if hasMemReq {
		req[corev1.ResourceMemory] = curMemReq
		if hasMemLim {
			lim[corev1.ResourceMemory] = curMemLim
		}
	}

	// QoS shape must be preserved.
	from := qosShape(c.Resources.Requests, c.Resources.Limits)
	to := qosShape(req, lim)
	if from != to {
		return nil, nil, fmt.Sprintf("in-place would change QoS class (%s -> %s)", from, to)
	}
	if len(lim) == 0 {
		lim = nil
	}
	return req, lim, ""
}

// qosShape computes the QoS class of a single container's request/limit lists.
// Guaranteed = cpu & mem request==limit (both set); BestEffort = nothing set;
// Burstable otherwise.
func qosShape(req, lim corev1.ResourceList) string {
	cpuR, hasCPUR := req[corev1.ResourceCPU]
	memR, hasMemR := req[corev1.ResourceMemory]
	if !hasCPUR && !hasMemR {
		if len(req) == 0 && len(lim) == 0 {
			return "BestEffort"
		}
		return "Burstable"
	}
	cpuL, hasCPUL := lim[corev1.ResourceCPU]
	memL, hasMemL := lim[corev1.ResourceMemory]
	if hasCPUR && hasMemR && hasCPUL && hasMemL && cpuR.Cmp(cpuL) == 0 && memR.Cmp(memL) == 0 {
		return "Guaranteed"
	}
	return "Burstable"
}

// resizeContainer issues the ONE write of this feature: a strategic-merge PATCH to the
// pod's resize subresource. It sends only the changed container's resources, request
// and limit together. In gate.Simulate (or DRY_RUN) it records the intent and makes
// ZERO API calls. It NEVER deletes/evicts a pod or patches a workload template.
func (v *VPAManager) resizeContainer(ctx context.Context, mode gate.Mode, pod *corev1.Pod,
	containerName string, req, lim corev1.ResourceList) (resizeOutcome, error) {
	if mode == gate.Simulate || v.dryRun {
		return resizeSimulated, nil // zero API calls
	}
	resources := map[string]any{"requests": quantityMap(req)}
	if len(lim) > 0 {
		resources["limits"] = quantityMap(lim)
	}
	patch := map[string]any{"spec": map[string]any{"containers": []map[string]any{{
		"name":      containerName,
		"resources": resources,
	}}}}
	body, err := json.Marshal(patch)
	if err != nil {
		return resizeFailed, err
	}
	_, err = v.k8s.CoreV1().Pods(pod.Namespace).Patch(
		ctx, pod.Name, types.StrategicMergePatchType, body, metav1.PatchOptions{
			FieldManager: "xamops-vpa-manager",
		}, "resize") // ← the resize subresource; NOT a template patch, NOT an eviction
	if err != nil {
		return outcomeFromErr(err), err
	}
	return resizeDone, nil
}

// outcomeFromErr maps an API error to a refusal (4xx — not retried as a restart) or a
// transient failure (5xx / transport — retried next cycle).
func outcomeFromErr(err error) resizeOutcome {
	if err == nil {
		return resizeDone
	}
	if se, ok := err.(apierrors.APIStatus); ok {
		code := se.Status().Code
		if code >= 400 && code < 500 {
			return resizeRefusedByServer
		}
	}
	return resizeFailed
}

func quantityMap(rl corev1.ResourceList) map[string]any {
	out := map[string]any{}
	for name, q := range rl {
		out[string(name)] = q.String()
	}
	return out
}

// stampPriorResources writes the undo snapshot + watch-window annotations to the pod
// in a SEPARATE metadata PATCH before the resize (annotations are not resizable via
// the resize subresource). A stamp failure aborts the resize.
func (v *VPAManager) stampPriorResources(ctx context.Context, t *vpaTarget) error {
	priors := map[string]vpaPriorResource{}
	for i := range t.pod.Spec.Containers {
		c := &t.pod.Spec.Containers[i]
		if c.Name != t.container {
			continue
		}
		pr := vpaPriorResource{}
		if q, ok := c.Resources.Requests[corev1.ResourceCPU]; ok {
			pr.CPU = q.String()
		}
		if q, ok := c.Resources.Requests[corev1.ResourceMemory]; ok {
			pr.Memory = q.String()
		}
		if q, ok := c.Resources.Limits[corev1.ResourceCPU]; ok {
			pr.CPULimit = q.String()
		}
		if q, ok := c.Resources.Limits[corev1.ResourceMemory]; ok {
			pr.MemLimit = q.String()
		}
		priors[c.Name] = pr
	}
	priorsJSON, err := json.Marshal(priors)
	if err != nil {
		return err
	}
	patch := map[string]any{"metadata": map[string]any{"annotations": map[string]any{
		annVpaPriorResources: string(priorsJSON),
		annVpaResizedAt:      time.Now().UTC().Format(time.RFC3339),
		annVpaWatchRemaining: strconv.Itoa(vpaWatchWindowCycles),
	}}}
	body, err := json.Marshal(patch)
	if err != nil {
		return err
	}
	_, err = v.k8s.CoreV1().Pods(t.pod.Namespace).Patch(
		ctx, t.pod.Name, types.StrategicMergePatchType, body, metav1.PatchOptions{
			FieldManager: "xamops-vpa-manager",
		})
	return err
}

// runVpaWatch processes every pod carrying xamops.io/vpa-resized-at: it queries
// Prometheus for an OOM/throttle regression (exact pod="<pod>" matcher, same queries
// and threshold as the rightsizer) and auto-reverts to the stored prior resources on a
// regression; a clean window clears the annotations; missing data never reverts.
func (v *VPAManager) runVpaWatch(ctx context.Context, run *VpaRun, mset *metrics.Set) []policy.ActionRecord {
	podList, err := v.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil
	}
	var records []policy.ActionRecord
	for i := range podList.Items {
		pod := &podList.Items[i]
		if pod.Annotations[annVpaResizedAt] == "" {
			continue
		}
		if rec := v.watchPod(ctx, run, pod, mset); rec != nil {
			records = append(records, *rec)
		}
	}
	return records
}

// watchPod runs one cycle of the auto-revert watch for a single resized pod.
func (v *VPAManager) watchPod(ctx context.Context, run *VpaRun, pod *corev1.Pod, mset *metrics.Set) *policy.ActionRecord {
	priors := readVpaPriors(pod.Annotations)
	if len(priors) == 0 {
		_ = v.clearVpaWatch(ctx, pod)
		return nil
	}
	regressed, reason := v.detectVpaRegression(ctx, pod)
	if regressed {
		return v.revertPod(ctx, run, pod, priors, reason, mset)
	}
	// Clean cycle (incl. "no Prometheus data" — never revert on absence of data).
	remaining := vpaWatchWindowCycles
	if n, err := strconv.Atoi(pod.Annotations[annVpaWatchRemaining]); err == nil {
		remaining = n
	}
	remaining--
	if remaining <= 0 {
		_ = v.clearVpaWatch(ctx, pod)
		return nil
	}
	_ = v.setVpaWatchRemaining(ctx, pod, remaining)
	return nil
}

// detectVpaRegression queries Prometheus for OOMKilled restarts and CPU throttling on
// the exact pod within the watch window. It returns (true, reason) only on positive
// evidence; missing data returns (false, "").
func (v *VPAManager) detectVpaRegression(ctx context.Context, pod *corev1.Pod) (bool, string) {
	window := "5m"
	oomQuery := fmt.Sprintf(
		`sum(increase(kube_pod_container_status_restarts_total{namespace="%s",pod="%s"}[%s]) and on(pod) (kube_pod_container_status_last_terminated_reason{namespace="%s",pod="%s",reason="OOMKilled"} > 0))`,
		pod.Namespace, pod.Name, window, pod.Namespace, pod.Name,
	)
	if oom, err := v.promQuery(ctx, oomQuery); err == nil && oom > 0 {
		return true, fmt.Sprintf("OOMKilled detected (%g restart(s) with OOMKilled in the last %s)", oom, window)
	}
	throttleQuery := fmt.Sprintf(
		`sum(rate(container_cpu_cfs_throttled_periods_total{namespace="%s",pod="%s"}[%s])) / sum(rate(container_cpu_cfs_periods_total{namespace="%s",pod="%s"}[%s]))`,
		pod.Namespace, pod.Name, window, pod.Namespace, pod.Name, window,
	)
	if ratio, err := v.promQuery(ctx, throttleQuery); err == nil && ratio > vpaThrottleRegressionRatio {
		return true, fmt.Sprintf("CPU throttling detected (%.1f%% of periods throttled over the last %s, above the %.0f%% threshold)",
			ratio*100, window, vpaThrottleRegressionRatio*100)
	}
	return false, ""
}

// revertPod re-issues a pods/resize PATCH back to the stored prior resources and
// records an aborted/reverted ActionRecord. On success the watch annotations are
// cleared. It NEVER evicts or deletes the pod.
func (v *VPAManager) revertPod(ctx context.Context, run *VpaRun, pod *corev1.Pod,
	priors map[string]vpaPriorResource, reason string, mset *metrics.Set) *policy.ActionRecord {
	rec := telemetry.NewAction(policy.ControllerVPAManager, "workload-resize-revert",
		pod.Namespace+"/"+pod.Name, v.dryRun)
	telemetry.SetDetail(&rec, "regression", reason)

	if v.dryRun {
		finished := telemetry.Finish(rec, policy.ActionAborted, false,
			"aborted: would revert after regression ("+reason+")")
		return &finished
	}

	var reverted []string
	for container, pr := range priors {
		req := corev1.ResourceList{}
		lim := corev1.ResourceList{}
		if pr.CPU != "" {
			if q, err := resource.ParseQuantity(pr.CPU); err == nil {
				req[corev1.ResourceCPU] = q
			}
		}
		if pr.Memory != "" {
			if q, err := resource.ParseQuantity(pr.Memory); err == nil {
				req[corev1.ResourceMemory] = q
			}
		}
		if pr.CPULimit != "" {
			if q, err := resource.ParseQuantity(pr.CPULimit); err == nil {
				lim[corev1.ResourceCPU] = q
			}
		}
		if pr.MemLimit != "" {
			if q, err := resource.ParseQuantity(pr.MemLimit); err == nil {
				lim[corev1.ResourceMemory] = q
			}
		}
		if _, err := v.resizeContainer(ctx, gate.Live, pod, container, req, lim); err == nil {
			reverted = append(reverted, container)
		}
	}
	if len(reverted) == 0 {
		finished := telemetry.Finish(rec, policy.ActionFailed, false,
			"auto-revert blocked after regression: "+reason)
		return &finished
	}
	telemetry.SetDetail(&rec, "restored", reverted)
	rec.Verification = "prior resources restored in place (pods/resize)"
	finished := telemetry.Finish(rec, policy.ActionAborted, true,
		"aborted: reverted after regression ("+reason+")")
	run.Summary.Reverted++
	run.Workloads = append(run.Workloads, VpaWorkloadRun{
		Namespace: pod.Namespace, WorkloadName: pod.Name, Status: vpaStatusReverted, Reason: reason,
	})
	mset.IncVpaReverted()
	_ = v.clearVpaWatch(ctx, pod)
	return &finished
}

// promQuery runs a Prometheus instant query through the test seam when set, else over
// prometheusURL. An empty URL (and nil seam) returns "no data" so the watch never
// reverts.
func (v *VPAManager) promQuery(ctx context.Context, query string) (float64, error) {
	if v.promQueryFn != nil {
		return v.promQueryFn(ctx, query)
	}
	if v.prometheusURL == "" {
		return 0, fmt.Errorf("no data")
	}
	return promInstantQuery(ctx, v.httpClient, v.prometheusURL, query)
}

func readVpaPriors(ann map[string]string) map[string]vpaPriorResource {
	out := map[string]vpaPriorResource{}
	if ann == nil {
		return out
	}
	if raw, ok := ann[annVpaPriorResources]; ok && raw != "" {
		_ = json.Unmarshal([]byte(raw), &out)
		if out == nil {
			out = map[string]vpaPriorResource{}
		}
	}
	return out
}

func (v *VPAManager) clearVpaWatch(ctx context.Context, pod *corev1.Pod) error {
	if v.dryRun {
		return nil
	}
	patch := map[string]any{"metadata": map[string]any{"annotations": map[string]any{
		annVpaPriorResources: nil,
		annVpaResizedAt:      nil,
		annVpaWatchRemaining: nil,
	}}}
	body, _ := json.Marshal(patch)
	_, err := v.k8s.CoreV1().Pods(pod.Namespace).Patch(
		ctx, pod.Name, types.StrategicMergePatchType, body, metav1.PatchOptions{
			FieldManager: "xamops-vpa-manager",
		})
	return err
}

// promInstantQuery runs a single Prometheus instant query and returns the first
// sample value, mirroring the rightsizer's helper.
func promInstantQuery(ctx context.Context, client *http.Client, baseURL, query string) (float64, error) {
	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s", baseURL, url.QueryEscape(query))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, err
	}
	resp, err := client.Do(req)
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
	val, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, err
	}
	if math.IsNaN(val) || math.IsInf(val, 0) {
		return 0, fmt.Errorf("no data (%s)", s)
	}
	return val, nil
}

func (v *VPAManager) setVpaWatchRemaining(ctx context.Context, pod *corev1.Pod, remaining int) error {
	if v.dryRun {
		return nil
	}
	patch := map[string]any{"metadata": map[string]any{"annotations": map[string]any{
		annVpaWatchRemaining: strconv.Itoa(remaining),
	}}}
	body, _ := json.Marshal(patch)
	_, err := v.k8s.CoreV1().Pods(pod.Namespace).Patch(
		ctx, pod.Name, types.StrategicMergePatchType, body, metav1.PatchOptions{
			FieldManager: "xamops-vpa-manager",
		})
	return err
}
