package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

func optNode(name string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: map[string]string{"node.kubernetes.io/instance-type": "t3.medium"}},
		Status: corev1.NodeStatus{
			Capacity:    corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("2"), corev1.ResourceMemory: resource.MustParse("4Gi")},
			Allocatable: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("1000m"), corev1.ResourceMemory: resource.MustParse("3Gi")},
		},
	}
}

func optPod(name, nodeName, cpu string) *corev1.Pod {
	p := evictablePod(name, nodeName)
	p.Spec.Containers[0].Resources.Requests[corev1.ResourceCPU] = resource.MustParse(cpu)
	return p
}

// livePrometheus answers the node CPU/memory queries; the live CPU ordering flips
// on every call so a selection based on live CPU% would change between calls.
func livePrometheus(t *testing.T) *httptest.Server {
	var calls int32
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("query")
		vals := map[string]string{"n-a": "50", "n-b": "50", "n-c": "50"}
		if strings.Contains(q, "node_cpu_seconds_total") {
			if atomic.AddInt32(&calls, 1)%2 == 1 {
				vals = map[string]string{"n-a": "10", "n-b": "60", "n-c": "30"}
			} else {
				vals = map[string]string{"n-a": "60", "n-b": "10", "n-c": "30"}
			}
		}
		var rows []string
		for n, v := range vals {
			rows = append(rows, fmt.Sprintf(`{"metric":{"node":%q},"value":[1,%q]}`, n, v))
		}
		fmt.Fprintf(w, `{"status":"success","data":{"resultType":"vector","result":[%s]}}`, strings.Join(rows, ","))
	}))
}

func optCluster() *fake.Clientset {
	return fake.NewSimpleClientset(
		optNode("n-a"), optNode("n-b"), optNode("n-c"),
		optPod("a1", "n-a", "500m"), // n-a requests 50%
		optPod("b1", "n-b", "100m"), // n-b requests 10%
		optPod("c1", "n-c", "100m"), // n-c requests 10% (tie with n-b -> name)
	)
}

func TestNodeOptimizerReportHasNoFabricatedNumbers(t *testing.T) {
	prom := livePrometheus(t)
	defer prom.Close()
	c := optCluster()
	n := NewNodeOptimizer(c, nil, nil, prom.URL, true)
	report, err := n.Analyse(context.Background(), policy.DefaultBundle().SafetyGuards)
	if err != nil {
		t.Fatal(err)
	}
	if len(report.SpotCandidates) != 3 {
		t.Fatalf("spot candidates = %+v", report.SpotCandidates)
	}
	b, err := json.Marshal(report)
	if err != nil {
		t.Fatal(err)
	}
	js := string(b)
	for _, want := range []string{
		`"savingsPct":null`, `"interruptible":null`, `"estimatedSavingsPct":null`,
		`"savingsStatus":"unknown"`, `"interruptibilityStatus":"unknown"`, `"estimatedSavingsStatus":"unknown"`,
		`"savingsReason":"` + NoPricingReason + `"`, `"interruptibilityReason":"` + InterruptibilityReason + `"`,
		`"dryRun":true`,
	} {
		if !strings.Contains(js, want) {
			t.Errorf("report JSON lacks %s\n%s", want, js)
		}
	}
	if report.SpotCandidates[0].RequestsCPUPct != 10 {
		t.Errorf("requestsCpuPct = %v, want 10", report.SpotCandidates[0].RequestsCPUPct)
	}
	assertOnlyReads(t, c)
}

func TestNodeOptimizerSelectionIgnoresLiveCPU(t *testing.T) {
	prom := livePrometheus(t)
	defer prom.Close()
	c := optCluster()
	n := NewNodeOptimizer(c, nil, nil, prom.URL, true)

	r1, err := n.Analyse(context.Background(), policy.DefaultBundle().SafetyGuards)
	if err != nil {
		t.Fatal(err)
	}
	r2, err := n.Analyse(context.Background(), policy.DefaultBundle().SafetyGuards)
	if err != nil {
		t.Fatal(err)
	}
	if r1.SpotCandidates[0].CPUUsagePct == r2.SpotCandidates[0].CPUUsagePct &&
		r1.SpotCandidates[2].CPUUsagePct == r2.SpotCandidates[2].CPUUsagePct {
		t.Fatal("fixture error: live CPU% must differ between the two calls")
	}
	s1 := n.SelectOnDemandNodesForDrain(r1, 100, 3)
	s2 := n.SelectOnDemandNodesForDrain(r2, 100, 3)
	want := []string{"n-b", "n-c", "n-a"}
	if !reflect.DeepEqual(s1, want) || !reflect.DeepEqual(s2, want) {
		t.Errorf("selection = %v / %v, want %v both times", s1, s2, want)
	}
	if got := n.SelectOnDemandNodesForDrain(r1, 100, 1); !reflect.DeepEqual(got, []string{"n-b"}) {
		t.Errorf("maxDrains 1 = %v", got)
	}
}

