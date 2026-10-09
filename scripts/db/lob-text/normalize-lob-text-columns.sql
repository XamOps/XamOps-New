-- ============================================================================
-- normalize-lob-text-columns.sql (Script N) -- large-object refs -> inline text
-- ============================================================================
--
-- Purpose
--   Replace every remaining PostgreSQL large-object (OID) reference in the four
--   in-scope TEXT columns with the text the object holds, so that the columns hold
--   one format again. Scope:
--     cached_data.json_data
--     cloudsitter_policies.schedule_json
--     github_integration_config.encrypted_pat
--     sonarqube_project.encrypted_token
--   The whole run is one transaction: it either converts everything it can, or
--   changes nothing.
--
-- Preconditions
--   * Any psql (no \if, no client-side variable is used). Verify: psql --version.
--   * Every application instance that writes these columns runs the inline write
--     mode (xammer.lob-text.write-mode=inline), and the August build is retired.
--     Verify: check the deployed image tag and the startup line
--       "lob-text write-mode=INLINE" in each service log.
--     A writer still in legacy mode re-creates references while this runs, which
--     step 7 detects: the whole transaction then rolls back.
--   * Confirmation, in the same session, before this file:
--       SET lobtext.confirm = 'august-retired-and-inline-mode';
--     Verify: SELECT current_setting('lobtext.confirm', true);
--   * No user trigger on the four tables (step 2 checks this and aborts),
--     because last_updated preservation would otherwise be unprovable.
--   * Enough free space for the backup table: one row per converted value,
--     holding the original OID text (tiny), not the payload.
--
-- Window / lock impact
--   Needs a maintenance window. Each converted row takes a ROW EXCLUSIVE lock
--   that is held until COMMIT, so application writes to those rows block until
--   then; reads are never blocked. SET LOCAL lock_timeout = '5s' means the run
--   aborts and rolls back rather than queueing behind an application transaction.
--   Measure the duration on the rehearsal clone first (runbook §8.1).
--
-- What changes
--   * CREATE TABLE IF NOT EXISTS lob_text_backup_20261005 (audit and rollback).
--   * One UPDATE per convertible row, setting only the value column. last_updated
--     is never named, so it keeps the time the data was fetched.
--   * Rows whose object is missing, unreadable, undecodable or itself OID shaped
--     are counted, reported and left exactly as they are.
--   * No large object is created or removed, so every reference stays resolvable.
--
-- Rollback
--   Before COMMIT: any error, including the step 8 rehearsal hook, rolls the whole
--   run back by itself.
--   After COMMIT: psql -X -v ON_ERROR_STOP=1 \
--       -c "SET lobtext.confirm = 'rollback-lob-text'" \
--       -f scripts/db/lob-text/rollback-lob-text.sql
--   That restores from lob_text_backup_20261005, and only where the current text
--   is still exactly what this script wrote.
--
-- Post-steps
--   1. Re-run scripts/db/lob-text/census-lob-text.sql: convertible must be 0 for
--      all four columns.
--   2. Drop the derived caches that were computed from the unresolved values
--      (runbook §8.3), so they recompose from the converted rows.
--   3. Keep lob_text_backup_20261005 until the soak window has passed.
--   4. Report-only columns (design D17) are untouched here; dev_ops_script and
--      devops_script have their own script, dev-ops-script-lob-to-inline.sql.
--
-- Large objects
--   lo_unlink and vacuumlo are never used by any script in this directory. Do not
--   run vacuumlo while any TEXT column, or any lob_text_* backup table, still
--   holds an OID: vacuumlo only understands oid/lo typed columns, so it would see
--   these references as unreferenced and delete the objects they point at. Leaving
--   the objects in place is what keeps the rollback above possible.
--
-- Usage
--   psql -X -v ON_ERROR_STOP=1 \
--        -c "SET lobtext.confirm = 'august-retired-and-inline-mode'" \
--        -f scripts/db/lob-text/normalize-lob-text-columns.sql
--   Rehearsal (converts, reports, then rolls everything back):
--   psql -X -v ON_ERROR_STOP=1 \
--        -c "SET lobtext.confirm = 'august-retired-and-inline-mode'" \
--        -c "SET lobtext.fail_after_convert = 'on'" \
--        -f scripts/db/lob-text/normalize-lob-text-columns.sql
--   Output goes to stderr as NOTICE lines:
--     lobtext|<phase>|<table>|<column>|<metric>|<value>
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- 1. Confirmation and lock policy --------------------------------------------
DO $confirm$
BEGIN
    IF COALESCE(current_setting('lobtext.confirm', true), '') <> 'august-retired-and-inline-mode' THEN
        RAISE EXCEPTION 'lobtext: refusing to run normalize-lob-text-columns.sql'
            USING HINT = 'first run: SET lobtext.confirm = ''august-retired-and-inline-mode''';
    END IF;
