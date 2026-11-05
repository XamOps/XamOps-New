# 06 — Data Model and API Contract

Single source of truth for table names, DTOs, endpoint paths, the query language and the live-tail protocol. 04 (backend), 05 (frontend) and 07 (UI) must use these names verbatim.

## 1. Conventions

- Base path `/api/obs/v1`. UI endpoints use the existing session (cookie + `X-XSRF-TOKEN` on mutating calls via `apiClient`). Machine endpoints: `/agent/**` (header `X-XamOps-Ingest-Token` or, for register, the enrollment token in the body) and `/internal/**` (header `X-Obs-Internal-Key`). Neither machine surface uses `Authorization`.
- IDs are `int64` JSON numbers (BIGSERIAL), except `hostKey` (string) and tokens (strings). `cloudAccountId` is **`cloud_account.id`**, never the 12-digit AWS id (which appears as `awsAccountId`).
- Time: ISO-8601 UTC strings (`2026-11-02T10:15:30Z`) in `from`/`to`; timestamps in responses are ISO strings with millisecond precision. Relative ranges ("last 15m") are resolved by the client.
- Scope object accepted by every query endpoint: `{"cloudAccountIds":[12,15],"regions":["us-east-1"],"hostIds":[101],"hostKeys":["i-0abc"],"services":["nginx"],"tags":{"env":"prod"}}`. `cloudAccountIds` empty or absent = all accounts the user may access. Each id goes through `CloudAccountAccessService.requireAccess`; a denied id fails the whole request (403, same body as "not found").
- Pagination: `limit` (default 100, max 1000 for logs, 200 elsewhere) and opaque `cursor`. Lists return `{ "items": [], "nextCursor": null, "total": 123 }` (`total` omitted when expensive).
- Error envelope (all endpoints): `{"error":{"code":"STRING_CODE","message":"Human text","details":{},"requestId":"uuid","retryable":false}}`. Codes in section 7.
- Naming of enums: UPPER_SNAKE in JSON.

## 2. PostgreSQL schema (Flyway)

Migration files (latest existing on disk is `V20261024_39__account_cur_stack_identity.sql`; these sort after it; re-verify the highest at implementation time):

| File | Tables |
|---|---|
| `V20261025_1__obs_core_fleet.sql` | `obs_config_profile`, `obs_host_group`, `obs_agent_enrollment_token`, `obs_host`, `obs_ingest_token`, `obs_agent_release`, `obs_agent_rollout`, `obs_agent_command`, `obs_container` |
| `V20261025_2__obs_onboarding_storage.sql` | `obs_install_job`, `obs_install_job_target`, `obs_permission_stack`, `obs_storage_destination`, `obs_storage_usage_daily`, `obs_archive_manifest`, `obs_query_job` |
| `V20261025_3__obs_explore_content.sql` | `obs_saved_query`, `obs_saved_view`, `obs_dashboard`, `obs_log_pattern`, `obs_service`, `obs_service_edge` |
| `V20261025_4__obs_alerting_slo.sql` | `obs_notification_channel`, `obs_escalation_policy`, `obs_alert_rule`, `obs_alert_state`, `obs_incident`, `obs_incident_event`, `obs_notification_delivery`, `obs_mute_window`, `obs_slo`, `obs_slo_snapshot` |
| `V20261025_5__obs_smart_insights.sql` | `obs_insight`, `obs_baseline_stat` |
| `V20261025_6__obs_governance.sql` | `obs_redaction_rule`, `obs_cost_control`, `obs_usage_hourly`, `obs_audit_log`, `obs_tenant_settings`, `obs_user_permission`, `obs_deletion_request` |

