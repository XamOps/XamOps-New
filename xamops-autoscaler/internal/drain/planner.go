package drain

import "context"

// Planner proves (or refuses) that a set of nodes can be drained: every pod that must
// move has a place to go and no guard blocks it. FitPlanner (internal/fitsim) is the
// production planner; UnavailablePlanner refuses everything.
type Planner interface {
	Plan(ctx context.Context, nodes []string) (Plan, error)
}

// Replanner is implemented by planners that can re-plan a set mid-drain while
// ignoring the pods this drain already evicted (they may still be terminating; their
// replacements are accounted for where they run). Planners without it are re-asked
// with Plan and the executor drops the already-evicted moves itself.
type Replanner interface {
	Replan(ctx context.Context, nodes []string, evictedUIDs map[string]bool) (Plan, error)
}

// SetSelector is implemented by planners that choose a multi-node drain set in one
// consistent view (plan.md section 3.8). Executor.SelectSet falls back to a greedy
// loop over Plan for planners without it.
type SetSelector interface {
	SelectSet(ctx context.Context, candidates []string, capacity int) ([]string, map[string]Plan, error)
}

// EachPlanner is implemented by planners that plan every node on its own in one
// consistent view. DrainCandidates uses it to tell a candidate skipped for budget
// why it could (not) have been drained anyway; planners without it are asked once
// per node with Plan.
type EachPlanner interface {
	PlanEach(ctx context.Context, nodes []string) (map[string]Plan, error)
}

// Plan is a planner's verdict for one drain set.
type Plan struct {
	Nodes      []string `json:"nodes"`
	Drainable  bool     `json:"drainable"`
	FitChecked bool     `json:"fitChecked"`
	Moves      []Move   `json:"moves,omitempty"` // eviction order, largest first
	Reasons    []string `json:"reasons,omitempty"`
	// Retryable marks a non-drainable plan whose only obstacle is transient PDB state
	// (disruptionsAllowed < 1 or a stale status): mid-drain the executor waits up to
	// PDBRetry for it to clear before aborting.
	Retryable bool        `json:"retryable,omitempty"`
	Detail    interface{} `json:"detail,omitempty"`
}

// Move is one pod the executor evicts, with the owner used to verify its replacement.
type Move struct {
	Namespace string `json:"namespace"`
	Name      string `json:"name"`
	UID       string `json:"uid"`
	OwnerKind string `json:"ownerKind"`
	OwnerName string `json:"ownerName"`
	OwnerUID  string `json:"ownerUid"`
	From      string `json:"from"`
	To        string `json:"to,omitempty"`
}

// UnavailableReason is the refusal UnavailablePlanner gives every drain.
const UnavailableReason = "fit simulator not available in this build — live drains refused"

// UnavailablePlanner refuses every drain (plan.md D7, the FEAT-001 interim planner;
// kept as the executor's fail-closed default). Simulations carry fitChecked=false so
// nobody mistakes them for a feasibility proof.
type UnavailablePlanner struct{}

// Plan always returns a non-drainable, unchecked plan.
func (UnavailablePlanner) Plan(_ context.Context, nodes []string) (Plan, error) {
	return Plan{
		Nodes:      append([]string(nil), nodes...),
		Drainable:  false,
		FitChecked: false,
		Reasons:    []string{UnavailableReason},
	}, nil
}
