package approval

import (
	"fmt"
	"time"

	corev1 "k8s.io/api/core/v1"
)

// DwellSkew is the fixed margin added to the dwell guard: firstSeenAt is backend time
// and the agent clock may differ.
const DwellSkew = 60 * time.Second

// Fresh is this cycle's freshly computed classification of the approved action's
// candidate (nil when the candidate is no longer produced).
type Fresh struct {
	Verdict      string
	RefusalCodes []string
	Reason       string
	Fingerprint  string
}

// Verdict results of Revalidate.
const (
	RevalOK      = "ok"
	RevalStale   = "stale"
	RevalRefused = "refused"
)

// Revalidation is the result of re-checking an approved action against the cluster.
type Revalidation struct {
	Result string // RevalOK | RevalStale | RevalRefused
	Reason string
}

// Revalidate re-checks an approved action before anything executes. node is nil when
// the node is gone; fresh is nil when this cycle no longer produces the candidate.
// The first failing check wins; a drifted proposal is stale and a candidate that is
// now refused is refused (stage EXECUTION), never executed. Approval opens no gate.
func Revalidate(a ApprovedAction, node *corev1.Node, fresh *Fresh, now time.Time) Revalidation {
	stale := func(format string, args ...interface{}) Revalidation {
		return Revalidation{Result: RevalStale, Reason: fmt.Sprintf(format, args...)}
	}
	if node == nil {
		return stale("node %s no longer exists", a.Target.Name)
	}
	if string(node.UID) != a.Target.UID {
		return stale("node %s was replaced (uid changed)", a.Target.Name)
	}
	if node.Spec.Unschedulable != a.ParsedParams().UnschedulableAtProposal {
		return stale("node %s cordon state changed since the proposal", a.Target.Name)
	}
	if fresh == nil {
		return stale("the candidate is no longer produced this cycle")
	}
	if fresh.Verdict == VerdictRefused {
		reason := "plan refused at execution time"
		if fresh.Reason != "" {
			reason += ": " + fresh.Reason
		}
		return Revalidation{Result: RevalRefused, Reason: reason}
	}
	if fresh.Fingerprint != a.PlanFingerprint {
		return stale("plan changed since the proposal (fingerprint differs)")
	}
	if a.ActionType == TypeNodeConsolidate {
		if a.DwellSeconds == nil {
			return stale("dwell missing")
		}
		ready := a.FirstSeenAt.Add(time.Duration(*a.DwellSeconds) * time.Second).Add(DwellSkew)
		if now.Before(ready) {
			return stale("dwell not elapsed")
		}
	}
	return Revalidation{Result: RevalOK}
}
