package collector

import (
	"strings"
	"testing"
)

// TestPostgresIndexUsage asserts the query shape (PERF-13): pick the top-N candidates by the cheap
// relpages catalog proxy (joined via indexrelid), then size only those by indexrelid OID.
func TestPostgresIndexUsage(t *testing.T) {
	q := postgresIndexUsageQuery

	if !strings.Contains(q, "JOIN pg_class ci ON ci.oid = s.indexrelid") {
		t.Errorf("expected pg_class join on indexrelid:\n%s", q)
	}
	if !strings.Contains(q, "ci.relpages DESC") {
		t.Errorf("expected relpages catalog proxy in the candidate ordering:\n%s", q)
	}
	if !strings.Contains(q, "LIMIT 200") {
		t.Errorf("expected LIMIT 200 candidate prefilter:\n%s", q)
	}
	if !strings.Contains(q, "pg_relation_size(sub.indexrelid)") {
		t.Errorf("expected exact sizing by indexrelid OID:\n%s", q)
	}
	if !strings.Contains(q, "s.idx_scan ASC") {
		t.Errorf("expected least-used-first intent preserved (idx_scan ASC):\n%s", q)
	}
}
