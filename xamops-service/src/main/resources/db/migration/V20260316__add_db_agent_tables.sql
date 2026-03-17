-- =============================================
-- DbOps Agent Support — separate from K8s cluster agents
-- DB agents run on EC2, not in Kubernetes
-- =============================================

-- 1. DB Agent registration table
CREATE TABLE IF NOT EXISTS db_agent_config (
    id          BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL,
    agent_name  VARCHAR(100) NOT NULL,          -- CLUSTER_NAME env var value
    status      VARCHAR(20)  DEFAULT 'pending', -- pending | active | inactive
    agent_version VARCHAR(20),
    agent_ip    VARCHAR(45),
    last_checkin TIMESTAMP,
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT fk_db_agent_account
        FOREIGN KEY (cloud_account_id) REFERENCES cloud_account(id) ON DELETE CASCADE,
    CONSTRAINT uq_db_agent_name
        UNIQUE (cloud_account_id, agent_name)
);

CREATE INDEX IF NOT EXISTS idx_db_agent_account ON db_agent_config(cloud_account_id);
CREATE INDEX IF NOT EXISTS idx_db_agent_status  ON db_agent_config(status);

-- 2. DB Agent data cache — stores metrics/queries pushed by DB agents
CREATE TABLE IF NOT EXISTS db_agent_data_cache (
    id           BIGSERIAL PRIMARY KEY,
    db_agent_id  BIGINT       NOT NULL,
    data_type    VARCHAR(50)  NOT NULL,  -- e.g. "db-metrics", "slow-queries", "db-connections"
    data_json    TEXT,
    item_count   INT          DEFAULT 0,
    agent_version VARCHAR(20),
    collected_at TIMESTAMP    NOT NULL,
    received_at  TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT fk_db_agent_data
        FOREIGN KEY (db_agent_id) REFERENCES db_agent_config(id) ON DELETE CASCADE,
    CONSTRAINT uq_db_agent_data_type
        UNIQUE (db_agent_id, data_type)
);

CREATE INDEX IF NOT EXISTS idx_db_agent_data_agent    ON db_agent_data_cache(db_agent_id);
CREATE INDEX IF NOT EXISTS idx_db_agent_data_type     ON db_agent_data_cache(data_type);
CREATE INDEX IF NOT EXISTS idx_db_agent_data_collected ON db_agent_data_cache(collected_at);
