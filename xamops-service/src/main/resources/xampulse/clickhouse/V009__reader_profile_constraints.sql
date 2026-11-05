-- XamPulse ClickHouse V009: constrain the reader settings profile created in V006. Idempotent (ALTER ... IF EXISTS);
-- V001 to V008 are never edited. Holds no credentials.
--
-- What this does: with readonly = 2 the reader session may change settings, so every limit it could raise is now
-- bounded by a constraint. max_execution_time and max_rows_to_read cannot exceed the values the executor sends,
-- max_result_rows and max_memory_usage are immutable, and SQL_xp_tenant stays settable (the executor sets it on
-- every statement; the row policies from V007 read it).
--
-- Honest limit: a reader session can still set SQL_xp_tenant, because the executor must. Tenant isolation therefore
-- rests on TenantScopedQueryBuilder plus ClickHouseExecutor.assertSafe (rejects ';', SET and SETTINGS in statements).
-- This profile only removes every other lever. Per-tenant ClickHouse users are the real fix (go-live design item).
--
-- Follow-up, not applied here because it is unverified against clickhouse-jdbc 0.11.0 (see deploy/xampulse/README.md):
--   ALTER SETTINGS PROFILE IF EXISTS xp_reader_profile SETTINGS readonly = 1;
-- Requires <custom_settings_prefixes>SQL_xp_</custom_settings_prefixes> (or the broader SQL_) in the server config.
ALTER SETTINGS PROFILE IF EXISTS xp_reader_profile
    SETTINGS readonly = 2,
             max_execution_time = 30 MAX 30,
             max_rows_to_read = 2000000000 MAX 2000000000,
             max_result_rows = 100000 READONLY,
             max_memory_usage = 4000000000 READONLY,
             SQL_xp_tenant = '0';
