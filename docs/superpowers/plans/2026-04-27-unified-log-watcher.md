# Unified Log Watcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend xamops-agent to collect logs from journald (EC2) and K8s pod logs, ship them directly to OpenObserve, and detect anomalies inline — replacing Fluent Bit with a single unified agent.

**Architecture:** A new `LogWatcher` runs on a 60-second ticker alongside the existing batch collection loop. On EC2 it reads the last 65 seconds of journald logs; on K8s it fetches the last 65 seconds of pod logs for every non-system pod. Log records are batched and POSTed to OpenObserve's `/_json` ingest endpoint. Anomalies detected inline are queued in a thread-safe channel; the existing `CollectAll()` drains that channel into an `sre-alerts` batch as before.

**Tech Stack:** Go 1.21, `k8s.io/client-go` (already in go.mod), OpenObserve HTTP ingest API (JSON over HTTP, no new dependencies), `os/exec` + `bufio` for journald (already in project).

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `xamops-agent/internal/config/config.go` | Modify | Add OpenObserve + log watcher config fields |
| `xamops-agent/internal/collector/openobserve_shipper.go` | Create | HTTP client for OpenObserve ingest API |
| `xamops-agent/internal/collector/log_watcher.go` | Create | EC2 journald + K8s pod log collection, anomaly detection, anomaly queue |
| `xamops-agent/internal/collector/manager.go` | Modify | Hold `LogWatcher`, drain anomaly queue in `CollectAll()`, expose `StartLogWatcher()` |
| `xamops-agent/cmd/agent/main.go` | Modify | Call `StartLogWatcher(ctx)` after creating manager |

---

### Task 1: Config fields

**Files:**
- Modify: `xamops-agent/internal/config/config.go`

- [ ] **Step 1: Add fields to the `Config` struct**

In `config.go`, inside the `Config` struct after `CollectorSreWatcher`, add:

```go
CollectorLogWatcher  bool   // stream logs to OpenObserve + inline anomaly detection (opt-in)
LogWatcherInterval   int    // seconds between log collection runs (default 60)
LogWatcherBatchSize  int    // max log records per OpenObserve ingest call (default 500)

// OpenObserve log shipping
OpenObserveURL      string // e.g. http://openobserve:5080
OpenObserveOrg      string // e.g. default
OpenObserveStream   string // stream name (default: k8s-logs or ec2-logs based on mode)
OpenObserveUsername string // basic auth username (usually email)
OpenObservePassword string // basic auth password or API token
```

- [ ] **Step 2: Wire fields in `Load()`**

In the `cfg := &Config{...}` literal in `Load()`, add after the `CollectorSreWatcher` line:

```go
CollectorLogWatcher: getEnvBool("COLLECTOR_LOG_WATCHER", false),  // opt-in
LogWatcherInterval:  getEnvInt("LOG_WATCHER_INTERVAL", 60),
LogWatcherBatchSize: getEnvInt("LOG_WATCHER_BATCH_SIZE", 500),

OpenObserveURL:      getEnv("OPENOBSERVE_URL", "http://openobserve:5080"),
OpenObserveOrg:      getEnv("OPENOBSERVE_ORG", "default"),
OpenObserveStream:   getEnv("OPENOBSERVE_STREAM", ""),
OpenObserveUsername: getEnv("OPENOBSERVE_USERNAME", "root@example.com"),
OpenObservePassword: getEnv("OPENOBSERVE_PASSWORD", ""),
```

- [ ] **Step 3: Build to verify no compile errors**

```bash
cd xamops-agent && go build ./...
```

Expected: no output (success).

- [ ] **Step 4: Commit**

```bash
git add xamops-agent/internal/config/config.go
git commit -m "feat(agent): add OpenObserve + log watcher config fields"
```

---

### Task 2: OpenObserve shipper

**Files:**
- Create: `xamops-agent/internal/collector/openobserve_shipper.go`

