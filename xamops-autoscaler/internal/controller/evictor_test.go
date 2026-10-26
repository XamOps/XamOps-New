package controller

import (
	"context"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

var podsGVR = corev1.SchemeGroupVersion.WithResource("pods")

func ctrlRef(b bool) *bool { return &b }

// drainableNode returns a Ready node that passes every node gate: not cordoned, well
// past the grace period, no control-plane role label, 4 CPU / 8Gi / 17 pods.
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
				corev1.ResourcePods:   resource.MustParse("17"),
			},
			Conditions: []corev1.NodeCondition{{Type: corev1.NodeReady, Status: corev1.ConditionTrue}},
		},
	}
}

// demoRS is the ReplicaSet (3 replicas) owning evictablePod pods.
func demoRS() *appsv1.ReplicaSet {
	three := int32(3)
	return &appsv1.ReplicaSet{ObjectMeta: metav1.ObjectMeta{Name: "demo-rs", Namespace: "default", UID: "demo-rs-uid"},
		Spec: appsv1.ReplicaSetSpec{Replicas: &three}}
}

// evictablePod returns a 100m/128Mi pod of demo-rs in a non-critical namespace: it
// hits no blocker and fits on any other drainableNode.
func evictablePod(name, nodeName string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: "default",
			UID:       types.UID("uid-" + name),
			OwnerReferences: []metav1.OwnerReference{
				{Kind: "ReplicaSet", Name: "demo-rs", UID: "demo-rs-uid", Controller: ctrlRef(true)},
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
		Status: corev1.PodStatus{Phase: corev1.PodRunning,
			Conditions: []corev1.PodCondition{{Type: corev1.PodReady, Status: corev1.ConditionTrue}}},
	}
}

func sizedPod(name, nodeName, cpu string) *corev1.Pod {
	p := evictablePod(name, nodeName)
	p.Spec.Containers[0].Resources.Requests[corev1.ResourceCPU] = resource.MustParse(cpu)
	return p
}

func daemonSetPod(name, nodeName string) *corev1.Pod {
	p := evictablePod(name, nodeName)
	p.Namespace = "kube-system"
	p.OwnerReferences = []metav1.OwnerReference{{Kind: "DaemonSet", Name: "aws-node", UID: "ds-uid", Controller: ctrlRef(true)}}
	return p
}

func testEvictorPolicy(analyseOnly bool) policy.EvictorPolicy {
	p := policy.DefaultBundle().EvictorPolicy
	p.Enabled = true
	p.AnalyseOnly = analyseOnly
	return p
}

func fitExec(c *fake.Clientset) *drain.Executor {
	e := drain.NewExecutor(c, drain.NewFitPlanner(c, nil, fitsim.Options{Guards: policy.DefaultBundle().SafetyGuards, SelfNamespace: selfNS}), selfNS)
	e.ReplacementTimeout, e.PDBRetry, e.PollInterval = 300*time.Millisecond, 50*time.Millisecond, 10*time.Millisecond
	return e
}

// replaceOnEviction emulates the API server + ReplicaSet controller: an evicted pod
// is deleted and a same-size replacement with a new UID becomes Running+Ready on
// target.
func replaceOnEviction(c *fake.Clientset, target string) {
	var n int32
	c.PrependReactor("create", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if action.GetSubresource() != "eviction" {
			return false, nil, nil
		}
		ev := action.(k8stesting.CreateAction).GetObject().(*policyv1.Eviction)
		obj, err := c.Tracker().Get(podsGVR, ev.Namespace, ev.Name)
		if err != nil {
			return true, nil, err
		}
		old := obj.(*corev1.Pod).DeepCopy()
		if err := c.Tracker().Delete(podsGVR, ev.Namespace, ev.Name); err != nil {
			return true, nil, err
		}
		i := atomic.AddInt32(&n, 1)
		old.Name, old.UID = fmt.Sprintf("%s-repl-%d", old.Name, i), types.UID(fmt.Sprintf("uid-repl-%d", i))
		old.Spec.NodeName, old.ResourceVersion = target, ""
		return true, nil, c.Tracker().Create(podsGVR, old, old.Namespace)
	})
}

func candidate(t *testing.T, r *EvictorResult, name string) NodeCandidate {
	t.Helper()
	for _, c := range r.CandidateNodes {
		if c.NodeName == name {
			return c
		}
	}
	t.Fatalf("no candidate %s", name)
	return NodeCandidate{}
}

