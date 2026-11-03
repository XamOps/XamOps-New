package collector

import (
	"database/sql"
	"strings"
	"testing"
)

// TestReplicationQueryShape asserts the primary reads pg_stat_replication write/flush/replay lag
// and the replica reads the applied-commit timestamp.
func TestReplicationQueryShape(t *testing.T) {
	if !strings.Contains(postgresPrimaryReplicationQuery, "pg_stat_replication") {
		t.Errorf("primary query must read pg_stat_replication:\n%s", postgresPrimaryReplicationQuery)
	}
	for _, lag := range []string{"write_lag", "flush_lag", "replay_lag"} {
		if !strings.Contains(postgresPrimaryReplicationQuery, lag) {
			t.Errorf("primary query must expose %s:\n%s", lag, postgresPrimaryReplicationQuery)
		}
	}
	if !strings.Contains(postgresReplicaReplicationQuery, "pg_last_xact_replay_timestamp()") {
		t.Errorf("replica query must read pg_last_xact_replay_timestamp():\n%s", postgresReplicaReplicationQuery)
	}
}

func TestNullFloatPtr(t *testing.T) {
	if nullFloatPtr(sql.NullFloat64{Valid: false}) != nil {
		t.Errorf("invalid NullFloat64 must map to nil")
	}
	p := nullFloatPtr(sql.NullFloat64{Float64: 1.5, Valid: true})
	if p == nil || *p != 1.5 {
		t.Errorf("valid NullFloat64 must map to its value")
	}
}