END
$confirm$;

SET LOCAL lock_timeout = '5s';

-- 2. Pre-checks ---------------------------------------------------------------
DO $precheck$
DECLARE
    tgt      record;
    datatype text;
    trg      text;
    present  int := 0;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            ('cached_data',               'json_data'),
            ('cloudsitter_policies',      'schedule_json'),
            ('github_integration_config', 'encrypted_pat'),
            ('sonarqube_project',         'encrypted_token')
        ) AS t(tbl, col)
    LOOP
        IF to_regclass(tgt.tbl) IS NULL THEN
            RAISE NOTICE 'lobtext: table % is absent here, skipped', tgt.tbl;
            CONTINUE;
        END IF;
        SELECT c.data_type INTO datatype
        FROM information_schema.columns c
        WHERE c.table_schema = 'public' AND c.table_name = tgt.tbl AND c.column_name = tgt.col;
        IF datatype IS NULL THEN
            RAISE EXCEPTION 'lobtext: %.% does not exist', tgt.tbl, tgt.col;
        END IF;
        IF datatype <> 'text' THEN
            RAISE EXCEPTION 'lobtext: %.% is %, expected text', tgt.tbl, tgt.col, datatype;
        END IF;
        SELECT string_agg(g.tgname, ', ') INTO trg
        FROM pg_trigger g
        WHERE g.tgrelid = to_regclass(tgt.tbl) AND NOT g.tgisinternal;
        IF trg IS NOT NULL THEN
            RAISE EXCEPTION 'lobtext: user trigger(s) on %: % -- drop them or convert manually', tgt.tbl, trg;
        END IF;
        present := present + 1;
    END LOOP;
    IF present = 0 THEN
        RAISE EXCEPTION 'lobtext: none of the four in-scope tables exist in this database';
    END IF;
    RAISE NOTICE 'lobtext: pre-checks passed for % of 4 in-scope tables', present;
END
$precheck$;

