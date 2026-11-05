package policy

import (
	"context"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func fetchFromBody(t *testing.T, body string) PolicyBundle {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(body)); err != nil {
			t.Errorf("write response: %v", err)
		}
	}))
	defer srv.Close()

	bundle, err := NewClient(srv.URL, "test-key", "test-cluster", "123456789012").
		FetchPolicy(context.Background())
	if err != nil {
		t.Fatalf("FetchPolicy returned error: %v", err)
	}
	return bundle
}

// A backend that does not yet serve analyseOnly must not un-gate the evictor:
// the response is unmarshalled into DefaultBundle(), so the absent field stays true.
func TestFetchPolicyOmittedAnalyseOnlyFailsSafe(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full","evictorPolicy":{"enabled":true}}`)

	if !bundle.EvictorPolicy.AnalyseOnly {
		t.Error("expected EvictorPolicy.AnalyseOnly to default to true when omitted")
	}
	if !bundle.EvictorPolicy.Enabled {
		t.Error("expected EvictorPolicy.Enabled to be taken from the response")
	}
	if bundle.AutoscalerMode != "full" {
		t.Errorf("expected autoscalerMode full, got %q", bundle.AutoscalerMode)
	}
}

// partialBundleBody is eks/test-clusters/harness/policies/12-partial-bundle.json with
// the harness-only _label/_note keys stripped (the stub strips them before serving).
const partialBundleBody = `{
  "autoscalerMode": "full",
  "evictorPolicy": {
    "enabled": true,
    "targetUtilizationCpu": 100,
    "targetUtilizationMemory": 100,
    "nodeGracePeriodMinutes": 0
  }
}`

// A bundle that omits safetyGuards must keep every default guard (live pass P1-3).
func TestFetchPolicyPartialBundleKeepsSafetyGuards(t *testing.T) {
	bundle := fetchFromBody(t, partialBundleBody)

	g := bundle.SafetyGuards
	if !g.RespectPodDisruptionBudgets || !g.ProtectSingleReplicas || !g.ProtectStatefulSets || !g.ProtectCriticalNamespaces {
		t.Errorf("expected all four guards true, got %+v", g)
	}
	want := []string{"kube-system", "monitoring", "ingress-nginx", "xamops-system"}
	if !reflect.DeepEqual(g.CriticalNamespaces, want) {
		t.Errorf("criticalNamespaces = %v, want %v", g.CriticalNamespaces, want)
	}
	if !bundle.EvictorPolicy.AnalyseOnly {
		t.Error("expected EvictorPolicy.AnalyseOnly true")
	}
	if !bundle.EvictorPolicy.Enabled || bundle.EvictorPolicy.TargetUtilizationCpu != 100 {
		t.Errorf("expected the evictor fields sent by the backend to win, got %+v", bundle.EvictorPolicy)
	}
	if bundle.WorkloadRightsizerPolicy.Enabled || bundle.HPAManagerPolicy.Enabled ||
		bundle.VPAManagerPolicy.Enabled || bundle.NodeOptimizerPolicy.Enabled {
		t.Error("expected rightsizer/HPA/VPA/node optimizer disabled")
	}
	if bundle.NodeOptimizerPolicy.MaxParallelDrains != 1 {
		t.Errorf("maxParallelDrains = %d, want 1", bundle.NodeOptimizerPolicy.MaxParallelDrains)
	}
	if bundle.Rebalancer.Status != "idle" {
		t.Errorf("rebalancer.status = %q, want idle", bundle.Rebalancer.Status)
	}
	if bundle.Rebalancer.OperationID != "" {
		t.Errorf("rebalancer.operationId = %q, want empty", bundle.Rebalancer.OperationID)
	}
}

// A bundle without the FEAT-002 fields keeps the rightsizer clamp floors.
func TestFetchPolicyPartialBundleKeepsRightsizerFloors(t *testing.T) {
	bundle := fetchFromBody(t, partialBundleBody)

	rs := bundle.WorkloadRightsizerPolicy
	if rs.MinCpuMillicores != 25 || rs.MinMemoryMib != 250 {
		t.Errorf("rightsizer floors = %dm/%dMi, want 25m/250Mi", rs.MinCpuMillicores, rs.MinMemoryMib)
	}
}

func TestFetchPolicyExplicitRightsizerFloorsAreHonoured(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full",`+
		`"workloadRightsizerPolicy":{"enabled":true,"minCpuMillicores":50,"minMemoryMib":128}}`)

	rs := bundle.WorkloadRightsizerPolicy
	if rs.MinCpuMillicores != 50 || rs.MinMemoryMib != 128 {
		t.Errorf("rightsizer floors = %dm/%dMi, want 50m/128Mi", rs.MinCpuMillicores, rs.MinMemoryMib)
	}
}

