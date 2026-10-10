package controller

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

func TestClampTarget(t *testing.T) {
	const floor = 25
	cases := []struct {
		name                  string
		current, demand       int64
		wantTarget            int64
		wantChanged           bool
		wantReasonContainsAny string
	}{
		{"current == floor, demand below", 25, 3, 25, false, "never raised to the floor"},
		{"current below floor, demand below", 10, 3, 10, false, "never raised to the floor"},
		{"demand between floor and current", 100, 40, 40, true, "decrease to measured demand"},
		{"decrease clamped to floor", 1000, 1, 25, true, "clamped to the floor"},
		{"demand 0 -> floor, never 0", 100, 0, 25, true, "clamped to the floor"},
		{"increase below the floor is allowed", 10, 20, 20, true, "increase"},
		{"increase above the floor", 10, 56, 56, true, "increase"},
		{"demand equals current", 80, 80, 80, false, "equals"},
		{"explicit zero request is unset", 0, 50, 0, false, "unset"},
		{"negative request is unset", -1, 50, -1, false, "unset"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, changed, reason := clampTarget(tc.current, tc.demand, floor)
			if got != tc.wantTarget || changed != tc.wantChanged || !strings.Contains(reason, tc.wantReasonContainsAny) {
				t.Errorf("clampTarget(%d,%d,%d) = (%d,%v,%q), want (%d,%v,~%q)",
					tc.current, tc.demand, floor, got, changed, reason, tc.wantTarget, tc.wantChanged, tc.wantReasonContainsAny)
			}
		})
	}
	// A non-positive floor never lets CPU reach 0m.
	if got, _, _ := clampTarget(100, 0, 0); got != 1 {
		t.Errorf("floor 0: target = %d, want 1", got)
	}
}

// baselineRow is one autoscaling-rightsizing row of the phase-5 baseline
// (captures/phase5/autoscaler-pushes-raw.txt) with its seeded requests.
type baselineRow struct {
	ns, workload, container string
	cpu, mem                string // seeded requests; "" = unset
	p80CPU, p100Mem         float64
	// expected clamped output; wantRow=false -> no row
	wantRow            bool
	wantCPU, wantMem   string
	wantSavingsPercent float64
}

var baselineRows = []baselineRow{
	{"team-a", "overprovisioned-api", "idler", "1", "1Gi", 0.04172422886188257, 339968, true, "25m", "250Mi", 97.5},
	{"dev-sim", "idle-batch-listener", "sleeper", "100m", "128Mi", 0, 348160, true, "25m", "128Mi", 75},
	{"team-b", "pdb-guarded-web", "web", "50m", "48Mi", 0, 3141632, true, "25m", "48Mi", 50},
	{"prod-sim", "rightsized-worker", "worker", "80m", "64Mi", 48.85805274539612, 352256, true, "54m", "64Mi", 32.5},
	{"team-b", "php-apache", "php-apache", "200m", "64Mi", 130.1919496970533, 13787136, true, "144m", "64Mi", 28},
	{"prod-sim", "throttled-encoder", "encoder", "10m", "32Mi", 50.009575422667304, 352256, true, "56m", "32Mi", 0},
	{"dev-sim", "no-requests-sidecar-app", "app", "", "", 0.008712783759390247, 352256, false, "", "", 0},
}

