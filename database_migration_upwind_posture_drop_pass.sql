-- Optional housekeeping after the posture sync stopped storing PASS findings.
--
-- Nothing here is required. The first clean posture run archives the leftover PASS rows on
-- its own: they are never re-synced, so mark-resolved sets status = 'RESOLVED', and every
-- query that surfaces findings already filters status = 'FAIL'. The only reason to run this
-- is to reclaim the space, which is not small — measured 2026-08-18 the live org held
-- 501,000+ PASS findings, the same checks repeated once per framework across 55 enabled
-- frameworks (9.09x duplication).
--
-- Run against the XamOps database with the service stopped or between sync runs.

-- 1. See what is there first.
SELECT status, count(*) AS rows, pg_size_pretty(sum(pg_column_size(raw_payload))) AS payload
FROM upwind_posture_finding
GROUP BY status
ORDER BY rows DESC;

-- 2. Reclaim the space. Safe: no query reads PASS rows any more, and the FAIL rows the
--    product does read are untouched. Batched so one statement cannot hold a long lock on a
--    half-million-row delete; repeat until it reports 0.
DELETE FROM upwind_posture_finding
WHERE id IN (
  SELECT id FROM upwind_posture_finding WHERE status = 'PASS' LIMIT 50000
);

-- 3. After the last batch, return the space to the OS and refresh the planner's stats.
--    VACUUM FULL takes an ACCESS EXCLUSIVE lock on the table — run it in a maintenance
--    window, or leave it out and let autovacuum reuse the pages in place.
-- VACUUM FULL ANALYZE upwind_posture_finding;
ANALYZE upwind_posture_finding;