func TestNormalizeRightsizerFloors(t *testing.T) {
	cases := []struct {
		name             string
		cpu, mem         int
		wantCPU, wantMem int
		wantWarnings     int
	}{
		{"defaults untouched", 25, 250, 25, 250, 0},
		{"explicit kept", 50, 128, 50, 128, 0},
		{"zero cpu", 0, 250, 25, 250, 1},
		{"negative both", -5, -1, 25, 250, 2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := DefaultBundle()
			b.WorkloadRightsizerPolicy.MinCpuMillicores, b.WorkloadRightsizerPolicy.MinMemoryMib = tc.cpu, tc.mem
			w := b.Normalize()
			rs := b.WorkloadRightsizerPolicy
			if rs.MinCpuMillicores != tc.wantCPU || rs.MinMemoryMib != tc.wantMem {
				t.Errorf("floors = %dm/%dMi, want %dm/%dMi", rs.MinCpuMillicores, rs.MinMemoryMib, tc.wantCPU, tc.wantMem)
			}
			if len(w) != tc.wantWarnings {
				t.Errorf("warnings = %v, want %d", w, tc.wantWarnings)
			}
		})
	}
}

// An explicit empty criticalNamespaces list is the operator's choice and is honoured.
func TestFetchPolicyExplicitEmptyCriticalNamespacesIsHonoured(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full","safetyGuards":{"respectPodDisruptionBudgets":true,`+
		`"protectSingleReplicas":true,"protectStatefulSets":true,"protectCriticalNamespaces":true,"criticalNamespaces":[]}}`)

	if len(bundle.SafetyGuards.CriticalNamespaces) != 0 {
		t.Errorf("expected an empty criticalNamespaces list, got %v", bundle.SafetyGuards.CriticalNamespaces)
	}
	if !bundle.SafetyGuards.ProtectCriticalNamespaces {
		t.Error("expected protectCriticalNamespaces true")
	}
}

func TestFetchPolicyOperationIDIsRead(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full","rebalancer":{"enabled":true,"status":"running","operationId":"op-7"}}`)
	if bundle.Rebalancer.OperationID != "op-7" {
		t.Errorf("operationId = %q, want op-7", bundle.Rebalancer.OperationID)
	}
}

func TestNormalize(t *testing.T) {
	cases := []struct {
		name         string
		maxDrains    int
		critical     []string
		wantDrains   int
		wantWarnings int
	}{
		{"zero drains", 0, []string{"kube-system"}, 1, 1},
		{"negative drains", -3, []string{"kube-system"}, 1, 1},
		{"positive drains kept", 2, []string{"kube-system"}, 2, 0},
		{"empty critical list warns but is kept", 1, []string{}, 1, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := DefaultBundle()
			b.NodeOptimizerPolicy.MaxParallelDrains = tc.maxDrains
			b.SafetyGuards.CriticalNamespaces = tc.critical
			warnings := b.Normalize()
			if b.NodeOptimizerPolicy.MaxParallelDrains != tc.wantDrains {
				t.Errorf("maxParallelDrains = %d, want %d", b.NodeOptimizerPolicy.MaxParallelDrains, tc.wantDrains)
			}
			if len(warnings) != tc.wantWarnings {
				t.Errorf("warnings = %v, want %d", warnings, tc.wantWarnings)
			}
			if !reflect.DeepEqual(b.SafetyGuards.CriticalNamespaces, tc.critical) {
				t.Errorf("criticalNamespaces changed to %v", b.SafetyGuards.CriticalNamespaces)
			}
		})
	}
}

// An explicit false still wins — present fields override the defaults.
func TestFetchPolicyExplicitAnalyseOnlyFalseIsHonoured(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full","evictorPolicy":{"enabled":true,"analyseOnly":false}}`)

	if bundle.EvictorPolicy.AnalyseOnly {
		t.Error("expected EvictorPolicy.AnalyseOnly to be false when the backend sends false")
	}
}

// AC-G8: a backend that omits approvalMode (every pre-Manual backend, and Autonomous
// output) decodes to "autonomous" with no intent.
func TestFetchPolicyOmittedApprovalModeIsAutonomous(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full"}`)
	if bundle.ApprovalMode != ApprovalAutonomous || bundle.IsManual() || bundle.ManualIntent != nil {
		t.Errorf("approvalMode = %q manual=%v intent=%v", bundle.ApprovalMode, bundle.IsManual(), bundle.ManualIntent)
	}
	if DefaultBundle().ApprovalMode != ApprovalAutonomous {
		t.Error("DefaultBundle must be autonomous")
	}
	if got := bundle.Intent(); !reflect.DeepEqual(got, bundle) {
		t.Error("Intent() of an autonomous bundle must equal the bundle")
	}
}

