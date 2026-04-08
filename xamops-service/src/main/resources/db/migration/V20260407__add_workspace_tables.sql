-- ============================================================
-- Google Workspace Integration Tables
-- Stores the service-account connection per tenant and cached
-- user data synced from Google Admin Directory API.
-- ============================================================

-- 1. One workspace connection per XamOps client/tenant
CREATE TABLE workspace_connection (
    id                  BIGSERIAL       PRIMARY KEY,
    client_id           BIGINT          NOT NULL UNIQUE REFERENCES client(id) ON DELETE CASCADE,
    domain              VARCHAR(255)    NOT NULL,
    admin_email         VARCHAR(255)    NOT NULL,
    service_account_key TEXT            NOT NULL,
    status              VARCHAR(50)     NOT NULL DEFAULT 'DISCONNECTED',
    error_message       TEXT,
    total_users         INT,
    active_users        INT,
    inactive_users      INT,
    suspended_users     INT,
    last_synced_at      TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ws_conn_client ON workspace_connection(client_id);

-- 2. Cached Google Workspace user records (rebuilt on every sync)
CREATE TABLE workspace_user (
    id              BIGSERIAL       PRIMARY KEY,
    connection_id   BIGINT          NOT NULL REFERENCES workspace_connection(id) ON DELETE CASCADE,
    primary_email   VARCHAR(255)    NOT NULL,
    full_name       VARCHAR(255),
    org_unit_path   VARCHAR(500),
    is_suspended    BOOLEAN         NOT NULL DEFAULT FALSE,
    is_admin        BOOLEAN         NOT NULL DEFAULT FALSE,
    last_login_time TIMESTAMP,
    created_time    TIMESTAMP,
    days_since_login INT,
    user_status     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    synced_at       TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ws_user_connection ON workspace_user(connection_id);
CREATE INDEX idx_ws_user_email      ON workspace_user(primary_email);
CREATE INDEX idx_ws_user_status     ON workspace_user(connection_id, user_status);
