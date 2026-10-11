package fitsim

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"

	"github.com/xamops/automation/internal/policy"
)

// Synthetic fixtures written to testdata/ as kubectl-style v1 Lists so the Python
// oracle (eks/test-clusters/harness/fit_ground_truth.py) can read the same input.
// Regenerate with FITSIM_WRITE_TESTDATA=1 go test ./internal/fitsim -run Testdata,
// then re-run the oracle from internal/fitsim (H=../../../eks/test-clusters/harness):
//
//	python3 $H/fit_ground_truth.py testdata/section4.json --json > testdata/section4.oracle.json
//	python3 $H/fit_ground_truth.py testdata/positive.json --json > testdata/positive.oracle.json
//	python3 $H/fit_ground_truth.py testdata/rules.json --critical kube-system,monitoring --json > testdata/rules.oracle.json
//
// testdata/xamops-test-1-2026-10-10.json is a reduced read-only live snapshot
// (fit_ground_truth.py --reduce); its oracle file used --policy policies/10-analyse-only.json.
var syntheticFixtures = map[string]func() *objects{
	"section4": section4Fixture,
	"positive": positiveFixture,
	"rules":    rulesFixture,
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

func TestTestdataFixturesUpToDate(t *testing.T) {
	write := os.Getenv("FITSIM_WRITE_TESTDATA") == "1"
	for name, build := range syntheticFixtures {
		path := filepath.Join("testdata", name+".json")
		want := listJSON(t, build())
		if write {
			if err := os.WriteFile(path, want, 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		got, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%s: %v (regenerate with FITSIM_WRITE_TESTDATA=1)", path, err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s is stale: regenerate with FITSIM_WRITE_TESTDATA=1 and re-run the oracle", path)
		}
	}
}

// loadList decodes a kubectl `get ... -o json` List into typed objects.
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
		}
		if err != nil {
			t.Fatalf("%s: decode %s: %v", path, head.Kind, err)
		}
	}
	return o
}

type oracleFile struct {
	Options struct {
		CriticalNamespaces        []string `json:"criticalNamespaces"`
		ProtectCriticalNamespaces bool     `json:"protectCriticalNamespaces"`
		ProtectSingleReplicas     bool     `json:"protectSingleReplicas"`
		ProtectStatefulSets       bool     `json:"protectStatefulSets"`
		SelfNamespace             string   `json:"selfNamespace"`
	} `json:"options"`
	Nodes map[string]map[string]interface{} `json:"nodes"`
}

var comparedKeys = []string{"drainable", "excludedCount", "excluded", "refused", "blocked", "strands", "moves", "pdbChecks"}

// normalize keeps the compared keys of a plan decoded from JSON (errors: absent == []).
func normalize(m map[string]interface{}) map[string]interface{} {
	out := map[string]interface{}{}
	for _, k := range comparedKeys {
		out[k] = m[k]
	}
	errs, _ := m["errors"].([]interface{})
	out["errors"] = len(errs)
	return out
}

// Every testdata/<name>.oracle.json (fit_ground_truth.py --json on <name>.json) must
// equal the Go simulator's plans, node for node and key for key.
func TestOracleAgreement(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("testdata", "*.oracle.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) < len(syntheticFixtures) {
		t.Fatalf("expected an oracle file per synthetic fixture, found %v", files)
	}
	for _, of := range files {
		input := strings.TrimSuffix(of, ".oracle.json") + ".json"
		raw, err := os.ReadFile(of)
		if err != nil {
			t.Fatal(err)
		}
		var oracle oracleFile
		if err := json.Unmarshal(raw, &oracle); err != nil {
			t.Fatalf("%s: %v", of, err)
		}
		opts := Options{
			Guards: policy.SafetyGuards{
				ProtectCriticalNamespaces: oracle.Options.ProtectCriticalNamespaces,
				ProtectSingleReplicas:     oracle.Options.ProtectSingleReplicas,
				ProtectStatefulSets:       oracle.Options.ProtectStatefulSets,
				CriticalNamespaces:        oracle.Options.CriticalNamespaces,
			},
			SelfNamespace: oracle.Options.SelfNamespace,
		}
		s := loadList(t, input).snapshot()
		if len(oracle.Nodes) == 0 {
			t.Fatalf("%s: no nodes", of)
		}
		var sets []string
		for k := range oracle.Nodes {
			sets = append(sets, k)
		}
		sort.Strings(sets)
		drainable := 0
		for _, set := range sets {
			b, _ := json.Marshal(SimulateDrain(s, strings.Split(set, ","), opts))
			var got map[string]interface{}
			_ = json.Unmarshal(b, &got)
			g, w := normalize(got), normalize(oracle.Nodes[set])
			if !reflect.DeepEqual(g, w) {
				gj, _ := json.Marshal(g)
				wj, _ := json.Marshal(w)
				t.Errorf("%s set %s differs:\n go:     %s\n oracle: %s", filepath.Base(of), set, gj, wj)
			}
			if got["drainable"] == true {
				drainable++
			}
		}
		t.Logf("%s: %d set(s) agree (%d drainable)", filepath.Base(of), len(sets), drainable)
	}
}

func ExampleSimulateDrain() {
	s := positiveFixture().snapshot()
	p := SimulateDrain(s, []string{"node-d"}, testOptions())
	fmt.Println(p.Drainable, len(p.Moves), p.ExcludedCount)
	// Output: true 3 5
}
