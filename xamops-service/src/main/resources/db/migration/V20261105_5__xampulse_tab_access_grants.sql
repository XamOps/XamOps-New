-- XamPulse tab keys (xp-*). user_tab_access is an allow-list (no rows = unrestricted), so restricted users
-- who could see infrastructure telemetry-adjacent areas (secops-xamsecure or finops-cost) receive the
-- eight XamPulse keys. Unrestricted users (no rows) are untouched. Idempotent. String match only.
-- To reverse: DELETE FROM user_tab_access WHERE tab_name LIKE 'xp-%';
-- Guarded because user_tab_access is created by Hibernate, not by a migration.
DO $$
BEGIN
    IF to_regclass('user_tab_access') IS NOT NULL THEN
        INSERT INTO user_tab_access (user_id, tab_name)
        SELECT DISTINCT uta.user_id, k.tab
        FROM user_tab_access uta
        CROSS JOIN (VALUES ('xp-overview'), ('xp-hosts'), ('xp-containers'), ('xp-logs'),
                           ('xp-metrics'), ('xp-alerts'), ('xp-insights'), ('xp-setup')) AS k(tab)
        WHERE uta.tab_name IN ('secops-xamsecure', 'finops-cost')
          AND NOT EXISTS (
              SELECT 1 FROM user_tab_access existing
              WHERE existing.user_id = uta.user_id AND existing.tab_name = k.tab
          );
    END IF;
END $$;
