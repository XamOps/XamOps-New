-- XamPulse ClickHouse V002: metrics (landing, points, rollups).
CREATE TABLE IF NOT EXISTS xp_land_metrics_gauge
(
    ResourceAttributes Map(LowCardinality(String), String),
    ResourceSchemaUrl String,
    ScopeName String,
    ScopeVersion String,
    ScopeAttributes Map(LowCardinality(String), String),
    ScopeDroppedAttrCount UInt32,
    ScopeSchemaUrl String,
    ServiceName LowCardinality(String),
    MetricName LowCardinality(String),
    MetricDescription String,
    MetricUnit String,
    Attributes Map(LowCardinality(String), String),
    StartTimeUnix DateTime64(9, 'UTC'),
    TimeUnix DateTime64(9, 'UTC'),
    Value Float64,
    Flags UInt32,
    `Exemplars.FilteredAttributes` Array(Map(LowCardinality(String), String)),
    `Exemplars.TimeUnix` Array(DateTime64(9, 'UTC')),
    `Exemplars.Value` Array(Float64),
    `Exemplars.SpanId` Array(String),
    `Exemplars.TraceId` Array(String)
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toDate(TimeUnix)
ORDER BY (MetricName, TimeUnix)
TTL toDateTime(TimeUnix) + toIntervalHour(2);

CREATE TABLE IF NOT EXISTS xp_land_metrics_sum
(
    ResourceAttributes Map(LowCardinality(String), String),
    ResourceSchemaUrl String,
    ScopeName String,
    ScopeVersion String,
    ScopeAttributes Map(LowCardinality(String), String),
    ScopeDroppedAttrCount UInt32,
    ScopeSchemaUrl String,
    ServiceName LowCardinality(String),
    MetricName LowCardinality(String),
    MetricDescription String,
    MetricUnit String,
    Attributes Map(LowCardinality(String), String),
    StartTimeUnix DateTime64(9, 'UTC'),
    TimeUnix DateTime64(9, 'UTC'),
    Value Float64,
    Flags UInt32,
    `Exemplars.FilteredAttributes` Array(Map(LowCardinality(String), String)),
    `Exemplars.TimeUnix` Array(DateTime64(9, 'UTC')),
    `Exemplars.Value` Array(Float64),
    `Exemplars.SpanId` Array(String),
    `Exemplars.TraceId` Array(String),
    AggregationTemporality Int32,
    IsMonotonic Bool
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toDate(TimeUnix)
ORDER BY (MetricName, TimeUnix)
TTL toDateTime(TimeUnix) + toIntervalHour(2);

CREATE TABLE IF NOT EXISTS xp_metric_points
(
    tenant_id UInt64,
    ts DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    metric LowCardinality(String),
    instance_id LowCardinality(String),
    labels Map(LowCardinality(String), String) CODEC(ZSTD(3)),
    value Float64 CODEC(Gorilla, ZSTD(1)),
    kind LowCardinality(String),
    retain_days UInt16 DEFAULT 14
)
ENGINE = {{REPLICATED}}MergeTree
PARTITION BY toDate(ts)
ORDER BY (tenant_id, metric, instance_id, ts)
TTL toDateTime(ts) + toIntervalDay(retain_days);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_land_metrics_gauge TO xp_metric_points AS
SELECT
    toUInt64OrZero(ResourceAttributes['xp.tenant_id']) AS tenant_id,
    toDateTime64(TimeUnix, 3, 'UTC') AS ts,
    MetricName AS metric,
    ResourceAttributes['xp.instance_id'] AS instance_id,
    Attributes AS labels,
    Value AS value,
    'GAUGE' AS kind,
    toUInt16OrDefault(ResourceAttributes['xp.retain_days'], toUInt16(14)) AS retain_days
FROM xp_land_metrics_gauge
WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0;

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_land_metrics_sum TO xp_metric_points AS
SELECT
    toUInt64OrZero(ResourceAttributes['xp.tenant_id']) AS tenant_id,
    toDateTime64(TimeUnix, 3, 'UTC') AS ts,
    MetricName AS metric,
    ResourceAttributes['xp.instance_id'] AS instance_id,
    Attributes AS labels,
    Value AS value,
    'COUNTER' AS kind,
    toUInt16OrDefault(ResourceAttributes['xp.retain_days'], toUInt16(14)) AS retain_days
FROM xp_land_metrics_sum
WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0;

CREATE TABLE IF NOT EXISTS xp_metrics_1m
(
    tenant_id UInt64,
    ts DateTime('UTC'),
    metric LowCardinality(String),
    instance_id LowCardinality(String),
    labels_key String,
    avg_state AggregateFunction(avg, Float64),
    min_state AggregateFunction(min, Float64),
    max_state AggregateFunction(max, Float64),
    sum_state AggregateFunction(sum, Float64),
    count_state AggregateFunction(count),
    q_state AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), Float64),
    last_state AggregateFunction(argMax, Float64, DateTime64(3, 'UTC'))
)
ENGINE = {{REPLICATED}}AggregatingMergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, metric, instance_id, labels_key, ts)
TTL ts + toIntervalDay(30);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_metrics_1m TO xp_metrics_1m AS
SELECT
    tenant_id,
    toStartOfMinute(ts) AS ts,
    metric,
    instance_id,
    arrayStringConcat(arrayMap(k -> concat(k, '=', labels[k]), arraySort(mapKeys(labels))), ',') AS labels_key,
    avgState(value) AS avg_state,
    minState(value) AS min_state,
    maxState(value) AS max_state,
    sumState(value) AS sum_state,
    countState() AS count_state,
    quantilesTDigestState(0.5, 0.95, 0.99)(value) AS q_state,
    argMaxState(value, xp_metric_points.ts) AS last_state
