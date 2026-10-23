package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	autoscalingv2 "k8s.io/api/autoscaling/v2"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/client-go/kubernetes/scheme"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
	"github.com/xamops/automation/internal/telemetry"
)

// fixture reads a harness policy file and strips the harness-only _label/_note keys
// (the stub strips them before serving).
func fixture(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "eks", "test-clusters", "harness", "policies", name))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("parse fixture: %v", err)
	}
	delete(m, "_label")
	delete(m, "_note")
	out, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

type captured struct {
	mu      sync.Mutex
	batches map[string][]json.RawMessage
}

func (c *captured) items(collector string) []json.RawMessage {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.batches[collector]
}

// backend serves the policy, captures ingest pushes and answers Prometheus with
// empty vectors.
func backend(t *testing.T, policyBody []byte) (*httptest.Server, *captured) {
	t.Helper()
	return backendWithProm(t, policyBody, nil)
}

// backendWithProm is backend with a Prometheus answer function: prom returns the
// sample value for a query, or "" for an empty vector.
func backendWithProm(t *testing.T, policyBody []byte, prom func(query string) string) (*httptest.Server, *captured) {
	t.Helper()
	cap := &captured{batches: map[string][]json.RawMessage{}}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/agent/autoscaling-policy", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(policyBody)
	})
	mux.HandleFunc("/api/v1/agent/ingest", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var p struct {
			Batches []struct {
				Collector string            `json:"collector"`
				Items     []json.RawMessage `json:"items"`
			} `json:"batches"`
		}
		if err := json.Unmarshal(body, &p); err != nil {
			t.Errorf("ingest payload: %v", err)
		}
		cap.mu.Lock()
		for _, b := range p.Batches {
			cap.batches[b.Collector] = append(cap.batches[b.Collector], b.Items...)
		}
		cap.mu.Unlock()
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/api/v1/query", func(w http.ResponseWriter, r *http.Request) {
		if prom != nil {
			if v := prom(r.URL.Query().Get("query")); v != "" {
				_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"` + v + `"]}]}}`))
				return
			}
		}
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[]}}`))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, cap
}

func ctrl(b bool) *bool { return &b }

func cycleNode(name string, labels map[string]string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: labels, CreationTimestamp: metav1.NewTime(time.Now().Add(-48 * time.Hour))},
		Status: corev1.NodeStatus{
			Capacity:    corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("2"), corev1.ResourceMemory: resource.MustParse("4Gi"), corev1.ResourcePods: resource.MustParse("17")},
			Allocatable: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("1930m"), corev1.ResourceMemory: resource.MustParse("3294Mi"), corev1.ResourcePods: resource.MustParse("17")},
			Conditions:  []corev1.NodeCondition{{Type: corev1.NodeReady, Status: corev1.ConditionTrue}},
		},
	}
}

func cyclePod(ns, name, nodeName, ownerKind, ownerName string, labels map[string]string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: ns, UID: types.UID("uid-" + name), Labels: labels,
			OwnerReferences: []metav1.OwnerReference{{Kind: ownerKind, Name: ownerName, UID: types.UID("uid-" + ownerName), Controller: ctrl(true)}},
		},
		Spec: corev1.PodSpec{NodeName: nodeName, Containers: []corev1.Container{{
			Name: "app",
			Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
				corev1.ResourceCPU: resource.MustParse("100m"), corev1.ResourceMemory: resource.MustParse("128Mi")}},
		}}},
		Status: corev1.PodStatus{Phase: corev1.PodRunning,
			Conditions: []corev1.PodCondition{{Type: corev1.PodReady, Status: corev1.ConditionTrue}}},
	}
}

