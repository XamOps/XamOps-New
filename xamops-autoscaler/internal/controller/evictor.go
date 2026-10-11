package controller

import (
	"context"
	"fmt"
	"log"
	"sort"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

// EvictorResult is returned after each evictor cycle.
type EvictorResult struct {
	CycleTime      time.Time
	CandidateNodes []NodeCandidate   // every node, with its fit plan
	EvictedPods    []string          // pod names that were evicted
	DrainedNodes   []string          // nodes drained live AND verified by the executor
	NodesSaved     int               // verified live drains only (never counts simulations)
	SkipReasons    map[string]string // nodeName → reason the drain did not complete
	DryRun         bool
	AnalyseOnly    bool // true when the cycle only produced candidates and took no action
}

// NodeCandidate is one node as the evictor sees it (collector
// "autoscaling-evictor-candidates", read leniently by the backend).
type NodeCandidate struct {
	NodeName       string  `json:"nodeName"`
	AllocatableCPU int64   `json:"allocatableCpuMillis"`
	AllocatableMem int64   `json:"allocatableMemBytes"`
	UsedCPU        int64   `json:"usedCpuMillis"`
	UsedMem        int64   `json:"usedMemBytes"`
	UtilizationCPU float64 `json:"utilizationCpuPercent"`
	UtilizationMem float64 `json:"utilizationMemPercent"`
	PodCount       int     `json:"podCount"`
	CanEvict       bool    `json:"canEvict"`
	// SkipReason is a human summary: the first failed node gate, else the fit
	// plan's summary when the node is not drainable.
	SkipReason string      `json:"skipReason,omitempty"`
	DryRun     bool        `json:"dryRun"`
	Fit        *FitSummary `json:"fit"`
}

// FitSummary is the single-node fit plan of a candidate (plan.md section 3). The
// harness compares it with the Python oracle (compare_fit.py).
type FitSummary struct {
	Drainable     bool              `json:"drainable"`
	ExcludedCount int               `json:"excludedCount"`
	Refused       []fitsim.Finding  `json:"refused"`
	Blocked       []fitsim.Finding  `json:"blocked"`
	Strands       []string          `json:"strands"`
	Moves         []fitsim.Move     `json:"moves"`
	PDBChecks     []fitsim.PDBCheck `json:"pdbChecks"`
	Summary       string            `json:"summary"`
}

func fitSummary(p fitsim.Plan) *FitSummary {
	return &FitSummary{
		Drainable: p.Drainable, ExcludedCount: p.ExcludedCount, Refused: p.Refused, Blocked: p.Blocked,
		Strands: p.Strands, Moves: p.Moves, PDBChecks: p.PDBChecks, Summary: p.Summary,
	}
}

// Evictor implements threshold-based scale-in with a real scheduler-fit check.
//
// Every node gets a single-node what-if plan from the fit simulator (internal/fitsim,
// plan.md section 3): DaemonSet and mirror/static pods are excluded (they never move
// and never block), unmodelled pods are refused, guarded pods block, and every other
// pod must be placed on another schedulable Ready node by first-fit decreasing. The
// plan is attached to the candidate as `fit`.
//
// canEvict = the node gates pass (not cordoned, older than NodeGracePeriodMinutes, no
// control-plane/master role label, summed effective pod REQUESTS at or below
// TargetUtilizationCpu and TargetUtilizationMemory — a requests ratio, not live
// usage) AND the plan is drainable.
//
// Candidates are sorted by CPU requests utilisation ascending, then name. In
// Simulate/Live the canEvict nodes go to drain.Executor.DrainCandidates, which
// selects (fitsim.PlanDrainSet, caller order, fresh snapshot) the largest set that is
// drainable as a whole within the global budget and drains it as one set with the
// closed-loop re-plan before every eviction. Analyse (the default) only reports.
type Evictor struct {
	k8s    kubernetes.Interface
	dryRun bool
}

func NewEvictor(k8s kubernetes.Interface, dryRun bool) *Evictor {
	return &Evictor{k8s: k8s, dryRun: dryRun}
}

// Run executes one evictor cycle. NodesSaved and DrainedNodes count only verified
// live drains.
func (e *Evictor) Run(
	ctx context.Context,
	p policy.EvictorPolicy,
	guards policy.SafetyGuards,
	d gate.Decision,
	budget *drain.Budget,
	exec *drain.Executor,
) (*EvictorResult, []policy.ActionRecord, error) {
	result := &EvictorResult{
		CycleTime:   time.Now(),
		SkipReasons: map[string]string{},
		DryRun:      d.Mode != gate.Live,
	}

	if d.Mode == gate.Off || !p.Enabled {
		log.Printf("Evictor: off (%s), skipping", d.Reason)
		return result, nil, nil
	}

	nodes, err := e.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, nil, fmt.Errorf("list nodes: %w", err)
	}
	snap, err := fitsim.BuildSnapshot(ctx, e.k8s)
	if err != nil {
		return nil, nil, fmt.Errorf("fit snapshot: %w", err)
	}
	opts := fitsim.Options{Guards: guards}
	if exec != nil {
		opts.SelfNamespace = exec.SelfNamespace()
	}

	gracePeriod := time.Duration(p.NodeGracePeriodMinutes) * time.Minute
	candidates := make([]NodeCandidate, 0, len(nodes.Items))
	for _, node := range nodes.Items {
		plan := fitsim.SimulateDrain(snap, []string{node.Name}, opts)
		c := e.evaluateNode(node, snap.Node(node.Name), plan, p, gracePeriod)
		c.DryRun = result.DryRun
		candidates = append(candidates, c)
	}

	// Most empty first, then name (deterministic).
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].UtilizationCPU != candidates[j].UtilizationCPU {
			return candidates[i].UtilizationCPU < candidates[j].UtilizationCPU
		}
		return candidates[i].NodeName < candidates[j].NodeName
	})
	result.CandidateNodes = candidates
	eligible := filterCanEvict(candidates)

	if d.Mode == gate.Analyse || p.AnalyseOnly {
		result.AnalyseOnly = true
		log.Printf("Evictor: analyse-only mode — %d node(s) evaluated, %d consolidation candidate(s), no action taken",
			len(candidates), len(eligible))
		return result, nil, nil
	}
	if len(eligible) == 0 {
		log.Printf("Evictor: no consolidation candidates this cycle")
		return result, nil, nil
	}
	if exec == nil {
		return result, nil, fmt.Errorf("evictor needs a drain executor")
	}

	names := make([]string, len(eligible))
	for i, c := range eligible {
		names[i] = c.NodeName
	}
	log.Printf("Evictor: consolidation candidates %v — %s", names, d.Mode)
	opID := fmt.Sprintf("evictor-%d", result.CycleTime.Unix())
	res := exec.DrainCandidates(ctx, policy.ControllerEvictor, opID, names, 0, d.Mode, budget)
	if res.Set != nil && res.Set.Verified() {
		result.DrainedNodes = append(result.DrainedNodes, res.Selected...)
		result.EvictedPods = append(result.EvictedPods, res.Set.Evicted...)
		result.NodesSaved += len(res.Selected)
	}
	for _, o := range res.Outcomes() {
		if o.Verified() || len(o.Actions) == 0 {
			continue
		}
		for _, rep := range o.Reports {
			result.SkipReasons[rep.NodeName] = o.Status + ": " + o.Actions[0].Reason
		}
	}
	return result, res.Actions(), nil
}

