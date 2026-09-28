-- Matters.ai DSPM add-on, phase 1.
--
-- Three objects, for three different questions:
--   client.matters_dspm_enabled  -- has this customer bought the add-on?
--   matters_project_mapping      -- which Matters project is their data in, and with which key?
--   matters_access_audit         -- who read that data, and who changed the mapping?

-- ── 1. Entitlement ──────────────────────────────────────────────────────────────────────────────
--
-- DEFAULT FALSE, unlike xamsecure_onboarding_enabled which defaults TRUE. Same reasoning as
-- V20260915_1's notification channels: this is separately sold, and each enabled customer consumes a
-- Matters project plus an API key. Defaulting TRUE would put a DSPM tab in every client's sidebar on
-- deploy, and every one of them would open it to an empty page.

ALTER TABLE client
    ADD COLUMN IF NOT EXISTS matters_dspm_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill guard: the column above is NOT NULL DEFAULT FALSE, but an earlier hand-run ALTER on a
-- given environment (or ddl-auto=update, which adds columns nullable) may have left it nullable.
-- Matches the pattern in V20260821_1 and V20260915_1.
UPDATE client SET matters_dspm_enabled = FALSE WHERE matters_dspm_enabled IS NULL;

-- ── 2. The org -> project mapping ───────────────────────────────────────────────────────────────
--
-- Why a table rather than shared config, which is all the Upwind integration needed: a Matters API
-- key is issued against exactly one account AND one project, and tenancy is derived from the key
-- itself. Verified against the live API on 2026-09-25 -- every candidate project selector
-- (X-Project-Id, X-Project-ID, X-Project, X-Matters-Project, ?project_id, ?projectId, ?project)
-- returned byte-identical results, including when passed a nonexistent project id. The project is
-- simply not an input to the request, so the credential is necessarily per-customer and must be
-- stored.
--
-- That also makes this table the entire tenancy boundary for the add-on. Reads pick the row by
-- client_id and use its key; there is no second filter downstream, and no way for a request to widen
-- its own scope.