// seededCluster: 4 nodes (one carries an NTH spot-interruption label, one holds only
// DaemonSet pods so it looks idle), DaemonSet pods everywhere, ReplicaSet pods, a
// PDB and an HPA.
func seededCluster() *fake.Clientset {
	web := map[string]string{"app": "web"}
	two := int32(2)
	objs := []runtime.Object{
		cycleNode("node-1", nil), cycleNode("node-2", nil), cycleNode("node-3", nil),
		cycleNode("node-4", map[string]string{"aws-node-termination-handler/action-reason": "SpotInterruption"}),
		cyclePod("default", "web-1", "node-1", "ReplicaSet", "web-rs", web),
		cyclePod("default", "web-2", "node-2", "ReplicaSet", "web-rs", web),
		cyclePod("team-a", "api-1", "node-4", "ReplicaSet", "api-rs", map[string]string{"app": "api"}),
		&policyv1.PodDisruptionBudget{
			ObjectMeta: metav1.ObjectMeta{Name: "web-pdb", Namespace: "default"},
			Spec:       policyv1.PodDisruptionBudgetSpec{MinAvailable: &intstr.IntOrString{IntVal: 1}, Selector: &metav1.LabelSelector{MatchLabels: web}},
			Status:     policyv1.PodDisruptionBudgetStatus{DisruptionsAllowed: 1},
		},
		&autoscalingv2.HorizontalPodAutoscaler{
			ObjectMeta: metav1.ObjectMeta{Name: "web", Namespace: "default"},
			Spec: autoscalingv2.HorizontalPodAutoscalerSpec{
				ScaleTargetRef: autoscalingv2.CrossVersionObjectReference{Kind: "Deployment", Name: "web", APIVersion: "apps/v1"},
				MinReplicas:    &two, MaxReplicas: 5,
			},
		},
	}
	for i, n := range []string{"node-1", "node-2", "node-3", "node-4"} {
		objs = append(objs, cyclePod("kube-system", "aws-node-"+string(rune('a'+i)), n, "DaemonSet", "aws-node", nil))
	}
	return fake.NewSimpleClientset(objs...)
}

func writeVerbs(c *fake.Clientset) []string {
	var out []string
	for _, a := range c.Actions() {
		switch a.GetVerb() {
		case "get", "list", "watch":
			continue
		}
		out = append(out, a.GetVerb()+" "+a.GetResource().Resource+"/"+a.GetSubresource())
	}
	return out
}

func runTestCycle(t *testing.T, cfg *config.Config, c *fake.Clientset, srvURL string) {
	t.Helper()
	cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID = srvURL, "test-key", "test-cluster", "123456789012"
	cfg.PrometheusURL = srvURL
	cfg.MaxRetries, cfg.RetryDelay = 1, 0
	cfg.DrainTimeoutSeconds = 30
	cfg.PodNamespace, cfg.StateConfigMap = "xamops-system", "xamops-automation-state"

	runCycle(context.Background(), cfg, metrics.New(), c,
		policy.NewClient(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID),
		pusher.New(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID, cfg.MaxRetries, cfg.RetryDelay),
		controller.NewEvictor(c, cfg.DryRun),
		controller.NewRightsizer(c, cfg.PrometheusURL, cfg.DryRun),
		controller.NewRebalancer(c, cfg.DryRun),
		controller.NewHPAManager(c, cfg.PrometheusURL, cfg.DryRun),
		controller.NewVPAManager(c, nil, cfg.DryRun),
		controller.NewNodeOptimizer(c, nil, nil, cfg.PrometheusURL, cfg.DryRun),
	)
}

var outcomeWords = regexp.MustCompile(`(?i)\b(completed|drained|consolidated|applied)\b`)

