-- Time-series history for DB agent metrics (one row per push, not upserted)
-- Enables throughput, connection, and buffer pool charts over time.
-- Retained for 30 days; older rows are pruned by a scheduled job.

CREATE TABLE IF NOT EXISTS db_agent_metrics_history (
    id            BIGSERIAL PRIMARY KEY,
    db_agent_id   BIGINT       NOT NULL,
    data_type     VARCHAR(50)  NOT NULL,
    data_json     TEXT,
    item_count    INT,
    collected_at  TIMESTAMP    NOT NULL,
    received_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_db_metrics_hist_agent
        FOREIGN KEY (db_agent_id) REFERENCES db_agent_config(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_db_metrics_hist_agent     ON db_agent_metrics_history(db_agent_id);
CREATE INDEX IF NOT EXISTS idx_db_metrics_hist_type      ON db_agent_metrics_history(data_type);
CREATE INDEX IF NOT EXISTS idx_db_metrics_hist_collected ON db_agent_metrics_history(collected_at);
