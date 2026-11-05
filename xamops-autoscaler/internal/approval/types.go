// Package approval is the agent side of Manual mode (Phase B): the wire types shared
// with the backend, the HTTP client, the plan fingerprint, the in-memory outcome queue
// and the pure decision functions (re-validation, restart reconciliation). It does not
// depend on the controller package and performs no cluster writes.
//
// The wire structs mirror the golden fixtures in testdata/contract/, which are
// byte-identical to the Java copies (scripts/check-approval-contract-sync.sh). The
// envelope fields are strict in tests (DisallowUnknownFields); params, evidence,
// actions, reports and consolidation are opaque JSON.
package approval

import (
	"bytes"
	"encoding/json"
	"fmt"
	"time"
)

// SchemaVersion is the only proposal/outcome schema version.
const SchemaVersion = 1

// Action types the agent may propose (the backend rejects any other with BAD_TYPE).
const (
	TypeNodeDrain       = "NODE_DRAIN"
	TypeNodeConsolidate = "NODE_CONSOLIDATE"
	TypeRebalance       = "REBALANCE"
)

// Operations.
const (
	OpDrain      = "DRAIN"
	OpCordonOnly = "CORDON_ONLY"
	OpEmpty      = "EMPTY"
)

// Origin controllers (the proposal's originController).
const (
	OriginNodeOptimizer = "node-optimizer"
	OriginEvictor       = "evictor"
	OriginConsolidation = "consolidation"
	OriginRebalancer    = "rebalancer"
)

// Verdicts.
const (
	VerdictAllowed = "ALLOWED"
	VerdictRefused = "REFUSED"
)

// Outcome statuses accepted by the backend. StatusRunning is non-terminal.
const (
	StatusSucceeded  = "succeeded"
	StatusSimulated  = "simulated"
	StatusFailed     = "failed"
	StatusRefused    = "refused"
	StatusSkipped    = "skipped"
	StatusAborted    = "aborted"
	StatusStale      = "stale"
	StatusNotStarted = "not-started"
	StatusRunning    = "running"
)

// Outcome stages.
const (
	StagePropose   = "PROPOSAL"
	StageExecution = "EXECUTION"
)

// OperationID is the one idempotency key of every approved node action: the node's
// xamops.io/drain-operation annotation, every ActionRecord.OperationID and the
// outcome's operationId.
func OperationID(proposalID string) string { return "proposal-" + proposalID }

// Target identifies the node an action is about.
type Target struct {
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	UID        string `json:"uid"`
	ProviderID string `json:"providerId"`
	NodeGroup  string `json:"nodeGroup"`
}

// Refusal carries the machine reason codes of a REFUSED verdict.
type Refusal struct {
	Codes  []string `json:"codes"`
	Reason string   `json:"reason"`
}

// Gate is the intent gate decision the proposal was built under.
type Gate struct {
	Mode   string `json:"mode"`
	Reason string `json:"reason"`
	DryRun bool   `json:"dryRun"`
}

// Item is one proposal in a batch.
type Item struct {
	DedupeKey        string          `json:"dedupeKey"`
	ActionType       string          `json:"actionType"`
	Operation        string          `json:"operation"`
	OriginController string          `json:"originController"`
	Target           Target          `json:"target"`
	Params           json.RawMessage `json:"params"`
	Verdict          string          `json:"verdict"`
	Refusal          Refusal         `json:"refusal"`
	Gate             Gate            `json:"gate"`
	PlanFingerprint  string          `json:"planFingerprint"`
	DwellSeconds     *int            `json:"dwellSeconds"`
	Evidence         json.RawMessage `json:"evidence"`
}

// Rebalance is sent only on the final chunk of a cycle.
type Rebalance struct {
	Complete       bool `json:"complete"`
	CandidateCount int  `json:"candidateCount"`
}

// Batch is one POST /api/v1/agent/proposals body.
type Batch struct {
	SchemaVersion int        `json:"schemaVersion"`
	CycleID       string     `json:"cycleId"`
	ObservedAt    string     `json:"observedAt"`
	Items         []Item     `json:"items"`
	Rebalance     *Rebalance `json:"rebalance,omitempty"`
}

// BatchResponse is the 200 answer to a batch.
type BatchResponse struct {
	Accepted   int `json:"accepted"`
	Suppressed []struct {
		DedupeKey string `json:"dedupeKey"`
		Reason    string `json:"reason"`
	} `json:"suppressed"`
	Rejected []struct {
		DedupeKey string `json:"dedupeKey"`
		Code      string `json:"code"`
	} `json:"rejected"`
}

// QueueFull reports whether any item was rejected QUEUE_FULL.
func (r BatchResponse) QueueFull() bool {
	for _, x := range r.Rejected {
		if x.Code == "QUEUE_FULL" {
			return true
		}
	}
	return false
}

// Person is the approver as the claim reports it.
type Person struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

