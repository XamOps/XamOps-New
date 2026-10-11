package drain

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"
	typedcorev1 "k8s.io/client-go/kubernetes/typed/core/v1"
	k8stesting "k8s.io/client-go/testing"

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
)

// ctxClient makes the fake clientset behave like a real API client towards
// contexts: a request built from a cancelled or expired context fails before it
// is sent. The fake clientset alone ignores contexts, which hid the abort bug.
type ctxClient struct {
	kubernetes.Interface
	afterPodList func(ns string, l *corev1.PodList)
}

func (c ctxClient) CoreV1() typedcorev1.CoreV1Interface {
	return ctxCore{CoreV1Interface: c.Interface.CoreV1(), c: c}
}

type ctxCore struct {
	typedcorev1.CoreV1Interface
	c ctxClient
}

func (x ctxCore) Nodes() typedcorev1.NodeInterface {
	return ctxNodes{x.CoreV1Interface.Nodes()}
}

func (x ctxCore) Pods(ns string) typedcorev1.PodInterface {
	return ctxPods{PodInterface: x.CoreV1Interface.Pods(ns), ns: ns, after: x.c.afterPodList}
}

type ctxNodes struct{ typedcorev1.NodeInterface }

func (n ctxNodes) Get(ctx context.Context, name string, o metav1.GetOptions) (*corev1.Node, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return n.NodeInterface.Get(ctx, name, o)
}

func (n ctxNodes) List(ctx context.Context, o metav1.ListOptions) (*corev1.NodeList, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return n.NodeInterface.List(ctx, o)
}

func (n ctxNodes) Patch(ctx context.Context, name string, pt types.PatchType, data []byte, o metav1.PatchOptions, sub ...string) (*corev1.Node, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return n.NodeInterface.Patch(ctx, name, pt, data, o, sub...)
}

type ctxPods struct {
	typedcorev1.PodInterface
	ns    string
	after func(ns string, l *corev1.PodList)
}

func (p ctxPods) List(ctx context.Context, o metav1.ListOptions) (*corev1.PodList, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	l, err := p.PodInterface.List(ctx, o)
	if err == nil && p.after != nil {
		p.after(p.ns, l)
	}
	return l, err
}

func (p ctxPods) Get(ctx context.Context, name string, o metav1.GetOptions) (*corev1.Pod, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return p.PodInterface.Get(ctx, name, o)
}

func (p ctxPods) EvictV1(ctx context.Context, ev *policyv1.Eviction) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return p.PodInterface.EvictV1(ctx, ev)
}

// cancelOnCallPlanner returns the static plan and calls cancel on call number at.
type cancelOnCallPlanner struct {
	plan   Plan
	calls  *int32
	at     int32
	cancel func()
}

func (p cancelOnCallPlanner) Plan(_ context.Context, nodes []string) (Plan, error) {
	if atomic.AddInt32(p.calls, 1) == p.at {
		p.cancel()
	}
	out := p.plan
	out.Nodes = nodes
	return out, nil
}

func ctxExecutor(k kubernetes.Interface, p Planner) *Executor {
	e := NewExecutor(k, p, "xamops-system")
	// Long enough that only the context can end the wait in these tests.
	e.ReplacementTimeout = 30 * time.Second
	e.PDBRetry = 30 * time.Second
	e.PollInterval = 10 * time.Millisecond
	return e
}

