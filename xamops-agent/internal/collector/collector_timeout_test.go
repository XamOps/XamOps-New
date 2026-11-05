package collector

import (
	"context"
	"errors"
	"testing"
	"time"
)

// TestRunDBCollectorTimeout proves PERF-7 isolation: a collector stalled on its (simulated) lock is
// bounded to its own child deadline, and the NEXT collector on the same parent starts on a fresh
// deadline rather than an already-expired one.
func TestRunDBCollectorTimeout(t *testing.T) {
	parent := context.Background()
	timeout := 100 * time.Millisecond

	// 1. A collector that blocks until its child context is cancelled (a held lock), returning ctx.Err().
	start := time.Now()
	_, err := runDBCollector(parent, "stalled", timeout, func(c context.Context) (DataBatch, error) {
		<-c.Done()
		return DataBatch{}, c.Err()
	})
	elapsed := time.Since(start)

	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded from the stalled collector, got %v", err)
	}
	if elapsed > timeout+2*time.Second {
		t.Fatalf("stalled collector did not return near its own timeout: took %v", elapsed)
	}

	// 2. The next collector on the SAME parent must receive a fresh (non-expired) deadline and run.
	var remaining time.Duration
	batch, err := runDBCollector(parent, "next", timeout, func(c context.Context) (DataBatch, error) {
		dl, ok := c.Deadline()
		if !ok {
			return DataBatch{}, errors.New("expected a deadline on the child context")
		}
		remaining = time.Until(dl)
		return DataBatch{Collector: "next", Count: 1}, nil
	})
	if err != nil {
		t.Fatalf("second collector errored: %v", err)
	}
	if batch.Count != 1 {
		t.Fatalf("expected the second collector to return its items, got count=%d", batch.Count)
	}
	if remaining < timeout/2 {
		t.Fatalf("second collector got a stale (near-expired) deadline: %v remaining", remaining)
	}
}
