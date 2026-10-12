package drain

import (
	"context"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

var podsGVR = corev1.SchemeGroupVersion.WithResource("pods")

func node(name string) *corev1.Node {
	return &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: name}}
}

func boolPtr(b bool) *bool { return &b }

func rsPod(ns, name, uid, nodeName string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: ns, UID: types.UID(uid),
			OwnerReferences: []metav1.OwnerReference{{Kind: "ReplicaSet", Name: "web-rs", UID: "rs-uid", Controller: boolPtr(true)}},
		},
		Spec: corev1.PodSpec{NodeName: nodeName},
		Status: corev1.PodStatus{
			Phase:      corev1.PodRunning,
			Conditions: []corev1.PodCondition{{Type: corev1.PodReady, Status: corev1.ConditionTrue}},
		},
	}
}

func dsPod(name, nodeName string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: "kube-system", UID: types.UID("uid-" + name),
			OwnerReferences: []metav1.OwnerReference{{Kind: "DaemonSet", Name: "aws-node", UID: "ds-uid", Controller: boolPtr(true)}},
		},
		Spec:   corev1.PodSpec{NodeName: nodeName},
		Status: corev1.PodStatus{Phase: corev1.PodRunning},
	}
}

type staticPlanner struct{ plan Plan }

func (s staticPlanner) Plan(_ context.Context, nodes []string) (Plan, error) {
	p := s.plan
	p.Nodes = nodes
	return p, nil
}

func drainablePlan() Plan {
	return Plan{Drainable: true, FitChecked: true, Moves: []Move{{
		Namespace: "default", Name: "web-1", UID: "uid-web-1",
		OwnerKind: "ReplicaSet", OwnerName: "web-rs", OwnerUID: "rs-uid", From: "node-a", To: "node-b",
	}}}
}

func seeded() *fake.Clientset {
	return fake.NewSimpleClientset(
		node("node-a"), node("node-b"),
		rsPod("default", "web-1", "uid-web-1", "node-a"),
		rsPod("default", "web-0", "uid-web-0", "node-b"), // pre-existing Running+Ready sibling
		dsPod("aws-node-a", "node-a"),
	)
}

// evictionReactor emulates the API server: on an eviction it deletes the pod and,
// when replace is true, creates a replacement with a NEW UID Running+Ready on node-b.
// It works on the tracker directly (calling the clientset from a reactor deadlocks).
func evictionReactor(c *fake.Clientset, replace bool) {
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
		old := obj.(*corev1.Pod)
		if err := c.Tracker().Delete(podsGVR, ev.Namespace, ev.Name); err != nil {
			return true, nil, err
		}
		if replace {
			i := atomic.AddInt32(&n, 1)
			repl := rsPod(old.Namespace, fmt.Sprintf("web-new-%d", i), fmt.Sprintf("uid-new-%d", i), "node-b")
			if err := c.Tracker().Create(podsGVR, repl, old.Namespace); err != nil {
				return true, nil, err
			}
		}
		return true, nil, nil
	})
}

func fastExecutor(c *fake.Clientset, p Planner) *Executor {
	e := NewExecutor(c, p, "xamops-system")
	e.ReplacementTimeout = 150 * time.Millisecond
	e.PDBRetry = 60 * time.Millisecond
	e.PollInterval = 10 * time.Millisecond
	return e
}

func verbs(c *fake.Clientset) (writes []string) {
	for _, a := range c.Actions() {
		switch a.GetVerb() {
		case "get", "list", "watch":
		default:
			s := a.GetVerb() + " " + a.GetResource().Resource
			if a.GetSubresource() != "" {
				s += "/" + a.GetSubresource()
			}
			writes = append(writes, s)
		}
	}
	return writes
}

func assertNoDelete(t *testing.T, c *fake.Clientset) {
	t.Helper()
	for _, a := range c.Actions() {
		if a.GetVerb() == "delete" || a.GetVerb() == "deletecollection" {
			t.Errorf("unexpected %s %s", a.GetVerb(), a.GetResource().Resource)
		}
	}
}

func getNode(t *testing.T, c *fake.Clientset, name string) *corev1.Node {
	t.Helper()
	n, err := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func TestSimulateWritesNothing(t *testing.T) {
	c := seeded()
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "rebalancer", "gen-1", []string{"node-a"}, gate.Simulate, NewBudget(1, nil))

	if out.Status != policy.ActionSimulated {
		t.Fatalf("status = %s, want simulated (%+v)", out.Status, out.Actions)
	}
	if !out.Actions[0].DryRun || out.Actions[0].Verified {
		t.Errorf("simulated record must be dryRun=true verified=false: %+v", out.Actions[0])
	}
	if out.Actions[0].Details["fitChecked"] != true {
		t.Errorf("details.fitChecked = %v, want true from the plan", out.Actions[0].Details["fitChecked"])
	}
	if !out.Reports[0].DryRun || out.Reports[0].Status != StatusSimulated {
		t.Errorf("report = %+v", out.Reports[0])
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("simulate issued writes: %v", w)
	}
}

