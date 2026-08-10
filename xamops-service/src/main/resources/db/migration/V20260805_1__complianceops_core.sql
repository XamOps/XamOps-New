-- ComplianceOps: a real compliance record.
--
-- Before this, the whole feature derived itself per-request from a single
-- PROWLER::{account}::LATEST JSON blob that every scan overwrote. That had three
-- consequences worth naming, because they are what these tables exist to fix:
--
--   1. No history, so the "trend over time" chart was generated from today's counts
--      with a synthetic decay curve. It always sloped downwards.
--   2. No control identity, so controls were grouped by the first 120 characters of a
--      finding description. Control totals moved whenever Prowler reworded a message.
--   3. Nowhere to record a human decision — no waiver, no owner, no remediation state.
--      A failing control failed forever, with no way to say "accepted, expires in 90 days".
--
-- Design notes:
--   * A scan is immutable. Results are written once and never updated, so any past date
--     can be reproduced exactly — which is what an audit window actually needs.
--   * Human decisions (exceptions, ownership) are keyed to the control, NOT the scan,
--     so they survive re-scans. Findings are keyed to the scan.
--   * check_id is Prowler's stable event_code (e.g. "iam_root_mfa_enabled"). It is the
--     join key across every table here. Framework control ids (CIS 1.5, CC6.1) are a
--     many-to-many mapping on top of it, because one check satisfies several frameworks.

-- ── Scans ────────────────────────────────────────────────────────────────────

CREATE TABLE compliance_scan (
    id                BIGSERIAL PRIMARY KEY,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    provider          VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,   -- RUNNING | COMPLETED | FAILED
    started_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    -- Recorded per scan so a historical result stays interpretable: a score is only
    -- comparable across scans that covered the same regions and framework versions.
    regions           TEXT,
    frameworks        TEXT,
    prowler_version   VARCHAR(32),
    total_findings    INT          NOT NULL DEFAULT 0,
    passed_findings   INT          NOT NULL DEFAULT 0,
    failed_findings   INT          NOT NULL DEFAULT 0,
    error_message     TEXT,
    -- Denormalised so the trend chart is one indexed read instead of an aggregate
    -- over every result row for every scan.
    score             INT
);
CREATE INDEX idx_cscan_account_started
    ON compliance_scan (cloud_account_id, started_at DESC);
CREATE INDEX idx_cscan_account_status
    ON compliance_scan (cloud_account_id, status);

-- ── Per-control outcome, one row per (scan, check) ────────────────────────────

CREATE TABLE compliance_control_result (
    id                BIGSERIAL PRIMARY KEY,
    scan_id           BIGINT       NOT NULL REFERENCES compliance_scan (id) ON DELETE CASCADE,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    check_id          VARCHAR(256) NOT NULL,
    check_title       TEXT,
    service           VARCHAR(64),
    severity          VARCHAR(16),
    -- PASS when every in-scope resource passed, FAIL when any failed, MANUAL when
    -- Prowler could not decide. Keeping MANUAL distinct matters: it is the difference
    -- between "verified compliant" and "nobody looked".
    status            VARCHAR(16)  NOT NULL,
    passed_resources  INT          NOT NULL DEFAULT 0,
    failed_resources  INT          NOT NULL DEFAULT 0,
    remediation       TEXT,
    remediation_refs  TEXT,
    CONSTRAINT uq_ccr_scan_check UNIQUE (scan_id, check_id)
);
CREATE INDEX idx_ccr_scan ON compliance_control_result (scan_id);
CREATE INDEX idx_ccr_account_check
    ON compliance_control_result (cloud_account_id, check_id);
CREATE INDEX idx_ccr_status ON compliance_control_result (scan_id, status);

-- ── Framework ↔ check mapping, per scan ───────────────────────────────────────
--
-- Many-to-many on purpose. One check ("root account has MFA") satisfies CIS 1.5,
-- SOC2 CC6.1 and ISO A.9.2 at once, which is what makes "fix once, satisfy four
-- frameworks" answerable rather than guesswork.

