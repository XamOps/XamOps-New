package consolidation

import (
	"fmt"
	"strings"

	"github.com/xamops/automation/internal/fitsim"
)

// Well-known labels and annotations.
const (
	LabelNodeGroup         = "eks.amazonaws.com/nodegroup"
	LabelKarpenterNodePool = "karpenter.sh/nodepool"
	LabelKarpenterProv     = "karpenter.sh/provisioner-name"
	LabelKarpenterCapacity = "karpenter.sh/capacity-type"
	LabelEKSCapacity       = "eks.amazonaws.com/capacityType"
	LabelInstanceType      = "node.kubernetes.io/instance-type"
	LabelInstanceTypeBeta  = "beta.kubernetes.io/instance-type"
	LabelZone              = "topology.kubernetes.io/zone"
	LabelZoneBeta          = "failure-domain.beta.kubernetes.io/zone"
	LabelComputeType       = "eks.amazonaws.com/compute-type"
	LabelRemovalDisabled   = "cloudk8s.xammer.in/removal-disabled"

	AnnotationKarpenterDoNotDisrupt = "karpenter.sh/do-not-disrupt"
	AnnotationDoNotEvict            = "cloudk8s.xammer.in/do-not-evict"
)

// Reason codes added by this package (fitsim's codes are used as they are).
const (
	// Pod-level.
	CodeLocalStorageNoReplica = "local-storage-no-replica"
	CodeKubeSystemNoPDB       = "kube-system-no-pdb"
	CodeDoNotDisrupt          = "do-not-disrupt"
	CodeStrand                = "strand"

	// Node-level (Blocker.Pod is "").
	CodeNodeNotReady     = "node-not-ready"
	CodeNodeCordoned     = "node-cordoned"
	CodeRemovalDisabled  = "removal-disabled"
	CodeFargateNode      = "fargate-node"
	CodeUnmanagedNode    = "unmanaged-node"
	CodeLastNodeInGroup  = "last-node-in-group"
	CodeNoRoomInPlan     = "no-room-in-plan"
	CodePlanLimitReached = "plan-limit"
)

// reasons maps a code (or the prefix of a ":"-suffixed code) to its human reason.
// Suffixed codes are formatted with the suffix.
var reasons = map[string]string{
	// fitsim refused (unmodelled)
	fitsim.ReasonPodAffinity:      "required pod affinity (not modelled)",
	fitsim.ReasonPodAntiAffinity:  "required pod anti-affinity (not modelled)",
	fitsim.ReasonTopologySpread:   "DoNotSchedule topology spread (not modelled)",
	fitsim.ReasonPVCVolume:        "persistent volume claim",
	fitsim.ReasonEphemeralVolume:  "ephemeral volume",
	fitsim.ReasonHostPort:         "host port",
	fitsim.ReasonExtendedResource: "requests extended resource %s",

	// fitsim blocked
	fitsim.ReasonCriticalNamespace: "critical namespace",
	fitsim.ReasonSelfNamespace:     "XamOps autoscaler namespace",
	fitsim.ReasonStatefulSet:       "StatefulSet pod",
	fitsim.ReasonSingleReplica:     "single replica",
	fitsim.ReasonReplicasUnknown:   "owner replica count unknown",
	fitsim.ReasonNoController:      "bare pod (no controller)",
	fitsim.ReasonJobOwner:          "Job pod",
	fitsim.ReasonUnknownOwnerKind:  "owner kind %s is not supported",
	fitsim.ReasonSafeToEvictFalse:  "do-not-evict annotation",
	fitsim.ReasonMultiplePDBs:      "matched by more than one PodDisruptionBudget (%s)",
	fitsim.ReasonPDBNoDisruptions:  "PodDisruptionBudget %s at limit",
	fitsim.ReasonPDBStale:          "PodDisruptionBudget %s status is stale",

	// this package
	CodeStrand:                fmt.Sprintf("no other node has room (keeping %d%% headroom)", HeadroomPct),
	CodeLocalStorageNoReplica: "local storage, no redundant replica",
	CodeKubeSystemNoPDB:       "kube-system pod without PDB",
	CodeDoNotDisrupt:          "do-not-evict annotation",
	CodeNodeNotReady:          "node is not Ready",
	CodeNodeCordoned:          "node is cordoned",
	CodeRemovalDisabled:       "removal disabled (label " + LabelRemovalDisabled + ")",
	CodeFargateNode:           "Fargate node",
	CodeUnmanagedNode:         "unmanaged node (no EKS node group or Karpenter node pool)",
	CodeLastNodeInGroup:       "last node in node group %s",
	CodeNoRoomInPlan:          "pods don't fit once the other planned nodes are removed",
	CodePlanLimitReached:      fmt.Sprintf("plan limit of %d nodes reached", MaxNodesPerPlan),
}

// Reason returns the human reason for a code. A ":"-suffixed code is matched on its
// prefix and the suffix is put into the sentence; an unknown code is returned as is.
func Reason(code string) string {
	if r, ok := reasons[code]; ok && !strings.Contains(r, "%s") {
		return r
	}
	prefix, arg, _ := strings.Cut(code, ":")
	if r, ok := reasons[prefix]; ok {
		if strings.Contains(r, "%s") {
			return fmt.Sprintf(r, arg)
		}
		return r
	}
	return code
}

func blocker(pod, code string) Blocker {
	return Blocker{Pod: pod, Code: code, Reason: Reason(code)}
}

// lastNodeBlocker names the node group in the reason; the code stays the bare
// constant so consumers can match it.
func lastNodeBlocker(group string) Blocker {
	return Blocker{Pod: "", Code: CodeLastNodeInGroup, Reason: fmt.Sprintf(reasons[CodeLastNodeInGroup], group)}
}
