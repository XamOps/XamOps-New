package approval

import (
	"encoding/json"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

func approved(unsched bool) ApprovedAction {
	p, _ := json.Marshal(map[string]interface{}{"unschedulableAtProposal": unsched})
	return ApprovedAction{
		ProposalID: "p1", ActionType: TypeNodeDrain, Operation: OpDrain, OriginController: OriginEvictor,
		Target: Target{Kind: "NODE", Name: "n1", UID: "u1"}, Params: p, PlanFingerprint: "sha256:aa",
		FirstSeenAt: time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC),
	}
}

func liveNode(uid string, unsched bool) *corev1.Node {
	n := &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: "n1", UID: types.UID(uid)}}
	n.Spec.Unschedulable = unsched
	return n
}

func TestRevalidate(t *testing.T) {
	now := time.Date(2026, 1, 1, 11, 0, 0, 0, time.UTC)
	ok := &Fresh{Verdict: VerdictAllowed, Fingerprint: "sha256:aa"}
	cons := approved(false)
	cons.ActionType = TypeNodeConsolidate
	for _, tc := range []struct {
		name   string
		a      ApprovedAction
		node   *corev1.Node
		fresh  *Fresh
		want   string
		reason string
	}{
		{"ok", approved(false), liveNode("u1", false), ok, RevalOK, ""},
		{"node gone", approved(false), nil, ok, RevalStale, "node n1 no longer exists"},
		{"uid changed", approved(false), liveNode("u2", false), ok, RevalStale, "node n1 was replaced (uid changed)"},
		{"cordon state changed", approved(false), liveNode("u1", true), ok, RevalStale, "node n1 cordon state changed since the proposal"},
		{"cordoned at proposal and still", approved(true), liveNode("u1", true), ok, RevalOK, ""},
		{"candidate gone", approved(false), liveNode("u1", false), nil, RevalStale, "the candidate is no longer produced this cycle"},
		{"fingerprint drift", approved(false), liveNode("u1", false), &Fresh{Verdict: VerdictAllowed, Fingerprint: "sha256:bb"}, RevalStale, "plan changed since the proposal (fingerprint differs)"},
		{"now refused", approved(false), liveNode("u1", false), &Fresh{Verdict: VerdictRefused, Reason: "pdb-no-disruptions:ns/x", Fingerprint: "sha256:bb"}, RevalRefused, "plan refused at execution time: pdb-no-disruptions:ns/x"},
		{"consolidate dwell missing", cons, liveNode("u1", false), ok, RevalStale, "dwell missing"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := Revalidate(tc.a, tc.node, tc.fresh, now)
			if r.Result != tc.want || r.Reason != tc.reason {
				t.Errorf("Revalidate = %+v, want %s %q", r, tc.want, tc.reason)
			}
		})
	}
}

func TestRevalidateConsolidationDwellGuard(t *testing.T) {
	a := approved(false)
	a.ActionType = TypeNodeConsolidate
	a.DwellSeconds = intp(300)
	ok := &Fresh{Verdict: VerdictAllowed, Fingerprint: "sha256:aa"}
	first := a.FirstSeenAt
	// firstSeenAt + dwell + 60 s skew margin.
	if r := Revalidate(a, liveNode("u1", false), ok, first.Add(300*time.Second+59*time.Second)); r.Result != RevalStale || r.Reason != "dwell not elapsed" {
		t.Errorf("before the margin: %+v", r)
	}
	if r := Revalidate(a, liveNode("u1", false), ok, first.Add(300*time.Second+60*time.Second)); r.Result != RevalOK {
		t.Errorf("at the margin: %+v", r)
	}
}
