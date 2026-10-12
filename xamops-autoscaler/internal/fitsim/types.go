// Package fitsim is the scheduler-fit simulator every drain path uses (plan.md D8,
// roadmap C1). It answers one question with pure functions over a snapshot: can the
// nodes of a drain set S be emptied so that every pod that has to move lands on
// another node, and does no guard forbid it? Semantics ("Fit semantics v1",
// plan.md section 3) are implemented here and, independently, in the Python oracle
// eks/test-clusters/harness/fit_ground_truth.py; both must agree.
//
//  1. Bound pod: spec.nodeName set and phase not Succeeded/Failed (terminating pods
//     still count).
//  2. Effective request per resource (CPU millicores, memory bytes): per container
//     max(spec request, status.allocatedResources), missing = 0; app = sum of the
//     regular containers; sidecars = init containers with restartPolicy Always;
//     eff = max(app + sum(sidecars), max over the other init containers i of
//     (init_i + sum of sidecars declared before i)) + spec.overhead.
//  3. Node free = allocatable - sum(eff of all bound pods, DaemonSets included);
//     free pod slots = allocatable pods - count(bound pods).
//  4. Every pod bound to a node of S is exactly one of (first match wins):
//     Excluded (DaemonSet controller owner, or mirror/static pod annotated
//     kubernetes.io/config.mirror): never moved, never blocks.
//     Refused (unmodelled, not placed): required pod (anti-)affinity, a
//     DoNotSchedule topology spread constraint, a persistentVolumeClaim or ephemeral
//     volume, a hostPort, a request for anything but cpu/memory.
//     Blocked (placed in the what-if, but S is not drainable): critical namespace
//     (protectCriticalNamespaces) or the autoscaler's own namespace; StatefulSet
//     owner (protectStatefulSets); controller spec.replicas == 1
//     (protectSingleReplicas); no controller owner, Job owner, or an owner kind
//     other than ReplicaSet/StatefulSet/ReplicationController;
//     cluster-autoscaler.kubernetes.io/safe-to-evict "false"; matched by more than
//     one PDB; a matched PDB with status.disruptionsAllowed < 1 or
//     status.observedGeneration < metadata.generation.
//     Movable: everything else.
//  5. What-if placement of Movable + Blocked pods, first-fit decreasing: pods by
//     (eff CPU desc, eff memory desc, namespace, name); targets = nodes outside S,
//     schedulable and Ready, by name; the first target that tolerates every
//     NoSchedule/NoExecute taint, satisfies nodeSelector and required node affinity
//     (terms ORed, expressions ANDed, matchFields metadata.name In/NotIn) and has
//     free CPU, memory and a pod slot takes the pod. Unplaced pods are strands.
//  6. drainable(S) = S non-empty, no Refused, no Blocked, no strands. All output
//     lists are sorted (moves in placement order); the result does not depend on
//     input order.
//  7. PDBs: every PDB matching a non-excluded pod of S must allow >= 1 disruption.
//     Execution is sequential with replacement-Ready waits, so one allowed
//     disruption covers several pods one at a time.
//  8. Multi-node sets (PlanDrainSet): candidates join S greedily in the caller's
//     order, only while S stays drainable and the capacity has a slot.
//
// FFD gives a constructive proof that a placement exists; a false negative is
// conservative. Soft constraints (preferred (anti-)affinity, ScheduleAnyway spread,
// PreferNoSchedule taints) are ignored because the scheduler may violate them.
// Anything hard that is not modelled is refused, never guessed.
package fitsim

import (
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/labels"

	"github.com/xamops/automation/internal/policy"
)

// Well-known annotations.
const (
	MirrorPodAnnotation = "kubernetes.io/config.mirror"
	SafeToEvictKey      = "cluster-autoscaler.kubernetes.io/safe-to-evict"
)

// Reason codes. A finding's Reason joins every applicable code with "; ", in the
// order below (the Python oracle uses the same codes and order).
const (
	// Refused
	ReasonPodAffinity      = "pod-affinity"
	ReasonPodAntiAffinity  = "pod-anti-affinity"
	ReasonTopologySpread   = "topology-spread"
	ReasonPVCVolume        = "pvc-volume"
	ReasonEphemeralVolume  = "ephemeral-volume"
	ReasonHostPort         = "host-port"
	ReasonExtendedResource = "extended-resource" // + ":" + sorted names joined by ","

	// Blocked
	ReasonCriticalNamespace = "critical-namespace"
	ReasonSelfNamespace     = "self-namespace"
	ReasonStatefulSet       = "statefulset"
	ReasonSingleReplica     = "single-replica"
	ReasonReplicasUnknown   = "replicas-unknown"
	ReasonNoController      = "no-controller"
	ReasonJobOwner          = "job-owner"
	ReasonUnknownOwnerKind  = "unknown-owner-kind" // + ":" + kind
	ReasonSafeToEvictFalse  = "safe-to-evict-false"
	ReasonMultiplePDBs      = "multiple-pdbs"      // + ":" + sorted ns/name joined by ","
	ReasonPDBNoDisruptions  = "pdb-no-disruptions" // + ":" + ns/name
	ReasonPDBStale          = "pdb-stale"          // + ":" + ns/name
)

