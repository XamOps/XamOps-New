-- Multiple API keys per provider with load balancing
CREATE TABLE llm_provider_key (
    id                  BIGSERIAL PRIMARY KEY,
    provider_config_id  BIGINT NOT NULL REFERENCES llm_provider_config(id) ON DELETE CASCADE,
    encrypted_api_key   TEXT NOT NULL,
    key_hint            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    current_requests    INT NOT NULL DEFAULT 0,
    rate_limited_until  TIMESTAMP,
    total_requests      BIGINT NOT NULL DEFAULT 0,
    total_cost          DECIMAL(14,6) NOT NULL DEFAULT 0,
    total_tokens        BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lpk_provider_config_id ON llm_provider_key(provider_config_id);
CREATE INDEX idx_lpk_status ON llm_provider_key(status);

-- Add load balance strategy to provider config
ALTER TABLE llm_provider_config
ADD COLUMN IF NOT EXISTS load_balance_strategy VARCHAR(30) NOT NULL DEFAULT 'ROUND_ROBIN';

-- Migrate existing keys: if api_key_encrypted exists on a provider, insert it as the first key
INSERT INTO llm_provider_key (provider_config_id, encrypted_api_key, key_hint, status)
SELECT id, api_key_encrypted, 'migrated', 'ACTIVE'
FROM llm_provider_config
WHERE api_key_encrypted IS NOT NULL AND api_key_encrypted != '';
