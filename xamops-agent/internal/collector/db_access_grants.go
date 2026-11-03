package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"

	_ "github.com/lib/pq"
)

const accessGrantSampleCap = 25 // object-name samples kept per (grantee, scope, class) bucket

// collectDBAccessGrants reads the READ-ONLY role/grant catalog for every Postgres DSN (COL-B1) and
// emits ONE DbAccessGraphDoc per DSN. It reads NO data rows and NO query text — only the role
// catalog (pg_roles), role inheritance (pg_auth_members) and object grants expanded from the
// catalog ACL columns via aclexplode(). Grants are aggregated at the agent to per-(grantee, scope,
// privilegeClass) counts so a many-table estate does not emit one row per object (§7.2).
func collectDBAccessGrants(ctx context.Context, dsns []string) (DataBatch, error) {
	var docs []DbAccessGraphDoc
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		if engine != "postgres" {
			continue
		}
		doc, err := queryPostgresAccessGrants(ctx, dsn, alias, host)
		if err != nil {
			log.Printf("WARN: db-access-grants: %s (%s): %v", alias, host, err)
			continue
		}
		docs = append(docs, *doc)
	}
	return DataBatch{Collector: "db-access-grants", Items: docs, Count: len(docs), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresAccessGrants(ctx context.Context, dsn, alias, host string) (*DbAccessGraphDoc, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(15 * time.Second)

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}
	if _, err := db.ExecContext(ctx, "SET statement_timeout = '10s'"); err != nil {
		return nil, fmt.Errorf("set statement_timeout: %w", err)
	}

	doc := &DbAccessGraphDoc{
		DSNAlias:    alias,
		DBEngine:    "postgres",
		DBHost:      host,
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
		Roles:       []DbAccessRole{},
		Memberships: []DbMembership{},
		Grants:      []DbAccessGrant{},
	}

	// Roles ───────────────────────────────────────────────────────────────────
	roleRows, err := db.QueryContext(ctx, postgresAccessRolesQuery)
	if err != nil {
		return nil, fmt.Errorf("query pg_roles: %w", err)
	}
	for roleRows.Next() {
		var r DbAccessRole
		var validUntil string
		if err := roleRows.Scan(&r.Name, &r.Oid, &r.CanLogin, &r.IsSuperuser,
			&r.InheritsPrivileges, &r.BypassRls, &r.CreateRole, &r.ConnLimit, &validUntil); err != nil {
			continue
		}
		r.ValidUntil = validUntil
		doc.Roles = append(doc.Roles, r)
	}
	roleRows.Close()
	if err := roleRows.Err(); err != nil {
		return nil, err
	}

	// Memberships ───────────────────────────────────────────────────────────────
	memRows, err := db.QueryContext(ctx, postgresAccessMembershipsQuery)
	if err != nil {
		return nil, fmt.Errorf("query pg_auth_members: %w", err)
	}
	for memRows.Next() {
		var m DbMembership
		if err := memRows.Scan(&m.Member, &m.GrantedRole, &m.WithAdminOption); err != nil {
			continue
		}
		doc.Memberships = append(doc.Memberships, m)
	}
	memRows.Close()
	if err := memRows.Err(); err != nil {
		return nil, err
	}

	// Object grants (aclexplode expansion), aggregated at the agent ─────────────
	grantRows, err := db.QueryContext(ctx, postgresAccessGrantsQuery)
	if err != nil {
		return nil, fmt.Errorf("query catalog ACLs: %w", err)
	}
	defer grantRows.Close()

	type aggKey struct{ grantee, scope, class string }
	type agg struct {
		objects map[string]struct{}
		hasPublic bool
	}
	buckets := map[aggKey]*agg{}
	for grantRows.Next() {
		var grantee, privType, objectKind, scope, objName string
		if err := grantRows.Scan(&grantee, &privType, &objectKind, &scope, &objName); err != nil {
			continue
		}
		class := bucketAccessPrivilege(privType, objectKind)
		if class == "" {
			continue // owner-only / unmapped privilege type — not a graphed grant class
		}
		k := aggKey{grantee: grantee, scope: scope, class: class}
		b := buckets[k]
		if b == nil {
			b = &agg{objects: map[string]struct{}{}}
			buckets[k] = b
		}
		b.objects[objName] = struct{}{}
		if grantee == "PUBLIC" {
			b.hasPublic = true
		}
	}
	if err := grantRows.Err(); err != nil {
		return nil, err
	}

	for k, b := range buckets {
		g := DbAccessGrant{
			Grantee:        k.grantee,
			Scope:          k.scope,
			PrivilegeClass: k.class,
			ObjectCount:    len(b.objects),
			HasPublic:      b.hasPublic,
		}
		for name := range b.objects {
			if len(g.SampleObjects) >= accessGrantSampleCap {
				break
			}
			g.SampleObjects = append(g.SampleObjects, name)
		}
		doc.Grants = append(doc.Grants, g)
	}

	log.Printf("INFO: db-access-grants: %s collected %d roles, %d memberships, %d grant buckets from %s",
		alias, len(doc.Roles), len(doc.Memberships), len(doc.Grants), host)
	return doc, nil
}

