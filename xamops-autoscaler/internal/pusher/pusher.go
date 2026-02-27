package pusher

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"
)

// Pusher sends data batches and heartbeats to the XamOps backend.
// Uses the same /api/v1/agent/ingest endpoint as the xamops-agent.
type Pusher struct {
	serverURL      string
	apiKey         string
	clusterName    string
	cloudAccountID string
	agentVersion   string
	httpClient     *http.Client
	maxRetries     int
	retryDelay     int
}

func New(serverURL, apiKey, clusterName, cloudAccountID string, maxRetries, retryDelay int) *Pusher {
	return &Pusher{
		serverURL:      serverURL,
		apiKey:         apiKey,
		clusterName:    clusterName,
		cloudAccountID: cloudAccountID,
		agentVersion:   "1.0.0-autoscaler",
		httpClient:     &http.Client{Timeout: 30 * time.Second},
		maxRetries:     maxRetries,
		retryDelay:     retryDelay,
	}
}

// Batch is a single collector's data to be pushed.
type Batch struct {
	Collector   string      `json:"collector"`
	Items       interface{} `json:"items"`
	Count       int         `json:"count"`
	CollectedAt time.Time   `json:"collectedAt"`
}

// Push sends one or more data batches to the XamOps ingest endpoint.
func (p *Pusher) Push(batches []Batch) error {
	if len(batches) == 0 {
		return nil
	}

	payload := map[string]interface{}{
		"schemaVersion":  "1.0",
		"agentVersion":   p.agentVersion,
		"clusterName":    p.clusterName,
		"cloudAccountId": p.cloudAccountID,
		"collectedAt":    time.Now().UTC().Format(time.RFC3339),
		"batches":        batches,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal push payload: %w", err)
	}

	return p.postWithRetry(p.serverURL+"/api/v1/agent/ingest", body)
}

// Heartbeat sends a lightweight check-in so the backend marks agent as active.
func (p *Pusher) Heartbeat() error {
	payload := map[string]interface{}{
		"clusterName":    p.clusterName,
		"cloudAccountId": p.cloudAccountID,
		"agentVersion":   p.agentVersion,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	req, err := http.NewRequest(http.MethodPost, p.serverURL+"/api/v1/agent/heartbeat", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+p.apiKey)

	resp, err := p.httpClient.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

func (p *Pusher) postWithRetry(url string, body []byte) error {
	var lastErr error
	for attempt := 1; attempt <= p.maxRetries; attempt++ {
		if attempt > 1 {
			time.Sleep(time.Duration(p.retryDelay) * time.Second)
			log.Printf("Push retry %d/%d", attempt, p.maxRetries)
		}

		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+p.apiKey)

		resp, err := p.httpClient.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		resp.Body.Close()

		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return nil
		}
		lastErr = fmt.Errorf("server returned HTTP %d", resp.StatusCode)
	}
	return fmt.Errorf("all push attempts failed: %w", lastErr)
}
