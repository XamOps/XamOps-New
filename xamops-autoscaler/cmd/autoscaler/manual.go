package main

// Manual mode (Phase B), agent side. A cluster served approvalMode=manual gets its
// acting sections neutered by the backend, so every existing gate lands at Analyse or
// Off. This file adds the only two things a Manual cycle may do beyond look-only work:
//
//   - propose: turn this cycle's look-only results (idle nodes, evictor candidates,
//     the consolidation plan, rebalance candidates) into proposals, each classified by
//     the same fit simulator the executor uses. Reads and HTTP only; zero cluster writes.
//   - execute: claim at most ONE human-approved node proposal, re-validate it against
//     the live cluster, re-check the user's intent gate, then run it through the
//     existing executor entry points. Approval never opens a gate.
//
// Everything is behind ENABLE_MANUAL_APPROVAL (default false) and approvalMode=manual.

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"log/slog"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/approval"
	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/obslog"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
	"github.com/xamops/automation/internal/telemetry"
)

// manualInputs are the look-only results of one cycle that runManual builds proposals
// from and re-validates approved actions against. Nothing is recomputed.
type manualInputs struct {
	Evictor   *controller.EvictorResult       // step 2 (set even when Analyse)
	Report    *controller.NodeOptimizerReport // step 6, nil when the gate is Off or Analyse errored
	ConsInput *consolidation.Input            // step 7; a shallow copy (its slices are never mutated)
	ConsPlan  *consolidation.Plan             // step 7
}

// manualState is the per-process memory Manual mode needs across cycles: the outcome
// queue (AC-G9) and the proposal operation ids this process has observed on a node
// (AC-G10 row 2). It is process-wide because runCycle's signature is fixed.
type manualState struct {
	queue      approval.Queue
	mu         sync.Mutex
	seenOps    map[string]bool
	warnedOnce bool
}

var manualProc = newManualState()

func newManualState() *manualState { return &manualState{seenOps: map[string]bool{}} }

func (s *manualState) markSeen(op string) {
	s.mu.Lock()
	s.seenOps[op] = true
	s.mu.Unlock()
}

func (s *manualState) seen(op string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.seenOps[op]
}

// warnFlagOffOnce reports true the first time it is called.
func (s *manualState) warnFlagOffOnce() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.warnedOnce {
		return false
	}
	s.warnedOnce = true
	return true
}

// manualSleep paces proposal chunks; tests replace it with a no-op.
var manualSleep func(ctx context.Context, d time.Duration) error

// manualRT bundles the per-cycle objects Manual mode needs. flushAndReconcile needs
// only the first group (it runs before the drain machinery exists); runManual needs
// the rest too.
type manualRT struct {
	cfg    *config.Config
	mset   *metrics.Set
	k8s    kubernetes.Interface
	client *approval.Client

	planner      drain.Planner
	exec         *drain.Executor
	budget       *drain.Budget
	acts         *cycleActions
	drainReports *[]controller.DrainProgressReport
	batches      *[]pusher.Batch
	orphanRecs   int
	cycleID      string
}

func newManualRT(cfg *config.Config, mset *metrics.Set, k8s kubernetes.Interface) *manualRT {
	return &manualRT{
		cfg: cfg, mset: mset, k8s: k8s,
		client:  approval.NewClient(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID),
		cycleID: fmt.Sprintf("c-%d", time.Now().Unix()),
	}
}

// heartbeatExtras is the closure registered on the pusher. Features and the drain
// timeout are sent only by the leader (the pusher enforces that); features only while
// ENABLE_MANUAL_APPROVAL is on, so turning the flag off stops advertising manual-v1.
func heartbeatExtras(cfg *config.Config) func() pusher.HeartbeatExtras {
	return func() pusher.HeartbeatExtras {
		e := pusher.HeartbeatExtras{Leader: isLeader.Load(), DrainTimeoutSeconds: cfg.DrainTimeoutSeconds}
		if cfg.EnableManualApproval {
			e.Features = []string{"manual-v1"}
		}
		return e
	}
}

// ── recovery ────────────────────────────────────────────────────────────────

