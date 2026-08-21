-- Ticket calls: liveness tracking.
--
-- Before this, a call only ever ended because someone pressed hang up. A closed
-- tab, a crashed browser or a dropped network left the participant row at
-- outcome=JOINED with left_at NULL forever, so the session stayed ACTIVE — and
-- because initiate() hands back any RINGING/ACTIVE session on the ticket, that
-- ticket could never start a new call again. Permanently.
--
-- last_seen_at is the fix: the client heartbeats while it is in a room, and a
-- sweeper closes any ACTIVE session whose occupants have all gone quiet. It is
-- seeded on join so a freshly answered call is never mistaken for an abandoned
-- one during the window before its first heartbeat lands.

ALTER TABLE call_participant
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

-- Existing rows: treat the join time as the last known contact. Rows already
-- abandoned will be far past any staleness threshold and get reaped on the first
-- sweep, which is the intended outcome.
UPDATE call_participant
   SET last_seen_at = joined_at
 WHERE last_seen_at IS NULL
   AND joined_at IS NOT NULL;

-- Drives the stale-ACTIVE sweeper, which scans in-room participants by recency.
CREATE INDEX IF NOT EXISTS idx_call_participant_live
    ON call_participant (call_session_id, outcome, left_at);

COMMENT ON COLUMN call_participant.last_seen_at IS
    'Last client heartbeat while in the room; the sweeper closes ACTIVE calls whose occupants have all gone silent';
