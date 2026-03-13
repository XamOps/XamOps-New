package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"

	"github.com/xamops/agent/internal/config"
)

const agentVersion = "1.0.0"

// Manager orchestrates all data collectors
type Manager struct {
	cfg           *config.Config
	k8sClient     kubernetes.Interface
	dynamicClient dynamic.Interface
	metricsClient metricsv.Interface
	httpClient    *http.Client
}

// NewCollectorManager creates a Manager with in-cluster Kubernetes clients
func NewCollectorManager(cfg *config.Config) *Manager {
	m := &Manager{
		cfg:        cfg,
		httpClient: &http.Client{Timeout: 30 * time.Second},
	}

	k8sCfg, err := rest.InClusterConfig()
	if err != nil {
		log.Printf("WARN: in-cluster config unavailable, running in observer-only mode: %v", err)
		return m
	}

	k8sClient, err := kubernetes.NewForConfig(k8sCfg)
	if err != nil {
		log.Printf("WARN: could not create k8s client: %v", err)
		return m
	}
	m.k8sClient = k8sClient

	dynamicClient, err := dynamic.NewForConfig(k8sCfg)
	if err != nil {
		log.Printf("WARN: could not create dynamic client: %v", err)
	} else {
		m.dynamicClient = dynamicClient
	}

	metricsClient, err := metricsv.NewForConfig(k8sCfg)
	if err != nil {
		log.Printf("WARN: metrics-server client unavailable (metrics-server may not be installed): %v", err)
	} else {
		m.metricsClient = metricsClient
	}

	return m
}

// CollectAll runs all enabled collectors and returns the combined payload
func (m *Manager) CollectAll() (*AgentPayload, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	payload := &AgentPayload{
		SchemaVersion:  "1.0",
		AgentVersion:   agentVersion,
		ClusterName:    m.cfg.ClusterName,
		CloudAccountID: m.cfg.CloudAccountID,
		CollectedAt:    time.Now().UTC(),
		Batches:        []DataBatch{},
	}

	// ── DB query stats (runs even without k8s — just needs TCP access to RDS) ──
	if m.cfg.CollectorDB && len(m.cfg.DBDSNs) > 0 {
		if batch, err := collectDBQueryStats(ctx, m.cfg.DBDSNs, m.cfg.DBQueryLimit); err == nil {
			if batch.Count > 0 {
				payload.Batches = append(payload.Batches, batch)
			}
		} else {
			log.Printf("WARN: db-query-stats: %v", err)
		}
	}

	if m.k8sClient == nil {
		if m.cfg.CollectorDB {
			// Running as a standalone DB agent (no k8s cluster) — DB batch already collected above
			return payload, nil
		}
		log.Println("WARN: no k8s client — skipping all collectors")
		return payload, nil
	}

	// ── 1. Cluster metadata (always on if CollectorMetrics) ──────────────
	if m.cfg.CollectorMetrics {
		if batch, err := collectClusterMetadata(ctx, m.k8sClient); err == nil {
			payload.Batches = append(payload.Batches, batch)
		} else {
			log.Printf("WARN: cluster-metadata: %v", err)
		}
	}

	// ── 2. Trivy vulnerability reports ───────────────────────────────────
	if m.cfg.CollectorTrivy && m.dynamicClient != nil {
		if batch, err := collectTrivy(ctx, m.dynamicClient); err == nil {
			payload.Batches = append(payload.Batches, batch)
		} else {
			log.Printf("WARN: trivy: %v", err)
		}
	}

	// ── 3. Kubescape compliance scans ────────────────────────────────────
	if m.cfg.CollectorKubescape && m.dynamicClient != nil {
		if batch, err := collectKubescape(ctx, m.dynamicClient); err == nil {
			payload.Batches = append(payload.Batches, batch)
		} else {
			log.Printf("WARN: kubescape: %v", err)
		}
	}

	// ── 4. OpenCost allocations ──────────────────────────────────────────
	if m.cfg.CollectorOpenCost {
		if batch, err := collectOpenCost(ctx, m.httpClient, m.cfg.OpenCostServiceURL, m.cfg.OpenCostWindow); err == nil {
			payload.Batches = append(payload.Batches, batch)
		} else {
			log.Printf("WARN: opencost: %v", err)
		}
	}

	// ── 5. Karpenter data ────────────────────────────────────────────────
	if m.cfg.CollectorKarpenter && m.dynamicClient != nil {
		if batch, err := collectKarpenter(ctx, m.dynamicClient); err == nil {
			payload.Batches = append(payload.Batches, batch)
		} else {
			log.Printf("WARN: karpenter: %v", err)
		}
	}

	// ── 6. Autoscaling metrics (observation-only) ────────────────────────
	// Pushes autoscaling-metrics (single KPI object) and autoscaling-events (list).
	// Does NOT change any cluster state — read-only Prometheus + k8s API queries.
	if m.cfg.CollectorAutoscaling {
		ac := &autoscalingMetricsCollector{
			k8sClient:     m.k8sClient,
			metricsClient: m.metricsClient,
			prometheusURL: m.cfg.PrometheusURL,
			httpClient:    m.httpClient,
		}
		if metricsBatch, err := ac.collectMetrics(ctx); err == nil {
			payload.Batches = append(payload.Batches, metricsBatch)
		} else {
			log.Printf("WARN: autoscaling-metrics: %v", err)
		}
		if eventsBatch, err := ac.collectEvents(ctx); err == nil && eventsBatch.Count > 0 {
			payload.Batches = append(payload.Batches, eventsBatch)
		}
	}

	return payload, nil
}

