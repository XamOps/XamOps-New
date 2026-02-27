package collector

import (
	"context"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

func collectClusterMetadata(ctx context.Context, k8s kubernetes.Interface) (DataBatch, error) {
	metadata := ClusterMetadata{}

	// Nodes
	nodeList, err := k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, err
	}
	for _, n := range nodeList.Items {
		metadata.Nodes = append(metadata.Nodes, NodeInfo{
			Name:   n.Name,
			Labels: n.Labels,
			Capacity: NodeCapacity{
				CPU:    n.Status.Capacity.Cpu().String(),
				Memory: n.Status.Capacity.Memory().String(),
				Pods:   n.Status.Capacity.Pods().String(),
			},
			Phase: string(n.Status.Phase),
		})
	}

	// Namespaces
	nsList, err := k8s.CoreV1().Namespaces().List(ctx, metav1.ListOptions{})
	if err == nil {
		for _, ns := range nsList.Items {
			metadata.Namespaces = append(metadata.Namespaces, ns.Name)
		}
	}

	// Pods summary
	podList, err := k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err == nil {
		summary := PodsSummary{Total: len(podList.Items)}
		for _, p := range podList.Items {
			switch p.Status.Phase {
			case "Running":
				summary.Running++
			case "Pending":
				summary.Pending++
			case "Failed":
				summary.Failed++
			}
		}
		metadata.PodsSummary = summary
	}

	return DataBatch{
		Collector:   "cluster-metadata",
		Items:       []ClusterMetadata{metadata},
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}
