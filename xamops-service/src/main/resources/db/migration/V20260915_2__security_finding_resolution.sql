-- =============================================================================
-- "I have fixed this" — a user's assertion that a finding no longer applies.
--
-- WHY THIS EXISTS
-- ExposureDetector already tells users to do this. Every derived finding carries
-- MANUAL_RESOLVE in its remediation text: "XamOps sees the change that opened
-- this but not the one that closes it, so mark the finding resolved once you have
-- verified the fix." There was no way to mark anything resolved. The promise had
-- no mechanism behind it, so a resource fixed silently stayed ACTIVE forever and
-- refreshing the page could never clear it — which is exactly what a customer
-- reported.
--
-- WHY NOT JUST WRITE status='RESOLVED' ON cloud_security_finding
-- Because that table holds only the event-driven findings. The Security Center
-- page also renders Prowler results and SecurityService's describe-based checks,
-- neither of which has a row here to update. Dismissing one of those has nowhere
-- to be recorded, so a resolve that only wrote to cloud_security_finding would
-- work on some rows of the same table and silently do nothing on others. Keying
-- on the natural identity of a finding instead makes one action work for every
-- source, including SOURCE_XAMOPS_DETECTOR — whose finding_id is a hash of the
-- CloudTrail event that raised it and so can never be recomputed from state.
--
-- WHY NOT REUSE compliance_exception
-- Different act. An exception is a governed acceptance of a risk that is still
-- real: it demands a justification, an approver and a mandatory expiry, because
-- "who signed off on this and when does it lapse" is the first thing an auditor
-- asks. This is the opposite claim — the risk is gone. Forcing an approval
-- workflow and an expiry date on "I turned the bucket private" would be
-- ceremony, and routing it through the waiver table would let a one-click action
-- move a compliance score. compliance_exception stays the only thing that can.
--
-- WHY IT IS SELF-CORRECTING
-- A dismissal that outlives the truth is a blindfold, so a resolution is retired
-- rather than permanent. Two things retire one: the provider re-reporting the
-- finding through the event stream (SecurityFindingIngestService's reopen path),
-- or a fresh scan still finding the same problem. That is what makes this safe
-- in a way an automatic sweep would not be — the user's claim is checked against
-- reality at the next opportunity instead of being trusted indefinitely.
-- =============================================================================
CREATE TABLE security_finding_resolution (
    id                BIGSERIAL     PRIMARY KEY,
    -- Scoped by cloud_account_id, matching cloud_security_finding and
    -- aws_cloud_resource. Never by the 12-digit AWS id: one AWS account can own
    -- several cloud_account rows, and keying on the shared id would let one
    -- tenant's dismissal hide another tenant's finding.
    cloud_account_id  BIGINT        NOT NULL REFERENCES cloud_account(id) ON DELETE CASCADE,
    -- sha256Short of resource_id|control_id, not the concatenation itself. The
    -- raw key runs to 2304 characters (a 2048-char ARN plus a 256-char control
    -- id) which is beyond what a btree index entry can hold, so indexing the
    -- literal would fail at insert time on the longest ARNs — the ones most
    -- likely to appear in a real estate. Same reasoning and same helper as the
    -- derived finding ids in SecurityFindingIngestService.
    finding_key_hash  VARCHAR(64)   NOT NULL,
    -- The components, kept in plain text for the audit trail and the "dismissed"
    -- list. Deliberately not indexed: reads load one account's resolutions as a
    -- set and match in memory, so nothing queries these directly.
    resource_id       VARCHAR(2048),
    control_id        VARCHAR(256),
    -- Denormalised so the audit trail survives the finding it refers to.
    severity          VARCHAR(16),
    title             VARCHAR(1024),
    resolved_by       VARCHAR(255)  NOT NULL,
    resolved_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    note              TEXT,
    -- Set when the claim stopped being believed, leaving the row as history.
    -- A hard delete would lose the most interesting record in the table: a
    -- finding that was marked fixed and came back.
    retired_at        TIMESTAMPTZ,
    -- REOPENED_BY_PROVIDER | STILL_PRESENT_IN_SCAN | RESTORED_BY_USER
    retired_reason    VARCHAR(64)
);

-- One live resolution per finding per connection. Partial, so that retiring a
-- resolution leaves the history behind and still allows the user to dismiss the
-- same finding again after it has been fixed a second time — a plain UNIQUE would
-- let the first retired row block that forever.
CREATE UNIQUE INDEX uq_sfr_live
    ON security_finding_resolution (cloud_account_id, finding_key_hash)
    WHERE retired_at IS NULL;

-- The hot read: every live resolution for one connection, loaded per request to
-- filter the findings list.
CREATE INDEX idx_sfr_account_live
    ON security_finding_resolution (cloud_account_id)
    WHERE retired_at IS NULL;