// ApprovedAction is the claim response: exactly one human-approved node proposal.
// DwellSeconds is non-nil only for NODE_CONSOLIDATE; the agent reads its dwell guard
// from here and never from its own current policy.
type ApprovedAction struct {
	ProposalID       string          `json:"proposalId"`
	Version          int             `json:"version"`
	ActionType       string          `json:"actionType"`
	Operation        string          `json:"operation"`
	OriginController string          `json:"originController"`
	DedupeKey        string          `json:"dedupeKey"`
	Target           Target          `json:"target"`
	Params           json.RawMessage `json:"params"`
	PlanFingerprint  string          `json:"planFingerprint"`
	FirstSeenAt      time.Time       `json:"firstSeenAt"`
	DwellSeconds     *int            `json:"dwellSeconds"`
	ApprovedBy       Person          `json:"approvedBy"`
	ApprovedAt       time.Time       `json:"approvedAt"`
	ApprovedUntil    time.Time       `json:"approvedUntil"`
	DryRunAtProposal bool            `json:"dryRunAtProposal"`
}

// ActionParams are the few agent-defined params fields the approved path reads back.
// The parse is lenient: params is opaque to the backend.
type ActionParams struct {
	Trigger                 string `json:"trigger"`
	UnschedulableAtProposal bool   `json:"unschedulableAtProposal"`
	MoveType                string `json:"moveType"`
	ProviderID              string `json:"providerId"`
	NodeGroup               string `json:"nodeGroup"`
}

// ParsedParams decodes Params leniently.
func (a ApprovedAction) ParsedParams() ActionParams {
	var p ActionParams
	_ = json.Unmarshal(a.Params, &p)
	return p
}

// ParseApprovedAction decodes a claim body. strict rejects unknown envelope fields
// (used against the golden fixtures; production decoding is lenient so a backend that
// adds a field does not break a running agent).
func ParseApprovedAction(b []byte, strict bool) (ApprovedAction, error) {
	var a ApprovedAction
	dec := json.NewDecoder(bytes.NewReader(b))
	if strict {
		dec.DisallowUnknownFields()
	}
	if err := dec.Decode(&a); err != nil {
		return ApprovedAction{}, fmt.Errorf("parse approved action: %w", err)
	}
	return a, nil
}

// DispatchedItem is one row of GET /proposals/dispatched.
type DispatchedItem struct {
	ID             string    `json:"id"`
	TargetRef      string    `json:"targetRef"`
	TargetIdentity string    `json:"targetIdentity"`
	OperationID    string    `json:"operationId"`
	ActionType     string    `json:"actionType"`
	Operation      string    `json:"operation"`
	DispatchedAt   time.Time `json:"dispatchedAt"`
	DryRun         bool      `json:"dryRun"`
}

// DispatchedList is the GET /proposals/dispatched body.
type DispatchedList struct {
	Items []DispatchedItem `json:"items"`
}

// Outcome is the POST /proposals/{id}/outcome body. Actions, Reports and
// Consolidation are opaque JSON on the backend.
type Outcome struct {
	SchemaVersion int             `json:"schemaVersion"`
	ProposalID    string          `json:"proposalId"`
	Status        string          `json:"status"`
	Verified      bool            `json:"verified"`
	DryRun        bool            `json:"dryRun"`
	Stage         string          `json:"stage"`
	Reason        string          `json:"reason"`
	OperationID   string          `json:"operationId"`
	Actions       json.RawMessage `json:"actions"`
	Reports       json.RawMessage `json:"reports"`
	Consolidation json.RawMessage `json:"consolidation"`
}

// Backend limits on an outcome (3.8 validation table).
const (
	maxOutcomeReason = 1000
	maxOutcomeList   = 50
	maxConsolidation = 256 << 10
)

// NewOutcome builds an outcome with the mandatory fields and empty opaque parts.
func NewOutcome(proposalID, status, stage, reason string) Outcome {
	return Outcome{
		SchemaVersion: SchemaVersion,
		ProposalID:    proposalID,
		Status:        status,
		Stage:         stage,
		Reason:        reason,
		OperationID:   OperationID(proposalID),
		Actions:       json.RawMessage("[]"),
		Reports:       json.RawMessage("[]"),
		Consolidation: json.RawMessage("null"),
	}
}

// WithDetail attaches the action records, drain reports and consolidation run,
// truncating each to what the backend accepts (a longer list or a larger run is
// clipped, never the reason an outcome is refused).
func (o Outcome) WithDetail(actions, reports, consolidation interface{}) Outcome {
	o.Actions = clipList(actions)
	o.Reports = clipList(reports)
	o.Consolidation = json.RawMessage("null")
	if consolidation != nil {
		if b, err := json.Marshal(consolidation); err == nil && len(b) <= maxConsolidation && string(b) != "null" {
			o.Consolidation = b
		}
	}
	return o
}

func clipList(v interface{}) json.RawMessage {
	if v == nil {
		return json.RawMessage("[]")
	}
	b, err := json.Marshal(v)
	if err != nil || string(b) == "null" {
		return json.RawMessage("[]")
	}
	var list []json.RawMessage
	if err := json.Unmarshal(b, &list); err != nil {
		return json.RawMessage("[]")
	}
	if len(list) > maxOutcomeList {
		list = list[:maxOutcomeList]
	}
	if list == nil {
		list = []json.RawMessage{}
	}
	out, err := json.Marshal(list)
	if err != nil {
		return json.RawMessage("[]")
	}
	return out
}

// Clamp enforces the reason length limit (in runes, so a cut never splits a character).
func (o Outcome) Clamp() Outcome {
	if r := []rune(o.Reason); len(r) > maxOutcomeReason {
		o.Reason = string(r[:maxOutcomeReason-1]) + "…"
	}
	return o
}
