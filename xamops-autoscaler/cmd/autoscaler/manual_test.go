package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/client-go/tools/leaderelection"

	"github.com/xamops/automation/internal/approval"
	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
)

const (
	keyEvictorN3   = "NODE_DRAIN:DRAIN:evictor:node-3"
	keyIdleDrainN3 = "NODE_DRAIN:DRAIN:node-optimizer:node-3"
	keyCordonN3    = "NODE_DRAIN:CORDON_ONLY:node-optimizer:node-3"
	keyConsolN3    = "NODE_CONSOLIDATE:EMPTY:consolidation:node-3"
	keyRebalN3     = "REBALANCE:DRAIN:rebalancer:node-3"
)

// consolidationCluster is seededCluster with node groups, so the consolidation plan
// has an EMPTY move (node-3: DaemonSet pods only).
func consolidationCluster() *fake.Clientset {
	web := map[string]string{"app": "web"}
	ng := func(name string) *corev1.Node {
		n := cycleNode(name, map[string]string{consolidation.LabelNodeGroup: "ng-1"})
		n.Spec.ProviderID = "aws:///us-east-1a/i-0" + name
		n.UID = types.UID("uid-" + name[len(name)-1:])
		return n
	}
	var objs []runtime.Object
	for _, n := range []string{"node-1", "node-2", "node-3"} {
		objs = append(objs, ng(n), cyclePod("kube-system", "aws-node-"+n, n, "DaemonSet", "aws-node", nil))
	}
	objs = append(objs,
		cyclePod("default", "web-1", "node-1", "ReplicaSet", "web-rs", web),
		cyclePod("default", "web-2", "node-2", "ReplicaSet", "web-rs", web))
	return fake.NewSimpleClientset(objs...)
}

