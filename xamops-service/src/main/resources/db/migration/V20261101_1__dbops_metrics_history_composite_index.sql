-- PERF-5: composite index for the DbOps agent timeseries read.
--
-- getAgentTimeseries reads db_agent_metrics_history with
--   findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc
--   => WHERE db_agent_id = ? AND data_type = ? AND collected_at > ? ORDER BY collected_at ASC
-- The table only had three single-column indexes (db_agent_id, data_type, collected_at), so
-- Postgres could use at most one branch of that predicate and then filter/sort the rest of the
-- agent's rows. This composite index covers the whole predicate and the ORDER BY in one range
-- scan, which matters as the append-only history grows between purges.
--
-- IF NOT EXISTS so a re-run is a no-op; NOT CONCURRENTLY because Flyway runs each migration inside
-- a transaction (CREATE INDEX CONCURRENTLY cannot run in a transaction block). The matching
-- @Index is also declared on the DbAgentMetricsHistory entity so ddl-auto and the H2 test schema
-- carry it too.
CREATE INDEX IF NOT EXISTS idx_db_metrics_hist_agent_type_collected
    ON db_agent_metrics_history (db_agent_id, data_type, collected_at);