func TestLiveUnavailablePlannerRefusesWithoutWrites(t *testing.T) {
	for _, mode := range []gate.Mode{gate.Live, gate.Simulate} {
		c := seeded()
		e := fastExecutor(c, UnavailablePlanner{})
		b := NewBudget(1, nil)
		out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, mode, b)
		if out.Status != policy.ActionRefused {
			t.Fatalf("%s: status = %s, want refused", mode, out.Status)
		}
		if out.Actions[0].Reason != UnavailableReason {
			t.Errorf("%s: reason = %q", mode, out.Actions[0].Reason)
		}
		if out.Actions[0].Details["fitChecked"] != false {
			t.Errorf("%s: fitChecked must be false", mode)
		}
		if out.Actions[0].DryRun != (mode == gate.Simulate) {
			t.Errorf("%s: dryRun = %v", mode, out.Actions[0].DryRun)
		}
		if w := verbs(c); len(w) != 0 {
			t.Errorf("%s: refused drain issued writes: %v", mode, w)
		}
		if b.Remaining() != 1 {
			t.Errorf("%s: a refused drain must release its budget slot, remaining=%d", mode, b.Remaining())
		}
	}
}

func TestLiveDrainVerifiesNewUIDReplacement(t *testing.T) {
	c := seeded()
	evictionReactor(c, true)
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "evictor", "evictor-op-1", []string{"node-a"}, gate.Live, NewBudget(1, nil))

	if out.Status != policy.ActionSucceeded || !out.Verified() {
		t.Fatalf("status = %s verified=%v reason=%q", out.Status, out.Verified(), out.Actions[0].Reason)
	}
	if out.Actions[0].DryRun {
		t.Error("live record must have dryRun=false")
	}
	if out.Reports[0].Status != StatusDrained || !out.Reports[0].Verified || out.Reports[0].PodsEvicted != 1 {
		t.Errorf("report = %+v", out.Reports[0])
	}
	n := getNode(t, c, "node-a")
	if !n.Spec.Unschedulable {
		t.Error("drained node must stay cordoned")
	}
	if n.Annotations[AnnotationState] != StateDrained || n.Annotations[AnnotationOperation] != "evictor-op-1" {
		t.Errorf("annotations = %v", n.Annotations)
	}
	assertNoDelete(t, c)
	for _, w := range verbs(c) {
		if w != "patch nodes" && w != "create pods/eviction" {
			t.Errorf("unexpected write %q", w)
		}
	}
}

// A pre-existing Running+Ready sibling (web-0 on node-b) must not count as the
// replacement: no new UID appears, so the drain aborts and the node is uncordoned.
func TestLiveDrainSiblingIsNotAReplacementAndAbortUncordons(t *testing.T) {
	c := seeded()
	evictionReactor(c, false)
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))

	if out.Status != policy.ActionAborted {
		t.Fatalf("status = %s, want aborted", out.Status)
	}
	if !strings.Contains(out.Actions[0].Reason, "no replacement") {
		t.Errorf("reason = %q", out.Actions[0].Reason)
	}
	n := getNode(t, c, "node-a")
	if n.Spec.Unschedulable {
		t.Error("aborted drain must uncordon the node it cordoned")
	}
	if n.Annotations[AnnotationState] != StateAborted {
		t.Errorf("drain-state = %q, want aborted", n.Annotations[AnnotationState])
	}
	if _, ok := n.Annotations[AnnotationCordonedBy]; ok {
		t.Error("cordoned-by marker must be removed on uncordon")
	}
	assertNoDelete(t, c)
}

// A node that was already cordoned by someone else stays cordoned after an abort.
func TestAbortLeavesForeignCordon(t *testing.T) {
	c := seeded()
	n := getNode(t, c, "node-a")
	n.Spec.Unschedulable = true
	if _, err := c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{}); err != nil {
		t.Fatal(err)
	}
	evictionReactor(c, false)
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionAborted {
		t.Fatalf("status = %s", out.Status)
	}
	if !getNode(t, c, "node-a").Spec.Unschedulable {
		t.Error("a cordon this autoscaler did not make must not be undone")
	}
}

func TestEviction429BeyondPDBRetryAborts(t *testing.T) {
	c := seeded()
	var calls int32
	c.PrependReactor("create", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if action.GetSubresource() != "eviction" {
			return false, nil, nil
		}
		atomic.AddInt32(&calls, 1)
		return true, nil, apierrors.NewTooManyRequests("Cannot evict pod as it would violate the pod's disruption budget.", 1)
	})
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))

	if out.Status != policy.ActionAborted {
		t.Fatalf("status = %s, want aborted", out.Status)
	}
	if !strings.Contains(out.Actions[0].Reason, "disruption budget still blocks") {
		t.Errorf("reason = %q", out.Actions[0].Reason)
	}
	if atomic.LoadInt32(&calls) < 2 {
		t.Errorf("expected retries, got %d eviction calls", calls)
	}
	if getNode(t, c, "node-a").Spec.Unschedulable {
		t.Error("node must be uncordoned after abort")
	}
	assertNoDelete(t, c)
}

