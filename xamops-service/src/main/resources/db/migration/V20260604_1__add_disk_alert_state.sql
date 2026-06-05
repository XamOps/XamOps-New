-- Tracks per-volume alert state for deduplication.
-- One row per (account, volume, alert_type). resolved_at IS NULL = active alert.
CREATE TABLE disk_alert_state (
    id              BIGSERIAL    PRIMARY KEY,
    account_id      BIGINT       NOT NULL REFERENCES cloud_account(id),
    volume_id       VARCHAR(100) NOT NULL,
    region          VARCHAR(50)  NOT NULL,
    alert_type      VARCHAR(20)  NOT NULL,   -- NEAR_FULL | UNDERUTILIZED
    alerted_at      TIMESTAMP    NOT NULL,
    resolved_at     TIMESTAMP,
    CONSTRAINT uq_disk_alert UNIQUE (account_id, volume_id, alert_type)
);

CREATE INDEX idx_disk_alert_account_active ON disk_alert_state(account_id) WHERE resolved_at IS NULL;
