-- ============================================================
-- Missing indexes for billops database
-- Run manually against the billops PostgreSQL schema
-- ============================================================

-- support_message  (most critical — no indexes at all)
-- Covers: findByAccountIdAndUserId*, countByAccountId*, findFirstByAccountId*, existsByAccountId*
CREATE INDEX IF NOT EXISTS idx_bsm_account_id
    ON support_message (account_id);

-- Covers: findByUserId*, countByUserId*, findFirstByUserId*, existsByUserId*
CREATE INDEX IF NOT EXISTS idx_bsm_user_id
    ON support_message (user_id);

-- Covers: findByAccountIdAndUserId* (4 methods — most frequent conversation scoping)
CREATE INDEX IF NOT EXISTS idx_bsm_account_user_time
    ON support_message (account_id, user_id, timestamp DESC);

-- Covers: is_user_message + status filter queries (6+ filter methods)
CREATE INDEX IF NOT EXISTS idx_bsm_user_msg_status
    ON support_message (is_user_message, status);

-- Covers: findByAutoPingEnabledTrueAndAutoPingSentFalse*
CREATE INDEX IF NOT EXISTS idx_bsm_auto_ping
    ON support_message (auto_ping_enabled, auto_ping_sent);

-- Covers: existsByExternalMessageId
CREATE INDEX IF NOT EXISTS idx_bsm_external_message_id
    ON support_message (external_message_id);

-- Covers: ORDER BY timestamp queries / findFirstBy*OrderByTimestampDesc
CREATE INDEX IF NOT EXISTS idx_bsm_timestamp_desc
    ON support_message (timestamp DESC);


-- cloud_account
-- Covers: findByClientId
CREATE INDEX IF NOT EXISTS idx_bca_client_id
    ON cloud_account (client_id);

-- Covers: findByGcpProjectId
CREATE INDEX IF NOT EXISTS idx_bca_gcp_project_id
    ON cloud_account (gcp_project_id);

-- Covers: findByAzureSubscriptionId
CREATE INDEX IF NOT EXISTS idx_bca_azure_subscription_id
    ON cloud_account (azure_subscription_id);


-- invoice
-- Covers: findByCloudAccountIdAndBillingPeriodAndStatus, findByCloudAccountIdAndBillingPeriod
CREATE INDEX IF NOT EXISTS idx_invoice_account_period_status
    ON invoice (cloud_account_id, billing_period, status);

-- Covers: findTop1ByCloudAccountIdOrderByInvoiceDateDesc
CREATE INDEX IF NOT EXISTS idx_invoice_account_id
    ON invoice (cloud_account_id);

-- Covers: findByClientId
CREATE INDEX IF NOT EXISTS idx_invoice_client_id
    ON invoice (client_id);


-- app_user
-- Covers: findByClientId
CREATE INDEX IF NOT EXISTS idx_bau_client_id
    ON app_user (client_id);

-- Covers: findByEmail
CREATE INDEX IF NOT EXISTS idx_bau_email
    ON app_user (email);


-- user_cloud_account_access
-- Covers: findByUserId, deleteByUserId, findCloudAccountIdsByUserId
CREATE INDEX IF NOT EXISTS idx_ucaa_user_id
    ON user_cloud_account_access (user_id);


-- ticket
-- Covers: findAllForAdminList, findAllWithRepliesAndAuthors (scoped per client)
CREATE INDEX IF NOT EXISTS idx_btkt_client_id
    ON ticket (client_id);


-- agreement
-- Covers: findByCloudAccountIdAndStatus, findByCloudAccountId
CREATE INDEX IF NOT EXISTS idx_agreement_account_status
    ON agreement (cloud_account_id, status);
