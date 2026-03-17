package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

const tableSizeLimit = 50 // top N tables by total size

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
	const q = `
SELECT
    schemaname,
    tablename,
    pg_table_size(schemaname || '.' || tablename)         AS data_bytes,
    pg_indexes_size(schemaname || '.' || tablename)       AS index_bytes,
    pg_total_relation_size(schemaname || '.' || tablename) AS total_bytes,
    COALESCE(n_live_tup, 0)                               AS row_count
FROM pg_stat_user_tables
ORDER BY total_bytes DESC
LIMIT $1`

	rows, err := db.QueryContext(ctx, q, tableSizeLimit)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_user_tables: %w", err)
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
