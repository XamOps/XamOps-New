package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"
)

func collectDBWaitEvents(ctx context.Context, dsns []string) (DataBatch, error) {
	var events []DbWaitEvent
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			log.Printf("WARN: db-wait-events: DSN %d parse error: %v", i, err)
			continue
		}
		ev, err := queryWaitEvents(ctx, dsn, engine, alias, host)
		if err != nil {
			log.Printf("WARN: db-wait-events: %s (%s): %v", alias, host, err)
			continue
		}
		events = append(events, ev...)
	}
	return DataBatch{
		Collector:   "db-wait-events",
		Items:       events,
		Count:       len(events),
		CollectedAt: time.Now().UTC(),
	}, nil
}

func queryWaitEvents(ctx context.Context, dsn, engine, alias, host string) ([]DbWaitEvent, error) {
	db, err := sql.Open(engine, dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(10 * time.Second)

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}

	switch engine {
	case "postgres":
		return queryPostgresWaitEvents(ctx, db, alias, host)
	case "mysql":
		return queryMySQLWaitEvents(ctx, db, alias, host)
	default:
		return nil, fmt.Errorf("unsupported engine: %s", engine)
	}
}

func queryPostgresWaitEvents(ctx context.Context, db *sql.DB, alias, host string) ([]DbWaitEvent, error) {
	qCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if _, err := db.ExecContext(qCtx, "SET statement_timeout = '2s'"); err != nil {
		return nil, fmt.Errorf("set statement_timeout: %w", err)
	}

	const q = `
SELECT
	COALESCE(wait_event_type, 'CPU') AS wait_event_type,
	COALESCE(wait_event, 'CPU')      AS wait_event,
	count(*)                          AS session_count
FROM pg_stat_activity
WHERE state != 'idle'
  AND pid != pg_backend_pid()
GROUP BY wait_event_type, wait_event`

	rows, err := db.QueryContext(qCtx, q)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_activity: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	var events []DbWaitEvent
	for rows.Next() {
		var evType, evName string
		var count int
		if err := rows.Scan(&evType, &evName, &count); err != nil {
			continue
		}
		events = append(events, DbWaitEvent{
			DSNAlias:      alias,
			DBEngine:      "postgres",
			DBHost:        host,
			WaitCategory:  categorizePostgresWait(evType),
			WaitEventName: evName,
			SessionCount:  count,
			CollectedAt:   now,
		})
	}
	return events, rows.Err()
}

func queryMySQLWaitEvents(ctx context.Context, db *sql.DB, alias, host string) ([]DbWaitEvent, error) {
	qCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	if _, err := db.ExecContext(qCtx, "SET SESSION MAX_EXECUTION_TIME=2000"); err != nil {
		log.Printf("WARN: db-wait-events: could not set MAX_EXECUTION_TIME on %s: %v", host, err)
	}

	const q = `
SELECT
	event_name,
	SUM_NO_WAIT_COUNT + SUM_WAIT_WAITED_COUNT AS session_count
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE SUM_NO_WAIT_COUNT + SUM_WAIT_WAITED_COUNT > 0
  AND event_name NOT LIKE 'idle%'
ORDER BY session_count DESC
LIMIT 20`

	rows, err := db.QueryContext(qCtx, q)
	if err != nil {
		return nil, fmt.Errorf("query events_waits_summary: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	var events []DbWaitEvent
	for rows.Next() {
		var name string
		var count int64
		if err := rows.Scan(&name, &count); err != nil {
			continue
		}
		events = append(events, DbWaitEvent{
			DSNAlias:      alias,
			DBEngine:      "mysql",
			DBHost:        host,
			WaitCategory:  categorizeMySQLWait(name),
			WaitEventName: name,
			SessionCount:  int(count),
			CollectedAt:   now,
		})
	}
	return events, rows.Err()
}

func categorizePostgresWait(waitType string) string {
	switch strings.ToLower(waitType) {
	case "io":
		return "IO"
	case "lock", "lwlock":
		return "Lock"
	case "cpu", "":
		return "CPU"
	case "client", "network":
		return "Network"
	default:
		return "Other"
	}
}

func categorizeMySQLWait(eventName string) string {
	lower := strings.ToLower(eventName)
	switch {
	case strings.Contains(lower, "io/") || strings.Contains(lower, "file/"):
		return "IO"
	case strings.Contains(lower, "lock") || strings.Contains(lower, "mutex") || strings.Contains(lower, "rwlock"):
		return "Lock"
	case strings.Contains(lower, "network") || strings.Contains(lower, "socket"):
		return "Network"
	case strings.Contains(lower, "cpu"):
		return "CPU"
	default:
		return "Other"
	}
}
