package drain

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	"k8s.io/client-go/kubernetes/scheme"

	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/policy"
)

// Evictability on the anonymized stateful DB fixture export
// (../fitsim/testdata/stateful-db-fixtures.json, documented in
// internal/fitsim/statefuldb_test.go): FitPlanner is what the evictor, rebalancer
// and node optimizer hand to the executor, so its Moves are the evictions that
// would happen. TestStatefulDBToday* pins TODAY's behaviour
// (.agents/tasks/stateful-db-fixtures-2026-10-24/investigation.md sections 2.3,
// 4.3); TestStatefulDBI15* is the desired invariant, skipped.

func statefulDBClient(t *testing.T, dropPods ...string) *fake.Clientset {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "fitsim", "testdata", "stateful-db-fixtures.json"))
	if err != nil {
		t.Fatal(err)
	}
	var list struct {
		Items []json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatal(err)
	}
	drop := map[string]bool{}
	for _, p := range dropPods {
		drop[p] = true
	}
	decode := scheme.Codecs.UniversalDeserializer()
	var objs []runtime.Object
	for _, it := range list.Items {
		obj, _, err := decode.Decode(it, nil, nil)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if m, ok := obj.(interface{ GetName() string }); ok && drop[m.GetName()] {
			continue
		}
		objs = append(objs, obj)
	}
	return fake.NewSimpleClientset(objs...)
}

func statefulDBOpts(protect bool) fitsim.Options {
	return fitsim.Options{
		Guards: policy.SafetyGuards{
			RespectPodDisruptionBudgets: true,
			ProtectSingleReplicas:       protect,
			ProtectStatefulSets:         protect,
			ProtectCriticalNamespaces:   true,
			CriticalNamespaces:          []string{"kube-system", "monitoring", "ingress-nginx", "xamops-system"},
		},
		SelfNamespace: "xamops-system",
	}
}

type evictionPair struct{ pod, to string }

func dbEvictions(p Plan) []evictionPair {
	out := []evictionPair{}
	for _, m := range p.Moves {
		out = append(out, evictionPair{m.Namespace + "/" + m.Name, m.To})
	}
	return out
}

func TestStatefulDBFitPlannerEvictions(t *testing.T) {
	cases := []struct {
		name          string
		drop          []string
		node          string
		protect       bool
		wantDrainable bool
		wantReasons   []string
		wantEvictions []evictionPair
	}{
		{
			name: "1a live guards: both DB pods refused db-emptydir, nothing evicted",
			node: "db-node-3", protect: true,
			wantReasons: []string{
				"refused db-fixtures/mongo-0: db-emptydir",
				"refused db-fixtures/pg-unsafe-0: db-emptydir",
			},
			wantEvictions: []evictionPair{},
		},
		{
			name: "1a without mongo-0, live guards: the emptyDir DB is refused db-emptydir",
			drop: []string{"mongo-0"}, node: "db-node-3", protect: true,
			wantReasons:   []string{"refused db-fixtures/pg-unsafe-0: db-emptydir"},
			wantEvictions: []evictionPair{},
		},
		{
			// I15: with every policy toggle off, the emptyDir database is STILL
			// refused and never handed to the executor, where the pre-engine
			// behaviour evicted it (data loss).
			name: "1a without mongo-0, relaxed guards: emptyDir DB still refused, nothing evicted",
			drop: []string{"mongo-0"}, node: "db-node-3", protect: false,
			wantReasons:   []string{"refused db-fixtures/pg-unsafe-0: db-emptydir"},
			wantEvictions: []evictionPair{},
		},
		{
			// The CNPG primary's PDB (0 allowed) never shows: Refused skips it, so
			// the plan is not even Retryable.
			name: "1c relaxed guards: CNPG primary and Mongo refused db-emptydir",
			node: "db-node-1", protect: false,
			wantReasons: []string{
				"refused db-fixtures/mongo-2: db-emptydir",
				"refused db-fixtures/pg-cnpg-1: db-emptydir",
			},
			wantEvictions: []evictionPair{},
		},
		{
			name: "1b relaxed guards: mysql-0 and the sync standby refused db-emptydir",
			node: "db-node-2", protect: false,
			wantReasons: []string{
				"refused db-fixtures/mongo-1: db-emptydir",
				"refused db-fixtures/mysql-0: db-emptydir",
				"refused db-fixtures/pg-cnpg-2: db-emptydir",
			},
			wantEvictions: []evictionPair{},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			planner := NewFitPlanner(statefulDBClient(t, c.drop...), nil, statefulDBOpts(c.protect))
			p, err := planner.Plan(context.Background(), []string{c.node})
			if err != nil {
				t.Fatal(err)
			}
			if !p.FitChecked {
				t.Fatalf("plan not fit-checked: %+v", p.Reasons)
			}
			if p.Drainable != c.wantDrainable {
				t.Errorf("drainable = %v, want %v (%v)", p.Drainable, c.wantDrainable, p.Reasons)
			}
			if p.Retryable {
				t.Errorf("retryable = true, want false (%v)", p.Reasons)
			}
			if !reflect.DeepEqual(p.Reasons, c.wantReasons) {
				t.Errorf("reasons = %q, want %q", p.Reasons, c.wantReasons)
			}
			if got := dbEvictions(p); !reflect.DeepEqual(got, c.wantEvictions) {
				t.Errorf("evictions = %v, want %v", got, c.wantEvictions)
			}
		})
	}
}

// TestStatefulDBI15NoProtectedDBEviction: whatever the policy toggles, the planner
// never hands the executor an eviction of a protected DB pod (primary, sync
// standby, only ready replica, unknown role, emptyDir DB), and records why.
func TestStatefulDBI15NoProtectedDBEviction(t *testing.T) {
	protected := map[string]bool{
		"db-fixtures/pg-cnpg-1": true, "db-fixtures/pg-cnpg-2": true, "db-fixtures/mongo-0": true,
		"db-fixtures/mysql-0": true, "db-fixtures/pg-unsafe-0": true,
	}
	for _, drop := range [][]string{nil, {"mongo-0"}} {
		for _, node := range []string{"db-node-1", "db-node-2", "db-node-3"} {
			for _, protect := range []bool{true, false} {
				planner := NewFitPlanner(statefulDBClient(t, drop...), nil, statefulDBOpts(protect))
				p, err := planner.Plan(context.Background(), []string{node})
				if err != nil {
					t.Fatal(err)
				}
				for _, e := range dbEvictions(p) {
					if protected[e.pod] {
						t.Errorf("drop=%v node=%s protect=%v: protected DB pod %s planned for eviction to %s", drop, node, protect, e.pod, e.to)
					}
				}
			}
		}
	}
}