CREATE TABLE IF NOT EXISTS matters_project_mapping (
    id                   BIGSERIAL PRIMARY KEY,

    -- Client-scoped, following observability_config. Most integration configs in this schema hang off
    -- user_id (jenkins_integration_config and friends), which means the connection dies with whoever
    -- created it. A DSPM subscription belongs to the customer, and an internal operator creates it,
    -- so there is frequently no sensible user to attach.
    --
    -- ON DELETE CASCADE: the mapping is meaningless without its client, and leaving an orphan behind
    -- would hold a live credential for a customer that no longer exists.
    client_id            BIGINT NOT NULL
                             REFERENCES client (id) ON DELETE CASCADE,

    matters_account_id   VARCHAR(128),
    matters_project_id   VARCHAR(128) NOT NULL,
    matters_project_name VARCHAR(256),

    -- Regional tenant host, e.g. https://app.matters.ai or https://app.ap-south-1.matters.ai. Per
    -- row, not global: a key is bound to one environment and is rejected outright against another
    -- ("API key is for a different environment"), so the host has to travel with the key.
    base_url             VARCHAR(256) NOT NULL,

    -- Encrypted by EncryptedStringConverter (AES-256/GCM, random IV per value, 'enc:v1:' prefix).
    -- TEXT rather than a large-object column: @Lob on Postgres is what produced the "Unable to access
    -- lob stream" failures already unpicked elsewhere in this schema.
    api_key              TEXT,

    -- Parsed from the key at save time (mts_<env>_<key_id>_<secret>) so the admin UI can show which
    -- environment and which key a mapping uses, and flag a key/base-URL mismatch, without decrypting.
    api_key_env          VARCHAR(32),
    api_key_id           VARCHAR(128),

    -- Also the presence sentinel for api_key above. EncryptionService.decrypt returns NULL on
    -- failure rather than throwing, so a key written under a different app.encryption.secret-key
    -- reads back as NULL -- indistinguishable from "no key was ever stored". This column is stored in
    -- the clear and therefore survives a key mismatch: set + api_key NULL means decryption failed
    -- (an XamOps fault needing an operator), whereas NULL means not yet provisioned (the normal gap
    -- after granting the add-on, which the customer should see as "being set up").
    api_key_last_four    VARCHAR(8),

    -- Matters caps key lifetime at 180 days, so every key here is on a clock. With one key per
    -- customer, expiries arrive continuously -- recorded so an operator is warned before a customer
    -- reports an outage.
    api_key_expires_at   TIMESTAMPTZ,

    -- Operational switch, separate from client.matters_dspm_enabled above: lets an operator park a
    -- broken or mid-rotation connection without touching the customer's entitlement.
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,

    -- UNVERIFIED | CONNECTED | INVALID_KEY | FORBIDDEN | UNREACHABLE | DECRYPT_FAILED.
    -- Distinguished rather than a single boolean because the remedies differ: rotate the key, fix the
    -- scope, wait for the tenant, or fix our own encryption key. DECRYPT_FAILED in particular must
    -- not send anyone to Matters to rotate a key that was never the problem.
    connection_status    VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    last_verified_at     TIMESTAMPTZ,
    last_error           TEXT,
    last_error_at        TIMESTAMPTZ,

    updated_by           VARCHAR(128),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One mapping per client. Without this, two rows for one client would make which project a customer
-- sees depend on row order.
CREATE UNIQUE INDEX IF NOT EXISTS uk_mpm_client
    ON matters_project_mapping (client_id);

-- One client per project. This is a security control, not housekeeping: the API key already scopes
-- reads to a single project, so two clients sharing a project id is precisely the shape of a
-- cross-customer data leak. Enforced here so it cannot depend on every future call site checking.
CREATE UNIQUE INDEX IF NOT EXISTS uk_mpm_project
    ON matters_project_mapping (matters_project_id);

-- ── 3. Access audit trail ───────────────────────────────────────────────────────────────────────
--
-- The deliberate replacement for something given up by choice. Holding each key in AWS Secrets
-- Manager would have produced a CloudTrail record of every GetSecretValue for free; encrypting in
-- the application instead means credential reads happen entirely inside the service and leave no
-- trace anywhere else. So the trail is written here or it does not exist.
--
-- The question it answers: who caused customer X's sensitive-data inventory to be read, and when.
-- That matters more here than for most integrations -- the data behind these calls is a catalogue of
-- where a customer's PII and secrets live, and an operator with impersonation rights can reach it.
--
-- The API key is never written to this table in any form. api_key_id is the non-secret key-id
-- segment, enough to tie a call to a credential during a rotation or an incident and useless to
-- anyone who obtains the table.

CREATE TABLE IF NOT EXISTS matters_access_audit (
    id                 BIGSERIAL PRIMARY KEY,

    -- Plain BIGINT with no FK, unlike matters_project_mapping.client_id above. The row must outlive
    -- its client: "this customer's inventory was read, then the customer was deleted" is exactly the
    -- sequence an audit log exists to preserve, and ON DELETE CASCADE would erase it.
    client_id          BIGINT,

    actor_user_id      BIGINT,
    actor_username     VARCHAR(256),
    -- Captured at call time rather than resolved later: roles change, and the question is always
    -- what the actor was permitted to do at the moment they acted.
    actor_role         VARCHAR(64),
    -- CUSTOMER | OPERATOR | SYSTEM. The distinction that matters on review -- a customer reading
    -- their own inventory is routine, an operator reading it is the event worth looking at.
    actor_type         VARCHAR(32),
    impersonated       BOOLEAN NOT NULL DEFAULT FALSE,

    -- CREDENTIAL_USED | MAPPING_CREATED | MAPPING_UPDATED | MAPPING_DELETED | MAPPING_VERIFIED
    -- | KEY_ROTATED | PROJECT_REPOINTED | ENTITLEMENT_GRANTED | ENTITLEMENT_WITHDRAWN
    action             VARCHAR(64) NOT NULL,

    api_key_id         VARCHAR(128),
    matters_project_id VARCHAR(128),
    -- Path only. Query strings carry customer search terms, which do not belong in a long-retained
    -- table.
    endpoint           VARCHAR(256),

    outcome            VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    -- MattersApiException.Reason name, so failures can be counted by cause without parsing prose.
    failure_reason     VARCHAR(64),
    detail             TEXT,

    ip_address         VARCHAR(64),
    user_agent         VARCHAR(512),
    duration_ms        INTEGER,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-customer trail, newest first -- the admin page's default view.
CREATE INDEX IF NOT EXISTS idx_maa_client_time
    ON matters_access_audit (client_id, created_at DESC);

-- Unfiltered newest-first, and the driving index for the retention sweep.
CREATE INDEX IF NOT EXISTS idx_maa_time
    ON matters_access_audit (created_at DESC);

-- Partial index: the retention sweep only ever deletes CREDENTIAL_USED rows, and those are the bulk
-- of the table. Operator mutations are low-volume and never expire, so excluding them keeps this
-- index small.
CREATE INDEX IF NOT EXISTS idx_maa_cred_use_time
    ON matters_access_audit (created_at) WHERE action = 'CREDENTIAL_USED';
