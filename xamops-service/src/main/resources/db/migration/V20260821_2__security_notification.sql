-- =============================================================================
-- Notification preferences and the in-app findings inbox.
--
-- Both exist because the platform had neither. Every digest today goes to every
-- user under the client with no opt-out, no severity threshold and no channel
-- choice, and the only in-app inbox is sre_notification — feature-specific, and
-- the closest thing to a precedent.
--
-- Sending an immediate email on every critical finding without a preferences
-- table would be unbounded: an account under attack produces findings in bursts,
-- and the user has no way to turn it off. So the table comes first.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- Per-client notification preferences.
--
-- Keyed on client rather than user: recipients are already resolved as "every
-- user under the client" throughout CloudGuardService, and a per-user table
-- would imply a settings UI per user that does not exist yet. Per-client is the
-- smallest thing that makes the feature controllable.
--
-- Absence of a row means defaults apply, so no backfill is needed and a client
-- created tomorrow behaves the same as one created last year.
-- ----------------------------------------------------------------------------
CREATE TABLE security_notification_preference (
    client_id              BIGINT       PRIMARY KEY REFERENCES client(id) ON DELETE CASCADE,

    -- Master switch for immediate security emails.
    email_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Minimum normalised severity that triggers an immediate email. Defaults to
    -- CRITICAL rather than HIGH: an immediate email should mean "stop what you
    -- are doing", and anything more frequent than that trains people to ignore
    -- it. HIGH and below still reach the twice-weekly digest.
    severity_threshold     VARCHAR(16)  NOT NULL DEFAULT 'CRITICAL',

    -- Cap on immediate emails per account per hour. GuardDuty can emit hundreds
    -- of findings in minutes during an active incident; without a cap the first
    -- thing a compromised customer experiences is a mail flood, which buries the
    -- one message that mattered.
    max_emails_per_hour    INTEGER      NOT NULL DEFAULT 4,

    -- Whether to write in-app inbox rows. Separate from email because the two
    -- have different tolerances: the inbox is free and silent, so most users
    -- want it on even when they mute email.
    inbox_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,

    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ----------------------------------------------------------------------------
-- In-app inbox.
--
-- One row per notification batch, not per finding. A batch is what the email
-- says and what a user acts on: "3 critical findings on account X" is one thing
-- to read and dismiss, whereas a row per finding makes the bell counter a
-- restatement of the findings table with a second read-state to keep in sync.
--
-- Modelled on sre_notification (client-scoped, is_read flag, polled over REST)
-- so the existing bell UI pattern applies without new frontend concepts.
-- ----------------------------------------------------------------------------
CREATE TABLE security_notification (
    id                BIGSERIAL     PRIMARY KEY,

    client_id         BIGINT        NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    -- Nullable: the owning connection can be deleted while the notification is
    -- still worth reading, and SET NULL keeps the history rather than erasing
    -- the record that an account had critical findings before it was removed.
    cloud_account_id  BIGINT        REFERENCES cloud_account(id) ON DELETE SET NULL,
    aws_account_id    VARCHAR(32),

    title             VARCHAR(512)  NOT NULL,
    message           TEXT,

    -- Highest severity in the batch, so the bell can be coloured without
    -- joining back to the findings.
    severity          VARCHAR(16)   NOT NULL,
    finding_count     INTEGER       NOT NULL DEFAULT 1,

    is_read           BOOLEAN       NOT NULL DEFAULT FALSE,
    email_sent        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- The bell: unread count and the dropdown list, both per client.
CREATE INDEX idx_sn_client_unread
    ON security_notification (client_id, is_read, created_at DESC);

CREATE INDEX idx_sn_client_created
    ON security_notification (client_id, created_at DESC);
