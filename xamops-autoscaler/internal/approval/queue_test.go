package approval

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

type outcomeServer struct {
	mu     sync.Mutex
	status int
	posts  []string // "<proposalId> <status>"
	paths  []string
	auth   string
	query  string
}

func (o *outcomeServer) handler(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	o.mu.Lock()
	o.auth, o.query = r.Header.Get("Authorization"), r.URL.RawQuery
	o.paths = append(o.paths, r.URL.Path)
	o.posts = append(o.posts, string(body))
	st := o.status
	o.mu.Unlock()
	if st == 409 {
		w.WriteHeader(409)
		_, _ = w.Write([]byte(`{"error":"not dispatched","code":"NOT_DISPATCHED"}`))
		return
	}
	w.WriteHeader(st)
}

func TestQueueRetriesUntilDelivered(t *testing.T) {
	o := &outcomeServer{status: 500}
	srv := httptest.NewServer(http.HandlerFunc(o.handler))
	t.Cleanup(srv.Close)
	c := NewClient(srv.URL, "secret", "my-cluster", "123")
	q := &Queue{}
	out := NewOutcome("p1", StatusSucceeded, StageExecution, "")

	if q.Deliver(context.Background(), c, out) {
		t.Fatal("5xx must not count as delivered")
	}
	if q.Len() != 1 || !q.Has("p1") {
		t.Fatalf("queue = %d", q.Len())
	}
	q.Flush(context.Background(), c) // still 500: kept
	if q.Len() != 1 {
		t.Fatalf("a failed retry must keep the outcome, len=%d", q.Len())
	}
	o.mu.Lock()
	o.status = 200
	o.mu.Unlock()
	q.Flush(context.Background(), c)
	if q.Len() != 0 {
		t.Errorf("200 must drain the queue, len=%d", q.Len())
	}
	if o.auth != "Bearer secret" || !strings.Contains(o.query, "clusterName=my-cluster") || !strings.Contains(o.query, "cloudAccountId=123") {
		t.Errorf("auth=%q query=%q", o.auth, o.query)
	}
	if o.paths[0] != "/api/v1/agent/proposals/p1/outcome" {
		t.Errorf("path = %s", o.paths[0])
	}
}

func TestQueueDropsOn409(t *testing.T) {
	o := &outcomeServer{status: 409}
	srv := httptest.NewServer(http.HandlerFunc(o.handler))
	t.Cleanup(srv.Close)
	q := &Queue{}
	if !q.Deliver(context.Background(), NewClient(srv.URL, "k", "c", "1"), NewOutcome("p1", StatusFailed, StageExecution, "x")) {
		t.Error("409 (backend already resolved it) must count as delivered")
	}
	if q.Len() != 0 {
		t.Errorf("len = %d", q.Len())
	}
}

func TestQueueReplacesSameProposalAndBounds(t *testing.T) {
	q := &Queue{}
	q.Add(NewOutcome("p1", StatusRunning, "", ""))
	q.Add(NewOutcome("p1", StatusSucceeded, "", ""))
	if q.Len() != 1 {
		t.Errorf("same proposal must be replaced, len=%d", q.Len())
	}
	for i := 0; i < maxQueued+25; i++ {
		q.Add(NewOutcome(strings.Repeat("x", 1)+string(rune('A'+i%26))+strings.Repeat("y", i), StatusFailed, "", ""))
	}
	if q.Len() > maxQueued {
		t.Errorf("queue grew to %d, cap %d", q.Len(), maxQueued)
	}
}

func TestClientClaim(t *testing.T) {
	var claimBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		claimBody = string(b)
		if r.URL.Query().Get("clusterName") == "none" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_, _ = w.Write(fixtureBytes(t, "approved_action.json"))
	}))
	t.Cleanup(srv.Close)
	if a, err := NewClient(srv.URL, "k", "none", "1").Claim(context.Background(), "c-1"); a != nil || err != nil {
		t.Errorf("204 must be (nil, nil), got %v %v", a, err)
	}
	a, err := NewClient(srv.URL, "k", "c", "1").Claim(context.Background(), "c-1")
	if err != nil || a == nil || a.ProposalID == "" || a.DwellSeconds != nil {
		t.Fatalf("claim = %+v err=%v", a, err)
	}
	if claimBody != `{"cycleId":"c-1"}` {
		t.Errorf("claim body = %s (only cycleId may be sent)", claimBody)
	}
}

func TestClientErrorCodes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "2")
		w.WriteHeader(429)
		_, _ = w.Write([]byte(`{"error":"slow down","code":"RATE_LIMITED"}`))
	}))
	t.Cleanup(srv.Close)
	_, err := NewClient(srv.URL, "k", "c", "1").PostProposals(context.Background(), Batch{})
	ae, ok := err.(*APIError)
	if !ok || ae.Status != 429 || ae.Code != "RATE_LIMITED" || ae.RetryAfter.Seconds() != 2 {
		t.Fatalf("err = %#v", err)
	}
	if StatusOf(err) != 429 || CodeOf(err) != "RATE_LIMITED" || StatusOf(io.EOF) != 0 {
		t.Error("StatusOf/CodeOf")
	}
}
