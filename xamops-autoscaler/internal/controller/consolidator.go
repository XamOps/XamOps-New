package controller

import (
	"context"
	"fmt"
	"log"
	"sort"
	"time"

	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/removal"
	"github.com/xamops/automation/internal/telemetry"
)

// AnnotationConsolidationCandidateSince records, as an RFC3339 UTC time, when a node
// first became a consolidation candidate. The controller stamps it the first time it
// sees a node in the plan's moves and only acts once now - since >=
// consolidateAfterSeconds (T4 idle dwell). It is read from the node objects in the
// consolidation Input (no extra API read).
const AnnotationConsolidationCandidateSince = "xamops.io/consolidation-candidate-since"

// ConsolidationResult is what one Execute call returns alongside the action records.
type ConsolidationResult struct {
	Run     *consolidation.ConsolidationRunDto
	Actions []policy.ActionRecord
}

// Consolidator executes the consolidation plan: it acts on the EMPTY/DRAIN moves the
// look-only analyzer already computed, drains each selected node through the shared
// drain.Executor and — only for a XamOps-owned ASG node, and only AFTER Drain returns
// a verified ActionSucceeded — performs the controller-owned ASG terminate through
// the cloud provider.
//
// The controller NEVER re-collects or re-analyzes the cluster: main hands it the same
// Input + Plan the plan push used. Its ONLY re-analysis is the recheck-identical-
// candidate guard, a pure re-run of consolidation.Analyze over the same Input, so a
// candidate that no longer appears as the same move type / node group / providerID is
// dropped (recorded skipped) rather than forced.
//
// Pre-filters, in order, before any drain:
//   - T4 dwell: a node acted on only once it has been a candidate for at least
//     consolidateAfterSeconds (injected now vs the candidate-since annotation).
//   - I6 per-node-group cap: at most MaxConcurrentDisruptions candidates per node
//     group act this cycle (Move.Node.NodeGroup; the global drain.Budget is untouched).
//   - recheck-identical-candidate.
//
// gate.Simulate records every intended removal as a simulated action and calls the
// provider NOT AT ALL. gate.Live drains then, for a XamopsASG node, re-Looks up the
// instance, re-checks MinSize vs DesiredCapacity and terminates with decrement;
// DeferToActor leaves the node cordoned+drained with the deferral reason and makes no
// cloud call; a drain abort / lookup failure / at-min leaves the node cordoned and is
// recorded (never terminated).
type Consolidator struct {
	provider removal.CloudProvider
	exec     *drain.Executor
	mset     *metrics.Set
}

// NewConsolidator builds a consolidator. provider and exec may be nil only when the
// controller is never run live (the gate keeps it Off); Execute guards against a nil
// executor.
func NewConsolidator(provider removal.CloudProvider, exec *drain.Executor, mset *metrics.Set) *Consolidator {
	return &Consolidator{provider: provider, exec: exec, mset: mset}
}

