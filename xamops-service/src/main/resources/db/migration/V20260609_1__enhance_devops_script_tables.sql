-- =============================================
-- DevOps in a Box Improvements - Enhanced Schema
-- Migration: Add new fields and tables for script improvements
-- Requirements: REQ-2.1.1, REQ-2.1.3, REQ-2.2.1, REQ-2.4.1, REQ-2.4.3, REQ-2.7.1
-- =============================================

-- =============================================
-- 1. Enhance existing devops_script table
-- =============================================

-- Ensure the base table exists. Flyway runs BEFORE Hibernate's ddl-auto, so on a
-- fresh database (e.g. first prod deploy) the table won't have been created yet
-- and the ALTERs below would fail with "relation devops_script does not exist".
-- The entity maps id as GenerationType.IDENTITY (Long) -> BIGSERIAL on Postgres.
-- CREATE ... IF NOT EXISTS is a no-op where the table already exists (e.g. UAT).
CREATE TABLE IF NOT EXISTS devops_script (
    id BIGSERIAL PRIMARY KEY
);

-- Ensure base columns exist (table may have been created by Hibernate with a
-- different/older schema, or these columns may otherwise be missing). These are
-- referenced later in this migration (indexes, UPDATE, INSERT ... WHERE).
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS name VARCHAR(200);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS category VARCHAR(50);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS code TEXT;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS version VARCHAR(20);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS downloads INTEGER DEFAULT 0;

