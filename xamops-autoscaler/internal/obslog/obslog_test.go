package obslog

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"testing"
)

// TestDrainSerializesFields asserts a structured drain line carries the
// operationId/node/controller/mode/result fields as JSON.
func TestDrainSerializesFields(t *testing.T) {
	var buf bytes.Buffer
	orig := logger
	logger = slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	defer func() { logger = orig }()

	Drain("Drain[rebalancer drain node/n1]: succeeded", "op-123", "node/n1", "rebalancer", "simulate", "succeeded")

	var m map[string]any
	if err := json.Unmarshal(buf.Bytes(), &m); err != nil {
		t.Fatalf("drain line is not valid JSON: %v (%s)", err, buf.String())
	}
	for k, want := range map[string]string{
		"event":       "drain",
		"operationId": "op-123",
		"node":        "node/n1",
		"controller":  "rebalancer",
		"mode":        "simulate",
		"result":      "succeeded",
	} {
		if got, _ := m[k].(string); got != want {
			t.Errorf("field %q = %q, want %q", k, got, want)
		}
	}
}