func TestBudget(t *testing.T) {
	b := NewBudget(1, nil)
	if !b.TryReserve("rebalancer", "node-a") {
		t.Fatal("first reservation refused")
	}
	if !b.TryReserve("rebalancer", "node-a") {
		t.Error("re-reserving the same node for the same controller must be idempotent")
	}
	if b.TryReserve("evictor", "node-b") {
		t.Error("capacity 1 must refuse a second reservation")
	}
	if b.TryReserve("evictor", "node-a") {
		t.Error("a node reserved by another controller must be refused")
	}
	if b.Remaining() != 0 {
		t.Errorf("remaining = %d", b.Remaining())
	}

	// An in-flight (annotated draining) node consumes capacity.
	nodes := []corev1.Node{*node("node-a"), *node("node-x")}
	nodes[1].Annotations = map[string]string{AnnotationState: StateDraining}
	inflight := InflightNodes(nodes)
	if len(inflight) != 1 || inflight[0] != "node-x" {
		t.Fatalf("inflight = %v", inflight)
	}
	b2 := NewBudget(1, inflight)
	if b2.TryReserve("evictor", "node-a") {
		t.Error("in-flight node must consume the only slot")
	}
	if NewBudget(0, nil).Capacity() != 1 {
		t.Error("capacity <= 0 must mean 1")
	}

	// The executor itself refuses when the budget is exhausted.
	c := seeded()
	e := fastExecutor(c, staticPlanner{drainablePlan()})
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Simulate, b2)
	if out.Status != policy.ActionSkipped || !strings.Contains(out.Actions[0].Reason, "budget exhausted") {
		t.Errorf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
}

func TestSelfNamespaceMoveRefused(t *testing.T) {
	c := seeded()
	plan := drainablePlan()
	plan.Moves[0].Namespace = "xamops-system"
	e := fastExecutor(c, staticPlanner{plan})
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionRefused || !strings.Contains(out.Actions[0].Reason, "own namespace") {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}

	// A self-namespace pod on the node is refused even if the planner omitted it.
	c2 := seeded()
	if err := c2.Tracker().Create(podsGVR, rsPod("xamops-system", "xamops-automation-1", "uid-self", "node-a"), "xamops-system"); err != nil {
		t.Fatal(err)
	}
	out = fastExecutor(c2, staticPlanner{drainablePlan()}).Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionRefused || !strings.Contains(out.Actions[0].Reason, "own namespace") {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if w := verbs(c2); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

func TestCordonOnly(t *testing.T) {
	c := seeded()
	out := fastExecutor(c, UnavailablePlanner{}).CordonOnly(context.Background(), "node-optimizer", "op", "node-a", gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionRefused {
		t.Fatalf("UnavailablePlanner cordon: status = %s", out.Status)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}

	c2 := seeded()
	out = fastExecutor(c2, staticPlanner{drainablePlan()}).CordonOnly(context.Background(), "node-optimizer", "op", "node-a", gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionSucceeded || !out.Verified() {
		t.Fatalf("status = %s verified=%v", out.Status, out.Verified())
	}
	n := getNode(t, c2, "node-a")
	if !n.Spec.Unschedulable || n.Annotations[AnnotationState] != StateCordoned {
		t.Errorf("node = %+v %v", n.Spec, n.Annotations)
	}
}

func orphanClient() *fake.Clientset {
	n := node("node-a")
	n.Spec.Unschedulable = true
	n.Annotations = map[string]string{AnnotationState: StateDraining, AnnotationOperation: "gen-3", AnnotationCordonedBy: CordonedByXamops}
	return fake.NewSimpleClientset(n, node("node-b"))
}

func TestRecoverOrphans(t *testing.T) {
	c := orphanClient()
	recs := fastExecutor(c, UnavailablePlanner{}).RecoverOrphans(context.Background(), gate.Simulate)
	if len(recs) != 1 || recs[0].Status != policy.ActionSimulated || !recs[0].DryRun {
		t.Fatalf("simulate records = %+v", recs)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("simulate recovery wrote: %v", w)
	}
	if !getNode(t, c, "node-a").Spec.Unschedulable {
		t.Error("simulate must leave the node cordoned")
	}

	c2 := orphanClient()
	recs = fastExecutor(c2, UnavailablePlanner{}).RecoverOrphans(context.Background(), gate.Live)
	if len(recs) != 1 || recs[0].Status != policy.ActionSucceeded || !recs[0].Verified || recs[0].OperationID != "gen-3" {
		t.Fatalf("live records = %+v", recs)
	}
	n := getNode(t, c2, "node-a")
	if n.Spec.Unschedulable || n.Annotations[AnnotationState] != StateAborted {
		t.Errorf("orphan not recovered: unschedulable=%v annotations=%v", n.Spec.Unschedulable, n.Annotations)
	}

	if recs := fastExecutor(orphanClient(), UnavailablePlanner{}).RecoverOrphans(context.Background(), gate.Off); recs != nil {
		t.Errorf("Off must do nothing, got %+v", recs)
	}
}
