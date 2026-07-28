-- Live calling on support tickets.
--
-- A call is a room, not a 1:1 leg: the initiator rings a set of people, the first
-- to accept becomes the answerer, and anyone else who was rung can still join the
-- room afterwards. call_session holds the room; call_participant holds one row per
-- person who was rung or joined, which doubles as the missed-call record.

CREATE TABLE IF NOT EXISTS call_session (
    id                      BIGSERIAL PRIMARY KEY,
    -- LiveKit room name. Opaque and unique so a room is never silently reused.
    room_name               VARCHAR(128) NOT NULL UNIQUE,
    ticket_id               BIGINT       NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    client_id               BIGINT,
    -- Cloud provider account identifier copied from ticket.account_id (AWS account
    -- number / GCP project id), kept for auditing the ring set after the fact.
    account_id              VARCHAR(255),

    direction               VARCHAR(32)  NOT NULL,  -- USER_TO_ADMIN | ADMIN_TO_USER
    state                   VARCHAR(32)  NOT NULL,  -- RINGING | ACTIVE | ENDED | MISSED | DECLINED | CANCELLED
    media_kind              VARCHAR(16)  NOT NULL DEFAULT 'AUDIO', -- AUDIO | VIDEO

    initiator_user_id       BIGINT       NOT NULL,
    initiator_username      VARCHAR(255) NOT NULL,
    initiator_display_name  VARCHAR(255),

    answered_by_user_id     BIGINT,
    answered_by_username    VARCHAR(255),

    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Ring deadline. Enforced server-side: a browser that closes mid-ring must not
    -- be able to strand the call in RINGING forever.
    ring_expires_at         TIMESTAMP    NOT NULL,
    answered_at             TIMESTAMP,
    ended_at                TIMESTAMP,
    end_reason              VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_call_session_ticket    ON call_session(ticket_id);
CREATE INDEX IF NOT EXISTS idx_call_session_state     ON call_session(state);
CREATE INDEX IF NOT EXISTS idx_call_session_initiator ON call_session(initiator_user_id);
-- Drives the sweeper that expires abandoned rings.
CREATE INDEX IF NOT EXISTS idx_call_session_ring_expiry ON call_session(state, ring_expires_at);

CREATE TABLE IF NOT EXISTS call_participant (
    id              BIGSERIAL PRIMARY KEY,
    call_session_id BIGINT       NOT NULL REFERENCES call_session(id) ON DELETE CASCADE,
    user_id         BIGINT       NOT NULL,
    username        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    party           VARCHAR(32)  NOT NULL,  -- INITIATOR | ADMIN | CUSTOMER
    was_rung        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- NULL while the call is live; set once the participant's fate is known.
    outcome         VARCHAR(32),            -- JOINED | DECLINED | MISSED | CANCELLED
    joined_at       TIMESTAMP,
    left_at         TIMESTAMP,
    CONSTRAINT uq_call_participant UNIQUE (call_session_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_call_participant_session ON call_participant(call_session_id);
-- Powers the per-user missed-call badge.
CREATE INDEX IF NOT EXISTS idx_call_participant_user_outcome ON call_participant(user_id, outcome);

COMMENT ON TABLE  call_session      IS 'One live/attempted call room attached to a support ticket';
COMMENT ON TABLE  call_participant  IS 'Per-user ring and join record for a call; rows with outcome=MISSED are the missed-call feed';
COMMENT ON COLUMN call_session.ring_expires_at IS 'Server-side ring deadline; sweeper marks the call MISSED past this point';
