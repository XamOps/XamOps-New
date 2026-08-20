-- Scope the inventory plane to the cloud_account row rather than the AWS account id.
--
-- Reconnecting an account creates a new cloud_account row, so one 12-digit AWS account id
-- can appear on several rows. The plane read its sync state with an Optional-returning
-- lookup keyed on that id, which threw NonUniqueResultException the moment a second row
-- existed; callers treat any exception as "plane unavailable", so the whole feature turned
-- itself off silently while still reporting healthy syncs.
--
-- Two things to clean up here.

-- 1. Inventory left behind by connections that no longer exist. These are unreachable by
--    every scoped read, and no scheduler runs for a deleted connection, so the
--    mark-and-sweep that would normally retire them never fires again.
DELETE FROM aws_cloud_resource
 WHERE cloud_account_id NOT IN (SELECT id FROM cloud_account);

DELETE FROM aws_inventory_sync_state
 WHERE cloud_account_id NOT IN (SELECT id FROM cloud_account);

-- 2. Indexes matching how the table is now queried. Every read filters on
--    (cloud_account_id, status); the old aws_account_id indexes no longer match any query
--    plan the application issues.
DROP INDEX IF EXISTS idx_aws_cloud_resource_account_status;
DROP INDEX IF EXISTS idx_aws_cloud_resource_account_type;

CREATE INDEX IF NOT EXISTS idx_aws_cloud_resource_cloudacct_status
    ON aws_cloud_resource (cloud_account_id, status);

CREATE INDEX IF NOT EXISTS idx_aws_cloud_resource_cloudacct_type
    ON aws_cloud_resource (cloud_account_id, status, display_type);

-- Backs the "new since" feed.
CREATE INDEX IF NOT EXISTS idx_aws_cloud_resource_cloudacct_first_seen
    ON aws_cloud_resource (cloud_account_id, status, first_seen_at);

-- The upsert path looks a row up by exactly this pair, once per resource per sync.
CREATE UNIQUE INDEX IF NOT EXISTS uq_aws_cloud_resource_cloudacct_arn
    ON aws_cloud_resource (cloud_account_id, arn);
