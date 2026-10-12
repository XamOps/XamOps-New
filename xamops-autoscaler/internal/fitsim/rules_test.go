package fitsim

import (
	"sort"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/xamops/automation/internal/policy"
)

// rulesOptions: xamops-system is the self namespace but NOT in the critical list, so
// the self-namespace rule is visible on its own.
func rulesOptions() Options {
	return Options{
		Guards: policy.SafetyGuards{
			RespectPodDisruptionBudgets: true, ProtectSingleReplicas: true, ProtectStatefulSets: true,
			ProtectCriticalNamespaces: true, CriticalNamespaces: []string{"kube-system", "monitoring"},
		},
		SelfNamespace: "xamops-system",
	}
}

// rulesFixture exercises every rule of plan.md section 3 with single-node drains.
// Targets: t1-tainted (1000m, taint dedicated=batch:NoSchedule, zone z1, tier 5,
// disk ssd) and t2-plain (1000m, PreferNoSchedule taint (ignored), zone z2, tier 2,
// rank abc, hosting a 100m terminating pod plus a Succeeded and an unbound pod that do
// not count). t3-cordoned and t4-notready are never targets. Every source node s-*
// has one pod slot, filled by its own pod, so sources are never targets.
func rulesFixture() *objects {
	o := &objects{}
	t1 := o.node("t1-tainted", "1000m", "2Gi", 10)
	t1.Labels = map[string]string{"zone": "z1", "tier": "5", "disk": "ssd"}
	t1.Spec.Taints = []corev1.Taint{{Key: "dedicated", Value: "batch", Effect: corev1.TaintEffectNoSchedule}}
	t2 := o.node("t2-plain", "1000m", "2Gi", 10)
	t2.Labels = map[string]string{"zone": "z2", "tier": "2", "rank": "abc"}
	t2.Spec.Taints = []corev1.Taint{{Key: "soft", Value: "yes", Effect: corev1.TaintEffectPreferNoSchedule}}
	o.node("t3-cordoned", "8", "16Gi", 50).Spec.Unschedulable = true
	o.node("t4-notready", "8", "16Gi", 50).Status.Conditions[0].Status = corev1.ConditionFalse

	o.rs("apps", "rules-rs", 3)
	o.rs("apps", "single-rs", 1)
	o.rs("kube-system", "sys-rs", 2)
	o.rs("xamops-system", "self-rs", 2)
	o.sts("apps", "db", 3)

	now := metav1.NewTime(time.Date(2026, 10, 10, 0, 0, 0, 0, time.UTC)) // fixed: testdata must be stable
	o.pod("apps", "terminating", "t2-plain", "ReplicaSet", "rules-rs", "100m", "64Mi", nil).DeletionTimestamp = &now
	o.pod("apps", "succeeded", "t2-plain", "ReplicaSet", "rules-rs", "900m", "64Mi", nil).Status.Phase = corev1.PodSucceeded
	o.pod("apps", "unbound", "", "ReplicaSet", "rules-rs", "900m", "64Mi", nil).Status.Phase = corev1.PodPending

	src := func(rule string) string {
		name := "s-" + rule
		o.node(name, "4", "8Gi", 1)
		return name
	}
	// add puts one pod named rule on its own source node.
	add := func(rule, ns, ownerKind, ownerName, cpu, mem string) *corev1.Pod {
		return o.pod(ns, rule, src(rule), ownerKind, ownerName, cpu, mem, map[string]string{"rule": rule})
	}
	app := func(rule, cpu string) *corev1.Pod { return add(rule, "apps", "ReplicaSet", "rules-rs", cpu, "64Mi") }
	tolAll := []corev1.Toleration{{Operator: corev1.TolerationOpExists}}
	aff := func(rule string, terms ...corev1.NodeSelectorTerm) {
		p := app(rule, "100m")
		p.Spec.Tolerations = tolAll
		p.Spec.Affinity = &corev1.Affinity{NodeAffinity: &corev1.NodeAffinity{
			RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{NodeSelectorTerms: terms}}}
	}
	expr := func(key string, op corev1.NodeSelectorOperator, vals ...string) corev1.NodeSelectorTerm {
		return corev1.NodeSelectorTerm{MatchExpressions: []corev1.NodeSelectorRequirement{{Key: key, Operator: op, Values: vals}}}
	}
	field := func(op corev1.NodeSelectorOperator, vals ...string) corev1.NodeSelectorTerm {
		return corev1.NodeSelectorTerm{MatchFields: []corev1.NodeSelectorRequirement{{Key: "metadata.name", Operator: op, Values: vals}}}
	}
	cpuMem := func(cpu, mem string) corev1.ResourceRequirements {
		return corev1.ResourceRequirements{Requests: corev1.ResourceList{
			corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse(mem)}}
	}

	// ── placement: taints, nodeSelector, node affinity, capacity ──
	app("plain", "100m")
	app("tolerates", "100m").Spec.Tolerations = []corev1.Toleration{{Key: "dedicated", Operator: corev1.TolerationOpEqual, Value: "batch", Effect: corev1.TaintEffectNoSchedule}}
	app("tol-exists", "100m").Spec.Tolerations = tolAll
	app("tol-wrong-value", "100m").Spec.Tolerations = []corev1.Toleration{{Key: "dedicated", Value: "other", Effect: corev1.TaintEffectNoSchedule}}
	app("sel-z1", "100m").Spec.NodeSelector = map[string]string{"zone": "z1"}
	p := app("sel-z1-tol", "100m")
	p.Spec.NodeSelector, p.Spec.Tolerations = map[string]string{"zone": "z1"}, tolAll
	app("sel-missing", "100m").Spec.NodeSelector = map[string]string{"gpu": "yes"}
	aff("aff-in", expr("zone", corev1.NodeSelectorOpIn, "z2"))
	aff("aff-notin", expr("zone", corev1.NodeSelectorOpNotIn, "z1"))
	aff("aff-notin-absent", expr("gpu", corev1.NodeSelectorOpNotIn, "yes"))
	aff("aff-exists", expr("disk", corev1.NodeSelectorOpExists))
	aff("aff-dne", expr("disk", corev1.NodeSelectorOpDoesNotExist))
	aff("aff-gt", expr("tier", corev1.NodeSelectorOpGt, "3"))
	aff("aff-lt", expr("tier", corev1.NodeSelectorOpLt, "3"))
	aff("aff-gt-none", expr("tier", corev1.NodeSelectorOpGt, "9"))
	aff("aff-gt-nonint", expr("rank", corev1.NodeSelectorOpGt, "1"))
	aff("aff-field-in", field(corev1.NodeSelectorOpIn, "t2-plain"))
	aff("aff-field-notin", field(corev1.NodeSelectorOpNotIn, "t1-tainted"))
	aff("aff-or", expr("zone", corev1.NodeSelectorOpIn, "nope"), expr("zone", corev1.NodeSelectorOpIn, "z2"))
	aff("aff-empty-term", corev1.NodeSelectorTerm{})
	aff("aff-and", corev1.NodeSelectorTerm{MatchExpressions: []corev1.NodeSelectorRequirement{
		{Key: "zone", Operator: corev1.NodeSelectorOpIn, Values: []string{"z1"}},
		{Key: "tier", Operator: corev1.NodeSelectorOpLt, Values: []string{"3"}}}})
	app("too-big", "1500m")
	add("mem-big", "apps", "ReplicaSet", "rules-rs", "100m", "3Gi")

	// ── effective requests ──
	always := corev1.ContainerRestartPolicyAlways
	p = app("sidecar", "600m")
	p.Spec.InitContainers = []corev1.Container{{Name: "proxy", RestartPolicy: &always, Resources: cpuMem("500m", "16Mi")}}
	p = app("init-big", "100m")
	p.Spec.InitContainers = []corev1.Container{{Name: "migrate", Resources: cpuMem("1200m", "16Mi")}}
	p = app("init-ok", "600m")
	p.Spec.InitContainers = []corev1.Container{
		{Name: "proxy", RestartPolicy: &always, Resources: cpuMem("100m", "16Mi")},
		{Name: "migrate", Resources: cpuMem("300m", "16Mi")}}
	app("overhead", "900m").Spec.Overhead = corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("200m")}
	app("alloc", "100m").Status.ContainerStatuses = []corev1.ContainerStatus{{Name: "app",
		AllocatedResources: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("1200m")}}}

	// ── refusals ──
	app("pvc", "100m").Spec.Volumes = []corev1.Volume{{Name: "data", VolumeSource: corev1.VolumeSource{
		PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: "data"}}}}
	app("ephemeral", "100m").Spec.Volumes = []corev1.Volume{{Name: "scratch", VolumeSource: corev1.VolumeSource{
		Ephemeral: &corev1.EphemeralVolumeSource{}}}}
	app("hostport", "100m").Spec.Containers[0].Ports = []corev1.ContainerPort{{ContainerPort: 80, HostPort: 8080}}
	term := corev1.PodAffinityTerm{TopologyKey: "kubernetes.io/hostname", LabelSelector: &metav1.LabelSelector{MatchLabels: map[string]string{"rule": "x"}}}
	app("podaff", "100m").Spec.Affinity = &corev1.Affinity{PodAffinity: &corev1.PodAffinity{RequiredDuringSchedulingIgnoredDuringExecution: []corev1.PodAffinityTerm{term}}}
	app("antiaff", "100m").Spec.Affinity = &corev1.Affinity{PodAntiAffinity: &corev1.PodAntiAffinity{RequiredDuringSchedulingIgnoredDuringExecution: []corev1.PodAffinityTerm{term}}}
	app("antiaff-preferred", "100m").Spec.Affinity = &corev1.Affinity{PodAntiAffinity: &corev1.PodAntiAffinity{
		PreferredDuringSchedulingIgnoredDuringExecution: []corev1.WeightedPodAffinityTerm{{Weight: 100, PodAffinityTerm: term}}}}
	app("spread-dns", "100m").Spec.TopologySpreadConstraints = []corev1.TopologySpreadConstraint{{MaxSkew: 1, TopologyKey: "zone", WhenUnsatisfiable: corev1.DoNotSchedule}}
	app("spread-anyway", "100m").Spec.TopologySpreadConstraints = []corev1.TopologySpreadConstraint{{MaxSkew: 1, TopologyKey: "zone", WhenUnsatisfiable: corev1.ScheduleAnyway}}
	p = app("extended", "100m")
	p.Spec.Containers[0].Resources.Requests["nvidia.com/gpu"] = resource.MustParse("1")
	p.Spec.InitContainers = []corev1.Container{{Name: "i", Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
		corev1.ResourceEphemeralStorage: resource.MustParse("1Gi")}}}}
	p = app("multi-refuse", "100m")
	p.Spec.Volumes = []corev1.Volume{{Name: "d", VolumeSource: corev1.VolumeSource{PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: "d"}}}}
	p.Spec.Containers[0].Ports = []corev1.ContainerPort{{ContainerPort: 80, HostPort: 80}}
	p = add("refused-in-kube-system", "kube-system", "ReplicaSet", "sys-rs", "100m", "64Mi")
	p.Spec.Containers[0].Ports = []corev1.ContainerPort{{ContainerPort: 53, HostPort: 53}}

	// ── blockers ──
	add("critical", "kube-system", "ReplicaSet", "sys-rs", "100m", "64Mi")
	add("self", "xamops-system", "ReplicaSet", "self-rs", "100m", "64Mi")
	add("sts", "apps", "StatefulSet", "db", "100m", "64Mi")
	add("single", "apps", "ReplicaSet", "single-rs", "100m", "64Mi")
	add("replicas-unknown", "apps", "ReplicaSet", "gone-rs", "100m", "64Mi")
	add("rc", "apps", "ReplicationController", "legacy-rc", "100m", "64Mi")
	add("ownerless", "apps", "", "", "100m", "64Mi")
	add("job", "apps", "Job", "batch-job", "100m", "64Mi")
	add("custom", "apps", "Rollout", "canary", "100m", "64Mi")
	app("safe-false", "100m").Annotations = map[string]string{SafeToEvictKey: "false"}
	app("safe-true", "100m").Annotations = map[string]string{SafeToEvictKey: "true"}
	app("multi-pdb", "100m")
	o.pdb("apps", "pdb-m1", map[string]string{"rule": "multi-pdb"}, 1)
	o.pdb("apps", "pdb-m2", map[string]string{"rule": "multi-pdb"}, 1)
	app("pdb-zero", "100m")
	o.pdb("apps", "pdb-zero", map[string]string{"rule": "pdb-zero"}, 0)
	app("pdb-stale", "100m")
	o.pdb("apps", "pdb-stale", map[string]string{"rule": "pdb-stale"}, 1).Generation = 2
	app("pdb-ok", "100m")
	o.pdb("apps", "pdb-ok", map[string]string{"rule": "pdb-ok"}, 1)
	add("pdb-everything", "allns", "ReplicaSet", "all-rs", "100m", "64Mi")
	o.rs("allns", "all-rs", 2)
	o.pdb("allns", "pdb-all", map[string]string{}, 1).Spec.Selector = &metav1.LabelSelector{}
	o.pdb("allns", "pdb-nil", nil, 0).Spec.Selector = nil
	add("blocked-and-strand", "kube-system", "ReplicaSet", "sys-rs", "1500m", "64Mi")

	// ── exclusions ──
	n := src("ds-only")
	o.pod("kube-system", "ds-pod", n, "DaemonSet", "aws-node", "100m", "64Mi", nil)
	n = src("mirror")
	o.pod("kube-system", "mirror-pod", n, "Node", n, "100m", "64Mi", nil).Annotations = map[string]string{MirrorPodAnnotation: "x"}
	o.node("s-empty", "4", "8Gi", 0)
	return o
}

