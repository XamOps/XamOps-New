
-- Fix for User Creation SQL Error
-- Adds the missing 'enabled' column to app_user and global_users tables

-- 1. Update app_user table (for User entity)
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE;

-- 2. Update global_users table (for MasterDatabaseService)
-- The MasterDatabaseService explicitly inserts into this column, so it must exist.
ALTER TABLE global_users ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE;

-- Verification
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name IN ('app_user', 'global_users') AND column_name = 'enabled';
