package controller

import (
	"context"
	"fmt"
	"log"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/policy"
)

// VPA GVR — VerticalPodAutoscaler CRD from k8s.io/autoscaler/vertical-pod-autoscaler
var vpaGVR = schema.GroupVersionResource{
	Group:    "autoscaling.k8s.io",
	Version:  "v1",
	Resource: "verticalpodautoscalers",
}

// VPAManager creates and manages VerticalPodAutoscaler objects based on XamOps
// policy recommendations. It also reads VPA status (recommendations) and pushes
// them back to the backend so the XamOps UI can display them.
// VPA must already be installed in the cluster (helm install vpa ...).
type VPAManager struct {
	k8s           kubernetes.Interface
	dynamicClient dynamic.Interface
	dryRun        bool
}

func NewVPAManager(k8s kubernetes.Interface, dc dynamic.Interface, dryRun bool) *VPAManager {
	return &VPAManager{
		k8s:           k8s,
		dynamicClient: dc,
		dryRun:        dryRun,
	}
}

// VPARecommendation is what VPA recommends for a single container.
// Pushed back as collector: "vpa-recommendations".
type VPARecommendation struct {
	Namespace     string `json:"namespace"`
	WorkloadName  string `json:"workloadName"`
	WorkloadKind  string `json:"workloadKind"` // Deployment | StatefulSet | DaemonSet
	ContainerName string `json:"containerName"`
	// VPA lower/target/upper bounds
	LowerCPU     string `json:"lowerCpu,omitempty"`
	TargetCPU    string `json:"targetCpu,omitempty"`
	UpperCPU     string `json:"upperCpu,omitempty"`
	LowerMemory  string `json:"lowerMemory,omitempty"`
	TargetMemory string `json:"targetMemory,omitempty"`
	UpperMemory  string `json:"upperMemory,omitempty"`
	// Current requests for comparison
	CurrentCPU    string `json:"currentCpu,omitempty"`
	CurrentMemory string `json:"currentMemory,omitempty"`
	// VPA update mode applied
	UpdateMode  string `json:"updateMode"` // Off | Initial | Recreate | Auto
	CollectedAt string `json:"collectedAt"`
}

// VPAApplyRequest is sent from the XamOps backend to the autoscaler.
type VPAApplyRequest struct {
	Namespace    string `json:"namespace"`
	WorkloadName string `json:"workloadName"`
	WorkloadKind string `json:"workloadKind"`
	UpdateMode   string `json:"updateMode"` // Off | Initial | Recreate | Auto
	Delete       bool   `json:"delete"`     // true = remove the VPA object
}