func nodeAnn(t *testing.T, c *fake.Clientset, name string) *corev1.Node {
	t.Helper()
	n, err := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func setUID(c *fake.Clientset, name, uid string) {
	n, _ := c.CoreV1().Nodes().Get(context.Background(), name, metav1.GetOptions{})
	n.UID = types.UID("u-" + name + uid)
	_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
}

// proposeCycle runs one propose cycle (live gates, so the items carry gate=live) and
// returns the items and the number of API calls the cycle made against the cluster.
func proposeCycle(t *testing.T, fb *fakeBackend, c *fake.Clientset) map[string]approval.Item {
	t.Helper()
	runManualCycle(t, manualCfg(false), c, fb)
	items, _ := fb.allItems()
	out := map[string]approval.Item{}
	for _, it := range items {
		out[it.DedupeKey] = it
	}
	fb.resetBatches()
	c.ClearActions()
	return out
}

// AC-G1: a propose-only cycle (live intent, Manual served) issues zero cluster writes,
// zero ConfigMap writes and never builds a cloud provider; the proposals strictly
// satisfy the wire contract and carry the right shape.
func TestManualProposeOnlyCycleWritesNothing(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	mset := runManualCycle(t, manualCfg(false), c, fb)

	if w := writeVerbs(c); len(w) != 0 {
		t.Fatalf("propose-only cycle issued writes: %v", w)
	}
	if acts := fb.cap.items("autoscaling-actions"); len(acts) != 0 {
		t.Errorf("a propose-only cycle recorded actions: %s", acts)
	}
	items, last := fb.allItems()
	byKey := map[string]approval.Item{}
	for _, it := range items {
		byKey[it.DedupeKey] = it
	}
	for _, k := range []string{keyEvictorN3, keyIdleDrainN3, keyCordonN3, keyConsolN3, keyRebalN3} {
		it, ok := byKey[k]
		if !ok {
			t.Errorf("missing proposal %s (have %v)", k, keysOf(byKey))
			continue
		}
		if it.Verdict != approval.VerdictAllowed || it.Gate.Mode != "live" || it.Gate.DryRun {
			t.Errorf("%s = verdict %s gate %+v", k, it.Verdict, it.Gate)
		}
		if it.Target.Kind != "NODE" || it.Target.UID != "uid-3" || it.Target.Name != "node-3" {
			t.Errorf("%s target = %+v", k, it.Target)
		}
		if !strings.HasPrefix(it.PlanFingerprint, "sha256:") || len(it.PlanFingerprint) != 71 {
			t.Errorf("%s fingerprint = %q", k, it.PlanFingerprint)
		}
		if approval.DedupeKey(it.ActionType, it.Operation, it.OriginController, it.Target.Name) != k {
			t.Errorf("%s: dedupe key is not DedupeKey()", k)
		}
		var ev struct {
			Fit    map[string]interface{} `json:"fit"`
			Guards map[string]interface{} `json:"guards"`
		}
		if json.Unmarshal(it.Evidence, &ev) != nil || ev.Fit["drainable"] != true || ev.Guards["protectStatefulSets"] != true {
			t.Errorf("%s evidence = %s", k, it.Evidence)
		}
	}
	// Consolidation carries the intent's dwell; nothing else does.
	if d := byKey[keyConsolN3].DwellSeconds; d == nil || *d != 600 {
		t.Errorf("consolidate dwellSeconds = %v, want 600", d)
	}
	if byKey[keyEvictorN3].DwellSeconds != nil {
		t.Error("drain proposals must carry dwellSeconds=null")
	}
	// The unschedulable-at-proposal fact the revalidation compares against.
	var params struct {
		Unsched bool   `json:"unschedulableAtProposal"`
		Trigger string `json:"trigger"`
	}
	_ = json.Unmarshal(byKey[keyEvictorN3].Params, &params)
	if params.Unsched || params.Trigger != "evictor" {
		t.Errorf("evictor params = %s", byKey[keyEvictorN3].Params)
	}
	// Rebalance: nodes with unknown replicas are proposed REFUSED; the request is
	// consumed (complete) because every candidate was enumerated and delivered.
	if byKey["REBALANCE:DRAIN:rebalancer:node-1"].Verdict != approval.VerdictRefused ||
		len(byKey["REBALANCE:DRAIN:rebalancer:node-1"].Refusal.Codes) == 0 {
		t.Errorf("node-1 rebalance = %+v", byKey["REBALANCE:DRAIN:rebalancer:node-1"])
	}
	if last.Rebalance == nil || !last.Rebalance.Complete || last.Rebalance.CandidateCount != 3 {
		t.Errorf("rebalance = %+v, want complete with 3 candidates", last.Rebalance)
	}
	out := scrapeMetrics(t, mset)
	for _, want := range []string{`xamops_proposals_total{type="NODE_DRAIN",verdict="ALLOWED"}`, `xamops_proposals_total{type="REBALANCE",verdict="REFUSED"}`} {
		if !strings.Contains(out, want) {
			t.Errorf("missing metric %s", want)
		}
	}
}

func keysOf(m map[string]approval.Item) []string {
	var k []string
	for key := range m {
		k = append(k, key)
	}
	return k
}

// AC-G7: with ENABLE_MANUAL_APPROVAL=false a Manual cluster gets no proposals and no
// /proposals request at all, and the (neutered) cycle acts on nothing.
func TestManualFlagOffProposesNothing(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	cfg := manualCfg(false)
	cfg.EnableManualApproval = false
	runManualCycle(t, cfg, c, fb)
	if n := fb.proposalRequests(); n != 0 {
		t.Errorf("%d request(s) to /proposals with the flag off", n)
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
	if acts := fb.cap.items("autoscaling-actions"); len(acts) != 0 {
		t.Errorf("actions with the flag off: %s", acts)
	}
}

// orphanCluster has node-3 left annotated draining by a crashed earlier process.
func orphanCluster() *fake.Clientset {
	c := consolidationCluster()
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "node-3", metav1.GetOptions{})
	n.Spec.Unschedulable = true
	n.Annotations = map[string]string{
		drain.AnnotationState: drain.StateDraining, drain.AnnotationOperation: "evictor-1",
		drain.AnnotationCordonedBy: drain.CordonedByXamops,
	}
	_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
	c.ClearActions()
	return c
}

// AC-G1b + AC-G7: a node left draining by a crash is restored by the next Manual cycle
// at the INTENT's mode even though every served gate is Off, and even with
// ENABLE_MANUAL_APPROVAL=false. The cycle that recovered does not claim.
func TestManualOrphanRecoveryUsesIntentGates(t *testing.T) {
	for _, tc := range []struct {
		name        string
		cfg         func() *config.Config
		wantPatches int
		wantState   string
	}{
		{"intent Live restores", func() *config.Config { return manualCfg(false) }, 1, drain.StateAborted},
		{"intent Live restores with the manual flag off", func() *config.Config {
			c := manualCfg(false)
			c.EnableManualApproval = false
			return c
		}, 1, drain.StateAborted},
		{"intent Off (env flags off) restores nothing", func() *config.Config { return &config.Config{EnableManualApproval: true} }, 0, drain.StateDraining},
		{"DRY_RUN only reports", func() *config.Config { return manualCfg(true) }, 0, drain.StateDraining},
	} {
		t.Run(tc.name, func(t *testing.T) {
			useCleanManualState(t)
			fb := newFakeBackend(t, servedManual("full", nil))
			fb.queueClaim("{}") // must never be claimed in this cycle
			c := orphanCluster()
			runManualCycle(t, tc.cfg(), c, fb)
			patched, ev := nodeWrites(c)
			if len(patched) != tc.wantPatches || ev != 0 {
				t.Fatalf("patched=%v evictions=%d, want %d patch(es)", patched, ev, tc.wantPatches)
			}
			if w := writeVerbs(c); len(w) != tc.wantPatches {
				t.Errorf("writes beyond the recovery patch: %v", w)
			}
			n := nodeAnn(t, c, "node-3")
			if n.Annotations[drain.AnnotationState] != tc.wantState {
				t.Errorf("drain-state = %q, want %q", n.Annotations[drain.AnnotationState], tc.wantState)
			}
			if tc.wantPatches == 1 && n.Spec.Unschedulable {
				t.Error("recovered node is still cordoned")
			}
			if fb.claimCount() != 0 {
				t.Errorf("a cycle with an in-flight or just-recovered drain claimed (%d)", fb.claimCount())
			}
		})
	}
}

// The cycle AFTER recovery has an empty in-flight list, so it may claim (AC-G1b/G6).
func TestManualClaimsOnTheCycleAfterRecovery(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := orphanCluster()
	runManualCycle(t, manualCfg(false), c, fb)
	if fb.claimCount() != 0 {
		t.Fatalf("claimed in the recovery cycle")
	}
	runManualCycle(t, manualCfg(false), c, fb)
	if fb.claimCount() != 1 {
		t.Errorf("claims after recovery = %d, want 1", fb.claimCount())
	}
}

// AC-G6: a drain in flight (budget full) means no claim.
func TestManualDrainInFlightBlocksClaim(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := orphanCluster()
	// Intent gates Off: recovery stays Off, so the draining node stays in flight.
	runManualCycle(t, &config.Config{EnableManualApproval: true}, c, fb)
	if fb.claimCount() != 0 {
		t.Errorf("claimed with a drain in flight")
	}
}

// AC-G2: an approved node proposal runs exactly one Drain after re-validation, writes
// the proposal-<id> annotation, reports exactly one outcome and does not propose in
// the same cycle.
func TestManualApprovedDrainExecutesOnceAndReportsOnce(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	items := proposeCycle(t, fb, c)
	it := items[keyEvictorN3]
	fb.queueClaim(approvedJSON(t, it, "p-evict", time.Now().Add(-time.Hour), nil))

	mset := runManualCycle(t, manualCfg(false), c, fb)

	n := nodeAnn(t, c, "node-3")
	if n.Annotations[drain.AnnotationOperation] != "proposal-p-evict" || n.Annotations[drain.AnnotationState] != drain.StateDrained || !n.Spec.Unschedulable {
		t.Fatalf("node-3 after the approved drain: unschedulable=%v annotations=%v", n.Spec.Unschedulable, n.Annotations)
	}
	patched, ev := nodeWrites(c)
	if ev != 0 {
		t.Errorf("evictions: %d", ev)
	}
	for _, p := range patched {
		if p != "node-3" {
			t.Errorf("patched a node other than the approved one: %v", patched)
		}
	}
	outs := fb.outcomeList()
	if len(outs) != 1 {
		t.Fatalf("outcomes = %d, want exactly 1", len(outs))
	}
	o := outs[0].Out
	if o.ProposalID != "p-evict" || o.Status != approval.StatusSucceeded || !o.Verified || o.DryRun ||
		o.OperationID != "proposal-p-evict" || o.Stage != approval.StageExecution || o.SchemaVersion != 1 {
		t.Errorf("outcome = %+v", o)
	}
	var recs []struct {
		Controller  string                 `json:"controller"`
		OperationID string                 `json:"operationId"`
		Details     map[string]interface{} `json:"details"`
	}
	if err := json.Unmarshal(o.Actions, &recs); err != nil || len(recs) != 1 || recs[0].Controller != "evictor" ||
		recs[0].OperationID != "proposal-p-evict" || recs[0].Details["trigger"] != "approved-proposal" || recs[0].Details["proposalId"] != "p-evict" {
		t.Errorf("outcome actions = %s (err %v)", o.Actions, err)
	}
	if len(fb.batches) != 0 {
		t.Errorf("a live action ran: proposals must wait for the next cycle, got %d batch(es)", len(fb.batches))
	}
	// The same records ride the cycle's one push with the proposal id.
	pushed := false
	for _, raw := range fb.cap.items("autoscaling-actions") {
		pushed = pushed || strings.Contains(string(raw), `"proposalId":"p-evict"`)
	}
	if !pushed {
		t.Error("approved action records did not reach autoscaling-actions")
	}
	out := scrapeMetrics(t, mset)
	for _, want := range []string{`xamops_approved_executions_total{result="succeeded",type="NODE_DRAIN"} 1`, `xamops_proposal_revalidation_total{result="ok"} 1`} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %s in:\n%s", want, grepLines(out, "xamops_approved"))
		}
	}
}

