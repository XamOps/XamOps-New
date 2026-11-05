package approval

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// APIError is a non-success answer from the backend. Code is the backend's error
// code when the body carried one ({"error","code"}).
type APIError struct {
	Status     int
	Code       string
	Message    string
	RetryAfter time.Duration
}

func (e *APIError) Error() string {
	return fmt.Sprintf("backend answered HTTP %d code=%q %s", e.Status, e.Code, e.Message)
}

// StatusOf returns the HTTP status of an *APIError, or 0 for any other error.
func StatusOf(err error) int {
	if ae, ok := err.(*APIError); ok {
		return ae.Status
	}
	return 0
}

// CodeOf returns the backend error code of an *APIError, or "".
func CodeOf(err error) string {
	if ae, ok := err.(*APIError); ok {
		return ae.Code
	}
	return ""
}

// Client talks to /api/v1/agent/proposals with the same Bearer key and
// clusterName/cloudAccountId query as policy.Client.
type Client struct {
	serverURL      string
	apiKey         string
	clusterName    string
	cloudAccountID string
	httpClient     *http.Client
}

// NewClient returns a client with a 30 s per-request timeout.
func NewClient(serverURL, apiKey, clusterName, cloudAccountID string) *Client {
	return &Client{
		serverURL: serverURL, apiKey: apiKey, clusterName: clusterName, cloudAccountID: cloudAccountID,
		httpClient: &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *Client) endpoint(path string) string {
	q := url.Values{}
	q.Set("clusterName", c.clusterName)
	q.Set("cloudAccountId", c.cloudAccountID)
	return c.serverURL + path + "?" + q.Encode()
}

// do sends one request and returns the status and body. A transport error is returned
// as-is; a non-2xx status is returned as *APIError.
func (c *Client) do(ctx context.Context, method, path string, body interface{}) (int, []byte, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, fmt.Errorf("marshal %s: %w", path, err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.endpoint(path), rd)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.apiKey)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return resp.StatusCode, data, nil
	}
	ae := &APIError{Status: resp.StatusCode}
	var eb struct {
		Error string `json:"error"`
		Code  string `json:"code"`
	}
	if json.Unmarshal(data, &eb) == nil {
		ae.Code, ae.Message = eb.Code, eb.Error
	}
	if ra := resp.Header.Get("Retry-After"); ra != "" {
		if secs, err := strconv.Atoi(strings.TrimSpace(ra)); err == nil && secs > 0 {
			ae.RetryAfter = time.Duration(secs) * time.Second
		}
	}
	return resp.StatusCode, data, ae
}

// PostProposals sends one chunk of a cycle's batch.
func (c *Client) PostProposals(ctx context.Context, b Batch) (BatchResponse, error) {
	var out BatchResponse
	_, data, err := c.do(ctx, http.MethodPost, "/api/v1/agent/proposals", b)
	if err != nil {
		return out, err
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return out, fmt.Errorf("parse batch response: %w", err)
	}
	return out, nil
}

// Claim asks for the oldest approved node proposal. It returns (nil, nil) on 204.
func (c *Client) Claim(ctx context.Context, cycleID string) (*ApprovedAction, error) {
	status, data, err := c.do(ctx, http.MethodPost, "/api/v1/agent/proposals/claim", map[string]string{"cycleId": cycleID})
	if err != nil {
		return nil, err
	}
	if status == http.StatusNoContent || len(bytes.TrimSpace(data)) == 0 {
		return nil, nil
	}
	a, err := ParseApprovedAction(data, false)
	if err != nil {
		return nil, err
	}
	return &a, nil
}

// Dispatched lists the cluster's DISPATCHED node proposals.
func (c *Client) Dispatched(ctx context.Context) ([]DispatchedItem, error) {
	_, data, err := c.do(ctx, http.MethodGet, "/api/v1/agent/proposals/dispatched", nil)
	if err != nil {
		return nil, err
	}
	var l DispatchedList
	if err := json.Unmarshal(data, &l); err != nil {
		return nil, fmt.Errorf("parse dispatched: %w", err)
	}
	return l.Items, nil
}

// ReportOutcome posts one outcome. A 409 is returned as *APIError (the caller treats
// NOT_DISPATCHED as "the backend already resolved it").
func (c *Client) ReportOutcome(ctx context.Context, o Outcome) error {
	_, _, err := c.do(ctx, http.MethodPost, "/api/v1/agent/proposals/"+url.PathEscape(o.ProposalID)+"/outcome", o.Clamp())
	return err
}
