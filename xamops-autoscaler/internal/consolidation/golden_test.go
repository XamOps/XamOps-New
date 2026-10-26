package consolidation

import (
	"bytes"
	"encoding/json"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"
)

// Golden plans. Inputs: the synthetic fixtures (testdata/<name>.json, regenerate
// with CONSOLIDATION_WRITE_TESTDATA=1 go test ./internal/consolidation -run Testdata)
// and the committed fitsim lists (../fitsim/testdata/<name>.json, golden name
// fitsim-<name>). Expected plans: testdata/golden/<name>.plan.json, rewritten only
// with CONSOLIDATION_UPDATE_GOLDEN=1 go test ./internal/consolidation -run Golden.
// The backend contract test reads these goldens.

var goldenNow = time.Date(2026, 10, 24, 0, 0, 0, 0, time.UTC)

var fitsimLists = []string{"section4", "positive", "rules", "xamops-test-1-2026-10-10"}

type goldenCase struct {
	name  string
	input string // path of the kubectl-style List
}

func goldenCases() []goldenCase {
	var out []goldenCase
	for name := range syntheticFixtures {
		out = append(out, goldenCase{name, filepath.Join("testdata", name+".json")})
	}
	for _, name := range fitsimLists {
		out = append(out, goldenCase{"fitsim-" + name, filepath.Join("..", "fitsim", "testdata", name+".json")})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].name < out[j].name })
	return out
}

func planJSON(t *testing.T, p Plan) []byte {
	t.Helper()
	b, err := json.MarshalIndent(p, "", " ")
	if err != nil {
		t.Fatal(err)
	}
	return append(b, '\n')
}

func goldenPath(name string) string { return filepath.Join("testdata", "golden", name+".plan.json") }

func TestTestdataFixturesUpToDate(t *testing.T) {
	write := os.Getenv("CONSOLIDATION_WRITE_TESTDATA") == "1"
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
			t.Fatalf("%s: %v (regenerate with CONSOLIDATION_WRITE_TESTDATA=1)", path, err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s is stale: regenerate with CONSOLIDATION_WRITE_TESTDATA=1", path)
		}
	}
}

func TestGoldenPlans(t *testing.T) {
	update := os.Getenv("CONSOLIDATION_UPDATE_GOLDEN") == "1"
	if update {
		if err := os.MkdirAll(filepath.Join("testdata", "golden"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for _, gc := range goldenCases() {
		t.Run(gc.name, func(t *testing.T) {
			got := planJSON(t, Analyze(loadList(t, gc.input).input(), testOptions(), goldenNow))
			path := goldenPath(gc.name)
			if update {
				if err := os.WriteFile(path, got, 0o644); err != nil {
					t.Fatal(err)
				}
				return
			}
			want, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("%s: %v (create with CONSOLIDATION_UPDATE_GOLDEN=1)", path, err)
			}
			if !bytes.Equal(got, want) {
				t.Errorf("%s: golden plan changed — a human must approve the rebaseline (CONSOLIDATION_UPDATE_GOLDEN=1)\n got:\n%s", path, got)
			}
		})
	}
}

// shuffled returns a copy of in with every slice shuffled.
func shuffled(in Input, r *rand.Rand) Input {
	out := Input{
		Nodes:        append(in.Nodes[:0:0], in.Nodes...),
		Pods:         append(in.Pods[:0:0], in.Pods...),
		PDBs:         append(in.PDBs[:0:0], in.PDBs...),
		ReplicaSets:  append(in.ReplicaSets[:0:0], in.ReplicaSets...),
		StatefulSets: append(in.StatefulSets[:0:0], in.StatefulSets...),
		Deployments:  append(in.Deployments[:0:0], in.Deployments...),
		PVCs:         append(in.PVCs[:0:0], in.PVCs...),
		PVs:          append(in.PVs[:0:0], in.PVs...),
		CRs:          append(in.CRs[:0:0], in.CRs...),
	}
	r.Shuffle(len(out.Nodes), func(i, j int) { out.Nodes[i], out.Nodes[j] = out.Nodes[j], out.Nodes[i] })
	r.Shuffle(len(out.Pods), func(i, j int) { out.Pods[i], out.Pods[j] = out.Pods[j], out.Pods[i] })
	r.Shuffle(len(out.PDBs), func(i, j int) { out.PDBs[i], out.PDBs[j] = out.PDBs[j], out.PDBs[i] })
	r.Shuffle(len(out.ReplicaSets), func(i, j int) {
		out.ReplicaSets[i], out.ReplicaSets[j] = out.ReplicaSets[j], out.ReplicaSets[i]
	})
	r.Shuffle(len(out.StatefulSets), func(i, j int) {
		out.StatefulSets[i], out.StatefulSets[j] = out.StatefulSets[j], out.StatefulSets[i]
	})
	r.Shuffle(len(out.Deployments), func(i, j int) {
		out.Deployments[i], out.Deployments[j] = out.Deployments[j], out.Deployments[i]
	})
	r.Shuffle(len(out.PVCs), func(i, j int) { out.PVCs[i], out.PVCs[j] = out.PVCs[j], out.PVCs[i] })
	r.Shuffle(len(out.PVs), func(i, j int) { out.PVs[i], out.PVs[j] = out.PVs[j], out.PVs[i] })
	r.Shuffle(len(out.CRs), func(i, j int) { out.CRs[i], out.CRs[j] = out.CRs[j], out.CRs[i] })
	return out
}

// The plan does not depend on input order: 25 shuffles per fixture give the golden
// bytes.
func TestGoldenPlansDeterministic(t *testing.T) {
	if os.Getenv("CONSOLIDATION_UPDATE_GOLDEN") == "1" {
		t.Skip("goldens are being rewritten")
	}
	for _, gc := range goldenCases() {
		want, err := os.ReadFile(goldenPath(gc.name))
		if err != nil {
			t.Fatal(err)
		}
		in := loadList(t, gc.input).input()
		for i := 0; i < 25; i++ {
			got := planJSON(t, Analyze(shuffled(in, rand.New(rand.NewSource(int64(i)))), testOptions(), goldenNow))
			if !bytes.Equal(got, want) {
				t.Errorf("%s: shuffle %d differs from the golden plan", gc.name, i)
				break
			}
		}
	}
}

func TestGoldenFileSet(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("testdata", "golden", "*.plan.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != len(goldenCases()) {
		t.Errorf("golden files %v, want one per case (%d)", files, len(goldenCases()))
	}
}