// Execute runs the consolidation controller for one cycle. It returns the run-history
// record and every action record produced (one per acted-on / skipped / refused
// node). It takes no action when mode is not Simulate/Live.
//
// opts is the fit options (guards, self-namespace) used for the recheck re-analysis;
// pol carries the dwell (consolidateAfterSeconds) and the per-node-group cap
// (maxConcurrentDisruptions). now is the injected clock used for every dwell/cooldown
// comparison (never time.Now()).
func (c *Consolidator) Execute(
	ctx context.Context,
	in consolidation.Input,
	plan consolidation.Plan,
	mode gate.Mode,
	opts fitsim.Options,
	pol policy.ConsolidationPolicy,
	budget *drain.Budget,
	now time.Time,
) (ConsolidationResult, error) {
	runID := fmt.Sprintf("consolidation-%d", now.UTC().Unix())
	run := &consolidation.ConsolidationRunDto{
		SchemaVersion: consolidation.RunSchemaVersion,
		RunID:         runID,
		GeneratedAt:   now.UTC().Format(time.RFC3339),
		Mode:          mode.String(),
		DryRun:        mode != gate.Live,
		Actor:         plan.Actor,
		Nodes:         []consolidation.ConsolidationNodeRun{},
	}
	res := ConsolidationResult{Run: run}

	if mode != gate.Simulate && mode != gate.Live {
		log.Printf("Consolidator: off (gate mode %s) — no action", mode)
		return res, nil
	}
	if c.exec == nil {
		return res, fmt.Errorf("consolidator needs a drain executor")
	}

	// A freshly-analyzed plan over the SAME input: the recheck-identical-candidate
	// guard compares each candidate against this, never forcing a drifted one.
	fresh := consolidation.Analyze(in, opts, now)
	freshMove := map[string]consolidation.Move{}
	for _, m := range fresh.Moves {
		freshMove[m.Node.Name] = m
	}

	// Candidate-since per node (read from the Input node objects, no extra API call).
	since := map[string]string{}
	for i := range in.Nodes {
		if v := in.Nodes[i].Annotations[AnnotationConsolidationCandidateSince]; v != "" {
			since[in.Nodes[i].Name] = v
		}
	}

	dwell := time.Duration(pol.ConsolidateAfterSeconds) * time.Second
	perGroup := map[string]int{}
	groupCap := pol.MaxConcurrentDisruptions
	if groupCap <= 0 {
		groupCap = 1
	}

	// Act on the plan's moves in a deterministic order (by node name).
	moves := append([]consolidation.Move(nil), plan.Moves...)
	sort.Slice(moves, func(i, j int) bool { return moves[i].Node.Name < moves[j].Node.Name })

	for _, m := range moves {
		node := m.Node
		nr := consolidation.ConsolidationNodeRun{Node: node, MoveType: m.Type}

		// T4 dwell: a node acts only once it has been a candidate long enough.
		if ok, reason := c.dwellSatisfied(since[node.Name], now, dwell); !ok {
			nr.Status = consolidation.RunStatusSkipped
			nr.Reason = reason
			c.record(&res, run, nr, mode, runID)
			continue
		}

		// I6 per-node-group cap (controller pre-filter; the global budget is untouched).
		if perGroup[node.NodeGroup] >= groupCap {
			nr.Status = consolidation.RunStatusSkipped
			nr.Reason = fmt.Sprintf("per-node-group disruption cap reached (maxConcurrentDisruptions=%d for node group %q)", groupCap, node.NodeGroup)
			c.record(&res, run, nr, mode, runID)
			continue
		}

		// recheck-identical-candidate: survives only if the fresh plan still has this
		// node as the same move type with the same node group and providerID.
		fm, ok := freshMove[node.Name]
		if !ok || fm.Type != m.Type || fm.Node.NodeGroup != node.NodeGroup || fm.Node.ProviderID != node.ProviderID {
			nr.Status = consolidation.RunStatusSkipped
			nr.Reason = "candidate drifted since the plan was computed (recheck failed) — dropped, not forced"
			c.record(&res, run, nr, mode, runID)
			continue
		}

		perGroup[node.NodeGroup]++
		c.actOnNode(ctx, &res, run, m, mode, budget, runID)
	}

	// Metrics for the whole run.
	if c.mset != nil {
		c.mset.SetConsolidationActionRate(run.Summary.Removed + run.Summary.Deferred + run.Summary.Simulated)
		c.mset.AddConsolidationPodsEvicted(run.Summary.PodsEvicted)
		c.mset.IncConsolidationRun(runStatus(run))
	}
	run.Summary.Considered = len(run.Nodes)
	log.Printf("Consolidation (%s): considered=%d removed=%d deferred=%d simulated=%d skipped=%d refused=%d aborted=%d — actor=%s",
		mode, run.Summary.Considered, run.Summary.Removed, run.Summary.Deferred, run.Summary.Simulated,
		run.Summary.Skipped, run.Summary.Refused, run.Summary.Aborted, plan.Actor.Kind)
	return res, nil
}

// dwellSatisfied reports whether a candidate-since timestamp is old enough. A missing
// or unparseable timestamp means the node was first seen now (or its mark is
// unreadable): it is not yet dwelt and is skipped this cycle.
func (c *Consolidator) dwellSatisfied(sinceStr string, now time.Time, dwell time.Duration) (bool, string) {
	if dwell <= 0 {
		return true, ""
	}
	if sinceStr == "" {
		return false, fmt.Sprintf("first seen as a candidate this cycle — waiting %s before acting (consolidateAfter dwell)", dwell)
	}
	since, err := time.Parse(time.RFC3339, sinceStr)
	if err != nil {
		return false, fmt.Sprintf("candidate-since annotation %q unparseable — waiting (fail-closed)", sinceStr)
	}
	if now.Sub(since) < dwell {
		return false, fmt.Sprintf("candidate for %s (< %s consolidateAfter dwell) — not acted on yet",
			now.Sub(since).Round(time.Second), dwell)
	}
	return true, ""
}

