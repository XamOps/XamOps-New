// Package watch records a soak window as timestamped JSONL streams, one file per
// resource, for the invariants checker.
//
// It drives kubectl subprocesses (`get ... --watch --output-watch-events -o json` and
// `logs -f --timestamps`) instead of client-go: kubectl already handles kubeconfig and
// EKS exec credentials, and the agent module stays free of new dependencies.
//
// Root cause of the earlier empty soak watches: `kubectl get -w --output-watch-events
// -o custom-columns=...` wraps every object in a {type, object} watch event, so the
// .metadata/.spec/.status column paths resolved to <none> on every row. This package
// records the full event as JSON and decodes it with encoding/json, so nothing is
// lost to a printer. It also stamps every record with a nanosecond receive time,
// restarts a watch the apiserver closes (server-side watch timeout), and follows the
// agent logs continuously.
//
// Safety: every kubectl argv is built by Args, which always injects
// `--context <ctx>` first and allows only the read-only verbs get, logs and config.
// The watcher cannot mutate the cluster.
package watch

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"k8s.io/apimachinery/pkg/labels"

	"github.com/xamops/automation/hack/soak/invariants"
)

// AllowedVerbs are the only kubectl verbs the watcher may run (all read-only).
var AllowedVerbs = map[string]bool{"get": true, "logs": true, "config": true}

// DenyList matches contexts, clusters and servers the watcher refuses to touch.
var DenyList = regexp.MustCompile(`k8opt-test|autospotting|192\.168\.`)

// Args builds a kubectl argv: `--context <kctx> <verb> <rest...>`. It refuses an
// empty context and any verb outside AllowedVerbs.
func Args(kctx, verb string, rest ...string) ([]string, error) {
	if strings.TrimSpace(kctx) == "" {
		return nil, errors.New("kube context is required (--context)")
	}
	if !AllowedVerbs[verb] {
		return nil, fmt.Errorf("kubectl verb %q is not allowed (read-only: get, logs, config)", verb)
	}
	return append([]string{"--context", kctx, verb}, rest...), nil
}

// Runner starts a kubectl process and returns its stdout and a wait function.
type Runner interface {
	Start(ctx context.Context, args []string) (io.ReadCloser, func() error, error)
}

// ExecRunner runs the real kubectl binary.
type ExecRunner struct {
	Kubectl string    // default "kubectl"
	Stderr  io.Writer // kubectl stderr; default os.Stderr
}

// Start implements Runner.
func (e ExecRunner) Start(ctx context.Context, args []string) (io.ReadCloser, func() error, error) {
	bin := e.Kubectl
	if bin == "" {
		bin = "kubectl"
	}
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Stderr = e.Stderr
	if cmd.Stderr == nil {
		cmd.Stderr = os.Stderr
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	return out, cmd.Wait, nil
}

func output(ctx context.Context, r Runner, args []string) ([]byte, error) {
	out, wait, err := r.Start(ctx, args)
	if err != nil {
		return nil, err
	}
	b, rerr := io.ReadAll(out)
	if werr := wait(); werr != nil {
		return b, werr
	}
	return b, rerr
}

func run(ctx context.Context, r Runner, kctx, verb string, rest ...string) (string, error) {
	args, err := Args(kctx, verb, rest...)
	if err != nil {
		return "", err
	}
	b, err := output(ctx, r, args)
	if err != nil {
		return "", fmt.Errorf("kubectl %s: %w", strings.Join(args, " "), err)
	}
	return strings.TrimSpace(string(b)), nil
}

// Preflight refuses an empty, unknown or deny-listed context and checks that the
// apiserver answers. It returns the context's server URL.
func Preflight(ctx context.Context, r Runner, kctx string) (string, error) {
	if strings.TrimSpace(kctx) == "" {
		return "", errors.New("kube context is required (--context); refusing to use the current context")
	}
	if DenyList.MatchString(kctx) {
		return "", fmt.Errorf("context %q is deny-listed", kctx)
	}
	names, err := run(ctx, r, kctx, "config", "get-contexts", "-o", "name")
	if err != nil {
		return "", err
	}
	found := false
	for _, n := range strings.Split(names, "\n") {
		if strings.TrimSpace(n) == kctx {
			found = true
		}
	}
	if !found {
		return "", fmt.Errorf("context %q is not in the kubeconfig", kctx)
	}
	cluster, err := run(ctx, r, kctx, "config", "view", "--minify", "-o", "jsonpath={.contexts[0].context.cluster}")
	if err != nil {
		return "", err
	}
	server, err := run(ctx, r, kctx, "config", "view", "--minify", "-o", "jsonpath={.clusters[0].cluster.server}")
	if err != nil {
		return "", err
	}
	if DenyList.MatchString(cluster) || DenyList.MatchString(server) {
		return "", fmt.Errorf("context %q points at deny-listed cluster %q (%s)", kctx, cluster, server)
	}
	if _, err := run(ctx, r, kctx, "get", "--raw", "/version"); err != nil {
		return "", fmt.Errorf("apiserver check failed (credentials or connectivity): %w", err)
	}
	return server, nil
}

// SyncWriter serialises whole-record writes to one file.
type SyncWriter struct {
	mu sync.Mutex
	w  io.Writer
}

// NewSyncWriter wraps w.
func NewSyncWriter(w io.Writer) *SyncWriter { return &SyncWriter{w: w} }

// Write writes p with a single Write call.
func (s *SyncWriter) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.w.Write(p)
}

