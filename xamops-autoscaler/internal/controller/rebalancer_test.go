package controller

import (
	"context"
	"sync"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/state"
)

const selfNS, ledgerName = "xamops-system", "xamops-automation-state"

// countingPlanner counts Plan calls and returns a fixed verdict.
type countingPlanner struct {
	mu        sync.Mutex
	calls     int
	nodes     [][]string
	drainable bool
}

func (p *countingPlanner) Plan(_ context.Context, nodes []string) (drain.Plan, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls++
	p.nodes = append(p.nodes, nodes)
	if !p.drainable {
		return drain.Plan{Nodes: nodes, Reasons: []string{"test planner: not drainable"}}, nil
	}
	return drain.Plan{Nodes: nodes, Drainable: true, FitChecked: true}, nil
}

func rebalancerNode(name string, age time.Duration, labels map[string]string) *corev1.Node {
	return &corev1.Node{ObjectMeta: metav1.ObjectMeta{
		Name: name, Labels: labels, CreationTimestamp: metav1.NewTime(time.Now().Add(-age)),
	}}
}

func rebalancerCluster() *fake.Clientset {
	return fake.NewSimpleClientset(
		rebalancerNode("node-young", 1*time.Hour, nil),
		rebalancerNode("node-old", 48*time.Hour, nil),
		rebalancerNode("node-spot", 72*time.Hour, map[string]string{"eks.amazonaws.com/capacityType": "SPOT"}),
		rebalancerNode("node-cp", 96*time.Hour, map[string]string{"node-role.kubernetes.io/control-plane": ""}),
	)
}

func runningCfg(opID string) policy.RebalancerConfig {
	return policy.RebalancerConfig{Enabled: true, Status: "running", OperationID: opID, DrainTimeoutMinutes: 1}
}

func runOnce(t *testing.T, rb *Rebalancer, c *fake.Clientset, planner drain.Planner, cfg policy.RebalancerConfig, mode gate.Mode, capacity int, readOnly bool) (*RebalanceResult, []policy.ActionRecord) {
	t.Helper()
	exec := drain.NewExecutor(c, planner, selfNS)
	ledger := state.New(c, selfNS, ledgerName, readOnly)
	res, recs, err := rb.Run(context.Background(), cfg, policy.DefaultBundle().SafetyGuards,
		gate.Decision{Mode: mode}, drain.NewBudget(capacity, nil), exec, ledger)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	return res, recs
}

func TestRebalancerOffReturnsNil(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{}
	res, recs := runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Off, 1, false)
	if res != nil || recs != nil || p.calls != 0 {
		t.Fatalf("Off must do nothing: res=%+v recs=%+v calls=%d", res, recs, p.calls)
	}
	if len(c.Actions()) != 0 {
		t.Errorf("Off touched the API: %v", c.Actions())
	}
}

func TestRebalancerSameOperationRunsOnce(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{}
	rb := NewRebalancer(c, false)

	res, recs := runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
	if res.Status != state.StateRefused || res.OperationID != "gen-1" || res.DryRun {
		t.Fatalf("first run = %+v", res)
	}
	if len(recs) != 1 || recs[0].Target != "node/node-old" {
		t.Fatalf("expected one drain of the oldest candidate, got %+v", recs)
	}

	for i := 0; i < 3; i++ {
		res, recs = runOnce(t, rb, c, p, runningCfg(""), gate.Live, 1, false)
		if len(recs) != 0 || res.Status != state.StateRefused {
			t.Fatalf("re-run %d: status=%s recs=%+v", i, res.Status, recs)
		}
		if res.Reason != "operation gen-1 already refused; not re-run" {
			t.Errorf("reason = %q", res.Reason)
		}
	}
	if p.calls != 1 {
		t.Errorf("executor planned %d times, want 1", p.calls)
	}
	assertNoPodDelete(t, c)
}

