package fitsim

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// Stateful DB fixtures: an anonymized export of the shapes deployed on the test
// cluster on 2026-10-25 (.agents/tasks/stateful-db-fixtures-2026-10-24/deploy.md,
// investigation.md). Node names, volume IDs, UIDs, the account and the cluster are
// replaced; the namespace is db-fixtures; no Secret object, secret value or env var
// is exported (only the Secret *name* a volume references). Node names keep the
// live sort order (db-node-1 = 1c, db-node-2 = 1b, db-node-3 = 1a), which decides
// first-fit targets.
//
//	db-node-1 (1c): mongo-2 (SECONDARY), pg-cnpg-1 (CNPG primary)
//	db-node-2 (1b): mongo-1 (SECONDARY), mysql-0 (single instance), pg-cnpg-2 (CNPG quorum standby)
//	db-node-3 (1a): mongo-0 (PRIMARY), pg-unsafe-0 (Postgres on emptyDir)
//	gp-node-1 (1a): load generators, CNPG operator, filler (kept, > 50 % requested)
//
// The file is testdata/stateful-db-fixtures.json (regenerate with
// FITSIM_WRITE_TESTDATA=1 go test ./internal/fitsim -run StatefulDBFixtureUpToDate).
// internal/consolidation and internal/drain read the same file.
//
// Tests named TestStatefulDBToday* pin TODAY's behaviour as documented in
// investigation.md; TestStatefulDBI15*/I16* encode the desired invariants
// (docs/superpowers/specs/2026-10-24-consolidation-testing-spec.md) and are
// skipped until the engine implements them.

const (
	dbNS                   = "db-fixtures"
	statefulDBFixtureFile  = "stateful-db-fixtures.json"
	labelPool              = "xamops.io/pool"
	labelCapacity          = "xamops.io/capacity"
	labelZone              = "topology.kubernetes.io/zone"
	labelNodeGroup         = "eks.amazonaws.com/nodegroup"
	statefulPool           = "stateful"
	statefulNodeGroup      = "ng-stateful"
	generalNodeGroup       = "ng-ondemand"
	statefulAllocCPU       = "1930m"
	statefulAllocMem       = "3388Mi"
	statefulAllocPods      = 17
	otelVolumePrefix       = "opentelemetry-auto-instrumentation-"
	cnpgClusterAPIVersion  = "postgresql.cnpg.io/v1"
	cnpgInstanceRoleLabel  = "cnpg.io/instanceRole"
	statefulDBFixtureNodeA = "db-node-3" // 1a: mongo-0 + pg-unsafe-0
	statefulDBFixtureNodeB = "db-node-2" // 1b: mongo-1 + mysql-0 + pg-cnpg-2
	statefulDBFixtureNodeC = "db-node-1" // 1c: mongo-2 + pg-cnpg-1
)

// statefulDBExport is the fixture bag plus the PVCs/PVs the snapshot never reads.
type statefulDBExport struct {
	objects
	pvcs []corev1.PersistentVolumeClaim
	pvs  []corev1.PersistentVolume
}

func (e *statefulDBExport) allObjects() []runtime.Object {
	out := e.objects.runtimeObjects()
	for i := range e.pvcs {
		out = append(out, &e.pvcs[i])
	}
	for i := range e.pvs {
		out = append(out, &e.pvs[i])
	}
	return out
}

// snapshot builds the snapshot WITH the PVCs/PVs (and no operator CRs — the export
// carries none; CNPG role falls back to the instanceRole label). Unlike
// objects.snapshot(), it feeds the zonal-PV node-affinity to the simulator, so a
// fully-modellable PVC pod carries no pvc refusal.
func (e *statefulDBExport) snapshot() *Snapshot {
	return FromObjectsWithCRs(e.nodes, e.pods, e.pdbs, e.rss, e.stss, e.pvcs, e.pvs, nil)
}

func (e *statefulDBExport) listJSON(t *testing.T) []byte {
	t.Helper()
	items := []interface{}{}
	for _, obj := range e.allObjects() {
		items = append(items, obj)
	}
	b, err := json.MarshalIndent(map[string]interface{}{"apiVersion": "v1", "kind": "List", "items": items}, "", " ")
	if err != nil {
		t.Fatal(err)
	}
	return append(b, '\n')
}

func rl(cpu, mem string) corev1.ResourceList {
	return corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse(mem)}
}

func qp(s string) *resource.Quantity { q := resource.MustParse(s); return &q }

func (e *statefulDBExport) fixtureNode(name, group, zone string, extra map[string]string, taints []corev1.Taint) {
	n := e.node(name, statefulAllocCPU, statefulAllocMem, statefulAllocPods)
	for k, v := range map[string]string{
		labelNodeGroup:                     group,
		"eks.amazonaws.com/capacityType":   "ON_DEMAND",
		"node.kubernetes.io/instance-type": "t3.medium",
		labelZone:                          zone,
	} {
		n.Labels[k] = v
	}
	for k, v := range extra {
		n.Labels[k] = v
	}
	n.Spec.Taints = taints
}

