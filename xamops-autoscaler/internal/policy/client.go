package policy

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

// Client fetches the autoscaling policy from the XamOps backend.
// The backend stores the policy in agent_data_cache (dataType="autoscaling-policy").
// This client reads it via a dedicated agent endpoint (authenticated with API key).
type Client struct {
	serverURL      string
	apiKey         string
	clusterName    string
	cloudAccountID string
	httpClient     *http.Client
}

func NewClient(serverURL, apiKey, clusterName, cloudAccountID string) *Client {
	return &Client{
		serverURL:      serverURL,
		apiKey:         apiKey,
		clusterName:    clusterName,
		cloudAccountID: cloudAccountID,
		httpClient:     &http.Client{Timeout: 15 * time.Second},
	}
}

// FetchPolicy retrieves the current policy from XamOps backend.
// Returns the default bundle if the backend is unreachable or autoscaling is disabled.
func (c *Client) FetchPolicy(ctx context.Context) (PolicyBundle, error) {
	url := fmt.Sprintf("%s/api/v1/agent/autoscaling-policy?clusterName=%s&cloudAccountId=%s",
		c.serverURL, c.clusterName, c.cloudAccountID)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return DefaultBundle(), fmt.Errorf("create policy request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.apiKey)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		log.Printf("WARN: could not reach XamOps backend for policy: %v — using defaults", err)
		return DefaultBundle(), nil
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		// Cluster not registered or autoscaling not enabled
		log.Println("INFO: autoscaling not enabled for this cluster in XamOps — controller idle")
		return DefaultBundle(), nil
	}

	if resp.StatusCode != http.StatusOK {
		return DefaultBundle(), fmt.Errorf("policy endpoint returned HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return DefaultBundle(), fmt.Errorf("read policy response: %w", err)
	}

	// Unmarshal into the safe defaults, not a zero PolicyBundle: any field the backend
	// omits would otherwise silently become false, so an older backend that does not yet
	// serve e.g. evictorPolicy.analyseOnly would un-gate the evictor on a new autoscaler.
	// Fields the backend does send still win, so there is no behaviour change against a
	// correctly-serving backend.
	bundle := DefaultBundle()
	if err := json.Unmarshal(body, &bundle); err != nil {
		return DefaultBundle(), fmt.Errorf("parse policy: %w", err)
	}

	log.Printf("INFO: policy fetched — mode=%s evictor=%v rightsizer=%v rebalancer_status=%s hpa=%v vpa=%v nodeopt=%v",
		bundle.AutoscalerMode,
		bundle.EvictorPolicy.Enabled,
		bundle.WorkloadRightsizerPolicy.Enabled,
		bundle.Rebalancer.Status,
		bundle.HPAManagerPolicy.Enabled,
		bundle.VPAManagerPolicy.Enabled,
		bundle.NodeOptimizerPolicy.Enabled)

	return bundle, nil
}
