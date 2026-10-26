package consolidation

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/labels"

	"github.com/xamops/automation/internal/fitsim"
)

// Input is one consistent read-only view of the cluster.
type Input struct {
	Nodes        []corev1.Node
	Pods         []corev1.Pod
	PDBs         []policyv1.PodDisruptionBudget
	ReplicaSets  []appsv1.ReplicaSet
	StatefulSets []appsv1.StatefulSet
	Deployments  []appsv1.Deployment
	// PVCs, PVs and CRs carry the stateful/zone data (MEDIUM-1): the zonal-PV
	// node-affinity and the operator Custom Resources the db-* protection reads.
	PVCs []corev1.PersistentVolumeClaim
	PVs  []corev1.PersistentVolume
	CRs  []unstructured.Unstructured
}

// candidate is a node that survived classification and the evictability checks.
type candidate struct {
	ref   NodeRef
	node  *fitsim.Node
	empty bool
}

// Analyze computes the look-only plan. It is pure: the result depends only on the
// input objects (not their order), opts and now.
//
//  1. Every node, by name: node-level blockers (not Ready, cordoned, removal
//     disabled, Fargate, unmanaged) -> blocked; no bound non-excluded pod -> EMPTY
//     candidate; CPU and memory requests both below 50% of allocatable -> DRAIN
//     candidate; otherwise kept.
//  2. DRAIN candidates: this package's pod checks (local storage without a
//     redundant replica, kube-system pod without PDB, do-not-disrupt annotations)
//     plus fitsim.SimulateDrain of the node alone on the headroom snapshot (Refused,
//     Blocked, strands). Any blocker -> blocked.
//  3. Survivors in plan order (EMPTY by name, then DRAIN by requested CPU, memory,
//     name); per node group at most (Ready nodes - 1) survive, the rest are blocked
//     as the last node of their group.
//  4. fitsim.PlanDrainSet on the headroom snapshot selects the set; unselected
//     candidates are blocked (no room once the other planned nodes are removed).
//
// The headroom snapshot scales every node's allocatable CPU and memory by
// (100-HeadroomPct)/100, so receivers keep 15% free. Placement uses effective
// requests, never usage.
func Analyze(in Input, opts fitsim.Options, now time.Time) Plan {
	snap := fitsim.FromObjectsWithCRs(in.Nodes, in.Pods, in.PDBs, in.ReplicaSets, in.StatefulSets,
		in.PVCs, in.PVs, in.CRs)
	head := withHeadroom(snap)

	snapPods := map[string]*fitsim.Pod{}
	for i := range snap.Pods {
		snapPods[snap.Pods[i].Key()] = &snap.Pods[i]
	}
	// Bound, non-excluded pods per node (raw objects, for the package's own checks).
	podsOn := map[string][]*corev1.Pod{}
	for i := range in.Pods {
		p := &in.Pods[i]
		sp := snapPods[p.Namespace+"/"+p.Name]
		if sp == nil || !sp.Bound() || excluded(sp) {
			continue
		}
		podsOn[sp.NodeName] = append(podsOn[sp.NodeName], p)
	}

	nodes := make([]*fitsim.Node, 0, len(snap.Nodes))
	for i := range snap.Nodes {
		nodes = append(nodes, &snap.Nodes[i])
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].Name < nodes[j].Name })

	readyInGroup := map[string]int{}
	for _, n := range nodes {
		if g := nodeGroup(n.Labels); g != "" && n.Ready {
			readyInGroup[g]++
		}
	}

	plan := Plan{
		SchemaVersion: SchemaVersion,
		GeneratedAt:   now.UTC().Format(time.RFC3339),
		Mode:          ModeAnalysis,
		Actor:         DetectActor(in.Nodes, in.Deployments),
		Settings: Settings{
			UnderutilizedThresholdPct: UnderutilizedThresholdPct,
			HeadroomPct:               HeadroomPct,
			MaxNodesPerPlan:           MaxNodesPerPlan,
		},
		Moves:   []Move{},
		Blocked: []BlockedNode{},
		Kept:    []KeptNode{},
	}
	block := func(ref NodeRef, bs []Blocker) {
		plan.Blocked = append(plan.Blocked, BlockedNode{Node: ref, Blockers: bs})
	}

	// 1 + 2. Classification and evictability.
	var empties, drains []candidate
	for _, n := range nodes {
		ref := nodeRef(n)
		if bs := nodeBlockers(n); len(bs) > 0 {
			block(ref, bs)
			continue
		}
		pods := podsOn[n.Name]
		if len(pods) == 0 {
			empties = append(empties, candidate{ref: ref, node: n, empty: true})
			continue
		}
		if reason, ok := underutilized(n, ref); !ok {
			plan.Kept = append(plan.Kept, KeptNode{Node: ref, Reason: reason})
			continue
		}
		var bs []Blocker
		for _, p := range pods {
			bs = append(bs, podBlockers(p, snapPods[p.Namespace+"/"+p.Name], snap)...)
		}
		sim := fitsim.SimulateDrain(head, []string{n.Name}, opts)
		for _, f := range append(append([]fitsim.Finding{}, sim.Refused...), sim.Blocked...) {
			for _, code := range strings.Split(f.Reason, ";") {
				if code = strings.TrimSpace(code); code != "" {
					bs = append(bs, blocker(f.Pod, code))
				}
			}
		}
		for _, s := range sim.Strands {
			bs = append(bs, blocker(s, CodeStrand))
		}
		if len(bs) > 0 {
			block(ref, bs)
			continue
		}
		drains = append(drains, candidate{ref: ref, node: n})
	}

	// 3. Plan order and the last-node-in-group floor.
	sort.Slice(drains, func(i, j int) bool {
		a, b := drains[i].node.Requested, drains[j].node.Requested
		if a.MilliCPU != b.MilliCPU {
			return a.MilliCPU < b.MilliCPU
		}
		if a.MemoryBytes != b.MemoryBytes {
			return a.MemoryBytes < b.MemoryBytes
		}
		return drains[i].ref.Name < drains[j].ref.Name
	})
	used := map[string]int{}
	var ordered []candidate
	for _, c := range append(empties, drains...) {
		g := c.ref.NodeGroup
		if used[g] >= readyInGroup[g]-1 {
			block(c.ref, []Blocker{lastNodeBlocker(g)})
			continue
		}
		used[g]++
		ordered = append(ordered, c)
	}

	// 4. Set selection and moves.
	if len(ordered) > 0 {
		names := make([]string, len(ordered))
		for i, c := range ordered {
			names[i] = c.ref.Name
		}
		capacity := MaxNodesPerPlan
		if len(names) < capacity {
			capacity = len(names)
		}
		selected, considered := fitsim.PlanDrainSet(head, names, capacity, opts)
		isSelected := map[string]bool{}
		for _, s := range selected {
			isSelected[s] = true
		}
		podMoves := map[string][]PodMove{}
		if len(selected) > 0 {
			for _, m := range fitsim.SimulateDrain(head, selected, opts).Moves {
				if m.Blocked {
					continue
				}
				owner := ""
				if m.OwnerKind != "" {
					owner = m.OwnerKind + "/" + m.OwnerName
				}
				podMoves[m.From] = append(podMoves[m.From], PodMove{
					Pod: m.Pod, Owner: owner, To: m.To, CPUMillis: m.CPU, MemBytes: m.Mem})
			}
		}
		for _, c := range ordered {
			switch {
			case isSelected[c.ref.Name]:
				mv := Move{Type: MoveDrain, Node: c.ref, Pods: podMoves[c.ref.Name]}
				if c.empty {
					mv.Type, mv.Pods = MoveEmpty, nil
				}
				if mv.Pods == nil {
					mv.Pods = []PodMove{}
				}
				sort.Slice(mv.Pods, func(i, j int) bool { return mv.Pods[i].Pod < mv.Pods[j].Pod })
				plan.Moves = append(plan.Moves, mv)
			default:
				code := CodeNoRoomInPlan
				if _, ok := considered[c.ref.Name]; !ok {
					code = CodePlanLimitReached
				}
				block(c.ref, []Blocker{blocker("", code)})
			}
		}
	}

	// Deterministic output.
	sort.Slice(plan.Moves, func(i, j int) bool { return plan.Moves[i].Node.Name < plan.Moves[j].Node.Name })
	sort.Slice(plan.Kept, func(i, j int) bool { return plan.Kept[i].Node.Name < plan.Kept[j].Node.Name })
	sort.Slice(plan.Blocked, func(i, j int) bool { return plan.Blocked[i].Node.Name < plan.Blocked[j].Node.Name })
	for i := range plan.Blocked {
		bs := plan.Blocked[i].Blockers
		sort.Slice(bs, func(a, b int) bool {
			if bs[a].Pod != bs[b].Pod {
				return bs[a].Pod < bs[b].Pod
			}
			return bs[a].Code < bs[b].Code
		})
	}

	plan.Summary = Summary{NodesEvaluated: len(nodes), Blocked: len(plan.Blocked), Kept: len(plan.Kept)}
	for _, m := range plan.Moves {
		if m.Type == MoveEmpty {
			plan.Summary.Empty++
		} else {
			plan.Summary.Drain++
		}
	}
	return plan
}

