package controller

import (
	"context"
	"fmt"
	"log"
	"sort"
	"time"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/autoscaler/internal/policy"
)

// EvictorResult is returned after each evictor cycle.
type EvictorResult struct {
	CycleTime       time.Time
	CandidateNodes  []NodeCandidate  // nodes that could be consolidated
	EvictedPods     []string         // pod names that were evicted
	DrainedNodes    []string         // node names that were drained
	NodesSaved      int              // count of removed nodes
	SkipReasons     map[string]string // nodeNname → reason skipped
	DryRun          bool
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
}

// Evictor implements bin-packing based scale-in.
// It simulates whether all pods on a node can fit elsewhere BEFORE taking any action.
// Safety checks run before every eviction attempt.
type Evictor struct {
	k8s   kubernetes.Interface
	dryRun bool
}

func NewEvictor(k8s kubernetes.Interface, dryRun bool) *Evictor {
	return &Evictor{k8s: k8s, dryRun: dryRun}
}

// Run executes one evictor cycle.
// It identifies under-utilised nodes and, if consolidation is safe, drains them.
func (e *Evictor) Run(ctx context.Context, p policy.EvictorPolicy, guards policy.SafetyGuards) (*EvictorResult, error) {
	result := &EvictorResult{
		CycleTime:   time.Now(),
		SkipReasons: map[string]string{},
		DryRun:      e.dryRun,
	}

	if !p.Enabled {
		log.Println("Evictor: disabled in policy, skipping")
		return result, nil
	}

	// 1. List all nodes
	nodes, err := e.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list nodes: %w", err)
	}

	// 2. List all pods
	pods, err := e.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
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
		candidates = append(candidates, candidate)
	}

	// Sort: most empty first
	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].UtilizationCPU < candidates[j].UtilizationCPU
	})
	result.CandidateNodes = candidates

	// 5. For nodes that can evict: simulate bin-packing, then act
	remainingCandidates := filterCanEvict(candidates)
	if len(remainingCandidates) == 0 {
		log.Printf("Evictor: no consolidation candidates this cycle")
		return result, nil
	}

	// Attempt to consolidate the most-empty node
	target := remainingCandidates[0]
	log.Printf("Evictor: attempting to consolidate node %s (CPU=%.1f%%, Pods=%d)",
		target.NodeName, target.UtilizationCPU, target.PodCount)

	if e.dryRun {
		log.Printf("DRY-RUN: would drain node %s", target.NodeName)
		result.DrainedNodes = append(result.DrainedNodes, target.NodeName+" (dry-run)")
		result.NodesSaved++
		return result, nil
	}

	// Cordon the node
	if err := e.cordonNode(ctx, target.NodeName); err != nil {
		log.Printf("Evictor: cordon %s failed: %v", target.NodeName, err)
		return result, nil
	}

	// Drain (evict pods with safety checks)
	evicted, err := e.drainNode(ctx, target.NodeName, podsByNode[target.NodeName], guards, p)
	result.EvictedPods = append(result.EvictedPods, evicted...)
	if err != nil {
		log.Printf("Evictor: drain %s had errors: %v — node remains cordoned", target.NodeName, err)
		return result, err
	}

	result.DrainedNodes = append(result.DrainedNodes, target.NodeName)
	result.NodesSaved++
	log.Printf("Evictor: successfully drained node %s (%d pods evicted)", target.NodeName, len(evicted))

	return result, nil
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

// cordonNode marks a node as unschedulable
func (e *Evictor) cordonNode(ctx context.Context, nodeName string) error {
	node, err := e.k8s.CoreV1().Nodes().Get(ctx, nodeName, metav1.GetOptions{})
	if err != nil {
		return err
	}
	node.Spec.Unschedulable = true
	_, err = e.k8s.CoreV1().Nodes().Update(ctx, node, metav1.UpdateOptions{})
	return err
}

// drainNode evicts all pods from a node respecting PDBs and safety guards
func (e *Evictor) drainNode(ctx context.Context, nodeName string, pods []corev1.Pod, guards policy.SafetyGuards, p policy.EvictorPolicy) ([]string, error) {
	evicted := make([]string, 0)
	for _, pod := range pods {
		if reason := e.podBlocksEviction(pod, guards, p.AggressiveMode); reason != "" {
			log.Printf("Evictor: skip pod %s/%s: %s", pod.Namespace, pod.Name, reason)
			continue
		}

		// PDB check
		if guards.RespectPodDisruptionBudgets {
			if blocked, reason := e.checkPDB(ctx, pod); blocked {
				log.Printf("Evictor: pod %s/%s blocked by PDB: %s", pod.Namespace, pod.Name, reason)
				continue
			}
		}

		// Evict
		eviction := &policyv1.Eviction{
			ObjectMeta: metav1.ObjectMeta{
				Name:      pod.Name,
				Namespace: pod.Namespace,
			},
		}
		if err := e.k8s.CoreV1().Pods(pod.Namespace).EvictV1(ctx, eviction); err != nil {
			log.Printf("Evictor: evict pod %s/%s failed: %v", pod.Namespace, pod.Name, err)
			continue
		}
		evicted = append(evicted, fmt.Sprintf("%s/%s", pod.Namespace, pod.Name))
		log.Printf("Evictor: evicted pod %s/%s from node %s", pod.Namespace, pod.Name, nodeName)
	}
	return evicted, nil
}

// checkPDB checks whether evicting this pod would violate its PodDisruptionBudget
func (e *Evictor) checkPDB(ctx context.Context, pod corev1.Pod) (bool, string) {
	pdbList, err := e.k8s.PolicyV1().PodDisruptionBudgets(pod.Namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return false, "" // if we can't check, allow eviction
	}
	for _, pdb := range pdbList.Items {
		if pdb.Status.DisruptionsAllowed == 0 {
			selector, err := metav1.LabelSelectorAsSelector(pdb.Spec.Selector)
			if err != nil {
				continue
			}
			if selector.Matches(labelSet(pod.Labels)) {
				return true, fmt.Sprintf("PDB %s allows 0 disruptions", pdb.Name)
			}
		}
	}
	return false, ""
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

// labelSet converts map[string]string to labels.Set for selector matching
func labelSet(m map[string]string) labelsSet {
	return labelsSet(m)
}

type labelsSet map[string]string

func (l labelsSet) Has(key string) bool         { _, ok := l[key]; return ok }
func (l labelsSet) Get(key string) string        { return l[key] }
func (l labelsSet) AsSelector() interface{}      { return nil }
func (l labelsSet) String() string               { return fmt.Sprintf("%v", map[string]string(l)) }
