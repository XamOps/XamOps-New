package collector

import (
	"strings"
	"testing"
)

// TestAccessGrantsQueryShape asserts COL-B1 expands catalog ACLs with aclexplode() over LEFT JOIN
// LATERAL (owner-only objects kept, not dropped), includes sequences (relkind 'S'), reads datacl /
// proacl, and does NOT read an information_schema grant view (which self-filters to the connecting role).
func TestAccessGrantsQueryShape(t *testing.T) {
	q := postgresAccessGrantsQuery
	if !strings.Contains(q, "aclexplode(") {
		t.Errorf("access-grants query must expand ACLs with aclexplode():\n%s", q)
	}
	if !strings.Contains(q, "LEFT JOIN LATERAL aclexplode") {
		t.Errorf("access-grants query must use LEFT JOIN LATERAL so owner-only objects are kept:\n%s", q)
	}
	if !strings.Contains(q, "'r','p','v','m','f','S'") {
		t.Errorf("access-grants query must include sequences (relkind 'S'):\n%s", q)
	}
	for _, src := range []string{"d.datacl", "p.proacl", "n.nspacl", "pg_default_acl"} {
		if !strings.Contains(q, src) {
			t.Errorf("access-grants query must read %s:\n%s", src, q)
		}
	}
	// Excluding the information_schema NAMESPACE by name is fine; reading FROM an information_schema
	// grant view (which self-filters to the connecting role) is what must never happen.
	for _, forbidden := range []string{"FROM information_schema", "role_table_grants", "table_privileges"} {
		if strings.Contains(q, forbidden) {
			t.Errorf("access-grants query must NOT read %s (it self-filters to the connecting role):\n%s", forbidden, q)
		}
	}
	// Roles query exposes the governance attributes.
	for _, col := range []string{"rolsuper", "rolcanlogin", "rolbypassrls"} {
		if !strings.Contains(postgresAccessRolesQuery, col) {
			t.Errorf("roles query must expose %s:\n%s", col, postgresAccessRolesQuery)
		}
	}
	if !strings.Contains(postgresAccessMembershipsQuery, "admin_option") {
		t.Errorf("memberships query must read admin_option:\n%s", postgresAccessMembershipsQuery)
	}
}

// TestBucketAccessPrivilege covers the relkind-aware mapping: the same privilege_type means
// different things on different object kinds (table SELECT = READ, sequence SELECT = USAGE/EXEC).
func TestBucketAccessPrivilege(t *testing.T) {
	cases := []struct {
		priv, kind, want string
	}{
		{"SELECT", "table", "READ"},
		{"INSERT", "table", "WRITE"},
		{"UPDATE", "table", "WRITE"},
		{"DELETE", "table", "WRITE"},
		{"TRUNCATE", "table", "WRITE"},
		{"REFERENCES", "table", "DDL"},
		{"TRIGGER", "table", "DDL"},
		{"OWNER", "table", "ADMIN"},
		{"SELECT", "sequence", "USAGE_EXEC"},
		{"USAGE", "sequence", "USAGE_EXEC"},
		{"CREATE", "schema", "DDL"},
		{"USAGE", "schema", "USAGE_EXEC"},
		{"EXECUTE", "routine", "USAGE_EXEC"},
		{"CONNECT", "database", "CONNECT"},
		{"TEMPORARY", "database", "CONNECT"},
		{"ALTER", "table", ""}, // owner-only — never emitted by aclexplode, unmapped here
	}
	for _, c := range cases {
		if got := bucketAccessPrivilege(c.priv, c.kind); got != c.want {
			t.Errorf("bucketAccessPrivilege(%q,%q) = %q, want %q", c.priv, c.kind, got, c.want)
		}
	}
}
