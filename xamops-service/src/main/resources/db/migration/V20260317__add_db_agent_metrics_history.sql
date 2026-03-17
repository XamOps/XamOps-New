-- Time-series history for DB agent metrics (one row per push, not upserted)
-- Enables throughput, connection, and buffer pool charts over time.
-- Retained for 30 days; older rows pruned by scheduled cleanup.

CREATE TABLE IF NOT EXISTS db_agent_metrics_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_agent_id   BIGINT       NOT NULL,
    data_type     VARCHAR(50)  NOT NULL,
    data_json     TEXT,
    item_count    INT,
    collected_at  DATETIME(6)  NOT NULL,
    received_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_db_metrics_hist_agent     (db_agent_id),
    INDEX idx_db_metrics_hist_type      (data_type),
    INDEX idx_db_metrics_hist_collected (collected_at),

    CONSTRAINT fk_db_metrics_hist_agent
        FOREIGN KEY (db_agent_id) REFERENCES db_agent_config(id) ON DELETE CASCADE
);
