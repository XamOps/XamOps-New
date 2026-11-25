-- XamPulse ClickHouse V007: row policies. The reader sees only rows whose tenant_id equals the
-- SQL_xp_tenant setting of the session. toUInt64OrZero turns an empty/unset value into tenant 0, which
-- matches no stored row (the ingest views drop tenant 0), so a missing setting returns zero rows.
CREATE ROW POLICY IF NOT EXISTS xp_tenant_logs ON xp_logs
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_metric_points ON xp_metric_points
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_metrics_1m ON xp_metrics_1m
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_metrics_5m ON xp_metrics_5m
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_metrics_1h ON xp_metrics_1h
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_events ON xp_events
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_log_volume_1m ON xp_log_volume_1m
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_pattern_map ON xp_pattern_map
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_baseline ON xp_baseline
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
CREATE ROW POLICY IF NOT EXISTS xp_tenant_health_1m ON xp_health_1m
    USING tenant_id = toUInt64OrZero(getSetting('SQL_xp_tenant')) AS permissive TO xp_reader_role;
