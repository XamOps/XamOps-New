-- XamLens becomes a SecOps sub-tab with one key, secops-xamlens, like XamSecure.
--
-- Supersedes the three per-tab keys V20260929_1 added hours earlier (xamlens-cloud, xamlens-saas,
-- xamlens-endpoint), when XamLens was briefly a top-level module. V20260929_1 is left untouched:
-- Flyway checksums applied migrations, so editing it would stop the service from starting wherever it
-- has already run.
--
-- user_tab_access is an allow-list (no rows = unrestricted, any rows = only those keys), so the new key
-- is granted to every restricted user who could see the DSPM tabs before: anyone holding
-- secops-xamsecure (where the tabs lived until today) or any of the superseded keys. The superseded
-- keys are then removed, after their holders have the replacement. They exist in no catalog any more,
-- and the admin screens load and re-save a user's whole list, so left in place they would be carried
-- through every edit. Unrestricted users (no rows) are untouched. Idempotent.
--
-- To reverse: DELETE FROM user_tab_access WHERE tab_name = 'secops-xamlens';
--
-- Guarded because the table is created by Hibernate rather than by a migration, so a fresh database
-- may not have it yet when Flyway runs.

DO $$
BEGIN
    IF to_regclass('user_tab_access') IS NOT NULL THEN
        INSERT INTO user_tab_access (user_id, tab_name)
        SELECT DISTINCT uta.user_id, 'secops-xamlens'
        FROM user_tab_access uta
        WHERE uta.tab_name IN ('secops-xamsecure', 'xamlens-cloud', 'xamlens-saas', 'xamlens-endpoint')
          AND NOT EXISTS (
              SELECT 1 FROM user_tab_access existing
              WHERE existing.user_id = uta.user_id
                AND existing.tab_name = 'secops-xamlens'
          );

        DELETE FROM user_tab_access
        WHERE tab_name IN ('xamlens-cloud', 'xamlens-saas', 'xamlens-endpoint');
    END IF;
END $$;
