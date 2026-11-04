package approval

import (
	corev1 "k8s.io/api/core/v1"

	"github.com/xamops/automation/internal/drain"
)

// Reconciled is the outcome Classify derives from a DISPATCHED row and the node's
// current annotations. Terminal=false means "still running": the agent sends a
// non-terminal status=running outcome so the backend refreshes last_progress_at.
type Reconciled struct {
	Status   string
	Verified bool
	Reason   string
	Terminal bool
}

// Classify is the AC-G10 decision table, evaluated top to bottom, first match wins.
// node is nil when the node does not exist; seenOp is true when this process ever
// observed (or itself wrote) xamops.io/drain-operation == the row's operation id.
// It never executes anything; it is a pure read of already-fetched state.
func Classify(item DispatchedItem, node *corev1.Node, seenOp bool) Reconciled {
	// 2. node not found.
	if node == nil {
		if seenOp {
			return Reconciled{Status: StatusSucceeded, Verified: false, Reason: "node no longer exists", Terminal: true}
		}
		return Reconciled{Status: StatusFailed, Reason: "node removed before execution", Terminal: true}
	}
	ann := node.Annotations
	if ann[drain.AnnotationOperation] == item.OperationID && item.OperationID != "" {
		switch ann[drain.AnnotationState] {
		case drain.StateDrained: // 3
			reason := "reported after restart"
			if item.ActionType == TypeNodeConsolidate {
				reason = "drained; node removal not confirmed"
			}
			return Reconciled{Status: StatusSucceeded, Verified: false, Reason: reason, Terminal: true}
		case drain.StateDraining: // 4
			return Reconciled{Status: StatusRunning, Reason: "drain still running", Terminal: false}
		case drain.StateAborted: // 5
			return Reconciled{Status: StatusFailed, Reason: "drain aborted", Terminal: true}
		case drain.StateCordoned: // 6
			if node.Spec.Unschedulable && ann[drain.AnnotationCordonedBy] == drain.CordonedByXamops {
				return Reconciled{Status: StatusSucceeded, Verified: true, Reason: "cordoned (reported after restart)", Terminal: true}
			}
		}
		// 7. state absent or any other value (uncordon-all cleared it, or an operator edit).
		return Reconciled{Status: StatusFailed, Reason: "node restored by operator", Terminal: true}
	}
	// No annotation attributable to this proposal.
	if item.DryRun { // 8a
		return Reconciled{Status: StatusFailed, Reason: "dry run: outcome lost, not re-run", Terminal: true}
	}
	return Reconciled{Status: StatusNotStarted, Reason: "no sign of the action on the node", Terminal: true} // 8b
}
