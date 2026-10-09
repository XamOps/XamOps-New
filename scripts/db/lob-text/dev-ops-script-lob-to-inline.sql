-- ============================================================================
-- dev-ops-script-lob-to-inline.sql (Script D) -- DevOps script bodies -> inline
-- ============================================================================
--
-- Purpose
--   Replace every PostgreSQL large-object (OID) reference in the DevOps script
--   library with the text the object holds. Scope:
--     dev_ops_script.code, dev_ops_script.usage_instructions
--     devops_script.code,  devops_script.usage_instructions
--   dev_ops_script is the table name the entity used before it was renamed; both
--   names are handled, and a name that does not exist here is skipped.
--
-- Why there is no August precondition (F12)
--   These columns were taken off @Lob earlier and separately from the four
--   in-scope columns: 9224765d9 ("remove @Lob"), a1ba0dce7, f9d0f4fbd, 8a618e7eb
--   and b80f62ecc. Both the current entity and every current query read these
--   columns as plain TEXT, so no deployed build can create a new reference here,
--   and none of them can resolve an old one either -- a script body that is an OID
--   reference is simply broken today. Converting can therefore only fix reads, so
--   this script does not need the "August build retired and every writer inline"
--   precondition that normalize-lob-text-columns.sql has. It is safe to run
--   before, after or independently of Script N.
--
-- Preconditions
--   * Any psql (no \if, no client-side variable is used). Verify: psql --version.
--   * Confirmation, in the same session, before this file:
--       SET lobtext.confirm = 'convert-dev-ops-script';
--     Verify: SELECT current_setting('lobtext.confirm', true);
--   * At least one of the two tables exists, its columns are text, and its primary
--     key column is id (step 2 checks all three and aborts otherwise).
--   * No user trigger on the tables in scope (step 2 checks this and aborts).
--
-- Window / lock impact
--   Small. The library is a few hundred rows, and the expected volume is 40
--   references per column (step 2 prints that expectation next to the measured
--   count). Converted rows hold a ROW EXCLUSIVE lock until COMMIT, so an editor
--   saving the same script would block briefly; reads are never blocked.
--   SET LOCAL lock_timeout = '5s' aborts the run instead of queueing.
--
-- What changes
--   * CREATE TABLE IF NOT EXISTS lob_text_backup_20261005 (shared with Script N).
--   * One UPDATE per convertible row, setting only the value column. Nothing else
--     on the row is named, so devops_script.last_updated (a text column, written
--     by the application) is neither read nor written here.
--   * Rows whose object is missing, unreadable, undecodable or itself OID shaped
--     are counted, reported and left exactly as they are.
--   * No large object is created or removed.
--
-- Rollback
--   Before COMMIT: any error, including the step 8 rehearsal hook, rolls the whole
--   run back by itself.
--   After COMMIT: psql -X -v ON_ERROR_STOP=1 \
--       -c "SET lobtext.confirm = 'rollback-lob-text'" \
--       -f scripts/db/lob-text/rollback-lob-text.sql
--   The rollback script covers this script's backup rows as well as Script N's.
--
-- Post-steps
--   1. Re-run scripts/db/lob-text/census-lob-text.sql: the D17 report-only lines
--      for these columns must show convertible 0.
--   2. Spot-check one converted script body in the UI, then keep
--      lob_text_backup_20261005 until the soak window has passed.
--
-- Large objects
--   lo_unlink and vacuumlo are never used by any script in this directory. Do not
--   run vacuumlo while any TEXT column, or any lob_text_* backup table, still
--   holds an OID: vacuumlo only understands oid/lo typed columns, so it would see
--   these references as unreferenced and delete the objects they point at.
--
-- Usage
--   psql -X -v ON_ERROR_STOP=1 \
--        -c "SET lobtext.confirm = 'convert-dev-ops-script'" \
--        -f scripts/db/lob-text/dev-ops-script-lob-to-inline.sql
--   Rehearsal (converts, reports, then rolls everything back):
--   psql -X -v ON_ERROR_STOP=1 \
--        -c "SET lobtext.confirm = 'convert-dev-ops-script'" \
--        -c "SET lobtext.fail_after_convert = 'on'" \
--        -f scripts/db/lob-text/dev-ops-script-lob-to-inline.sql
--   Output goes to stderr as NOTICE lines:
--     lobtext|<phase>|<table>|<column>|<metric>|<value>
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- 1. Confirmation and lock policy --------------------------------------------
DO $confirm$
BEGIN
    IF COALESCE(current_setting('lobtext.confirm', true), '') <> 'convert-dev-ops-script' THEN
        RAISE EXCEPTION 'lobtext: refusing to run dev-ops-script-lob-to-inline.sql'
            USING HINT = 'first run: SET lobtext.confirm = ''convert-dev-ops-script''';
    END IF;
