CREATE TABLE IF NOT EXISTS cloudsitter_audit_log (
    id             BIGSERIAL    PRIMARY KEY,
    account_id     VARCHAR(255) NOT NULL,
    instance_id    VARCHAR(255) NOT NULL,
    instance_name  VARCHAR(255),
    region         VARCHAR(100),
    action         VARCHAR(50)  NOT NULL,
    policy_id      BIGINT,
    policy_name    VARCHAR(255),
    trigger_reason VARCHAR(100) NOT NULL DEFAULT 'SCHEDULE',
    previous_state VARCHAR(50),
    outcome        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    error_message  TEXT,
    timestamp      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cs_audit_account   ON cloudsitter_audit_log (account_id);
CREATE INDEX IF NOT EXISTS idx_cs_audit_instance  ON cloudsitter_audit_log (instance_id);
CREATE INDEX IF NOT EXISTS idx_cs_audit_timestamp ON cloudsitter_audit_log (timestamp DESC);