func runEvictor(t *testing.T, c *fake.Clientset, d gate.Decision, analyseOnly bool, exec *drain.Executor, capacity int) (*EvictorResult, []policy.ActionRecord) {
	t.Helper()
	r, recs, err := NewEvictor(c, d.Mode != gate.Live).Run(context.Background(), testEvictorPolicy(analyseOnly),
		policy.DefaultBundle().SafetyGuards, d, drain.NewBudget(capacity, nil), exec)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	return r, recs
}

func TestEvictorRunAnalyseOnlyReportsCandidatesWithoutActing(t *testing.T) {
	c := fake.NewSimpleClientset(drainableNode("node-a"), drainableNode("node-b"), demoRS(), evictablePod("pod-a", "node-a"))
	result, recs := runEvictor(t, c, gate.Decision{Mode: gate.Analyse, Reason: "evictorPolicy.analyseOnly=true"}, true, fitExec(c), 1)

	if !result.AnalyseOnly || len(recs) != 0 {
		t.Fatalf("analyse-only: AnalyseOnly=%v recs=%+v", result.AnalyseOnly, recs)
	}
	if len(result.CandidateNodes) != 2 {
		t.Fatalf("expected a candidate (with a plan) for every node, got %d", len(result.CandidateNodes))
	}
	a := candidate(t, result, "node-a")
	if !a.CanEvict || a.Fit == nil || !a.Fit.Drainable || len(a.Fit.Moves) != 1 || a.Fit.Moves[0].To != "node-b" {
		t.Errorf("node-a = %+v fit = %+v", a, a.Fit)
	}
	if b := candidate(t, result, "node-b"); b.Fit == nil || !b.Fit.Drainable {
		t.Errorf("an empty node must carry a drainable plan: %+v", b.Fit)
	}
	if result.NodesSaved != 0 || len(result.DrainedNodes) != 0 || len(result.EvictedPods) != 0 {
		t.Errorf("analyse-only acted: %+v", result)
	}
	assertOnlyReads(t, c)
}

// DaemonSet pods are excluded: a node with DaemonSet pods and movable pods that fit
// elsewhere is canEvict=true.
func TestEvictorDaemonSetPodsDoNotBlock(t *testing.T) {
	c := fake.NewSimpleClientset(drainableNode("node-a"), drainableNode("node-b"), demoRS(),
		evictablePod("pod-a", "node-a"), daemonSetPod("aws-node-a", "node-a"), daemonSetPod("aws-node-b", "node-b"))
	result, _ := runEvictor(t, c, gate.Decision{Mode: gate.Analyse}, true, fitExec(c), 1)
	a := candidate(t, result, "node-a")
	if !a.CanEvict || a.Fit.ExcludedCount != 1 || len(a.Fit.Blocked) != 0 {
		t.Errorf("node-a: canEvict=%v skip=%q fit=%+v", a.CanEvict, a.SkipReason, a.Fit)
	}
	if a.PodCount != 2 {
		t.Errorf("podCount = %d, want 2 (DaemonSet pods still count)", a.PodCount)
	}
}

// A pod that fits nowhere else makes the node canEvict=false with the strand listed.
func TestEvictorStrandIsListed(t *testing.T) {
	small := drainableNode("node-b")
	small.Status.Allocatable[corev1.ResourceCPU] = resource.MustParse("1")
	c := fake.NewSimpleClientset(drainableNode("node-a"), small, demoRS(), sizedPod("big", "node-a", "1500m"))
	result, _ := runEvictor(t, c, gate.Decision{Mode: gate.Analyse}, true, fitExec(c), 1)
	a := candidate(t, result, "node-a")
	if a.CanEvict || len(a.Fit.Strands) != 1 || a.Fit.Strands[0] != "default/big" {
		t.Errorf("node-a: canEvict=%v fit=%+v", a.CanEvict, a.Fit)
	}
	if !strings.Contains(a.SkipReason, "strand default/big") {
		t.Errorf("skipReason = %q", a.SkipReason)
	}
}

// Node gates still apply before the plan: a cordoned node is never canEvict even
// when its plan is drainable.
func TestEvictorNodeGatesStillApply(t *testing.T) {
	cordoned := drainableNode("node-a")
	cordoned.Spec.Unschedulable = true
	c := fake.NewSimpleClientset(cordoned, drainableNode("node-b"))
	result, _ := runEvictor(t, c, gate.Decision{Mode: gate.Analyse}, true, fitExec(c), 1)
	a := candidate(t, result, "node-a")
	if a.CanEvict || a.SkipReason != "already cordoned" || !a.Fit.Drainable {
		t.Errorf("node-a = %+v fit = %+v", a, a.Fit)
	}
}