// AC-G2 for DRY_RUN: the approved action is simulated (no write) and reported as such.
func TestManualApprovedDrainUnderDryRunIsSimulated(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	it := proposeCycle(t, fb, c)[keyEvictorN3]
	fb.queueClaim(approvedJSON(t, it, "p-dry", time.Now().Add(-time.Hour), nil))
	runManualCycle(t, manualCfg(true), c, fb)
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("DRY_RUN executed writes: %v", w)
	}
	outs := fb.outcomeList()
	if len(outs) != 1 || outs[0].Out.Status != approval.StatusSimulated || !outs[0].Out.DryRun || outs[0].Out.Verified {
		t.Fatalf("outcomes = %+v", outs)
	}
}

// AC-G3: every drift case is reported stale and the cluster is untouched.
func TestManualDriftedProposalIsStale(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(c *fake.Clientset, fb *fakeBackend) func(m map[string]interface{})
		reason string
	}{
		{"node uid changed", func(c *fake.Clientset, fb *fakeBackend) func(map[string]interface{}) {
			setUID(c, "node-3", "-replaced")
			return nil
		}, "replaced (uid changed)"},
		{"cordon state changed", func(c *fake.Clientset, fb *fakeBackend) func(map[string]interface{}) {
			n := nodeAnn(t, c, "node-3")
			n.Spec.Unschedulable = true
			_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
			return nil
		}, "cordon state changed"},
		{"plan fingerprint changed", func(c *fake.Clientset, fb *fakeBackend) func(map[string]interface{}) {
			return func(m map[string]interface{}) {
				m["planFingerprint"] = "sha256:" + strings.Repeat("0", 64)
			}
		}, "fingerprint differs"},
		{"candidate gone", func(c *fake.Clientset, fb *fakeBackend) func(map[string]interface{}) {
			// The user lowered the evictor target: node-3 is still drainable but is no
			// longer produced as a candidate.
			fb.setPolicy(servedManual("full", func(in map[string]map[string]interface{}) {
				in["evictorPolicy"]["targetUtilizationCpu"] = 1
				in["evictorPolicy"]["targetUtilizationMemory"] = 1
			}))
			return nil
		}, "no longer produced"},
		{"node gone", func(c *fake.Clientset, fb *fakeBackend) func(map[string]interface{}) {
			_ = c.CoreV1().Nodes().Delete(context.Background(), "node-3", metav1.DeleteOptions{})
			return nil
		}, "no longer exists"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			useCleanManualState(t)
			fb := newFakeBackend(t, servedManual("full", nil))
			c := consolidationCluster()
			it := proposeCycle(t, fb, c)[keyEvictorN3]
			mut := tc.mutate(c, fb)
			fb.queueClaim(approvedJSON(t, it, "p-drift", time.Now().Add(-time.Hour), mut))
			c.ClearActions()
			runManualCycle(t, manualCfg(false), c, fb)
			if w := writeVerbs(c); len(w) != 0 {
				t.Errorf("a drifted proposal touched the cluster: %v", w)
			}
			outs := fb.outcomeList()
			if len(outs) != 1 || outs[0].Out.Status != approval.StatusStale || !strings.Contains(outs[0].Out.Reason, tc.reason) {
				t.Fatalf("outcomes = %+v, want one stale containing %q", outs, tc.reason)
			}
		})
	}
}

