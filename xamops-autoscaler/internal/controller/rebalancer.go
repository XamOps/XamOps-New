package controller

import (
	"context"
	"fmt"
	"log"
	"sort"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/state"
)

// Rebalancer drains on-demand nodes when the backend triggers a rebalance
// (rebalancer.status == "running"), so that Karpenter / Cluster Autoscaler can
// replace them. It never provisions nodes itself.
//
// Safety (plan.md D3-D7):
//   - it runs only when gate.Rebalancer is open (ENABLE_REBALANCER ∧ mode=full ∧
//     rebalancer.enabled ∧ status=running); DRY_RUN turns it into a simulation;
//   - each run has an operation ID (rebalancer.operationId or "gen-<N>" from the
//     ledger) and a live outcome is terminal: the same operation is never re-run,
//     across cycles and restarts. A dry run simulates each operation once per process
//     and writes nothing;
//   - candidates (on-demand, schedulable, non-control-plane; creationTimestamp asc,
//     name) go through drain.Executor.DrainCandidates: the fit simulator selects the
//     largest set drainable as a whole within the shared budget, the executor drains
//     it as one set (Eviction API, closed-loop re-plan before every eviction,
//     verified replacements, abort + uncordon). Nothing here deletes pods.
type Rebalancer struct {
	k8s    kubernetes.Interface
	dryRun bool

	// simulated remembers the first simulation of each operation in this process.
	simulated map[string]*RebalanceResult
}

func NewRebalancer(k8s kubernetes.Interface, dryRun bool) *Rebalancer {
	return &Rebalancer{k8s: k8s, dryRun: dryRun, simulated: map[string]*RebalanceResult{}}
}

// RebalanceResult captures what happened during a rebalance cycle.
type RebalanceResult struct {
	Status          string    `json:"status"` // simulated | refused | completed | failed
	StartedAt       time.Time `json:"startedAt"`
	CompletedAt     time.Time `json:"completedAt,omitempty"`
	NodesDrained    []string  `json:"nodesDrained"` // verified live drains only
	PodsEvicted     int       `json:"podsEvicted"`
	DurationMinutes int       `json:"durationMinutes"`
	Error           string    `json:"error,omitempty"`
	DryRun          bool      `json:"dryRun"`
	OperationID     string    `json:"operationId"`
	NodesSimulated  int       `json:"nodesSimulated"`
	Verified        bool      `json:"verified"`
	Reason          string    `json:"reason,omitempty"`
	// DrainReports are the executor's node reports of the drain attempted in THIS
	// call (collector "node-drain-progress"); empty when the operation was not
	// (re-)run.
	DrainReports []drain.Report `json:"-"`
}

