-- XamPulse ClickHouse V005: log volume, pattern map, baselines, host health.
CREATE TABLE IF NOT EXISTS xp_log_volume_1m
(
    tenant_id UInt64,
    minute DateTime('UTC'),
    service LowCardinality(String),
    instance_id LowCardinality(String),
    severity LowCardinality(String),
    rows UInt64,
    bytes UInt64
)
ENGINE = {{REPLICATED}}SummingMergeTree((rows, bytes))
PARTITION BY toYYYYMM(minute)
ORDER BY (tenant_id, minute, service, instance_id, severity)
TTL minute + toIntervalDay(90);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_log_volume_1m TO xp_log_volume_1m AS
SELECT
    tenant_id,
    toStartOfMinute(ts) AS minute,
    service,
    instance_id,
    toString(severity) AS severity,
    count() AS rows,
    sum(length(body)) AS bytes
FROM xp_logs
GROUP BY tenant_id, minute, service, instance_id, severity;

CREATE TABLE IF NOT EXISTS xp_pattern_map
(
    tenant_id UInt64,
    tpl_hash UInt64,
    pattern_id String,
    template SimpleAggregateFunction(any, String),
    first_seen SimpleAggregateFunction(min, DateTime64(3, 'UTC'))
)
ENGINE = {{REPLICATED}}AggregatingMergeTree
ORDER BY (tenant_id, tpl_hash, pattern_id)
TTL toDateTime(first_seen) + toIntervalDay(90);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_pattern_map TO xp_pattern_map AS
SELECT
    tenant_id,
    tpl_hash,
    hex(tpl_hash) AS pattern_id,
    any(substring(replaceRegexpAll(body, '[0-9a-fA-F-]{8,}|[0-9]+', '?'), 1, 300)) AS template,
    min(ts) AS first_seen
FROM xp_logs
GROUP BY tenant_id, tpl_hash, pattern_id;

CREATE TABLE IF NOT EXISTS xp_baseline
(
    tenant_id UInt64,
    metric LowCardinality(String),
    instance_id LowCardinality(String),
    hour_of_week UInt8,
    med Float64,
    mad Float64,
    updated_at DateTime('UTC')
)
ENGINE = {{REPLICATED}}ReplacingMergeTree(updated_at)
ORDER BY (tenant_id, metric, instance_id, hour_of_week);

CREATE TABLE IF NOT EXISTS xp_health_1m
(
    tenant_id UInt64,
    minute DateTime('UTC'),
    instance_id LowCardinality(String),
    score Nullable(UInt8),
    lanes String
)
ENGINE = {{REPLICATED}}ReplacingMergeTree
PARTITION BY toYYYYMM(minute)
ORDER BY (tenant_id, instance_id, minute)
TTL minute + toIntervalDay(30);
