package collector

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// openObserveShipper sends log record batches to OpenObserve's ingest API.
// Endpoint: POST {baseURL}/api/{org}/{stream}/_json
// Auth: Basic base64(username:password)
type openObserveShipper struct {
	baseURL    string
	org        string
	authHeader string
	httpClient *http.Client
}

func newOpenObserveShipper(baseURL, org, username, password string) *openObserveShipper {
	creds := base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
	return &openObserveShipper{
		baseURL:    baseURL,
		org:        org,
		authHeader: "Basic " + creds,
		httpClient: &http.Client{Timeout: 15 * time.Second},
	}
}

// ship POSTs records to the given stream in OpenObserve.
// records must be a slice of map[string]interface{} where _timestamp is microseconds.
func (s *openObserveShipper) ship(stream string, records []map[string]interface{}) error {
	if len(records) == 0 {
		return nil
	}
	body, err := json.Marshal(records)
	if err != nil {
		return fmt.Errorf("openobserve: marshal: %w", err)
	}
	url := s.baseURL + "/api/" + s.org + "/" + stream + "/_json"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("openobserve: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", s.authHeader)

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("openobserve: http: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("openobserve: ingest returned HTTP %d", resp.StatusCode)
	}
	return nil
}
