package fitsim

import (
	"fmt"
	"sort"
	"strings"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/selection"
)

// SimulateDrain evaluates the drain set nodes against the snapshot (plan.md section 3).
// The snapshot is not modified.
func SimulateDrain(s *Snapshot, nodes []string, o Options) Plan {
	set := map[string]bool{}
	for _, n := range nodes {
		set[n] = true
	}
	plan := Plan{
		Nodes:     sortedKeys(set),
		Excluded:  []string{},
		Refused:   []Finding{},
		Blocked:   []Finding{},
		Strands:   []string{},
		Moves:     []Move{},
		PDBChecks: []PDBCheck{},
	}
	if s == nil {
		plan.Errors = []string{"no snapshot"}
		plan.Summary = summarize(&plan)
		return plan
	}

	known := map[string]bool{}
	for i := range s.Nodes {
		known[s.Nodes[i].Name] = true
	}
	for _, n := range plan.Nodes {
		if !known[n] {
			plan.Errors = append(plan.Errors, fmt.Sprintf("node %s not in the snapshot", n))
		}
	}

	// 3.3 free capacity of every node from all bound pods.
	free := map[string]*Resources{}
	for i := range s.Nodes {
		n := &s.Nodes[i]
		free[n.Name] = &Resources{MilliCPU: n.Allocatable.MilliCPU, MemoryBytes: n.Allocatable.MemoryBytes, Pods: n.Allocatable.Pods}
	}
	var inSet []*Pod
	for i := range s.Pods {
		p := &s.Pods[i]
		if !p.Bound() {
			continue
		}
		if f, ok := free[p.NodeName]; ok {
			f.MilliCPU -= p.Eff.MilliCPU
			f.MemoryBytes -= p.Eff.MemoryBytes
			f.Pods--
		}
		if set[p.NodeName] {
			inSet = append(inSet, p)
		}
	}

	// 3.4 classification.
	pdbMatched := map[string]int{}
	pdbByKey := map[string]*PDB{}
	var toPlace []*Pod
	blocked := map[string]bool{}
	for _, p := range inSet {
		if isExcluded(p) {
			plan.Excluded = append(plan.Excluded, p.Key())
			continue
		}
		matched := matchingPDBs(s, p)
		for _, b := range matched {
			pdbMatched[b.Key()]++
			pdbByKey[b.Key()] = b
		}
		// Stateful/DB protection (I15): a detected database is Refused-class and runs
		// BEFORE the zone predicate, so a protected pod never reaches placement.
		if p.DBVerdict != "" {
			plan.Refused = append(plan.Refused, Finding{Pod: p.Key(), Reason: p.DBVerdict})
			continue
		}
		if len(p.Unmodelled) > 0 {
			plan.Refused = append(plan.Refused, Finding{Pod: p.Key(), Reason: strings.Join(p.Unmodelled, "; ")})
			continue
		}
		if reasons := blockReasons(p, matched, o); len(reasons) > 0 {
			plan.Blocked = append(plan.Blocked, Finding{Pod: p.Key(), Reason: strings.Join(reasons, "; ")})
			blocked[p.Key()] = true
		}
		toPlace = append(toPlace, p)
	}
	plan.ExcludedCount = len(plan.Excluded)
	for key, n := range pdbMatched {
		b := pdbByKey[key]
		plan.PDBChecks = append(plan.PDBChecks, PDBCheck{PDB: key, MatchedInSet: n, DisruptionsAllowed: b.DisruptionsAllowed, Stale: b.Stale})
	}

	// 3.5 first-fit-decreasing what-if placement.
	sort.Slice(toPlace, func(i, j int) bool {
		a, b := toPlace[i], toPlace[j]
		if a.Eff.MilliCPU != b.Eff.MilliCPU {
			return a.Eff.MilliCPU > b.Eff.MilliCPU
		}
		if a.Eff.MemoryBytes != b.Eff.MemoryBytes {
			return a.Eff.MemoryBytes > b.Eff.MemoryBytes
		}
		if a.Namespace != b.Namespace {
			return a.Namespace < b.Namespace
		}
		return a.Name < b.Name
	})
	var targets []*Node
	for i := range s.Nodes {
		n := &s.Nodes[i]
		if !set[n.Name] && !n.Unschedulable && n.Ready {
			targets = append(targets, n)
		}
	}
	sort.Slice(targets, func(i, j int) bool { return targets[i].Name < targets[j].Name })

	for _, p := range toPlace {
		placed := false
		for _, t := range targets {
			f := free[t.Name]
			if !fits(p, t, f) {
				continue
			}
			f.MilliCPU -= p.Eff.MilliCPU
			f.MemoryBytes -= p.Eff.MemoryBytes
			f.Pods--
			m := Move{Pod: p.Key(), From: p.NodeName, To: t.Name, CPU: p.Eff.MilliCPU, Mem: p.Eff.MemoryBytes,
				Namespace: p.Namespace, Name: p.Name, UID: p.UID, Blocked: blocked[p.Key()]}
			if p.Owner != nil {
				m.OwnerKind, m.OwnerName, m.OwnerUID = p.Owner.Kind, p.Owner.Name, p.Owner.UID
			}
			plan.Moves = append(plan.Moves, m)
			placed = true
			break
		}
		if !placed {
			plan.Strands = append(plan.Strands, p.Key())
		}
	}

	// 3.6 verdict + deterministic output.
	sort.Strings(plan.Excluded)
	sort.Strings(plan.Strands)
	sort.Slice(plan.Refused, func(i, j int) bool { return plan.Refused[i].Pod < plan.Refused[j].Pod })
	sort.Slice(plan.Blocked, func(i, j int) bool { return plan.Blocked[i].Pod < plan.Blocked[j].Pod })
	sort.Slice(plan.PDBChecks, func(i, j int) bool { return plan.PDBChecks[i].PDB < plan.PDBChecks[j].PDB })
	plan.Drainable = len(plan.Nodes) > 0 && len(plan.Errors) == 0 &&
		len(plan.Refused) == 0 && len(plan.Blocked) == 0 && len(plan.Strands) == 0
	plan.Summary = summarize(&plan)
	return plan
}