// 11-dryrun-actions + DRY_RUN=true with every ENABLE_* on: nothing is written, every
// action record is a dry run, no event claims success, the rebalancer is simulated.
func TestRunCycleDryRunAllActionsWritesNothing(t *testing.T) {
	srv, cap := backend(t, fixture(t, "11-dryrun-actions.json"))
	c := seededCluster()
	cfg := &config.Config{DryRun: true, EnableEvictor: true, EnableRightsizer: true, EnableRightsizerApply: true,
		EnableRebalancer: true, EnableHPAManager: true, EnableVPAManager: true, EnableNodeOptimizer: true}
	runTestCycle(t, cfg, c, srv.URL)

	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("DRY_RUN cycle issued writes: %v", w)
	}

	actions := cap.items("autoscaling-actions")
	if len(actions) == 0 {
		t.Fatal("no autoscaling-actions pushed")
	}
	simulatedDrains := 0
	controllers := map[string]bool{}
	for _, raw := range actions {
		var a map[string]interface{}
		if err := json.Unmarshal(raw, &a); err != nil {
			t.Fatal(err)
		}
		if v, ok := a["dryRun"]; !ok || v != true {
			t.Errorf("action without dryRun=true: %s", raw)
		}
		if a["verified"] != false {
			t.Errorf("dry-run action marked verified: %s", raw)
		}
		controllers[a["controller"].(string)] = true
		if a["action"] == "drain" && a["status"] == policy.ActionSimulated {
			simulatedDrains++
		}
	}
	// The rebalancer takes the only budget slot (node-3, DaemonSet pods only); the
	// evictor and the node optimizer record refusals/budget skips.
	for _, want := range []string{policy.ControllerRebalancer, policy.ControllerNodeOptimizer} {
		if !controllers[want] {
			t.Errorf("no action record from %s (got %v)", want, controllers)
		}
	}
	if simulatedDrains > 1 { // maxParallelDrains = 1 in the fixture
		t.Errorf("%d simulated drains in one cycle, budget is 1", simulatedDrains)
	}

	events := cap.items("autoscaling-events")
	if len(events) != len(actions) {
		t.Errorf("%d events for %d actions — every event needs a paired record", len(events), len(actions))
	}
	for _, raw := range events {
		var ev map[string]interface{}
		if err := json.Unmarshal(raw, &ev); err != nil {
			t.Fatal(err)
		}
		if ev["severity"] == "success" {
			t.Errorf("success event in a dry run: %s", raw)
		}
		if outcomeWords.MatchString(ev["message"].(string)) {
			t.Errorf("dry-run event claims an outcome: %s", raw)
		}
		if len(ev) > 8 {
			t.Errorf("event has %d keys (frozen at 8): %s", len(ev), raw)
		}
	}

	rs := cap.items("autoscaling-rebalance-status")
	if len(rs) != 1 {
		t.Fatalf("rebalance-status items = %d, want 1", len(rs))
	}
	var st map[string]interface{}
	_ = json.Unmarshal(rs[0], &st)
	if st["dryRun"] != true || st["status"] != policy.ActionSimulated || st["operationId"] != "gen-1" {
		t.Errorf("rebalance status = %s", rs[0])
	}

	for _, raw := range cap.items("node-drain-progress") {
		var r map[string]interface{}
		_ = json.Unmarshal(raw, &r)
		if r["dryRun"] != true || r["verified"] != false || r["status"] == "drained" {
			t.Errorf("drain report = %s", raw)
		}
	}
	for _, raw := range cap.items("node-optimizer-report") {
		var r map[string]interface{}
		_ = json.Unmarshal(raw, &r)
		if r["dryRun"] != true || r["estimatedSavingsPct"] != nil {
			t.Errorf("node optimizer report = %s", raw)
		}
	}
}

// 13-rebalancer-env-bypass + ENABLE_REBALANCER=false + DRY_RUN=false: the backend's
// running status cannot start the rebalancer (the fail-open baseline row).
func TestRunCycleRebalancerEnvOffIsOff(t *testing.T) {
	srv, cap := backend(t, fixture(t, "13-rebalancer-env-bypass.json"))
	c := seededCluster()
	cfg := &config.Config{DryRun: false, EnableRebalancer: false, EnableEvictor: true, EnableRightsizer: true,
		EnableHPAManager: true, EnableVPAManager: true, EnableNodeOptimizer: true}
	runTestCycle(t, cfg, c, srv.URL)

	if rs := cap.items("autoscaling-rebalance-status"); len(rs) != 0 {
		t.Errorf("rebalance status pushed: %s", rs)
	}
	for _, raw := range cap.items("autoscaling-actions") {
		var a map[string]interface{}
		_ = json.Unmarshal(raw, &a)
		if a["controller"] == policy.ControllerRebalancer {
			t.Errorf("rebalancer action: %s", raw)
		}
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

func cycleDeployment(ns, name string, replicas int32, cpu, mem string) *appsv1.Deployment {
	l := map[string]string{"app": name}
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: l},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: l},
				Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "app", Resources: corev1.ResourceRequirements{
					Requests: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse(mem)}}}}},
			},
		},
		Status: appsv1.DeploymentStatus{ReadyReplicas: replicas},
	}
}

