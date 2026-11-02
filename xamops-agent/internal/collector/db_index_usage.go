package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

// postgresIndexUsageQuery (PERF-13) sizes only the top-N candidate indexes instead of every index.
//
// The old query computed pg_relation_size(indexrelid) for all ~24k indexes just to ORDER BY size,
// then LIMIT 200. Instead an inner select picks the 200 "least-used, largest-first" candidates using
// pg_class.relpages (the planner's cheap cached page-count estimate) as the size proxy — joined to
// pg_stat_user_indexes via indexrelid — and the outer select computes the exact
// pg_relation_size(indexrelid) for ONLY those 200. The least-used-first intent (idx_scan ASC) and
// the IsUnused = idx_scan == 0 flag are preserved; the outer sort re-ranks the 200 by exact size.
const postgresIndexUsageQuery = `
SELECT
    sub.schemaname,
    sub.table_name,
    sub.index_name,
    sub.idx_scan,
    sub.idx_tup_read,
    sub.idx_tup_fetch,
    COALESCE(pg_relation_size(sub.indexrelid), 0) AS index_size_bytes
FROM (
    SELECT
        s.schemaname,
        s.relname      AS table_name,
        s.indexrelname AS index_name,
        s.indexrelid,
        s.idx_scan,
        s.idx_tup_read,
        s.idx_tup_fetch
    FROM pg_stat_user_indexes s
    JOIN pg_class ci ON ci.oid = s.indexrelid
    ORDER BY s.idx_scan ASC, ci.relpages DESC
    LIMIT 200
) sub
ORDER BY sub.idx_scan ASC, index_size_bytes DESC`

// collectDBIndexUsage collects per-index scan statistics.
// Postgres: pg_stat_user_indexes. MySQL: performance_schema.table_io_waits_summary_by_index_usage.
func collectDBIndexUsage(ctx context.Context, dsns []string) (DataBatch, error) {
	collected := make([]DbIndexUsage, 0)
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		var rows []DbIndexUsage
		var queryErr error
		switch engine {
		case "postgres":
			rows, queryErr = queryPostgresIndexUsage(ctx, dsn, alias, host)
		case "mysql":
			rows, queryErr = queryMySQLIndexUsage(ctx, dsn, alias, host)
		default:
			continue
		}
		if queryErr != nil {
			log.Printf("WARN: db-index-usage: %s (%s): %v", alias, host, queryErr)
			continue
		}
		collected = append(collected, rows...)
	}
	return DataBatch{Collector: "db-index-usage", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresIndexUsage(ctx context.Context, dsn, alias, host string) ([]DbIndexUsage, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}

	rows, err := db.QueryContext(ctx, postgresIndexUsageQuery)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_user_indexes: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbIndexUsage, 0)
	for rows.Next() {
		var u DbIndexUsage
		u.DSNAlias = alias
		u.DBEngine = "postgres"
		u.DBHost = host
		u.CollectedAt = now
		if err := rows.Scan(
			&u.SchemaName, &u.TableName, &u.IndexName,
			&u.IdxScan, &u.IdxTupRead, &u.IdxTupFetch,
			&u.IndexSizeBytes,
		); err != nil {
			continue
		}
		u.IsUnused = u.IdxScan == 0
		result = append(result, u)
	}
	return result, nil
}

func queryMySQLIndexUsage(ctx context.Context, dsn, alias, host string) ([]DbIndexUsage, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}

	rows, err := db.QueryContext(ctx, `
		SELECT
			OBJECT_SCHEMA,
			OBJECT_NAME,
			INDEX_NAME,
			COALESCE(COUNT_STAR, 0) AS idx_scan
		FROM performance_schema.table_io_waits_summary_by_index_usage
		WHERE INDEX_NAME IS NOT NULL
		  AND OBJECT_SCHEMA NOT IN ('mysql','performance_schema','information_schema','sys')
		ORDER BY COUNT_STAR ASC
		LIMIT 200
	`)
	if err != nil {
		return nil, fmt.Errorf("query table_io_waits_summary_by_index_usage: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbIndexUsage, 0)
	for rows.Next() {
		var u DbIndexUsage
		u.DSNAlias = alias
		u.DBEngine = "mysql"
		u.DBHost = host
		u.CollectedAt = now
		if err := rows.Scan(&u.SchemaName, &u.TableName, &u.IndexName, &u.IdxScan); err != nil {
			continue
		}
		u.IsUnused = u.IdxScan == 0
		result = append(result, u)
	}
	return result, nil
}
