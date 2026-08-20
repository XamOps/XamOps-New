-- ============================================================================
-- Shared AWS resource inventory ("inventory plane").
--
-- Replaces the two redundant JSON blobs CloudListService writes into
-- cached_data (CLOUD_LIST::{acct} and CLOUD_LIST_GROUPED::{acct}) with one
-- indexed row per resource. That switch is what makes the rest possible:
--   * a single resource can be inserted/updated by an event, instead of
--     re-running a ~1000-call account-wide scan to change one row
--   * pagination and filtering move into SQL, instead of subList() over the
--     whole deserialised account in memory
--   * first_seen_at gives the UI a real "new resource" signal
--   * the ~29 classes that each re-enumerate EC2/EBS/RDS today get one
--     place to read from
--
-- Populated by AwsInventoryReconciler (AWS Resource Explorer, free) and later
-- patched in near-real-time by the EventBridge -> SQS listener.
-- ============================================================================

CREATE TABLE aws_cloud_resource (
    id                BIGSERIAL     PRIMARY KEY,

    -- Tenancy / ownership. cloud_account_id is the FK; aws_account_id is
    -- denormalised because almost every read path has the AWS account id
    -- string in hand (from the URL) and not the internal row id.
    cloud_account_id  BIGINT        NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    aws_account_id    VARCHAR(32)   NOT NULL,

    -- Identity. arn is the natural key and is what events carry.
    -- resource_id keeps the short form (i-abc123) the existing ResourceDto
    -- and every frontend link already use.
    arn               VARCHAR(2048) NOT NULL,
    resource_id       VARCHAR(512)  NOT NULL,
    name              VARCHAR(512),

    -- Classification. display_type is the human string the UI groups by
    -- ("EC2 Instance"), deliberately kept identical to the literals
    -- CloudListService emits today so consumers need no changes.
    -- service/native_type keep Resource Explorer's own vocabulary
    -- ("ec2" / "ec2:instance") so unmapped types are still queryable.
    service           VARCHAR(64)   NOT NULL,
    native_type       VARCHAR(128),
    display_type      VARCHAR(128)  NOT NULL,

    region            VARCHAR(64),
    state             VARCHAR(64),

    -- Tags and type-specific attributes as JSON-in-text.
    --
    -- NOT jsonb: Hibernate binds a Java String as varchar and Postgres will not
    -- implicitly cast that to jsonb, so every insert failed with "column is of
    -- type jsonb but expression is of type character varying" and no resource
    -- was ever stored. Making these queryable in SQL later needs a proper JSONB
    -- user type on the entity, not just a column type change here.
    tags              TEXT          NOT NULL DEFAULT '{}',
    details           TEXT          NOT NULL DEFAULT '{}',

    -- Lifecycle. status ACTIVE/DELETED rather than a hard delete: the waste,
    -- cost and audit views all need to reason about something that existed
    -- yesterday and is gone today.
    status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    launch_time       TIMESTAMPTZ,
    first_seen_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ,

    -- Reconciliation. The reconciler stamps every row it observes with the
    -- current generation, then sweeps rows left behind at the previous one.
    -- This is what lets a full baseline sync be correct even if the event
    -- stream dropped or duplicated messages.
    sync_generation   BIGINT        NOT NULL DEFAULT 0,

    -- Provenance: RESOURCE_EXPLORER | EVENT | DESCRIBE_FALLBACK.
    -- Needed to debug disagreements between the two write paths, and to tell
    -- whether an account is on the fast path or the fallback.
    source            VARCHAR(32)   NOT NULL DEFAULT 'RESOURCE_EXPLORER',

    CONSTRAINT uq_aws_cloud_resource_arn UNIQUE (cloud_account_id, arn)
);

-- Primary read pattern: "all live resources for this account", then group or
-- filter by type. Partial index keeps the deleted tail out of the hot path.
CREATE INDEX idx_acr_account_active
    ON aws_cloud_resource (aws_account_id, display_type)
    WHERE status = 'ACTIVE';

-- Cloud list / dashboard grouping and per-service counts.
CREATE INDEX idx_acr_account_service
    ON aws_cloud_resource (cloud_account_id, service);

-- Region map, region-scoped pages, and active-region derivation (which
-- replaces the describeRegions + 5-probes-per-region discovery pass).
CREATE INDEX idx_acr_account_region
    ON aws_cloud_resource (cloud_account_id, region);

-- Event path looks rows up by ARN alone before it knows the account row.
CREATE INDEX idx_acr_arn ON aws_cloud_resource (arn);

-- Sweep query: find rows this generation did not touch.
CREATE INDEX idx_acr_generation ON aws_cloud_resource (cloud_account_id, sync_generation);

-- "New since" badges and change feeds.
CREATE INDEX idx_acr_first_seen ON aws_cloud_resource (cloud_account_id, first_seen_at DESC);

-- No GIN index on tags: the column is TEXT (see above), so a GIN jsonb index
-- does not apply. Tag filtering happens in the projection layer today. If tag
-- queries move into SQL, convert the column to jsonb with a matching Hibernate
-- user type and add the index in the same migration.


-- ----------------------------------------------------------------------------
-- Per-account sync bookkeeping.
--
-- Separate from cached_data because this is control state, not a cached
-- payload: the reconciler reads it to decide whether to run, the UI reads it
-- to show "last synced", and support reads it to see why an account fell back
-- to describes.
-- ----------------------------------------------------------------------------
CREATE TABLE aws_inventory_sync_state (
    cloud_account_id      BIGINT        PRIMARY KEY REFERENCES cloud_account(id) ON DELETE CASCADE,
    aws_account_id        VARCHAR(32)   NOT NULL,

    sync_generation       BIGINT        NOT NULL DEFAULT 0,
    last_full_sync_at     TIMESTAMPTZ,
    last_event_at         TIMESTAMPTZ,

    -- RESOURCE_EXPLORER once we have confirmed the index is usable for this
    -- account, DESCRIBE_FALLBACK when Resource Explorer is blocked by an SCP,
    -- unavailable in their regions, or returning nothing.
    mode                  VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',

    -- Resource Explorer serves partial results without a service-linked role.
    -- Surfacing that explicitly stops us reporting a half inventory as whole.
    index_type            VARCHAR(32),
    aggregator_region     VARCHAR(64),

    resource_count        INTEGER       NOT NULL DEFAULT 0,
    last_error            VARCHAR(1024),
    last_sync_duration_ms BIGINT,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_aiss_aws_account ON aws_inventory_sync_state (aws_account_id);
