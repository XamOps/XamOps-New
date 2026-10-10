package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/telemetry"
)

// NodeOptimizer analyses node utilization, detects idle nodes, lists on-demand nodes
// that could be replaced with spot, detects spot interruption notices, and — only
// through gate.NodeAction, the shared drain budget and drain.Executor — cordons or
// drains nodes. It has no pricing input, so it reports no savings numbers.
type NodeOptimizer struct {
	k8s           kubernetes.Interface
	dc            dynamic.Interface // for Karpenter NodePool CRD patching (optional)
	metricsClient metricsv.Interface
	prometheusURL string
	httpClient    *http.Client
	dryRun        bool
}

func NewNodeOptimizer(k8s kubernetes.Interface, mc metricsv.Interface, dc dynamic.Interface, prometheusURL string, dryRun bool) *NodeOptimizer {
	return &NodeOptimizer{
		k8s:           k8s,
		dc:            dc,
		metricsClient: mc,
		prometheusURL: prometheusURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
		dryRun:        dryRun,
	}
}

// ── DrainProgressReport ───────────────────────────────────────────────────────

// DrainProgressReport describes the outcome of a single node drain operation.
// Pushed back to XamOps backend as collector "node-drain-progress". It is the
// executor's report type (statuses include simulated, refused and aborted).
type DrainProgressReport = drain.Report

// Reasons for values the node optimizer cannot know (plan.md D11).
const (
	StatusUnknown          = "unknown"
	NoPricingReason        = "no pricing input — pricing is owned by the XamOps backend (roadmap C2); the agent has none"
	InterruptibilityReason = "interruption tolerance not assessed"
)

// NodeOptimizerReport is pushed as collector "node-optimizer-report".
type NodeOptimizerReport struct {
	TotalNodes             int                 `json:"totalNodes"`
	OnDemandNodes          int                 `json:"onDemandNodes"`
	SpotNodes              int                 `json:"spotNodes"`
	IdleNodes              []IdleNodeInfo      `json:"idleNodes"`
	SpotCandidates         []SpotCandidateInfo `json:"spotCandidates"`
	InterruptedNodes       []string            `json:"interruptedNodes,omitempty"`
	EstimatedSavingsPct    *float64            `json:"estimatedSavingsPct"` // always null: no pricing input
	EstimatedSavingsStatus string              `json:"estimatedSavingsStatus"`
	EstimatedSavingsReason string              `json:"estimatedSavingsReason"`
	DryRun                 bool                `json:"dryRun"`
	CollectedAt            string              `json:"collectedAt"`
}

type IdleNodeInfo struct {
	NodeName       string  `json:"nodeName"`
	InstanceType   string  `json:"instanceType,omitempty"`
	CPUUsagePct    float64 `json:"cpuUsagePct"`
	MemUsagePct    float64 `json:"memUsagePct"`
	PodCount       int     `json:"podCount"`
	SystemPodsOnly bool    `json:"systemPodsOnly"` // only kube-system/daemonset pods
	RecommendDrain bool    `json:"recommendDrain"`
	CapacityType   string  `json:"capacityType"`
}

type SpotCandidateInfo struct {
	NodeName     string  `json:"nodeName"`
	InstanceType string  `json:"instanceType,omitempty"`
	CPUUsagePct  float64 `json:"cpuUsagePct"` // live, informational only
	MemUsagePct  float64 `json:"memUsagePct"` // live, informational only
	// RequestsCPUPct = summed pod CPU requests / allocatable CPU. Drain-target
	// selection uses this (stable), never live CPU%.
	RequestsCPUPct         float64  `json:"requestsCpuPct"`
	SavingsPct             *float64 `json:"savingsPct"` // always null: no pricing input
	SavingsStatus          string   `json:"savingsStatus"`
	SavingsReason          string   `json:"savingsReason"`
	Interruptible          *bool    `json:"interruptible"` // always null: not assessed
	InterruptibilityStatus string   `json:"interruptibilityStatus"`
	InterruptibilityReason string   `json:"interruptibilityReason"`
}

