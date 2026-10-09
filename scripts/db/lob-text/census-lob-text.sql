-- ============================================================================
-- census-lob-text.sql -- read-only census of legacy large-object references
-- ============================================================================
--
-- Purpose
--   Count, per column, how many stored TEXT values are really PostgreSQL large
--   object (OID) references left behind by the August @Lob mapping, and how many
--   of those are still resolvable. Nothing is written. Run this before and after
--   every phase of docs/runbooks/lob-text-cutover.md.
--
-- Preconditions
--   * psql 10 or newer (this script uses \if). Verify: psql --version.
--   * A role that can SELECT the tables below. Verify:
--       SELECT has_table_privilege('cached_data', 'SELECT');
--     Columns the role cannot read are skipped with a NOTICE, not an error.
--   * Nothing else. The script needs no write privilege and no maintenance window.
--
-- Window / lock impact
--   None beyond ordinary read locks (ACCESS SHARE). The script runs inside
--   BEGIN READ ONLY, so it can never block a writer and can never write. It does
--   a sequential scan of each table plus one pg_largeobject_metadata lookup and
--   one lo_get per candidate, so on a large cached_data it is I/O bound: budget
--   minutes, not seconds, and prefer a replica or an off-peak window.
--
-- What changes
--   Nothing. No DDL, no DML, no large object is created, modified or removed.
--
-- Rollback
--   Not applicable: the transaction is READ ONLY and is committed with no writes.
--
-- Post-steps
--   Feed the numbers into the runbook. `convertible` is what Script N
--   (normalize-lob-text-columns.sql) would convert; `candidates` minus
--   `convertible` is what it would report as skipped and leave untouched.
--
-- Large objects
--   lo_unlink and vacuumlo are never used by any script in this directory. Do not
--   run vacuumlo while any TEXT column, or any lob_text_* backup table, still
--   holds an OID: vacuumlo only understands oid/lo typed columns, so it would see
--   these references as unreferenced and delete the objects they point at.
--
-- Usage
--   psql -X -v ON_ERROR_STOP=1 -f scripts/db/lob-text/census-lob-text.sql
--   psql -X -v ON_ERROR_STOP=1 -v since_oid=123456 \
--        -f scripts/db/lob-text/census-lob-text.sql
--   With -v since_oid=<W> the script also reports, per column, how many candidate
--   values are above the watermark W, that is references to objects created after
--   W was recorded (runbook step C3). Output goes to stderr as NOTICE lines:
--     lobtext|<phase>|<table>|<column>|<metric>|<value>
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN READ ONLY;

-- psql does not interpolate variables inside dollar quotes, so the watermark
-- reaches the DO block below through a transaction-local setting.
\if :{?since_oid}
SELECT set_config('lobtext.since_oid', :'since_oid', true);
\endif

DO $census$
DECLARE
    tgt        record;
    r          record;
    since_txt  text;
    since_oid  bigint := NULL;
    decoded    text;
    n_rows     bigint;
    n_nulls    bigint;
    n_noncand  bigint;
    n_digits   bigint;
    n_cand     bigint;
    n_exists   bigint := 0;
    n_readable bigint := 0;
    n_decode   bigint := 0;
    n_oidshape bigint := 0;
    n_convert  bigint := 0;
    n_above    bigint := 0;