// Run executes (or simulates) the current rebalance operation. It returns nil when
// the gate is Off. Observe the status on the ledger every cycle the rebalancer is
// armed (see ObserveRebalancerStatus) so running edges are counted.
func (rb *Rebalancer) Run(
	ctx context.Context,
	cfg policy.RebalancerConfig,
	guards policy.SafetyGuards,
	d gate.Decision,
	budget *drain.Budget,
	exec *drain.Executor,
	ledger *state.Ledger,
) (*RebalanceResult, []policy.ActionRecord, error) {
	if d.Mode != gate.Simulate && d.Mode != gate.Live {
		return nil, nil, nil
	}
	if ledger == nil || exec == nil {
		return nil, nil, fmt.Errorf("rebalancer needs a ledger and an executor")
	}
	if err := ledger.Observe(ctx, cfg.Status); err != nil {
		return nil, nil, fmt.Errorf("observe rebalancer status: %w", err)
	}
	opID, err := ledger.OperationID(ctx, cfg)
	if err != nil {
		return nil, nil, fmt.Errorf("resolve operation id: %w", err)
	}

	live := d.Mode == gate.Live
	result := &RebalanceResult{StartedAt: time.Now(), DryRun: !live, OperationID: opID}

	// ── Idempotency ───────────────────────────────────────────────────────────
	if live {
		op, ok, err := ledger.Get(ctx, opID)
		if err != nil {
			return nil, nil, fmt.Errorf("read ledger: %w", err)
		}
		if ok && op.Mode == "live" && state.IsTerminal(op.State) {
			result.Status = op.State
			result.Reason = fmt.Sprintf("operation %s already %s; not re-run", opID, op.State)
			if op.State == state.StateCompleted {
				result.NodesDrained = op.Nodes
				result.Verified = true
			}
			log.Printf("Rebalancer: %s", result.Reason)
			return result, nil, nil
		}
		if ok && op.Mode == "live" && op.State == state.StateRunning {
			// A "running" record at the start of a cycle belongs to a process that
			// died mid-operation (cycles are sequential). Never resume it blindly:
			// orphan recovery has already restored its nodes; close it as aborted.
			op.State = state.StateAborted
			op.FinishedAt = time.Now().UTC().Format(time.RFC3339)
			op.Reason = "interrupted by an autoscaler restart; nodes restored by orphan recovery"
			if err := ledger.Record(ctx, opID, op); err != nil {
				return nil, nil, fmt.Errorf("record interrupted operation: %w", err)
			}
			result.Status = state.StateAborted
			result.Reason = fmt.Sprintf("operation %s %s; not re-run", opID, op.Reason)
			return result, nil, nil
		}
	} else if prev, ok := rb.simulated[opID]; ok {
		again := *prev
		again.DrainReports = nil // nothing was simulated again
		again.Reason = fmt.Sprintf("operation %s already simulated in this process; not re-run", opID)
		return &again, nil, nil
	}

	// ── Candidates ────────────────────────────────────────────────────────────
	nodes, err := rb.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		result.Status = policy.ActionFailed
		result.Error = err.Error()
		return result, nil, err
	}
	candidates := selectRebalanceCandidates(nodes.Items)

	if budget.Remaining() == 0 {
		// Nothing was attempted: not terminal, the operation is retried next cycle.
		result.Status = policy.ActionRefused
		if !live {
			result.Status = policy.ActionSimulated
		}
		result.Reason = "drain budget exhausted before the rebalancer ran; retried next cycle"
		return result, nil, nil
	}
	if len(candidates) == 0 {
		return rb.finish(ctx, ledger, result, live, drain.CandidateResult{}, "no rebalance candidates (on-demand, schedulable, non-control-plane)"), nil, nil
	}
	log.Printf("Rebalancer: operation %s (%s) — candidates %v", opID, d.Mode, nodeNames(candidates))

	if live {
		if err := ledger.Record(ctx, opID, state.Operation{
			State: state.StateRunning, Mode: "live", StartedAt: result.StartedAt.UTC().Format(time.RFC3339),
			Nodes: nodeNames(candidates),
		}); err != nil {
			return nil, nil, fmt.Errorf("record operation start: %w", err)
		}
	}

	drainTimeout := time.Duration(cfg.DrainTimeoutMinutes) * time.Minute
	if drainTimeout <= 0 {
		drainTimeout = 20 * time.Minute
	}
	// The fit simulator picks, in candidate order, the largest set drainable as a
	// whole within the budget; the executor drains it as one set.
	dctx, cancel := context.WithTimeout(ctx, drainTimeout)
	cr := exec.DrainCandidates(dctx, policy.ControllerRebalancer, opID, nodeNames(candidates), 0, d.Mode, budget)
	cancel()
	return rb.finish(ctx, ledger, result, live, cr, ""), cr.Actions(), nil
}

