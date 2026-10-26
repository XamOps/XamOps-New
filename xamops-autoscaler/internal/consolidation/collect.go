package consolidation

import (
	"context"
	"fmt"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/fitsim"
)

// Collect lists nodes, all pods, policy/v1 PDBs, ReplicaSets, StatefulSets,
// Deployments, PersistentVolumeClaims, PersistentVolumes and the allow-listed
// operator Custom Resources (through the dynamic client). It only lists: the package
// makes no other API call. A nil dynamic client lists no CRs. An allow-listed CRD
// that is not installed contributes zero CRs (non-fatal).
func Collect(ctx context.Context, k8s kubernetes.Interface, dyn dynamic.Interface) (Input, error) {
	var in Input
	nodes, err := k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list nodes: %w", err)
	}
	pods, err := k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list pods: %w", err)
	}
	pdbs, err := k8s.PolicyV1().PodDisruptionBudgets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list poddisruptionbudgets: %w", err)
	}
	rss, err := k8s.AppsV1().ReplicaSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list replicasets: %w", err)
	}
	stss, err := k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list statefulsets: %w", err)
	}
	deps, err := k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list deployments: %w", err)
	}
	pvcs, err := k8s.CoreV1().PersistentVolumeClaims("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list persistentvolumeclaims: %w", err)
	}
	pvs, err := k8s.CoreV1().PersistentVolumes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return in, fmt.Errorf("list persistentvolumes: %w", err)
	}
	crs, err := fitsim.ListOperatorCRs(ctx, dyn)
	if err != nil {
		return in, err
	}
	return Input{
		Nodes:        nodes.Items,
		Pods:         pods.Items,
		PDBs:         pdbs.Items,
		ReplicaSets:  rss.Items,
		StatefulSets: stss.Items,
		Deployments:  deps.Items,
		PVCs:         pvcs.Items,
		PVs:          pvs.Items,
		CRs:          crs,
	}, nil
}

// Run collects one snapshot and analyzes it. It never acts on the cluster.
func Run(ctx context.Context, k8s kubernetes.Interface, dyn dynamic.Interface, opts fitsim.Options, now time.Time) (Plan, error) {
	in, err := Collect(ctx, k8s, dyn)
	if err != nil {
		return Plan{}, err
	}
	return Analyze(in, opts, now), nil
}
