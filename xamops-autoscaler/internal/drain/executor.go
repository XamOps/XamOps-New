package drain

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sort"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/telemetry"
)

// Report statuses (collector "node-drain-progress").
const (
	StatusQueued    = "queued"
	StatusCordoned  = "cordoned"
	StatusDraining  = "draining"
	StatusDrained   = "drained"
	StatusFailed    = "failed"
	StatusSkipped   = "skipped"
	StatusSimulated = "simulated"
	StatusRefused   = "refused"
	StatusAborted   = "aborted"
)

// ControllerExecutor names records the executor produces on its own (orphan recovery).
const ControllerExecutor = "drain-executor"

// MirrorPodAnnotation marks static/mirror pods (never moved, never block).
const MirrorPodAnnotation = "kubernetes.io/config.mirror"

// Report describes one node's drain (collector "node-drain-progress").
type Report struct {
	NodeName    string     `json:"nodeName"`
	Status      string     `json:"status"` // queued | cordoned | draining | drained | failed | skipped | simulated | refused | aborted
	Reason      string     `json:"reason,omitempty"`
	PodsTotal   int        `json:"podsTotal"`
	PodsEvicted int        `json:"podsEvicted"`
	PodsSkipped int        `json:"podsSkipped"`
	StartedAt   time.Time  `json:"startedAt"`
	FinishedAt  *time.Time `json:"finishedAt,omitempty"`
	Error       string     `json:"error,omitempty"`
	DryRun      bool       `json:"dryRun"`
	Verified    bool       `json:"verified"`
	OperationID string     `json:"operationId,omitempty"`
}

// Outcome is the result of one Drain / CordonOnly call.
type Outcome struct {
	Reports []Report              `json:"reports"`
	Actions []policy.ActionRecord `json:"actions"`
	// Status is an ActionRecord status: simulated | succeeded | refused | skipped | aborted | failed.
	Status  string   `json:"status"`
	Plan    *Plan    `json:"plan,omitempty"`
	Evicted []string `json:"evicted,omitempty"` // namespace/name, live only
}

// Verified reports whether the outcome is a verified live success.
func (o Outcome) Verified() bool {
	return o.Status == policy.ActionSucceeded && len(o.Actions) > 0 && telemetry.VerifiedLive(o.Actions[0])
}

// Executor is the single drain path for every controller.
type Executor struct {
	k8s           kubernetes.Interface
	planner       Planner
	selfNamespace string

	// ReplacementTimeout bounds the wait for each evicted pod's replacement and for
	// the final verification. PDBRetry bounds retries of a 429 (PDB) eviction.
	// PollInterval is the polling period (injectable for tests).
	ReplacementTimeout time.Duration
	PDBRetry           time.Duration
	PollInterval       time.Duration
}

// NewExecutor returns an executor with the plan.md D6 timings (180s / 60s / 5s).
func NewExecutor(k8s kubernetes.Interface, planner Planner, selfNamespace string) *Executor {
	if planner == nil {
		planner = UnavailablePlanner{}
	}
	return &Executor{
		k8s:                k8s,
		planner:            planner,
		selfNamespace:      selfNamespace,
		ReplacementTimeout: 180 * time.Second,
		PDBRetry:           60 * time.Second,
		PollInterval:       5 * time.Second,
	}
}

// SelfNamespace returns the always-protected namespace.
func (e *Executor) SelfNamespace() string { return e.selfNamespace }

func nodeTarget(nodes []string) string {
	if len(nodes) == 1 {
		return "node/" + nodes[0]
	}
	return "nodes/" + strings.Join(nodes, ",")
}

// run carries one call's bookkeeping.
type run struct {
	e          *Executor
	controller string
	mode       gate.Mode
	budget     *Budget
	nodes      []string
	newlyHeld  []string // reservations this call made (released on refusal)
	rec        policy.ActionRecord
	reports    []Report
	plan       *Plan
	evicted    []string
}

func (e *Executor) newRun(controller, action, operationID string, nodes []string, mode gate.Mode, budget *Budget) *run {
	dry := mode != gate.Live
	r := &run{e: e, controller: controller, mode: mode, budget: budget, nodes: append([]string(nil), nodes...)}
	r.rec = telemetry.NewAction(controller, action, nodeTarget(nodes), dry)
	r.rec.OperationID = operationID
	now := time.Now().UTC()
	for _, n := range nodes {
		r.reports = append(r.reports, Report{NodeName: n, Status: StatusQueued, StartedAt: now, DryRun: dry, OperationID: operationID})
	}
	return r
}

