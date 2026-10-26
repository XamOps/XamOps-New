package drain

import (
	"context"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

var pdbGVR = policyv1.SchemeGroupVersion.WithResource("poddisruptionbudgets")

func fitNode(name, cpu string) *corev1.Node {
	return &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{Name: name},
		Status: corev1.NodeStatus{
			Allocatable: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(cpu),
				corev1.ResourceMemory: resource.MustParse("4Gi"), corev1.ResourcePods: resource.MustParse("17")},
			Conditions: []corev1.NodeCondition{{Type: corev1.NodeReady, Status: corev1.ConditionTrue}},
		},
	}
}

func fitPod(ns, name, uid, nodeName, cpu string) *corev1.Pod {
	p := rsPod(ns, name, uid, nodeName)
	p.Labels = map[string]string{"app": "web"}
	p.Spec.Containers = []corev1.Container{{Name: "app", Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
		corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse("64Mi")}}}}
	return p
}

func webRS() *appsv1.ReplicaSet {
	three := int32(3)
	return &appsv1.ReplicaSet{ObjectMeta: metav1.ObjectMeta{Namespace: "default", Name: "web-rs", UID: "rs-uid"},
		Spec: appsv1.ReplicaSetSpec{Replicas: &three}}
}

func webPDB(allowed int32) *policyv1.PodDisruptionBudget {
	one := intstr.FromInt(1)
	return &policyv1.PodDisruptionBudget{
		ObjectMeta: metav1.ObjectMeta{Namespace: "default", Name: "web-pdb", Generation: 1},
		Spec:       policyv1.PodDisruptionBudgetSpec{MinAvailable: &one, Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "web"}}},
		Status:     policyv1.PodDisruptionBudgetStatus{DisruptionsAllowed: allowed, ObservedGeneration: 1},
	}
}

func fitOptions() fitsim.Options {
	return fitsim.Options{Guards: policy.SafetyGuards{
		ProtectSingleReplicas: true, ProtectStatefulSets: true, ProtectCriticalNamespaces: true,
		CriticalNamespaces: []string{"kube-system"},
	}, SelfNamespace: "xamops-system"}
}

// fitCluster: node-a (to drain) holds web-1 (300m), web-2 (200m) and a DaemonSet pod;
// node-b (1000m) is the only target.
func fitCluster(extra ...runtime.Object) *fake.Clientset {
	objs := []runtime.Object{
		fitNode("node-a", "1000m"), fitNode("node-b", "1000m"), webRS(),
		fitPod("default", "web-1", "uid-web-1", "node-a", "300m"),
		fitPod("default", "web-2", "uid-web-2", "node-a", "200m"),
		dsPod("aws-node-a", "node-a"),
	}
	return fake.NewSimpleClientset(append(objs, extra...)...)
}

// fitEvictionReactor deletes the evicted pod and creates a same-size replacement with
// a new UID Running+Ready on node-b; after(i) runs after the i-th eviction (1-based).
func fitEvictionReactor(c *fake.Clientset, after func(i int)) *int32 {
	var n int32
	c.PrependReactor("create", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if action.GetSubresource() != "eviction" {
			return false, nil, nil
		}
		ev := action.(k8stesting.CreateAction).GetObject().(*policyv1.Eviction)
		obj, err := c.Tracker().Get(podsGVR, ev.Namespace, ev.Name)
		if err != nil {
			return true, nil, err
		}
		old := obj.(*corev1.Pod)
		if err := c.Tracker().Delete(podsGVR, ev.Namespace, ev.Name); err != nil {
			return true, nil, err
		}
		i := atomic.AddInt32(&n, 1)
		repl := fitPod(old.Namespace, fmt.Sprintf("web-new-%d", i), fmt.Sprintf("uid-new-%d", i), "node-b",
			old.Spec.Containers[0].Resources.Requests.Cpu().String())
		if err := c.Tracker().Create(podsGVR, repl, old.Namespace); err != nil {
			return true, nil, err
		}
		if after != nil {
			after(int(i))
		}
		return true, nil, nil
	})
	return &n
}

func evictions(c *fake.Clientset) int {
	n := 0
	for _, a := range c.Actions() {
		if a.GetVerb() == "create" && a.GetSubresource() == "eviction" {
			n++
		}
	}
	return n
}

