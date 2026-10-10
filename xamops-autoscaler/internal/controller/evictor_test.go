package controller

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
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

	d := gate.Decision{Mode: gate.Analyse, Reason: "evictorPolicy.analyseOnly=true"}
	exec := drain.NewExecutor(client, drain.UnavailablePlanner{}, "xamops-system")
	result, recs, err := e.Run(context.Background(), testEvictorPolicy(true), policy.DefaultBundle().SafetyGuards, d, drain.NewBudget(1, nil), exec)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if !result.AnalyseOnly {
		t.Error("expected result.AnalyseOnly to be true")
	}
	if len(recs) != 0 {
		t.Errorf("analyse-only must produce no action records, got %+v", recs)
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
	assertOnlyReads(t, client)
}

// A dry-run cycle no longer claims a saved node: NodesSaved 0, no DrainedNodes,
// exactly one simulation record with dryRun=true, and the node stays schedulable.
func TestEvictorDryRunReportsOneSimulationAndSavesNothing(t *testing.T) {
	client := fake.NewSimpleClientset(drainableNode("node-a"), evictablePod("pod-a", "node-a"))
	e := NewEvictor(client, true)

	d := gate.Decision{Mode: gate.Simulate, Reason: "DRY_RUN=true"}
	exec := drain.NewExecutor(client, drain.UnavailablePlanner{}, "xamops-system")
	result, recs, err := e.Run(context.Background(), testEvictorPolicy(false), policy.DefaultBundle().SafetyGuards, d, drain.NewBudget(1, nil), exec)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if result.AnalyseOnly {
		t.Error("expected result.AnalyseOnly to be false")
	}
	if result.NodesSaved != 0 {
		t.Errorf("expected NodesSaved 0, got %d", result.NodesSaved)
	}
	if len(result.DrainedNodes) != 0 {
		t.Errorf("expected no DrainedNodes, got %v", result.DrainedNodes)
	}
	if len(recs) != 1 {
		t.Fatalf("expected exactly one action record, got %+v", recs)
	}
	if !recs[0].DryRun || recs[0].Verified || recs[0].Target != "node/node-a" {
		t.Errorf("record = %+v", recs[0])
	}
	// The interim planner cannot prove the drain, so the simulation is a refusal.
	if recs[0].Status != policy.ActionRefused || recs[0].Details["fitChecked"] != false {
		t.Errorf("status = %s fitChecked = %v", recs[0].Status, recs[0].Details["fitChecked"])
	}
	if !result.CandidateNodes[0].DryRun {
		t.Error("candidate must carry dryRun=true")
	}
	assertOnlyReads(t, client)

	node, err := client.CoreV1().Nodes().Get(context.Background(), "node-a", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get node-a: %v", err)
	}
	if node.Spec.Unschedulable {
		t.Error("expected node-a to remain schedulable in dry-run mode")
	}
}

// Live with the interim planner: refused, nothing written, nothing saved.
func TestEvictorLiveRefusedByUnavailablePlanner(t *testing.T) {
	client := fake.NewSimpleClientset(drainableNode("node-a"), evictablePod("pod-a", "node-a"))
	e := NewEvictor(client, false)
	d := gate.Decision{Mode: gate.Live}
	exec := drain.NewExecutor(client, drain.UnavailablePlanner{}, "xamops-system")
	result, recs, err := e.Run(context.Background(), testEvictorPolicy(false), policy.DefaultBundle().SafetyGuards, d, drain.NewBudget(1, nil), exec)
	if err != nil {
		t.Fatal(err)
	}
	if result.NodesSaved != 0 || len(recs) != 1 || recs[0].Status != policy.ActionRefused || recs[0].DryRun {
		t.Errorf("result = %+v recs = %+v", result, recs)
	}
	assertOnlyReads(t, client)
}
