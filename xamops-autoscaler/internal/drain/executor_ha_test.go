package drain

import (
	"context"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

// Never-cordon/drain-self: a Drain whose node set contains the agent's own node is
// refused with a reason mentioning that node, in both Simulate and Live, and nothing
// is written.
func TestDrainRefusesAgentOwnNode(t *testing.T) {
	for _, mode := range []gate.Mode{gate.Simulate, gate.Live} {
		c := seeded()
		e := fastExecutor(c, staticPlanner{drainablePlan()})
		e.SetSelfNode("node-a") // agent runs on node-a
		out := e.Drain(context.Background(), "rebalancer", "op", []string{"node-a"}, mode, NewBudget(1, nil))
		if out.Status != policy.ActionRefused {
			t.Fatalf("%s: status = %s, want refused", mode, out.Status)
		}
		if !strings.Contains(out.Actions[0].Reason, "node-a") ||
			!strings.Contains(out.Actions[0].Reason, "agent") {
			t.Errorf("%s: refusal reason should mention the agent's own node: %q", mode, out.Actions[0].Reason)
		}
		if w := verbs(c); len(w) != 0 {
			t.Errorf("%s: refusal issued writes: %v", mode, w)
		}
	}
}

// Never-cordon-self also blocks CordonOnly (which shares prepare()).
func TestCordonOnlyRefusesAgentOwnNode(t *testing.T) {
	c := seeded()
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	e.SetSelfNode("node-a")
	out := e.CordonOnly(context.Background(), "node-optimizer", "op", "node-a", gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionRefused {
		t.Fatalf("status = %s, want refused", out.Status)
	}
	if !strings.Contains(out.Actions[0].Reason, "node-a") {
		t.Errorf("refusal reason should mention node-a: %q", out.Actions[0].Reason)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("refusal issued writes: %v", w)
	}
	// A different node is still cordonable (the invariant only guards the agent's node).
	out = e.CordonOnly(context.Background(), "node-optimizer", "op", "node-b", gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionSucceeded {
		t.Fatalf("cordon of a non-self node = %s, want succeeded (%+v)", out.Status, out.Actions)
	}
}

// A mixed set that includes the agent's own node is refused as a whole.
func TestDrainRefusesSetContainingAgentOwnNode(t *testing.T) {
	c := seeded()
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	e.SetSelfNode("node-b")
	out := e.Drain(context.Background(), "rebalancer", "op", []string{"node-a", "node-b"}, gate.Live, NewBudget(2, nil))
	if out.Status != policy.ActionRefused {
		t.Fatalf("status = %s, want refused", out.Status)
	}
	if !strings.Contains(out.Actions[0].Reason, "node-b") {
		t.Errorf("refusal reason should mention node-b: %q", out.Actions[0].Reason)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("refusal issued writes: %v", w)
	}
}

// xamopsCordonedNode returns a node cordoned by this autoscaler.
func xamopsCordonedNode(name string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{
			Name: name,
			Annotations: map[string]string{
				AnnotationCordonedBy: CordonedByXamops,
				AnnotationState:      StateCordoned,
				AnnotationOperation:  "op-" + name,
			},
		},
		Spec: corev1.NodeSpec{Unschedulable: true},
	}
}

// foreignCordonedNode returns a node cordoned by something else (operator / CA).
func foreignCordonedNode(name string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{Name: name},
		Spec:       corev1.NodeSpec{Unschedulable: true},
	}
}

func isUnschedulable(t *testing.T, c *fake.Clientset, name string) bool {
	t.Helper()
	n, err := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	return n.Spec.Unschedulable
}

// UncordonAllXamopsNodes (Live) uncordons only the XamOps-cordoned nodes, clears their
// cordoned-by + drain-state annotations, and leaves foreign-cordoned nodes untouched.
func TestUncordonAllXamopsNodesLive(t *testing.T) {
	c := fake.NewSimpleClientset(
		xamopsCordonedNode("x-1"),
		xamopsCordonedNode("x-2"),
		foreignCordonedNode("foreign"),
		node("schedulable"), // not cordoned at all
	)
	e := NewExecutor(c, nil, "xamops-system")
	recs := e.UncordonAllXamopsNodes(context.Background(), gate.Live)

	if len(recs) != 2 {
		t.Fatalf("records = %d, want 2 (only XamOps-cordoned nodes)", len(recs))
	}
	for _, r := range recs {
		if r.Status != policy.ActionSucceeded || !r.Verified {
			t.Errorf("record = %+v, want succeeded+verified", r)
		}
	}
	if isUnschedulable(t, c, "x-1") || isUnschedulable(t, c, "x-2") {
		t.Error("XamOps-cordoned nodes should be uncordoned")
	}
	if !isUnschedulable(t, c, "foreign") {
		t.Error("foreign-cordoned node must stay cordoned")
	}
	for _, name := range []string{"x-1", "x-2"} {
		n, _ := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
		if n.Annotations[AnnotationCordonedBy] != "" || n.Annotations[AnnotationState] != "" {
			t.Errorf("%s annotations not cleared: %v", name, n.Annotations)
		}
	}
}

// UncordonAllXamopsNodes (Simulate / DRY_RUN) reports only and writes nothing.
func TestUncordonAllXamopsNodesSimulateWritesNothing(t *testing.T) {
	c := fake.NewSimpleClientset(xamopsCordonedNode("x-1"), xamopsCordonedNode("x-2"))
	e := NewExecutor(c, nil, "xamops-system")
	recs := e.UncordonAllXamopsNodes(context.Background(), gate.Simulate)

	if len(recs) != 2 {
		t.Fatalf("records = %d, want 2", len(recs))
	}
	for _, r := range recs {
		if r.Status != policy.ActionSimulated || !r.DryRun || r.Verified {
			t.Errorf("record = %+v, want simulated dryRun=true verified=false", r)
		}
	}
	if !isUnschedulable(t, c, "x-1") || !isUnschedulable(t, c, "x-2") {
		t.Error("DRY_RUN must not uncordon any node")
	}
	for _, a := range c.Actions() {
		switch a.GetVerb() {
		case "patch", "update", "delete", "create":
			t.Errorf("DRY_RUN uncordon-all issued a write: %s %s", a.GetVerb(), a.GetResource().Resource)
		}
	}
}

// UncordonAllXamopsNodes never touches the agent's own node even if XamOps-cordoned.
func TestUncordonAllSkipsAgentOwnNode(t *testing.T) {
	c := fake.NewSimpleClientset(xamopsCordonedNode("self"), xamopsCordonedNode("other"))
	e := NewExecutor(c, nil, "xamops-system")
	e.SetSelfNode("self")
	recs := e.UncordonAllXamopsNodes(context.Background(), gate.Live)
	if len(recs) != 1 || !strings.Contains(recs[0].Target, "other") {
		t.Fatalf("records = %+v, want exactly one for node 'other'", recs)
	}
	if !isUnschedulable(t, c, "self") {
		t.Error("agent's own node must not be uncordoned by uncordon-all")
	}
}

// Other modes (Off/Analyse) do nothing.
func TestUncordonAllInactiveModes(t *testing.T) {
	c := fake.NewSimpleClientset(xamopsCordonedNode("x-1"))
	e := NewExecutor(c, nil, "xamops-system")
	for _, mode := range []gate.Mode{gate.Off, gate.Analyse} {
		if recs := e.UncordonAllXamopsNodes(context.Background(), mode); recs != nil {
			t.Errorf("mode %s should do nothing, got %+v", mode, recs)
		}
	}
}
