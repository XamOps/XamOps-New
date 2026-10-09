-- Remove Azure AutoSpotting (VM Scale Set Spot automation).
--
-- Purpose
--   The feature and its code are gone. This deletes the Azure rows it wrote into the
--   AutoSpotting tables shared with AWS/GCP (spot_optimization_event, daily_spot_savings,
--   autospotting_settings), any persisted azure-autospotting cache rows, and drops its own
--   table vmss_spot_config (and the Hibernate sequence vmss_spot_config_id_seq).
--
-- Azure predicate
--   cloud_account.provider is a free-form VARCHAR. The application writes 'Azure'
--   (controller/AccountManagerController.java) and ClientSavingsService groups on 'Azure'.
--   upper(btrim(provider)) = 'AZURE' also covers legacy casing and stray whitespace.
--   AWS accounts are written as 'AWS' and GCP as 'GCP', so neither can match.
--
-- Guards
--   Every statement is guarded with to_regclass(...). Flyway can run before Hibernate
--   ddl-auto has created any table (fresh database), and the migration can be re-run, so
--   on a fresh or already-migrated database it is a no-op. vmss_spot_config is dropped
--   without CASCADE: if another table's foreign key or a view depends on it, the migration
--   raises an exception and the whole transaction rolls back.
--
-- IRREVERSIBLE
--   The deleted rows and the dropped table cannot be recovered by Flyway. Take the
--   pre-deploy runbook backups first (\copy of the Azure rows, cached_data
--   'azure-autospotting-%' rows, and pg_dump -t vmss_spot_config -t vmss_spot_config_id_seq).
--
-- Restore
--   Restore the rows from those backups. The vmss_spot_config table DDL is in
--   V20260303__add_vmss_spot_config_enabled_column.sql.

SET LOCAL lock_timeout = '30s';

DO $$
DECLARE
    dependents integer;
BEGIN
    -- 1. Azure rows in the AutoSpotting tables shared with AWS/GCP.
    IF to_regclass('cloud_account') IS NOT NULL THEN
        IF to_regclass('spot_optimization_event') IS NOT NULL THEN
            DELETE FROM spot_optimization_event x
             USING cloud_account ca
             WHERE x.account_id = ca.id
               AND upper(btrim(ca.provider)) = 'AZURE';
        END IF;

        IF to_regclass('daily_spot_savings') IS NOT NULL THEN
            DELETE FROM daily_spot_savings x
             USING cloud_account ca
             WHERE x.account_id = ca.id
               AND upper(btrim(ca.provider)) = 'AZURE';
        END IF;

        IF to_regclass('autospotting_settings') IS NOT NULL THEN
            DELETE FROM autospotting_settings x
             USING cloud_account ca
             WHERE x.account_id = ca.id
               AND upper(btrim(ca.provider)) = 'AZURE';
        END IF;
    END IF;

    -- 2. Persisted Azure AutoSpotting cache rows. These caches were Redis-only, so no rows
    --    are expected; this is a safety net.
    IF to_regclass('cached_data') IS NOT NULL THEN
        DELETE FROM cached_data
         WHERE cache_key LIKE 'azure-autospotting-vmss-%'
            OR cache_key LIKE 'azure-autospotting-instances-%';
    END IF;

    -- 3. The feature's own table. Refuse to drop it if anything else depends on it.
    IF to_regclass('vmss_spot_config') IS NOT NULL THEN
        SELECT
            (SELECT count(*)
               FROM pg_constraint
              WHERE contype = 'f'
                AND confrelid = 'vmss_spot_config'::regclass
                AND conrelid <> 'vmss_spot_config'::regclass)
          + (SELECT count(DISTINCT r.ev_class)
               FROM pg_depend d
               JOIN pg_rewrite r ON r.oid = d.objid
              WHERE d.classid = 'pg_rewrite'::regclass
                AND d.refclassid = 'pg_class'::regclass
                AND d.refobjid = 'vmss_spot_config'::regclass
                AND r.ev_class <> 'vmss_spot_config'::regclass)
          INTO dependents;

        IF dependents > 0 THEN
            RAISE EXCEPTION 'vmss_spot_config has % dependent foreign key(s)/view(s); refusing to drop it without CASCADE. Remove them first.', dependents;
        END IF;

        DROP TABLE vmss_spot_config;
    END IF;

    -- 4. Hibernate's standalone sequence. A BIGSERIAL-owned sequence goes with the table.
    DROP SEQUENCE IF EXISTS vmss_spot_config_id_seq;
END $$;