// actOnNode drains one node and, for a XamOps-owned ASG node under live mode, runs the
// controller-owned terminate after a verified drain.
func (c *Consolidator) actOnNode(
	ctx context.Context,
	res *ConsolidationResult,
	run *consolidation.ConsolidationRunDto,
	m consolidation.Move,
	mode gate.Mode,
	budget *drain.Budget,
	runID string,
) {
	node := m.Node
	nr := consolidation.ConsolidationNodeRun{Node: node, MoveType: m.Type}

	// The removal decision is pure and needs no cloud lookup in simulate: it tells us
	// which path a live run would take so the simulated record is honest.
	instanceID, idOK := removal.ParseInstanceID(node.ProviderID)

	if mode == gate.Simulate {
		// Simulate drains (records simulated, no writes) and records the intended
		// removal WITHOUT calling the provider at all.
		out := c.exec.Drain(ctx, policy.ControllerConsolidator, runID, []string{node.Name}, gate.Simulate, budget)
		res.Actions = append(res.Actions, out.Actions...)
		nr.Status = consolidation.RunStatusSimulated
		nr.PodsEvicted = len(out.Evicted)
		if idOK {
			nr.Reason = fmt.Sprintf("DRY-RUN: would drain and (actor permitting) remove instance %s; provider not called", instanceID)
		} else {
			nr.Reason = "DRY-RUN: would drain; providerID has no resolvable instance id (removal would refuse fail-closed)"
		}
		c.record(res, run, nr, mode, runID)
		return
	}

	// gate.Live: drain, then act on the removal decision only after a verified drain.
	start := time.Now()
	out := c.exec.Drain(ctx, policy.ControllerConsolidator, runID, []string{node.Name}, gate.Live, budget)
	if c.mset != nil {
		c.mset.ObserveConsolidationDrain(time.Since(start).Seconds())
	}
	res.Actions = append(res.Actions, out.Actions...)
	nr.PodsEvicted = len(out.Evicted)

	if !out.Verified() {
		// Drain did not verify (refused / skipped / aborted / failed): leave the node
		// cordoned, never terminate.
		nr.Status = consolidation.RunStatusAborted
		nr.Reason = fmt.Sprintf("drain did not complete (%s) — node left cordoned, not terminated", out.Status)
		if c.mset != nil {
			c.mset.IncConsolidationRunAbort("drain-" + out.Status)
		}
		c.record(res, run, nr, mode, runID)
		return
	}

	// Drain verified. Now decide who removes the emptied node.
	if !idOK {
		nr.Status = consolidation.RunStatusRefused
		nr.Decision = removal.Refuse.String()
		nr.Reason = fmt.Sprintf("providerID %q has no resolvable instance id — removal refused fail-closed (node left cordoned+drained)", node.ProviderID)
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionRefused, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}
	if c.provider == nil {
		nr.Status = consolidation.RunStatusRefused
		nr.Decision = removal.Refuse.String()
		nr.Reason = "no cloud provider configured — removal refused (node left cordoned+drained)"
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionRefused, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}

	facts, err := c.provider.Lookup(ctx, instanceID)
	if err != nil {
		nr.Status = consolidation.RunStatusAborted
		nr.Reason = fmt.Sprintf("post-drain instance lookup failed: %v — node left cordoned, not terminated", err)
		if c.mset != nil {
			c.mset.IncConsolidationRunAbort("lookup-failed")
		}
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionAborted, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}

	decision, reason := removal.DecideRemoval(run.Actor, facts)
	nr.Decision = decision.String()
	switch decision {
	case removal.DeferToActor:
		nr.Status = consolidation.RunStatusDeferred
		nr.Reason = reason
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionSkipped, false, reason))
		if c.mset != nil {
			c.mset.IncConsolidationNodeRemoved("defer-to-actor")
		}
		c.record(res, run, nr, mode, runID)
		return
	case removal.Refuse:
		nr.Status = consolidation.RunStatusRefused
		nr.Reason = reason + " — node left cordoned+drained, not terminated"
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionRefused, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}

	// decision == XamopsASG: re-check MinSize vs DesiredCapacity one last time, then
	// terminate with decrement (never launches a replacement).
	if facts.DesiredCapacity <= facts.MinSize {
		nr.Status = consolidation.RunStatusRefused
		nr.Decision = removal.Refuse.String()
		nr.Reason = "nodegroup-at-min on the post-drain re-check — node left cordoned+drained, not terminated"
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionRefused, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}
	if err := c.provider.TerminateAndDecrement(ctx, instanceID, facts.ASG); err != nil {
		nr.Status = consolidation.RunStatusAborted
		nr.Reason = fmt.Sprintf("terminate %s failed: %v — node left cordoned", instanceID, err)
		if c.mset != nil {
			c.mset.IncConsolidationRunAbort("terminate-failed")
		}
		res.Actions = append(res.Actions, c.terminateRecord(runID, node.Name, policy.ActionFailed, false, nr.Reason))
		c.record(res, run, nr, mode, runID)
		return
	}
	nr.Status = consolidation.RunStatusRemoved
	nr.Reason = fmt.Sprintf("drained and terminated instance %s in ASG %s with decrement", instanceID, facts.ASG)
	rec := c.terminateRecord(runID, node.Name, policy.ActionSucceeded, true, "")
	rec.Verification = fmt.Sprintf("TerminateInstanceInAutoScalingGroup(%s, decrement=true) in %s", instanceID, facts.ASG)
	res.Actions = append(res.Actions, rec)
	if c.mset != nil {
		c.mset.IncConsolidationNodeRemoved("xamops-asg")
	}
	c.record(res, run, nr, mode, runID)
}

