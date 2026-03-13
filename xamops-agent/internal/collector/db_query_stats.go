package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"net/url"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"
)

// collectDBQueryStats queries all configured DSNs and returns a single DataBatch
// containing DbQueryStat entries from every reachable database instance.
func collectDBQueryStats(ctx context.Context, dsns []string, queryLimit int) (DataBatch, error) {
	collected := make([]DbQueryStat, 0, len(dsns)*queryLimit)

	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			log.Printf("WARN: db-query-stats: could not parse DSN %d: %v", i, err)
			continue
		}

		stats, err := queryDB(ctx, dsn, engine, alias, host, queryLimit)
		if err != nil {
			log.Printf("WARN: db-query-stats: %s (%s): %v", alias, host, err)
			continue
		}
		collected = append(collected, stats...)
		log.Printf("INFO: db-query-stats: %s collected %d queries from %s", alias, len(stats), host)
	}

	return DataBatch{
		Collector:   "db-query-stats",
		Items:       collected,
		Count:       len(collected),
		CollectedAt: time.Now().UTC(),
	}, nil
}

// queryDB opens a connection to one DB instance and collects top slow queries.
func queryDB(ctx context.Context, dsn, engine, alias, host string, limit int) ([]DbQueryStat, error) {
	db, err := sql.Open(engine, dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()

	// Validate connectivity — use a short timeout so a dead host doesn't block the collection cycle
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}

	switch engine {
	case "mysql":
		return queryMySQL(ctx, db, alias, host, limit)
	case "postgres":
		return queryPostgres(ctx, db, alias, host, limit)
	default:
		return nil, fmt.Errorf("unsupported engine: %s", engine)
	}
}

// queryMySQL reads performance_schema.events_statements_summary_by_digest.
// Requires: GRANT SELECT ON performance_schema.* TO 'xamops'@'%';
func queryMySQL(ctx context.Context, db *sql.DB, alias, host string, limit int) ([]DbQueryStat, error) {
	const q = `
SELECT
    COALESCE(DIGEST_TEXT, '(unknown)') AS query_digest,
    COUNT_STAR                         AS call_count,
    SUM_TIMER_WAIT  / 1e12             AS total_time_sec,
    AVG_TIMER_WAIT  / 1e12             AS avg_time_sec,
    MAX_TIMER_WAIT  / 1e12             AS max_time_sec,
    SUM_ROWS_EXAMINED                  AS rows_examined,
    SUM_ROWS_SENT                      AS rows_sent,
    SUM_LOCK_TIME   / 1e12             AS lock_time_sec,
    SUM_NO_INDEX_USED                  AS no_index_used
FROM performance_schema.events_statements_summary_by_digest
WHERE DIGEST_TEXT IS NOT NULL
  AND COUNT_STAR   > 0
ORDER BY SUM_TIMER_WAIT DESC
LIMIT ?`

	rows, err := db.QueryContext(ctx, q, limit)
	if err != nil {
		return nil, fmt.Errorf("query performance_schema: %w", err)
	}
	defer rows.Close()

	var stats []DbQueryStat
	for rows.Next() {
		var s DbQueryStat
		var noIndexUsed int64
		if err := rows.Scan(
			&s.QueryDigest,
			&s.CallCount,
			&s.TotalTimeSeconds,
			&s.AvgTimeSeconds,
			&s.MaxTimeSeconds,
			&s.RowsExamined,
			&s.RowsSent,
			&s.LockTimeSeconds,
			&noIndexUsed,
		); err != nil {
			return nil, fmt.Errorf("scan: %w", err)
		}
		s.DSNAlias = alias
		s.DBEngine = "mysql"
		s.DBHost = host
		s.NoIndexUsed = noIndexUsed > 0
		stats = append(stats, s)
	}
	return stats, rows.Err()
}

// queryPostgres reads pg_stat_statements.
// Requires: CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
//           GRANT SELECT ON pg_stat_statements TO xamops;
func queryPostgres(ctx context.Context, db *sql.DB, alias, host string, limit int) ([]DbQueryStat, error) {
	const q = `
SELECT
    query,
    calls,
    total_exec_time / 1000.0  AS total_time_sec,
    mean_exec_time  / 1000.0  AS avg_time_sec,
    max_exec_time   / 1000.0  AS max_time_sec,
    rows,
    shared_blks_hit,
    shared_blks_read
FROM pg_stat_statements
WHERE calls > 0
ORDER BY total_exec_time DESC
LIMIT $1`

	rows, err := db.QueryContext(ctx, q, limit)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_statements: %w", err)
	}
	defer rows.Close()

	var stats []DbQueryStat
	for rows.Next() {
		var s DbQueryStat
		if err := rows.Scan(
			&s.QueryDigest,
			&s.CallCount,
			&s.TotalTimeSeconds,
			&s.AvgTimeSeconds,
			&s.MaxTimeSeconds,
			&s.RowsSent,
			&s.SharedBlksHit,
			&s.SharedBlksRead,
		); err != nil {
			return nil, fmt.Errorf("scan: %w", err)
		}
		s.DSNAlias = alias
		s.DBEngine = "postgres"
		s.DBHost = host
		stats = append(stats, s)
	}
	return stats, rows.Err()
}

// parseDSN detects the engine and extracts the host without credentials.
// Supported formats:
//   MySQL:    user:pass@tcp(host:3306)/db  OR  mysql://user:pass@host:3306/db
//   Postgres: postgres://user:pass@host:5432/db  OR  host=h user=u ...
func parseDSN(dsn string) (engine, host string, err error) {
	lower := strings.ToLower(dsn)

	// URL-style postgres
	if strings.HasPrefix(lower, "postgres://") || strings.HasPrefix(lower, "postgresql://") {
		u, e := url.Parse(dsn)
		if e != nil {
			return "", "", fmt.Errorf("parse postgres url: %w", e)
		}
		return "postgres", u.Host, nil
	}

	// URL-style mysql
	if strings.HasPrefix(lower, "mysql://") {
		u, e := url.Parse(dsn)
		if e != nil {
			return "", "", fmt.Errorf("parse mysql url: %w", e)
		}
		return "mysql", u.Host, nil
	}

	// go-sql-driver/mysql DSN: user:pass@tcp(host:port)/db
	if strings.Contains(dsn, "@tcp(") || strings.Contains(dsn, "@unix(") {
		start := strings.Index(dsn, "@tcp(")
		if start == -1 {
			start = strings.Index(dsn, "@unix(")
		}
		start++ // skip @
		end := strings.Index(dsn[start:], ")/")
		if end == -1 {
			end = len(dsn) - start
		}
		host = dsn[start : start+end+1] // includes "tcp(...)"
		// Strip tcp()/unix() wrapper to get just host:port
		host = strings.TrimPrefix(host, "tcp(")
		host = strings.TrimPrefix(host, "unix(")
		host = strings.TrimSuffix(host, ")")
		return "mysql", host, nil
	}

	// key=value postgres DSN: host=... user=... password=...
	if strings.Contains(dsn, "host=") {
		for _, part := range strings.Fields(dsn) {
			if strings.HasPrefix(part, "host=") {
				host = strings.TrimPrefix(part, "host=")
			}
		}
		return "postgres", host, nil
	}

	return "", "", fmt.Errorf("cannot detect DB engine from DSN (expected postgres:// or user:pass@tcp(host)/db)")
}
