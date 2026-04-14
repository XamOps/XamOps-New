-- Allow multiple API keys per provider per client (remove unique constraint on client_id + provider)
DO $$
DECLARE
    v_constraint_name text;
BEGIN
    SELECT tc.constraint_name INTO v_constraint_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu1
         ON tc.constraint_name = kcu1.constraint_name
        AND kcu1.column_name = 'client_id'
    JOIN information_schema.key_column_usage kcu2
         ON tc.constraint_name = kcu2.constraint_name
        AND kcu2.column_name = 'provider'
    WHERE tc.table_name = 'llm_provider_config'
      AND tc.constraint_type = 'UNIQUE'
    LIMIT 1;

    IF v_constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE llm_provider_config DROP CONSTRAINT ' || quote_ident(v_constraint_name);
    END IF;
END $$;