// AC-G4: with the intent gate Off or Analyse an approved proposal is refused
// (EXECUTION) and nothing executes; approval never opens a gate.
func TestManualApprovedProposalCannotOpenAGate(t *testing.T) {
	for _, tc := range []struct {
		name string
		cfg  func() *config.Config
		pol  func() []byte
	}{
		{"env flag off", func() *config.Config { c := manualCfg(false); c.EnableEvictor = false; return c }, nil},
		{"mode scoped", nil, func() []byte { return servedManual("scoped", nil) }},
		{"policy flag off (analyseOnly)", nil, func() []byte {
			return servedManual("full", func(in map[string]map[string]interface{}) { in["evictorPolicy"]["analyseOnly"] = true })
		}},
		{"evictor disabled in policy", nil, func() []byte {
			return servedManual("full", func(in map[string]map[string]interface{}) { in["evictorPolicy"]["enabled"] = false })
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			useCleanManualState(t)
			fb := newFakeBackend(t, servedManual("full", nil))
			c := consolidationCluster()
			it := proposeCycle(t, fb, c)[keyEvictorN3]
			fb.queueClaim(approvedJSON(t, it, "p-gate", time.Now().Add(-time.Hour), nil))
			cfg := manualCfg(false)
			if tc.cfg != nil {
				cfg = tc.cfg()
			}
			if tc.pol != nil {
				fb.setPolicy(tc.pol())
			}
			runManualCycle(t, cfg, c, fb)
			if w := writeVerbs(c); len(w) != 0 {
				t.Errorf("a closed gate was opened by approval: %v", w)
			}
			outs := fb.outcomeList()
			if len(outs) != 1 || outs[0].Out.Status != approval.StatusRefused || outs[0].Out.Stage != approval.StageExecution ||
				!strings.HasPrefix(outs[0].Out.Reason, "gate closed") {
				t.Fatalf("outcomes = %+v", outs)
			}
		})
	}
}

// AC-G5: a plan the executor's classifier refuses is refused at proposal time AND, if a
// backend defect approved it anyway, refused again at execution with nothing evicted.
func TestManualRefusedPlansAtBothStages(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := fake.NewSimpleClientset(loadSection4(t)...)
	items := proposeCycle(t, fb, c)
	var refused *approval.Item
	for _, it := range items {
		it := it
		if it.Verdict == approval.VerdictRefused && len(it.Refusal.Codes) > 0 {
			refused = &it
			break
		}
	}
	if refused == nil {
		t.Fatalf("section-4 fixture produced no refused proposal: %v", keysOf(items))
	}
	t.Logf("refused proposal %s codes=%v", refused.DedupeKey, refused.Refusal.Codes)
	fb.queueClaim(approvedJSON(t, *refused, "p-refused", time.Now().Add(-time.Hour), nil))
	runManualCycle(t, manualCfg(false), c, fb)
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("a refused plan wrote: %v", w)
	}
	outs := fb.outcomeList()
	if len(outs) != 1 || outs[0].Out.Status != approval.StatusRefused || outs[0].Out.Stage != approval.StageExecution ||
		!strings.Contains(outs[0].Out.Reason, "plan refused at execution time") {
		t.Fatalf("outcomes = %+v", outs)
	}
}

// AC-G6: two approved proposals never run in the same cycle: one claim per cycle.
func TestManualOneClaimPerCycle(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	it := proposeCycle(t, fb, c)[keyEvictorN3]
	fb.queueClaim(approvedJSON(t, it, "p-1", time.Now().Add(-time.Hour), nil))
	fb.queueClaim(approvedJSON(t, it, "p-2", time.Now().Add(-time.Hour), nil))
	base := fb.claimCount()
	runManualCycle(t, manualCfg(true), c, fb)
	if fb.claimCount()-base != 1 || len(fb.outcomeList()) != 1 {
		t.Fatalf("claims=%d outcomes=%d after one cycle, want 1/1", fb.claimCount()-base, len(fb.outcomeList()))
	}
	runManualCycle(t, manualCfg(true), c, fb)
	if fb.claimCount()-base != 2 || len(fb.outcomeList()) != 2 {
		t.Errorf("claims=%d outcomes=%d after two cycles, want 2/2", fb.claimCount()-base, len(fb.outcomeList()))
	}
}

// AC-G9: a failed outcome POST is queued and retried at the top of the next cycle even
// when that cycle is Autonomous-or-disabled; a paused cycle writes nothing and never
// runs orphan recovery.
func TestManualOutcomeQueueSurvivesPause(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	c := consolidationCluster()
	it := proposeCycle(t, fb, c)[keyEvictorN3]
	fb.queueClaim(approvedJSON(t, it, "p-queue", time.Now().Add(-time.Hour), nil))
	fb.outcomeFail = 1
	mset := runManualCycle(t, manualCfg(true), c, fb)
	if len(fb.outcomeList()) != 0 || manualProc.queue.Len() != 1 {
		t.Fatalf("after a failed POST: delivered=%d queued=%d, want 0/1", len(fb.outcomeList()), manualProc.queue.Len())
	}
	if !strings.Contains(scrapeMetrics(t, mset), "xamops_proposal_outcome_queue 1") {
		t.Error("outcome queue gauge not 1")
	}

	// Pause all writes: disabled autoscaler mode, Autonomous approvalMode. A node is
	// left draining; with RecoverOrphans skipped it must stay untouched.
	n := nodeAnn(t, c, "node-1")
	n.Annotations = map[string]string{drain.AnnotationState: drain.StateDraining, drain.AnnotationCordonedBy: drain.CordonedByXamops}
	n.Spec.Unschedulable = true
	_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
	c.ClearActions()
	fb.setPolicy([]byte(`{"autoscalerMode":"disabled","approvalMode":"autonomous"}`))
	runManualCycle(t, manualCfg(false), c, fb)
	outs := fb.outcomeList()
	if len(outs) != 1 || outs[0].ID != "p-queue" || outs[0].Out.Status != approval.StatusSimulated {
		t.Fatalf("queued outcome after the pause = %+v", outs)
	}
	if manualProc.queue.Len() != 0 {
		t.Errorf("queue not drained: %d", manualProc.queue.Len())
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("a paused cycle wrote (RecoverOrphans must not run while disabled): %v", w)
	}
}