// baselinePrometheus answers the rightsizer's CPU (quantile_over_time) and memory
// (max_over_time) queries with the baseline values, matched on the pod regex.
func baselinePrometheus(t *testing.T, rows []baselineRow) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("query")
		for _, row := range rows {
			if !strings.Contains(q, `pod=~"`+row.workload+`-.*"`) {
				continue
			}
			v := row.p100Mem
			if strings.HasPrefix(q, "quantile_over_time") {
				v = row.p80CPU
			}
			fmt.Fprintf(w, `{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"%v"]}]}}`, v)
			return
		}
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[]}}`))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func rsContainer(name, cpu, mem string) corev1.Container {
	c := corev1.Container{Name: name}
	if cpu != "" || mem != "" {
		c.Resources.Requests = corev1.ResourceList{}
	}
	if cpu != "" {
		c.Resources.Requests[corev1.ResourceCPU] = resource.MustParse(cpu)
	}
	if mem != "" {
		c.Resources.Requests[corev1.ResourceMemory] = resource.MustParse(mem)
	}
	return c
}

func rsDeployment(ns, name string, replicas int32, c corev1.Container) *appsv1.Deployment {
	l := map[string]string{"app": name}
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: l},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: l},
				Spec:       corev1.PodSpec{Containers: []corev1.Container{c}},
			},
		},
	}
}

func rsPolicy() policy.WorkloadRightsizerPolicy {
	p := policy.DefaultBundle().WorkloadRightsizerPolicy
	p.Enabled = true
	return p
}

// The 7 baseline rows reproduce exactly the clamped outputs of plan.md FEAT-002 step 10.
func TestRightsizerAnalyzeContainerBaselineRows(t *testing.T) {
	prom := baselinePrometheus(t, baselineRows)
	r := NewRightsizer(fake.NewSimpleClientset(), prom.URL, true)
	p := rsPolicy()

	for _, row := range baselineRows {
		t.Run(row.workload, func(t *testing.T) {
			a := r.analyzeContainer(context.Background(), row.ns, row.workload, "Deployment",
				rsContainer(row.container, row.cpu, row.mem), p)
			if !row.wantRow {
				if a != nil {
					t.Fatalf("expected no row, got %+v", a.result)
				}
				return
			}
			if a == nil {
				t.Fatal("expected a row, got none")
			}
			got := a.result
			t.Logf("expected %s/%s savings %.1f | actual %s/%s savings %.4f | %s",
				row.wantCPU, row.wantMem, row.wantSavingsPercent, got.RecommendedCPU, got.RecommendedMem, got.EstimatedSavings, a.clampNote)
			if got.RecommendedCPU != row.wantCPU || got.RecommendedMem != row.wantMem {
				t.Errorf("recommended %s/%s, want %s/%s", got.RecommendedCPU, got.RecommendedMem, row.wantCPU, row.wantMem)
			}
			if d := got.EstimatedSavings - row.wantSavingsPercent; d > 1e-9 || d < -1e-9 {
				t.Errorf("savings %v, want %v", got.EstimatedSavings, row.wantSavingsPercent)
			}
			if got.RecommendedCPU == "0m" {
				t.Error("0m CPU recommended")
			}
		})
	}
}

// An explicit zero request is treated like an unset one: no row.
func TestRightsizerZeroRequestIsSkipped(t *testing.T) {
	prom := baselinePrometheus(t, baselineRows)
	r := NewRightsizer(fake.NewSimpleClientset(), prom.URL, true)
	for _, c := range []corev1.Container{
		rsContainer("idler", "0", "1Gi"),
		rsContainer("idler", "1", "0"),
		rsContainer("idler", "1", ""),
		rsContainer("idler", "", "1Gi"),
	} {
		if a := r.analyzeContainer(context.Background(), "team-a", "overprovisioned-api", "Deployment", c, rsPolicy()); a != nil {
			t.Errorf("requests %v: expected no row, got %+v", c.Resources.Requests, a.result)
		}
	}
}

func patchActions(c *fake.Clientset) int {
	n := 0
	for _, a := range c.Actions() {
		if a.GetVerb() == "patch" && a.GetResource().Resource == "deployments" {
			n++
		}
	}
	return n
}

// Gate truth table: a Deployment patch happens only for ENABLE_RIGHTSIZER_APPLY=true ∧
// applyMode=immediate ∧ DRY_RUN=false.
func TestRightsizerApplyGateTruthTable(t *testing.T) {
	prom := baselinePrometheus(t, baselineRows)
	for _, flag := range []bool{false, true} {
		for _, mode := range []string{"immediate", "deferred"} {
			for _, dry := range []bool{false, true} {
				name := fmt.Sprintf("apply=%v/mode=%s/dryRun=%v", flag, mode, dry)
				t.Run(name, func(t *testing.T) {
					c := fake.NewSimpleClientset(rsDeployment("team-a", "overprovisioned-api", 2, rsContainer("idler", "1", "1Gi")))
					cfg := &config.Config{EnableRightsizer: true, EnableRightsizerApply: flag, DryRun: dry}
					b := policy.DefaultBundle()
					b.AutoscalerMode = "full"
					b.WorkloadRightsizerPolicy.Enabled = true
					b.WorkloadRightsizerPolicy.ApplyMode = mode
					d := gate.RightsizerApply(cfg, b)

					rows, recs, err := NewRightsizer(c, prom.URL, dry).Run(context.Background(), b.WorkloadRightsizerPolicy, b.SafetyGuards, d)
					if err != nil {
						t.Fatal(err)
					}
					if len(rows) != 1 {
						t.Fatalf("rows = %d, want 1", len(rows))
					}
					row := rows[0]
					wantLive := flag && mode == "immediate" && !dry
					wantSim := flag && mode == "immediate" && dry
					t.Logf("gate=%s patches=%d applied=%v records=%d reason=%q", d, patchActions(c), row.Applied, len(recs), row.Reason)

					if got := patchActions(c); (got == 1) != wantLive || got > 1 {
						t.Errorf("deployment patches = %d, want live=%v", got, wantLive)
					}
					if row.Applied != wantLive {
						t.Errorf("applied = %v, want %v", row.Applied, wantLive)
					}
					switch {
					case wantLive:
						if len(recs) != 1 || recs[0].Status != policy.ActionSucceeded || !recs[0].Verified || recs[0].DryRun {
							t.Errorf("live records = %+v", recs)
						}
						dep, _ := c.AppsV1().Deployments("team-a").Get(context.Background(), "overprovisioned-api", metav1.GetOptions{})
						req := dep.Spec.Template.Spec.Containers[0].Resources.Requests
						if req.Cpu().MilliValue() != 25 || req.Memory().Value() != 250*mib {
							t.Errorf("patched requests = %s/%s, want 25m/250Mi", req.Cpu(), req.Memory())
						}
					case wantSim:
						if len(recs) != 1 || recs[0].Status != policy.ActionSimulated || !recs[0].DryRun || recs[0].Verified {
							t.Errorf("simulated records = %+v", recs)
						}
						if !strings.HasPrefix(row.Reason, reasonSimulated) {
							t.Errorf("reason = %q", row.Reason)
						}
					default:
						if len(recs) != 0 {
							t.Errorf("records = %+v, want none", recs)
						}
						want := reasonDeferred
						if !flag {
							want = reasonApplyOff
						}
						if !strings.HasPrefix(row.Reason, want) {
							t.Errorf("reason = %q, want prefix %q", row.Reason, want)
						}
					}
				})
			}
		}
	}
}

// protectSingleReplicas and protectCriticalNamespaces produce no rows.
func TestRightsizerGuardsProduceNoRows(t *testing.T) {
	prom := baselinePrometheus(t, baselineRows)
	objs := []runtime.Object{
		rsDeployment("team-b", "php-apache", 1, rsContainer("php-apache", "200m", "64Mi")),
		rsDeployment("kube-system", "overprovisioned-api", 2, rsContainer("idler", "1", "1Gi")),
	}
	c := fake.NewSimpleClientset(objs...)
	b := policy.DefaultBundle()
	b.WorkloadRightsizerPolicy.Enabled = true
	b.WorkloadRightsizerPolicy.ApplyMode = "immediate"
	rows, recs, err := NewRightsizer(c, prom.URL, false).Run(context.Background(), b.WorkloadRightsizerPolicy, b.SafetyGuards,
		gate.Decision{Mode: gate.Live, Reason: "test"})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 || len(recs) != 0 || patchActions(c) != 0 {
		t.Errorf("rows=%+v recs=%+v patches=%d, want none", rows, recs, patchActions(c))
	}
}

// patchDeploymentContainer re-validates the clamp and refuses before calling the API.
func TestRightsizerPatchRevalidates(t *testing.T) {
	dep := rsDeployment("team-a", "overprovisioned-api", 2, rsContainer("idler", "1", "1Gi"))
	unset := rsDeployment("dev-sim", "no-requests-sidecar-app", 2, rsContainer("app", "", ""))
	cases := []struct {
		name          string
		dep           *appsv1.Deployment
		cpu, mem      int64
		dryRun        bool
		wantRefusePfx string
	}{
		{"cpu 0m", dep, 0, 250 * mib, false, "refused: CPU target 0m"},
		{"cpu decrease below floor", dep, 10, 250 * mib, false, "refused: CPU decrease"},
		{"memory decrease below floor", dep, 25, 100 * mib, false, "refused: memory decrease"},
		{"unset requests", unset, 25, 250 * mib, false, "refused: current requests unset"},
		{"dry run", dep, 25, 250 * mib, true, "refused: DRY_RUN"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := fake.NewSimpleClientset(tc.dep)
			r := NewRightsizer(c, "http://unused", tc.dryRun)
			_, err := r.patchDeploymentContainer(context.Background(), *tc.dep, tc.dep.Spec.Template.Spec.Containers[0].Name, tc.cpu, tc.mem, 25, 250*mib)
			if err == nil || !strings.HasPrefix(err.Error(), tc.wantRefusePfx) {
				t.Errorf("err = %v, want prefix %q", err, tc.wantRefusePfx)
			}
			if patchActions(c) != 0 {
				t.Error("patch issued despite refusal")
			}
		})
	}
}