// bucketAccessPrivilege maps an (aclexplode privilege_type, source object kind) pair to a graph
// privilege class. The same privilege_type means different things on different objects — a table
// SELECT is READ, a sequence SELECT is USAGE/EXEC — so bucketing reads BOTH the type and the kind
// (§7.2). "OWNER" is the owner-fallback sentinel emitted for a NULL acl (object ownership → ADMIN).
// An unmapped pair returns "" and is skipped.
func bucketAccessPrivilege(privType, objectKind string) string {
	switch objectKind {
	case "database":
		return "CONNECT" // CONNECT / TEMP / TEMPORARY, where PUBLIC CONNECT over-grants live
	case "schema":
		switch privType {
		case "CREATE":
			return "DDL"
		case "USAGE":
			return "USAGE_EXEC"
		case "OWNER":
			return "ADMIN"
		}
	case "routine":
		if privType == "OWNER" {
			return "ADMIN"
		}
		return "USAGE_EXEC" // EXECUTE
	case "sequence":
		if privType == "OWNER" {
			return "ADMIN"
		}
		return "USAGE_EXEC" // USAGE / SELECT / UPDATE on a sequence
	case "table", "default_acl":
		switch privType {
		case "SELECT":
			return "READ"
		case "INSERT", "UPDATE", "DELETE", "TRUNCATE":
			return "WRITE"
		case "REFERENCES", "TRIGGER":
			return "DDL"
		case "EXECUTE", "USAGE":
			return "USAGE_EXEC"
		case "OWNER":
			return "ADMIN"
		}
	}
	return ""
}

const postgresAccessRolesQuery = `
SELECT
	rolname,
	oid::bigint,
	rolcanlogin,
	rolsuper,
	rolinherit,
	rolbypassrls,
	rolcreaterole,
	rolconnlimit,
	COALESCE(to_char(rolvaliduntil AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '')
FROM pg_roles`

const postgresAccessMembershipsQuery = `
SELECT m.rolname AS member, r.rolname AS granted_role, am.admin_option
FROM pg_auth_members am
JOIN pg_roles m ON m.oid = am.member
JOIN pg_roles r ON r.oid = am.roleid`

