-- Remove the three enabled-toggle columns from observability_config.
-- OpenObserve has no concept of "disabled" stream types — whether logs/metrics/traces
-- flow is controlled entirely by what the agent sends, not a DB flag.
-- Only retention_days is kept as it maps to a real O2 stream setting.

ALTER TABLE observability_config
    DROP COLUMN IF EXISTS logs_enabled,
    DROP COLUMN IF EXISTS metrics_enabled,
    DROP COLUMN IF EXISTS traces_enabled;
