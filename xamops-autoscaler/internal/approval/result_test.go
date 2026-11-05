package approval

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

func drainOutcome(status string, verified bool, reason string) drain.Outcome {
	return drain.Outcome{Status: status, Actions: []policy.ActionRecord{{
		Controller: "evictor", Action: "drain", Status: status, Verified: verified, DryRun: false, Reason: reason}}}
}

func TestFromDrainMapping(t *testing.T) {
	for _, tc := range []struct {
		name     string
		o        drain.Outcome
		mode     gate.Mode
		status   string
		verified bool
		dry      bool
	}{
		{"verified", drainOutcome(policy.ActionSucceeded, true, ""), gate.Live, StatusSucceeded, true, false},
		{"unverified", drainOutcome(policy.ActionSucceeded, false, "read-back"), gate.Live, StatusSucceeded, false, false},
		{"simulated", drainOutcome(policy.ActionSimulated, false, ""), gate.Simulate, StatusSimulated, false, true},
		{"refused", drainOutcome(policy.ActionRefused, false, "pdb"), gate.Live, StatusRefused, false, false},
		{"skipped", drainOutcome(policy.ActionSkipped, false, "budget"), gate.Live, StatusSkipped, false, false},
		{"aborted", drainOutcome(policy.ActionAborted, false, "x"), gate.Live, StatusAborted, false, false},
		{"failed", drainOutcome(policy.ActionFailed, false, "x"), gate.Live, StatusFailed, false, false},
		{"unknown status never succeeds", drainOutcome("weird", true, ""), gate.Live, StatusFailed, false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			o := FromDrain("p1", tc.o, tc.mode)
			if o.Status != tc.status || o.Verified != tc.verified || o.DryRun != tc.dry ||
				o.OperationID != "proposal-p1" || o.Stage != StageExecution || o.ProposalID != "p1" {
				t.Errorf("outcome = %+v", o)
			}
		})
	}
}

func TestFromConsolidationMapping(t *testing.T) {
	run := func(status string) *consolidation.ConsolidationRunDto {
		return &consolidation.ConsolidationRunDto{Nodes: []consolidation.ConsolidationNodeRun{{Status: status, Reason: "r"}}}
	}
	for status, want := range map[string]string{
		consolidation.RunStatusRemoved:   StatusSucceeded,
		consolidation.RunStatusDeferred:  StatusSucceeded,
		consolidation.RunStatusSimulated: StatusSimulated,
		consolidation.RunStatusSkipped:   StatusStale,
		consolidation.RunStatusRefused:   StatusRefused,
		consolidation.RunStatusAborted:   StatusAborted,
	} {
		if o := FromConsolidation("p1", run(status), nil, gate.Live); o.Status != want {
			t.Errorf("%s -> %s, want %s", status, o.Status, want)
		}
	}
	if o := FromConsolidation("p1", nil, nil, gate.Live); o.Status != StatusFailed {
		t.Errorf("no run -> %s, want failed", o.Status)
	}
	if o := FromConsolidation("p1", run(consolidation.RunStatusRemoved), nil, gate.Live); !o.Verified || string(o.Consolidation) == "null" {
		t.Errorf("removed must be verified and carry the run: %+v", o)
	}
}

func TestOutcomeLimitsAreClipped(t *testing.T) {
	var many []map[string]int
	for i := 0; i < 80; i++ {
		many = append(many, map[string]int{"i": i})
	}
	o := NewOutcome("p1", StatusFailed, StageExecution, strings.Repeat("é", 1500)).WithDetail(many, many, nil).Clamp()
	var a []interface{}
	_ = json.Unmarshal(o.Actions, &a)
	if len(a) != 50 {
		t.Errorf("actions clipped to %d, want 50", len(a))
	}
	if n := len([]rune(o.Reason)); n > 1000 {
		t.Errorf("reason has %d runes, want <= 1000", n)
	}
	if string(o.Consolidation) != "null" {
		t.Errorf("consolidation = %s, want null", o.Consolidation)
	}
}