END
$confirm$;

SET LOCAL lock_timeout = '5s';

-- 2. Pre-checks ---------------------------------------------------------------
DO $precheck$
DECLARE
    tgt      record;
    datatype text;
    pkcol    text;
    trg      text;
    present  int := 0;
    measured bigint;
BEGIN
    FOR tgt IN
        SELECT *
        FROM (VALUES
            ('dev_ops_script', 'code'),
            ('dev_ops_script', 'usage_instructions'),
            ('devops_script',  'code'),
            ('devops_script',  'usage_instructions')
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

        SELECT a.attname INTO pkcol
        FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
        WHERE i.indrelid = to_regclass(tgt.tbl) AND i.indisprimary;
        IF pkcol IS DISTINCT FROM 'id' THEN
            RAISE EXCEPTION 'lobtext: primary key of % is %, expected a single column id',
                tgt.tbl, COALESCE(pkcol, '<none>');
        END IF;

        SELECT string_agg(g.tgname, ', ') INTO trg
        FROM pg_trigger g
        WHERE g.tgrelid = to_regclass(tgt.tbl) AND NOT g.tgisinternal;
        IF trg IS NOT NULL THEN
            RAISE EXCEPTION 'lobtext: user trigger(s) on %: % -- drop them or convert manually', tgt.tbl, trg;
        END IF;

        EXECUTE format($q$
            SELECT count(*) FROM (
                SELECT CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                            THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                      THEN %1$I::bigint::oid END END AS cand
                FROM %2$I) s
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl) INTO measured;
        RAISE NOTICE 'lobtext: %.% expected 40 large-object references (F12), measured %',
            tgt.tbl, tgt.col, measured;
        present := present + 1;
    END LOOP;
    IF present = 0 THEN
        RAISE EXCEPTION 'lobtext: neither dev_ops_script nor devops_script exists in this database';
    END IF;
    RAISE NOTICE 'lobtext: pre-checks passed for % of 4 columns', present;
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
            ('dev_ops_script', 'code'),
            ('dev_ops_script', 'usage_instructions'),
            ('devops_script',  'code'),
            ('devops_script',  'usage_instructions')
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

-- 4. Backup table (shared with Script N) --------------------------------------
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
            -- table, key column, key type, value column
            ('dev_ops_script', 'id', 'bigint', 'code'),
            ('dev_ops_script', 'id', 'bigint', 'usage_instructions'),
            ('devops_script',  'id', 'bigint', 'code'),
            ('devops_script',  'id', 'bigint', 'usage_instructions')
        ) AS t(tbl, keycol, keytype, col)
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

            INSERT INTO lob_text_backup_20261005
                (table_name, column_name, row_key, original_text, converted_md5)
            VALUES (tgt.tbl, tgt.col, r.row_key, r.original, md5(decoded))
            ON CONFLICT DO NOTHING;

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
            ('dev_ops_script', 'code'),
            ('dev_ops_script', 'usage_instructions'),
            ('devops_script',  'code'),
            ('devops_script',  'usage_instructions')
        ) AS t(tbl, col)
    LOOP
        CONTINUE WHEN to_regclass(tgt.tbl) IS NULL;
        n_exists := 0; n_readable := 0; n_decode := 0; n_oidshape := 0; n_convert := 0;
        EXECUTE format($q$
            SELECT count(*) FROM (
                SELECT CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                            THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                      THEN %1$I::bigint::oid END END AS cand
                FROM %2$I) s
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl) INTO n_cand;

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

        RAISE NOTICE 'lobtext|after|%|%|candidates|%',         tgt.tbl, tgt.col, n_cand;
        RAISE NOTICE 'lobtext|after|%|%|lo_exists|%',          tgt.tbl, tgt.col, n_exists;
        RAISE NOTICE 'lobtext|after|%|%|readable|%',           tgt.tbl, tgt.col, n_readable;
        RAISE NOTICE 'lobtext|after|%|%|decodable|%',          tgt.tbl, tgt.col, n_decode;
        RAISE NOTICE 'lobtext|after|%|%|oid_shaped_content|%', tgt.tbl, tgt.col, n_oidshape;
        RAISE NOTICE 'lobtext|after|%|%|convertible|%',        tgt.tbl, tgt.col, n_convert;
    END LOOP;
END
$after$;

-- 7. Assertion ----------------------------------------------------------------
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
            ('dev_ops_script', 'code'),
            ('dev_ops_script', 'usage_instructions'),
            ('devops_script',  'code'),
            ('devops_script',  'usage_instructions')
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
            USING HINT = 'something is writing new references to these columns: find it, then re-run';
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