// Analyse runs the node optimization analysis (read-only).
func (n *NodeOptimizer) Analyse(ctx context.Context, guards policy.SafetyGuards) (*NodeOptimizerReport, error) {
	report := &NodeOptimizerReport{
		CollectedAt:            time.Now().UTC().Format(time.RFC3339),
		EstimatedSavingsStatus: StatusUnknown,
		EstimatedSavingsReason: NoPricingReason,
		DryRun:                 n.dryRun,
	}

	nodes, err := n.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list nodes: %w", err)
	}

	// Build pod-per-node map
	podList, err := n.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{
		FieldSelector: "status.phase=Running",
	})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
	}
	podsOnNode := map[string][]corev1.Pod{}
	for _, p := range podList.Items {
		if p.Spec.NodeName != "" && p.Status.Phase == corev1.PodRunning {
			podsOnNode[p.Spec.NodeName] = append(podsOnNode[p.Spec.NodeName], p)
		}
	}

	// Gather node metrics
	cpuPct, memPct := n.gatherNodeUtilization(ctx, nodes.Items)

	report.TotalNodes = len(nodes.Items)

	for _, node := range nodes.Items {
		capacityType := "on-demand"
		if isSpotNode(node) {
			capacityType = "spot"
			report.SpotNodes++
		} else {
			report.OnDemandNodes++
		}

		instanceType := node.Labels["node.kubernetes.io/instance-type"]
		cpuU := cpuPct[node.Name]
		memU := memPct[node.Name]
		pods := podsOnNode[node.Name]
		podCount := len(pods)

		// Detect idle nodes: CPU < 10% AND memory < 20%
		isIdle := cpuU < 10 && memU < 20

		// System-pods-only: all pods are kube-system or DaemonSet
		systemOnly := allSystemPods(pods)

		if isIdle || (systemOnly && podCount <= 3) {
			recommend := isIdle && systemOnly
			report.IdleNodes = append(report.IdleNodes, IdleNodeInfo{
				NodeName:       node.Name,
				InstanceType:   instanceType,
				CPUUsagePct:    nodeOptimizerRound(cpuU),
				MemUsagePct:    nodeOptimizerRound(memU),
				PodCount:       podCount,
				SystemPodsOnly: systemOnly,
				RecommendDrain: recommend,
				CapacityType:   capacityType,
			})
		}

		// On-demand nodes that could be spot candidates (CPU < 70%, stable workloads)
		if capacityType == "on-demand" && cpuU < 70 && cpuU > 0 {
			report.SpotCandidates = append(report.SpotCandidates, SpotCandidateInfo{
				NodeName:               node.Name,
				InstanceType:           instanceType,
				CPUUsagePct:            nodeOptimizerRound(cpuU),
				MemUsagePct:            nodeOptimizerRound(memU),
				RequestsCPUPct:         nodeOptimizerRound(requestsCPUPct(node, pods)),
				SavingsStatus:          StatusUnknown,
				SavingsReason:          NoPricingReason,
				InterruptibilityStatus: StatusUnknown,
				InterruptibilityReason: InterruptibilityReason,
			})
		}
	}

	sortByRequests(report.SpotCandidates)
	sort.SliceStable(report.IdleNodes, func(i, j int) bool { return report.IdleNodes[i].NodeName < report.IdleNodes[j].NodeName })

	// Check for spot interruption notices via AWS node termination handler labels
	report.InterruptedNodes = n.detectSpotInterruptions(ctx)

	return report, nil
}

// requestsCPUPct = Σ container CPU requests of the node's pods / allocatable CPU.
func requestsCPUPct(node corev1.Node, pods []corev1.Pod) float64 {
	alloc := node.Status.Allocatable.Cpu().MilliValue()
	if alloc <= 0 {
		return 0
	}
	var req int64
	for _, p := range pods {
		for _, c := range p.Spec.Containers {
			req += c.Resources.Requests.Cpu().MilliValue()
		}
	}
	return float64(req) / float64(alloc) * 100
}

// sortByRequests orders by requests utilisation ascending, then node name.
func sortByRequests(c []SpotCandidateInfo) {
	sort.SliceStable(c, func(i, j int) bool {
		if c[i].RequestsCPUPct != c[j].RequestsCPUPct {
			return c[i].RequestsCPUPct < c[j].RequestsCPUPct
		}
		return c[i].NodeName < c[j].NodeName
	})
}