type watchEvent struct {
	Type   string          `json:"type"`
	Object json.RawMessage `json:"object"`
}

// Stamp decodes kubectl's concatenated (pretty-printed) watch-event JSON from r and
// writes one JSONL record per event to w, stamped with now(). onEvent, when set, sees
// every event after it is written. It returns the number of records written.
func Stamp(r io.Reader, w io.Writer, stream string, now func() time.Time, onEvent func(typ string, obj json.RawMessage)) (int, error) {
	dec := json.NewDecoder(r)
	n := 0
	for {
		var ev watchEvent
		if err := dec.Decode(&ev); err != nil {
			if errors.Is(err, io.EOF) {
				return n, nil
			}
			return n, err
		}
		ts := now().UTC()
		var obj bytes.Buffer
		if len(ev.Object) > 0 {
			if err := json.Compact(&obj, ev.Object); err != nil {
				return n, err
			}
		}
		line, err := json.Marshal(invariants.Record{TS: ts, Stream: stream, Type: ev.Type, Object: obj.Bytes()})
		if err != nil {
			return n, err
		}
		if _, err := w.Write(append(line, '\n')); err != nil {
			return n, err
		}
		n++
		if onEvent != nil {
			onEvent(ev.Type, ev.Object)
		}
	}
}

// Config configures a watch run.
type Config struct {
	Context        string
	OutDir         string
	Duration       time.Duration // 0: until the context is cancelled
	AgentNamespace string
	AgentSelector  string
	LeaseName      string
	Backoff        time.Duration // restart backoff; default 1s
	Logf           func(format string, a ...interface{})
}

// Watcher records every stream of a soak window.
type Watcher struct {
	cfg    Config
	runner Runner
	now    func() time.Time

	mu       sync.Mutex
	stats    map[string]*invariants.MetaInfo
	pods     map[string]bool // agent pod UIDs with a follower
	podAlive map[string]bool // agent pod UID -> not yet DELETED
	wg       sync.WaitGroup
}

// New returns a watcher. now may be nil (time.Now).
func New(cfg Config, r Runner, now func() time.Time) *Watcher {
	if now == nil {
		now = time.Now
	}
	if cfg.Backoff <= 0 {
		cfg.Backoff = time.Second
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...interface{}) {}
	}
	return &Watcher{cfg: cfg, runner: r, now: now, stats: map[string]*invariants.MetaInfo{},
		pods: map[string]bool{}, podAlive: map[string]bool{}}
}

// StreamArgs returns the kubectl `get` arguments (after the verb) of every watched
// resource stream.
func StreamArgs(agentNS, lease string) map[string][]string {
	w := []string{"--watch", "--output-watch-events", "-o", "json"}
	with := func(a ...string) []string { return append(a, w...) }
	return map[string][]string{
		invariants.StreamNodes:        with("nodes"),
		invariants.StreamPDBs:         with("poddisruptionbudgets", "-A"),
		invariants.StreamDeployments:  with("deployments", "-A"),
		invariants.StreamStatefulSets: with("statefulsets", "-A"),
		invariants.StreamReplicaSets:  with("replicasets", "-A"),
		invariants.StreamPods:         with("pods", "-A"),
		invariants.StreamEvents:       with("events", "-A"),
		invariants.StreamLeases:       with("leases", "-n", agentNS, "--field-selector", "metadata.name="+lease),
	}
}

