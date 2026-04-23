-- Identity Management tables for AccessOps module
-- Authentik is the headless identity backend; these tables store per-tenant
-- configuration and a local audit trail. Authentik is the source of truth for
-- users/groups; this DB never duplicates that data.

CREATE TABLE IF NOT EXISTS client_identity_config (
    id                        BIGSERIAL    PRIMARY KEY,
    client_id                 BIGINT       NOT NULL UNIQUE REFERENCES client(id),
    -- AES-256-GCM encrypted Authentik API token for this tenant's service account.
    -- Format: base64(iv[12] || ciphertext || tag[16])
    authentik_token_encrypted TEXT         NOT NULL,
    -- Prefix used for all Authentik groups belonging to this tenant, e.g. "Acme-"
    -- Backend enforces this prefix on every Authentik API response (hard isolation).
    group_prefix              VARCHAR(100) NOT NULL,
    -- Authentik pk of the service account user created for this tenant
    service_account_pk        VARCHAR(255),
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Maps an Authentik group (owned by this tenant) to an AWS account + permission set.
-- Used to display AWS context on the Groups page. Populated when a group is linked
-- to an AWS account during tenant setup.
CREATE TABLE IF NOT EXISTS client_aws_access (
    id                  BIGSERIAL    PRIMARY KEY,
    client_id           BIGINT       NOT NULL REFERENCES client(id),
    -- UUID of the Authentik group (core/groups pk)
    authentik_group_id  VARCHAR(255) NOT NULL,
    group_name          VARCHAR(255) NOT NULL,
    aws_account_id      VARCHAR(255),
    aws_account_name    VARCHAR(255),
    -- Name of the AWS IAM Identity Center permission set
    permission_set_name VARCHAR(255),
    UNIQUE (client_id, authentik_group_id)
);

-- Immutable audit trail. Written BEFORE every Authentik mutation so that a failed
-- Authentik call still leaves an attempted-action record.
CREATE TABLE IF NOT EXISTS identity_audit_log (
    id                BIGSERIAL    PRIMARY KEY,
    client_id         BIGINT       NOT NULL REFERENCES client(id),
    -- XamOps User.id of the admin who performed the action
    actor_user_id     BIGINT,
    -- e.g. USER_INVITED, USER_DEACTIVATED, USER_ADDED_TO_GROUP, USER_REMOVED_FROM_GROUP
    action            VARCHAR(100) NOT NULL,
    target_user_email VARCHAR(255),
    group_name        VARCHAR(255),
    -- Additional context stored as JSON (Authentik response snippet, error message, etc.)
    metadata          TEXT,
    timestamp         TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ial_client_ts ON identity_audit_log(client_id, timestamp DESC);