// Every abort path that fires because the drain's context ended (SIGTERM cancels
// it; the rebalancer / node-optimizer drain timeouts expire it) must still restore
// the node: schedulable again, drain-state=aborted, cordoned-by marker removed.
func TestAbortRestoresNodeWhenContextEnds(t *testing.T) {
	cases := []struct {
		name       string
		setup      func(c *fake.Clientset, cancel func()) (Planner, func(ns string, l *corev1.PodList))
		timeout    time.Duration // >0: the drain context expires (drain timeout) instead of being cancelled
		wantReason string
	}{
		{
			name: "SIGTERM while waiting for the replacement",
			setup: func(c *fake.Clientset, cancel func()) (Planner, func(string, *corev1.PodList)) {
				evictionReactor(c, false)
				c.PrependReactor("create", "pods", func(a k8stesting.Action) (bool, runtime.Object, error) {
					if a.GetSubresource() == "eviction" {
						cancel() // the eviction itself is accepted; the wait sees ctx.Done()
					}
					return false, nil, nil
				})
				return staticPlanner{drainablePlan()}, nil
			},
			wantReason: "no replacement",
		},
		{
			name: "drain timeout expires while waiting for the replacement",
			setup: func(c *fake.Clientset, _ func()) (Planner, func(string, *corev1.PodList)) {
				evictionReactor(c, false)
				return staticPlanner{drainablePlan()}, nil
			},
			timeout:    100 * time.Millisecond,
			wantReason: "no replacement",
		},
		{
			name: "SIGTERM during the PDB retry of an eviction",
			setup: func(c *fake.Clientset, cancel func()) (Planner, func(string, *corev1.PodList)) {
				c.PrependReactor("create", "pods", func(a k8stesting.Action) (bool, runtime.Object, error) {
					if a.GetSubresource() != "eviction" {
						return false, nil, nil
					}
					cancel()
					return true, nil, apierrors.NewTooManyRequests("Cannot evict pod as it would violate the pod's disruption budget.", 1)
				})
				return staticPlanner{drainablePlan()}, nil
			},
			wantReason: "eviction of default/web-1: context canceled",
		},
		{
			name: "SIGTERM between the re-plan and the eviction (API call fails on the ended context)",
			setup: func(c *fake.Clientset, cancel func()) (Planner, func(string, *corev1.PodList)) {
				evictionReactor(c, true)
				var calls int32
				// Call 1 is prepare's plan, call 2 the closed loop's first re-plan.
				return cancelOnCallPlanner{plan: drainablePlan(), calls: &calls, at: 2, cancel: cancel}, nil
			},
			wantReason: "list siblings of default/web-1: context canceled",
		},
		{
			name: "SIGTERM after a verified replacement, before the next eviction",
			setup: func(c *fake.Clientset, cancel func()) (Planner, func(string, *corev1.PodList)) {
				evictionReactor(c, true)
				return staticPlanner{drainablePlan()}, func(ns string, l *corev1.PodList) {
					for _, p := range l.Items {
						if p.Name == "web-new-1" {
							cancel() // the replacement is returned; the loop's ctx check fires next
						}
					}
				}
			},
			wantReason: "context ended: context canceled",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := seeded()
			var ctx context.Context
			var cancel context.CancelFunc
			if tc.timeout > 0 {
				ctx, cancel = context.WithTimeout(context.Background(), tc.timeout)
			} else {
				ctx, cancel = context.WithCancel(context.Background())
			}
			defer cancel()
			planner, after := tc.setup(c, cancel)
			e := ctxExecutor(ctxClient{Interface: c, afterPodList: after}, planner)

			start := time.Now()
			out := e.Drain(ctx, "rebalancer", "op-ctx", []string{"node-a"}, gate.Live, NewBudget(1, nil))
			if time.Since(start) > 10*time.Second {
				t.Errorf("drain took %s; it must end with its context", time.Since(start))
			}
			if out.Status != policy.ActionAborted {
				t.Fatalf("status = %s, want aborted (reason %q)", out.Status, out.Actions[0].Reason)
			}
			reason := out.Actions[0].Reason
			if !strings.Contains(reason, tc.wantReason) {
				t.Errorf("reason = %q, want it to contain %q", reason, tc.wantReason)
			}
			if strings.Contains(reason, "restoring node") {
				t.Errorf("restore failed: %q", reason)
			}
			n := getNode(t, c, "node-a")
			if n.Spec.Unschedulable {
				t.Error("node must be schedulable again after the abort")
			}
			if n.Annotations[AnnotationState] != StateAborted {
				t.Errorf("drain-state = %q, want aborted", n.Annotations[AnnotationState])
			}
			if _, ok := n.Annotations[AnnotationCordonedBy]; ok {
				t.Error("cordoned-by marker must be removed on uncordon")
			}
			if got := InflightNodes([]corev1.Node{*n}); len(got) != 0 {
				t.Errorf("restored node still holds a budget slot: %v", got)
			}
			assertNoDelete(t, c)
		})
	}
}

// The wrapper must reject requests on an ended context, or the test above proves nothing.
func TestCtxClientRejectsEndedContext(t *testing.T) {
	c := seeded()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	e := ctxExecutor(ctxClient{Interface: c}, staticPlanner{drainablePlan()})
	if err := e.markAborted(ctx, "node-a", true); err == nil {
		t.Fatal("a patch on a cancelled context must fail")
	}
	rctx, rcancel := e.restoreContext(ctx)
	defer rcancel()
	if err := e.markAborted(rctx, "node-a", true); err != nil {
		t.Fatalf("restore context must outlive the cancelled drain context: %v", err)
	}
	if _, ok := rctx.Deadline(); !ok {
		t.Error("restore context must be bounded")
	}
}
