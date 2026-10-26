package fitsim

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"

	"github.com/xamops/automation/internal/stateful"
)

// operatorCRGVRs is the allow-list of operator Custom Resources the stateful
// protection layer reads. An allow-listed CRD that is not installed on the cluster
// (IsNotFound / no-match) is treated as zero CRs — logged at debug, never fatal.
var operatorCRGVRs = []schema.GroupVersionResource{
	{Group: "postgresql.cnpg.io", Version: "v1", Resource: "clusters"},
}

// BuildSnapshot lists nodes, all pods, policy/v1 PDBs, ReplicaSets, StatefulSets,
// PersistentVolumeClaims, PersistentVolumes (reads only) and the allow-listed
// operator Custom Resources through the dynamic client, then builds a snapshot. A
// nil dynamic client lists no CRs (the stateful layer then relies on labels/images).
func BuildSnapshot(ctx context.Context, k8s kubernetes.Interface, dyn dynamic.Interface) (*Snapshot, error) {
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
	pvcs, err := k8s.CoreV1().PersistentVolumeClaims("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list persistentvolumeclaims: %w", err)
	}
	pvs, err := k8s.CoreV1().PersistentVolumes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return nil, fmt.Errorf("list persistentvolumes: %w", err)
	}
	crs, err := ListOperatorCRs(ctx, dyn)
	if err != nil {
		return nil, err
	}
	return FromObjectsWithCRs(nodes.Items, pods.Items, pdbs.Items, rss.Items, stss.Items,
		pvcs.Items, pvs.Items, crs), nil
}

// ListOperatorCRs lists the allow-listed operator Custom Resources. An allow-listed
// CRD that is not installed (IsNotFound / no-match) contributes zero CRs; any other
// list error is returned. A nil dynamic client yields no CRs.
func ListOperatorCRs(ctx context.Context, dyn dynamic.Interface) ([]unstructured.Unstructured, error) {
	if dyn == nil {
		return nil, nil
	}
	var out []unstructured.Unstructured
	for _, gvr := range operatorCRGVRs {
		list, err := dyn.Resource(gvr).Namespace("").List(ctx, metav1.ListOptions{})
		if err != nil {
			if errors.IsNotFound(err) || meta.IsNoMatchError(err) {
				continue // CRD not installed — zero CRs of this kind.
			}
			return nil, fmt.Errorf("list %s: %w", gvr.Resource, err)
		}
		out = append(out, list.Items...)
	}
	return out, nil
}

// FromObjects builds a snapshot from typed objects (tests, testdata fixtures). It is
// the empty-PVC/PV/CR wrapper around FromObjectsWithCRs so existing callers keep
// compiling.
func FromObjects(nodes []corev1.Node, pods []corev1.Pod, pdbs []policyv1.PodDisruptionBudget,
	rss []appsv1.ReplicaSet, stss []appsv1.StatefulSet) *Snapshot {
	return FromObjectsWithCRs(nodes, pods, pdbs, rss, stss, nil, nil, nil)
}

