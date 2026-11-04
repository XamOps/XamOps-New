package approval

import (
	"strings"

	"github.com/xamops/automation/internal/consolidation"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

// FromDrain maps one Executor.Drain / CordonOnly outcome to a proposal outcome:
//
//	succeeded+verified -> succeeded, verified=true
//	succeeded          -> succeeded, verified=false (cordon read-back unconfirmed)
//	simulated          -> simulated
//	refused            -> refused (stage EXECUTION)
//	skipped/aborted/failed -> skipped/aborted/failed (the backend records FAILED)
func FromDrain(proposalID string, o drain.Outcome, mode gate.Mode) Outcome {
	status := o.Status
	switch status {
	case policy.ActionSucceeded, policy.ActionSimulated, policy.ActionRefused,
		policy.ActionSkipped, policy.ActionAborted, policy.ActionFailed:
	default:
		status = StatusFailed // unknown => never reported as success
	}
	reason := ""
	if len(o.Actions) > 0 {
		reason = o.Actions[0].Reason
	}
	out := NewOutcome(proposalID, status, StageExecution, reason).WithDetail(o.Actions, o.Reports, nil)
	out.DryRun = mode != gate.Live
	out.Verified = status == StatusSucceeded && o.Verified()
	return out
}

// FromConsolidation maps a single-move consolidation run to a proposal outcome:
//
//	removed / deferred  -> succeeded, verified (the drain verified)
//	simulated           -> simulated
//	skipped             -> stale (dwell, cap or recheck dropped it; never forced)
//	refused             -> refused (stage EXECUTION)
//	aborted             -> aborted
func FromConsolidation(proposalID string, run *consolidation.ConsolidationRunDto, actions []policy.ActionRecord, mode gate.Mode) Outcome {
	out := NewOutcome(proposalID, StatusFailed, StageExecution, "consolidation produced no result")
	if run != nil && len(run.Nodes) > 0 {
		nr := run.Nodes[0]
		out.Reason = nr.Reason
		switch nr.Status {
		case consolidation.RunStatusRemoved, consolidation.RunStatusDeferred:
			out.Status, out.Verified = StatusSucceeded, true
		case consolidation.RunStatusSimulated:
			out.Status = StatusSimulated
		case consolidation.RunStatusSkipped:
			out.Status = StatusStale
		case consolidation.RunStatusRefused:
			out.Status = StatusRefused
		case consolidation.RunStatusAborted:
			out.Status = StatusAborted
		default:
			out.Status, out.Reason = StatusFailed, "unknown consolidation status "+nr.Status
		}
	}
	var runAny interface{}
	if run != nil {
		runAny = run
	}
	out = out.WithDetail(actions, nil, runAny)
	out.DryRun = mode != gate.Live
	return out
}

// Refused builds a refused/EXECUTION outcome (a closed intent gate, a failed
// benefit guard, a plan that is no longer drainable).
func Refused(proposalID, reason string) Outcome {
	return NewOutcome(proposalID, StatusRefused, StageExecution, reason)
}

// Stale builds a stale outcome; nothing was touched.
func Stale(proposalID, reason string) Outcome {
	return NewOutcome(proposalID, StatusStale, StageExecution, reason)
}

// Failed builds a failed outcome for an agent-side error before anything ran.
func Failed(proposalID, reason string) Outcome {
	return NewOutcome(proposalID, StatusFailed, StageExecution, reason)
}

// JoinCodes renders refusal codes as the human reason.
func JoinCodes(codes []string) string { return strings.Join(codes, "; ") }
