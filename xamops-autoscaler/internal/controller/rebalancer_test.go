package controller

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/state"
)

const selfNS, ledgerName = "xamops-system", "xamops-automation-state"

// countingPlanner wraps the fitsim-backed planner and counts Plan calls (it hides
// SelectSet, so the executor's greedy selection goes through Plan too).
type countingPlanner struct {
	inner drain.Planner
	mu    sync.Mutex
	calls int
	nodes [][]string
}

func (p *countingPlanner) Plan(ctx context.Context, nodes []string) (drain.Plan, error) {
	p.mu.Lock()
	p.calls++
	p.nodes = append(p.nodes, append([]string(nil), nodes...))
	p.mu.Unlock()
	return p.inner.Plan(ctx, nodes)
}

func fitCounting(c *fake.Clientset) *countingPlanner {
	return &countingPlanner{inner: drain.NewFitPlanner(c, fitsim.Options{Guards: policy.DefaultBundle().SafetyGuards, SelfNamespace: selfNS})}
}

func rebalancerNode(name string, age time.Duration, labels map[string]string) *corev1.Node {
	n := drainableNode(name)
	n.Labels = labels
	n.CreationTimestamp = metav1.NewTime(time.Now().Add(-age))
	return n
}

func rebalancerNodes() []runtime.Object {
	return []runtime.Object{
		rebalancerNode("node-young", 1*time.Hour, nil),
		rebalancerNode("node-old", 48*time.Hour, nil),
		rebalancerNode("node-spot", 72*time.Hour, map[string]string{"eks.amazonaws.com/capacityType": "SPOT"}),
		rebalancerNode("node-cp", 96*time.Hour, map[string]string{"node-role.kubernetes.io/control-plane": ""}),
		demoRS(),
	}
}

// blockedCluster: both rebalancer candidates (node-old, node-young) host a
// kube-system pod, a protected namespace — no drain set is possible.
func blockedCluster() *fake.Clientset {
	objs := rebalancerNodes()
	for _, n := range []string{"node-old", "node-young"} {
		p := evictablePod("dns-"+n, n)
		p.Namespace = "kube-system"
		objs = append(objs, p)
	}
	return fake.NewSimpleClientset(objs...)
}

// drainableCluster: node-old and node-young each host one movable pod; the spot and
// control-plane nodes (not candidates, but schedulable targets) have room.
func drainableCluster() *fake.Clientset {
	objs := append(rebalancerNodes(), evictablePod("w-old", "node-old"), evictablePod("w-young", "node-young"))
	return fake.NewSimpleClientset(objs...)
}

func runningCfg(opID string) policy.RebalancerConfig {
	return policy.RebalancerConfig{Enabled: true, Status: "running", OperationID: opID, DrainTimeoutMinutes: 1}
}

func runOnce(t *testing.T, rb *Rebalancer, c *fake.Clientset, planner drain.Planner, cfg policy.RebalancerConfig, mode gate.Mode, capacity int, readOnly bool) (*RebalanceResult, []policy.ActionRecord) {
	t.Helper()
	exec := drain.NewExecutor(c, planner, selfNS)
	exec.ReplacementTimeout, exec.PDBRetry, exec.PollInterval = 300*time.Millisecond, 50*time.Millisecond, 10*time.Millisecond
	ledger := state.New(c, selfNS, ledgerName, readOnly)
	res, recs, err := rb.Run(context.Background(), cfg, policy.DefaultBundle().SafetyGuards,
		gate.Decision{Mode: mode}, drain.NewBudget(capacity, nil), exec, ledger)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	return res, recs
}

func TestRebalancerOffReturnsNil(t *testing.T) {
	c := blockedCluster()
	p := fitCounting(c)
	res, recs := runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Off, 1, false)
	if res != nil || recs != nil || p.calls != 0 {
		t.Fatalf("Off must do nothing: res=%+v recs=%+v calls=%d", res, recs, p.calls)
	}
	if len(c.Actions()) != 0 {
		t.Errorf("Off touched the API: %v", c.Actions())
	}
}

