package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// collectOpenCost gathers cost allocation at two levels in one batch:
//
//   - namespace aggregation, which drives the "Cost by Namespace" table
//   - controller aggregation, which drives "Cost by Workload"
//
// Controller rows are distinguished by a non-empty Controller field. The controller
// query is best-effort: if it fails the namespace rows are still returned, since a
// partially populated Cost tab is better than none.
func collectOpenCost(ctx context.Context, client *http.Client, serviceURL, window string) (DataBatch, error) {
	allocations, err := fetchAllocations(ctx, client, serviceURL, window, "namespace")
	if err != nil {
		return DataBatch{}, err
	}

	if controllerRows, cErr := fetchAllocations(ctx, client, serviceURL, window, "controller"); cErr == nil {
		allocations = append(allocations, controllerRows...)
	}

	return DataBatch{
		Collector:   "opencost-cost",
		Items:       allocations,
		Count:       len(allocations),
		CollectedAt: time.Now().UTC(),
	}, nil
}

// fetchAllocations queries one aggregation level from OpenCost's allocation API.
func fetchAllocations(ctx context.Context, client *http.Client,
	serviceURL, window, aggregate string) ([]OpenCostAllocation, error) {

	url := fmt.Sprintf("%s/allocation/compute?window=%s&aggregate=%s&includeIdle=false",
		serviceURL, window, aggregate)

	body, err := httpGet(ctx, client, url)
	if err != nil {
		return nil, fmt.Errorf("opencost HTTP (%s): %w", aggregate, err)
	}

	// OpenCost response: {"code":200,"status":"success","data":[{...}]}
	var resp struct {
		Code   int                                 `json:"code"`
		Status string                              `json:"status"`
		Data   []map[string]map[string]interface{} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("opencost parse (%s): %w", aggregate, err)
	}

	allocations := make([]OpenCostAllocation, 0)
	for _, window := range resp.Data {
		for key, alloc := range window {
			if key == "__idle__" || key == "__unmounted__" {
				continue
			}

			a := OpenCostAllocation{}
			if aggregate == "controller" {
				// Controller keys arrive as "namespace/controller"; properties carry the
				// authoritative values, so prefer those and fall back to splitting the key.
				a.Namespace = stringField(alloc, "namespace")
				a.Controller = stringField(alloc, "controller")
				a.ControllerKind = stringField(alloc, "controllerKind")
				if a.Controller == "" {
					ns, ctrl := splitControllerKey(key)
					if a.Namespace == "" {
						a.Namespace = ns
					}
					a.Controller = ctrl
				}
				if a.Controller == "" {
					continue // nothing identifying to show
				}
			} else {
				a.Namespace = key
			}

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
	return allocations, nil
}

// stringField reads a string property, tolerating absent or non-string values.
func stringField(alloc map[string]interface{}, key string) string {
	if v, ok := alloc[key].(string); ok {
		return v
	}
	// Some OpenCost versions nest identifiers under "properties".
	if props, ok := alloc["properties"].(map[string]interface{}); ok {
		if v, ok := props[key].(string); ok {
			return v
		}
	}
	return ""
}

// splitControllerKey splits "namespace/controller", tolerating a bare controller name.
func splitControllerKey(key string) (namespace, controller string) {
	for i := 0; i < len(key); i++ {
		if key[i] == '/' {
			return key[:i], key[i+1:]
		}
	}
	return "", key
}
