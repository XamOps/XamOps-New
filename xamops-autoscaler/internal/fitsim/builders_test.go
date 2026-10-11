package fitsim

import (
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/xamops/automation/internal/policy"
)

// objects is a cluster fixture: typed objects in one bag.
type objects struct {
	nodes []corev1.Node
	pods  []corev1.Pod
	pdbs  []policyv1.PodDisruptionBudget
	rss   []appsv1.ReplicaSet
	stss  []appsv1.StatefulSet
}

func (o *objects) snapshot() *Snapshot {
	return FromObjects(o.nodes, o.pods, o.pdbs, o.rss, o.stss)
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
	return out
}

func (o *objects) node(name, cpu, mem string, pods int64) *corev1.Node {
	n := corev1.Node{
		TypeMeta:   metav1.TypeMeta{Kind: "Node", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: map[string]string{"kubernetes.io/hostname": name}},
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

func (o *objects) pdb(ns, name string, match map[string]string, allowed int32) *policyv1.PodDisruptionBudget {
	one := intstr.FromInt(1)
	b := policyv1.PodDisruptionBudget{
		TypeMeta:   metav1.TypeMeta{Kind: "PodDisruptionBudget", APIVersion: "policy/v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name, Generation: 1},
		Spec:       policyv1.PodDisruptionBudgetSpec{MinAvailable: &one, Selector: &metav1.LabelSelector{MatchLabels: match}},
		Status:     policyv1.PodDisruptionBudgetStatus{DisruptionsAllowed: allowed, ObservedGeneration: 1},
	}
	o.pdbs = append(o.pdbs, b)
	return &o.pdbs[len(o.pdbs)-1]
}

// dsPods adds n kube-system DaemonSet pods of cpu/mem each to node.
func (o *objects) dsPods(nodeName string, n int, cpu, mem string) {
	names := []string{"aws-node", "kube-proxy", "ebs-csi-node", "node-exporter", "fluent-bit", "ds-6", "ds-7"}
	for i := 0; i < n; i++ {
		o.pod("kube-system", names[i]+"-"+nodeName, nodeName, "DaemonSet", names[i], cpu, mem, nil)
	}
}

func testOptions() Options {
	return Options{
		Guards: policy.SafetyGuards{
			RespectPodDisruptionBudgets: true,
			ProtectSingleReplicas:       true,
			ProtectStatefulSets:         true,
			ProtectCriticalNamespaces:   true,
			CriticalNamespaces:          []string{"kube-system", "monitoring", "ingress-nginx", "xamops-system"},
		},
		SelfNamespace: "xamops-system",
	}
}

// section4Fixture reproduces the baseline shape (plan.md section 1 / FEAT-005 step 3):
// 4 nodes of 1930m / 3294Mi / 17 pods with 5 DaemonSet pods of 110m/64Mi each.
//
//	node-a: pdb-guarded-web x2 (50m, PDB disruptionsAllowed 0) + app x2 (300m) -> free 680m
//	node-b: monitoring/prometheus-server (105m) + xamops-system/xamops-automation (50m)
//	        + worker x2 (270m) -> free 685m
//	node-c: team-a/overprovisioned-api-1 (1000m) + cache x2 (200m, 130m) -> free 50m
//	node-d: team-a/overprovisioned-api-2 (1000m) + cache x2 (200m, 130m) -> free 50m
//
// No node is drainable: a is blocked by the PDB, b by protected namespaces, c and d
// strand their 1000m pod by fragmentation (aggregate free elsewhere 1415m >= 1330m).
func section4Fixture() *objects {
	o := &objects{}
	for _, n := range []string{"node-a", "node-b", "node-c", "node-d"} {
		o.node(n, "1930m", "3294Mi", 17)
		o.dsPods(n, 5, "110m", "64Mi")
	}
	web := map[string]string{"app": "pdb-guarded-web"}
	o.rs("team-b", "pdb-guarded-web-rs", 2)
	o.pod("team-b", "pdb-guarded-web-1", "node-a", "ReplicaSet", "pdb-guarded-web-rs", "50m", "48Mi", web)
	o.pod("team-b", "pdb-guarded-web-2", "node-a", "ReplicaSet", "pdb-guarded-web-rs", "50m", "48Mi", web)
	o.pdb("team-b", "pdb-guarded-web", web, 0)
	o.rs("team-c", "app-rs", 2)
	o.pod("team-c", "app-1", "node-a", "ReplicaSet", "app-rs", "300m", "256Mi", map[string]string{"app": "app"})
	o.pod("team-c", "app-2", "node-a", "ReplicaSet", "app-rs", "300m", "256Mi", map[string]string{"app": "app"})

	o.rs("monitoring", "prometheus-server-rs", 1)
	o.pod("monitoring", "prometheus-server-1", "node-b", "ReplicaSet", "prometheus-server-rs", "105m", "528Mi", nil)
	o.rs("xamops-system", "xamops-automation-rs", 1)
	o.pod("xamops-system", "xamops-automation-1", "node-b", "ReplicaSet", "xamops-automation-rs", "50m", "64Mi", nil)
	o.rs("team-d", "worker-rs", 2)
	o.pod("team-d", "worker-1", "node-b", "ReplicaSet", "worker-rs", "270m", "128Mi", nil)
	o.pod("team-d", "worker-2", "node-b", "ReplicaSet", "worker-rs", "270m", "128Mi", nil)

	o.rs("team-a", "overprovisioned-api-rs", 2)
	o.rs("team-e", "cache-rs", 4)
	o.pod("team-a", "overprovisioned-api-1", "node-c", "ReplicaSet", "overprovisioned-api-rs", "1000m", "1024Mi", nil)
	o.pod("team-e", "cache-1", "node-c", "ReplicaSet", "cache-rs", "200m", "256Mi", nil)
	o.pod("team-e", "cache-2", "node-c", "ReplicaSet", "cache-rs", "130m", "128Mi", nil)
	o.pod("team-a", "overprovisioned-api-2", "node-d", "ReplicaSet", "overprovisioned-api-rs", "1000m", "1024Mi", nil)
	o.pod("team-e", "cache-3", "node-d", "ReplicaSet", "cache-rs", "200m", "256Mi", nil)
	o.pod("team-e", "cache-4", "node-d", "ReplicaSet", "cache-rs", "130m", "128Mi", nil)
	return o
}

// positiveFixture is section4Fixture with overprovisioned-api-2 lowered to 600m:
// node-d (free 450m) becomes the only drainable node.
func positiveFixture() *objects {
	o := section4Fixture()
	for i := range o.pods {
		if o.pods[i].Name == "overprovisioned-api-2" {
			o.pods[i].Spec.Containers[0].Resources.Requests[corev1.ResourceCPU] = resource.MustParse("600m")
		}
	}
	return o
}