// rulesExpectations: node -> plan. Strand-free movable pods name their target.
func rulesExpectations() map[string]expectation {
	mv := func(rule, to string) expectation {
		return expectation{drainable: true, moves: []movePair{{"apps/" + rule, to}}}
	}
	strand := func(pod string) expectation { return expectation{strands: []string{pod}} }
	refused := func(pod, reason string) expectation {
		return expectation{refused: []Finding{{Pod: pod, Reason: reason}}}
	}
	blocked := func(pod, to, reason string) expectation {
		return expectation{blocked: []Finding{{Pod: pod, Reason: reason}}, moves: []movePair{{pod, to}}}
	}
	e := map[string]expectation{
		"s-plain": mv("plain", "t2-plain"), "s-tolerates": mv("tolerates", "t1-tainted"),
		"s-tol-exists": mv("tol-exists", "t1-tainted"), "s-tol-wrong-value": mv("tol-wrong-value", "t2-plain"),
		"s-sel-z1": strand("apps/sel-z1"), "s-sel-z1-tol": mv("sel-z1-tol", "t1-tainted"),
		"s-sel-missing": strand("apps/sel-missing"),
		"s-aff-in":      mv("aff-in", "t2-plain"), "s-aff-notin": mv("aff-notin", "t2-plain"),
		"s-aff-notin-absent": mv("aff-notin-absent", "t1-tainted"), "s-aff-exists": mv("aff-exists", "t1-tainted"),
		"s-aff-dne": mv("aff-dne", "t2-plain"), "s-aff-gt": mv("aff-gt", "t1-tainted"), "s-aff-lt": mv("aff-lt", "t2-plain"),
		"s-aff-gt-none": strand("apps/aff-gt-none"), "s-aff-gt-nonint": strand("apps/aff-gt-nonint"),
		"s-aff-field-in": mv("aff-field-in", "t2-plain"), "s-aff-field-notin": mv("aff-field-notin", "t2-plain"),
		"s-aff-or": mv("aff-or", "t2-plain"), "s-aff-empty-term": strand("apps/aff-empty-term"),
		"s-aff-and": strand("apps/aff-and"), "s-too-big": strand("apps/too-big"), "s-mem-big": strand("apps/mem-big"),
		"s-sidecar": strand("apps/sidecar"), "s-init-big": strand("apps/init-big"), "s-init-ok": mv("init-ok", "t2-plain"),
		"s-overhead": strand("apps/overhead"), "s-alloc": strand("apps/alloc"),

		"s-pvc": refused("apps/pvc", ReasonPVCVolume), "s-ephemeral": refused("apps/ephemeral", ReasonEphemeralVolume),
		"s-hostport": refused("apps/hostport", ReasonHostPort), "s-podaff": refused("apps/podaff", ReasonPodAffinity),
		"s-antiaff": refused("apps/antiaff", ReasonPodAntiAffinity), "s-antiaff-preferred": mv("antiaff-preferred", "t2-plain"),
		"s-spread-dns": refused("apps/spread-dns", ReasonTopologySpread), "s-spread-anyway": mv("spread-anyway", "t2-plain"),
		"s-extended":               refused("apps/extended", ReasonExtendedResource+":ephemeral-storage,nvidia.com/gpu"),
		"s-multi-refuse":           refused("apps/multi-refuse", ReasonPVCVolume+"; "+ReasonHostPort),
		"s-refused-in-kube-system": refused("kube-system/refused-in-kube-system", ReasonHostPort),

		"s-critical":         blocked("kube-system/critical", "t2-plain", ReasonCriticalNamespace),
		"s-self":             blocked("xamops-system/self", "t2-plain", ReasonSelfNamespace),
		"s-sts":              blocked("apps/sts", "t2-plain", ReasonStatefulSet),
		"s-single":           blocked("apps/single", "t2-plain", ReasonSingleReplica),
		"s-replicas-unknown": blocked("apps/replicas-unknown", "t2-plain", ReasonReplicasUnknown),
		"s-rc":               blocked("apps/rc", "t2-plain", ReasonReplicasUnknown),
		"s-ownerless":        blocked("apps/ownerless", "t2-plain", ReasonNoController),
		"s-job":              blocked("apps/job", "t2-plain", ReasonJobOwner),
		"s-custom":           blocked("apps/custom", "t2-plain", ReasonUnknownOwnerKind+":Rollout"),
		"s-safe-false":       blocked("apps/safe-false", "t2-plain", ReasonSafeToEvictFalse),
		"s-safe-true":        mv("safe-true", "t2-plain"),
		"s-pdb-zero":         blocked("apps/pdb-zero", "t2-plain", ReasonPDBNoDisruptions+":apps/pdb-zero"),
		"s-pdb-stale":        blocked("apps/pdb-stale", "t2-plain", ReasonPDBStale+":apps/pdb-stale"),
		"s-blocked-and-strand": {blocked: []Finding{{Pod: "kube-system/blocked-and-strand", Reason: ReasonCriticalNamespace}},
			strands: []string{"kube-system/blocked-and-strand"}},

		"s-ds-only": {drainable: true, excluded: 1}, "s-mirror": {drainable: true, excluded: 1},
		"s-empty": {drainable: true},
		// Targets: t1/t3/t4 hold no counted pod. t2's terminating pod still counts and
		// has nowhere to go (t1 tainted, t3 cordoned, t4 NotReady, sources full).
		"t1-tainted":  {drainable: true},
		"t2-plain":    {strands: []string{"apps/terminating"}},
		"t3-cordoned": {drainable: true},
		"t4-notready": {drainable: true},
	}
	x := blocked("apps/multi-pdb", "t2-plain", ReasonMultiplePDBs+":apps/pdb-m1,apps/pdb-m2")
	x.pdbChecks = []PDBCheck{{PDB: "apps/pdb-m1", MatchedInSet: 1, DisruptionsAllowed: 1}, {PDB: "apps/pdb-m2", MatchedInSet: 1, DisruptionsAllowed: 1}}
	e["s-multi-pdb"] = x
	x = e["s-pdb-zero"]
	x.pdbChecks = []PDBCheck{{PDB: "apps/pdb-zero", MatchedInSet: 1}}
	e["s-pdb-zero"] = x
	x = e["s-pdb-stale"]
	x.pdbChecks = []PDBCheck{{PDB: "apps/pdb-stale", MatchedInSet: 1, DisruptionsAllowed: 1, Stale: true}}
	e["s-pdb-stale"] = x
	x = mv("pdb-ok", "t2-plain")
	x.pdbChecks = []PDBCheck{{PDB: "apps/pdb-ok", MatchedInSet: 1, DisruptionsAllowed: 1}}
	e["s-pdb-ok"] = x
	e["s-pdb-everything"] = expectation{drainable: true, moves: []movePair{{"allns/pdb-everything", "t2-plain"}},
		pdbChecks: []PDBCheck{{PDB: "allns/pdb-all", MatchedInSet: 1, DisruptionsAllowed: 1}}}
	return e
}

