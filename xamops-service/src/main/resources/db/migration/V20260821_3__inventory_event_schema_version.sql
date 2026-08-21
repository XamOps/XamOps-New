-- =============================================================================
-- Records which version of the event forwarder an account is running.
--
-- Version 1 forwards resource changes. Version 2 also attaches a slimmed
-- CloudTrail detail, which is what the exposure detectors need in order to tell
-- a security group opened to the office VPN from one opened to 0.0.0.0/0.
--
-- This column exists because capability is otherwise unobservable. An account on
-- an old stack produces no exposure findings — and so does a genuinely secure
-- account on a current stack. Without a version to compare, the product cannot
-- distinguish "nothing is wrong" from "nothing is being checked", and would show
-- an unmonitored customer a clean security page.
--
-- Nullable with no default: null means no event has arrived yet, which is
-- different from version 1 and should not be reported as an out-of-date stack.
-- =============================================================================

ALTER TABLE aws_inventory_sync_state
    ADD COLUMN IF NOT EXISTS event_schema_version INTEGER;
