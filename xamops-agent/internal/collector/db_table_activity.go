package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

// collectDBTableActivity collects read/write activity counters per table.
// Postgres: pg_stat_user_tables. MySQL: performance_schema.table_io_waits_summary_by_table.
func collectDBTableActivity(ctx context.Context, dsns []string) (DataBatch, error) {
	collected := make([]DbTableActivity, 0)
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		var rows []DbTableActivity
		var queryErr error
		switch engine {
		case "postgres":
			rows, queryErr = queryPostgresTableActivity(ctx, dsn, alias, host)
		case "mysql":
			rows, queryErr = queryMySQLTableActivity(ctx, dsn, alias, host)
		default:
			continue
		}
		if queryErr != nil {
			log.Printf("WARN: db-table-activity: %s (%s): %v", alias, host, queryErr)
			continue
		}
		collected = append(collected, rows...)
	}
	return DataBatch{Collector: "db-table-activity", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresTableActivity(ctx context.Context, dsn, alias, host string) ([]DbTableActivity, error) {
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

	rows, err := db.QueryContext(ctx, `
		SELECT
			schemaname,
			relname,
			COALESCE(seq_scan, 0),
			COALESCE(seq_tup_read, 0),
			COALESCE(idx_scan, 0),
			COALESCE(idx_tup_fetch, 0),
			COALESCE(n_tup_ins, 0),
			COALESCE(n_tup_upd, 0),
			COALESCE(n_tup_del, 0),
			COALESCE(n_tup_hot_upd, 0)
		FROM pg_stat_user_tables
		ORDER BY (COALESCE(seq_scan,0) + COALESCE(idx_scan,0) + COALESCE(n_tup_ins,0) + COALESCE(n_tup_upd,0) + COALESCE(n_tup_del,0)) DESC
		LIMIT 100
	`)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_user_tables: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbTableActivity, 0)
	for rows.Next() {
		var t DbTableActivity
		t.DSNAlias = alias
		t.DBEngine = "postgres"
		t.DBHost = host
		t.CollectedAt = now
		if err := rows.Scan(
			&t.SchemaName, &t.TableName,
			&t.SeqScan, &t.SeqTupRead,
			&t.IdxScan, &t.IdxTupFetch,
			&t.NTupIns, &t.NTupUpd, &t.NTupDel, &t.NTupHotUpd,
		); err != nil {
			continue
		}
		result = append(result, t)
	}
	return result, nil
}

func queryMySQLTableActivity(ctx context.Context, dsn, alias, host string) ([]DbTableActivity, error) {
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
			COALESCE(COUNT_READ,  0),
			COALESCE(COUNT_WRITE, 0),
			COALESCE(COUNT_FETCH, 0),
			COALESCE(COUNT_INSERT,0),
			COALESCE(COUNT_UPDATE,0),
			COALESCE(COUNT_DELETE,0)
		FROM performance_schema.table_io_waits_summary_by_table
		WHERE OBJECT_SCHEMA NOT IN ('mysql','performance_schema','information_schema','sys')
		ORDER BY (COUNT_READ + COUNT_WRITE) DESC
		LIMIT 100
	`)
	if err != nil {
		return nil, fmt.Errorf("query table_io_waits_summary_by_table: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbTableActivity, 0)
	for rows.Next() {
		var t DbTableActivity
		t.DSNAlias = alias
		t.DBEngine = "mysql"
		t.DBHost = host
		t.CollectedAt = now
		var countRead, countWrite, countFetch int64
		if err := rows.Scan(
			&t.SchemaName, &t.TableName,
			&countRead, &countWrite, &countFetch,
			&t.NTupIns, &t.NTupUpd, &t.NTupDel,
		); err != nil {
			continue
		}
		// Map MySQL counters to the shared fields
		t.SeqScan = countRead   // total read IOs (proxy for table access)
		t.IdxScan = countFetch  // fetch IOs (index-driven reads)
		result = append(result, t)
	}
	return result, nil
}