// recoveryMode is the mode orphan recovery runs at. It is d.nodeActionMode() unchanged
// unless the cluster is Manual, in which case it is the strongest of that and the
// mode the user's INTENT would allow: a node left draining by a crash (or by an
// autonomous drain interrupted by the switch) is restored even though every served
// gate is Off. It is the only write a propose-only cycle may perform, it only
// restores state this agent created, and it is independent of ENABLE_MANUAL_APPROVAL.
func recoveryMode(cfg *config.Config, b policy.PolicyBundle, d cycleDecisions) gate.Mode {
	m := d.nodeActionMode()
	if !b.IsManual() {
		return m
	}
	return strongerMode(m, decide(cfg, b.Intent()).manualRecoveryMode())
}

func strongerMode(a, b gate.Mode) gate.Mode {
	if b > a {
		return b
	}
	return a
}

// manualRecoveryMode is the strongest mode over every node-action gate, including
// the consolidation actions gate that nodeActionMode does not cover.
func (d cycleDecisions) manualRecoveryMode() gate.Mode {
	m := gate.Off
	for _, x := range []gate.Decision{d.Rebalancer, d.Evictor, d.Cordon, d.IdleDrain, d.SpotRatio, d.Interruption, d.ConsolidationActions} {
		if x.Mode == gate.Live {
			return gate.Live
		}
		if x.Mode == gate.Simulate {
			m = gate.Simulate
		}
	}
	return m
}

// ── flush + reconcile (runs before the "disabled" early return) ─────────────

// flushAndReconcile (i) retries queued outcomes and (ii) resolves DISPATCHED rows this
// process cannot account for (a restart, a lost claim response). It does HTTP and node
// reads only, so it is safe while the cluster is paused, and it is independent of
// approvalMode and autoscalerMode.
func flushAndReconcile(ctx context.Context, rt *manualRT) {
	manualProc.queue.Flush(ctx, rt.client)
	defer func() { rt.mset.SetProposalOutcomeQueue(manualProc.queue.Len()) }()

	items, err := rt.client.Dispatched(ctx)
	if err != nil {
		if approval.StatusOf(err) != 404 { // an older backend has no endpoint: stay quiet
			log.Printf("WARN: manual: list dispatched proposals: %v", err)
		}
		return
	}
	for _, it := range items {
		if manualProc.queue.Has(it.ID) {
			continue // an outcome for it is already queued
		}
		var node *corev1.Node
		n, err := rt.k8s.CoreV1().Nodes().Get(ctx, it.TargetRef, metav1.GetOptions{})
		switch {
		case err == nil:
			node = n
			if n.Annotations[drain.AnnotationOperation] == it.OperationID {
				manualProc.markSeen(it.OperationID)
			}
		case apierrors.IsNotFound(err):
			node = nil
		default:
			log.Printf("WARN: manual: reconcile %s: get node %s: %v — retried next cycle", it.ID, it.TargetRef, err)
			continue
		}
		rec := approval.Classify(it, node, manualProc.seen(it.OperationID))
		out := approval.NewOutcome(it.ID, rec.Status, approval.StageExecution, rec.Reason)
		out.Verified, out.DryRun = rec.Verified, it.DryRun
		obslog.Cycle("proposal-reconciled", slog.String("proposalId", it.ID), slog.String("status", rec.Status), slog.String("reason", rec.Reason))
		log.Printf("manual: reconciled DISPATCHED proposal %s on node %s: %s (%s)", it.ID, it.TargetRef, rec.Status, rec.Reason)
		if rec.Terminal {
			manualProc.queue.Deliver(ctx, rt.client, out)
		} else if err := rt.client.ReportOutcome(ctx, out); err != nil { // non-terminal: best effort
			log.Printf("WARN: manual: running ping for %s: %v", it.ID, err)
		}
	}
}

// ── runManual ───────────────────────────────────────────────────────────────

// runManual executes at most one approved action, then (unless a live action just ran
// and made this cycle's look-only results stale) proposes.
func runManual(ctx context.Context, rt *manualRT, bundle policy.PolicyBundle, mi manualInputs) {
	intent := bundle.Intent()
	di := decide(rt.cfg, intent)
	for name, dec := range di.byController() {
		rt.mset.SetGateDecision(name+"-intent", dec.Mode.String())
	}
	nodeList, err := rt.k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: manual: list nodes: %v — no proposals or execution this cycle", err)
		return
	}
	nodes := nodeList.Items

	if rt.executeApproved(ctx, bundle, intent, di, mi, nodes) {
		log.Println("manual: a live approved action ran this cycle — proposals resume next cycle")
		return
	}
	rt.propose(ctx, bundle, intent, di, mi, nodes)
}

