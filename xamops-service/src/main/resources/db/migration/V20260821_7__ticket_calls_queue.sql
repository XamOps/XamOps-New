-- Ticket calls: turn the 40-second ring into a real queue.
--
-- Before this a call had exactly one deadline. If nobody picked up inside it — or
-- worse, if no agent happened to have a tab open at the moment of dialling — the
-- call was closed within seconds and the customer was told nobody was available.
-- That is correct for a phone and wrong for a support desk, where waiting a few
-- minutes for someone to become free is the normal case.
--
-- Now there are two deadlines:
--
--   ring_expires_at   how long agents are interrupted with a ringtone (~45s)
--   queue_expires_at  how long the caller keeps waiting overall (~10min)
--
-- Between the two the call is "queued": it stays open and answerable, the
-- ringtone stops, and it shows up in every agent's waiting list — including agents
-- who only log in partway through. The state stays RINGING throughout, so accept,
-- decline, join and cancel need no special cases; `queued` marks which phase it is
-- in, which also keeps the escalation from firing more than once.

ALTER TABLE call_session
    ADD COLUMN IF NOT EXISTS queue_expires_at TIMESTAMP;

ALTER TABLE call_session
    ADD COLUMN IF NOT EXISTS queued BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing rows: no queue phase ever applied to them, so the queue deadline is the
-- ring deadline. Anything still open is long dead and gets swept on the next pass.
UPDATE call_session
   SET queue_expires_at = ring_expires_at
 WHERE queue_expires_at IS NULL;

-- Drives the second sweep, which gives up on a call nobody took within the queue
-- window.
CREATE INDEX IF NOT EXISTS idx_call_session_queue_expiry
    ON call_session (state, queue_expires_at);

-- Drives the first sweep: ringing calls not yet escalated to the quiet queue phase.
CREATE INDEX IF NOT EXISTS idx_call_session_ring_escalation
    ON call_session (state, queued, ring_expires_at);

COMMENT ON COLUMN call_session.queue_expires_at IS
    'Overall wait deadline; the sweeper records the call as missed past this point';
COMMENT ON COLUMN call_session.queued IS
    'True once the loud ring window has passed and the call is waiting quietly in the queue';
