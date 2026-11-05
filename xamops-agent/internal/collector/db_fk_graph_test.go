package collector

import (
	"strings"
	"testing"
)

// TestFkGraphQueryShape asserts COL-C1 reads FK edges from pg_constraint contype='f' and sizes
// tables from the CATALOG (relpages × block_size, GREATEST(reltuples,0)) — never with the
// pg_total_relation_size filesystem stat PERF-6 indicts.
func TestFkGraphQueryShape(t *testing.T) {
	if !strings.Contains(postgresFkEdgesQuery, "con.contype = 'f'") {
		t.Errorf("FK edge query must filter contype = 'f':\n%s", postgresFkEdgesQuery)
	}
	if !strings.Contains(postgresFkTableWeightsQuery, "relpages") ||
		!strings.Contains(postgresFkTableWeightsQuery, "block_size") {
		t.Errorf("weights query must size by relpages × block_size:\n%s", postgresFkTableWeightsQuery)
	}
	if !strings.Contains(postgresFkTableWeightsQuery, "GREATEST(c.reltuples, 0)") {
		t.Errorf("weights query must clamp reltuples with GREATEST(c.reltuples, 0):\n%s", postgresFkTableWeightsQuery)
	}
	if strings.Contains(postgresFkEdgesQuery, "pg_total_relation_size") ||
		strings.Contains(postgresFkTableWeightsQuery, "pg_total_relation_size") {
		t.Errorf("FK queries must NOT use pg_total_relation_size (PERF-6)")
	}
}

func TestFkActionLabel(t *testing.T) {
	cases := map[string]string{"a": "NO ACTION", "r": "RESTRICT", "c": "CASCADE", "n": "SET NULL", "d": "SET DEFAULT", "?": ""}
	for code, want := range cases {
		if got := fkActionLabel(code); got != want {
			t.Errorf("fkActionLabel(%q) = %q, want %q", code, got, want)
		}
	}
}
