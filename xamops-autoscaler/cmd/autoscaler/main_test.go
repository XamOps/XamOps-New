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
	"sync"
	"testing"
	"time"

	autoscalingv2 "k8s.io/api/autoscaling/v2"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
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

	runCycle(context.Background(), cfg, c,
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
	// The evictor reports no action here: every seeded node hosts a DaemonSet pod,
	// which still blocks consolidation until FEAT-005 (fit simulator) lands.
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
