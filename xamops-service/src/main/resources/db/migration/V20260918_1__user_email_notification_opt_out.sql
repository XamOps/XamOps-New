-- Per-USER opt-out for security and WAR email, controlled from BillOps admin → Accounts → Users.
--
-- Why per user and not per client. The existing notification switches are all client-scoped
-- (security_notification_preference.email_enabled, client.sms_notifications_enabled,
-- client.whatsapp_notifications_enabled), so the only way to quieten one person was to silence
-- the whole tenant. Every security fan-out resolves recipients the same way — every row in
-- app_user for the client — which means a ten-user tenant where two people own security has
-- eight people receiving WAR and finding email they cannot act on. The usual outcome is a mail
-- rule, and after that nobody on the tenant is reading these at all.
--
-- DEFAULT TRUE, unlike the SMS/WhatsApp entitlements which default FALSE. Those gate spend, so
-- the safe default is off. This one gates delivery of security alerts the customer already pays
-- for, and defaulting it off would silently stop WAR and finding email for every existing user on
-- deploy — a failure indistinguishable from the notifier being broken. Opting out has to be a
-- deliberate act, per user, with an audit line in the log.
--
-- Scope of the flag, deliberately narrow. SUPPRESSED:
--   * the WAR onboarding report          (WarOnboardingReportService)
--   * security finding notifications     (SecurityFindingNotifier)
--   * the AWS alert digest               (CloudGuardService)
--   * the GCP alert digest               (GcpCloudGuardService)
--
-- NOT SUPPRESSED, and not by omission:
--   * cost and operational email — monthly cost digest, budget alerts, DB health alerts. Someone
--     who is not on the security rota may still be the person who owns the bill.
--   * login OTP, password reset, team invitations. Muting security mail must not lock anyone out.
--   * ticket correspondence, which is a conversation the user started.
--   * the in-app notification bell and WebSocket push. Findings stay visible in the product to
--     every user — this switch is about the mailbox, not about what someone is allowed to see.

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill guard: the column is NOT NULL DEFAULT TRUE, but an earlier hand-run ALTER on a given
-- environment may have added it nullable. Same pattern as
-- V20260821_1__xamsecure_self_service_onboarding.sql and
-- V20260915_1__client_mobile_notification_entitlement.sql.
UPDATE app_user SET email_notifications_enabled = TRUE WHERE email_notifications_enabled IS NULL;