// evaluateNode applies the node gates and attaches the fit plan.
func (e *Evictor) evaluateNode(
	node corev1.Node,
	sn *fitsim.Node,
	plan fitsim.Plan,
	p policy.EvictorPolicy,
	gracePeriod time.Duration,
) NodeCandidate {
	c := NodeCandidate{NodeName: node.Name, Fit: fitSummary(plan)}
	if sn != nil {
		c.PodCount = int(sn.Requested.Pods)
		c.AllocatableCPU = sn.Allocatable.MilliCPU
		c.AllocatableMem = sn.Allocatable.MemoryBytes
		c.UsedCPU = sn.Requested.MilliCPU
		c.UsedMem = sn.Requested.MemoryBytes
	}
	if c.AllocatableCPU > 0 {
		c.UtilizationCPU = float64(c.UsedCPU) / float64(c.AllocatableCPU) * 100
	}
	if c.AllocatableMem > 0 {
		c.UtilizationMem = float64(c.UsedMem) / float64(c.AllocatableMem) * 100
	}

	age := time.Since(node.CreationTimestamp.Time)
	_, controlPlane := node.Labels["node-role.kubernetes.io/control-plane"]
	_, master := node.Labels["node-role.kubernetes.io/master"]
	switch {
	case node.Spec.Unschedulable:
		c.SkipReason = "already cordoned"
	case age < gracePeriod:
		c.SkipReason = fmt.Sprintf("node too young (%.1f min < %d min grace period)", age.Minutes(), p.NodeGracePeriodMinutes)
	case controlPlane:
		c.SkipReason = "control-plane node"
	case master:
		c.SkipReason = "master node"
	case c.UtilizationCPU > float64(p.TargetUtilizationCpu) || c.UtilizationMem > float64(p.TargetUtilizationMemory):
		c.SkipReason = fmt.Sprintf("utilization above target (CPU=%.1f%% Mem=%.1f%%)", c.UtilizationCPU, c.UtilizationMem)
	case !plan.Drainable:
		c.SkipReason = plan.Summary
	default:
		c.CanEvict = true
	}
	return c
}

func filterCanEvict(candidates []NodeCandidate) []NodeCandidate {
	out := make([]NodeCandidate, 0)
	for _, c := range candidates {
		if c.CanEvict {
			out = append(out, c)
		}
	}
	return out
}