func (r *run) releaseHeld() {
	for _, n := range r.newlyHeld {
		r.budget.Release(r.controller, n)
	}
	r.newlyHeld = nil
}

// done finishes the call with one status for every node report.
func (r *run) done(status, reportStatus string, verified bool, reason string) Outcome {
	now := time.Now().UTC()
	for i := range r.reports {
		r.reports[i].Status = reportStatus
		r.reports[i].Reason = reason
		r.reports[i].Verified = verified
		r.reports[i].FinishedAt = &now
		if status == policy.ActionAborted || status == policy.ActionFailed {
			r.reports[i].Error = reason
		}
	}
	rec := telemetry.Finish(r.rec, status, verified, reason)
	log.Printf("Drain[%s %s %s]: %s — %s", r.controller, rec.Action, rec.Target, status, reason)
	return Outcome{Reports: r.reports, Actions: []policy.ActionRecord{rec}, Status: status, Plan: r.plan, Evicted: r.evicted}
}

// prepare runs the steps shared by Drain and CordonOnly: mode check, budget, plan,
// self-namespace protection. It returns a non-nil Outcome when the call ends here.
func (r *run) prepare(ctx context.Context) *Outcome {
	e := r.e
	if r.mode != gate.Simulate && r.mode != gate.Live {
		o := r.done(policy.ActionSkipped, StatusSkipped, false, fmt.Sprintf("gate mode %s does not allow node actions", r.mode))
		return &o
	}
	if len(r.nodes) == 0 {
		o := r.done(policy.ActionRefused, StatusRefused, false, "empty node set")
		return &o
	}
	if r.budget == nil {
		o := r.done(policy.ActionRefused, StatusRefused, false, "no drain budget")
		return &o
	}
	for _, n := range r.nodes {
		held := r.budget.holds(r.controller, n)
		if !r.budget.TryReserve(r.controller, n) {
			r.releaseHeld()
			o := r.done(policy.ActionSkipped, StatusSkipped, false,
				fmt.Sprintf("drain budget exhausted (capacity %d, remaining %d)", r.budget.Capacity(), r.budget.Remaining()))
			return &o
		}
		if !held {
			r.newlyHeld = append(r.newlyHeld, n)
		}
	}

	plan, err := e.planner.Plan(ctx, r.nodes)
	if err != nil {
		r.releaseHeld()
		o := r.done(policy.ActionRefused, StatusRefused, false, fmt.Sprintf("planner error: %v", err))
		return &o
	}
	r.plan = &plan
	telemetry.SetDetail(&r.rec, "fitChecked", plan.FitChecked)
	telemetry.SetDetail(&r.rec, "drainable", plan.Drainable)
	telemetry.SetDetail(&r.rec, "plannedMoves", len(plan.Moves))
	if len(plan.Reasons) > 0 {
		telemetry.SetDetail(&r.rec, "planReasons", plan.Reasons)
	}
	telemetry.SetDetail(&r.rec, "budget", r.budget.Snapshot())
	movesFrom := map[string]int{}
	for _, m := range plan.Moves {
		movesFrom[m.From]++
	}
	for i := range r.reports {
		r.reports[i].PodsTotal = movesFrom[r.reports[i].NodeName]
	}

	if !plan.Drainable {
		r.releaseHeld()
		reason := strings.Join(plan.Reasons, "; ")
		if reason == "" {
			reason = "plan is not drainable"
		}
		o := r.done(policy.ActionRefused, StatusRefused, false, reason)
		return &o
	}
	if reason := e.selfNamespaceViolation(ctx, plan, r.nodes); reason != "" {
		r.releaseHeld()
		o := r.done(policy.ActionRefused, StatusRefused, false, reason)
		return &o
	}
	for _, m := range plan.Moves {
		if m.OwnerUID == "" {
			r.releaseHeld()
			o := r.done(policy.ActionRefused, StatusRefused, false,
				fmt.Sprintf("pod %s/%s has no controller owner — its replacement cannot be verified", m.Namespace, m.Name))
			return &o
		}
	}
	return nil
}