// 11-dryrun-actions + DRY_RUN=true + ENABLE_RIGHTSIZER_APPLY=true (applyMode immediate):
// the rightsizer simulates but never patches, every row is applied=false with a dry-run
// reason, and every HPA row carries dryRun=true.
func TestRunCycleDryRunRightsizerApplyFlagOnWritesNothing(t *testing.T) {
	prom := func(q string) string {
		switch {
		case strings.HasPrefix(q, "quantile_over_time"): // rightsizer CPU p80 (millicores)
			return "0.04"
		case strings.HasPrefix(q, "max_over_time"): // rightsizer memory p100 (bytes)
			return "339968"
		case strings.HasPrefix(q, "quantile(0.9"): // HPA p90 (cores)
			return "0.05"
		}
		return ""
	}
	srv, cap := backendWithProm(t, fixture(t, "11-dryrun-actions.json"), prom)
	c := seededCluster()
	for _, d := range []*appsv1.Deployment{
		cycleDeployment("team-a", "overprovisioned-api", 2, "1", "1Gi"),
		cycleDeployment("dev-sim", "idle-batch-listener", 3, "100m", "512Mi"),
		cycleDeployment("default", "web", 2, "200m", "300Mi"),
	} {
		if err := c.Tracker().Add(d); err != nil {
			t.Fatal(err)
		}
	}
	cfg := &config.Config{DryRun: true, EnableEvictor: true, EnableRightsizer: true, EnableRightsizerApply: true,
		EnableRebalancer: true, EnableHPAManager: true, EnableVPAManager: true, EnableNodeOptimizer: true}
	runTestCycle(t, cfg, c, srv.URL)

	for _, a := range c.Actions() {
		if a.GetVerb() == "patch" && a.GetResource().Resource == "deployments" {
			t.Errorf("deployment patched under DRY_RUN: %v", a)
		}
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("DRY_RUN cycle issued writes: %v", w)
	}

	rows := cap.items("autoscaling-rightsizing")
	if len(rows) != 3 {
		t.Fatalf("rightsizing rows = %d, want 3", len(rows))
	}
	for _, raw := range rows {
		var r map[string]interface{}
		_ = json.Unmarshal(raw, &r)
		if r["applied"] != false || !strings.HasPrefix(r["reason"].(string), "dry-run") {
			t.Errorf("rightsizing row = %s", raw)
		}
		if r["recommendedCpu"] == "0m" {
			t.Errorf("0m CPU: %s", raw)
		}
		if len(r) > 14 {
			t.Errorf("rightsizing row has %d keys (frozen at 14): %s", len(r), raw)
		}
	}

	simulated := 0
	for _, raw := range cap.items("autoscaling-actions") {
		var a map[string]interface{}
		_ = json.Unmarshal(raw, &a)
		if a["controller"] == policy.ControllerRightsizer {
			if a["status"] != policy.ActionSimulated || a["dryRun"] != true || a["verified"] != false {
				t.Errorf("rightsizer action = %s", raw)
			}
			simulated++
		}
	}
	if simulated != 3 {
		t.Errorf("simulated rightsizer actions = %d, want 3", simulated)
	}
	for _, raw := range cap.items("autoscaling-events") {
		var ev map[string]interface{}
		_ = json.Unmarshal(raw, &ev)
		if ev["severity"] == "success" || outcomeWords.MatchString(ev["message"].(string)) {
			t.Errorf("dry-run event claims an outcome: %s", raw)
		}
	}

	hpa := cap.items("hpa-recommendations")
	if len(hpa) != 3 {
		t.Fatalf("hpa rows = %d, want 3", len(hpa))
	}
	for _, raw := range hpa {
		var r map[string]interface{}
		_ = json.Unmarshal(raw, &r)
		if r["dryRun"] != true || r["applied"] != false || r["finding"] == "" {
			t.Errorf("hpa row = %s", raw)
		}
	}
}

