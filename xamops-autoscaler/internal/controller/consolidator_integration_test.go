package controller

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/removal"
)

// This is the tightly-scoped design §8.2 integration reconcile, run FakeProvider-
// backed (no EC2/ASG, no live cluster): a single seeded over-provisioned snapshot is
// driven through ANALYSIS (gate.Simulate, DRY_RUN) — asserting ZERO evictions and
// ZERO provider calls — and then through AUTOMATED (gate.Live, DRY_RUN=false) with the
// FakeProvider, which removes one seeded empty node and drains one under-utilized node
// whose pods belong to a multi-replica Deployment guarded by a PDB. The run asserts
// I1 (eviction via the Eviction API, never a raw Delete, PDB object present),
// I2 (one-at-a-time per owner with replacement-ready wait — emulated by
// replaceOnEviction), I9 (FFD proved a feasible receiver before any eviction, so no
// pod is stranded), and I11 (the ASG terminate decrements desired capacity, launches
// no replacement, and is refused fail-closed at the nodegroup minimum).

// labeledRSPod is an evictablePod of demo-rs (3 replicas) carrying app=demo so a PDB
// selector can match it. It is the multi-replica Deployment workload of §8.2.
func labeledRSPod(name, nodeName, cpu string) *corev1.Pod {
	p := sizedPod(name, nodeName, cpu)
	p.Labels = map[string]string{"app": "demo"}
	return p
}

// integrationPolicy is consolPolicy with the per-node-group cap raised to 2 so a
// single §8.2 reconcile can both remove the empty node and drain the under-utilized
// node, which share node group ng-a.
func integrationPolicy() policy.ConsolidationPolicy {
	p := consolPolicy()
	p.MaxConcurrentDisruptions = 2
	return p
}

// demoPDB is a PDB (minAvailable=1) selecting app=demo, the guard the drain's eviction
// path must honour (I1).
func demoPDB() *policyv1.PodDisruptionBudget {
	one := intstr.FromInt(1)
	return &policyv1.PodDisruptionBudget{
		ObjectMeta: metav1.ObjectMeta{Name: "demo-pdb", Namespace: "default", Generation: 1},
		Spec: policyv1.PodDisruptionBudgetSpec{
			MinAvailable: &one,
			Selector:     &metav1.LabelSelector{MatchLabels: map[string]string{"app": "demo"}},
		},
		Status: policyv1.PodDisruptionBudgetStatus{DisruptionsAllowed: 1, ObservedGeneration: 1},
	}
}

// overProvisionedCluster seeds the §8.2 snapshot in node group ng-a:
//   - empty-node: only a DaemonSet pod  -> EMPTY move
//   - under-node: two small app=demo pods (200m total, < 50% of 4 CPU) -> DRAIN move
//   - host-node:  a 3-CPU pod keeps it above the under-utilized threshold and leaves
//     just enough headroom to receive the under-node's pods (I9 target).
//
// All three sit in ng-a so the last-node-in-group floor (ready-1 = 2) allows both the
// empty and the under-utilized node to be removed in one plan.
func overProvisionedCluster() *fake.Clientset {
	return fake.NewSimpleClientset(
		consolNode("empty-node", "ng-a", "aws:///us-east-1a/i-0empty", 20*time.Minute),
		consolNode("under-node", "ng-a", "aws:///us-east-1a/i-0under", 20*time.Minute),
		consolNode("host-node", "ng-a", "aws:///us-east-1a/i-0host", 0),
		demoRS(),
		demoPDB(),
		daemonSetPod("aws-node-empty", "empty-node"),
		labeledRSPod("app-1", "under-node", "100m"),
		labeledRSPod("app-2", "under-node", "100m"),
		busyPod("busy-host", "host-node"),
	)
}

// Phase 1 of §8.2: ANALYSIS (gate.Simulate / DRY_RUN) takes ZERO evictions and makes
// ZERO provider calls, even though the plan clearly carries an EMPTY and a DRAIN move.
func TestIntegrationAnalysisTakesZeroEvictions(t *testing.T) {
	c := overProvisionedCluster()
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)

	assertPlanHasEmpty(t, plan, "empty-node")
	assertPlanHasDrain(t, plan, "under-node")

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0empty"] = facts("i-0empty", "asg-a", 1, 3)
	fakeProv.Facts["i-0under"] = facts("i-0under", "asg-a", 1, 3)

	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Simulate, consolOpts(), integrationPolicy(),
		drain.NewBudget(5, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	if len(fakeProv.Terminations()) != 0 {
		t.Fatalf("ANALYSIS must call the provider zero times, got %+v", fakeProv.Terminations())
	}
	if res.Run.Summary.Removed != 0 || res.Run.Summary.PodsEvicted != 0 {
		t.Fatalf("ANALYSIS took evictions/removals: %+v", res.Run.Summary)
	}
	if res.Run.Summary.Simulated != 2 {
		t.Errorf("both candidates should be recorded simulated, got %+v", res.Run.Summary)
	}
	// The whole reconcile wrote nothing to the cluster.
	assertOnlyReads(t, c)
}