// nodeDaemonSets adds the 6 DaemonSet pods each node runs (490m / 311Mi in total).
func (e *statefulDBExport) nodeDaemonSets(node string) {
	for _, d := range []struct{ ns, name, cpu, mem string }{
		{"kube-system", "aws-node", "25m", "64Mi"},
		{"kube-system", "kube-proxy", "100m", "64Mi"},
		{"kube-system", "ebs-csi-node", "30m", "48Mi"},
		{"kube-system", "node-termination-handler", "35m", "46Mi"},
		{"amazon-cloudwatch", "cloudwatch-agent", "250m", "64Mi"},
		{"amazon-cloudwatch", "fluent-bit", "50m", "25Mi"},
	} {
		e.pod(d.ns, d.name+"-"+node, node, "DaemonSet", d.name, d.cpu, d.mem, map[string]string{"app": d.name})
	}
}

func statefulScheduling(p *corev1.Pod) {
	p.Spec.Tolerations = []corev1.Toleration{{Key: labelPool, Operator: corev1.TolerationOpEqual, Value: statefulPool, Effect: corev1.TaintEffectNoSchedule}}
	p.Spec.Affinity = &corev1.Affinity{NodeAffinity: &corev1.NodeAffinity{
		RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
			MatchExpressions: []corev1.NodeSelectorRequirement{
				{Key: labelPool, Operator: corev1.NodeSelectorOpIn, Values: []string{statefulPool}},
				{Key: labelCapacity, Operator: corev1.NodeSelectorOpNotIn, Values: []string{"spot"}},
			}}}}}}
}

// dbImages maps a DB container name to the anonymized-but-recognizable image the
// stateful layer detects on (deploy.md DB versions). Keeping real image names makes
// the fixture an honest export and lets the db-* detection fire on image alone.
var dbImages = map[string]string{
	"mongo":    "mongo:7.0.24",
	"mysql":    "mysql:8.4.6",
	"postgres": "postgres:16.10",
}

// dbPod adds a DB pod: one container requesting 100m/256Mi (limit 250m/512Mi) with
// the stateful pool toleration and required node affinity. The container image is
// the recognized DB image for container, so the stateful layer detects it.
func (e *statefulDBExport) dbPod(name, node, ownerKind, ownerName, container string, lbls map[string]string) *corev1.Pod {
	l := map[string]string{"xamops.io/fixture": "true"}
	for k, v := range lbls {
		l[k] = v
	}
	p := e.pod(dbNS, name, node, ownerKind, ownerName, "100m", "256Mi", l)
	p.Spec.Containers[0].Name = container
	p.Spec.Containers[0].Image = dbImages[container]
	p.Spec.Containers[0].Resources.Limits = rl("250m", "512Mi")
	statefulScheduling(p)
	return p
}

// withOTel adds the 4 init containers + emptyDirs the cluster's CloudWatch/OTel
// webhook injects (no requests).
func withOTel(p *corev1.Pod) {
	for _, lang := range []string{"java", "nodejs", "python", "dotnet"} {
		v := otelVolumePrefix + lang
		p.Spec.InitContainers = append(p.Spec.InitContainers, corev1.Container{
			Name: v, VolumeMounts: []corev1.VolumeMount{{Name: v, MountPath: "/otel-auto-instrumentation-" + lang}}})
		p.Spec.Volumes = append(p.Spec.Volumes, corev1.Volume{Name: v,
			VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{SizeLimit: qp("200Mi")}}})
	}
}

func pvcVolume(name, claim string) corev1.Volume {
	return corev1.Volume{Name: name, VolumeSource: corev1.VolumeSource{
		PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: claim}}}
}

func emptyDirVolume(name string, medium corev1.StorageMedium, limit string) corev1.Volume {
	ed := &corev1.EmptyDirVolumeSource{Medium: medium}
	if limit != "" {
		ed.SizeLimit = qp(limit)
	}
	return corev1.Volume{Name: name, VolumeSource: corev1.VolumeSource{EmptyDir: ed}}
}

