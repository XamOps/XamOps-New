package approval

import (
	"regexp"
	"testing"
)

func baseInput() NodeFingerprintInput {
	return NodeFingerprintInput{
		ActionType: TypeNodeDrain, Operation: OpDrain, Origin: OriginNodeOptimizer, Node: "n1", UID: "u1",
		Verdict: VerdictAllowed, Drainable: true,
	}
}

func TestNodeFingerprintFormatAndStability(t *testing.T) {
	fp := NodeFingerprint(baseInput())
	if !regexp.MustCompile(`^sha256:[0-9a-f]{64}$`).MatchString(fp) {
		t.Fatalf("fingerprint %q", fp)
	}
	if NodeFingerprint(baseInput()) != fp {
		t.Error("not deterministic")
	}
	// List order and duplicates do not matter.
	a, b := baseInput(), baseInput()
	a.RefusalCodes = []string{"b", "a", "a"}
	b.RefusalCodes = []string{"a", "b"}
	if NodeFingerprint(a) != NodeFingerprint(b) {
		t.Error("refusal code order/dups changed the fingerprint")
	}
}

func TestNodeFingerprintDiffersOnEachBoundField(t *testing.T) {
	base := NodeFingerprint(baseInput())
	mut := map[string]func(*NodeFingerprintInput){
		"uid":       func(i *NodeFingerprintInput) { i.UID = "u2" },
		"verdict":   func(i *NodeFingerprintInput) { i.Verdict = VerdictRefused },
		"refusal":   func(i *NodeFingerprintInput) { i.RefusalCodes = []string{"pdb-no-disruptions:ns/x"} },
		"drainable": func(i *NodeFingerprintInput) { i.Drainable = false },
		"operation": func(i *NodeFingerprintInput) { i.Operation = OpCordonOnly },
		"origin":    func(i *NodeFingerprintInput) { i.Origin = OriginEvictor },
		"node":      func(i *NodeFingerprintInput) { i.Node = "n2" },
		"refused":   func(i *NodeFingerprintInput) { i.RefusedCodes = []string{"pvc-volume"} },
		"blocked":   func(i *NodeFingerprintInput) { i.BlockedCodes = []string{"statefulset"} },
		"strands":   func(i *NodeFingerprintInput) { i.Strands = true },
	}
	for name, f := range mut {
		in := baseInput()
		f(&in)
		if NodeFingerprint(in) == base {
			t.Errorf("changing %s did not change the fingerprint", name)
		}
	}
	// nodeGroup / providerId / moveType matter for consolidation only.
	drain := baseInput()
	drain.NodeGroup = "ng-9"
	if NodeFingerprint(drain) != base {
		t.Error("nodeGroup must not affect a NODE_DRAIN fingerprint")
	}
	c1 := baseInput()
	c1.ActionType, c1.Operation, c1.Origin, c1.MoveType, c1.NodeGroup = TypeNodeConsolidate, OpEmpty, OriginConsolidation, "EMPTY", "ng-1"
	c2 := c1
	c2.NodeGroup = "ng-2"
	c3 := c1
	c3.ProviderID = "aws:///x/i-1"
	if NodeFingerprint(c1) == NodeFingerprint(c2) || NodeFingerprint(c1) == NodeFingerprint(c3) {
		t.Error("consolidation nodeGroup/providerId must change the fingerprint")
	}
}

func TestDedupeKeyTable(t *testing.T) {
	for _, tc := range []struct{ typ, op, origin, node, want string }{
		{TypeNodeDrain, OpDrain, OriginNodeOptimizer, "ip-10-0-1-5.ec2.internal", "NODE_DRAIN:DRAIN:node-optimizer:ip-10-0-1-5.ec2.internal"},
		{TypeNodeDrain, OpDrain, OriginEvictor, "n", "NODE_DRAIN:DRAIN:evictor:n"},
		{TypeNodeDrain, OpCordonOnly, OriginNodeOptimizer, "n", "NODE_DRAIN:CORDON_ONLY:node-optimizer:n"},
		{TypeNodeConsolidate, OpEmpty, OriginConsolidation, "n", "NODE_CONSOLIDATE:EMPTY:consolidation:n"},
		{TypeRebalance, OpDrain, OriginRebalancer, "n", "REBALANCE:DRAIN:rebalancer:n"},
	} {
		if got := DedupeKey(tc.typ, tc.op, tc.origin, tc.node); got != tc.want {
			t.Errorf("DedupeKey = %q, want %q", got, tc.want)
		}
	}
	if DedupeKey(TypeNodeDrain, OpDrain, OriginNodeOptimizer, "n") == DedupeKey(TypeNodeDrain, OpCordonOnly, OriginNodeOptimizer, "n") {
		t.Error("CORDON_ONLY and DRAIN of one node/origin must have distinct keys")
	}
}

func TestReasonCodesSplitKeepsSuffix(t *testing.T) {
	got := ReasonCodes("pdb-no-disruptions:ns/a; statefulset; unknown-owner-kind:Foo")
	want := []string{"pdb-no-disruptions:ns/a", "statefulset", "unknown-owner-kind:Foo"}
	if len(got) != 3 || got[0] != want[0] || got[1] != want[1] || got[2] != want[2] {
		t.Errorf("ReasonCodes = %v", got)
	}
}