// selfNamespaceViolation refuses any set that would move a pod of the autoscaler's
// own namespace, whether the planner listed it or not (the backend's default
// criticalNamespaces omit xamops-system).
func (e *Executor) selfNamespaceViolation(ctx context.Context, plan Plan, nodes []string) string {
	if e.selfNamespace == "" {
		return ""
	}
	for _, m := range plan.Moves {
		if m.Namespace == e.selfNamespace {
			return fmt.Sprintf("planned move of %s/%s is in the autoscaler's own namespace %s — refused", m.Namespace, m.Name, e.selfNamespace)
		}
	}
	pods, err := e.podsOnNodes(ctx, nodes)
	if err != nil {
		return fmt.Sprintf("cannot list pods to check the autoscaler's own namespace: %v", err)
	}
	for _, p := range pods {
		if p.Namespace == e.selfNamespace && !excluded(p) {
			return fmt.Sprintf("node %s hosts %s/%s from the autoscaler's own namespace %s — refused", p.Spec.NodeName, p.Namespace, p.Name, e.selfNamespace)
		}
	}
	return ""
}

func (b *Budget) holds(controller, node string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.reserved[node] == controller
}

// Drain drains nodes as one set. Simulate never writes; Live follows plan.md D6.
func (e *Executor) Drain(ctx context.Context, controller, operationID string, nodes []string, mode gate.Mode, budget *Budget) Outcome {
	r := e.newRun(controller, "drain", operationID, nodes, mode, budget)
	if o := r.prepare(ctx); o != nil {
		return *o
	}
	plan := *r.plan
	if mode == gate.Simulate {
		telemetry.SetDetail(&r.rec, "moves", plan.Moves)
		return r.done(policy.ActionSimulated, StatusSimulated, false,
			fmt.Sprintf("would cordon and evict %d pod(s) through the Eviction API", len(plan.Moves)))
	}
	return e.liveDrain(ctx, r, operationID)
}