// zonalClaim adds a Bound 1Gi gp3 PVC and its EBS PV pinned to zone (anonymized handle).
func (e *statefulDBExport) zonalClaim(claim, zone string) {
	sc := "gp3-fixtures"
	pv := "pv-" + dbNS + "-" + claim
	e.pvcs = append(e.pvcs, corev1.PersistentVolumeClaim{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolumeClaim", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: dbNS, Name: claim},
		Spec: corev1.PersistentVolumeClaimSpec{
			AccessModes:      []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce},
			StorageClassName: &sc, VolumeName: pv,
			Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{corev1.ResourceStorage: resource.MustParse("1Gi")}},
		},
		Status: corev1.PersistentVolumeClaimStatus{Phase: corev1.ClaimBound},
	})
	e.pvs = append(e.pvs, corev1.PersistentVolume{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolume", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Name: pv},
		Spec: corev1.PersistentVolumeSpec{
			Capacity:         corev1.ResourceList{corev1.ResourceStorage: resource.MustParse("1Gi")},
			AccessModes:      []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce},
			StorageClassName: sc,
			ClaimRef:         &corev1.ObjectReference{Kind: "PersistentVolumeClaim", Namespace: dbNS, Name: claim},
			PersistentVolumeSource: corev1.PersistentVolumeSource{CSI: &corev1.CSIPersistentVolumeSource{
				Driver: "ebs.csi.aws.com", VolumeHandle: "vol-anon-" + claim, FSType: "ext4"}},
			NodeAffinity: &corev1.VolumeNodeAffinity{Required: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
				MatchExpressions: []corev1.NodeSelectorRequirement{{Key: labelZone, Operator: corev1.NodeSelectorOpIn, Values: []string{zone}}}}}}},
		},
		Status: corev1.PersistentVolumeStatus{Phase: corev1.VolumeBound},
	})
}