// descriptor is one place a proposal can come from, before any planning.
type descriptor struct {
	ActionType string
	Operation  string
	Origin     string
	Node       string
	Gate       gate.Decision
	Trigger    string
	MoveType   string
	NodeGroup  string
	ProviderID string
	Extra      map[string]interface{}
}

func (d descriptor) key() string {
	return approval.DedupeKey(d.ActionType, d.Operation, d.Origin, d.Node)
}

// manualGate recomputes the intent gate for one action. For a REBALANCE executed
// after its request was consumed the intent status is substituted with "running".
func manualGate(cfg *config.Config, intent policy.PolicyBundle, di cycleDecisions, actionType, operation, origin string, consumed bool) (gate.Decision, bool) {
	switch actionType {
	case approval.TypeNodeDrain:
		switch {
		case origin == approval.OriginNodeOptimizer && operation == approval.OpDrain:
			return di.IdleDrain, true
		case origin == approval.OriginNodeOptimizer && operation == approval.OpCordonOnly:
			return di.Cordon, true
		case origin == approval.OriginEvictor && operation == approval.OpDrain:
			return di.Evictor, true
		}
	case approval.TypeNodeConsolidate:
		if origin == approval.OriginConsolidation && (operation == approval.OpDrain || operation == approval.OpEmpty) {
			return di.ConsolidationActions, true
		}
	case approval.TypeRebalance:
		if origin == approval.OriginRebalancer && operation == approval.OpDrain {
			b := intent
			if consumed {
				b.Rebalancer.Status = "running"
			}
			return gate.Rebalancer(cfg, b), true
		}
	}
	return gate.Decision{Mode: gate.Off, Reason: "unknown action"}, false
}

func actsOnCluster(m gate.Mode) bool { return m == gate.Simulate || m == gate.Live }

// enumerate lists every candidate this cycle's inputs produce, regardless of gate
// (each descriptor carries its intent gate). Proposing filters on the gate;
// re-validation only needs to know the candidate still exists.
func (rt *manualRT) enumerate(intent policy.PolicyBundle, di cycleDecisions, mi manualInputs, nodes []corev1.Node) []descriptor {
	var out []descriptor
	if mi.Report != nil {
		for _, n := range mi.Report.IdleNodes {
			if !n.RecommendDrain {
				continue
			}
			extra := map[string]interface{}{"cpuUsagePct": n.CPUUsagePct, "memUsagePct": n.MemUsagePct, "podsToMove": n.PodCount}
			out = append(out,
				descriptor{ActionType: approval.TypeNodeDrain, Operation: approval.OpDrain, Origin: approval.OriginNodeOptimizer,
					Node: n.NodeName, Gate: di.IdleDrain, Trigger: "idle-drain", Extra: extra},
				descriptor{ActionType: approval.TypeNodeDrain, Operation: approval.OpCordonOnly, Origin: approval.OriginNodeOptimizer,
					Node: n.NodeName, Gate: di.Cordon, Trigger: "idle-cordon", Extra: extra})
		}
	}
	if mi.Evictor != nil {
		for _, c := range mi.Evictor.CandidateNodes {
			if !c.CanEvict {
				continue
			}
			out = append(out, descriptor{ActionType: approval.TypeNodeDrain, Operation: approval.OpDrain, Origin: approval.OriginEvictor,
				Node: c.NodeName, Gate: di.Evictor, Trigger: "evictor",
				Extra: map[string]interface{}{"cpuRequestPct": c.UtilizationCPU, "memRequestPct": c.UtilizationMem, "podCount": c.PodCount}})
		}
	}
	if mi.ConsPlan != nil {
		for _, m := range mi.ConsPlan.Moves {
			trigger := "consolidation-drain"
			if m.Type == consolidation.MoveEmpty {
				trigger = "consolidation-empty"
			}
			out = append(out, descriptor{ActionType: approval.TypeNodeConsolidate, Operation: string(m.Type), Origin: approval.OriginConsolidation,
				Node: m.Node.Name, Gate: di.ConsolidationActions, Trigger: trigger, MoveType: string(m.Type),
				NodeGroup: m.Node.NodeGroup, ProviderID: m.Node.ProviderID})
		}
	}
	for _, n := range controller.RebalanceCandidates(nodes) {
		out = append(out, descriptor{ActionType: approval.TypeRebalance, Operation: approval.OpDrain, Origin: approval.OriginRebalancer,
			Node: n.Name, Gate: di.Rebalancer, Trigger: "rebalance"})
	}
	return out
}