// Phase 2 of §8.2: AUTOMATED (gate.Live, DRY_RUN=false) with the FakeProvider removes
// the empty node and drains the under-utilized node, asserting I1/I2/I9/I11.
func TestIntegrationAutomatedDrainAndRemove(t *testing.T) {
	c := overProvisionedCluster()
	// I2: emulate the API server + ReplicaSet controller so an evicted pod is replaced
	// Running+Ready on host-node (the drain waits for the replacement before moving on).
	replaceOnEviction(c, "host-node")

	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	assertPlanHasEmpty(t, plan, "empty-node")
	assertPlanHasDrain(t, plan, "under-node")

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0empty"] = facts("i-0empty", "asg-a", 1, 3)
	fakeProv.Facts["i-0under"] = facts("i-0under", "asg-a", 1, 3)

	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), integrationPolicy(),
		drain.NewBudget(5, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	// Both nodes removed.
	if res.Run.Summary.Removed != 2 {
		t.Fatalf("summary.removed = %d, want 2 (empty + under); run=%+v", res.Run.Summary.Removed, res.Run.Summary)
	}
	empty, _ := findNodeRun(res.Run, "empty-node")
	under, _ := findNodeRun(res.Run, "under-node")
	if empty.Status != consolidation.RunStatusRemoved || empty.MoveType != consolidation.MoveEmpty {
		t.Errorf("empty-node run = %+v", empty)
	}
	if under.Status != consolidation.RunStatusRemoved || under.MoveType != consolidation.MoveDrain {
		t.Errorf("under-node run = %+v", under)
	}

	// I11: both instances terminated with decrement, in their ASG, no replacement.
	calls := fakeProv.Terminations()
	if len(calls) != 2 {
		t.Fatalf("I11: expected two terminate calls, got %+v", calls)
	}
	seen := map[string]removal.TerminateCall{}
	for _, call := range calls {
		if !call.Decrement {
			t.Errorf("I11: terminate without decrement: %+v", call)
		}
		if call.ASG != "asg-a" {
			t.Errorf("I11: terminate in wrong ASG: %+v", call)
		}
		seen[call.InstanceID] = call
	}
	if _, ok := seen["i-0empty"]; !ok {
		t.Errorf("I11: empty node instance not terminated; calls=%+v", calls)
	}
	if _, ok := seen["i-0under"]; !ok {
		t.Errorf("I11: under-utilized node instance not terminated; calls=%+v", calls)
	}

	// I9: the under node's pods were placed on a feasible receiver before eviction, so
	// none was stranded — at least the two app pods were evicted and the run is clean.
	if under.PodsEvicted < 2 {
		t.Errorf("I9: under-node evicted %d pods, want >= 2 (its app pods relocated)", under.PodsEvicted)
	}
	if res.Run.Summary.Aborted != 0 || res.Run.Summary.Refused != 0 {
		t.Errorf("I9: no pod should strand — aborted/refused seen: %+v", res.Run.Summary)
	}

	// I1: eviction went through the Eviction API, never a raw pod Delete.
	assertNoPodDelete(t, c)

	// Both nodes cordoned + marked drained before terminate.
	for _, name := range []string{"empty-node", "under-node"} {
		n, _ := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
		if !n.Spec.Unschedulable || n.Annotations[drain.AnnotationState] != drain.StateDrained {
			t.Errorf("%s not drained before terminate: %+v %v", name, n.Spec, n.Annotations)
		}
	}
	// host-node (the receiver) is never touched.
	if h, _ := c.CoreV1().Nodes().Get(context.Background(), "host-node", metav1.GetOptions{}); h.Spec.Unschedulable {
		t.Error("host-node (receiver) must not be cordoned")
	}
}