// A dry run never claims a saved node: the most empty canEvict node gets the one
// budget slot as a simulation, the other is skipped (budget), nothing is written.
func TestEvictorDryRunReportsOneSimulationAndSavesNothing(t *testing.T) {
	c := fake.NewSimpleClientset(drainableNode("node-a"), drainableNode("node-b"), demoRS(),
		evictablePod("pod-a", "node-a"), sizedPod("pod-b", "node-b", "500m"))
	result, recs := runEvictor(t, c, gate.Decision{Mode: gate.Simulate, Reason: "DRY_RUN=true"}, false, fitExec(c), 1)

	if result.AnalyseOnly || result.NodesSaved != 0 || len(result.DrainedNodes) != 0 {
		t.Errorf("result = %+v", result)
	}
	if len(recs) != 2 {
		t.Fatalf("expected 2 records (simulated + budget-skipped), got %+v", recs)
	}
	if r := recs[0]; r.Status != policy.ActionSimulated || !r.DryRun || r.Verified || r.Target != "node/node-a" || r.Details["fitChecked"] != true {
		t.Errorf("simulation = %+v", r)
	}
	if r := recs[1]; r.Status != policy.ActionSkipped || r.Target != "node/node-b" || !strings.Contains(r.Reason, "budget exhausted") {
		t.Errorf("second = %+v", r)
	}
	if !result.CandidateNodes[0].DryRun {
		t.Error("candidate must carry dryRun=true")
	}
	assertOnlyReads(t, c)
}

// Live with the fail-closed UnavailablePlanner: every canEvict node is refused,
// nothing written, nothing saved.
func TestEvictorLiveRefusedByUnavailablePlanner(t *testing.T) {
	c := fake.NewSimpleClientset(drainableNode("node-a"), drainableNode("node-b"), demoRS(), evictablePod("pod-a", "node-a"))
	result, recs := runEvictor(t, c, gate.Decision{Mode: gate.Live}, false, drain.NewExecutor(c, drain.UnavailablePlanner{}, selfNS), 1)
	if result.NodesSaved != 0 || len(recs) != 2 {
		t.Fatalf("result = %+v recs = %+v", result, recs)
	}
	for _, r := range recs {
		if r.Status != policy.ActionRefused || r.DryRun || r.Reason != drain.UnavailableReason {
			t.Errorf("record = %+v", r)
		}
	}
	assertOnlyReads(t, c)
}

// Live with the fit planner: the selected node is cordoned, its pod evicted and
// replaced elsewhere, the DaemonSet pod stays, NodesSaved counts the verified drain.
func TestEvictorLiveDrainThroughFitPlanner(t *testing.T) {
	c := fake.NewSimpleClientset(drainableNode("node-a"), drainableNode("node-b"), demoRS(),
		evictablePod("pod-a", "node-a"), daemonSetPod("aws-node-a", "node-a"), sizedPod("pod-b", "node-b", "500m"))
	replaceOnEviction(c, "node-b")
	result, recs := runEvictor(t, c, gate.Decision{Mode: gate.Live}, false, fitExec(c), 1)
	if result.NodesSaved != 1 || len(result.DrainedNodes) != 1 || result.DrainedNodes[0] != "node-a" {
		t.Fatalf("result = %+v recs = %+v", result, recs)
	}
	if len(result.EvictedPods) != 1 || result.EvictedPods[0] != "default/pod-a" {
		t.Errorf("evicted = %v", result.EvictedPods)
	}
	if recs[0].Status != policy.ActionSucceeded || !recs[0].Verified || recs[0].DryRun {
		t.Errorf("record = %+v", recs[0])
	}
	if _, err := c.CoreV1().Pods("kube-system").Get(context.Background(), "aws-node-a", metav1.GetOptions{}); err != nil {
		t.Errorf("DaemonSet pod must stay on the node: %v", err)
	}
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "node-a", metav1.GetOptions{})
	if !n.Spec.Unschedulable || n.Annotations[drain.AnnotationState] != drain.StateDrained {
		t.Errorf("node-a = %+v %v", n.Spec, n.Annotations)
	}
	if b, _ := c.CoreV1().Nodes().Get(context.Background(), "node-b", metav1.GetOptions{}); b.Spec.Unschedulable {
		t.Error("node-b must not be touched (budget 1)")
	}
	assertNoPodDelete(t, c)
}