-- 3. Before-census ------------------------------------------------------------
DO $before$
DECLARE
    tgt        record;
    r          record;
    decoded    text;
    n_rows     bigint;
    n_nulls    bigint;
    n_noncand  bigint;
    n_digits   bigint;
    n_cand     bigint;
    n_exists   bigint;
    n_readable bigint;
    n_decode   bigint;
    n_oidshape bigint;
    n_convert  bigint;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            ('cached_data',               'json_data'),
            ('cloudsitter_policies',      'schedule_json'),
            ('github_integration_config', 'encrypted_pat'),
            ('sonarqube_project',         'encrypted_token')
        ) AS t(tbl, col)
    LOOP
        CONTINUE WHEN to_regclass(tgt.tbl) IS NULL;
        EXECUTE format($q$
            SELECT count(*),
                   count(*) FILTER (WHERE s.val IS NULL),
                   count(*) FILTER (WHERE s.val IS NOT NULL AND s.val !~ '^[0-9]+$'),
                   count(*) FILTER (WHERE s.val ~ '^[0-9]+$' AND s.cand IS NULL),
                   count(*) FILTER (WHERE s.cand IS NOT NULL)
            FROM (SELECT %1$I AS val,
                         CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint::oid END END AS cand
                  FROM %2$I) s
        $q$, tgt.col, tgt.tbl)
        INTO n_rows, n_nulls, n_noncand, n_digits, n_cand;

        n_exists := 0; n_readable := 0; n_decode := 0; n_oidshape := 0; n_convert := 0;
        FOR r IN EXECUTE format($q$
            SELECT s.cand,
                   (m.oid IS NOT NULL) AS lo_exists,
                   CASE WHEN m.oid IS NULL THEN false
                        WHEN (SELECT x.rolsuper FROM pg_roles x WHERE x.rolname = current_user) THEN true
                        WHEN current_setting('lo_compat_privileges')::boolean THEN true
                        WHEN pg_has_role(m.lomowner, 'USAGE') THEN true
                        ELSE EXISTS (SELECT 1
                                     FROM aclexplode(COALESCE(m.lomacl, acldefault('L', m.lomowner))) a
                                     WHERE a.privilege_type = 'SELECT'
                                       AND CASE WHEN a.grantee = 0 THEN true
                                                ELSE pg_has_role(a.grantee, 'USAGE') END)
                   END AS can_read
            FROM (SELECT CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint::oid END END AS cand
                  FROM %2$I) s
            LEFT JOIN pg_largeobject_metadata m ON m.oid = s.cand
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl)
        LOOP
            CONTINUE WHEN NOT r.lo_exists;
            n_exists := n_exists + 1;
            CONTINUE WHEN NOT r.can_read;
            n_readable := n_readable + 1;
            BEGIN
                decoded := convert_from(lo_get(r.cand), 'UTF8');
            EXCEPTION WHEN others THEN
                decoded := NULL;
            END;
            CONTINUE WHEN decoded IS NULL;
            n_decode := n_decode + 1;
            IF decoded ~ '^[1-9][0-9]{4,9}$' THEN
                IF decoded::bigint BETWEEN 16384 AND 4294967295 THEN
                    n_oidshape := n_oidshape + 1;
                    CONTINUE;
                END IF;
            END IF;
            n_convert := n_convert + 1;
        END LOOP;

        RAISE NOTICE 'lobtext|before|%|%|rows|%',                 tgt.tbl, tgt.col, n_rows;
        RAISE NOTICE 'lobtext|before|%|%|nulls|%',                tgt.tbl, tgt.col, n_nulls;
        RAISE NOTICE 'lobtext|before|%|%|non_candidate|%',        tgt.tbl, tgt.col, n_noncand;
        RAISE NOTICE 'lobtext|before|%|%|digits_not_candidate|%', tgt.tbl, tgt.col, n_digits;
        RAISE NOTICE 'lobtext|before|%|%|candidates|%',           tgt.tbl, tgt.col, n_cand;
        RAISE NOTICE 'lobtext|before|%|%|lo_exists|%',            tgt.tbl, tgt.col, n_exists;
        RAISE NOTICE 'lobtext|before|%|%|readable|%',             tgt.tbl, tgt.col, n_readable;
        RAISE NOTICE 'lobtext|before|%|%|decodable|%',            tgt.tbl, tgt.col, n_decode;
        RAISE NOTICE 'lobtext|before|%|%|oid_shaped_content|%',   tgt.tbl, tgt.col, n_oidshape;
        RAISE NOTICE 'lobtext|before|%|%|convertible|%',          tgt.tbl, tgt.col, n_convert;
    END LOOP;
END
$before$;

-- 4. Backup table -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lob_text_backup_20261005 (
    table_name    text,
    column_name   text,
    row_key       text,
    original_text text NOT NULL,
    converted_md5 text NOT NULL,
    last_updated  timestamp,
    backed_up_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (table_name, column_name, row_key)
);

