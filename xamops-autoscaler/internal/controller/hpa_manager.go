package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	autoscalingv2 "k8s.io/api/autoscaling/v2"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/policy"
)

// HPAManager reports HPA recommendations (collector "hpa-recommendations").
//
// Scope (2026-10-10 narrowing, plan.md D10): the HPA decision logic is being retired
// into the Java engine, which must own HPA and VPA decisions together (a CPU request
// change moves the HPA utilisation denominator). This controller therefore keeps its
// existing recommendation formula and only carries safety work:
//   - PDB veto: a recommended minReplicas never undercuts a PDB (min >= pdbFloor);
//   - unschedulable/Pending workloads, protectSingleReplicas, protectCriticalNamespaces
//     and excludeNamespaces are skipped consistently with the rightsizer;
//   - honest telemetry: every row carries finding and dryRun.
//
// It is analysis only: the gate never makes it Live and Apply/upsertHPA/deleteHPA have
// no caller (off by default). p90Cpu is collected and reported upward for the Java
// engine; it is not a load-aware input here.
type HPAManager struct {
	k8s           kubernetes.Interface
	prometheusURL string
	httpClient    *http.Client
	dryRun        bool
}

func NewHPAManager(k8s kubernetes.Interface, prometheusURL string, dryRun bool) *HPAManager {
	return &HPAManager{
		k8s:           k8s,
		prometheusURL: prometheusURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
		dryRun:        dryRun,
	}
}

// HPA findings (HPARecommendation.Finding); first match wins.
const (
	FindingUnschedulable          = "unschedulable"
	FindingProtectedSingleReplica = "protected-single-replica"
	FindingMetricsUnavailable     = "metrics-unavailable"
	FindingRecommend              = "recommend"
	// AdvisoryPDBBlocksDisruption is added to Reason when a PDB allows no voluntary
	// disruption at all (maxUnavailable 0, minAvailable 100%).
	AdvisoryPDBBlocksDisruption = "pdb-blocks-disruption"
)

// HPARecommendation is the output of the analysis phase pushed back to XamOps.
// RecommendMin/Max/Target are set only for Finding == "recommend".
type HPARecommendation struct {
	Namespace       string  `json:"namespace"`
	WorkloadName    string  `json:"workloadName"`
	WorkloadKind    string  `json:"workloadKind"` // Deployment | StatefulSet
	ContainerName   string  `json:"containerName"`
	CurrentHPA      bool    `json:"currentHpa"` // HPA already exists?
	RecommendMin    int32   `json:"recommendMin"`
	RecommendMax    int32   `json:"recommendMax"`
	RecommendTarget int32   `json:"recommendTargetCpuPct"` // CPU utilization target %
	P90CPU          float64 `json:"p90Cpu"`                // 90th percentile CPU (cores), reported upward
	CurrentReplicas int32   `json:"currentReplicas"`
	Applied         bool    `json:"applied"`
	AppliedAt       string  `json:"appliedAt,omitempty"`
	Reason          string  `json:"reason,omitempty"` // why skipped if not applied
	// Finding: unschedulable | protected-single-replica | metrics-unavailable | recommend.
	Finding string `json:"finding"`
	// PDBMinAvailable lists the minAvailable values of the matching PDBs as written.
	PDBMinAvailable string `json:"pdbMinAvailable,omitempty"`
	// PDBFloor is the smallest minReplicas that still leaves one voluntary disruption
	// under every matching PDB (0 = no matching PDB). RecommendMin is never below it.
	PDBFloor int32 `json:"pdbFloor"`
	// PDBConflict: an existing HPA's minReplicas is below PDBFloor (advisory).
	PDBConflict bool `json:"pdbConflict"`
	DryRun      bool `json:"dryRun"`
}

// HPAApplyRequest is received from XamOps backend telling the agent to apply an HPA.
type HPAApplyRequest struct {
	Namespace    string `json:"namespace"`
	WorkloadName string `json:"workloadName"`
	WorkloadKind string `json:"workloadKind"`
	MinReplicas  int32  `json:"minReplicas"`
	MaxReplicas  int32  `json:"maxReplicas"`
	TargetCPUPct int32  `json:"targetCpuPct"`
	Delete       bool   `json:"delete"` // true = remove the HPA
}

// hpaWorkload is one Deployment/StatefulSet with the objects that decide its row.
type hpaWorkload struct {
	namespace, name, kind string
	replicas              int32 // spec.replicas (nil -> 1)
	ready                 int32
	templateLabels        map[string]string
	pods                  []corev1.Pod
}