func (w *Watcher) stat(stream string) *invariants.MetaInfo {
	w.mu.Lock()
	defer w.mu.Unlock()
	s := w.stats[stream]
	if s == nil {
		s = &invariants.MetaInfo{}
		w.stats[stream] = s
	}
	return s
}

func (w *Watcher) add(stream string, records, restarts int) {
	s := w.stat(stream)
	w.mu.Lock()
	s.Records += records
	s.Restarts += restarts
	w.mu.Unlock()
}

// Stats returns a copy of the per-stream counters.
func (w *Watcher) Stats() map[string]invariants.MetaInfo {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := map[string]invariants.MetaInfo{}
	for k, v := range w.stats {
		out[k] = *v
	}
	return out
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// Supervise runs `kubectl get <args>` for stream and restarts it whenever it exits,
// until ctx is done. Every restart is counted.
func (w *Watcher) Supervise(ctx context.Context, stream string, getArgs []string, out io.Writer, onEvent func(string, json.RawMessage)) {
	w.stat(stream)
	for first := true; ; first = false {
		if ctx.Err() != nil {
			return
		}
		if !first {
			w.add(stream, 0, 1)
			w.cfg.Logf("soakwatch: %s watch exited; restarting", stream)
		}
		args, err := Args(w.cfg.Context, "get", getArgs...)
		if err != nil {
			w.cfg.Logf("soakwatch: %s: %v", stream, err)
			return
		}
		rc, wait, err := w.runner.Start(ctx, args)
		if err != nil {
			w.cfg.Logf("soakwatch: %s: start: %v", stream, err)
		} else {
			n, serr := Stamp(rc, out, stream, w.now, onEvent)
			w.add(stream, n, 0)
			_ = rc.Close()
			if werr := wait(); werr != nil && ctx.Err() == nil {
				w.cfg.Logf("soakwatch: %s: kubectl exited: %v", stream, werr)
			}
			if serr != nil && ctx.Err() == nil {
				w.cfg.Logf("soakwatch: %s: decode: %v", stream, serr)
			}
		}
		if !sleepCtx(ctx, w.cfg.Backoff) {
			return
		}
	}
}

type podMeta struct {
	Metadata struct {
		Namespace string            `json:"namespace"`
		Name      string            `json:"name"`
		UID       string            `json:"uid"`
		Labels    map[string]string `json:"labels"`
	} `json:"metadata"`
}

// AgentPodWatcher returns the pods-stream callback that starts exactly one log
// follower per agent pod UID (pods in AgentNamespace matching AgentSelector).
func (w *Watcher) AgentPodWatcher(ctx context.Context, logs io.Writer) (func(string, json.RawMessage), error) {
	sel, err := labels.Parse(w.cfg.AgentSelector)
	if err != nil {
		return nil, fmt.Errorf("agent selector %q: %w", w.cfg.AgentSelector, err)
	}
	return func(typ string, obj json.RawMessage) {
		var p podMeta
		if json.Unmarshal(obj, &p) != nil || p.Metadata.Namespace != w.cfg.AgentNamespace ||
			!sel.Matches(labels.Set(p.Metadata.Labels)) {
			return
		}
		uid := p.Metadata.UID
		if uid == "" {
			uid = p.Metadata.Namespace + "/" + p.Metadata.Name
		}
		w.mu.Lock()
		defer w.mu.Unlock()
		if typ == "DELETED" {
			w.podAlive[uid] = false
			return
		}
		w.podAlive[uid] = true
		if w.pods[uid] {
			return
		}
		w.pods[uid] = true
		w.wg.Add(1)
		go func() {
			defer w.wg.Done()
			w.follow(ctx, p.Metadata.Namespace, p.Metadata.Name, uid, logs)
		}()
	}, nil
}

func (w *Watcher) alive(uid string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.podAlive[uid]
}

// follow streams one agent pod's logs; it restarts with --since-time while the pod
// exists and drops lines already written.
func (w *Watcher) follow(ctx context.Context, ns, name, uid string, out io.Writer) {
	stream := invariants.StreamAgentLogs
	w.stat(stream)
	var last time.Time
	seen := map[string]bool{} // ts|line of lines at or after the second of `last`
	for first := true; ; first = false {
		if ctx.Err() != nil {
			return
		}
		if !first {
			w.add(stream, 0, 1)
		}
		rest := []string{"-f", "--timestamps", "--all-containers=true", "-n", ns, "pod/" + name}
		if !last.IsZero() {
			rest = append(rest, "--since-time="+last.Truncate(time.Second).Format(time.RFC3339))
		}
		args, err := Args(w.cfg.Context, "logs", rest...)
		if err != nil {
			return
		}
		rc, wait, err := w.runner.Start(ctx, args)
		if err == nil {
			sc := bufio.NewScanner(rc)
			sc.Buffer(make([]byte, 0, 1<<16), 16<<20)
			for sc.Scan() {
				ts, line := splitTimestamp(sc.Text(), w.now)
				k := ts.Format(time.RFC3339Nano) + "|" + line
				if ts.Before(last.Truncate(time.Second)) || seen[k] {
					continue
				}
				if ts.Truncate(time.Second).After(last.Truncate(time.Second)) {
					seen = map[string]bool{}
				}
				seen[k] = true
				if ts.After(last) {
					last = ts
				}
				b, merr := json.Marshal(invariants.Record{TS: ts, Stream: stream, Type: "LOG", Pod: name, Line: line})
				if merr != nil {
					continue
				}
				if _, werr := out.Write(append(b, '\n')); werr == nil {
					w.add(stream, 1, 0)
				}
			}
			_ = rc.Close()
			_ = wait()
		}
		if !w.alive(uid) || !sleepCtx(ctx, w.cfg.Backoff) {
			return
		}
	}
}

// splitTimestamp parses the RFC3339Nano prefix `kubectl logs --timestamps` adds; a
// line without one is stamped with now().
func splitTimestamp(s string, now func() time.Time) (time.Time, string) {
	if i := strings.IndexByte(s, ' '); i > 0 {
		if ts, err := time.Parse(time.RFC3339Nano, s[:i]); err == nil {
			return ts.UTC(), s[i+1:]
		}
	}
	return now().UTC(), s
}

// Run preflights the context, records every stream into OutDir until Duration
// elapses or ctx is cancelled, then writes watch-meta.json.
func (w *Watcher) Run(ctx context.Context) error {
	server, err := Preflight(ctx, w.runner, w.cfg.Context)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(w.cfg.OutDir, 0o755); err != nil {
		return err
	}
	if w.cfg.Duration > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, w.cfg.Duration)
		defer cancel()
	}
	start := w.now().UTC()
	open := func(stream string) (*os.File, error) {
		return os.OpenFile(filepath.Join(w.cfg.OutDir, stream+".jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	}
	logFile, err := open(invariants.StreamAgentLogs)
	if err != nil {
		return err
	}
	defer logFile.Close()
	onPod, err := w.AgentPodWatcher(ctx, NewSyncWriter(logFile))
	if err != nil {
		return err
	}
	w.stat(invariants.StreamAgentLogs)
	specs := StreamArgs(w.cfg.AgentNamespace, w.cfg.LeaseName)
	names := make([]string, 0, len(specs))
	for s := range specs {
		names = append(names, s)
	}
	sort.Strings(names)
	for _, s := range names {
		f, err := open(s)
		if err != nil {
			return err
		}
		defer f.Close()
		var cb func(string, json.RawMessage)
		if s == invariants.StreamPods {
			cb = onPod
		}
		s, args, out := s, specs[s], NewSyncWriter(f)
		w.wg.Add(1)
		go func() {
			defer w.wg.Done()
			w.Supervise(ctx, s, args, out, cb)
		}()
	}
	w.cfg.Logf("soakwatch: recording context %s (%s) into %s", w.cfg.Context, server, w.cfg.OutDir)
	<-ctx.Done()
	w.wg.Wait()

	meta := invariants.Meta{Context: w.cfg.Context, Server: server, Start: start, End: w.now().UTC(), Streams: w.Stats()}
	b, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(w.cfg.OutDir, invariants.MetaFile), append(b, '\n'), 0o644)
}