// AC-G9 + AC-G10: while paused (disabled), a DISPATCHED row the agent cannot account
// for is resolved from the node's annotations, with zero node writes.
func TestManualReconcileWhilePaused(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, []byte(`{"autoscalerMode":"disabled"}`))
	c := consolidationCluster()
	n := nodeAnn(t, c, "node-3")
	n.Spec.Unschedulable = true
	n.Annotations = map[string]string{drain.AnnotationOperation: "proposal-p-lost", drain.AnnotationState: drain.StateDrained}
	_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
	c.ClearActions()
	fb.dispatched = `{"items":[
	  {"id":"p-lost","targetRef":"node-3","targetIdentity":"uid-3","operationId":"proposal-p-lost","actionType":"NODE_DRAIN","operation":"DRAIN","dispatchedAt":"2026-11-01T10:02:15Z","dryRun":false},
	  {"id":"p-never","targetRef":"node-2","targetIdentity":"uid-2","operationId":"proposal-p-never","actionType":"NODE_DRAIN","operation":"DRAIN","dispatchedAt":"2026-11-01T10:02:15Z","dryRun":false},
	  {"id":"p-gone","targetRef":"node-9","targetIdentity":"uid-9","operationId":"proposal-p-gone","actionType":"NODE_DRAIN","operation":"DRAIN","dispatchedAt":"2026-11-01T10:02:15Z","dryRun":false}]}`
	runManualCycle(t, manualCfg(false), c, fb)
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("reconcile wrote to the cluster: %v", w)
	}
	got := map[string]approval.Outcome{}
	for _, o := range fb.outcomeList() {
		got[o.ID] = o.Out
	}
	if o := got["p-lost"]; o.Status != approval.StatusSucceeded || o.Verified || o.Reason != "reported after restart" {
		t.Errorf("p-lost = %+v", o)
	}
	if o := got["p-never"]; o.Status != approval.StatusNotStarted {
		t.Errorf("p-never = %+v", o)
	}
	if o := got["p-gone"]; o.Status != approval.StatusFailed || o.Reason != "node removed before execution" {
		t.Errorf("p-gone = %+v", o)
	}
	for _, o := range got {
		if o.OperationID != "proposal-"+o.ProposalID || o.Stage != approval.StageExecution {
			t.Errorf("reconcile outcome shape: %+v", o)
		}
	}
}

// Consolidation: the approved move runs through ExecuteWithRunID with the in-memory
// candidate-since from firstSeenAt. The agent guard holds a young proposal; the real
// node object is never patched for candidate-since; the cycle's Input is not mutated.
func TestManualApprovedConsolidation(t *testing.T) {
	setup := func(t *testing.T) (*fakeBackend, *fake.Clientset, approval.Item) {
		useCleanManualState(t)
		fb := newFakeBackend(t, servedManual("full", nil))
		c := consolidationCluster()
		it, ok := proposeCycle(t, fb, c)[keyConsolN3]
		if !ok {
			t.Fatal("no consolidation proposal")
		}
		return fb, c, it
	}

	t.Run("old enough runs (simulated) and writes nothing", func(t *testing.T) {
		fb, c, it := setup(t)
		fb.queueClaim(approvedJSON(t, it, "p-cons", time.Now().Add(-2*time.Hour), nil))
		runManualCycle(t, manualCfg(true), c, fb)
		if w := writeVerbs(c); len(w) != 0 {
			t.Errorf("writes: %v", w)
		}
		if _, set := nodeAnn(t, c, "node-3").Annotations["xamops.io/consolidation-candidate-since"]; set {
			t.Error("candidate-since was written to the real node")
		}
		outs := fb.outcomeList()
		if len(outs) != 1 || outs[0].Out.Status != approval.StatusSimulated || !outs[0].Out.DryRun {
			t.Fatalf("outcomes = %+v", outs)
		}
		var run struct {
			RunID string `json:"runId"`
		}
		if err := json.Unmarshal(outs[0].Out.Consolidation, &run); err != nil || run.RunID != "proposal-p-cons" {
			t.Errorf("consolidation run = %s (err %v), want runId proposal-p-cons", outs[0].Out.Consolidation, err)
		}
	})

	t.Run("agent dwell guard holds a young proposal", func(t *testing.T) {
		fb, c, it := setup(t)
		fb.queueClaim(approvedJSON(t, it, "p-young", time.Now().Add(-5*time.Minute), nil)) // dwell 600 + 60
		runManualCycle(t, manualCfg(true), c, fb)
		outs := fb.outcomeList()
		if len(outs) != 1 || outs[0].Out.Status != approval.StatusStale || outs[0].Out.Reason != "dwell not elapsed" {
			t.Fatalf("outcomes = %+v", outs)
		}
	})

	t.Run("missing dwell is stale", func(t *testing.T) {
		fb, c, it := setup(t)
		fb.queueClaim(approvedJSON(t, it, "p-nodwell", time.Now().Add(-2*time.Hour), func(m map[string]interface{}) { m["dwellSeconds"] = nil }))
		runManualCycle(t, manualCfg(true), c, fb)
		outs := fb.outcomeList()
		if len(outs) != 1 || outs[0].Out.Status != approval.StatusStale || outs[0].Out.Reason != "dwell missing" {
			t.Fatalf("outcomes = %+v", outs)
		}
	})

	t.Run("the consolidator's own dwell still runs on top", func(t *testing.T) {
		fb, c, it := setup(t)
		// The claim's dwell (120) passes the agent guard, but the intent's own
		// consolidateAfterSeconds is longer: the controller skips it, reported stale.
		fb.setPolicy(servedManual("full", func(in map[string]map[string]interface{}) {
			in["consolidationPolicy"]["consolidateAfterSeconds"] = 36000
		}))
		fb.queueClaim(approvedJSON(t, it, "p-dwell", time.Now().Add(-time.Hour), func(m map[string]interface{}) { m["dwellSeconds"] = 120 }))
		c.ClearActions()
		runManualCycle(t, manualCfg(true), c, fb)
		outs := fb.outcomeList()
		if len(outs) != 1 {
			t.Fatalf("outcomes = %+v", outs)
		}
		// The intent changed the dwell, which changes the dedupe-relevant plan only via
		// dwell: the fingerprint is unaffected, so the controller's own check decides.
		if outs[0].Out.Status != approval.StatusStale || outs[0].Out.Reason == "dwell not elapsed" ||
			!strings.Contains(outs[0].Out.Reason, "consolidateAfter dwell") {
			t.Errorf("status = %s (%s), want stale from the consolidator's own dwell check", outs[0].Out.Status, outs[0].Out.Reason)
		}
	})
}

