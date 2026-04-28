package collector

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

// SreAlertItem is a single anomaly the backend should investigate.
type SreAlertItem struct {
	ResourceType string `json:"resourceType"` // K8S_POD | EC2
	ResourceID   string `json:"resourceId"`
	ResourceName string `json:"resourceName"`
	Namespace    string `json:"namespace,omitempty"`
	Reason       string `json:"reason"`  // CrashLoopBackOff | OOMKilled | HighRestarts
	Severity     string `json:"severity"` // critical | warning
	Message      string `json:"message"`
}

// alertCooldown prevents the same resource from being re-alerted within a window.
var alertCooldown = struct {
	sync.Mutex
	seen map[string]time.Time
}{seen: map[string]time.Time{}}

const cooldownDuration = 30 * time.Minute
const crashLoopRestartThreshold = 5

func isDuplicateAlert(key string) bool {
	alertCooldown.Lock()
	defer alertCooldown.Unlock()
	if last, ok := alertCooldown.seen[key]; ok && time.Since(last) < cooldownDuration {
		return true
	}
	alertCooldown.seen[key] = time.Now()
	return false
}

// collectSreAlerts scans all pods for anomalies and returns a sre-alerts batch.
func collectSreAlerts(ctx context.Context, k8s kubernetes.Interface) (DataBatch, error) {
	pods, err := k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, err
	}

	var alerts []SreAlertItem
	for _, pod := range pods.Items {
		if a := checkPod(pod); a != nil {
			key := a.ResourceType + ":" + a.ResourceID
			if !isDuplicateAlert(key) {
				alerts = append(alerts, *a)
			}
		}
	}

	if len(alerts) == 0 {
		log.Printf("sre-watcher: no anomalies detected across %d pods", len(pods.Items))
	} else {
		log.Printf("sre-watcher: detected %d anomalies", len(alerts))
	}

	items := make([]interface{}, len(alerts))
	for i, a := range alerts {
		items[i] = a
	}
	return DataBatch{
		Collector:   "sre-alerts",
		Items:       items,
		Count:       len(items),
		CollectedAt: time.Now(),
	}, nil
}

// checkPod returns an SreAlertItem if the pod has a detectable anomaly, nil otherwise.
func checkPod(pod corev1.Pod) *SreAlertItem {
	// Skip system namespaces to reduce noise
	ns := pod.Namespace
	if ns == "kube-system" || ns == "kube-public" || ns == "kube-node-lease" {
		return nil
	}

	for _, cs := range pod.Status.ContainerStatuses {
		// CrashLoopBackOff
		if cs.State.Waiting != nil && cs.State.Waiting.Reason == "CrashLoopBackOff" {
			return &SreAlertItem{
				ResourceType: "K8S_POD",
				ResourceID:   pod.Name,
				ResourceName: pod.Name + " (" + ns + ")",
				Namespace:    ns,
				Reason:       "CrashLoopBackOff",
				Severity:     "critical",
				Message:      "Container " + cs.Name + " is in CrashLoopBackOff (restarts: " + itoa(int(cs.RestartCount)) + ")",
			}
		}

		// OOMKilled in last terminated state
		if cs.LastTerminationState.Terminated != nil && cs.LastTerminationState.Terminated.Reason == "OOMKilled" {
			return &SreAlertItem{
				ResourceType: "K8S_POD",
				ResourceID:   pod.Name,
				ResourceName: pod.Name + " (" + ns + ")",
				Namespace:    ns,
				Reason:       "OOMKilled",
				Severity:     "critical",
				Message:      "Container " + cs.Name + " was OOMKilled",
			}
		}

		// High restart count
		if cs.RestartCount >= crashLoopRestartThreshold {
			return &SreAlertItem{
				ResourceType: "K8S_POD",
				ResourceID:   pod.Name,
				ResourceName: pod.Name + " (" + ns + ")",
				Namespace:    ns,
				Reason:       "HighRestarts",
				Severity:     "warning",
				Message:      "Container " + cs.Name + " has restarted " + itoa(int(cs.RestartCount)) + " times",
			}
		}
	}

	// Init container stuck
	for _, cs := range pod.Status.InitContainerStatuses {
		if cs.State.Waiting != nil && cs.State.Waiting.Reason == "CrashLoopBackOff" {
			return &SreAlertItem{
				ResourceType: "K8S_POD",
				ResourceID:   pod.Name,
				ResourceName: pod.Name + " (" + ns + ")",
				Namespace:    ns,
				Reason:       "CrashLoopBackOff",
				Severity:     "critical",
				Message:      "Init container " + cs.Name + " is in CrashLoopBackOff",
			}
		}
	}

	return nil
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	result := ""
	for n > 0 {
		result = string(rune('0'+n%10)) + result
		n /= 10
	}
	return result
}

