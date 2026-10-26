package consolidation

import (
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/xamops/automation/internal/fitsim"
)

// Phase 1 analyzer on the anonymized stateful DB fixture export
// (../fitsim/testdata/stateful-db-fixtures.json, built and documented in
// internal/fitsim/statefuldb_test.go). TestStatefulDBToday* pins TODAY's plan as
// documented in .agents/tasks/stateful-db-fixtures-2026-10-24/investigation.md
// (section 3); TestStatefulDBI15* encodes the desired invariant and is skipped.

const sdbNS = "db-fixtures"

func loadStatefulDB(t *testing.T) *objects {
	t.Helper()
	return loadList(t, filepath.Join("..", "fitsim", "testdata", "stateful-db-fixtures.json"))
}

func sdbRemovePod(o *objects, name string) {
	out := o.pods[:0]
	for _, p := range o.pods {
		if !(p.Namespace == sdbNS && p.Name == name) {
			out = append(out, p)
		}
	}
	o.pods = out
}

// sdbStripOTel removes the webhook-injected OpenTelemetry init containers and
// emptyDirs from a pod.
func sdbStripOTel(o *objects, name string) {
	for i := range o.pods {
		p := &o.pods[i]
		if p.Namespace != sdbNS || p.Name != name {
			continue
		}
		vols := p.Spec.Volumes[:0]
		for _, v := range p.Spec.Volumes {
			if !strings.HasPrefix(v.Name, "opentelemetry-auto-instrumentation-") {
				vols = append(vols, v)
			}
		}
		p.Spec.Volumes = vols
		inits := p.Spec.InitContainers[:0]
		for _, c := range p.Spec.InitContainers {
			if !strings.HasPrefix(c.Name, "opentelemetry-auto-instrumentation-") {
				inits = append(inits, c)
			}
		}
		p.Spec.InitContainers = inits
	}
}

func sdbSetSTSReplicas(o *objects, name string, n int32) {
	for i := range o.stss {
		if o.stss[i].Namespace == sdbNS && o.stss[i].Name == name {
			o.stss[i].Spec.Replicas = i32(n)
		}
	}
}

func sdbRelaxed() fitsim.Options {
	o := testOptions()
	o.Guards.ProtectStatefulSets = false
	o.Guards.ProtectSingleReplicas = false
	return o
}

// sdbBlockers renders a blocked node's blockers as sorted "pod code" strings.
func sdbBlockers(p Plan) map[string][]string {
	out := map[string][]string{}
	for _, b := range p.Blocked {
		list := []string{}
		for _, x := range b.Blockers {
			list = append(list, strings.TrimPrefix(x.Pod, sdbNS+"/")+" "+x.Code)
		}
		sort.Strings(list)
		out[b.Node.Name] = list
	}
	return out
}

func sdbMoves(p Plan) map[string][]string {
	out := map[string][]string{}
	for _, m := range p.Moves {
		list := []string{}
		for _, pm := range m.Pods {
			list = append(list, strings.TrimPrefix(pm.Pod, sdbNS+"/")+" "+pm.Owner+" -> "+pm.To)
		}
		out[string(m.Type)+" "+m.Node.Name] = list
	}
	return out
}

func sdbKept(p Plan) []string {
	out := []string{}
	for _, k := range p.Kept {
		out = append(out, k.Node.Name)
	}
	return out
}

// Plan blockers per stateful node under the stateful/zone engine. Every DB pod
// carries a non-Memory emptyDir, so the simulate.go Refused verdict is db-emptydir;
// the analyzer's own local-storage-no-replica check (emptyDir + replicas < 2) adds
// a second blocker for the single-replica DBs (mysql-0, pg-cnpg-*, pg-unsafe-0).
// The DB-specific db-emptydir code replaces the pre-engine generic pvc/affinity
// codes, and no blocker is ever dropped by relaxing the policy toggles.
var (
	sdbNode1 = []string{ // 1c
		"mongo-2 db-emptydir",
		"pg-cnpg-1 db-emptydir", "pg-cnpg-1 local-storage-no-replica",
	}
	sdbNode2 = []string{ // 1b
		"mongo-1 db-emptydir",
		"mysql-0 db-emptydir", "mysql-0 local-storage-no-replica",
		"pg-cnpg-2 db-emptydir", "pg-cnpg-2 local-storage-no-replica",
	}
	sdbNode3 = []string{ // 1a
		"mongo-0 db-emptydir",
		"pg-unsafe-0 db-emptydir", "pg-unsafe-0 local-storage-no-replica",
	}
)