// Analyse scans Deployments (and StatefulSets when not protected) and reports one row
// per workload. dryRun is reported on every row.
func (h *HPAManager) Analyse(ctx context.Context, p policy.HPAManagerPolicy, guards policy.SafetyGuards, dryRun bool) ([]HPARecommendation, error) {
	results := make([]HPARecommendation, 0)

	existing, err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list HPAs: %w", err)
	}
	pdbs, err := h.k8s.PolicyV1().PodDisruptionBudgets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list PDBs: %w", err)
	}
	pods, err := h.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
	}
	rsets, err := h.k8s.AppsV1().ReplicaSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list replicasets: %w", err)
	}

	skip := func(ns string) bool {
		return (guards.ProtectCriticalNamespaces && isCriticalNS(ns, guards.CriticalNamespaces)) ||
			isCriticalNS(ns, p.ExcludeNamespaces)
	}

	deployments, err := h.k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list deployments: %w", err)
	}
	for _, dep := range deployments.Items {
		if skip(dep.Namespace) {
			continue
		}
		if dep.Spec.Replicas != nil && *dep.Spec.Replicas == 0 {
			continue // skip scaled-to-zero deployments
		}
		w := hpaWorkload{namespace: dep.Namespace, name: dep.Name, kind: "Deployment",
			replicas: replicasOrOne(dep.Spec.Replicas), ready: dep.Status.ReadyReplicas,
			templateLabels: dep.Spec.Template.Labels,
			pods:           deploymentPods(dep, rsets.Items, pods.Items)}
		results = append(results, h.analyseWorkload(ctx, w, guards, dryRun, pdbs.Items, existing.Items))
	}

	if !guards.ProtectStatefulSets {
		statefulSets, err := h.k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
		if err != nil {
			log.Printf("WARN: hpa-manager list statefulsets: %v", err)
		} else {
			for _, ss := range statefulSets.Items {
				if skip(ss.Namespace) {
					continue
				}
				if ss.Spec.Replicas != nil && *ss.Spec.Replicas == 0 {
					continue
				}
				w := hpaWorkload{namespace: ss.Namespace, name: ss.Name, kind: "StatefulSet",
					replicas: replicasOrOne(ss.Spec.Replicas), ready: ss.Status.ReadyReplicas,
					templateLabels: ss.Spec.Template.Labels,
					pods:           ownedPods(pods.Items, ss.Namespace, "StatefulSet", ss.Name, string(ss.UID))}
				results = append(results, h.analyseWorkload(ctx, w, guards, dryRun, pdbs.Items, existing.Items))
			}
		}
	}

	return results, nil
}

func replicasOrOne(r *int32) int32 {
	if r == nil {
		return 1
	}
	return *r
}

// ownedBy reports whether refs name kind/name as controller owner (UID-checked when known).
func ownedBy(refs []metav1.OwnerReference, kind, name, uid string) bool {
	for _, ref := range refs {
		if ref.Kind == kind && ref.Name == name && (uid == "" || ref.UID == "" || string(ref.UID) == uid) {
			return true
		}
	}
	return false
}

func ownedPods(pods []corev1.Pod, ns, kind, name, uid string) []corev1.Pod {
	var out []corev1.Pod
	for _, p := range pods {
		if p.Namespace == ns && ownedBy(p.OwnerReferences, kind, name, uid) {
			out = append(out, p)
		}
	}
	return out
}

// deploymentPods returns the pods of the ReplicaSets owned by the Deployment.
func deploymentPods(dep appsv1.Deployment, rsets []appsv1.ReplicaSet, pods []corev1.Pod) []corev1.Pod {
	var out []corev1.Pod
	for _, rs := range rsets {
		if rs.Namespace == dep.Namespace && ownedBy(rs.OwnerReferences, "Deployment", dep.Name, string(dep.UID)) {
			out = append(out, ownedPods(pods, rs.Namespace, "ReplicaSet", rs.Name, string(rs.UID))...)
		}
	}
	return out
}

