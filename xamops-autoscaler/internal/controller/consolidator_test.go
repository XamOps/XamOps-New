package controller

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/removal"
)

// fixedNow is the injected clock used by every consolidator test.
var fixedNow = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)

// consolNode is a Ready node in node group ng with the given providerID. candidateAgo
// (>0) stamps the candidate-since annotation that many seconds before fixedNow so the
// dwell can be controlled; 0 leaves it unset.
func consolNode(name, ng, providerID string, candidateAgo time.Duration) *corev1.Node {
	n := drainableNode(name)
	n.Labels = map[string]string{consolidation.LabelNodeGroup: ng}
	n.Spec.ProviderID = providerID
	if candidateAgo > 0 {
		if n.Annotations == nil {
			n.Annotations = map[string]string{}
		}
		n.Annotations[AnnotationConsolidationCandidateSince] = fixedNow.Add(-candidateAgo).Format(time.RFC3339)
	}
	return n
}

// busyPod keeps a node out of the candidate set (requests above 50%): 3 CPU on a
// 4-CPU node.
func busyPod(name, nodeName string) *corev1.Pod { return sizedPod(name, nodeName, "3") }

func consolOpts() fitsim.Options {
	return fitsim.Options{Guards: policy.DefaultBundle().SafetyGuards, SelfNamespace: selfNS}
}

func consolPolicy() policy.ConsolidationPolicy {
	return policy.ConsolidationPolicy{
		Enabled: true, ConsolidateAfterSeconds: 600, MaxConcurrentDisruptions: 1, DwellSeconds: 600,
	}
}

// collectInput lists the cluster into a consolidation.Input (no dynamic CRs).
func collectInput(t *testing.T, c *fake.Clientset) consolidation.Input {
	t.Helper()
	in, err := consolidation.Collect(context.Background(), c, nil)
	if err != nil {
		t.Fatalf("collect: %v", err)
	}
	return in
}

// facts returns scripted NodeFacts for an instance (managed nodegroup, above min).
func facts(instanceID, asg string, min, desired int) removal.NodeFacts {
	return removal.NodeFacts{
		InstanceID: instanceID, ASG: asg, MinSize: min, DesiredCapacity: desired, ManagedNodegroup: "ng-a",
	}
}

func findNodeRun(run *consolidation.ConsolidationRunDto, name string) (consolidation.ConsolidationNodeRun, bool) {
	for _, n := range run.Nodes {
		if n.Node.Name == name {
			return n, true
		}
	}
	return consolidation.ConsolidationNodeRun{}, false
}

// Simulate acts on the plan but makes NO cloud call: the provider's terminate log is
// empty, the node is recorded simulated, and every emitted move type is EMPTY/DRAIN.
func TestConsolidatorSimulateNeverCallsProvider(t *testing.T) {
	// node-a EMPTY (only a DaemonSet pod); node-b kept (busy). Both in ng-a so node-a
	// is not the last node in its group.
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 20*time.Minute),
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		daemonSetPod("aws-node-a", "node-a"), busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	assertPlanHasEmpty(t, plan, "node-a")

	fakeProv := removal.NewFakeProvider()
	exec := fitExec(c)
	cons := NewConsolidator(fakeProv, exec, nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Simulate, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	if len(fakeProv.Terminations()) != 0 {
		t.Fatalf("Simulate must not call the provider, got %+v", fakeProv.Terminations())
	}
	nr, ok := findNodeRun(res.Run, "node-a")
	if !ok || nr.Status != consolidation.RunStatusSimulated {
		t.Fatalf("node-a run = %+v (ok=%v)", nr, ok)
	}
	if res.Run.DryRun != true || res.Run.Mode != "simulate" {
		t.Errorf("run dryRun/mode = %v/%s", res.Run.DryRun, res.Run.Mode)
	}
	for _, n := range res.Run.Nodes {
		if n.MoveType != consolidation.MoveEmpty && n.MoveType != consolidation.MoveDrain {
			t.Errorf("I0 violated: move type %q", n.MoveType)
		}
	}
	assertOnlyReads(t, c)
}

