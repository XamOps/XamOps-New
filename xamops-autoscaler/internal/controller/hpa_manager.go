package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"time"

	autoscalingv2 "k8s.io/api/autoscaling/v2"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/policy"
)

// HPAManager creates, updates, and deletes HorizontalPodAutoscalers based on
// recommendations fetched from the XamOps backend policy API.
// It also generates HPA recommendations by analysing actual CPU usage from Prometheus.
type HPAManager struct {
	k8s           kubernetes.Interface
	prometheusURL string
	httpClient    *http.Client
	dryRun        bool
}

func NewHPAManager(k8s kubernetes.Interface, prometheusURL string, dryRun bool) *HPAManager {
	return &HPAManager{
		k8s:           k8s,
		prometheusURL: prometheusURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
		dryRun:        dryRun,
	}
}

// HPARecommendation is the output of the analysis phase pushed back to XamOps.
type HPARecommendation struct {
	Namespace       string  `json:"namespace"`
	WorkloadName    string  `json:"workloadName"`
	WorkloadKind    string  `json:"workloadKind"` // Deployment | StatefulSet
	ContainerName   string  `json:"containerName"`
	CurrentHPA      bool    `json:"currentHpa"`     // HPA already exists?
	RecommendMin    int32   `json:"recommendMin"`
	RecommendMax    int32   `json:"recommendMax"`
	RecommendTarget int32   `json:"recommendTargetCpuPct"` // CPU utilization target %
	P90CPU          float64 `json:"p90Cpu"`                // 90th percentile CPU (cores)
	CurrentReplicas int32   `json:"currentReplicas"`
	Applied         bool    `json:"applied"`
	AppliedAt       string  `json:"appliedAt,omitempty"`
	Reason          string  `json:"reason,omitempty"` // why skipped if not applied
}

// HPAApplyRequest is received from XamOps backend telling the agent to apply an HPA.
type HPAApplyRequest struct {
	Namespace   string `json:"namespace"`
	WorkloadName string `json:"workloadName"`
	WorkloadKind string `json:"workloadKind"`
	MinReplicas int32  `json:"minReplicas"`
	MaxReplicas int32  `json:"maxReplicas"`
	TargetCPUPct int32 `json:"targetCpuPct"`
	Delete      bool   `json:"delete"` // true = remove the HPA
}

// Analyse scans all Deployments and StatefulSets without HPAs and recommends settings.
// Returns the recommendations to be pushed back to the backend (collector: "hpa-recommendations").
func (h *HPAManager) Analyse(ctx context.Context, guards policy.SafetyGuards) ([]HPARecommendation, error) {
	results := make([]HPARecommendation, 0)

	// Get existing HPAs to know what's already managed
	existing, err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list HPAs: %w", err)
	}
	hpaMap := map[string]bool{} // "namespace/name" -> true
	for _, hpa := range existing.Items {
		key := hpa.Namespace + "/" + hpa.Spec.ScaleTargetRef.Name
		hpaMap[key] = true
	}

	// Analyse Deployments
	deployments, err := h.k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list deployments: %w", err)
	}
	for _, dep := range deployments.Items {
		if guards.ProtectCriticalNamespaces && isCriticalNS(dep.Namespace, guards.CriticalNamespaces) {
			continue
		}
		if dep.Spec.Replicas != nil && *dep.Spec.Replicas == 0 {
			continue // skip scaled-to-zero deployments
		}

		rec := h.analyseWorkload(ctx, dep.Namespace, dep.Name, "Deployment",
			dep.Status.ReadyReplicas, hpaMap)
		results = append(results, rec)
	}

	// Analyse StatefulSets
	statefulSets, err := h.k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: hpa-manager list statefulsets: %v", err)
	} else {
		for _, ss := range statefulSets.Items {
			if guards.ProtectCriticalNamespaces && isCriticalNS(ss.Namespace, guards.CriticalNamespaces) {
				continue
			}
			if guards.ProtectStatefulSets {
				continue // statefulsets protected by default unless guard disabled
			}
			rec := h.analyseWorkload(ctx, ss.Namespace, ss.Name, "StatefulSet",
				ss.Status.ReadyReplicas, hpaMap)
			results = append(results, rec)
		}
	}

	return results, nil
}

func (h *HPAManager) analyseWorkload(
	ctx context.Context,
	namespace, name, kind string,
	currentReplicas int32,
	hpaMap map[string]bool,
) HPARecommendation {
	rec := HPARecommendation{
		Namespace:       namespace,
		WorkloadName:    name,
		WorkloadKind:    kind,
		CurrentReplicas: currentReplicas,
		CurrentHPA:      hpaMap[namespace+"/"+name],
	}

	// Query P90 CPU usage for this workload from Prometheus
	p90CPU, err := h.queryP90CPU(ctx, namespace, name)
	if err != nil {
		log.Printf("DEBUG: hpa-manager p90 CPU %s/%s: %v", namespace, name, err)
		rec.Reason = "prometheus unavailable for CPU analysis"
		return rec
	}
	rec.P90CPU = roundTo2(p90CPU)

	// Recommend min = 2 if multi-replica, else 1
	rec.RecommendMin = 1
	if currentReplicas > 1 {
		rec.RecommendMin = 2
	}

	// Recommend max = max(3, currentReplicas*2) capped at 20
	rec.RecommendMax = currentReplicas * 2
	if rec.RecommendMax < 3 {
		rec.RecommendMax = 3
	}
	if rec.RecommendMax > 20 {
		rec.RecommendMax = 20
	}

	// Target CPU utilization: if P90 > 70% suggest 70%, else 80%
	if p90CPU > 70 {
		rec.RecommendTarget = 70
	} else {
		rec.RecommendTarget = 80
	}

	return rec
}

