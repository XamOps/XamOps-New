package xampulse

import (
	"bufio"
	"context"
	"io/fs"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// QueueBytes sums the size of the collector's persistent sending queue directory.
func QueueBytes(dir string) int64 {
	var total int64
	_ = filepath.WalkDir(dir, func(_ string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, ierr := d.Info(); ierr == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}

// DroppedItems reads the collector's own Prometheus endpoint and sums every enqueue-failed counter, which
// counts telemetry the sending queue refused because it was full. Returns 0 when the endpoint is unreachable.
func DroppedItems(ctx context.Context, metricsURL string) int64 {
	if metricsURL == "" {
		metricsURL = "http://localhost:8888/metrics"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, metricsURL, nil)
	if err != nil {
		return 0
	}
	c := http.Client{Timeout: 2 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return 0
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0
	}
	return sumEnqueueFailed(bufio.NewScanner(resp.Body))
}

func sumEnqueueFailed(sc *bufio.Scanner) int64 {
	var total float64
	for sc.Scan() {
		line := sc.Text()
		if strings.HasPrefix(line, "#") || !strings.HasPrefix(line, "otelcol_exporter_enqueue_failed_") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		if v, err := strconv.ParseFloat(fields[len(fields)-1], 64); err == nil {
			total += v
		}
	}
	return int64(total)
}
