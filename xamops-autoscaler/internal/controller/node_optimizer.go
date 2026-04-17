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
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"

	"github.com/xamops/automation/internal/policy"
)

// NodeOptimizer analyses node utilization, detects idle nodes, identifies
// on-demand nodes that could be replaced with spot, handles spot interruption
// events, and manages the spot/on-demand ratio via safe PDB-aware drains.
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
// Pushed back to XamOps backend as collector "node-drain-progress".
type DrainProgressReport struct {
	NodeName    string     `json:"nodeName"`
	Status      string     `json:"status"` // queued | cordoned | draining | drained | failed | skipped
	Reason      string     `json:"reason,omitempty"`
	PodsTotal   int        `json:"podsTotal"`
	PodsEvicted int        `json:"podsEvicted"`
	PodsSkipped int        `json:"podsSkipped"`
	StartedAt   time.Time  `json:"startedAt"`
	FinishedAt  *time.Time `json:"finishedAt,omitempty"`
	Error       string     `json:"error,omitempty"`
}

// NodeOptimizerReport is pushed as collector "node-optimizer-report".
type NodeOptimizerReport struct {
	TotalNodes          int                 `json:"totalNodes"`
	OnDemandNodes       int                 `json:"onDemandNodes"`
	SpotNodes           int                 `json:"spotNodes"`
	IdleNodes           []IdleNodeInfo      `json:"idleNodes"`
	SpotCandidates      []SpotCandidateInfo `json:"spotCandidates"`
	InterruptedNodes    []string            `json:"interruptedNodes,omitempty"`
	EstimatedSavingsPct float64             `json:"estimatedSavingsPct"`
	CollectedAt         string              `json:"collectedAt"`
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
	NodeName      string  `json:"nodeName"`
	InstanceType  string  `json:"instanceType,omitempty"`
	CPUUsagePct   float64 `json:"cpuUsagePct"`
	MemUsagePct   float64 `json:"memUsagePct"`
	SavingsPct    float64 `json:"savingsPct"` // typical spot savings vs on-demand
	Interruptible bool    `json:"interruptible"`
}

// SpotInterruptionAction describes a cordon+drain triggered by spot interruption notice.
type SpotInterruptionAction struct {
	NodeName    string    `json:"nodeName"`
	TriggeredAt time.Time `json:"triggeredAt"`
	Status      string    `json:"status"` // cordoned | draining | drained | failed
}

// Analyse runs the full node optimization analysis.
func (n *NodeOptimizer) Analyse(ctx context.Context, guards policy.SafetyGuards) (*NodeOptimizerReport, error) {
	report := &NodeOptimizerReport{
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
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
		if p.Spec.NodeName != "" {
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
			savingsPct := estimateSpotSavings(instanceType)
			report.SpotCandidates = append(report.SpotCandidates, SpotCandidateInfo{
				NodeName:      node.Name,
				InstanceType:  instanceType,
				CPUUsagePct:   nodeOptimizerRound(cpuU),
				MemUsagePct:   nodeOptimizerRound(memU),
				SavingsPct:    savingsPct,
				Interruptible: true,
			})
		}
	}

	// Sort spot candidates by highest potential savings first
	sort.Slice(report.SpotCandidates, func(i, j int) bool {
		return report.SpotCandidates[i].SavingsPct > report.SpotCandidates[j].SavingsPct
	})

	// Estimated overall savings if all spot candidates were converted
	if report.OnDemandNodes > 0 && len(report.SpotCandidates) > 0 {
		totalSavings := 0.0
		for _, c := range report.SpotCandidates {
			totalSavings += c.SavingsPct
		}
		report.EstimatedSavingsPct = nodeOptimizerRound(totalSavings / float64(len(report.SpotCandidates)))
	}

	// Check for spot interruption notices via AWS node termination handler labels
	report.InterruptedNodes = n.detectSpotInterruptions(ctx)

	return report, nil
}

