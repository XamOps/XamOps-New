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
	"strings"
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
	TotalNodes     int                 `json:"totalNodes"`
	OnDemandNodes  int                 `json:"onDemandNodes"`
	SpotNodes      int                 `json:"spotNodes"`
	IdleNodes      []IdleNodeInfo      `json:"idleNodes"`
	SpotCandidates []SpotCandidateInfo `json:"spotCandidates"`
	// InterruptedNodes is the spot-interruption drain input (legacy NTH label only).
	InterruptedNodes []string `json:"interruptedNodes"`
	// InterruptedNodesDetectionFailed: the legacy-label list call errored, so
	// InterruptedNodes is unknown (sent as []), not "none".
	InterruptedNodesDetectionFailed bool `json:"interruptedNodesDetectionFailed,omitempty"`
	// InterruptionNotices is observation only (never a drain input); always an
	// array, capped at maxInterruptionNotices.
	InterruptionNotices []InterruptionNotice `json:"interruptionNotices"`
	// InterruptionHandling is the spot-interruption gate decision (set by main.go).
	InterruptionHandling   *gate.Decision `json:"interruptionHandling,omitempty"`
	EstimatedSavingsPct    *float64       `json:"estimatedSavingsPct"` // always null: no pricing input
	EstimatedSavingsStatus string         `json:"estimatedSavingsStatus"`
	EstimatedSavingsReason string         `json:"estimatedSavingsReason"`
	DryRun                 bool           `json:"dryRun"`
	CollectedAt            string         `json:"collectedAt"`
}

