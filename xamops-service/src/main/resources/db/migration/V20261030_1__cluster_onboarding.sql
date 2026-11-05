-- =============================================================================
-- CloudK8s two-phase cluster onboarding — schema (design §2.1).
--
-- VERSION CHOICE (design-delta §8): this migration is V20261030_1, NOT the
-- design's original V20261024_1 and NOT V20261024_40. The current max on
-- origin/main is V20261024_39; concurrent in-flight branches carry up to
-- V20261029_1; prod runs spring.flyway.out-of-order=true. 2026-10-30 sorts
-- strictly above every version in use, so this cannot collide or apply
-- out-of-order behind an in-flight migration. If V20261030_1 is claimed by
-- merge time, take the next free V20261030_N.
--
-- COLUMN TYPES MATCH HIBERNATE. UAT runs ddl-auto=update against the shared
-- xamops_db_uat, so Hibernate may create a column/table before Flyway sees it.
-- Types below equal Hibernate's mapping (Instant -> TIMESTAMPTZ, Long -> BIGINT,
-- String -> VARCHAR(n), boolean -> BOOLEAN) so whichever runs first the other
-- has nothing to alter. Everything is IF NOT EXISTS and the file is re-runnable.
-- This file changes no existing column's data.
-- =============================================================================

-- 1. New columns on kubernetes_cluster_config (all nullable; existing rows unaffected)
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS agent_key_issued_at          TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS agent_key_last_used_at       TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS last_agent_key_scope         VARCHAR(16);   -- CLUSTER|ACCOUNT|LEGACY_GLOBAL (scope the data agent last authenticated with)
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_agent_api_key     VARCHAR(64);
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_key_issued_at     TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_key_last_used_at  TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS last_exchange_at             TIMESTAMPTZ;   -- last successful token exchange (any purpose)
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS identity_mode                VARCHAR(16);   -- POD_IDENTITY|IRSA|NONE
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS iam_stack_name               VARCHAR(128);
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS read_role_arn                VARCHAR(512);
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS write_role_arn               VARCHAR(512);
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS node_scaler                  VARCHAR(24);   -- KARPENTER|CLUSTER_AUTOSCALER|BOTH|NONE|EKS_AUTO_MODE|UNKNOWN
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS node_scaler_detected_at      TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS onboarding_features          VARCHAR(255);  -- comma list of FeatureId
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_consent_at        TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_consent_by        BIGINT;        -- users.id, no FK (user deletion must not cascade)
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_permission_hash   VARCHAR(64);
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_verified_at       TIMESTAMPTZ;
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_identity_arn      VARCHAR(512);  -- last reported sts caller ARN of the automation agent
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_identity_seen_at  TIMESTAMPTZ;   -- last heartbeat whose ARN matched write_role_arn (§3.4)
ALTER TABLE kubernetes_cluster_config ADD COLUMN IF NOT EXISTS automation_disabled_at       TIMESTAMPTZ;   -- set by disable-automation, cleared by a successful ENABLE_AUTOMATION exchange (§3.1a)

CREATE UNIQUE INDEX IF NOT EXISTS uq_kcc_automation_agent_api_key
    ON kubernetes_cluster_config(automation_agent_api_key) WHERE automation_agent_api_key IS NOT NULL;

-- 2. One-time connect tokens
CREATE TABLE IF NOT EXISTS cluster_connect_token (
    id                  BIGSERIAL    PRIMARY KEY,
    token_hash          VARCHAR(64)  NOT NULL,             -- lowercase hex SHA-256 of the full token
    token_prefix        VARCHAR(12)  NOT NULL,             -- 'xct_' + the next 8 chars (12 total), for logs/UI everywhere
    cluster_config_id   BIGINT       NOT NULL REFERENCES kubernetes_cluster_config(id) ON DELETE CASCADE,
    purpose             VARCHAR(32)  NOT NULL,             -- CONNECT_READ_ONLY|ENABLE_AUTOMATION|ROTATE_DATA_KEY|ROTATE_AUTOMATION_KEY
    features            VARCHAR(255) NOT NULL,
    create_read_role    BOOLEAN      NOT NULL,             -- CONNECT_READ_ONLY: the user's choice; every other purpose: (config.read_role_arn IS NOT NULL) at issue (§4.3a)
    permission_hash     VARCHAR(64),                       -- required for ENABLE_AUTOMATION
    node_scaler         VARCHAR(24),                       -- snapshot shown to the user (ENABLE_AUTOMATION); never copied into config.node_scaler
    identity_mode       VARCHAR(16),                       -- CONNECT: server preflight snapshot; other purposes: stack identity mode (§4.3a)
    node_removal        BOOLEAN      NOT NULL DEFAULT FALSE, -- the §5.3 predicate, evaluated at issue (ENABLE_AUTOMATION)
    issued_by_user_id   BIGINT       NOT NULL,
    issued_at           TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    used_at             TIMESTAMPTZ,
    used_from_ip        VARCHAR(45),
    revoked_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cct_token_hash ON cluster_connect_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_cct_cluster_purpose ON cluster_connect_token(cluster_config_id, purpose);

-- 3. Onboarding audit log — deliberately NO FK so it survives offboarding (FR-8.2)
CREATE TABLE IF NOT EXISTS cluster_onboarding_event (
    id                  BIGSERIAL    PRIMARY KEY,
    client_id           BIGINT,
    cloud_account_pk    BIGINT       NOT NULL,             -- cloud_account.id (never the AWS number; see B4 note in V20261013_1)
    cluster_name        VARCHAR(255) NOT NULL,
    cluster_config_id   BIGINT,                            -- informational; may point to a deleted row
    event_type          VARCHAR(40)  NOT NULL,             -- see §2.3
    actor_type          VARCHAR(16)  NOT NULL,             -- USER|INSTALLER|AGENT|SYSTEM
    actor_user_id       BIGINT,
    detail_json         TEXT,                              -- never contains a token or key
    created_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_coe_account_cluster ON cluster_onboarding_event(cloud_account_pk, cluster_name, created_at);

-- 4. Offboarding tombstone — blocks auto-register by stale agents (FR-8.2)
CREATE TABLE IF NOT EXISTS cluster_offboarding_tombstone (
    id                  BIGSERIAL    PRIMARY KEY,
    cloud_account_pk    BIGINT       NOT NULL,
    cluster_name        VARCHAR(255) NOT NULL,
    offboarded_at       TIMESTAMPTZ  NOT NULL,
    offboarded_by       BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cot_account_cluster ON cluster_offboarding_tombstone(cloud_account_pk, cluster_name);