// PlanDrainSet adds candidates to the drain set greedily in the caller's order
// (section 3.8): a candidate joins only if the set with it stays drainable and fewer
// than capacity nodes are selected. plans holds, for every candidate considered, the
// plan of (selected so far + candidate); candidates after the capacity is reached are
// not considered and have no entry. Duplicate candidates are ignored.
func PlanDrainSet(s *Snapshot, candidates []string, capacity int, o Options) ([]string, map[string]Plan) {
	selected := []string{}
	plans := map[string]Plan{}
	seen := map[string]bool{}
	for _, c := range candidates {
		if seen[c] {
			continue
		}
		seen[c] = true
		if len(selected) >= capacity {
			break
		}
		set := append(append([]string{}, selected...), c)
		p := SimulateDrain(s, set, o)
		plans[c] = p
		if p.Drainable {
			selected = append(selected, c)
		}
	}
	return selected, plans
}

func isExcluded(p *Pod) bool {
	if _, ok := p.Annotations[MirrorPodAnnotation]; ok {
		return true
	}
	return p.Owner != nil && p.Owner.Kind == "DaemonSet"
}

func matchingPDBs(s *Snapshot, p *Pod) []*PDB {
	var out []*PDB
	set := labels.Set(p.Labels)
	for i := range s.PDBs {
		b := &s.PDBs[i]
		if b.Namespace == p.Namespace && b.Selector != nil && b.Selector.Matches(set) {
			out = append(out, b)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key() < out[j].Key() })
	return out
}

// blockReasons returns the section 3.4 blocker codes in their fixed order.
func blockReasons(p *Pod, matched []*PDB, o Options) []string {
	var r []string
	g := o.Guards
	if g.ProtectCriticalNamespaces {
		for _, ns := range g.CriticalNamespaces {
			if ns == p.Namespace {
				r = append(r, ReasonCriticalNamespace)
				break
			}
		}
	}
	if o.SelfNamespace != "" && p.Namespace == o.SelfNamespace {
		r = append(r, ReasonSelfNamespace)
	}
	switch {
	case p.Owner == nil:
		r = append(r, ReasonNoController)
	case p.Owner.Kind == "Job":
		r = append(r, ReasonJobOwner)
	case p.Owner.Kind != "ReplicaSet" && p.Owner.Kind != "StatefulSet" && p.Owner.Kind != "ReplicationController":
		r = append(r, ReasonUnknownOwnerKind+":"+p.Owner.Kind)
	default:
		if p.Owner.Kind == "StatefulSet" && g.ProtectStatefulSets {
			r = append(r, ReasonStatefulSet)
		}
		if g.ProtectSingleReplicas {
			switch {
			case p.ControllerReplicas == nil:
				r = append(r, ReasonReplicasUnknown)
			case *p.ControllerReplicas == 1:
				r = append(r, ReasonSingleReplica)
			}
		}
	}
	if p.Annotations[SafeToEvictKey] == "false" {
		r = append(r, ReasonSafeToEvictFalse)
	}
	if len(matched) > 1 {
		keys := make([]string, len(matched))
		for i, b := range matched {
			keys[i] = b.Key()
		}
		r = append(r, ReasonMultiplePDBs+":"+strings.Join(keys, ","))
	}
	for _, b := range matched {
		if b.DisruptionsAllowed < 1 {
			r = append(r, ReasonPDBNoDisruptions+":"+b.Key())
		}
	}
	for _, b := range matched {
		if b.Stale {
			r = append(r, ReasonPDBStale+":"+b.Key())
		}
	}
	return r
}

