-- XamPulse ClickHouse V006: roles, settings profile and quota. Users themselves are created by the
-- deployment (users.d), never here, so this file holds no credentials.
-- Requires <custom_settings_prefixes>SQL_xp_</custom_settings_prefixes> in the server config.
CREATE ROLE IF NOT EXISTS xp_reader_role;
CREATE ROLE IF NOT EXISTS xp_writer_role;

GRANT SELECT ON xp_logs TO xp_reader_role;
GRANT SELECT ON xp_metric_points TO xp_reader_role;
GRANT SELECT ON xp_metrics_1m TO xp_reader_role;
GRANT SELECT ON xp_metrics_5m TO xp_reader_role;
GRANT SELECT ON xp_metrics_1h TO xp_reader_role;
GRANT SELECT ON xp_events TO xp_reader_role;
GRANT SELECT ON xp_log_volume_1m TO xp_reader_role;
GRANT SELECT ON xp_pattern_map TO xp_reader_role;
GRANT SELECT ON xp_baseline TO xp_reader_role;
GRANT SELECT ON xp_health_1m TO xp_reader_role;

GRANT INSERT, SELECT ON xp_events TO xp_writer_role;
GRANT INSERT, SELECT ON xp_baseline TO xp_writer_role;
GRANT INSERT, SELECT ON xp_health_1m TO xp_writer_role;
GRANT INSERT, SELECT ON xp_logs TO xp_writer_role;
GRANT INSERT, SELECT ON xp_metric_points TO xp_writer_role;

-- The reader profile pins SQL_xp_tenant to 0 by default (matches no tenant) and bounds every query.
CREATE SETTINGS PROFILE IF NOT EXISTS xp_reader_profile
    SETTINGS readonly = 2, max_execution_time = 30, max_rows_to_read = 2000000000,
             max_result_rows = 100000, max_memory_usage = 4000000000, SQL_xp_tenant = '0'
    TO xp_reader_role;

CREATE QUOTA IF NOT EXISTS xp_reader_quota
    FOR INTERVAL 1 minute MAX queries = 600
    TO xp_reader_role;
