ALTER TABLE llm_provider_config
    ADD COLUMN IF NOT EXISTS enabled_models TEXT;

-- Backfill all existing rows with the full model list for their provider type
UPDATE llm_provider_config SET enabled_models = 'gpt-4o,gpt-4o-mini,gpt-4-turbo,gpt-3.5-turbo,o1,o3-mini'
    WHERE provider = 'OPENAI' AND enabled_models IS NULL;

UPDATE llm_provider_config SET enabled_models = 'claude-sonnet-4-5-20250929,claude-opus-4-5-20250929,claude-3-5-sonnet-20241022,claude-3-5-haiku-20241022,claude-3-haiku-20240307'
    WHERE provider = 'ANTHROPIC' AND enabled_models IS NULL;

UPDATE llm_provider_config SET enabled_models = 'gemini-2.0-flash,gemini-1.5-pro,gemini-1.5-flash'
    WHERE provider = 'GEMINI' AND enabled_models IS NULL;

UPDATE llm_provider_config SET enabled_models = 'anthropic.claude-3-5-sonnet-20241022-v2:0,anthropic.claude-3-haiku-20240307-v1:0,amazon.titan-text-express-v1,meta.llama3-70b-instruct-v1:0'
    WHERE provider = 'BEDROCK' AND enabled_models IS NULL;
