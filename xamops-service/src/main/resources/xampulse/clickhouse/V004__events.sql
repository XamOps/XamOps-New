-- XamPulse ClickHouse V004: events written by the platform (deploys, EC2 state, alerts, anomalies).
CREATE TABLE IF NOT EXISTS xp_events
(
    tenant_id UInt64,
    ts DateTime64(3, 'UTC'),
    kind LowCardinality(String),
    resource String,
    title String,
    detail String
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, ts, kind)
TTL toDateTime(ts) + toIntervalDay(90);
