-- Records HOW an AWS account's cost data is sourced, so the read path can choose between a
-- Cost and Usage Report and the Cost Explorer API per account.
--
--   XAMMER_MPA_CUR      account sits under a XamOps management/payer account; its rows live in
--                       the MPA's own CUR, in a XamOps-owned bucket, read with our credentials
--   OWN_CUR             customer account with a CUR our onboarding stack created in it
--   EXISTING_CUR        customer pointed us at a CUR they already had
--   COST_EXPLORER_ONLY  no CUR is obtainable — typically a member account whose payer never
--                       enabled linked-account access to billing data
--
-- The fourth value is the point of the enum. The GCP side shipped this as a boolean and had to
-- be migrated (V20260818_2) after a Xammer-billed project with its own export was routed to the
-- reseller dataset, matched no rows, and reported zero spend on every cost widget. An AWS account
-- pointed at a CUR that does not contain it fails exactly the same way, and $0 is
-- indistinguishable from "spent nothing" once it reaches a chart. COST_EXPLORER_ONLY makes
-- "cannot use a CUR" an explicit, routable state rather than the absence of a configured source.

-- ── One row per CUR we can query ────────────────────────────────────────────────────────────
-- Many-to-one from cloud_account, not columns on it: a single CUR in a XamOps MPA covers every
-- member account under that MPA, so per-account bucket/prefix columns would duplicate one row of
-- config across the whole org and let the copies drift.
CREATE TABLE IF NOT EXISTS cur_source (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    owner_account_id        VARCHAR(32)  NOT NULL,
    -- Bucket is in a XamOps account, so query with the application's own credentials rather
    -- than assuming a customer role. Derived from the billing source at write time.
    xamops_owned            BOOLEAN      NOT NULL DEFAULT FALSE,
    s3_bucket               VARCHAR(255) NOT NULL,
    s3_prefix               VARCHAR(512),
    report_name             VARCHAR(255) NOT NULL,
    -- Region of the BUCKET, which is where Athena must run — not where the report was defined
    -- (legacy CUR definitions always live in us-east-1).
    region                  VARCHAR(32)  NOT NULL,
    athena_database         VARCHAR(255),
    athena_table            VARCHAR(255),
    athena_workgroup        VARCHAR(255) DEFAULT 'primary',
    athena_results_location VARCHAR(512),

    -- ── Discovered from Manifest.json, never assumed ────────────────────────────────────────
    -- An EXISTING_CUR belongs to the customer: it may be monthly CSV with no resource IDs, which
    -- supports far less than a report we provisioned. Features gate on these columns instead of
    -- failing at query time with a missing-column error.
    time_unit               VARCHAR(16),
    format                  VARCHAR(16),
    cur_version             VARCHAR(8)   DEFAULT 'cur',
    resource_ids_enabled    BOOLEAN,
    split_cost_data_enabled BOOLEAN,
    -- Earliest billing period present. A legacy CUR does not backfill, so this is normally the
    -- month the report was created; a range starting before it must fall back to Cost Explorer
    -- rather than return a silently short sum.
    earliest_billing_period VARCHAR(8),

    status                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    status_detail           VARCHAR(512),
    last_validated_at       TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The ingestion job selects every source that is usable right now.
CREATE INDEX IF NOT EXISTS idx_cur_source_status ON cur_source(status);

-- One report per bucket+prefix+name; re-running onboarding must not create a second row
-- pointing at the same files.
CREATE UNIQUE INDEX IF NOT EXISTS idx_cur_source_location
    ON cur_source(s3_bucket, COALESCE(s3_prefix, ''), report_name);

-- ── Wire accounts to their source ───────────────────────────────────────────────────────────
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS aws_billing_source VARCHAR(32);
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS cur_source_id BIGINT REFERENCES cur_source(id);

CREATE INDEX IF NOT EXISTS idx_cloud_account_cur_source ON cloud_account(cur_source_id);
CREATE INDEX IF NOT EXISTS idx_cloud_account_aws_billing_source ON cloud_account(aws_billing_source);

-- Backfill preserves current behaviour exactly: every existing AWS account keeps reading from
-- Cost Explorer, which is what it reads today. Moving an account onto a CUR is a deliberate act
-- with a validated cur_source attached — nothing in the existing data can tell us which accounts
-- are under a XamOps MPA, and guessing would point accounts at a report that does not contain
-- them. That is precisely the zero-spend failure this migration exists to prevent.
UPDATE cloud_account
SET aws_billing_source = 'COST_EXPLORER_ONLY'
WHERE provider = 'AWS' AND aws_billing_source IS NULL;
