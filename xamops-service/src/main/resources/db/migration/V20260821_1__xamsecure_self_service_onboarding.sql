-- Self-service Upwind account onboarding.
--
-- Upwind's CloudFormation template generates the external ID and registers the account with
-- Upwind itself (Custom::UpwindGeneratedToken + Custom::UpwindRegisteredAccount), so XamOps
-- only has to hand out a pre-filled launch URL and then notice when the account comes online.
-- These columns are what "notice when it comes online" needs.

ALTER TABLE upwind_account_connection
    ADD COLUMN IF NOT EXISTS stack_requested_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS stack_region           VARCHAR(32),
    ADD COLUMN IF NOT EXISTS stack_name             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS connection_verified_at TIMESTAMP,
    -- Upwind's own id for the registered connection, from POST /cloud-credentials. Needed to
    -- DELETE it again: Upwind's CloudFormation template does NOT deregister on stack delete
    -- (its Delete handler only replies SUCCESS to CloudFormation and calls no API), so without
    -- this id a disconnect can only remove the IAM role and leave a permanently failing
    -- connection behind in the org.
    ADD COLUMN IF NOT EXISTS upwind_credential_id   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS disconnected_at        TIMESTAMP;

-- The verifier polls for connections that are not yet CONNECTED; keep that lookup cheap as the
-- table grows with every onboarded account.
CREATE INDEX IF NOT EXISTS idx_upwind_conn_status
    ON upwind_account_connection (status);

-- Per-client permission to use self-service XamSecure onboarding, controlled from BillOps admin.
--
-- DEFAULT TRUE and backfilled to TRUE: enabled for everyone today, as requested. The column
-- exists so access can be withdrawn per client without a code change — onboarding spends an
-- Upwind license seat and places Xammer's shared Cloud account credential inside the customer's
-- AWS account, so it is a contractual grant rather than an open feature.
ALTER TABLE client
    ADD COLUMN IF NOT EXISTS xamsecure_onboarding_enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE client SET xamsecure_onboarding_enabled = TRUE WHERE xamsecure_onboarding_enabled IS NULL;
