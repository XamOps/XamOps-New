package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"
)

// AutoscalingKpis matches the backend AutoscalingKpisDto exactly.
// Stored as a single JSON object (SINGLE_OBJECT_COLLECTORS on backend).
type AutoscalingKpis struct {
	NodesSavedToday   int     `json:"nodesSavedToday"`
	CostSavedThisWeek float64 `json:"costSavedThisWeek"`
	// PendingPodsResolved is a deprecated misnomer kept for contract compatibility:
	// this has always been the number of currently Pending pods with
	// PodScheduled=False/Unschedulable, not pods resolved. The backend parses this
	// object strictly (EksAutoscalingService.readMetrics, plain ObjectMapper), so no
	// key may be added here; the correctly named value is k8s-resources
	// summary.unscheduledPods.
	PendingPodsResolved      int `json:"pendingPodsResolved"`
	ActiveRebalances         int `json:"activeRebalances"`
	SpotRatio                int `json:"spotRatio"`
	AvgNodeUtilizationCpu    int `json:"avgNodeUtilizationCpu"`
	AvgNodeUtilizationMemory int `json:"avgNodeUtilizationMemory"`
	TotalManagedNodes        int `json:"totalManagedNodes"`
}

// AutoscalingEvent matches the backend AutoscalingEventDto.
type AutoscalingEvent struct {
	ID           string  `json:"id"`
	Timestamp    string  `json:"timestamp"`
	Type         string  `json:"type"`     // scale-out | scale-in | rebalance | workload-resize | spot-fallback | node-replace
	Severity     string  `json:"severity"` // info | warning | success
	Message      string  `json:"message"`
	Details      string  `json:"details,omitempty"`
	NodeCount    int     `json:"nodeCount,omitempty"`
	SavingsDelta float64 `json:"savingsDelta,omitempty"`
}

// autoscalingMetricsCollector is the read-only part of autoscaling observation.
// It does NOT modify any cluster state — only reads Prometheus + k8s API.
type autoscalingMetricsCollector struct {
	k8sClient     kubernetes.Interface
	metricsClient metricsv.Interface
	prometheusURL string
	httpClient    *http.Client
}

// collectMetrics returns a single-item batch (autoscaling-metrics) with KPI values.
// The backend stores this as a raw JSON object (not wrapped in an array) because
// "autoscaling-metrics" is in the SINGLE_OBJECT_COLLECTORS set.
func (c *autoscalingMetricsCollector) collectMetrics(ctx context.Context) (DataBatch, error) {
	kpis := AutoscalingKpis{}

	// ── 1. Total managed nodes + spot ratio (from k8s node labels) ───────
	nodes, err := c.k8sClient.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, fmt.Errorf("list nodes: %w", err)
	}
	kpis.TotalManagedNodes = len(nodes.Items)
	spotCount := 0
	for _, node := range nodes.Items {
		lbl := node.Labels
		if lbl["node-lifecycle"] == "spot" ||
			lbl["eks.amazonaws.com/capacityType"] == "SPOT" ||
			lbl["karpenter.sh/capacity-type"] == "spot" ||
			lbl["cloud.google.com/gke-spot"] == "true" ||
			lbl["kubernetes.azure.com/scalesetpriority"] == "spot" {
			spotCount++
		}
	}
	if kpis.TotalManagedNodes > 0 {
		kpis.SpotRatio = int(math.Round(float64(spotCount) / float64(kpis.TotalManagedNodes) * 100))
	}

	// ── 2. Unschedulable pod count ────────────────────────────────────────
	pods, err := c.k8sClient.CoreV1().Pods("").List(ctx, metav1.ListOptions{
		FieldSelector: "status.phase=Pending",
	})
	if err == nil {
		unschedulable := 0
		for _, pod := range pods.Items {
			for _, cond := range pod.Status.Conditions {
				if cond.Type == corev1.PodScheduled &&
					cond.Status == corev1.ConditionFalse &&
					cond.Reason == "Unschedulable" {
					unschedulable++
				}
			}
		}
		// Deprecated misnomer kept for contract compatibility: this is the number of
		// currently Pending pods with PodScheduled=False/Unschedulable, not pods
		// resolved. The backend parses this object strictly
		// (EksAutoscalingService.readMetrics, plain ObjectMapper), so no key may be
		// added here; the correctly named value is k8s-resources summary.unscheduledPods.
		kpis.PendingPodsResolved = unschedulable
	}

	// ── 3. CPU/Memory utilization — try Prometheus first, fallback to metrics-server ──
	cpuUtil, memUtil, promErr := c.queryPrometheus(ctx)
	if promErr != nil {
		log.Printf("DEBUG: Prometheus unavailable (%v), falling back to metrics-server", promErr)
		cpuUtil, memUtil = c.queryMetricsServer(ctx, nodes.Items)
	}
	kpis.AvgNodeUtilizationCpu = cpuUtil
	kpis.AvgNodeUtilizationMemory = memUtil

	return DataBatch{
		Collector:   "autoscaling-metrics",
		Items:       []interface{}{kpis}, // backend unwraps [0] due to SINGLE_OBJECT_COLLECTORS
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}

