// Package consolidation is the look-only node consolidation analyzer (CloudK8s
// Node Consolidation Phase 1). Over one read-only snapshot it answers: which nodes
// could be removed because they are EMPTY (DaemonSet/mirror pods only) or could be
// DRAINed (both CPU and memory requests below 50% of allocatable, every pod
// evictable and placeable elsewhere with 15% headroom kept on the receivers), why
// every other node cannot be, and who removes nodes on this cluster today.
//
// The package never acts: it only lists objects (collect.go) and calls the pure
// fit simulator (fitsim.FromObjects, fitsim.SimulateDrain, fitsim.PlanDrainSet),
// which it does not modify. Move types are EMPTY and DRAIN only (I0): there is no
// replacement move in v1. The plan carries no prices; the backend prices the
// removed nodes with its cost engine when the plan is read.
package consolidation

// Fixed engine constants, echoed in Plan.Settings. They are not policy fields in
// Phase 1 (look-only).
const (
	SchemaVersion             = 1
	ModeAnalysis              = "analysis"
	UnderutilizedThresholdPct = 50
	HeadroomPct               = 15
	MaxNodesPerPlan           = 100
)

// MoveType is a v1 consolidation move. Only EMPTY and DRAIN exist.
type MoveType string

const (
	// MoveEmpty removes a node that runs only DaemonSet / mirror pods.
	MoveEmpty MoveType = "EMPTY"
	// MoveDrain moves every pod of an under-utilized node elsewhere, then removes it.
	MoveDrain MoveType = "DRAIN"
)

// Actor kinds: who removes nodes on this cluster today (display only).
const (
	ActorKarpenter         = "KARPENTER"
	ActorClusterAutoscaler = "CLUSTER_AUTOSCALER"
	ActorMultiple          = "MULTIPLE"
	ActorNone              = "NONE"
)

// Plan is the look-only consolidation plan pushed as agent dataType
// autoscaling-consolidation-plan. No field is omitted; empty lists marshal as [].
type Plan struct {
	SchemaVersion int           `json:"schemaVersion"`
	GeneratedAt   string        `json:"generatedAt"`
	Mode          string        `json:"mode"`
	Actor         Actor         `json:"actor"`
	Settings      Settings      `json:"settings"`
	Summary       Summary       `json:"summary"`
	Moves         []Move        `json:"moves"`
	Blocked       []BlockedNode `json:"blocked"`
	Kept          []KeptNode    `json:"kept"`
}

// Actor is the detected node-removal actor with the evidence that found it.
type Actor struct {
	Kind     string   `json:"kind"`
	Evidence []string `json:"evidence"`
	Note     string   `json:"note"`
}

// Settings echoes the fixed engine constants used for this plan.
type Settings struct {
	UnderutilizedThresholdPct int `json:"underutilizedThresholdPct"`
	HeadroomPct               int `json:"headroomPct"`
	MaxNodesPerPlan           int `json:"maxNodesPerPlan"`
}

// Summary counts the plan. Empty + Drain = len(Moves); every evaluated node is in
// exactly one of Moves, Blocked and Kept.
type Summary struct {
	NodesEvaluated int `json:"nodesEvaluated"`
	Empty          int `json:"empty"`
	Drain          int `json:"drain"`
	Blocked        int `json:"blocked"`
	Kept           int `json:"kept"`
}

// NodeRef identifies a node with the facts the backend needs to price it.
// Requested percentages are effective requests / allocatable, rounded to 0.1.
type NodeRef struct {
	Name            string  `json:"name"`
	NodeGroup       string  `json:"nodeGroup"`
	InstanceType    string  `json:"instanceType"`
	CapacityType    string  `json:"capacityType"`
	Zone            string  `json:"zone"`
	Fargate         bool    `json:"fargate"`
	ProviderID      string  `json:"providerId"`
	CPURequestedPct float64 `json:"cpuRequestedPct"`
	MemRequestedPct float64 `json:"memRequestedPct"`
}

// Move is one node the plan would remove.
type Move struct {
	Type MoveType  `json:"type"`
	Node NodeRef   `json:"node"`
	Pods []PodMove `json:"pods"`
}

// PodMove is one what-if placement of a DRAIN move. Owner is "Kind/name".
type PodMove struct {
	Pod       string `json:"pod"`
	Owner     string `json:"owner"`
	To        string `json:"to"`
	CPUMillis int64  `json:"cpuMillis"`
	MemBytes  int64  `json:"memBytes"`
}

// BlockedNode is a node that could not be removed, with every blocker found.
type BlockedNode struct {
	Node     NodeRef   `json:"node"`
	Blockers []Blocker `json:"blockers"`
}

// Blocker is one reason a node cannot be removed. Pod is "namespace/name", or ""
// for node-level reasons. Code is the machine code (fitsim codes keep their
// ":"-suffix), Reason the human sentence.
type Blocker struct {
	Pod    string `json:"pod"`
	Code   string `json:"code"`
	Reason string `json:"reason"`
}

// KeptNode is a node that was never a candidate, with why.
type KeptNode struct {
	Node   NodeRef `json:"node"`
	Reason string  `json:"reason"`
}
