package consolidation

import (
	"fmt"
	"math/rand"
	"testing"

	corev1 "k8s.io/api/core/v1"

	"github.com/xamops/automation/internal/fitsim"
)

// checkInvariants asserts the plan invariants against its input:
//
//	I0        every move type is EMPTY or DRAIN;
//	partition every node is in exactly one of moves, blocked, kept and the summary
//	          counts match;
//	I10       after the moves, every receiving node's summed effective requests are
//	          <= 85% of allocatable (CPU and memory), and receivers are not removed;
//	drain     a DRAIN move moves every bound non-excluded pod of its node, an EMPTY
//	          move has none;
//	group     no node group loses all its Ready nodes.
func checkInvariants(t *testing.T, name string, in Input, p Plan) {
	t.Helper()
	snap := fitsim.FromObjects(in.Nodes, in.Pods, in.PDBs, in.ReplicaSets, in.StatefulSets)

	seen := map[string]int{}
	removed := map[string]bool{}
	for _, m := range p.Moves {
		if m.Type != MoveEmpty && m.Type != MoveDrain {
			t.Errorf("%s: I0: move type %q on %s", name, m.Type, m.Node.Name)
		}
		seen[m.Node.Name]++
		removed[m.Node.Name] = true
	}
	for _, b := range p.Blocked {
		seen[b.Node.Name]++
		if len(b.Blockers) == 0 {
			t.Errorf("%s: blocked node %s has no blocker", name, b.Node.Name)
		}
	}
	for _, k := range p.Kept {
		seen[k.Node.Name]++
	}
	for _, n := range snap.Nodes {
		if seen[n.Name] != 1 {
			t.Errorf("%s: partition: node %s appears %d time(s)", name, n.Name, seen[n.Name])
		}
	}
	if len(seen) != len(snap.Nodes) {
		t.Errorf("%s: partition: plan names %d node(s), input has %d", name, len(seen), len(snap.Nodes))
	}
	empty, drain := 0, 0
	for _, m := range p.Moves {
		if m.Type == MoveEmpty {
			empty++
		} else {
			drain++
		}
	}
	want := Summary{NodesEvaluated: len(snap.Nodes), Empty: empty, Drain: drain, Blocked: len(p.Blocked), Kept: len(p.Kept)}
	if p.Summary != want {
		t.Errorf("%s: summary %+v, want %+v", name, p.Summary, want)
	}

	// Pods that have to move, per node.
	mustMove := map[string]int{}
	for i := range snap.Pods {
		sp := &snap.Pods[i]
		if sp.Bound() && !excluded(sp) {
			mustMove[sp.NodeName]++
		}
	}
	added := map[string]*fitsim.Resources{}
	for _, m := range p.Moves {
		if m.Type == MoveEmpty && (len(m.Pods) != 0 || mustMove[m.Node.Name] != 0) {
			t.Errorf("%s: EMPTY move of %s has pods (%d to move)", name, m.Node.Name, mustMove[m.Node.Name])
		}
		if m.Type == MoveDrain && len(m.Pods) != mustMove[m.Node.Name] {
			t.Errorf("%s: DRAIN of %s moves %d of %d pod(s)", name, m.Node.Name, len(m.Pods), mustMove[m.Node.Name])
		}
		for _, pm := range m.Pods {
			if removed[pm.To] {
				t.Errorf("%s: %s moves to removed node %s", name, pm.Pod, pm.To)
			}
			if added[pm.To] == nil {
				added[pm.To] = &fitsim.Resources{}
			}
			added[pm.To].MilliCPU += pm.CPUMillis
			added[pm.To].MemoryBytes += pm.MemBytes
		}
	}
	for to, a := range added {
		n := snap.Node(to)
		if n == nil {
			t.Errorf("%s: receiver %s not in the input", name, to)
			continue
		}
		cpu, mem := n.Requested.MilliCPU+a.MilliCPU, n.Requested.MemoryBytes+a.MemoryBytes
		if cpu*100 > n.Allocatable.MilliCPU*(100-HeadroomPct) || mem*100 > n.Allocatable.MemoryBytes*(100-HeadroomPct) {
			t.Errorf("%s: I10: %s ends at %dm/%d of %dm/%d allocatable (> %d%%)", name, to, cpu, mem,
				n.Allocatable.MilliCPU, n.Allocatable.MemoryBytes, 100-HeadroomPct)
		}
	}

	ready, readyRemoved := map[string]int{}, map[string]int{}
	for _, n := range snap.Nodes {
		g := nodeGroup(n.Labels)
		if g == "" || !n.Ready {
			continue
		}
		ready[g]++
		if removed[n.Name] {
			readyRemoved[g]++
		}
	}
	for g, r := range ready {
		if readyRemoved[g] >= r {
			t.Errorf("%s: node group %s loses all %d Ready node(s)", name, g, r)
		}
	}
}