// Resources is an amount of CPU (millicores), memory (bytes) and pod slots.
type Resources struct {
	MilliCPU    int64 `json:"milliCpu"`
	MemoryBytes int64 `json:"memoryBytes"`
	Pods        int64 `json:"pods"`
}

// Node is the simulator's view of a node.
type Node struct {
	Name          string
	Labels        map[string]string
	Taints        []corev1.Taint
	Unschedulable bool
	Ready         bool
	Allocatable   Resources
	// Requested = sum of eff over every bound pod (DaemonSets included); Pods = count.
	Requested Resources
}

// Free returns allocatable - requested (may be negative on an overcommitted node).
func (n *Node) Free() Resources {
	return Resources{
		MilliCPU:    n.Allocatable.MilliCPU - n.Requested.MilliCPU,
		MemoryBytes: n.Allocatable.MemoryBytes - n.Requested.MemoryBytes,
		Pods:        n.Allocatable.Pods - n.Requested.Pods,
	}
}

// Owner is a pod's controller owner reference (controller=true).
type Owner struct {
	Kind string
	Name string
	UID  string
}

// Pod is the simulator's view of a pod.
type Pod struct {
	Namespace   string
	Name        string
	UID         string
	NodeName    string
	Labels      map[string]string
	Annotations map[string]string
	Phase       corev1.PodPhase
	// Eff is the effective request (section 3.2); Pods is always 1.
	Eff                  Resources
	Tolerations          []corev1.Toleration
	NodeSelector         map[string]string
	RequiredNodeAffinity *corev1.NodeSelector
	// Owner is the controller owner reference, nil when there is none.
	Owner *Owner
	// ControllerReplicas is spec.replicas of the owning ReplicaSet/StatefulSet
	// (nil spec.replicas means 1); nil when the owner object is not in the snapshot.
	ControllerReplicas *int32
	// Unmodelled lists the refusal codes (section 3.4) found when the pod was built.
	Unmodelled []string
}

// Key returns "namespace/name".
func (p *Pod) Key() string { return p.Namespace + "/" + p.Name }

// Bound reports section 3.1.
func (p *Pod) Bound() bool {
	return p.NodeName != "" && p.Phase != corev1.PodSucceeded && p.Phase != corev1.PodFailed
}

// PDB is the simulator's view of a policy/v1 PodDisruptionBudget.
type PDB struct {
	Namespace          string
	Name               string
	Selector           labels.Selector
	DisruptionsAllowed int32
	// Stale = status.observedGeneration < metadata.generation.
	Stale bool
}

// Key returns "namespace/name".
func (b *PDB) Key() string { return b.Namespace + "/" + b.Name }

// Snapshot is one consistent view of the cluster.
type Snapshot struct {
	Nodes []Node
	Pods  []Pod
	PDBs  []PDB
}

// Options carries the guards that turn pods into blockers.
type Options struct {
	Guards policy.SafetyGuards
	// SelfNamespace is the autoscaler's own namespace, always protected.
	SelfNamespace string
}

// Finding is a refused or blocked pod with its reason codes.
type Finding struct {
	Pod    string `json:"pod"`
	Reason string `json:"reason"`
}

// Move is one what-if placement.
type Move struct {
	Pod  string `json:"pod"`
	From string `json:"from"`
	To   string `json:"to"`
	CPU  int64  `json:"cpuMillis"`
	Mem  int64  `json:"memBytes"`

	// Identity used by the executor (not serialized).
	Namespace string `json:"-"`
	Name      string `json:"-"`
	UID       string `json:"-"`
	OwnerKind string `json:"-"`
	OwnerName string `json:"-"`
	OwnerUID  string `json:"-"`
	// Blocked marks a placement of a Blocked pod (only shown in non-drainable plans).
	Blocked bool `json:"-"`
}

// PDBCheck reports one PDB matching non-excluded pods of the set.
type PDBCheck struct {
	PDB                string `json:"pdb"`
	MatchedInSet       int    `json:"matchedInSet"`
	DisruptionsAllowed int32  `json:"disruptionsAllowed"`
	Stale              bool   `json:"stale"`
}

// Plan is the simulator's verdict for one drain set.
type Plan struct {
	Nodes         []string   `json:"nodes"`
	Drainable     bool       `json:"drainable"`
	ExcludedCount int        `json:"excludedCount"`
	Excluded      []string   `json:"excluded"`
	Refused       []Finding  `json:"refused"`
	Blocked       []Finding  `json:"blocked"`
	Strands       []string   `json:"strands"`
	Moves         []Move     `json:"moves"` // placement order (largest first)
	PDBChecks     []PDBCheck `json:"pdbChecks"`
	// Errors are input problems (a node of S not in the snapshot); any error makes
	// the set non-drainable.
	Errors  []string `json:"errors,omitempty"`
	Summary string   `json:"summary"`
}
