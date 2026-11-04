package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/approval"
	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/metrics"
)

// fakeBackend is the XamOps backend as the Manual-mode agent sees it: the served
// policy, the ingest endpoint, the proposal endpoints, and a Prometheus that answers
// with empty vectors. It records every request.
type fakeBackend struct {
	t   *testing.T
	srv *httptest.Server
	cap *captured

	mu          sync.Mutex
	policy      []byte
	requests    []string // "METHOD path"
	batches     [][]byte
	claimQueue  []string
	claims      int
	dispatched  string
	outcomes    []recordedOutcome
	outcomeFail int // the next N outcome POSTs answer 500
	// proposalsStatus, when non-zero, is the status POST /proposals answers.
	proposalsStatus int
}

type recordedOutcome struct {
	ID  string
	Out approval.Outcome
}

func newFakeBackend(t *testing.T, policyBody []byte) *fakeBackend {
	t.Helper()
	fb := &fakeBackend{t: t, policy: policyBody, cap: &captured{batches: map[string][]json.RawMessage{}}, dispatched: `{"items":[]}`}
	fb.srv = httptest.NewServer(http.HandlerFunc(fb.serve))
	t.Cleanup(fb.srv.Close)
	return fb
}

func (fb *fakeBackend) setPolicy(b []byte) {
	fb.mu.Lock()
	fb.policy = b
	fb.mu.Unlock()
}

func (fb *fakeBackend) queueClaim(body string) {
	fb.mu.Lock()
	fb.claimQueue = append(fb.claimQueue, body)
	fb.mu.Unlock()
}

func (fb *fakeBackend) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	fb.mu.Lock()
	defer fb.mu.Unlock()
	fb.requests = append(fb.requests, r.Method+" "+r.URL.Path)
	p := r.URL.Path
	switch {
	case p == "/api/v1/agent/autoscaling-policy":
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(fb.policy)
	case p == "/api/v1/agent/ingest":
		var in struct {
			Batches []struct {
				Collector string            `json:"collector"`
				Items     []json.RawMessage `json:"items"`
			} `json:"batches"`
		}
		_ = json.Unmarshal(body, &in)
		fb.cap.mu.Lock()
		for _, b := range in.Batches {
			fb.cap.batches[b.Collector] = append(fb.cap.batches[b.Collector], b.Items...)
		}
		fb.cap.mu.Unlock()
	case p == "/api/v1/query":
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[]}}`))
	case p == "/api/v1/agent/proposals":
		if fb.proposalsStatus != 0 {
			w.WriteHeader(fb.proposalsStatus)
			_, _ = w.Write([]byte(`{"error":"rejected","code":"BAD_REBALANCE"}`))
			return
		}
		fb.batches = append(fb.batches, body)
		var b approval.Batch
		_ = json.Unmarshal(body, &b)
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"accepted":%d,"suppressed":[],"rejected":[]}`, len(b.Items))
	case p == "/api/v1/agent/proposals/claim":
		fb.claims++
		if len(fb.claimQueue) == 0 {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next := fb.claimQueue[0]
		fb.claimQueue = fb.claimQueue[1:]
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(next))
	case p == "/api/v1/agent/proposals/dispatched":
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(fb.dispatched))
	case strings.HasPrefix(p, "/api/v1/agent/proposals/") && strings.HasSuffix(p, "/outcome"):
		if fb.outcomeFail > 0 {
			fb.outcomeFail--
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		var o approval.Outcome
		_ = json.Unmarshal(body, &o)
		fb.outcomes = append(fb.outcomes, recordedOutcome{ID: o.ProposalID, Out: o})
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

// proposalRequests counts every request to a /proposals endpoint.
func (fb *fakeBackend) proposalRequests() int {
	fb.mu.Lock()
	defer fb.mu.Unlock()
	n := 0
	for _, r := range fb.requests {
		if strings.Contains(r, "/api/v1/agent/proposals") {
			n++
		}
	}
	return n
}

func (fb *fakeBackend) claimCount() int {
	fb.mu.Lock()
	defer fb.mu.Unlock()
	return fb.claims
}

func (fb *fakeBackend) outcomeList() []recordedOutcome {
	fb.mu.Lock()
	defer fb.mu.Unlock()
	return append([]recordedOutcome(nil), fb.outcomes...)
}

// lastBatch strictly decodes the first chunk of the most recent cycle's proposals.
func (fb *fakeBackend) allItems() (items []approval.Item, last approval.Batch) {
	fb.mu.Lock()
	defer fb.mu.Unlock()
	for _, raw := range fb.batches {
		var b approval.Batch
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&b); err != nil {
			fb.t.Fatalf("agent sent a batch that does not strictly parse as the contract: %v\n%s", err, raw)
		}
		items = append(items, b.Items...)
		last = b
	}
	return items, last
}

func (fb *fakeBackend) resetBatches() {
	fb.mu.Lock()
	fb.batches, fb.outcomes = nil, nil
	fb.mu.Unlock()
}

// useCleanManualState gives the test its own process state and no chunk pacing.
func useCleanManualState(t *testing.T) {
	t.Helper()
	oldProc, oldSleep := manualProc, manualSleep
	manualProc = newManualState()
	manualSleep = func(context.Context, time.Duration) error { return nil }
	t.Cleanup(func() { manualProc, manualSleep = oldProc, oldSleep })
}

