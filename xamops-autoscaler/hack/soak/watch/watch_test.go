package watch

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/xamops/automation/hack/soak/invariants"
)

// fakeRunner records every argv and fails the test on any argv that does not start
// with --context <ctx> or uses a verb outside AllowedVerbs.
type fakeRunner struct {
	t       *testing.T
	ctx     string
	mu      sync.Mutex
	calls   [][]string
	respond func(ctx context.Context, args []string) (io.Reader, error)
}

func (f *fakeRunner) Start(ctx context.Context, args []string) (io.ReadCloser, func() error, error) {
	f.mu.Lock()
	f.calls = append(f.calls, append([]string(nil), args...))
	f.mu.Unlock()
	if len(args) < 3 || args[0] != "--context" || args[1] != f.ctx || !AllowedVerbs[args[2]] {
		f.t.Errorf("unsafe kubectl argv: %q", args)
		return nil, nil, errors.New("unsafe argv")
	}
	r, err := f.respond(ctx, args)
	if err != nil {
		return nil, nil, err
	}
	return io.NopCloser(r), func() error { return nil }, nil
}

func (f *fakeRunner) argvs() [][]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]string(nil), f.calls...)
}

// blocking returns a reader that yields EOF only once ctx is done.
func blocking(ctx context.Context) io.Reader {
	pr, pw := io.Pipe()
	go func() {
		<-ctx.Done()
		_ = pw.Close()
	}()
	return pr
}

func has(args []string, s string) bool {
	for _, a := range args {
		if a == s {
			return true
		}
	}
	return false
}

func TestArgs(t *testing.T) {
	got, err := Args("ctx-a", "get", "nodes")
	if err != nil || strings.Join(got, " ") != "--context ctx-a get nodes" {
		t.Fatalf("Args = %q, %v", got, err)
	}
	for _, verb := range []string{"delete", "patch", "apply", "cordon", "uncordon", "drain", "scale", "annotate", "label", "edit", "create", "exec"} {
		if _, err := Args("ctx-a", verb, "nodes"); err == nil {
			t.Errorf("verb %q allowed", verb)
		}
	}
	for _, ctx := range []string{"", "  "} {
		if _, err := Args(ctx, "get", "nodes"); err == nil {
			t.Errorf("empty context %q allowed", ctx)
		}
	}
}

