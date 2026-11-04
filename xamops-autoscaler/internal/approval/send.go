package approval

import (
	"context"
	"encoding/json"
	"log"
	"sort"
	"strconv"
	"time"
)

// Per-POST and per-cycle limits (3.8 chunking). The backend accepts twice the per-POST
// limits; the agent stays inside them.
const (
	MaxItemsPerPost  = 100
	MaxBytesPerPost  = 1 << 20
	MaxChunksPerCyc  = 5
	MaxItemsPerCycle = MaxItemsPerPost * MaxChunksPerCyc
	// ChunkGap is the pacing between chunks of one cycle (the backend allows one POST
	// per cluster per 2 s).
	ChunkGap = 2100 * time.Millisecond
)

// SortItems orders a cycle's items: ALLOWED first, then REFUSED, each in stable
// dedupeKey order. Items past the per-cycle cap are the REFUSED ones.
func SortItems(items []Item) {
	sort.SliceStable(items, func(i, j int) bool {
		ai, aj := items[i].Verdict == VerdictAllowed, items[j].Verdict == VerdictAllowed
		if ai != aj {
			return ai
		}
		return items[i].DedupeKey < items[j].DedupeKey
	})
}

// BuildChunks splits items into POST bodies of at most maxItems items and maxBytes
// bytes of serialized items. A single item larger than maxBytes still gets its own
// chunk (the backend then rejects it item-level, TOO_LARGE).
func BuildChunks(items []Item, maxItems, maxBytes int) [][]Item {
	var chunks [][]Item
	var cur []Item
	size := 0
	for _, it := range items {
		b, _ := json.Marshal(it)
		if len(cur) > 0 && (len(cur) >= maxItems || size+len(b) > maxBytes) {
			chunks = append(chunks, cur)
			cur, size = nil, 0
		}
		cur = append(cur, it)
		size += len(b)
	}
	if len(cur) > 0 {
		chunks = append(chunks, cur)
	}
	return chunks
}

// RebalanceRequest tells Send the cycle enumerated Candidates rebalance candidates for
// a running rebalance request.
type RebalanceRequest struct {
	Candidates int
}

// SendResult summarises one cycle's delivery.
type SendResult struct {
	Chunks  int
	Dropped int
	// RebalanceComplete is the complete flag sent on the final chunk (false when no
	// rebalance object was sent).
	RebalanceComplete bool
	RebalanceSent     bool
	// RateLimited is true when a 429 ended the cycle's batch.
	RateLimited bool
}

// Sender delivers a cycle's items in paced chunks.
type Sender struct {
	Client *Client
	// Sleep pauses between chunks; nil uses a context-aware real sleep.
	Sleep func(ctx context.Context, d time.Duration) error
	// Gap overrides ChunkGap (tests).
	Gap time.Duration
	// OnRejected is called with the backend code of every 400/413 (and other
	// non-429 4xx) answer; OnDropped with the number of items dropped for the cap.
	OnRejected func(code string)
	OnDropped  func(n int)
}

func realSleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Send delivers items. It never retries a chunk within the cycle: a 429 ends the
// batch (the next cycle re-sends everything; dedupe makes that idempotent).
func (s *Sender) Send(ctx context.Context, cycleID, observedAt string, items []Item, rb *RebalanceRequest) SendResult {
	var res SendResult
	SortItems(items)
	if len(items) > MaxItemsPerCycle {
		res.Dropped = len(items) - MaxItemsPerCycle
		items = items[:MaxItemsPerCycle]
	}
	chunks := BuildChunks(items, MaxItemsPerPost, MaxBytesPerPost)
	if len(chunks) > MaxChunksPerCyc {
		for _, c := range chunks[MaxChunksPerCyc:] {
			res.Dropped += len(c)
		}
		chunks = chunks[:MaxChunksPerCyc]
	}
	if res.Dropped > 0 {
		log.Printf("WARN: manual: %d proposal item(s) dropped for this cycle (cap %d items / %d chunks)", res.Dropped, MaxItemsPerCycle, MaxChunksPerCyc)
		if s.OnDropped != nil {
			s.OnDropped(res.Dropped)
		}
	}
	if len(chunks) == 0 {
		if rb == nil {
			return res
		}
		chunks = [][]Item{{}} // an empty final chunk may carry the rebalance object
	}
	sleep, gap := s.Sleep, s.Gap
	if sleep == nil {
		sleep = realSleep
	}
	if gap <= 0 {
		gap = ChunkGap
	}
	cleanSoFar := res.Dropped == 0 // every earlier chunk delivered, no QUEUE_FULL, nothing dropped
	for i, chunk := range chunks {
		if i > 0 {
			if err := sleep(ctx, gap); err != nil {
				return res
			}
		}
		b := Batch{SchemaVersion: SchemaVersion, CycleID: cycleID, ObservedAt: observedAt, Items: chunk}
		if b.Items == nil {
			b.Items = []Item{}
		}
		last := i == len(chunks)-1
		if last && rb != nil {
			n := rb.Candidates
			complete := cleanSoFar && n <= 500
			if n > 500 {
				log.Printf("WARN: manual: %d rebalance candidates exceed the 500 cap — sending complete=false (request not consumed)", n)
				n = 500
			}
			b.Rebalance = &Rebalance{Complete: complete, CandidateCount: n}
			res.RebalanceSent, res.RebalanceComplete = true, complete
		}
		resp, err := s.Client.PostProposals(ctx, b)
		res.Chunks++
		if err != nil {
			cleanSoFar = false
			if ae, ok := err.(*APIError); ok {
				if ae.Status == 429 {
					res.RateLimited = true
					log.Printf("WARN: manual: proposals rate limited (retry-after %s) — rest of this cycle's batch deferred", ae.RetryAfter)
					return res
				}
				log.Printf("WARN: manual: proposal batch rejected HTTP %d code=%q: %s", ae.Status, ae.Code, ae.Message)
				if ae.Status >= 400 && ae.Status < 500 && s.OnRejected != nil {
					code := ae.Code
					if code == "" {
						code = "HTTP_" + strconv.Itoa(ae.Status)
					}
					s.OnRejected(code)
				}
			} else {
				log.Printf("WARN: manual: proposal batch POST failed: %v", err)
			}
			if last && b.Rebalance != nil {
				res.RebalanceComplete = false
			}
			continue
		}
		if resp.QueueFull() {
			cleanSoFar = false
			// The final chunk's own QUEUE_FULL: the backend declines to consume.
			if last && b.Rebalance != nil {
				res.RebalanceComplete = false
			}
		}
		for _, r := range resp.Rejected {
			log.Printf("WARN: manual: proposal %s rejected by the backend: %s", r.DedupeKey, r.Code)
		}
	}
	return res
}
