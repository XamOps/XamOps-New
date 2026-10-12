package fitsim

import (
	"context"
	"fmt"
	"sort"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/client-go/kubernetes"
)

// BuildSnapshot lists nodes, all pods, policy/v1 PDBs, ReplicaSets and StatefulSets
// (reads only) and builds a snapshot.
func BuildSnapshot(ctx context.Context, k8s kubernetes.Interface) (*Snapshot, error) {
	nodes, err := k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list nodes: %w", err)
	}
	pods, err := k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list pods: %w", err)
	}
	pdbs, err := k8s.PolicyV1().PodDisruptionBudgets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list poddisruptionbudgets: %w", err)
	}
	rss, err := k8s.AppsV1().ReplicaSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list replicasets: %w", err)
	}
	stss, err := k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list statefulsets: %w", err)
	}
	return FromObjects(nodes.Items, pods.Items, pdbs.Items, rss.Items, stss.Items), nil
}

// FromObjects builds a snapshot from typed objects (tests, testdata fixtures).
func FromObjects(nodes []corev1.Node, pods []corev1.Pod, pdbs []policyv1.PodDisruptionBudget,
	rss []appsv1.ReplicaSet, stss []appsv1.StatefulSet) *Snapshot {
	s := &Snapshot{}
	for i := range nodes {
		s.Nodes = append(s.Nodes, buildNode(&nodes[i]))
	}
	replicas := map[string]*int32{} // kind/ns/name -> spec.replicas (nil spec -> 1)
	uids := map[string]string{}
	one := int32(1)
	for i := range rss {
		r := &rss[i]
		k := "ReplicaSet/" + r.Namespace + "/" + r.Name
		replicas[k], uids[k] = orDefault(r.Spec.Replicas, one), string(r.UID)
	}
	for i := range stss {
		r := &stss[i]
		k := "StatefulSet/" + r.Namespace + "/" + r.Name
		replicas[k], uids[k] = orDefault(r.Spec.Replicas, one), string(r.UID)
	}
	for i := range pods {
		p := buildPod(&pods[i])
		if p.Owner != nil {
			k := p.Owner.Kind + "/" + p.Namespace + "/" + p.Owner.Name
			if r, ok := replicas[k]; ok && (p.Owner.UID == "" || uids[k] == "" || uids[k] == p.Owner.UID) {
				v := *r
				p.ControllerReplicas = &v
			}
		}
		s.Pods = append(s.Pods, p)
	}
	for i := range pdbs {
		s.PDBs = append(s.PDBs, buildPDB(&pdbs[i]))
	}
	s.finalize()
	return s
}

// WithoutPods returns a copy of the snapshot without the pods whose UID is in uids
// (the executor's closed loop drops pods it already evicted: they are terminating
// and their replacements are accounted for where they run).
func (s *Snapshot) WithoutPods(uids map[string]bool) *Snapshot {
	out := &Snapshot{PDBs: s.PDBs}
	out.Nodes = append([]Node(nil), s.Nodes...)
	for _, p := range s.Pods {
		if p.UID != "" && uids[p.UID] {
			continue
		}
		out.Pods = append(out.Pods, p)
	}
	out.finalize()
	return out
}

// finalize recomputes Node.Requested from the bound pods.
func (s *Snapshot) finalize() {
	idx := map[string]int{}
	for i := range s.Nodes {
		s.Nodes[i].Requested = Resources{}
		idx[s.Nodes[i].Name] = i
	}
	for i := range s.Pods {
		p := &s.Pods[i]
		if !p.Bound() {
			continue
		}
		if j, ok := idx[p.NodeName]; ok {
			r := &s.Nodes[j].Requested
			r.MilliCPU += p.Eff.MilliCPU
			r.MemoryBytes += p.Eff.MemoryBytes
			r.Pods++
		}
	}
}

// Node returns the named node, or nil.
func (s *Snapshot) Node(name string) *Node {
	for i := range s.Nodes {
		if s.Nodes[i].Name == name {
			return &s.Nodes[i]
		}
	}
	return nil
}

func orDefault(v *int32, d int32) *int32 {
	if v == nil {
		return &d
	}
	x := *v
	return &x
}

func buildNode(n *corev1.Node) Node {
	out := Node{
		Name:          n.Name,
		Labels:        n.Labels,
		Taints:        n.Spec.Taints,
		Unschedulable: n.Spec.Unschedulable,
		Allocatable: Resources{
			MilliCPU:    n.Status.Allocatable.Cpu().MilliValue(),
			MemoryBytes: n.Status.Allocatable.Memory().Value(),
			Pods:        n.Status.Allocatable.Pods().Value(),
		},
	}
	for _, c := range n.Status.Conditions {
		if c.Type == corev1.NodeReady {
			out.Ready = c.Status == corev1.ConditionTrue
		}
	}
	return out
}

func buildPDB(b *policyv1.PodDisruptionBudget) PDB {
	out := PDB{
		Namespace:          b.Namespace,
		Name:               b.Name,
		DisruptionsAllowed: b.Status.DisruptionsAllowed,
		Stale:              b.Status.ObservedGeneration < b.Generation,
	}
	// policy/v1: a nil selector matches no pods, an empty one matches every pod of
	// the namespace. An invalid selector matches nothing.
	if b.Spec.Selector != nil {
		if sel, err := metav1.LabelSelectorAsSelector(b.Spec.Selector); err == nil {
			out.Selector = sel
		} else {
			out.Selector = labels.Nothing()
		}
	}
	return out
}

