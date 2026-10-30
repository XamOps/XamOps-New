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
	resp2, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics after standby: %v", err)
	}
	defer resp2.Body.Close()
	body2, _ := io.ReadAll(resp2.Body)
	if !strings.Contains(string(body2), `xamops_leader{is_leader="pod-abc"} 0`) {
		t.Errorf("expected xamops_leader ... 0 after standby")
	}
}

// TestHandlerServesConsolidationMetrics (FEAT-002) asserts the xamops_consolidation_*
// metrics appear with their design-fixed names and labels after they are exercised.
func TestHandlerServesConsolidationMetrics(t *testing.T) {
	s := New()
	s.IncConsolidationRun("succeeded")
	s.IncConsolidationNodeRemoved("xamops-asg")
	s.IncConsolidationNodeRemoved("defer-to-actor")
	s.AddConsolidationPodsEvicted(4)
	s.ObserveConsolidationDrain(2.5)
	s.IncConsolidationRunAbort("drain-aborted")
	s.SetConsolidationActionRate(2)

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	out := string(body)

	want := []string{
		"xamops_consolidation_runs_total",
		"xamops_consolidation_nodes_removed_total",
		"xamops_consolidation_pods_evicted_total",
		"xamops_consolidation_drain_duration_seconds",
		"xamops_consolidation_run_aborts_total",
		"xamops_consolidation_action_rate",
	}
	for _, m := range want {
		if !strings.Contains(out, m) {
			t.Errorf("metric %q missing from /metrics output", m)
		}
	}
	if !strings.Contains(out, `xamops_consolidation_runs_total{status="succeeded"} 1`) {
		t.Errorf("expected runs_total{status=succeeded} 1, got:\n%s", out)
	}
	if !strings.Contains(out, `xamops_consolidation_nodes_removed_total{path="xamops-asg"} 1`) {
		t.Errorf("expected nodes_removed_total{path=xamops-asg} 1")
	}
	if !strings.Contains(out, "xamops_consolidation_pods_evicted_total 4") {
		t.Errorf("expected pods_evicted_total 4")
	}
	if !strings.Contains(out, "xamops_consolidation_action_rate 2") {
		t.Errorf("expected action_rate 2")
	}
}

// TestVpaMetricsRegistered (FEAT cloudk8s-vpa-apply) asserts the xamops_vpa_* metrics
// appear with their frozen names and labels after they are exercised.
func TestVpaMetricsRegistered(t *testing.T) {
	s := New()
	s.IncVpaResized()
	s.IncVpaRefusedNotEligible("resizePolicy=RestartContainer")
	s.IncVpaRefusedByClassifier("db-primary")
	s.IncVpaReverted()

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	out := string(body)

	want := []string{
		"xamops_vpa_resized_total",
		"xamops_vpa_refused_not_eligible_total",
		"xamops_vpa_refused_by_classifier_total",
		"xamops_vpa_reverted_total",
	}
	for _, m := range want {
		if !strings.Contains(out, m) {
			t.Errorf("metric %q missing from /metrics output", m)
		}
	}
	if !strings.Contains(out, "xamops_vpa_resized_total 1") {
		t.Errorf("expected xamops_vpa_resized_total 1, got:\n%s", out)
	}
	if !strings.Contains(out, `xamops_vpa_refused_not_eligible_total{reason="resizePolicy=RestartContainer"} 1`) {
		t.Errorf("expected refused_not_eligible{reason} 1 in output")
	}
	if !strings.Contains(out, `xamops_vpa_refused_by_classifier_total{code="db-primary"} 1`) {
		t.Errorf("expected refused_by_classifier{code=db-primary} 1 in output")
	}
	if !strings.Contains(out, "xamops_vpa_reverted_total 1") {
		t.Errorf("expected xamops_vpa_reverted_total 1 in output")
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
	s.IncConsolidationRun("x")
	s.IncConsolidationNodeRemoved("x")
	s.AddConsolidationPodsEvicted(2)
	s.ObserveConsolidationDrain(1)
	s.IncConsolidationRunAbort("x")
	s.SetConsolidationActionRate(1)
	s.IncVpaResized()
	s.IncVpaRefusedNotEligible("x")
	s.IncVpaRefusedByClassifier("x")
	s.IncVpaReverted()
}
