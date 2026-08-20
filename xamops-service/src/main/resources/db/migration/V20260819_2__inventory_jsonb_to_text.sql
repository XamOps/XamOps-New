-- ============================================================================
-- Converts aws_cloud_resource.tags/details from jsonb to text.
--
-- The columns were originally declared jsonb so tag documents would be
-- queryable in SQL. In practice Hibernate binds a Java String parameter as
-- varchar, and Postgres will not implicitly cast varchar to jsonb, so every
-- single insert failed:
--
--   ERROR: column "details" is of type jsonb but expression is of type
--          character varying
--
-- The failure was total but quiet from the outside: the reconciler logged a
-- SQLGrammarException per batch, the table stayed empty, isFresh() therefore
-- returned false, and every page silently kept using the old describe path. The
-- feature looked deployed and did nothing.
--
-- Text is the correct choice here because nothing reads inside these documents
-- in SQL — the projection layer parses them in Java. Restoring jsonb later
-- requires a Hibernate user type for the binding as well as the column change;
-- changing only the column reintroduces this bug.
--
-- Idempotent: the USING clause runs only if the column is still jsonb, and the
-- DROP INDEX tolerates the index never having existed.
-- ============================================================================

DROP INDEX IF EXISTS idx_acr_tags;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'aws_cloud_resource'
          AND column_name = 'tags'
          AND data_type = 'jsonb'
    ) THEN
        ALTER TABLE aws_cloud_resource
            ALTER COLUMN tags TYPE TEXT USING tags::text;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'aws_cloud_resource'
          AND column_name = 'details'
          AND data_type = 'jsonb'
    ) THEN
        ALTER TABLE aws_cloud_resource
            ALTER COLUMN details TYPE TEXT USING details::text;
    END IF;
END $$;

-- Defaults referenced ::jsonb literals, which are invalid once the column is text.
ALTER TABLE aws_cloud_resource ALTER COLUMN tags    SET DEFAULT '{}';
ALTER TABLE aws_cloud_resource ALTER COLUMN details SET DEFAULT '{}';
