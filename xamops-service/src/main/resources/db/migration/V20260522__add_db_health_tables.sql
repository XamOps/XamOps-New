CREATE TABLE db_health_snapshot (
    id            BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id    BIGINT          NOT NULL,
    instance_id   VARCHAR(255)    NOT NULL,
    score         INT             NOT NULL,
    status        VARCHAR(20)     NOT NULL,
    issues_json   TEXT,
    computed_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_snapshot_lookup (account_id, instance_id, computed_at)
);

CREATE TABLE db_health_alert (
    id            BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id    BIGINT          NOT NULL,
    instance_id   VARCHAR(255)    NOT NULL,
    alert_type    VARCHAR(50)     NOT NULL,
    severity      VARCHAR(20)     NOT NULL,
    message       TEXT            NOT NULL,
    triggered_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at   TIMESTAMP       NULL,
    is_active     BOOLEAN         NOT NULL DEFAULT TRUE,
    INDEX idx_alert_lookup (account_id, instance_id, alert_type, is_active),
    INDEX idx_alert_feed   (account_id, is_active, triggered_at)
);
