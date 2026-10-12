package controller

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

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

	"github.com/xamops/automation/internal/policy"
)

// seededWorkload mirrors one Deployment of eks/test-clusters/1x-*.yaml.
type seededWorkload struct {
	ns, name    string
	replicas    int32
	cpu, mem    string // "" = unset
	pendingPods bool   // the pods are Pending/Unschedulable
}

// seededWorkloads: php-apache runs at 5 replicas as in the phase-5 baseline (its HPA
// had scaled it); every other replica count is the manifest value.
var seededWorkloads = []seededWorkload{
	{ns: "team-a", name: "overprovisioned-api", replicas: 2, cpu: "1", mem: "1Gi"},
	{ns: "prod-sim", name: "rightsized-worker", replicas: 2, cpu: "80m", mem: "64Mi"},
	{ns: "prod-sim", name: "throttled-encoder", replicas: 2, cpu: "10m", mem: "32Mi"},
	{ns: "dev-sim", name: "idle-batch-listener", replicas: 3, cpu: "100m", mem: "128Mi"},
	{ns: "dev-sim", name: "no-requests-sidecar-app", replicas: 2},
	{ns: "team-b", name: "php-apache", replicas: 5, cpu: "200m", mem: "64Mi"},
	{ns: "team-b", name: "load-generator", replicas: 1, cpu: "50m", mem: "32Mi"},
	{ns: "team-b", name: "pdb-guarded-web", replicas: 3, cpu: "50m", mem: "48Mi"},
	{ns: "team-a", name: "unschedulable-giant", replicas: 1, cpu: "32", mem: "64Gi", pendingPods: true},
	// Guard rows: a critical namespace and an excluded namespace — never a row.
	{ns: "kube-system", name: "coredns", replicas: 2, cpu: "100m", mem: "70Mi"},
	{ns: "excluded-ns", name: "batch", replicas: 2, cpu: "100m", mem: "64Mi"},
}

func hpaTestObjects(ws []seededWorkload) []runtime.Object {
	var objs []runtime.Object
	for _, w := range ws {
		l := map[string]string{"app": w.name}
		dep := rsDeployment(w.ns, w.name, w.replicas, rsContainer("main", w.cpu, w.mem))
		dep.UID = types.UID("dep-" + w.name)
		ready := w.replicas
		if w.pendingPods {
			ready = 0
		}
		dep.Status.ReadyReplicas = ready
		rsName := w.name + "-5d4f8c7b9"
		rs := &appsv1.ReplicaSet{ObjectMeta: metav1.ObjectMeta{
			Name: rsName, Namespace: w.ns, UID: types.UID("rs-" + w.name), Labels: l,
			OwnerReferences: []metav1.OwnerReference{{Kind: "Deployment", Name: w.name, UID: dep.UID, Controller: boolPtr(true)}},
		}}
		objs = append(objs, dep, rs)
		for i := int32(0); i < w.replicas; i++ {
			pod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{
					Name: rsName + "-" + string(rune('a'+i)) + "bcde", Namespace: w.ns, Labels: l,
					OwnerReferences: []metav1.OwnerReference{{Kind: "ReplicaSet", Name: rsName, UID: rs.UID, Controller: boolPtr(true)}},
				},
				Status: corev1.PodStatus{Phase: corev1.PodRunning},
			}
			if w.pendingPods {
				pod.Status = corev1.PodStatus{Phase: corev1.PodPending, Conditions: []corev1.PodCondition{{
					Type: corev1.PodScheduled, Status: corev1.ConditionFalse, Reason: "Unschedulable"}}}
			}
			objs = append(objs, pod)
		}
	}
	one := int32(1)
	fifty := int32(50)
	objs = append(objs,
		testPDB("team-b", "pdb-guarded-web", intstr.FromInt(3), nil, "pdb-guarded-web"),
		&autoscalingv2.HorizontalPodAutoscaler{
			ObjectMeta: metav1.ObjectMeta{Name: "php-apache", Namespace: "team-b"},
			Spec: autoscalingv2.HorizontalPodAutoscalerSpec{
				ScaleTargetRef: autoscalingv2.CrossVersionObjectReference{APIVersion: "apps/v1", Kind: "Deployment", Name: "php-apache"},
				MinReplicas:    &one, MaxReplicas: 5,
				Metrics: []autoscalingv2.MetricSpec{{Type: autoscalingv2.ResourceMetricSourceType, Resource: &autoscalingv2.ResourceMetricSource{
					Name: corev1.ResourceCPU, Target: autoscalingv2.MetricTarget{Type: autoscalingv2.UtilizationMetricType, AverageUtilization: &fifty}}}},
			},
		},
	)
	return objs
}