func statefulDBFixture() *statefulDBExport {
	e := &statefulDBExport{}
	taint := []corev1.Taint{{Key: labelPool, Value: statefulPool, Effect: corev1.TaintEffectNoSchedule}}
	poolLabels := map[string]string{labelPool: statefulPool, "xamops.io/fixture": "true"}
	zones := map[string]string{
		statefulDBFixtureNodeC: "ap-south-1c",
		statefulDBFixtureNodeB: "ap-south-1b",
		statefulDBFixtureNodeA: "ap-south-1a",
	}
	for _, n := range []string{statefulDBFixtureNodeC, statefulDBFixtureNodeB, statefulDBFixtureNodeA} {
		e.fixtureNode(n, statefulNodeGroup, zones[n], poolLabels, taint)
		e.nodeDaemonSets(n)
	}

	// MongoDB rs0: StatefulSet of 3, zone spread DoNotSchedule, PDB minAvailable 2.
	e.sts(dbNS, "mongo", 3)
	for i, n := range []string{statefulDBFixtureNodeA, statefulDBFixtureNodeB, statefulDBFixtureNodeC} {
		name := "mongo-" + string(rune('0'+i))
		p := e.dbPod(name, n, "StatefulSet", "mongo", "mongo",
			map[string]string{"app": "mongo", "statefulset.kubernetes.io/pod-name": name})
		p.Spec.InitContainers = []corev1.Container{{Name: "keyfile", Resources: corev1.ResourceRequirements{
			Requests: rl("10m", "16Mi"), Limits: rl("50m", "32Mi")}}}
		p.Spec.TopologySpreadConstraints = []corev1.TopologySpreadConstraint{{
			MaxSkew: 1, TopologyKey: labelZone, WhenUnsatisfiable: corev1.DoNotSchedule,
			LabelSelector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "mongo"}}}}
		p.Spec.Volumes = []corev1.Volume{
			pvcVolume("data", "data-"+name),
			{Name: "keysrc", VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{SecretName: "mongo-auth"}}},
			emptyDirVolume("keys", corev1.StorageMediumMemory, "1Mi"),
		}
		withOTel(p)
		e.zonalClaim("data-"+name, zones[n])
	}
	two := intstr.FromInt(2)
	e.pdb(dbNS, "mongo", map[string]string{"app": "mongo"}, 1).Spec.MinAvailable = &two

	// MySQL: single-instance StatefulSet with a PVC, no PDB.
	e.sts(dbNS, "mysql", 1)
	p := e.dbPod("mysql-0", statefulDBFixtureNodeB, "StatefulSet", "mysql", "mysql",
		map[string]string{"app": "mysql", "statefulset.kubernetes.io/pod-name": "mysql-0"})
	p.Spec.Volumes = []corev1.Volume{pvcVolume("data", "data-mysql-0")}
	withOTel(p)
	e.zonalClaim("data-mysql-0", zones[statefulDBFixtureNodeB])

	// Postgres on emptyDir: single-replica StatefulSet, no PVC, no PDB.
	e.sts(dbNS, "pg-unsafe", 1)
	p = e.dbPod("pg-unsafe-0", statefulDBFixtureNodeA, "StatefulSet", "pg-unsafe", "postgres",
		map[string]string{"app": "pg-unsafe", "statefulset.kubernetes.io/pod-name": "pg-unsafe-0"})
	p.Spec.Volumes = []corev1.Volume{emptyDirVolume("data", "", "1Gi")}
	withOTel(p)

	// CloudNativePG Cluster pg-cnpg (2 instances, quorum ANY 1): owner kind Cluster,
	// required zone anti-affinity, PDB pg-cnpg-primary on the primary only.
	for _, inst := range []struct{ name, node, role string }{
		{"pg-cnpg-1", statefulDBFixtureNodeC, "primary"},
		{"pg-cnpg-2", statefulDBFixtureNodeB, "replica"},
	} {
		p := e.dbPod(inst.name, inst.node, "Cluster", "pg-cnpg", "postgres", map[string]string{
			"cnpg.io/cluster": "pg-cnpg", "cnpg.io/instanceName": inst.name, "cnpg.io/podRole": "instance",
			cnpgInstanceRoleLabel: inst.role, "role": inst.role})
		p.OwnerReferences[0].APIVersion = cnpgClusterAPIVersion
		p.Spec.InitContainers = []corev1.Container{{Name: "bootstrap-controller", Resources: corev1.ResourceRequirements{
			Requests: rl("100m", "256Mi"), Limits: rl("250m", "512Mi")}}}
		p.Spec.Affinity.PodAntiAffinity = &corev1.PodAntiAffinity{
			RequiredDuringSchedulingIgnoredDuringExecution: []corev1.PodAffinityTerm{{
				TopologyKey: labelZone,
				LabelSelector: &metav1.LabelSelector{MatchExpressions: []metav1.LabelSelectorRequirement{
					{Key: "cnpg.io/cluster", Operator: metav1.LabelSelectorOpIn, Values: []string{"pg-cnpg"}},
					{Key: "cnpg.io/podRole", Operator: metav1.LabelSelectorOpIn, Values: []string{"instance"}},
				}}}}}
		p.Spec.Volumes = []corev1.Volume{
			pvcVolume("pgdata", inst.name),
			emptyDirVolume("scratch-data", "", ""),
			emptyDirVolume("shm", corev1.StorageMediumMemory, ""),
		}
		e.zonalClaim(inst.name, zones[inst.node])
	}
	e.pdb(dbNS, "pg-cnpg-primary", map[string]string{"cnpg.io/cluster": "pg-cnpg", cnpgInstanceRoleLabel: "primary"}, 0)

	// General on-demand node: load generators (1-replica Deployments), the CNPG
	// operator and a filler that keeps the node above 50 % requested.
	e.fixtureNode("gp-node-1", generalNodeGroup, "ap-south-1a", nil, nil)
	e.nodeDaemonSets("gp-node-1")
	for _, lg := range []string{"load-pgbench", "load-mysql", "load-mongo"} {
		e.rs(dbNS, lg+"-rs", 1)
		lp := e.pod(dbNS, lg+"-1", "gp-node-1", "ReplicaSet", lg+"-rs", "10m", "32Mi", map[string]string{"app": lg})
		lp.Spec.Affinity = &corev1.Affinity{NodeAffinity: &corev1.NodeAffinity{
			RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
				MatchExpressions: []corev1.NodeSelectorRequirement{
					{Key: labelNodeGroup, Operator: corev1.NodeSelectorOpIn, Values: []string{generalNodeGroup}},
					{Key: labelCapacity, Operator: corev1.NodeSelectorOpNotIn, Values: []string{"spot"}},
				}}}}}}
	}
	e.rs("cnpg-system", "cnpg-controller-manager-rs", 1)
	e.pod("cnpg-system", "cnpg-controller-manager-1", "gp-node-1", "ReplicaSet", "cnpg-controller-manager-rs", "100m", "200Mi", nil)
	e.rs("team-apps", "web-rs", 2)
	e.pod("team-apps", "web-1", "gp-node-1", "ReplicaSet", "web-rs", "600m", "1Gi", map[string]string{"app": "web"})
	return e
}

// loadStatefulDBExport reads the committed export (snapshot objects + PVCs/PVs)
// into a statefulDBExport and returns, as a convenience, each claim's PV zone.
func loadStatefulDBExport(t *testing.T) (*statefulDBExport, map[string]string) {
	t.Helper()
	path := filepath.Join("testdata", statefulDBFixtureFile)
	e := &statefulDBExport{objects: *loadList(t, path)}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var list struct {
		Items []json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatal(err)
	}
	// claim (ns/name) -> zone of its PV's required node affinity.
	claimZone := map[string]string{}
	for _, it := range list.Items {
		var head struct {
			Kind string `json:"kind"`
		}
		if err := json.Unmarshal(it, &head); err != nil {
			t.Fatal(err)
		}
		switch head.Kind {
		case "PersistentVolumeClaim":
			var pvc corev1.PersistentVolumeClaim
			if err := json.Unmarshal(it, &pvc); err != nil {
				t.Fatal(err)
			}
			e.pvcs = append(e.pvcs, pvc)
		case "PersistentVolume":
			var pv corev1.PersistentVolume
			if err := json.Unmarshal(it, &pv); err != nil {
				t.Fatal(err)
			}
			e.pvs = append(e.pvs, pv)
			if pv.Spec.ClaimRef != nil && pv.Spec.NodeAffinity != nil && pv.Spec.NodeAffinity.Required != nil {
				for _, term := range pv.Spec.NodeAffinity.Required.NodeSelectorTerms {
					for _, ex := range term.MatchExpressions {
						if ex.Key == labelZone && len(ex.Values) == 1 {
							claimZone[pv.Spec.ClaimRef.Namespace+"/"+pv.Spec.ClaimRef.Name] = ex.Values[0]
						}
					}
				}
			}
		}
	}
	return e, claimZone
}

