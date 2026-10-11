package drain

import (
	"context"
	"fmt"
	"strings"

	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/fitsim"
)

// FitPlanner is the production Planner: every call builds a fresh read-only snapshot
// (nodes, pods, PDBs, ReplicaSets, StatefulSets) and runs the fit simulator
// (internal/fitsim, plan.md section 3).
type FitPlanner struct {
	k8s  kubernetes.Interface
	opts fitsim.Options
}

// NewFitPlanner returns a fitsim-backed planner with the cycle's guards and the
// autoscaler's own namespace.
func NewFitPlanner(k8s kubernetes.Interface, opts fitsim.Options) *FitPlanner {
	return &FitPlanner{k8s: k8s, opts: opts}
}

// Plan simulates draining nodes as one set.
func (p *FitPlanner) Plan(ctx context.Context, nodes []string) (Plan, error) {
	return p.Replan(ctx, nodes, nil)
}

// Replan simulates draining nodes as one set, ignoring the pods in evictedUIDs.
func (p *FitPlanner) Replan(ctx context.Context, nodes []string, evictedUIDs map[string]bool) (Plan, error) {
	s, err := fitsim.BuildSnapshot(ctx, p.k8s)
	if err != nil {
		return Plan{Nodes: append([]string(nil), nodes...), FitChecked: false, Reasons: []string{"snapshot: " + err.Error()}}, err
	}
	if len(evictedUIDs) > 0 {
		s = s.WithoutPods(evictedUIDs)
	}
	return FromFit(fitsim.SimulateDrain(s, nodes, p.opts)), nil
}

// SelectSet runs fitsim.PlanDrainSet over one snapshot.
func (p *FitPlanner) SelectSet(ctx context.Context, candidates []string, capacity int) ([]string, map[string]Plan, error) {
	s, err := fitsim.BuildSnapshot(ctx, p.k8s)
	if err != nil {
		return nil, nil, err
	}
	sel, fps := fitsim.PlanDrainSet(s, candidates, capacity, p.opts)
	plans := make(map[string]Plan, len(fps))
	for k, v := range fps {
		plans[k] = FromFit(v)
	}
	return sel, plans, nil
}

// FromFit maps a simulator plan to the executor's plan: moves in placement order
// (largest first), reasons from errors/refused/blocked/strands, Detail = the full
// simulator plan.
func FromFit(fp fitsim.Plan) Plan {
	p := Plan{
		Nodes:      append([]string(nil), fp.Nodes...),
		Drainable:  fp.Drainable,
		FitChecked: true,
		Detail:     fp,
	}
	for _, m := range fp.Moves {
		if m.Blocked {
			continue // a blocked pod's what-if placement is information, never an eviction
		}
		p.Moves = append(p.Moves, Move{
			Namespace: m.Namespace, Name: m.Name, UID: m.UID,
			OwnerKind: m.OwnerKind, OwnerName: m.OwnerName, OwnerUID: m.OwnerUID,
			From: m.From, To: m.To,
		})
	}
	p.Reasons = append(p.Reasons, fp.Errors...)
	for _, f := range fp.Refused {
		p.Reasons = append(p.Reasons, fmt.Sprintf("refused %s: %s", f.Pod, f.Reason))
	}
	for _, f := range fp.Blocked {
		p.Reasons = append(p.Reasons, fmt.Sprintf("blocked %s: %s", f.Pod, f.Reason))
	}
	for _, s := range fp.Strands {
		p.Reasons = append(p.Reasons, fmt.Sprintf("strand %s: no other node fits it", s))
	}
	if !fp.Drainable && len(fp.Nodes) == 0 {
		p.Reasons = append(p.Reasons, "empty drain set")
	}
	p.Retryable = !fp.Drainable && len(fp.Errors) == 0 && len(fp.Refused) == 0 && len(fp.Strands) == 0 &&
		len(fp.Blocked) > 0 && onlyTransientPDB(fp.Blocked)
	return p
}

// onlyTransientPDB: every blocker code is a PDB status condition that clears by
// itself once replacements become Ready (disruptionsAllowed, observedGeneration).
func onlyTransientPDB(blocked []fitsim.Finding) bool {
	for _, f := range blocked {
		for _, code := range strings.Split(f.Reason, "; ") {
			if !strings.HasPrefix(code, fitsim.ReasonPDBNoDisruptions+":") && !strings.HasPrefix(code, fitsim.ReasonPDBStale+":") {
				return false
			}
		}
	}
	return true
}