func boolPtr(b bool) *bool { return &b }

func testPDB(ns, name string, minAvailable intstr.IntOrString, maxUnavailable *intstr.IntOrString, app string) *policyv1.PodDisruptionBudget {
	pdb := &policyv1.PodDisruptionBudget{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns},
		Spec:       policyv1.PodDisruptionBudgetSpec{Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": app}}},
	}
	if maxUnavailable != nil {
		pdb.Spec.MaxUnavailable = maxUnavailable
	} else {
		pdb.Spec.MinAvailable = &minAvailable
	}
	return pdb
}

// hpaPrometheus answers the (existing, namespace-wide) p90 query; empty=true answers
// every query with an empty vector.
func hpaPrometheus(t *testing.T, empty bool) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if empty {
			_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[]}}`))
			return
		}
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"0.05"]}]}}`))
	}))
	t.Cleanup(srv.Close)
	return srv
}

type hpaWant struct {
	finding               string
	min, max, target      int32
	pdbFloor              int32
	currentHPA            bool
	reasonContains        string
	pdbMinAvailableString string
}

func hpaPolicy() policy.HPAManagerPolicy {
	p := policy.DefaultBundle().HPAManagerPolicy
	p.Enabled = true
	p.ExcludeNamespaces = []string{"excluded-ns"}
	return p
}

func runHPA(t *testing.T, objs []runtime.Object, guards policy.SafetyGuards, dryRun, emptyProm bool) (map[string]HPARecommendation, *fake.Clientset) {
	t.Helper()
	c := fake.NewSimpleClientset(objs...)
	recs, err := NewHPAManager(c, hpaPrometheus(t, emptyProm).URL, dryRun).Analyse(context.Background(), hpaPolicy(), guards, dryRun)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]HPARecommendation{}
	for _, r := range recs {
		out[r.WorkloadName] = r
	}
	return out, c
}

func checkHPA(t *testing.T, got map[string]HPARecommendation, want map[string]hpaWant, dryRun bool) {
	t.Helper()
	if len(got) != len(want) {
		names := []string{}
		for n := range got {
			names = append(names, n)
		}
		t.Errorf("rows for %v, want %d rows", names, len(want))
	}
	for name, w := range want {
		r, ok := got[name]
		if !ok {
			t.Errorf("%s: no row", name)
			continue
		}
		t.Logf("%-24s expected %-24s min=%d max=%d target=%d pdbFloor=%d | actual %-24s min=%d max=%d target=%d pdbFloor=%d dryRun=%v",
			name, w.finding, w.min, w.max, w.target, w.pdbFloor, r.Finding, r.RecommendMin, r.RecommendMax, r.RecommendTarget, r.PDBFloor, r.DryRun)
		if r.Finding != w.finding || r.RecommendMin != w.min || r.RecommendMax != w.max || r.RecommendTarget != w.target ||
			r.PDBFloor != w.pdbFloor || r.CurrentHPA != w.currentHPA || r.PDBMinAvailable != w.pdbMinAvailableString {
			t.Errorf("%s: got %+v, want %+v", name, r, w)
		}
		if w.reasonContains != "" && !strings.Contains(r.Reason, w.reasonContains) {
			t.Errorf("%s: reason %q does not contain %q", name, r.Reason, w.reasonContains)
		}
		if r.DryRun != dryRun {
			t.Errorf("%s: dryRun = %v, want %v", name, r.DryRun, dryRun)
		}
		if r.Applied {
			t.Errorf("%s: applied in an analysis-only controller", name)
		}
		if r.Finding == FindingRecommend && (r.RecommendMin < r.PDBFloor || r.RecommendMax < r.RecommendMin+1) {
			t.Errorf("%s: min %d max %d violates pdbFloor %d / max>=min+1", name, r.RecommendMin, r.RecommendMax, r.PDBFloor)
		}
	}
}

