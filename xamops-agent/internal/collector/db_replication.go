package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"

	_ "github.com/lib/pq"
)

// collectDBReplication collects replication role and lag for every Postgres DSN (COL-A3).
//
// A primary reports one row per connected standby from pg_stat_replication (write/flush/replay lag
// in seconds, standby state). A replica reports a single row from pg_last_wal_replay_lsn() /
// pg_last_xact_replay_timestamp(). Role is labelled from pg_is_in_recovery(). A standalone primary
// with no standbys emits a single primary row with null lags, so the health view can say
// "no standby" rather than rendering nothing.
func collectDBReplication(ctx context.Context, dsns []string) (DataBatch, error) {
	var rows []DbReplication
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		if engine != "postgres" {
			continue
		}
		r, err := queryPostgresReplication(ctx, dsn, alias, host)
		if err != nil {
			log.Printf("WARN: db-replication: %s (%s): %v", alias, host, err)
			continue
		}
		rows = append(rows, r...)
	}
	return DataBatch{Collector: "db-replication", Items: rows, Count: len(rows), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresReplication(ctx context.Context, dsn, alias, host string) ([]DbReplication, error) {
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
	if _, err := db.ExecContext(qCtx, "SET statement_timeout = '2s'"); err != nil {
		return nil, fmt.Errorf("set statement_timeout: %w", err)
	}

	var inRecovery bool
	if err := db.QueryRowContext(qCtx, "SELECT pg_is_in_recovery()").Scan(&inRecovery); err != nil {
		return nil, fmt.Errorf("pg_is_in_recovery: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339)
	if inRecovery {
		return queryReplicaReplication(qCtx, db, alias, host, now)
	}
	return queryPrimaryReplication(qCtx, db, alias, host, now)
}

func queryPrimaryReplication(ctx context.Context, db *sql.DB, alias, host, now string) ([]DbReplication, error) {
	rows, err := db.QueryContext(ctx, postgresPrimaryReplicationQuery)
	if err != nil {
		return nil, fmt.Errorf("query pg_stat_replication: %w", err)
	}
	defer rows.Close()

	var out []DbReplication
	for rows.Next() {
		var r DbReplication
		var clientAddr, appName, state sql.NullString
		var writeLag, flushLag, replayLag sql.NullFloat64
		if err := rows.Scan(&clientAddr, &appName, &state, &writeLag, &flushLag, &replayLag); err != nil {
			continue
		}
		r.DSNAlias, r.DBEngine, r.DBHost, r.Role, r.CollectedAt = alias, "postgres", host, "primary", now
		r.ClientAddr = clientAddr.String
		r.ApplicationName = appName.String
		r.State = state.String
		r.WriteLagSeconds = nullFloatPtr(writeLag)
		r.FlushLagSeconds = nullFloatPtr(flushLag)
		r.ReplayLagSeconds = nullFloatPtr(replayLag)
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// A standalone primary (no standbys) still reports its role so the view can say "no standby".
	if len(out) == 0 {
		out = append(out, DbReplication{
			DSNAlias: alias, DBEngine: "postgres", DBHost: host, Role: "primary", CollectedAt: now,
		})
	}
	return out, nil
}

func queryReplicaReplication(ctx context.Context, db *sql.DB, alias, host, now string) ([]DbReplication, error) {
	r := DbReplication{DSNAlias: alias, DBEngine: "postgres", DBHost: host, Role: "replica", CollectedAt: now}
	var replayLag sql.NullFloat64
	var lastReplay sql.NullString
	// Lag = now() - last applied commit timestamp; null when the replica has replayed nothing yet.
	if err := db.QueryRowContext(ctx, postgresReplicaReplicationQuery).Scan(&replayLag, &lastReplay); err != nil {
		return nil, fmt.Errorf("query replica replay: %w", err)
	}
	r.ReplayLagSeconds = nullFloatPtr(replayLag)
	r.LastReplayTime = lastReplay.String
	return []DbReplication{r}, nil
}

func nullFloatPtr(v sql.NullFloat64) *float64 {
	if !v.Valid {
		return nil
	}
	f := v.Float64
	return &f
}

const postgresPrimaryReplicationQuery = `
SELECT
	COALESCE(client_addr::text, '')                 AS client_addr,
	COALESCE(application_name, '')                  AS application_name,
	COALESCE(state, '')                             AS state,
	EXTRACT(EPOCH FROM write_lag)::float8           AS write_lag_seconds,
	EXTRACT(EPOCH FROM flush_lag)::float8           AS flush_lag_seconds,
	EXTRACT(EPOCH FROM replay_lag)::float8          AS replay_lag_seconds
FROM pg_stat_replication`

const postgresReplicaReplicationQuery = `
SELECT
	CASE WHEN pg_last_xact_replay_timestamp() IS NULL THEN NULL
	     ELSE EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))::float8 END AS replay_lag_seconds,
	COALESCE(to_char(pg_last_xact_replay_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS last_replay_time`
