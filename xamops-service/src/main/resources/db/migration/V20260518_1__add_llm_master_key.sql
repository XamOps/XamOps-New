CREATE TABLE IF NOT EXISTS llm_master_key (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    master_key VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_llm_master_key_client UNIQUE (client_id)
);

CREATE INDEX IF NOT EXISTS idx_llm_master_key_client ON llm_master_key(client_id);
CREATE INDEX IF NOT EXISTS idx_llm_master_key_key    ON llm_master_key(master_key);