func TestStatefulDBFixtureUpToDate(t *testing.T) {
	path := filepath.Join("testdata", statefulDBFixtureFile)
	want := statefulDBFixture().listJSON(t)
	if os.Getenv("FITSIM_WRITE_TESTDATA") == "1" {
		if err := os.WriteFile(path, want, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("%s: %v (regenerate with FITSIM_WRITE_TESTDATA=1)", path, err)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("%s is stale: regenerate with FITSIM_WRITE_TESTDATA=1", path)
	}
	// No secret material may ever be exported.
	for _, banned := range []string{`"kind": "Secret"`, "secretKeyRef", "PASSWORD", "102227938259", "ip-172-31-", "vol-0"} {
		if bytes.Contains(got, []byte(banned)) {
			t.Errorf("%s contains %q: the export must stay anonymized and secret-free", path, banned)
		}
	}
}

// liveGuards are the guards the deployed agent fetched (pdb/single/sts/critical on).
func liveGuards() Options { return testOptions() }

// relaxedGuards turns protectStatefulSets and protectSingleReplicas off
// (investigation.md section 4.3).
func relaxedGuards() Options {
	o := testOptions()
	o.Guards.ProtectStatefulSets = false
	o.Guards.ProtectSingleReplicas = false
	return o
}

func podUID(ns, name string) string { return "uid-" + ns + "-" + name }

func TestStatefulDBTodaySnapshotView(t *testing.T) {
	o, _ := loadStatefulDBExport(t)
	s := o.snapshot()
	byKey := map[string]*Pod{}
	for i := range s.Pods {
		byKey[s.Pods[i].Key()] = &s.Pods[i]
	}
	three, one := int32(3), int32(1)
	cases := []struct {
		pod        string
		ownerKind  string
		replicas   *int32
		verdict    string
		unmodelled []string
	}{
		// The PVC-backed DBs (CNPG pg-cnpg-*, mysql-0, mongo-*) also mount a
		// scratch/OTel emptyDir, but their real data lives on the PVC, so the
		// emptyDir gate no longer masks the role: each resolves by role — pg-cnpg-1
		// is the CNPG primary by label (db-primary), pg-cnpg-2 an async replica with
		// no v1 lag source (db-lag-unknown), and the role-less mongo-*/mysql-0 fail
		// closed on db-role-unknown. Only pg-unsafe-0, an emptyDir-only DB with no
		// PVC, stays db-emptydir. The zonal PVs now model, so the PVC refusal
		// narrows to the unmodelled anti-affinity/topology term only. The CNPG pods'
		// owner kind Cluster is not in the replica map.
		{"pg-cnpg-1", "Cluster", nil, "db-primary", []string{ReasonPodAntiAffinity}},
		{"pg-cnpg-2", "Cluster", nil, "db-lag-unknown", []string{ReasonPodAntiAffinity}},
		{"mongo-0", "StatefulSet", &three, "db-role-unknown", []string{ReasonTopologySpread}},
		{"mongo-1", "StatefulSet", &three, "db-role-unknown", []string{ReasonTopologySpread}},
		{"mongo-2", "StatefulSet", &three, "db-role-unknown", []string{ReasonTopologySpread}},
		{"mysql-0", "StatefulSet", &one, "db-role-unknown", nil},
		{"pg-unsafe-0", "StatefulSet", &one, CodeDBEmptyDir, nil},
	}
	for _, c := range cases {
		t.Run(c.pod, func(t *testing.T) {
			p := byKey[dbNS+"/"+c.pod]
			if p == nil {
				t.Fatalf("pod %s not in snapshot", c.pod)
			}
			if p.Owner == nil || p.Owner.Kind != c.ownerKind {
				t.Errorf("owner = %+v, want kind %s", p.Owner, c.ownerKind)
			}
			switch {
			case c.replicas == nil && p.ControllerReplicas != nil:
				t.Errorf("controllerReplicas = %d, want nil", *p.ControllerReplicas)
			case c.replicas != nil && (p.ControllerReplicas == nil || *p.ControllerReplicas != *c.replicas):
				t.Errorf("controllerReplicas = %v, want %d", p.ControllerReplicas, *c.replicas)
			}
			if p.DBVerdict != c.verdict {
				t.Errorf("dbVerdict = %q, want %q", p.DBVerdict, c.verdict)
			}
			if strings.Join(p.Unmodelled, ",") != strings.Join(c.unmodelled, ",") {
				t.Errorf("unmodelled = %v, want %v", p.Unmodelled, c.unmodelled)
			}
		})
	}
}

// CodeDBEmptyDir mirrors internal/stateful.CodeDBEmptyDir without importing it in a
// test in the fitsim package (the verdict strings are the stable wire contract).
const CodeDBEmptyDir = "db-emptydir"

// TestStatefulDBDrainPlans pins SimulateDrain (the evictor / rebalancer / node
// optimizer path) on the fixture against the stateful/zone engine. Every detected
// database carries a non-Memory emptyDir, so each is Refused db-emptydir — a
// Refused-class verdict that short-circuits before the Unmodelled refusal, the PDB
// check and placement (simulate.go), and that is guard-independent: the result is
// identical under live and relaxed guards. This is the I15 invariant realized.
func TestStatefulDBDrainPlans(t *testing.T) {
	o, _ := loadStatefulDBExport(t)
	full := o.snapshot()
	noMongo0 := full.WithoutPods(map[string]bool{podUID(dbNS, "mongo-0"): true})
	k := func(n string) string { return dbNS + "/" + n }
	mongoPDB := PDBCheck{PDB: k("mongo"), MatchedInSet: 1, DisruptionsAllowed: 1}
	// Per-pod role-aware refusal codes. Only pg-unsafe-0 (emptyDir-only, no PVC)
	// stays db-emptydir; the PVC-backed DBs resolve by role.
	refusal := func(n, reason string) Finding { return Finding{Pod: k(n), Reason: reason} }

	cases := []struct {
		name    string
		snap    *Snapshot
		nodes   []string
		opts    Options
		want    expectation
		summary string
	}{
		{
			name: "1a live guards: mongo-0 (role-unknown) and the emptyDir DB pg-unsafe-0 both refused",
			snap: full, nodes: []string{statefulDBFixtureNodeA}, opts: liveGuards(),
			want: expectation{excluded: 6,
				refused:   []Finding{refusal("mongo-0", "db-role-unknown"), refusal("pg-unsafe-0", CodeDBEmptyDir)},
				pdbChecks: []PDBCheck{mongoPDB}},
		},
		{
			name: "1a relaxed guards: unchanged — the db-* refusal is guard-independent",
			snap: full, nodes: []string{statefulDBFixtureNodeA}, opts: relaxedGuards(),
			want: expectation{excluded: 6,
				refused:   []Finding{refusal("mongo-0", "db-role-unknown"), refusal("pg-unsafe-0", CodeDBEmptyDir)},
				pdbChecks: []PDBCheck{mongoPDB}},
		},
		{
			// I15: with mongo-0 gone and every policy toggle off, the emptyDir DB is
			// STILL refused (never drainable), where the pre-engine behaviour evicted
			// it. No PDB selects pg-unsafe-0, so there is no PDB check left.
			name: "1a without mongo-0, relaxed guards: emptyDir DB still refused, node not drainable",
			snap: noMongo0, nodes: []string{statefulDBFixtureNodeA}, opts: relaxedGuards(),
			want:    expectation{excluded: 6, refused: []Finding{refusal("pg-unsafe-0", CodeDBEmptyDir)}},
			summary: "not drainable: refused db-fixtures/pg-unsafe-0 (db-emptydir)",
		},
		{
			// pg-cnpg-primary allows 0 disruptions but the Refused pod skips every
			// Blocked check: the PDB is counted in PDBChecks, never a blocker.
			name: "1c live guards: CNPG primary refused db-primary, its 0-allowed PDB never blocks",
			snap: full, nodes: []string{statefulDBFixtureNodeC}, opts: liveGuards(),
			want: expectation{excluded: 6, refused: []Finding{refusal("mongo-2", "db-role-unknown"), refusal("pg-cnpg-1", "db-primary")},
				pdbChecks: []PDBCheck{mongoPDB, {PDB: k("pg-cnpg-primary"), MatchedInSet: 1, DisruptionsAllowed: 0}}},
		},
		{
			name: "1c relaxed guards: unchanged (refusal is guard-independent)",
			snap: full, nodes: []string{statefulDBFixtureNodeC}, opts: relaxedGuards(),
			want: expectation{excluded: 6, refused: []Finding{refusal("mongo-2", "db-role-unknown"), refusal("pg-cnpg-1", "db-primary")},
				pdbChecks: []PDBCheck{mongoPDB, {PDB: k("pg-cnpg-primary"), MatchedInSet: 1, DisruptionsAllowed: 0}}},
		},
		{
			name: "1b live guards: mysql-0 (role-unknown) and the replica (lag-unknown) refused",
			snap: full, nodes: []string{statefulDBFixtureNodeB}, opts: liveGuards(),
			want: expectation{excluded: 6,
				refused:   []Finding{refusal("mongo-1", "db-role-unknown"), refusal("mysql-0", "db-role-unknown"), refusal("pg-cnpg-2", "db-lag-unknown")},
				pdbChecks: []PDBCheck{mongoPDB}},
		},
		{
			name: "1b relaxed guards: unchanged",
			snap: full, nodes: []string{statefulDBFixtureNodeB}, opts: relaxedGuards(),
			want: expectation{excluded: 6,
				refused:   []Finding{refusal("mongo-1", "db-role-unknown"), refusal("mysql-0", "db-role-unknown"), refusal("pg-cnpg-2", "db-lag-unknown")},
				pdbChecks: []PDBCheck{mongoPDB}},
		},
		{
			// Whole stateful pool: every DB pod is refused db-emptydir, so nothing is
			// ever placed and nothing strands.
			name: "whole stateful pool, live guards: every DB pod refused (role-aware)",
			snap: full, nodes: []string{statefulDBFixtureNodeC, statefulDBFixtureNodeB, statefulDBFixtureNodeA}, opts: liveGuards(),
			want: expectation{excluded: 18,
				refused: []Finding{refusal("mongo-0", "db-role-unknown"), refusal("mongo-1", "db-role-unknown"), refusal("mongo-2", "db-role-unknown"),
					refusal("mysql-0", "db-role-unknown"), refusal("pg-cnpg-1", "db-primary"), refusal("pg-cnpg-2", "db-lag-unknown"), refusal("pg-unsafe-0", CodeDBEmptyDir)},
				pdbChecks: []PDBCheck{{PDB: k("mongo"), MatchedInSet: 3, DisruptionsAllowed: 1}, {PDB: k("pg-cnpg-primary"), MatchedInSet: 1, DisruptionsAllowed: 0}}},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := SimulateDrain(c.snap, c.nodes, c.opts)
			checkPlan(t, c.name, got, c.want)
			if c.summary != "" && got.Summary != c.summary {
				t.Errorf("summary = %q, want %q", got.Summary, c.summary)
			}
		})
	}
}