func (e *Executor) liveDrain(ctx context.Context, r *run, operationID string) Outcome {
	plan := *r.plan
	cordoned := map[string]bool{}
	abort := func(reason string) Outcome {
		for _, n := range r.nodes {
			if err := e.markAborted(ctx, n, cordoned[n]); err != nil {
				log.Printf("ERROR: drain abort: restore node %s: %v", n, err)
				reason += fmt.Sprintf("; restoring node %s failed: %v", n, err)
			}
		}
		telemetry.SetDetail(&r.rec, "uncordoned", sortedKeys(cordoned))
		return r.done(policy.ActionAborted, StatusAborted, false, reason)
	}

	// 1. Cordon by merge patch, annotated with the operation.
	for i, n := range r.nodes {
		node, err := e.k8s.CoreV1().Nodes().Get(ctx, n, metav1.GetOptions{})
		if err != nil {
			return abort(fmt.Sprintf("get node %s: %v", n, err))
		}
		ann := map[string]interface{}{AnnotationOperation: operationID, AnnotationState: StateDraining}
		patch := map[string]interface{}{"metadata": map[string]interface{}{"annotations": ann}}
		if !node.Spec.Unschedulable {
			ann[AnnotationCordonedBy] = CordonedByXamops
			patch["spec"] = map[string]interface{}{"unschedulable": true}
		}
		if err := e.patchNode(ctx, n, patch); err != nil {
			return abort(fmt.Sprintf("cordon node %s: %v", n, err))
		}
		if !node.Spec.Unschedulable {
			cordoned[n] = true
		}
		r.reports[i].Status = StatusCordoned
	}

	inSet := map[string]bool{}
	for _, n := range r.nodes {
		inSet[n] = true
	}
	reportIdx := map[string]int{}
	for i, rep := range r.reports {
		reportIdx[rep.NodeName] = i
	}

	// 2. Closed loop (plan.md D6/FEAT-005): before EVERY eviction the remaining pods of
	// the set are re-planned against fresh cluster state; the drain continues only
	// while the set is still drainable, evicting the largest remaining planned pod.
	// A transient PDB obstacle is waited for up to PDBRetry. Each replacement must be
	// a NEW pod.
	owners := map[string]Move{}
	evicted := map[string]bool{}
	maxEvictions := 2*len(plan.Moves) + 5
	replans := 0
	for n := 0; ; n++ {
		if err := ctx.Err(); err != nil {
			return abort(fmt.Sprintf("context ended: %v", err))
		}
		fresh, waited, err := e.replanUntilSettled(ctx, r.nodes, evicted)
		replans++
		telemetry.SetDetail(&r.rec, "replans", replans)
		if err != nil {
			return abort(fmt.Sprintf("re-plan before eviction %d: planner error: %v", n+1, err))
		}
		if !fresh.Drainable {
			reason := strings.Join(fresh.Reasons, "; ")
			if waited > 0 {
				reason += fmt.Sprintf(" (waited %s for the PDB to allow a disruption)", waited.Round(time.Millisecond))
			}
			return abort(fmt.Sprintf("re-plan before eviction %d: set no longer drainable: %s", n+1, reason))
		}
		var next *Move
		for i := range fresh.Moves {
			if !evicted[moveKey(fresh.Moves[i])] {
				next = &fresh.Moves[i]
				break
			}
		}
		if next == nil {
			break
		}
		if n >= maxEvictions {
			return abort(fmt.Sprintf("drain did not converge after %d evictions", n))
		}
		m := *next
		if m.OwnerUID == "" {
			return abort(fmt.Sprintf("re-plan: pod %s/%s has no controller owner — its replacement cannot be verified", m.Namespace, m.Name))
		}
		if e.selfNamespace != "" && m.Namespace == e.selfNamespace {
			return abort(fmt.Sprintf("re-plan: move of %s/%s is in the autoscaler's own namespace %s — refused", m.Namespace, m.Name, e.selfNamespace))
		}
		known, err := e.ownerPodUIDs(ctx, m.Namespace, m.OwnerUID)
		if err != nil {
			return abort(fmt.Sprintf("list siblings of %s/%s: %v", m.Namespace, m.Name, err))
		}
		if m.UID != "" {
			known[m.UID] = true
		}
		if i, ok := reportIdx[m.From]; ok {
			r.reports[i].Status = StatusDraining
		}
		if err := e.evict(ctx, m); err != nil {
			return abort(fmt.Sprintf("eviction of %s/%s: %v", m.Namespace, m.Name, err))
		}
		evicted[moveKey(m)] = true
		r.evicted = append(r.evicted, m.Namespace+"/"+m.Name)
		if i, ok := reportIdx[m.From]; ok {
			r.reports[i].PodsEvicted++
		}
		repl, ok := e.waitForReplacement(ctx, m.Namespace, m.OwnerUID, known, inSet, e.ReplacementTimeout)
		if !ok {
			return abort(fmt.Sprintf("no replacement for %s/%s (%s %s) with a new UID Running+Ready on another node within %s",
				m.Namespace, m.Name, m.OwnerKind, m.OwnerName, e.ReplacementTimeout))
		}
		log.Printf("Drain: %s/%s replaced by %s/%s (uid %s) on %s", m.Namespace, m.Name, repl.Namespace, repl.Name, repl.UID, repl.Spec.NodeName)
		owners[m.Namespace+"/"+m.OwnerUID] = m
	}

	// 3. Verify: only excluded pods remain and no affected owner has Pending pods.
	if detail, ok := e.verifyDrained(ctx, r.nodes, owners, e.ReplacementTimeout); !ok {
		return abort("verification failed: " + detail)
	}

	// 4. Mark drained (the nodes stay cordoned for the node autoscaler to remove).
	for _, n := range r.nodes {
		patch := map[string]interface{}{"metadata": map[string]interface{}{"annotations": map[string]interface{}{AnnotationState: StateDrained}}}
		if err := e.patchNode(ctx, n, patch); err != nil {
			return abort(fmt.Sprintf("annotate node %s drained: %v", n, err))
		}
	}
	r.rec.Verification = fmt.Sprintf("%d pod(s) replaced by new-UID pods Running+Ready on other nodes (%d re-plan(s)); node(s) hold only DaemonSet/mirror pods; no Pending pods for affected owners",
		len(r.evicted), replans)
	return r.done(policy.ActionSucceeded, StatusDrained, true, "")
}

// moveKey identifies an evicted pod across re-plans (UID, else namespace/name).
func moveKey(m Move) string {
	if m.UID != "" {
		return m.UID
	}
	return m.Namespace + "/" + m.Name
}

// replan asks the planner for a fresh plan of nodes without the pods already evicted.
func (e *Executor) replan(ctx context.Context, nodes []string, evicted map[string]bool) (Plan, error) {
	if rp, ok := e.planner.(Replanner); ok {
		uids := map[string]bool{}
		for k := range evicted {
			uids[k] = true
		}
		return rp.Replan(ctx, nodes, uids)
	}
	return e.planner.Plan(ctx, nodes)
}

