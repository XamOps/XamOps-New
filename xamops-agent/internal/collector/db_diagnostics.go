package collector

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"
)

// collectDBDiagnostics checks reachability and privilege health for every DSN.
// Always returns a batch even if all DSNs are healthy (empty ErrorType = healthy).
func collectDBDiagnostics(ctx context.Context, dsns []string) (DataBatch, error) {
	var diags []DbDiagnostic
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			diags = append(diags, DbDiagnostic{
				DSNAlias:     alias,
				DBEngine:     "unknown",
				DBHost:       dsn,
				Reachable:    false,
				ErrorType:    "connection",
				ErrorMessage: fmt.Sprintf("cannot parse DSN: %v", err),
				CollectedAt:  time.Now().UTC().Format(time.RFC3339),
			})
			continue
		}
		diag := checkDiagnostic(ctx, dsn, engine, alias, host)
		diags = append(diags, diag)
	}
	return DataBatch{
		Collector:   "db-diagnostics",
		Items:       diags,
		Count:       len(diags),
		CollectedAt: time.Now().UTC(),
	}, nil
}

func checkDiagnostic(ctx context.Context, dsn, engine, alias, host string) DbDiagnostic {
	now := time.Now().UTC().Format(time.RFC3339)
	base := DbDiagnostic{DSNAlias: alias, DBEngine: engine, DBHost: host, CollectedAt: now}

	switch engine {
	case "mongodb":
		return checkMongoDiagnostic(ctx, dsn, base)
	case "redis":
		return checkRedisDiagnostic(ctx, dsn, base)
	}

	// SQL engines (mysql, postgres)
	db, err := sql.Open(engine, dsn)
	if err != nil {
		base.ErrorType = "connection"
		base.ErrorMessage = sanitizeError(err.Error())
		return base
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(10 * time.Second)

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		base.ErrorType = classifyError(err.Error())
		base.ErrorMessage = sanitizeError(err.Error())
		return base
	}
	base.Reachable = true
	if engine == "postgres" {
		checkPostgresPrivileges(ctx, db, &base)
	} else if engine == "mysql" {
		checkMySQLPrivileges(ctx, db, &base)
	}
	return base
}

func checkMongoDiagnostic(ctx context.Context, dsn string, base DbDiagnostic) DbDiagnostic {
	client, err := connectMongo(ctx, dsn)
	if err != nil {
		base.ErrorType = "connection"
		base.ErrorMessage = sanitizeError(err.Error())
		return base
	}
	defer client.Disconnect(context.Background())
	base.Reachable = true

	qCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var result interface{}
	if err := client.Database("admin").RunCommand(qCtx,
		map[string]interface{}{"serverStatus": 1}).Decode(&result); err != nil {
		base.ErrorType = "privilege"
		base.ErrorMessage = "Missing clusterMonitor role. Grant: db.grantRolesToUser('<user>', [{role:'clusterMonitor',db:'admin'}])"
	}
	return base
}

func checkRedisDiagnostic(ctx context.Context, dsn string, base DbDiagnostic) DbDiagnostic {
	client, err := connectRedis(ctx, dsn)
	if err != nil {
		base.ErrorType = "connection"
		base.ErrorMessage = sanitizeError(err.Error())
		return base
	}
	defer client.Close()
	base.Reachable = true
	return base
}

func checkPostgresPrivileges(ctx context.Context, db *sql.DB, d *DbDiagnostic) {
	qCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	// Check pg_stat_statements extension
	var extCount int
	_ = db.QueryRowContext(qCtx, "SELECT count(*) FROM pg_extension WHERE extname='pg_stat_statements'").Scan(&extCount)
	if extCount == 0 {
		d.ErrorType = "extension_missing"
		d.ErrorMessage = "pg_stat_statements extension is not installed. Run: CREATE EXTENSION pg_stat_statements;"
		return
	}

	// Check SELECT on pg_stat_statements
	rows, err := db.QueryContext(qCtx, "SELECT query FROM pg_stat_statements LIMIT 1")
	if err != nil {
		if classifyError(err.Error()) == "privilege" {
			d.ErrorType = "privilege"
			d.ErrorMessage = "Missing SELECT on pg_stat_statements. Grant: GRANT pg_read_all_stats TO <user>;"
		}
		return
	}
	rows.Close()
}

func checkMySQLPrivileges(ctx context.Context, db *sql.DB, d *DbDiagnostic) {
	qCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	rows, err := db.QueryContext(qCtx,
		"SELECT DIGEST_TEXT FROM performance_schema.events_statements_summary_by_digest LIMIT 1")
	if err != nil {
		if classifyError(err.Error()) == "privilege" {
			d.ErrorType = "privilege"
			d.ErrorMessage = "Missing SELECT on performance_schema. Grant: GRANT SELECT ON performance_schema.* TO '<user>'@'%';"
		}
		return
	}
	rows.Close()
}

// classifyError maps DB error messages to error type strings.
func classifyError(msg string) string {
	lower := strings.ToLower(msg)
	switch {
	case strings.Contains(lower, "permission denied") ||
		strings.Contains(lower, "access denied") ||
		strings.Contains(lower, "command denied") ||
		strings.Contains(lower, "must be superuser"):
		return "privilege"
	case strings.Contains(lower, "does not exist") && strings.Contains(lower, "extension"):
		return "extension_missing"
	default:
		return "connection"
	}
}

// sanitizeError strips potential credential leaks and truncates long messages.
func sanitizeError(msg string) string {
	if len(msg) > 300 {
		msg = msg[:300] + "…"
	}
	return msg
}
