-- XamPulse ClickHouse V001: logs.
-- {{REPLICATED}} is substituted by ClickHouseMigrationRunner ("" for single node, "Replicated" in production).
-- Gateway-written landing table: short TTL. Its columns must be exactly the ones the OTel collector clickhouse exporter
-- inserts (verified against otelcol-contrib 0.131.0 with create_schema=true); a missing column fails every export.
-- The gateway stamps xp.tenant_id / xp.instance_id / xp.retain_days from the authenticated headers.
CREATE TABLE IF NOT EXISTS xp_land_logs
(
    Timestamp DateTime64(9, 'UTC'),
    TraceId String,
    SpanId String,
    TraceFlags UInt8,
    SeverityText LowCardinality(String),
    SeverityNumber UInt8,
    ServiceName LowCardinality(String),
    Body String,
    ResourceSchemaUrl LowCardinality(String),
    ResourceAttributes Map(LowCardinality(String), String),
    ScopeSchemaUrl LowCardinality(String),
    ScopeName String,
    ScopeVersion LowCardinality(String),
    ScopeAttributes Map(LowCardinality(String), String),
    LogAttributes Map(LowCardinality(String), String)
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, Timestamp)
TTL toDateTime(Timestamp) + toIntervalHour(2);

CREATE TABLE IF NOT EXISTS xp_logs
(
    tenant_id UInt64,
    ts DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    ingest_ts DateTime64(3, 'UTC') DEFAULT now64(3) CODEC(Delta, ZSTD(1)),
    region LowCardinality(String),
    instance_id LowCardinality(String),
    service LowCardinality(String),
    source LowCardinality(String),
    container_id String,
    k8s_ns LowCardinality(String),
    k8s_pod String,
    severity Enum8('TRACE' = 1, 'DEBUG' = 2, 'INFO' = 3, 'WARN' = 4, 'ERROR' = 5, 'FATAL' = 6),
    body String CODEC(ZSTD(3)),
    tpl_hash UInt64 MATERIALIZED cityHash64(replaceRegexpAll(body, '[0-9a-fA-F-]{8,}|[0-9]+', '?')),
    trace_id String,
    span_id String,
    attrs Map(LowCardinality(String), String) CODEC(ZSTD(3)),
    retain_days UInt16 DEFAULT 14,
    INDEX idx_body body TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 1,
    INDEX idx_trace trace_id TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toDate(ts)
ORDER BY (tenant_id, service, instance_id, toStartOfHour(ts), ts)
TTL toDateTime(ts) + toIntervalDay(retain_days);

-- Rows without a gateway-stamped tenant are dropped here, never stored under tenant 0.
CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_land_logs TO xp_logs AS
SELECT
    toUInt64OrZero(ResourceAttributes['xp.tenant_id']) AS tenant_id,
    toDateTime64(Timestamp, 3, 'UTC') AS ts,
    ResourceAttributes['cloud.region'] AS region,
    ResourceAttributes['xp.instance_id'] AS instance_id,
    ServiceName AS service,
    ResourceAttributes['xp.source'] AS source,
    ResourceAttributes['container.id'] AS container_id,
    ResourceAttributes['k8s.namespace.name'] AS k8s_ns,
    ResourceAttributes['k8s.pod.name'] AS k8s_pod,
    multiIf(
        upper(SeverityText) IN ('FATAL', 'CRITICAL', 'EMERGENCY', 'ALERT') OR SeverityNumber >= 21, 'FATAL',
        upper(SeverityText) IN ('ERROR', 'ERR') OR SeverityNumber >= 17, 'ERROR',
        upper(SeverityText) IN ('WARN', 'WARNING') OR SeverityNumber >= 13, 'WARN',
        upper(SeverityText) = 'DEBUG' OR (SeverityNumber >= 5 AND SeverityNumber < 9), 'DEBUG',
        upper(SeverityText) = 'TRACE' OR (SeverityNumber >= 1 AND SeverityNumber < 5), 'TRACE',
        'INFO') AS severity,
    Body AS body,
    TraceId AS trace_id,
    SpanId AS span_id,
    mapUpdate(LogAttributes, map('xp.demo', ResourceAttributes['xp.demo'])) AS attrs,
    toUInt16OrDefault(ResourceAttributes['xp.retain_days'], toUInt16(14)) AS retain_days
FROM xp_land_logs
WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0;
