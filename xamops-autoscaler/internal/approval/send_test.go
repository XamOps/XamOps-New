package approval

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

type recorder struct {
	mu      sync.Mutex
	batches []Batch
	sizes   []int
	status  func(n int) (int, string) // n = 1-based request number
}

func (r *recorder) handler(w http.ResponseWriter, req *http.Request) {
	body, _ := io.ReadAll(req.Body)
	var b Batch
	_ = json.Unmarshal(body, &b)
	r.mu.Lock()
	r.batches = append(r.batches, b)
	r.sizes = append(r.sizes, len(body))
	n := len(r.batches)
	r.mu.Unlock()
	code, payload := 200, fmt.Sprintf(`{"accepted":%d,"suppressed":[],"rejected":[]}`, len(b.Items))
	if r.status != nil {
		code, payload = r.status(n)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(payload))
}

func newSender(t *testing.T, r *recorder) (*Sender, *[]string, *[]int) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(r.handler))
	t.Cleanup(srv.Close)
	var rejected []string
	var dropped []int
	return &Sender{
		Client:     NewClient(srv.URL, "k", "c", "1"),
		Sleep:      func(context.Context, time.Duration) error { return nil },
		OnRejected: func(code string) { rejected = append(rejected, code) },
		OnDropped:  func(n int) { dropped = append(dropped, n) },
	}, &rejected, &dropped
}

func nItems(n int) []Item {
	items := make([]Item, n)
	for i := range items {
		items[i] = Item{
			DedupeKey: fmt.Sprintf("REBALANCE:DRAIN:rebalancer:node-%04d", i), ActionType: TypeRebalance, Operation: OpDrain,
			OriginController: OriginRebalancer, Target: Target{Kind: "NODE", Name: fmt.Sprintf("node-%04d", i)},
			Params: json.RawMessage(`{}`), Verdict: VerdictAllowed, Refusal: Refusal{Codes: []string{}},
			PlanFingerprint: "sha256:" + fmt.Sprintf("%064d", i), Evidence: json.RawMessage(`{"fit":{}}`),
		}
	}
	return items
}

// AC-G11: 150 candidates -> two chunks (100 + 50), each within the limits, the
// rebalance object only on the second, complete:true.
func TestSend150CandidatesTwoChunks(t *testing.T) {
	r := &recorder{}
	s, rejected, dropped := newSender(t, r)
	res := s.Send(context.Background(), "c-1", "2026-11-01T10:00:00Z", nItems(150), &RebalanceRequest{Candidates: 150})
	if res.Chunks != 2 || len(r.batches) != 2 || res.Dropped != 0 {
		t.Fatalf("chunks=%d posted=%d dropped=%d", res.Chunks, len(r.batches), res.Dropped)
	}
	if len(r.batches[0].Items) != 100 || len(r.batches[1].Items) != 50 {
		t.Errorf("chunk sizes = %d/%d", len(r.batches[0].Items), len(r.batches[1].Items))
	}
	for i, sz := range r.sizes {
		if sz > MaxBytesPerPost {
			t.Errorf("chunk %d is %d bytes (> 1 MB)", i, sz)
		}
	}
	if r.batches[0].Rebalance != nil {
		t.Error("rebalance sent on a non-final chunk")
	}
	if rb := r.batches[1].Rebalance; rb == nil || !rb.Complete || rb.CandidateCount != 150 {
		t.Errorf("final rebalance = %+v", rb)
	}
	if !res.RebalanceSent || !res.RebalanceComplete || len(*rejected) != 0 || len(*dropped) != 0 {
		t.Errorf("result = %+v rejected=%v dropped=%v", res, *rejected, *dropped)
	}
	for _, b := range r.batches {
		if b.SchemaVersion != 1 || b.CycleID != "c-1" {
			t.Errorf("batch header = %+v", b)
		}
	}
}

// AC-G11: 600 candidates -> five chunks, 100 dropped (metric callback), complete:false.
func TestSend600CandidatesFiveChunksDropsAndNeverCompletes(t *testing.T) {
	r := &recorder{}
	s, _, dropped := newSender(t, r)
	res := s.Send(context.Background(), "c-1", "t", nItems(600), &RebalanceRequest{Candidates: 600})
	if res.Chunks != 5 || res.Dropped != 100 || len(*dropped) != 1 || (*dropped)[0] != 100 {
		t.Fatalf("chunks=%d dropped=%d callbacks=%v", res.Chunks, res.Dropped, *dropped)
	}
	rb := r.batches[4].Rebalance
	if rb == nil || rb.Complete || rb.CandidateCount != 500 {
		t.Errorf("final rebalance = %+v, want complete=false candidateCount=500", rb)
	}
}