// collectSreAlertsEC2 detects anomalies on the current EC2 instance (OOM kills,
// high disk usage, failed systemd services) and returns a sre-alerts batch.
func collectSreAlertsEC2(ctx context.Context) (DataBatch, error) {
	httpClient := &http.Client{Timeout: 5 * time.Second}
	instanceID, err := imdsInstanceIDWithRetry(ctx, httpClient)
	if err != nil {
		return DataBatch{}, fmt.Errorf("ec2-sre: IMDS unavailable: %w", err)
	}

	hostname, _ := os.Hostname()
	resourceName := instanceID
	if hostname != "" {
		resourceName = instanceID + " (" + hostname + ")"
	}

	var alerts []SreAlertItem

	// OOM kills: scan dmesg for "Out of memory: Kill process" in the last 10 minutes
	if a := checkEC2OOM(instanceID, resourceName); a != nil {
		key := "EC2:OOM:" + instanceID
		if !isDuplicateAlert(key) {
			alerts = append(alerts, *a)
		}
	}

	// High disk usage (> 85%)
	if a := checkEC2DiskUsage(instanceID, resourceName); a != nil {
		key := "EC2:DISK:" + instanceID
		if !isDuplicateAlert(key) {
			alerts = append(alerts, *a)
		}
	}

	// Failed systemd services
	if a := checkEC2FailedServices(instanceID, resourceName); a != nil {
		key := "EC2:SERVICE:" + instanceID
		if !isDuplicateAlert(key) {
			alerts = append(alerts, *a)
		}
	}

	if len(alerts) == 0 {
		log.Printf("sre-watcher: no EC2 anomalies detected on %s", instanceID)
	} else {
		log.Printf("sre-watcher: detected %d EC2 anomalies on %s", len(alerts), instanceID)
	}

	items := make([]interface{}, len(alerts))
	for i, a := range alerts {
		items[i] = a
	}
	return DataBatch{
		Collector:   "sre-alerts",
		Items:       items,
		Count:       len(items),
		CollectedAt: time.Now(),
	}, nil
}

func checkEC2OOM(instanceID, resourceName string) *SreAlertItem {
	// Try dmesg first (doesn't require root on most modern kernels)
	out, err := exec.Command("dmesg", "--time-format=reltime", "--level=err,crit,alert,emerg").Output()
	if err != nil {
		// Fall back to /var/log/syslog or /var/log/messages
		for _, path := range []string{"/var/log/syslog", "/var/log/messages"} {
			f, err := os.Open(path)
			if err != nil {
				continue
			}
			defer f.Close()
			scanner := bufio.NewScanner(f)
			var lines []string
			for scanner.Scan() {
				lines = append(lines, scanner.Text())
			}
			// Only look at last 500 lines to keep it cheap
			start := 0
			if len(lines) > 500 {
				start = len(lines) - 500
			}
			for _, line := range lines[start:] {
				if strings.Contains(line, "Out of memory") || strings.Contains(line, "OOM") {
					return &SreAlertItem{
						ResourceType: "EC2",
						ResourceID:   instanceID,
						ResourceName: resourceName,
						Reason:       "OOMKilled",
						Severity:     "critical",
						Message:      "OOM kill detected on host: " + line,
					}
				}
			}
			return nil
		}
		return nil
	}

	// Scan dmesg output
	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := scanner.Text()
		if strings.Contains(line, "Out of memory") || strings.Contains(line, "Killed process") {
			return &SreAlertItem{
				ResourceType: "EC2",
				ResourceID:   instanceID,
				ResourceName: resourceName,
				Reason:       "OOMKilled",
				Severity:     "critical",
				Message:      "OOM kill detected via dmesg: " + strings.TrimSpace(line),
			}
		}
	}
	return nil
}

func checkEC2DiskUsage(instanceID, resourceName string) *SreAlertItem {
	f, err := os.Open("/proc/mounts")
	if err != nil {
		return nil
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		mountpoint := fields[1]
		// Skip pseudo-filesystems
		if strings.HasPrefix(mountpoint, "/sys") || strings.HasPrefix(mountpoint, "/proc") ||
			strings.HasPrefix(mountpoint, "/dev") || mountpoint == "/run" {
			continue
		}

		var stat syscall.Statfs_t
		if err := syscall.Statfs(mountpoint, &stat); err != nil {
			continue
		}
		if stat.Blocks == 0 {
			continue
		}
		usedPct := 100 * (stat.Blocks - stat.Bfree) / stat.Blocks
		if usedPct >= 85 {
			return &SreAlertItem{
				ResourceType: "EC2",
				ResourceID:   instanceID,
				ResourceName: resourceName,
				Reason:       "HighDiskUsage",
				Severity:     "warning",
				Message:      "Disk usage at " + itoa(int(usedPct)) + "% on mount " + mountpoint,
			}
		}
	}
	return nil
}

func checkEC2FailedServices(instanceID, resourceName string) *SreAlertItem {
	out, err := exec.Command("systemctl", "list-units", "--failed", "--no-legend", "--plain").Output()
	if err != nil {
		return nil
	}
	lines := strings.TrimSpace(string(out))
	if lines == "" {
		return nil
	}
	// Count failed services
	parts := strings.Split(lines, "\n")
	count := 0
	var names []string
	for _, l := range parts {
		l = strings.TrimSpace(l)
		if l == "" {
			continue
		}
		count++
		fields := strings.Fields(l)
		if len(fields) > 0 {
			names = append(names, fields[0])
		}
		if len(names) >= 3 {
			break
		}
	}
	if count == 0 {
		return nil
	}
	msg := itoa(count) + " failed systemd service(s): " + strings.Join(names, ", ")
	return &SreAlertItem{
		ResourceType: "EC2",
		ResourceID:   instanceID,
		ResourceName: resourceName,
		Reason:       "FailedServices",
		Severity:     "warning",
		Message:      msg,
	}
}
