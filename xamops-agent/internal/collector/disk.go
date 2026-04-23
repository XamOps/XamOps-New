package collector

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// collectDiskUsage collects per-mount-point disk usage for this EC2 instance.
// Requires COLLECTOR_DISK=true. Returns error if not running on EC2 (IMDS unreachable).
func collectDiskUsage(ctx context.Context) (DataBatch, error) {
	client := &http.Client{Timeout: 5 * time.Second}

	// Retry IMDS — on fresh EC2 boot, metadata service can take up to ~30s to respond.
	instanceID, err := imdsInstanceIDWithRetry(ctx, client)
	if err != nil {
		return DataBatch{}, fmt.Errorf("IMDS unavailable (not running on EC2?): %w", err)
	}

	mounts, err := parseDfOutput()
	if err != nil {
		return DataBatch{}, fmt.Errorf("df: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339)
	items := make([]DiskUsage, 0, len(mounts))
	for _, m := range mounts {
		volumeID := resolveVolumeID(m.Device)
		items = append(items, DiskUsage{
			InstanceID:  instanceID,
			VolumeID:    volumeID,
			Device:      m.Device,
			MountPoint:  m.MountPoint,
			TotalGB:     m.TotalGB,
			UsedGB:      m.UsedGB,
			FreeGB:      m.FreeGB,
			UsedPercent: m.UsedPct,
			CollectedAt: now,
		})
	}

	log.Printf("[disk] collected %d mount points for instance %s", len(items), instanceID)

	return DataBatch{
		Collector:   "disk-usage",
		Items:       items,
		Count:       len(items),
		CollectedAt: time.Now().UTC(),
	}, nil
}

// ── IMDS helpers ──────────────────────────────────────────────────────────────

func imdsInstanceID(ctx context.Context, client *http.Client) (string, error) {
	token, err := imdsToken(ctx, client)
	if err != nil {
		// Fall back to IMDSv1 (not all instances have IMDSv2 enforced)
		return imdsGet(ctx, client, "", "instance-id")
	}
	return imdsGet(ctx, client, token, "instance-id")
}

// imdsInstanceIDWithRetry retries IMDS up to 12 times (60s total) with linear backoff.
// On EC2 boot, IMDS can take 20-30s to become available, especially after stop/start.
func imdsInstanceIDWithRetry(ctx context.Context, client *http.Client) (string, error) {
	const maxAttempts = 12
	const retryDelay = 5 * time.Second
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		id, err := imdsInstanceID(ctx, client)
		if err == nil {
			if attempt > 1 {
				log.Printf("[disk] IMDS ready after %d attempts", attempt)
			}
			return id, nil
		}
		lastErr = err
		log.Printf("[disk] IMDS attempt %d/%d failed: %v — retrying in %s", attempt, maxAttempts, err, retryDelay)
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(retryDelay):
		}
	}
	return "", lastErr
}

func imdsToken(ctx context.Context, client *http.Client) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut,
		"http://169.254.169.254/latest/api/token", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("X-aws-ec2-metadata-token-ttl-seconds", "21600")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(b)), nil
}

func imdsGet(ctx context.Context, client *http.Client, token, path string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"http://169.254.169.254/latest/meta-data/"+path, nil)
	if err != nil {
		return "", err
	}
	if token != "" {
		req.Header.Set("X-aws-ec2-metadata-token", token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("IMDS HTTP %d for %s", resp.StatusCode, path)
	}
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(b)), nil
}

// ── df parsing ────────────────────────────────────────────────────────────────

type mountInfo struct {
	Device     string
	MountPoint string
	TotalGB    float64
	UsedGB     float64
	FreeGB     float64
	UsedPct    float64
}

// parseDfOutput runs `df -BG` and parses the output.
// Only returns real block devices (lines starting with /dev/).
func parseDfOutput() ([]mountInfo, error) {
	out, err := exec.Command("df", "-BG").Output()
	if err != nil {
		return nil, fmt.Errorf("df -BG failed: %w", err)
	}

	var mounts []mountInfo
	scanner := bufio.NewScanner(bytes.NewReader(out))
	header := true
	for scanner.Scan() {
		line := scanner.Text()
		if header {
			header = false
			continue // skip "Filesystem 1G-blocks Used Available Use% Mounted on"
		}
		fields := strings.Fields(line)
		if len(fields) < 6 {
			continue
		}
		device := fields[0]
		if !strings.HasPrefix(device, "/dev/") {
			continue // skip tmpfs, devtmpfs, overlay, etc.
		}

		total := parseGB(fields[1])
		used := parseGB(fields[2])
		free := parseGB(fields[3])
		pct := parsePct(fields[4])
		mount := fields[5]

		mounts = append(mounts, mountInfo{
			Device:     device,
			MountPoint: mount,
			TotalGB:    total,
			UsedGB:     used,
			FreeGB:     free,
			UsedPct:    pct,
		})
	}
	return mounts, scanner.Err()
}

func parseGB(s string) float64 {
	s = strings.TrimSuffix(s, "G")
	v, _ := strconv.ParseFloat(s, 64)
	return v
}

func parsePct(s string) float64 {
	s = strings.TrimSuffix(s, "%")
	v, _ := strconv.ParseFloat(s, 64)
	return v
}

// ── Volume ID resolution ──────────────────────────────────────────────────────

// resolveVolumeID returns the EBS volume ID for a device path.
// For NVMe devices (/dev/nvme*), reads the serial from /sys/block/<dev>/serial.
// Returns empty string for Xen devices (/dev/xvd*) — backend maps by device name.
func resolveVolumeID(device string) string {
	base := filepath.Base(device) // e.g. "nvme0n1p1" or "xvda1"

	if !strings.HasPrefix(base, "nvme") {
		return "" // Xen/virtio — no serial in sysfs
	}

	// Strip partition suffix: nvme0n1p1 → nvme0n1
	diskBase := base
	if idx := strings.LastIndex(base, "p"); idx > 0 {
		candidate := base[:idx]
		// Make sure what remains still looks like an nvme disk (contains digits)
		if len(candidate) > 4 {
			diskBase = candidate
		}
	}

	serialPath := "/sys/block/" + diskBase + "/serial"
	data, err := os.ReadFile(serialPath)
	if err != nil {
		return ""
	}
	serial := strings.TrimSpace(string(data))
	// AWS serial format: vol0abc123def456789 (no dash) → vol-0abc123def456789
	if strings.HasPrefix(serial, "vol") && !strings.HasPrefix(serial, "vol-") {
		return "vol-" + serial[3:]
	}
	return serial
}
