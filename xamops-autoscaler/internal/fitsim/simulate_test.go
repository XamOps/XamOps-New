package fitsim

import (
	"encoding/json"
	"math/rand"
	"reflect"
	"testing"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type movePair struct{ pod, to string }

func moves(p Plan) []movePair {
	out := []movePair{}
	for _, m := range p.Moves {
		out = append(out, movePair{m.Pod, m.To})
	}
	return out
}

type expectation struct {
	drainable bool
	excluded  int
	refused   []Finding
	blocked   []Finding
	strands   []string
	moves     []movePair
	pdbChecks []PDBCheck
}

func checkPlan(t *testing.T, label string, got Plan, want expectation) {
	t.Helper()
	if want.refused == nil {
		want.refused = []Finding{}
	}
	if want.blocked == nil {
		want.blocked = []Finding{}
	}
	if want.strands == nil {
		want.strands = []string{}
	}
	if want.moves == nil {
		want.moves = []movePair{}
	}
	if want.pdbChecks == nil {
		want.pdbChecks = []PDBCheck{}
	}
	if got.Drainable != want.drainable {
		t.Errorf("%s: drainable = %v, want %v (%s)", label, got.Drainable, want.drainable, got.Summary)
	}
	if got.ExcludedCount != want.excluded {
		t.Errorf("%s: excluded = %d, want %d (%v)", label, got.ExcludedCount, want.excluded, got.Excluded)
	}
	if !reflect.DeepEqual(got.Refused, want.refused) {
		t.Errorf("%s: refused = %+v, want %+v", label, got.Refused, want.refused)
	}
	if !reflect.DeepEqual(got.Blocked, want.blocked) {
		t.Errorf("%s: blocked = %+v, want %+v", label, got.Blocked, want.blocked)
	}
	if !reflect.DeepEqual(got.Strands, want.strands) {
		t.Errorf("%s: strands = %v, want %v", label, got.Strands, want.strands)
	}
	if !reflect.DeepEqual(moves(got), want.moves) {
		t.Errorf("%s: moves = %v, want %v", label, moves(got), want.moves)
	}
	if !reflect.DeepEqual(got.PDBChecks, want.pdbChecks) {
		t.Errorf("%s: pdbChecks = %+v, want %+v", label, got.PDBChecks, want.pdbChecks)
	}
}

// section4Expectations are the exact per-node plans of section4Fixture (also recorded
// in FEAT-005 findings; the Python oracle must produce the same).
func section4Expectations() map[string]expectation {
	webPDB := "pdb-no-disruptions:team-b/pdb-guarded-web"
	return map[string]expectation{
		"node-a": {
			excluded: 5,
			blocked: []Finding{
				{Pod: "team-b/pdb-guarded-web-1", Reason: webPDB},
				{Pod: "team-b/pdb-guarded-web-2", Reason: webPDB},
			},
			moves: []movePair{
				{"team-c/app-1", "node-b"}, {"team-c/app-2", "node-b"},
				{"team-b/pdb-guarded-web-1", "node-b"}, {"team-b/pdb-guarded-web-2", "node-c"},
			},
			pdbChecks: []PDBCheck{{PDB: "team-b/pdb-guarded-web", MatchedInSet: 2, DisruptionsAllowed: 0}},
		},
		"node-b": {
			excluded: 5,
			blocked: []Finding{
				{Pod: "monitoring/prometheus-server-1", Reason: "critical-namespace; single-replica"},
				{Pod: "xamops-system/xamops-automation-1", Reason: "critical-namespace; self-namespace; single-replica"},
			},
			moves: []movePair{
				{"team-d/worker-1", "node-a"}, {"team-d/worker-2", "node-a"},
				{"monitoring/prometheus-server-1", "node-a"}, {"xamops-system/xamops-automation-1", "node-c"},
			},
		},
		"node-c": {
			excluded: 5,
			strands:  []string{"team-a/overprovisioned-api-1"},
			moves:    []movePair{{"team-e/cache-1", "node-a"}, {"team-e/cache-2", "node-a"}},
		},
		"node-d": {
			excluded: 5,
			strands:  []string{"team-a/overprovisioned-api-2"},
			moves:    []movePair{{"team-e/cache-3", "node-a"}, {"team-e/cache-4", "node-a"}},
		},
	}
}

func TestSection4FixtureNoNodeDrainable(t *testing.T) {
	s := section4Fixture().snapshot()
	free := map[string]int64{"node-a": 680, "node-b": 685, "node-c": 50, "node-d": 50}
	var aggregate int64
	for name, want := range free {
		n := s.Node(name)
		if got := n.Free().MilliCPU; got != want {
			t.Errorf("fixture: %s free cpu = %dm, want %dm", name, got, want)
		}
		aggregate += want
	}
	// Fragmentation: the 1000m pod strands although node-c's movable 1330m fits the
	// 1415m free on a, b, d in aggregate.
	if aggregate-free["node-c"] < 1330 {
		t.Fatalf("fixture: aggregate free elsewhere must exceed node-c's movable requests")
	}
	for node, want := range section4Expectations() {
		checkPlan(t, node, SimulateDrain(s, []string{node}, testOptions()), want)
	}
}

func TestPositiveFixtureExactlyOneDrainable(t *testing.T) {
	s := positiveFixture().snapshot()
	var drainable []string
	for _, n := range []string{"node-a", "node-b", "node-c", "node-d"} {
		if SimulateDrain(s, []string{n}, testOptions()).Drainable {
			drainable = append(drainable, n)
		}
	}
	if !reflect.DeepEqual(drainable, []string{"node-d"}) {
		t.Fatalf("drainable = %v, want [node-d]", drainable)
	}
	checkPlan(t, "node-d", SimulateDrain(s, []string{"node-d"}, testOptions()), expectation{
		drainable: true, excluded: 5,
		moves: []movePair{
			{"team-a/overprovisioned-api-2", "node-a"}, {"team-e/cache-3", "node-b"}, {"team-e/cache-4", "node-b"},
		},
	})
	p := SimulateDrain(s, []string{"node-d"}, testOptions())
	if m := p.Moves[0]; m.CPU != 600 || m.OwnerKind != "ReplicaSet" || m.OwnerUID != "uid-team-a-overprovisioned-api-rs" || m.UID == "" {
		t.Errorf("move identity = %+v", m)
	}
}

// Shuffled inputs give byte-identical plans.
func TestDeterminismUnderShuffledInput(t *testing.T) {
	base := section4Fixture()
	want := map[string]string{}
	for _, n := range []string{"node-a", "node-b", "node-c", "node-d"} {
		b, _ := json.Marshal(SimulateDrain(base.snapshot(), []string{n}, testOptions()))
		want[n] = string(b)
	}
	r := rand.New(rand.NewSource(42))
	for i := 0; i < 20; i++ {
		o := section4Fixture()
		r.Shuffle(len(o.nodes), func(a, b int) { o.nodes[a], o.nodes[b] = o.nodes[b], o.nodes[a] })
		r.Shuffle(len(o.pods), func(a, b int) { o.pods[a], o.pods[b] = o.pods[b], o.pods[a] })
		r.Shuffle(len(o.rss), func(a, b int) { o.rss[a], o.rss[b] = o.rss[b], o.rss[a] })
		s := o.snapshot()
		for n, w := range want {
			b, _ := json.Marshal(SimulateDrain(s, []string{n}, testOptions()))
			if string(b) != w {
				t.Fatalf("shuffle %d: plan of %s differs:\n%s\nwant\n%s", i, n, b, w)
			}
		}
	}
	// The drain-set order does not matter either.
	s := base.snapshot()
	a, _ := json.Marshal(SimulateDrain(s, []string{"node-c", "node-a"}, testOptions()))
	b, _ := json.Marshal(SimulateDrain(s, []string{"node-a", "node-c"}, testOptions()))
	if string(a) != string(b) {
		t.Errorf("set order changed the plan:\n%s\n%s", a, b)
	}
}

func TestDaemonSetOnlyAndMirrorNodesAreDrainable(t *testing.T) {
	o := &objects{}
	o.node("ds-only", "2", "4Gi", 17)
	o.dsPods("ds-only", 3, "100m", "64Mi")
	o.node("mirror", "2", "4Gi", 17)
	m := o.pod("kube-system", "kube-apiserver-mirror", "mirror", "Node", "mirror", "250m", "256Mi", nil)
	m.Annotations = map[string]string{MirrorPodAnnotation: "abc"}
	o.node("empty", "2", "4Gi", 17)
	s := o.snapshot()
	checkPlan(t, "ds-only", SimulateDrain(s, []string{"ds-only"}, testOptions()), expectation{drainable: true, excluded: 3})
	checkPlan(t, "mirror", SimulateDrain(s, []string{"mirror"}, testOptions()), expectation{drainable: true, excluded: 1})
	checkPlan(t, "empty", SimulateDrain(s, []string{"empty"}, testOptions()), expectation{drainable: true})
	if p := SimulateDrain(s, nil, testOptions()); p.Drainable {
		t.Error("an empty set must not be drainable")
	}
	if p := SimulateDrain(s, []string{"nope"}, testOptions()); p.Drainable || len(p.Errors) != 1 {
		t.Errorf("unknown node: %+v", p)
	}
}

// CPU and memory fit, but every target's 17 pod slots are taken: the pod strands.
func TestPodSlotsBind(t *testing.T) {
	o := &objects{}
	o.rs("apps", "rs", 3)
	o.node("src", "2", "4Gi", 17)
	o.pod("apps", "mover", "src", "ReplicaSet", "rs", "10m", "16Mi", nil)
	o.node("full", "4", "8Gi", 17)
	for i := 0; i < 17; i++ {
		o.pod("apps", "filler-"+string(rune('a'+i)), "full", "ReplicaSet", "rs", "1m", "1Mi", nil)
	}
	s := o.snapshot()
	if f := s.Node("full").Free(); f.MilliCPU < 3000 || f.Pods != 0 {
		t.Fatalf("fixture: full free = %+v", f)
	}
	checkPlan(t, "src", SimulateDrain(s, []string{"src"}, testOptions()), expectation{strands: []string{"apps/mover"}})
}

// Cordoned and NotReady nodes are never targets; terminal and unbound pods do not
// count; terminating pods do.
func TestTargetsAndBoundPods(t *testing.T) {
	o := &objects{}
	o.rs("apps", "rs", 3)
	o.node("src", "2", "4Gi", 17)
	o.pod("apps", "mover", "src", "ReplicaSet", "rs", "500m", "64Mi", nil)
	o.node("a-cordoned", "4", "8Gi", 17).Spec.Unschedulable = true
	o.node("b-notready", "4", "8Gi", 17).Status.Conditions[0].Status = corev1.ConditionFalse
	o.node("c-target", "1", "4Gi", 17)
	o.pod("apps", "done", "c-target", "ReplicaSet", "rs", "900m", "64Mi", nil).Status.Phase = corev1.PodSucceeded
	o.pod("apps", "failed", "c-target", "ReplicaSet", "rs", "900m", "64Mi", nil).Status.Phase = corev1.PodFailed
	o.pod("apps", "pending", "", "ReplicaSet", "rs", "900m", "64Mi", nil).Status.Phase = corev1.PodPending
	now := metav1.Now()
	o.pod("apps", "terminating", "c-target", "ReplicaSet", "rs", "400m", "64Mi", nil).DeletionTimestamp = &now
	s := o.snapshot()
	checkPlan(t, "fits", SimulateDrain(s, []string{"src"}, testOptions()), expectation{
		drainable: true, moves: []movePair{{"apps/mover", "c-target"}}})

	// A bigger terminating pod leaves too little room: strand.
	o.pods[len(o.pods)-1].Spec.Containers[0].Resources.Requests[corev1.ResourceCPU] = resource.MustParse("600m")
	checkPlan(t, "terminating counts", SimulateDrain(o.snapshot(), []string{"src"}, testOptions()), expectation{
		strands: []string{"apps/mover"}})

	// WithoutPods drops the terminating pod: the mover fits again.
	s = o.snapshot().WithoutPods(map[string]bool{"uid-apps-terminating": true})
	checkPlan(t, "without", SimulateDrain(s, []string{"src"}, testOptions()), expectation{
		drainable: true, moves: []movePair{{"apps/mover", "c-target"}}})
}

func TestEffectiveRequests(t *testing.T) {
	always := corev1.ContainerRestartPolicyAlways
	c := func(name, cpu, mem string) corev1.Container {
		return corev1.Container{Name: name, Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
			corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse(mem)}}}
	}
	sidecar := func(name, cpu, mem string) corev1.Container {
		x := c(name, cpu, mem)
		x.RestartPolicy = &always
		return x
	}
	mi := int64(1 << 20)
	for _, tc := range []struct {
		name     string
		spec     corev1.PodSpec
		status   corev1.PodStatus
		cpu, mem int64
	}{
		{"containers sum", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi"), c("b", "250m", "32Mi")}}, corev1.PodStatus{}, 350, 96 * mi},
		{"no requests", corev1.PodSpec{Containers: []corev1.Container{{Name: "a"}}}, corev1.PodStatus{}, 0, 0},
		{"sidecar adds", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")},
			InitContainers: []corev1.Container{sidecar("s", "50m", "16Mi")}}, corev1.PodStatus{}, 150, 80 * mi},
		{"big init wins", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")},
			InitContainers: []corev1.Container{c("i", "500m", "32Mi")}}, corev1.PodStatus{}, 500, 64 * mi},
		{"init plus earlier sidecars", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")},
			InitContainers: []corev1.Container{sidecar("s1", "50m", "16Mi"), c("i", "300m", "100Mi"), sidecar("s2", "70m", "8Mi")}},
			corev1.PodStatus{}, 350, 116 * mi}, // cpu max(100+120, 300+50); mem max(64+24, 100+16)
		{"overhead", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")},
			Overhead: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("10m"), corev1.ResourceMemory: resource.MustParse("20Mi")}},
			corev1.PodStatus{}, 110, 84 * mi},
		{"allocatedResources above request", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")}},
			corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{Name: "a", AllocatedResources: corev1.ResourceList{
				corev1.ResourceCPU: resource.MustParse("300m"), corev1.ResourceMemory: resource.MustParse("32Mi")}}}}, 300, 64 * mi},
		{"allocatedResources of another container ignored", corev1.PodSpec{Containers: []corev1.Container{c("a", "100m", "64Mi")}},
			corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{Name: "zz", AllocatedResources: corev1.ResourceList{
				corev1.ResourceCPU: resource.MustParse("900m")}}}}, 100, 64 * mi},
	} {
		p := &corev1.Pod{Spec: tc.spec, Status: tc.status}
		cpu, mem := EffectiveRequests(p)
		if cpu != tc.cpu || mem != tc.mem {
			t.Errorf("%s: eff = %dm/%d, want %dm/%d", tc.name, cpu, mem, tc.cpu, tc.mem)
		}
	}
}

