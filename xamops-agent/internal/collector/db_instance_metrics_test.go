package collector

import (
	"strings"
	"testing"
)

// TestPostgresPerDbCountersScoped asserts the extended COL-A1 per-database counters are scoped
// WHERE datname = current_database() (LOW-2) and cache-hit is read instance-wide.
func TestPostgresPerDbCountersScoped(t *testing.T) {
	if !strings.Contains(pgPerDbCountersQuery, "datname = current_database()") {
		t.Errorf("per-DB counters must be scoped to current_database():\n%s", pgPerDbCountersQuery)
	}
	if !strings.Contains(pgPerDbDeadlocksQuery, "datname = current_database()") {
		t.Errorf("deadlocks must be scoped to current_database() (LOW-2):\n%s", pgPerDbDeadlocksQuery)
	}
	// Cache hit is instance-wide: a sum over pg_stat_database, NOT scoped to one database.
	if strings.Contains(pgCacheHitQuery, "current_database()") {
		t.Errorf("cache-hit must be instance-wide, not per-database:\n%s", pgCacheHitQuery)
	}
	if !strings.Contains(pgCacheHitQuery, "sum(blks_hit)") || !strings.Contains(pgCacheHitQuery, "sum(blks_read)") {
		t.Errorf("cache-hit must sum blks_hit/blks_read instance-wide:\n%s", pgCacheHitQuery)
	}
}

// TestPostgresCheckpointQueryVersionBranch asserts the checkpoint source branches on version:
// pg_stat_checkpointer on PG17+, pg_stat_bgwriter below.
func TestPostgresCheckpointQueryVersionBranch(t *testing.T) {
	pg17 := pgCheckpointQuery(170000)
	if !strings.Contains(pg17, "pg_stat_checkpointer") {
		t.Errorf("PG17+ must read pg_stat_checkpointer:\n%s", pg17)
	}
	pg16 := pgCheckpointQuery(160000)
	if !strings.Contains(pg16, "pg_stat_bgwriter") {
		t.Errorf("PG<17 must read pg_stat_bgwriter:\n%s", pg16)
	}
	if strings.Contains(pg16, "pg_stat_checkpointer") {
		t.Errorf("PG<17 must NOT read pg_stat_checkpointer:\n%s", pg16)
	}
}

// TestPostgresWalQueryColumnPinned asserts the WAL read is column-pinned (never SELECT *) and that
// the timing columns appear only below PG18.
func TestPostgresWalQueryColumnPinned(t *testing.T) {
	for _, v := range []int{160000, 170000, 180000, 0} {
		q := pgWalQuery(v)
		if strings.Contains(q, "SELECT *") {
			t.Errorf("WAL query must be column-pinned, never SELECT * (version %d):\n%s", v, q)
		}
		for _, col := range []string{"wal_records", "wal_fpi", "wal_bytes", "wal_buffers_full"} {
			if !strings.Contains(q, col) {
				t.Errorf("WAL query must always pin %s (version %d):\n%s", col, v, q)
			}
		}
	}
	pre18 := pgWalQuery(170000)
	if !strings.Contains(pre18, "wal_write_time") || !strings.Contains(pre18, "wal_sync_time") {
		t.Errorf("PG<18 WAL query must include timing columns:\n%s", pre18)
	}
	pg18 := pgWalQuery(180000)
	if strings.Contains(pg18, "wal_write_time") || strings.Contains(pg18, "wal_sync_time") {
		t.Errorf("PG18+ WAL query must NOT include timing columns:\n%s", pg18)
	}
}

func TestPostgresDatfrozenxidAgeQuery(t *testing.T) {
	if !strings.Contains(pgDatfrozenxidAgeQuery, "age(datfrozenxid)") {
		t.Errorf("wraparound query must read age(datfrozenxid):\n%s", pgDatfrozenxidAgeQuery)
	}
}
