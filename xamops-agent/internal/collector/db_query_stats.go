package collector

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"
)

// ec2IMDSToken fetches an IMDSv2 session token (required on most EC2 instances).
// Returns empty string if not on EC2 or IMDSv2 unavailable.
func ec2IMDSToken() string {
	c := &http.Client{Timeout: 2 * time.Second}
	req, err := http.NewRequest("PUT", "http://169.254.169.254/latest/api/token", nil)
	if err != nil {
		return ""
	}
	req.Header.Set("X-aws-ec2-metadata-token-ttl-seconds", "21600")
	resp, err := c.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return strings.TrimSpace(string(b))
}

// ec2Meta fetches a single EC2 metadata path using the provided IMDSv2 token.
// Falls back to IMDSv1 (no token) if token is empty.
func ec2Meta(token, path string) string {
	c := &http.Client{Timeout: 2 * time.Second}
	req, err := http.NewRequest("GET", "http://169.254.169.254/latest/meta-data/"+path, nil)
	if err != nil {
		return ""
	}
	if token != "" {
		req.Header.Set("X-aws-ec2-metadata-token", token)
	}
	resp, err := c.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return strings.TrimSpace(string(b))
}

// ec2Info fetches region, instance type, and instance ID from EC2 metadata.
// Returns empty strings if not running on EC2.
func ec2Info() (region, instanceType, instanceID string) {
	token := ec2IMDSToken()
	region = ec2Meta(token, "placement/region")
	instanceType = ec2Meta(token, "instance-type")
	instanceID = ec2Meta(token, "instance-id")
	return
}

// collectDBInstanceMetrics collects server-level status metrics for all DSNs.
func collectDBInstanceMetrics(ctx context.Context, dsns []string) (DataBatch, error) {
	region, instanceType, instanceID := ec2Info()
	collected := make([]DbInstanceMetrics, 0, len(dsns))
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		m, err := queryDBMetrics(ctx, dsn, engine, alias, host)
		if err != nil {
			log.Printf("WARN: db-instance-metrics: %s (%s): %v", alias, host, err)
			continue
		}
		m.Region = region
		m.EC2InstanceType = instanceType
		m.EC2InstanceId = instanceID
		collected = append(collected, *m)
		log.Printf("INFO: db-instance-metrics: %s collected metrics from %s (conn=%d, db=%s, region=%s, ec2=%s/%s)", alias, host, m.CurrentConnections, m.DBName, m.Region, instanceType, instanceID)
	}
	return DataBatch{Collector: "db-instance-metrics", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryDBMetrics(ctx context.Context, dsn, engine, alias, host string) (*DbInstanceMetrics, error) {
	db, err := sql.Open(engine, dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}
	switch engine {
	case "mysql":
		return queryMySQLMetrics(ctx, db, alias, host)
	case "postgres":
		return queryPostgresMetrics(ctx, db, alias, host)
	default:
		return nil, fmt.Errorf("unsupported engine: %s", engine)
	}
}

func queryMySQLMetrics(ctx context.Context, db *sql.DB, alias, host string) (*DbInstanceMetrics, error) {
	m := &DbInstanceMetrics{DSNAlias: alias, DBEngine: "mysql", DBHost: host, CollectedAt: time.Now().UTC().Format(time.RFC3339)}
	_ = db.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&m.DBName)
	_ = db.QueryRowContext(ctx, `SELECT SUM(data_length + index_length) FROM information_schema.tables WHERE table_schema = DATABASE()`).Scan(&m.DBSizeBytes)
	statusRows, err := db.QueryContext(ctx, "SHOW GLOBAL STATUS")
	if err != nil {
		return nil, fmt.Errorf("SHOW GLOBAL STATUS: %w", err)
	}
	defer statusRows.Close()
	status := map[string]int64{}
	for statusRows.Next() {
		var k string
		var v sql.NullString
		if err := statusRows.Scan(&k, &v); err != nil {
			continue
		}
		if v.Valid {
			var n int64
			fmt.Sscanf(v.String, "%d", &n)
			status[k] = n
		}
	}
	m.CurrentConnections = status["Threads_connected"]
	m.MaxUsedConnections = status["Max_used_connections"]
	m.TotalQueries = status["Questions"]
	m.TotalSlowQueries = status["Slow_queries"]
	m.ComSelect = status["Com_select"]
	m.ComInsert = status["Com_insert"]
	m.ComUpdate = status["Com_update"]
	m.ComDelete = status["Com_delete"]
	m.BytesReceived = status["Bytes_received"]
	m.BytesSent = status["Bytes_sent"]
	m.Uptime = status["Uptime"]
	reads, readReqs := status["Innodb_buffer_pool_reads"], status["Innodb_buffer_pool_read_requests"]
	m.InnodbBufferPoolReads = reads
	if readReqs > 0 {
		m.InnodbBufferPoolHitPct = float64(readReqs-reads) / float64(readReqs) * 100.0
	}
	m.InnodbRowLockWaits = status["Innodb_row_lock_waits"]
	m.InnodbDeadlocks = status["Innodb_deadlocks"]
	m.ThreadsRunning = status["Threads_running"]
	varRows, err := db.QueryContext(ctx, "SHOW VARIABLES WHERE Variable_name IN ('max_connections','version')")
	if err == nil {
		defer varRows.Close()
		for varRows.Next() {
			var k, v string
			if err2 := varRows.Scan(&k, &v); err2 == nil {
				switch k {
				case "max_connections":
					fmt.Sscanf(v, "%d", &m.MaxConnections)
				case "version":
					m.Version = v
				}
			}
		}
	}
	return m, nil
}

