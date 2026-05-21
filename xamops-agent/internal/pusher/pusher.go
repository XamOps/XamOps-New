package pusher

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"

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

// FetchConfig calls GET /api/v1/agent/config and populates any unset OpenObserve
// fields in cfg from the server response, so operators don't need to set them manually.
func (p *Pusher) FetchConfig() {
	configURL := p.cfg.ServerURL + "/api/v1/agent/config"
	if p.cfg.CloudAccountID != "" {
		configURL += "?accountId=" + p.cfg.CloudAccountID
	}
	req, err := http.NewRequest(http.MethodGet, configURL, nil)
	if err != nil {
		log.Printf("FetchConfig: create request: %v", err)
		return
	}
	req.Header.Set("Authorization", "Bearer "+p.cfg.APIKey)

	resp, err := p.httpClient.Do(req)
	if err != nil {
		log.Printf("FetchConfig: http request: %v", err)
		return
	}
	defer func() {
		io.Copy(io.Discard, resp.Body) //nolint:errcheck
		resp.Body.Close()
	}()

	if resp.StatusCode != http.StatusOK {
		log.Printf("FetchConfig: server returned HTTP %d", resp.StatusCode)
		return
	}

	var body struct {
		OpenObserve map[string]string `json:"openobserve"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		log.Printf("FetchConfig: decode response: %v", err)
		return
	}

	oo := body.OpenObserve
	if oo["url"] != "" && p.cfg.OpenObserveURL == "" {
		p.cfg.OpenObserveURL = oo["url"]
	}
	if oo["org"] != "" && p.cfg.OpenObserveOrg == "" {
		p.cfg.OpenObserveOrg = oo["org"]
	}
	if oo["username"] != "" && p.cfg.OpenObserveUsername == "" {
		p.cfg.OpenObserveUsername = oo["username"]
	}
	if oo["password"] != "" && p.cfg.OpenObservePassword == "" {
		p.cfg.OpenObservePassword = oo["password"]
	}
	log.Printf("FetchConfig: OpenObserve config applied (url=%s, org=%s)", p.cfg.OpenObserveURL, p.cfg.OpenObserveOrg)
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

// explainRateLimit tracks last EXPLAIN time per DSN to enforce 60 s cooldown.
// Keyed by dsnAlias — bounded by number of DSNs, never grows unboundedly.
var explainRateLimit = map[string]time.Time{}

// PollAndExecCommands fetches at most one pending EXPLAIN command from the server,
// executes it safely, and pushes the result back.
// Non-blocking: any server/DB error is logged and the heartbeat continues.
func (p *Pusher) PollAndExecCommands() {
	req, err := http.NewRequest(http.MethodGet, p.cfg.ServerURL+"/api/v1/agent/commands", nil)
	if err != nil {
		log.Printf("WARN: commands poll: create request: %v", err)
		return
	}
	req.Header.Set("Authorization", "Bearer "+p.cfg.APIKey)
	req.Header.Set("X-Agent-Name", p.cfg.ClusterName)
	req.Header.Set("X-Cloud-Account-Id", p.cfg.CloudAccountID)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("WARN: commands poll: %v", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusNotFound {
		return // no pending commands
	}
	if resp.StatusCode != http.StatusOK {
		log.Printf("WARN: commands poll: server returned HTTP %d", resp.StatusCode)
		return
	}

	var commands []collector.AgentCommand
	if err := json.NewDecoder(resp.Body).Decode(&commands); err != nil || len(commands) == 0 {
		return
	}

	cmd := commands[0] // process at most one per heartbeat cycle
	if cmd.Type != "explain" {
		log.Printf("WARN: commands poll: unknown command type %q, skipping", cmd.Type)
		return
	}

	// Rate limit: one EXPLAIN per DSN per 60 s
	if last, ok := explainRateLimit[cmd.DSNAlias]; ok && time.Since(last) < 60*time.Second {
		log.Printf("INFO: commands poll: rate-limiting EXPLAIN for %s (last run %s ago)",
			cmd.DSNAlias, time.Since(last).Round(time.Second))
		return
	}

	log.Printf("INFO: commands poll: running EXPLAIN commandId=%s dsnAlias=%s", cmd.CommandID, cmd.DSNAlias)
	result := execExplain(cmd, p.cfg.DBDSNs)
	explainRateLimit[cmd.DSNAlias] = time.Now()

	// Push result as a single-item db-explain-result batch
	payload := &collector.AgentPayload{
		SchemaVersion:  "1.0",
		AgentVersion:   "1.0.0",
		ClusterName:    p.cfg.ClusterName,
		CloudAccountID: p.cfg.CloudAccountID,
		CollectedAt:    time.Now().UTC(),
		Batches: []collector.DataBatch{{
			Collector:   "db-explain-result",
			Items:       []collector.ExplainResult{result},
			Count:       1,
			CollectedAt: time.Now().UTC(),
		}},
	}
	if err := p.Push(payload); err != nil {
		log.Printf("WARN: commands poll: push explain result: %v", err)
	}
}

// execExplain runs EXPLAIN (no ANALYZE) for the DSN matching cmd.DSNAlias.
// NEVER runs EXPLAIN ANALYZE — only the plan is computed, query is not executed.
func execExplain(cmd collector.AgentCommand, dsns []string) collector.ExplainResult {
	now := time.Now().UTC().Format(time.RFC3339)
	result := collector.ExplainResult{CommandID: cmd.CommandID, DSNAlias: cmd.DSNAlias, CollectedAt: now}

	// Find the DSN matching the alias (db-0, db-1, …)
	dsn := ""
	for i, d := range dsns {
		if fmt.Sprintf("db-%d", i) == cmd.DSNAlias {
			dsn = d
			break
		}
	}
	if dsn == "" {
		result.Error = fmt.Sprintf("DSN alias %q not found in agent configuration", cmd.DSNAlias)
		return result
	}

	db, err := sql.Open(cmd.DBEngine, dsn)
	if err != nil {
		result.Error = fmt.Sprintf("open: %v", err)
		return result
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(10 * time.Second)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		result.Error = fmt.Sprintf("ping: %v", err)
		return result
	}

	switch cmd.DBEngine {
	case "postgres":
		_, _ = db.ExecContext(ctx, "SET statement_timeout = '3s'")
		var plan []byte
		err = db.QueryRowContext(ctx, "EXPLAIN (FORMAT JSON) "+cmd.QueryText).Scan(&plan)
		if err == nil {
			result.PlanJSON = string(plan)
		} else {
			result.Error = fmt.Sprintf("explain: %v", err)
		}
	case "mysql":
		_, _ = db.ExecContext(ctx, "SET SESSION MAX_EXECUTION_TIME=3000")
		rows, err := db.QueryContext(ctx, "EXPLAIN FORMAT=JSON "+cmd.QueryText)
		if err != nil {
			result.Error = fmt.Sprintf("explain: %v", err)
		} else {
			defer rows.Close()
			if rows.Next() {
				if err := rows.Scan(&result.PlanJSON); err != nil {
					result.Error = fmt.Sprintf("scan plan: %v", err)
				}
			}
		}
	default:
		result.Error = fmt.Sprintf("unsupported engine: %s", cmd.DBEngine)
	}
	return result
}