// Verified live rightsizer patches are summarised in one event with the agreed wording;
// simulated ones keep their own honest event.
func TestAddRightsizerSummarisesVerifiedPatches(t *testing.T) {
	live := func(target, workload string) policy.ActionRecord {
		r := telemetry.NewAction(policy.ControllerRightsizer, "patch-requests", target, false)
		telemetry.SetDetail(&r, "workload", workload)
		return telemetry.Finish(r, policy.ActionSucceeded, true, "")
	}
	sim := telemetry.Finish(telemetry.NewAction(policy.ControllerRightsizer, "patch-requests", "ns/c/x", true),
		policy.ActionSimulated, false, "dry-run")
	acts := &cycleActions{}
	acts.addRightsizer([]policy.ActionRecord{live("ns/a/x", "ns/a"), live("ns/a/y", "ns/a"), live("ns/b/x", "ns/b"), sim})

	if len(acts.records) != 5 || len(acts.events) != 2 {
		t.Fatalf("records=%d events=%d, want 5/2", len(acts.records), len(acts.events))
	}
	ids := map[string]bool{}
	for _, r := range acts.records {
		ids[r.ID] = true
	}
	var summary policy.AutoscalingEvent
	for _, ev := range acts.events {
		if !ids[ev.ID] {
			t.Errorf("event %s has no paired record", ev.ID)
		}
		if ev.Severity == "success" {
			summary = ev
		}
	}
	if summary.Message != "Rightsizer patched 2 Deployment template(s) — rollout pending" {
		t.Errorf("summary = %+v", summary)
	}
}

type drainRecord struct {
	Controller, Action, Target, Status, Reason string
	DryRun                                     bool
}

func drainRecords(t *testing.T, cap *captured) []drainRecord {
	t.Helper()
	var out []drainRecord
	for _, raw := range cap.items("autoscaling-actions") {
		var a struct {
			Controller, Action, Target, Status, Reason string
			DryRun                                     bool `json:"dryRun"`
		}
		if err := json.Unmarshal(raw, &a); err != nil {
			t.Fatal(err)
		}
		if a.Action == "drain" {
			out = append(out, drainRecord{a.Controller, a.Action, a.Target, a.Status, a.Reason, a.DryRun})
		}
	}
	return out
}

// 11-dryrun-actions + DRY_RUN=true on the seeded cluster, where the fit simulator
// finds exactly one drainable node (node-3: DaemonSet pods only; the other nodes'
// ReplicaSet pods have no ReplicaSet object, so protectSingleReplicas blocks them as
// replicas-unknown): the cycle reports exactly one simulated drain, of node-3, and
// every other drain candidate is refused with plan reasons or skipped for budget.
func TestRunCycleDryRunOneDrainableNodeOneSimulatedDrain(t *testing.T) {
	srv, cap := backend(t, fixture(t, "11-dryrun-actions.json"))
	c := seededCluster()
	cfg := &config.Config{DryRun: true, EnableEvictor: true, EnableRebalancer: true, EnableNodeOptimizer: true}
	runTestCycle(t, cfg, c, srv.URL)
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("DRY_RUN cycle issued writes: %v", w)
	}

	var simulated []drainRecord
	for _, r := range drainRecords(t, cap) {
		if !r.DryRun {
			t.Errorf("drain record without dryRun: %+v", r)
		}
		switch r.Status {
		case policy.ActionSimulated:
			simulated = append(simulated, r)
		case policy.ActionRefused:
			if !strings.Contains(r.Reason, "blocked ") && !strings.Contains(r.Reason, "strand ") {
				t.Errorf("refusal without plan reasons: %+v", r)
			}
		case policy.ActionSkipped:
			if !strings.Contains(r.Reason, "budget exhausted") {
				t.Errorf("skip without budget reason: %+v", r)
			}
		default:
			t.Errorf("unexpected drain status: %+v", r)
		}
	}
	if len(simulated) != 1 || simulated[0].Target != "node/node-3" {
		t.Fatalf("simulated drains = %+v, want exactly one of node/node-3", simulated)
	}

	cands := cap.items("autoscaling-evictor-candidates")
	if len(cands) != 4 {
		t.Fatalf("evictor candidates = %d, want 4 (a plan for every node)", len(cands))
	}
	for _, raw := range cands {
		var c struct {
			NodeName string `json:"nodeName"`
			CanEvict bool   `json:"canEvict"`
			Fit      *struct {
				Drainable     bool `json:"drainable"`
				ExcludedCount int  `json:"excludedCount"`
			} `json:"fit"`
		}
		_ = json.Unmarshal(raw, &c)
		if c.Fit == nil || c.Fit.ExcludedCount != 1 || c.Fit.Drainable != (c.NodeName == "node-3") || c.CanEvict != c.Fit.Drainable {
			t.Errorf("candidate = %s", raw)
		}
	}
}

