package controller

import (
	"context"
	"fmt"
	"log"
	"sort"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

// EvictorResult is returned after each evictor cycle.
type EvictorResult struct {
	CycleTime      time.Time
	CandidateNodes []NodeCandidate   // nodes that could be consolidated
	EvictedPods    []string          // pod names that were evicted
	DrainedNodes   []string          // nodes drained live AND verified by the executor
	NodesSaved     int               // verified live drains only (never counts simulations)
	SkipReasons    map[string]string // nodeNname → reason skipped
	DryRun         bool
	AnalyseOnly    bool // true when the cycle only produced candidates and took no action
}

// NodeCandidate is a node the evictor considers for scale-in.
type NodeCandidate struct {
	NodeName       string  `json:"nodeName"`
	AllocatableCPU int64   `json:"allocatableCpuMillis"`
	AllocatableMem int64   `json:"allocatableMemBytes"`
	UsedCPU        int64   `json:"usedCpuMillis"`
	UsedMem        int64   `json:"usedMemBytes"`
	UtilizationCPU float64 `json:"utilizationCpuPercent"`
	UtilizationMem float64 `json:"utilizationMemPercent"`
	PodCount       int     `json:"podCount"`
	CanEvict       bool    `json:"canEvict"`
	SkipReason     string  `json:"skipReason,omitempty"`
	DryRun         bool    `json:"dryRun"`
}

// Evictor implements threshold-based scale-in.
//
// A node becomes a candidate only if it passes every gate in evaluateNode:
//   - it is not already cordoned
//   - it is older than p.NodeGracePeriodMinutes
//   - it carries neither the control-plane nor the master role label
//   - the SUM OF ITS PODS' RESOURCE REQUESTS is at or below TargetUtilizationCpu and
//     TargetUtilizationMemory — this is a requests ratio, not live usage from metrics
//   - none of its pods hits an eviction blocker (DaemonSet, protected StatefulSet,
//     critical namespace, or ownerless pod outside aggressive mode)
//
// Run sorts candidates by CPU utilisation and hands AT MOST ONE node per cycle to
// the shared drain.Executor, within the global drain budget. The executor asks its
// Planner to prove the drain is safe before touching the node; in this build the
// planner is drain.UnavailablePlanner, so every live drain is refused and a dry run
// reports a refused simulation (fitChecked=false). The fit simulator (roadmap C1,
// FEAT-005) plugs in as the planner. AnalyseOnly (the default) only reports.
type Evictor struct {
	k8s    kubernetes.Interface
	dryRun bool
}

func NewEvictor(k8s kubernetes.Interface, dryRun bool) *Evictor {
	return &Evictor{k8s: k8s, dryRun: dryRun}
}

// Run executes one evictor cycle.
//
// It scores every node on summed pod REQUESTS against the policy's utilisation
// targets and returns the full candidate list. In Analyse mode it stops there. In
// Simulate/Live it passes the single most-empty candidate to exec.Drain; NodesSaved
// and DrainedNodes count only verified live drains.
func (e *Evictor) Run(
	ctx context.Context,
	p policy.EvictorPolicy,
	guards policy.SafetyGuards,
	d gate.Decision,
	budget *drain.Budget,
	exec *drain.Executor,
) (*EvictorResult, []policy.ActionRecord, error) {
	result := &EvictorResult{
		CycleTime:   time.Now(),
		SkipReasons: map[string]string{},
		DryRun:      d.Mode != gate.Live,
	}

	if d.Mode == gate.Off || !p.Enabled {
		log.Printf("Evictor: off (%s), skipping", d.Reason)
		return result, nil, nil
	}

	// 1. List all nodes
	nodes, err := e.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, nil, fmt.Errorf("list nodes: %w", err)
	}

	// 2. List all pods
	pods, err := e.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, nil, fmt.Errorf("list pods: %w", err)
	}

	// 3. Group pods by node
	podsByNode := map[string][]corev1.Pod{}
	for _, pod := range pods.Items {
		if pod.Spec.NodeName == "" || pod.Status.Phase == corev1.PodSucceeded || pod.Status.Phase == corev1.PodFailed {
			continue
		}
		podsByNode[pod.Spec.NodeName] = append(podsByNode[pod.Spec.NodeName], pod)
	}

	// 4. Identify candidate nodes (under-utilised)
	gracePeriod := time.Duration(p.NodeGracePeriodMinutes) * time.Minute
	candidates := make([]NodeCandidate, 0)

	for _, node := range nodes.Items {
		candidate := e.evaluateNode(node, podsByNode[node.Name], p, guards, gracePeriod)
		candidate.DryRun = result.DryRun
		candidates = append(candidates, candidate)
	}

	// Sort: most empty first, then name (deterministic)
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].UtilizationCPU != candidates[j].UtilizationCPU {
			return candidates[i].UtilizationCPU < candidates[j].UtilizationCPU
		}
		return candidates[i].NodeName < candidates[j].NodeName
	})
	result.CandidateNodes = candidates

	// 5. Analyse-only mode (the default): report candidates, take no action.
	if d.Mode == gate.Analyse || p.AnalyseOnly {
		result.AnalyseOnly = true
		log.Printf("Evictor: analyse-only mode — %d node(s) evaluated, %d consolidation candidate(s), no action taken",
			len(candidates), len(filterCanEvict(candidates)))
		return result, nil, nil
	}

	// 6. Hand the most-empty candidate to the executor (one per cycle)
	remainingCandidates := filterCanEvict(candidates)
	if len(remainingCandidates) == 0 {
		log.Printf("Evictor: no consolidation candidates this cycle")
		return result, nil, nil
	}

	target := remainingCandidates[0]
	log.Printf("Evictor: consolidation candidate %s (CPU=%.1f%%, Pods=%d) — %s",
		target.NodeName, target.UtilizationCPU, target.PodCount, d.Mode)

	opID := fmt.Sprintf("evictor-%s-%d", target.NodeName, result.CycleTime.Unix())
	out := exec.Drain(ctx, policy.ControllerEvictor, opID, []string{target.NodeName}, d.Mode, budget)
	if out.Verified() {
		result.DrainedNodes = append(result.DrainedNodes, target.NodeName)
		result.EvictedPods = append(result.EvictedPods, out.Evicted...)
		result.NodesSaved++
	} else if len(out.Actions) > 0 {
		result.SkipReasons[target.NodeName] = out.Status + ": " + out.Actions[0].Reason
	}
	return result, out.Actions, nil
}