func seededHPAWant() map[string]hpaWant {
	return map[string]hpaWant{
		"unschedulable-giant":     {finding: FindingUnschedulable, reasonContains: "1 pod(s) Pending (Unschedulable)"},
		"load-generator":          {finding: FindingProtectedSingleReplica},
		"overprovisioned-api":     {finding: FindingRecommend, min: 2, max: 4, target: 80},
		"rightsized-worker":       {finding: FindingRecommend, min: 2, max: 4, target: 80},
		"throttled-encoder":       {finding: FindingRecommend, min: 2, max: 4, target: 80},
		"idle-batch-listener":     {finding: FindingRecommend, min: 2, max: 6, target: 80},
		"no-requests-sidecar-app": {finding: FindingRecommend, min: 2, max: 4, target: 80},
		"php-apache":              {finding: FindingRecommend, min: 2, max: 10, target: 80, currentHPA: true},
		// PDB veto: minAvailable 3 -> pdbFloor 4; the baseline proposed min 2.
		"pdb-guarded-web": {finding: FindingRecommend, min: 4, max: 6, target: 80, pdbFloor: 4,
			pdbMinAvailableString: "3", reasonContains: "raised from 2 to pdbFloor 4"},
	}
}

func TestHPAManagerSeededCluster(t *testing.T) {
	got, c := runHPA(t, hpaTestObjects(seededWorkloads), policy.DefaultBundle().SafetyGuards, true, false)
	checkHPA(t, got, seededHPAWant(), true)
	for _, a := range c.Actions() {
		if v := a.GetVerb(); v != "get" && v != "list" && v != "watch" {
			t.Errorf("write verb %s %s", v, a.GetResource().Resource)
		}
	}
}

// D18: a minAvailable-2 PDB on rightsized-worker lifts min to 3 (pdbFloor 3).
func TestHPAManagerPDBVetoRightsizedWorker(t *testing.T) {
	objs := append(hpaTestObjects(seededWorkloads),
		testPDB("prod-sim", "harness-pdb-rightsized-worker", intstr.FromInt(2), nil, "rightsized-worker"))
	got, _ := runHPA(t, objs, policy.DefaultBundle().SafetyGuards, false, false)
	want := seededHPAWant()
	want["rightsized-worker"] = hpaWant{finding: FindingRecommend, min: 3, max: 4, target: 80, pdbFloor: 3,
		pdbMinAvailableString: "2", reasonContains: "raised from 2 to pdbFloor 3"}
	checkHPA(t, got, want, false)
}

// An existing HPA below the PDB floor is flagged (advisory), never rewritten.
func TestHPAManagerExistingHPAPDBConflict(t *testing.T) {
	objs := append(hpaTestObjects(seededWorkloads),
		testPDB("team-b", "harness-pdb-php-apache", intstr.FromInt(2), nil, "php-apache"))
	got, _ := runHPA(t, objs, policy.DefaultBundle().SafetyGuards, true, false)
	r := got["php-apache"]
	if !r.PDBConflict || r.PDBFloor != 3 || r.RecommendMin < 3 || !strings.Contains(r.Reason, "pdbConflict") {
		t.Errorf("php-apache = %+v", r)
	}
}

func TestHPAManagerProtectSingleReplicasFalse(t *testing.T) {
	guards := policy.DefaultBundle().SafetyGuards
	guards.ProtectSingleReplicas = false
	got, _ := runHPA(t, hpaTestObjects(seededWorkloads), guards, true, false)
	want := seededHPAWant()
	want["load-generator"] = hpaWant{finding: FindingRecommend, min: 1, max: 3, target: 80}
	checkHPA(t, got, want, true)
}

func TestHPAManagerMetricsUnavailable(t *testing.T) {
	got, _ := runHPA(t, hpaTestObjects(seededWorkloads), policy.DefaultBundle().SafetyGuards, true, true)
	want := map[string]hpaWant{}
	for name, w := range seededHPAWant() {
		if w.finding == FindingRecommend {
			w = hpaWant{finding: FindingMetricsUnavailable, pdbFloor: w.pdbFloor, currentHPA: w.currentHPA, pdbMinAvailableString: w.pdbMinAvailableString}
		}
		want[name] = w
	}
	checkHPA(t, got, want, true)
}

// StatefulSets are analysed only when not protected; their Pending pods are found
// through the StatefulSet owner reference.
func TestHPAManagerStatefulSetPending(t *testing.T) {
	two := int32(2)
	ss := &appsv1.StatefulSet{
		ObjectMeta: metav1.ObjectMeta{Name: "db", Namespace: "prod-sim", UID: "ss-db"},
		Spec: appsv1.StatefulSetSpec{Replicas: &two, Template: corev1.PodTemplateSpec{
			ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": "db"}},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "db", Resources: corev1.ResourceRequirements{
				Requests: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("100m")}}}}},
		}},
	}
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: "db-1", Namespace: "prod-sim",
			OwnerReferences: []metav1.OwnerReference{{Kind: "StatefulSet", Name: "db", UID: "ss-db", Controller: boolPtr(true)}}},
		Status: corev1.PodStatus{Phase: corev1.PodPending},
	}
	guards := policy.DefaultBundle().SafetyGuards
	got, _ := runHPA(t, []runtime.Object{ss, pod}, guards, true, false)
	if len(got) != 0 {
		t.Errorf("protectStatefulSets=true: rows %v", got)
	}
	guards.ProtectStatefulSets = false
	got, _ = runHPA(t, []runtime.Object{ss, pod}, guards, true, false)
	if r, ok := got["db"]; !ok || r.Finding != FindingUnschedulable || !strings.Contains(r.Reason, "1 pod(s) Pending (Pending)") {
		t.Errorf("db = %+v", got["db"])
	}
}

