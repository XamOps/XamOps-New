-- ============================================================================
-- Records enabled regions that have no Resource Explorer index.
--
-- Resource Explorer does not automatically create indexes in AWS opt-in
-- regions. Measured on a real account: 18 enabled regions, 17 indexed, and the
-- 7 EC2 instances living in the unindexed opt-in region (ap-south-2) were
-- absent from the aggregator search entirely while describeInstances found them.
--
-- Without tracking this, the inventory plane silently under-reports: the cloud
-- list looks healthy and just omits whole regions. Worse, mark-and-sweep would
-- tombstone rows the describe fallback had discovered there, actively deleting
-- correct data. The reconciler now skips sweeping rows in these regions and the
-- status endpoint surfaces them.
-- ============================================================================

ALTER TABLE aws_inventory_sync_state
    ADD COLUMN IF NOT EXISTS uncovered_regions VARCHAR(1024);
