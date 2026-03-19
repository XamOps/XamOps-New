package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

// collectDBVacuumStats collects vacuum/analyze history and dead tuple ratios
// from pg_stat_user_tables. PostgreSQL only — MySQL DSNs are skipped.
func collectDBVacuumStats(ctx context.Context, dsns []string) (DataBatch, error) {
	collected := make([]DbVacuumStat, 0)
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		if engine != "postgres" {
			continue
		}
		rows, err := queryVacuumStats(ctx, dsn, alias, host)
		if err != nil {
			log.Printf("WARN: db-vacuum-stats: %s (%s): %v", alias, host, err)
			continue
		}
		collected = append(collected, rows...)
	}
	return DataBatch{Collector: "db-vacuum-stats", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryVacuumStats(ctx context.Context, dsn, alias, host string) ([]DbVacuumStat, error) {
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
			n_live_tup,
			n_dead_tup,
			CASE WHEN (n_live_tup + n_dead_tup) > 0
				THEN n_dead_tup::float / (n_live_tup + n_dead_tup)
				ELSE 0.0
			END AS dead_tup_ratio,
			COALESCE(to_char(last_vacuum,      'YYYY-MM-DD"T"HH24:MI:SS'), ''),
			COALESCE(to_char(last_autovacuum,  'YYYY-MM-DD"T"HH24:MI:SS'), ''),
			COALESCE(to_char(last_analyze,     'YYYY-MM-DD"T"HH24:MI:SS'), ''),
			COALESCE(to_char(last_autoanalyze, 'YYYY-MM-DD"T"HH24:MI:SS'), '')
		FROM pg_stat_user_tables
		ORDER BY n_dead_tup DESC
		LIMIT 100
	`)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_user_tables: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbVacuumStat, 0)
	for rows.Next() {
		var s DbVacuumStat
		s.DSNAlias = alias
		s.DBEngine = "postgres"
		s.DBHost = host
		s.CollectedAt = now
		if err := rows.Scan(
			&s.SchemaName, &s.TableName,
			&s.NLiveTup, &s.NDeadTup, &s.DeadTupRatio,
			&s.LastVacuum, &s.LastAutoVacuum,
			&s.LastAnalyze, &s.LastAutoAnalyze,
		); err != nil {
			continue
		}
		result = append(result, s)
	}
	return result, nil
}
