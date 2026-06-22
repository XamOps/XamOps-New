-- =============================================
-- CI/CD Failure Intelligence
-- Migration: Add failure categorization and notification tracking
-- Task: 1 - Database schema for failure intelligence feature
-- =============================================

-- =============================================
-- 1. Add failure intelligence columns to cicd_run_record
-- =============================================

ALTER TABLE cicd_run_record
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(30),
    ADD COLUMN IF NOT EXISTS notification_sent BOOLEAN NOT NULL DEFAULT FALSE;

-- =============================================
-- 2. Create notification configuration table
-- =============================================

CREATE TABLE IF NOT EXISTS cicd_notification_config (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    slack_webhook_url VARCHAR(500),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- =============================================
-- 3. Add indexes for notification config queries
-- =============================================

CREATE INDEX IF NOT EXISTS idx_cicd_notification_config_user_id
ON cicd_notification_config(user_id);

-- =============================================
-- 4. Add comments for documentation
-- =============================================

COMMENT ON TABLE cicd_notification_config IS 'User notification preferences for CI/CD pipeline failures (email and Slack)';
COMMENT ON COLUMN cicd_notification_config.user_id IS 'Foreign key to users table; one config per user';
COMMENT ON COLUMN cicd_notification_config.email_enabled IS 'Whether email notifications are enabled for this user';
COMMENT ON COLUMN cicd_notification_config.slack_webhook_url IS 'Slack webhook URL for pipeline failure notifications (optional)';
COMMENT ON COLUMN cicd_run_record.failure_category IS 'Category of failure (TEST_FAILURE, BUILD_ERROR, DEPLOY_FAILURE, INFRA_ERROR, UNKNOWN)';
COMMENT ON COLUMN cicd_run_record.notification_sent IS 'Flag to indicate if notification has been sent for this run failure';

-- =============================================
-- Migration Complete - CI/CD Failure Intelligence
-- =============================================