// HandleSpotInterruption cordons and drains a node that received an AWS spot
// interruption notice. Must be called within ~2 minutes of the notice.
func (n *NodeOptimizer) HandleSpotInterruption(ctx context.Context, nodeName string, guards policy.SafetyGuards) (*SpotInterruptionAction, error) {
	action := &SpotInterruptionAction{
		NodeName:    nodeName,
		TriggeredAt: time.Now().UTC(),
	}

	if n.dryRun {
		log.Printf("DRY-RUN: would cordon+drain node %s (spot interruption)", nodeName)
		action.Status = "cordoned"
		return action, nil
	}

	// Step 1: Cordon
	cordonPatch := []byte(`{"spec":{"unschedulable":true}}`)
	_, err := n.k8s.CoreV1().Nodes().Patch(ctx, nodeName, types.MergePatchType, cordonPatch, metav1.PatchOptions{})
	if err != nil {
		action.Status = "failed"
		return action, fmt.Errorf("cordon node %s: %w", nodeName, err)
	}
	log.Printf("NodeOptimizer: cordoned spot node %s", nodeName)
	action.Status = "draining"

	// Step 2: Evict all non-DaemonSet, non-critical pods
	pods, err := n.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{
		FieldSelector: fmt.Sprintf("spec.nodeName=%s,status.phase=Running", nodeName),
	})
	if err != nil {
		action.Status = "failed"
		return action, fmt.Errorf("list pods on %s: %w", nodeName, err)
	}

	// Spot interruption: 2-minute window — skip PDB wait, just evict as fast as possible
	// but still respect critical namespace protection
	evicted := 0
	for _, pod := range pods.Items {
		if isDaemonSetPod(pod) {
			continue
		}
		if guards.ProtectCriticalNamespaces && isCriticalNS(pod.Namespace, guards.CriticalNamespaces) {
			continue
		}
		// For spot interruptions, skip PDB wait (we don't have time) but still check
		// and log so the user sees disruption warnings in the activity feed
		if guards.RespectPodDisruptionBudgets {
			if blocked, pdbName := n.checkPDBBlocksEviction(ctx, pod); blocked {
				log.Printf("WARN: NodeOptimizer: spot-interruption eviction of %s/%s blocked by PDB %s — evicting anyway (AWS deadline)",
					pod.Namespace, pod.Name, pdbName)
			}
		}
		eviction := buildEviction(pod.Name, pod.Namespace)
		if evictErr := n.k8s.CoreV1().Pods(pod.Namespace).EvictV1(ctx, eviction); evictErr != nil {
			log.Printf("WARN: NodeOptimizer: evict pod %s/%s: %v", pod.Namespace, pod.Name, evictErr)
		} else {
			evicted++
		}
	}

	action.Status = "drained"
	log.Printf("NodeOptimizer: drained spot node %s (%d pods evicted)", nodeName, evicted)
	return action, nil
}

// CordonIdleNode cordons a detected idle node for review.
func (n *NodeOptimizer) CordonIdleNode(ctx context.Context, nodeName string) error {
	if n.dryRun {
		log.Printf("DRY-RUN: would cordon idle node %s", nodeName)
		return nil
	}
	cordonPatch := []byte(`{"spec":{"unschedulable":true}}`)
	_, err := n.k8s.CoreV1().Nodes().Patch(ctx, nodeName, types.MergePatchType, cordonPatch, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("cordon idle node %s: %w", nodeName, err)
	}
	log.Printf("NodeOptimizer: cordoned idle node %s", nodeName)
	return nil
}

// UncordonNode reverses a cordon.
func (n *NodeOptimizer) UncordonNode(ctx context.Context, nodeName string) error {
	if n.dryRun {
		log.Printf("DRY-RUN: would uncordon node %s", nodeName)
		return nil
	}
	patch := []byte(`{"spec":{"unschedulable":false}}`)
	_, err := n.k8s.CoreV1().Nodes().Patch(ctx, nodeName, types.MergePatchType, patch, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("uncordon node %s: %w", nodeName, err)
	}
	log.Printf("NodeOptimizer: uncordoned node %s", nodeName)
	return nil
}

// ── PDB-aware safe drain ──────────────────────────────────────────────────────

