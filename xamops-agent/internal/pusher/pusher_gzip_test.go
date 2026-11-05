package pusher

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/xamops/agent/internal/collector"
	"github.com/xamops/agent/internal/config"
)

// TestPushGzip proves PERF-8 round-trip: Push compresses the body and sets Content-Encoding: gzip,
// and gunzipping the received body yields JSON that unmarshals back to the original payload.
func TestPushGzip(t *testing.T) {
	var gotEncoding string
	var gotBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotEncoding = r.Header.Get("Content-Encoding")
		b, _ := io.ReadAll(r.Body)
		gotBody = b
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	cfg := &config.Config{
		ServerURL:      srv.URL,
		APIKey:         "test-key",
		ClusterName:    "c1",
		CloudAccountID: "a1",
		MaxRetries:     1,
		RetryDelay:     0,
	}
	p := NewPusher(cfg)

	payload := &collector.AgentPayload{
		SchemaVersion:  "1.0",
		ClusterName:    "c1",
		CloudAccountID: "a1",
		Batches: []collector.DataBatch{{
			Collector: "db-table-sizes",
			Count:     2,
			Items:     []string{"t1", "t2"},
		}},
	}
	if err := p.Push(payload); err != nil {
		t.Fatalf("Push failed: %v", err)
	}

	if gotEncoding != "gzip" {
		t.Fatalf("expected Content-Encoding gzip, got %q", gotEncoding)
	}

	zr, err := gzip.NewReader(bytes.NewReader(gotBody))
	if err != nil {
		t.Fatalf("gzip.NewReader: %v", err)
	}
	plain, err := io.ReadAll(zr)
	if err != nil {
		t.Fatalf("gunzip body: %v", err)
	}

	var round collector.AgentPayload
	if err := json.Unmarshal(plain, &round); err != nil {
		t.Fatalf("unmarshal inflated body: %v", err)
	}
	if round.SchemaVersion != "1.0" || round.CloudAccountID != "a1" {
		t.Fatalf("round-trip header mismatch: %+v", round)
	}
	if len(round.Batches) != 1 || round.Batches[0].Collector != "db-table-sizes" || round.Batches[0].Count != 2 {
		t.Fatalf("round-trip batches mismatch: %+v", round.Batches)
	}
}