BEGIN
    since_txt := current_setting('lobtext.since_oid', true);
    IF since_txt IS NOT NULL AND since_txt <> '' THEN
        IF since_txt !~ '^[0-9]+$' THEN
            RAISE EXCEPTION 'lobtext: since_oid must be digits only, got %', since_txt;
        END IF;
        since_oid := since_txt::bigint;
        RAISE NOTICE 'lobtext: watermark since_oid=%', since_oid;
    END IF;

    FOR tgt IN
        SELECT *
        FROM (VALUES
            -- in-scope columns (Script N converts these)
            ('cached_data',               'json_data'),
            ('cloudsitter_policies',      'schedule_json'),
            ('github_integration_config', 'encrypted_pat'),
            ('sonarqube_project',         'encrypted_token'),
            -- D17 report-only columns (no script converts these)
            ('script_versions',           'code'),
            ('jenkins_integration_config', 'encrypted_api_token'),
            ('devops_script',             'code'),
            ('devops_script',             'usage_instructions'),
            ('saved_customizations',      'generated_code'),
            ('dev_ops_script',            'code'),
            ('dev_ops_script',            'usage_instructions')
        ) AS t(tbl, col)
    LOOP
        IF to_regclass(tgt.tbl) IS NULL THEN
            RAISE NOTICE 'lobtext: table % is absent here, skipped', tgt.tbl;
            CONTINUE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns c
                       WHERE c.table_schema = 'public' AND c.table_name = tgt.tbl
                         AND c.column_name = tgt.col) THEN
            RAISE NOTICE 'lobtext: column %.% is absent here, skipped', tgt.tbl, tgt.col;
            CONTINUE;
        END IF;
        IF NOT has_table_privilege(tgt.tbl, 'SELECT') THEN
            RAISE NOTICE 'lobtext: no SELECT privilege on %, skipped', tgt.tbl;
            CONTINUE;
        END IF;

        -- Shape counts. The D12 candidate rule is always a CASE: the regex first,
        -- then the OID range, so the ::oid cast can never see a bad value.
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

        n_exists := 0; n_readable := 0; n_decode := 0;
        n_oidshape := 0; n_convert := 0; n_above := 0;

        FOR r IN EXECUTE format($q$
            SELECT s.cand,
                   s.cand_num,
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
                                        THEN %1$I::bigint::oid END END AS cand,
                         CASE WHEN %1$I ~ '^[1-9][0-9]{4,9}$'
                              THEN CASE WHEN %1$I::bigint BETWEEN 16384 AND 4294967295
                                        THEN %1$I::bigint END END AS cand_num
                  FROM %2$I) s
            LEFT JOIN pg_largeobject_metadata m ON m.oid = s.cand
            WHERE s.cand IS NOT NULL
        $q$, tgt.col, tgt.tbl)
        LOOP
            IF since_oid IS NOT NULL AND r.cand_num > since_oid THEN
                n_above := n_above + 1;
            END IF;
            IF NOT r.lo_exists THEN
                CONTINUE;
            END IF;
            n_exists := n_exists + 1;
            IF NOT r.can_read THEN
                CONTINUE;                       -- the §3.1 permission branch
            END IF;
            n_readable := n_readable + 1;
            -- A per-row sub-block: a read-only transaction allows the subtransaction
            -- that BEGIN … EXCEPTION needs, so an undecodable object cannot end the run.
            BEGIN
                decoded := convert_from(lo_get(r.cand), 'UTF8');
            EXCEPTION WHEN others THEN
                decoded := NULL;
            END;
            IF decoded IS NULL THEN
                CONTINUE;
            END IF;
            n_decode := n_decode + 1;
            IF decoded ~ '^[1-9][0-9]{4,9}$' THEN
                IF decoded::bigint BETWEEN 16384 AND 4294967295 THEN
                    n_oidshape := n_oidshape + 1;   -- D11: the content is itself OID shaped
                    CONTINUE;
                END IF;
            END IF;
            n_convert := n_convert + 1;
        END LOOP;

        RAISE NOTICE 'lobtext|census|%|%|rows|%',                 tgt.tbl, tgt.col, n_rows;
        RAISE NOTICE 'lobtext|census|%|%|nulls|%',                tgt.tbl, tgt.col, n_nulls;
        RAISE NOTICE 'lobtext|census|%|%|non_candidate|%',        tgt.tbl, tgt.col, n_noncand;
        RAISE NOTICE 'lobtext|census|%|%|digits_not_candidate|%', tgt.tbl, tgt.col, n_digits;
        RAISE NOTICE 'lobtext|census|%|%|candidates|%',           tgt.tbl, tgt.col, n_cand;
        RAISE NOTICE 'lobtext|census|%|%|lo_exists|%',            tgt.tbl, tgt.col, n_exists;
        RAISE NOTICE 'lobtext|census|%|%|readable|%',             tgt.tbl, tgt.col, n_readable;
        RAISE NOTICE 'lobtext|census|%|%|decodable|%',            tgt.tbl, tgt.col, n_decode;
        RAISE NOTICE 'lobtext|census|%|%|oid_shaped_content|%',   tgt.tbl, tgt.col, n_oidshape;
        RAISE NOTICE 'lobtext|census|%|%|convertible|%',          tgt.tbl, tgt.col, n_convert;
        IF since_oid IS NOT NULL THEN
            RAISE NOTICE 'lobtext|census|%|%|above_watermark|%',  tgt.tbl, tgt.col, n_above;
        END IF;
    END LOOP;
END
$census$;

COMMIT;