// replanUntilSettled re-plans; while the only obstacle is transient PDB state
// (Plan.Retryable) it polls up to PDBRetry. It returns the last plan and how long it
// waited.
func (e *Executor) replanUntilSettled(ctx context.Context, nodes []string, evicted map[string]bool) (Plan, time.Duration, error) {
	start := time.Now()
	deadline := start.Add(e.PDBRetry)
	for {
		p, err := e.replan(ctx, nodes, evicted)
		if err != nil || p.Drainable || !p.Retryable || !time.Now().Before(deadline) {
			return p, time.Since(start), err
		}
		if err := sleep(ctx, e.PollInterval); err != nil {
			return p, time.Since(start), nil
		}
	}
}

// SelectSet chooses, in the caller's order, the drain set of at most capacity nodes
// the planner proves drainable as a whole (plan.md section 3.8). plans holds the plan
// of every candidate considered (the set so far plus that candidate).
func (e *Executor) SelectSet(ctx context.Context, candidates []string, capacity int) ([]string, map[string]Plan, error) {
	if s, ok := e.planner.(SetSelector); ok {
		return s.SelectSet(ctx, candidates, capacity)
	}
	selected := []string{}
	plans := map[string]Plan{}
	for _, c := range dedupe(candidates) {
		if len(selected) >= capacity {
			break
		}
		p, err := e.planner.Plan(ctx, append(append([]string{}, selected...), c))
		if err != nil {
			return nil, nil, err
		}
		plans[c] = p
		if p.Drainable {
			selected = append(selected, c)
		}
	}
	return selected, plans, nil
}