// loadSection4 decodes the fitsim section-4 testdata List into runtime objects.
func loadSection4(t *testing.T) []runtime.Object {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "internal", "fitsim", "testdata", "section4.json"))
	if err != nil {
		t.Fatal(err)
	}
	var list struct {
		Items []json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatal(err)
	}
	var objs []runtime.Object
	for _, it := range list.Items {
		obj, _, err := scheme.Codecs.UniversalDeserializer().Decode(it, nil, nil)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		objs = append(objs, obj)
	}
	return objs
}

// 11-dryrun-actions + DRY_RUN=true on the section-4-shaped fixture (PDB-0 node,
// protected-namespace node, two fragmentation strands): zero writes, zero simulated
// drains, refusals carry the simulator's reasons.
func TestRunCycleDryRunSection4FixtureRefusesWithReasons(t *testing.T) {
	srv, cap := backend(t, fixture(t, "11-dryrun-actions.json"))
	c := fake.NewSimpleClientset(loadSection4(t)...)
	cfg := &config.Config{DryRun: true, EnableEvictor: true, EnableRightsizer: true, EnableRightsizerApply: true,
		EnableRebalancer: true, EnableHPAManager: true, EnableVPAManager: true, EnableNodeOptimizer: true}
	runTestCycle(t, cfg, c, srv.URL)
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("DRY_RUN cycle issued writes: %v", w)
	}

	recs := drainRecords(t, cap)
	if len(recs) == 0 {
		t.Fatal("no drain records")
	}
	all := ""
	for _, r := range recs {
		if r.Status == policy.ActionSimulated {
			t.Errorf("simulated drain on a fixture with no drainable node: %+v", r)
		}
		if r.Status == policy.ActionRefused && r.Reason == "" {
			t.Errorf("refusal without a reason: %+v", r)
		}
		all += r.Reason + "\n"
	}
	for _, want := range []string{
		"blocked team-b/pdb-guarded-web-1: pdb-no-disruptions:team-b/pdb-guarded-web",
		"blocked xamops-system/xamops-automation-1: critical-namespace; self-namespace; single-replica",
		"strand team-a/overprovisioned-api-1: no other node fits it",
		"strand team-a/overprovisioned-api-2: no other node fits it",
	} {
		if !strings.Contains(all, want) {
			t.Errorf("no refusal mentions %q:\n%s", want, all)
		}
	}

	rs := cap.items("autoscaling-rebalance-status")
	if len(rs) != 1 {
		t.Fatalf("rebalance-status items = %d", len(rs))
	}
	var st map[string]interface{}
	_ = json.Unmarshal(rs[0], &st)
	if st["status"] != policy.ActionSimulated || st["nodesSimulated"] != float64(0) || !strings.Contains(st["reason"].(string), "strand") {
		t.Errorf("rebalance status = %s", rs[0])
	}
	for _, raw := range cap.items("autoscaling-evictor-candidates") {
		var c map[string]interface{}
		_ = json.Unmarshal(raw, &c)
		fit, _ := c["fit"].(map[string]interface{})
		if c["canEvict"] != false || fit == nil || fit["drainable"] != false {
			t.Errorf("candidate = %s", raw)
		}
	}
}

