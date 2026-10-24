package gate

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/policy"
)

var modes = []string{"full", "scoped", "disabled"}

func bundle(mode string) policy.PolicyBundle {
	b := policy.DefaultBundle()
	b.AutoscalerMode = mode
	return b
}

// The full cross product of plan.md section 5 for the rebalancer:
// ENABLE_REBALANCER × rebalancer.enabled × status × DRY_RUN × mode.
func TestRebalancerCrossProduct(t *testing.T) {
	rows := 0
	for _, env := range []bool{false, true} {
		for _, enabled := range []bool{false, true} {
			for _, status := range []string{"idle", "running", "completed"} {
				for _, dry := range []bool{false, true} {
					for _, mode := range modes {
						rows++
						name := fmt.Sprintf("env=%v/enabled=%v/status=%s/dry=%v/mode=%s", env, enabled, status, dry, mode)
						cfg := &config.Config{EnableRebalancer: env, DryRun: dry}
						b := bundle(mode)
						b.Rebalancer.Enabled = enabled
						b.Rebalancer.Status = status

						want := Off
						if env && mode == "full" && enabled && status == "running" {
							want = Live
							if dry {
								want = Simulate
							}
						}
						got := Rebalancer(cfg, b)
						if got.Mode != want {
							t.Errorf("%s: got %s, want %s", name, got, want)
						}
						if got.Reason == "" {
							t.Errorf("%s: empty reason", name)
						}
					}
				}
			}
		}
	}
	if rows != 72 {
		t.Fatalf("cross product has %d rows, want 72", rows)
	}
}

// The baseline fail-open row: the backend leaves status=running, the operator has
// ENABLE_REBALANCER=false. It must be Off, and the reason must name the env flag.
func TestRebalancerEnvOffRunningIsOff(t *testing.T) {
	cfg := &config.Config{EnableRebalancer: false, DryRun: false}
	b := bundle("full")
	b.Rebalancer.Enabled = true
	b.Rebalancer.Status = "running"

	got := Rebalancer(cfg, b)
	if got.Mode != Off {
		t.Fatalf("got %s, want off", got)
	}
	if got.Reason != "ENABLE_REBALANCER=false" {
		t.Errorf("reason = %q, want ENABLE_REBALANCER=false", got.Reason)
	}
}

func TestEvictor(t *testing.T) {
	cases := []struct {
		env, enabled, analyseOnly, dry bool
		mode                           string
		want                           Mode
		reason                         string
	}{
		{false, true, false, false, "full", Off, "ENABLE_EVICTOR=false"},
		{true, false, false, false, "full", Off, "evictorPolicy.enabled=false"},
		{true, true, false, false, "scoped", Off, "evictor needs full"},
		{true, true, false, false, "disabled", Off, "autoscalerMode=disabled"},
		{true, true, true, false, "full", Analyse, "analyseOnly"},
		{true, true, true, true, "full", Analyse, "analyseOnly"},
		{true, true, false, true, "full", Simulate, "DRY_RUN"},
		{true, true, false, false, "full", Live, "DRY_RUN=false"},
	}
	for _, tc := range cases {
		cfg := &config.Config{EnableEvictor: tc.env, DryRun: tc.dry}
		b := bundle(tc.mode)
		b.EvictorPolicy.Enabled = tc.enabled
		b.EvictorPolicy.AnalyseOnly = tc.analyseOnly
		got := Evictor(cfg, b)
		if got.Mode != tc.want || !strings.Contains(got.Reason, tc.reason) {
			t.Errorf("%+v: got %s, want %s containing %q", tc, got, tc.want, tc.reason)
		}
	}
}

func TestNodeOptimizerAnalysisAndActions(t *testing.T) {
	for _, env := range []bool{false, true} {
		for _, enabled := range []bool{false, true} {
			for _, mode := range modes {
				for _, flag := range []bool{false, true} {
					for _, dry := range []bool{false, true} {
						cfg := &config.Config{EnableNodeOptimizer: env, DryRun: dry}
						b := bundle(mode)
						b.NodeOptimizerPolicy.Enabled = enabled
						name := fmt.Sprintf("env=%v/enabled=%v/mode=%s/flag=%v/dry=%v", env, enabled, mode, flag, dry)

						wantAnalysis := Off
						if env && enabled && (mode == "full" || mode == "scoped") {
							wantAnalysis = Analyse
						}
						if got := NodeOptimizerAnalysis(cfg, b); got.Mode != wantAnalysis {
							t.Errorf("analysis %s: got %s, want %s", name, got, wantAnalysis)
						}

						wantAction := Off
						if env && enabled && mode == "full" && flag {
							wantAction = Live
							if dry {
								wantAction = Simulate
							}
						}
						got := NodeAction(cfg, b, flag, "autoDrainIdleNodes")
						if got.Mode != wantAction {
							t.Errorf("action %s: got %s, want %s", name, got, wantAction)
						}
						if env && enabled && mode == "full" && !flag && !strings.Contains(got.Reason, "autoDrainIdleNodes") {
							t.Errorf("action %s: reason %q does not name the flag", name, got.Reason)
						}
					}
				}
			}
		}
	}
}

