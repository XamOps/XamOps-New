package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// prometheusMetricsCollector fetches all observability metrics from the in-cluster
// Prometheus instance: node utilization, namespace usage, cost metrics (cost-exporter),
// Falco security events, pod restart rates, and top resource consumers.
type prometheusMetricsCollector struct {
	prometheusURL string
	httpClient    *http.Client
}

// collect runs all Prometheus queries and returns a single "prometheus-metrics" batch.
func (c *prometheusMetricsCollector) collect(ctx context.Context) (DataBatch, error) {
	metrics := PrometheusMetrics{
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
	}

	// ── Cluster-level CPU/Memory utilization ─────────────────────────────────
	if v, err := c.queryScalar(ctx, `round(100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))), 0.01)`); err == nil {
		metrics.ClusterCPUUsagePct = v
	} else {
		log.Printf("DEBUG: prometheus cluster CPU: %v", err)
	}

	if v, err := c.queryScalar(ctx, `round(100 * (1 - avg(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)), 0.01)`); err == nil {
		metrics.ClusterMemUsagePct = v
	}

	// ── Per-node utilization ─────────────────────────────────────────────────
	// CPU usage per node
	cpuByNode, _ := c.queryLabels(ctx,
		`round(100 * (1 - avg by (node) (rate(node_cpu_seconds_total{mode="idle"}[5m]))), 0.01)`,
		"node")
	// Memory usage per node
	memByNode, _ := c.queryLabels(ctx,
		`round(100 * (1 - avg by (node) (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)), 0.01)`,
		"node")
	// CPU cores used per node
	cpuCoresByNode, _ := c.queryLabels(ctx,
		`sum by (node) (rate(node_cpu_seconds_total{mode!="idle"}[5m]))`,
		"node")
	// Memory GB used per node
	memGBByNode, _ := c.queryLabels(ctx,
		`sum by (node) (node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / 1073741824`,
		"node")

	for nodeName, cpuPct := range cpuByNode {
		metrics.NodeUtilization = append(metrics.NodeUtilization, NodeUtilizationMetric{
			NodeName:     nodeName,
			CPUUsagePct:  cpuPct,
			MemUsagePct:  memByNode[nodeName],
			CPUCoresUsed: cpuCoresByNode[nodeName],
			MemGBUsed:    memGBByNode[nodeName],
		})
	}

	// ── Per-namespace resource usage ─────────────────────────────────────────
	// CPU usage (actual) per namespace
	nsCPU, _ := c.queryLabels(ctx,
		`sum by (namespace) (rate(container_cpu_usage_seconds_total{container!="",container!="POD"}[5m]))`,
		"namespace")
	// Memory usage per namespace
	nsMem, _ := c.queryLabels(ctx,
		`sum by (namespace) (container_memory_working_set_bytes{container!="",container!="POD"}) / 1073741824`,
		"namespace")
	// CPU requests per namespace
	nsCPUReq, _ := c.queryLabels(ctx,
		`sum by (namespace) (kube_pod_container_resource_requests{resource="cpu",unit="core"})`,
		"namespace")
	// Memory requests per namespace
	nsMemReq, _ := c.queryLabels(ctx,
		`sum by (namespace) (kube_pod_container_resource_requests{resource="memory",unit="byte"}) / 1073741824`,
		"namespace")

	for ns, cpu := range nsCPU {
		metrics.NamespaceUtilization = append(metrics.NamespaceUtilization, NamespaceUsageMetric{
			Namespace:       ns,
			CPUCores:        cpu,
			MemGB:           nsMem[ns],
			CPURequestCores: nsCPUReq[ns],
			MemRequestGB:    nsMemReq[ns],
		})
	}

	// ── Cost metrics (OpenCost / kube-cost-exporter / opencost-exporter) ─────
	// These metrics are exposed when opencost or a cost exporter is running.
	// Gracefully skip if not present.
	costMetrics := c.collectCostMetrics(ctx)
	if costMetrics != nil {
		metrics.CostMetrics = costMetrics
	}

	// ── Falco security events ────────────────────────────────────────────────
	// falco_events_total is the standard counter exposed by Falco + falco-exporter.
	falcoRows, err := c.queryMatrix(ctx,
		`sum by (rule, priority, namespace, pod) (increase(falco_events_total[1h]))`)
	if err == nil {
		for _, row := range falcoRows {
			count, _ := strconv.ParseFloat(row.value, 64)
			if count == 0 {
				continue
			}
			metrics.FalcoEvents = append(metrics.FalcoEvents, FalcoEvent{
				Rule:        row.labels["rule"],
				Priority:    row.labels["priority"],
				Namespace:   row.labels["namespace"],
				Pod:         row.labels["pod"],
				Count:       count,
				CollectedAt: metrics.CollectedAt,
			})
		}
	}

	// ── Pod restart rates ────────────────────────────────────────────────────
	restartRows, err := c.queryMatrix(ctx,
		`sum by (pod, namespace, container) (rate(kube_pod_container_status_restarts_total[1h])) * 3600`)
	if err == nil {
		for _, row := range restartRows {
			rate, _ := strconv.ParseFloat(row.value, 64)
			if rate < 0.01 {
				continue
			}
			metrics.PodRestartRates = append(metrics.PodRestartRates, PodRestartMetric{
				PodName:       row.labels["pod"],
				Namespace:     row.labels["namespace"],
				ContainerName: row.labels["container"],
				RestartRate:   roundTo2(rate),
			})
		}
	}

	// ── Top CPU consumers (pods) ─────────────────────────────────────────────
	cpuRows, err := c.queryMatrix(ctx,
		`topk(20, sum by (pod, namespace, container) (rate(container_cpu_usage_seconds_total{container!="",container!="POD"}[5m])))`)
	if err == nil {
		for _, row := range cpuRows {
			v, _ := strconv.ParseFloat(row.value, 64)
			metrics.TopCPUPods = append(metrics.TopCPUPods, PodResourceMetric{
				PodName:       row.labels["pod"],
				Namespace:     row.labels["namespace"],
				ContainerName: row.labels["container"],
				Value:         roundTo2(v),
			})
		}
	}

	// ── Top Memory consumers (pods) ──────────────────────────────────────────
	memRows, err := c.queryMatrix(ctx,
		`topk(20, sum by (pod, namespace, container) (container_memory_working_set_bytes{container!="",container!="POD"}))`)
	if err == nil {
		for _, row := range memRows {
			v, _ := strconv.ParseFloat(row.value, 64)
			metrics.TopMemoryPods = append(metrics.TopMemoryPods, PodResourceMetric{
				PodName:       row.labels["pod"],
				Namespace:     row.labels["namespace"],
				ContainerName: row.labels["container"],
				Value:         roundTo2(v),
			})
		}
	}

	// ── Network I/O per namespace ────────────────────────────────────────────
	netInRows, _ := c.queryMatrix(ctx,
		`sum by (namespace) (rate(container_network_receive_bytes_total{container!="POD"}[5m]))`)
	netOutRows, _ := c.queryMatrix(ctx,
		`sum by (namespace) (rate(container_network_transmit_bytes_total{container!="POD"}[5m]))`)

	netInMap := rowsToMap(netInRows, "namespace")
	netOutMap := rowsToMap(netOutRows, "namespace")

	for ns, inRate := range netInMap {
		metrics.NetworkMetrics = append(metrics.NetworkMetrics, NamespaceNetworkMetric{
			Namespace:      ns,
			BytesInPerSec:  roundTo2(inRate),
			BytesOutPerSec: roundTo2(netOutMap[ns]),
		})
	}
	for ns, outRate := range netOutMap {
		if _, already := netInMap[ns]; !already {
			metrics.NetworkMetrics = append(metrics.NetworkMetrics, NamespaceNetworkMetric{
				Namespace:      ns,
				BytesOutPerSec: roundTo2(outRate),
			})
		}
	}

	return DataBatch{
		Collector:   "prometheus-metrics",
		Items:       []PrometheusMetrics{metrics},
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}

// collectCostMetrics tries several Prometheus metric names used by common cost exporters.
// Returns nil if no cost metrics are found (exporter not installed).
func (c *prometheusMetricsCollector) collectCostMetrics(ctx context.Context) *ClusterCostMetrics {
	// OpenCost / kube-cost-exporter compatible queries
	// Try opencost metrics first (opencost_cluster_hourly_cost), then node-cost-exporter
	totalCost, errTotal := c.queryScalar(ctx,
		`sum(node_total_hourly_cost)`)
	if errTotal != nil {
		// Try opencost naming
		totalCost, errTotal = c.queryScalar(ctx,
			`sum(opencost:cluster:hourly_cost)`)
	}
	if errTotal != nil {
		return nil // no cost exporter metrics available
	}

	cpuCost, _ := c.queryScalar(ctx, `sum(node_cpu_hourly_cost)`)
	memCost, _ := c.queryScalar(ctx, `sum(node_ram_hourly_cost)`)

	// Wasted resource percentages
	wastedCPU, _ := c.queryScalar(ctx, `
		100 * (
			1 - sum(kube_pod_container_resource_requests{resource="cpu",unit="core"}) /
			sum(kube_node_status_allocatable{resource="cpu",unit="core"})
		)`)
	wastedMem, _ := c.queryScalar(ctx, `
		100 * (
			1 - sum(kube_pod_container_resource_requests{resource="memory",unit="byte"}) /
			sum(kube_node_status_allocatable{resource="memory",unit="byte"})
		)`)

	return &ClusterCostMetrics{
		TotalHourlyCost:     roundTo2(totalCost),
		CPUHourlyCost:       roundTo2(cpuCost),
		MemoryHourlyCost:    roundTo2(memCost),
		MonthlyEstimate:     roundTo2(totalCost * 730),
		WastedCPUPercent:    roundTo2(wastedCPU),
		WastedMemoryPercent: roundTo2(wastedMem),
	}
}

// ── Prometheus HTTP query helpers ────────────────────────────────────────────

type promQueryRow struct {
	labels map[string]string
	value  string
}

type promAPIResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string `json:"resultType"`
		Result     []struct {
			Metric map[string]string `json:"metric"`
			Value  []interface{}     `json:"value"` // [timestamp, "value"]
		} `json:"result"`
	} `json:"data"`
}