// classified is the agent's verdict on one candidate.
type classified struct {
	Item        approval.Item
	Verdict     string
	Codes       []string
	Reason      string
	Fingerprint string
}

type classifyEnv struct {
	guards  policy.SafetyGuards
	actor   string
	dwell   int
	selfNod string
}

// classify runs the executor's own classifier output (the fit plan) through the
// refusal rules and builds the proposal item. Unknown refuses: a planner error, an
// absent simulator plan or a move with no controller owner is a REFUSED verdict.
func (rt *manualRT) classify(ctx context.Context, d descriptor, node corev1.Node, plan drain.Plan, planErr error, env classifyEnv) classified {
	var refusedCodes, blockedCodes, codes []string
	drainable := false
	strands := false
	var fit interface{}

	switch {
	case planErr != nil:
		codes = append(codes, "planner-error")
		fit = map[string]interface{}{"drainable": false, "errors": []string{planErr.Error()}}
	default:
		fp, ok := plan.Detail.(fitsim.Plan)
		if !ok {
			codes = append(codes, "plan-unavailable")
			fit = map[string]interface{}{"drainable": false, "reasons": plan.Reasons}
			break
		}
		drainable = fp.Drainable
		for _, f := range fp.Refused {
			refusedCodes = append(refusedCodes, approval.ReasonCodes(f.Reason)...)
		}
		for _, f := range fp.Blocked {
			blockedCodes = append(blockedCodes, approval.ReasonCodes(f.Reason)...)
		}
		strands = len(fp.Strands) > 0
		codes = append(codes, refusedCodes...)
		codes = append(codes, blockedCodes...)
		if strands {
			codes = append(codes, "strand")
		}
		if len(fp.Errors) > 0 {
			codes = append(codes, "plan-error")
		}
		if !fp.Drainable && len(codes) == 0 {
			codes = append(codes, "not-drainable")
		}
		for _, m := range plan.Moves {
			if m.OwnerUID == "" {
				codes = append(codes, "no-controller-owner")
				break
			}
		}
		fit = fp
	}
	if env.selfNod != "" && d.Node == env.selfNod {
		codes = append(codes, "self-node")
	}
	// The rebalancer's benefit guard sits above DrainCandidates; mirror it per node.
	if d.ActionType == approval.TypeRebalance && len(codes) == 0 && drainable {
		sel, _, err := rt.exec.SelectSet(ctx, []string{d.Node}, 1)
		switch {
		case err != nil:
			codes = append(codes, "benefit-check-error")
		case len(sel) == 0:
			codes = append(codes, "no-net-reduction")
		}
	}
	codes = approval.SortedUnique(codes)
	verdict := approval.VerdictAllowed
	if len(codes) > 0 {
		verdict = approval.VerdictRefused
	}

	nodeGroup := nodeGroupOf(node.Labels)
	providerID := node.Spec.ProviderID
	if d.ActionType == approval.TypeNodeConsolidate {
		nodeGroup, providerID = d.NodeGroup, d.ProviderID
	}
	fpIn := approval.NodeFingerprintInput{
		ActionType: d.ActionType, Operation: d.Operation, Origin: d.Origin, Node: d.Node, UID: string(node.UID),
		Verdict: verdict, RefusalCodes: codes, Drainable: drainable, RefusedCodes: refusedCodes,
		BlockedCodes: blockedCodes, Strands: strands,
		MoveType: d.MoveType, NodeGroup: nodeGroup, ProviderID: providerID,
	}
	fingerprint := approval.NodeFingerprint(fpIn)

	params := map[string]interface{}{"trigger": d.Trigger, "unschedulableAtProposal": node.Spec.Unschedulable}
	for k, v := range d.Extra {
		params[k] = v
	}
	if d.MoveType != "" {
		params["moveType"] = d.MoveType
	}
	item := approval.Item{
		DedupeKey: d.key(), ActionType: d.ActionType, Operation: d.Operation, OriginController: d.Origin,
		Target:  approval.Target{Kind: "NODE", Name: d.Node, UID: string(node.UID), ProviderID: providerID, NodeGroup: nodeGroup},
		Params:  mustJSON(params),
		Verdict: verdict,
		Refusal: approval.Refusal{Codes: codes, Reason: approval.JoinCodes(codes)},
		Gate: approval.Gate{Mode: d.Gate.Mode.String(), Reason: d.Gate.Reason,
			DryRun: d.Gate.Mode != gate.Live},
		PlanFingerprint: fingerprint,
		Evidence:        evidenceJSON(fit, env),
	}
	if d.ActionType == approval.TypeNodeConsolidate {
		dw := env.dwell
		item.DwellSeconds = &dw
	}
	return classified{Item: item, Verdict: verdict, Codes: codes, Reason: approval.JoinCodes(codes), Fingerprint: fingerprint}
}

