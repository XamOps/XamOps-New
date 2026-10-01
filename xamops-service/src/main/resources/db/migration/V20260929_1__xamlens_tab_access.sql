-- XamLens: the DSPM tabs move out of XamSecure into their own module, with their own tab keys.
--
-- user_tab_access is an allow-list: a user with no rows is unrestricted, a user with any rows sees
-- only those keys. A key introduced today is missing from every list saved before it, so without this
-- every restricted user who could see the DSPM tabs under XamSecure would silently lose them.
--
-- The DSPM tabs rode secops-xamsecure, so granting the three XamLens keys to exactly the users who
-- hold secops-xamsecure keeps today's access, no more and no less. Unrestricted users (no rows) are
-- untouched and still see everything. Idempotent: users who already hold a key are skipped.
--
-- To reverse: DELETE FROM user_tab_access
--             WHERE tab_name IN ('xamlens-cloud', 'xamlens-saas', 'xamlens-endpoint');
--
-- Guarded because the table is created by Hibernate rather than by a migration, so a fresh database
-- may not have it yet when Flyway runs.

DO $$
BEGIN
    IF to_regclass('user_tab_access') IS NOT NULL THEN
        INSERT INTO user_tab_access (user_id, tab_name)
        SELECT DISTINCT uta.user_id, k.tab_name
        FROM user_tab_access uta
        CROSS JOIN (VALUES ('xamlens-cloud'), ('xamlens-saas'), ('xamlens-endpoint')) AS k(tab_name)
        WHERE uta.tab_name = 'secops-xamsecure'
          AND NOT EXISTS (
              SELECT 1 FROM user_tab_access existing
              WHERE existing.user_id = uta.user_id
                AND existing.tab_name = k.tab_name
          );
    END IF;
END $$;
