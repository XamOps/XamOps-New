package controller

import (
	"math"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// buildEviction creates a policyv1.Eviction object for a pod.
func buildEviction(podName, namespace string) *policyv1.Eviction {
	return &policyv1.Eviction{
		ObjectMeta: metav1.ObjectMeta{
			Name:      podName,
			Namespace: namespace,
		},
	}
}

// isSpotNode returns true if the node is a spot/preemptible instance.
// Checks labels set by EKS, Karpenter, GKE, and AKS.
func isSpotNode(node corev1.Node) bool {
	lbl := node.Labels
	return lbl["node-lifecycle"] == "spot" ||
		lbl["eks.amazonaws.com/capacityType"] == "SPOT" ||
		lbl["karpenter.sh/capacity-type"] == "spot" ||
		lbl["cloud.google.com/gke-spot"] == "true" ||
		lbl["kubernetes.azure.com/scalesetpriority"] == "spot"
}

// roundTo2 rounds a float64 to 2 decimal places.
func roundTo2(f float64) float64 {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return 0
	}
	return math.Round(f*100) / 100
}