// I11 fail-closed at the nodegroup minimum: the same reconcile, but the under node's
// ASG is already at min at the post-drain re-check. The node is drained+cordoned but
// NEVER terminated; the empty node (above min) still is.
func TestIntegrationAutomatedRefusesAtMin(t *testing.T) {
	c := overProvisionedCluster()
	replaceOnEviction(c, "host-node")

	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0empty"] = facts("i-0empty", "asg-a", 1, 3)
	fakeProv.Facts["i-0under"] = facts("i-0under", "asg-b", 2, 2) // desired == min

	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), integrationPolicy(),
		drain.NewBudget(5, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	calls := fakeProv.Terminations()
	if len(calls) != 1 || calls[0].InstanceID != "i-0empty" {
		t.Fatalf("I11 at-min: only the empty node (above min) should terminate, got %+v", calls)
	}
	under, _ := findNodeRun(res.Run, "under-node")
	if under.Status != consolidation.RunStatusRefused {
		t.Fatalf("I11 at-min: under-node should be refused, got %+v", under)
	}
	// Refused still leaves the node drained + cordoned (valid, never forced).
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "under-node", metav1.GetOptions{})
	if !n.Spec.Unschedulable || n.Annotations[drain.AnnotationState] != drain.StateDrained {
		t.Errorf("under-node should be drained+cordoned even when terminate is refused: %+v %v", n.Spec, n.Annotations)
	}
}

// The verified-in-cycle-drain terminate gate (consolidator.go: !out.Verified() ->
// RunStatusAborted, TerminateAndDecrement NOT called). Here the under node carries
// app=demo pods but NO replaceOnEviction reactor is installed, so the evicted pods
// never get a Ready replacement and the live in-cycle drain cannot verify within the
// test ReplacementTimeout. The gate is the single thing standing between an unverified
// drain and a live instance termination: it must record RunStatusAborted and make ZERO
// provider terminate calls for that node, proving terminate is strictly downstream of a
// verified drain (AC-26: zero live terminate calls). The empty node (whose DaemonSet-
// only drain DOES verify) is still removed, so this isolates the gate rather than a
// blanket no-op. Note the aborting drain RESTORES (uncordons) the node it cordoned —
// the gate never force-cordons or force-terminates a node it could not cleanly drain.
func TestIntegrationAbortsUnverifiedDrainWithoutTerminate(t *testing.T) {
	c := overProvisionedCluster() // NO replaceOnEviction: the under node's drain cannot verify.

	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	assertPlanHasDrain(t, plan, "under-node")

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0empty"] = facts("i-0empty", "asg-a", 1, 3)
	fakeProv.Facts["i-0under"] = facts("i-0under", "asg-a", 1, 3)

	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), integrationPolicy(),
		drain.NewBudget(5, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	under, ok := findNodeRun(res.Run, "under-node")
	if !ok {
		t.Fatalf("no run recorded for under-node; run=%+v", res.Run)
	}
	// !out.Verified() => aborted, never refused/removed/deferred.
	if under.Status != consolidation.RunStatusAborted {
		t.Fatalf("unverified drain must record RunStatusAborted, got %+v", under)
	}
	// AC-26: the node whose drain did not verify was NEVER terminated.
	for _, call := range fakeProv.Terminations() {
		if call.InstanceID == "i-0under" {
			t.Fatalf("TerminateAndDecrement called for an unverified drain: %+v", call)
		}
	}
	// The aborting drain restored the node it cordoned — never force-terminated, never
	// left half-drained-and-live.
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "under-node", metav1.GetOptions{})
	if n.Spec.Unschedulable {
		t.Errorf("an aborted drain must restore (uncordon) the node, got cordoned: %+v", n.Spec)
	}
	if n.Annotations[drain.AnnotationState] == drain.StateDrained {
		t.Errorf("an unverified drain must not mark the node drained: %v", n.Annotations)
	}
	if res.Run.Summary.Aborted < 1 {
		t.Errorf("summary should count the aborted node: %+v", res.Run.Summary)
	}
}

// assertPlanHasDrain fails unless the plan carries a DRAIN move for name.
func assertPlanHasDrain(t *testing.T, plan consolidation.Plan, name string) {
	t.Helper()
	for _, m := range plan.Moves {
		if m.Node.Name == name && m.Type == consolidation.MoveDrain {
			return
		}
	}
	t.Fatalf("plan has no DRAIN move for %s; moves=%+v blocked=%+v kept=%+v", name, plan.Moves, plan.Blocked, plan.Kept)
}