// protectedDBPods are the pods I15 says must never be evicted, with why.
var protectedDBPods = []struct{ pod, node, why string }{
	{"pg-cnpg-1", statefulDBFixtureNodeC, "CNPG primary"},
	{"pg-cnpg-2", statefulDBFixtureNodeB, "CNPG active (quorum) sync standby"},
	{"mongo-0", statefulDBFixtureNodeA, "Mongo PRIMARY; role unknown to the engine => refuse"},
	{"mysql-0", statefulDBFixtureNodeB, "only ready replica of a StatefulSet with a PVC"},
	{"pg-unsafe-0", statefulDBFixtureNodeA, "database on emptyDir (drain = data loss)"},
}

// dbRefusalCodes are the stateful layer's db-* refusal codes (internal/stateful).
var dbRefusalCodes = []string{"db-primary", "db-sync-standby", "db-role-unknown", "db-only-ready-replica", "db-lag-unknown", "db-lag-high", "db-emptydir"}

// TestStatefulDBI15ProtectedPodsRefusedWithReason: with every policy toggle off,
// each protected DB pod is still refused with a DB-specific reason (not only the
// generic pvc/affinity refusal). On this fixture every DB pod carries a non-Memory
// emptyDir, so the recorded reason is db-emptydir — the fail-closed verdict the
// stateful layer emits first; the point of I15 is that SOME db-* reason is recorded
// and the pod is never drainable, which holds here regardless of role.
func TestStatefulDBI15ProtectedPodsRefusedWithReason(t *testing.T) {
	o, _ := loadStatefulDBExport(t)
	s := o.snapshot()
	roleCodes := dbRefusalCodes
	for _, c := range protectedDBPods {
		t.Run(c.pod, func(t *testing.T) {
			p := SimulateDrain(s, []string{c.node}, relaxedGuards())
			if p.Drainable {
				t.Fatalf("%s (%s): node %s drainable", c.pod, c.why, c.node)
			}
			found := false
			for _, f := range append(append([]Finding{}, p.Refused...), p.Blocked...) {
				if f.Pod != dbNS+"/"+c.pod {
					continue
				}
				for _, rc := range roleCodes {
					found = found || strings.Contains(f.Reason, rc)
				}
			}
			if !found {
				t.Errorf("%s (%s): no DB-specific refusal reason recorded (refused %+v, blocked %+v)", c.pod, c.why, p.Refused, p.Blocked)
			}
		})
	}
}

