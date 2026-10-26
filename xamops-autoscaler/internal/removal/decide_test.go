package removal

import (
	"context"
	"testing"

	"github.com/xamops/automation/internal/consolidation"
)

func actor(kind string) consolidation.Actor { return consolidation.Actor{Kind: kind} }

// managed is a NodeFacts for an EKS managed nodegroup node with room to shrink.
func managed() NodeFacts {
	return NodeFacts{InstanceID: "i-0abc", ASG: "asg-1", ManagedNodegroup: "ng-1", MinSize: 1, DesiredCapacity: 3}
}

func TestDecideDeferToActor_I12(t *testing.T) {
	for _, kind := range []string{consolidation.ActorKarpenter, consolidation.ActorClusterAutoscaler, consolidation.ActorMultiple} {
		d, reason := DecideRemoval(actor(kind), managed())
		if d != DeferToActor {
			t.Errorf("actor=%s: got %v, want DeferToActor (%s)", kind, d, reason)
		}
	}
}

func TestDecideUnknownActorRefuses(t *testing.T) {
	if d, _ := DecideRemoval(actor("WHAT"), managed()); d != Refuse {
		t.Errorf("unknown actor: got %v, want Refuse", d)
	}
	if d, _ := DecideRemoval(actor(""), managed()); d != Refuse {
		t.Errorf("empty actor: got %v, want Refuse", d)
	}
}

func TestDecideXamopsASG_I11_I13(t *testing.T) {
	// NONE + managed + room -> XamopsASG.
	if d, _ := DecideRemoval(actor(consolidation.ActorNone), managed()); d != XamopsASG {
		t.Errorf("NONE managed with room: got %v, want XamopsASG", d)
	}
	// I13: unmanaged node -> Refuse.
	nf := managed()
	nf.ManagedNodegroup = ""
	if d, reason := DecideRemoval(actor(consolidation.ActorNone), nf); d != Refuse {
		t.Errorf("unmanaged: got %v (%s), want Refuse", d, reason)
	}
	// I11: at min -> Refuse.
	atMin := managed()
	atMin.DesiredCapacity = atMin.MinSize
	if d, reason := DecideRemoval(actor(consolidation.ActorNone), atMin); d != Refuse {
		t.Errorf("at-min: got %v (%s), want Refuse", d, reason)
	}
	// Below min (concurrent scale-in) also refuses.
	below := managed()
	below.DesiredCapacity = below.MinSize - 1
	if d, _ := DecideRemoval(actor(consolidation.ActorNone), below); d != Refuse {
		t.Errorf("below-min: got %v, want Refuse", d)
	}
}

func TestParseInstanceID(t *testing.T) {
	cases := []struct {
		providerID string
		want       string
		ok         bool
	}{
		{"aws:///ap-south-1c/i-0abc123def456", "i-0abc123def456", true},
		{"aws:///ap-south-1c/i-0abc123def456/", "i-0abc123def456", true},
		{"i-0simple", "i-0simple", true},
		{"kwok://node-a", "", false},
		{"aws:///ap-south-1c/fargate-abc", "", false},
		{"", "", false},
		{"aws:///ap-south-1c/i-", "", false},
	}
	for _, c := range cases {
		got, ok := ParseInstanceID(c.providerID)
		if got != c.want || ok != c.ok {
			t.Errorf("ParseInstanceID(%q) = (%q,%v), want (%q,%v)", c.providerID, got, ok, c.want, c.ok)
		}
	}
}

// Exercise the FakeProvider call log the way the controller would: a NONE+managed
// node terminates exactly once with decrement=true, a Karpenter node terminates
// zero times, and the two never both happen.
func TestFakeProviderCallLog_I11_I12(t *testing.T) {
	ctx := context.Background()
	fp := NewFakeProvider()
	fp.Facts["i-0abc"] = managed()

	// NONE -> XamopsASG -> terminate once, decrement true.
	if d, _ := DecideRemoval(actor(consolidation.ActorNone), fp.Facts["i-0abc"]); d == XamopsASG {
		if err := fp.TerminateAndDecrement(ctx, "i-0abc", "asg-1"); err != nil {
			t.Fatal(err)
		}
	}
	// Karpenter -> DeferToActor -> no terminate.
	if d, _ := DecideRemoval(actor(consolidation.ActorKarpenter), fp.Facts["i-0abc"]); d == XamopsASG {
		t.Fatal("Karpenter should not take the ASG path")
	}

	calls := fp.Terminations()
	if len(calls) != 1 {
		t.Fatalf("want exactly 1 terminate call, got %d", len(calls))
	}
	if !calls[0].Decrement {
		t.Errorf("terminate must always decrement desired capacity (I11)")
	}
	if calls[0].InstanceID != "i-0abc" || calls[0].ASG != "asg-1" {
		t.Errorf("unexpected terminate call: %+v", calls[0])
	}
}

func TestFakeProviderErrorInjectionAndIdempotency(t *testing.T) {
	ctx := context.Background()
	fp := NewFakeProvider()
	fp.FailOnCall = 2
	if err := fp.TerminateAndDecrement(ctx, "i-1", "asg"); err != nil {
		t.Fatalf("call 1 should succeed: %v", err)
	}
	if err := fp.TerminateAndDecrement(ctx, "i-2", "asg"); err == nil {
		t.Fatal("call 2 should fail (injected)")
	}
	// Idempotent repeat on an already-terminated instance.
	if err := fp.TerminateAndDecrement(ctx, "i-1", "asg"); err != nil {
		t.Fatalf("idempotent repeat should succeed: %v", err)
	}
	if len(fp.Terminations()) != 3 {
		t.Errorf("all calls recorded, got %d", len(fp.Terminations()))
	}
}