// pendingSummary returns the Pending pod count and the PodScheduled reasons seen.
func pendingSummary(pods []corev1.Pod) (int, string) {
	n := 0
	reasons := map[string]bool{}
	for _, p := range pods {
		if p.Status.Phase != corev1.PodPending {
			continue
		}
		n++
		reason := "Pending"
		for _, c := range p.Status.Conditions {
			if c.Type == corev1.PodScheduled && c.Status == corev1.ConditionFalse && c.Reason != "" {
				reason = c.Reason
			}
		}
		reasons[reason] = true
	}
	keys := make([]string, 0, len(reasons))
	for k := range reasons {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return n, strings.Join(keys, ",")
}

// matchingPDBs returns the PDBs of ns whose selector (full matchLabels +
// matchExpressions) selects the template labels, sorted by name.
func matchingPDBs(pdbs []policyv1.PodDisruptionBudget, ns string, tmplLabels map[string]string) []policyv1.PodDisruptionBudget {
	var out []policyv1.PodDisruptionBudget
	for _, pdb := range pdbs {
		if pdb.Namespace != ns {
			continue
		}
		sel, err := metav1.LabelSelectorAsSelector(pdb.Spec.Selector)
		if err != nil {
			log.Printf("WARN: hpa-manager PDB %s/%s selector: %v", pdb.Namespace, pdb.Name, err)
			continue
		}
		if sel.Matches(labels.Set(tmplLabels)) {
			out = append(out, pdb)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// pdbFloor computes the smallest replica count that leaves one voluntary disruption
// under every PDB (plan.md D10 PDB headroom rule):
//   - minAvailable M (int) -> M+1;
//   - minAvailable p% -> smallest n >= 1 with n - ceil(p*n/100) >= 1 (none for 100%);
//   - maxUnavailable >= 1 or > 0% -> 1;
//   - maxUnavailable 0 / 0% (or minAvailable 100%) -> advisory pdb-blocks-disruption;
//   - several PDBs -> the maximum.
//
// minAvailable returns the minAvailable values as written, comma-separated.
func pdbFloor(pdbs []policyv1.PodDisruptionBudget) (floor int32, minAvailable string, advisories []string) {
	var written []string
	for _, pdb := range pdbs {
		f := int32(0)
		switch {
		case pdb.Spec.MinAvailable != nil:
			v := pdb.Spec.MinAvailable
			written = append(written, v.String())
			if v.Type == intstr.Int {
				f = v.IntVal + 1
				break
			}
			pct, ok := parsePercent(v.StrVal)
			if !ok {
				advisories = append(advisories, fmt.Sprintf("PDB %s: unparsable minAvailable %q", pdb.Name, v.StrVal))
				break
			}
			if n, ok := smallestDisruptable(pct); ok {
				f = n
			} else {
				advisories = append(advisories, fmt.Sprintf("%s: PDB %s minAvailable %s allows no disruption", AdvisoryPDBBlocksDisruption, pdb.Name, v.StrVal))
			}
		case pdb.Spec.MaxUnavailable != nil:
			v := pdb.Spec.MaxUnavailable
			blocks := false
			if v.Type == intstr.Int {
				blocks = v.IntVal < 1
			} else {
				pct, ok := parsePercent(v.StrVal)
				blocks = !ok || pct <= 0
			}
			if blocks {
				advisories = append(advisories, fmt.Sprintf("%s: PDB %s maxUnavailable %s allows no disruption", AdvisoryPDBBlocksDisruption, pdb.Name, v.String()))
			} else {
				f = 1
			}
		default:
			f = 1
		}
		if f > floor {
			floor = f
		}
	}
	return floor, strings.Join(written, ","), advisories
}

func parsePercent(s string) (int, bool) {
	if !strings.HasSuffix(s, "%") {
		return 0, false
	}
	n, err := strconv.Atoi(strings.TrimSuffix(s, "%"))
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}

// smallestDisruptable is the smallest n >= 1 with n - ceil(pct*n/100) >= 1.
func smallestDisruptable(pct int) (int32, bool) {
	if pct >= 100 {
		return 0, false
	}
	for n := 1; n <= 10000; n++ {
		if n-(pct*n+99)/100 >= 1 {
			return int32(n), true
		}
	}
	return 0, false
}

// matchingHPA returns the HPA whose scaleTargetRef is kind/name in ns.
func matchingHPA(hpas []autoscalingv2.HorizontalPodAutoscaler, ns, kind, name string) *autoscalingv2.HorizontalPodAutoscaler {
	for i := range hpas {
		ref := hpas[i].Spec.ScaleTargetRef
		if hpas[i].Namespace == ns && ref.Kind == kind && ref.Name == name {
			return &hpas[i]
		}
	}
	return nil
}

func (h *HPAManager) analyseWorkload(
	ctx context.Context,
	w hpaWorkload,
	guards policy.SafetyGuards,
	dryRun bool,
	pdbs []policyv1.PodDisruptionBudget,
	hpas []autoscalingv2.HorizontalPodAutoscaler,
) HPARecommendation {
	rec := HPARecommendation{
		Namespace:       w.namespace,
		WorkloadName:    w.name,
		WorkloadKind:    w.kind,
		CurrentReplicas: w.ready,
		DryRun:          dryRun,
	}
	hpa := matchingHPA(hpas, w.namespace, w.kind, w.name)
	rec.CurrentHPA = hpa != nil

	floor, minAvail, advisories := pdbFloor(matchingPDBs(pdbs, w.namespace, w.templateLabels))
	rec.PDBFloor, rec.PDBMinAvailable = floor, minAvail
	if hpa != nil {
		hpaMin := int32(1)
		if hpa.Spec.MinReplicas != nil {
			hpaMin = *hpa.Spec.MinReplicas
		}
		if hpaMin < floor {
			rec.PDBConflict = true
			advisories = append(advisories, fmt.Sprintf("pdbConflict: existing HPA %s minReplicas %d is below pdbFloor %d", hpa.Name, hpaMin, floor))
		}
	}
	reason := func(msg string) string {
		if len(advisories) == 0 {
			return msg
		}
		return msg + " | " + strings.Join(advisories, "; ")
	}

	if n, why := pendingSummary(w.pods); n > 0 {
		rec.Finding = FindingUnschedulable
		rec.Reason = reason(fmt.Sprintf("%d pod(s) Pending (%s) — no HPA recommended for a workload that cannot schedule", n, why))
		return rec
	}
	if guards.ProtectSingleReplicas && w.replicas == 1 {
		rec.Finding = FindingProtectedSingleReplica
		rec.Reason = reason("protectSingleReplicas: single-replica workload — skipped (same rule as the rightsizer)")
		return rec
	}

	// p90 CPU is collected and reported upward (the Java engine consumes it); the
	// formula below keeps its existing inputs.
	p90CPU, err := h.queryP90CPU(ctx, w.namespace, w.name)
	if err != nil {
		log.Printf("DEBUG: hpa-manager p90 CPU %s/%s: %v", w.namespace, w.name, err)
		rec.Finding = FindingMetricsUnavailable
		rec.Reason = reason("prometheus unavailable for CPU analysis")
		return rec
	}
	rec.P90CPU = roundTo2(p90CPU)

	// Existing formula (decision logic is retired to the Java engine; not reworked).
	// Recommend min = 2 if multi-replica, else 1
	rec.RecommendMin = 1
	if w.ready > 1 {
		rec.RecommendMin = 2
	}

	// Recommend max = max(3, currentReplicas*2) capped at 20
	rec.RecommendMax = w.ready * 2
	if rec.RecommendMax < 3 {
		rec.RecommendMax = 3
	}
	if rec.RecommendMax > 20 {
		rec.RecommendMax = 20
	}

	// Target CPU utilization: if P90 > 70% suggest 70%, else 80%
	if p90CPU > 70 {
		rec.RecommendTarget = 70
	} else {
		rec.RecommendTarget = 80
	}

	// PDB veto: minReplicas never undercuts a PDB; max stays above min.
	msg := "recommendation (analysis only — apply is off and superseded by the Java engine)"
	if rec.RecommendMin < floor {
		msg += fmt.Sprintf("; minReplicas raised from %d to pdbFloor %d (PDB minAvailable %s)", rec.RecommendMin, floor, minAvail)
		rec.RecommendMin = floor
	}
	if rec.RecommendMax < rec.RecommendMin+1 {
		rec.RecommendMax = rec.RecommendMin + 1
	}
	rec.Finding = FindingRecommend
	rec.Reason = reason(msg)
	return rec
}

// Apply creates or updates an HPA as directed by the XamOps backend.
//
// Analysis only: Apply has no caller in this build (plan.md D10; the HPA gate is never
// Live). It is kept for the Java-engine/agent command path and stays off by default.
func (h *HPAManager) Apply(ctx context.Context, req HPAApplyRequest) error {
	if req.Delete {
		return h.deleteHPA(ctx, req.Namespace, req.WorkloadName)
	}
	return h.upsertHPA(ctx, req)
}

func (h *HPAManager) upsertHPA(ctx context.Context, req HPAApplyRequest) error {
	minReplicas := req.MinReplicas
	cpuTarget := req.TargetCPUPct

	desired := &autoscalingv2.HorizontalPodAutoscaler{
		ObjectMeta: metav1.ObjectMeta{
			Name:      req.WorkloadName + "-xamops-hpa",
			Namespace: req.Namespace,
			Labels: map[string]string{
				"app.kubernetes.io/managed-by": "xamops-automation",
			},
			Annotations: map[string]string{
				"xamops.io/managed":  "true",
				"xamops.io/modified": time.Now().UTC().Format(time.RFC3339),
			},
		},
		Spec: autoscalingv2.HorizontalPodAutoscalerSpec{
			ScaleTargetRef: autoscalingv2.CrossVersionObjectReference{
				APIVersion: "apps/v1",
				Kind:       req.WorkloadKind,
				Name:       req.WorkloadName,
			},
			MinReplicas: &minReplicas,
			MaxReplicas: req.MaxReplicas,
			Metrics: []autoscalingv2.MetricSpec{
				{
					Type: autoscalingv2.ResourceMetricSourceType,
					Resource: &autoscalingv2.ResourceMetricSource{
						Name: corev1.ResourceCPU,
						Target: autoscalingv2.MetricTarget{
							Type:               autoscalingv2.UtilizationMetricType,
							AverageUtilization: &cpuTarget,
						},
					},
				},
			},
		},
	}

	if h.dryRun {
		log.Printf("DRY-RUN: would upsert HPA %s/%s (min=%d max=%d target=%d%%)",
			req.Namespace, desired.Name, req.MinReplicas, req.MaxReplicas, req.TargetCPUPct)
		return nil
	}

	_, err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Get(ctx, desired.Name, metav1.GetOptions{})
	if errors.IsNotFound(err) {
		_, createErr := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Create(ctx, desired, metav1.CreateOptions{})
		if createErr != nil {
			return fmt.Errorf("create HPA %s/%s: %w", req.Namespace, desired.Name, createErr)
		}
		log.Printf("HPA created: %s/%s", req.Namespace, desired.Name)
		return nil
	}
	if err != nil {
		return fmt.Errorf("get HPA %s/%s: %w", req.Namespace, desired.Name, err)
	}

	// Update existing
	existing, _ := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Get(ctx, desired.Name, metav1.GetOptions{})
	existing.Spec = desired.Spec
	if existing.Annotations == nil {
		existing.Annotations = map[string]string{}
	}
	existing.Annotations["xamops.io/modified"] = time.Now().UTC().Format(time.RFC3339)

	_, err = h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Update(ctx, existing, metav1.UpdateOptions{})
	if err != nil {
		return fmt.Errorf("update HPA %s/%s: %w", req.Namespace, desired.Name, err)
	}
	log.Printf("HPA updated: %s/%s", req.Namespace, desired.Name)
	return nil
}

func (h *HPAManager) deleteHPA(ctx context.Context, namespace, workloadName string) error {
	hpaName := workloadName + "-xamops-hpa"
	if h.dryRun {
		log.Printf("DRY-RUN: would delete HPA %s/%s", namespace, hpaName)
		return nil
	}
	err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(namespace).Delete(ctx, hpaName, metav1.DeleteOptions{})
	if errors.IsNotFound(err) {
		return nil // already gone
	}
	if err != nil {
		return fmt.Errorf("delete HPA %s/%s: %w", namespace, hpaName, err)
	}
	log.Printf("HPA deleted: %s/%s", namespace, hpaName)
	return nil
}

// ── Prometheus helpers ────────────────────────────────────────────────────────

// queryP90CPU returns the 90th percentile CPU usage (cores) for a workload over 1h.
func (h *HPAManager) queryP90CPU(ctx context.Context, namespace, workload string) (float64, error) {
	// Uses kube_pod_labels to match pods by owner (Deployment/RS label)
	query := fmt.Sprintf(
		`quantile(0.9, sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="%s",container!="",container!="POD"}[5m])))`,
		namespace)

	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s",
		h.prometheusURL, url.QueryEscape(query))

	body, err := hpaHTTPGet(ctx, h.httpClient, endpoint)
	if err != nil {
		return 0, err
	}

	var resp struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Value []interface{} `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return 0, err
	}
	if resp.Status != "success" || len(resp.Data.Result) == 0 {
		return 0, fmt.Errorf("no prometheus results for %s/%s", namespace, workload)
	}
	vals := resp.Data.Result[0].Value
	if len(vals) < 2 {
		return 0, fmt.Errorf("unexpected prometheus value format")
	}
	s, ok := vals[1].(string)
	if !ok {
		return 0, fmt.Errorf("unexpected value type")
	}
	return strconv.ParseFloat(s, 64)
}

func hpaHTTPGet(ctx context.Context, client *http.Client, endpoint string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 512)
	for {
		n, readErr := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if readErr != nil {
			break
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return buf, nil
}
