package invariants

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// Stream names; the file of stream s is s + ".jsonl".
const (
	StreamNodes        = "nodes"
	StreamPDBs         = "pdbs"
	StreamDeployments  = "deployments"
	StreamStatefulSets = "statefulsets"
	StreamReplicaSets  = "replicasets"
	StreamPods         = "pods"
	StreamEvents       = "events"
	StreamLeases       = "leases"
	StreamAgentLogs    = "agent-logs"

	// MetaFile is written by the watcher on shutdown.
	MetaFile = "watch-meta.json"
)

// Streams lists every stream in file order (ties in ts keep this order).
var Streams = []string{
	StreamNodes, StreamPDBs, StreamDeployments, StreamStatefulSets, StreamReplicaSets,
	StreamPods, StreamEvents, StreamLeases, StreamAgentLogs,
}

// Record is one line of a stream file.
type Record struct {
	TS     time.Time       `json:"ts"`
	Stream string          `json:"stream"`
	Type   string          `json:"type"`
	Object json.RawMessage `json:"object,omitempty"`
	Pod    string          `json:"pod,omitempty"`
	Line   string          `json:"line,omitempty"`
}

// StreamCoverage describes one stream of a run.
type StreamCoverage struct {
	Stream   string     `json:"stream"`
	Present  bool       `json:"present"`
	Records  int        `json:"records"`
	First    *time.Time `json:"first,omitempty"`
	Last     *time.Time `json:"last,omitempty"`
	Restarts *int       `json:"restarts,omitempty"`
}

// Meta is the watcher's watch-meta.json.
type Meta struct {
	Context string              `json:"context"`
	Server  string              `json:"server,omitempty"`
	Start   time.Time           `json:"start"`
	End     time.Time           `json:"end"`
	Streams map[string]MetaInfo `json:"streams"`
}

// MetaInfo is the per-stream part of Meta.
type MetaInfo struct {
	Restarts int `json:"restarts"`
	Records  int `json:"records"`
}

// Timeline is every record of a run merged in ts order.
type Timeline struct {
	Records  []Record
	Coverage map[string]*StreamCoverage
}

// Has reports whether stream s is present with at least one record.
func (t *Timeline) Has(s string) bool {
	c := t.Coverage[s]
	return c != nil && c.Records > 0
}

// Load reads every known stream file in dir and merges them into one timeline
// sorted stably by ts (file order, then line order, for ties).
func Load(dir string) (*Timeline, error) {
	st, err := os.Stat(dir)
	if err != nil {
		return nil, err
	}
	if !st.IsDir() {
		return nil, fmt.Errorf("%s is not a directory", dir)
	}
	tl := &Timeline{Coverage: map[string]*StreamCoverage{}}
	var meta *Meta
	if raw, err := os.ReadFile(filepath.Join(dir, MetaFile)); err == nil {
		m := &Meta{}
		if err := json.Unmarshal(raw, m); err != nil {
			return nil, fmt.Errorf("%s: %w", MetaFile, err)
		}
		meta = m
	}
	for _, s := range Streams {
		cov := &StreamCoverage{Stream: s}
		tl.Coverage[s] = cov
		if meta != nil {
			if mi, ok := meta.Streams[s]; ok {
				r := mi.Restarts
				cov.Restarts = &r
			}
		}
		recs, err := readStream(filepath.Join(dir, s+".jsonl"), s)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, err
		}
		cov.Present = true
		cov.Records = len(recs)
		for i := range recs {
			ts := recs[i].TS
			if cov.First == nil || ts.Before(*cov.First) {
				cov.First = &ts
			}
			if cov.Last == nil || ts.After(*cov.Last) {
				cov.Last = &ts
			}
		}
		tl.Records = append(tl.Records, recs...)
	}
	sort.SliceStable(tl.Records, func(i, j int) bool { return tl.Records[i].TS.Before(tl.Records[j].TS) })
	return tl, nil
}

func readStream(path, stream string) ([]Record, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []Record
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 64<<20)
	line := 0
	for sc.Scan() {
		line++
		b := sc.Bytes()
		if len(b) == 0 {
			continue
		}
		var r Record
		if err := json.Unmarshal(b, &r); err != nil {
			return nil, fmt.Errorf("%s:%d: %w", path, line, err)
		}
		if r.TS.IsZero() {
			return nil, fmt.Errorf("%s:%d: record without ts", path, line)
		}
		r.Stream = stream // the file decides how a record is evaluated
		out = append(out, r)
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return out, nil
}