-- 5. Conversion ---------------------------------------------------------------
DO $convert$
DECLARE
    tgt           record;
    r             record;
    decoded       text;
    touched       int;
    c_converted   bigint;
    c_missing     bigint;
    c_unreadable  bigint;
    c_undecodable bigint;
    c_oidshaped   bigint;
    c_concurrent  bigint;
    t_converted   bigint := 0;
    t_missing     bigint := 0;
    t_unreadable  bigint := 0;
    t_undecodable bigint := 0;
    t_oidshaped   bigint := 0;
    t_concurrent  bigint := 0;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            -- table, key column, key type, value column, has a timestamp last_updated
            ('cached_data',               'cache_key', 'text',   'json_data',       true),
            ('cloudsitter_policies',      'id',        'bigint', 'schedule_json',   false),
            ('github_integration_config', 'id',        'bigint', 'encrypted_pat',   false),
            ('sonarqube_project',         'id',        'bigint', 'encrypted_token', false)
        ) AS t(tbl, keycol, keytype, col, has_last_updated)
    LOOP
        CONTINUE WHEN to_regclass(tgt.tbl) IS NULL;
        c_converted := 0; c_missing := 0; c_unreadable := 0;
        c_undecodable := 0; c_oidshaped := 0; c_concurrent := 0;

        FOR r IN EXECUTE format($q$
            WITH c AS (
                SELECT %1$I::text AS row_key,
                       %2$I AS original,
                       CASE WHEN %2$I ~ '^[1-9][0-9]{4,9}$'
                            THEN CASE WHEN %2$I::bigint BETWEEN 16384 AND 4294967295
                                      THEN %2$I::bigint::oid END END AS cand
                FROM %3$I)
            SELECT c.row_key,
                   c.original,
                   c.cand,
                   (m.oid IS NOT NULL) AS lo_exists,
                   CASE WHEN m.oid IS NULL THEN false
                        WHEN (SELECT x.rolsuper FROM pg_roles x WHERE x.rolname = current_user) THEN true
                        WHEN current_setting('lo_compat_privileges')::boolean THEN true
                        WHEN pg_has_role(m.lomowner, 'USAGE') THEN true
                        ELSE EXISTS (SELECT 1
                                     FROM aclexplode(COALESCE(m.lomacl, acldefault('L', m.lomowner))) a
                                     WHERE a.privilege_type = 'SELECT'
                                       AND CASE WHEN a.grantee = 0 THEN true
                                                ELSE pg_has_role(a.grantee, 'USAGE') END)
                   END AS can_read
            FROM c LEFT JOIN pg_largeobject_metadata m ON m.oid = c.cand
            WHERE c.cand IS NOT NULL
        $q$, tgt.keycol, tgt.col, tgt.tbl)
        LOOP
            IF NOT r.lo_exists THEN
                c_missing := c_missing + 1;
                CONTINUE;
            END IF;
            IF NOT r.can_read THEN
                c_unreadable := c_unreadable + 1;
                CONTINUE;
            END IF;
            BEGIN
                decoded := convert_from(lo_get(r.cand), 'UTF8');
            EXCEPTION WHEN others THEN
                decoded := NULL;
            END;
            IF decoded IS NULL THEN
                c_undecodable := c_undecodable + 1;
                CONTINUE;
            END IF;
            -- Nested IFs, so the ::bigint cast only ever runs after the regex matched.
            IF decoded ~ '^[1-9][0-9]{4,9}$' THEN
                IF decoded::bigint BETWEEN 16384 AND 4294967295 THEN
                    c_oidshaped := c_oidshaped + 1;
                    CONTINUE;
                END IF;
            END IF;

            IF tgt.has_last_updated THEN
                EXECUTE format($q$
                    INSERT INTO lob_text_backup_20261005
                        (table_name, column_name, row_key, original_text, converted_md5, last_updated)
                    SELECT %1$L, %2$L, $2, $3, md5($1), t.last_updated
                    FROM %3$I t WHERE t.%4$I = $2::%5$s
                    ON CONFLICT DO NOTHING
                $q$, tgt.tbl, tgt.col, tgt.tbl, tgt.keycol, tgt.keytype)
                USING decoded, r.row_key, r.original;
            ELSE
                INSERT INTO lob_text_backup_20261005
                    (table_name, column_name, row_key, original_text, converted_md5)
                VALUES (tgt.tbl, tgt.col, r.row_key, r.original, md5(decoded))
                ON CONFLICT DO NOTHING;
            END IF;

            -- Only the value column is named, so last_updated is preserved, and the
            -- original-value predicate makes a concurrently changed row a no-op.
            EXECUTE format('UPDATE %1$I SET %2$I = $1 WHERE %3$I = $2::%4$s AND %2$I = $3',
                           tgt.tbl, tgt.col, tgt.keycol, tgt.keytype)
            USING decoded, r.row_key, r.original;
            GET DIAGNOSTICS touched = ROW_COUNT;
            IF touched = 1 THEN
                c_converted := c_converted + 1;
            ELSE
                c_concurrent := c_concurrent + 1;
            END IF;
        END LOOP;

        RAISE NOTICE 'lobtext|run|%|%|converted|%',            tgt.tbl, tgt.col, c_converted;
        RAISE NOTICE 'lobtext|run|%|%|skipped_missing|%',      tgt.tbl, tgt.col, c_missing;
        RAISE NOTICE 'lobtext|run|%|%|skipped_unreadable|%',   tgt.tbl, tgt.col, c_unreadable;
        RAISE NOTICE 'lobtext|run|%|%|skipped_undecodable|%',  tgt.tbl, tgt.col, c_undecodable;
        RAISE NOTICE 'lobtext|run|%|%|skipped_oid_shaped|%',   tgt.tbl, tgt.col, c_oidshaped;
        RAISE NOTICE 'lobtext|run|%|%|concurrent_changed|%',   tgt.tbl, tgt.col, c_concurrent;

        t_converted   := t_converted + c_converted;
        t_missing     := t_missing + c_missing;
        t_unreadable  := t_unreadable + c_unreadable;
        t_undecodable := t_undecodable + c_undecodable;
        t_oidshaped   := t_oidshaped + c_oidshaped;
        t_concurrent  := t_concurrent + c_concurrent;
    END LOOP;

    -- Session-level settings survive the COMMIT below, so step 9 can report them.
    PERFORM set_config('lobtext.converted',           t_converted::text,   false);
    PERFORM set_config('lobtext.skipped_missing',     t_missing::text,     false);
    PERFORM set_config('lobtext.skipped_unreadable',  t_unreadable::text,  false);
    PERFORM set_config('lobtext.skipped_undecodable', t_undecodable::text, false);
    PERFORM set_config('lobtext.skipped_oid_shaped',  t_oidshaped::text,   false);
    PERFORM set_config('lobtext.concurrent_changed',  t_concurrent::text,  false);
