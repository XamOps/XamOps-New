package metrics

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestHandlerServesNamedMetrics registers the set, increments every collector, and
// asserts the seven design-named metrics appear in the /metrics output with their
// exact names.
func TestHandlerServesNamedMetrics(t *testing.T) {
	s := New()

	s.IncDrain("rebalancer", "succeeded")
	s.SetDrainBudgetInflight(2)
	s.IncDrainReplan("rebalancer")
	s.ObserveCycle(0.5)
	s.SetGateDecision("rebalancer", "simulate")
	s.IncPolicyFetchError()
	s.IncRightsizerApply("simulated")

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()

	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	out := string(body)

	want := []string{
		"xamops_drains_total",
		"xamops_drain_budget_inflight",
		"xamops_drain_replan_total",
		"xamops_cycle_duration_seconds",
		"xamops_gate_decision",
		"xamops_policy_fetch_errors_total",
		"xamops_rightsizer_apply_total",
	}
	for _, m := range want {
		if !strings.Contains(out, m) {
			t.Errorf("metric %q missing from /metrics output", m)
		}
	}

	// Spot-check a label set and value from an incremented counter.
	if !strings.Contains(out, `xamops_drains_total{controller="rebalancer",result="succeeded"} 1`) {
		t.Errorf("expected xamops_drains_total{controller=rebalancer,result=succeeded} 1 in output, got:\n%s", out)
	}
	if !strings.Contains(out, `xamops_gate_decision{controller="rebalancer",mode="simulate"} 1`) {
		t.Errorf("expected xamops_gate_decision{controller=rebalancer,mode=simulate} 1 in output")
	}
}

// TestHandlerServesHAMetrics (FEAT-002) asserts the agent-HA metrics
// xamops_leader{is_leader} and xamops_orphans_recovered_total appear with the
// expected values after the leader gauge is set and orphans are recorded.
func TestHandlerServesHAMetrics(t *testing.T) {
	s := New()
	s.SetLeaderIdentity("pod-abc")
	s.SetLeader(true)
	s.AddOrphansRecovered(3)

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	out := string(body)

	if !strings.Contains(out, `xamops_leader{is_leader="pod-abc"} 1`) {
		t.Errorf("expected xamops_leader{is_leader=pod-abc} 1 in output, got:\n%s", out)
	}
	if !strings.Contains(out, "xamops_orphans_recovered_total 3") {
		t.Errorf("expected xamops_orphans_recovered_total 3 in output")
	}

	// Standby sets it back to 0.
	s.SetLeader(false)
	resp2, _ := http.Get(srv.URL)
	defer resp2.Body.Close()
	body2, _ := io.ReadAll(resp2.Body)
	if !strings.Contains(string(body2), `xamops_leader{is_leader="pod-abc"} 0`) {
		t.Errorf("expected xamops_leader ... 0 after standby")
	}
}

// TestNilSetIsSafe ensures the convenience methods are no-ops on a nil set.
func TestNilSetIsSafe(t *testing.T) {
	var s *Set
	s.IncDrain("x", "y")
	s.SetDrainBudgetInflight(1)
	s.IncDrainReplan("x")
	s.ObserveCycle(1)
	s.SetGateDecision("x", "y")
	s.IncPolicyFetchError()
	s.IncRightsizerApply("x")
	s.SetLeaderIdentity("x")
	s.SetLeader(true)
	s.AddOrphansRecovered(2)
}
