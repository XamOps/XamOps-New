package xpec2receiver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// imdsState is what the instance metadata service currently says about upcoming interruptions.
type imdsState struct {
	SpotAction        string // hibernate, stop or terminate; "" when there is no notice
	SpotTime          string
	RebalanceNotice   string // noticeTime; "" when there is no recommendation
	ScheduledActive   int    // scheduled maintenance events in state "active"
	ScheduledEarliest string // NotBefore of the first active event, as IMDS writes it
}

// errNotEC2 means the metadata service did not answer: the host is not an EC2 instance (or IMDS is disabled).
var errNotEC2 = errors.New("instance metadata service not reachable")

// imdsClient reads three IMDS items with an IMDSv2 session token. The token is cached for most of its lifetime,
// and when the service does not answer at all, it is not asked again for retryAfter, so a non-EC2 host does not
// spend a timeout on every scrape.
type imdsClient struct {
	endpoint   string
	http       *http.Client
	now        func() time.Time
	retryAfter time.Duration

	mu        sync.Mutex
	token     string
	tokenExp  time.Time
	downUntil time.Time
}

const imdsTokenTTL = 6 * time.Hour

func newIMDSClient(endpoint string, timeout time.Duration) *imdsClient {
	return &imdsClient{
		endpoint:   strings.TrimRight(endpoint, "/"),
		http:       &http.Client{Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		now:        time.Now,
		retryAfter: 5 * time.Minute,
	}
}

func (c *imdsClient) state(ctx context.Context) (imdsState, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	var st imdsState
	if c.now().Before(c.downUntil) {
		return st, errNotEC2
	}
	tok, err := c.sessionToken(ctx)
	if err != nil {
		c.downUntil = c.now().Add(c.retryAfter)
		return st, errNotEC2
	}

	var spot struct {
		Action string `json:"action"`
		Time   string `json:"time"`
	}
	if found, err := c.getJSON(ctx, tok, "/latest/meta-data/spot/instance-action", &spot); err != nil {
		return st, err
	} else if found {
		st.SpotAction, st.SpotTime = spot.Action, spot.Time
	}

	var rebalance struct {
		NoticeTime string `json:"noticeTime"`
	}
	if found, err := c.getJSON(ctx, tok, "/latest/meta-data/events/recommendations/rebalance", &rebalance); err != nil {
		return st, err
	} else if found {
		st.RebalanceNotice = rebalance.NoticeTime
		if st.RebalanceNotice == "" {
			st.RebalanceNotice = "unknown"
		}
	}

	var scheduled []struct {
		NotBefore string `json:"NotBefore"`
		State     string `json:"State"`
	}
	if found, err := c.getJSON(ctx, tok, "/latest/meta-data/events/maintenance/scheduled", &scheduled); err != nil {
		return st, err
	} else if found {
		for _, e := range scheduled {
			if strings.EqualFold(e.State, "active") {
				st.ScheduledActive++
				if st.ScheduledEarliest == "" {
					st.ScheduledEarliest = e.NotBefore
				}
			}
		}
	}
	return st, nil
}

func (c *imdsClient) sessionToken(ctx context.Context) (string, error) {
	if c.token != "" && c.now().Before(c.tokenExp) {
		return c.token, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, c.endpoint+"/latest/api/token", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("X-aws-ec2-metadata-token-ttl-seconds", fmt.Sprint(int(imdsTokenTTL.Seconds())))
	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK || len(body) == 0 {
		return "", fmt.Errorf("token request: HTTP %d", resp.StatusCode)
	}
	c.token = strings.TrimSpace(string(body))
	c.tokenExp = c.now().Add(imdsTokenTTL - 10*time.Minute)
	return c.token, nil
}

// getJSON returns found=false on 404 (IMDS's way of saying "no such notice"). A 401 drops the cached token so the
// next scrape fetches a fresh one.
func (c *imdsClient) getJSON(ctx context.Context, token, path string, into any) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.endpoint+path, nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("X-aws-ec2-metadata-token", token)
	resp, err := c.http.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return false, err
	}
	switch resp.StatusCode {
	case http.StatusOK:
		if len(strings.TrimSpace(string(body))) == 0 {
			return false, nil
		}
		if err := json.Unmarshal(body, into); err != nil {
			return false, fmt.Errorf("%s: %w", path, err)
		}
		return true, nil
	case http.StatusNotFound:
		return false, nil
	case http.StatusUnauthorized:
		c.token = ""
		return false, fmt.Errorf("%s: token rejected", path)
	default:
		return false, fmt.Errorf("%s: HTTP %d", path, resp.StatusCode)
	}
}
