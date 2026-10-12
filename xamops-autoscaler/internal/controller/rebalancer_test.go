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

// The simulator refuses both candidates (protected namespace); the refusal is
// terminal and never re-planned.
func TestRebalancerSameOperationRunsOnce(t *testing.T) {
	c := blockedCluster()
	p := fitCounting(c)
	rb := NewRebalancer(c, false)

	res, recs := runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
	if res.Status != state.StateRefused || res.OperationID != "gen-1" || res.DryRun {
		t.Fatalf("first run = %+v", res)
	}
	if len(recs) != 2 || recs[0].Target != "node/node-old" || recs[1].Target != "node/node-young" {
		t.Fatalf("expected one refusal per candidate, oldest first, got %+v", recs)
	}
	for _, r := range recs {
		if r.Status != policy.ActionRefused || !strings.Contains(r.Reason, "critical-namespace") {
			t.Errorf("record = %+v", r)
		}
	}
	if !strings.Contains(res.Reason, "critical-namespace") {
		t.Errorf("result reason must carry the plan reasons: %q", res.Reason)
	}
	first := p.calls

	for i := 0; i < 3; i++ {
		res, recs = runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
		if len(recs) != 0 || res.Status != state.StateRefused {
			t.Fatalf("re-run %d: status=%s recs=%+v", i, res.Status, recs)
		}
		if res.Reason != "operation gen-1 already refused; not re-run" {
			t.Errorf("reason = %q", res.Reason)
		}
	}
	if p.calls != first || first == 0 {
		t.Errorf("planner calls %d after re-runs, %d after the first run", p.calls, first)
	}
	for _, n := range []string{"node-old", "node-young"} {
		if node, _ := c.CoreV1().Nodes().Get(context.Background(), n, metav1.GetOptions{}); node.Spec.Unschedulable {
			t.Errorf("%s cordoned by a refused operation", n)
		}
	}
	assertNoPodDelete(t, c)
}

func TestRebalancerRestartPersistence(t *testing.T) {
	c := blockedCluster()
	p := fitCounting(c)
	runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Live, 1, false)
	first := p.calls

	// New process: new Rebalancer, new Ledger, same API.
	res, recs := runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Live, 1, false)
	if len(recs) != 0 || p.calls != first {
		t.Fatalf("operation re-run after restart: calls=%d/%d recs=%+v", p.calls, first, recs)
	}
	if res.OperationID != "gen-1" || res.Status != state.StateRefused {
		t.Errorf("after restart = %+v", res)
	}
}

func TestRebalancerNewOperationIDRunsAgain(t *testing.T) {
	c := blockedCluster()
	p := fitCounting(c)
	rb := NewRebalancer(c, false)
	runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false)
	first := p.calls
	runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false)
	res, recs := runOnce(t, rb, c, p, runningCfg("op-b"), gate.Live, 1, false)
	if p.calls != 2*first || len(recs) != 2 || res.OperationID != "op-b" {
		t.Errorf("calls=%d (first %d) recs=%d res=%+v", p.calls, first, len(recs), res)
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

// With the fail-closed planner a dry run still reports status simulated, but nothing
// was simulated as drainable.
func TestRebalancerDryRunUnavailablePlanner(t *testing.T) {
	c := drainableCluster()
	res, recs := runOnce(t, NewRebalancer(c, true), c, drain.UnavailablePlanner{}, runningCfg(""), gate.Simulate, 1, true)
	if res.Status != policy.ActionSimulated || res.NodesSimulated != 0 || !res.DryRun {
		t.Fatalf("res = %+v", res)
	}
	if len(recs) != 2 || recs[0].Status != policy.ActionRefused || !recs[0].DryRun {
		t.Fatalf("recs = %+v", recs)
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
