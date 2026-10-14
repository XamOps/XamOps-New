-- ============================================================================
-- XamOps trial audit — STRICTLY READ-ONLY. No UPDATE/DELETE/DDL anywhere.
-- Safe to run on production: plain SELECTs cannot trigger the app's lazy-expiry
-- write (that only happens via TrialService.effectiveStatus in the Java path).
--
-- Connect (prod creds live in Secrets Manager, NOT in the repo):
--   aws secretsmanager get-secret-value --region ap-south-1 \
--       --secret-id prod/xamops_db --query SecretString --output text
--   # -> JSON with: username, password, host, port, dbname
--
--   PGPASSWORD='<password>' psql -h <host> -p <port> -U <username> -d <dbname> \
--       -f .agents/tasks/trial-audit-readonly.sql
--
-- NOTE: the tenant table is `client` (SINGULAR). There is no `clients` table.
-- ============================================================================

\echo ''
\echo '=== 0. Where am I? Confirm before trusting anything below. ==============='
SELECT current_database()                AS database,
       inet_server_addr()                AS server_ip,
       current_user                      AS connected_as,
       now()                             AS db_now;

\echo ''
\echo '=== 1. Does the trial schema exist here at all? =========================='
-- If this returns 0 rows, this DB predates the trial feature and nothing below applies.
SELECT column_name, data_type
FROM   information_schema.columns
WHERE  table_schema = 'public'
  AND  table_name   = 'client'
  AND  column_name IN ('plan_type','trial_status','trial_start_at',
                       'trial_end_at','reminder5_sent_at','final_day_sent_at')
ORDER  BY column_name;

\echo ''
\echo '=== 2. Fleet summary — how tenants are distributed ======================='
SELECT COALESCE(plan_type,    '(null)') AS plan_type,
       COALESCE(trial_status, '(null)') AS trial_status,
       COUNT(*)                         AS tenants
FROM   client
GROUP  BY 1, 2
ORDER  BY tenants DESC;

\echo ''
\echo '=== 3. THE MAIN CHECK — who is blocked, and why ========================='
-- The CASE below replays com.xammer.cloud.service.auth.TrialService.effectiveStatus
-- in its exact evaluation order (TrialService.java:70-83). Order is the whole story:
--   :71  plan_type <> 'TRIAL'                  -> NONE     (never gated, wins over everything)
--   :74  trial_status = 'EXPIRED'              -> EXPIRED  (beats a future trial_end_at)
--   :77  now() > trial_end_at                  -> EXPIRED  (lazy; Java also PERSISTS this)
--   else                                       -> ACTIVE
SELECT c.id                                   AS client_id,
       c.name                                 AS tenant,
       u.email                                AS login_email,
       CASE WHEN u.id IS NULL THEN '(NO LOGIN - orphan)'
            ELSE COALESCE(u.auth_provider,'PASSWORD') END AS login_via,
       c.plan_type,
       c.trial_status,
       c.trial_end_at,
       CASE
         WHEN COALESCE(c.plan_type,'') <> 'TRIAL'
           THEN 'ALLOWED (not trial-gated)'
         WHEN c.trial_status = 'EXPIRED'
           THEN 'BLOCKED 402 (status=EXPIRED)'
         WHEN c.trial_end_at IS NOT NULL AND now() > c.trial_end_at
           THEN 'BLOCKED 402 (lapsed ' || EXTRACT(day FROM now() - c.trial_end_at)::int || 'd ago)'
         WHEN c.trial_end_at IS NULL
           THEN 'ALLOWED (trial, no end date)'
         ELSE 'ALLOWED (' || EXTRACT(day FROM c.trial_end_at - now())::int || 'd left)'
       END                                    AS enforcement,
       u.last_login_at
FROM   client c
LEFT   JOIN app_user u ON u.client_id = c.id
WHERE  c.plan_type = 'TRIAL' OR c.trial_status IS NOT NULL
ORDER  BY c.trial_end_at NULLS LAST, c.id;

\echo ''
\echo '=== 4. Blocked tenants that have a REAL login (these are the ones to fix) '
-- Tenants with no app_user row are orphan/test shells — nobody can log in, ignore them.
SELECT c.id        AS client_id,
       c.name      AS tenant,
       c.trial_end_at,
       COUNT(u.id) AS login_count,
       STRING_AGG(DISTINCT u.email, ', ') AS emails
FROM   client c
JOIN   app_user u ON u.client_id = c.id
WHERE  c.plan_type = 'TRIAL'
  AND  (c.trial_status = 'EXPIRED'
        OR (c.trial_end_at IS NOT NULL AND now() > c.trial_end_at))
GROUP  BY c.id, c.name, c.trial_end_at
ORDER  BY c.trial_end_at;

\echo ''
\echo '=== 5. Orphan trial tenants — expired but NO login attached (safe to ignore/purge)'
SELECT c.id AS client_id, c.name AS tenant, c.email, c.trial_start_at, c.trial_end_at
FROM   client c
WHERE  c.plan_type = 'TRIAL'
  AND  NOT EXISTS (SELECT 1 FROM app_user u WHERE u.client_id = c.id)
  AND  (c.trial_status = 'EXPIRED'
        OR (c.trial_end_at IS NOT NULL AND now() > c.trial_end_at))
ORDER  BY c.id;

\echo ''
\echo '=== 6. SSO auto-provisioned tenants (the "logged in without creating a user" case)'
SELECT u.id          AS user_id,
       u.email,
       u.auth_provider,
       u.role,
       c.id           AS client_id,
       c.name         AS tenant,
       c.plan_type,
       c.trial_status,
       c.trial_start_at,
       u.last_login_at,
       -- signup and first login within a second => tenant was auto-created at login
       (c.trial_start_at IS NOT NULL
        AND u.last_login_at IS NOT NULL
        AND abs(EXTRACT(epoch FROM u.last_login_at - c.trial_start_at)) < 1) AS auto_provisioned_at_login
FROM   app_user u
JOIN   client   c ON c.id = u.client_id
WHERE  u.auth_provider IN ('GOOGLE','MICROSOFT')
ORDER  BY u.id;

\echo ''
\echo '=== 7. Targeted lookup — EDIT THE EMAIL, then run just this block ======='
\echo '    (left as a literal so it is harmless until you change it)'
SELECT u.id      AS user_id,
       u.email,
       u.username,
       u.role,
       COALESCE(u.auth_provider,'PASSWORD') AS login_via,
       c.id      AS client_id,
       c.name    AS tenant,
       c.plan_type,
       c.trial_status,
       c.trial_start_at,
       c.trial_end_at,
       c.reminder5_sent_at,
       c.final_day_sent_at
FROM   app_user u
JOIN   client   c ON c.id = u.client_id
WHERE  lower(u.email)    = lower('CHANGE_ME@example.com')
   OR  lower(u.username) = lower('CHANGE_ME@example.com');
