-- =============================================
-- Index Verification Script
-- Purpose: Verify that all performance indexes are being used correctly
-- Task: 1.4 Create database indexes for performance
-- =============================================

-- =============================================
-- 1. Verify devops_script indexes
-- =============================================

-- Test 1: Filter by status with sorting by created_at
-- Should use: idx_devops_script_status_created
EXPLAIN ANALYZE
SELECT * FROM devops_script 
WHERE status = 'published' 
ORDER BY created_at DESC 
LIMIT 20;

-- Test 2: Filter by category and status
-- Should use: idx_devops_script_category_status
EXPLAIN ANALYZE
SELECT * FROM devops_script 
WHERE category = 'terraform' AND status = 'published';

-- Test 3: Filter by difficulty
-- Should use: idx_devops_script_difficulty
EXPLAIN ANALYZE
SELECT * FROM devops_script 
WHERE difficulty = 'beginner';

-- =============================================
-- 2. Verify script_cloud_providers indexes
-- =============================================

-- Test 4: Join scripts with cloud provider filter
-- Should use: idx_cloud_providers_provider_script and idx_devops_script_status
EXPLAIN ANALYZE
SELECT ds.* 
FROM devops_script ds
JOIN script_cloud_providers scp ON ds.id = scp.script_id
WHERE scp.provider = 'aws' AND ds.status = 'published'
ORDER BY ds.created_at DESC
LIMIT 20;

-- =============================================
-- 3. Verify user_script_interactions indexes
-- =============================================

-- Test 5: Get user interaction history with type filter
-- Should use: idx_user_interactions_timestamp
EXPLAIN ANALYZE
SELECT * FROM user_script_interactions
WHERE user_id = 'test-user-123' 
  AND interaction_type = 'view'
ORDER BY timestamp DESC
LIMIT 50;

-- Test 6: Get script interaction history
-- Should use: idx_script_interactions_timestamp
EXPLAIN ANALYZE
SELECT * FROM user_script_interactions
WHERE script_id = 1
ORDER BY timestamp DESC
LIMIT 50;

-- Test 7: Get all interactions for a script with type filter
-- Should use: idx_script_interactions
EXPLAIN ANALYZE
SELECT COUNT(*) 
FROM user_script_interactions
WHERE script_id = 1 AND interaction_type = 'download';

-- =============================================
-- 4. Verify script_ratings indexes
-- =============================================

-- Test 8: Get recent ratings for a script
-- Should use: idx_script_ratings_script_created
EXPLAIN ANALYZE
SELECT * FROM script_ratings
WHERE script_id = 1
ORDER BY created_at DESC
LIMIT 10;

-- Test 9: Get user's rating for a script
-- Should use: uq_script_rating_user (unique constraint index)
EXPLAIN ANALYZE
SELECT * FROM script_ratings
WHERE script_id = 1 AND user_id = 'test-user-123';

-- Test 10: Get high-rated scripts
-- Should use: idx_script_ratings_rating on devops_script
EXPLAIN ANALYZE
SELECT * FROM devops_script
WHERE average_rating >= 4.0
ORDER BY average_rating DESC
LIMIT 20;

-- =============================================
-- 5. Verify script_parameters indexes
-- =============================================

-- Test 11: Get parameters for a script in display order
-- Should use: idx_script_params
EXPLAIN ANALYZE
SELECT * FROM script_parameters
WHERE script_id = 1
ORDER BY display_order;

-- =============================================
-- 6. Complex query tests combining multiple indexes
-- =============================================

-- Test 12: Search with multiple filters (trending scripts)
-- Should use multiple indexes
EXPLAIN ANALYZE
SELECT ds.*, 
       COUNT(DISTINCT usi.id) as recent_interactions
FROM devops_script ds
LEFT JOIN user_script_interactions usi 
  ON ds.id = usi.script_id 
  AND usi.timestamp > NOW() - INTERVAL '7 days'
WHERE ds.status = 'published'
  AND ds.average_rating >= 3.0
GROUP BY ds.id
HAVING COUNT(DISTINCT usi.id) >= 10
ORDER BY recent_interactions DESC, ds.average_rating DESC
LIMIT 10;

-- Test 13: Get user's favorited scripts with details
EXPLAIN ANALYZE
SELECT ds.*
FROM devops_script ds
JOIN user_script_interactions usi 
  ON ds.id = usi.script_id
WHERE usi.user_id = 'test-user-123'
  AND usi.interaction_type = 'favorite'
  AND ds.status = 'published'
ORDER BY usi.timestamp DESC;

-- =============================================
-- 7. List all indexes for verification
-- =============================================

-- Show all indexes on devops_script table
SELECT 
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'devops_script'
ORDER BY indexname;

-- Show all indexes on user_script_interactions table
SELECT 
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'user_script_interactions'
ORDER BY indexname;

-- Show all indexes on script_ratings table
SELECT 
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'script_ratings'
ORDER BY indexname;

-- Show all indexes on script_parameters table
SELECT 
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'script_parameters'
ORDER BY indexname;

-- Show all indexes on script_cloud_providers table
SELECT 
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'script_cloud_providers'
ORDER BY indexname;

-- =============================================
-- 8. Check index sizes and usage statistics
-- =============================================

-- Show index sizes for devops_script related tables
SELECT
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE tablename IN ('devops_script', 'user_script_interactions', 
                    'script_ratings', 'script_parameters', 
                    'script_cloud_providers')
ORDER BY tablename, indexname;

-- =============================================
-- Verification Complete
-- =============================================