// Live: the EMPTY node is drained, then the provider is looked up and terminated with
// decrement; the run records it removed.
func TestConsolidatorLiveLooksUpAndTerminates(t *testing.T) {
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 20*time.Minute),
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		daemonSetPod("aws-node-a", "node-a"), busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	assertPlanHasEmpty(t, plan, "node-a")

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0aaa"] = facts("i-0aaa", "asg-a", 1, 3)
	exec := fitExec(c)
	cons := NewConsolidator(fakeProv, exec, nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}

	calls := fakeProv.Terminations()
	if len(calls) != 1 || calls[0].InstanceID != "i-0aaa" || calls[0].ASG != "asg-a" || !calls[0].Decrement {
		t.Fatalf("terminate calls = %+v", calls)
	}
	nr, _ := findNodeRun(res.Run, "node-a")
	if nr.Status != consolidation.RunStatusRemoved || nr.Decision != removal.XamopsASG.String() {
		t.Fatalf("node-a run = %+v", nr)
	}
	if res.Run.Summary.Removed != 1 {
		t.Errorf("summary.removed = %d", res.Run.Summary.Removed)
	}
	// The drain cordoned + marked the node drained before terminate.
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "node-a", metav1.GetOptions{})
	if !n.Spec.Unschedulable || n.Annotations[drain.AnnotationState] != drain.StateDrained {
		t.Errorf("node-a not drained before terminate: %+v %v", n.Spec, n.Annotations)
	}
}

// At the nodegroup minimum the removal is refused fail-closed: node drained+cordoned
// but NOT terminated.
func TestConsolidatorLiveRefusesAtMin(t *testing.T) {
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 20*time.Minute),
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		daemonSetPod("aws-node-a", "node-a"), busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0aaa"] = facts("i-0aaa", "asg-a", 2, 2) // desired == min
	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if len(fakeProv.Terminations()) != 0 {
		t.Fatalf("at-min must not terminate, got %+v", fakeProv.Terminations())
	}
	nr, _ := findNodeRun(res.Run, "node-a")
	if nr.Status != consolidation.RunStatusRefused {
		t.Fatalf("node-a run = %+v", nr)
	}
}

// T4 dwell: a node first seen as a candidate this cycle (no candidate-since) is not
// acted on and makes no cloud call.
func TestConsolidatorDwellNotYetElapsed(t *testing.T) {
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 60*time.Second), // only 60s < 600s dwell
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		daemonSetPod("aws-node-a", "node-a"), busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0aaa"] = facts("i-0aaa", "asg-a", 1, 3)
	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if len(fakeProv.Terminations()) != 0 {
		t.Fatalf("within-dwell node must not terminate, got %+v", fakeProv.Terminations())
	}
	nr, _ := findNodeRun(res.Run, "node-a")
	if nr.Status != consolidation.RunStatusSkipped {
		t.Fatalf("node-a run = %+v", nr)
	}
	assertOnlyReads(t, c)
}