CREATE TABLE compliance_control_framework (
    id                BIGSERIAL PRIMARY KEY,
    scan_id           BIGINT       NOT NULL REFERENCES compliance_scan (id) ON DELETE CASCADE,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    check_id          VARCHAR(256) NOT NULL,
    framework         VARCHAR(64)  NOT NULL,
    framework_control VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL
);
CREATE INDEX idx_ccf_scan_framework
    ON compliance_control_framework (scan_id, framework);
CREATE INDEX idx_ccf_check ON compliance_control_framework (scan_id, check_id);

-- ── Resource-level evidence ───────────────────────────────────────────────────
--
-- The auditor question is never "is control X failing", it is "show me which
-- resources, when, and what the check actually observed". status_detail is Prowler's
-- status_extended — the closest thing to a captured observation we have.

CREATE TABLE compliance_finding (
    id                BIGSERIAL PRIMARY KEY,
    scan_id           BIGINT       NOT NULL REFERENCES compliance_scan (id) ON DELETE CASCADE,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    check_id          VARCHAR(256) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    severity          VARCHAR(16),
    resource_uid      VARCHAR(512),
    resource_name     VARCHAR(512),
    resource_type     VARCHAR(128),
    region            VARCHAR(64),
    status_detail     TEXT,
    observed_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_cf_scan_check ON compliance_finding (scan_id, check_id);
CREATE INDEX idx_cf_resource ON compliance_finding (cloud_account_id, resource_uid);

-- ── Exceptions / waivers ──────────────────────────────────────────────────────
--
-- Keyed to the account + check rather than a scan, so a waiver survives re-scanning.
-- expires_at is NOT NULL by design: a compliance exception without an expiry is how
-- an organisation quietly accepts a risk forever. Auditors ask for the justification,
-- the approver and the review date, so all three are required.

CREATE TABLE compliance_exception (
    id                   BIGSERIAL PRIMARY KEY,
    cloud_account_id     VARCHAR(64)  NOT NULL,
    check_id             VARCHAR(256) NOT NULL,
    -- NULL scopes the waiver to the whole check; set it to waive one resource only.
    resource_uid         VARCHAR(512),
    justification        TEXT         NOT NULL,
    compensating_control TEXT,
    requested_by         VARCHAR(128) NOT NULL,
    approved_by          VARCHAR(128),
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | REJECTED | EXPIRED | REVOKED
    created_at           TIMESTAMPTZ  NOT NULL,
    approved_at          TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ,
    revoked_reason       TEXT
);
CREATE INDEX idx_cex_account_check
    ON compliance_exception (cloud_account_id, check_id);
CREATE INDEX idx_cex_expiry ON compliance_exception (status, expires_at);

-- ── Control ownership ─────────────────────────────────────────────────────────
--
-- Also keyed to the check, not the scan. "Who fixes this" is a standing fact about
-- the control, not a property of one scan run.

CREATE TABLE compliance_control_owner (
    id                BIGSERIAL PRIMARY KEY,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    check_id          VARCHAR(256) NOT NULL,
    owner_name        VARCHAR(128),
    owner_email       VARCHAR(256),
    team              VARCHAR(128),
    notes             TEXT,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(128),
    CONSTRAINT uq_cco_account_check UNIQUE (cloud_account_id, check_id)
);
CREATE INDEX idx_cco_account ON compliance_control_owner (cloud_account_id);

-- ── Remediation tracking ──────────────────────────────────────────────────────
--
-- verified_at is the important column: it records that a later scan actually
-- observed the control passing, rather than someone ticking a box.

CREATE TABLE compliance_remediation (
    id                BIGSERIAL PRIMARY KEY,
    cloud_account_id  VARCHAR(64)  NOT NULL,
    check_id          VARCHAR(256) NOT NULL,
    resource_uid      VARCHAR(512),
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN', -- OPEN | IN_PROGRESS | RESOLVED | VERIFIED | WONT_FIX
    assignee_name     VARCHAR(128),
    assignee_email    VARCHAR(256),
    ticket_ref        VARCHAR(128),
    due_at            TIMESTAMPTZ,
    notes             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(128),
    updated_at        TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    verified_at       TIMESTAMPTZ,
    verified_scan_id  BIGINT
);
CREATE INDEX idx_crem_account_check
    ON compliance_remediation (cloud_account_id, check_id);
CREATE INDEX idx_crem_status ON compliance_remediation (cloud_account_id, status);