FROM xp_metric_points
GROUP BY tenant_id, ts, metric, instance_id, labels_key;

CREATE TABLE IF NOT EXISTS xp_metrics_5m
(
    tenant_id UInt64,
    ts DateTime('UTC'),
    metric LowCardinality(String),
    instance_id LowCardinality(String),
    labels_key String,
    avg_state AggregateFunction(avg, Float64),
    min_state AggregateFunction(min, Float64),
    max_state AggregateFunction(max, Float64),
    sum_state AggregateFunction(sum, Float64),
    count_state AggregateFunction(count),
    q_state AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), Float64),
    last_state AggregateFunction(argMax, Float64, DateTime64(3, 'UTC'))
)
ENGINE = {{REPLICATED}}AggregatingMergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, metric, instance_id, labels_key, ts)
TTL ts + toIntervalDay(90);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_metrics_5m TO xp_metrics_5m AS
SELECT
    tenant_id,
    toStartOfFiveMinutes(ts) AS ts,
    metric,
    instance_id,
    arrayStringConcat(arrayMap(k -> concat(k, '=', labels[k]), arraySort(mapKeys(labels))), ',') AS labels_key,
    avgState(value) AS avg_state,
    minState(value) AS min_state,
    maxState(value) AS max_state,
    sumState(value) AS sum_state,
    countState() AS count_state,
    quantilesTDigestState(0.5, 0.95, 0.99)(value) AS q_state,
    argMaxState(value, xp_metric_points.ts) AS last_state
FROM xp_metric_points
GROUP BY tenant_id, ts, metric, instance_id, labels_key;

CREATE TABLE IF NOT EXISTS xp_metrics_1h
(
    tenant_id UInt64,
    ts DateTime('UTC'),
    metric LowCardinality(String),
    instance_id LowCardinality(String),
    labels_key String,
    avg_state AggregateFunction(avg, Float64),
    min_state AggregateFunction(min, Float64),
    max_state AggregateFunction(max, Float64),
    sum_state AggregateFunction(sum, Float64),
    count_state AggregateFunction(count),
    q_state AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), Float64),
    last_state AggregateFunction(argMax, Float64, DateTime64(3, 'UTC'))
)
ENGINE = {{REPLICATED}}AggregatingMergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, metric, instance_id, labels_key, ts)
TTL ts + toIntervalDay(400);

CREATE MATERIALIZED VIEW IF NOT EXISTS xp_mv_metrics_1h TO xp_metrics_1h AS
SELECT
    tenant_id,
    toStartOfHour(ts) AS ts,
    metric,
    instance_id,
    arrayStringConcat(arrayMap(k -> concat(k, '=', labels[k]), arraySort(mapKeys(labels))), ',') AS labels_key,
    avgState(value) AS avg_state,
    minState(value) AS min_state,
    maxState(value) AS max_state,
    sumState(value) AS sum_state,
    countState() AS count_state,
    quantilesTDigestState(0.5, 0.95, 0.99)(value) AS q_state,
    argMaxState(value, xp_metric_points.ts) AS last_state
FROM xp_metric_points
GROUP BY tenant_id, ts, metric, instance_id, labels_key;