func dedupe(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

// CandidateResult is the outcome of DrainCandidates.
type CandidateResult struct {
	Selected []string
	Plans    map[string]Plan
	// Set is the drain of the selected set (nil when nothing was selected).
	Set *Outcome
	// Others has one outcome per candidate not selected: refused with the plan's
	// reasons, or skipped (budget exhausted / selection limit reached).
	Others []Outcome
}

// Outcomes returns Set (if any) followed by Others.
func (c CandidateResult) Outcomes() []Outcome {
	var out []Outcome
	if c.Set != nil {
		out = append(out, *c.Set)
	}
	return append(out, c.Others...)
}

// Actions returns every action record, Set first.
func (c CandidateResult) Actions() []policy.ActionRecord {
	var out []policy.ActionRecord
	for _, o := range c.Outcomes() {
		out = append(out, o.Actions...)
	}
	return out
}

// Reports returns every node report, Set first.
func (c CandidateResult) Reports() []Report {
	var out []Report
	for _, o := range c.Outcomes() {
		out = append(out, o.Reports...)
	}
	return out
}

// DrainCandidates is the shared selection + drain path of the rebalancer, the
// evictor and the node optimizer: it selects the largest drain set the planner proves
// drainable as a whole, in the caller's deterministic order, within the remaining
// budget (and maxNodes when > 0), drains that set as ONE set (cordon all, then evict
// with the closed loop) and records every other candidate as refused (with the
// plan's reasons) or skipped.
func (e *Executor) DrainCandidates(ctx context.Context, controller, operationID string, candidates []string, maxNodes int, mode gate.Mode, budget *Budget) CandidateResult {
	res := CandidateResult{Plans: map[string]Plan{}}
	cands := dedupe(candidates)
	if (mode != gate.Simulate && mode != gate.Live) || budget == nil {
		for _, c := range cands {
			res.Others = append(res.Others, e.Drain(ctx, controller, operationID, []string{c}, mode, budget))
		}
		return res
	}
	capacity := budget.Remaining()
	limited := false
	if maxNodes > 0 && maxNodes < capacity {
		capacity, limited = maxNodes, true
	}
	if capacity > 0 && len(cands) > 0 {
		sel, plans, err := e.SelectSet(ctx, cands, capacity)
		if err != nil {
			for _, c := range cands {
				res.Others = append(res.Others, e.finishedWithout(controller, operationID, c, mode, nil,
					policy.ActionRefused, StatusRefused, fmt.Sprintf("planner error: %v", err)))
			}
			return res
		}
		res.Selected, res.Plans = sel, plans
	}
	if len(res.Selected) > 0 {
		o := e.Drain(ctx, controller, operationID, res.Selected, mode, budget)
		res.Set = &o
	}
	inSet := map[string]bool{}
	for _, n := range res.Selected {
		inSet[n] = true
	}
	for _, c := range cands {
		if inSet[c] {
			continue
		}
		if p, ok := res.Plans[c]; ok {
			reason := strings.Join(p.Reasons, "; ")
			if reason == "" {
				reason = "plan is not drainable"
			}
			if len(p.Nodes) > 1 {
				reason = fmt.Sprintf("not drainable together with %s: %s", strings.Join(res.Selected, ","), reason)
			}
			res.Others = append(res.Others, e.finishedWithout(controller, operationID, c, mode, &p,
				policy.ActionRefused, StatusRefused, reason))
			continue
		}
		reason := fmt.Sprintf("drain budget exhausted (capacity %d, remaining %d)", budget.Capacity(), budget.Remaining())
		if limited {
			reason = fmt.Sprintf("selection limit reached (%d node(s) needed)", maxNodes)
		}
		res.Others = append(res.Others, e.finishedWithout(controller, operationID, c, mode, nil,
			policy.ActionSkipped, StatusSkipped, reason))
	}
	return res
}

// finishedWithout records a candidate that was never handed to Drain (no budget
// reservation, no API call).
func (e *Executor) finishedWithout(controller, operationID, node string, mode gate.Mode, plan *Plan, status, reportStatus, reason string) Outcome {
	r := e.newRun(controller, "drain", operationID, []string{node}, mode, nil)
	if plan != nil {
		r.plan = plan
		telemetry.SetDetail(&r.rec, "fitChecked", plan.FitChecked)
		telemetry.SetDetail(&r.rec, "drainable", plan.Drainable)
		if len(plan.Reasons) > 0 {
			telemetry.SetDetail(&r.rec, "planReasons", plan.Reasons)
		}
	}
	return r.done(status, reportStatus, false, reason)
}

// CordonOnly cordons one node after the same budget/plan/self-namespace checks as a drain.
func (e *Executor) CordonOnly(ctx context.Context, controller, operationID, node string, mode gate.Mode, budget *Budget) Outcome {
	r := e.newRun(controller, "cordon", operationID, []string{node}, mode, budget)
	if o := r.prepare(ctx); o != nil {
		return *o
	}
	if mode == gate.Simulate {
		return r.done(policy.ActionSimulated, StatusSimulated, false, "would cordon the node")
	}
	current, err := e.k8s.CoreV1().Nodes().Get(ctx, node, metav1.GetOptions{})
	if err != nil {
		return r.done(policy.ActionFailed, StatusFailed, false, fmt.Sprintf("get node: %v", err))
	}
	if current.Spec.Unschedulable {
		return r.done(policy.ActionSkipped, StatusSkipped, false, "node already unschedulable")
	}
	patch := map[string]interface{}{
		"metadata": map[string]interface{}{"annotations": map[string]interface{}{
			AnnotationOperation: operationID, AnnotationState: StateCordoned, AnnotationCordonedBy: CordonedByXamops}},
		"spec": map[string]interface{}{"unschedulable": true},
	}
	if err := e.patchNode(ctx, node, patch); err != nil {
		return r.done(policy.ActionFailed, StatusFailed, false, fmt.Sprintf("cordon: %v", err))
	}
	after, err := e.k8s.CoreV1().Nodes().Get(ctx, node, metav1.GetOptions{})
	if err != nil || !after.Spec.Unschedulable {
		return r.done(policy.ActionSucceeded, StatusCordoned, false, "cordon requested; read-back did not confirm")
	}
	r.rec.Verification = "node read back with spec.unschedulable=true"
	return r.done(policy.ActionSucceeded, StatusCordoned, true, "")
}

// RecoverOrphans handles nodes left annotated draining by an earlier process (a
// crash mid-drain). Live: uncordon (only if this autoscaler cordoned it) and mark
// aborted. Simulate: report only. Other modes: nothing.
func (e *Executor) RecoverOrphans(ctx context.Context, mode gate.Mode) []policy.ActionRecord {
	if mode != gate.Live && mode != gate.Simulate {
		return nil
	}
	nodes, err := e.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: drain: list nodes for orphan recovery: %v", err)
		return nil
	}
	var out []policy.ActionRecord
	for _, n := range nodes.Items {
		if n.Annotations[AnnotationState] != StateDraining {
			continue
		}
		ours := n.Annotations[AnnotationCordonedBy] == CordonedByXamops
		rec := telemetry.NewAction(ControllerExecutor, "recover-orphan", "node/"+n.Name, mode != gate.Live)
		rec.OperationID = n.Annotations[AnnotationOperation]
		telemetry.SetDetail(&rec, "uncordon", ours && n.Spec.Unschedulable)
		if mode == gate.Simulate {
			out = append(out, telemetry.Finish(rec, policy.ActionSimulated, false,
				"orphaned drain found; live mode would mark it aborted"+map[bool]string{true: " and uncordon the node", false: ""}[ours]))
			continue
		}
		if err := e.markAborted(ctx, n.Name, ours); err != nil {
			out = append(out, telemetry.Finish(rec, policy.ActionFailed, false, fmt.Sprintf("restore orphaned node: %v", err)))
			continue
		}
		after, err := e.k8s.CoreV1().Nodes().Get(ctx, n.Name, metav1.GetOptions{})
		verified := err == nil && after.Annotations[AnnotationState] == StateAborted && (!ours || !after.Spec.Unschedulable)
		if verified {
			rec.Verification = "node read back with drain-state=aborted" + map[bool]string{true: " and schedulable", false: ""}[ours]
		}
		out = append(out, telemetry.Finish(rec, policy.ActionSucceeded, verified, "orphaned drain from an earlier process"))
	}
	return out
}