// With the interim planner every node action path refuses and writes nothing,
// including the spot-interruption path (no PDB override, no "evicting anyway").
func TestNodeOptimizerActionsWriteNothingUnderUnavailablePlanner(t *testing.T) {
	ctx := context.Background()
	c := optCluster()
	n := NewNodeOptimizer(c, nil, nil, "", false)
	exec := drain.NewExecutor(c, drain.UnavailablePlanner{}, "xamops-system")

	for name, out := range map[string]drain.Outcome{
		"cordon":       n.CordonIdleNode(ctx, "n-a", gate.Live, drain.NewBudget(1, nil), exec),
		"idle-drain":   n.DrainForSpotConversion(ctx, "idle-drain", "n-a", gate.Live, drain.NewBudget(1, nil), exec),
		"spot-ratio":   n.DrainForSpotConversion(ctx, "spot-ratio-drain", "n-b", gate.Live, drain.NewBudget(1, nil), exec),
		"interruption": n.HandleSpotInterruption(ctx, "n-c", gate.Live, drain.NewBudget(1, nil), exec),
		"simulated":    n.DrainForSpotConversion(ctx, "idle-drain", "n-a", gate.Simulate, drain.NewBudget(1, nil), exec),
	} {
		if out.Status != policy.ActionRefused {
			t.Errorf("%s: status = %s, want refused", name, out.Status)
		}
		if len(out.Actions) != 1 || out.Actions[0].Reason != drain.UnavailableReason || out.Actions[0].Verified {
			t.Errorf("%s: actions = %+v", name, out.Actions)
		}
		if len(out.Reports) != 1 || out.Reports[0].Status != drain.StatusRefused {
			t.Errorf("%s: reports = %+v", name, out.Reports)
		}
	}
	if rec := n.PatchKarpenterSpotRatio(ctx, 50, gate.Live); rec.Status != policy.ActionRefused {
		t.Errorf("karpenter without a dynamic client: %+v", rec)
	}
	assertOnlyReads(t, c)
	for _, name := range []string{"n-a", "n-b", "n-c"} {
		node, _ := c.CoreV1().Nodes().Get(ctx, name, metav1.GetOptions{})
		if node.Spec.Unschedulable {
			t.Errorf("%s was cordoned", name)
		}
	}
}

// fitOptCluster: four Ready 1000m nodes. n-a holds a 500m movable pod, n-b a
// kube-system pod (critical namespace), n-c a 950m pod that fits nowhere else
// (most free elsewhere is 900m), n-d a 400m movable pod.
func fitOptCluster() *fake.Clientset {
	nodes := []*corev1.Node{optNode("n-a"), optNode("n-b"), optNode("n-c"), optNode("n-d")}
	for _, n := range nodes {
		n.Status.Allocatable[corev1.ResourcePods] = resource.MustParse("17")
		n.Status.Conditions = []corev1.NodeCondition{{Type: corev1.NodeReady, Status: corev1.ConditionTrue}}
	}
	sys := optPod("dns", "n-b", "100m")
	sys.Namespace = "kube-system"
	return fake.NewSimpleClientset(nodes[0], nodes[1], nodes[2], nodes[3], demoRS(),
		optPod("a1", "n-a", "500m"), sys, optPod("c1", "n-c", "950m"), optPod("d1", "n-d", "400m"))
}