// maxEvidenceBytes keeps evidence under the backend's 256 KB limit with headroom.
const maxEvidenceBytes = 200 << 10

func evidenceJSON(fit interface{}, env classifyEnv) []byte {
	build := func(f interface{}) []byte {
		return mustJSON(map[string]interface{}{
			"fit": f,
			"guards": map[string]interface{}{
				"respectPodDisruptionBudgets": env.guards.RespectPodDisruptionBudgets,
				"protectSingleReplicas":       env.guards.ProtectSingleReplicas,
				"protectStatefulSets":         env.guards.ProtectStatefulSets,
				"protectCriticalNamespaces":   env.guards.ProtectCriticalNamespaces,
			},
			"stateful": []interface{}{},
			"actor":    env.actor,
		})
	}
	b := build(fit)
	if len(b) > maxEvidenceBytes {
		if fp, ok := fit.(fitsim.Plan); ok {
			fp.Moves = nil // the move list is the bulk; the verdict and findings stay
			fp.Summary += " (move list omitted: evidence too large)"
			b = build(fp)
		}
	}
	return b
}

func mustJSON(v interface{}) []byte {
	b, err := json.Marshal(v)
	if err != nil { // only maps of plain values are passed in
		panic("manual: marshal: " + err.Error())
	}
	return b
}

func nodeGroupOf(labels map[string]string) string {
	for _, k := range []string{consolidation.LabelNodeGroup, consolidation.LabelKarpenterNodePool, consolidation.LabelKarpenterProv} {
		if v := labels[k]; v != "" {
			return v
		}
	}
	return ""
}

func envFor(rt *manualRT, bundle, intent policy.PolicyBundle, mi manualInputs) classifyEnv {
	env := classifyEnv{guards: bundle.SafetyGuards, dwell: clampDwell(intent.ConsolidationPolicy.ConsolidateAfterSeconds)}
	if mi.ConsPlan != nil {
		env.actor = mi.ConsPlan.Actor.Kind
	}
	if rt.exec != nil {
		env.selfNod = rt.exec.SelfNode()
	}
	return env
}

func clampDwell(s int) int {
	switch {
	case s < 0:
		return 0
	case s > 86400:
		return 86400
	}
	return s
}

// ── propose ─────────────────────────────────────────────────────────────────