func TestPreflight(t *testing.T) {
	newRunner := func(kctx string) *fakeRunner {
		f := &fakeRunner{t: t, ctx: kctx}
		f.respond = func(_ context.Context, args []string) (io.Reader, error) {
			joined := strings.Join(args[2:], " ")
			switch {
			case strings.HasPrefix(joined, "config get-contexts"):
				return strings.NewReader("ctx-a\nctx-down\nctx-lan\nk8opt-test\n"), nil
			case strings.Contains(joined, ".contexts[0].context.cluster"):
				return strings.NewReader("cluster-" + kctx), nil
			case strings.Contains(joined, ".clusters[0].cluster.server"):
				if kctx == "ctx-lan" {
					return strings.NewReader("https://192.168.1.5:6443"), nil
				}
				return strings.NewReader("https://example.eks.amazonaws.com"), nil
			case joined == "get --raw /version":
				if kctx == "ctx-down" {
					return nil, errors.New("exit status 1")
				}
				return strings.NewReader(`{"gitVersion":"v1.30.0"}`), nil
			}
			t.Errorf("unexpected argv %q", args)
			return nil, errors.New("unexpected")
		}
		return f
	}
	cases := []struct {
		ctx, wantErr string
	}{
		{"", "required"},
		{"ctx-unknown", "not in the kubeconfig"},
		{"k8opt-test", "deny-listed"},
		{"ctx-lan", "deny-listed"},
		{"ctx-down", "apiserver check failed"},
		{"ctx-a", ""},
	}
	for _, tc := range cases {
		t.Run(tc.ctx, func(t *testing.T) {
			r := newRunner(tc.ctx)
			server, err := Preflight(context.Background(), r, tc.ctx)
			if tc.wantErr == "" {
				if err != nil || server != "https://example.eks.amazonaws.com" {
					t.Fatalf("Preflight = %q, %v", server, err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("err = %v, want %q", err, tc.wantErr)
			}
			if tc.ctx == "" && len(r.argvs()) != 0 {
				t.Errorf("kubectl ran without a context: %q", r.argvs())
			}
		})
	}
}

func fixedClock() func() time.Time {
	var mu sync.Mutex
	t0 := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	return func() time.Time {
		mu.Lock()
		defer mu.Unlock()
		t0 = t0.Add(1500 * time.Microsecond)
		return t0
	}
}

func decodeLines(t *testing.T, b []byte) []invariants.Record {
	t.Helper()
	var out []invariants.Record
	for _, l := range bytes.Split(bytes.TrimSpace(b), []byte("\n")) {
		if len(l) == 0 {
			continue
		}
		var r invariants.Record
		if err := json.Unmarshal(l, &r); err != nil {
			t.Fatalf("not JSONL: %s: %v", l, err)
		}
		out = append(out, r)
	}
	return out
}

// kubectl's pretty-printed concatenated watch events become one JSONL record each,
// with the real object (not <none>) and a nanosecond receive timestamp.
func TestStampWatchEvents(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "kubectl-watch-events.json"))
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	var seen []string
	n, err := Stamp(bytes.NewReader(raw), &buf, invariants.StreamNodes, fixedClock(), func(typ string, _ json.RawMessage) { seen = append(seen, typ) })
	if err != nil || n != 3 {
		t.Fatalf("Stamp = %d, %v", n, err)
	}
	recs := decodeLines(t, buf.Bytes())
	want := []string{"ADDED", "ADDED", "MODIFIED"}
	if len(recs) != 3 || strings.Join(seen, ",") != strings.Join(want, ",") {
		t.Fatalf("records = %d, callback types = %v", len(recs), seen)
	}
	for i, r := range recs {
		var obj struct {
			Metadata struct{ Name string } `json:"metadata"`
		}
		if err := json.Unmarshal(r.Object, &obj); err != nil || obj.Metadata.Name == "" {
			t.Errorf("record %d has no object name: %s", i, r.Object)
		}
		if r.Stream != invariants.StreamNodes || r.Type != want[i] || r.TS.Nanosecond() == 0 {
			t.Errorf("record %d = %s %s %s", i, r.Stream, r.Type, r.TS.Format(time.RFC3339Nano))
		}
	}
	if bytes.Count(buf.Bytes(), []byte("\n")) != 3 {
		t.Errorf("want exactly one line per record:\n%s", buf.Bytes())
	}
}

// A watch that kubectl ends (server-side timeout) is restarted for the whole window.
func TestSuperviseRestartsExitedWatch(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "kubectl-watch-events.json"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var mu sync.Mutex
	starts := 0
	f := &fakeRunner{t: t, ctx: "ctx-a"}
	f.respond = func(ctx context.Context, args []string) (io.Reader, error) {
		mu.Lock()
		defer mu.Unlock()
		starts++
		if starts <= 2 {
			return bytes.NewReader(raw), nil // exits after 3 events
		}
		cancel() // the third watch stays open until the window ends
		return blocking(ctx), nil
	}
	w := New(Config{Context: "ctx-a", Backoff: time.Millisecond}, f, fixedClock())
	var buf bytes.Buffer
	w.Supervise(ctx, invariants.StreamNodes, StreamArgs("xamops-system", "lease")[invariants.StreamNodes], &buf, nil)

	st := w.Stats()[invariants.StreamNodes]
	if st.Restarts != 2 || st.Records != 6 {
		t.Fatalf("stats = %+v, want restarts=2 records=6", st)
	}
	if got := len(decodeLines(t, buf.Bytes())); got != 6 {
		t.Errorf("lines = %d", got)
	}
	for _, a := range f.argvs() {
		if !has(a, "--watch") || !has(a, "--output-watch-events") || !has(a, "json") {
			t.Errorf("watch argv lacks the watch-event JSON flags: %q", a)
		}
	}
}

