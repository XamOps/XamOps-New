-- ============================================================================
-- rollback-lob-text.sql -- put the original OID references back
-- ============================================================================
--
-- Purpose
--   Undo normalize-lob-text-columns.sql (Script N) and
--   dev-ops-script-lob-to-inline.sql (Script D) from their shared backup table
--   lob_text_backup_20261005, restoring the original large-object reference in
--   every row that still holds exactly the text those scripts wrote.
--
-- Preconditions
--   * Any psql (no \if, no client-side variable is used). Verify: psql --version.
--   * lob_text_backup_20261005 exists. Verify:
--       SELECT count(*) FROM lob_text_backup_20261005;
--     If it is absent the script reports 0 rows and commits without changes.
--   * Confirmation, in the same session, before this file:
--       SET lobtext.confirm = 'rollback-lob-text';
--     Verify: SELECT current_setting('lobtext.confirm', true);
--   * The large objects are still present. They are never unlinked, so this holds
--     unless someone ran vacuumlo. Verify with the same guarded candidate rule the
--     other scripts use -- the regex first, then the range, never a bare cast:
--       WITH b AS (SELECT CASE WHEN original_text ~ '^[1-9][0-9]{4,9}$'
--                              THEN CASE WHEN original_text::bigint
--                                             BETWEEN 16384 AND 4294967295
--                                        THEN original_text::bigint::oid END END AS cand
--                  FROM lob_text_backup_20261005)
--       SELECT count(*) FROM b
--       WHERE b.cand IS NOT NULL
--         AND NOT EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = b.cand);
--     A non-zero count means those rows would be restored to a dangling
--     reference, which the application reads as a miss.
--   * Only roll back while a build that can resolve references is deployed.
--
-- Window / lock impact
--   Same as the script being undone, and smaller: one ROW EXCLUSIVE lock per
--   restored row, held until COMMIT. SET LOCAL lock_timeout = '5s' aborts the run
--   instead of queueing behind an application transaction.
--
-- What changes
--   * One UPDATE per restorable row, setting only the value column. last_updated
--     is never named, so it is untouched.
--   * A row whose current text is neither the original nor what the script wrote
--     was changed by the application afterwards. It is counted as
--     restore_skipped_changed and left alone -- newer data is never overwritten.
--   * Backup rows are kept, so this script is re-runnable: a second run restores
--     0 and reports every row as already_restored.
--   * No large object is created or removed.
--
-- Rollback
--   Re-run the script that was undone (Script N or Script D). Both are idempotent
--   and both back up again before converting.
--
-- Post-steps
--   1. Run scripts/db/lob-text/census-lob-text.sql and compare `candidates`
--      against the before-census of the run being undone.
--   2. Drop the derived caches again (runbook §8.3), because they now hold values
--      computed from the converted rows.
--
-- Large objects
--   lo_unlink and vacuumlo are never used by any script in this directory. Do not
--   run vacuumlo while any TEXT column, or any lob_text_* backup table, still
--   holds an OID: vacuumlo only understands oid/lo typed columns, so it would see
--   these references as unreferenced and delete the objects they point at -- and
--   that would make this rollback restore dangling references.
--
-- Usage
--   psql -X -v ON_ERROR_STOP=1 \
--        -c "SET lobtext.confirm = 'rollback-lob-text'" \
--        -f scripts/db/lob-text/rollback-lob-text.sql
--   Output goes to stderr as NOTICE lines:
--     lobtext|<phase>|<table>|<column>|<metric>|<value>
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- 1. Confirmation and lock policy --------------------------------------------
DO $confirm$
BEGIN
    IF COALESCE(current_setting('lobtext.confirm', true), '') <> 'rollback-lob-text' THEN
        RAISE EXCEPTION 'lobtext: refusing to run rollback-lob-text.sql'
            USING HINT = 'first run: SET lobtext.confirm = ''rollback-lob-text''';
    END IF;
END
$confirm$;

SET LOCAL lock_timeout = '5s';