func TestRebalancerRestartPersistence(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{}
	runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Live, 1, false)

	// New process: new Rebalancer, new Ledger, same API.
	res, recs := runOnce(t, NewRebalancer(c, false), c, p, runningCfg(""), gate.Live, 1, false)
	if len(recs) != 0 || p.calls != 1 {
		t.Fatalf("operation re-run after restart: calls=%d recs=%+v", p.calls, recs)
	}
	if res.OperationID != "gen-1" || res.Status != state.StateRefused {
		t.Errorf("after restart = %+v", res)
	}
}

func TestRebalancerNewOperationIDRunsAgain(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{}
	rb := NewRebalancer(c, false)
	runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false)
	runOnce(t, rb, c, p, runningCfg("op-a"), gate.Live, 1, false)
	res, recs := runOnce(t, rb, c, p, runningCfg("op-b"), gate.Live, 1, false)
	if p.calls != 2 || len(recs) != 1 || res.OperationID != "op-b" {
		t.Errorf("calls=%d recs=%d res=%+v", p.calls, len(recs), res)
	}
}

func TestRebalancerDryRunSimulatesOnceAndWritesNothing(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{drainable: true}
	rb := NewRebalancer(c, true)

	res, recs := runOnce(t, rb, c, p, runningCfg(""), gate.Simulate, 1, true)
	if res.Status != policy.ActionSimulated || !res.DryRun || res.NodesSimulated != 1 || len(res.NodesDrained) != 0 || res.Verified {
		t.Fatalf("dry run = %+v", res)
	}
	if len(recs) != 1 || !recs[0].DryRun || recs[0].Status != policy.ActionSimulated {
		t.Fatalf("records = %+v", recs)
	}
	res, recs = runOnce(t, rb, c, p, runningCfg(""), gate.Simulate, 1, true)
	if len(recs) != 0 || res.Status != policy.ActionSimulated {
		t.Errorf("second dry-run cycle re-simulated: %+v %+v", res, recs)
	}
	if p.calls != 1 {
		t.Errorf("planned %d times, want 1", p.calls)
	}
	assertOnlyReads(t, c)
}

// With the interim planner a dry run still reports status simulated, but nothing
// was simulated as drainable.
func TestRebalancerDryRunUnavailablePlanner(t *testing.T) {
	c := rebalancerCluster()
	res, recs := runOnce(t, NewRebalancer(c, true), c, drain.UnavailablePlanner{}, runningCfg(""), gate.Simulate, 1, true)
	if res.Status != policy.ActionSimulated || res.NodesSimulated != 0 || !res.DryRun {
		t.Fatalf("res = %+v", res)
	}
	if len(recs) != 1 || recs[0].Status != policy.ActionRefused || !recs[0].DryRun {
		t.Fatalf("recs = %+v", recs)
	}
	assertOnlyReads(t, c)
}

func TestRebalancerBudgetLimitsNodesPerRun(t *testing.T) {
	c := rebalancerCluster()
	p := &countingPlanner{drainable: true}
	_, recs := runOnce(t, NewRebalancer(c, true), c, p, runningCfg(""), gate.Simulate, 1, true)
	if len(recs) != 1 || len(p.nodes) != 1 || len(p.nodes[0]) != 1 || p.nodes[0][0] != "node-old" {
		t.Errorf("capacity 1: recs=%d planned=%v", len(recs), p.nodes)
	}

	c2 := rebalancerCluster()
	p2 := &countingPlanner{drainable: true}
	_, recs = runOnce(t, NewRebalancer(c2, true), c2, p2, runningCfg(""), gate.Simulate, 5, true)
	// Only two eligible candidates (spot and control-plane excluded), oldest first.
	if len(recs) != 2 || p2.nodes[0][0] != "node-old" || p2.nodes[1][0] != "node-young" {
		t.Errorf("capacity 5: recs=%d planned=%v", len(recs), p2.nodes)
	}
}
