package policy

import (
	"context"
	"net/http"
	"net/http/httptest"
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

// An explicit false still wins — present fields override the defaults.
func TestFetchPolicyExplicitAnalyseOnlyFalseIsHonoured(t *testing.T) {
	bundle := fetchFromBody(t, `{"autoscalerMode":"full","evictorPolicy":{"enabled":true,"analyseOnly":false}}`)

	if bundle.EvictorPolicy.AnalyseOnly {
		t.Error("expected EvictorPolicy.AnalyseOnly to be false when the backend sends false")
	}
}
