package consolidation

import (
	"encoding/json"
	"os"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/policy"
)

// objects is a cluster fixture: typed objects in one bag (modelled on
// internal/fitsim/builders_test.go, plus Deployments).
type objects struct {
	nodes []corev1.Node
	pods  []corev1.Pod
	pdbs  []policyv1.PodDisruptionBudget
	rss   []appsv1.ReplicaSet
	stss  []appsv1.StatefulSet
	deps  []appsv1.Deployment
	pvcs  []corev1.PersistentVolumeClaim
	pvs   []corev1.PersistentVolume
	crs   []unstructured.Unstructured
}

func (o *objects) input() Input {
	return Input{Nodes: o.nodes, Pods: o.pods, PDBs: o.pdbs, ReplicaSets: o.rss, StatefulSets: o.stss,
		Deployments: o.deps, PVCs: o.pvcs, PVs: o.pvs, CRs: o.crs}
}

// runtimeObjects returns every object (for fake clientsets and testdata files).
func (o *objects) runtimeObjects() []runtime.Object {
	var out []runtime.Object
	for i := range o.nodes {
		out = append(out, &o.nodes[i])
	}
	for i := range o.pods {
		out = append(out, &o.pods[i])
	}
	for i := range o.pdbs {
		out = append(out, &o.pdbs[i])
	}
	for i := range o.rss {
		out = append(out, &o.rss[i])
	}
	for i := range o.stss {
		out = append(out, &o.stss[i])
	}
	for i := range o.deps {
		out = append(out, &o.deps[i])
	}
	for i := range o.pvcs {
		out = append(out, &o.pvcs[i])
	}
	for i := range o.pvs {
		out = append(out, &o.pvs[i])
	}
	for i := range o.crs {
		out = append(out, &o.crs[i])
	}
	return out
}

// rawNode adds a Ready node with the given labels (hostname is always set).
func (o *objects) rawNode(name, cpu, mem string, pods int64, lbls map[string]string) *corev1.Node {
	l := map[string]string{"kubernetes.io/hostname": name}
	for k, v := range lbls {
		l[k] = v
	}
	n := corev1.Node{
		TypeMeta:   metav1.TypeMeta{Kind: "Node", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: l},
		Status: corev1.NodeStatus{
			Allocatable: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpu),
				corev1.ResourceMemory: resource.MustParse(mem),
				corev1.ResourcePods:   *resource.NewQuantity(pods, resource.DecimalSI),
			},
			Conditions: []corev1.NodeCondition{{Type: corev1.NodeReady, Status: corev1.ConditionTrue}},
		},
	}
	o.nodes = append(o.nodes, n)
	return &o.nodes[len(o.nodes)-1]
}

// node adds a Ready EKS managed node group node (m5.xlarge, ON_DEMAND, ap-south-1a).
// An empty group leaves the node unmanaged.
func (o *objects) node(name, group, cpu, mem string) *corev1.Node {
	l := map[string]string{
		LabelInstanceType: "m5.xlarge",
		LabelEKSCapacity:  "ON_DEMAND",
		LabelZone:         "ap-south-1a",
	}
	if group != "" {
		l[LabelNodeGroup] = group
	}
	return o.rawNode(name, cpu, mem, 30, l)
}

func ctrlTrue() *bool { b := true; return &b }

// pod adds a Running pod with one container. ownerKind "" means no owner.
func (o *objects) pod(ns, name, nodeName, ownerKind, ownerName, cpu, mem string, lbls map[string]string) *corev1.Pod {
	p := corev1.Pod{
		TypeMeta:   metav1.TypeMeta{Kind: "Pod", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, UID: types.UID("uid-" + ns + "-" + name), Labels: lbls},
		Spec: corev1.PodSpec{NodeName: nodeName, Containers: []corev1.Container{{
			Name: "app",
			Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
				corev1.ResourceCPU: resource.MustParse(cpu), corev1.ResourceMemory: resource.MustParse(mem)}},
		}}},
		Status: corev1.PodStatus{Phase: corev1.PodRunning,
			Conditions: []corev1.PodCondition{{Type: corev1.PodReady, Status: corev1.ConditionTrue}}},
	}
	if ownerKind != "" {
		p.OwnerReferences = []metav1.OwnerReference{{
			APIVersion: "apps/v1", Kind: ownerKind, Name: ownerName,
			UID: types.UID("uid-" + ns + "-" + ownerName), Controller: ctrlTrue(),
		}}
	}
	o.pods = append(o.pods, p)
	return &o.pods[len(o.pods)-1]
}