func TestInvariantsGoldens(t *testing.T) {
	for _, gc := range goldenCases() {
		in := loadList(t, gc.input).input()
		checkInvariants(t, gc.name, in, Analyze(in, testOptions(), goldenNow))
	}
}

// randomCluster: 2-12 nodes in 1-3 node groups with random sizes, DaemonSet pods,
// ReplicaSet pods of random requests, PDBs and annotations; a few nodes are
// cordoned, not Ready or unmanaged.
func randomCluster(seed int64) *objects {
	r := rand.New(rand.NewSource(seed))
	o := &objects{}
	groups := 1 + r.Intn(3)
	nodes := 2 + r.Intn(11)
	sizes := []struct{ cpu, mem string }{{"2", "8Gi"}, {"4", "16Gi"}, {"8", "32Gi"}}

	nrs := 1 + r.Intn(5)
	for i := 0; i < nrs; i++ {
		o.rs("team-a", fmt.Sprintf("rs-%d", i), int32(1+r.Intn(4)))
		if r.Intn(3) == 0 {
			o.pdb("team-a", fmt.Sprintf("pdb-%d", i), map[string]string{"app": fmt.Sprintf("rs-%d", i)}, int32(r.Intn(2)))
		}
	}
	pod := 0
	for i := 0; i < nodes; i++ {
		name := fmt.Sprintf("n-%02d", i)
		sz := sizes[r.Intn(len(sizes))]
		n := o.node(name, fmt.Sprintf("ng-%d", r.Intn(groups)), sz.cpu, sz.mem)
		switch r.Intn(15) {
		case 0:
			n.Spec.Unschedulable = true
		case 1:
			n.Status.Conditions[0].Status = corev1.ConditionFalse
		case 2:
			delete(n.Labels, LabelNodeGroup)
		}
		o.dsPods(name, r.Intn(3), fmt.Sprintf("%dm", 25+r.Intn(100)), "64Mi")
		for k := r.Intn(6); k > 0; k-- {
			pod++
			rs := fmt.Sprintf("rs-%d", r.Intn(nrs))
			owner := "ReplicaSet"
			if r.Intn(20) == 0 {
				owner, rs = "", ""
			}
			p := o.pod("team-a", fmt.Sprintf("p-%03d", pod), name, owner, rs,
				fmt.Sprintf("%dm", 50+r.Intn(1500)), fmt.Sprintf("%dMi", 64+r.Intn(2048)), map[string]string{"app": rs})
			switch r.Intn(25) {
			case 0:
				p.Annotations = map[string]string{fitsim.SafeToEvictKey: "false"}
			case 1:
				p.Annotations = map[string]string{AnnotationKarpenterDoNotDisrupt: "true"}
			case 2:
				p.Spec.Volumes = []corev1.Volume{{Name: "s", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}}}
			}
		}
	}
	return o
}

func TestInvariantsRandomClusters(t *testing.T) {
	moves := map[MoveType]int{}
	for seed := int64(0); seed < 200; seed++ {
		in := randomCluster(seed).input()
		p := Analyze(in, testOptions(), goldenNow)
		checkInvariants(t, fmt.Sprintf("seed %d", seed), in, p)
		for _, m := range p.Moves {
			moves[m.Type]++
		}
	}
	// The generator must exercise both move types, or the invariants prove little.
	if moves[MoveEmpty] == 0 || moves[MoveDrain] == 0 {
		t.Errorf("random clusters produced moves %v, want both EMPTY and DRAIN", moves)
	}
	t.Logf("moves over 200 random clusters: %v", moves)
}
