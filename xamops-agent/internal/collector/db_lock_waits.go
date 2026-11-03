package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"

	_ "github.com/lib/pq"
)

// collectDBLockWaits collects blocking / blocked / long-running / idle-in-transaction sessions for
// every Postgres DSN (COL-A2). MySQL is not probed here — lock/long-txn visibility is a Postgres
// cut in Phase 2 (clean extension point for MySQL later).
//
// SEC invariant: the query pins ONLY session identity, wait and age columns — it never selects
// pg_stat_activity.query (the statement text). longXactSecs is the single collector-side threshold
// (DB_LONGXACT_SECS) that bounds which long-running / idle-in-transaction sessions are emitted; the
// health service classifies severity from the same value so emit-filter and classification agree.
func collectDBLockWaits(ctx context.Context, dsns []string, longXactSecs int) (DataBatch, error) {
	var rows []DbLockWait
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		if engine != "postgres" {
			continue
		}
		r, err := queryPostgresLockWaits(ctx, dsn, alias, host, longXactSecs)
		if err != nil {
			log.Printf("WARN: db-lock-waits: %s (%s): %v", alias, host, err)
			continue
		}
		rows = append(rows, r...)
	}
	return DataBatch{Collector: "db-lock-waits", Items: rows, Count: len(rows), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresLockWaits(ctx context.Context, dsn, alias, host string, longXactSecs int) ([]DbLockWait, error) {
	db, err := sql.Open("postgres", dsn)
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

	qCtx, cancel2 := context.WithTimeout(ctx, 4*time.Second)
	defer cancel2()
	// Own-connection guards (the db_wait_events.go precedent): a stalled catalog read or a lock
	// cannot burn the cycle — this collector bounds itself to 2s of execution and 1s of lock wait.
	if _, err := db.ExecContext(qCtx, "SET statement_timeout = '2s'"); err != nil {
		return nil, fmt.Errorf("set statement_timeout: %w", err)
	}
	if _, err := db.ExecContext(qCtx, "SET lock_timeout = '1s'"); err != nil {
		return nil, fmt.Errorf("set lock_timeout: %w", err)
	}

	var versionNum int
	_ = db.QueryRowContext(qCtx, "SELECT current_setting('server_version_num')::int").Scan(&versionNum)

	rows, err := db.QueryContext(qCtx, postgresLockWaitsQuery(versionNum), longXactSecs)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_activity lock waits: %w", err)
	}
	defer rows.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	var out []DbLockWait
	blockers := map[int]bool{} // pids referenced as blockers by some blocked session
	for rows.Next() {
		var w DbLockWait
		if err := rows.Scan(
			&w.PID, &w.Username, &w.ApplicationName, &w.State,
			&w.WaitEventType, &w.WaitEvent, &w.BackendXid,
			&w.XactStart, &w.QueryStart, &w.StateChange,
			&w.XactAgeSeconds, &w.BlockedByPids, &w.QueryID,
		); err != nil {
			continue
		}
		w.DSNAlias = alias
		w.DBEngine = "postgres"
		w.DBHost = host
		w.CollectedAt = now
		if w.BlockedByPids != "" {
			for _, p := range splitPids(w.BlockedByPids) {
				blockers[p] = true
			}
		}
		out = append(out, w)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	// Categorize from the identity/wait/age fields only (no query text involved).
	for i := range out {
		out[i].Category = classifyLockWait(out[i], blockers)
	}
	return out, nil
}

// classifyLockWait derives the lock-wait category from identity/wait/age fields. Precedence:
// blocked (waiting on another session) > blocking (its pid blocks a waiter) > idle_in_transaction >
// long_running.
func classifyLockWait(w DbLockWait, blockers map[int]bool) string {
	switch {
	case w.BlockedByPids != "":
		return "blocked"
	case blockers[w.PID]:
		return "blocking"
	case w.State == "idle in transaction":
		return "idle_in_transaction"
	default:
		return "long_running"
	}
}

func splitPids(csv string) []int {
	var out []int
	cur := 0
	have := false
	for _, r := range csv {
		if r >= '0' && r <= '9' {
			cur = cur*10 + int(r-'0')
			have = true
		} else if have {
			out = append(out, cur)
			cur, have = 0, false
		}
	}
	if have {
		out = append(out, cur)
	}
	return out
}

// postgresLockWaitsQuery pins only identity, wait and age columns — never a.query (the statement
// text). $1 is the DB_LONGXACT_SECS threshold in seconds; it bounds the long-running /
// idle-in-transaction rows the collector emits. pg_stat_activity.query_id exists only on PG14+, so
// it is selected there and emitted as '' below that (the statement text is never read on any version).
func postgresLockWaitsQuery(versionNum int) string {
	queryIDCol := `'' AS query_id`
	if versionNum == 0 || versionNum >= 140000 {
		queryIDCol = `COALESCE(a.query_id::text, '') AS query_id`
	}
	return `
SELECT
	a.pid,
	COALESCE(a.usename, '')                                                             AS usename,
	COALESCE(a.application_name, '')                                                    AS application_name,
	COALESCE(a.state, '')                                                               AS state,
	COALESCE(a.wait_event_type, '')                                                     AS wait_event_type,
	COALESCE(a.wait_event, '')                                                          AS wait_event,
	COALESCE(a.backend_xid::text, '')                                                   AS backend_xid,
	COALESCE(to_char(a.xact_start   AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS xact_start,
	COALESCE(to_char(a.query_start  AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS query_start,
	COALESCE(to_char(a.state_change AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS state_change,
	COALESCE(EXTRACT(EPOCH FROM (now() - a.xact_start)), 0)::float8                      AS xact_age_seconds,
	COALESCE(array_to_string(pg_blocking_pids(a.pid), ','), '')                          AS blocked_by_pids,
	` + queryIDCol + `
FROM pg_stat_activity a
WHERE a.pid <> pg_backend_pid()
  AND (
        cardinality(pg_blocking_pids(a.pid)) > 0
     OR a.pid IN (SELECT unnest(pg_blocking_pids(b.pid)) FROM pg_stat_activity b)
     OR (a.state = 'active'              AND a.xact_start IS NOT NULL AND now() - a.xact_start >= make_interval(secs => $1))
     OR (a.state = 'idle in transaction' AND a.xact_start IS NOT NULL AND now() - a.xact_start >= make_interval(secs => $1))
  )`
}
