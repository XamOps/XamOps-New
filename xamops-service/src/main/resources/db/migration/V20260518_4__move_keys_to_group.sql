-- Drop old provider_config_id FK and add key_group_id + provider
ALTER TABLE llm_provider_key DROP CONSTRAINT IF EXISTS llm_provider_key_provider_config_id_fkey;
ALTER TABLE llm_provider_key DROP COLUMN IF EXISTS provider_config_id;
ALTER TABLE llm_provider_key ADD COLUMN IF NOT EXISTS key_group_id BIGINT REFERENCES llm_key_group(id) ON DELETE CASCADE;
ALTER TABLE llm_provider_key ADD COLUMN IF NOT EXISTS provider VARCHAR(30);

-- Move load_balance_strategy from provider config to group
ALTER TABLE llm_key_group ADD COLUMN IF NOT EXISTS load_balance_strategy VARCHAR(30) NOT NULL DEFAULT 'ROUND_ROBIN';
ALTER TABLE llm_provider_config DROP COLUMN IF EXISTS load_balance_strategy;

-- Indexes
DROP INDEX IF EXISTS idx_lpk_provider_config_id;
CREATE INDEX IF NOT EXISTS idx_lpk_key_group_id ON llm_provider_key(key_group_id);
CREATE INDEX IF NOT EXISTS idx_lpk_key_group_provider ON llm_provider_key(key_group_id, provider);
