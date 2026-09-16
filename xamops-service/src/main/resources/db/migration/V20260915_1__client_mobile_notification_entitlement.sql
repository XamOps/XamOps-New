-- Per-client entitlement for SMS and WhatsApp notification delivery, controlled from
-- BillOps admin → Accounts.
--
-- DEFAULT FALSE, unlike xamsecure_onboarding_enabled which defaults TRUE. These channels are
-- billed per message by the provider (~INR 0.125 per WhatsApp send at time of writing), so the
-- safe state for every existing and future client is off until someone deliberately grants it.
-- Enabling this for all clients on migration would start charging for every alert and scheduled
-- report across the estate.
--
-- Two columns rather than one "mobile notifications" flag: the channels are priced separately and
-- approved through different regimes — Meta template review for WhatsApp, TRAI/DLT registration
-- for SMS — so a client can be entitled to one and not the other.
--
-- Email delivery is untouched by both flags and remains the channel of record.

ALTER TABLE client
    ADD COLUMN IF NOT EXISTS sms_notifications_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS whatsapp_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill guard: the columns are NOT NULL DEFAULT FALSE, but an earlier hand-run ALTER on a
-- given environment may have added them nullable. Matches the pattern used by
-- V20260821_1__xamsecure_self_service_onboarding.sql.
UPDATE client SET sms_notifications_enabled = FALSE      WHERE sms_notifications_enabled IS NULL;
UPDATE client SET whatsapp_notifications_enabled = FALSE WHERE whatsapp_notifications_enabled IS NULL;