func (o *objects) rs(ns, name string, replicas int32) {
	o.rss = append(o.rss, appsv1.ReplicaSet{
		TypeMeta:   metav1.TypeMeta{Kind: "ReplicaSet", APIVersion: "apps/v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, UID: types.UID("uid-" + ns + "-" + name)},
		Spec:       appsv1.ReplicaSetSpec{Replicas: &replicas},
	})
}

func (o *objects) sts(ns, name string, replicas int32) {
	o.stss = append(o.stss, appsv1.StatefulSet{
		TypeMeta:   metav1.TypeMeta{Kind: "StatefulSet", APIVersion: "apps/v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, UID: types.UID("uid-" + ns + "-" + name)},
		Spec:       appsv1.StatefulSetSpec{Replicas: &replicas},
	})
}

func (o *objects) pdb(ns, name string, match map[string]string, allowed int32) {
	one := intstr.FromInt(1)
	o.pdbs = append(o.pdbs, policyv1.PodDisruptionBudget{
		TypeMeta:   metav1.TypeMeta{Kind: "PodDisruptionBudget", APIVersion: "policy/v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, Generation: 1},
		Spec:       policyv1.PodDisruptionBudgetSpec{MinAvailable: &one, Selector: &metav1.LabelSelector{MatchLabels: match}},
		Status:     policyv1.PodDisruptionBudgetStatus{DisruptionsAllowed: allowed, ObservedGeneration: 1},
	})
}

// deployment adds a Deployment; replicas nil leaves spec.replicas unset.
func (o *objects) deployment(ns, name string, replicas *int32, lbls map[string]string) {
	o.deps = append(o.deps, appsv1.Deployment{
		TypeMeta:   metav1.TypeMeta{Kind: "Deployment", APIVersion: "apps/v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, UID: types.UID("uid-" + ns + "-" + name), Labels: lbls},
		Spec: appsv1.DeploymentSpec{Replicas: replicas,
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": name}},
			Template: corev1.PodTemplateSpec{ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": name}}}},
	})
}

// zonalPVC adds a Bound PVC and its PV pinned to zone via the PV's required
// node-affinity (the shape the EBS CSI driver writes). The pod references the PVC
// by claim name; the simulator resolves PVC -> PV -> zone.
func (o *objects) zonalPVC(ns, claim, zone string) {
	sc := "gp3"
	pv := "pv-" + ns + "-" + claim
	o.pvcs = append(o.pvcs, corev1.PersistentVolumeClaim{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolumeClaim", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: claim},
		Spec: corev1.PersistentVolumeClaimSpec{
			AccessModes:      []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce},
			StorageClassName: &sc, VolumeName: pv,
			Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{corev1.ResourceStorage: resource.MustParse("1Gi")}},
		},
		Status: corev1.PersistentVolumeClaimStatus{Phase: corev1.ClaimBound},
	})
	o.pvs = append(o.pvs, corev1.PersistentVolume{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolume", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Name: pv},
		Spec: corev1.PersistentVolumeSpec{
			Capacity:         corev1.ResourceList{corev1.ResourceStorage: resource.MustParse("1Gi")},
			AccessModes:      []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce},
			StorageClassName: sc,
			ClaimRef:         &corev1.ObjectReference{Kind: "PersistentVolumeClaim", Namespace: ns, Name: claim},
			PersistentVolumeSource: corev1.PersistentVolumeSource{CSI: &corev1.CSIPersistentVolumeSource{
				Driver: "ebs.csi.aws.com", VolumeHandle: "vol-" + claim, FSType: "ext4"}},
			NodeAffinity: &corev1.VolumeNodeAffinity{Required: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
				MatchExpressions: []corev1.NodeSelectorRequirement{{Key: LabelZone, Operator: corev1.NodeSelectorOpIn, Values: []string{zone}}}}}}},
		},
		Status: corev1.PersistentVolumeStatus{Phase: corev1.VolumeBound},
	})
}

// pvcVol attaches a PVC-backed volume to a pod.
func pvcVol(p *corev1.Pod, name, claim string) {
	p.Spec.Volumes = append(p.Spec.Volumes, corev1.Volume{Name: name, VolumeSource: corev1.VolumeSource{
		PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: claim}}})
}

// emptyDirVol attaches a non-Memory emptyDir volume to a pod.
func emptyDirVol(p *corev1.Pod, name string) {
	p.Spec.Volumes = append(p.Spec.Volumes, corev1.Volume{Name: name,
		VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}})
}