// withHeadroom returns a copy of s whose nodes keep HeadroomPct of their
// allocatable CPU and memory free (integer floor). Pods and PDBs are shared
// read-only; Requested is unchanged.
func withHeadroom(s *fitsim.Snapshot) *fitsim.Snapshot {
	out := &fitsim.Snapshot{Pods: s.Pods, PDBs: s.PDBs}
	out.Nodes = append([]fitsim.Node(nil), s.Nodes...)
	for i := range out.Nodes {
		a := &out.Nodes[i].Allocatable
		a.MilliCPU = a.MilliCPU * (100 - HeadroomPct) / 100
		a.MemoryBytes = a.MemoryBytes * (100 - HeadroomPct) / 100
	}
	return out
}

// excluded mirrors fitsim: DaemonSet controller owner or a mirror/static pod.
func excluded(p *fitsim.Pod) bool {
	if _, ok := p.Annotations[fitsim.MirrorPodAnnotation]; ok {
		return true
	}
	return p.Owner != nil && p.Owner.Kind == "DaemonSet"
}

func firstLabel(l map[string]string, keys ...string) string {
	for _, k := range keys {
		if v := l[k]; v != "" {
			return v
		}
	}
	return ""
}

func nodeGroup(l map[string]string) string {
	return firstLabel(l, LabelNodeGroup, LabelKarpenterNodePool, LabelKarpenterProv)
}

