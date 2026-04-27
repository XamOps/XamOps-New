package collector

import (
	"bufio"
	"context"
	"encoding/json"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
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

func newLogWatcher(url, org, username, password, stream string, batchSize, interval int, k8s kubernetes.Interface) *logWatcher {
	if stream == "" {
		if k8s != nil {
			stream = "k8s-logs"
		} else {
			stream = "ec2-logs"
		}
	}
	if interval <= 0 {
		interval = 60
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

// runOnce collects one window of logs and ships them to OpenObserve.
// Called on a ticker from StartLogWatcher().
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
		"-n", "2000",
	)
	out, err := cmd.Output()
	if err != nil {
		log.Printf("log-watcher: journalctl failed — journald may not be present: %v", err)
		return
	}

	var records []map[string]interface{}
	scanner := bufio.NewScanner(strings.NewReader(string(out)))
	scanner.Buffer(make([]byte, 256*1024), 256*1024)
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

		if alert := detectLogAnomaly(rec, "EC2"); alert != nil {
			key := "EC2:LOG:" + alert.Reason + ":" + alert.ResourceID
			if !isDuplicateAlert(key) {
				select {
				case w.anomalyQueue <- *alert:
				default:
				}
			}
		}

		if len(records) >= w.batchSize {
			if err := w.shipper.ship(ctx, w.streamName, records); err != nil {
				log.Printf("log-watcher: ship ec2 logs: %v", err)
			}
			records = records[:0]
		}
	}

	if len(records) > 0 {
		if err := w.shipper.ship(ctx, w.streamName, records); err != nil {
			log.Printf("log-watcher: ship ec2 logs: %v", err)
		}
	}
}

// journaldRecordToOO converts a raw journald JSON entry to an OpenObserve record.
func journaldRecordToOO(jd map[string]interface{}) map[string]interface{} {
	rec := map[string]interface{}{}
	if ts, ok := jd["__REALTIME_TIMESTAMP"].(string); ok {
		if usec, err := strconv.ParseInt(ts, 10, 64); err == nil {
			rec["_timestamp"] = usec
		} else {
			rec["_timestamp"] = time.Now().UnixMicro()
		}
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

// collectK8sLogs fetches the last (interval+5)s of logs for every non-system running pod
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
	scanner.Buffer(make([]byte, 256*1024), 256*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		// K8s log lines with timestamps look like: "2024-01-15T10:00:00.000000000Z actual message"
		ts := time.Now().UnixMicro()
		msg := line
		if len(line) > 31 && line[29] == 'Z' {
			msg = strings.TrimSpace(line[31:])
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
			if err := w.shipper.ship(ctx, w.streamName, records); err != nil {
				log.Printf("log-watcher: ship k8s logs [%s/%s]: %v", ns, podName, err)
			}
			records = records[:0]
		}
	}

	if len(records) > 0 {
		if err := w.shipper.ship(ctx, w.streamName, records); err != nil {
			log.Printf("log-watcher: ship k8s logs [%s/%s]: %v", ns, podName, err)
		}
	}
}

// detectLogAnomaly checks a single log record for known anomaly patterns.
// resourceType is "EC2" or "K8S_POD". Returns nil if no anomaly found.
func detectLogAnomaly(rec map[string]interface{}, resourceType string) *SreAlertItem {
	msg, _ := rec["message"].(string)
	if msg == "" {
		return nil
	}
	msgLow := strings.ToLower(msg)

	host, _ := rec["host"].(string)
	if host == "" {
		host = "unknown-host"
	}
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

	if strings.Contains(msgLow, "out of memory") || strings.Contains(msgLow, "killed process") ||
		(strings.Contains(msgLow, "oom") && strings.Contains(msgLow, "kill")) {
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

	// CrashLoopBackOff messages from K8s control-plane are always exactly this spelling.
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