// TestStatefulDBI15EmptyDirDBNeverDrained: the emptyDir database is never
// drainable, whatever the policy toggles.
func TestStatefulDBI15EmptyDirDBNeverDrained(t *testing.T) {
	o, _ := loadStatefulDBExport(t)
	s := o.snapshot().WithoutPods(map[string]bool{podUID(dbNS, "mongo-0"): true})
	for _, opts := range []Options{liveGuards(), relaxedGuards()} {
		p := SimulateDrain(s, []string{statefulDBFixtureNodeA}, opts)
		if p.Drainable {
			t.Errorf("guards %+v: %s drainable with the emptyDir DB on it: %s", opts.Guards, statefulDBFixtureNodeA, p.Summary)
		}
		for _, m := range p.Moves {
			if m.Pod == dbNS+"/pg-unsafe-0" && !m.Blocked {
				t.Errorf("guards %+v: pg-unsafe-0 planned for eviction to %s", opts.Guards, m.To)
			}
		}
	}
}

// zoneAppPod adds a plain (non-database) StatefulSet pod bound to a zonal PV: a
// 3-replica cache whose image is not a DB image, so the stateful layer allows it
// and the zone predicate — not the db-* refusal — governs placement. This is the
// pod I16 needs: every DB pod in the live fixture is refused db-emptydir, so the
// zone rule can only be observed on a placeable pod.
func (e *statefulDBExport) zoneAppPod(name, node, zone string) {
	p := e.pod(dbNS, name, node, "StatefulSet", "cache", "100m", "128Mi",
		map[string]string{"app": "cache", "statefulset.kubernetes.io/pod-name": name})
	p.Spec.Containers[0].Image = "nginx:1.27"
	p.Spec.Volumes = []corev1.Volume{pvcVolume("data", "data-"+name)}
	statefulScheduling(p)
	e.zonalClaim("data-"+name, zone)
}

