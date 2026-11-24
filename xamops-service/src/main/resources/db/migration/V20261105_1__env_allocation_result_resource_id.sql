-- Environment allocation: keep the resource on each result row.
--
-- A run used to group its output by environment, rule, service, usage type, region, charge type
-- and matched value, which dropped the resource id even when the month had been read per
-- resource. The By environment tab can then say UAT spent $88 on EC2 but not on which instances.
-- Keeping the id lets it list the resources under each service.
--
-- Nullable, and null on every existing row: those runs were stored without it, and a run read
-- without resource grain (no label or resource-id rule) still has no id to keep. Such rows show
-- their service total with nothing beneath it, as before. Re-running a month fills it in.
--
-- 512 to match resource_label_assignments.resource_id: a CUR resource id can be a long ARN.
--
-- Adding a nullable column with no default rewrites no rows in Postgres.
--
-- To reverse:
--   ALTER TABLE env_allocation_result DROP COLUMN IF EXISTS resource_id;

ALTER TABLE env_allocation_result
    ADD COLUMN IF NOT EXISTS resource_id VARCHAR(512);