- [ ] **Step 1: Create the file**

```go
package collector

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// openObserveShipper sends log record batches to OpenObserve's ingest API.
// Endpoint: POST {baseURL}/api/{org}/{stream}/_json
// Auth: Basic base64(username:password)
type openObserveShipper struct {
	baseURL    string
	org        string
	authHeader string
	httpClient *http.Client
}

func newOpenObserveShipper(baseURL, org, username, password string) *openObserveShipper {
	creds := base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
	return &openObserveShipper{
		baseURL:    baseURL,
		org:        org,
		authHeader: "Basic " + creds,
		httpClient: &http.Client{Timeout: 15 * time.Second},
	}
}

// ship POSTs records to the given stream in OpenObserve.
// records must be a slice of map[string]interface{} where _timestamp is microseconds.
func (s *openObserveShipper) ship(stream string, records []map[string]interface{}) error {
	if len(records) == 0 {
		return nil
	}
	body, err := json.Marshal(records)
	if err != nil {
		return fmt.Errorf("openobserve: marshal: %w", err)
	}
	url := s.baseURL + "/api/" + s.org + "/" + stream + "/_json"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("openobserve: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", s.authHeader)

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("openobserve: http: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("openobserve: ingest returned HTTP %d", resp.StatusCode)
	}
	return nil
}
```

- [ ] **Step 2: Build to verify**

```bash
cd xamops-agent && go build ./...
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add xamops-agent/internal/collector/openobserve_shipper.go
git commit -m "feat(agent): add OpenObserve HTTP ingest shipper"
```

---

### Task 3: Log watcher — EC2 journald mode

**Files:**
- Create: `xamops-agent/internal/collector/log_watcher.go`

This task creates the core log watcher with EC2 (journald) support. K8s support is added in Task 4.

- [ ] **Step 1: Create `log_watcher.go`**

```go
package collector

import (
	"bufio"
	"context"
	"encoding/json"
	"log"
	"os/exec"
	"strings"
	"time"

	"k8s.io/client-go/kubernetes"
)

// logWatcher collects logs from journald (EC2) or K8s pod logs,
// ships them to OpenObserve, and queues anomalies for SRE investigation.
type logWatcher struct {
	shipper      *openObserveShipper
	streamName   string
	batchSize    int
	interval     int // seconds
	k8sClient    kubernetes.Interface
	anomalyQueue chan SreAlertItem // drained by CollectAll into sre-alerts batch
}

func newLogWatcher(cfg interface{ getLogWatcherConfig() logWatcherConfig }, k8s kubernetes.Interface) *logWatcher {
	c := cfg.getLogWatcherConfig()
	stream := c.stream
	if stream == "" {
		if k8s != nil {
			stream = "k8s-logs"
		} else {
			stream = "ec2-logs"
		}
	}
	return &logWatcher{
		shipper:      newOpenObserveShipper(c.url, c.org, c.username, c.password),
		streamName:   stream,
		batchSize:    c.batchSize,
		interval:     c.interval,
		k8sClient:    k8s,
		anomalyQueue: make(chan SreAlertItem, 256),
	}
}

type logWatcherConfig struct {
	url, org, username, password, stream string
	batchSize, interval                  int
}
```

- [ ] **Step 2: Add EC2 journald collection method**

Append to `log_watcher.go`:

```go
// runOnce collects one window of logs and ships them to OpenObserve.
// Called on a ticker from Start().
func (w *logWatcher) runOnce(ctx context.Context) {
	if w.k8sClient != nil {
		w.collectK8sLogs(ctx)
	} else {
		w.collectEC2Logs(ctx)
	}
}

// collectEC2Logs reads the last (interval+5)s of journald logs as JSON records.
func (w *logWatcher) collectEC2Logs(ctx context.Context) {
	since := itoa(w.interval+5) + " seconds ago"
	cmd := exec.CommandContext(ctx, "journalctl",
		"--since", since,
		"-o", "json",
		"--no-pager",
		"-n", "2000", // safety cap per run
	)
	out, err := cmd.Output()
	if err != nil {
		// journald may not be present — not fatal
		return
	}

	var records []map[string]interface{}
	scanner := bufio.NewScanner(strings.NewReader(string(out)))
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		var jd map[string]interface{}
		if err := json.Unmarshal([]byte(line), &jd); err != nil {
			continue
		}
		rec := journaldRecordToOO(jd)
		records = append(records, rec)

		// Inline anomaly detection
		if alert := detectLogAnomaly(rec, "EC2"); alert != nil {
			key := "EC2:LOG:" + alert.Reason + ":" + alert.ResourceID
			if !isDuplicateAlert(key) {
				select {
				case w.anomalyQueue <- *alert:
				default: // queue full — drop
				}
			}
		}

		if len(records) >= w.batchSize {
			if err := w.shipper.ship(w.streamName, records); err != nil {
				log.Printf("log-watcher: ship ec2 logs: %v", err)
			}
			records = records[:0]
		}
	}

	if len(records) > 0 {
		if err := w.shipper.ship(w.streamName, records); err != nil {
			log.Printf("log-watcher: ship ec2 logs: %v", err)
		}
	}
}

// journaldRecordToOO converts a raw journald JSON entry to an OpenObserve record.
func journaldRecordToOO(jd map[string]interface{}) map[string]interface{} {
	rec := map[string]interface{}{}
	// _REALTIME_TIMESTAMP is microseconds since epoch as a string
	if ts, ok := jd["__REALTIME_TIMESTAMP"].(string); ok {
		rec["_timestamp"] = ts // OpenObserve accepts string microseconds
	} else {
		rec["_timestamp"] = time.Now().UnixMicro()
	}
	if msg, ok := jd["MESSAGE"].(string); ok {
		rec["message"] = msg
	}
	if host, ok := jd["_HOSTNAME"].(string); ok {
		rec["host"] = host
	}
	if unit, ok := jd["_SYSTEMD_UNIT"].(string); ok {
		rec["unit"] = unit
	}
	if ident, ok := jd["SYSLOG_IDENTIFIER"].(string); ok {
		rec["source"] = ident
	}
	if pri, ok := jd["PRIORITY"].(string); ok {
		rec["priority"] = pri
	}
	return rec
}
```

- [ ] **Step 3: Add anomaly detection on log lines**

Append to `log_watcher.go`:

```go
// detectLogAnomaly checks a single log record for known anomaly patterns.
// resourceType is "EC2" or "K8S_POD". Returns nil if no anomaly found.
func detectLogAnomaly(rec map[string]interface{}, resourceType string) *SreAlertItem {
	msg, _ := rec["message"].(string)
	if msg == "" {
		return nil
	}
	msgLow := strings.ToLower(msg)

	host, _ := rec["host"].(string)
	pod, _ := rec["pod"].(string)
	ns, _ := rec["namespace"].(string)

	resourceID := host
	resourceName := host
	if resourceType == "K8S_POD" {
		resourceID = pod
		if ns != "" {
			resourceName = pod + " (" + ns + ")"
		} else {
			resourceName = pod
		}
	}

	if resourceID == "" {
		return nil
	}

	// OOM
	if strings.Contains(msgLow, "out of memory") || strings.Contains(msgLow, "killed process") ||
		strings.Contains(msgLow, "oom") && strings.Contains(msgLow, "kill") {
		return &SreAlertItem{
			ResourceType: resourceType,
			ResourceID:   resourceID,
			ResourceName: resourceName,
			Namespace:    ns,
			Reason:       "OOMKilled",
			Severity:     "critical",
			Message:      "OOM detected in logs: " + truncate(msg, 200),
		}
	}

	// CrashLoopBackOff
	if strings.Contains(msg, "CrashLoopBackOff") || strings.Contains(msg, "Back-off restarting failed container") {
		return &SreAlertItem{
			ResourceType: resourceType,
			ResourceID:   resourceID,
			ResourceName: resourceName,
			Namespace:    ns,
			Reason:       "CrashLoopBackOff",
			Severity:     "critical",
			Message:      "CrashLoop detected in logs: " + truncate(msg, 200),
		}
	}

	// Kernel panic
	if strings.Contains(msgLow, "kernel panic") || strings.Contains(msgLow, "oops:") {
		return &SreAlertItem{
			ResourceType: resourceType,
			ResourceID:   resourceID,
			ResourceName: resourceName,
			Namespace:    ns,
			Reason:       "KernelPanic",
			Severity:     "critical",
			Message:      "Kernel panic detected: " + truncate(msg, 200),
		}
	}

	return nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
```