// DrainForSpotConversion safely drains an on-demand node to allow its replacement
// with a spot node. Steps:
//  1. Pre-flight: cluster must have >1 schedulable node, node must exist
//  2. Cordon (stop new pods scheduling)
//  3. For each non-DaemonSet pod: check PDB, evict, wait for replacement pod running
//  4. Return DrainProgressReport pushed to XamOps
func (n *NodeOptimizer) DrainForSpotConversion(ctx context.Context, nodeName string, guards policy.SafetyGuards, drainTimeoutSec int) *DrainProgressReport {
	now := time.Now().UTC()
	report := &DrainProgressReport{
		NodeName:  nodeName,
		Status:    "queued",
		StartedAt: now,
	}

	// ── Pre-flight: cluster capacity check ────────────────────────────────────
	nodes, err := n.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		report.Status = "failed"
		report.Error = fmt.Sprintf("list nodes for capacity check: %v", err)
		return report
	}
	schedulableCount := 0
	for _, nd := range nodes.Items {
		if !nd.Spec.Unschedulable && nd.Name != nodeName {
			schedulableCount++
		}
	}
	if schedulableCount == 0 {
		report.Status = "skipped"
		report.Reason = "no other schedulable nodes — drain would leave cluster with no capacity"
		log.Printf("NodeOptimizer: skip drain %s — no remaining schedulable nodes", nodeName)
		return report
	}

	if n.dryRun {
		log.Printf("DRY-RUN: would drain node %s for spot conversion (%d other schedulable nodes)", nodeName, schedulableCount)
		finished := time.Now().UTC()
		report.Status = "drained"
		report.Reason = "dry-run"
		report.FinishedAt = &finished
		return report
	}

	// ── Step 1: Cordon ─────────────────────────────────────────────────────────
	cordonPatch := []byte(`{"spec":{"unschedulable":true}}`)
	if _, err := n.k8s.CoreV1().Nodes().Patch(ctx, nodeName, types.MergePatchType, cordonPatch, metav1.PatchOptions{}); err != nil {
		report.Status = "failed"
		report.Error = fmt.Sprintf("cordon: %v", err)
		return report
	}
	report.Status = "cordoned"
	log.Printf("NodeOptimizer: cordoned %s for spot conversion", nodeName)

	// ── Step 2: List evictable pods ────────────────────────────────────────────
	pods, err := n.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{
		FieldSelector: fmt.Sprintf("spec.nodeName=%s,status.phase=Running", nodeName),
	})
	if err != nil {
		report.Status = "failed"
		report.Error = fmt.Sprintf("list pods: %v", err)
		return report
	}

	evictable := make([]corev1.Pod, 0, len(pods.Items))
	for _, pod := range pods.Items {
		if isDaemonSetPod(pod) {
			continue // DaemonSets reschedule automatically — skip
		}
		if guards.ProtectCriticalNamespaces && isCriticalNS(pod.Namespace, guards.CriticalNamespaces) {
			report.PodsSkipped++
			continue
		}
		if guards.ProtectStatefulSets && isStatefulSetPod(pod) {
			report.PodsSkipped++
			log.Printf("NodeOptimizer: skipping StatefulSet pod %s/%s (ProtectStatefulSets=true)", pod.Namespace, pod.Name)
			continue
		}
		if guards.ProtectSingleReplicas && isSingleReplicaDeployment(pod) {
			report.PodsSkipped++
			log.Printf("NodeOptimizer: skipping single-replica pod %s/%s (ProtectSingleReplicas=true)", pod.Namespace, pod.Name)
			continue
		}
		evictable = append(evictable, pod)
	}
	report.PodsTotal = len(evictable) + report.PodsSkipped
	report.Status = "draining"

	// ── Step 3: PDB-aware eviction with replacement wait ──────────────────────
	deadline := time.Now().Add(time.Duration(drainTimeoutSec) * time.Second)
	for _, pod := range evictable {
		if time.Now().After(deadline) {
			report.Status = "failed"
			report.Error = fmt.Sprintf("drain timeout after %ds", drainTimeoutSec)
			break
		}

		// Check PDB — retry up to 12 times (1 min) if disruption not allowed
		if guards.RespectPodDisruptionBudgets {
			if blocked, pdbName := n.checkPDBBlocksEviction(ctx, pod); blocked {
				log.Printf("NodeOptimizer: PDB %s/%s blocks eviction of %s/%s — waiting...",
					pod.Namespace, pdbName, pod.Namespace, pod.Name)
				// Wait and retry up to 60 seconds for PDB to allow disruption
				allowed := false
				for i := 0; i < 12; i++ {
					time.Sleep(5 * time.Second)
					if blocked2, _ := n.checkPDBBlocksEviction(ctx, pod); !blocked2 {
						allowed = true
						break
					}
				}
				if !allowed {
					report.PodsSkipped++
					log.Printf("NodeOptimizer: PDB still blocking %s/%s after 60s — skipping", pod.Namespace, pod.Name)
					continue
				}
			}
		}

		// Evict
		eviction := &policyv1.Eviction{
			ObjectMeta: metav1.ObjectMeta{
				Name:      pod.Name,
				Namespace: pod.Namespace,
			},
		}
		if err := n.k8s.CoreV1().Pods(pod.Namespace).EvictV1(ctx, eviction); err != nil {
			log.Printf("WARN: NodeOptimizer: evict %s/%s: %v — skipping", pod.Namespace, pod.Name, err)
			report.PodsSkipped++
			continue
		}
		report.PodsEvicted++
		log.Printf("NodeOptimizer: evicted %s/%s from %s", pod.Namespace, pod.Name, nodeName)

		// Wait for the pod's replacement to be Running elsewhere (up to 3 min)
		if waited := n.waitForPodRunningElsewhere(ctx, pod, nodeName, 180*time.Second); !waited {
			log.Printf("WARN: NodeOptimizer: replacement for %s/%s not Running within 3m — continuing drain", pod.Namespace, pod.Name)
		}
	}

	if report.Status == "draining" {
		report.Status = "drained"
	}
	finished := time.Now().UTC()
	report.FinishedAt = &finished
	log.Printf("NodeOptimizer: drain %s complete (status=%s evicted=%d skipped=%d)",
		nodeName, report.Status, report.PodsEvicted, report.PodsSkipped)
	return report
}