// I6 per-node-group cap: with two EMPTY candidates in the same group and
// maxConcurrentDisruptions=1, only one acts; the other is skipped.
func TestConsolidatorPerNodeGroupCap(t *testing.T) {
	// node-a, node-b EMPTY; node-c kept (busy). All ng-a => floor allows two of three.
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 20*time.Minute),
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 20*time.Minute),
		consolNode("node-c", "ng-a", "aws:///us-east-1a/i-0ccc", 0),
		daemonSetPod("aws-node-a", "node-a"), daemonSetPod("aws-node-b", "node-b"),
		busyPod("busy-c", "node-c"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	empties := 0
	for _, m := range plan.Moves {
		if m.Type == consolidation.MoveEmpty {
			empties++
		}
	}
	if empties < 2 {
		t.Fatalf("expected 2 EMPTY moves to exercise the cap, got %d (plan %+v)", empties, plan.Moves)
	}

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0aaa"] = facts("i-0aaa", "asg-a", 1, 3)
	fakeProv.Facts["i-0bbb"] = facts("i-0bbb", "asg-a", 1, 3)
	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), consolPolicy(),
		drain.NewBudget(5, nil), fixedNow) // budget wide open: the cap, not the budget, limits
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if n := len(fakeProv.Terminations()); n != 1 {
		t.Fatalf("per-group cap=1 should terminate exactly one, got %d (%+v)", n, fakeProv.Terminations())
	}
	if res.Run.Summary.Removed != 1 || res.Run.Summary.Skipped != 1 {
		t.Errorf("summary = %+v (want removed=1 skipped=1)", res.Run.Summary)
	}
}

// recheck-identical-candidate: a plan move for a node that the fresh re-analysis no
// longer lists (here, because the Input has no such node at all) is dropped, not
// forced.
func TestConsolidatorRecheckDropsDriftedCandidate(t *testing.T) {
	c := fake.NewSimpleClientset(
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	// A hand-built plan that claims a phantom node-a as an EMPTY move. The fresh
	// re-analysis over the real Input has no such move, so the recheck drops it.
	plan := consolidation.Plan{
		Actor: consolidation.Actor{Kind: consolidation.ActorNone},
		Moves: []consolidation.Move{{
			Type: consolidation.MoveEmpty,
			Node: consolidation.NodeRef{Name: "node-a", NodeGroup: "ng-a", ProviderID: "aws:///us-east-1a/i-0aaa"},
			Pods: []consolidation.PodMove{},
		}},
	}

	fakeProv := removal.NewFakeProvider()
	fakeProv.Facts["i-0aaa"] = facts("i-0aaa", "asg-a", 1, 3)
	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Live, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if len(fakeProv.Terminations()) != 0 {
		t.Fatalf("drifted candidate must not terminate, got %+v", fakeProv.Terminations())
	}
	nr, _ := findNodeRun(res.Run, "node-a")
	if nr.Status != consolidation.RunStatusSkipped {
		t.Fatalf("node-a run = %+v (want skipped)", nr)
	}
	assertOnlyReads(t, c)
}

// Off gate: no action, no provider call, empty run.
func TestConsolidatorOffGateDoesNothing(t *testing.T) {
	c := fake.NewSimpleClientset(
		consolNode("node-a", "ng-a", "aws:///us-east-1a/i-0aaa", 20*time.Minute),
		consolNode("node-b", "ng-a", "aws:///us-east-1a/i-0bbb", 0),
		daemonSetPod("aws-node-a", "node-a"), busyPod("busy-b", "node-b"),
	)
	in := collectInput(t, c)
	plan := consolidation.Analyze(in, consolOpts(), fixedNow)
	fakeProv := removal.NewFakeProvider()
	cons := NewConsolidator(fakeProv, fitExec(c), nil)
	res, err := cons.Execute(context.Background(), in, plan, gate.Off, consolOpts(), consolPolicy(),
		drain.NewBudget(1, nil), fixedNow)
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if len(fakeProv.Terminations()) != 0 || len(res.Run.Nodes) != 0 || len(res.Actions) != 0 {
		t.Fatalf("off gate acted: terminations=%v run=%+v actions=%d", fakeProv.Terminations(), res.Run.Nodes, len(res.Actions))
	}
	assertOnlyReads(t, c)
}

func assertPlanHasEmpty(t *testing.T, plan consolidation.Plan, name string) {
	t.Helper()
	for _, m := range plan.Moves {
		if m.Node.Name == name && m.Type == consolidation.MoveEmpty {
			return
		}
	}
	t.Fatalf("plan has no EMPTY move for %s; moves=%+v blocked=%+v kept=%+v", name, plan.Moves, plan.Blocked, plan.Kept)
}
