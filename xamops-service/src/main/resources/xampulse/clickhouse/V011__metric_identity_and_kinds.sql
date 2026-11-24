-- XamPulse ClickHouse V011: metric identity labels, counter kinds, and process metrics out of the long rollups.
-- V001 to V010 are never edited. Each view keeps its name; ALTER ... MODIFY QUERY swaps its SELECT in place, so no
-- insert is lost and none is doubled while the change applies (verified on clickhouse-server 25.8.33.6, the pinned
-- version). Re-running the statement sets the same query again, so replaying every script stays safe.
--
-- 1. labels. The collector sends container and process identity as RESOURCE attributes (docker_stats:
--    container.id, container.name, container.image.name; hostmetrics process scraper: process.pid,
--    process.executable.name, process.owner). V002 copied only datapoint attributes, so every container and every
--    process on a host fell into one series and the Containers tab and Top processes found no ids. The identity keys
--    below are merged in; datapoint attributes win on a clash. process.command_line is left out on purpose: it is
--    unbounded and command lines often carry credentials.
-- 2. kind. An OTel Sum is a counter only when IsMonotonic. Memory and filesystem usage, connection counts and process
--    counts are non-monotonic sums (current values), so they are GAUGE. AggregationTemporality 1 is DELTA, 2 is
--    CUMULATIVE; the agent only sends cumulative counters today.
-- 3. process.* metrics are kept only in xp_metric_points (retain_days, default 14). With the pid in labels they would
--    add one series per process to the 30, 90 and 400 day rollups. Top processes reads xp_metric_points.

ALTER TABLE xp_mv_land_metrics_gauge MODIFY QUERY
SELECT
    toUInt64OrZero(ResourceAttributes['xp.tenant_id']) AS tenant_id,
    toDateTime64(TimeUnix, 3, 'UTC') AS ts,
    MetricName AS metric,
    ResourceAttributes['xp.instance_id'] AS instance_id,
    mapUpdate(mapFilter((k, v) -> k IN ('container.id', 'container.name', 'container.image.name', 'process.pid', 'process.executable.name', 'process.owner'), ResourceAttributes), Attributes) AS labels,
    Value AS value,
    'GAUGE' AS kind,
    toUInt16OrDefault(ResourceAttributes['xp.retain_days'], toUInt16(14)) AS retain_days
FROM xp_land_metrics_gauge
WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0;

ALTER TABLE xp_mv_land_metrics_sum MODIFY QUERY
SELECT
    toUInt64OrZero(ResourceAttributes['xp.tenant_id']) AS tenant_id,
    toDateTime64(TimeUnix, 3, 'UTC') AS ts,
    MetricName AS metric,
    ResourceAttributes['xp.instance_id'] AS instance_id,
    mapUpdate(mapFilter((k, v) -> k IN ('container.id', 'container.name', 'container.image.name', 'process.pid', 'process.executable.name', 'process.owner'), ResourceAttributes), Attributes) AS labels,
    Value AS value,
    multiIf(NOT IsMonotonic, 'GAUGE', AggregationTemporality = 1, 'DELTA', 'COUNTER') AS kind,
    toUInt16OrDefault(ResourceAttributes['xp.retain_days'], toUInt16(14)) AS retain_days
FROM xp_land_metrics_sum
WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0;

ALTER TABLE xp_mv_metrics_1m MODIFY QUERY
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
WHERE NOT startsWith(metric, 'process.')
GROUP BY tenant_id, ts, metric, instance_id, labels_key;

ALTER TABLE xp_mv_metrics_5m MODIFY QUERY
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
WHERE NOT startsWith(metric, 'process.')
GROUP BY tenant_id, ts, metric, instance_id, labels_key;

ALTER TABLE xp_mv_metrics_1h MODIFY QUERY
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
WHERE NOT startsWith(metric, 'process.')
GROUP BY tenant_id, ts, metric, instance_id, labels_key;