// executeConsolidation never mutates the cycle's consolidation Input (shallow copy, node
// slice copied, only the target node's annotations replaced in the copy).
func TestExecuteConsolidationDoesNotMutateInput(t *testing.T) {
	useCleanManualState(t)
	c := consolidationCluster()
	in, err := consolidation.Collect(context.Background(), c, nil)
	if err != nil {
		t.Fatal(err)
	}
	plan := consolidation.Analyze(in, fitsimOptions(), time.Now())
	if len(plan.Moves) != 1 {
		t.Fatalf("moves = %+v", plan.Moves)
	}
	before := map[string]map[string]string{}
	for _, n := range in.Nodes {
		before[n.Name] = n.Annotations
	}
	rt := newTestRT(t, c, manualCfg(true))
	dw := 600
	a := &approval.ApprovedAction{ProposalID: "p-x", ActionType: approval.TypeNodeConsolidate, Operation: approval.OpEmpty,
		OriginController: approval.OriginConsolidation, Target: approval.Target{Name: "node-3"},
		FirstSeenAt: time.Now().Add(-time.Hour), DwellSeconds: &dw}
	bundle, intent := testBundles(t)
	out := rt.executeConsolidation(context.Background(), a, bundle, intent, gate.Simulate, manualInputs{ConsInput: &in, ConsPlan: &plan})
	if out.Status != approval.StatusSimulated {
		t.Errorf("outcome = %+v", out)
	}
	for _, n := range in.Nodes {
		if _, set := n.Annotations["xamops.io/consolidation-candidate-since"]; set {
			t.Errorf("Input.Nodes[%s] was mutated", n.Name)
		}
		if len(before[n.Name]) != len(n.Annotations) {
			t.Errorf("annotations of %s changed", n.Name)
		}
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

// (e) isLeader flips on the election callbacks, and the heartbeat closure follows it.
func TestLeadershipCallbacksFlipIsLeaderAndHeartbeat(t *testing.T) {
	isLeader.Store(false)
	t.Cleanup(func() { isLeader.Store(false) })
	ran := make(chan struct{}, 1)
	lc := &loopController{run: func(ctx context.Context) { ran <- struct{}{}; <-ctx.Done() }}
	mset := metrics.New()
	cb := leadershipCallbacks("pod-a", mset, lc)
	var _ leaderelection.LeaderCallbacks = cb

	on, off := &config.Config{EnableManualApproval: true, DrainTimeoutSeconds: 900}, &config.Config{DrainTimeoutSeconds: 900}
	if e := heartbeatExtras(on)(); e.Leader || len(e.Features) != 1 || e.Features[0] != "manual-v1" || e.DrainTimeoutSeconds != 900 {
		t.Errorf("standby extras (flag on) = %+v", e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cb.OnStartedLeading(ctx)
	<-ran
	if !isLeader.Load() || !heartbeatExtras(on)().Leader {
		t.Error("isLeader not set when the Lease was acquired")
	}
	if e := heartbeatExtras(off)(); !e.Leader || len(e.Features) != 0 {
		t.Errorf("flag off must advertise no features, got %+v", e)
	}
	cb.OnStoppedLeading()
	if isLeader.Load() || heartbeatExtras(on)().Leader {
		t.Error("isLeader still set after the Lease was lost")
	}
}

// recoveryMode: Autonomous is unchanged; Manual takes the stronger of served and intent,
// and the consolidation actions gate counts (nodeActionMode does not cover it).
func TestRecoveryModeManualUsesIntent(t *testing.T) {
	cfg := manualCfg(false)
	fb := newFakeBackend(t, servedManual("full", nil))
	bundle, err := policy.NewClient(fb.srv.URL, "k", "c", "1").FetchPolicy(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	d := decide(cfg, bundle)
	if d.nodeActionMode() != gate.Off {
		t.Fatalf("served node-action mode = %s, want off", d.nodeActionMode())
	}
	if m := recoveryMode(cfg, bundle, d); m != gate.Live {
		t.Errorf("manual recovery mode = %s, want live", m)
	}
	if m := recoveryMode(manualCfg(true), bundle, decide(manualCfg(true), bundle)); m != gate.Simulate {
		t.Errorf("manual recovery under DRY_RUN = %s, want simulate", m)
	}
	bundle.ApprovalMode = policy.ApprovalAutonomous
	if m := recoveryMode(cfg, bundle, d); m != gate.Off {
		t.Errorf("autonomous recovery mode = %s, want off (unchanged)", m)
	}
	// Only the consolidation actions gate is open.
	consOnly := decide(cfg, bundle)
	consOnly.ConsolidationActions = gate.Decision{Mode: gate.Live}
	if consOnly.manualRecoveryMode() != gate.Live || consOnly.nodeActionMode() != gate.Off {
		t.Errorf("manualRecoveryMode must include ConsolidationActions: %s / %s", consOnly.manualRecoveryMode(), consOnly.nodeActionMode())
	}
}

func fitsimOptions() fitsim.Options {
	return fitsim.Options{Guards: policy.DefaultBundle().SafetyGuards, SelfNamespace: "xamops-system"}
}

// newTestRT is a manualRT over a fake cluster with the real fit planner.
func newTestRT(t *testing.T, c *fake.Clientset, cfg *config.Config) *manualRT {
	t.Helper()
	cfg.PodNamespace, cfg.DrainTimeoutSeconds = "xamops-system", 30
	mset := metrics.New()
	planner := drain.NewFitPlanner(c, nil, fitsimOptions())
	return &manualRT{
		cfg: cfg, mset: mset, k8s: c, planner: planner, exec: drain.NewExecutor(c, planner, "xamops-system"),
		budget: drain.NewBudget(1, nil), acts: &cycleActions{mset: mset},
		drainReports: &[]controller.DrainProgressReport{}, batches: &[]pusher.Batch{},
	}
}

func testBundles(t *testing.T) (bundle, intent policy.PolicyBundle) {
	t.Helper()
	bundle = policy.DefaultBundle()
	intent = bundle
	intent.ConsolidationPolicy = policy.ConsolidationPolicy{Enabled: true, ConsolidateAfterSeconds: 600, MaxConcurrentDisruptions: 1, DwellSeconds: 600}
	return bundle, intent
}

// stubPlanner scripts the fit planner (and its SetSelector) for classification and
// benefit-guard tests.
type stubPlanner struct {
	plan     drain.Plan
	err      error
	selected []string
	selErr   error
	selCalls int
}

func (p *stubPlanner) Plan(_ context.Context, nodes []string) (drain.Plan, error) {
	pl := p.plan
	pl.Nodes = nodes
	return pl, p.err
}

func (p *stubPlanner) SelectSet(_ context.Context, _ []string, _ int) ([]string, map[string]drain.Plan, error) {
	p.selCalls++
	return p.selected, nil, p.selErr
}

func drainablePlan() drain.Plan {
	return drain.Plan{Drainable: true, FitChecked: true, Detail: fitsim.Plan{Drainable: true}}
}

func stubRT(t *testing.T, c *fake.Clientset, sp *stubPlanner, cfg *config.Config) *manualRT {
	t.Helper()
	rt := newTestRT(t, c, cfg)
	rt.planner = sp
	rt.exec = drain.NewExecutor(c, sp, "xamops-system")
	return rt
}

func oneNodeCluster() (*fake.Clientset, corev1.Node) {
	c := fake.NewSimpleClientset(cycleNode("n1", nil))
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "n1", metav1.GetOptions{})
	n.UID = "uid-n1"
	_, _ = c.CoreV1().Nodes().Update(context.Background(), n, metav1.UpdateOptions{})
	c.ClearActions()
	return c, *n
}

func rebalDesc() descriptor {
	return descriptor{ActionType: approval.TypeRebalance, Operation: approval.OpDrain, Origin: approval.OriginRebalancer,
		Node: "n1", Trigger: "rebalance", Gate: gate.Decision{Mode: gate.Live}}
}

// AC-G5b: the rebalancer's benefit guard (exec.SelectSet capacity 1) refuses at proposal
// time with no-net-reduction, refuses again at execution with nothing evicted and no
// Drain call, and a candidate SelectSet accepts proceeds to exec.Drain.
func TestManualRebalanceBenefitGuard(t *testing.T) {
	useCleanManualState(t)
	ctx := context.Background()

	t.Run("refused at proposal", func(t *testing.T) {
		c, node := oneNodeCluster()
		sp := &stubPlanner{plan: drainablePlan(), selected: []string{}}
		rt := stubRT(t, c, sp, manualCfg(false))
		got := rt.classify(ctx, rebalDesc(), node, drainablePlan(), nil, classifyEnv{})
		if got.Verdict != approval.VerdictRefused || fmt.Sprint(got.Codes) != "[no-net-reduction]" || sp.selCalls != 1 {
			t.Errorf("classified = %+v selCalls=%d", got, sp.selCalls)
		}
	})
	t.Run("refused at execution, nothing evicted", func(t *testing.T) {
		c, _ := oneNodeCluster()
		sp := &stubPlanner{plan: drainablePlan(), selected: []string{}}
		rt := stubRT(t, c, sp, manualCfg(false))
		a := &approval.ApprovedAction{ProposalID: "p-b", ActionType: approval.TypeRebalance, Operation: approval.OpDrain,
			OriginController: approval.OriginRebalancer, Target: approval.Target{Name: "n1"}}
		out := rt.executeDrain(ctx, a, gate.Live)
		if out.Status != approval.StatusRefused || out.Stage != approval.StageExecution || !strings.Contains(out.Reason, "no net node reduction") {
			t.Errorf("outcome = %+v", out)
		}
		if w := writeVerbs(c); len(w) != 0 {
			t.Errorf("writes: %v", w)
		}
		if len(rt.acts.records) != 0 {
			t.Errorf("Drain was called: %+v", rt.acts.records)
		}
	})
	t.Run("a benefit-check error refuses", func(t *testing.T) {
		c, _ := oneNodeCluster()
		sp := &stubPlanner{plan: drainablePlan(), selErr: fmt.Errorf("boom")}
		rt := stubRT(t, c, sp, manualCfg(false))
		a := &approval.ApprovedAction{ProposalID: "p-e", ActionType: approval.TypeRebalance, Operation: approval.OpDrain,
			OriginController: approval.OriginRebalancer, Target: approval.Target{Name: "n1"}}
		if out := rt.executeDrain(ctx, a, gate.Live); out.Status != approval.StatusRefused || len(rt.acts.records) != 0 {
			t.Errorf("outcome = %+v", out)
		}
	})
	t.Run("accepted candidate proceeds to Drain", func(t *testing.T) {
		c, node := oneNodeCluster()
		sp := &stubPlanner{plan: drainablePlan(), selected: []string{"n1"}}
		rt := stubRT(t, c, sp, manualCfg(true))
		if got := rt.classify(ctx, rebalDesc(), node, drainablePlan(), nil, classifyEnv{}); got.Verdict != approval.VerdictAllowed {
			t.Errorf("classified = %+v", got)
		}
		a := &approval.ApprovedAction{ProposalID: "p-ok", ActionType: approval.TypeRebalance, Operation: approval.OpDrain,
			OriginController: approval.OriginRebalancer, Target: approval.Target{Name: "n1"}}
		out := rt.executeDrain(ctx, a, gate.Simulate)
		if out.Status != approval.StatusSimulated || len(rt.acts.records) != 1 {
			t.Fatalf("outcome = %+v records=%d", out, len(rt.acts.records))
		}
		if r := rt.acts.records[0]; r.Controller != "rebalancer" || r.OperationID != "proposal-p-ok" || r.Action != "drain" {
			t.Errorf("record = %+v", r)
		}
	})
}

// classify turns the simulator's findings into refusal codes (suffixes kept, sorted,
// de-duplicated) and treats everything it cannot explain as refused.
func TestManualClassifyRefusalCodes(t *testing.T) {
	useCleanManualState(t)
	ctx := context.Background()
	c, node := oneNodeCluster()
	rt := stubRT(t, c, &stubPlanner{}, manualCfg(false))
	d := descriptor{ActionType: approval.TypeNodeDrain, Operation: approval.OpDrain, Origin: approval.OriginEvictor, Node: "n1",
		Trigger: "evictor", Gate: gate.Decision{Mode: gate.Live}}
	withFit := func(fp fitsim.Plan, moves ...drain.Move) drain.Plan {
		return drain.Plan{Drainable: fp.Drainable, FitChecked: true, Moves: moves, Detail: fp}
	}
	for _, tc := range []struct {
		name  string
		plan  drain.Plan
		err   error
		self  string
		codes string
	}{
		{"pdb + statefulset + pvc", withFit(fitsim.Plan{
			Refused: []fitsim.Finding{{Pod: "a/b", Reason: "pvc-volume"}},
			Blocked: []fitsim.Finding{{Pod: "a/c", Reason: "pdb-no-disruptions:a/pdb; statefulset"}, {Pod: "a/d", Reason: "statefulset"}},
		}), nil, "", "[pdb-no-disruptions:a/pdb pvc-volume statefulset]"},
		{"strand", withFit(fitsim.Plan{Strands: []string{"a/b"}}), nil, "", "[strand]"},
		{"plan error", withFit(fitsim.Plan{Errors: []string{"node missing"}}), nil, "", "[plan-error]"},
		{"not drainable without a reason", withFit(fitsim.Plan{}), nil, "", "[not-drainable]"},
		{"planner error", drain.Plan{}, fmt.Errorf("boom"), "", "[planner-error]"},
		{"no fit plan (unavailable planner)", drain.Plan{Reasons: []string{drain.UnavailableReason}}, nil, "", "[plan-unavailable]"},
		{"self node", withFit(fitsim.Plan{Drainable: true}), nil, "n1", "[self-node]"},
		{"move without a controller owner", withFit(fitsim.Plan{Drainable: true}, drain.Move{Name: "p"}), nil, "", "[no-controller-owner]"},
		{"drainable", withFit(fitsim.Plan{Drainable: true}, drain.Move{Name: "p", OwnerUID: "o"}), nil, "", "[]"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := rt.classify(ctx, d, node, tc.plan, tc.err, classifyEnv{selfNod: tc.self})
			if fmt.Sprint(got.Codes) != tc.codes {
				t.Errorf("codes = %v, want %s", got.Codes, tc.codes)
			}
			if (len(got.Codes) > 0) != (got.Verdict == approval.VerdictRefused) || got.Item.Refusal.Reason != approval.JoinCodes(got.Codes) {
				t.Errorf("verdict/reason mismatch: %+v", got)
			}
			if got.Item.Refusal.Codes == nil {
				t.Error("refusal.codes must serialize as [] not null")
			}
		})
	}
}

// The fingerprint ignores the fields that churn every cycle (moves, PDB counters,
// summary) and changes with uid and with the refusal set.
func TestManualFingerprintIgnoresChurn(t *testing.T) {
	useCleanManualState(t)
	ctx := context.Background()
	c, node := oneNodeCluster()
	rt := stubRT(t, c, &stubPlanner{}, manualCfg(false))
	d := descriptor{ActionType: approval.TypeNodeDrain, Operation: approval.OpDrain, Origin: approval.OriginEvictor, Node: "n1",
		Trigger: "evictor", Gate: gate.Decision{Mode: gate.Live}}
	fp := func(f fitsim.Plan, n corev1.Node, extra map[string]interface{}) string {
		dd := d
		dd.Extra = extra
		return rt.classify(ctx, dd, n, drain.Plan{Drainable: f.Drainable, Detail: f}, nil, classifyEnv{}).Fingerprint
	}
	a := fp(fitsim.Plan{Drainable: true, Summary: "3 pods", Moves: []fitsim.Move{{Pod: "x/y"}},
		PDBChecks: []fitsim.PDBCheck{{PDB: "x/p", DisruptionsAllowed: 1}}}, node, map[string]interface{}{"cpuRequestPct": 4.2})
	b := fp(fitsim.Plan{Drainable: true, Summary: "9 pods", Moves: []fitsim.Move{{Pod: "x/z"}, {Pod: "x/w"}},
		PDBChecks: []fitsim.PDBCheck{{PDB: "x/p", DisruptionsAllowed: 5}}}, node, map[string]interface{}{"cpuRequestPct": 6.0})
	if a != b {
		t.Error("churning fields changed the fingerprint")
	}
	other := node
	other.UID = "uid-other"
	if fp(fitsim.Plan{Drainable: true}, other, nil) == a {
		t.Error("uid must change the fingerprint")
	}
	if fp(fitsim.Plan{Drainable: true, Blocked: []fitsim.Finding{{Reason: "statefulset"}}}, node, nil) == a {
		t.Error("a new blocking code must change the fingerprint")
	}
}

// A 400 from the backend is counted by code and the cycle carries on (never silent).
func TestManualProposalRejectionIsCounted(t *testing.T) {
	useCleanManualState(t)
	fb := newFakeBackend(t, servedManual("full", nil))
	fb.proposalsStatus = 400
	c := consolidationCluster()
	mset := runManualCycle(t, manualCfg(false), c, fb)
	if out := scrapeMetrics(t, mset); !strings.Contains(out, `xamops_proposal_post_rejected_total{code="BAD_REBALANCE"} 1`) {
		t.Errorf("rejection not counted:\n%s", grepLines(out, "proposal_post"))
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}
