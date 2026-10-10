package drain

import "context"

// Planner proves (or refuses) that a set of nodes can be drained: every pod that must
// move has a place to go and no guard blocks it. FEAT-005 plugs in the fit simulator.
type Planner interface {
	Plan(ctx context.Context, nodes []string) (Plan, error)
}

// Plan is a planner's verdict for one drain set.
type Plan struct {
	Nodes      []string    `json:"nodes"`
	Drainable  bool        `json:"drainable"`
	FitChecked bool        `json:"fitChecked"`
	Moves      []Move      `json:"moves,omitempty"` // eviction order, largest first
	Reasons    []string    `json:"reasons,omitempty"`
	Detail     interface{} `json:"detail,omitempty"`
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

// UnavailableReason is the refusal every live drain gets until the fit simulator exists.
const UnavailableReason = "fit simulator not available in this build — live drains refused"

// UnavailablePlanner refuses every drain (plan.md D7). Simulations carry
// fitChecked=false so nobody mistakes them for a feasibility proof.
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