// queryScalar executes a PromQL instant query and returns the first scalar result.
func (c *prometheusMetricsCollector) queryScalar(ctx context.Context, query string) (float64, error) {
	rows, err := c.queryMatrix(ctx, query)
	if err != nil || len(rows) == 0 {
		return 0, fmt.Errorf("no results for query: %s: %w", truncate(query, 80), err)
	}
	v, err := strconv.ParseFloat(rows[0].value, 64)
	if err != nil {
		return 0, err
	}
	return v, nil
}

// queryLabels executes a PromQL query and returns a map of labelValue → float64.
func (c *prometheusMetricsCollector) queryLabels(ctx context.Context, query, labelKey string) (map[string]float64, error) {
	rows, err := c.queryMatrix(ctx, query)
	if err != nil {
		return nil, err
	}
	result := make(map[string]float64, len(rows))
	for _, row := range rows {
		key := row.labels[labelKey]
		if key == "" {
			continue
		}
		v, _ := strconv.ParseFloat(row.value, 64)
		result[key] = v
	}
	return result, nil
}

// queryMatrix executes a PromQL instant query and returns all result rows.
func (c *prometheusMetricsCollector) queryMatrix(ctx context.Context, query string) ([]promQueryRow, error) {
	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s",
		strings.TrimRight(c.prometheusURL, "/"),
		url.QueryEscape(strings.TrimSpace(query)))

	body, err := httpGet(ctx, c.httpClient, endpoint)
	if err != nil {
		return nil, fmt.Errorf("prometheus HTTP: %w", err)
	}

	var resp promAPIResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("prometheus parse: %w", err)
	}
	if resp.Status != "success" {
		return nil, fmt.Errorf("prometheus returned status: %s", resp.Status)
	}

	rows := make([]promQueryRow, 0, len(resp.Data.Result))
	for _, r := range resp.Data.Result {
		val := ""
		if len(r.Value) >= 2 {
			if s, ok := r.Value[1].(string); ok {
				val = s
			}
		}
		rows = append(rows, promQueryRow{labels: r.Metric, value: val})
	}
	return rows, nil
}

func rowsToMap(rows []promQueryRow, labelKey string) map[string]float64 {
	m := make(map[string]float64, len(rows))
	for _, row := range rows {
		key := row.labels[labelKey]
		if key == "" {
			continue
		}
		v, _ := strconv.ParseFloat(row.value, 64)
		m[key] = v
	}
	return m
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

// collectPrometheusMetrics is the package-level entry point called from manager.go
func collectPrometheusMetrics(ctx context.Context, prometheusURL string, httpClient *http.Client) (DataBatch, error) {
	c := &prometheusMetricsCollector{
		prometheusURL: prometheusURL,
		httpClient:    httpClient,
	}
	return c.collect(ctx)
}