func fitExecutor(c *fake.Clientset) *Executor {
	return fastExecutor(c, NewFitPlanner(c, nil, fitOptions()))
}

func TestFitPlannerLiveDrainReplansBeforeEveryEviction(t *testing.T) {
	c := fitCluster()
	fitEvictionReactor(c, nil)
	out := fitExecutor(c).Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionSucceeded || !out.Verified() {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if evictions(c) != 2 || len(out.Evicted) != 2 || out.Evicted[0] != "default/web-1" {
		t.Errorf("evictions = %d evicted = %v (largest first)", evictions(c), out.Evicted)
	}
	// One re-plan before each of the 2 evictions plus the final one that finds no move left.
	if got := out.Actions[0].Details["replans"]; got != 3 {
		t.Errorf("replans = %v, want 3", got)
	}
	if out.Plan == nil || !out.Plan.FitChecked || out.Actions[0].Details["fitChecked"] != true {
		t.Errorf("plan not fit-checked: %+v", out.Plan)
	}
	assertNoDelete(t, c)
}

// A strand appears between the two evictions (a hog pod takes node-b's room): the
// re-plan before the second eviction aborts and uncordons.
func TestFitPlannerStrandMidDrainAbortsBeforeSecondEviction(t *testing.T) {
	c := fitCluster()
	fitEvictionReactor(c, func(i int) {
		if i == 1 {
			hog := fitPod("other", "hog", "uid-hog", "node-b", "600m")
			hog.OwnerReferences[0].UID = "hog-rs"
			_ = c.Tracker().Create(podsGVR, hog, "other")
		}
	})
	out := fitExecutor(c).Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionAborted {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if r := out.Actions[0].Reason; !strings.Contains(r, "re-plan before eviction 2") || !strings.Contains(r, "strand default/web-2") {
		t.Errorf("reason = %q", r)
	}
	if evictions(c) != 1 {
		t.Errorf("evictions = %d, want 1 (abort before the second)", evictions(c))
	}
	n := getNode(t, c, "node-a")
	if n.Spec.Unschedulable || n.Annotations[AnnotationState] != StateAborted {
		t.Errorf("node-a not restored: unschedulable=%v annotations=%v", n.Spec.Unschedulable, n.Annotations)
	}
	assertNoDelete(t, c)
}

func setPDBAllowed(t *testing.T, c *fake.Clientset, allowed int32) {
	obj, err := c.Tracker().Get(pdbGVR, "default", "web-pdb")
	if err != nil {
		t.Error(err)
		return
	}
	b := obj.(*policyv1.PodDisruptionBudget).DeepCopy()
	b.Status.DisruptionsAllowed = allowed
	if err := c.Tracker().Update(pdbGVR, b, "default"); err != nil {
		t.Error(err)
	}
}

// disruptionsAllowed drops to 0 after the first eviction and stays there: the
// executor waits up to PDBRetry, then aborts before the second eviction.
func TestFitPlannerPDBDropsToZeroWaitsThenAborts(t *testing.T) {
	c := fitCluster(webPDB(1))
	fitEvictionReactor(c, func(i int) { setPDBAllowed(t, c, 0) })
	e := fitExecutor(c)
	e.PDBRetry = 120 * time.Millisecond
	start := time.Now()
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionAborted {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if r := out.Actions[0].Reason; !strings.Contains(r, "pdb-no-disruptions:default/web-pdb") || !strings.Contains(r, "waited") {
		t.Errorf("reason = %q", r)
	}
	if time.Since(start) < e.PDBRetry {
		t.Errorf("aborted after %s, before waiting PDBRetry %s", time.Since(start), e.PDBRetry)
	}
	if evictions(c) != 1 {
		t.Errorf("evictions = %d, want 1", evictions(c))
	}
	if getNode(t, c, "node-a").Spec.Unschedulable {
		t.Error("node-a must be uncordoned")
	}
}

// disruptionsAllowed drops to 0 and recovers while the executor waits: the drain
// completes.
func TestFitPlannerPDBRecoversWhileWaiting(t *testing.T) {
	c := fitCluster(webPDB(1))
	fitEvictionReactor(c, func(i int) {
		setPDBAllowed(t, c, 0)
		go func() {
			time.Sleep(40 * time.Millisecond)
			setPDBAllowed(t, c, 1)
		}()
	})
	e := fitExecutor(c)
	e.PDBRetry = 2 * time.Second
	out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, gate.Live, NewBudget(1, nil))
	if out.Status != policy.ActionSucceeded || !out.Verified() {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if evictions(c) != 2 {
		t.Errorf("evictions = %d, want 2", evictions(c))
	}
}

// The fit simulator blocks a pod of the autoscaler's own namespace even when the
// policy protects no critical namespace; the executor refuses without writing.
func TestFitPlannerSelfNamespaceRefused(t *testing.T) {
	self := fitPod("xamops-system", "xamops-automation-1", "uid-self", "node-a", "50m")
	self.OwnerReferences[0].UID = "self-rs"
	c := fitCluster(self)
	opts := fitOptions()
	opts.Guards = policy.SafetyGuards{}
	e := fastExecutor(c, NewFitPlanner(c, nil, opts))
	for _, mode := range []gate.Mode{gate.Live, gate.Simulate} {
		out := e.Drain(context.Background(), "evictor", "op", []string{"node-a"}, mode, NewBudget(1, nil))
		if out.Status != policy.ActionRefused || !strings.Contains(out.Actions[0].Reason, "blocked xamops-system/xamops-automation-1: self-namespace") {
			t.Errorf("%s: status = %s reason = %q", mode, out.Status, out.Actions[0].Reason)
		}
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

// Simulate uses the real plan: drainable -> simulated with fitChecked=true and the
// planned moves; a strand -> refused with the strand in the reason. Never a write.
func TestFitPlannerSimulate(t *testing.T) {
	c := fitCluster()
	out := fitExecutor(c).Drain(context.Background(), "rebalancer", "gen-1", []string{"node-a"}, gate.Simulate, NewBudget(1, nil))
	if out.Status != policy.ActionSimulated || out.Actions[0].Details["fitChecked"] != true || out.Reports[0].PodsTotal != 2 {
		t.Fatalf("status = %s details = %v report = %+v", out.Status, out.Actions[0].Details, out.Reports[0])
	}
	big := fitPod("default", "web-3", "uid-web-3", "node-a", "600m")
	c2 := fitCluster(big)
	out = fitExecutor(c2).Drain(context.Background(), "rebalancer", "gen-1", []string{"node-a"}, gate.Simulate, NewBudget(1, nil))
	if out.Status != policy.ActionRefused || !strings.Contains(out.Actions[0].Reason, "strand default/") {
		t.Fatalf("status = %s reason = %q", out.Status, out.Actions[0].Reason)
	}
	if w := append(verbs(c), verbs(c2)...); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

// DrainCandidates: candidate order is kept, the first drainable one takes the only
// budget slot, the undrainable one is refused with its reasons, the rest is skipped.
func TestDrainCandidatesSelectsWithinBudget(t *testing.T) {
	c := fake.NewSimpleClientset(
		fitNode("n-blocked", "1000m"), fitNode("n-ok-1", "1000m"), fitNode("n-ok-2", "1000m"), fitNode("n-target", "4000m"),
		webRS(),
		fitPod("kube-system", "dns", "uid-dns", "n-blocked", "100m"),
		fitPod("default", "w1", "uid-w1", "n-ok-1", "100m"),
		fitPod("default", "w2", "uid-w2", "n-ok-2", "100m"),
	)
	e := fitExecutor(c)
	b := NewBudget(1, nil)
	res := e.DrainCandidates(context.Background(), "node-optimizer", "op", []string{"n-blocked", "n-ok-1", "n-ok-2"}, 0, gate.Simulate, b)
	if len(res.Selected) != 1 || res.Selected[0] != "n-ok-1" || res.Set == nil || res.Set.Status != policy.ActionSimulated {
		t.Fatalf("selected = %v set = %+v", res.Selected, res.Set)
	}
	if len(res.Others) != 2 {
		t.Fatalf("others = %+v", res.Others)
	}
	if o := res.Others[0]; o.Status != policy.ActionRefused || !strings.Contains(o.Actions[0].Reason, "critical-namespace") || !o.Actions[0].DryRun {
		t.Errorf("n-blocked = %s %q", o.Status, o.Actions[0].Reason)
	}
	// Skipped for budget, but the record still carries the node's own verdict.
	if o := res.Others[1]; o.Status != policy.ActionSkipped || !strings.Contains(o.Actions[0].Reason, "budget exhausted") ||
		!strings.HasSuffix(o.Actions[0].Reason, "; on its own: drainable") {
		t.Errorf("n-ok-2 = %s %q", o.Status, o.Actions[0].Reason)
	}
	if b.Remaining() != 0 {
		t.Errorf("the simulated drain must keep its budget slot")
	}
	if got := len(res.Actions()); got != 3 {
		t.Errorf("actions = %d", got)
	}

	// Capacity 3: both drainable nodes form one set, drained as one call.
	res = e.DrainCandidates(context.Background(), "node-optimizer", "op", []string{"n-blocked", "n-ok-1", "n-ok-2"}, 0, gate.Simulate, NewBudget(3, nil))
	if len(res.Selected) != 2 || res.Set == nil || res.Set.Actions[0].Target != "nodes/n-ok-1,n-ok-2" {
		t.Fatalf("selected = %v set = %+v", res.Selected, res.Set)
	}
	// maxNodes caps the selection below the budget.
	res = e.DrainCandidates(context.Background(), "node-optimizer", "op", []string{"n-ok-1", "n-ok-2"}, 1, gate.Simulate, NewBudget(3, nil))
	if len(res.Selected) != 1 || len(res.Others) != 1 || !strings.Contains(res.Others[0].Actions[0].Reason, "selection limit") ||
		!strings.Contains(res.Others[0].Actions[0].Reason, "on its own: drainable") {
		t.Errorf("maxNodes: selected = %v others = %+v", res.Selected, res.Others)
	}
	// Off: every candidate skipped by the executor's own gate check.
	res = e.DrainCandidates(context.Background(), "node-optimizer", "op", []string{"n-ok-1"}, 0, gate.Off, NewBudget(1, nil))
	if len(res.Others) != 1 || res.Others[0].Status != policy.ActionSkipped || res.Set != nil {
		t.Errorf("off = %+v", res)
	}
	if w := verbs(c); len(w) != 0 {
		t.Errorf("writes: %v", w)
	}
}

// FromFit marks PDB-only blockers retryable and nothing else.
func TestFromFitRetryable(t *testing.T) {
	for _, tc := range []struct {
		plan fitsim.Plan
		want bool
	}{
		{fitsim.Plan{Nodes: []string{"a"}, Blocked: []fitsim.Finding{{Pod: "x/p", Reason: "pdb-no-disruptions:x/b"}}}, true},
		{fitsim.Plan{Nodes: []string{"a"}, Blocked: []fitsim.Finding{{Pod: "x/p", Reason: "pdb-stale:x/b; pdb-no-disruptions:x/b"}}}, true},
		{fitsim.Plan{Nodes: []string{"a"}, Blocked: []fitsim.Finding{{Pod: "x/p", Reason: "critical-namespace; pdb-no-disruptions:x/b"}}}, false},
		{fitsim.Plan{Nodes: []string{"a"}, Blocked: []fitsim.Finding{{Pod: "x/p", Reason: "pdb-no-disruptions:x/b"}}, Strands: []string{"x/q"}}, false},
		{fitsim.Plan{Nodes: []string{"a"}, Strands: []string{"x/q"}}, false},
		{fitsim.Plan{Nodes: []string{"a"}, Drainable: true}, false},
	} {
		if got := FromFit(tc.plan).Retryable; got != tc.want {
			t.Errorf("%+v: retryable = %v, want %v", tc.plan, got, tc.want)
		}
	}
	p := FromFit(fitsim.Plan{Nodes: []string{"a"}, Drainable: true, Moves: []fitsim.Move{
		{Pod: "x/p", Namespace: "x", Name: "p", UID: "u", OwnerUID: "o", From: "a", To: "b"},
		{Pod: "x/q", Namespace: "x", Name: "q", Blocked: true},
	}})
	if !p.FitChecked || len(p.Moves) != 1 || p.Moves[0].UID != "u" || p.Moves[0].OwnerUID != "o" {
		t.Errorf("moves = %+v", p.Moves)
	}
}
