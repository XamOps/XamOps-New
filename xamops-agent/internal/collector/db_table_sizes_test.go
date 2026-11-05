package collector

import (
	"strings"
	"testing"
)

// TestPostgresTableSizes asserts the query shape (PERF-6): size the top-N candidates by OID after a
// cheap relpages prefilter, and never use the old schemaname||'.'||relname regclass cast.
func TestPostgresTableSizes(t *testing.T) {
	q := postgresTableSizesQuery

	if strings.Contains(q, "|| '.' ||") {
		t.Errorf("query still uses the regclass name cast (|| '.' ||):\n%s", q)
	}
	for _, fn := range []string{"pg_table_size(c.oid)", "pg_indexes_size(c.oid)", "pg_total_relation_size(c.oid)"} {
		if !strings.Contains(q, fn) {
			t.Errorf("expected sizing by OID via %s:\n%s", fn, q)
		}
	}
	if !strings.Contains(q, "ORDER BY c.relpages DESC") {
		t.Errorf("expected relpages-ordered candidate prefilter:\n%s", q)
	}
	if !strings.Contains(q, "LIMIT $1") {
		t.Errorf("expected parameterised LIMIT $1 prefilter:\n%s", q)
	}
	if !strings.Contains(q, "pg_class") || !strings.Contains(q, "pg_namespace") {
		t.Errorf("expected catalog prefilter over pg_class/pg_namespace:\n%s", q)
	}
}