// Spot interruption goes through the simulator: a node whose plan is not drainable is
// refused (reported, not drained) even in Live, and a drainable one only within the
// shared budget.
func TestNodeOptimizerInterruptionUsesSimulatorAndBudget(t *testing.T) {
	ctx := context.Background()
	c := fitOptCluster()
	n := NewNodeOptimizer(c, nil, nil, "", false)
	exec := fitExec(c)

	res := n.HandleSpotInterruptions(ctx, []string{"n-b", "n-c"}, gate.Live, drain.NewBudget(1, nil), exec)
	if res.Set != nil || len(res.Others) != 2 {
		t.Fatalf("result = %+v", res)
	}
	for i, want := range []string{"blocked kube-system/dns: critical-namespace", "strand default/c1"} {
		o := res.Others[i]
		if o.Status != policy.ActionRefused || !strings.Contains(o.Actions[0].Reason, want) || o.Actions[0].DryRun {
			t.Errorf("other %d = %s %q", i, o.Status, o.Actions[0].Reason)
		}
		if o.Actions[0].Details["trigger"] != "spot-interruption" {
			t.Errorf("trigger = %v", o.Actions[0].Details["trigger"])
		}
	}
	assertOnlyReads(t, c)

	// Budget already used by an earlier controller: a drainable interrupted node is
	// skipped, not drained.
	b := drain.NewBudget(1, nil)
	b.TryReserve(policy.ControllerRebalancer, "n-d")
	res = n.HandleSpotInterruptions(ctx, []string{"n-a"}, gate.Live, b, exec)
	if res.Set != nil || len(res.Others) != 1 || res.Others[0].Status != policy.ActionSkipped {
		t.Fatalf("budget-exhausted interruption = %+v", res)
	}
	assertOnlyReads(t, c)

	// DRY_RUN: the drainable node is simulated only.
	out := n.HandleSpotInterruption(ctx, "n-a", gate.Simulate, drain.NewBudget(1, nil), exec)
	if out.Status != policy.ActionSimulated || !out.Actions[0].DryRun || out.Actions[0].Details["fitChecked"] != true {
		t.Errorf("simulated interruption = %s %+v", out.Status, out.Actions)
	}
	assertOnlyReads(t, c)
}

// Spot-ratio drains: candidates in requests order go through PlanDrainSet; only
// drainable nodes are selected, at most `needed` and within the budget.
func TestNodeOptimizerSpotRatioSelectsThroughSimulator(t *testing.T) {
	c := fitOptCluster()
	n := NewNodeOptimizer(c, nil, nil, "", true)
	report := &NodeOptimizerReport{TotalNodes: 4, SpotCandidates: []SpotCandidateInfo{
		{NodeName: "n-c", RequestsCPUPct: 90}, {NodeName: "n-b", RequestsCPUPct: 10},
		{NodeName: "n-a", RequestsCPUPct: 50}, {NodeName: "n-d", RequestsCPUPct: 40},
	}}
	cands, needed := n.SpotRatioDrainCandidates(report, 50)
	if strings.Join(cands, ",") != "n-b,n-d,n-a,n-c" || needed != 2 {
		t.Fatalf("candidates = %v needed = %d", cands, needed)
	}
	res := n.DrainNodes(context.Background(), "spot-ratio-drain", cands, needed, gate.Simulate, drain.NewBudget(3, nil), fitExec(c))
	// n-b blocked; n-d alone fits (onto n-a); {n-d,n-a}: a1 (500m) and d1 (400m) both
	// land on n-b (900m free); needed=2 is reached, so n-c is not considered.
	if res.Set == nil || res.Set.Status != policy.ActionSimulated {
		t.Fatalf("set = %+v others = %+v", res.Set, res.Others)
	}
	if strings.Join(res.Selected, ",") != "n-d,n-a" {
		t.Errorf("selected = %v, want [n-d n-a]", res.Selected)
	}
	if len(res.Others) != 2 || res.Others[0].Status != policy.ActionRefused || res.Others[1].Status != policy.ActionSkipped {
		t.Errorf("others = %+v", res.Others)
	}
	assertOnlyReads(t, c)
}

// staticPrometheus answers every node query with vals (node -> value).
func staticPrometheus(vals map[string]string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var rows []string
		for n, v := range vals {
			rows = append(rows, fmt.Sprintf(`{"metric":{"node":%q},"value":[1,%q]}`, n, v))
		}
		fmt.Fprintf(w, `{"status":"success","data":{"resultType":"vector","result":[%s]}}`, strings.Join(rows, ","))
	}))
}

