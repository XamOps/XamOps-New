-- Fix all previous incorrect Claude 4 model IDs to the verified correct form
UPDATE llm_provider_config
SET enabled_models = REPLACE(REPLACE(REPLACE(enabled_models,
    'claude-sonnet-4-5-20250514', 'claude-sonnet-4-5-20250929'),
    'claude-sonnet-4-20250514',   'claude-sonnet-4-5-20250929'),
    'claude-sonnet-4-5',          'claude-sonnet-4-5-20250929')
WHERE enabled_models LIKE '%claude-sonnet-4%'
  AND enabled_models NOT LIKE '%claude-sonnet-4-5-20250929%';

UPDATE llm_provider_config
SET enabled_models = REPLACE(REPLACE(REPLACE(enabled_models,
    'claude-opus-4-5-20250514', 'claude-opus-4-5-20250929'),
    'claude-opus-4-20250514',   'claude-opus-4-5-20250929'),
    'claude-opus-4-5',          'claude-opus-4-5-20250929')
WHERE enabled_models LIKE '%claude-opus-4%'
  AND enabled_models NOT LIKE '%claude-opus-4-5-20250929%';
