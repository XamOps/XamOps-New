package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

const tableSizeLimit = 50 // top N tables by total size

// postgresTableSizesQuery (PERF-6) sizes only the top-N candidate tables instead of every table.
//
// The old query called pg_total_relation_size on every row of pg_stat_user_tables just to ORDER BY
// it, then LIMIT 50 — on a 12k-table database that is 12k size calls (seconds + hundreds of MB).
// Instead an inner select picks the top-N candidate OIDs by pg_class.relpages (the planner's cheap
// cached page-count estimate, no size call), and the outer select computes the exact
// pg_table_size/pg_indexes_size/pg_total_relation_size for ONLY those N by OID. Sizing by c.oid also
// drops the old `schemaname || '.' || relname` regclass cast, which broke on mixed-case/quoted
// names (incidental COR-12 fix). Candidate membership is now chosen by the relpages estimate, so at
// the N boundary it can differ slightly from an exact global ordering — the intended optimisation.
const postgresTableSizesQuery = `
SELECT
    n.nspname                       AS schemaname,
    c.relname                       AS relname,
    pg_table_size(c.oid)            AS data_bytes,
    pg_indexes_size(c.oid)          AS index_bytes,
    pg_total_relation_size(c.oid)   AS total_bytes,
    COALESCE(st.n_live_tup, 0)      AS row_count
FROM (
    SELECT c.oid
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r','p','m')
      AND n.nspname NOT IN ('pg_catalog','information_schema')
      AND n.nspname NOT LIKE 'pg_toast%'
    ORDER BY c.relpages DESC
    LIMIT $1
) cand
JOIN pg_class c      ON c.oid = cand.oid
JOIN pg_namespace n  ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables st ON st.relid = c.oid
ORDER BY total_bytes DESC`

// collectDBTableSizes queries table storage stats for all DSNs.
// Pushed as "db-table-sizes" batch — safe to add: server stores any batch type it receives.
func collectDBTableSizes(ctx context.Context, dsns []string) (DataBatch, error) {
	collected := make([]DbTableSize, 0, len(dsns)*10)
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		db, err := sql.Open(engine, dsn)
		if err != nil {
			log.Printf("WARN: db-table-sizes: open %s: %v", alias, err)
			continue
		}
		pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		err = db.PingContext(pingCtx)
		cancel()
		if err != nil {
			db.Close()
			log.Printf("WARN: db-table-sizes: ping %s (%s): %v", alias, host, err)
			continue
		}
		var rows []DbTableSize
		switch engine {
		case "mysql":
			rows, err = queryMySQLTableSizes(ctx, db, alias, host)
		case "postgres":
			rows, err = queryPostgresTableSizes(ctx, db, alias, host)
		}
		db.Close()
		if err != nil {
			log.Printf("WARN: db-table-sizes: %s (%s): %v", alias, host, err)
			continue
		}
		collected = append(collected, rows...)
		log.Printf("INFO: db-table-sizes: %s collected %d tables from %s", alias, len(rows), host)
	}
	return DataBatch{Collector: "db-table-sizes", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryMySQLTableSizes(ctx context.Context, db *sql.DB, alias, host string) ([]DbTableSize, error) {
	const q = `
SELECT
    TABLE_SCHEMA,
    TABLE_NAME,
    COALESCE(DATA_LENGTH, 0)   AS data_bytes,
    COALESCE(INDEX_LENGTH, 0)  AS index_bytes,
    COALESCE(DATA_LENGTH + INDEX_LENGTH, 0) AS total_bytes,
    COALESCE(TABLE_ROWS, 0)    AS row_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA NOT IN ('information_schema','performance_schema','mysql','sys')
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY total_bytes DESC
LIMIT ?`

	rows, err := db.QueryContext(ctx, q, tableSizeLimit)
	if err != nil {
		return nil, fmt.Errorf("query information_schema.TABLES: %w", err)
	}
	defer rows.Close()

	ts := time.Now().UTC().Format(time.RFC3339)
	var out []DbTableSize
	for rows.Next() {
		var t DbTableSize
		if err := rows.Scan(&t.SchemaName, &t.TableName, &t.DataSizeBytes, &t.IndexSizeBytes, &t.TotalSizeBytes, &t.Rows); err != nil {
			continue
		}
		t.DSNAlias = alias
		t.DBEngine = "mysql"
		t.DBHost = host
		t.CollectedAt = ts
		out = append(out, t)
	}
	return out, rows.Err()
}

func queryPostgresTableSizes(ctx context.Context, db *sql.DB, alias, host string) ([]DbTableSize, error) {
	rows, err := db.QueryContext(ctx, postgresTableSizesQuery, tableSizeLimit)
	if err != nil {
		return nil, fmt.Errorf("query pg table sizes: %w", err)
	}
	defer rows.Close()

	ts := time.Now().UTC().Format(time.RFC3339)
	var out []DbTableSize
	for rows.Next() {
		var t DbTableSize
		if err := rows.Scan(&t.SchemaName, &t.TableName, &t.DataSizeBytes, &t.IndexSizeBytes, &t.TotalSizeBytes, &t.Rows); err != nil {
			continue
		}
		t.DSNAlias = alias
		t.DBEngine = "postgres"
		t.DBHost = host
		t.CollectedAt = ts
		out = append(out, t)
	}
	return out, rows.Err()
}
