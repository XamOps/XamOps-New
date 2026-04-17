package controller

import (
	"context"
	"fmt"
	"log"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/policy"
)

// Rebalancer handles the full node-rebalancing workflow:
//  1. Detect that a rebalance is triggered (rebalancer.status == "running")
//  2. Identify nodes to drain (most expensive / least efficient)
//  3. Cordon + drain them gracefully
//  4. Report completion (autoscaler pushes event back to XamOps)
//
// NOTE: The actual new-node provisioning relies on Karpenter or Cluster Autoscaler
// already running in the cluster. The rebalancer drains old nodes; Karpenter
// schedules new ones automatically based on pending pods from the drain.
type Rebalancer struct {
	k8s    kubernetes.Interface
	dryRun bool
}

func NewRebalancer(k8s kubernetes.Interface, dryRun bool) *Rebalancer {
	return &Rebalancer{k8s: k8s, dryRun: dryRun}
}

// RebalanceResult captures what happened during a rebalance cycle.
type RebalanceResult struct {
	Status          string    `json:"status"`           // running | completed | failed | idle
	StartedAt       time.Time `json:"startedAt"`
	CompletedAt     time.Time `json:"completedAt,omitempty"`
	NodesDrained    []string  `json:"nodesDrained"`
	PodsEvicted     int       `json:"podsEvicted"`
	DurationMinutes int       `json:"durationMinutes"`
	Error           string    `json:"error,omitempty"`
	DryRun          bool      `json:"dryRun"`
}

// Run checks whether a rebalance is pending and executes it if so.
// It returns nil if no rebalance is needed.
func (rb *Rebalancer) Run(ctx context.Context, rebalancerCfg policy.RebalancerConfig, guards policy.SafetyGuards) (*RebalanceResult, error) {
	if !rebalancerCfg.Enabled {
		return nil, nil
	}

	// Only act when status == "running" (set by XamOps UI trigger or scheduled)
	if rebalancerCfg.Status != "running" {
		return nil, nil
	}

	log.Println("Rebalancer: rebalance triggered — starting drain cycle")
	result := &RebalanceResult{
		Status:    "running",
		StartedAt: time.Now(),
		DryRun:    rb.dryRun,
	}

	drainTimeout := time.Duration(rebalancerCfg.DrainTimeoutMinutes) * time.Minute
	if drainTimeout == 0 {
		drainTimeout = 20 * time.Minute
	}

	// 1. Find candidate nodes for rebalancing (spot nodes with low utilisation
	//    or any node the user wants rotated — future: support node selectors via UI)
	nodes, err := rb.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		result.Status = "failed"
		result.Error = err.Error()
		return result, err
	}

	candidates := rb.selectRebalanceCandidates(nodes.Items, guards)
	if len(candidates) == 0 {
		log.Println("Rebalancer: no rebalance candidates found")
		result.Status = "completed"
		result.CompletedAt = time.Now()
		return result, nil
	}

	log.Printf("Rebalancer: will drain %d node(s): %v", len(candidates), nodeNames(candidates))

	// 2. Drain each candidate
	for _, node := range candidates {
		if rb.dryRun {
			log.Printf("DRY-RUN: would drain node %s", node.Name)
			result.NodesDrained = append(result.NodesDrained, node.Name+" (dry-run)")
			continue
		}

		// Cordon first
		if err := rb.cordonNode(ctx, node.Name); err != nil {
			log.Printf("Rebalancer: cordon %s failed: %v — skipping", node.Name, err)
			continue
		}

		// Drain with timeout
		drainCtx, cancel := context.WithTimeout(ctx, drainTimeout)
		podsEvicted, err := rb.drainNode(drainCtx, node.Name, guards, rebalancerCfg.GracefulDraining)
		cancel()

		result.PodsEvicted += podsEvicted
		if err != nil {
			log.Printf("Rebalancer: drain %s had errors: %v", node.Name, err)
			if !rebalancerCfg.GracefulDraining {
				result.Error = fmt.Sprintf("drain %s: %v", node.Name, err)
			}
		} else {
			result.NodesDrained = append(result.NodesDrained, node.Name)
			log.Printf("Rebalancer: drained node %s (%d pods evicted)", node.Name, podsEvicted)
		}
	}

	elapsed := time.Since(result.StartedAt)
	result.Status = "completed"
	result.CompletedAt = time.Now()
	result.DurationMinutes = int(elapsed.Minutes())

	log.Printf("Rebalancer: completed in %.1f min — %d nodes drained, %d pods evicted",
		elapsed.Minutes(), len(result.NodesDrained), result.PodsEvicted)

	return result, nil
}

