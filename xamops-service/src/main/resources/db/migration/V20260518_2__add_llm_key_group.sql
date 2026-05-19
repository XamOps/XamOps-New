CREATE TABLE IF NOT EXISTS llm_key_group (
    id          BIGSERIAL PRIMARY KEY,
    client_id   BIGINT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    group_key   VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_llm_key_group_client_name UNIQUE (client_id, name)
);

CREATE TABLE IF NOT EXISTS llm_key_group_provider (
    group_id    BIGINT NOT NULL REFERENCES llm_key_group(id) ON DELETE CASCADE,
    provider_id BIGINT NOT NULL REFERENCES llm_provider_config(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, provider_id)
);

CREATE INDEX IF NOT EXISTS idx_llm_key_group_client ON llm_key_group(client_id);
CREATE INDEX IF NOT EXISTS idx_llm_key_group_key    ON llm_key_group(group_key);
