// Package telemetry builds ActionRecords ("autoscaling-actions") and maps them onto
// the legacy AutoscalingEvent ("autoscaling-events") without changing its key set.
//
// Honesty rules (plan.md D2a): severity "success" only for a verified live outcome;
// "warning" for failed/aborted; "info" otherwise. Simulations read "DRY-RUN
// (simulated): would …", refusals "Refused: … — <reason>", unverified live requests
// "… requested; outcome not verified". The words completed/drained/consolidated/
// applied appear only for verified live outcomes. Details carries the paired record's
// identity as compact JSON text and NodeCount counts verified nodes only.
package telemetry

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync/atomic"
	"time"

	"github.com/xamops/automation/internal/policy"
)

var seq uint64

// NewAction starts a record. The ID is unique per process and is also the ID of the
// paired legacy event.
func NewAction(controller, action, target string, dryRun bool) policy.ActionRecord {
	now := time.Now().UTC()
	n := atomic.AddUint64(&seq, 1)
	return policy.ActionRecord{
		ID:         fmt.Sprintf("%s-%s-%d-%d", controller, action, now.UnixNano(), n),
		Controller: controller,
		Action:     action,
		Target:     target,
		DryRun:     dryRun,
		StartedAt:  now.Format(time.RFC3339),
	}
}

// Finish sets the outcome of a record and stamps FinishedAt.
func Finish(rec policy.ActionRecord, status string, verified bool, reason string) policy.ActionRecord {
	rec.Status = status
	rec.Verified = verified
	rec.Reason = reason
	rec.FinishedAt = time.Now().UTC().Format(time.RFC3339)
	return rec
}

// SetDetail adds one key to the record's Details map.
func SetDetail(rec *policy.ActionRecord, key string, value interface{}) {
	if rec.Details == nil {
		rec.Details = map[string]interface{}{}
	}
	rec.Details[key] = value
}

// VerifiedLive reports whether a record is a verified live success — the only case
// that may be presented as done.
func VerifiedLive(rec policy.ActionRecord) bool {
	return rec.Status == policy.ActionSucceeded && rec.Verified && !rec.DryRun
}

// detailsJSON is the compact identity embedded in AutoscalingEvent.Details.
type detailsJSON struct {
	ActionID    string `json:"actionId"`
	DryRun      bool   `json:"dryRun"`
	Status      string `json:"status"`
	Verified    bool   `json:"verified"`
	OperationID string `json:"operationId"`
}

// pastTense is used only for verified live outcomes.
var pastTense = map[string]string{
	"drain":          "Drained",
	"cordon":         "Cordoned",
	"uncordon":       "Uncordoned",
	"rebalance":      "Rebalance completed for",
	"patch-requests": "Applied new requests to",
}

func humanAction(action string) string { return strings.ReplaceAll(action, "-", " ") }

func withReason(msg, reason string) string {
	if reason == "" {
		return msg
	}
	return msg + " — " + reason
}

// LegacyEvent maps a record onto the frozen AutoscalingEvent shape. nodeCount is the
// number of nodes the action covers; it is reported only for verified live outcomes.
func LegacyEvent(rec policy.ActionRecord, eventType string, nodeCount int) policy.AutoscalingEvent {
	ev := policy.AutoscalingEvent{
		ID:        rec.ID,
		Timestamp: rec.FinishedAt,
		Type:      eventType,
		Severity:  "info",
	}
	if ev.Timestamp == "" {
		ev.Timestamp = rec.StartedAt
	}

	what := strings.TrimSpace(humanAction(rec.Action) + " " + rec.Target)
	prefix := ""
	if rec.DryRun {
		prefix = "DRY-RUN (simulated): "
	}

	switch {
	case VerifiedLive(rec):
		ev.Severity = "success"
		ev.NodeCount = nodeCount
		verb, ok := pastTense[rec.Action]
		if !ok {
			verb = "Completed " + humanAction(rec.Action) + " on"
		}
		msg := verb + " " + rec.Target
		if rec.Verification != "" {
			msg += " (verified: " + rec.Verification + ")"
		}
		ev.Message = msg
	case rec.Status == policy.ActionFailed:
		ev.Severity = "warning"
		ev.Message = prefix + withReason("Failed: "+what, rec.Reason)
	case rec.Status == policy.ActionAborted:
		ev.Severity = "warning"
		ev.Message = prefix + withReason("Aborted: "+what, rec.Reason)
	case rec.Status == policy.ActionRefused:
		ev.Message = prefix + withReason("Refused: "+what, rec.Reason)
	case rec.Status == policy.ActionSkipped:
		ev.Message = prefix + withReason("Skipped: "+what, rec.Reason)
	case rec.DryRun || rec.Status == policy.ActionSimulated:
		ev.Message = withReason("DRY-RUN (simulated): would "+what, rec.Reason)
	default:
		// Live success without verification (or an unknown status).
		ev.Message = withReason(what+" requested; outcome not verified", rec.Reason)
	}

	d, _ := json.Marshal(detailsJSON{
		ActionID:    rec.ID,
		DryRun:      rec.DryRun,
		Status:      rec.Status,
		Verified:    rec.Verified,
		OperationID: rec.OperationID,
	})
	ev.Details = string(d)
	return ev
}