- [ ] **Step 4: Build to verify**

```bash
cd xamops-agent && go build ./...
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add xamops-agent/internal/collector/log_watcher.go
git commit -m "feat(agent): add EC2 journald log watcher with OpenObserve shipping and inline anomaly detection"
```

---

### Task 4: Log watcher — K8s pod logs mode

**Files:**
- Modify: `xamops-agent/internal/collector/log_watcher.go`

- [ ] **Step 1: Add K8s imports**

At the top of `log_watcher.go`, update the import block to include:

```go
import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log"
	"os/exec"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)
```

- [ ] **Step 2: Add `collectK8sLogs` method**

Append to `log_watcher.go`:

```go
// collectK8sLogs fetches the last (interval+5)s of logs for every non-system pod
// and ships them to OpenObserve. Anomalies are queued for SRE investigation.
func (w *logWatcher) collectK8sLogs(ctx context.Context) {
	pods, err := w.k8sClient.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("log-watcher: list pods: %v", err)
		return
	}

	sinceSeconds := int64(w.interval + 5)

	for _, pod := range pods.Items {
		ns := pod.Namespace
		if ns == "kube-system" || ns == "kube-public" || ns == "kube-node-lease" {
			continue
		}
		if pod.Status.Phase != corev1.PodRunning {
			continue
		}

		for _, c := range pod.Spec.Containers {
			w.collectContainerLogs(ctx, pod.Name, ns, c.Name, sinceSeconds)
		}
	}
}

func (w *logWatcher) collectContainerLogs(ctx context.Context, podName, ns, container string, sinceSeconds int64) {
	req := w.k8sClient.CoreV1().Pods(ns).GetLogs(podName, &corev1.PodLogOptions{
		Container:    container,
		SinceSeconds: &sinceSeconds,
		Timestamps:   true,
	})
	stream, err := req.Stream(ctx)
	if err != nil {
		return // pod may have restarted — not fatal
	}
	defer stream.Close()

	var records []map[string]interface{}
	scanner := bufio.NewScanner(stream)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		// K8s log line with timestamp prefix: "2024-01-15T10:00:00.000000000Z actual message"
		ts := time.Now().UnixMicro()
		msg := line
		if len(line) > 31 && line[29] == 'Z' {
			msg = line[31:]
		}

		rec := map[string]interface{}{
			"_timestamp": ts,
			"message":    msg,
			"pod":        podName,
			"namespace":  ns,
			"container":  container,
			"host":       podName,
		}
		records = append(records, rec)

		if alert := detectLogAnomaly(rec, "K8S_POD"); alert != nil {
			key := "K8S_POD:LOG:" + alert.Reason + ":" + podName
			if !isDuplicateAlert(key) {
				select {
				case w.anomalyQueue <- *alert:
				default:
				}
			}
		}

		if len(records) >= w.batchSize {
			if err := w.shipper.ship(w.streamName, records); err != nil {
				log.Printf("log-watcher: ship k8s logs [%s/%s]: %v", ns, podName, err)
			}
			records = records[:0]
		}
	}

	// ignore scanner.Err() — EOF is normal here
	_ = io.EOF

	if len(records) > 0 {
		if err := w.shipper.ship(w.streamName, records); err != nil {
			log.Printf("log-watcher: ship k8s logs [%s/%s]: %v", ns, podName, err)
		}
	}
}
```