func (rt *manualRT) propose(ctx context.Context, bundle, intent policy.PolicyBundle, di cycleDecisions, mi manualInputs, nodes []corev1.Node) {
	byName := map[string]corev1.Node{}
	for _, n := range nodes {
		byName[n.Name] = n
	}
	var wanted []descriptor
	rebalanceCandidates := 0
	for _, d := range rt.enumerate(intent, di, mi, nodes) {
		if d.ActionType == approval.TypeRebalance {
			rebalanceCandidates++
		}
		if !actsOnCluster(d.Gate.Mode) {
			continue
		}
		if _, ok := byName[d.Node]; !ok {
			continue
		}
		wanted = append(wanted, d)
	}
	rebalanceRunning := actsOnCluster(di.Rebalancer.Mode) // requires intent status=running

	// One consistent snapshot classifies every candidate node (the same simulator and
	// the same single-node plan the executor re-runs at execution time).
	plans, planAllErr := rt.planAll(ctx, wanted)
	env := envFor(rt, bundle, intent, mi)
	var items []approval.Item
	for _, d := range wanted {
		plan, ok := plans[d.Node]
		var perr error
		if !ok {
			if perr = planAllErr; perr == nil {
				perr = fmt.Errorf("no plan was produced for node %s", d.Node)
			}
		}
		c := rt.classify(ctx, d, byName[d.Node], plan, perr, env)
		rt.mset.IncProposal(d.ActionType, c.Verdict)
		items = append(items, c.Item)
	}

	var rb *approval.RebalanceRequest
	if rebalanceRunning {
		rb = &approval.RebalanceRequest{Candidates: rebalanceCandidates}
	}
	if len(items) == 0 && rb == nil {
		return
	}
	s := &approval.Sender{
		Client: rt.client, Sleep: manualSleep,
		OnRejected: rt.mset.IncProposalPostRejected,
		OnDropped:  rt.mset.AddProposalItemsDropped,
	}
	res := s.Send(ctx, rt.cycleID, time.Now().UTC().Format(time.RFC3339), items, rb)
	obslog.Cycle("proposal-submitted", slog.Int("items", len(items)), slog.Int("chunks", res.Chunks),
		slog.Int("dropped", res.Dropped), slog.Bool("rateLimited", res.RateLimited))
	log.Printf("manual: proposed %d item(s) in %d chunk(s), %d dropped", len(items), res.Chunks, res.Dropped)
}

// planAll plans every distinct candidate node over one snapshot when the planner
// supports it, else node by node.
func (rt *manualRT) planAll(ctx context.Context, wanted []descriptor) (map[string]drain.Plan, error) {
	seen := map[string]bool{}
	var names []string
	for _, d := range wanted {
		if !seen[d.Node] {
			seen[d.Node] = true
			names = append(names, d.Node)
		}
	}
	if len(names) == 0 {
		return nil, nil
	}
	if ep, ok := rt.planner.(drain.EachPlanner); ok {
		return ep.PlanEach(ctx, names)
	}
	out := map[string]drain.Plan{}
	for _, n := range names {
		p, err := rt.planner.Plan(ctx, []string{n})
		if err != nil {
			return out, err
		}
		out[n] = p
	}
	return out, nil
}

// ── execute ─────────────────────────────────────────────────────────────────

// report delivers an outcome (queued on failure) and counts it.
func (rt *manualRT) report(ctx context.Context, a *approval.ApprovedAction, o approval.Outcome) {
	obslog.Cycle("proposal-outcome", slog.String("proposalId", a.ProposalID), slog.String("status", o.Status), slog.String("reason", o.Reason))
	log.Printf("manual: proposal %s (%s %s on %s) outcome %s: %s", a.ProposalID, a.ActionType, a.Operation, a.Target.Name, o.Status, o.Reason)
	rt.mset.IncApprovedExecution(a.ActionType, o.Status)
	manualProc.queue.Deliver(ctx, rt.client, o)
	rt.mset.SetProposalOutcomeQueue(manualProc.queue.Len())
}