func TestRulesFixtureEveryRule(t *testing.T) {
	o := rulesFixture()
	s := o.snapshot()
	want := rulesExpectations()
	var names []string
	for _, n := range s.Nodes {
		names = append(names, n.Name)
	}
	sort.Strings(names)
	for _, n := range names {
		w, ok := want[n]
		if !ok {
			t.Errorf("no expectation for node %s", n)
			continue
		}
		checkPlan(t, n, SimulateDrain(s, []string{n}, rulesOptions()), w)
	}
	if len(want) != len(names) {
		t.Errorf("%d expectations for %d nodes", len(want), len(names))
	}
}

// Guards switched off turn the guarded blockers into movable pods; the unconditional
// blockers (owner kind, safe-to-evict, PDB) stay.
func TestRulesGuardsOff(t *testing.T) {
	s := rulesFixture().snapshot()
	off := Options{Guards: policy.SafetyGuards{CriticalNamespaces: []string{"kube-system"}}}
	for node, want := range map[string]bool{
		"s-critical": true, "s-self": true, "s-sts": true, "s-single": true, "s-replicas-unknown": true, "s-rc": true,
		"s-ownerless": false, "s-job": false, "s-custom": false, "s-safe-false": false, "s-pdb-zero": false,
		"s-pdb-stale": false, "s-multi-pdb": false,
	} {
		if got := SimulateDrain(s, []string{node}, off).Drainable; got != want {
			t.Errorf("%s with guards off: drainable = %v, want %v", node, got, want)
		}
	}
}
