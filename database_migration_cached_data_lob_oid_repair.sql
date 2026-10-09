-- ############################################################################
-- SUPERSEDED, do not run; see scripts/db/lob-text/normalize-lob-text-columns.sql
-- ############################################################################
--
-- This file is kept for the record only. Everything below is superseded by the
-- scripts in scripts/db/lob-text/ (census, Script N, Script D, rollback), which
-- are idempotent, back every converted row up and never cast an unguarded value.
-- Three specific pieces of advice below are WITHDRAWN:
--
--   1. The vacuumlo advice (line :52, "Reclaim them with vacuumlo only after
--      verifying") is wrong and destructive. vacuumlo only understands oid and lo
--      typed columns, so it does not see these TEXT columns, nor the backup
--      tables, as referencing anything. Running it while any TEXT column or any
--      backup table still holds an OID deletes the objects those references point
--      at, which destroys both the data and the ability to roll back. No script
--      in scripts/db/lob-text/ ever calls lo_unlink or vacuumlo.
--
--   2. The claim at line :45 that deploying first is safe "because
--      CostDataSnapshotService.resolvePayload reads both formats and converts
--      each row as it is touched" no longer holds. resolvePayload is now thin:
--      it returns the already-decoded value, decoding happens in the mapping
--      (LegacyLobTextType) and the opt-in convert-on-read path is gated behind
--      xammer.lob-text.convert-on-read.enabled plus inline write mode. Treat this
--      file's "cleanup, not a prerequisite" framing as obsolete and follow
--      docs/runbooks/lob-text-cutover.md instead.
--
--   3. The unguarded ::oid casts at lines :80, :89, :98 and :103 are unsafe. They
--      accept any value matching '^[0-9]{1,10}$', so a payload such as 0, 42 or
--      9999999999 reaches the cast; values above 4294967295 raise "OID out of
--      range" and abort the whole transaction, and a legitimately numeric payload
--      can be mistaken for a reference. The replacement scripts always use a CASE
--      that applies the regex '^[1-9][0-9]{4,9}$' first and then the range
--      16384..4294967295, so the cast can never see a bad value, and they never
--      put a bare cast in a WHERE clause.
--
-- Also note that this file covers only three columns and leaves
-- cloudsitter_policies.schedule_json, dev_ops_script and devops_script untouched.
--
-- ############################################################################

-- ============================================================================
-- Repair cached_data rows that hold a PostgreSQL large-object OID instead of JSON
-- ============================================================================
--
-- SYMPTOM
--   The Account Dashboard showed "Last Month $1,503,879.00" for an account whose
--   August 2026 spend was $1,953.07, and a Cost Trend chart peaking at $1.5M.
--   The Cost page was correct, because it queries Cost Explorer / CUR live for an
--   explicit date range instead of reading a snapshot.
--
-- CAUSE
--   CachedData.jsonData used to carry @Lob. On PostgreSQL that makes Hibernate
--   store the string as a large object and write only its OID into the TEXT
--   column. Every row written before the annotation was removed therefore holds a
--   bare integer -- the real JSON is still in pg_largeobject under that OID.
--
--   AWS_MONTHLY_TOTAL::<account>::<YYYY-MM> is read back as a Double. Jackson
--   parses "1503879" without complaint, so an OID was rendered as dollars. The
--   list-typed keys (breakdowns, daily cost, waste, rightsizing, security) fail to
--   deserialize instead and silently refetch, which is why only the spend figures
--   looked wrong.
--
-- WHAT THIS DOES
--   Decodes each affected row back out of pg_largeobject and writes the JSON into
--   the column, where the current mapping expects it. Content is unchanged -- this
--   only moves it. Rows already holding JSON are not touched.
--
-- ORDERING -- READ BEFORE RUNNING ON ANY ENVIRONMENT
--   Run this ONLY against an environment already running code WITHOUT @Lob on
--   CachedData.jsonData. A deployment still carrying @Lob reads this column as an
--   OID, so handing it JSON breaks every cached read instantly.
--
--   Check what an environment is running before you touch it:
--
--     SELECT count(*) FILTER (WHERE json_data ~ '^[0-9]{1,10}$') AS oid_rows,
--            count(*) FILTER (WHERE json_data !~ '^[0-9]{1,10}$') AS json_rows,
--            max(last_updated) FILTER (WHERE json_data ~ '^[0-9]{1,10}$') AS newest_oid
--     FROM cached_data;
--
--   json_rows = 0 and a recent newest_oid means the OLD code is still deployed and
--   actively writing OIDs -- DO NOT run this yet. Deploy first. As of 2026-09-16
--   that was prod: 62,207 rows, all OIDs, still being written.
--
--   Deploying first is safe in either direction, because
--   CostDataSnapshotService.resolvePayload reads both formats and converts each row
--   as it is touched. This migration is the bulk pass for rows nothing reads --
--   it is a cleanup, not a prerequisite.
--
-- SAFETY
--   * cached_data_lob_oid_backup keeps every original (key, oid) pair.
--   * The large objects themselves are left in place, so the OIDs stay resolvable
--     and step 5 can undo this. Reclaim them with vacuumlo only after verifying.
--   * Only rows whose payload is all digits AND resolves to an existing large
--     object are updated, so a legitimately numeric payload cannot be caught.
--   * Re-runnable: a repaired row no longer matches the WHERE clause.
--
-- AFTER RUNNING
--   Drop the derived caches, which were computed from the bad values:
--     redis-cli --scan --pattern 'costHistory-*'   | xargs -r redis-cli DEL
--     redis-cli --scan --pattern 'DASHBOARD_COST*' | xargs -r redis-cli DEL
--     redis-cli --scan --pattern 'lastMonthCost-*' | xargs -r redis-cli DEL
--   and delete the cost-history snapshots so they recompose from repaired data:
--     DELETE FROM cached_data WHERE cache_key LIKE 'FINOPS_COST_HISTORY::%';
-- ============================================================================

BEGIN;

-- 1. Back up what we are about to overwrite -------------------------------------
CREATE TABLE IF NOT EXISTS cached_data_lob_oid_backup (
    cache_key    VARCHAR(255) PRIMARY KEY,
    lob_oid      TEXT,
    last_updated TIMESTAMP,
    backed_up_at TIMESTAMP DEFAULT now()
);

INSERT INTO cached_data_lob_oid_backup (cache_key, lob_oid, last_updated)
SELECT c.cache_key, c.json_data, c.last_updated
FROM cached_data c
WHERE c.json_data ~ '^[0-9]{1,10}$'
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = c.json_data::oid)
ON CONFLICT (cache_key) DO NOTHING;

