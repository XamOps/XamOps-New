package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

func collectOpenCost(ctx context.Context, client *http.Client, serviceURL, window string) (DataBatch, error) {
	url := fmt.Sprintf("%s/allocation/compute?window=%s&aggregate=namespace&includeIdle=false", serviceURL, window)

	body, err := httpGet(ctx, client, url)
	if err != nil {
		return DataBatch{}, fmt.Errorf("opencost HTTP: %w", err)
	}

	// OpenCost response: {"code":200,"status":"success","data":[{...}]}
	var resp struct {
		Code   int                                         `json:"code"`
		Status string                                      `json:"status"`
		Data   []map[string]map[string]interface{}         `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return DataBatch{}, fmt.Errorf("opencost parse: %w", err)
	}

	allocations := make([]OpenCostAllocation, 0)
	for _, window := range resp.Data {
		for ns, alloc := range window {
			if ns == "__idle__" || ns == "__unmounted__" {
				continue
			}
			a := OpenCostAllocation{Namespace: ns}
			if v, ok := alloc["cpuCost"].(float64); ok {
				a.CPUCost = v
			}
			if v, ok := alloc["ramCost"].(float64); ok {
				a.RAMCost = v
			}
			if v, ok := alloc["pvCost"].(float64); ok {
				a.PVCost = v
			}
			if v, ok := alloc["networkCost"].(float64); ok {
				a.NetworkCost = v
			}
			if v, ok := alloc["totalCost"].(float64); ok {
				a.TotalCost = v
			}
			if v, ok := alloc["cpuEfficiency"].(float64); ok {
				a.CPUEfficiency = v
			}
			if v, ok := alloc["ramEfficiency"].(float64); ok {
				a.RAMEfficiency = v
			}
			allocations = append(allocations, a)
		}
	}

	return DataBatch{
		Collector:   "opencost-cost",
		Items:       allocations,
		Count:       len(allocations),
		CollectedAt: time.Now().UTC(),
	}, nil
}