func buildPod(p *corev1.Pod) Pod {
	out := Pod{
		Namespace:    p.Namespace,
		Name:         p.Name,
		UID:          string(p.UID),
		NodeName:     p.Spec.NodeName,
		Labels:       p.Labels,
		Annotations:  p.Annotations,
		Phase:        p.Status.Phase,
		Tolerations:  p.Spec.Tolerations,
		NodeSelector: p.Spec.NodeSelector,
	}
	for _, ref := range p.OwnerReferences {
		if ref.Controller != nil && *ref.Controller {
			out.Owner = &Owner{Kind: ref.Kind, Name: ref.Name, UID: string(ref.UID)}
			break
		}
	}
	if a := p.Spec.Affinity; a != nil && a.NodeAffinity != nil {
		out.RequiredNodeAffinity = a.NodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution
	}
	cpu, mem := EffectiveRequests(p)
	out.Eff = Resources{MilliCPU: cpu, MemoryBytes: mem, Pods: 1}
	out.Unmodelled = unmodelled(p)
	return out
}

// EffectiveRequests implements section 3.2 for CPU (millicores) and memory (bytes).
func EffectiveRequests(p *corev1.Pod) (milliCPU, memBytes int64) {
	alloc := map[string]corev1.ResourceList{}
	for _, st := range p.Status.ContainerStatuses {
		alloc["c/"+st.Name] = st.AllocatedResources
	}
	for _, st := range p.Status.InitContainerStatuses {
		alloc["i/"+st.Name] = st.AllocatedResources
	}
	req := func(c *corev1.Container, a corev1.ResourceList) (int64, int64) {
		cpu := c.Resources.Requests.Cpu().MilliValue()
		mem := c.Resources.Requests.Memory().Value()
		if a != nil {
			if v := a.Cpu().MilliValue(); v > cpu {
				cpu = v
			}
			if v := a.Memory().Value(); v > mem {
				mem = v
			}
		}
		return cpu, mem
	}
	var appCPU, appMem int64
	for i := range p.Spec.Containers {
		c, m := req(&p.Spec.Containers[i], alloc["c/"+p.Spec.Containers[i].Name])
		appCPU += c
		appMem += m
	}
	var sideCPU, sideMem, initCPU, initMem int64
	for i := range p.Spec.InitContainers {
		ic := &p.Spec.InitContainers[i]
		c, m := req(ic, alloc["i/"+ic.Name])
		if ic.RestartPolicy != nil && *ic.RestartPolicy == corev1.ContainerRestartPolicyAlways {
			sideCPU += c
			sideMem += m
			continue
		}
		// A regular init container runs with the sidecars declared before it.
		if v := c + sideCPU; v > initCPU {
			initCPU = v
		}
		if v := m + sideMem; v > initMem {
			initMem = v
		}
	}
	milliCPU = appCPU + sideCPU
	if initCPU > milliCPU {
		milliCPU = initCPU
	}
	memBytes = appMem + sideMem
	if initMem > memBytes {
		memBytes = initMem
	}
	milliCPU += p.Spec.Overhead.Cpu().MilliValue()
	memBytes += p.Spec.Overhead.Memory().Value()
	return milliCPU, memBytes
}

// unmodelled returns the section 3.4 refusal codes in their fixed order.
func unmodelled(p *corev1.Pod) []string {
	var r []string
	if a := p.Spec.Affinity; a != nil {
		if a.PodAffinity != nil && len(a.PodAffinity.RequiredDuringSchedulingIgnoredDuringExecution) > 0 {
			r = append(r, ReasonPodAffinity)
		}
		if a.PodAntiAffinity != nil && len(a.PodAntiAffinity.RequiredDuringSchedulingIgnoredDuringExecution) > 0 {
			r = append(r, ReasonPodAntiAffinity)
		}
	}
	for _, c := range p.Spec.TopologySpreadConstraints {
		if c.WhenUnsatisfiable == corev1.DoNotSchedule {
			r = append(r, ReasonTopologySpread)
			break
		}
	}
	pvc, eph := false, false
	for _, v := range p.Spec.Volumes {
		pvc = pvc || v.PersistentVolumeClaim != nil
		eph = eph || v.Ephemeral != nil
	}
	if pvc {
		r = append(r, ReasonPVCVolume)
	}
	if eph {
		r = append(r, ReasonEphemeralVolume)
	}
	all := append(append([]corev1.Container{}, p.Spec.InitContainers...), p.Spec.Containers...)
	hostPort := false
	extended := map[string]bool{}
	for _, c := range all {
		for _, port := range c.Ports {
			if port.HostPort > 0 {
				hostPort = true
			}
		}
		for name := range c.Resources.Requests {
			if name != corev1.ResourceCPU && name != corev1.ResourceMemory {
				extended[string(name)] = true
			}
		}
	}
	if hostPort {
		r = append(r, ReasonHostPort)
	}
	if len(extended) > 0 {
		names := make([]string, 0, len(extended))
		for n := range extended {
			names = append(names, n)
		}
		sort.Strings(names)
		r = append(r, ReasonExtendedResource+":"+strings.Join(names, ","))
	}
	return r
}