// No candidate set is drainable (both host a protected-namespace pod), so the fit
// simulation proves no net node reduction. The FEAT-005 benefit guard refuses to
// start BEFORE any ledger "running" write: the refusal is NOT terminal (nothing is
// recorded live), so it is re-evaluated — and honestly re-refused — every cycle.
func TestRebalancerBenefitGuardRefusesWhenNoReduction(t *testing.T) {
	c := blockedCluster()
	p := fitCounting(c)
	rb := NewRebalancer(c, false)

	res, recs := runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
	if res.Status != policy.ActionRefused || res.OperationID != "gen-1" || res.DryRun {
		t.Fatalf("first run = %+v", res)
	}
	if len(recs) != 0 {
		t.Fatalf("the benefit guard refuses before any drain record: %+v", recs)
	}
	if !strings.Contains(res.Reason, "no net node reduction achievable") {
		t.Errorf("result reason must be the honest no-benefit reason: %q", res.Reason)
	}
	// Nothing was written to the ledger as running; the operation is not terminal.
	op, ok, err := state.New(c, selfNS, ledgerName, true).Get(context.Background(), "gen-1")
	if err != nil {
		t.Fatalf("ledger read: %v", err)
	}
	if ok {
		t.Errorf("the guard must write nothing to the ledger, got %+v", op)
	}

	// Every cycle re-evaluates the guard (not terminal): the planner runs again and
	// the refusal is re-reported, still with no ledger write and no drain record.
	for i := 0; i < 3; i++ {
		res, recs = runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
		if len(recs) != 0 || res.Status != policy.ActionRefused {
			t.Fatalf("re-run %d: status=%s recs=%+v", i, res.Status, recs)
		}
		if !strings.Contains(res.Reason, "no net node reduction achievable") {
			t.Errorf("re-run %d reason = %q", i, res.Reason)
		}
	}
	if p.calls == 0 {
		t.Error("the benefit guard must run the fit simulation")
	}
	for _, n := range []string{"node-old", "node-young"} {
		if node, _ := c.CoreV1().Nodes().Get(context.Background(), n, metav1.GetOptions{}); node.Spec.Unschedulable {
			t.Errorf("%s cordoned by a refused operation", n)
		}
	}
	assertNoPodDelete(t, c)
}

// A terminal live outcome persists across a process restart: the completed operation
// is read from the ledger and never re-run (the benefit guard is not even reached).
func TestRebalancerRestartPersistence(t *testing.T) {
	c := drainableCluster()
	replaceOnEviction(c, "node-spot")
	p := fitCounting(c)
	res, _ := runOnce(t, NewRebalancer(c, false), c, p, runningCfg("op-persist"), gate.Live, 1, false)
	if res.Status != state.StateCompleted {
		t.Fatalf("first run = %+v", res)
	}
	first := p.calls

	// New process: new Rebalancer, new Ledger, same API.
	res, recs := runOnce(t, NewRebalancer(c, false), c, p, runningCfg("op-persist"), gate.Live, 1, false)
	if len(recs) != 0 || p.calls != first {
		t.Fatalf("operation re-run after restart: calls=%d/%d recs=%+v", p.calls, first, recs)
	}
	if res.OperationID != "op-persist" || res.Status != state.StateCompleted {
		t.Errorf("after restart = %+v", res)
	}
}

// A terminal operation is never re-run under its own id, but a NEW operation id is a
// fresh operation and is evaluated (and here drained) again.
func TestRebalancerNewOperationIDRunsAgain(t *testing.T) {
	c := drainableCluster()
	replaceOnEviction(c, "node-spot")
	p := fitCounting(c)
	rb := NewRebalancer(c, false)
	if res, _ := runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false); res.Status != state.StateCompleted {
		t.Fatalf("op-a first run = %+v", res)
	}
	first := p.calls
	// op-a is terminal: re-running it does not touch the planner.
	if _, recs := runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false); len(recs) != 0 || p.calls != first {
		t.Fatalf("op-a re-run: calls=%d/%d recs=%+v", p.calls, first, recs)
	}
	// op-b is a new operation: it is evaluated afresh (planner runs again).
	res, _ := runOnce(t, rb, c, p, runningCfg("op-b"), gate.Live, 1, false)
	if p.calls <= first || res.OperationID != "op-b" || res.Status != state.StateCompleted {
		t.Errorf("op-b: calls=%d (first %d) res=%+v", p.calls, first, res)
	}
}

