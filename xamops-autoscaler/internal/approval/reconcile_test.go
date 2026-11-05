package approval

import (
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/xamops/automation/internal/drain"
)

func nodeWith(op, state, cordonedBy string, unschedulable bool) *corev1.Node {
	n := &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: "n1", Annotations: map[string]string{}}}
	n.Spec.Unschedulable = unschedulable
	if op != "" {
		n.Annotations[drain.AnnotationOperation] = op
	}
	if state != "" {
		n.Annotations[drain.AnnotationState] = state
	}
	if cordonedBy != "" {
		n.Annotations[drain.AnnotationCordonedBy] = cordonedBy
	}
	return n
}

// AC-G10: every row of the decision table, first match wins; nothing re-executes.
func TestClassifyDecisionTable(t *testing.T) {
	it := DispatchedItem{ID: "p1", OperationID: "proposal-p1", ActionType: TypeNodeDrain}
	itDry := it
	itDry.DryRun = true
	itCons := it
	itCons.ActionType = TypeNodeConsolidate
	op := "proposal-p1"
	for _, tc := range []struct {
		name     string
		item     DispatchedItem
		node     *corev1.Node
		seen     bool
		status   string
		verified bool
		terminal bool
		reason   string
	}{
		{"2 gone, op seen", it, nil, true, StatusSucceeded, false, true, "node no longer exists"},
		{"2 gone, never seen", it, nil, false, StatusFailed, false, true, "node removed before execution"},
		{"3 drained", it, nodeWith(op, "drained", "", true), false, StatusSucceeded, false, true, "reported after restart"},
		{"3 consolidate drained, removal unconfirmed", itCons, nodeWith(op, "drained", "", true), false, StatusSucceeded, false, true, "drained; node removal not confirmed"},
		{"4 draining", it, nodeWith(op, "draining", "", true), false, StatusRunning, false, false, "drain still running"},
		{"5 aborted", it, nodeWith(op, "aborted", "", false), false, StatusFailed, false, true, "drain aborted"},
		{"6 cordoned by xamops", it, nodeWith(op, "cordoned", drain.CordonedByXamops, true), false, StatusSucceeded, true, true, "cordoned (reported after restart)"},
		{"7 cordoned but schedulable again", it, nodeWith(op, "cordoned", drain.CordonedByXamops, false), false, StatusFailed, false, true, "node restored by operator"},
		{"7 state cleared by uncordon-all", it, nodeWith(op, "", "", false), false, StatusFailed, false, true, "node restored by operator"},
		{"8a no annotation, dry run", itDry, nodeWith("", "", "", false), false, StatusFailed, false, true, "dry run: outcome lost, not re-run"},
		{"8b no annotation", it, nodeWith("", "", "", false), false, StatusNotStarted, false, true, "no sign of the action on the node"},
		{"8b another operation's draining state is not attributable", it, nodeWith("consolidation-1", "draining", "", true), false, StatusNotStarted, false, true, "no sign of the action on the node"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := Classify(tc.item, tc.node, tc.seen)
			if r.Status != tc.status || r.Verified != tc.verified || r.Terminal != tc.terminal || r.Reason != tc.reason {
				t.Errorf("Classify = %+v, want status=%s verified=%v terminal=%v reason=%q", r, tc.status, tc.verified, tc.terminal, tc.reason)
			}
		})
	}
	// An annotation row beats the dry-run row (a DRY_RUN flip must not hide a real drain).
	if r := Classify(itDry, nodeWith(op, "drained", "", true), false); r.Status != StatusSucceeded {
		t.Errorf("dry-run row hid a real drain: %+v", r)
	}
}