// TestStatefulDBI16ZonalVolumeStaysInZone: a pod bound to a zonal PV is placeable
// (no blanket pvc-volume refusal) and is placed ONLY on a node in its volume's
// zone. With an in-zone and an out-of-zone empty receiver, the what-if move lands
// in-zone; the out-of-zone node is never chosen even though it has room.
// zoneExport builds a minimal stateful-pool export with a source node holding
// cache-0 (zonal PV in ap-south-1b) plus one receiver per zone in recvZones.
func zoneExport(recvZones ...string) *statefulDBExport {
	e := &statefulDBExport{}
	taint := []corev1.Taint{{Key: labelPool, Value: statefulPool, Effect: corev1.TaintEffectNoSchedule}}
	pool := map[string]string{labelPool: statefulPool}
	e.sts(dbNS, "cache", 3)
	e.fixtureNode("db-node-5", statefulNodeGroup, "ap-south-1b", pool, taint)
	e.nodeDaemonSets("db-node-5")
	e.zoneAppPod("cache-0", "db-node-5", "ap-south-1b")
	for i, z := range recvZones {
		name := "recv-" + string(rune('a'+i))
		e.fixtureNode(name, statefulNodeGroup, z, pool, taint)
		e.nodeDaemonSets(name)
	}
	return e
}

func TestStatefulDBI16ZonalVolumeStaysInZone(t *testing.T) {
	// cache-0's PV is in ap-south-1b. With a same-zone (1b) and a wrong-zone (1c)
	// receiver, it is placed, and only on the 1b node — never the 1c node, even
	// though the 1c node has room. recv-a (1b) sorts before recv-b (1c).
	e := zoneExport("ap-south-1b", "ap-south-1c")
	nodeZone := map[string]string{}
	for _, n := range e.snapshot().Nodes {
		nodeZone[n.Name] = n.Labels[labelZone]
	}
	plan := SimulateDrain(e.snapshot(), []string{"db-node-5"}, relaxedGuards())
	placed := map[string]string{}
	for _, m := range plan.Moves {
		placed[m.Pod] = m.To
	}
	to, ok := placed[dbNS+"/cache-0"]
	if !ok {
		t.Fatalf("cache-0 (PV in ap-south-1b) was not placed: refused=%+v blocked=%+v strands=%v",
			plan.Refused, plan.Blocked, plan.Strands)
	}
	if nodeZone[to] != "ap-south-1b" {
		t.Errorf("cache-0 placed on %s (zone %s), want an ap-south-1b node; a cross-AZ move would orphan its volume", to, nodeZone[to])
	}

	// With ONLY an out-of-zone (1c) receiver, the pod strands rather than crossing AZs.
	plan2 := SimulateDrain(zoneExport("ap-south-1c").snapshot(), []string{"db-node-5"}, relaxedGuards())
	stranded := false
	for _, strand := range plan2.Strands {
		stranded = stranded || strand == dbNS+"/cache-0"
	}
	if !stranded {
		t.Errorf("cache-0 should strand with only an out-of-zone (1c) receiver, got moves=%+v strands=%v", plan2.Moves, plan2.Strands)
	}
}