// collectEvents gathers recent Kubernetes events related to node/pod scheduling
// and emits them as autoscaling-events so the activity feed in XamOps UI populates.
func (c *autoscalingMetricsCollector) collectEvents(ctx context.Context) (DataBatch, error) {
	// Read Kubernetes events from the last hour (autoscaling-relevant reasons only)
	since := metav1.NewTime(time.Now().Add(-1 * time.Hour))
	eventList, err := c.k8sClient.CoreV1().Events("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, fmt.Errorf("list events: %w", err)
	}

	relevantReasons := map[string]string{
		"TriggeredScaleUp":   "scale-out",
		"SuccessfulCreate":   "scale-out",
		"ScaleUp":            "scale-out",
		"TriggeredScaleDown": "scale-in",
		"ScaleDown":          "scale-in",
		"NodeNotReady":       "node-replace",
		"Evicted":            "scale-in",
		"Preempting":         "scale-in",
	}

	events := make([]AutoscalingEvent, 0)
	for _, e := range eventList.Items {
		if e.LastTimestamp.Before(&since) {
			continue
		}
		eventType, relevant := relevantReasons[e.Reason]
		if !relevant {
			continue
		}
		severity := "info"
		if e.Type == corev1.EventTypeWarning {
			severity = "warning"
		}
		events = append(events, AutoscalingEvent{
			ID:        fmt.Sprintf("%s-%s", e.Name, e.UID),
			Timestamp: e.LastTimestamp.UTC().Format(time.RFC3339),
			Type:      eventType,
			Severity:  severity,
			Message:   fmt.Sprintf("[%s] %s: %s", e.Reason, e.InvolvedObject.Name, e.Message),
			Details:   e.Message,
		})
		if len(events) >= 50 {
			break
		}
	}

	if len(events) == 0 {
		return DataBatch{}, nil
	}

	return DataBatch{
		Collector:   "autoscaling-events",
		Items:       events,
		Count:       len(events),
		CollectedAt: time.Now().UTC(),
	}, nil
}

// ── Prometheus helpers ──────────────────────────────────────────────────────

type prometheusResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string `json:"resultType"`
		Result     []struct {
			Metric map[string]string `json:"metric"`
			Value  []interface{}     `json:"value"` // [timestamp, "value_string"]
		} `json:"result"`
	} `json:"data"`
}

func (c *autoscalingMetricsCollector) queryPrometheus(ctx context.Context) (cpuPercent, memPercent int, err error) {
	cpuQuery := `round(100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))))`
	memQuery := `round(100 * (1 - avg(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)))`

	cpu, err := c.promQuery(ctx, cpuQuery)
	if err != nil {
		return 0, 0, err
	}
	mem, err := c.promQuery(ctx, memQuery)
	if err != nil {
		return 0, 0, err
	}

	return clampPercent(cpu), clampPercent(mem), nil
}

func (c *autoscalingMetricsCollector) promQuery(ctx context.Context, query string) (float64, error) {
	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s", c.prometheusURL, url.QueryEscape(query))
	body, err := httpGet(ctx, c.httpClient, endpoint)
	if err != nil {
		return 0, err
	}

	var pr prometheusResponse
	if err := json.Unmarshal(body, &pr); err != nil {
		return 0, fmt.Errorf("parse prometheus response: %w", err)
	}
	if pr.Status != "success" || len(pr.Data.Result) == 0 {
		return 0, fmt.Errorf("prometheus returned no results")
	}

	vals := pr.Data.Result[0].Value
	if len(vals) < 2 {
		return 0, fmt.Errorf("unexpected prometheus value format")
	}
	s, ok := vals[1].(string)
	if !ok {
		return 0, fmt.Errorf("unexpected value type")
	}
	return strconv.ParseFloat(s, 64)
}

// ── metrics-server fallback ─────────────────────────────────────────────────

func (c *autoscalingMetricsCollector) queryMetricsServer(ctx context.Context, nodes []corev1.Node) (cpuPercent, memPercent int) {
	if c.metricsClient == nil {
		return 0, 0
	}

	nodeMetrics, err := c.metricsClient.MetricsV1beta1().NodeMetricses().List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("DEBUG: metrics-server unavailable: %v", err)
		return 0, 0
	}

	// Build capacity map
	capacityCPU := map[string]int64{}
	capacityMem := map[string]int64{}
	for _, n := range nodes {
		capacityCPU[n.Name] = n.Status.Capacity.Cpu().MilliValue()
		capacityMem[n.Name] = n.Status.Capacity.Memory().Value()
	}

	var totalCPUUsed, totalCPUCap, totalMemUsed, totalMemCap int64
	for _, nm := range nodeMetrics.Items {
		totalCPUUsed += nm.Usage.Cpu().MilliValue()
		totalMemUsed += nm.Usage.Memory().Value()
		totalCPUCap += capacityCPU[nm.Name]
		totalMemCap += capacityMem[nm.Name]
	}

	if totalCPUCap > 0 {
		cpuPercent = clampPercent(float64(totalCPUUsed) / float64(totalCPUCap) * 100)
	}
	if totalMemCap > 0 {
		memPercent = clampPercent(float64(totalMemUsed) / float64(totalMemCap) * 100)
	}
	return
}

func clampPercent(v float64) int {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return 0
	}
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return int(math.Round(v))
}