// ── API helpers ─────────────────────────────────────────────────────────────

func (e *Executor) patchNode(ctx context.Context, name string, patch map[string]interface{}) error {
	b, err := json.Marshal(patch)
	if err != nil {
		return err
	}
	_, err = e.k8s.CoreV1().Nodes().Patch(ctx, name, types.MergePatchType, b, metav1.PatchOptions{})
	return err
}

// markAborted annotates a node aborted and, when this autoscaler cordoned it,
// uncordons it and drops the cordoned-by marker.
func (e *Executor) markAborted(ctx context.Context, name string, uncordon bool) error {
	ann := map[string]interface{}{AnnotationState: StateAborted}
	patch := map[string]interface{}{"metadata": map[string]interface{}{"annotations": ann}}
	if uncordon {
		ann[AnnotationCordonedBy] = nil
		patch["spec"] = map[string]interface{}{"unschedulable": false}
	}
	return e.patchNode(ctx, name, patch)
}

// evict issues one Eviction (never a Delete). 429 (PDB) is retried up to PDBRetry.
func (e *Executor) evict(ctx context.Context, m Move) error {
	ev := &policyv1.Eviction{ObjectMeta: metav1.ObjectMeta{Name: m.Name, Namespace: m.Namespace}}
	if m.UID != "" {
		uid := types.UID(m.UID)
		ev.DeleteOptions = &metav1.DeleteOptions{Preconditions: &metav1.Preconditions{UID: &uid}}
	}
	deadline := time.Now().Add(e.PDBRetry)
	for {
		err := e.k8s.CoreV1().Pods(m.Namespace).EvictV1(ctx, ev)
		switch {
		case err == nil, apierrors.IsNotFound(err):
			return nil
		case !apierrors.IsTooManyRequests(err):
			return err
		case !time.Now().Before(deadline):
			return fmt.Errorf("disruption budget still blocks after %s: %w", e.PDBRetry, err)
		}
		if err := sleep(ctx, e.PollInterval); err != nil {
			return err
		}
	}
}

func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

func controllerUID(p corev1.Pod) string {
	for _, ref := range p.OwnerReferences {
		if ref.Controller != nil && *ref.Controller {
			return string(ref.UID)
		}
	}
	if len(p.OwnerReferences) > 0 {
		return string(p.OwnerReferences[0].UID)
	}
	return ""
}

func (e *Executor) ownerPodUIDs(ctx context.Context, ns, ownerUID string) (map[string]bool, error) {
	pods, err := e.k8s.CoreV1().Pods(ns).List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, err
	}
	known := map[string]bool{}
	for _, p := range pods.Items {
		if controllerUID(p) == ownerUID {
			known[string(p.UID)] = true
		}
	}
	return known, nil
}

func podReady(p corev1.Pod) bool {
	if p.Status.Phase != corev1.PodRunning || p.DeletionTimestamp != nil {
		return false
	}
	for _, c := range p.Status.Conditions {
		if c.Type == corev1.PodReady {
			return c.Status == corev1.ConditionTrue
		}
	}
	return false
}

