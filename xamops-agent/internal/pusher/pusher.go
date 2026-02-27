package pusher

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/xamops/agent/internal/collector"
	"github.com/xamops/agent/internal/config"
)

// Pusher handles all HTTP communication back to the XamOps server.
type Pusher struct {
	cfg        *config.Config
	httpClient *http.Client
}

// NewPusher creates a Pusher with a shared http.Client.
func NewPusher(cfg *config.Config) *Pusher {
	return &Pusher{
		cfg: cfg,
		httpClient: &http.Client{
			Timeout: 60 * time.Second,
		},
	}
}

// Push sends the collected agent payload to the XamOps ingest endpoint.
// Retries up to cfg.MaxRetries times on failure.
func (p *Pusher) Push(payload *collector.AgentPayload) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload: %w", err)
	}

	url := p.cfg.ServerURL + "/api/v1/agent/ingest"

	var lastErr error
	for attempt := 1; attempt <= p.cfg.MaxRetries; attempt++ {
		if attempt > 1 {
			log.Printf("Push retry %d/%d after %ds...", attempt, p.cfg.MaxRetries, p.cfg.RetryDelay)
			time.Sleep(time.Duration(p.cfg.RetryDelay) * time.Second)
		}

		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			lastErr = fmt.Errorf("create request: %w", err)
			continue
		}

		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+p.cfg.APIKey)

		resp, err := p.httpClient.Do(req)
		if err != nil {
			lastErr = fmt.Errorf("http request: %w", err)
			continue
		}
		resp.Body.Close()

		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return nil
		}

		lastErr = fmt.Errorf("server returned HTTP %d", resp.StatusCode)
		log.Printf("WARN: Push attempt %d failed: %v", attempt, lastErr)
	}

	return fmt.Errorf("all push attempts failed: %w", lastErr)
}

// Heartbeat sends a minimal heartbeat to signal the agent is alive.
// Uses the ingest endpoint with an empty batches list so the backend
// updates last_agent_checkin without needing a separate endpoint.
func (p *Pusher) Heartbeat() error {
	heartbeat := map[string]interface{}{
		"schemaVersion":  "1.0",
		"agentVersion":   "1.0.0",
		"clusterName":    p.cfg.ClusterName,
		"cloudAccountId": p.cfg.CloudAccountID,
		"collectedAt":    time.Now().UTC().Format(time.RFC3339),
		"batches":        []interface{}{},
	}

	body, err := json.Marshal(heartbeat)
	if err != nil {
		return fmt.Errorf("marshal heartbeat: %w", err)
	}

	req, err := http.NewRequest(http.MethodPost, p.cfg.ServerURL+"/api/v1/agent/ingest", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create heartbeat request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+p.cfg.APIKey)

	resp, err := p.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("heartbeat http: %w", err)
	}
	resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}

	return fmt.Errorf("heartbeat: server returned HTTP %d", resp.StatusCode)
}