// finish derives the status from the drain of the selected set and records live
// outcomes. completed = the selected set was drained and verified; failed = that
// drain aborted or failed; refused = no set was drainable (reasons from the plans).
func (rb *Rebalancer) finish(ctx context.Context, ledger *state.Ledger, result *RebalanceResult, live bool, cr drain.CandidateResult, reason string) *RebalanceResult {
	var reasons []string
	if reason != "" {
		reasons = append(reasons, reason)
	}
	failed, verified := false, false
	if o := cr.Set; o != nil {
		switch o.Status {
		case policy.ActionSimulated:
			result.NodesSimulated += len(o.Reports)
		case policy.ActionSucceeded:
			if o.Verified() {
				verified = true
				for _, rep := range o.Reports {
					result.NodesDrained = append(result.NodesDrained, rep.NodeName)
				}
			}
		case policy.ActionAborted, policy.ActionFailed:
			failed = true
		}
		result.PodsEvicted += len(o.Evicted)
	}
	for _, o := range cr.Outcomes() {
		for _, a := range o.Actions {
			if a.Reason != "" {
				reasons = append(reasons, a.Target+": "+a.Reason)
			}
		}
	}
	result.Reason = strings.Join(reasons, "; ")
	result.DrainReports = cr.Reports()
	result.CompletedAt = time.Now()
	result.DurationMinutes = int(result.CompletedAt.Sub(result.StartedAt).Minutes())

	switch {
	case !live:
		result.Status = policy.ActionSimulated
	case failed:
		result.Status = state.StateFailed
		result.Error = result.Reason
	case verified:
		result.Status = state.StateCompleted
		result.Verified = true
	default:
		result.Status = state.StateRefused
	}

	if !live {
		cp := *result
		rb.simulated[result.OperationID] = &cp
		log.Printf("Rebalancer: operation %s simulated — %d node(s) simulated; %s", result.OperationID, result.NodesSimulated, result.Reason)
		return result
	}
	op := state.Operation{
		State: result.Status, Mode: "live",
		StartedAt:  result.StartedAt.UTC().Format(time.RFC3339),
		FinishedAt: result.CompletedAt.UTC().Format(time.RFC3339),
		Nodes:      result.NodesDrained, Reason: result.Reason,
	}
	// Detached from ctx so a drain cut short by SIGTERM still records its terminal
	// outcome instead of leaving the operation "running".
	lctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), drain.DefaultRestoreTimeout)
	defer cancel()
	if err := ledger.Record(lctx, result.OperationID, op); err != nil {
		log.Printf("ERROR: rebalancer: record operation %s: %v", result.OperationID, err)
		result.Error = strings.TrimPrefix(result.Error+"; ledger: "+err.Error(), "; ")
	}
	log.Printf("Rebalancer: operation %s %s — %d node(s) drained (verified); %s", result.OperationID, result.Status, len(result.NodesDrained), result.Reason)
	return result
}

// ObserveRebalancerStatus feeds the ledger the status seen this cycle when the
// rebalancer is armed (ENABLE_REBALANCER ∧ mode=full ∧ rebalancer.enabled), so a
// non-running -> running edge starts a new generation. When not armed nothing is
// observed (and nothing is written).
func ObserveRebalancerStatus(ctx context.Context, ledger *state.Ledger, armed bool, status string) error {
	if !armed || ledger == nil {
		return nil
	}
	return ledger.Observe(ctx, status)
}

// selectRebalanceCandidates: not control-plane/master, not cordoned, not SPOT;
// ordered by creationTimestamp ascending (oldest first), then name.
func selectRebalanceCandidates(nodes []corev1.Node) []corev1.Node {
	candidates := make([]corev1.Node, 0)
	for _, node := range nodes {
		if _, ok := node.Labels["node-role.kubernetes.io/control-plane"]; ok {
			continue
		}
		if _, ok := node.Labels["node-role.kubernetes.io/master"]; ok {
			continue
		}
		if node.Spec.Unschedulable {
			continue
		}
		if node.Labels["eks.amazonaws.com/capacityType"] == "SPOT" {
			continue
		}
		candidates = append(candidates, node)
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		ti, tj := candidates[i].CreationTimestamp.Time, candidates[j].CreationTimestamp.Time
		if !ti.Equal(tj) {
			return ti.Before(tj)
		}
		return candidates[i].Name < candidates[j].Name
	})
	return candidates
}

func nodeNames(nodes []corev1.Node) []string {
	names := make([]string, len(nodes))
	for i, n := range nodes {
		names[i] = n.Name
	}
	return names
}