func TestPDBFloor(t *testing.T) {
	pct := func(s string) intstr.IntOrString { return intstr.FromString(s) }
	ip := func(v intstr.IntOrString) *intstr.IntOrString { return &v }
	cases := []struct {
		name         string
		pdbs         []*policyv1.PodDisruptionBudget
		wantFloor    int32
		wantWritten  string
		wantAdvisory bool
	}{
		{"none", nil, 0, "", false},
		{"minAvailable 3", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.FromInt(3), nil, "x")}, 4, "3", false},
		{"minAvailable 0", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.FromInt(0), nil, "x")}, 1, "0", false},
		{"minAvailable 50%", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", pct("50%"), nil, "x")}, 2, "50%", false},
		{"minAvailable 80%", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", pct("80%"), nil, "x")}, 5, "80%", false},
		{"minAvailable 100%", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", pct("100%"), nil, "x")}, 0, "100%", true},
		{"maxUnavailable 1", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.IntOrString{}, ip(intstr.FromInt(1)), "x")}, 1, "", false},
		{"maxUnavailable 25%", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.IntOrString{}, ip(pct("25%")), "x")}, 1, "", false},
		{"maxUnavailable 0", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.IntOrString{}, ip(intstr.FromInt(0)), "x")}, 0, "", true},
		{"maxUnavailable 0%", []*policyv1.PodDisruptionBudget{testPDB("ns", "a", intstr.IntOrString{}, ip(pct("0%")), "x")}, 0, "", true},
		{"multiple -> max", []*policyv1.PodDisruptionBudget{
			testPDB("ns", "a", intstr.FromInt(2), nil, "x"),
			testPDB("ns", "b", intstr.IntOrString{}, ip(intstr.FromInt(1)), "x"),
			testPDB("ns", "c", pct("50%"), nil, "x"),
		}, 3, "2,50%", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var in []policyv1.PodDisruptionBudget
			for _, p := range tc.pdbs {
				in = append(in, *p)
			}
			floor, written, adv := pdbFloor(in)
			hasAdv := false
			for _, a := range adv {
				if strings.HasPrefix(a, AdvisoryPDBBlocksDisruption) {
					hasAdv = true
				}
			}
			t.Logf("expected floor=%d written=%q advisory=%v | actual floor=%d written=%q advisories=%v",
				tc.wantFloor, tc.wantWritten, tc.wantAdvisory, floor, written, adv)
			if floor != tc.wantFloor || written != tc.wantWritten || hasAdv != tc.wantAdvisory {
				t.Errorf("pdbFloor = (%d,%q,%v), want (%d,%q,advisory=%v)", floor, written, adv, tc.wantFloor, tc.wantWritten, tc.wantAdvisory)
			}
		})
	}
}

// PDB selection uses the full selector, matchExpressions included, and stays in-namespace.
func TestMatchingPDBsFullSelector(t *testing.T) {
	expr := &policyv1.PodDisruptionBudget{
		ObjectMeta: metav1.ObjectMeta{Name: "expr", Namespace: "ns"},
		Spec: policyv1.PodDisruptionBudgetSpec{Selector: &metav1.LabelSelector{MatchExpressions: []metav1.LabelSelectorRequirement{
			{Key: "app", Operator: metav1.LabelSelectorOpIn, Values: []string{"web", "api"}},
			{Key: "tier", Operator: metav1.LabelSelectorOpNotIn, Values: []string{"batch"}},
		}}},
	}
	other := testPDB("other-ns", "x", intstr.FromInt(1), nil, "web")
	all := []policyv1.PodDisruptionBudget{*expr, *other}
	if got := matchingPDBs(all, "ns", map[string]string{"app": "web"}); len(got) != 1 || got[0].Name != "expr" {
		t.Errorf("web: %v", got)
	}
	if got := matchingPDBs(all, "ns", map[string]string{"app": "web", "tier": "batch"}); len(got) != 0 {
		t.Errorf("batch tier matched: %v", got)
	}
}
