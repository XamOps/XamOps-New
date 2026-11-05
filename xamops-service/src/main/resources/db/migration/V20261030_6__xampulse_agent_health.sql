-- XamPulse: keep the last heartbeat health report (per-source state, recent errors, queue) so a missing-log hint can name the source.
ALTER TABLE xp_agent ADD COLUMN IF NOT EXISTS health_json TEXT;
