package consolidation

import (
	"fmt"
	"sort"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

// NoneNote is the actor note when no node-removal actor is found.
const NoneNote = "Neither Karpenter nor the Cluster Autoscaler was found. Engine v1 will remove nodes " +
	"with an ASG terminate that decrements desired capacity, never below the node group minimum. Display only."

var actorNotes = map[string]string{
	ActorKarpenter:         "Karpenter was found on this cluster. Display only.",
	ActorClusterAutoscaler: "The Cluster Autoscaler was found on this cluster. Display only.",
	ActorMultiple:          "Both Karpenter and the Cluster Autoscaler were found on this cluster. Display only.",
	ActorNone:              NoneNote,
}

// DetectActor finds who removes nodes on this cluster today (read-only, display
// only). Karpenter: a node labelled karpenter.sh/nodepool or
// karpenter.sh/provisioner-name, or a Deployment named karpenter or labelled
// app.kubernetes.io/name=karpenter. Cluster Autoscaler: a Deployment whose name
// contains cluster-autoscaler or whose app.kubernetes.io/name or app label is
// cluster-autoscaler or aws-cluster-autoscaler. A Deployment counts only when
// spec.replicas is nil or > 0.
func DetectActor(nodes []corev1.Node, deps []appsv1.Deployment) Actor {
	var evidence []string
	karpenter, ca := false, false

	for _, label := range []string{LabelKarpenterNodePool, LabelKarpenterProv} {
		n := 0
		for i := range nodes {
			if _, ok := nodes[i].Labels[label]; ok {
				n++
			}
		}
		if n > 0 {
			karpenter = true
			evidence = append(evidence, fmt.Sprintf("node label %s on %d node(s)", label, n))
		}
	}
	for i := range deps {
		d := &deps[i]
		if d.Spec.Replicas != nil && *d.Spec.Replicas <= 0 {
			continue
		}
		isK := d.Name == "karpenter" || d.Labels["app.kubernetes.io/name"] == "karpenter"
		isCA := strings.Contains(d.Name, "cluster-autoscaler") ||
			isCALabel(d.Labels["app.kubernetes.io/name"]) || isCALabel(d.Labels["app"])
		if isK || isCA {
			evidence = append(evidence, "Deployment "+d.Namespace+"/"+d.Name)
		}
		karpenter = karpenter || isK
		ca = ca || isCA
	}

	kind := ActorNone
	switch {
	case karpenter && ca:
		kind = ActorMultiple
	case karpenter:
		kind = ActorKarpenter
	case ca:
		kind = ActorClusterAutoscaler
	}
	return Actor{Kind: kind, Evidence: sortedUnique(evidence), Note: actorNotes[kind]}
}

func isCALabel(v string) bool { return v == "cluster-autoscaler" || v == "aws-cluster-autoscaler" }

func sortedUnique(in []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out
}