// ── Helpers shared by multiple collectors ─────────────────────────────────────

// listCRD lists all instances of a CRD across all namespaces using the dynamic client
func listCRD(ctx context.Context, dc dynamic.Interface, group, version, resource string) ([]map[string]interface{}, error) {
	gvr := schema.GroupVersionResource{Group: group, Version: version, Resource: resource}
	list, err := dc.Resource(gvr).Namespace("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list %s/%s: %w", resource, version, err)
	}
	items := make([]map[string]interface{}, 0, len(list.Items))
	for _, item := range list.Items {
		items = append(items, item.Object)
	}
	return items, nil
}

// httpGet performs a simple GET and returns the response body bytes
func httpGet(ctx context.Context, client *http.Client, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d from %s", resp.StatusCode, url)
	}
	return io.ReadAll(resp.Body)
}

// safeString returns a string value from a nested map path, empty string if missing
func safeString(obj map[string]interface{}, keys ...string) string {
	cur := obj
	for i, k := range keys {
		v, ok := cur[k]
		if !ok {
			return ""
		}
		if i == len(keys)-1 {
			if s, ok := v.(string); ok {
				return s
			}
			return fmt.Sprintf("%v", v)
		}
		next, ok := v.(map[string]interface{})
		if !ok {
			return ""
		}
		cur = next
	}
	return ""
}

// safeMap returns a nested map at the given key path
func safeMap(obj map[string]interface{}, keys ...string) map[string]interface{} {
	cur := obj
	for _, k := range keys {
		v, ok := cur[k]
		if !ok {
			return nil
		}
		next, ok := v.(map[string]interface{})
		if !ok {
			return nil
		}
		cur = next
	}
	return cur
}

// safeInt64 extracts an int64 from interface{} (handles float64 from JSON unmarshaling)
func safeInt64(v interface{}) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int64:
		return n
	case int:
		return int64(n)
	}
	return 0
}

// jsonMarshal is a shorthand for json.Marshal that returns "" on error
func jsonMarshal(v interface{}) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// containsAny checks if s contains any of the substrings
func containsAny(s string, subs ...string) bool {
	for _, sub := range subs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}