// executeApproved claims and runs at most one approved node action. It returns true
// when a live action reached the executor (this cycle's look-only inputs are then
// stale, so the caller skips proposing).
func (rt *manualRT) executeApproved(ctx context.Context, bundle, intent policy.PolicyBundle, di cycleDecisions, mi manualInputs, nodes []corev1.Node) bool {
	// 1. Pre-claim checks (no network). The whole loop only runs while leading.
	if inflight := drain.InflightNodes(nodes); len(inflight) > 0 {
		log.Printf("manual: not claiming — drain in flight on %v", inflight)
		return false
	}
	if rt.budget == nil || rt.budget.Remaining() <= 0 {
		log.Println("manual: not claiming — drain budget exhausted")
		return false
	}
	if rt.orphanRecs > 0 {
		log.Printf("manual: not claiming — orphan recovery returned %d record(s) this cycle", rt.orphanRecs)
		return false
	}
	// 2. Claim.
	a, err := rt.client.Claim(ctx, rt.cycleID)
	if err != nil {
		log.Printf("WARN: manual: claim: %v", err)
		return false
	}
	if a == nil {
		return false
	}
	obslog.Cycle("proposal-claimed", slog.String("proposalId", a.ProposalID), slog.String("type", a.ActionType), slog.String("node", a.Target.Name))
	log.Printf("manual: claimed proposal %s (%s %s %s on %s)", a.ProposalID, a.ActionType, a.Operation, a.OriginController, a.Target.Name)
	opID := approval.OperationID(a.ProposalID)

	// 3. Gate: approval never opens a gate.
	g, known := manualGate(rt.cfg, intent, di, a.ActionType, a.Operation, a.OriginController, true)
	if !known {
		rt.report(ctx, a, approval.Refused(a.ProposalID, "unknown action "+a.ActionType+"/"+a.Operation+"/"+a.OriginController))
		return false
	}
	if !actsOnCluster(g.Mode) {
		rt.report(ctx, a, approval.Refused(a.ProposalID, "gate closed: "+g.Reason))
		return false
	}

	// 4. Re-validate against the live cluster and this cycle's inputs.
	var node *corev1.Node
	if n, err := rt.k8s.CoreV1().Nodes().Get(ctx, a.Target.Name, metav1.GetOptions{}); err == nil {
		node = n
	} else if !apierrors.IsNotFound(err) {
		rt.report(ctx, a, approval.Failed(a.ProposalID, fmt.Sprintf("get node %s: %v", a.Target.Name, err)))
		return false
	}
	var fresh *approval.Fresh
	if node != nil {
		env := envFor(rt, bundle, intent, mi)
		var match *descriptor
		for _, d := range rt.enumerate(intent, di, mi, nodes) {
			if d.key() == a.DedupeKey && d.Node == a.Target.Name {
				d := d
				match = &d
				break
			}
		}
		produced := match != nil
		if match == nil {
			// No longer produced this cycle. Classify it anyway: a plan that has since
			// become refused is reported refused (with its codes), not merely stale.
			p := a.ParsedParams()
			match = &descriptor{ActionType: a.ActionType, Operation: a.Operation, Origin: a.OriginController,
				Node: a.Target.Name, Trigger: p.Trigger, MoveType: p.MoveType, NodeGroup: p.NodeGroup, ProviderID: p.ProviderID}
		}
		match.Gate = g
		plan, perr := rt.planner.Plan(ctx, []string{match.Node})
		c := rt.classify(ctx, *match, *node, plan, perr, env)
		if produced || c.Verdict == approval.VerdictRefused {
			fresh = &approval.Fresh{Verdict: c.Verdict, RefusalCodes: c.Codes, Reason: c.Reason, Fingerprint: c.Fingerprint}
		}
	}
	rv := approval.Revalidate(*a, node, fresh, time.Now())
	rt.mset.IncProposalRevalidation(rv.Result)
	switch rv.Result {
	case approval.RevalStale:
		rt.report(ctx, a, approval.Stale(a.ProposalID, rv.Reason))
		return false
	case approval.RevalRefused:
		rt.report(ctx, a, approval.Refused(a.ProposalID, rv.Reason))
		return false
	}

	// 5. Execute exactly one action through the existing entry points.
	timeout := time.Duration(rt.cfg.DrainTimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 20 * time.Minute
	}
	dctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if g.Mode == gate.Live {
		manualProc.markSeen(opID) // this process is about to write the annotation
	}

	var out approval.Outcome
	switch a.ActionType {
	case approval.TypeNodeConsolidate:
		out = rt.executeConsolidation(dctx, a, bundle, intent, g.Mode, mi)
	default:
		out = rt.executeDrain(dctx, a, g.Mode)
	}
	rt.report(ctx, a, out)
	return g.Mode == gate.Live
}

// markApproved stamps the paired records so the activity feed shows where they came from.
func markApproved(recs []policy.ActionRecord, proposalID string) {
	for i := range recs {
		telemetry.SetDetail(&recs[i], "trigger", "approved-proposal")
		telemetry.SetDetail(&recs[i], "proposalId", proposalID)
	}
}