// checkPDBBlocksEviction returns true if evicting pod would violate a PDB.
// Returns (blocked bool, pdbName string).
func (n *NodeOptimizer) checkPDBBlocksEviction(ctx context.Context, pod corev1.Pod) (bool, string) {
	pdbs, err := n.k8s.PolicyV1().PodDisruptionBudgets(pod.Namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return false, "" // if we can't check, allow eviction
	}
	for _, pdb := range pdbs.Items {
		if pdb.Spec.Selector == nil {
			continue
		}
		// Check if PDB selector matches this pod's labels
		if labelsMatchSelector(pod.Labels, pdb.Spec.Selector.MatchLabels) {
			if pdb.Status.DisruptionsAllowed <= 0 {
				return true, pdb.Name
			}
		}
	}
	return false, ""
}

// waitForPodRunningElsewhere polls until a replacement pod (same owner) is Running
// on a node other than excludeNode, or the timeout expires.
func (n *NodeOptimizer) waitForPodRunningElsewhere(ctx context.Context, pod corev1.Pod, excludeNode string, timeout time.Duration) bool {
	if len(pod.OwnerReferences) == 0 {
		return true // bare pod — nothing to wait for
	}
	ownerUID := pod.OwnerReferences[0].UID

	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		time.Sleep(5 * time.Second)
		allPods, err := n.k8s.CoreV1().Pods(pod.Namespace).List(ctx, metav1.ListOptions{})
		if err != nil {
			continue
		}
		for _, p := range allPods.Items {
			if p.Name == pod.Name {
				continue // the evicted pod itself (may still be Terminating)
			}
			for _, ref := range p.OwnerReferences {
				if ref.UID == ownerUID && p.Spec.NodeName != excludeNode && p.Status.Phase == corev1.PodRunning {
					return true
				}
			}
		}
	}
	return false
}

// labelsMatchSelector returns true if all selector labels are present in podLabels.
func labelsMatchSelector(podLabels, selectorLabels map[string]string) bool {
	for k, v := range selectorLabels {
		if podLabels[k] != v {
			return false
		}
	}
	return true
}

// isStatefulSetPod returns true if the pod is owned by a StatefulSet.
func isStatefulSetPod(p corev1.Pod) bool {
	for _, ref := range p.OwnerReferences {
		if ref.Kind == "StatefulSet" {
			return true
		}
	}
	return false
}