func TestRightsizerAnalysisAndApply(t *testing.T) {
	for _, env := range []bool{false, true} {
		for _, apply := range []bool{false, true} {
			for _, applyMode := range []string{"immediate", "deferred"} {
				for _, dry := range []bool{false, true} {
					for _, mode := range modes {
						cfg := &config.Config{EnableRightsizer: env, EnableRightsizerApply: apply, DryRun: dry}
						b := bundle(mode)
						b.WorkloadRightsizerPolicy.Enabled = true
						b.WorkloadRightsizerPolicy.ApplyMode = applyMode
						name := fmt.Sprintf("env=%v/apply=%v/applyMode=%s/dry=%v/mode=%s", env, apply, applyMode, dry, mode)

						analysisOn := env && (mode == "full" || mode == "scoped")
						wantAnalysis := Off
						if analysisOn {
							wantAnalysis = Analyse
						}
						if got := RightsizerAnalysis(cfg, b); got.Mode != wantAnalysis {
							t.Errorf("analysis %s: got %s, want %s", name, got, wantAnalysis)
						}

						wantApply := Off
						if analysisOn && apply && applyMode == "immediate" {
							wantApply = Live
							if dry {
								wantApply = Simulate
							}
						}
						if got := RightsizerApply(cfg, b); got.Mode != wantApply {
							t.Errorf("apply %s: got %s, want %s", name, got, wantApply)
						}
					}
				}
			}
		}
	}

	// Policy enabled=false closes both.
	cfg := &config.Config{EnableRightsizer: true, EnableRightsizerApply: true}
	b := bundle("full")
	b.WorkloadRightsizerPolicy.ApplyMode = "immediate"
	if got := RightsizerApply(cfg, b); got.Mode != Off || got.Reason != "workloadRightsizerPolicy.enabled=false" {
		t.Errorf("policy disabled: got %s", got)
	}
}

// The HPA manager is analysis-only: no input combination makes it Live or Simulate.
func TestHPAManagerNeverLive(t *testing.T) {
	for _, env := range []bool{false, true} {
		for _, enabled := range []bool{false, true} {
			for _, dry := range []bool{false, true} {
				for _, mode := range modes {
					cfg := &config.Config{EnableHPAManager: env, DryRun: dry}
					b := bundle(mode)
					b.HPAManagerPolicy.Enabled = enabled
					b.HPAManagerPolicy.AnalyseOnly = false
					got := HPAManager(cfg, b)
					want := Off
					if env && enabled && (mode == "full" || mode == "scoped") {
						want = Analyse
					}
					if got.Mode != want {
						t.Errorf("env=%v enabled=%v dry=%v mode=%s: got %s, want %s", env, enabled, dry, mode, got, want)
					}
				}
			}
		}
	}
}

// Consolidation analysis is look-only: over every combination of the 7 ENABLE_*
// flags, DRY_RUN and the autoscaler mode it is Off or Analyse, Analyse exactly for
// full and scoped.
func TestConsolidationAnalysisNeverActs(t *testing.T) {
	rows := 0
	for bits := 0; bits < 1<<7; bits++ {
		on := func(i int) bool { return bits&(1<<i) != 0 }
		for _, dry := range []bool{false, true} {
			for _, mode := range []string{"full", "scoped", "disabled", "", "bogus"} {
				rows++
				cfg := &config.Config{
					EnableEvictor: on(0), EnableRightsizer: on(1), EnableRebalancer: on(2), EnableHPAManager: on(3),
					EnableVPAManager: on(4), EnableNodeOptimizer: on(5), EnableRightsizerApply: on(6), DryRun: dry,
				}
				got := ConsolidationAnalysis(cfg, bundle(mode))
				want := Off
				if mode == "full" || mode == "scoped" {
					want = Analyse
				}
				if got.Mode != want {
					t.Errorf("flags=%07b dry=%v mode=%q: got %s, want %s", bits, dry, mode, got, want)
				}
				if got.Reason == "" {
					t.Errorf("flags=%07b dry=%v mode=%q: empty reason", bits, dry, mode)
				}
			}
		}
	}
	if rows != 128*2*5 {
		t.Fatalf("cross product has %d rows, want %d", rows, 128*2*5)
	}
	if got := ConsolidationAnalysis(&config.Config{}, bundle("disabled")); got.Reason != "autoscalerMode=disabled" {
		t.Errorf("disabled reason = %q", got.Reason)
	}
}