// WaitForReplacement polls until a pod of the same controller (ownerUID) whose UID
// is not in knownUIDs is Running with Ready=True on a node outside excludeNodes.
// A pre-existing sibling never satisfies it — that was the false positive of the
// old waitForPodRunningElsewhere.
func WaitForReplacement(ctx context.Context, k8s kubernetes.Interface, ns, ownerUID string,
	knownUIDs, excludeNodes map[string]bool, timeout, poll time.Duration) (corev1.Pod, bool) {
	deadline := time.Now().Add(timeout)
	for {
		pods, err := k8s.CoreV1().Pods(ns).List(ctx, metav1.ListOptions{})
		if err == nil {
			for _, p := range pods.Items {
				if controllerUID(p) != ownerUID || knownUIDs[string(p.UID)] {
					continue
				}
				if p.Spec.NodeName == "" || excludeNodes[p.Spec.NodeName] || !podReady(p) {
					continue
				}
				return p, true
			}
		}
		if !time.Now().Before(deadline) {
			return corev1.Pod{}, false
		}
		if sleep(ctx, poll) != nil {
			return corev1.Pod{}, false
		}
	}
}

func (e *Executor) waitForReplacement(ctx context.Context, ns, ownerUID string, known, exclude map[string]bool, timeout time.Duration) (corev1.Pod, bool) {
	return WaitForReplacement(ctx, e.k8s, ns, ownerUID, known, exclude, timeout, e.PollInterval)
}

// excluded: DaemonSet and mirror/static pods are never moved and never block.
func excluded(p corev1.Pod) bool {
	if _, ok := p.Annotations[MirrorPodAnnotation]; ok {
		return true
	}
	for _, ref := range p.OwnerReferences {
		if ref.Kind == "DaemonSet" {
			return true
		}
	}
	return false
}

func terminal(p corev1.Pod) bool {
	return p.Status.Phase == corev1.PodSucceeded || p.Status.Phase == corev1.PodFailed
}

// podsOnNodes lists all pods and filters by node client-side (field selectors are
// not honoured by the fake clientset, and the result is identical on a real API).
func (e *Executor) podsOnNodes(ctx context.Context, nodes []string) ([]corev1.Pod, error) {
	in := map[string]bool{}
	for _, n := range nodes {
		in[n] = true
	}
	pods, err := e.k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, err
	}
	var out []corev1.Pod
	for _, p := range pods.Items {
		if in[p.Spec.NodeName] && !terminal(p) {
			out = append(out, p)
		}
	}
	return out, nil
}

func (e *Executor) verifyDrained(ctx context.Context, nodes []string, owners map[string]Move, timeout time.Duration) (string, bool) {
	deadline := time.Now().Add(timeout)
	for {
		detail := e.drainResidue(ctx, nodes, owners)
		if detail == "" {
			return "", true
		}
		if !time.Now().Before(deadline) {
			return detail, false
		}
		if err := sleep(ctx, e.PollInterval); err != nil {
			return detail + "; " + err.Error(), false
		}
	}
}

func (e *Executor) drainResidue(ctx context.Context, nodes []string, owners map[string]Move) string {
	pods, err := e.podsOnNodes(ctx, nodes)
	if err != nil {
		return fmt.Sprintf("list pods: %v", err)
	}
	var left []string
	for _, p := range pods {
		if !excluded(p) {
			left = append(left, fmt.Sprintf("%s/%s on %s", p.Namespace, p.Name, p.Spec.NodeName))
		}
	}
	if len(left) > 0 {
		sort.Strings(left)
		return "pods still on the node(s): " + strings.Join(left, ", ")
	}
	var pending []string
	for _, m := range owners {
		list, err := e.k8s.CoreV1().Pods(m.Namespace).List(ctx, metav1.ListOptions{})
		if err != nil {
			return fmt.Sprintf("list pods in %s: %v", m.Namespace, err)
		}
		for _, p := range list.Items {
			if controllerUID(p) == m.OwnerUID && p.Status.Phase == corev1.PodPending {
				pending = append(pending, p.Namespace+"/"+p.Name)
			}
		}
	}
	if len(pending) > 0 {
		sort.Strings(pending)
		return "affected owners have Pending pods: " + strings.Join(pending, ", ")
	}
	return ""
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