func fits(p *Pod, t *Node, f *Resources) bool {
	if f.MilliCPU < p.Eff.MilliCPU || f.MemoryBytes < p.Eff.MemoryBytes || f.Pods < 1 {
		return false
	}
	return toleratesAll(p.Tolerations, t.Taints) &&
		matchesNodeSelectorMap(p.NodeSelector, t.Labels) &&
		matchesRequiredAffinity(p.RequiredNodeAffinity, t) &&
		matchesRequiredAffinity(p.VolumeNodeAffinity, t)
}

func toleratesAll(tols []corev1.Toleration, taints []corev1.Taint) bool {
	for i := range taints {
		taint := &taints[i]
		if taint.Effect != corev1.TaintEffectNoSchedule && taint.Effect != corev1.TaintEffectNoExecute {
			continue
		}
		ok := false
		for j := range tols {
			if tols[j].ToleratesTaint(taint) {
				ok = true
				break
			}
		}
		if !ok {
			return false
		}
	}
	return true
}

func matchesNodeSelectorMap(sel, nodeLabels map[string]string) bool {
	for k, v := range sel {
		if got, ok := nodeLabels[k]; !ok || got != v {
			return false
		}
	}
	return true
}

// matchesRequiredAffinity: terms are ORed; an empty term matches nothing; inside a
// term every expression and field requirement must hold.
func matchesRequiredAffinity(ns *corev1.NodeSelector, n *Node) bool {
	if ns == nil {
		return true
	}
	for _, term := range ns.NodeSelectorTerms {
		if termMatches(term, n) {
			return true
		}
	}
	return false
}

var nodeSelectorOps = map[corev1.NodeSelectorOperator]selection.Operator{
	corev1.NodeSelectorOpIn:           selection.In,
	corev1.NodeSelectorOpNotIn:        selection.NotIn,
	corev1.NodeSelectorOpExists:       selection.Exists,
	corev1.NodeSelectorOpDoesNotExist: selection.DoesNotExist,
	corev1.NodeSelectorOpGt:           selection.GreaterThan,
	corev1.NodeSelectorOpLt:           selection.LessThan,
}

func termMatches(term corev1.NodeSelectorTerm, n *Node) bool {
	if len(term.MatchExpressions) == 0 && len(term.MatchFields) == 0 {
		return false
	}
	set := labels.Set(n.Labels)
	for _, e := range term.MatchExpressions {
		op, ok := nodeSelectorOps[e.Operator]
		if !ok {
			return false
		}
		req, err := labels.NewRequirement(e.Key, op, e.Values)
		if err != nil || !req.Matches(set) {
			return false
		}
	}
	for _, f := range term.MatchFields {
		if f.Key != "metadata.name" {
			return false
		}
		in := false
		for _, v := range f.Values {
			if v == n.Name {
				in = true
				break
			}
		}
		switch f.Operator {
		case corev1.NodeSelectorOpIn:
			if !in {
				return false
			}
		case corev1.NodeSelectorOpNotIn:
			if in {
				return false
			}
		default:
			return false
		}
	}
	return true
}

func summarize(p *Plan) string {
	if p.Drainable {
		return fmt.Sprintf("drainable: %d pod(s) fit on other nodes, %d excluded (DaemonSet/mirror)", len(p.Moves), p.ExcludedCount)
	}
	var parts []string
	if len(p.Nodes) == 0 {
		parts = append(parts, "empty drain set")
	}
	parts = append(parts, p.Errors...)
	for _, f := range p.Refused {
		parts = append(parts, fmt.Sprintf("refused %s (%s)", f.Pod, f.Reason))
	}
	for _, f := range p.Blocked {
		parts = append(parts, fmt.Sprintf("blocked %s (%s)", f.Pod, f.Reason))
	}
	for _, s := range p.Strands {
		parts = append(parts, fmt.Sprintf("strand %s (no node fits)", s))
	}
	return "not drainable: " + strings.Join(parts, "; ")
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
