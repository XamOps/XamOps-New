package consolidation

import (
	"os"
	"testing"
	"time"
)

// TestLiveSnapshot analyzes a read-only kubectl snapshot and checks the invariants.
// It is skipped unless CONSOLIDATION_LIVE_SNAPSHOT names the List file, e.g.
//
//	kubectl --context <ctx> get nodes,pods,pdb,replicasets,statefulsets,deployments -A -o json > /tmp/snap.json
//	CONSOLIDATION_LIVE_SNAPSHOT=/tmp/snap.json go test ./internal/consolidation -run TestLiveSnapshot -v
//
// Snapshots are never committed.
func TestLiveSnapshot(t *testing.T) {
	path := os.Getenv("CONSOLIDATION_LIVE_SNAPSHOT")
	if path == "" {
		t.Skip("CONSOLIDATION_LIVE_SNAPSHOT not set")
	}
	in := loadList(t, path).input()
	p := Analyze(in, testOptions(), time.Now())
	checkInvariants(t, path, in, p)
	t.Logf("plan:\n%s", planJSON(t, p))
}