// terminateRecord builds the paired action record for the controller-owned ASG
// terminate step (separate from the drain's own record).
func (c *Consolidator) terminateRecord(runID, nodeName, status string, verified bool, reason string) policy.ActionRecord {
	rec := telemetry.NewAction(policy.ControllerConsolidator, "terminate", "node/"+nodeName, status != policy.ActionSucceeded)
	rec.OperationID = runID
	return telemetry.Finish(rec, status, verified, reason)
}

// record appends the node run to the DTO, updates the summary counters, and ensures
// an action record exists for a pre-filter skip (dwell/cap/recheck) that never
// reached the drain path.
func (c *Consolidator) record(
	res *ConsolidationResult,
	run *consolidation.ConsolidationRunDto,
	nr consolidation.ConsolidationNodeRun,
	mode gate.Mode,
	runID string,
) {
	run.Nodes = append(run.Nodes, nr)
	run.Summary.PodsEvicted += nr.PodsEvicted
	switch nr.Status {
	case consolidation.RunStatusRemoved:
		run.Summary.Removed++
	case consolidation.RunStatusDeferred:
		run.Summary.Deferred++
	case consolidation.RunStatusSimulated:
		run.Summary.Simulated++
	case consolidation.RunStatusSkipped:
		run.Summary.Skipped++
		// A pre-filter skip never reached Drain, so emit its own record so the skip is
		// visible in the activity feed.
		rec := telemetry.NewAction(policy.ControllerConsolidator, "consolidate", "node/"+nr.Node.Name, mode != gate.Live)
		rec.OperationID = runID
		res.Actions = append(res.Actions, telemetry.Finish(rec, policy.ActionSkipped, false, nr.Reason))
	case consolidation.RunStatusRefused:
		run.Summary.Refused++
	case consolidation.RunStatusAborted:
		run.Summary.Aborted++
	}
}

// runStatus collapses a run into one terminal status for the runs_total metric.
func runStatus(run *consolidation.ConsolidationRunDto) string {
	switch {
	case run.Summary.Aborted > 0:
		return policy.ActionAborted
	case run.Summary.Removed > 0 || run.Summary.Deferred > 0:
		return policy.ActionSucceeded
	case run.Summary.Simulated > 0:
		return policy.ActionSimulated
	case run.Summary.Refused > 0:
		return policy.ActionRefused
	case run.Summary.Skipped > 0:
		return policy.ActionSkipped
	default:
		return policy.ActionSkipped
	}
}