END
$convert$;

-- 6. After-census -------------------------------------------------------------
DO $after$
DECLARE
    tgt        record;
    r          record;
    decoded    text;
    n_rows     bigint;
    n_nulls    bigint;
    n_noncand  bigint;
    n_digits   bigint;
    n_cand     bigint;
    n_exists   bigint;
    n_readable bigint;
    n_decode   bigint;
    n_oidshape bigint;
    n_convert  bigint;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            ('cached_data',               'json_data'),
            ('cloudsitter_policies',      'schedule_json'),
            ('github_integration_config', 'encrypted_pat'),
            ('sonarqube_project',         'encrypted_token')
        ) AS t(tbl, col)
    LOOP
        CONTINUE WHEN to_regclass(tgt.tbl) IS NULL;
        EXECUTE format($q$
            SELECT count(*),
                   count(*) FILTER (WHERE s.val IS NULL),
                   count(*) FILTER (WHERE s.val IS NOT NULL AND s.val !~ '^[0-9]+$'),
                   count(*) FILTER (WHERE s.val ~ '^[0-9]+$' AND s.cand IS NULL),
                   count(*) FILTER (WHERE s.cand IS NOT NULL)
            FROM (SELECT %1$I AS val,
                         CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint::oid END END AS cand
                  FROM %2$I) s
        $q$, tgt.col, tgt.tbl)
        INTO n_rows, n_nulls, n_noncand, n_digits, n_cand;

        n_exists := 0; n_readable := 0; n_decode := 0; n_oidshape := 0; n_convert := 0;
        FOR r IN EXECUTE format($q$
            SELECT s.cand,
                   (m.oid IS NOT NULL) AS lo_exists,
                   CASE WHEN m.oid IS NULL THEN false
                        WHEN (SELECT x.rolsuper FROM pg_roles x WHERE x.rolname = current_user) THEN true
                        WHEN current_setting('lo_compat_privileges')::boolean THEN true
                        WHEN pg_has_role(m.lomowner, 'USAGE') THEN true
                        ELSE EXISTS (SELECT 1
                                     FROM aclexplode(COALESCE(m.lomacl, acldefault('L', m.lomowner))) a
                                     WHERE a.privilege_type = 'SELECT'
                                       AND CASE WHEN a.grantee = 0 THEN true
                                                ELSE pg_has_role(a.grantee, 'USAGE') END)
                   END AS can_read
            FROM (SELECT CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint::oid END END AS cand
                  FROM %2$I) s
            LEFT JOIN pg_largeobject_metadata m ON m.oid = s.cand
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl)
        LOOP
            CONTINUE WHEN NOT r.lo_exists;
            n_exists := n_exists + 1;
            CONTINUE WHEN NOT r.can_read;
            n_readable := n_readable + 1;
            BEGIN
                decoded := convert_from(lo_get(r.cand), 'UTF8');
            EXCEPTION WHEN others THEN
                decoded := NULL;
            END;
            CONTINUE WHEN decoded IS NULL;
            n_decode := n_decode + 1;
            IF decoded ~ '^[1-9][0-9]{4,9}$' THEN
                IF decoded::bigint BETWEEN 16384 AND 4294967295 THEN
                    n_oidshape := n_oidshape + 1;
                    CONTINUE;
                END IF;
            END IF;
            n_convert := n_convert + 1;
        END LOOP;

        RAISE NOTICE 'lobtext|after|%|%|rows|%',                 tgt.tbl, tgt.col, n_rows;
        RAISE NOTICE 'lobtext|after|%|%|nulls|%',                tgt.tbl, tgt.col, n_nulls;
        RAISE NOTICE 'lobtext|after|%|%|non_candidate|%',        tgt.tbl, tgt.col, n_noncand;
        RAISE NOTICE 'lobtext|after|%|%|digits_not_candidate|%', tgt.tbl, tgt.col, n_digits;
        RAISE NOTICE 'lobtext|after|%|%|candidates|%',           tgt.tbl, tgt.col, n_cand;
        RAISE NOTICE 'lobtext|after|%|%|lo_exists|%',            tgt.tbl, tgt.col, n_exists;
        RAISE NOTICE 'lobtext|after|%|%|readable|%',             tgt.tbl, tgt.col, n_readable;
        RAISE NOTICE 'lobtext|after|%|%|decodable|%',            tgt.tbl, tgt.col, n_decode;
        RAISE NOTICE 'lobtext|after|%|%|oid_shaped_content|%',   tgt.tbl, tgt.col, n_oidshape;
        RAISE NOTICE 'lobtext|after|%|%|convertible|%',          tgt.tbl, tgt.col, n_convert;
    END LOOP;
