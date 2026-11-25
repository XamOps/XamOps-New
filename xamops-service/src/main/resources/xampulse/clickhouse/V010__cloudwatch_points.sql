-- XamPulse ClickHouse V010: CloudWatch datapoints fetched on demand (the Refresh button on the EC2 instance page).
-- V001 to V009 are never edited. Holds no credentials.
--
-- Nothing polls CloudWatch in the background (decision 2026-11-10: the customer pays per GetMetricData metric, so
-- data is fetched only when a user asks). Every fetch is kept here so history accumulates and past fetches can be
-- viewed again without another CloudWatch call.
--
-- ReplacingMergeTree keyed on (tenant, instance, metric, dimension, ts): refreshes that overlap the same window
-- replace rather than duplicate points (the newest fetched_at wins). Readers query with FINAL.
-- dimension is '' for instance-level metrics (AWS/EC2) and the volume id for AWS/EBS metrics.
-- 63 days matches how long CloudWatch itself keeps 5-minute data.

CREATE TABLE IF NOT EXISTS xp_cloudwatch_points
(
    tenant_id UInt64,
    instance_id LowCardinality(String),
    metric LowCardinality(String),
    dimension String,
    ts DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    value Float64 CODEC(Gorilla, ZSTD(1)),
    fetched_at DateTime64(3, 'UTC')
)
ENGINE = {{REPLICATED}}ReplacingMergeTree(fetched_at)
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, instance_id, metric, dimension, ts)
TTL toDateTime(ts) + toIntervalDay(63);

GRANT SELECT ON xp_cloudwatch_points TO xp_reader_role;
GRANT INSERT, SELECT ON xp_cloudwatch_points TO xp_writer_role;

CREATE ROW POLICY IF NOT EXISTS xp_tenant_cloudwatch_points ON xp_cloudwatch_points
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