// Two drainable candidates: capacity 1 selects the first in caller order; capacity 2
// selects both only when their union still fits.
func TestPlanDrainSet(t *testing.T) {
	build := func(cpu string) *Snapshot {
		o := &objects{}
		o.rs("apps", "rs", 3)
		for _, n := range []string{"x", "y", "z"} {
			o.node(n, "1000m", "4Gi", 17)
		}
		o.pod("apps", "px", "x", "ReplicaSet", "rs", cpu, "64Mi", nil)
		o.pod("apps", "py", "y", "ReplicaSet", "rs", cpu, "64Mi", nil)
		return o.snapshot()
	}
	opts := testOptions()

	s := build("300m") // union {x,y}: 600m onto z (1000m) fits
	for _, n := range []string{"x", "y"} {
		if !SimulateDrain(s, []string{n}, opts).Drainable {
			t.Fatalf("fixture: %s must be drainable alone", n)
		}
	}
	sel, plans := PlanDrainSet(s, []string{"y", "x"}, 1, opts)
	if !reflect.DeepEqual(sel, []string{"y"}) || len(plans) != 1 {
		t.Errorf("capacity 1: selected %v plans %d, want [y] 1", sel, len(plans))
	}
	sel, plans = PlanDrainSet(s, []string{"x", "y"}, 2, opts)
	if !reflect.DeepEqual(sel, []string{"x", "y"}) || !plans["y"].Drainable || !reflect.DeepEqual(plans["y"].Nodes, []string{"x", "y"}) {
		t.Errorf("capacity 2 fitting union: selected %v plan[y] %+v", sel, plans["y"])
	}

	s = build("600m") // each alone fits (onto z), the union does not (1200m onto z)
	sel, plans = PlanDrainSet(s, []string{"x", "y", "x"}, 2, opts)
	if !reflect.DeepEqual(sel, []string{"x"}) {
		t.Errorf("capacity 2 non-fitting union: selected %v, want [x]", sel)
	}
	if p := plans["y"]; p.Drainable || len(p.Strands) != 1 {
		t.Errorf("plan of {x,y} = %+v", p)
	}
	if sel, plans = PlanDrainSet(s, []string{"x"}, 0, opts); len(sel) != 0 || len(plans) != 0 {
		t.Errorf("capacity 0: %v %v", sel, plans)
	}
}
