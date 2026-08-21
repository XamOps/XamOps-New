-- =============================================================================
-- Live security findings, one row per finding.
--
-- The AWS EventBridge stream already forwards aws.securityhub, aws.guardduty and
-- aws.config events with their `detail` intact — the xamops-inventory-security
-- rule has existed since the inventory stack shipped and IncludeSecurityFindings
-- defaults to 'true'. InventoryEventListener was deleting them unread, because
-- those events carry no resource ARNs and so fell through the "no ARN, leave it
-- to the reconciler" branch. This table is where they land instead.
--
-- Deliberately separate from compliance_finding. That table belongs to a
-- compliance_scan row and represents a point-in-time Prowler result; these arrive
-- continuously with no scan to belong to, and are keyed by the provider's own
-- finding id so a redelivery updates rather than duplicates.
--
-- Scoped by cloud_account_id, not aws_account_id. Same reasoning as
-- aws_cloud_resource: one AWS account can own several cloud_account rows (two
-- tenants connecting it, or a disconnect/reconnect minting a new row). On a
-- findings table the consequence is worse than double counting — scoping reads by
-- the 12-digit id would show one tenant another tenant's vulnerabilities.
-- =============================================================================

CREATE TABLE cloud_security_finding (
    id                   BIGSERIAL     PRIMARY KEY,

    -- FK with cascade so disconnecting an account cannot leave findings behind.
    -- The repository still carries an orphan sweep as a backstop, matching
    -- aws_cloud_resource, because rows predating this constraint would otherwise
    -- be unreachable by every scoped read and never retired.
    cloud_account_id     BIGINT        NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    -- Denormalised: read paths arrive holding the 12-digit id from the request URL.
    aws_account_id       VARCHAR(32)   NOT NULL,

    -- The provider's own finding id, or a deterministic hash for findings we
    -- derive ourselves. Unique per connection, which is what makes ingest
    -- idempotent: SQS is at-least-once, GuardDuty republishes subsequent
    -- occurrences of a finding on its own cadence (six hours by default), and
    -- Security Hub re-imports on every state change.
    finding_id           VARCHAR(512)  NOT NULL,

    -- SECURITYHUB | GUARDDUTY | CONFIG | XAMOPS_DETECTOR
    source               VARCHAR(32)   NOT NULL,

    -- Normalised at ingest to CRITICAL | HIGH | MEDIUM | LOW | INFORMATIONAL.
    -- The codebase already had four incompatible vocabularies: upper-case in
    -- Security Hub, ComplianceOps, WAR and Upwind; title-case plus
    -- "Informational" in SecurityService and ProwlerService;
    -- CRITICAL/WARNING/WEAK_WARNING in PerformanceInsightDto; and a 1.0-10.0
    -- float in GuardDuty. A severity filter or an "email me about criticals"
    -- threshold cannot be written against four, so everything converts on the
    -- way in. See SecuritySeverity.
    severity             VARCHAR(16)   NOT NULL,

    title                VARCHAR(1024) NOT NULL,
    description          TEXT,

    resource_arn         VARCHAR(2048),
    resource_type        VARCHAR(256),
    region               VARCHAR(64),

    -- ACTIVE | RESOLVED | SUPPRESSED. Findings are resolved, never deleted:
    -- "was this bucket public at any point last quarter" is the audit question.
    status               VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',

    compliance_framework VARCHAR(256),
    control_id           VARCHAR(256),
    remediation          TEXT,

    -- TIMESTAMPTZ throughout, matching aws_cloud_resource. The entity maps these
    -- to java.time.Instant, which is an absolute point in time; a zone-less
    -- TIMESTAMP column would silently reinterpret it against the session zone.
    first_seen_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    last_seen_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at          TIMESTAMPTZ,

    -- When a notification was last sent for this finding. Null means never.
    -- The notifier gates on this rather than on AlertNotificationDeduplicator's
    -- content fingerprint, which joins type|name|service|region|status and so
    -- ignores severity entirely — it cannot express a severity threshold.
    notified_at          TIMESTAMPTZ,

    -- The original event detail, as text rather than jsonb. Hibernate binds a
    -- Java String as varchar and Postgres refuses to cast that to jsonb
    -- implicitly, which broke every insert on aws_cloud_resource until
    -- V20260819_2__inventory_jsonb_to_text.sql undid it. Nothing queries inside
    -- this document in SQL; if that changes, add a proper JSONB user type on the
    -- entity in the same migration rather than only changing the column.
    raw_detail           TEXT,

    -- When AWS says it happened, as opposed to when we stored it. The two can be
    -- hours apart: GuardDuty aggregates repeat occurrences, and CloudTrail
    -- delivery is not instantaneous.
    event_time           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_cloud_security_finding UNIQUE (cloud_account_id, finding_id)
);

-- The hot read: active findings for one connection, most severe then newest.
CREATE INDEX idx_csf_account_status
    ON cloud_security_finding (cloud_account_id, status, last_seen_at DESC);

-- Severity filtering, the counts-by-severity strip, and the CRITICAL/HIGH slice
-- the Alerts page reads.
CREATE INDEX idx_csf_account_severity
    ON cloud_security_finding (cloud_account_id, status, severity);

-- Backs the "new since" feed and the notifier's unnotified sweep.
CREATE INDEX idx_csf_account_first_seen
    ON cloud_security_finding (cloud_account_id, first_seen_at DESC);

-- Resource drill-down: "what is wrong with this bucket".
CREATE INDEX idx_csf_resource
    ON cloud_security_finding (cloud_account_id, resource_arn);
