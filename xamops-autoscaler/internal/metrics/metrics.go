// Package metrics is the shared observability baseline for the automation agent
// (hardening-design.md 'observability baseline', agent-ha-design.md Gap-3). It owns
// a private *prometheus.Registry and the typed collector handles the control loop,
// the drain path and the rightsizer increment. New() registers every collector on
// its own registry (no reliance on the global default), and Handler() serves them
// through promhttp.
//
// Metric names and labels are fixed by the design docs and MUST NOT change: changing
// them breaks the dashboards/alerts those docs define.
package metrics

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Set is the agent's metric set, registered on its own registry. Construct it once in
// main() with New() and pass it (or its handles) into the control loop / drain path.
type Set struct {
	registry *prometheus.Registry

	// DrainsTotal counts drain outcomes by controller and result. result is derived
	// from the drain Outcome.Status (succeeded/refused/skipped/aborted/failed/simulated).
	DrainsTotal *prometheus.CounterVec
	// DrainBudgetInflight is the number of nodes currently in flight for the global
	// per-cycle drain budget.
	DrainBudgetInflight prometheus.Gauge
	// DrainReplanTotal counts drain re-plans by controller (cycle-level drain outcomes
	// — we do not fabricate per-eviction replan counts we cannot observe).
	DrainReplanTotal *prometheus.CounterVec
	// CycleDurationSeconds observes the wall-clock duration of each control cycle.
	CycleDurationSeconds prometheus.Histogram
	// GateDecision records the gate mode chosen for each controller this cycle
	// (controller,mode labels); it is set to 1 for the current cycle's decision.
	GateDecision *prometheus.GaugeVec
	// PolicyFetchErrorsTotal counts policy-fetch failures.
	PolicyFetchErrorsTotal prometheus.Counter
	// RightsizerApplyTotal counts rightsizer apply outcomes by result (incremented by
	// FEAT-004).
	RightsizerApplyTotal *prometheus.CounterVec
}

// New builds the metric set, registering all collectors on a fresh registry.
func New() *Set {
	reg := prometheus.NewRegistry()
	s := &Set{
		registry: reg,
		DrainsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "xamops_drains_total",
			Help: "Drain outcomes by controller and result (succeeded/refused/skipped/aborted/failed/simulated).",
		}, []string{"controller", "result"}),
		DrainBudgetInflight: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "xamops_drain_budget_inflight",
			Help: "Nodes currently in flight for the global per-cycle drain budget.",
		}),
		DrainReplanTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "xamops_drain_replan_total",
			Help: "Drain re-plans observed at cycle level, by controller.",
		}, []string{"controller"}),
		CycleDurationSeconds: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "xamops_cycle_duration_seconds",
			Help:    "Wall-clock duration of each automation control cycle.",
			Buckets: prometheus.DefBuckets,
		}),
		GateDecision: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "xamops_gate_decision",
			Help: "Gate decision per controller this cycle (controller,mode); set to 1 for the chosen mode.",
		}, []string{"controller", "mode"}),
		PolicyFetchErrorsTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "xamops_policy_fetch_errors_total",
			Help: "Policy-fetch failures.",
		}),
		RightsizerApplyTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "xamops_rightsizer_apply_total",
			Help: "Rightsizer apply outcomes by result.",
		}, []string{"result"}),
	}
	reg.MustRegister(
		s.DrainsTotal,
		s.DrainBudgetInflight,
		s.DrainReplanTotal,
		s.CycleDurationSeconds,
		s.GateDecision,
		s.PolicyFetchErrorsTotal,
		s.RightsizerApplyTotal,
	)
	return s
}

// Registry exposes the private registry (handy for tests).
func (s *Set) Registry() *prometheus.Registry { return s.registry }

// Handler returns an http.Handler that serves the set's registry in the Prometheus
// text exposition format.
func (s *Set) Handler() http.Handler {
	return promhttp.HandlerFor(s.registry, promhttp.HandlerOpts{})
}

// ObserveCycle records one control cycle's wall-clock duration in seconds.
func (s *Set) ObserveCycle(seconds float64) {
	if s == nil {
		return
	}
	s.CycleDurationSeconds.Observe(seconds)
}

// SetGateDecision sets the gate decision gauge to 1 for (controller, mode).
func (s *Set) SetGateDecision(controller, mode string) {
	if s == nil {
		return
	}
	s.GateDecision.WithLabelValues(controller, mode).Set(1)
}

// SetDrainBudgetInflight records the number of in-flight drain budget slots.
func (s *Set) SetDrainBudgetInflight(n int) {
	if s == nil {
		return
	}
	s.DrainBudgetInflight.Set(float64(n))
}

// IncPolicyFetchError increments the policy-fetch error counter.
func (s *Set) IncPolicyFetchError() {
	if s == nil {
		return
	}
	s.PolicyFetchErrorsTotal.Inc()
}

// IncDrain increments the drain counter for (controller, result).
func (s *Set) IncDrain(controller, result string) {
	if s == nil {
		return
	}
	s.DrainsTotal.WithLabelValues(controller, result).Inc()
}

// IncDrainReplan increments the drain re-plan counter for controller.
func (s *Set) IncDrainReplan(controller string) {
	if s == nil {
		return
	}
	s.DrainReplanTotal.WithLabelValues(controller).Inc()
}

// IncRightsizerApply increments the rightsizer apply counter for result (FEAT-004).
func (s *Set) IncRightsizerApply(result string) {
	if s == nil {
		return
	}
	s.RightsizerApplyTotal.WithLabelValues(result).Inc()
}
