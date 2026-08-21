-- =============================================================================
-- Local-profile repair: adds what Flyway would have created.
--
-- WHY THIS FILE EXISTS
--
-- application-local.properties sets spring.flyway.enabled=false and
-- spring.jpa.hibernate.ddl-auto=update, so on a local database the three tables
-- added by V20260821_1 and V20260821_2 are created by Hibernate from the entity
-- mappings instead of by the migrations. Hibernate produces the columns and the
-- primary keys and nothing else — no unique constraints, no foreign keys, no
-- secondary indexes.
--
-- That matters for two reasons beyond speed:
--
--   * uq_cloud_security_finding is the database-level guarantee behind ingest
--     idempotency. SQS is at-least-once, GuardDuty republishes repeat
--     occurrences, and Security Hub re-imports on every state change. The Java
--     path checks before inserting, so a single poller is fine, but without the
--     constraint two pollers racing on the same redelivery both see "absent"
--     and both insert.
--
--   * the foreign keys carry ON DELETE CASCADE, which is what stops a
--     disconnected account leaving findings behind. Without them the orphan
--     sweep in the repository is the only cleanup.
--
-- Production is unaffected: application.properties uses ddl-auto=none and does
-- not disable Flyway, so there the migrations run and this file is unnecessary.
--
-- SAFE TO RE-RUN. Every statement is guarded, so applying it twice is a no-op.
-- It only adds; it drops nothing and rewrites no data.
--
-- Usage:
--   psql -h <host> -U <user> -d <db> -f 2026-08-21-security-finding-indexes.sql
-- =============================================================================

BEGIN;

-- ── cloud_security_finding ───────────────────────────────────────────────────

-- Idempotency guarantee. Added before the indexes because it is the one that
-- changes behaviour rather than performance.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_cloud_security_finding'
          AND conrelid = 'cloud_security_finding'::regclass
    ) THEN
        ALTER TABLE cloud_security_finding
            ADD CONSTRAINT uq_cloud_security_finding UNIQUE (cloud_account_id, finding_id);
        RAISE NOTICE 'added uq_cloud_security_finding';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_csf_cloud_account'
          AND conrelid = 'cloud_security_finding'::regclass
    ) THEN
        ALTER TABLE cloud_security_finding
            ADD CONSTRAINT fk_csf_cloud_account
            FOREIGN KEY (cloud_account_id) REFERENCES cloud_account(id) ON DELETE CASCADE;
        RAISE NOTICE 'added fk_csf_cloud_account';
    END IF;
END $$;

-- The hot read: active findings for one connection, most severe then newest.
CREATE INDEX IF NOT EXISTS idx_csf_account_status
    ON cloud_security_finding (cloud_account_id, status, last_seen_at DESC);

-- Severity filtering, the counts-by-severity strip, and the CRITICAL/HIGH slice
-- the Alerts page reads.
CREATE INDEX IF NOT EXISTS idx_csf_account_severity
    ON cloud_security_finding (cloud_account_id, status, severity);

-- "New since" feed and the notifier's unnotified sweep.
CREATE INDEX IF NOT EXISTS idx_csf_account_first_seen
    ON cloud_security_finding (cloud_account_id, first_seen_at DESC);

-- Resource drill-down: "what is wrong with this bucket".
CREATE INDEX IF NOT EXISTS idx_csf_resource
    ON cloud_security_finding (cloud_account_id, resource_arn);

-- ── security_notification ────────────────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_sn_client'
          AND conrelid = 'security_notification'::regclass
    ) THEN
        ALTER TABLE security_notification
            ADD CONSTRAINT fk_sn_client
            FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE CASCADE;
        RAISE NOTICE 'added fk_sn_client';
    END IF;
END $$;

-- SET NULL rather than CASCADE, matching the migration: a notification stays
-- worth reading after the connection it referred to has been removed, and
-- erasing it would erase the record that the account had critical findings.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_sn_cloud_account'
          AND conrelid = 'security_notification'::regclass
    ) THEN
        ALTER TABLE security_notification
            ADD CONSTRAINT fk_sn_cloud_account
            FOREIGN KEY (cloud_account_id) REFERENCES cloud_account(id) ON DELETE SET NULL;
        RAISE NOTICE 'added fk_sn_cloud_account';
    END IF;
END $$;

-- The bell: unread count and the dropdown, both per client.
CREATE INDEX IF NOT EXISTS idx_sn_client_unread
    ON security_notification (client_id, is_read, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sn_client_created
    ON security_notification (client_id, created_at DESC);

-- ── security_notification_preference ─────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_snp_client'
          AND conrelid = 'security_notification_preference'::regclass
    ) THEN
        ALTER TABLE security_notification_preference
            ADD CONSTRAINT fk_snp_client
            FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE CASCADE;
        RAISE NOTICE 'added fk_snp_client';
    END IF;
END $$;

COMMIT;
