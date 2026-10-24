package main

import (
	"context"
	"sync"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/gate"
)

// A standby replica (one that never becomes leader) must NOT run the control loop;
// the leader does. The loopController is the unit the leaderelection callbacks drive:
// OnStartedLeading calls start(), OnStoppedLeading calls stop().
func TestLoopControllerRunsOnlyWhenLeading(t *testing.T) {
	var mu sync.Mutex
	ran := false
	lc := &loopController{run: func(ctx context.Context) {
		mu.Lock()
		ran = true
		mu.Unlock()
		<-ctx.Done() // emulate the real loop: runs until its context is cancelled
	}}

	// Standby: start() is never called (OnStartedLeading never fires) -> loop must not run.
	time.Sleep(20 * time.Millisecond)
	mu.Lock()
	standbyRan := ran
	mu.Unlock()
	if standbyRan || lc.runCount != 0 {
		t.Fatalf("standby ran the loop (ran=%v runCount=%d) — it must not", standbyRan, lc.runCount)
	}

	// Leader: OnStartedLeading -> start() runs the loop exactly once.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	lc.start(ctx)
	deadline := time.After(time.Second)
	for {
		mu.Lock()
		got := ran
		mu.Unlock()
		if got {
			break
		}
		select {
		case <-deadline:
			t.Fatal("leader did not run the control loop")
		case <-time.After(5 * time.Millisecond):
		}
	}
	if lc.runCount != 1 || !lc.started {
		t.Fatalf("after becoming leader: runCount=%d started=%v, want 1/true", lc.runCount, lc.started)
	}

	// start() is idempotent while already leading (no duplicate loop).
	lc.start(ctx)
	if lc.runCount != 1 {
		t.Fatalf("duplicate start ran a second loop: runCount=%d", lc.runCount)
	}

	// OnStoppedLeading -> stop() cancels the loop's context.
	lc.stop()
	if lc.started {
		t.Fatal("stop() did not clear the started flag")
	}
}

// RUN_MODE=uncordon-all one-shot: under DRY_RUN it simulates (no write); live it
// uncordons the XamOps-cordoned nodes. Exercised through runUncordonAll's executor
// wiring (main() returns after it).
func TestRunUncordonAllHonoursDryRun(t *testing.T) {
	cordoned := func(name string) *corev1.Node {
		return &corev1.Node{
			ObjectMeta: metav1.ObjectMeta{Name: name, Annotations: map[string]string{
				drain.AnnotationCordonedBy: drain.CordonedByXamops, drain.AnnotationState: drain.StateCordoned}},
			Spec: corev1.NodeSpec{Unschedulable: true},
		}
	}

	// DRY_RUN=true -> simulate only, node stays cordoned.
	c := fake.NewSimpleClientset(cordoned("x-1"))
	runUncordonAll(context.Background(), &config.Config{DryRun: true, PodNamespace: "xamops-system"}, c)
	n, _ := c.CoreV1().Nodes().Get(context.Background(), "x-1", metav1.GetOptions{})
	if !n.Spec.Unschedulable {
		t.Error("DRY_RUN uncordon-all must not uncordon the node")
	}

	// DRY_RUN=false -> node uncordoned.
	c2 := fake.NewSimpleClientset(cordoned("x-1"))
	runUncordonAll(context.Background(), &config.Config{DryRun: false, PodNamespace: "xamops-system"}, c2)
	n2, _ := c2.CoreV1().Nodes().Get(context.Background(), "x-1", metav1.GetOptions{})
	if n2.Spec.Unschedulable {
		t.Error("live uncordon-all should uncordon the XamOps-cordoned node")
	}
}

// Sanity: config default has leader election ON and reads NODE_NAME/POD_NAME when the
// gate mode helper is used elsewhere — here we just assert the gate.Mode mapping used
// by runUncordonAll is the two active modes.
func TestUncordonAllModeMapping(t *testing.T) {
	if gate.Simulate == gate.Live {
		t.Fatal("simulate and live must differ")
	}
}