// selectRebalanceCandidates picks nodes to drain during rebalancing.
// Strategy: prefer older nodes and on-demand nodes (spot nodes are often already optimal).
// Future: support node selectors from UI node templates.
func (rb *Rebalancer) selectRebalanceCandidates(nodes []corev1.Node, guards policy.SafetyGuards) []corev1.Node {
	candidates := make([]corev1.Node, 0)

	for _, node := range nodes {
		// Skip control-plane
		if _, ok := node.Labels["node-role.kubernetes.io/control-plane"]; ok {
			continue
		}
		if _, ok := node.Labels["node-role.kubernetes.io/master"]; ok {
			continue
		}

		// Skip already cordoned
		if node.Spec.Unschedulable {
			continue
		}

		// Prefer on-demand nodes for rebalancing (spot nodes stay)
		capacityType := node.Labels["eks.amazonaws.com/capacityType"]
		if capacityType == "SPOT" {
			continue
		}

		candidates = append(candidates, node)
	}

	// Limit to 2 nodes per rebalance cycle to avoid service disruption
	if len(candidates) > 2 {
		candidates = candidates[:2]
	}

	return candidates
}

func (rb *Rebalancer) cordonNode(ctx context.Context, nodeName string) error {
	node, err := rb.k8s.CoreV1().Nodes().Get(ctx, nodeName, metav1.GetOptions{})
	if err != nil {
		return err
	}
	node.Spec.Unschedulable = true
	_, err = rb.k8s.CoreV1().Nodes().Update(ctx, node, metav1.UpdateOptions{})
	return err
}

func (rb *Rebalancer) drainNode(ctx context.Context, nodeName string, guards policy.SafetyGuards, graceful bool) (int, error) {
	pods, err := rb.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{
		FieldSelector: fmt.Sprintf("spec.nodeName=%s", nodeName),
	})
	if err != nil {
		return 0, err
	}

	evicted := 0
	for _, pod := range pods.Items {
		if pod.Status.Phase == corev1.PodSucceeded || pod.Status.Phase == corev1.PodFailed {
			continue
		}

		// Skip DaemonSet pods
		isDaemonSet := false
		for _, ref := range pod.OwnerReferences {
			if ref.Kind == "DaemonSet" {
				isDaemonSet = true
				break
			}
		}
		if isDaemonSet {
			continue
		}

		// Skip critical namespaces
		if guards.ProtectCriticalNamespaces && isCriticalNS(pod.Namespace, guards.CriticalNamespaces) {
			continue
		}

		// Delete/Evict the pod
		deletePolicy := metav1.DeletePropagationForeground
		err := rb.k8s.CoreV1().Pods(pod.Namespace).Delete(ctx, pod.Name, metav1.DeleteOptions{
			PropagationPolicy: &deletePolicy,
		})
		if err != nil {
			log.Printf("Rebalancer: failed to delete pod %s/%s: %v", pod.Namespace, pod.Name, err)
			if !graceful {
				return evicted, err
			}
		} else {
			evicted++
		}
	}

	return evicted, nil
}

func nodeNames(nodes []corev1.Node) []string {
	names := make([]string, len(nodes))
	for i, n := range nodes {
		names[i] = n.Name
	}
	return names
}
