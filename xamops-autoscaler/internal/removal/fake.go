package removal

import (
	"context"
	"fmt"
	"sync"
)

// TerminateCall records one TerminateAndDecrement call. decrement is always true
// for a correct AWSProvider; the fake records what it was actually asked to do.
type TerminateCall struct {
	InstanceID string
	ASG        string
	Decrement  bool
}

// FakeProvider is the test CloudProvider. It serves scriptable NodeFacts per
// instance, records every TerminateAndDecrement call, and can inject an error on
// the Nth terminate call. It is safe for concurrent use.
type FakeProvider struct {
	mu sync.Mutex

	// Facts is the scripted Lookup result per instance id. A missing id yields
	// LookupErr (or a not-found error when LookupErr is nil).
	Facts map[string]NodeFacts
	// LookupErr, when set, is returned by every Lookup.
	LookupErr error

	// FailOnCall, when > 0, makes the FailOnCall-th TerminateAndDecrement call (1-
	// based) return TerminateErr (or a default error). The call is still recorded.
	FailOnCall   int
	TerminateErr error

	// TerminateCalls is the ordered log of every TerminateAndDecrement call.
	TerminateCalls []TerminateCall
	terminated     map[string]bool // idempotency: an already-terminated instance is a no-op
}

// NewFakeProvider returns an empty fake with no scripted facts.
func NewFakeProvider() *FakeProvider {
	return &FakeProvider{Facts: map[string]NodeFacts{}, terminated: map[string]bool{}}
}

// Lookup returns the scripted NodeFacts for instanceID.
func (f *FakeProvider) Lookup(ctx context.Context, instanceID string) (NodeFacts, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.LookupErr != nil {
		return NodeFacts{}, f.LookupErr
	}
	nf, ok := f.Facts[instanceID]
	if !ok {
		return NodeFacts{}, fmt.Errorf("fake: no NodeFacts scripted for %s", instanceID)
	}
	return nf, nil
}

// TerminateAndDecrement records the call (always with decrement=true, the only
// correct value) and honours the scripted Nth-call error. It is idempotent on an
// already-terminated instance.
func (f *FakeProvider) TerminateAndDecrement(ctx context.Context, instanceID, asg string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.TerminateCalls = append(f.TerminateCalls, TerminateCall{InstanceID: instanceID, ASG: asg, Decrement: true})
	if f.FailOnCall > 0 && len(f.TerminateCalls) == f.FailOnCall {
		if f.TerminateErr != nil {
			return f.TerminateErr
		}
		return fmt.Errorf("fake: injected error on terminate call %d", f.FailOnCall)
	}
	if f.terminated[instanceID] {
		return nil // idempotent
	}
	f.terminated[instanceID] = true
	return nil
}

// Terminations returns a copy of the call log.
func (f *FakeProvider) Terminations() []TerminateCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]TerminateCall(nil), f.TerminateCalls...)
}
