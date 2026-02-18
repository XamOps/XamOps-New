-- =============================================
-- XamOps Agent Support - Database Migration
-- Run on: LOCAL, UAT, LIVE (all environments)
-- =============================================

-- 1. Add data_source_mode column to kubernetes_cluster_config
ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS data_source_mode VARCHAR(20) DEFAULT 'kubeconfig';

-- 2. Add agent tracking columns to kubernetes_cluster_config
ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS agent_api_key VARCHAR(64);

ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS agent_version VARCHAR(20);

ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS last_agent_checkin TIMESTAMP;

ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS agent_status VARCHAR(20) DEFAULT 'pending';

-- 3. Create agent_data_cache table
CREATE TABLE IF NOT EXISTS agent_data_cache (
    id BIGSERIAL PRIMARY KEY,
    cluster_config_id BIGINT NOT NULL,
    data_type VARCHAR(50) NOT NULL,
    data_json TEXT,
    item_count INT DEFAULT 0,
    agent_version VARCHAR(20),
    collected_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_agent_data_cluster
        FOREIGN KEY (cluster_config_id)
        REFERENCES kubernetes_cluster_config(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_agent_data_cluster_type
        UNIQUE (cluster_config_id, data_type)
);

CREATE INDEX IF NOT EXISTS idx_agent_data_cluster
    ON agent_data_cache(cluster_config_id);

CREATE INDEX IF NOT EXISTS idx_agent_data_type
    ON agent_data_cache(data_type);

CREATE INDEX IF NOT EXISTS idx_agent_data_collected
    ON agent_data_cache(collected_at);

-- 4. Create agent_event table for logging
CREATE TABLE IF NOT EXISTS agent_event (
    id BIGSERIAL PRIMARY KEY,
    cluster_config_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    agent_version VARCHAR(20),
    agent_ip VARCHAR(45),
    items_received INT DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_agent_event_cluster
        FOREIGN KEY (cluster_config_id)
        REFERENCES kubernetes_cluster_config(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_event_cluster
    ON agent_event(cluster_config_id);

CREATE INDEX IF NOT EXISTS idx_agent_event_created
    ON agent_event(created_at);

-- 5. Comment for documentation
COMMENT ON TABLE agent_data_cache IS 'Stores data pushed by XamOps agents from customer clusters';
COMMENT ON TABLE agent_event IS 'Logs agent push events for monitoring and debugging';
COMMENT ON COLUMN kubernetes_cluster_config.data_source_mode IS 'How to fetch data: agent, kubeconfig, or hybrid';
