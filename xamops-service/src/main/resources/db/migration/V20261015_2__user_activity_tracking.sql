-- Durable per-user activity tracking, backing the "Check Activity" window in
-- BillOps admin -> Accounts -> Users.
--
-- Why this exists. app_user.last_login_at (added in V20260313) is a single scalar
-- overwritten on every login, so it can answer "when did this user last sign in"
-- and nothing else. There was no login history, no logout record, no session
-- duration, and -- the gap that matters most -- no record whatsoever of a BillOps
-- admin using "Login as" to enter a customer's account, despite that minting an
-- 8-hour full-access token. "Who entered this account, when, and what did they
-- look at" was unanswerable from the database.
--
-- Two tables rather than one, deliberately. Logins are instants; page visits are
-- intervals with a duration. Folding visits into the event table would put nullable
-- duration columns on every login row and make SUM(dwell) GROUP BY route awkward
-- to index.
--
-- app_user.last_login_at is intentionally left in place. BillOpsAdminController and
-- BillOpsClientsCacheWarmingService both read it, and the existing "Last Active"
-- column is driven by it. These tables supplement that column; they do not replace it.

-- ───────────────────────────────────────────────────────────────────────────────
-- Discrete auth events
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_activity_event (
    id             BIGSERIAL    PRIMARY KEY,

    -- XamOps app_user.id. Deliberately NOT a foreign key, matching
    -- identity_audit_log.actor_user_id: an audit row has to outlive the user it
    -- describes. A REFERENCES here would either block user deletion or cascade the
    -- history away, and "this account was deleted on the 3rd" is precisely the
    -- sequence an audit trail exists to preserve.
    user_id        BIGINT       NOT NULL,

    -- Denormalised for client-scoped rollups without joining app_user. Nullable so
    -- an event recorded before the client is resolved still lands.
    client_id      BIGINT       REFERENCES client(id),

    -- LOGIN | LOGIN_FAILED | LOGOUT | IMPERSONATION_STARTED
    action         VARCHAR(100) NOT NULL,

    -- Which mechanism was used for THIS event: LOCAL, OTP, GOOGLE, MICROSOFT.
    -- Distinct from app_user.auth_provider, which is the account's configured
    -- default. A GOOGLE-provisioned user signing in by email code is exactly the
    -- discrepancy worth being able to see.
    auth_provider  VARCHAR(50),

    -- Who CAUSED the event, when that is not the subject. Set to the admin's
    -- app_user.id for IMPERSONATION_STARTED; NULL for ordinary self-service action.
    -- This single column is what makes the impersonation ledger possible.
    actor_user_id  BIGINT,

    -- Hash of the servlet session id. Pairing a LOGIN with its LOGOUT on this value
    -- is what yields session duration; without it there are events but no sessions.
    -- Hashed rather than stored raw so the table is not a session-hijacking aid.
    session_id     VARCHAR(128),

    -- 45 = INET6_ADDRSTRLEN, so IPv6 fits without truncation.
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(512),

    -- SUCCESS | FAILURE. Mirrors the shape of matters_access_audit.
    outcome        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    failure_reason VARCHAR(255),

    -- Free-form JSON for context that does not justify a column.
    metadata       TEXT,

    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- The activity window's primary query: newest events for one user.
CREATE INDEX IF NOT EXISTS idx_uae_user_ts
    ON user_activity_event (user_id, created_at DESC);

-- Client-level rollups ("logins across this tenant this month").
CREATE INDEX IF NOT EXISTS idx_uae_client_ts
    ON user_activity_event (client_id, created_at DESC);

-- ───────────────────────────────────────────────────────────────────────────────
-- Page visits (intervals)
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_page_view (
    id            BIGSERIAL    PRIMARY KEY,

    user_id       BIGINT       NOT NULL,
    client_id     BIGINT       REFERENCES client(id),

    -- Normalised React Router pattern, e.g. /sre/investigations/:id rather than
    -- /sre/investigations/8f21. Storing the raw pathname would make every detail
    -- page a distinct "page" and the top-pages aggregate meaningless.
    route         VARCHAR(255) NOT NULL,

    -- The pathname as actually visited, for drill-down. Truncated by the client.
    path          VARCHAR(512),

    entered_at    TIMESTAMP    NOT NULL,
    exited_at     TIMESTAMP,

    -- Wall-clock milliseconds between entering and leaving the route.
    dwell_ms      INTEGER,

    -- The subset of dwell_ms during which the tab was actually visible.
    --
    -- Kept as its own column because it is the only honest number of the two. Wall
    -- time happily counts a dashboard parked on a second monitor overnight as nine
    -- hours of engagement. Reports should read visible_ms; dwell_ms is retained
    -- because the ratio between them is itself the signal for how much of the
    -- figure to believe.
    visible_ms    INTEGER,

    -- How the visit ended: NAVIGATE (routed away), HIDDEN (tab backgrounded),
    -- UNLOAD (tab or browser closed), TIMEOUT (idle cap reached).
    exit_reason   VARCHAR(20),

    session_id    VARCHAR(128),

    -- Set when a BillOps admin was driving this session via "Login as". Those rows
    -- are excluded from the user's own totals by default -- otherwise "time on page"
    -- silently measures the support team rather than the customer.
    actor_user_id BIGINT,

    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Timeline and session reconstruction.
CREATE INDEX IF NOT EXISTS idx_upv_user_entered
    ON user_page_view (user_id, entered_at DESC);

-- Top-pages aggregation: SUM(visible_ms) GROUP BY route for one user.
CREATE INDEX IF NOT EXISTS idx_upv_user_route
    ON user_page_view (user_id, route);