func TestUnknownModeFailsClosed(t *testing.T) {
	cfg := &config.Config{EnableRebalancer: true, EnableEvictor: true, EnableNodeOptimizer: true,
		EnableRightsizer: true, EnableHPAManager: true}
	b := bundle("")
	b.Rebalancer.Enabled, b.Rebalancer.Status = true, "running"
	b.EvictorPolicy.Enabled, b.EvictorPolicy.AnalyseOnly = true, false
	b.NodeOptimizerPolicy.Enabled = true
	b.WorkloadRightsizerPolicy.Enabled = true
	b.HPAManagerPolicy.Enabled = true
	for name, d := range map[string]Decision{
		"rebalancer": Rebalancer(cfg, b), "evictor": Evictor(cfg, b),
		"nodeopt": NodeOptimizerAnalysis(cfg, b), "rightsizer": RightsizerAnalysis(cfg, b),
		"hpa": HPAManager(cfg, b),
	} {
		if d.Mode != Off {
			t.Errorf("%s with empty mode: got %s, want off", name, d)
		}
	}
}

// The interruption gate opens only when the served policy sets
// handleSpotInterruptions=true; an absent field, an absent section or a null section
// decode (through the real FetchPolicy path) to false and the gate is Off.
func TestInterruptionGateFailsClosed(t *testing.T) {
	bodies := map[string]string{
		"field absent":   `{"autoscalerMode":"full","nodeOptimizerPolicy":{"enabled":true}}`,
		"false":          `{"autoscalerMode":"full","nodeOptimizerPolicy":{"enabled":true,"handleSpotInterruptions":false}}`,
		"true":           `{"autoscalerMode":"full","nodeOptimizerPolicy":{"enabled":true,"handleSpotInterruptions":true}}`,
		"section absent": `{"autoscalerMode":"full"}`,
		"section null":   `{"autoscalerMode":"full","nodeOptimizerPolicy":null}`,
	}
	cases := []struct {
		body string
		cfg  config.Config
		want Mode
	}{
		{"field absent", config.Config{EnableNodeOptimizer: true, DryRun: false}, Off},
		{"field absent", config.Config{EnableNodeOptimizer: true, DryRun: true}, Off},
		{"false", config.Config{EnableNodeOptimizer: true, DryRun: false}, Off},
		{"false", config.Config{EnableNodeOptimizer: true, DryRun: true}, Off},
		{"section absent", config.Config{EnableNodeOptimizer: true, DryRun: false}, Off},
		{"section absent", config.Config{EnableNodeOptimizer: true, DryRun: true}, Off},
		{"section null", config.Config{EnableNodeOptimizer: true, DryRun: false}, Off},
		{"section null", config.Config{EnableNodeOptimizer: true, DryRun: true}, Off},
		{"true", config.Config{EnableNodeOptimizer: true, DryRun: false}, Live},
		{"true", config.Config{EnableNodeOptimizer: true, DryRun: true}, Simulate},
		{"true", config.Config{EnableNodeOptimizer: false, DryRun: false}, Off},
	}
	for _, tc := range cases {
		name := fmt.Sprintf("%s/enable=%v/dry=%v", tc.body, tc.cfg.EnableNodeOptimizer, tc.cfg.DryRun)
		t.Run(name, func(t *testing.T) {
			body := bodies[tc.body]
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(body))
			}))
			defer srv.Close()
			b, err := policy.NewClient(srv.URL, "k", "c", "1").FetchPolicy(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			cfg := tc.cfg
			got := Interruption(&cfg, b)
			if got.Mode != tc.want {
				t.Fatalf("got %s, want %s", got, tc.want)
			}
			if tc.body != "true" && b.NodeOptimizerPolicy.HandleSpotInterruptions {
				t.Errorf("%s decoded handleSpotInterruptions=true", tc.body)
			}
			// With the section present and enabled the reason names the flag; an absent or
			// null section is already Off at nodeOptimizerPolicy.enabled=false.
			if (tc.body == "field absent" || tc.body == "false") && !strings.Contains(got.Reason, "handleSpotInterruptions") {
				t.Errorf("reason %q does not name handleSpotInterruptions", got.Reason)
			}
		})
	}
}