// setImage sets the pod's app container image so the stateful layer can detect a
// database by image.
func setImage(p *corev1.Pod, img string) { p.Spec.Containers[0].Image = img }

// cnpgCluster adds a CloudNativePG Cluster CR with the given currentPrimary and,
// when sync>0, synchronous.number replication.
func (o *objects) cnpgCluster(ns, name, currentPrimary string, sync int64) {
	obj := map[string]interface{}{
		"apiVersion": "postgresql.cnpg.io/v1",
		"kind":       "Cluster",
		"metadata":   map[string]interface{}{"namespace": ns, "name": name},
		"status":     map[string]interface{}{"currentPrimary": currentPrimary},
	}
	if sync > 0 {
		obj["spec"] = map[string]interface{}{"postgresql": map[string]interface{}{
			"synchronous": map[string]interface{}{"number": sync}}}
	}
	o.crs = append(o.crs, unstructured.Unstructured{Object: obj})
}

// dsPods adds n kube-system DaemonSet pods of cpu/mem each to node.
func (o *objects) dsPods(nodeName string, n int, cpu, mem string) {
	names := []string{"aws-node", "kube-proxy", "ebs-csi-node", "node-exporter", "fluent-bit"}
	for i := 0; i < n; i++ {
		o.pod("kube-system", names[i]+"-"+nodeName, nodeName, "DaemonSet", names[i], cpu, mem, nil)
	}
}

func i32(v int32) *int32 { return &v }

func mustQ(s string) resource.Quantity { return resource.MustParse(s) }

// testOptions are the fixed golden options: every protect* guard on, PDBs
// respected, critical namespaces kube-system and monitoring, self namespace
// xamops-system.
func testOptions() fitsim.Options {
	return fitsim.Options{
		Guards: policy.SafetyGuards{
			RespectPodDisruptionBudgets: true,
			ProtectSingleReplicas:       true,
			ProtectStatefulSets:         true,
			ProtectCriticalNamespaces:   true,
			CriticalNamespaces:          []string{"kube-system", "monitoring"},
		},
		SelfNamespace: "xamops-system",
	}
}

func listJSON(t *testing.T, o *objects) []byte {
	t.Helper()
	items := []interface{}{}
	for _, obj := range o.runtimeObjects() {
		items = append(items, obj)
	}
	b, err := json.MarshalIndent(map[string]interface{}{"apiVersion": "v1", "kind": "List", "items": items}, "", " ")
	if err != nil {
		t.Fatal(err)
	}
	return append(b, '\n')
}

// loadList decodes a kubectl `get ... -o json` List into typed objects (fitsim's
// loader extended with Deployment).
func loadList(t *testing.T, path string) *objects {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var list struct {
		Items []json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	o := &objects{}
	for _, it := range list.Items {
		var head struct {
			Kind string `json:"kind"`
		}
		if err := json.Unmarshal(it, &head); err != nil {
			t.Fatal(err)
		}
		var err error
		switch head.Kind {
		case "Node":
			var x corev1.Node
			err = json.Unmarshal(it, &x)
			o.nodes = append(o.nodes, x)
		case "Pod":
			var x corev1.Pod
			err = json.Unmarshal(it, &x)
			o.pods = append(o.pods, x)
		case "PodDisruptionBudget":
			var x policyv1.PodDisruptionBudget
			err = json.Unmarshal(it, &x)
			o.pdbs = append(o.pdbs, x)
		case "ReplicaSet":
			var x appsv1.ReplicaSet
			err = json.Unmarshal(it, &x)
			o.rss = append(o.rss, x)
		case "StatefulSet":
			var x appsv1.StatefulSet
			err = json.Unmarshal(it, &x)
			o.stss = append(o.stss, x)
		case "Deployment":
			var x appsv1.Deployment
			err = json.Unmarshal(it, &x)
			o.deps = append(o.deps, x)
		case "PersistentVolumeClaim":
			var x corev1.PersistentVolumeClaim
			err = json.Unmarshal(it, &x)
			o.pvcs = append(o.pvcs, x)
		case "PersistentVolume":
			var x corev1.PersistentVolume
			err = json.Unmarshal(it, &x)
			o.pvs = append(o.pvs, x)
		case "Cluster":
			var x unstructured.Unstructured
			err = json.Unmarshal(it, &x)
			o.crs = append(o.crs, x)
		}
		if err != nil {
			t.Fatalf("%s: decode %s: %v", path, head.Kind, err)
		}
	}
	return o
}