// CollectRecommendations reads all existing VPA objects and extracts their recommendations.
// Returns results to be pushed as "vpa-recommendations".
func (v *VPAManager) CollectRecommendations(ctx context.Context) ([]VPARecommendation, error) {
	if v.dynamicClient == nil {
		return nil, fmt.Errorf("dynamic client not available")
	}

	vpaList, err := v.dynamicClient.Resource(vpaGVR).Namespace("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list VPAs: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339)
	results := make([]VPARecommendation, 0, len(vpaList.Items))

	for _, vpa := range vpaList.Items {
		namespace := vpa.GetNamespace()
		name := vpa.GetName()

		// Get target ref
		targetName, _, _ := unstructured.NestedString(vpa.Object, "spec", "targetRef", "name")
		targetKind, _, _ := unstructured.NestedString(vpa.Object, "spec", "targetRef", "kind")
		updateMode, _, _ := unstructured.NestedString(vpa.Object, "spec", "updatePolicy", "updateMode")

		if targetName == "" {
			targetName = name
		}

		// Read recommendation from status
		containerRecs, found, err := unstructured.NestedSlice(vpa.Object, "status", "recommendation", "containerRecommendations")
		if err != nil || !found {
			// VPA installed but no recommendations yet
			results = append(results, VPARecommendation{
				Namespace:    namespace,
				WorkloadName: targetName,
				WorkloadKind: targetKind,
				UpdateMode:   updateMode,
				CollectedAt:  now,
			})
			continue
		}

		for _, crRaw := range containerRecs {
			cr, ok := crRaw.(map[string]interface{})
			if !ok {
				continue
			}
			containerName, _ := cr["containerName"].(string)

			rec := VPARecommendation{
				Namespace:     namespace,
				WorkloadName:  targetName,
				WorkloadKind:  targetKind,
				ContainerName: containerName,
				UpdateMode:    updateMode,
				CollectedAt:   now,
			}

			if lower, ok := cr["lowerBound"].(map[string]interface{}); ok {
				rec.LowerCPU, _ = lower["cpu"].(string)
				rec.LowerMemory, _ = lower["memory"].(string)
			}
			if target, ok := cr["target"].(map[string]interface{}); ok {
				rec.TargetCPU, _ = target["cpu"].(string)
				rec.TargetMemory, _ = target["memory"].(string)
			}
			if upper, ok := cr["upperBound"].(map[string]interface{}); ok {
				rec.UpperCPU, _ = upper["cpu"].(string)
				rec.UpperMemory, _ = upper["memory"].(string)
			}

			results = append(results, rec)
		}
	}
	return results, nil
}

// Apply creates or deletes a VPA object as directed by the XamOps backend.
func (v *VPAManager) Apply(ctx context.Context, req VPAApplyRequest, guards policy.SafetyGuards) error {
	if guards.ProtectCriticalNamespaces && isCriticalNS(req.Namespace, guards.CriticalNamespaces) {
		log.Printf("VPA-Manager: skipping %s/%s — critical namespace", req.Namespace, req.WorkloadName)
		return nil
	}

	vpaName := req.WorkloadName + "-xamops-vpa"

	if req.Delete {
		return v.deleteVPA(ctx, req.Namespace, vpaName)
	}
	return v.upsertVPA(ctx, req, vpaName)
}

func (v *VPAManager) upsertVPA(ctx context.Context, req VPAApplyRequest, vpaName string) error {
	updateMode := req.UpdateMode
	if updateMode == "" {
		updateMode = "Off" // safe default — only provides recommendations without changing pods
	}

	apiVersion := req.WorkloadKind
	switch req.WorkloadKind {
	case "Deployment", "StatefulSet", "DaemonSet":
		apiVersion = "apps/v1"
	default:
		apiVersion = "apps/v1"
	}

	vpaObj := &unstructured.Unstructured{
		Object: map[string]interface{}{
			"apiVersion": "autoscaling.k8s.io/v1",
			"kind":       "VerticalPodAutoscaler",
			"metadata": map[string]interface{}{
				"name":      vpaName,
				"namespace": req.Namespace,
				"labels": map[string]interface{}{
					"app.kubernetes.io/managed-by": "xamops-automation",
				},
				"annotations": map[string]interface{}{
					"xamops.io/managed":  "true",
					"xamops.io/modified": time.Now().UTC().Format(time.RFC3339),
				},
			},
			"spec": map[string]interface{}{
				"targetRef": map[string]interface{}{
					"apiVersion": apiVersion,
					"kind":       req.WorkloadKind,
					"name":       req.WorkloadName,
				},
				"updatePolicy": map[string]interface{}{
					"updateMode": updateMode,
				},
				"resourcePolicy": map[string]interface{}{
					"containerPolicies": []interface{}{
						map[string]interface{}{
							"containerName":       "*",
							"controlledResources": []interface{}{"cpu", "memory"},
						},
					},
				},
			},
		},
	}

	if v.dryRun {
		log.Printf("DRY-RUN: would upsert VPA %s/%s (mode=%s target=%s/%s)",
			req.Namespace, vpaName, updateMode, req.WorkloadKind, req.WorkloadName)
		return nil
	}

	_, err := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Get(ctx, vpaName, metav1.GetOptions{})
	if err != nil {
		// Create
		_, createErr := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Create(ctx, vpaObj, metav1.CreateOptions{})
		if createErr != nil {
			return fmt.Errorf("create VPA %s/%s: %w", req.Namespace, vpaName, createErr)
		}
		log.Printf("VPA created: %s/%s (mode=%s)", req.Namespace, vpaName, updateMode)
		return nil
	}

	// Update spec only
	existing, getErr := v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Get(ctx, vpaName, metav1.GetOptions{})
	if getErr != nil {
		return getErr
	}
	if err := unstructured.SetNestedField(existing.Object, updateMode, "spec", "updatePolicy", "updateMode"); err != nil {
		return err
	}
	if existing.GetAnnotations() == nil {
		existing.SetAnnotations(map[string]string{})
	}
	ann := existing.GetAnnotations()
	ann["xamops.io/modified"] = time.Now().UTC().Format(time.RFC3339)
	existing.SetAnnotations(ann)

	_, err = v.dynamicClient.Resource(vpaGVR).Namespace(req.Namespace).Update(ctx, existing, metav1.UpdateOptions{})
	if err != nil {
		return fmt.Errorf("update VPA %s/%s: %w", req.Namespace, vpaName, err)
	}
	log.Printf("VPA updated: %s/%s (mode=%s)", req.Namespace, vpaName, updateMode)
	return nil
}

func (v *VPAManager) deleteVPA(ctx context.Context, namespace, vpaName string) error {
	if v.dryRun {
		log.Printf("DRY-RUN: would delete VPA %s/%s", namespace, vpaName)
		return nil
	}
	err := v.dynamicClient.Resource(vpaGVR).Namespace(namespace).Delete(ctx, vpaName, metav1.DeleteOptions{})
	if err != nil {
		// Not found is fine
		return fmt.Errorf("delete VPA %s/%s: %w", namespace, vpaName, err)
	}
	log.Printf("VPA deleted: %s/%s", namespace, vpaName)
	return nil
}

// ── Detect whether VPA CRD is installed ─────────────────────────────────────

// IsVPAInstalled checks whether the VPA CRD exists in this cluster.
// Returns false (not an error) if VPA is not installed.
func IsVPAInstalled(ctx context.Context, dc dynamic.Interface) bool {
	if dc == nil {
		return false
	}
	_, err := dc.Resource(vpaGVR).Namespace("").List(ctx, metav1.ListOptions{Limit: 1})
	return err == nil
}
