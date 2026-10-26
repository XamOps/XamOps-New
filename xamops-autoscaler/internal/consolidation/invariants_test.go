package consolidation

import (
	"fmt"
	"math/rand"
	"testing"

	corev1 "k8s.io/api/core/v1"

	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/stateful"
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

// blockerCodes returns the blocker codes recorded against pod ns/name across the
// whole plan.
func blockerCodes(p Plan, pod string) []string {
	var out []string
	for _, b := range p.Blocked {
		for _, x := range b.Blockers {
			if x.Pod == pod {
				out = append(out, x.Code)
			}
		}
	}
	return out
}

func hasCode(codes []string, want string) bool {
	for _, c := range codes {
		if c == want {
			return true
		}
	}
	return false
}

// TestI15DatabaseRolesRefusedWithDistinctReason exercises each db-* role code
// distinctly (the stateful-DB-fixtures report §5 "Tests to add"): the CNPG primary
// and its synchronous standby, an operator-less DB of unknown role, and a database
// on an emptyDir. Each is a look-only blocker with a DB-specific code, and the plan
// never moves any of them, whatever the policy toggles.
func TestI15DatabaseRolesRefusedWithDistinctReason(t *testing.T) {
	cases := []struct {
		name    string
		fixture func() *objects
		pod     string
		code    string
	}{
		{"cnpg primary", cnpgClusterFixture, "team-db/pg-1", stateful.CodeDBPrimary},
		{"cnpg sync standby", cnpgClusterFixture, "team-db/pg-2", stateful.CodeDBSyncStandby},
		{"operator-less DB, unknown role", singleMySQLFixture, "team-db/mysql-0", stateful.CodeDBRoleUnknown},
		{"emptyDir DB", emptyDirDBFixture, "team-db/pg-unsafe-0", stateful.CodeDBEmptyDir},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			for _, opts := range []fitsim.Options{testOptions(), relaxedOptions()} {
				in := c.fixture().input()
				p := Analyze(in, opts, goldenNow)
				checkInvariants(t, c.name, in, p)
				if codes := blockerCodes(p, c.pod); !hasCode(codes, c.code) {
					t.Errorf("%s: %s blockers %v, want %s", c.name, c.pod, codes, c.code)
				}
				for _, m := range p.Moves {
					for _, pm := range m.Pods {
						if pm.Pod == c.pod {
							t.Errorf("%s: protected DB pod %s was planned for a move to %s", c.name, c.pod, pm.To)
						}
					}
				}
			}
		})
	}
}

// TestI15OnlyReadyReplicaRefused: a detected database StatefulSet scaled to a single
// ready replica (no emptyDir, no role label) is refused db-role-unknown first; a
// StatefulSet with a PVC whose replica count is exactly 1 would otherwise be the
// db-only-ready-replica case. This pins that a single-instance DB never moves.
func TestI15OnlyReadyReplicaRefused(t *testing.T) {
	in := singleMySQLFixture().input()
	p := Analyze(in, relaxedOptions(), goldenNow)
	checkInvariants(t, "single-mysql", in, p)
	codes := blockerCodes(p, "team-db/mysql-0")
	if !hasCode(codes, stateful.CodeDBRoleUnknown) && !hasCode(codes, stateful.CodeDBOnlyReadyReplica) {
		t.Errorf("mysql-0 blockers %v, want a db-role-unknown or db-only-ready-replica refusal", codes)
	}
}

// TestI16ZonalVolumePlacesInZone: a drainable pod bound to a zonal PV is placed only
// on an in-zone node, and strands (never crosses AZs) when no in-zone receiver
// exists. The analyzer, not just fitsim, honours the PV zone predicate.
func TestI16ZonalVolumePlacesInZone(t *testing.T) {
	inPlace := zonalPVPlacesFixture().input()
	pPlace := Analyze(inPlace, testOptions(), goldenNow)
	checkInvariants(t, "zonal-pv-places", inPlace, pPlace)
	placed := ""
	for _, m := range pPlace.Moves {
		for _, pm := range m.Pods {
			if pm.Pod == "team-z/cache-0" {
				placed = pm.To
			}
		}
	}
	zoneOf := map[string]string{}
	for _, n := range inPlace.Nodes {
		zoneOf[n.Name] = n.Labels[LabelZone]
	}
	if placed == "" {
		t.Fatalf("cache-0 was not placed: blocked=%v", pPlace.Blocked)
	}
	if zoneOf[placed] != "ap-south-1a" {
		t.Errorf("cache-0 placed on %s (zone %s), want an ap-south-1a node", placed, zoneOf[placed])
	}

	inStrand := zonalPVStrandsFixture().input()
	pStrand := Analyze(inStrand, testOptions(), goldenNow)
	checkInvariants(t, "zonal-pv-strands", inStrand, pStrand)
	if !hasCode(blockerCodes(pStrand, "team-z/cache-0"), CodeStrand) {
		t.Errorf("cache-0 should strand (no in-zone receiver), blockers %v", blockerCodes(pStrand, "team-z/cache-0"))
	}
}

// relaxedOptions turns the StatefulSet and single-replica policy guards off, to
// prove the stateful layer's db-* protection is independent of policy toggles.
func relaxedOptions() fitsim.Options {
	o := testOptions()
	o.Guards.ProtectStatefulSets = false
	o.Guards.ProtectSingleReplicas = false
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
