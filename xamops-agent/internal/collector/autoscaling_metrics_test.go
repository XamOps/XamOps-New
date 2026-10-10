package collector

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sort"
	"testing"

	"k8s.io/client-go/kubernetes/fake"
)

func TestCollectMetricsPendingPodsResolvedMeaning(t *testing.T) {
	// Prometheus returning 500 forces the metrics-server fallback; the nil
	// metrics client then yields 0/0 utilisation.
	prom := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer prom.Close()

	c := &autoscalingMetricsCollector{
		k8sClient:     fake.NewSimpleClientset(testObjects()...),
		prometheusURL: prom.URL,
		httpClient:    prom.Client(),
	}
	batch, err := c.collectMetrics(context.Background())
	if err != nil {
		t.Fatalf("collectMetrics: %v", err)
	}
	items, ok := batch.Items.([]interface{})
	if !ok || len(items) != 1 {
		t.Fatalf("unexpected items %T %v", batch.Items, batch.Items)
	}
	kpis, ok := items[0].(AutoscalingKpis)
	if !ok {
		t.Fatalf("unexpected item type %T", items[0])
	}
	// Meaning unchanged: currently Pending pods with PodScheduled=False/Unschedulable.
	if kpis.PendingPodsResolved != 1 {
		t.Errorf("PendingPodsResolved = %d, want 1", kpis.PendingPodsResolved)
	}
	if kpis.AvgNodeUtilizationCpu != 0 || kpis.AvgNodeUtilizationMemory != 0 {
		t.Errorf("utilisation = %d/%d, want 0/0 on fallback with nil metrics client",
			kpis.AvgNodeUtilizationCpu, kpis.AvgNodeUtilizationMemory)
	}
}

// TestAutoscalingKpisKeySetFrozen freezes the autoscaling-metrics JSON contract.
// The backend reads this object strictly (EksAutoscalingService.readMetrics with a
// plain ObjectMapper into AutoscalingKpisDto): any added or renamed key makes the
// read fail and zeroes every KPI tile. Do not change this set without first making
// the backend DTO tolerant of unknown properties.
func TestAutoscalingKpisKeySetFrozen(t *testing.T) {
	raw, err := json.Marshal(AutoscalingKpis{})
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	got := make([]string, 0, len(m))
	for k := range m {
		got = append(got, k)
	}
	sort.Strings(got)
	want := []string{
		"activeRebalances", "avgNodeUtilizationCpu", "avgNodeUtilizationMemory",
		"costSavedThisWeek", "nodesSavedToday", "pendingPodsResolved",
		"spotRatio", "totalManagedNodes",
	}
	if len(got) != len(want) {
		t.Fatalf("keys = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("keys = %v, want %v", got, want)
		}
	}
}