// Apply creates or updates an HPA as directed by the XamOps backend.
func (h *HPAManager) Apply(ctx context.Context, req HPAApplyRequest) error {
	if req.Delete {
		return h.deleteHPA(ctx, req.Namespace, req.WorkloadName)
	}
	return h.upsertHPA(ctx, req)
}

func (h *HPAManager) upsertHPA(ctx context.Context, req HPAApplyRequest) error {
	minReplicas := req.MinReplicas
	cpuTarget := req.TargetCPUPct

	desired := &autoscalingv2.HorizontalPodAutoscaler{
		ObjectMeta: metav1.ObjectMeta{
			Name:      req.WorkloadName + "-xamops-hpa",
			Namespace: req.Namespace,
			Labels: map[string]string{
				"app.kubernetes.io/managed-by": "xamops-automation",
			},
			Annotations: map[string]string{
				"xamops.io/managed":  "true",
				"xamops.io/modified": time.Now().UTC().Format(time.RFC3339),
			},
		},
		Spec: autoscalingv2.HorizontalPodAutoscalerSpec{
			ScaleTargetRef: autoscalingv2.CrossVersionObjectReference{
				APIVersion: "apps/v1",
				Kind:       req.WorkloadKind,
				Name:       req.WorkloadName,
			},
			MinReplicas: &minReplicas,
			MaxReplicas: req.MaxReplicas,
			Metrics: []autoscalingv2.MetricSpec{
				{
					Type: autoscalingv2.ResourceMetricSourceType,
					Resource: &autoscalingv2.ResourceMetricSource{
						Name: corev1.ResourceCPU,
						Target: autoscalingv2.MetricTarget{
							Type:               autoscalingv2.UtilizationMetricType,
							AverageUtilization: &cpuTarget,
						},
					},
				},
			},
		},
	}

	if h.dryRun {
		log.Printf("DRY-RUN: would upsert HPA %s/%s (min=%d max=%d target=%d%%)",
			req.Namespace, desired.Name, req.MinReplicas, req.MaxReplicas, req.TargetCPUPct)
		return nil
	}

	_, err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Get(ctx, desired.Name, metav1.GetOptions{})
	if errors.IsNotFound(err) {
		_, createErr := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Create(ctx, desired, metav1.CreateOptions{})
		if createErr != nil {
			return fmt.Errorf("create HPA %s/%s: %w", req.Namespace, desired.Name, createErr)
		}
		log.Printf("HPA created: %s/%s", req.Namespace, desired.Name)
		return nil
	}
	if err != nil {
		return fmt.Errorf("get HPA %s/%s: %w", req.Namespace, desired.Name, err)
	}

	// Update existing
	existing, _ := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Get(ctx, desired.Name, metav1.GetOptions{})
	existing.Spec = desired.Spec
	if existing.Annotations == nil {
		existing.Annotations = map[string]string{}
	}
	existing.Annotations["xamops.io/modified"] = time.Now().UTC().Format(time.RFC3339)

	_, err = h.k8s.AutoscalingV2().HorizontalPodAutoscalers(req.Namespace).Update(ctx, existing, metav1.UpdateOptions{})
	if err != nil {
		return fmt.Errorf("update HPA %s/%s: %w", req.Namespace, desired.Name, err)
	}
	log.Printf("HPA updated: %s/%s", req.Namespace, desired.Name)
	return nil
}

func (h *HPAManager) deleteHPA(ctx context.Context, namespace, workloadName string) error {
	hpaName := workloadName + "-xamops-hpa"
	if h.dryRun {
		log.Printf("DRY-RUN: would delete HPA %s/%s", namespace, hpaName)
		return nil
	}
	err := h.k8s.AutoscalingV2().HorizontalPodAutoscalers(namespace).Delete(ctx, hpaName, metav1.DeleteOptions{})
	if errors.IsNotFound(err) {
		return nil // already gone
	}
	if err != nil {
		return fmt.Errorf("delete HPA %s/%s: %w", namespace, hpaName, err)
	}
	log.Printf("HPA deleted: %s/%s", namespace, hpaName)
	return nil
}

// ── Prometheus helpers ────────────────────────────────────────────────────────

// queryP90CPU returns the 90th percentile CPU usage (cores) for a workload over 1h.
func (h *HPAManager) queryP90CPU(ctx context.Context, namespace, workload string) (float64, error) {
	// Uses kube_pod_labels to match pods by owner (Deployment/RS label)
	query := fmt.Sprintf(
		`quantile(0.9, sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="%s",container!="",container!="POD"}[5m])))`,
		namespace)

	endpoint := fmt.Sprintf("%s/api/v1/query?query=%s",
		h.prometheusURL, url.QueryEscape(query))

	body, err := hpaHTTPGet(ctx, h.httpClient, endpoint)
	if err != nil {
		return 0, err
	}

	var resp struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Value []interface{} `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return 0, err
	}
	if resp.Status != "success" || len(resp.Data.Result) == 0 {
		return 0, fmt.Errorf("no prometheus results for %s/%s", namespace, workload)
	}
	vals := resp.Data.Result[0].Value
	if len(vals) < 2 {
		return 0, fmt.Errorf("unexpected prometheus value format")
	}
	s, ok := vals[1].(string)
	if !ok {
		return 0, fmt.Errorf("unexpected value type")
	}
	return strconv.ParseFloat(s, 64)
}

func hpaHTTPGet(ctx context.Context, client *http.Client, endpoint string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 512)
	for {
		n, readErr := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if readErr != nil {
			break
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return buf, nil
}