// postgresAccessGrantsQuery expands the catalog ACL columns with aclexplode() into one row per
// (grantee, privilege_type, object). It uses LEFT JOIN LATERAL aclexplode(...) ON true so an
// owner-only object (NULL acl → aclexplode returns zero rows) is KEPT and its owner emitted as an
// 'OWNER' grant, rather than silently dropped by a CROSS JOIN. It reads pg_class (incl. relkind 'S'
// sequences), pg_namespace.nspacl, pg_proc.proacl, pg_database.datacl and pg_default_acl.defaclacl —
// NOT information_schema.role_table_grants (which self-filters to the connecting role). grantee OID 0
// is PUBLIC.
const postgresAccessGrantsQuery = `
WITH table_grants AS (
	SELECT
		CASE WHEN a.grantee IS NULL THEN own.rolname
		     WHEN a.grantee = 0 THEN 'PUBLIC'
		     ELSE COALESCE(gr.rolname, 'oid:' || a.grantee) END                 AS grantee,
		COALESCE(a.privilege_type, 'OWNER')                                      AS privilege_type,
		CASE WHEN c.relkind = 'S' THEN 'sequence' ELSE 'table' END               AS object_kind,
		n.nspname                                                                AS scope,
		c.relname                                                                AS obj_name
	FROM pg_class c
	JOIN pg_namespace n ON n.oid = c.relnamespace
	LEFT JOIN LATERAL aclexplode(c.relacl) a ON true
	LEFT JOIN pg_roles gr  ON gr.oid  = a.grantee
	LEFT JOIN pg_roles own ON own.oid = c.relowner
	WHERE c.relkind IN ('r','p','v','m','f','S')
	  AND n.nspname NOT IN ('pg_catalog','information_schema')
	  AND n.nspname NOT LIKE 'pg_toast%'
),
schema_grants AS (
	SELECT
		CASE WHEN a.grantee IS NULL THEN own.rolname
		     WHEN a.grantee = 0 THEN 'PUBLIC'
		     ELSE COALESCE(gr.rolname, 'oid:' || a.grantee) END                 AS grantee,
		COALESCE(a.privilege_type, 'OWNER')                                      AS privilege_type,
		'schema'                                                                 AS object_kind,
		n.nspname                                                                AS scope,
		n.nspname                                                                AS obj_name
	FROM pg_namespace n
	LEFT JOIN LATERAL aclexplode(n.nspacl) a ON true
	LEFT JOIN pg_roles gr  ON gr.oid  = a.grantee
	LEFT JOIN pg_roles own ON own.oid = n.nspowner
	WHERE n.nspname NOT IN ('pg_catalog','information_schema')
	  AND n.nspname NOT LIKE 'pg_toast%'
),
routine_grants AS (
	SELECT
		CASE WHEN a.grantee IS NULL THEN own.rolname
		     WHEN a.grantee = 0 THEN 'PUBLIC'
		     ELSE COALESCE(gr.rolname, 'oid:' || a.grantee) END                 AS grantee,
		COALESCE(a.privilege_type, 'OWNER')                                      AS privilege_type,
		'routine'                                                                AS object_kind,
		n.nspname                                                                AS scope,
		p.proname                                                                AS obj_name
	FROM pg_proc p
	JOIN pg_namespace n ON n.oid = p.pronamespace
	LEFT JOIN LATERAL aclexplode(p.proacl) a ON true
	LEFT JOIN pg_roles gr  ON gr.oid  = a.grantee
	LEFT JOIN pg_roles own ON own.oid = p.proowner
	WHERE n.nspname NOT IN ('pg_catalog','information_schema')
	  AND n.nspname NOT LIKE 'pg_toast%'
),
database_grants AS (
	SELECT
		CASE WHEN a.grantee IS NULL THEN own.rolname
		     WHEN a.grantee = 0 THEN 'PUBLIC'
		     ELSE COALESCE(gr.rolname, 'oid:' || a.grantee) END                 AS grantee,
		COALESCE(a.privilege_type, 'OWNER')                                      AS privilege_type,
		'database'                                                               AS object_kind,
		d.datname                                                                AS scope,
		d.datname                                                                AS obj_name
	FROM pg_database d
	LEFT JOIN LATERAL aclexplode(d.datacl) a ON true
	LEFT JOIN pg_roles gr  ON gr.oid  = a.grantee
	LEFT JOIN pg_roles own ON own.oid = d.datdba
	WHERE d.datname = current_database()
),
default_grants AS (
	SELECT
		CASE WHEN a.grantee = 0 THEN 'PUBLIC'
		     ELSE COALESCE(gr.rolname, 'oid:' || a.grantee) END                 AS grantee,
		a.privilege_type                                                         AS privilege_type,
		'default_acl'                                                            AS object_kind,
		COALESCE(n.nspname, '')                                                  AS scope,
		'default:' || dacl.defaclobjtype                                         AS obj_name
	FROM pg_default_acl dacl
	LEFT JOIN pg_namespace n ON n.oid = dacl.defaclnamespace
	LEFT JOIN LATERAL aclexplode(dacl.defaclacl) a ON true
	LEFT JOIN pg_roles gr ON gr.oid = a.grantee
	WHERE a.grantee IS NOT NULL
)
SELECT grantee, privilege_type, object_kind, scope, obj_name FROM table_grants    WHERE grantee IS NOT NULL
UNION ALL
SELECT grantee, privilege_type, object_kind, scope, obj_name FROM schema_grants   WHERE grantee IS NOT NULL
UNION ALL
SELECT grantee, privilege_type, object_kind, scope, obj_name FROM routine_grants  WHERE grantee IS NOT NULL
UNION ALL
SELECT grantee, privilege_type, object_kind, scope, obj_name FROM database_grants WHERE grantee IS NOT NULL
UNION ALL
SELECT grantee, privilege_type, object_kind, scope, obj_name FROM default_grants  WHERE grantee IS NOT NULL`