- [ ] **Step 3: Build to verify**

```bash
cd xamops-agent && go build ./...
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add xamops-agent/internal/collector/log_watcher.go
git commit -m "feat(agent): add K8s pod log collection to log watcher"
```

---

### Task 5: Wire log watcher into Manager and main.go

**Files:**
- Modify: `xamops-agent/internal/collector/manager.go`
- Modify: `xamops-agent/cmd/agent/main.go`

- [ ] **Step 1: Add `logWatcherConfig` getter on Config**

In `xamops-agent/internal/config/config.go`, add this method at the bottom of the file:

```go
// getLogWatcherConfig returns log watcher fields as a struct.
// The collector package cannot import config directly without a cycle,
// so it calls this via an interface — but since Manager already has *Config,
// we add a small adapter method here and pass the values directly.
func (c *Config) LogWatcherSettings() (url, org, username, password, stream string, batchSize, interval int) {
	return c.OpenObserveURL, c.OpenObserveOrg, c.OpenObserveUsername, c.OpenObservePassword,
		c.OpenObserveStream, c.LogWatcherBatchSize, c.LogWatcherInterval
}
```

- [ ] **Step 2: Update `logWatcher` constructor to not use interface**

In `log_watcher.go`, replace the `newLogWatcher` function signature to accept plain values (not an interface), keeping it simple:

```go
func newLogWatcher(url, org, username, password, stream string, batchSize, interval int, k8s kubernetes.Interface) *logWatcher {
	if stream == "" {
		if k8s != nil {
			stream = "k8s-logs"
		} else {
			stream = "ec2-logs"
		}
	}
	return &logWatcher{
		shipper:      newOpenObserveShipper(url, org, username, password),
		streamName:   stream,
		batchSize:    batchSize,
		interval:     interval,
		k8sClient:    k8s,
		anomalyQueue: make(chan SreAlertItem, 256),
	}
}
```

Also remove the `logWatcherConfig` struct and the old `newLogWatcher` with the interface — they are replaced by this.

- [ ] **Step 3: Add `logWatcher` field to Manager and initialize it**

In `manager.go`, add `logWatcher *logWatcher` to the `Manager` struct:

```go
type Manager struct {
	cfg           *config.Config
	k8sClient     kubernetes.Interface
	dynamicClient dynamic.Interface
	metricsClient metricsv.Interface
	httpClient    *http.Client
	logWatcher    *logWatcher
}
```

At the end of `NewCollectorManager`, before `return m`, add:

```go
if cfg.CollectorLogWatcher && cfg.OpenObservePassword != "" {
	url, org, username, password, stream, batchSize, interval := cfg.LogWatcherSettings()
	m.logWatcher = newLogWatcher(url, org, username, password, stream, batchSize, interval, m.k8sClient)
}
```

- [ ] **Step 4: Add `StartLogWatcher` method to Manager**

Append to `manager.go`:

```go
// StartLogWatcher launches the log watcher goroutine. Call once from main.go.
// No-op if log watcher is disabled or OpenObserve password is not configured.
func (m *Manager) StartLogWatcher(ctx context.Context) {
	if m.logWatcher == nil {
		return
	}
	log.Printf("log-watcher: starting (interval=%ds, stream=%s)", m.logWatcher.interval, m.logWatcher.streamName)
	go func() {
		// Run immediately on start
		m.logWatcher.runOnce(ctx)
		ticker := time.NewTicker(time.Duration(m.logWatcher.interval) * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				log.Println("log-watcher: stopped")
				return
			case <-ticker.C:
				m.logWatcher.runOnce(ctx)
			}
		}
	}()
}
```

