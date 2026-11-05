package approval

import (
	"context"
	"log"
	"sync"
)

// maxQueued bounds the in-memory queue so a backend that stays unreachable for days
// cannot grow it without limit. The oldest entry is dropped first; the backend's
// dispatch lease and reconcile resolve a dropped row.
const maxQueued = 200

// Queue holds terminal outcomes whose POST failed, to be retried at the top of every
// later cycle until the backend answers 200 or 409. It is process memory: after a
// restart, reconcile (GET /dispatched) rebuilds the outcome from the node.
type Queue struct {
	mu    sync.Mutex
	items []Outcome
}

// Add queues an outcome (replacing any queued outcome for the same proposal).
func (q *Queue) Add(o Outcome) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for i := range q.items {
		if q.items[i].ProposalID == o.ProposalID {
			q.items[i] = o
			return
		}
	}
	q.items = append(q.items, o)
	if len(q.items) > maxQueued {
		q.items = q.items[len(q.items)-maxQueued:]
	}
}

// Has reports whether an outcome for the proposal is queued.
func (q *Queue) Has(proposalID string) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	for _, o := range q.items {
		if o.ProposalID == proposalID {
			return true
		}
	}
	return false
}

// Len is the number of queued outcomes.
func (q *Queue) Len() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.items)
}

// Deliver posts o. A 200 or a 409 (the backend already resolved the row) is done; any
// transport error, 5xx or other status queues it. It returns true when delivered.
func (q *Queue) Deliver(ctx context.Context, c *Client, o Outcome) bool {
	err := c.ReportOutcome(ctx, o)
	if err == nil || StatusOf(err) == 409 {
		return true
	}
	log.Printf("WARN: proposal outcome for %s not delivered (%v) — queued for the next cycle", o.ProposalID, err)
	q.Add(o)
	return false
}

// Flush retries every queued outcome once, keeping the ones that still fail.
func (q *Queue) Flush(ctx context.Context, c *Client) {
	q.mu.Lock()
	pending := append([]Outcome(nil), q.items...)
	q.items = nil
	q.mu.Unlock()
	for _, o := range pending {
		if ctx.Err() != nil {
			q.Add(o)
			continue
		}
		err := c.ReportOutcome(ctx, o)
		if err == nil || StatusOf(err) == 409 {
			continue
		}
		log.Printf("WARN: proposal outcome retry for %s failed (%v) — kept queued", o.ProposalID, err)
		q.Add(o)
	}
}