func (rt *manualRT) executeDrain(ctx context.Context, a *approval.ApprovedAction, mode gate.Mode) approval.Outcome {
	opID := approval.OperationID(a.ProposalID)
	node := []string{a.Target.Name}
	var o drain.Outcome
	eventType := "scale-in"
	switch {
	case a.ActionType == approval.TypeNodeDrain && a.Operation == approval.OpCordonOnly:
		o = rt.exec.CordonOnly(ctx, policy.ControllerNodeOptimizer, opID, a.Target.Name, mode, rt.budget)
	case a.ActionType == approval.TypeNodeDrain:
		controllerName := policy.ControllerNodeOptimizer
		if a.OriginController == approval.OriginEvictor {
			controllerName = policy.ControllerEvictor
		}
		o = rt.exec.Drain(ctx, controllerName, opID, node, mode, rt.budget)
	case a.ActionType == approval.TypeRebalance:
		eventType = "rebalance"
		// The benefit guard the autonomous Rebalancer.Run applies above DrainCandidates,
		// scoped to the one approved node. The ledger is not used: the proposal id is
		// the idempotency key.
		sel, _, err := rt.exec.SelectSet(ctx, node, 1)
		if err != nil || len(sel) == 0 {
			reason := "no net node reduction achievable — rebalance would drain without benefit"
			if err != nil {
				reason = fmt.Sprintf("benefit check failed: %v", err)
			}
			return approval.Refused(a.ProposalID, reason)
		}
		o = rt.exec.Drain(ctx, policy.ControllerRebalancer, opID, node, mode, rt.budget)
	default:
		return approval.Refused(a.ProposalID, "unsupported action "+a.ActionType)
	}
	markApproved(o.Actions, a.ProposalID)
	rt.acts.add(eventType, 1, o.Actions...)
	*rt.drainReports = append(*rt.drainReports, o.Reports...)
	return approval.FromDrain(a.ProposalID, o, mode)
}

func (rt *manualRT) executeConsolidation(ctx context.Context, a *approval.ApprovedAction, bundle, intent policy.PolicyBundle, mode gate.Mode, mi manualInputs) approval.Outcome {
	if mi.ConsInput == nil || mi.ConsPlan == nil {
		return approval.Stale(a.ProposalID, "no consolidation plan this cycle")
	}
	var move *consolidation.Move
	for i := range mi.ConsPlan.Moves {
		if mi.ConsPlan.Moves[i].Node.Name == a.Target.Name {
			move = &mi.ConsPlan.Moves[i]
			break
		}
	}
	if move == nil {
		return approval.Stale(a.ProposalID, "node is not a consolidation move this cycle")
	}
	// A shallow copy of the cycle's input whose node slice is a copy and whose target
	// node carries the candidate-since mark, in memory only: the executor's dwell read
	// sees the proposal's firstSeenAt without anything being written to the cluster.
	in2 := *mi.ConsInput
	in2.Nodes = make([]corev1.Node, len(mi.ConsInput.Nodes))
	copy(in2.Nodes, mi.ConsInput.Nodes)
	for i := range in2.Nodes {
		if in2.Nodes[i].Name != a.Target.Name {
			continue
		}
		ann := map[string]string{}
		for k, v := range in2.Nodes[i].Annotations {
			ann[k] = v
		}
		ann[controller.AnnotationConsolidationCandidateSince] = a.FirstSeenAt.UTC().Format(time.RFC3339)
		in2.Nodes[i].Annotations = ann
	}
	planOne := *mi.ConsPlan
	planOne.Moves = []consolidation.Move{*move}

	copts := fitsim.Options{Guards: bundle.SafetyGuards, SelfNamespace: rt.cfg.PodNamespace}
	provider := consolidationProvider(ctx, rt.cfg, mode)
	cons := controller.NewConsolidator(provider, rt.exec, rt.mset)
	res, err := cons.ExecuteWithRunID(ctx, in2, planOne, mode, copts, intent.ConsolidationPolicy, rt.budget, time.Now(),
		approval.OperationID(a.ProposalID))
	if err != nil {
		return approval.Failed(a.ProposalID, fmt.Sprintf("consolidation controller: %v", err))
	}
	markApproved(res.Actions, a.ProposalID)
	rt.acts.add("node-replace", 1, res.Actions...)
	if res.Run != nil {
		*rt.batches = append(*rt.batches, pusher.Batch{
			Collector:   "autoscaling-consolidation-run",
			Items:       []interface{}{res.Run},
			Count:       1,
			CollectedAt: time.Now(),
		})
	}
	return approval.FromConsolidation(a.ProposalID, res.Run, res.Actions, mode)
}