END
$after$;

-- 7. Assertion ----------------------------------------------------------------
-- A second guarded pass: if anything is still convertible, something wrote a new
-- reference while this ran (a writer still in legacy mode), so roll everything back.
DO $assert$
DECLARE
    tgt       record;
    r         record;
    decoded   text;
    remaining bigint := 0;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            ('cached_data',               'json_data'),
            ('cloudsitter_policies',      'schedule_json'),
            ('github_integration_config', 'encrypted_pat'),
            ('sonarqube_project',         'encrypted_token')
        ) AS t(tbl, col)
    LOOP
        CONTINUE WHEN to_regclass(tgt.tbl) IS NULL;
        FOR r IN EXECUTE format($q$
            SELECT s.cand,
                   (m.oid IS NOT NULL) AS lo_exists,
                   CASE WHEN m.oid IS NULL THEN false
                        WHEN (SELECT x.rolsuper FROM pg_roles x WHERE x.rolname = current_user) THEN true
                        WHEN current_setting('lo_compat_privileges')::boolean THEN true
                        WHEN pg_has_role(m.lomowner, 'USAGE') THEN true
                        ELSE EXISTS (SELECT 1
                                     FROM aclexplode(COALESCE(m.lomacl, acldefault('L', m.lomowner))) a
                                     WHERE a.privilege_type = 'SELECT'
                                       AND CASE WHEN a.grantee = 0 THEN true
                                                ELSE pg_has_role(a.grantee, 'USAGE') END)
                   END AS can_read
            FROM (SELECT CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint::oid END END AS cand
                  FROM %2$I) s
            LEFT JOIN pg_largeobject_metadata m ON m.oid = s.cand
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl)
        LOOP
            CONTINUE WHEN NOT r.lo_exists;
            CONTINUE WHEN NOT r.can_read;
            BEGIN
                decoded := convert_from(lo_get(r.cand), 'UTF8');
            EXCEPTION WHEN others THEN
                decoded := NULL;
            END;
            CONTINUE WHEN decoded IS NULL;
            IF decoded ~ '^[1-9][0-9]{4,9}$' THEN
                IF decoded::bigint BETWEEN 16384 AND 4294967295 THEN
                    CONTINUE;
                END IF;
            END IF;
            remaining := remaining + 1;
            RAISE NOTICE 'lobtext: still convertible after the pass: %.% oid=%', tgt.tbl, tgt.col, r.cand;
        END LOOP;
    END LOOP;
    IF remaining > 0 THEN
        RAISE EXCEPTION 'lobtext: % convertible reference(s) remain; rolling back', remaining
            USING HINT = 'a writer is still in legacy mode: retire it, then re-run';
    END IF;
    RAISE NOTICE 'lobtext|after|all|all|convertible|0';
