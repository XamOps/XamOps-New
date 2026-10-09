package controller

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/policy"
)

// drainableNode returns a node that passes every evaluateNode gate: not cordoned,
// well past the grace period, no control-plane role labels and plenty of allocatable.
func drainableNode(name string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{
			Name:              name,
			CreationTimestamp: metav1.NewTime(time.Now().Add(-24 * time.Hour)),
		},
		Status: corev1.NodeStatus{
			Allocatable: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse("4"),
				corev1.ResourceMemory: resource.MustParse("8Gi"),
			},
		},
	}
}

// evictablePod returns a tiny ReplicaSet-owned pod in a non-critical namespace,
// so it hits no eviction blocker and keeps the node far below the utilisation target.
func evictablePod(name, nodeName string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: "default",
			OwnerReferences: []metav1.OwnerReference{
				{Kind: "ReplicaSet", Name: "demo-rs"},
			},
		},
		Spec: corev1.PodSpec{
			NodeName: nodeName,
			Containers: []corev1.Container{{
				Name: "app",
				Resources: corev1.ResourceRequirements{
					Requests: corev1.ResourceList{
						corev1.ResourceCPU:    resource.MustParse("100m"),
						corev1.ResourceMemory: resource.MustParse("128Mi"),
					},
				},
			}},
		},
		Status: corev1.PodStatus{Phase: corev1.PodRunning},
	}
}

func testEvictorPolicy(analyseOnly bool) policy.EvictorPolicy {
	p := policy.DefaultBundle().EvictorPolicy
	p.Enabled = true
	p.AnalyseOnly = analyseOnly
	return p
}

func TestEvictorRunAnalyseOnlyReportsCandidatesWithoutActing(t *testing.T) {
	client := fake.NewSimpleClientset(drainableNode("node-a"), evictablePod("pod-a", "node-a"))
	e := NewEvictor(client, false)

	result, err := e.Run(context.Background(), testEvictorPolicy(true), policy.DefaultBundle().SafetyGuards)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if !result.AnalyseOnly {
		t.Error("expected result.AnalyseOnly to be true")
	}
	if len(result.CandidateNodes) != 1 {
		t.Fatalf("expected 1 candidate node, got %d", len(result.CandidateNodes))
	}
	if !result.CandidateNodes[0].CanEvict {
		t.Errorf("expected node-a to be evictable, skip reason: %q", result.CandidateNodes[0].SkipReason)
	}
	if len(result.DrainedNodes) != 0 {
		t.Errorf("expected no drained nodes, got %v", result.DrainedNodes)
	}
	if len(result.EvictedPods) != 0 {
		t.Errorf("expected no evicted pods, got %v", result.EvictedPods)
	}
	if result.NodesSaved != 0 {
		t.Errorf("expected NodesSaved 0, got %d", result.NodesSaved)
	}

	node, err := client.CoreV1().Nodes().Get(context.Background(), "node-a", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get node-a: %v", err)
	}
	if node.Spec.Unschedulable {
		t.Error("expected node-a to remain schedulable in analyse-only mode")
	}
}

func TestEvictorRunAnalyseOnlyDisabledStillDryRuns(t *testing.T) {
	client := fake.NewSimpleClientset(drainableNode("node-a"), evictablePod("pod-a", "node-a"))
	e := NewEvictor(client, true)

	result, err := e.Run(context.Background(), testEvictorPolicy(false), policy.DefaultBundle().SafetyGuards)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if result.AnalyseOnly {
		t.Error("expected result.AnalyseOnly to be false")
	}
	if result.NodesSaved != 1 {
		t.Errorf("expected NodesSaved 1, got %d", result.NodesSaved)
	}
	if len(result.DrainedNodes) != 1 {
		t.Fatalf("expected 1 reported drained node, got %v", result.DrainedNodes)
	}

	node, err := client.CoreV1().Nodes().Get(context.Background(), "node-a", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get node-a: %v", err)
	}
	if node.Spec.Unschedulable {
		t.Error("expected node-a to remain schedulable in dry-run mode")
	}
}
