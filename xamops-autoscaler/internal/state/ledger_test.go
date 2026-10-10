package state

import (
	"context"
	"fmt"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/policy"
)

const ns, name = "xamops-system", "xamops-automation-state"

func writeVerbs(c *fake.Clientset) []string {
	var out []string
	for _, a := range c.Actions() {
		switch a.GetVerb() {
		case "get", "list", "watch":
		default:
			out = append(out, a.GetVerb()+" "+a.GetResource().Resource)
		}
	}
	return out
}

func TestReadOnlyNeverWrites(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset()
	l := New(c, ns, name, true)

	if err := l.Observe(ctx, "idle"); err != nil {
		t.Fatal(err)
	}
	if err := l.Observe(ctx, "running"); err != nil {
		t.Fatal(err)
	}
	id, err := l.OperationID(ctx, policy.RebalancerConfig{})
	if err != nil {
		t.Fatal(err)
	}
	if id != "gen-1" {
		t.Errorf("operation id = %q, want gen-1", id)
	}
	if err := l.Record(ctx, id, Operation{State: StateRefused, Mode: "live"}); err != nil {
		t.Fatal(err)
	}
	// The overlay sees the record...
	if op, ok, _ := l.Get(ctx, id); !ok || op.State != StateRefused {
		t.Errorf("overlay lost the record: %+v %v", op, ok)
	}
	// ...but nothing reached the API.
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("read-only ledger issued writes: %v", w)
	}
}

func TestCreateIfMissingAndRestartPersistence(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset()

	l1 := New(c, ns, name, false)
	if err := l1.Observe(ctx, "running"); err != nil {
		t.Fatal(err)
	}
	id, _ := l1.OperationID(ctx, policy.RebalancerConfig{})
	if err := l1.Record(ctx, id, Operation{State: StateRefused, Mode: "live", Reason: "no plan"}); err != nil {
		t.Fatal(err)
	}
	cm, err := c.CoreV1().ConfigMaps(ns).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		t.Fatalf("ledger configmap not created: %v", err)
	}
	if cm.Data[DataKey] == "" {
		t.Fatal("ledger configmap has no data")
	}

	// A new process (new Ledger) on the same API sees the terminal op and the same
	// generation: status is still running, so there is no new edge.
	l2 := New(c, ns, name, false)
	if err := l2.Observe(ctx, "running"); err != nil {
		t.Fatal(err)
	}
	id2, _ := l2.OperationID(ctx, policy.RebalancerConfig{})
	if id2 != id {
		t.Errorf("after restart operation id = %q, want %q", id2, id)
	}
	op, ok, err := l2.Get(ctx, id2)
	if err != nil || !ok || !IsTerminal(op.State) {
		t.Errorf("after restart op = %+v ok=%v err=%v, want terminal", op, ok, err)
	}
}

func TestNewOperationIDYieldsNewOp(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset()
	l := New(c, ns, name, false)
	_ = l.Observe(ctx, "running")
	if err := l.Record(ctx, "op-a", Operation{State: StateCompleted, Mode: "live"}); err != nil {
		t.Fatal(err)
	}
	id, _ := l.OperationID(ctx, policy.RebalancerConfig{OperationID: "op-b"})
	if id != "op-b" {
		t.Fatalf("operation id = %q, want op-b", id)
	}
	if _, ok, _ := l.Get(ctx, id); ok {
		t.Error("a new operationId must not have a recorded outcome")
	}
}

func TestIdleToRunningIncrementsGeneration(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset()
	l := New(c, ns, name, false)

	steps := []struct {
		status string
		gen    int64
	}{
		{"running", 1}, {"running", 1}, {"idle", 1}, {"running", 2}, {"completed", 2}, {"running", 3},
	}
	for i, s := range steps {
		if err := l.Observe(ctx, s.status); err != nil {
			t.Fatal(err)
		}
		// Re-read through a fresh ledger so the persisted value is checked.
		g, err := New(c, ns, name, true).Generation(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if g != s.gen {
			t.Errorf("step %d (%s): generation %d, want %d", i, s.status, g, s.gen)
		}
	}
}

func TestObservingIdleOnFreshClusterWritesNothing(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset()
	l := New(c, ns, name, false)
	if err := l.Observe(ctx, "idle"); err != nil {
		t.Fatal(err)
	}
	if w := writeVerbs(c); len(w) != 0 {
		t.Errorf("observing idle wrote: %v", w)
	}
}

func TestPruneKeepsMostRecent(t *testing.T) {
	ctx := context.Background()
	c := fake.NewSimpleClientset(&corev1.ConfigMap{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns}})
	l := New(c, ns, name, false)
	for i := 0; i < MaxOperations+5; i++ {
		started := fmt.Sprintf("2026-10-10T10:%02d:00Z", i)
		if err := l.Record(ctx, fmt.Sprintf("op-%02d", i), Operation{State: StateRefused, Mode: "live", StartedAt: started}); err != nil {
			t.Fatal(err)
		}
	}
	st, err := New(c, ns, name, true).Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(st.Operations) != MaxOperations {
		t.Fatalf("kept %d ops, want %d", len(st.Operations), MaxOperations)
	}
	if _, ok := st.Operations["op-00"]; ok {
		t.Error("oldest op should have been pruned")
	}
	if _, ok := st.Operations[fmt.Sprintf("op-%02d", MaxOperations+4)]; !ok {
		t.Error("newest op missing")
	}
}