- [ ] **Step 5: Drain anomaly queue in `CollectAll()`**

In `manager.go`, inside `CollectAll()`, after the existing SRE watcher block (around line 258), add:

```go
// ── 9. Drain log-watcher anomaly queue into sre-alerts batch ──────────────
if m.logWatcher != nil {
	var logAlerts []SreAlertItem
drain:
	for {
		select {
		case a := <-m.logWatcher.anomalyQueue:
			logAlerts = append(logAlerts, a)
		default:
			break drain
		}
	}
	if len(logAlerts) > 0 {
		items := make([]interface{}, len(logAlerts))
		for i, a := range logAlerts {
			items[i] = a
		}
		payload.Batches = append(payload.Batches, DataBatch{
			Collector:   "sre-alerts",
			Items:       items,
			Count:       len(items),
			CollectedAt: time.Now(),
		})
		log.Printf("log-watcher: drained %d anomalies into sre-alerts batch", len(logAlerts))
	}
}
```

- [ ] **Step 6: Call `StartLogWatcher` from main.go**

In `xamops-agent/cmd/agent/main.go`, in `runCollectionLoop` (or directly in `main()`), after creating `collectors`, add:

```go
// Start log watcher background goroutine (no-op if disabled)
collectors.StartLogWatcher(ctx)
```

The full updated section in `main()`:

```go
collectors := collector.NewCollectorManager(cfg)
collectors.StartLogWatcher(ctx)

dataPusher := pusher.NewPusher(cfg)
```

- [ ] **Step 7: Build final**

```bash
cd xamops-agent && go build ./...
```

Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add xamops-agent/internal/collector/manager.go \
        xamops-agent/internal/config/config.go \
        xamops-agent/cmd/agent/main.go \
        xamops-agent/internal/collector/log_watcher.go
git commit -m "feat(agent): wire log watcher into manager and main — unified log collection + SRE anomaly detection"
```

---

## Deployment Config

To enable in a Kubernetes DaemonSet or EC2 install script, add these env vars:

```yaml
- name: COLLECTOR_LOG_WATCHER
  value: "true"
- name: OPENOBSERVE_URL
  value: "http://openobserve:5080"        # or ngrok URL for local dev
- name: OPENOBSERVE_ORG
  value: "default"
- name: OPENOBSERVE_USERNAME
  value: "root@example.com"
- name: OPENOBSERVE_PASSWORD
  value: "<your-openobserve-password>"
- name: OPENOBSERVE_STREAM
  value: "k8s-logs"                       # or "ec2-logs" for EC2 agents
- name: LOG_WATCHER_INTERVAL
  value: "60"
```

Once deployed, **remove Fluent Bit / OTEL collector** from the host — xamops-agent now ships logs directly.

---

## Self-Review

**Spec coverage:**
- ✅ EC2 journald log collection → Task 3
- ✅ K8s pod log collection → Task 4
- ✅ Ship logs to OpenObserve → Task 2 + Task 3 + Task 4
- ✅ Inline anomaly detection → Task 3 (`detectLogAnomaly`)
- ✅ Anomalies flow to SRE investigation → Task 5 (anomaly queue drain)
- ✅ Single agent replaces Fluent Bit → Task 5 (deployment config)
- ✅ Config flags (opt-in) → Task 1

**Placeholder scan:** None found. All code blocks are complete.

**Type consistency:**
- `SreAlertItem` defined in `sre_watcher.go` — used identically in `log_watcher.go` ✅
- `isDuplicateAlert(key string) bool` defined in `sre_watcher.go` — reused in `log_watcher.go` ✅
- `newOpenObserveShipper` defined in Task 2, used in Task 3 and Task 5 ✅
- `logWatcher.runOnce()` called in `StartLogWatcher` — defined in Task 3 ✅