func podEvent(typ, ns, name, uid string, lbl map[string]string) string {
	b, _ := json.Marshal(map[string]interface{}{"type": typ, "object": map[string]interface{}{
		"apiVersion": "v1", "kind": "Pod",
		"metadata": map[string]interface{}{"namespace": ns, "name": name, "uid": uid, "labels": lbl}}})
	return string(b) + "\n"
}

// Exactly one log follower per agent pod UID, none for non-matching pods; a
// restarted follower drops lines already written.
func TestAgentLogFollower(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	agent := map[string]string{"app": "xamops-automation"}
	logs := "2026-01-01T00:00:01.500000000Z 2026/01/01 00:00:01 Drain: a/web-1 replaced by a/web-2 (uid u2) on n2\n" +
		"2026-01-01T00:00:02.000000000Z cycle end\n"
	var mu sync.Mutex
	logCalls := 0
	f := &fakeRunner{t: t, ctx: "ctx-a"}
	f.respond = func(ctx context.Context, args []string) (io.Reader, error) {
		if args[2] != "logs" {
			t.Errorf("unexpected argv %q", args)
			return nil, errors.New("unexpected")
		}
		mu.Lock()
		logCalls++
		n := logCalls
		mu.Unlock()
		if n == 1 {
			return strings.NewReader(logs), nil // log stream ends; the pod still exists
		}
		// The restart (--since-time) re-sends the same lines, then stays open.
		return io.MultiReader(strings.NewReader(logs), blocking(ctx)), nil
	}
	w := New(Config{Context: "ctx-a", AgentNamespace: "xamops-system", AgentSelector: "app=xamops-automation", Backoff: time.Millisecond}, f, fixedClock())
	var out bytes.Buffer
	sw := NewSyncWriter(&out)
	onPod, err := w.AgentPodWatcher(ctx, sw)
	if err != nil {
		t.Fatal(err)
	}
	events := podEvent("ADDED", "xamops-system", "agent-a", "ua", agent) +
		podEvent("MODIFIED", "xamops-system", "agent-a", "ua", agent) +
		podEvent("ADDED", "team-a", "lookalike", "ub", agent) +
		podEvent("ADDED", "xamops-system", "other", "uc", map[string]string{"app": "other"})
	if _, err := Stamp(strings.NewReader(events), io.Discard, invariants.StreamPods, fixedClock(), onPod); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		mu.Lock()
		n := logCalls
		mu.Unlock()
		if n >= 2 || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	w.wg.Wait()

	firsts := 0
	for _, a := range f.argvs() {
		if !has(a, "pod/agent-a") || !has(a, "-f") || !has(a, "--timestamps") {
			t.Errorf("log argv = %q", a)
		}
		since := false
		for _, x := range a {
			since = since || strings.HasPrefix(x, "--since-time=")
		}
		if !since {
			firsts++
		}
	}
	if firsts != 1 || len(f.argvs()) < 2 {
		t.Fatalf("log argvs = %q, want one follower (one start without --since-time) plus a restart", f.argvs())
	}
	recs := decodeLines(t, out.Bytes())
	if len(recs) != 2 {
		t.Fatalf("agent log records = %d, want 2 (restart duplicates dropped):\n%s", len(recs), out.Bytes())
	}
	if recs[0].Type != "LOG" || recs[0].Pod != "agent-a" || !strings.HasPrefix(recs[0].Line, "2026/01/01 00:00:01 Drain:") ||
		!recs[0].TS.Equal(time.Date(2026, 1, 1, 0, 0, 1, 500000000, time.UTC)) {
		t.Errorf("record = %+v", recs[0])
	}
	if st := w.Stats()[invariants.StreamAgentLogs]; st.Restarts != 1 || st.Records != 2 {
		t.Errorf("agent-logs stats = %+v", st)
	}
}