-- 2. Restore the JSON ----------------------------------------------------------
-- last_updated is deliberately preserved: it records when the cost data was
-- fetched from AWS, and the snapshot schedulers use it to decide what to refresh.
UPDATE cached_data c
SET json_data = convert_from(lo_get(c.json_data::oid), 'UTF8')
WHERE c.json_data ~ '^[0-9]{1,10}$'
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = c.json_data::oid);

-- 3. Same corruption, same fix, different tables -------------------------------
-- These columns were written through the same @Lob mapping. The GitHub PAT and
-- SonarQube token are unusable in their current state -- both integrations read
-- them expecting ciphertext and get an OID.
UPDATE github_integration_config
SET encrypted_pat = convert_from(lo_get(encrypted_pat::oid), 'UTF8')
WHERE encrypted_pat ~ '^[0-9]{1,10}$'
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = encrypted_pat::oid);

UPDATE sonarqube_project
SET encrypted_token = convert_from(lo_get(encrypted_token::oid), 'UTF8')
WHERE encrypted_token ~ '^[0-9]{1,10}$'
  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = encrypted_token::oid);

COMMIT;

-- 4. Verify --------------------------------------------------------------------
-- Expect remaining_oid_rows = 0.
--
--   SELECT count(*) FILTER (WHERE json_data ~ '^[0-9]{1,10}$'
--                             AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m
--                                         WHERE m.oid = json_data::oid)) AS remaining_oid_rows,
--          count(*) AS total
--   FROM cached_data;
--
-- Spot-check one account's monthly totals -- these are dollars, not OIDs:
--
--   SELECT cache_key, json_data
--   FROM cached_data
--   WHERE cache_key LIKE 'AWS_MONTHLY_TOTAL::%::2026-0%'
--   ORDER BY cache_key LIMIT 20;

-- 5. Rollback, if ever needed --------------------------------------------------
--   UPDATE cached_data c
--   SET json_data = b.lob_oid
--   FROM cached_data_lob_oid_backup b
--   WHERE b.cache_key = c.cache_key;
