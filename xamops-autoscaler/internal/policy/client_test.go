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