func isFargate(l map[string]string) bool { return l[LabelComputeType] == "fargate" }

func pct(requested, allocatable int64) float64 {
	if allocatable <= 0 {
		return 0
	}
	return math.Round(float64(requested)*1000/float64(allocatable)) / 10
}

func nodeRef(n *fitsim.Node) NodeRef {
	return NodeRef{
		Name:            n.Name,
		NodeGroup:       nodeGroup(n.Labels),
		InstanceType:    firstLabel(n.Labels, LabelInstanceType, LabelInstanceTypeBeta),
		CapacityType:    firstLabel(n.Labels, LabelKarpenterCapacity, LabelEKSCapacity),
		Zone:            firstLabel(n.Labels, LabelZone, LabelZoneBeta),
		Fargate:         isFargate(n.Labels),
		ProviderID:      n.ProviderID,
		CPURequestedPct: pct(n.Requested.MilliCPU, n.Allocatable.MilliCPU),
		MemRequestedPct: pct(n.Requested.MemoryBytes, n.Allocatable.MemoryBytes),
	}
}

// nodeBlockers returns the node-level reasons the node can never be removed.
// A Fargate node is not also reported as unmanaged.
func nodeBlockers(n *fitsim.Node) []Blocker {
	var bs []Blocker
	if !n.Ready {
		bs = append(bs, blocker("", CodeNodeNotReady))
	}
	if n.Unschedulable {
		bs = append(bs, blocker("", CodeNodeCordoned))
	}
	if strings.EqualFold(n.Labels[LabelRemovalDisabled], "true") {
		bs = append(bs, blocker("", CodeRemovalDisabled))
	}
	switch {
	case isFargate(n.Labels):
		bs = append(bs, blocker("", CodeFargateNode))
	case nodeGroup(n.Labels) == "":
		bs = append(bs, blocker("", CodeUnmanagedNode))
	}
	return bs
}

// underutilized reports whether both CPU and memory requests are below the
// threshold; otherwise it returns the kept reason.
func underutilized(n *fitsim.Node, ref NodeRef) (string, bool) {
	a, r := n.Allocatable, n.Requested
	if a.MilliCPU <= 0 || a.MemoryBytes <= 0 {
		return "node reports no allocatable CPU or memory", false
	}
	if r.MilliCPU*100 < UnderutilizedThresholdPct*a.MilliCPU && r.MemoryBytes*100 < UnderutilizedThresholdPct*a.MemoryBytes {
		return "", true
	}
	return fmt.Sprintf("requests above %d%% of allocatable (CPU %.1f%%, memory %.1f%%)",
		UnderutilizedThresholdPct, ref.CPURequestedPct, ref.MemRequestedPct), false
}

// podBlockers are the evictability checks fitsim does not make (engine-logic §4).
func podBlockers(p *corev1.Pod, sp *fitsim.Pod, s *fitsim.Snapshot) []Blocker {
	key := p.Namespace + "/" + p.Name
	var bs []Blocker
	if sp.Owner != nil && hasEmptyDir(p) && (sp.ControllerReplicas == nil || *sp.ControllerReplicas < 2) {
		bs = append(bs, blocker(key, CodeLocalStorageNoReplica))
	}
	if p.Namespace == "kube-system" && !hasPDB(s, sp) {
		bs = append(bs, blocker(key, CodeKubeSystemNoPDB))
	}
	if strings.EqualFold(p.Annotations[AnnotationKarpenterDoNotDisrupt], "true") ||
		strings.EqualFold(p.Annotations[AnnotationDoNotEvict], "true") {
		bs = append(bs, blocker(key, CodeDoNotDisrupt))
	}
	return bs
}

func hasEmptyDir(p *corev1.Pod) bool {
	for _, v := range p.Spec.Volumes {
		if v.EmptyDir != nil {
			return true
		}
	}
	return false
}

// hasPDB matches PDBs the way fitsim does (same namespace, non-nil selector).
func hasPDB(s *fitsim.Snapshot, p *fitsim.Pod) bool {
	set := labels.Set(p.Labels)
	for i := range s.PDBs {
		b := &s.PDBs[i]
		if b.Namespace == p.Namespace && b.Selector != nil && b.Selector.Matches(set) {
			return true
		}
	}
	return false
}