func queryPostgresMetrics(ctx context.Context, db *sql.DB, alias, host string) (*DbInstanceMetrics, error) {
	m := &DbInstanceMetrics{DSNAlias: alias, DBEngine: "postgres", DBHost: host, CollectedAt: time.Now().UTC().Format(time.RFC3339)}

	// Connections
	_ = db.QueryRowContext(ctx, "SELECT count(*) FROM pg_stat_activity WHERE state IS NOT NULL").Scan(&m.CurrentConnections)
	_ = db.QueryRowContext(ctx, "SELECT current_setting('max_connections')::int").Scan(&m.MaxConnections)
	// PostgreSQL has no historical peak counter — report current as the observed peak
	m.MaxUsedConnections = m.CurrentConnections

	// Uptime & version
	_ = db.QueryRowContext(ctx, "SELECT EXTRACT(EPOCH FROM (now() - pg_postmaster_start_time()))::bigint").Scan(&m.Uptime)
	_ = db.QueryRowContext(ctx, "SELECT version()").Scan(&m.Version)
	if len(m.Version) > 40 {
		m.Version = m.Version[:40]
	}

	// Database name and size
	_ = db.QueryRowContext(ctx, "SELECT current_database()").Scan(&m.DBName)
	_ = db.QueryRowContext(ctx, "SELECT pg_database_size(current_database())").Scan(&m.DBSizeBytes)

	// Total queries from pg_stat_statements (sum of all call counts)
	_ = db.QueryRowContext(ctx, "SELECT COALESCE(sum(calls), 0) FROM pg_stat_statements").Scan(&m.TotalQueries)

	// Slow queries: distinct query patterns averaging > 1 second
	_ = db.QueryRowContext(ctx, "SELECT COALESCE(count(*), 0) FROM pg_stat_statements WHERE mean_exec_time > 1000").Scan(&m.TotalSlowQueries)

	// Query type breakdown from pg_stat_statements (sum of calls per DML type)
	_ = db.QueryRowContext(ctx, `
		SELECT
			COALESCE(sum(calls) FILTER (WHERE query ILIKE 'SELECT%'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ILIKE 'INSERT%'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ILIKE 'UPDATE%'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ILIKE 'DELETE%'), 0)
		FROM pg_stat_statements
		WHERE query != '<insufficient privilege>'`,
	).Scan(&m.ComSelect, &m.ComInsert, &m.ComUpdate, &m.ComDelete)

	// Deadlocks & active connections
	_ = db.QueryRowContext(ctx, "SELECT COALESCE(sum(deadlocks), 0) FROM pg_stat_database").Scan(&m.InnodbDeadlocks)
	_ = db.QueryRowContext(ctx, "SELECT count(*) FROM pg_stat_activity WHERE state != 'idle' AND state IS NOT NULL").Scan(&m.ThreadsRunning)

	return m, nil
}

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
  AND query != '<insufficient privilege>'
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