// manualCfg opens every env flag (and ENABLE_MANUAL_APPROVAL); dryRun picks Simulate/Live.
func manualCfg(dryRun bool) *config.Config {
	return &config.Config{DryRun: dryRun, EnableEvictor: true, EnableRebalancer: true, EnableNodeOptimizer: true,
		EnableConsolidation: true, EnableManualApproval: true}
}

func runManualCycle(t *testing.T, cfg *config.Config, c *fake.Clientset, fb *fakeBackend) *metrics.Set {
	t.Helper()
	mset := metrics.New()
	runTestCycleWith(t, cfg, c, fb.srv.URL, mset)
	return mset
}

// servedManual builds a Manual policy the way the backend serves it: every acting
// section neutered, the user's configuration under manualIntent. tweak edits the
// intent sections (keys evictorPolicy, nodeOptimizerPolicy, rebalancer,
// consolidationPolicy) before they are copied.
func servedManual(mode string, tweak func(intent map[string]map[string]interface{})) []byte {
	intent := map[string]map[string]interface{}{
		"evictorPolicy": {"enabled": true, "analyseOnly": false, "aggressiveMode": true, "nodeCycleIntervalSeconds": 60,
			"nodeGracePeriodMinutes": 0, "targetUtilizationCpu": 100, "targetUtilizationMemory": 100},
		"nodeOptimizerPolicy": {"enabled": true, "idleCpuThresholdPct": 10, "idleMemThresholdPct": 20,
			"autoCordonIdleNodes": true, "autoDrainIdleNodes": true, "handleSpotInterruptions": false,
			"spotRatioTargetPct": 0, "maxParallelDrains": 1},
		"rebalancer":          {"enabled": true, "gracefulDraining": true, "drainTimeoutMinutes": 20, "status": "running"},
		"consolidationPolicy": {"enabled": true, "consolidateAfterSeconds": 600, "maxConcurrentDisruptions": 1, "dwellSeconds": 600},
	}
	if tweak != nil {
		tweak(intent)
	}
	// The served copy is the intent with the acting switches neutered (what the
	// backend's ManualModeServePolicy does); non-acting fields pass through.
	served := func(section string, neuter map[string]interface{}) map[string]interface{} {
		out := map[string]interface{}{}
		for k, v := range intent[section] {
			out[k] = v
		}
		for k, v := range neuter {
			out[k] = v
		}
		return out
	}
	body := map[string]interface{}{
		"autoscalerMode": mode,
		"approvalMode":   "manual",
		"evictorPolicy":  served("evictorPolicy", map[string]interface{}{"analyseOnly": true}),
		"nodeOptimizerPolicy": served("nodeOptimizerPolicy", map[string]interface{}{"autoCordonIdleNodes": false,
			"autoDrainIdleNodes": false, "spotRatioTargetPct": 0, "handleSpotInterruptions": false}),
		"rebalancer":          served("rebalancer", map[string]interface{}{"enabled": false, "scheduleEnabled": false, "status": "idle"}),
		"consolidationPolicy": served("consolidationPolicy", map[string]interface{}{"enabled": false}),
		"vpaManagerPolicy":    map[string]interface{}{"enabled": false, "updateMode": "Off", "analyseOnly": true},
		"manualIntent":        intent,
	}
	b, err := json.Marshal(body)
	if err != nil {
		panic(err)
	}
	return b
}

// itemByKey finds one proposal item.
func itemByKey(items []approval.Item, key string) (approval.Item, bool) {
	for _, it := range items {
		if it.DedupeKey == key {
			return it, true
		}
	}
	return approval.Item{}, false
}

// approvedJSON builds the claim response the backend would hand out for an item it
// accepted and a human approved.
func approvedJSON(t *testing.T, it approval.Item, id string, firstSeen time.Time, mutate func(m map[string]interface{})) string {
	t.Helper()
	var params map[string]interface{}
	if err := json.Unmarshal(it.Params, &params); err != nil {
		t.Fatal(err)
	}
	m := map[string]interface{}{
		"proposalId": id, "version": 1, "actionType": it.ActionType, "operation": it.Operation,
		"originController": it.OriginController, "dedupeKey": it.DedupeKey,
		"target": map[string]interface{}{"kind": "NODE", "name": it.Target.Name, "uid": it.Target.UID,
			"providerId": it.Target.ProviderID, "nodeGroup": it.Target.NodeGroup},
		"params": params, "planFingerprint": it.PlanFingerprint,
		"firstSeenAt": firstSeen.UTC().Format(time.RFC3339), "dwellSeconds": it.DwellSeconds,
		"approvedBy": map[string]interface{}{"id": 42, "name": "A. Admin"},
		"approvedAt": time.Now().UTC().Format(time.RFC3339), "approvedUntil": time.Now().Add(15 * time.Minute).UTC().Format(time.RFC3339),
		"dryRunAtProposal": it.Gate.DryRun,
	}
	if mutate != nil {
		mutate(m)
	}
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	// The claim body must satisfy the strict contract parse.
	if _, err := approval.ParseApprovedAction(b, true); err != nil {
		t.Fatalf("test built a claim that violates the contract: %v", err)
	}
	return string(b)
}
