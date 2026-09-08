-- Email-to-ticket ingestion.
--
-- Until now mail only flowed outward: raising a ticket sent a notification to
-- techtickets@xamops.com or bizfin@xammer.com. Customers replied to those threads
-- and nobody's reply became anything. This is the inbound half — a mail that
-- mentions one of those group addresses in To or Cc becomes a ticket, and later
-- mail on the same thread becomes a reply on it.
--
-- Three schema problems had to be solved for that to work.

-- 1. Where the ticket came from.
--
-- Agents need to know at a glance that a ticket arrived by mail rather than
-- through the form, because the answer goes back out by mail and the customer
-- may never log in to see it. Existing rows are all form submissions.
ALTER TABLE ticket
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'WEB';

COMMENT ON COLUMN ticket.source IS
    'WEB for tickets raised through the app, EMAIL for tickets ingested from the support mailbox';

-- 2. Replies whose author is not a user.
--
-- ticket_reply.user_id was NOT NULL, which assumes every reply comes from
-- somebody with an account. Mail arrives from whoever is on the thread — a
-- customer''s colleague, a vendor, someone CC''d once. Those replies must still
-- be stored, so the author link becomes optional and the raw address is kept
-- alongside it for display.
--
-- Deliberately NOT solved by minting a user row per address: those rows would
-- land in user lists, in access checks, and in the login table with no password.
ALTER TABLE ticket_reply
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE ticket_reply
    ADD COLUMN IF NOT EXISTS author_email VARCHAR(320);

ALTER TABLE ticket_reply
    ADD COLUMN IF NOT EXISTS author_name VARCHAR(255);

COMMENT ON COLUMN ticket_reply.author_email IS
    'Sender address when the reply came from mail and matched no user; user_id is null in that case';
COMMENT ON COLUMN ticket_reply.author_name IS
    'Display name from the mail From header, when there was one';

-- 3. Idempotency, and the record of the mail itself.
--
-- Every processed message gets a row here, including ones that produced no
-- ticket (loops, auto-replies). Two things depend on that being *every* message:
--
--   * The backend runs 2 replicas (eks/manifests/05-backend.yaml) with no
--     scheduler locking, so both poll the mailbox and both see the same unread
--     message. The unique constraint on message_id is what stops that becoming
--     two tickets: both transactions race, one commits, the other violates the
--     constraint and rolls back its ticket with it. This constraint is load
--     bearing, not a tidiness measure.
--
--   * IMAP flags are not a reliable ledger. A connection dropped between
--     creating the ticket and setting \Seen would otherwise reprocess the
--     message on the next poll.
--
-- It doubles as the "detail about that mail" shown on the ticket: who sent it,
-- who else was on it, and which address was mentioned.
CREATE TABLE IF NOT EXISTS ticket_email_message (
    id                BIGSERIAL PRIMARY KEY,

    -- Null for messages that were processed and deliberately produced nothing.
    ticket_id         BIGINT REFERENCES ticket (id) ON DELETE CASCADE,
    ticket_reply_id   BIGINT REFERENCES ticket_reply (id) ON DELETE CASCADE,

    -- RFC 5322 caps a header line at 998 octets; Message-ID is well under that in
    -- practice but there is no reason to guess lower.
    message_id        VARCHAR(998) NOT NULL,
    in_reply_to       VARCHAR(998),
    references_header TEXT,

    -- 320 = RFC 5321 maximum address length (64 local + @ + 255 domain).
    from_address      VARCHAR(320) NOT NULL,
    from_name         VARCHAR(255),
    to_addresses      TEXT,
    cc_addresses      TEXT,

    -- Which group address was mentioned. Decides the ticket category, and is worth
    -- keeping because a mail can mention more than one.
    mailbox_address   VARCHAR(320),

    subject           TEXT,
    sent_at           TIMESTAMP,
    received_at       TIMESTAMP NOT NULL DEFAULT now(),

    -- TICKET_CREATED | REPLY_APPENDED | SKIPPED_LOOP | SKIPPED_AUTO_REPLY
    --   | SKIPPED_NO_MATCH | FAILED
    status            VARCHAR(32) NOT NULL,

    -- USER_EMAIL | DOMAIN | UNIDENTIFIED — how the sender was tied to a client.
    -- UNIDENTIFIED means the ticket sits under the intake client awaiting triage.
    match_method      VARCHAR(32),

    CONSTRAINT uq_ticket_email_message_message_id UNIQUE (message_id)
);

-- Threading: an incoming reply carries In-Reply-To / References pointing at the
-- Message-ID of an earlier mail on the thread. This index is what turns those
-- headers into "which ticket does this belong to".
CREATE INDEX IF NOT EXISTS idx_ticket_email_message_ticket
    ON ticket_email_message (ticket_id);

-- Triage queue: the unidentified senders an admin needs to assign to a client.
CREATE INDEX IF NOT EXISTS idx_ticket_email_message_match_method
    ON ticket_email_message (match_method)
    WHERE match_method = 'UNIDENTIFIED';

COMMENT ON TABLE ticket_email_message IS
    'One row per mail read from the support mailbox. Unique message_id makes ingestion idempotent across replicas.';