-- Add new columns to devops_script (if they don't already exist)
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS difficulty VARCHAR(50);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS author VARCHAR(100);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS author_id VARCHAR(255);
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS estimated_time_minutes INTEGER;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS prerequisites TEXT;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS related_documentation_url TEXT;

-- Add metrics columns
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS view_count INTEGER DEFAULT 0;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS download_count INTEGER DEFAULT 0;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS copy_count INTEGER DEFAULT 0;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS favorite_count INTEGER DEFAULT 0;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS average_rating DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS rating_count INTEGER DEFAULT 0;

-- Add timestamp columns
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW();
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP;

-- Add status columns
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'published';
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS is_user_contributed BOOLEAN DEFAULT FALSE;
ALTER TABLE devops_script ADD COLUMN IF NOT EXISTS is_featured BOOLEAN DEFAULT FALSE;

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_devops_script_category ON devops_script(category);
CREATE INDEX IF NOT EXISTS idx_devops_script_difficulty ON devops_script(difficulty);
CREATE INDEX IF NOT EXISTS idx_devops_script_status ON devops_script(status);
CREATE INDEX IF NOT EXISTS idx_devops_script_rating ON devops_script(average_rating DESC);
CREATE INDEX IF NOT EXISTS idx_devops_script_downloads ON devops_script(download_count DESC);
CREATE INDEX IF NOT EXISTS idx_devops_script_created_at ON devops_script(created_at DESC);

-- Update existing records with default values
UPDATE devops_script SET 
    difficulty = 'intermediate',
    status = 'published',
    view_count = 0,
    download_count = COALESCE(downloads, 0),
    copy_count = 0,
    favorite_count = 0,
    average_rating = 0.0,
    rating_count = 0,
    is_user_contributed = FALSE,
    is_featured = FALSE,
    created_at = COALESCE(created_at, NOW()),
    updated_at = COALESCE(updated_at, NOW())
WHERE difficulty IS NULL OR status IS NULL;

-- =============================================
-- 2. Create script_tags table (for ElementCollection)
-- =============================================

CREATE TABLE IF NOT EXISTS script_tags (
    script_id BIGINT NOT NULL,
    tag VARCHAR(50) NOT NULL,
    CONSTRAINT fk_script_tags_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_script_tags_script_id ON script_tags(script_id);
CREATE INDEX IF NOT EXISTS idx_script_tags_tag ON script_tags(tag);

-- =============================================
-- 3. Create script_cloud_providers table (for ElementCollection)
-- =============================================

CREATE TABLE IF NOT EXISTS script_cloud_providers (
    script_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    CONSTRAINT fk_script_cloud_providers_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_script_cloud_providers_script_id ON script_cloud_providers(script_id);
CREATE INDEX IF NOT EXISTS idx_script_cloud_providers_provider ON script_cloud_providers(provider);

-- =============================================
-- 4. Create script_ratings table
-- =============================================

CREATE TABLE IF NOT EXISTS script_ratings (
    id BIGSERIAL PRIMARY KEY,
    script_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    review TEXT,
    is_verified BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_script_ratings_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE,
    CONSTRAINT uq_script_rating_user
        UNIQUE (script_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_script_ratings_script_id ON script_ratings(script_id);
CREATE INDEX IF NOT EXISTS idx_script_ratings_user_id ON script_ratings(user_id);
CREATE INDEX IF NOT EXISTS idx_script_ratings_rating ON script_ratings(rating DESC);

-- =============================================
-- 5. Create user_script_interactions table
-- =============================================

CREATE TABLE IF NOT EXISTS user_script_interactions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    script_id BIGINT NOT NULL,
    interaction_type VARCHAR(50) NOT NULL,
    metadata TEXT,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_user_script_interactions_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_interactions ON user_script_interactions(user_id, interaction_type);
CREATE INDEX IF NOT EXISTS idx_script_interactions ON user_script_interactions(script_id, interaction_type);
CREATE INDEX IF NOT EXISTS idx_timestamp ON user_script_interactions(timestamp DESC);

-- =============================================
-- 6. Create script_versions table
-- =============================================

CREATE TABLE IF NOT EXISTS script_versions (
    id BIGSERIAL PRIMARY KEY,
    script_id BIGINT NOT NULL,
    version VARCHAR(20) NOT NULL,
    code TEXT NOT NULL,
    change_log TEXT,
    released_at TIMESTAMP NOT NULL DEFAULT NOW(),
    released_by VARCHAR(100),
    is_deprecated BOOLEAN DEFAULT FALSE,
    
    CONSTRAINT fk_script_versions_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE,
    CONSTRAINT uq_script_version
        UNIQUE (script_id, version)
);

CREATE INDEX IF NOT EXISTS idx_script_versions_script_id ON script_versions(script_id);
CREATE INDEX IF NOT EXISTS idx_script_versions_released_at ON script_versions(released_at DESC);

-- =============================================
-- 7. Create script_parameters table (for customization)
-- =============================================

CREATE TABLE IF NOT EXISTS script_parameters (
    id BIGSERIAL PRIMARY KEY,
    script_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    label VARCHAR(200) NOT NULL,
    description TEXT,
    parameter_type VARCHAR(50) NOT NULL,
    required BOOLEAN DEFAULT TRUE,
    default_value TEXT,
    validation_rules TEXT,
    category VARCHAR(50),
    depends_on VARCHAR(100),
    prefill_from VARCHAR(50),
    display_order INTEGER DEFAULT 0,
    
    CONSTRAINT fk_script_parameters_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_script_params ON script_parameters(script_id, display_order);
CREATE INDEX IF NOT EXISTS idx_script_params_name ON script_parameters(script_id, name);

-- =============================================
-- 8. Create saved_customizations table
-- =============================================

CREATE TABLE IF NOT EXISTS saved_customizations (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    script_id BIGINT NOT NULL,
    customization_name VARCHAR(200) NOT NULL,
    parameter_values TEXT NOT NULL,
    generated_code TEXT,
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP,
    
    CONSTRAINT fk_saved_customizations_script
        FOREIGN KEY (script_id) REFERENCES devops_script(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_customizations ON saved_customizations(user_id, script_id);
CREATE INDEX IF NOT EXISTS idx_saved_customizations_script_id ON saved_customizations(script_id);

-- =============================================
-- 9. Add comments for documentation
-- =============================================

COMMENT ON TABLE devops_script IS 'Enhanced DevOps scripts with multi-cloud support, ratings, and metrics tracking';
COMMENT ON TABLE script_tags IS 'Tags for script categorization and filtering';
COMMENT ON TABLE script_cloud_providers IS 'Cloud provider support for each script (aws, azure, gcp, multi-cloud)';
COMMENT ON TABLE script_ratings IS 'User ratings and reviews for scripts';
COMMENT ON TABLE user_script_interactions IS 'Track user interactions (view, download, copy, favorite) for analytics';
COMMENT ON TABLE script_versions IS 'Version history tracking for scripts';
COMMENT ON TABLE script_parameters IS 'Customizable parameters for script templates';
COMMENT ON TABLE saved_customizations IS 'User-saved script customization presets';

-- =============================================
-- 10. Insert sample tags and cloud providers for existing scripts
-- =============================================

-- Add sample tags for existing scripts (if any exist)
INSERT INTO script_tags (script_id, tag)
SELECT id, 'infrastructure' FROM devops_script WHERE category = 'terraform' 
ON CONFLICT DO NOTHING;

INSERT INTO script_tags (script_id, tag)
SELECT id, 'automation' FROM devops_script WHERE category = 'bash'
ON CONFLICT DO NOTHING;

-- Add cloud providers based on script content/name (basic heuristics)
INSERT INTO script_cloud_providers (script_id, provider)
SELECT id, 'aws' FROM devops_script 
WHERE LOWER(name) LIKE '%aws%' OR LOWER(description) LIKE '%aws%'
ON CONFLICT DO NOTHING;

INSERT INTO script_cloud_providers (script_id, provider)
SELECT id, 'azure' FROM devops_script 
WHERE LOWER(name) LIKE '%azure%' OR LOWER(description) LIKE '%azure%'
ON CONFLICT DO NOTHING;

INSERT INTO script_cloud_providers (script_id, provider)
SELECT id, 'gcp' FROM devops_script 
WHERE LOWER(name) LIKE '%gcp%' OR LOWER(description) LIKE '%google%'
ON CONFLICT DO NOTHING;

-- Mark scripts without specific cloud provider as multi-cloud
INSERT INTO script_cloud_providers (script_id, provider)
SELECT DISTINCT d.id, 'multi-cloud'
FROM devops_script d
LEFT JOIN script_cloud_providers scp ON d.id = scp.script_id
WHERE scp.script_id IS NULL
ON CONFLICT DO NOTHING;

-- =============================================
-- Migration Complete
-- =============================================