// InterruptionNotice is one node carrying an aws-node-termination-handler mark,
// observed from the node list Analyse already fetched. Observation only.
type InterruptionNotice struct {
	NodeName      string `json:"nodeName"`
	NodeCreatedAt string `json:"nodeCreatedAt"`          // RFC3339 UTC, node.CreationTimestamp
	InstanceID    string `json:"instanceId,omitempty"`   // last path segment of spec.providerID when it starts with "aws:///"
	InstanceType  string `json:"instanceType,omitempty"` // node.kubernetes.io/instance-type
	Zone          string `json:"zone,omitempty"`         // topology.kubernetes.io/zone
	CapacityType  string `json:"capacityType"`           // "spot" | "on-demand" via isSpotNode
	Nodegroup     string `json:"nodegroup,omitempty"`    // eks.amazonaws.com/nodegroup, else karpenter.sh/nodepool
	Kind          string `json:"kind"`                   // "interruption" | "rebalance"
	Source        string `json:"source"`                 // "nth-taint" | "nth-label-legacy"
	Cordoned      bool   `json:"cordoned"`               // spec.unschedulable
	DrainEligible bool   `json:"drainEligible"`          // picked by today's drain selector (legacy label); a node XamOps already drained (F1) stays eligible but is not re-drained
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
	// AlreadyDrained: XamOps drained this node and it is still cordoned
	// (drain.AlreadyDrained) with only system/DaemonSet pods on it; it is listed
	// but never recommended for drain again.
	AlreadyDrained bool `json:"alreadyDrained,omitempty"`
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
		IdleNodes:              []IdleNodeInfo{},
		SpotCandidates:         []SpotCandidateInfo{},
		InterruptionNotices:    []InterruptionNotice{},
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

	// Nodes XamOps already drained, that are still cordoned and still empty of
	// workloads are never drain, cordon, spot-ratio or interruption candidates
	// again (F1).
	drainedNodes := map[string]bool{}
	var drainedNames []string

	for _, node := range nodes.Items {
		// The drain-state annotation outlives an operator `kubectl uncordon`, so a
		// node that got workloads back and was then cordoned again (NTH, operator)
		// still carries drain-state=drained. It only counts as already drained
		// while it still carries nothing but system/DaemonSet pods.
		drained := drain.AlreadyDrained(node) && allSystemPods(podsOnNode[node.Name])
		if drained {
			drainedNodes[node.Name] = true
			drainedNames = append(drainedNames, node.Name)
		}
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
			recommend := isIdle && systemOnly && !drained
			report.IdleNodes = append(report.IdleNodes, IdleNodeInfo{
				NodeName:       node.Name,
				InstanceType:   instanceType,
				CPUUsagePct:    nodeOptimizerRound(cpuU),
				MemUsagePct:    nodeOptimizerRound(memU),
				PodCount:       podCount,
				SystemPodsOnly: systemOnly,
				RecommendDrain: recommend,
				CapacityType:   capacityType,
				AlreadyDrained: drained,
			})
		}

		// On-demand nodes that could be spot candidates (CPU < 70%, stable workloads)
		if capacityType == "on-demand" && cpuU < 70 && cpuU > 0 && !drained {
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
	if report.InterruptedNodes == nil { // list call failed: unknown, not "none"
		report.InterruptedNodes = []string{}
		report.InterruptedNodesDetectionFailed = true
	}

	// Observation only: NTH marks on the node list fetched above (no extra API call).
	// drainEligible means the legacy-label selector picked the node, so it is built
	// before the already-drained filter below: a node XamOps already drained is
	// covered by XamOps's handling, not outside it.
	drainSet := make(map[string]bool, len(report.InterruptedNodes))
	for _, name := range report.InterruptedNodes {
		drainSet[name] = true
	}
	report.InterruptionNotices = interruptionNotices(nodes.Items, drainSet)

	if len(drainedNodes) > 0 {
		kept := report.InterruptedNodes[:0]
		for _, name := range report.InterruptedNodes {
			if !drainedNodes[name] {
				kept = append(kept, name)
			}
		}
		report.InterruptedNodes = kept
		log.Printf("NodeOptimizer: %d node(s) already drained by XamOps (cordoned, drain-state=drained) — not re-drained: %v", len(drainedNames), drainedNames)
	}

	return report, nil
}

// aws-node-termination-handler marks. The taint keys are NTH's own
// (pkg/node/node.go: SpotInterruptionTaint, RebalanceRecommendationTaint); the label
// is the legacy selector detectSpotInterruptions (the drain input) still uses.
const (
	nthSpotITNTaint       = "aws-node-termination-handler/spot-itn"
	nthRebalanceTaint     = "aws-node-termination-handler/rebalance-recommendation"
	nthActionReasonLabel  = "aws-node-termination-handler/action-reason"
	nthSpotInterruptValue = "SpotInterruption"

	maxInterruptionNotices = 50
)

// interruptionNotices returns one notice per node carrying an NTH mark (first match
// wins: spot-itn taint, legacy label, rebalance taint), sorted by node name, capped
// at maxInterruptionNotices and never nil. drainSet is today's drain input
// (report.InterruptedNodes).
func interruptionNotices(nodes []corev1.Node, drainSet map[string]bool) []InterruptionNotice {
	out := []InterruptionNotice{}
	for _, node := range nodes {
		kind, source := nthMark(node)
		if kind == "" {
			continue
		}
		capacityType := "on-demand"
		if isSpotNode(node) {
			capacityType = "spot"
		}
		instanceID := ""
		if pid := node.Spec.ProviderID; strings.HasPrefix(pid, "aws:///") {
			instanceID = pid[strings.LastIndex(pid, "/")+1:]
		}
		nodegroup := node.Labels["eks.amazonaws.com/nodegroup"]
		if nodegroup == "" {
			nodegroup = node.Labels["karpenter.sh/nodepool"]
		}
		out = append(out, InterruptionNotice{
			NodeName:      node.Name,
			NodeCreatedAt: node.CreationTimestamp.UTC().Format(time.RFC3339),
			InstanceID:    instanceID,
			InstanceType:  node.Labels["node.kubernetes.io/instance-type"],
			Zone:          node.Labels["topology.kubernetes.io/zone"],
			CapacityType:  capacityType,
			Nodegroup:     nodegroup,
			Kind:          kind,
			Source:        source,
			Cordoned:      node.Spec.Unschedulable,
			DrainEligible: drainSet[node.Name],
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].NodeName < out[j].NodeName })
	if len(out) > maxInterruptionNotices {
		out = out[:maxInterruptionNotices]
	}
	return out
}

// nthMark returns the notice kind and source for a node, or "" when it has no mark.
func nthMark(node corev1.Node) (kind, source string) {
	hasTaint := func(key string) bool {
		for _, t := range node.Spec.Taints {
			if t.Key == key {
				return true
			}
		}
		return false
	}
	switch {
	case hasTaint(nthSpotITNTaint):
		return "interruption", "nth-taint"
	case node.Labels[nthActionReasonLabel] == nthSpotInterruptValue:
		return "interruption", "nth-label-legacy"
	case hasTaint(nthRebalanceTaint):
		return "rebalance", "nth-taint"
	}
	return "", ""
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

// DrainNodes is the node optimizer's one drain path (idle drain, spot-ratio drain,
// spot interruption): candidates in the caller's deterministic order go through
// drain.Executor.DrainCandidates — the fit simulator selects the largest set that is
// drainable as a whole within the shared budget (and maxNodes when > 0), the executor
// drains it as one set, every other candidate is recorded refused (with the plan's
// reasons) or skipped. Every record carries the trigger.
func (n *NodeOptimizer) DrainNodes(ctx context.Context, trigger string, nodes []string, maxNodes int, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.CandidateResult {
	res := exec.DrainCandidates(ctx, policy.ControllerNodeOptimizer, nodeOpID(trigger, "set"), nodes, maxNodes, mode, budget)
	tag := func(o *drain.Outcome) {
		for i := range o.Actions {
			telemetry.SetDetail(&o.Actions[i], "trigger", trigger)
		}
	}
	if res.Set != nil {
		tag(res.Set)
	}
	for i := range res.Others {
		tag(&res.Others[i])
	}
	return res
}

// HandleSpotInterruptions drains nodes that carry an AWS spot interruption notice
// through the same simulator, executor, budget and guards as every other drain: no
// PDB override and no "evicting anyway". A node whose plan is not drainable is
// reported (refused), not drained — aws-node-termination-handler, when installed,
// drains on its own.
func (n *NodeOptimizer) HandleSpotInterruptions(ctx context.Context, nodes []string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.CandidateResult {
	return n.DrainNodes(ctx, "spot-interruption", nodes, 0, mode, budget, exec)
}

// HandleSpotInterruption is HandleSpotInterruptions for one node.
func (n *NodeOptimizer) HandleSpotInterruption(ctx context.Context, nodeName string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.Outcome {
	return firstOutcome(n.HandleSpotInterruptions(ctx, []string{nodeName}, mode, budget, exec))
}

func firstOutcome(r drain.CandidateResult) drain.Outcome {
	if o := r.Outcomes(); len(o) > 0 {
		return o[0]
	}
	return drain.Outcome{}
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

// DrainForSpotConversion drains one on-demand node (idle drain or spot-ratio drain).
// Thin wrapper over DrainNodes: simulator selection within the budget -> cordon ->
// Eviction API with a re-plan before every eviction -> new-UID replacement wait ->
// verification; any failure aborts and uncordons.
func (n *NodeOptimizer) DrainForSpotConversion(ctx context.Context, trigger, nodeName string, mode gate.Mode, budget *drain.Budget, exec *drain.Executor) drain.Outcome {
	return firstOutcome(n.DrainNodes(ctx, trigger, []string{nodeName}, 0, mode, budget, exec))
}

// SpotRatioDrainCandidates returns every on-demand spot candidate in drain order
// (requests utilisation ascending, then name) and how many on-demand nodes must go
// to reach targetSpotPct (0 when already there).
func (n *NodeOptimizer) SpotRatioDrainCandidates(report *NodeOptimizerReport, targetSpotPct int) ([]string, int) {
	if report == nil || report.TotalNodes == 0 || targetSpotPct <= 0 {
		return nil, 0
	}
	needed := int(float64(report.TotalNodes)*float64(targetSpotPct)/100.0) - report.SpotNodes
	if int(float64(report.SpotNodes)/float64(report.TotalNodes)*100) >= targetSpotPct || needed <= 0 {
		return nil, 0
	}
	cands := make([]SpotCandidateInfo, len(report.SpotCandidates))
	copy(cands, report.SpotCandidates)
	sortByRequests(cands)
	names := make([]string, 0, len(cands))
	for _, c := range cands {
		names = append(names, c.NodeName)
	}
	return names, needed
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
