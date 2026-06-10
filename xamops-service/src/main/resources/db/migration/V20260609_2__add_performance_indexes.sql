-- =============================================
-- DevOps in a Box Improvements - Performance Indexes
-- Migration: Add missing composite indexes for query optimization
-- Requirements: REQ-2.6.1, REQ-2.6.4
-- Task: 1.4 Create database indexes for performance
-- =============================================

-- =============================================
-- 1. Add composite indexes on devops_script table
-- =============================================

-- Index for filtering by status with recent-first sorting
-- Used in queries: SELECT * FROM devops_script WHERE status = 'published' ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_devops_script_status_created 
ON devops_script(status, created_at DESC);

-- Index for filtering by category and status
-- Used in queries: SELECT * FROM devops_script WHERE category = 'terraform' AND status = 'published'
CREATE INDEX IF NOT EXISTS idx_devops_script_category_status 
ON devops_script(category, status);

-- =============================================
-- 2. Add composite indexes on script_cloud_providers table
-- =============================================

-- Index for filtering scripts by cloud provider and status
-- Used in queries that join: SELECT ds.* FROM devops_script ds 
-- JOIN script_cloud_providers scp ON ds.id = scp.script_id 
-- WHERE scp.provider = 'aws' AND ds.status = 'published'
-- Note: Since status is in devops_script, we create an index on provider
-- and rely on the join optimization with status index on devops_script
CREATE INDEX IF NOT EXISTS idx_cloud_providers_provider_script 
ON script_cloud_providers(provider, script_id);

-- =============================================
-- 3. Add composite indexes on user_script_interactions table
-- =============================================

-- Composite index for user interaction queries with timestamp
-- Used in queries: SELECT * FROM user_script_interactions 
-- WHERE user_id = ? AND interaction_type = ? ORDER BY timestamp DESC
CREATE INDEX IF NOT EXISTS idx_user_interactions_timestamp 
ON user_script_interactions(user_id, interaction_type, timestamp DESC);

-- Index for script interaction queries with timestamp
-- Used in queries: SELECT * FROM user_script_interactions 
-- WHERE script_id = ? ORDER BY timestamp DESC
CREATE INDEX IF NOT EXISTS idx_script_interactions_timestamp 
ON user_script_interactions(script_id, timestamp DESC);

-- =============================================
-- 4. Add composite indexes on script_ratings table
-- =============================================

-- Index for getting recent ratings for a script
-- Used in queries: SELECT * FROM script_ratings 
-- WHERE script_id = ? ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_script_ratings_script_created 
ON script_ratings(script_id, created_at DESC);

-- =============================================
-- 5. Add comments for documentation
-- =============================================

COMMENT ON INDEX idx_devops_script_status_created IS 'Optimizes queries filtering by status with created_at sorting';
COMMENT ON INDEX idx_devops_script_category_status IS 'Optimizes queries filtering by both category and status';
COMMENT ON INDEX idx_cloud_providers_provider_script IS 'Optimizes cloud provider filtering with join to devops_script';
COMMENT ON INDEX idx_user_interactions_timestamp IS 'Optimizes user interaction history queries with timestamp sorting';
COMMENT ON INDEX idx_script_interactions_timestamp IS 'Optimizes script interaction queries with timestamp sorting';
COMMENT ON INDEX idx_script_ratings_script_created IS 'Optimizes fetching recent ratings for a script';

-- =============================================
-- Migration Complete - Performance Indexes Added
-- =============================================

