-- =============================================================================
-- A severity threshold for the cloud alert digest.
--
-- The digest (quota, cost anomaly and security rows on the Alerts page) had no
-- severity control at all. AlertDto carried no severity field: quota checks put
-- CRITICAL/WARNING/OK in `status` while Security Hub and GCP Security Command
-- Center put a real finding severity in that same slot, so "only email me about
-- criticals" was not expressible — WARNING and MEDIUM were indistinguishable and
-- OK meant "not a problem at all".
--
-- Separate from security_notification_preference.severity_threshold on purpose.
-- That one gates the *immediate* per-finding email and defaults to CRITICAL,
-- because an immediate mail should mean "stop what you are doing". Reusing it
-- here would apply a CRITICAL floor to the digest as well and silently drop every
-- quota warning a customer currently receives — a behaviour change nobody asked
-- for, delivered as silence, which is the hardest kind to notice.
-- =============================================================================

ALTER TABLE security_notification_preference
    ADD COLUMN IF NOT EXISTS digest_severity_threshold VARCHAR(16) NOT NULL DEFAULT 'INFORMATIONAL';

-- INFORMATIONAL is the floor of the normalised vocabulary, so the default admits
-- everything and today's digest content is unchanged for every existing client.
-- The column exists so the threshold can be raised per client, not to raise it.
COMMENT ON COLUMN security_notification_preference.digest_severity_threshold IS
    'Minimum normalised severity (CRITICAL/HIGH/MEDIUM/LOW/INFORMATIONAL) included in the '
    'cloud alert digest email. Defaults to INFORMATIONAL, which admits everything.';