func TestStatefulDBTodayConsolidationPlan(t *testing.T) {
	cases := []struct {
		name        string
		mutate      func(*objects)
		opts        fitsim.Options
		wantSummary Summary
		wantBlocked map[string][]string
		wantMoves   map[string][]string
	}{
		{
			// 0 EMPTY, 0 DRAIN; all 3 stateful nodes blocked. Every DB pod is refused
			// db-emptydir — a DB-specific code, never a primary/sync-standby name
			// leak — and the single-replica DBs also carry local-storage-no-replica.
			name:        "live guards: every stateful node blocked, DB pods refused db-emptydir",
			opts:        testOptions(),
			wantSummary: Summary{NodesEvaluated: 4, Blocked: 3, Kept: 1},
			wantBlocked: map[string][]string{"db-node-1": sdbNode1, "db-node-2": sdbNode2, "db-node-3": sdbNode3},
			wantMoves:   map[string][]string{},
		},
		{
			// With its OTel emptyDirs removed, mysql-0 no longer has a non-Memory
			// emptyDir: the stateful layer can no longer call it an emptyDir DB, so
			// it detects it as a database of unknown role (db-role-unknown), and its
			// local-storage-no-replica blocker disappears.
			name:        "OTel injection removed from mysql-0: emptyDir DB becomes role-unknown",
			mutate:      func(o *objects) { sdbStripOTel(o, "mysql-0") },
			opts:        testOptions(),
			wantSummary: Summary{NodesEvaluated: 4, Blocked: 3, Kept: 1},
			wantBlocked: map[string][]string{"db-node-1": sdbNode1, "db-node-3": sdbNode3, "db-node-2": {
				"mongo-1 db-emptydir", "mysql-0 db-role-unknown",
				"pg-cnpg-2 db-emptydir", "pg-cnpg-2 local-storage-no-replica",
			}},
			wantMoves: map[string][]string{},
		},
		{
			name:        "relaxed guards: identical — the db-* refusal is guard-independent",
			opts:        sdbRelaxed(),
			wantSummary: Summary{NodesEvaluated: 4, Blocked: 3, Kept: 1},
			wantBlocked: map[string][]string{"db-node-1": sdbNode1, "db-node-2": sdbNode2, "db-node-3": sdbNode3},
			wantMoves:   map[string][]string{},
		},
		{
			name:        "relaxed guards, mongo-0 gone: pg-unsafe-0 still refused db-emptydir",
			mutate:      func(o *objects) { sdbRemovePod(o, "mongo-0") },
			opts:        sdbRelaxed(),
			wantSummary: Summary{NodesEvaluated: 4, Blocked: 3, Kept: 1},
			wantBlocked: map[string][]string{"db-node-1": sdbNode1, "db-node-2": sdbNode2, "db-node-3": {
				"pg-unsafe-0 db-emptydir", "pg-unsafe-0 local-storage-no-replica",
			}},
			wantMoves: map[string][]string{},
		},
		{
			// I15: at 2 replicas the local-storage-no-replica blocker drops, but the
			// emptyDir DB is STILL refused db-emptydir and is never planned as a
			// DRAIN — where the pre-engine behaviour evicted it (data loss).
			name: "relaxed guards, mongo-0 gone, pg-unsafe at 2 replicas: still blocked, never drained",
			mutate: func(o *objects) {
				sdbRemovePod(o, "mongo-0")
				sdbSetSTSReplicas(o, "pg-unsafe", 2)
			},
			opts:        sdbRelaxed(),
			wantSummary: Summary{NodesEvaluated: 4, Blocked: 3, Kept: 1},
			wantBlocked: map[string][]string{"db-node-1": sdbNode1, "db-node-2": sdbNode2, "db-node-3": {
				"pg-unsafe-0 db-emptydir",
			}},
			wantMoves: map[string][]string{},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			o := loadStatefulDB(t)
			if c.mutate != nil {
				c.mutate(o)
			}
			in := o.input()
			p := Analyze(in, c.opts, goldenNow)
			checkInvariants(t, c.name, in, p)
			if p.Summary != c.wantSummary {
				t.Errorf("summary = %+v, want %+v", p.Summary, c.wantSummary)
			}
			if got := sdbBlockers(p); !reflect.DeepEqual(got, c.wantBlocked) {
				t.Errorf("blockers =\n%v\nwant\n%v", got, c.wantBlocked)
			}
			if got := sdbMoves(p); !reflect.DeepEqual(got, c.wantMoves) {
				t.Errorf("moves = %v, want %v", got, c.wantMoves)
			}
			if got := sdbKept(p); !reflect.DeepEqual(got, []string{"gp-node-1"}) {
				t.Errorf("kept = %v, want [gp-node-1]", got)
			}
			if p.Actor.Kind != ActorNone {
				t.Errorf("actor = %s, want %s", p.Actor.Kind, ActorNone)
			}
		})
	}
}

// dbRefusalCodes are the stateful layer's db-* refusal codes (internal/stateful).
var dbRefusalCodes = []string{"db-primary", "db-sync-standby", "db-role-unknown", "db-only-ready-replica", "db-lag-unknown", "db-lag-high", "db-emptydir"}

// TestStatefulDBI15AnalyzerRecordsDBReason: the look-only plan names why a DB pod
// blocks its node with a DB-specific code (here db-emptydir, the fail-closed
// verdict the stateful layer emits for a detected DB with a non-Memory emptyDir),
// never only a generic pvc/affinity code.
func TestStatefulDBI15AnalyzerRecordsDBReason(t *testing.T) {
	p := Analyze(loadStatefulDB(t).input(), sdbRelaxed(), goldenNow)
	roleCodes := dbRefusalCodes
	for _, pod := range []string{"pg-cnpg-1", "pg-cnpg-2", "mongo-0", "mysql-0"} {
		found := false
		for _, b := range p.Blocked {
			for _, x := range b.Blockers {
				if x.Pod != sdbNS+"/"+pod {
					continue
				}
				for _, rc := range roleCodes {
					found = found || strings.HasPrefix(x.Code, rc)
				}
			}
		}
		if !found {
			t.Errorf("%s: no DB-specific blocker recorded: %v", pod, sdbBlockers(p))
		}
	}
}

// TestStatefulDBI15EmptyDirDBNeverPlanned: an emptyDir database is never part of
// a move, whatever its replica count and the policy toggles.
func TestStatefulDBI15EmptyDirDBNeverPlanned(t *testing.T) {
	o := loadStatefulDB(t)
	sdbRemovePod(o, "mongo-0")
	sdbSetSTSReplicas(o, "pg-unsafe", 2)
	p := Analyze(o.input(), sdbRelaxed(), goldenNow)
	for _, m := range p.Moves {
		if m.Node.Name == "db-node-3" {
			t.Errorf("db-node-3 (emptyDir DB pg-unsafe-0) planned as %s: %v", m.Type, m.Pods)
		}
	}
}