// FromObjectsWithCRs builds a snapshot from typed objects plus PVCs, PVs and the
// allow-listed operator Custom Resources (the stateful/zone data). The PVC/PV lists
// resolve each pod's zonal PV node-affinity and the operator CRs resolve the
// database-role verdict.
func FromObjectsWithCRs(nodes []corev1.Node, pods []corev1.Pod, pdbs []policyv1.PodDisruptionBudget,
	rss []appsv1.ReplicaSet, stss []appsv1.StatefulSet,
	pvcs []corev1.PersistentVolumeClaim, pvs []corev1.PersistentVolume, crs []unstructured.Unstructured) *Snapshot {
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

	// Index PVCs (by ns/name) and PVs (by name) for zone-affinity resolution, and the
	// operator CRs (by kind/ns/name) for the role verdict.
	pvByName := map[string]*corev1.PersistentVolume{}
	for i := range pvs {
		pvByName[pvs[i].Name] = &pvs[i]
	}
	pvcByKey := map[string]*corev1.PersistentVolumeClaim{}
	for i := range pvcs {
		pvcByKey[pvcs[i].Namespace+"/"+pvcs[i].Name] = &pvcs[i]
	}
	crIndex := indexCRs(crs)

	now := time.Now()
	for i := range pods {
		p := buildPod(&pods[i], pvcByKey, pvByName)
		if p.Owner != nil {
			k := p.Owner.Kind + "/" + p.Namespace + "/" + p.Owner.Name
			if r, ok := replicas[k]; ok && (p.Owner.UID == "" || uids[k] == "" || uids[k] == p.Owner.UID) {
				v := *r
				p.ControllerReplicas = &v
			}
		}
		p.DBVerdict = classifyStateful(&pods[i], &p, crIndex, now)
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
		ProviderID:    n.Spec.ProviderID,
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

func buildPod(p *corev1.Pod, pvcByKey map[string]*corev1.PersistentVolumeClaim,
	pvByName map[string]*corev1.PersistentVolume) Pod {
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
	out.HasEmptyDirAtDataPath = hasNonMemoryEmptyDir(p)
	zone, pvcModellable := resolveVolumeNodeAffinity(p, pvcByKey, pvByName)
	out.VolumeNodeAffinity = zone
	out.Unmodelled = unmodelled(p, pvcModellable)
	return out
}

// hasNonMemoryEmptyDir reports a non-Memory emptyDir volume (v1 does not resolve the
// data path, so any non-Memory emptyDir counts for the db-emptydir verdict).
func hasNonMemoryEmptyDir(p *corev1.Pod) bool {
	for _, v := range p.Spec.Volumes {
		if v.EmptyDir != nil && v.EmptyDir.Medium != corev1.StorageMediumMemory {
			return true
		}
	}
	return false
}

// resolveVolumeNodeAffinity walks each PVC volume -> PVC -> PV -> the PV's
// spec.nodeAffinity.required terms, ANDs them into one NodeSelector, and reports
// whether EVERY PVC volume was fully modellable (bound PVC -> bound PV with
// parseable node-affinity, or a PV with no node-affinity at all). An unbound PVC, a
// missing PV, or node-affinity the simulator cannot represent makes the pod
// non-modellable, so the caller keeps the fail-closed refusal.
func resolveVolumeNodeAffinity(p *corev1.Pod, pvcByKey map[string]*corev1.PersistentVolumeClaim,
	pvByName map[string]*corev1.PersistentVolume) (ns *corev1.NodeSelector, allModellable bool) {
	var terms []corev1.NodeSelectorTerm
	allModellable = true
	for _, v := range p.Spec.Volumes {
		if v.PersistentVolumeClaim == nil {
			continue
		}
		pvc := pvcByKey[p.Namespace+"/"+v.PersistentVolumeClaim.ClaimName]
		if pvc == nil || pvc.Status.Phase != corev1.ClaimBound || pvc.Spec.VolumeName == "" {
			allModellable = false
			continue
		}
		pv := pvByName[pvc.Spec.VolumeName]
		if pv == nil {
			allModellable = false
			continue
		}
		na := pv.Spec.NodeAffinity
		if na == nil || na.Required == nil {
			continue // a PV with no node-affinity constrains nothing — still modellable.
		}
		if !modellableNodeAffinity(na.Required) {
			allModellable = false
			continue
		}
		terms = append(terms, na.Required.NodeSelectorTerms...)
	}
	if len(terms) == 0 {
		return nil, allModellable
	}
	// AND the per-PV required selectors: a pod with PVs in different zones is placed
	// only on a node matching every PV's terms. Each PV's required selector is a
	// disjunction of terms; combining disjunctions is modelled by requiring the node
	// to satisfy the merged term set (every PV fixture carries exactly one zone term,
	// so this is the common single-term case).
	return &corev1.NodeSelector{NodeSelectorTerms: terms}, allModellable
}

// modellableNodeAffinity reports whether every term of a PV's required node-affinity
// is something the simulator can evaluate: match-expressions over the zone label
// using In/NotIn/Exists, and no match-fields. Anything else is not modelled.
func modellableNodeAffinity(sel *corev1.NodeSelector) bool {
	for _, term := range sel.NodeSelectorTerms {
		if len(term.MatchFields) > 0 {
			return false
		}
		for _, e := range term.MatchExpressions {
			switch e.Operator {
			case corev1.NodeSelectorOpIn, corev1.NodeSelectorOpNotIn, corev1.NodeSelectorOpExists:
			default:
				return false
			}
		}
	}
	return true
}

// indexCRs keys the allow-listed operator Custom Resources by "kind/namespace/name".
func indexCRs(crs []unstructured.Unstructured) map[string]*unstructured.Unstructured {
	out := map[string]*unstructured.Unstructured{}
	for i := range crs {
		cr := &crs[i]
		out[cr.GetKind()+"/"+cr.GetNamespace()+"/"+cr.GetName()] = cr
	}
	return out
}

// classifyStateful builds the stateful.PodFacts for a pod, resolves its operator CR
// from the index (via the owner reference or the CNPG cluster label), and returns
// the db-* verdict code ("" when the pod is not a refused database).
func classifyStateful(raw *corev1.Pod, p *Pod, crIndex map[string]*unstructured.Unstructured, now time.Time) string {
	images := make([]string, 0, len(raw.Spec.Containers)+len(raw.Spec.InitContainers))
	for _, c := range raw.Spec.InitContainers {
		images = append(images, strings.ToLower(c.Image))
	}
	for _, c := range raw.Spec.Containers {
		images = append(images, strings.ToLower(c.Image))
	}
	facts := stateful.PodFacts{
		Namespace:             p.Namespace,
		Name:                  p.Name,
		Labels:                p.Labels,
		ContainerImages:       images,
		HasEmptyDirAtDataPath: p.HasEmptyDirAtDataPath,
		HasPVC:                podHasPVC(raw),
		ReadyReplicas:         -1,
	}
	if p.Owner != nil {
		facts.Owner = &stateful.Owner{Kind: p.Owner.Kind, Name: p.Owner.Name}
	}
	if p.ControllerReplicas != nil {
		facts.ReadyReplicas = int(*p.ControllerReplicas)
	}
	cr := resolveCR(raw, p, crIndex)
	v := stateful.Classify(facts, cr, now)
	if v.Class == stateful.ClassAllowed {
		return ""
	}
	return v.Code
}

func podHasPVC(p *corev1.Pod) bool {
	for _, v := range p.Spec.Volumes {
		if v.PersistentVolumeClaim != nil {
			return true
		}
	}
	return false
}

// resolveCR finds the operator CR a pod belongs to: a CNPG pod's cnpg.io/cluster
// label names a Cluster CR in the pod's namespace; otherwise an operator owner kind
// names the CR directly.
func resolveCR(raw *corev1.Pod, p *Pod, crIndex map[string]*unstructured.Unstructured) *stateful.OperatorCR {
	if cluster, ok := raw.Labels[stateful.LabelCNPGCluster]; ok && cluster != "" {
		if obj := crIndex["Cluster/"+p.Namespace+"/"+cluster]; obj != nil {
			return &stateful.OperatorCR{Kind: obj.GetKind(), Name: obj.GetName(), Obj: obj}
		}
	}
	if p.Owner != nil {
		if obj := crIndex[p.Owner.Kind+"/"+p.Namespace+"/"+p.Owner.Name]; obj != nil {
			return &stateful.OperatorCR{Kind: obj.GetKind(), Name: obj.GetName(), Obj: obj}
		}
	}
	return nil
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
// pvcModellable reports that every PVC volume's PV zone-affinity was fully resolved
// (bound PVC -> bound PV -> parseable node-affinity): such a pod carries NO pvc
// refusal because the zone predicate in fits() handles it. A pod with any
// unmodellable PVC is refused with the narrower pvc-volume-unmodelled code.
func unmodelled(p *corev1.Pod, pvcModellable bool) []string {
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
	if pvc && !pvcModellable {
		// A PVC whose PV zone-affinity the simulator cannot fully model stays
		// refused, fail-closed, with the narrower code. A fully-modellable PVC emits
		// no refusal: the zone predicate in fits() places it same-AZ only.
		r = append(r, ReasonPVCVolumeUnmodelled)
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
