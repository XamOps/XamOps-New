package drain

import (
	"testing"

	"github.com/xamops/automation/internal/policy"
)

// TestCountRecovered pins BUG-OBS-006: CountRecovered feeds
// xamops_orphans_recovered_total and must count only recover-orphan records that
// succeeded live — never simulated or failed records, and never a succeeded record
// of some other action.
func TestCountRecovered(t *testing.T) {
	recs := []policy.ActionRecord{
		{Action: "recover-orphan", Status: policy.ActionSucceeded},
		{Action: "recover-orphan", Status: policy.ActionSucceeded},
		{Action: "recover-orphan", Status: policy.ActionSimulated},
		{Action: "recover-orphan", Status: policy.ActionFailed},
		{Action: "drain", Status: policy.ActionSucceeded},
	}
	if got, want := CountRecovered(recs), 2; got != want {
		t.Fatalf("CountRecovered = %d, want %d", got, want)
	}
}

func TestCountRecoveredAllSimulated(t *testing.T) {
	recs := []policy.ActionRecord{
		{Action: "recover-orphan", Status: policy.ActionSimulated},
		{Action: "recover-orphan", Status: policy.ActionSimulated},
	}
	if got := CountRecovered(recs); got != 0 {
		t.Fatalf("CountRecovered(all simulated) = %d, want 0", got)
	}
}

func TestCountRecoveredEmpty(t *testing.T) {
	if got := CountRecovered(nil); got != 0 {
		t.Fatalf("CountRecovered(nil) = %d, want 0", got)
	}
	if got := CountRecovered([]policy.ActionRecord{}); got != 0 {
		t.Fatalf("CountRecovered(empty) = %d, want 0", got)
	}
}