END
$assert$;

-- 8. Rehearsal hook (AC-28) ---------------------------------------------------
DO $hook$
BEGIN
    IF COALESCE(current_setting('lobtext.fail_after_convert', true), '') = 'on' THEN
        RAISE EXCEPTION 'lobtext: lobtext.fail_after_convert=on, rolling the rehearsal back'
            USING HINT = 'unset it (SET lobtext.fail_after_convert = '''') for the real run';
    END IF;
END
$hook$;

-- 9. Commit, then report ------------------------------------------------------
COMMIT;

DO $report$
BEGIN
    RAISE NOTICE 'lobtext|run|all|all|converted|%',           COALESCE(current_setting('lobtext.converted', true), '0');
    RAISE NOTICE 'lobtext|run|all|all|skipped_missing|%',     COALESCE(current_setting('lobtext.skipped_missing', true), '0');
    RAISE NOTICE 'lobtext|run|all|all|skipped_unreadable|%',  COALESCE(current_setting('lobtext.skipped_unreadable', true), '0');
    RAISE NOTICE 'lobtext|run|all|all|skipped_undecodable|%', COALESCE(current_setting('lobtext.skipped_undecodable', true), '0');
    RAISE NOTICE 'lobtext|run|all|all|skipped_oid_shaped|%',  COALESCE(current_setting('lobtext.skipped_oid_shaped', true), '0');
    RAISE NOTICE 'lobtext|run|all|all|concurrent_changed|%',  COALESCE(current_setting('lobtext.concurrent_changed', true), '0');
END
$report$;