func TestSendRebalanceNotCompleteAfterQueueFullOrFailedChunk(t *testing.T) {
	r := &recorder{status: func(n int) (int, string) {
		if n == 1 {
			return 200, `{"accepted":99,"suppressed":[],"rejected":[{"dedupeKey":"k","code":"QUEUE_FULL"}]}`
		}
		return 200, `{"accepted":50,"suppressed":[],"rejected":[]}`
	}}
	s, _, _ := newSender(t, r)
	res := s.Send(context.Background(), "c", "t", nItems(150), &RebalanceRequest{Candidates: 150})
	if res.RebalanceComplete || r.batches[1].Rebalance == nil || r.batches[1].Rebalance.Complete {
		t.Errorf("QUEUE_FULL in an earlier chunk must make complete=false: %+v / %+v", res, r.batches[1].Rebalance)
	}

	r2 := &recorder{status: func(n int) (int, string) {
		if n == 1 {
			return 500, `{}`
		}
		return 200, `{"accepted":50,"suppressed":[],"rejected":[]}`
	}}
	s2, _, _ := newSender(t, r2)
	res2 := s2.Send(context.Background(), "c", "t", nItems(150), &RebalanceRequest{Candidates: 150})
	if res2.RebalanceComplete || res2.Chunks != 2 {
		t.Errorf("a failed earlier chunk must make complete=false and later chunks still go: %+v", res2)
	}
}

// AC-G11: a 400 / 413 is counted by backend code and logged; it is not silent.
func TestSendCountsRejectedByCode(t *testing.T) {
	r := &recorder{status: func(n int) (int, string) {
		if n == 1 {
			return 413, `{"error":"too big","code":"TOO_LARGE"}`
		}
		return 400, `{"error":"bad","code":"BAD_REBALANCE"}`
	}}
	s, rejected, _ := newSender(t, r)
	s.Send(context.Background(), "c", "t", nItems(150), &RebalanceRequest{Candidates: 150})
	if len(*rejected) != 2 || (*rejected)[0] != "TOO_LARGE" || (*rejected)[1] != "BAD_REBALANCE" {
		t.Errorf("rejected codes = %v", *rejected)
	}
}

func TestSend429EndsTheBatch(t *testing.T) {
	r := &recorder{status: func(n int) (int, string) { return 429, `{"code":"RATE_LIMITED"}` }}
	s, rejected, _ := newSender(t, r)
	res := s.Send(context.Background(), "c", "t", nItems(250), nil)
	if !res.RateLimited || len(r.batches) != 1 || len(*rejected) != 0 {
		t.Errorf("429 must end the batch without retry: %+v posted=%d rejected=%v", res, len(r.batches), *rejected)
	}
}

func TestSendEmptyItemsWithRebalanceSendsOneEmptyChunk(t *testing.T) {
	r := &recorder{}
	s, _, _ := newSender(t, r)
	res := s.Send(context.Background(), "c", "t", nil, &RebalanceRequest{Candidates: 0})
	if len(r.batches) != 1 || len(r.batches[0].Items) != 0 || r.batches[0].Rebalance == nil || !r.batches[0].Rebalance.Complete {
		t.Fatalf("batches = %+v", r.batches)
	}
	if !res.RebalanceComplete {
		t.Errorf("res = %+v", res)
	}
	// Nothing to say, nothing to send.
	r2 := &recorder{}
	s2, _, _ := newSender(t, r2)
	s2.Send(context.Background(), "c", "t", nil, nil)
	if len(r2.batches) != 0 {
		t.Error("empty cycle posted")
	}
}

func TestSortItemsAllowedFirstThenByKey(t *testing.T) {
	items := []Item{
		{DedupeKey: "b", Verdict: VerdictRefused}, {DedupeKey: "z", Verdict: VerdictAllowed},
		{DedupeKey: "a", Verdict: VerdictRefused}, {DedupeKey: "m", Verdict: VerdictAllowed},
	}
	SortItems(items)
	got := items[0].DedupeKey + items[1].DedupeKey + items[2].DedupeKey + items[3].DedupeKey
	if got != "mzab" {
		t.Errorf("order = %s, want mzab", got)
	}
}

func TestBuildChunksSplitsOnBytes(t *testing.T) {
	big := nItems(5)
	for i := range big {
		big[i].Evidence = json.RawMessage(`{"pad":"` + string(make([]byte, 0)) + fmt.Sprintf("%0*d", 300000, 0) + `"}`)
	}
	chunks := BuildChunks(big, MaxItemsPerPost, MaxBytesPerPost)
	if len(chunks) < 2 {
		t.Fatalf("5 items of ~300 KB must split on the 1 MB limit, got %d chunk(s)", len(chunks))
	}
	total := 0
	for _, c := range chunks {
		b, _ := json.Marshal(c)
		if len(b) > MaxBytesPerPost+1024 {
			t.Errorf("chunk of %d bytes", len(b))
		}
		total += len(c)
	}
	if total != 5 {
		t.Errorf("items lost: %d", total)
	}
}