type drainReport struct {
	NodeName    string `json:"nodeName"`
	Status      string `json:"status"`
	Reason      string `json:"reason"`
	DryRun      bool   `json:"dryRun"`
	Verified    bool   `json:"verified"`
	OperationID string `json:"operationId"`
}

func drainReports(t *testing.T, cap *captured) map[string]drainReport {
	t.Helper()
	out := map[string]drainReport{}
	for _, raw := range cap.items("node-drain-progress") {
		var r drainReport
		if err := json.Unmarshal(raw, &r); err != nil {
			t.Fatal(err)
		}
		if _, dup := out[r.NodeName]; dup {
			t.Errorf("two node-drain-progress reports for %s", r.NodeName)
		}
		out[r.NodeName] = r
	}
	return out
}

// The evictor's and the rebalancer's drains reach collector node-drain-progress
// (plan.md section 4), simulated and live, with dryRun, verified and operationId. On
// the seeded cluster node-3 (DaemonSet pods only) is the one drainable node; a live
// drain of it cordons, evicts nothing and verifies.
func TestRunCycleEvictorAndRebalancerDrainsReachNodeDrainProgress(t *testing.T) {
	for _, tc := range []struct {
		name       string
		policy     string
		cfg        config.Config
		opPrefix   string
		wantStatus string
		wantOthers map[string]string // node -> status of the other candidates
	}{
		{"evictor simulated", "41-evictor-positive.json", config.Config{DryRun: true, EnableEvictor: true},
			"evictor-", "simulated", nil},
		{"evictor live", "41-evictor-positive.json", config.Config{DryRun: false, EnableEvictor: true},
			"evictor-", "drained", nil},
		{"rebalancer simulated", "17-rebalancer-running-opid.json", config.Config{DryRun: true, EnableRebalancer: true},
			"pass2-reb-1", "simulated", map[string]string{"node-1": "refused", "node-2": "refused", "node-4": "skipped"}},
		{"rebalancer live", "17-rebalancer-running-opid.json", config.Config{DryRun: false, EnableRebalancer: true},
			"pass2-reb-1", "drained", map[string]string{"node-1": "refused", "node-2": "refused", "node-4": "skipped"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv, cap := backend(t, fixture(t, tc.policy))
			c := seededCluster()
			cfg := tc.cfg
			runTestCycle(t, &cfg, c, srv.URL)

			reps := drainReports(t, cap)
			r, ok := reps["node-3"]
			if !ok {
				t.Fatalf("no node-drain-progress report for node-3: %v", cap.items("node-drain-progress"))
			}
			live := !tc.cfg.DryRun
			if r.Status != tc.wantStatus || r.DryRun == live || r.Verified != live || !strings.HasPrefix(r.OperationID, tc.opPrefix) {
				t.Errorf("node-3 report = %+v", r)
			}
			for node, st := range tc.wantOthers {
				o, ok := reps[node]
				if !ok || o.Status != st || o.DryRun == live || o.Verified || o.Reason == "" {
					t.Errorf("%s report = %+v (ok=%v), want status %s with a reason", node, o, ok, st)
				}
				if st == "skipped" && !strings.Contains(o.Reason, "on its own: not drainable") {
					t.Errorf("%s skipped without its own plan verdict: %q", node, o.Reason)
				}
			}
			if len(reps) != 1+len(tc.wantOthers) {
				t.Errorf("reports = %+v", reps)
			}
			if !live {
				if w := writeVerbs(c); len(w) != 0 {
					t.Errorf("DRY_RUN cycle issued writes: %v", w)
				}
			}
		})
	}
}