// evaluateNode determines whether a node is a consolidation candidate.
func (e *Evictor) evaluateNode(
	node corev1.Node,
	pods []corev1.Pod,
	p policy.EvictorPolicy,
	guards policy.SafetyGuards,
	gracePeriod time.Duration,
) NodeCandidate {
	c := NodeCandidate{
		NodeName: node.Name,
		PodCount: len(pods),
	}

	// Skip cordoned nodes (already being drained)
	if node.Spec.Unschedulable {
		c.SkipReason = "already cordoned"
		return c
	}

	// Grace period: don't evict nodes younger than NodeGracePeriodMinutes
	age := time.Since(node.CreationTimestamp.Time)
	if age < gracePeriod {
		c.SkipReason = fmt.Sprintf("node too young (%.1f min < %d min grace period)", age.Minutes(), p.NodeGracePeriodMinutes)
		return c
	}

	// Skip control-plane nodes
	if _, ok := node.Labels["node-role.kubernetes.io/control-plane"]; ok {
		c.SkipReason = "control-plane node"
		return c
	}
	if _, ok := node.Labels["node-role.kubernetes.io/master"]; ok {
		c.SkipReason = "master node"
		return c
	}

	// Calculate requested resources from pods
	var requestedCPU, requestedMem int64
	for _, pod := range pods {
		for _, c := range pod.Spec.Containers {
			requestedCPU += c.Resources.Requests.Cpu().MilliValue()
			requestedMem += c.Resources.Requests.Memory().Value()
		}
	}

	c.AllocatableCPU = node.Status.Allocatable.Cpu().MilliValue()
	c.AllocatableMem = node.Status.Allocatable.Memory().Value()
	c.UsedCPU = requestedCPU
	c.UsedMem = requestedMem

	if c.AllocatableCPU > 0 {
		c.UtilizationCPU = float64(requestedCPU) / float64(c.AllocatableCPU) * 100
	}
	if c.AllocatableMem > 0 {
		c.UtilizationMem = float64(requestedMem) / float64(c.AllocatableMem) * 100
	}

	// Check if below target utilization (candidate for consolidation)
	if c.UtilizationCPU > float64(p.TargetUtilizationCpu) ||
		c.UtilizationMem > float64(p.TargetUtilizationMemory) {
		c.SkipReason = fmt.Sprintf("utilization above target (CPU=%.1f%% Mem=%.1f%%)",
			c.UtilizationCPU, c.UtilizationMem)
		return c
	}

	// Check if any pod on this node blocks eviction
	for _, pod := range pods {
		if reason := e.podBlocksEviction(pod, guards, p.AggressiveMode); reason != "" {
			c.SkipReason = fmt.Sprintf("pod %s/%s: %s", pod.Namespace, pod.Name, reason)
			return c
		}
	}

	c.CanEvict = true
	return c
}

// podBlocksEviction returns a reason string if this pod must not be evicted, empty if safe.
func (e *Evictor) podBlocksEviction(pod corev1.Pod, guards policy.SafetyGuards, aggressiveMode bool) string {
	// DaemonSet pods — never evict
	for _, ref := range pod.OwnerReferences {
		if ref.Kind == "DaemonSet" {
			return "DaemonSet pod"
		}
		if ref.Kind == "StatefulSet" && guards.ProtectStatefulSets {
			return "StatefulSet pod (protectStatefulSets=true)"
		}
	}

	// Critical namespace
	if guards.ProtectCriticalNamespaces {
		for _, ns := range guards.CriticalNamespaces {
			if pod.Namespace == ns {
				return fmt.Sprintf("critical namespace %s", ns)
			}
		}
	}

	// Pods with no owner (standalone pods) — skip unless aggressive
	if len(pod.OwnerReferences) == 0 && !aggressiveMode {
		return "standalone pod (no owner reference)"
	}

	return ""
}

func filterCanEvict(candidates []NodeCandidate) []NodeCandidate {
	out := make([]NodeCandidate, 0)
	for _, c := range candidates {
		if c.CanEvict {
			out = append(out, c)
		}
	}
	return out
}
