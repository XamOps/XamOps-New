package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"
)

// collectDBUserActivity collects per-user connection counts and cumulative query stats.
// Supports both PostgreSQL (pg_stat_activity + pg_stat_statements) and MySQL (PROCESSLIST + performance_schema.accounts).
func collectDBUserActivity(ctx context.Context, dsns []string) (DataBatch, error) {
	collected := make([]DbUserActivity, 0)
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		var rows []DbUserActivity
		var queryErr error
		switch engine {
		case "postgres":
			rows, queryErr = queryPostgresUserActivity(ctx, dsn, alias, host)
		case "mysql":
			rows, queryErr = queryMySQLUserActivity(ctx, dsn, alias, host)
		default:
			continue
		}
		if queryErr != nil {
			log.Printf("WARN: db-user-activity: %s (%s): %v", alias, host, queryErr)
			continue
		}
		collected = append(collected, rows...)
	}
	return DataBatch{Collector: "db-user-activity", Items: collected, Count: len(collected), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresUserActivity(ctx context.Context, dsn, alias, host string) ([]DbUserActivity, error) {
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
			usename,
			COUNT(*) FILTER (WHERE state = 'active')  AS active_conn,
			COUNT(*) FILTER (WHERE state = 'idle')    AS idle_conn,
			COUNT(*)                                  AS total_conn
		FROM pg_stat_activity
		WHERE usename IS NOT NULL
		GROUP BY usename
		ORDER BY total_conn DESC
	`)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_activity: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbUserActivity, 0)
	for rows.Next() {
		var u DbUserActivity
		u.DSNAlias = alias
		u.DBEngine = "postgres"
		u.DBHost = host
		u.CollectedAt = now
		if err := rows.Scan(&u.Username, &u.ActiveConnections, &u.IdleConnections, &u.TotalConnections); err != nil {
			continue
		}
		result = append(result, u)
	}

	// Enrich with per-user query stats from pg_stat_statements (best-effort)
	stmtRows, err := db.QueryContext(ctx, `
		SELECT usename, COALESCE(sum(calls), 0), COALESCE(sum(total_exec_time) / 1000.0, 0)
		FROM pg_stat_statements
		JOIN pg_user ON pg_stat_statements.userid = pg_user.usesysid
		GROUP BY usename
	`)
	if err == nil {
		defer stmtRows.Close()
		type statEntry struct {
			calls     int64
			totalTime float64
		}
		callMap := map[string]statEntry{}
		for stmtRows.Next() {
			var username string
			var calls int64
			var totalTime float64
			if err2 := stmtRows.Scan(&username, &calls, &totalTime); err2 == nil {
				callMap[username] = statEntry{calls, totalTime}
			}
		}
		for i := range result {
			if stats, ok := callMap[result[i].Username]; ok {
				result[i].CallCount = stats.calls
				result[i].TotalTimeSeconds = stats.totalTime
			}
		}
	}
	return result, nil
}

func queryMySQLUserActivity(ctx context.Context, dsn, alias, host string) ([]DbUserActivity, error) {
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
			USER,
			SUM(CASE WHEN COMMAND != 'Sleep' THEN 1 ELSE 0 END) AS active_conn,
			SUM(CASE WHEN COMMAND  = 'Sleep' THEN 1 ELSE 0 END) AS idle_conn,
			COUNT(*) AS total_conn
		FROM information_schema.PROCESSLIST
		WHERE USER != 'system user' AND USER != ''
		GROUP BY USER
		ORDER BY total_conn DESC
	`)
	if err != nil {
		return nil, fmt.Errorf("query PROCESSLIST: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	result := make([]DbUserActivity, 0)
	for rows.Next() {
		var u DbUserActivity
		u.DSNAlias = alias
		u.DBEngine = "mysql"
		u.DBHost = host
		u.CollectedAt = now
		if err := rows.Scan(&u.Username, &u.ActiveConnections, &u.IdleConnections, &u.TotalConnections); err != nil {
			continue
		}
		result = append(result, u)
	}

	// Enrich with cumulative exec time from performance_schema.accounts (best-effort)
	acctRows, err := db.QueryContext(ctx, `
		SELECT USER, COALESCE(SUM_TIMER_WAIT / 1e12, 0)
		FROM performance_schema.accounts
		WHERE USER IS NOT NULL
	`)
	if err == nil {
		defer acctRows.Close()
		timeMap := map[string]float64{}
		for acctRows.Next() {
			var username string
			var totalTime float64
			if err2 := acctRows.Scan(&username, &totalTime); err2 == nil {
				timeMap[username] = totalTime
			}
		}
		for i := range result {
			if t, ok := timeMap[result[i].Username]; ok {
				result[i].TotalTimeSeconds = t
			}
		}
	}
	return result, nil
}
