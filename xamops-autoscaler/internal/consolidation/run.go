package consolidation

// ConsolidationRunDto is the consolidation controller's run-history record, pushed
// by the agent as dataType "autoscaling-consolidation-run" (a LIST collector: each
// run appends one record; it is never a single server-owned object).
//
// It is the ACTING counterpart of the look-only Plan (autoscaling-consolidation-plan):
// the plan says what could be removed, the run says what the controller actually did
// this cycle (or simulated / refused / deferred). Like the plan, no field is omitted
// so a reader can always rely on the shape; empty lists marshal as []. The backend's
// FEAT-003 ConsolidationRunDto record mirrors this JSON exactly.
type ConsolidationRunDto struct {
	// SchemaVersion is the run-record schema version (1).
	SchemaVersion int `json:"schemaVersion"`
	// RunID uniquely identifies this run (the operationId shared by the paired
	// autoscaling-actions / autoscaling-events records).
	RunID string `json:"runId"`
	// GeneratedAt is the RFC 3339 UTC time the controller built this record (the
	// injected now, never time.Now()).
	GeneratedAt string `json:"generatedAt"`
	// Mode is the gate mode the controller ran in: simulate | live.
	Mode string `json:"mode"`
	// DryRun is true when Mode != live (a simulation took no cloud or cluster action).
	DryRun bool `json:"dryRun"`
	// Actor echoes the plan's detected node-removal actor.
	Actor Actor `json:"actor"`
	// Summary counts this run's node verdicts.
	Summary RunSummary `json:"summary"`
	// Nodes is one record per node the controller considered this cycle.
	Nodes []ConsolidationNodeRun `json:"nodes"`
}

// RunSummary counts a run's node verdicts. Considered = len(Nodes).
type RunSummary struct {
	Considered int `json:"considered"`
	// Removed counts nodes the controller removed itself (XamopsASG terminate verified).
	Removed int `json:"removed"`
	// Deferred counts nodes cordoned + drained and left to Karpenter/CA (DeferToActor).
	Deferred int `json:"deferred"`
	// Simulated counts nodes a dry-run would have acted on (no cluster/cloud change).
	Simulated int `json:"simulated"`
	// Skipped counts candidates dropped by the dwell/cap/recheck pre-filters.
	Skipped int `json:"skipped"`
	// Refused counts nodes refused fail-closed by the removal decision.
	Refused int `json:"refused"`
	// Aborted counts nodes whose drain aborted (left cordoned, not terminated).
	Aborted int `json:"aborted"`
	// PodsEvicted is the total pods this run evicted across its live drains.
	PodsEvicted int `json:"podsEvicted"`
}

// ConsolidationNodeRun is one node's outcome in a run.
type ConsolidationNodeRun struct {
	// Node identifies the node (name, nodeGroup, providerId, …) — the same NodeRef
	// the plan carries.
	Node NodeRef `json:"node"`
	// MoveType is the plan move type the controller acted on (EMPTY | DRAIN).
	MoveType MoveType `json:"moveType"`
	// Status is the controller's outcome for this node: removed | deferred |
	// simulated | skipped | refused | aborted.
	Status string `json:"status"`
	// Decision is the removal decision string (xamops-asg | defer-to-actor | refuse),
	// empty when the node never reached the removal step (skipped before drain).
	Decision string `json:"decision"`
	// Reason is the human sentence explaining Status.
	Reason string `json:"reason"`
	// PodsEvicted is how many pods the controller evicted draining this node.
	PodsEvicted int `json:"podsEvicted"`
}

// Run status strings used in ConsolidationNodeRun.Status and the run summary.
const (
	RunStatusRemoved   = "removed"
	RunStatusDeferred  = "deferred"
	RunStatusSimulated = "simulated"
	RunStatusSkipped   = "skipped"
	RunStatusRefused   = "refused"
	RunStatusAborted   = "aborted"
)

// RunSchemaVersion is the schema version of ConsolidationRunDto.
const RunSchemaVersion = 1
