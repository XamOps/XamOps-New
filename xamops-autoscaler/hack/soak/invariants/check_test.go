package invariants

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

func load(t *testing.T, name string) *Timeline {
	t.Helper()
	tl, err := Load(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return tl
}

// Every recorded fixture stream, judged by the event-sourced checker.
func TestCheckFixtures(t *testing.T) {
	opts := DefaultOptions()
	short := opts
	short.LeaderTolerance = 500 * time.Millisecond
	cases := []struct {
		fixture string
		opts    Options
		want    map[string]string // invariant -> verdict
		// breachMs pins the measured duration of the first breach of an invariant.
		breachMs map[string]int64
		excluded map[string]int
	}{
		{fixture: "clean", opts: opts, want: map[string]string{"I1": Pass, "I2": Pass, "I6": Pass, "I14": Pass}},
		{fixture: "i1-unbudgeted-delete", opts: opts, want: map[string]string{"I1": Fail, "I2": NotEvaluated, "I6": NotEvaluated, "I14": NotEvaluated}},
		{fixture: "i2-short-dip", opts: opts, want: map[string]string{"I2": Fail}, breachMs: map[string]int64{"I2": 300}},
		// HPA 3->6 with no PDB: not-yet-Ready new pods are not a breach; scale-down applies at once.
		{fixture: "i2-scale-up", opts: opts, want: map[string]string{"I2": Pass}},
		// During a pending scale-up the previous floor still holds.
		{fixture: "i2-dip-during-scale-up", opts: opts, want: map[string]string{"I2": Fail}, breachMs: map[string]int64{"I2": 200}},
		// Once the new floor is reached it applies.
		{fixture: "i2-dip-after-scale-up", opts: opts, want: map[string]string{"I2": Fail}, breachMs: map[string]int64{"I2": 150}},
		{fixture: "i1-attribution", opts: opts, want: map[string]string{"I1": Fail, "I6": Pass}},
		{fixture: "i2-never-healthy", opts: opts, want: map[string]string{"I2": Pass}, excluded: map[string]int{"I2": 1}},
		{fixture: "i6-short-overlap", opts: opts, want: map[string]string{"I6": Fail}, breachMs: map[string]int64{"I6": 250}},
		{fixture: "i14-non-leader", opts: opts, want: map[string]string{"I14": Fail}},
		{fixture: "i14-handover-within-tolerance", opts: opts, want: map[string]string{"I14": Pass}},
		{fixture: "i14-handover-within-tolerance", opts: short, want: map[string]string{"I14": Fail}},
		{fixture: "missing-streams", opts: opts, want: map[string]string{"I1": NotEvaluated, "I2": NotEvaluated, "I6": Pass, "I14": NotEvaluated}},
	}
	for _, tc := range cases {
		t.Run(tc.fixture+"/tol="+tc.opts.LeaderTolerance.String(), func(t *testing.T) {
			rep := Check(load(t, tc.fixture), tc.opts)
			if len(rep.Errors) != 0 {
				t.Fatalf("decode errors: %v", rep.Errors)
			}
			for id, want := range tc.want {
				r := rep.Get(id)
				if r.Verdict != want {
					t.Errorf("%s = %s, want %s\n%s", id, r.Verdict, want, rep.Text())
				}
				if want == Fail && len(r.Breaches) == 0 {
					t.Errorf("%s FAIL without a breach", id)
				}
			}
			for id, ms := range tc.breachMs {
				r := rep.Get(id)
				if len(r.Breaches) != 1 || r.Breaches[0].DurationMs != ms {
					t.Errorf("%s breaches = %+v, want one of %d ms", id, r.Breaches, ms)
				}
			}
			for id, n := range tc.excluded {
				if got := len(rep.Get(id).Excluded); got != n {
					t.Errorf("%s excluded = %d, want %d", id, got, n)
				}
			}
			// The report always marshals (it is written as invariants-report.json).
			if _, err := json.Marshal(rep); err != nil {
				t.Fatal(err)
			}
		})
	}
}

// sampledI2 is what a fixed-interval poller sees: at each sample time it takes the
// latest Deployment and PDB state recorded at or before that time.
func sampledI2(t *testing.T, tl *Timeline, interval time.Duration) []time.Time {
	t.Helper()
	var breaches []time.Time
	start, end := tl.Records[0].TS, tl.Records[len(tl.Records)-1].TS
	for at := start; !at.After(end); at = at.Add(interval) {
		var d *appsv1.Deployment
		var pdbs []policyv1.PodDisruptionBudget
		for _, r := range tl.Records {
			if r.TS.After(at) {
				break
			}
			switch r.Stream {
			case StreamDeployments:
				d = &appsv1.Deployment{}
				if err := json.Unmarshal(r.Object, d); err != nil {
					t.Fatal(err)
				}
			case StreamPDBs:
				p := policyv1.PodDisruptionBudget{}
				if err := json.Unmarshal(r.Object, &p); err != nil {
					t.Fatal(err)
				}
				pdbs = []policyv1.PodDisruptionBudget{p}
			}
		}
		if d == nil {
			continue
		}
		if floor, _ := HAFloor(*d.Spec.Replicas, pdbs); d.Status.ReadyReplicas < floor {
			breaches = append(breaches, at)
		}
	}
	return breaches
}

// The required sub-interval breach: a 300 ms dip below the HA floor is invisible to
// 5 s and 10 s sampling and caught by the event-sourced checker.
func TestShortDipMissedBySamplingCaughtByChecker(t *testing.T) {
	tl := load(t, "i2-short-dip")
	for _, iv := range []time.Duration{5 * time.Second, 10 * time.Second} {
		if b := sampledI2(t, tl, iv); len(b) != 0 {
			t.Errorf("%s sampler saw a breach at %v; the fixture must hide the dip from sampling", iv, b)
		}
	}
	r := Check(tl, DefaultOptions()).Get("I2")
	if r.Verdict != Fail || len(r.Breaches) != 1 || r.Breaches[0].DurationMs != 300 {
		t.Fatalf("checker I2 = %+v", r)
	}
}

func TestHAFloor(t *testing.T) {
	pdb := func(min, max *intstr.IntOrString) policyv1.PodDisruptionBudget {
		return policyv1.PodDisruptionBudget{ObjectMeta: metav1.ObjectMeta{Namespace: "ns", Name: "p"},
			Spec: policyv1.PodDisruptionBudgetSpec{MinAvailable: min, MaxUnavailable: max}}
	}
	i := func(v int) *intstr.IntOrString { x := intstr.FromInt(v); return &x }
	s := func(v string) *intstr.IntOrString { x := intstr.FromString(v); return &x }
	cases := []struct {
		name     string
		replicas int32
		pdbs     []policyv1.PodDisruptionBudget
		want     int32
	}{
		{"no PDB: replicas-1", 3, nil, 2},
		{"no PDB, single replica: min 1", 1, nil, 1},
		{"no PDB, zero replicas", 0, nil, 0},
		{"minAvailable int", 5, []policyv1.PodDisruptionBudget{pdb(i(2), nil)}, 2},
		{"minAvailable percent rounds up", 3, []policyv1.PodDisruptionBudget{pdb(s("50%"), nil)}, 2},
		{"maxUnavailable int", 4, []policyv1.PodDisruptionBudget{pdb(nil, i(1))}, 3},
		{"maxUnavailable percent rounds up", 3, []policyv1.PodDisruptionBudget{pdb(nil, s("50%"))}, 1},
		{"maxUnavailable above replicas: 0", 2, []policyv1.PodDisruptionBudget{pdb(nil, i(5))}, 0},
		{"minAvailable above replicas: capped", 2, []policyv1.PodDisruptionBudget{pdb(i(5), nil)}, 2},
		{"largest of several PDBs", 4, []policyv1.PodDisruptionBudget{pdb(i(1), nil), pdb(nil, i(1))}, 3},
		{"PDB without either field: replicas-1", 3, []policyv1.PodDisruptionBudget{pdb(nil, nil)}, 2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got, src := HAFloor(tc.replicas, tc.pdbs); got != tc.want {
				t.Errorf("HAFloor = %d (%s), want %d", got, src, tc.want)
			}
		})
	}
}

// I1 cannot see who deleted a pod: each breach says whether the pod's node was
// draining under XamOps, so a controller delete elsewhere can be attributed.
func TestI1BreachAttribution(t *testing.T) {
	r := Check(load(t, "i1-attribution"), DefaultOptions()).Get("I1")
	if r.Verdict != Fail || len(r.Breaches) != 2 || !strings.Contains(r.Detail, "attribution") {
		t.Fatalf("I1 = %+v", r)
	}
	if b := r.Breaches[0]; b.Object != "pod team-a/web-1" || !strings.Contains(b.Detail, "node n-1 was draining under XamOps") {
		t.Errorf("draining-node breach = %+v", b)
	}
	if b := r.Breaches[1]; b.Object != "pod team-a/web-2" || !strings.Contains(b.Detail, "node n-2 was not draining under XamOps") ||
		!strings.Contains(b.Detail, "events.jsonl") {
		t.Errorf("controller-delete breach = %+v", b)
	}
}
