-- ============================================================
-- Missing indexes identified from repository query analysis
-- ============================================================

-- spot_optimization_event
-- Covers: findByAccountId*, findByAccountIdAndEventTimeBetween*, countByAccountId*, deleteByAccountId
CREATE INDEX IF NOT EXISTS idx_spot_event_account_time
    ON spot_optimization_event (account_id, event_time DESC);

-- Covers: findTop10/50ByOrderByEventTimeDesc
CREATE INDEX IF NOT EXISTS idx_spot_event_time_desc
    ON spot_optimization_event (event_time DESC);

-- Covers: existsByAccountIdAndNewInstanceId
CREATE INDEX IF NOT EXISTS idx_spot_event_account_new_instance
    ON spot_optimization_event (account_id, new_instance_id);


-- cloud_account
-- Covers: findByClientId, findByClientIdIn
CREATE INDEX IF NOT EXISTS idx_cloud_account_client_id
    ON cloud_account (client_id);

-- Covers: findByProvider
CREATE INDEX IF NOT EXISTS idx_cloud_account_provider
    ON cloud_account (provider);

-- Covers: findByGcpProjectId
CREATE INDEX IF NOT EXISTS idx_cloud_account_gcp_project_id
    ON cloud_account (gcp_project_id);

-- Covers: findByAzureSubscriptionId
CREATE INDEX IF NOT EXISTS idx_cloud_account_azure_subscription_id
    ON cloud_account (azure_subscription_id);

-- Covers: findByAwsAccountId (aws_account_id has a UNIQUE constraint but an explicit named index
--         makes the query plan obvious and allows partial index if needed later)
CREATE INDEX IF NOT EXISTS idx_cloud_account_aws_account_id
    ON cloud_account (aws_account_id);


-- app_user
-- Covers: findByClientId, findByClientIdIn
CREATE INDEX IF NOT EXISTS idx_app_user_client_id
    ON app_user (client_id);

-- Covers: findByCreatedBy
CREATE INDEX IF NOT EXISTS idx_app_user_created_by
    ON app_user (created_by);

-- Covers: findByEmail
CREATE INDEX IF NOT EXISTS idx_app_user_email
    ON app_user (email);


-- daily_spot_savings
-- Covers: findByAccountIdAndDateAfter, findByAccountIdAndDateBetween
-- (unique constraint on (account_id, date) handles point lookups; explicit index covers range scans)
CREATE INDEX IF NOT EXISTS idx_daily_spot_savings_account_date
    ON daily_spot_savings (account_id, date);


-- resource_label_assignments
-- Covers: findByLabelId, findAllAssignmentsByLabelIdAndAwsAccountId, countByLabelId, findResourceIdsByLabelId
CREATE INDEX IF NOT EXISTS idx_rla_label_id
    ON resource_label_assignments (label_id);

-- Covers: findByCloudAccountIdAndResourceId*, deleteByCloudAccountIdAndResourceId*,
--         existsByCloudAccountIdAndResourceIdAndLabelId
CREATE INDEX IF NOT EXISTS idx_rla_cloud_account_resource
    ON resource_label_assignments (cloud_account_id, resource_id);


-- eks_dashboard_cache
-- Covers: findByAccountId (all cache lookups start with accountId)
CREATE INDEX IF NOT EXISTS idx_eks_cache_account_id
    ON eks_dashboard_cache (account_id);


-- capacity_notification_history
-- Covers: findByCidrIdAndThreshold*, findFirstByCidrId*, hasDeploymentNotificationBeenSent
CREATE INDEX IF NOT EXISTS idx_cap_notif_cidr_threshold
    ON capacity_notification_history (cidr_id, threshold_percent);

-- Covers: findByNotifiedAtBetween
CREATE INDEX IF NOT EXISTS idx_cap_notif_notified_at
    ON capacity_notification_history (notified_at);

-- Covers: findByUserId
CREATE INDEX IF NOT EXISTS idx_cap_notif_user_id
    ON capacity_notification_history (user_id);


-- isv_marketplace_customer
-- Covers: findByProductCode, countActiveByProductCode, countTotalByProductCode, findActiveCustomersNotCheckedSince
CREATE INDEX IF NOT EXISTS idx_isv_mc_product_code
    ON isv_marketplace_customer (product_code);

-- Covers: findByProductCodeAndCustomerIdentifier (unique constraint already exists per entity definition,
--         but naming it explicitly aids visibility)
CREATE INDEX IF NOT EXISTS idx_isv_mc_product_customer
    ON isv_marketplace_customer (product_code, customer_identifier);

-- Covers: findByProductCodeAndStatus
CREATE INDEX IF NOT EXISTS idx_isv_mc_product_status
    ON isv_marketplace_customer (product_code, status);

-- Covers: findActiveCustomersNotCheckedSince (status + last_entitlement_check filter)
CREATE INDEX IF NOT EXISTS idx_isv_mc_status_entitlement_check
    ON isv_marketplace_customer (status, last_entitlement_check);

-- Covers: findByCustomerAwsAccountId
CREATE INDEX IF NOT EXISTS idx_isv_mc_customer_aws_account
    ON isv_marketplace_customer (customer_aws_account_id);


-- ticket
-- Covers: findAllByCategoryWithRepliesAndAuthors, countActiveByCategoryName
CREATE INDEX IF NOT EXISTS idx_ticket_category_status
    ON ticket (category, status);


-- support_message (xamops-service)
-- Covers: findByAccountIdOrderByTimestampAsc, findByAccountIdAnd*
CREATE INDEX IF NOT EXISTS idx_support_msg_account_time
    ON support_message (account_id, timestamp);

-- Covers: findAllUserMessagesWithSenderOrderByTimestampDesc
CREATE INDEX IF NOT EXISTS idx_support_msg_user_msg_time
    ON support_message (is_user_message, timestamp DESC);