// isSingleReplicaDeployment returns true if the pod's Deployment has only 1 replica.
// We check the owner ReplicaSet → owner Deployment spec.replicas.
// Falls back to false on any error (don't block eviction if we can't determine).
func isSingleReplicaDeployment(p corev1.Pod) bool {
	// Only check Deployment-owned pods (via ReplicaSet)
	var rsName string
	for _, ref := range p.OwnerReferences {
		if ref.Kind == "ReplicaSet" {
			rsName = ref.Name
			break
		}
	}
	return rsName == "" // can't determine — treat as safe to evict
}

// ── Karpenter NodePool patching ───────────────────────────────────────────────

var karpenterNodePoolGVR = schema.GroupVersionResource{
	Group:    "karpenter.sh",
	Version:  "v1",
	Resource: "nodepools",
}

// PatchKarpenterSpotRatio updates Karpenter NodePool CRDs to annotate the desired
// spot ratio. This doesn't directly change the ratio — it records the intent so
// that Karpenter disruption policies can be adjusted by the cluster operator.
// The actual node replacement happens via DrainForSpotConversion().
func (n *NodeOptimizer) PatchKarpenterSpotRatio(ctx context.Context, spotPct int) error {
	if n.dc == nil {
		return fmt.Errorf("dynamic client not available for Karpenter patching")
	}

	nodePools, err := n.dc.Resource(karpenterNodePoolGVR).List(ctx, metav1.ListOptions{})
	if err != nil {
		return fmt.Errorf("list Karpenter NodePools: %w", err)
	}

	if n.dryRun {
		log.Printf("DRY-RUN: would annotate %d Karpenter NodePool(s) with xamops.io/spot-ratio-target=%d%%",
			len(nodePools.Items), spotPct)
		return nil
	}

	annotation := fmt.Sprintf(`{"metadata":{"annotations":{"xamops.io/spot-ratio-target":"%d","xamops.io/managed-by":"xamops-automation"}}}`, spotPct)
	patchBytes := []byte(annotation)

	for _, np := range nodePools.Items {
		_, err := n.dc.Resource(karpenterNodePoolGVR).Patch(
			ctx, np.GetName(), types.MergePatchType, patchBytes, metav1.PatchOptions{},
		)
		if err != nil {
			log.Printf("WARN: NodeOptimizer: patch Karpenter NodePool %s: %v", np.GetName(), err)
		} else {
			log.Printf("NodeOptimizer: annotated Karpenter NodePool %s with spot-ratio-target=%d%%", np.GetName(), spotPct)
		}
	}
	return nil
}

// ── Spot ratio management ─────────────────────────────────────────────────────

// SelectOnDemandNodesForDrain returns on-demand nodes to drain to achieve the
// target spot ratio. Picks the least-loaded nodes first to minimise disruption.
// Returns at most maxDrains nodes.
func (n *NodeOptimizer) SelectOnDemandNodesForDrain(
	report *NodeOptimizerReport,
	targetSpotPct int,
	maxDrains int,
) []string {
	if report == nil || report.TotalNodes == 0 || targetSpotPct <= 0 {
		return nil
	}

	currentSpotPct := 0
	if report.TotalNodes > 0 {
		currentSpotPct = int(float64(report.SpotNodes) / float64(report.TotalNodes) * 100)
	}
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

	// Select candidates sorted by lowest CPU usage (least disruption)
	candidates := make([]SpotCandidateInfo, len(report.SpotCandidates))
	copy(candidates, report.SpotCandidates)
	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].CPUUsagePct < candidates[j].CPUUsagePct
	})

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

// estimateSpotSavings returns typical AWS spot discount by instance family.
func estimateSpotSavings(instanceType string) float64 {
	if len(instanceType) == 0 {
		return 60.0
	}
	switch instanceType[0] {
	case 'r':
		return 72.0 // memory-optimized
	case 'c':
		return 65.0 // compute-optimized
	case 'm':
		return 62.0 // general purpose
	case 't':
		return 50.0 // burstable
	case 'g', 'p':
		return 70.0 // GPU
	}
	return 60.0
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