func nodeOpID(action, node string) string {
	return fmt.Sprintf("node-optimizer-%s-%s-%d", action, node, time.Now().Unix())
}

// HandleSpotInterruption drains a node that carries an AWS spot interruption notice
// through the same executor and guards as every other drain: no PDB override and no
// "evicting anyway". If the plan is not drainable the interruption is reported, not
// drained — aws-node-termination-handler, when installed, drains on its own.
func (n *NodeOptimizer) HandleSpotInterruption(ctx context.Context, nodeName string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.Outcome {
	out := exec.Drain(ctx, policy.ControllerNodeOptimizer, nodeOpID("interruption", nodeName), []string{nodeName}, mode, budget)
	for i := range out.Actions {
		telemetry.SetDetail(&out.Actions[i], "trigger", "spot-interruption")
	}
	return out
}

// CordonIdleNode cordons a detected idle node for review, through the executor.
func (n *NodeOptimizer) CordonIdleNode(ctx context.Context, nodeName string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.Outcome {
	out := exec.CordonOnly(ctx, policy.ControllerNodeOptimizer, nodeOpID("cordon", nodeName), nodeName, mode, budget)
	for i := range out.Actions {
		telemetry.SetDetail(&out.Actions[i], "trigger", "idle-node")
	}
	return out
}

// ── Drains ────────────────────────────────────────────────────────────────────

// DrainForSpotConversion drains an on-demand node (idle drain or spot-ratio drain).
// Thin wrapper over drain.Executor: budget -> plan -> cordon -> Eviction API ->
// new-UID replacement wait -> verification; any failure aborts and uncordons.
func (n *NodeOptimizer) DrainForSpotConversion(ctx context.Context, trigger, nodeName string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.Outcome {
	out := exec.Drain(ctx, policy.ControllerNodeOptimizer, nodeOpID(trigger, nodeName), []string{nodeName}, mode, budget)
	for i := range out.Actions {
		telemetry.SetDetail(&out.Actions[i], "trigger", trigger)
	}
	return out
}

// waitForPodRunningElsewhere waits for a replacement of pod: a pod of the same
// controller whose UID is not in knownUIDs (the UIDs present before the eviction),
// Running+Ready on a node other than excludeNode. Thin wrapper over
// drain.WaitForReplacement — a pre-existing sibling never satisfies it.
func (n *NodeOptimizer) waitForPodRunningElsewhere(ctx context.Context, pod corev1.Pod, knownUIDs map[string]bool, excludeNode string, timeout time.Duration) bool {
	ownerUID := ""
	for _, ref := range pod.OwnerReferences {
		if ref.Controller != nil && *ref.Controller {
			ownerUID = string(ref.UID)
		}
	}
	if ownerUID == "" {
		return false // nothing recreates an ownerless pod
	}
	known := map[string]bool{string(pod.UID): true}
	for uid := range knownUIDs {
		known[uid] = true
	}
	_, ok := drain.WaitForReplacement(ctx, n.k8s, pod.Namespace, ownerUID, known,
		map[string]bool{excludeNode: true}, timeout, 5*time.Second)
	return ok
}

// ── Karpenter NodePool patching ───────────────────────────────────────────────

var karpenterNodePoolGVR = schema.GroupVersionResource{
	Group:    "karpenter.sh",
	Version:  "v1",
	Resource: "nodepools",
}

// PatchKarpenterSpotRatio annotates Karpenter NodePools with the desired spot ratio
// (intent only; the ratio is reached via drains). Gated by gate.NodeAction; it needs
// no drain budget. Simulate lists the NodePools and writes nothing.
func (n *NodeOptimizer) PatchKarpenterSpotRatio(ctx context.Context, spotPct int, mode gate.Mode) policy.ActionRecord {
	rec := telemetry.NewAction(policy.ControllerNodeOptimizer, "karpenter-spot-ratio-patch", "nodepools", mode != gate.Live)
	telemetry.SetDetail(&rec, "spotRatioTargetPct", spotPct)
	if mode != gate.Live && mode != gate.Simulate {
		return telemetry.Finish(rec, policy.ActionSkipped, false, fmt.Sprintf("gate mode %s", mode))
	}
	if n.dc == nil {
		return telemetry.Finish(rec, policy.ActionRefused, false, "dynamic client not available for Karpenter patching")
	}

	nodePools, err := n.dc.Resource(karpenterNodePoolGVR).List(ctx, metav1.ListOptions{})
	if err != nil {
		return telemetry.Finish(rec, policy.ActionFailed, false, fmt.Sprintf("list Karpenter NodePools: %v", err))
	}
	telemetry.SetDetail(&rec, "nodePools", len(nodePools.Items))

	if mode == gate.Simulate {
		return telemetry.Finish(rec, policy.ActionSimulated, false,
			fmt.Sprintf("would annotate %d NodePool(s) with xamops.io/spot-ratio-target=%d", len(nodePools.Items), spotPct))
	}

	annotation := fmt.Sprintf(`{"metadata":{"annotations":{"xamops.io/spot-ratio-target":"%d","xamops.io/managed-by":"xamops-automation"}}}`, spotPct)
	want := strconv.Itoa(spotPct)
	patched, failed := 0, 0
	for _, np := range nodePools.Items {
		obj, err := n.dc.Resource(karpenterNodePoolGVR).Patch(
			ctx, np.GetName(), types.MergePatchType, []byte(annotation), metav1.PatchOptions{},
		)
		if err != nil || obj.GetAnnotations()["xamops.io/spot-ratio-target"] != want {
			failed++
			log.Printf("WARN: NodeOptimizer: patch Karpenter NodePool %s: %v", np.GetName(), err)
			continue
		}
		patched++
	}
	if failed > 0 {
		return telemetry.Finish(rec, policy.ActionFailed, false, fmt.Sprintf("%d of %d NodePool patch(es) failed", failed, len(nodePools.Items)))
	}
	rec.Verification = fmt.Sprintf("%d NodePool(s) returned with the annotation", patched)
	return telemetry.Finish(rec, policy.ActionSucceeded, true, "")
}

// ── Spot ratio management ─────────────────────────────────────────────────────

// SelectOnDemandNodesForDrain returns on-demand nodes to drain to approach the target
// spot ratio, at most maxDrains. Order is deterministic: requests utilisation
// ascending, then node name (live CPU% is informational only).
func (n *NodeOptimizer) SelectOnDemandNodesForDrain(
	report *NodeOptimizerReport,
	targetSpotPct int,
	maxDrains int,
) []string {
	if report == nil || report.TotalNodes == 0 || targetSpotPct <= 0 {
		return nil
	}

	currentSpotPct := int(float64(report.SpotNodes) / float64(report.TotalNodes) * 100)
	if currentSpotPct >= targetSpotPct {
		return nil // already at or above target
	}

	// How many on-demand nodes need to become spot?
	targetSpotCount := int(float64(report.TotalNodes) * float64(targetSpotPct) / 100.0)
	needed := targetSpotCount - report.SpotNodes
	if needed <= 0 {
		return nil
	}
	if needed > maxDrains {
		needed = maxDrains
	}

	candidates := make([]SpotCandidateInfo, len(report.SpotCandidates))
	copy(candidates, report.SpotCandidates)
	sortByRequests(candidates)

	result := make([]string, 0, needed)
	for _, c := range candidates {
		if len(result) >= needed {
			break
		}
		result = append(result, c.NodeName)
	}
	return result
}

// ── Node utilization helpers ─────────────────────────────────────────────────

func (n *NodeOptimizer) gatherNodeUtilization(ctx context.Context, nodes []corev1.Node) (cpuPct, memPct map[string]float64) {
	cpuPct = map[string]float64{}
	memPct = map[string]float64{}

	cpuCapMillis := map[string]int64{}
	memCapBytes := map[string]int64{}
	for _, node := range nodes {
		cpuCapMillis[node.Name] = node.Status.Capacity.Cpu().MilliValue()
		memCapBytes[node.Name] = node.Status.Capacity.Memory().Value()
	}

	// Try metrics-server first
	if n.metricsClient != nil {
		nmList, err := n.metricsClient.MetricsV1beta1().NodeMetricses().List(ctx, metav1.ListOptions{})
		if err == nil {
			for _, nm := range nmList.Items {
				if cpuCapMillis[nm.Name] > 0 {
					cpuPct[nm.Name] = float64(nm.Usage.Cpu().MilliValue()) / float64(cpuCapMillis[nm.Name]) * 100
				}
				if memCapBytes[nm.Name] > 0 {
					memPct[nm.Name] = float64(nm.Usage.Memory().Value()) / float64(memCapBytes[nm.Name]) * 100
				}
			}
			return cpuPct, memPct
		}
		log.Printf("DEBUG: NodeOptimizer metrics-server unavailable: %v", err)
	}

	// Fallback: Prometheus per-node queries
	if n.prometheusURL != "" {
		cpuByNode := n.queryNodePrometheus(ctx,
			`100 * (1 - avg by (node) (rate(node_cpu_seconds_total{mode="idle"}[5m])))`)
		memByNode := n.queryNodePrometheus(ctx,
			`100 * (1 - avg by (node) (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes))`)
		for k, v := range cpuByNode {
			cpuPct[k] = v
		}
		for k, v := range memByNode {
			memPct[k] = v
		}
	}

	return cpuPct, memPct
}

func (n *NodeOptimizer) queryNodePrometheus(ctx context.Context, query string) map[string]float64 {
	result := map[string]float64{}
	rows, err := noPromQuery(ctx, n.httpClient, n.prometheusURL, query, "node")
	if err != nil {
		log.Printf("DEBUG: NodeOptimizer prometheus query: %v", err)
		return result
	}
	for node, val := range rows {
		v, _ := strconv.ParseFloat(val, 64)
		result[node] = v
	}
	return result
}

// detectSpotInterruptions checks for nodes labeled by aws-node-termination-handler.
func (n *NodeOptimizer) detectSpotInterruptions(ctx context.Context) []string {
	nodes, err := n.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{
		LabelSelector: "aws-node-termination-handler/action-reason=SpotInterruption",
	})
	if err != nil || nodes == nil {
		return nil
	}
	names := make([]string, 0, len(nodes.Items))
	for _, node := range nodes.Items {
		names = append(names, node.Name)
	}
	return names
}

// ── Helpers shared within this package ───────────────────────────────────────

func allSystemPods(pods []corev1.Pod) bool {
	if len(pods) == 0 {
		return true
	}
	for _, p := range pods {
		if !isDaemonSetPod(p) && p.Namespace != "kube-system" {
			return false
		}
	}
	return true
}

func isDaemonSetPod(p corev1.Pod) bool {
	for _, ref := range p.OwnerReferences {
		if ref.Kind == "DaemonSet" {
			return true
		}
	}
	return false
}

// noPromQuery is a minimal Prometheus instant query returning map[labelValue]→stringValue.
// Shared between NodeOptimizer and any other controller that needs simple label→value lookups.
func noPromQuery(ctx context.Context, client *http.Client, baseURL, query, labelKey string) (map[string]string, error) {
	type promRow struct {
		Metric map[string]string `json:"metric"`
		Value  []interface{}     `json:"value"`
	}
	type promResp struct {
		Status string `json:"status"`
		Data   struct {
			Result []promRow `json:"result"`
		} `json:"data"`
	}

	endpoint := baseURL + "/api/v1/query?query=" + url.QueryEscape(query)
	body, err := hpaHTTPGet(ctx, client, endpoint)
	if err != nil {
		return nil, err
	}
	var resp promResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	if resp.Status != "success" {
		return nil, fmt.Errorf("prometheus status: %s", resp.Status)
	}

	result := map[string]string{}
	for _, r := range resp.Data.Result {
		key := r.Metric[labelKey]
		if key == "" {
			continue
		}
		val := ""
		if len(r.Value) >= 2 {
			if s, ok := r.Value[1].(string); ok {
				val = s
			}
		}
		result[key] = val
	}
	return result, nil
}

func nodeOptimizerRound(f float64) float64 {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return 0
	}
	return math.Round(f*100) / 100
}
