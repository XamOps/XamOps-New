-- Records HOW a GCP project's cost data is sourced, which the csp_managed boolean could
-- not express. It only had two states, so a project whose billing Xammer manages but whose
-- costs live in a normal BigQuery billing export had to be onboarded as csp_managed. That
-- sent the read path to the reseller dataset, found no rows for the project, and reported
-- zero spend on every cost widget.
--
--   OWN_EXPORT          customer manages billing; read billing_export_table
--   XAMMER_CSP_DATASET  Xammer-billed reseller project; read the CSP billing dataset
--   XAMMER_EXPORT       Xammer-billed, but costs come from billing_export_table
--
-- csp_managed stays as the flag the reseller read path and the ingestion job select on;
-- CloudAccount.setBillingSource keeps the two in step.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS billing_source VARCHAR(32);

-- Backfill preserves current behaviour exactly: every existing account keeps reading from
-- the source it reads from today. Accounts that are really XAMMER_EXPORT have to be moved
-- deliberately, because nothing in the existing data distinguishes them from a project
-- that was mis-onboarded.
UPDATE cloud_account
SET billing_source = CASE WHEN csp_managed THEN 'XAMMER_CSP_DATASET' ELSE 'OWN_EXPORT' END
WHERE provider = 'GCP' AND billing_source IS NULL;
