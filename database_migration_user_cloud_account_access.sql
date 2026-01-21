-- Database Migration Script for User Cloud Account Access
-- This script creates the user_cloud_account_access table and adds the created_by column to app_user table

-- Add created_by column to app_user table (if not already exists)
-- This tracks which user created sub-users
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES app_user(id);

-- Create user_cloud_account_access table
-- This table establishes a many-to-many relationship between users and cloud accounts
-- Primary users can select which specific cloud accounts their sub-users can access
CREATE TABLE IF NOT EXISTS user_cloud_account_access (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, cloud_account_id)
);

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_user_cloud_account_access_user_id ON user_cloud_account_access(user_id);
CREATE INDEX IF NOT EXISTS idx_user_cloud_account_access_cloud_account_id ON user_cloud_account_access(cloud_account_id);

-- Comments for documentation
COMMENT ON TABLE user_cloud_account_access IS 'Manages which cloud accounts each user can access';
COMMENT ON COLUMN user_cloud_account_access.user_id IS 'Reference to the user (typically a sub-user)';
COMMENT ON COLUMN user_cloud_account_access.cloud_account_id IS 'Reference to the cloud account the user can access';
COMMENT ON COLUMN app_user.created_by IS 'Reference to the user who created this user (NULL means primary user)';