const manualServedBody = `{
  "autoscalerMode": "full",
  "approvalMode": "manual",
  "evictorPolicy": {"enabled": true, "analyseOnly": true, "targetUtilizationCpu": 70},
  "nodeOptimizerPolicy": {"enabled": true, "autoCordonIdleNodes": false, "autoDrainIdleNodes": false, "spotRatioTargetPct": 0, "handleSpotInterruptions": false, "maxParallelDrains": 2},
  "rebalancer": {"enabled": false, "scheduleEnabled": false, "status": "idle", "drainTimeoutMinutes": 33},
  "consolidationPolicy": {"enabled": false, "consolidateAfterSeconds": 300},
  "manualIntent": {
    "evictorPolicy": {"analyseOnly": false},
    "nodeOptimizerPolicy": {"autoDrainIdleNodes": true},
    "rebalancer": {"enabled": true, "status": "running"},
    "consolidationPolicy": {"enabled": true}
  }
}`

// Intent() decodes each section onto a copy of the neutered served section, so a field
// the intent omits keeps its neutered value instead of becoming a zero value.
func TestIntentDecodesOntoNeuteredSections(t *testing.T) {
	served := fetchFromBody(t, manualServedBody)
	if !served.IsManual() || served.ManualIntent == nil {
		t.Fatalf("served = %+v", served)
	}
	// The served bundle itself stays neutered.
	if !served.EvictorPolicy.AnalyseOnly || served.NodeOptimizerPolicy.AutoDrainIdleNodes || served.Rebalancer.Enabled || served.ConsolidationPolicy.Enabled {
		t.Fatalf("served bundle not neutered: %+v", served)
	}
	in := served.Intent()
	if in.EvictorPolicy.AnalyseOnly || !in.EvictorPolicy.Enabled || in.EvictorPolicy.TargetUtilizationCpu != 70 {
		t.Errorf("evictor intent = %+v (analyseOnly flipped; omitted fields must keep served values)", in.EvictorPolicy)
	}
	if !in.NodeOptimizerPolicy.AutoDrainIdleNodes || in.NodeOptimizerPolicy.AutoCordonIdleNodes ||
		in.NodeOptimizerPolicy.SpotRatioTargetPct != 0 || in.NodeOptimizerPolicy.HandleSpotInterruptions || in.NodeOptimizerPolicy.MaxParallelDrains != 2 {
		t.Errorf("node optimizer intent = %+v (only autoDrainIdleNodes may change)", in.NodeOptimizerPolicy)
	}
	if !in.Rebalancer.Enabled || in.Rebalancer.Status != "running" || in.Rebalancer.DrainTimeoutMinutes != 33 {
		t.Errorf("rebalancer intent = %+v", in.Rebalancer)
	}
	if !in.ConsolidationPolicy.Enabled || in.ConsolidationPolicy.ConsolidateAfterSeconds != 300 {
		t.Errorf("consolidation intent = %+v", in.ConsolidationPolicy)
	}
	if in.ManualIntent != nil || in.AutoscalerMode != "full" || !in.IsManual() {
		t.Errorf("intent envelope = mode %q manual=%v intent=%v", in.AutoscalerMode, in.IsManual(), in.ManualIntent)
	}
	// Intent() did not mutate the served bundle.
	if !served.EvictorPolicy.AnalyseOnly || served.Rebalancer.Status != "idle" {
		t.Error("Intent() mutated the served bundle")
	}
}

// An absent, null or undecodable intent section keeps the neutered served value.
func TestIntentFailsClosedOnBadSections(t *testing.T) {
	served := fetchFromBody(t, `{
	  "autoscalerMode": "full", "approvalMode": "manual",
	  "evictorPolicy": {"enabled": true, "analyseOnly": true},
	  "rebalancer": {"enabled": false, "status": "idle"},
	  "manualIntent": {"evictorPolicy": {"analyseOnly": "not-a-bool", "enabled": false}, "rebalancer": null}
	}`)
	in := served.Intent()
	if !in.EvictorPolicy.AnalyseOnly || !in.EvictorPolicy.Enabled {
		t.Errorf("undecodable evictor intent must keep the whole neutered section, got %+v", in.EvictorPolicy)
	}
	if in.Rebalancer.Enabled || in.Rebalancer.Status != "idle" {
		t.Errorf("null rebalancer intent must keep the served section, got %+v", in.Rebalancer)
	}
	if in.NodeOptimizerPolicy.AutoDrainIdleNodes || in.ConsolidationPolicy.Enabled {
		t.Errorf("absent sections must stay neutered: %+v %+v", in.NodeOptimizerPolicy, in.ConsolidationPolicy)
	}
}