Conventions (from `V20260818_1__aws_cloud_resource_inventory.sql`): `BIGSERIAL PRIMARY KEY`; `client_id BIGINT NOT NULL REFERENCES client(id)`; `cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE`; JSON stored as `TEXT` (never `jsonb`, per the repo's `V20260819_2__inventory_jsonb_to_text.sql` precedent); explanatory header comment per file; `created_at TIMESTAMP NOT NULL DEFAULT now()`. The `client` table name and its `id` column are assumed from `CloudAccount.client` (`client_id` FK); confirm the exact referenced table name when writing the migration.

### 2.1 V20261025_1 — core fleet

```sql
CREATE TABLE obs_config_profile (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT REFERENCES cloud_account(id) ON DELETE CASCADE, -- NULL = client default
  name VARCHAR(120) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  config_json TEXT NOT NULL,            -- desired config (section 9)
  etag VARCHAR(64) NOT NULL,            -- sha256 of config_json
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_obs_cfgprofile_default ON obs_config_profile(client_id, COALESCE(cloud_account_id,0)) WHERE is_default;

CREATE TABLE obs_host_group (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  name VARCHAR(120) NOT NULL,
  selector_json TEXT,                    -- {"asgNames":[],"tags":{}}
  config_profile_id BIGINT REFERENCES obs_config_profile(id),
  storage_destination_id BIGINT,         -- FK added in migration 2
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (cloud_account_id, name)
);

CREATE TABLE obs_agent_enrollment_token (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  host_group_id BIGINT REFERENCES obs_host_group(id) ON DELETE SET NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,   -- sha256(hex) of the secret part
  token_prefix VARCHAR(16) NOT NULL,     -- first chars for display "xo_enr_AbC1"
  max_uses INT NOT NULL DEFAULT 1,
  use_count INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP,
  install_job_id BIGINT,
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE obs_host (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  aws_account_id VARCHAR(12),
  host_key VARCHAR(190) NOT NULL,        -- i-0abc... or sanitised hostname
  hostname VARCHAR(255),
  instance_id VARCHAR(32), region VARCHAR(32), az VARCHAR(32), instance_type VARCHAR(48),
  inventory_arn VARCHAR(512),            -- loose reference to aws_cloud_resource.arn (no FK)
  os_family VARCHAR(24), os_version VARCHAR(80), arch VARCHAR(16), kernel VARCHAR(120),
  is_aws_verified BOOLEAN NOT NULL DEFAULT FALSE,
  host_group_id BIGINT REFERENCES obs_host_group(id) ON DELETE SET NULL,
  config_profile_id BIGINT REFERENCES obs_config_profile(id),
  labels_json TEXT,                      -- merged tags + user labels
  agent_version VARCHAR(32), collector_version VARCHAR(32), channel VARCHAR(16) NOT NULL DEFAULT 'STABLE',
  state VARCHAR(20) NOT NULL DEFAULT 'ENROLLED', -- ENROLLED|CONNECTED|DELAYED|SILENT|QUARANTINED|UNINSTALLED|TERMINATED
  maintenance_until TIMESTAMP,
  first_seen_at TIMESTAMP, first_metric_at TIMESTAMP, first_log_at TIMESTAMP,
  last_heartbeat_at TIMESTAMP, last_data_at TIMESTAMP,
  health_json TEXT,                      -- last agent self-report: queue, drops, cpu, mem
  config_etag_applied VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (cloud_account_id, host_key)
);
CREATE INDEX ix_obs_host_client_state ON obs_host(client_id, state);

CREATE TABLE obs_ingest_token (
  id BIGSERIAL PRIMARY KEY,
  host_id BIGINT NOT NULL REFERENCES obs_host(id) ON DELETE CASCADE,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  token_prefix VARCHAR(16) NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|ROTATING|REVOKED|EXPIRED
  issued_at TIMESTAMP NOT NULL DEFAULT now(),
  expires_at TIMESTAMP NOT NULL,
  last_used_at TIMESTAMP, last_ip VARCHAR(64),
  rotated_from_id BIGINT
);
CREATE INDEX ix_obs_ingest_token_host ON obs_ingest_token(host_id, status);

CREATE TABLE obs_agent_release (
  id BIGSERIAL PRIMARY KEY,
  version VARCHAR(32) NOT NULL,
  channel VARCHAR(16) NOT NULL,           -- STABLE|BETA
  os VARCHAR(16) NOT NULL, arch VARCHAR(16) NOT NULL,
  sha256 CHAR(64) NOT NULL, signature TEXT, size_bytes BIGINT NOT NULL,
  object_key VARCHAR(400) NOT NULL,       -- in obs.agent.release-bucket
  collector_version VARCHAR(32) NOT NULL,
  min_supported_version VARCHAR(32),
  status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|WITHDRAWN
  notes TEXT, created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (version, os, arch)
);

CREATE TABLE obs_agent_rollout (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT,                       -- NULL = platform-wide (admin only)
  release_id BIGINT NOT NULL REFERENCES obs_agent_release(id),
  scope_json TEXT NOT NULL,               -- {"cloudAccountIds":[],"hostGroupIds":[],"canaryPercent":10}
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING|CANARY|ROLLING|PAUSED|COMPLETED|ROLLED_BACK
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE obs_agent_command (
  id BIGSERIAL PRIMARY KEY,
  host_id BIGINT NOT NULL REFERENCES obs_host(id) ON DELETE CASCADE,
  client_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,              -- RESTART|UPDATE|ROLLBACK|ROTATE_TOKEN|UNINSTALL|QUARANTINE|COLLECT_DIAGNOSTICS|PAUSE_SOURCE|RESUME_SOURCE
  payload_json TEXT,
  status VARCHAR(12) NOT NULL DEFAULT 'QUEUED', -- QUEUED|DELIVERED|SUCCEEDED|FAILED|EXPIRED
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(),
  delivered_at TIMESTAMP, completed_at TIMESTAMP, result_json TEXT
);
CREATE INDEX ix_obs_agent_command_pending ON obs_agent_command(host_id, status);

CREATE TABLE obs_container (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  host_id BIGINT NOT NULL REFERENCES obs_host(id) ON DELETE CASCADE,
  runtime VARCHAR(16) NOT NULL,           -- DOCKER|CONTAINERD|ECS|EKS
  container_id VARCHAR(80) NOT NULL,
  name VARCHAR(255), image VARCHAR(512), image_tag VARCHAR(128),
  orchestrator_json TEXT,                 -- ecs task/service, k8s ns/pod/workload
  service_name VARCHAR(190),
  state VARCHAR(16), restart_count INT NOT NULL DEFAULT 0, oom_killed BOOLEAN NOT NULL DEFAULT FALSE,
  first_seen_at TIMESTAMP NOT NULL DEFAULT now(), last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (host_id, container_id)
);
```

### 2.2 V20261025_2 — onboarding and storage

```sql
CREATE TABLE obs_storage_destination (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  mode VARCHAR(10) NOT NULL,              -- MANAGED|BYO|CREATED
  bucket VARCHAR(63), region VARCHAR(32), prefix VARCHAR(200) NOT NULL DEFAULT '',
  kms_key_arn VARCHAR(2048),                            -- always a concrete ARN when encryption_mode=SSE_KMS; for the AWS-managed key it is the alias/aws/s3 ARN resolved by kms:DescribeKey at validation
  encryption_mode VARCHAR(8) NOT NULL DEFAULT 'SSE_KMS', -- SSE_KMS|SSE_S3 (SSE_S3 only for buckets whose default is SSE-S3; archiver then sends AES256)
  format VARCHAR(12) NOT NULL DEFAULT 'parquet',        -- parquet|jsonl_zst
  retention_days INT NOT NULL DEFAULT 365 CHECK (retention_days BETWEEN 7 AND 3650),
  writer_role_arn VARCHAR(2048), reader_role_arn VARCHAR(2048),
  safe_fallback BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING',        -- PENDING|HEALTHY|DEGRADED|FAILED|DISABLED
  status_detail TEXT,
  validated_at TIMESTAMP, validation_json TEXT, cutover_at TIMESTAMP,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_obs_storage_active ON obs_storage_destination(cloud_account_id) WHERE is_active;
ALTER TABLE obs_host_group ADD CONSTRAINT fk_obs_hg_storage FOREIGN KEY (storage_destination_id) REFERENCES obs_storage_destination(id);

CREATE TABLE obs_permission_stack (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL UNIQUE REFERENCES cloud_account(id) ON DELETE CASCADE,
  stack_name VARCHAR(128) NOT NULL, stack_id VARCHAR(2048), region VARCHAR(32) NOT NULL,
  template_version VARCHAR(16) NOT NULL,
  status VARCHAR(14) NOT NULL DEFAULT 'NOT_CREATED',    -- NOT_CREATED|CREATING|VERIFIED|DEGRADED|REMOVED
  operator_role_arn VARCHAR(2048), writer_role_arn VARCHAR(2048), reader_role_arn VARCHAR(2048),
  last_verified_at TIMESTAMP, verify_detail TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE obs_install_job (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  method VARCHAR(12) NOT NULL,            -- SSM|COPY_PASTE|ASG
  selector_json TEXT NOT NULL, sources_json TEXT NOT NULL,
  storage_destination_id BIGINT REFERENCES obs_storage_destination(id),
  status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',        -- RUNNING|COMPLETED|COMPLETED_WITH_ERRORS|CANCELLED
  created_by BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT now(), finished_at TIMESTAMP
);

CREATE TABLE obs_install_job_target (
  id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL REFERENCES obs_install_job(id) ON DELETE CASCADE,
  client_id BIGINT NOT NULL,
  instance_id VARCHAR(32), host_key VARCHAR(190), region VARCHAR(32),
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING',         -- PENDING|PREFLIGHT|BLOCKED|INSTALLING|WAITING_FOR_DATA|CONNECTED|FAILED|SKIPPED|CANCELLED
  failure_code VARCHAR(40), failure_detail TEXT,
  preflight_json TEXT, ssm_command_id VARCHAR(64),
  host_id BIGINT REFERENCES obs_host(id) ON DELETE SET NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT now(), created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_obs_job_target_job ON obs_install_job_target(job_id, state);

CREATE TABLE obs_archive_manifest (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  destination_id BIGINT NOT NULL REFERENCES obs_storage_destination(id),
  host_key VARCHAR(190) NOT NULL, log_type VARCHAR(20) NOT NULL,
  hour_start TIMESTAMP NOT NULL,
  object_key VARCHAR(1024) NOT NULL, bytes BIGINT NOT NULL, row_count BIGINT NOT NULL,
  min_ts TIMESTAMP, max_ts TIMESTAMP, level_counts_json TEXT,
  compacted BOOLEAN NOT NULL DEFAULT FALSE, storage_class VARCHAR(20),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (destination_id, object_key)
);
CREATE INDEX ix_obs_manifest_lookup ON obs_archive_manifest(cloud_account_id, host_key, log_type, hour_start);

CREATE TABLE obs_storage_usage_daily (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  destination_id BIGINT NOT NULL REFERENCES obs_storage_destination(id),
  day DATE NOT NULL, log_type VARCHAR(20) NOT NULL, host_key VARCHAR(190) NOT NULL,
  bytes BIGINT NOT NULL, objects BIGINT NOT NULL, row_count BIGINT NOT NULL,
  UNIQUE (destination_id, day, log_type, host_key)
);

CREATE TABLE obs_query_job (
  id BIGSERIAL PRIMARY KEY,
  client_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  kind VARCHAR(12) NOT NULL,              -- ARCHIVE_SEARCH|EXPORT|MIGRATE|DELETE
  query_text TEXT, scope_json TEXT NOT NULL,
  from_ts TIMESTAMP, to_ts TIMESTAMP,
  status VARCHAR(12) NOT NULL DEFAULT 'QUEUED',          -- QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|EXPIRED
  plan_json TEXT,                         -- files, bytes estimate
  bytes_scanned BIGINT, rows_returned BIGINT,
  result_ref VARCHAR(1024),               -- managed-bucket key for exports
  error_code VARCHAR(40), error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(), started_at TIMESTAMP, finished_at TIMESTAMP, expires_at TIMESTAMP
);
CREATE INDEX ix_obs_query_job_user ON obs_query_job(client_id, user_id, created_at DESC);
```

### 2.3 V20261025_3 — explore content

```sql
CREATE TABLE obs_saved_query (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
  signal VARCHAR(8) NOT NULL,             -- LOGS|METRICS
  name VARCHAR(160) NOT NULL, description TEXT,
  query_text TEXT NOT NULL, scope_json TEXT, columns_json TEXT,
  visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',     -- PRIVATE|CLIENT
  created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_saved_view (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
  page VARCHAR(24) NOT NULL,              -- OVERVIEW|HOSTS|LOGS|METRICS|CONTAINERS|SERVICES|ALERTS
  name VARCHAR(160) NOT NULL, state_json TEXT NOT NULL,  -- filters, time, columns, density
  visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE', is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_dashboard (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, owner_user_id BIGINT,
  name VARCHAR(160) NOT NULL, description TEXT, template_key VARCHAR(60),
  layout_json TEXT NOT NULL,              -- widgets: [{id,type,title,query,x,y,w,h}]
  visibility VARCHAR(10) NOT NULL DEFAULT 'CLIENT', is_generated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_log_pattern (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  scope_key VARCHAR(190) NOT NULL,        -- service name or host_key
  pattern_hash CHAR(16) NOT NULL, template TEXT NOT NULL, example TEXT,
  level VARCHAR(10), first_seen_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL,
  count_24h BIGINT NOT NULL DEFAULT 0, count_prev_24h BIGINT NOT NULL DEFAULT 0,
  is_new BOOLEAN NOT NULL DEFAULT TRUE, muted BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (cloud_account_id, scope_key, pattern_hash)
);
CREATE TABLE obs_service (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  name VARCHAR(190) NOT NULL, kind VARCHAR(24) NOT NULL,  -- WEB|DATABASE|CACHE|QUEUE|JVM|CONTAINER|OTHER
  technology VARCHAR(40), detected_from VARCHAR(40),      -- PORT|PROCESS|IMAGE|NGINX_UPSTREAM|MANUAL
  host_ids_json TEXT, health VARCHAR(12), first_seen_at TIMESTAMP, last_seen_at TIMESTAMP,
  UNIQUE (cloud_account_id, name)
);
CREATE TABLE obs_service_edge (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  from_service_id BIGINT NOT NULL REFERENCES obs_service(id) ON DELETE CASCADE,
  to_service_id BIGINT NOT NULL REFERENCES obs_service(id) ON DELETE CASCADE,
  protocol VARCHAR(16), conn_rate DOUBLE PRECISION, error_rate DOUBLE PRECISION, last_seen_at TIMESTAMP NOT NULL,
  UNIQUE (from_service_id, to_service_id, protocol)
);
```

### 2.4 V20261025_4 — alerting and SLO

```sql
CREATE TABLE obs_notification_channel (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL,
  type VARCHAR(12) NOT NULL,              -- SLACK|TEAMS|EMAIL|PAGERDUTY|WEBHOOK|SNS
  name VARCHAR(120) NOT NULL, config_enc TEXT NOT NULL,  -- AES-GCM encrypted JSON (secrets)
  last_test_at TIMESTAMP, last_test_ok BOOLEAN, enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_escalation_policy (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, name VARCHAR(120) NOT NULL,
  steps_json TEXT NOT NULL,               -- [{"afterMinutes":0,"channelIds":[1]},{"afterMinutes":15,"channelIds":[2]}]
  repeat_minutes INT NOT NULL DEFAULT 60, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_alert_rule (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL, description TEXT,
  type VARCHAR(16) NOT NULL,              -- THRESHOLD|RATE_OF_CHANGE|ABSENCE|ANOMALY|LOG_PATTERN|COMPOSITE|FORECAST|SLO_BURN
  severity VARCHAR(10) NOT NULL,          -- CRITICAL|HIGH|MEDIUM|LOW|INFO
  scope_json TEXT NOT NULL, condition_json TEXT NOT NULL,
  group_by_json TEXT, for_seconds INT NOT NULL DEFAULT 0, interval_seconds INT NOT NULL DEFAULT 60,
  escalation_policy_id BIGINT REFERENCES obs_escalation_policy(id), channel_ids_json TEXT,
  dedup_key_template VARCHAR(255), repeat_minutes INT NOT NULL DEFAULT 60,
  enabled BOOLEAN NOT NULL DEFAULT TRUE, next_eval_at TIMESTAMP NOT NULL DEFAULT now(), last_eval_at TIMESTAMP,
  last_eval_status VARCHAR(12), created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_obs_alert_rule_due ON obs_alert_rule(next_eval_at) WHERE enabled;
CREATE TABLE obs_alert_state (
  id BIGSERIAL PRIMARY KEY, rule_id BIGINT NOT NULL REFERENCES obs_alert_rule(id) ON DELETE CASCADE,
  series_key VARCHAR(255) NOT NULL,       -- group-by label values
  state VARCHAR(12) NOT NULL,             -- OK|PENDING|FIRING|NO_DATA
  since TIMESTAMP NOT NULL, last_value DOUBLE PRECISION, last_eval_at TIMESTAMP NOT NULL, incident_id BIGINT,
  UNIQUE (rule_id, series_key)
);
CREATE TABLE obs_incident (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT,
  rule_id BIGINT REFERENCES obs_alert_rule(id) ON DELETE SET NULL,
  title VARCHAR(255) NOT NULL, severity VARCHAR(10) NOT NULL,
  status VARCHAR(14) NOT NULL DEFAULT 'OPEN',            -- OPEN|ACKNOWLEDGED|RESOLVED|SUPPRESSED
  dedup_key VARCHAR(255) NOT NULL, host_ids_json TEXT, service_names_json TEXT,
  opened_at TIMESTAMP NOT NULL DEFAULT now(), acknowledged_at TIMESTAMP, acknowledged_by BIGINT, resolved_at TIMESTAMP,
  last_notified_at TIMESTAMP, escalation_step INT NOT NULL DEFAULT 0,
  summary_text TEXT, rca_json TEXT, event_count INT NOT NULL DEFAULT 1
);
CREATE INDEX ix_obs_incident_open ON obs_incident(client_id, status, opened_at DESC);
CREATE UNIQUE INDEX uq_obs_incident_dedup_open ON obs_incident(client_id, dedup_key) WHERE status IN ('OPEN','ACKNOWLEDGED');
CREATE TABLE obs_incident_event (
  id BIGSERIAL PRIMARY KEY, incident_id BIGINT NOT NULL REFERENCES obs_incident(id) ON DELETE CASCADE,
  type VARCHAR(20) NOT NULL,              -- OPENED|TRIGGERED_AGAIN|ACKED|RESOLVED|NOTIFIED|ESCALATED|COMMENT|SUPPRESSED
  actor_user_id BIGINT, payload_json TEXT, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_notification_delivery (
  id BIGSERIAL PRIMARY KEY, incident_id BIGINT REFERENCES obs_incident(id) ON DELETE CASCADE,
  channel_id BIGINT NOT NULL REFERENCES obs_notification_channel(id) ON DELETE CASCADE, client_id BIGINT NOT NULL,
  status VARCHAR(10) NOT NULL,            -- SENT|FAILED|SKIPPED_MUTED|SKIPPED_DEDUP
  attempt INT NOT NULL DEFAULT 1, http_status INT, error TEXT, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_mute_window (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL,
  scope_json TEXT NOT NULL,               -- hosts/tags/rules
  starts_at TIMESTAMP, ends_at TIMESTAMP, recurrence_rrule VARCHAR(255), reason TEXT,
  created_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_slo (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL, description TEXT,
  sli_type VARCHAR(14) NOT NULL,          -- LOG_RATIO|METRIC_RATIO
  good_query TEXT NOT NULL, total_query TEXT NOT NULL, scope_json TEXT NOT NULL,
  objective_pct DOUBLE PRECISION NOT NULL, window_days INT NOT NULL DEFAULT 30,
  burn_alerts_json TEXT, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_slo_snapshot (
  id BIGSERIAL PRIMARY KEY, slo_id BIGINT NOT NULL REFERENCES obs_slo(id) ON DELETE CASCADE,
  captured_at TIMESTAMP NOT NULL, good BIGINT NOT NULL, total BIGINT NOT NULL,
  sli DOUBLE PRECISION, budget_remaining_pct DOUBLE PRECISION, burn_rate_1h DOUBLE PRECISION, burn_rate_6h DOUBLE PRECISION,
  UNIQUE (slo_id, captured_at)
);
```

### 2.5 V20261025_5 — smart insights

```sql
CREATE TABLE obs_insight (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,   -- ANOMALY|FORECAST_DISK_FULL|FORECAST_MEMORY_LEAK|RIGHTSIZING|NOISY_PATTERN|NEW_PATTERN|SERVICE_DISCOVERED|SUGGESTED_DASHBOARD
  severity VARCHAR(10) NOT NULL, host_id BIGINT, service_name VARCHAR(190),
  title VARCHAR(255) NOT NULL, explanation TEXT NOT NULL, evidence_json TEXT,
  confidence DOUBLE PRECISION, eta_at TIMESTAMP, projected_saving_usd DOUBLE PRECISION,
  status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',          -- ACTIVE|DISMISSED|RESOLVED|APPLIED
  detected_at TIMESTAMP NOT NULL DEFAULT now(), resolved_at TIMESTAMP,
  dedup_key VARCHAR(255) NOT NULL, UNIQUE (client_id, dedup_key)
);
CREATE TABLE obs_baseline_stat (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, host_id BIGINT NOT NULL REFERENCES obs_host(id) ON DELETE CASCADE,
  metric_id VARCHAR(80) NOT NULL, median_json TEXT NOT NULL, mad_json TEXT NOT NULL,  -- 168 values (hour-of-week)
  samples INT NOT NULL, computed_at TIMESTAMP NOT NULL, UNIQUE (host_id, metric_id)
);
```

### 2.6 V20261025_6 — governance

```sql
CREATE TABLE obs_redaction_rule (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT,
  name VARCHAR(120) NOT NULL, kind VARCHAR(10) NOT NULL,  -- REGEX|FIELD_DROP|HASH
  pattern TEXT NOT NULL, replacement VARCHAR(80) NOT NULL DEFAULT '[REDACTED]',
  apply_to VARCHAR(10) NOT NULL DEFAULT 'BOTH',           -- HOST|GATEWAY|BOTH
  enabled BOOLEAN NOT NULL DEFAULT TRUE, is_builtin BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_cost_control (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT,
  scope_kind VARCHAR(10) NOT NULL,        -- TENANT|ACCOUNT|HOST|SERVICE|SOURCE
  scope_key VARCHAR(190), max_gb_per_day DOUBLE PRECISION, sample_rate DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  drop_rule_json TEXT, action_at_cap VARCHAR(10) NOT NULL DEFAULT 'SAMPLE',  -- SAMPLE|DROP|ALERT_ONLY
  alert_at_pct INT NOT NULL DEFAULT 80, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE obs_usage_hourly (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT NOT NULL,
  host_key VARCHAR(190) NOT NULL, signal VARCHAR(10) NOT NULL,  -- LOGS|METRICS|TRACES
  source VARCHAR(40) NOT NULL, hour_start TIMESTAMP NOT NULL,
  bytes_in BIGINT NOT NULL, records_in BIGINT NOT NULL, bytes_dropped BIGINT NOT NULL DEFAULT 0, series_active BIGINT,
  UNIQUE (cloud_account_id, host_key, signal, source, hour_start)
);
CREATE TABLE obs_audit_log (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, cloud_account_id BIGINT,
  actor_user_id BIGINT, actor_email VARCHAR(255), actor_ip VARCHAR(64),
  action VARCHAR(48) NOT NULL,            -- e.g. LOGS_ARCHIVE_QUERY, LOGS_EXPORT, TOKEN_REVOKED, HOST_UNINSTALL, STORAGE_CHANGED
  target_type VARCHAR(24), target_id VARCHAR(120), detail_json TEXT, created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_obs_audit_client_time ON obs_audit_log(client_id, created_at DESC);
-- Append-only: block UPDATE/DELETE.
CREATE FUNCTION obs_audit_immutable() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'obs_audit_log is append-only'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_obs_audit_immutable BEFORE UPDATE OR DELETE ON obs_audit_log FOR EACH ROW EXECUTE FUNCTION obs_audit_immutable();
CREATE TABLE obs_tenant_settings (
  client_id BIGINT PRIMARY KEY,
  ai_enabled BOOLEAN NOT NULL DEFAULT FALSE, cold_query_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  max_hosts INT NOT NULL DEFAULT 500, hot_retention_days INT NOT NULL DEFAULT 14,  -- read-only mirror of the platform value
  archive_scan_cap_gb INT NOT NULL DEFAULT 20, default_density VARCHAR(10) NOT NULL DEFAULT 'COMFORTABLE',
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE obs_deletion_request (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL,
  cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
  scope_kind VARCHAR(10) NOT NULL,        -- HOST|ACCOUNT|SIGNAL
  scope_key VARCHAR(190),                 -- host_key for HOST, signal name for SIGNAL, null for ACCOUNT
  storage_destination_id BIGINT REFERENCES obs_storage_destination(id),
  target VARCHAR(10) NOT NULL,            -- MANAGED_COLD|HOT_EXPIRY_ONLY (customer-owned buckets are never deleted by XamOps)
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|DONE|FAILED|CANCELLED
  objects_deleted BIGINT NOT NULL DEFAULT 0, error TEXT,
  requested_by BIGINT NOT NULL, requested_at TIMESTAMP NOT NULL DEFAULT now(), completed_at TIMESTAMP
);
CREATE INDEX ix_obs_delreq_status ON obs_deletion_request(status, requested_at);
CREATE TABLE obs_user_permission (
  id BIGSERIAL PRIMARY KEY, client_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  permission VARCHAR(40) NOT NULL, granted_by BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (client_id, user_id, permission)
);
```

Tenant check pattern in repositories: every finder includes `ClientId` (and the controller has already called `requireAccess` for the account). Tables without `client_id` (`obs_alert_state`, `obs_incident_event`, `obs_slo_snapshot`, `obs_notification_delivery` partly) are reached only through a parent row that was tenant-checked.

## 3. Telemetry data model

### 3.1 Resource attributes (stamped by gateway; client values overwritten)

`xo.client`, `xo.account` (cloud_account.id), `xo.host_id`, `xo.host_key`, plus detected: `cloud.provider`, `cloud.account.id`, `cloud.region`, `cloud.availability_zone`, `host.id`, `host.name`, `host.type`, `os.type`, `container.id`, `container.name`, `container.image.name`, `ecs.task.arn`, `k8s.pod.name`, `k8s.namespace.name`, `service.name`.

### 3.2 Log record (hot, VictoriaLogs JSON line)

```json
{"_time":"2026-11-02T10:15:30.123Z","_msg":"upstream timed out (110: Connection timed out)",
 "level":"error","host_key":"i-0abc","host":"web-1","service":"nginx","type":"web","account":"123456789012","region":"us-east-1",
 "source":"filelog:/var/log/nginx/error.log","trace_id":"","span_id":"","pattern_id":"a1b2c3d4e5f60718",
 "container":"","attr.http.status":"504","attr.upstream":"10.0.2.11:8080"}
```

Stream fields: `host_key,service,type`. Extra attributes are prefixed `attr.` in the hot store and mapped to `attributes[...]` in cold.

### 3.3 Metric naming

Canonical IDs (`metricId`) in the catalog, e.g. `host.cpu.usage` → stored as `xo_host_cpu_usage_ratio{cpu="cpu0",state="user"}`. Catalog file: `xamops-service/src/main/resources/observability/metric-catalog.yaml` (04 lists it). Required families:

| Family | metricIds (examples) |
|---|---|
| CPU | `host.cpu.usage` (state: user/system/iowait/steal/nice/irq/softirq; per-core label `cpu`), `host.load.1m/5m/15m`, `host.cpu.runqueue`, `host.cpu.context_switches` |
| Memory | `host.mem.used`, `host.mem.available`, `host.mem.cached`, `host.mem.buffers`, `host.mem.slab`, `host.swap.used`, `host.swap.in/out`, `host.mem.page_faults`, `host.mem.oom_kills`, `process.mem.rss` |
| Disk | `host.disk.iops` (r/w), `host.disk.throughput`, `host.disk.latency` (await), `host.disk.queue_depth`, `host.disk.utilization`, `host.fs.used`, `host.fs.free`, `host.fs.inodes_used`, `host.fs.inodes_free` |
| Network | `host.net.bytes`, `host.net.packets`, `host.net.errors`, `host.net.drops`, `host.tcp.connections` (state), `host.tcp.retransmits`, `host.sockets.count` |
| Processes | `host.processes.count`, `host.processes.zombies`, `process.cpu`, `process.mem.rss`, `process.threads`, `process.fds` |
| Containers | `container.cpu.usage`, `container.cpu.throttled_periods`, `container.mem.usage`, `container.mem.limit`, `container.restarts`, `container.oom_killed`, `container.net.bytes`, `container.blkio.bytes` |
| Agent self | `xo.agent.queue_bytes`, `xo.agent.dropped_total`, `xo.agent.export_errors_total`, `xo.agent.cpu`, `xo.agent.mem` |
| Frameworks | RED derived: `svc.requests.rate`, `svc.errors.ratio`, `svc.duration.p95` (from nginx logs/OTLP); USE derived from host families |
| Custom | any Prometheus/OTLP metric, namespaced `custom.<name>` |

### 3.4 Archive columns (Parquet envelope)

| Column | Parquet type | Notes |
|---|---|---|
| `ts` | TIMESTAMP(MILLIS, UTC) | event time |
| `ingest_ts` | TIMESTAMP(MILLIS, UTC) | gateway receive time |
| `tenant_hash` | BYTE_ARRAY (UTF8) | `sha256(client_id:cloud_account_id)` first 16 hex |
| `account` | UTF8 | 12-digit AWS id |
| `region` | UTF8 (dictionary) | |
| `host` | UTF8 | host_key |
| `service` | UTF8 (dictionary) | |
| `type` | UTF8 (dictionary) | enum from layout |
| `level` | UTF8 (dictionary) | `trace,debug,info,warn,error,fatal,unknown` |
| `message` | UTF8 | after redaction |
| `trace_id`, `span_id` | UTF8 | optional |
| `pattern_id` | UTF8 | 16 hex |
| `container` | UTF8 | optional |
| `attributes` | MAP<UTF8,UTF8> | everything else |

Metrics rows (type `metrics`): `ts` (minute), `host`, `metric`, `labels MAP`, `min`, `max`, `avg`, `count`.

## 4. REST endpoint catalogue (UI)

Permission column uses the keys in section 11. Request/response bodies for the starred (`*`) endpoints are in section 5.

### 4.1 Overview and insights

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET /api/obs/v1/overview/summary` `*` | `obs.view` | KPIs, fleet health score, top issues, cost, noisy sources |
| `GET /api/obs/v1/overview/fleet-health` | `obs.view` | Per-host health buckets for the host map |
| `GET /api/obs/v1/me/accounts` | `obs.view` | AWS accounts the caller may use: `{ "accounts": [{ "cloudAccountId": 12, "awsAccountId": "123456789012", "name": "prod", "provider": "AWS", "status": "CONNECTED", "stackStatus": "VERIFIED", "hostCount": 12 }] }` (backed by `CloudAccountRepository.findByClientId` filtered with `CloudAccountAccessService.canAccess`; gives the UI the `cloud_account.id` PK) |
| `GET /api/obs/v1/insights` | `obs.view` | List insights (filters `type`, `status`, `hostId`) |
| `POST /api/obs/v1/insights/{id}/dismiss` | `obs.alerts.manage` | Dismiss |
| `GET /api/obs/v1/insights/rightsizing` | `obs.view` | Rightsizing hints from observed p95 |
| `GET /api/obs/v1/insights/noise` | `obs.view` | Noisiest patterns with projected savings |

### 4.2 Onboarding and permission stack

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET /api/obs/v1/onboarding/accounts/{cloudAccountId}/discovery` `*` | `obs.agents.manage` | EC2 (+ECS/EKS) discovery with pre-flight L1 |
| `POST /api/obs/v1/onboarding/preflight` | `obs.agents.manage` | Run pre-flight for selected targets |
| `POST /api/obs/v1/onboarding/jobs` `*` | `obs.agents.manage` | Start install job |
| `GET /api/obs/v1/onboarding/jobs/{jobId}` `*` | `obs.agents.manage` | Job + per-target live state |
| `POST /api/obs/v1/onboarding/jobs/{jobId}/targets/{targetId}/retry` | `obs.agents.manage` | Retry one target |
| `POST /api/obs/v1/onboarding/jobs/{jobId}/cancel` | `obs.agents.manage` | Cancel |
| `POST /api/obs/v1/onboarding/jobs/{jobId}/rollback` | `obs.agents.manage` | Uninstall all hosts of the job |
| `POST /api/obs/v1/onboarding/install-command` `*` | `obs.agents.manage` | Copy-paste command + enrollment token |
| `POST /api/obs/v1/onboarding/snippets` | `obs.agents.manage` | IaC outputs (kind enum) |
| `GET /api/obs/v1/onboarding/trust-manifest` | `obs.view` | Trust panel data |
| `GET /api/obs/v1/accounts/{cloudAccountId}/permission-stack` | `obs.storage.manage` | Stack status |
| `GET /api/obs/v1/accounts/{cloudAccountId}/permission-stack/policy-preview` | `obs.view` | Exact JSON policies + plain-language |
| `POST /api/obs/v1/accounts/{cloudAccountId}/permission-stack/launch-url` | `obs.storage.manage` | CloudFormation quick-create URL |
| `POST /api/obs/v1/accounts/{cloudAccountId}/permission-stack/verify` | `obs.storage.manage` | Describe stack + test AssumeRole |

### 4.3 Hosts, containers, services, agents

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET /api/obs/v1/hosts` | `obs.view` | List hosts (filters, sort, cursor) |
| `GET /api/obs/v1/hosts/{hostId}` `*` | `obs.view` | Host detail summary |
| `GET /api/obs/v1/hosts/{hostId}/processes` | `obs.view` | Top processes snapshot |
| `GET /api/obs/v1/hosts/{hostId}/timeline` | `obs.view` | Events: agent, deploy, alerts, patterns |
| `PATCH /api/obs/v1/hosts/{hostId}` | `obs.agents.manage` | Labels, maintenance window, config profile |
| `POST /api/obs/v1/hosts/{hostId}/disconnect` | `obs.agents.manage` | Queue UNINSTALL |
| `GET /api/obs/v1/host-groups` / `POST` / `PUT /{id}` / `DELETE /{id}` | `obs.agents.manage` | Host groups (`/api/obs/v1/host-groups`) |
| `GET /api/obs/v1/containers` | `obs.view` | Container list |
| `GET /api/obs/v1/containers/{containerId}` | `obs.view` | Container detail |
| `GET /api/obs/v1/services` / `GET /api/obs/v1/services/{serviceId}` | `obs.view` | Discovered services |
| `GET /api/obs/v1/services/map` | `obs.view` | Service graph (nodes, edges) |
| `GET /api/obs/v1/agents/fleet` `*` | `obs.view` | Fleet table + version drift summary |
| `GET /api/obs/v1/agents/releases` | `obs.view` | Releases available |
| `POST /api/obs/v1/agents/rollouts` / `GET /api/obs/v1/agents/rollouts` / `POST /api/obs/v1/agents/rollouts/{id}/pause|resume|rollback` | `obs.agents.manage` | Staged update |
| `POST /api/obs/v1/agents/commands` | `obs.agents.manage` | Bulk command (restart, update, rotate, quarantine, uninstall) |
| `GET /api/obs/v1/agents/config-profiles` / `PUT /api/obs/v1/agents/config-profiles/{id}` / `POST /api/obs/v1/agents/config-profiles` | `obs.agents.manage` | Remote config |

### 4.4 Logs

| Method + Path | Permission | Purpose |
|---|---|---|
| `POST /api/obs/v1/logs/search` `*` | `obs.logs.read` | Hot search (and rows) |
| `POST /api/obs/v1/logs/histogram` | `obs.logs.read` | Hits per bucket by level |
| `POST /api/obs/v1/logs/facets` | `obs.logs.read` | Field facets with counts |
| `POST /api/obs/v1/logs/stats` | `obs.logs.read` | Aggregations (`stats` stage) |
| `POST /api/obs/v1/logs/context` | `obs.logs.read` | N lines before/after one record |
| `POST /api/obs/v1/logs/patterns` | `obs.logs.read` | Pattern clusters with trend |
| `POST /api/obs/v1/logs/validate` | `obs.logs.read` | Parse XLQ, return AST/compiled/errors |
| `GET /api/obs/v1/logs/fields` | `obs.logs.read` | Field names + top values |
| `GET /api/obs/v1/stream/logs/tail` `*` | `obs.logs.read` | SSE live tail (section 10) |
| `POST /api/obs/v1/query-jobs` `*` | `obs.logs.archive` | Create archive search / export / migrate job |
| `GET /api/obs/v1/query-jobs/{jobId}` | `obs.logs.archive` | Status + plan |
| `GET /api/obs/v1/query-jobs/{jobId}/results` | `obs.logs.archive` | Rows (cursor) |
| `GET /api/obs/v1/query-jobs/{jobId}/download` | `obs.logs.export` | Export file (signed URL) |
| `POST /api/obs/v1/query-jobs/{jobId}/cancel` | `obs.logs.archive` | Cancel |
| `GET/POST/PUT/DELETE /api/obs/v1/saved-queries` | `obs.logs.read` | Saved queries |
| `GET/POST/PUT/DELETE /api/obs/v1/saved-views` | `obs.view` | Saved views |

### 4.5 Metrics and dashboards

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET /api/obs/v1/metrics/catalog` | `obs.view` | Metric catalog (ids, units, labels, descriptions) |
| `POST /api/obs/v1/metrics/query` `*` | `obs.view` | Range/instant query (MetricQuery) |
| `POST /api/obs/v1/metrics/labels` | `obs.view` | Label names/values for a metric |
| `POST /api/obs/v1/metrics/correlate` `*` | `obs.logs.read` | Metric spike → logs deep link + co-movers |
| `GET/POST/PUT/DELETE /api/obs/v1/dashboards` | `obs.view` / `obs.alerts.manage` for write | Dashboards |
| `GET /api/obs/v1/dashboards/suggested` | `obs.view` | Generated dashboards for detected tech |

### 4.6 Alerts, incidents, SLOs, channels

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET/POST/PUT/DELETE /api/obs/v1/alert-rules` | `obs.alerts.manage` (read `obs.view`) | Rules |
| `POST /api/obs/v1/alert-rules/preview` `*` | `obs.alerts.manage` | Backtest: "would have fired N times" |
| `POST /api/obs/v1/alert-rules/{ruleId}/enable|disable` | `obs.alerts.manage` | Toggle |
| `GET /api/obs/v1/incidents` / `GET /api/obs/v1/incidents/{incidentId}` | `obs.view` | List / detail |
| `POST /api/obs/v1/incidents/{incidentId}/acknowledge|resolve|comment` | `obs.alerts.manage` | Actions |
| `POST /api/obs/v1/ai/rca` | `obs.ai.use` | RCA for an incident (grounded) |
| `GET/POST/PUT/DELETE /api/obs/v1/notification-channels` | `obs.alerts.manage` | Channels |
| `POST /api/obs/v1/notification-channels/{channelId}/test` | `obs.alerts.manage` | Send test |
| `GET/POST/PUT/DELETE /api/obs/v1/escalation-policies` | `obs.alerts.manage` | Escalation |
| `GET/POST/PUT/DELETE /api/obs/v1/mute-windows` | `obs.alerts.manage` | Mute windows |
| `GET/POST/PUT/DELETE /api/obs/v1/slos` / `GET /api/obs/v1/slos/{sloId}/budget` | `obs.alerts.manage` (read `obs.view`) | SLOs |

### 4.7 AI

| Method + Path | Permission | Purpose |
|---|---|---|
| `POST /api/obs/v1/ai/summarize` | `obs.ai.use` | Plain-language summary of a window/host/service |
| `POST /api/obs/v1/ai/nl-query` `*` | `obs.ai.use` | NL → XLQ/MetricQuery (returns compiled query, never auto-runs) |

### 4.8 Storage, usage, settings

| Method + Path | Permission | Purpose |
|---|---|---|
| `GET /api/obs/v1/storage/overview` | `obs.view` | Destination, usage, cost, tier distribution |
| `GET /api/obs/v1/storage/buckets` `*` | `obs.storage.manage` | List account buckets with warnings |
| `POST /api/obs/v1/storage/destinations/validate` `*` | `obs.storage.manage` | Validation + cost estimate |
| `POST /api/obs/v1/storage/destinations` / `PUT /{id}` / `GET` | `obs.storage.manage` | Create/update/list destinations |
| `POST /api/obs/v1/storage/destinations/create-bucket-url` | `obs.storage.manage` | Quick-create URL for dedicated bucket stack |
| `POST /api/obs/v1/storage/destinations/{id}/migrate` | `obs.storage.manage` | Move managed ↔ BYO |
| `GET /api/obs/v1/storage/destinations/{id}/usage` | `obs.view` | Daily usage series by type/host |
| `GET /api/obs/v1/storage/destinations/{id}/athena-ddl` | `obs.storage.manage` | Generated DDL |
| `POST /api/obs/v1/storage/deletion-requests` / `GET /api/obs/v1/storage/deletion-requests` | `obs.storage.manage` | Request deletion of managed cold data (host/account/signal) and list requests; processed by `DataDeletionJob`; audited (`DATA_DELETION_REQUESTED`) |
| `GET /api/obs/v1/usage` | `obs.view` | Meters + projected month-end cost |
| `GET/PUT /api/obs/v1/settings/tenant` | `obs.settings.manage` | `obs_tenant_settings` |
| `GET/POST/PUT/DELETE /api/obs/v1/settings/redaction-rules` + `POST /api/obs/v1/settings/redaction-rules/preview` | `obs.settings.manage` | Redaction |
| `GET/POST/PUT/DELETE /api/obs/v1/settings/cost-controls` | `obs.settings.manage` | Caps, sampling, drop rules |
| `GET /api/obs/v1/settings/tokens` / `POST /api/obs/v1/settings/tokens/revoke` | `obs.settings.manage` | Enrollment + ingest token list/revoke |
| `GET/PUT /api/obs/v1/settings/permissions` | `obs.settings.manage` | Per-user permission grants |
| `GET /api/obs/v1/settings/audit` | `obs.audit.read` | Audit log (cursor, filters, CSV via `Accept: text/csv`) |
| `GET /api/obs/v1/me/permissions` | any authenticated | Effective permissions of the caller (drives UI gating) |

### 4.9 Machine endpoints

See section 11.

## 5. Key request/response JSON

### 5.1 `GET /api/obs/v1/overview/summary?cloudAccountIds=12,15&from=...&to=...`

```json
{
  "generatedAt": "2026-11-02T10:15:30Z",
  "fleet": { "total": 48, "connected": 44, "delayed": 2, "silent": 1, "quarantined": 0, "uninstalled": 1, "healthScore": 91 },
  "kpis": {
    "logsIngestedBytes": { "value": 52613349376, "previous": 49000000000, "deltaPct": 7.4 },
    "errorRate": { "value": 0.012, "previous": 0.009, "deltaPct": 33.3 },
    "activeIncidents": { "value": 3, "critical": 1 },
    "avgCpu": { "value": 0.37, "previous": 0.35 }
  },
  "topHosts": [{ "hostId": 101, "hostKey": "i-0abc", "name": "web-1", "reason": "iowait 38% for 12m", "severity": "HIGH" }],
  "insights": [{ "id": 9, "type": "FORECAST_DISK_FULL", "title": "web-1: /var fills in ~6 days", "severity": "MEDIUM" }],
  "noisySources": [{ "service": "nginx", "bytes": 8123456789, "sharePct": 15.4, "projectedSavingUsd": 41.0 }],
  "cost": { "monthToDateUsd": 62.1, "projectedMonthUsd": 140.9, "currency": "USD" },
  "storage": { "destinationStatus": "HEALTHY", "bytesStored": 912345678901 }
}
```

`healthScore` = 100 minus weighted penalties: connected ratio (40%), unresolved critical/high incidents (30%), agent health (15%), resource saturation hosts (15%); formula documented in the response `healthScoreBreakdown` array for tooltips.

### 5.2 Discovery response

```json
{
  "cloudAccountId": 12, "awsAccountId": "123456789012",
  "inventory": { "fresh": true, "partial": false, "syncedAt": "2026-11-02T10:10:00Z" },
  "regions": ["us-east-1", "eu-west-1"],
  "permissionStack": { "status": "VERIFIED" },
  "instances": [{
    "instanceId": "i-0abc", "name": "web-1", "region": "us-east-1", "az": "us-east-1a", "state": "running",
    "platform": "linux", "arch": "x86_64", "instanceType": "m6i.large",
    "tags": { "env": "prod", "Name": "web-1" }, "asgName": "web-asg",
    "ssm": { "managed": true, "pingStatus": "Online" },
    "alreadyConnected": false,
    "preflight": { "level": 1, "status": "READY", "checks": [{ "code": "PF_STATE", "status": "PASS" }] }
  }],
  "containers": { "ecsClusters": [{ "name": "prod", "region": "us-east-1", "containerInstances": 6 }], "eksClusters": [{ "name": "main", "region": "us-east-1", "nodes": 8 }] },
  "regionErrors": [{ "region": "eu-west-3", "code": "DESCRIBE_FAILED", "retryable": true }]
}
```

### 5.3 `POST /api/obs/v1/onboarding/jobs`

```json
{
  "cloudAccountId": 12,
  "method": "SSM",
  "selector": { "mode": "filter", "regions": ["us-east-1"], "tags": { "env": "prod" }, "states": ["running"], "ids": [] },
  "sources": ["system", "auth", "journald", "docker", "nginx", "host-metrics"],
  "storage": { "mode": "BYO", "destinationId": 7 },
  "hostGroupName": "prod-web",
  "acknowledgements": { "taggingInstances": true, "policyPreviewViewed": true }
}
```

Response `201`: `{ "jobId": 55, "status": "RUNNING", "targetCount": 12 }`. Poll response:

```json
{ "jobId": 55, "status": "RUNNING", "createdAt": "...", "counts": { "connected": 7, "waitingForData": 3, "installing": 1, "failed": 1, "blocked": 0, "pending": 0 },
  "targets": [
   { "targetId": 901, "instanceId": "i-0abc", "region": "us-east-1", "state": "CONNECTED", "firstMetricAt": "...", "firstLogAt": "...", "hostId": 101 },
   { "targetId": 905, "instanceId": "i-0def", "state": "FAILED", "failureCode": "EGRESS_BLOCKED",
     "failureDetail": "Host cannot reach obs-ingest.example.com:443", "fix": { "title": "Allow outbound 443", "docs": "...", "retryable": true } } ] }
```

Validation: `cloudAccountId` required and access-checked; `method` enum; `selector.ids` ≤ 500 (use `filter`/`all` beyond), instance ids match `^i-[0-9a-f]{8,17}$`, regions in the account's active regions; `sources` subset of the catalog; `acknowledgements.taggingInstances` required true for `SSM`. Violations → 400 `VALIDATION_FAILED` with `details.fields`.

### 5.4 `POST /api/obs/v1/logs/search`

```json
{
  "query": "service:nginx level:(error OR warn) \"timed out\"",
  "scope": { "cloudAccountIds": [12], "hostKeys": ["i-0abc"] },
  "from": "2026-11-02T09:15:00Z", "to": "2026-11-02T10:15:00Z",
  "limit": 200, "cursor": null, "order": "DESC",
  "tier": "HOT"
}
```

```json
{
  "tier": "HOT", "tookMs": 184, "truncated": false,
  "compiled": { "engine": "LOGSQL", "text": "{host_key=\"i-0abc\"} service:=\"nginx\" level:in(\"error\",\"warn\") \"timed out\"", "limit": 200 },
  "rows": [{
    "id": "c2V...", "ts": "2026-11-02T10:14:59.812Z", "level": "error", "message": "upstream timed out ...",
    "host": "web-1", "hostKey": "i-0abc", "service": "nginx", "type": "web", "region": "us-east-1", "container": null,
    "patternId": "a1b2c3d4e5f60718", "attributes": { "http.status": "504" }, "cloudAccountId": 12
  }],
  "nextCursor": "eyJ0cyI6Li4ufQ"
}
```

`limit` exists **only** as this body field (page size, default 100, max 1000); XLQ has no `limit` stage (a `| limit n` in the query text is a `QUERY_SYNTAX_ERROR` with hint "use the page size"). **Multi-account fan-out:** `scope.cloudAccountIds` is required, 1 to 10 ids (`400 VALIDATION_FAILED` if empty, `400 TOO_MANY_ACCOUNTS` if more than 10). VictoriaLogs addresses exactly one tenant `(AccountID=client_id, ProjectID=cloud_account_id)` per call, so `ObsLogQueryService` runs one upstream call per account in parallel (per-call timeout 8 s, total budget 12 s), authorises each id with `requireAccess` first, and merges: rows ordered by `(ts desc, id desc)` and cut to `limit`; `histogram` and `facets` buckets summed by bucket/value; `stats` merged only for `count`, `sum`, `min`, `max` (and `top N`, merged by summed counts then re-cut); `avg`, `count_unique`, `p50..p99` and `dedup` across more than one account return `400 STATS_NOT_MERGEABLE` (single-account requests support everything). The response gains `"partial": false` and `"failedAccounts": []`; when some upstream calls fail or time out the successful accounts are returned with `partial: true` and `failedAccounts: [{ "cloudAccountId": 15, "code": "HOT_STORE_UNAVAILABLE" }]` (HTTP 200); only if every account fails does the call return the error. `nextCursor` is the base64url of a JSON map `{ "<cloudAccountId>": "<per-account cursor>" }`; an account missing from the map is exhausted. Row ids are unique across accounts (they embed the account id). `tier` is `HOT` or `COLD`. A COLD request is **rejected** on this endpoint with `409 ARCHIVE_REQUIRES_JOB` including `details.hint` (create via `POST /query-jobs`). If `from` is older than the hot window (14 days) the server responds `409 RANGE_OUTSIDE_HOT_WINDOW` with `details.hotFrom` and `details.suggestJob=true`; the UI shows the "Search archive" state. Limits: `to - from ≤ 7 days` for hot; `limit ≤ 1000`; query length ≤ 4,000 chars; max 10 requests/s per user (Bucket4j). Failures: `QUERY_SYNTAX_ERROR` (400, with `details.position`), `HOT_STORE_UNAVAILABLE` (503 retryable), `QUERY_TIMEOUT` (504 after 30 s).

### 5.5 `POST /api/obs/v1/query-jobs` (archive search)

```json
{ "kind": "ARCHIVE_SEARCH", "query": "level:error \"OutOfMemory\"", "scope": { "cloudAccountIds": [12], "hostKeys": ["i-0abc"] },
  "from": "2026-08-01T00:00:00Z", "to": "2026-08-03T00:00:00Z", "confirmPlan": false }
```

Response `202`: `{ "jobId": 301, "status": "QUEUED", "plan": { "files": 48, "bytes": 612345678, "estimatedSeconds": 14, "storageClasses": {"STANDARD":30,"STANDARD_IA":18}, "requiresRestore": [] } }`. Rules: `plan.bytes > tenant cap (archive_scan_cap_gb)` → 422 `SCAN_TOO_LARGE`; any hours in non-queryable classes → `plan.requiresRestore` and the job proceeds with a warning only if `confirmPlan` true. Job poll returns `status`, progress (`filesDone/filesTotal`), `rowsReturned`, `bytesScanned`. Results via cursor; exports as CSV/NDJSON (`format` field, max 1M rows, expire 24 h). Every create and export writes `obs_audit_log` (`LOGS_ARCHIVE_QUERY`, `LOGS_EXPORT`).

### 5.6 `POST /api/obs/v1/metrics/query`

See section 6 for the body. Response:

```json
{ "tookMs": 41, "step": 60, "series": [
  { "labels": { "host_key": "i-0abc", "state": "iowait" }, "unit": "ratio", "points": [[1793614500, 0.02], [1793614560, 0.41]] } ],
  "compiled": { "engine": "METRICSQL", "text": "avg by (host_key) (...)" }, "previous": null }
```

`compareTo`: `"PREVIOUS_PERIOD"` or `"PREVIOUS_DAY"|"PREVIOUS_WEEK"` returns `previous` with the shifted series aligned to the current timestamps.

### 5.7 `POST /api/obs/v1/metrics/correlate`

Request: `{ "metricId": "host.cpu.usage", "labels": {"host_key":"i-0abc","state":"iowait"}, "from": "...", "to": "...", "scope": {...} }`. Response:

```json
{ "logQuery": "level:(error OR warn OR fatal)", "scope": { "hostKeys": ["i-0abc"] }, "from": "2026-11-02T10:12:00Z", "to": "2026-11-02T10:22:00Z",
  "deepLink": "/observability-v1/logs?q=level%3A(error%20OR%20warn%20OR%20fatal)&hosts=i-0abc&from=2026-11-02T10:12:00Z&to=2026-11-02T10:22:00Z&src=metric",
  "counts": { "error": 214, "warn": 51 }, "topPatterns": [{ "patternId": "a1b2...", "template": "mysql: lock wait timeout <*>", "count": 190, "isNew": true }],
  "coMovers": [{ "metricId": "host.disk.latency", "correlation": 0.93, "labels": {"device":"nvme1n1"} }] }
```

### 5.8 `POST /api/obs/v1/alert-rules` and preview

```json
{
  "name": "Disk will fill within 3 days",
  "type": "FORECAST", "severity": "HIGH",
  "scope": { "cloudAccountIds": [12], "tags": { "env": "prod" } },
  "condition": { "metricId": "host.fs.used", "labels": { "mountpoint": "/" }, "horizonDays": 3, "lookbackDays": 7, "thresholdRatio": 1.0 },
  "forSeconds": 0, "intervalSeconds": 300, "groupBy": ["host_key", "mountpoint"],
  "channelIds": [4], "escalationPolicyId": null, "repeatMinutes": 240,
  "dedupKeyTemplate": "{rule}:{host_key}:{mountpoint}"
}
```

Condition shapes per type: `THRESHOLD {"metricId","labels","agg","op","value","window"}`; `RATE_OF_CHANGE {"metricId","window","op","pctChange"}`; `ABSENCE {"metricId|query","silentForSeconds"}`; `ANOMALY {"metricId","sensitivity":"LOW|MEDIUM|HIGH","direction":"UP|DOWN|BOTH","minDurationSeconds"}`; `LOG_PATTERN {"query","countOp","count","window"|"newPattern":true}`; `COMPOSITE {"op":"AND|THEN","ruleRefs":[ids],"withinSeconds"}`; `FORECAST` as above; `SLO_BURN {"sloId","fastBurn":14.4,"fastWindow":"1h","slowBurn":6,"slowWindow":"6h"}`.
Preview (`POST /alert-rules/preview`): body = rule + `lookbackDays` (≤ 14); response `{ "wouldHaveFired": 14, "distinctSeries": 3, "timeline": [{"at":"...","series":"i-0abc","value":0.93}], "estimatedNotifications": 14, "noiseWarning": "More than 10 per week; consider a longer 'for' duration." }`.
Validation: `intervalSeconds` ≥ 30; `forSeconds` ≤ 86,400; ≤ 500 rules per tenant; composite refs must belong to the tenant and not form cycles; channel ids tenant-checked.

### 5.9 Storage endpoints

`GET /storage/buckets?cloudAccountId=12&region=us-east-1` → `{ "buckets": [{ "name": "acme-logs", "region": "us-east-1", "encryption": "sse-kms", "publicAccessBlocked": true, "versioning": "Suspended", "objectLock": false, "sameRegion": true, "selectable": true, "warnings": [] }] }`.
`POST /storage/destinations/validate` body `{ "cloudAccountId":12, "mode":"BYO", "bucket":"acme-logs", "prefix":"", "kmsKeyArn":null, "format":"parquet", "retentionDays":365, "hostCount":12, "expectedGbPerHostPerDay":1.5 }` → section 8 of 03 response (`kmsKeyArn: null` means the AWS-managed key: the server resolves `alias/aws/s3` with `kms:DescribeKey` in the bucket region, returns it as `resolvedKmsKeyArn` and stores it; the validate response also returns `encryptionMode`; retention outside 7 to 3650 is `400 VALIDATION_FAILED`) with `checks[]` of `{ "code":"PUBLIC_ACCESS_BLOCK","status":"PASS|WARN|FAIL","message":"","fix":null }` and `estimate: { "monthlyUsd": 11.4, "twelveMonthUsd": 93.0, "gbPerMonth": 540, "assumptions": ["ratio 10x","IA after 30d","Glacier IR after 90d"], "priceSource": "AWS_PRICING_API|BUNDLED_TABLE" }`.

## 6. MetricQuery (structured, compiled to MetricsQL)

```json
{
  "metricId": "host.cpu.usage",
  "filters": [{ "label": "state", "op": "=", "value": "iowait" }],
  "scope": { "cloudAccountIds": [12], "tags": { "env": "prod" } },
  "agg": "avg", "groupBy": ["host_key"], "function": "none",
  "from": "2026-11-02T09:15:00Z", "to": "2026-11-02T10:15:00Z", "stepSeconds": 60, "compareTo": "PREVIOUS_PERIOD", "topN": 10
}
```

Rules: `agg` ∈ `avg,sum,min,max,p50,p90,p95,p99,count,rate`; `function` ∈ `none,rate,delta,increase`; `stepSeconds` ≥ 10 and `(to-from)/step ≤ 11,000` (else 400 `TOO_MANY_POINTS` with suggested step); `topN` ≤ 50; `label` names match `^[a-zA-Z_][a-zA-Z0-9_]*$`; scope filters `xo_client` and `xo_account` are added by the server. Raw mode: `{"mode":"PROMQL","expr":"..."}` requires `obs.metrics.advanced`; the expression is parsed, function allow-list enforced, and the tenant filter forced via `extra_filters`.

## 7. Error codes

`UNAUTHENTICATED` 401, `FORBIDDEN` 403 (also for "not yours"), `NOT_FOUND` 404, `VALIDATION_FAILED` 400, `QUERY_SYNTAX_ERROR` 400, `TOO_MANY_POINTS` 400, `TOO_MANY_ACCOUNTS` 400, `STATS_NOT_MERGEABLE` 400, `TAIL_UNSUPPORTED_STAGE` 400, `RANGE_OUTSIDE_HOT_WINDOW` 409, `ARCHIVE_REQUIRES_JOB` 409, `CONFLICT` 409, `SCAN_TOO_LARGE` 422, `QUOTA_EXCEEDED` 422 (hosts, rules), `RATE_LIMITED` 429 (`Retry-After`), `HOT_STORE_UNAVAILABLE` 503, `STORAGE_UNAVAILABLE` 503, `AWS_ACCESS_DENIED` 424 (customer role/permission), `AWS_THROTTLED` 429, `QUERY_TIMEOUT` 504, `INTERNAL` 500. Logging: 4xx at DEBUG (no query text), 5xx and `AWS_*` at WARN/ERROR with `requestId`; query text is never logged at INFO (may contain PII); audit rows are written for archive queries and exports regardless.

Internal API rule (same in 04 and 08): public port or non-allowed CIDR returns 404; private port, allowed CIDR, missing or wrong `X-Obs-Internal-Key` returns 401. nginx treats any non-2xx from `/_obs_auth` as deny.

## 8. Query language: XLQ ("XamOps Log Query")
Goal: readable by non-experts, a superset of what the filter bar produces from clicks, compilable to LogsQL (hot) and to parameterised SQL (cold). Keywords are case-insensitive. Time and result size are **not** part of XLQ (`from`/`to`/`limit` are request fields), which keeps saved queries portable.

### 8.1 Tokenizer (applied left to right, longest match)
| Token | Definition |
|---|---|
| whitespace | space, tab, CR, LF; separates tokens, otherwise ignored |
| `STRING` | `"` ... `"` with escapes `\"` `\\` `\n` `\t`; no other escapes; may contain anything else including reserved words, colons and spaces |
| `WORD` | first char `[A-Za-z0-9_.*@/%]`, then `[A-Za-z0-9_.*@/%+-]*`. No `:`, no quotes, no operator chars, no whitespace. Hyphens are allowed **inside** a word (`web-1`, `us-east-1`, `/var/log/syslog` are single WORDs) |
| `NUMBER` | `-?[0-9]+(\.[0-9]+)?`; a leading `-` belongs to the number only directly after a comparison operator (`status>=-1`) |
| `DURATION` | `[0-9]+[smhd]`; recognised only inside `bucket(...)` |
| `REGEX` | `/` ... `/` (a `/` inside is written `\/`); recognised **only** directly after `~` or `!~`. Everywhere else `/` begins or continues a WORD, so paths need no quoting |
| `MINUS` | `-` that starts a token (preceded by whitespace, `(` or start of input) and is immediately followed by a non-space character; synonym of `NOT`. `a-b` is one WORD; `a -b` is `a AND NOT b` |
| `BANG` | `!` at token start; synonym of `NOT` |
| punctuation | `(` `)` `,` `\|` |
| field marker | a WORD **immediately followed (no whitespace)** by `:` or a comparison operator is lexed as `FIELD` followed by that operator. `level:error` and `level=error` are FIELD forms; `level : error` is three terms (`level`, a stray `:` which is a syntax error) |
| comparison operators | `:` `=` `!=` `>` `>=` `<` `<=` `~` `!~` |

Reserved words (case-insensitive, only when bare/unquoted): `AND`, `OR`, `NOT`, and `exists` when followed by `(`. To search for these as text, quote them: `"and"`, `"not"`. Stage keywords (`sort fields stats top dedup by as asc desc`) and aggregate names are recognised **only** after a `|` (or inside a stage) and are ordinary words in the filter part, so `top` or `sort` can be searched without quotes. `limit` is not a keyword at all (`| limit` is rejected with a hint).

### 8.2 Grammar (EBNF over the tokens above)
```ebnf
query        = [ filter ] , { "|" , stage } ;
filter       = or_expr ;
or_expr      = and_expr , { OR , and_expr } ;                      (* lowest precedence *)
and_expr     = unary , { [ AND ] , unary } ;                       (* explicit or implicit AND *)
unary        = ( NOT | MINUS | BANG ) , unary | primary ;          (* highest precedence *)
primary      = "(" , or_expr , ")" | field_expr | text_term ;
field_expr   = FIELD , ( ":" | "=" ) , fvalue
             | FIELD , ":" , "(" , fvalue , { ( OR | "," ) , fvalue } , ")"
             | FIELD , ( "!=" | ">" | ">=" | "<" | "<=" ) , fvalue
             | FIELD , ( "~" | "!~" ) , ( REGEX | STRING )
             | "exists" , "(" , WORD , ")" ;
fvalue       = STRING | WORD | NUMBER ;                            (* WORD may contain * wildcards at start and/or end *)
text_term    = STRING | WORD ;                                     (* WORD must not be a reserved word *)
stage        = "sort" , WORD , [ "asc" | "desc" ]
             | "fields" , WORD , { "," , WORD }
             | "stats" , agg , { "," , agg } , [ "by" , group_item , { "," , group_item } ]
             | "top" , NUMBER , WORD
             | "dedup" , WORD , { "," , WORD } ;
agg          = AGGNAME , "(" , [ WORD ] , ")" , [ "as" , WORD ] ;  (* AGGNAME: count count_unique sum avg min max p50 p90 p95 p99 *)
group_item   = WORD | "bucket" , "(" , DURATION , ")" ;            (* the ONLY place bucket exists *)
```
Precedence and associativity: `NOT` > `AND` (explicit or implicit) > `OR`; all binary operators are left-associative; parentheses override. `a b OR c` parses as `(a AND b) OR c`. `-a b` is `(NOT a) AND b`.

### 8.3 Semantics and limits
- Bare words/strings match `message` (case-insensitive word/phrase match). `error` alone matches the word "error" in the message; `level:error` filters the field. `level=error` and `level:error` are identical for enum and attribute fields; for `message`, `:` is a word/phrase match and `=` is a full-value exact match. `a!=b` is `NOT a=b`.
- Reserved fields: `level, host, host_key, service, type, message, account, region, container, pattern_id, trace_id, source`. Any other field `x` resolves to attribute `x` (`attr.x` hot, `attributes['x']` cold). Field names match `^[A-Za-z_][A-Za-z0-9_.-]{0,127}$`.
- `*` wildcards only at the start and/or end of a value; `~` regex is RE2 (max 256 chars, max 3 regexes per query; cold uses `regexp_matches`). To regex-search the message write `message~/timed? out/`.
- Numeric comparison on attributes casts when numeric, else the row does not match.
- Stages: at most 3; `stats` and `top` are terminal except for a following `sort`; `top N` ≤ 100; `stats` group cardinality cap 1000 (server truncates with `truncated=true`). Filter limits: ≤ 50 terms, nesting ≤ 8.
- `stats ... by bucket(5m)` is the only time-bucketing syntax. Group items are fields or `bucket(DURATION)`.
- Time-restricted or size-restricted behaviour comes only from the request body (`from`, `to`, `limit`).

Examples (XLQ → LogsQL):
| XLQ | LogsQL (hot) |
|---|---|
| `service:nginx level:error "timed out"` | `service:="nginx" level:="error" "timed out"` |
| `host:web-* status>=500` | `host:web-* attr.status:>=500` |
| `level:(error OR fatal) -pattern_id:a1b2c3` | `level:in("error","fatal") -pattern_id:="a1b2c3"` |
| `service:api \| stats count() as n by level` | `service:="api" \| stats by (level) count() as n` |
| `error \| top 10 service` | `error \| top 10 by (service)` |
| `type:auth "Failed password" \| stats count() by bucket(5m)` | `type:="auth" "Failed password" \| stats by (_time:5m) count()` |
| `/var/log/nginx/error.log` (path as bare word, matches message text) | `"/var/log/nginx/error.log"` |
| `"not" found` | `"not" "found"` |
Cold SQL example (parameters bound, never concatenated): `SELECT ts, level, host, service, message, attributes FROM read_parquet(?) WHERE ts >= ? AND ts < ? AND service = ? AND level = ? AND message ILIKE ? ORDER BY ts DESC LIMIT ?`.
Parser errors return `{ "code":"QUERY_SYNTAX_ERROR","details":{"position":17,"expected":["value"],"message":"Expected a value after 'level:'"} }`. NL queries (`/ai/nl-query`) must return XLQ that passes `/logs/validate` before being shown.

### 8.4 Golden parser tests (`XlqParserTest`, 04 section 8; AST shown as S-expression)
| # | Input | Expected |
|---|---|---|
| 1 | `a b OR c` | valid: `(or (and a b) c)` |
| 2 | `a OR b c` | valid: `(or a (and b c))` |
| 3 | `-a b` | valid: `(and (not a) b)` |
| 4 | `a-b` | valid: single `text(a-b)` |
| 5 | `a -b` | valid: `(and a (not b))` |
| 6 | `level=error` | valid: `field(level, eq, error)`, identical AST to `level:error` |
| 7 | `level!=error` | valid: `(not field(level, eq, error))` |
| 8 | `/var/log/syslog` | valid: `text(/var/log/syslog)` (not a regex) |
| 9 | `message~/time(d)? out/` | valid: `regex(message, ...)` |
| 10 | `"and" "or"` | valid: two quoted terms |
| 11 | `error and` | invalid: position 7, expected term after AND |
| 12 | `level:` | invalid: expected value after `level:` |
| 13 | `error \| limit 10` | invalid: `limit` is not a stage; hint "use the page size" |
| 14 | `error \| stats count() by bucket(5m) \| top 3 service` | invalid: `stats` is terminal (only `sort` may follow) |
| 15 | `message~/abc` | invalid: unterminated REGEX at position of `/` |
| 16 | `(a OR (b` | invalid: unbalanced parenthesis, position at end |
| 17 | `service:api \| stats count() by bucket(5m)` | valid: stats with a bucket group item |
| 18 | `top sort` | valid: two text terms (stage words are not reserved in the filter) |

## 9. Desired agent config (served by `GET /api/obs/v1/agent/config`)

```json
{
  "schema": 1, "etag": "9f2c...", "pollSeconds": 60,
  "ingest": { "endpoint": "https://obs-ingest.example.com", "compression": "gzip", "queueMaxBytes": 536870912, "memoryLimitMiB": 256 },
  "resource": { "hostKey": "i-0abc", "extraLabels": { "env": "prod" } },
  "metrics": { "intervalSeconds": 10, "scrapers": ["cpu","memory","disk","filesystem","network","load","paging","processes","process"], "topProcesses": 20 },
  "logs": {
    "sources": [
      { "id": "journald", "enabled": true, "type": "system", "units": [] },
      { "id": "syslog", "enabled": true, "type": "system", "paths": ["/var/log/syslog","/var/log/messages"] },
      { "id": "auth", "enabled": true, "type": "auth", "paths": ["/var/log/auth.log","/var/log/secure"] },
      { "id": "nginx", "enabled": true, "type": "web", "paths": ["/var/log/nginx/*.log"], "parser": "nginx" },
      { "id": "docker", "enabled": true, "type": "container", "runtime": "docker", "paths": ["/var/lib/docker/containers/*/*-json.log"] },
      { "id": "app-custom", "enabled": true, "type": "application", "paths": ["/opt/app/logs/*.log"], "service": "orders",
        "multiline": { "firstLinePattern": "^\\d{4}-\\d{2}-\\d{2}" }, "json": "auto", "level": { "from": "json:level|regex:(?i)\\b(error|warn|info|debug)\\b" } }
    ],
    "exclude": ["/var/log/nginx/healthcheck.log"], "maxLineBytes": 65536
  },
  "prometheus": { "targets": [{ "job": "app", "address": "127.0.0.1:9100", "intervalSeconds": 30 }] },
  "redaction": { "rules": [{ "id": 3, "kind": "REGEX", "pattern": "AKIA[0-9A-Z]{16}", "replacement": "[REDACTED_AWS_KEY]" }], "builtins": ["aws_keys","jwt","bearer","private_key","credit_card_luhn","authorization_header"] },
  "sampling": [{ "match": "service=nginx AND level=debug", "rate": 0.1 }],
  "caps": { "maxGbPerDay": 20, "actionAtCap": "SAMPLE" },
  "update": { "channel": "STABLE", "pinnedVersion": null, "rolloutId": null }
}
```

Validation (server side before storing a profile): paths must be absolute, no `..`, max 50 sources, max 200 path globs, regexes RE2-validated ≤ 512 chars, sampling rate in (0,1], caps ≥ 0.1 GB; unknown keys rejected. `etag` = SHA-256 of canonical JSON; `304` when unchanged.

## 10. Live tail contract (SSE over fetch-streaming)
`GET /api/obs/v1/stream/logs/tail` with `Accept: text/event-stream`. Query parameters:
| Param | Required | Type and limits | Rule |
|---|---|---|---|
| `cloudAccountIds` | yes | comma list of 1 to 10 ids | omitted/empty → `400 VALIDATION_FAILED`; every id authorised with `requireAccess` before upgrade |
| `q` | no | XLQ, ≤ 4,000 chars | filter only; a stage (`stats`, `top`, `dedup`, `sort`, `fields`) → `400 TAIL_UNSUPPORTED_STAGE` |
| `levels` | no | comma list of `trace,debug,info,warn,error,fatal` | becomes `level:(<levels joined by OR>)` AND-ed with `q` |
| `hostKeys` | no | comma list, ≤ 50, each ≤ 190 chars | becomes `host_key:(<keys joined by OR>)` AND-ed with `q`; keys not in the authorised accounts simply match nothing |
| `startFrom` | no | `now` (default) or a back-offset `-30s`, `-1m`, `-5m` (max `-5m`) | back-offset replays up to that much recent history first |
Effective filter = `(q) AND levels AND hostKeys`. Auth: session cookie. Requires `obs.logs.read`. The client uses `fetch()` with `credentials: 'include'` and a streaming body reader, **not** `EventSource`, so that 4xx/5xx JSON bodies are readable before the stream starts and a `Last-Event-ID` header can be sent (05 `lib/sse.ts`).

### 10.1 Pre-stream HTTP errors (JSON body `{code, message, details}`; no upgrade happens)
| HTTP | Code | When |
|---|---|---|
| 400 | `QUERY_SYNTAX_ERROR` | XLQ does not parse (details has position/expected) |
| 400 | `TAIL_UNSUPPORTED_STAGE` | query contains a stage |
| 400 | `VALIDATION_FAILED` / `TOO_MANY_ACCOUNTS` | bad params, more than 10 accounts, bad `startFrom` |
| 401 | `UNAUTHENTICATED` | no session |
| 403 | `FORBIDDEN` | missing `obs.logs.read` or an account not accessible (same body either way) |
| 429 | `RATE_LIMITED` | more than 5 concurrent streams per user or 50 per tenant (`Retry-After`) |
| 503 | `HOT_STORE_UNAVAILABLE` | hot store down for every requested account |

### 10.2 Events (UTF-8, `\n\n` delimited; `id:` is the stream cursor)
| Event | Data schema | Notes |
|---|---|---|
| `status` | `{ "state": "CONNECTED"\|"THROTTLED"\|"RESUMED"\|"RESUMED_WITH_GAP"\|"PARTIAL", "rateLimit"?: 200, "dropped"?: n, "gap"?: true, "failedAccounts"?: [ids], "hint"?: "...", "query"?: "..." }` | sent first (`CONNECTED`, or `RESUMED`/`RESUMED_WITH_GAP` on reconnect); `PARTIAL` when some accounts' upstream failed (stream continues for the others); `THROTTLED` when rows are being dropped |
| `log` | `{ "rows": [Row], "dropped": n }`, `Row` as in 5.4 (including its unique `id`) | batch ≤ 250 ms or 50 rows |
| `heartbeat` | `{ "ts": "ISO-8601" }` | every 15 s, no `id` |
| `error` | `{ "code": "HOT_STORE_UNAVAILABLE"\|"QUERY_TIMEOUT"\|"INTERNAL"\|"FORBIDDEN", "retryable": true\|false }` | followed by `end` |
| `end` | `{ "reason": "MAX_DURATION"\|"CLIENT_CLOSE"\|"PERMISSION_REVOKED"\|"ERROR"\|"SERVER_SHUTDOWN" }` | last event |
Client rule: after `end` with `MAX_DURATION` or `SERVER_SHUTDOWN`, or after `error` with `retryable: true`, reconnect with exponential backoff (1 s, 2 s, 4 s ... cap 30 s, full jitter, give up after 8 attempts with a visible "Reconnect" button) sending `Last-Event-ID`; for `retryable: false`, `PERMISSION_REVOKED` or `CLIENT_CLOSE`, do not reconnect.

### 10.3 Cursor, resume, dedup
- Event `id` = `<tsMillis>-<seq>` where `tsMillis` is the greatest row timestamp in the batch and `seq` is a per-stream monotonic counter assigned by the tail service when it merges rows from multiple accounts/hosts (so it is total-ordered within one stream and meaningless across streams).
- On reconnect the server resumes from `tsMillis` of `Last-Event-ID` **inclusive** (so nothing is lost), bounded to 60 s back; if the cursor is older than 60 s it resumes at `now - 60s` and sends `status RESUMED_WITH_GAP` (`gap: true`), which the UI shows as a "Gap in the stream" divider.
- Inclusive resume can re-send rows, so the client dedups by the **row `id`** (stable: hash of account id, host key, nanosecond timestamp and message hash) using a rolling set of the last 5,000 ids.
- Every upstream (re)subscription re-checks `obs.logs.read` and account access (checked at connect and every 5 minutes; failure → `error{FORBIDDEN,false}` + `end{PERMISSION_REVOKED}`). Redaction rules are applied at ingest, so tailed rows are already redacted; the display layer additionally masks anything matching the tenant's `GATEWAY` rules loaded at connect.
- Tail with several accounts opens one upstream per account and counts as one stream against the 5-per-user / 50-per-tenant limits.

### 10.4 Server rules
Batch ≤ 250 ms or 50 rows; ≤ 200 rows/s per stream (excess dropped and counted in `dropped`, with `status THROTTLED`); stream closes after 30 min with `end{MAX_DURATION}`; the window is "now onward" (history comes from `/logs/search`). Pause/resume is client-side (buffer up to 5,000 rows then drop oldest with a visible counter). nginx location `/api/obs/v1/stream/` with `proxy_buffering off; proxy_read_timeout 3600s; proxy_http_version 1.1;`.
## 11. Permissions
Keys: `obs.view`, `obs.logs.read`, `obs.logs.archive`, `obs.logs.export`, `obs.metrics.advanced`, `obs.agents.manage`, `obs.alerts.manage`, `obs.storage.manage`, `obs.settings.manage`, `obs.ai.use`, `obs.audit.read`. **The single default-grant table** (properties `obs.rbac.defaults.<ROLE>` in 04 section 4 mirror it exactly):
| Role | Default permissions |
|---|---|
| `ROLE_SUPER_ADMIN`, `ROLE_XAMOPS`, `ROLE_ADMIN` | all eleven |
| `ROLE_XAMOPS` (standard customer user, the dominant customer role) | `obs.view`, `obs.logs.read`, `obs.logs.archive`, `obs.agents.manage`, `obs.storage.manage`, `obs.alerts.manage` |
| `ROLE_USER` | `obs.view` |
Admin-only by default (not granted to `ROLE_XAMOPS`): `obs.settings.manage`, `obs.logs.export`, `obs.audit.read`, `obs.ai.use`, `obs.metrics.advanced`. Rationale: `ROLE_XAMOPS` must be able to complete the whole Connect flow (discovery, install, bucket selection, permission stack) because that is the "single easy step" promise; exports, tenant settings, audit and AI are sensitive or cost-bearing and stay with admins unless granted. It is not verified which role a customer administrator holds, so admin-only items can be granted per user. Per-user grants/revocations are stored in `obs_user_permission` (grants add; a `-<permission>` deny row removes a default). Whether standard users should see log content by default is user-preference decision D6 in 00-README; changing it is a property change only. Access to an account always additionally requires `CloudAccountAccessService.requireAccess`. A test (`ConnectFlowRbacTest`, 08) asserts a plain `ROLE_XAMOPS` user can run discovery, pre-flight, an install job, bucket listing, validation and permission-stack launch end to end.
## 12. Machine endpoints

### 12.1 Agent (`X-XamOps-Ingest-Token` unless noted)

| Method + Path | Purpose |
|---|---|
| `POST /api/obs/v1/agent/register` (body has enrollment token) | Enroll; returns `{ "hostId":101, "ingestToken":"xo_ing_...", "ingestTokenExpiresAt":"...", "ingestEndpoint":"https://obs-ingest.example.com", "configEtag":"9f2c...", "pollSeconds":60 }`. Body: `{ "enrollmentToken", "agentVersion", "collectorVersion", "os":{"family","version","arch","kernel"}, "hostname", "identity":{"instanceId","accountId","region","az","instanceType","document","signature"}, "capabilities":["journald","docker"] }`. Idempotent per `(cloud_account, hostKey)`. Errors: `401 ENROLLMENT_INVALID`, `410 ENROLLMENT_EXPIRED`, `409 ENROLLMENT_EXHAUSTED`, `422 QUOTA_EXCEEDED`, `403 IDENTITY_MISMATCH` (document account differs from the connected account). |
| `GET /api/obs/v1/agent/config` | Desired config, `If-None-Match`; `304` when unchanged |
| `POST /api/obs/v1/agent/heartbeat` | Body `{ "agentVersion", "uptimeSeconds", "health":{"queueBytes","dropped","exportErrors","cpu","memBytes"}, "configEtag", "sources":[{"id","state","lastError"}], "firstMetricSent":true, "firstLogSent":true }`; response `{ "commands":[{"id":9,"type":"UPDATE","payload":{...}}], "pollSeconds":60 }` |
| `POST /api/obs/v1/agent/commands/{commandId}/result` | Command outcome |
| `POST /api/obs/v1/agent/token/rotate` | New token with 10-minute overlap |
| `GET /api/obs/v1/agent/releases/latest?os=&arch=&channel=` | Release manifest `{ "version","sha256","size","url","collectorVersion","signature" }` |
| `POST /api/obs/v1/agent/update-report` | `{ "from","to","status":"SUCCEEDED|ROLLED_BACK","error" }` |
| `POST /api/obs/v1/agent/uninstalled` | Final call from uninstall |
| `GET /obs-agent/install.sh`, `GET /obs-agent/download/{version}/manifest.json`, `GET /obs-agent/download/{version}/{file}` (302 to presigned) | Public installer and artifacts |

### 12.2 Internal (`X-Obs-Internal-Key`; reachable only from the gateway and pipeline network)

| Method + Path | Caller | Purpose |
|---|---|---|
| `GET /api/obs/v1/internal/ingest-auth` | nginx `auth_request` | Validate `X-XamOps-Ingest-Token`; response headers `X-Obs-Client`, `X-Obs-Account`, `X-Obs-Host`, `X-Obs-Host-Key` |
| `GET /api/obs/v1/internal/gateway/rules` | gateway config sidecar | Tenant redaction + sampling + cap rules (ETag) |
| `GET /api/obs/v1/internal/archiver/destinations` | archiver | Active destinations with role ARNs and external ids |
| `POST /api/obs/v1/internal/archiver/manifests` | archiver/compactor | Batch insert `obs_archive_manifest` rows |
| `POST /api/obs/v1/internal/archiver/status` | archiver | Destination health `{destinationId,status,detail}` |
| `POST /api/obs/v1/internal/usage` | hotwriter/gateway | Hourly meters (`obs_usage_hourly`) |
| `POST /api/obs/v1/internal/host-activity` | hotwriter | First metric/log seen, last data timestamp per host (batched, drives CONNECTED) |

## 13. Responses to design review (iteration 2)
- Finding 4 (fixed): `encryption_mode`, resolved `kms_key_arn`, validate response fields (2.2, 5.9).
- Finding 5 (fixed): single RBAC default table (section 11).
- Finding 6 and 11 (fixed): tokenizer, reserved words, precedence NOT > AND > OR, `limit` only in the body, `bucket` only as a group item, 18 golden tests (section 8; 5.4 example no longer has `| limit`).
- Finding 7 (fixed): parameter table, pre-stream error table, event schema table with enumerated values, filter combination rule, cursor/resume/dedup rules, fetch-streaming client (section 10).
- Finding 8 (fixed): fan-out and merge semantics, `partial`/`failedAccounts`, new error codes `TOO_MANY_ACCOUNTS`, `STATS_NOT_MERGEABLE`, `TAIL_UNSUPPORTED_STAGE` (5.4, section 7).
- Finding 9 (fixed): `obs_deletion_request` table and `/storage/deletion-requests` endpoints.
