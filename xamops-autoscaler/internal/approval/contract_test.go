package approval

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"testing"
)

// The golden fixtures are byte-identical to the Java copies
// (scripts/check-approval-contract-sync.sh). Envelope fields are strict
// (DisallowUnknownFields, build check B6); params, evidence, actions, reports and
// consolidation are opaque JSON and only checked for being the right JSON type.

func fixtureBytes(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "contract", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func strictDecode(t *testing.T, b []byte, into interface{}) {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(into); err != nil {
		t.Fatalf("strict decode: %v", err)
	}
}

// sameJSON compares two documents semantically (formatting and key order ignored).
func sameJSON(t *testing.T, want, got []byte) {
	t.Helper()
	var w, g interface{}
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(w, g) {
		t.Errorf("round trip changed the document:\nwant %s\ngot  %s", want, got)
	}
}

func TestContractProposalBatchStrictAndRoundTrips(t *testing.T) {
	raw := fixtureBytes(t, "proposal_batch.json")
	var b Batch
	strictDecode(t, raw, &b)
	if b.SchemaVersion != 1 || len(b.Items) != 3 || b.Rebalance == nil || !b.Rebalance.Complete {
		t.Fatalf("batch = %+v", b)
	}
	fp := regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)
	for _, it := range b.Items {
		if got := DedupeKey(it.ActionType, it.Operation, it.OriginController, it.Target.Name); got != it.DedupeKey {
			t.Errorf("DedupeKey = %q, fixture has %q", got, it.DedupeKey)
		}
		if !fp.MatchString(it.PlanFingerprint) {
			t.Errorf("fingerprint %q is not sha256:<64 hex>", it.PlanFingerprint)
		}
		var params, evidence map[string]json.RawMessage
		if json.Unmarshal(it.Params, &params) != nil || len(params) == 0 {
			t.Errorf("%s: params must be a non-empty object", it.DedupeKey)
		}
		if json.Unmarshal(it.Evidence, &evidence) != nil || len(evidence) == 0 {
			t.Errorf("%s: evidence must be a non-empty object", it.DedupeKey)
		}
	}
	// An ALLOWED drain item carries a real fit with a move and a PDB check.
	var ev struct {
		Fit struct {
			Moves     []json.RawMessage `json:"moves"`
			PDBChecks []json.RawMessage `json:"pdbChecks"`
		} `json:"fit"`
	}
	_ = json.Unmarshal(b.Items[0].Evidence, &ev)
	if len(ev.Fit.Moves) == 0 || len(ev.Fit.PDBChecks) == 0 {
		t.Errorf("fixture evidence lacks a move and a PDB check: %s", b.Items[0].Evidence)
	}
	if b.Items[0].DwellSeconds != nil || b.Items[1].DwellSeconds == nil || *b.Items[1].DwellSeconds != 600 {
		t.Errorf("dwellSeconds: drain must be null, consolidate 600: %v / %v", b.Items[0].DwellSeconds, b.Items[1].DwellSeconds)
	}
	if b.Items[2].Verdict != VerdictRefused || len(b.Items[2].Refusal.Codes) == 0 {
		t.Errorf("third item must be REFUSED with codes: %+v", b.Items[2])
	}
	out, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	sameJSON(t, raw, out)
}

func TestContractOutcomeStrictAndRoundTrips(t *testing.T) {
	raw := fixtureBytes(t, "outcome.json")
	var o Outcome
	strictDecode(t, raw, &o)
	if o.OperationID != OperationID(o.ProposalID) || o.Status != StatusSucceeded || !o.Verified || o.Stage != StageExecution {
		t.Fatalf("outcome = %+v", o)
	}
	for name, part := range map[string]json.RawMessage{"actions": o.Actions, "reports": o.Reports} {
		var l []map[string]interface{}
		if json.Unmarshal(part, &l) != nil || len(l) == 0 {
			t.Errorf("%s must be a non-empty array of objects", name)
		}
	}
	var c map[string]interface{}
	if json.Unmarshal(o.Consolidation, &c) != nil || len(c) == 0 {
		t.Errorf("consolidation must be a non-empty object")
	}
	out, err := json.Marshal(o)
	if err != nil {
		t.Fatal(err)
	}
	sameJSON(t, raw, out)
}

func TestContractDispatchedStrictAndRoundTrips(t *testing.T) {
	raw := fixtureBytes(t, "dispatched.json")
	var l DispatchedList
	strictDecode(t, raw, &l)
	if len(l.Items) != 1 || l.Items[0].OperationID != OperationID(l.Items[0].ID) {
		t.Fatalf("dispatched = %+v", l)
	}
	out, _ := json.Marshal(l)
	sameJSON(t, raw, out)
}

func TestContractApprovedActionStrict(t *testing.T) {
	for _, tc := range []struct {
		file  string
		dwell *int
	}{{"approved_action.json", nil}, {"approved_action_consolidate.json", intp(120)}} {
		raw := fixtureBytes(t, tc.file)
		a, err := ParseApprovedAction(raw, true)
		if err != nil {
			t.Fatalf("%s: %v", tc.file, err)
		}
		if !reflect.DeepEqual(a.DwellSeconds, tc.dwell) {
			t.Errorf("%s: dwellSeconds = %v, want %v", tc.file, a.DwellSeconds, tc.dwell)
		}
		if got := DedupeKey(a.ActionType, a.Operation, a.OriginController, a.Target.Name); got != a.DedupeKey {
			t.Errorf("%s: DedupeKey = %q, fixture %q", tc.file, got, a.DedupeKey)
		}
		if a.FirstSeenAt.IsZero() || a.ApprovedUntil.IsZero() {
			t.Errorf("%s: times not parsed: %+v", tc.file, a)
		}
		out, _ := json.Marshal(a)
		sameJSON(t, raw, out)
	}
	a, _ := ParseApprovedAction(fixtureBytes(t, "approved_action_consolidate.json"), true)
	if p := a.ParsedParams(); p.MoveType != "EMPTY" || p.NodeGroup != "ng-2" || p.UnschedulableAtProposal {
		t.Errorf("params = %+v", p)
	}
}

// B6: the strict parse really is strict, and production decoding really is lenient.
func TestContractUnknownEnvelopeFieldFailsStrictOnly(t *testing.T) {
	raw := bytes.Replace(fixtureBytes(t, "approved_action.json"), []byte(`"version": 3,`), []byte(`"version": 3, "surprise": true,`), 1)
	if !strings.Contains(string(raw), "surprise") {
		t.Fatal("fixture edit did not apply")
	}
	if _, err := ParseApprovedAction(raw, true); err == nil {
		t.Error("strict parse accepted an unknown envelope field")
	}
	if _, err := ParseApprovedAction(raw, false); err != nil {
		t.Errorf("lenient parse rejected an unknown envelope field: %v", err)
	}
	var b Batch
	dec := json.NewDecoder(bytes.NewReader(bytes.Replace(fixtureBytes(t, "proposal_batch.json"), []byte(`"cycleId"`), []byte(`"extra": 1, "cycleId"`), 1)))
	dec.DisallowUnknownFields()
	if dec.Decode(&b) == nil {
		t.Error("strict batch decode accepted an unknown field")
	}
}

func intp(n int) *int { return &n }
