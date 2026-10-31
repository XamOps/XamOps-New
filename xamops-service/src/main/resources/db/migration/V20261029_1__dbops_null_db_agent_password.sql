-- SEC-5: the DB agent reads its DSN (password included) from its own environment; the server
-- never uses db_agent_config.db_password. It was stored in plaintext and returned by the old
-- GET /db/config. Null existing values. No-op where the column is already absent; safe to re-run.
--
-- The column itself is kept (nullable) rather than dropped: reversible, and no entity remapping.
-- Going forward DbAgentService.saveConnectionConfig no longer writes it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name  = 'db_agent_config'
                 AND column_name = 'db_password') THEN
        UPDATE db_agent_config SET db_password = NULL WHERE db_password IS NOT NULL;
    END IF;
END $$;