-- 2. Restore ------------------------------------------------------------------
DO $restore$
DECLARE
    tgt         record;
    b           record;
    current_txt text;
    touched     int;
    c_restored  bigint;
    c_changed   bigint;
    c_already   bigint;
    t_restored  bigint := 0;
    t_changed   bigint := 0;
    t_already   bigint := 0;
BEGIN
    IF to_regclass('lob_text_backup_20261005') IS NULL THEN
        RAISE NOTICE 'lobtext: lob_text_backup_20261005 does not exist, nothing to restore';
        RAISE NOTICE 'lobtext|run|all|all|restored|0';
        RAISE NOTICE 'lobtext|run|all|all|restore_skipped_changed|0';
        RAISE NOTICE 'lobtext|run|all|all|already_restored|0';
        RETURN;
    END IF;

    FOR tgt IN
        SELECT *
        FROM (VALUES
            -- table, key column, key type, value column: Script N's four columns ...
            ('cached_data',               'cache_key', 'text',   'json_data'),
            ('cloudsitter_policies',      'id',        'bigint', 'schedule_json'),
            ('github_integration_config', 'id',        'bigint', 'encrypted_pat'),
            ('sonarqube_project',         'id',        'bigint', 'encrypted_token'),
            -- ... and Script D's four
            ('dev_ops_script',            'id',        'bigint', 'code'),
            ('dev_ops_script',            'id',        'bigint', 'usage_instructions'),
            ('devops_script',             'id',        'bigint', 'code'),
            ('devops_script',             'id',        'bigint', 'usage_instructions')
        ) AS t(tbl, keycol, keytype, col)
    LOOP
        IF to_regclass(tgt.tbl) IS NULL THEN
            RAISE NOTICE 'lobtext: table % is absent here, skipped', tgt.tbl;
            CONTINUE;
        END IF;
        c_restored := 0; c_changed := 0; c_already := 0;

        FOR b IN
            SELECT row_key, original_text, converted_md5
            FROM lob_text_backup_20261005
            WHERE table_name = tgt.tbl AND column_name = tgt.col
            ORDER BY row_key
        LOOP
            EXECUTE format('SELECT %1$I FROM %2$I WHERE %3$I = $1::%4$s',
                           tgt.col, tgt.tbl, tgt.keycol, tgt.keytype)
            INTO current_txt USING b.row_key;

            IF current_txt IS NULL THEN
                -- the row was deleted, or the column is NULL now: nothing to undo
                c_changed := c_changed + 1;
            ELSIF current_txt = b.original_text THEN
                c_already := c_already + 1;
            ELSIF md5(current_txt) = b.converted_md5 THEN
                -- Only the value column is named, so last_updated is untouched, and
                -- the digest predicate makes a concurrently changed row a no-op.
                EXECUTE format('UPDATE %1$I SET %2$I = $1 WHERE %3$I = $2::%4$s AND %2$I = $3',
                               tgt.tbl, tgt.col, tgt.keycol, tgt.keytype)
                USING b.original_text, b.row_key, current_txt;
                GET DIAGNOSTICS touched = ROW_COUNT;
                IF touched = 1 THEN
                    c_restored := c_restored + 1;
                ELSE
                    c_changed := c_changed + 1;
                END IF;
            ELSE
                c_changed := c_changed + 1;
            END IF;
        END LOOP;

        RAISE NOTICE 'lobtext|run|%|%|restored|%',                tgt.tbl, tgt.col, c_restored;
        RAISE NOTICE 'lobtext|run|%|%|restore_skipped_changed|%', tgt.tbl, tgt.col, c_changed;
        RAISE NOTICE 'lobtext|run|%|%|already_restored|%',        tgt.tbl, tgt.col, c_already;

        t_restored := t_restored + c_restored;
        t_changed  := t_changed + c_changed;
        t_already  := t_already + c_already;
    END LOOP;

    RAISE NOTICE 'lobtext|run|all|all|restored|%',                t_restored;
    RAISE NOTICE 'lobtext|run|all|all|restore_skipped_changed|%', t_changed;
    RAISE NOTICE 'lobtext|run|all|all|already_restored|%',        t_already;
END
$restore$;

-- Backup rows are kept on purpose, so this script can be re-run and so the
-- original (row, OID) pairs stay auditable.
COMMIT;
