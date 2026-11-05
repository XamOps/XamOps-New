package xampulse

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"runtime"
	"strings"
	"time"
)

// Errors the caller reacts to.
var (
	// ErrNotInScope: the server answered 403. The instance is not covered; stop trying.
	ErrNotInScope = errors.New("xampulse: instance is not in scope for this account")
	// ErrEnrollTimeout: the server kept answering 202 (pending) until the retry budget ran out.
	ErrEnrollTimeout = errors.New("xampulse: enrolment stayed pending until the retry limit")
)

const agentAPIPath = "/api/v1/agent/xampulse"

// Credentialer is what enrolment needs from IMDS (the real type is *IMDS; tests use a fake).
type Credentialer interface {
	Identity(ctx context.Context) (InstanceIdentity, error)
	RoleCredentials(ctx context.Context) (Credentials, error)
}

// Enroller performs the nonce challenge and the identity proof.
type Enroller struct {
	ServerURL    string
	HTTP         *http.Client
	IMDS         Credentialer
	AgentVersion string
	// MaxWait bounds how long a pending (202) enrolment is retried. Default 15 minutes.
	MaxWait time.Duration
	// Sleep and Now are injectable for tests.
	Sleep func(ctx context.Context, d time.Duration) error
	Now   func() time.Time
}

func (e *Enroller) httpc() *http.Client {
	if e.HTTP != nil {
		return e.HTTP
	}
	return &http.Client{Timeout: 20 * time.Second}
}

func (e *Enroller) sleep(ctx context.Context, d time.Duration) error {
	if e.Sleep != nil {
		return e.Sleep(ctx, d)
	}
	return sleepCtx(ctx, d)
}

func (e *Enroller) now() time.Time {
	if e.Now != nil {
		return e.Now()
	}
	return time.Now()
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Enroll returns the token pair and the first configuration. It retries pending and transient answers
// with backoff and jitter, and stops at once on 403.
func (e *Enroller) Enroll(ctx context.Context) (EnrollResponse, error) {
	maxWait := e.MaxWait
	if maxWait <= 0 {
		maxWait = 15 * time.Minute
	}
	deadline := e.now().Add(maxWait)
	attempt := 0
	for {
		res, pending, retryAfter, err := e.once(ctx)
		if err == nil && !pending {
			return res, nil
		}
		if errors.Is(err, ErrNotInScope) || ctx.Err() != nil {
			if ctx.Err() != nil {
				return EnrollResponse{}, ctx.Err()
			}
			return EnrollResponse{}, err
		}
		wait := retryAfter
		if wait <= 0 {
			wait = Backoff(attempt, 2*time.Second, 2*time.Minute)
		}
		if e.now().Add(wait).After(deadline) {
			if err != nil {
				return EnrollResponse{}, fmt.Errorf("%w: last error: %v", ErrEnrollTimeout, err)
			}
			return EnrollResponse{}, ErrEnrollTimeout
		}
		attempt++
		if err := e.sleep(ctx, jitter(wait)); err != nil {
			return EnrollResponse{}, err
		}
	}
}

// once runs one challenge and one enrol call. pending is true for a 202.
func (e *Enroller) once(ctx context.Context) (res EnrollResponse, pending bool, retryAfter time.Duration, err error) {
	id, err := e.IMDS.Identity(ctx)
	if err != nil {
		return res, false, 0, err
	}
	var ch EnrollChallengeResponse
	status, body, err := e.post(ctx, "/enroll/challenge", EnrollChallengeRequest{Region: id.Region})
	if err != nil {
		return res, false, 0, err
	}
	if status != http.StatusOK {
		return res, false, 0, fmt.Errorf("challenge: status %d", status)
	}
	if err := json.Unmarshal(body, &ch); err != nil || ch.Nonce == "" {
		return res, false, 0, errors.New("challenge: bad response")
	}
	creds, err := e.IMDS.RoleCredentials(ctx)
	if err != nil {
		return res, false, 0, err
	}
	sts, err := SignGetCallerIdentity(creds, id.Region, ch.Nonce, e.now())
	if err != nil {
		return res, false, 0, err
	}
	host, _ := os.Hostname()
	status, body, err = e.post(ctx, "/enroll", EnrollRequest{
		Nonce: ch.Nonce, Region: id.Region, StsRequest: sts, AgentVersion: e.AgentVersion,
		OS: runtime.GOOS, Arch: runtime.GOARCH, Hostname: host,
	})
	if err != nil {
		return res, false, 0, err
	}
	switch status {
	case http.StatusOK:
		if err := json.Unmarshal(body, &res); err != nil || res.AccessToken == "" || res.RefreshToken == "" {
			return res, false, 0, errors.New("enroll: bad response")
		}
		return res, false, 0, nil
	case http.StatusAccepted:
		var p EnrollPending
		_ = json.Unmarshal(body, &p)
		return res, true, time.Duration(p.RetryAfterSec) * time.Second, nil
	case http.StatusForbidden:
		return res, false, 0, ErrNotInScope
	default:
		return res, false, 0, fmt.Errorf("enroll: status %d", status)
	}
}

func (e *Enroller) post(ctx context.Context, path string, payload any) (int, []byte, error) {
	b, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(e.ServerURL, "/")+agentAPIPath+path, bytes.NewReader(b))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.httpc().Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode, body, err
}

// Backoff returns base*2^attempt capped at max (no jitter).
func Backoff(attempt int, base, max time.Duration) time.Duration {
	if attempt < 0 {
		attempt = 0
	}
	d := base
	for i := 0; i < attempt; i++ {
		d *= 2
		if d >= max {
			return max
		}
	}
	if d > max {
		return max
	}
	return d
}

// jitter spreads d by +/-20% so a fleet does not retry in lock step.
func jitter(d time.Duration) time.Duration {
	if d <= 0 {
		return d
	}
	f := 0.8 + rand.Float64()*0.4
	return time.Duration(float64(d) * f)
}