// F1: a node XamOps already drained (cordoned + drain-state=drained) is never a
// drain, spot-ratio or interruption candidate again; an operator uncordon makes it
// eligible again.
func TestAnalyseSkipsAlreadyDrainedNodes(t *testing.T) {
	const interruptLabel = "aws-node-termination-handler/action-reason"
	cases := []struct {
		name          string
		unschedulable bool
		annotations   map[string]string
		interrupted   bool
		prom          map[string]string // nil: no Prometheus, CPU/mem 0
		wantIdle      bool
		wantRecommend bool
		wantDrained   bool
		wantSpot      bool
		wantInterrupt bool
	}{
		{name: "fresh idle node", wantIdle: true, wantRecommend: true},
		{name: "cordoned and drained by xamops", unschedulable: true,
			annotations: map[string]string{drain.AnnotationState: drain.StateDrained},
			wantIdle:    true, wantDrained: true},
		{name: "operator uncordoned a drained node", unschedulable: false,
			annotations: map[string]string{drain.AnnotationState: drain.StateDrained, drain.AnnotationCordonedBy: drain.CordonedByXamops},
			wantIdle:    true, wantRecommend: true},
		{name: "drained node with a spot interruption notice", unschedulable: true, interrupted: true,
			annotations: map[string]string{drain.AnnotationState: drain.StateDrained},
			wantIdle:    true, wantDrained: true},
		{name: "fresh node with a spot interruption notice", interrupted: true,
			wantIdle: true, wantRecommend: true, wantInterrupt: true},
		{name: "drained busy on-demand node", unschedulable: true,
			annotations: map[string]string{drain.AnnotationState: drain.StateDrained},
			prom:        map[string]string{"n-x": "30"}, wantIdle: true, wantDrained: true},
		{name: "fresh busy on-demand node", prom: map[string]string{"n-x": "30"}, wantIdle: true, wantSpot: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			n := optNode("n-x")
			n.Spec.Unschedulable = tc.unschedulable
			n.Annotations = tc.annotations
			if tc.interrupted {
				n.Labels[interruptLabel] = "SpotInterruption"
			}
			// A second, never-drained interrupted node must stay in InterruptedNodes.
			other := optNode("n-other")
			other.Labels[interruptLabel] = "SpotInterruption"
			c := fake.NewSimpleClientset(n, other, daemonSetPod("ds-x", "n-x"))
			promURL := ""
			if tc.prom != nil {
				prom := staticPrometheus(tc.prom)
				defer prom.Close()
				promURL = prom.URL
			}
			report, err := NewNodeOptimizer(c, nil, nil, promURL, true).Analyse(context.Background(), policy.DefaultBundle().SafetyGuards)
			if err != nil {
				t.Fatal(err)
			}
			var idle *IdleNodeInfo
			for i := range report.IdleNodes {
				if report.IdleNodes[i].NodeName == "n-x" {
					idle = &report.IdleNodes[i]
				}
			}
			if (idle != nil) != tc.wantIdle {
				t.Fatalf("idle listed = %v, want %v (%+v)", idle != nil, tc.wantIdle, report.IdleNodes)
			}
			if idle != nil && (idle.RecommendDrain != tc.wantRecommend || idle.AlreadyDrained != tc.wantDrained) {
				t.Errorf("recommendDrain=%v alreadyDrained=%v, want %v/%v", idle.RecommendDrain, idle.AlreadyDrained, tc.wantRecommend, tc.wantDrained)
			}
			inSpot := false
			for _, s := range report.SpotCandidates {
				inSpot = inSpot || s.NodeName == "n-x"
			}
			if inSpot != tc.wantSpot {
				t.Errorf("spot candidate = %v, want %v", inSpot, tc.wantSpot)
			}
			inInterrupt, otherInterrupt := false, false
			for _, name := range report.InterruptedNodes {
				inInterrupt = inInterrupt || name == "n-x"
				otherInterrupt = otherInterrupt || name == "n-other"
			}
			if inInterrupt != tc.wantInterrupt || !otherInterrupt {
				t.Errorf("interrupted = %v, want n-x=%v and n-other present", report.InterruptedNodes, tc.wantInterrupt)
			}
			assertOnlyReads(t, c)
		})
	}
}
