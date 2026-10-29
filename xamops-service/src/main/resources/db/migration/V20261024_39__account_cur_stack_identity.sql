-- Records the CUR stack created for an account's own Cost and Usage Report.
--
-- The connection stack and the CUR stack now live apart: the connection stack is created in
-- ap-south-1 and nests the inventory engine, while the CUR report is its own stack pinned to
-- us-east-1 — the only region AWS::CUR::ReportDefinition exists in. Only an account that owns
-- its report (OWN_CUR) ever gets a CUR stack at all.
--
-- Unlike the connection stack, the CUR stack has no EventBridge callback to report its identity
-- back, so there is nowhere to capture it on connect. Its name and region are therefore recorded
-- at URL-generation time, when they are known and chosen deterministically.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS cur_stack_name VARCHAR(255);

-- Pinned region of the CUR stack, kept for the same reason stack_region is kept for the
-- connection stack: DescribeStacks only sees its own region, so the region has to be known to
-- find the stack again.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS cur_stack_region VARCHAR(64);

-- Nothing is backfilled. Both columns are NULL for every existing row and for every account that
-- does not own its CUR, which is exactly the intended meaning: no CUR stack of its own.
