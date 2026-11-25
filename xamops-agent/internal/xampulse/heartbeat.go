package xampulse

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// TokenHeader carries the agent access token to the XamOps agent API. It is not Authorization: the web
// application treats that header as a user session credential.
const TokenHeader = "X-Xp-Token"

// API calls the authenticated agent endpoints (heartbeat, config).
type API struct {
	ServerURL string
	HTTP      *http.Client
	Tokens    *TokenManager
}

func (a *API) httpc() *http.Client {
	if a.HTTP != nil {
		return a.HTTP
	}
	return &http.Client{Timeout: 20 * time.Second}
}

func (a *API) do(ctx context.Context, method, path string, payload any, out any, extra http.Header) (int, error) {
	retried := false
	for {
		tok, err := a.Tokens.Access(ctx)
		if err != nil {
			return 0, err
		}
		var rdr io.Reader
		if payload != nil {
			b, err := json.Marshal(payload)
			if err != nil {
				return 0, err
			}
			rdr = bytes.NewReader(b)
		}
		req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(a.ServerURL, "/")+agentAPIPath+path, rdr)
		if err != nil {
			return 0, err
		}
		req.Header.Set(TokenHeader, tok)
		if payload != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		for k, v := range extra {
			req.Header[k] = v
		}
		resp, err := a.httpc().Do(req)
		if err != nil {
			return 0, err
		}
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
		resp.Body.Close()
		if resp.StatusCode == http.StatusUnauthorized && !retried {
			retried = true
			if _, err := a.Tokens.ForceRefresh(ctx); err != nil {
				return resp.StatusCode, err
			}
			continue
		}
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return resp.StatusCode, ErrRevoked
		}
		if resp.StatusCode == http.StatusNotModified {
			return resp.StatusCode, nil
		}
		if resp.StatusCode != http.StatusOK {
			return resp.StatusCode, fmt.Errorf("%s %s: status %d", method, path, resp.StatusCode)
		}
		if out != nil {
			if err := json.Unmarshal(raw, out); err != nil {
				return resp.StatusCode, fmt.Errorf("%s %s: bad response: %w", method, path, err)
			}
		}
		return resp.StatusCode, nil
	}
}

// Heartbeat reports status and returns the server's command.
func (a *API) Heartbeat(ctx context.Context, hb HeartbeatRequest) (HeartbeatResponse, error) {
	var out HeartbeatResponse
	if hb.SourcesStatus == nil {
		hb.SourcesStatus = []SourceStatus{}
	}
	if hb.Errors == nil {
		hb.Errors = []string{}
	}
	if _, err := a.do(ctx, http.MethodPost, "/heartbeat", hb, &out, nil); err != nil {
		return out, err
	}
	if out.Command == "" {
		out.Command = CommandNone
	}
	return out, nil
}

// FetchConfig pulls the desired configuration. Returns (nil, nil) when the server says nothing changed.
func (a *API) FetchConfig(ctx context.Context, haveVersion int64) (*AgentConfig, error) {
	var cfg AgentConfig
	status, err := a.do(ctx, http.MethodGet, fmt.Sprintf("/config?version=%d", haveVersion), nil, &cfg, nil)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotModified {
		return nil, nil
	}
	return &cfg, nil
}

// HeartbeatInterval is how often the agent reports.
const HeartbeatInterval = 30 * time.Second

// StatusProvider supplies the current numbers for a heartbeat.
type StatusProvider interface {
	Status(ctx context.Context) HeartbeatRequest
}

// Loop sends heartbeats until ctx ends or a terminal answer arrives. onCommand is called for every command other
// than NONE; returning an error stops the loop. ErrRevoked is returned when credentials are refused.
func (a *API) Loop(ctx context.Context, interval time.Duration, status StatusProvider, onCommand func(HeartbeatResponse) error) error {
	t := time.NewTicker(interval)
	defer t.Stop()
	failures := 0
	for {
		resp, err := a.Heartbeat(ctx, status.Status(ctx))
		switch {
		case err == nil:
			failures = 0
			if resp.Command != CommandNone {
				if cerr := onCommand(resp); cerr != nil {
					return cerr
				}
			}
		case err == ErrRevoked:
			return ErrRevoked
		case ctx.Err() != nil:
			return ctx.Err()
		default:
			failures++
		}
		wait := interval
		if failures > 0 {
			wait = Backoff(failures, interval, 5*time.Minute)
		}
		t.Reset(wait)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
	}
}