func TestRebalancerDryRunSimulatesOnceAndWritesNothing(t *testing.T) {
	c := drainableCluster()
	p := fitCounting(c)
	rb := NewRebalancer(c, true)

	res, recs := runOnce(t, rb, c, p, runningCfg(""), gate.Simulate, 1, true)
	if res.Status != policy.ActionSimulated || !res.DryRun || res.NodesSimulated != 1 || len(res.NodesDrained) != 0 || res.Verified {
		t.Fatalf("dry run = %+v", res)
	}
	if len(recs) != 2 || recs[0].Status != policy.ActionSimulated || recs[0].Target != "node/node-old" || !recs[0].DryRun {
		t.Fatalf("records = %+v", recs)
	}
	if recs[1].Status != policy.ActionSkipped || !strings.Contains(recs[1].Reason, "budget exhausted") {
		t.Errorf("node-young beyond the budget = %+v", recs[1])
	}
	calls := p.calls
	res, recs = runOnce(t, rb, c, p, runningCfg(""), gate.Simulate, 1, true)
	if len(recs) != 0 || res.Status != policy.ActionSimulated {
		t.Errorf("second dry-run cycle re-simulated: %+v %+v", res, recs)
	}
	if p.calls != calls {
		t.Errorf("planned again: %d -> %d", calls, p.calls)
	}
	assertOnlyReads(t, c)
}

// With the fail-closed planner no set is drainable, so the benefit guard refuses in
// Simulate too (honest "refused: no benefit"): the dry run reports status simulated,
// nothing simulated as drainable, and no drain records.
func TestRebalancerDryRunUnavailablePlanner(t *testing.T) {
	c := drainableCluster()
	res, recs := runOnce(t, NewRebalancer(c, true), c, drain.UnavailablePlanner{}, runningCfg(""), gate.Simulate, 1, true)
	if res.Status != policy.ActionSimulated || res.NodesSimulated != 0 || !res.DryRun {
		t.Fatalf("res = %+v", res)
	}
	if len(recs) != 0 {
		t.Fatalf("the benefit guard refuses before any drain record: %+v", recs)
	}
	if !strings.Contains(res.Reason, "no net node reduction achievable") {
		t.Errorf("reason = %q", res.Reason)
	}
	assertOnlyReads(t, c)
}

// Capacity 1: one node simulated, the other skipped. Capacity 5: both nodes form
// one set (their union fits on the spot/control-plane nodes), simulated as one drain.
func TestRebalancerBudgetLimitsNodesPerRun(t *testing.T) {
	c := drainableCluster()
	_, recs := runOnce(t, NewRebalancer(c, true), c, fitCounting(c), runningCfg(""), gate.Simulate, 1, true)
	if len(recs) != 2 || recs[0].Target != "node/node-old" || recs[0].Status != policy.ActionSimulated {
		t.Errorf("capacity 1: %+v", recs)
	}

	c2 := drainableCluster()
	res, recs := runOnce(t, NewRebalancer(c2, true), c2, fitCounting(c2), runningCfg(""), gate.Simulate, 5, true)
	if len(recs) != 1 || recs[0].Target != "nodes/node-old,node-young" || recs[0].Status != policy.ActionSimulated || res.NodesSimulated != 2 {
		t.Errorf("capacity 5: res=%+v recs=%+v", res, recs)
	}
}

// Live with the simulator: the selected node is drained and verified, the operation
// is completed in the ledger, the node beyond the budget is untouched.
func TestRebalancerLiveDrainCompletes(t *testing.T) {
	c := drainableCluster()
	replaceOnEviction(c, "node-spot")
	rb := NewRebalancer(c, false)
	res, recs := runOnce(t, rb, c, fitCounting(c), runningCfg("op-live"), gate.Live, 1, false)
	if res.Status != state.StateCompleted || !res.Verified || len(res.NodesDrained) != 1 || res.NodesDrained[0] != "node-old" || res.PodsEvicted != 1 {
		t.Fatalf("res = %+v recs = %+v", res, recs)
	}
	op, ok, err := state.New(c, selfNS, ledgerName, true).Get(context.Background(), "op-live")
	if err != nil || !ok || op.State != state.StateCompleted {
		t.Errorf("ledger = %+v ok=%v err=%v", op, ok, err)
	}
	if n, _ := c.CoreV1().Nodes().Get(context.Background(), "node-young", metav1.GetOptions{}); n.Spec.Unschedulable {
		t.Error("node-young is beyond the budget and must stay schedulable")
	}
	assertNoPodDelete(t, c)
}
