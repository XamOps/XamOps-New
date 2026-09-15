-- ============================================================================
-- Scope architecture blueprints to a tenant.
--
-- architecture_blueprints shipped in V20260604_2 with six columns and no
-- ownership at all, while ArchitectureDesignController served
-- `repository.findAll()` on GET /api/xamops/design/list and deleted by raw id on
-- DELETE /api/xamops/design/{id}.
--
-- Every profile — local, uat, prod, preprod — resolves to a single Hikari
-- DataSource (MultiTenantDataSourceConfig#prodDataSource logs "Single-Tenant
-- Mode"; createRoutingDataSource has no profile bean wired to it), so there was
-- no database boundary doing the isolation either. Any authenticated user could
-- list, read, overwrite and delete every other tenant's saved architecture.
--
-- client_id is the same row-level tenancy key cloud_account already uses.
-- created_by records the author for attribution; it is not an access boundary,
-- because blueprints are shared within a tenant by design.
-- ============================================================================

ALTER TABLE architecture_blueprints
    ADD COLUMN IF NOT EXISTS client_id  BIGINT,
    ADD COLUMN IF NOT EXISTS created_by BIGINT;

-- Deliberately nullable rather than NOT NULL: existing rows predate ownership
-- and cannot be attributed. Reads are scoped to `client_id = :clientId`, so an
-- unattributed row is invisible instead of visible to everyone. Fail closed.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_arch_blueprint_client'
    ) THEN
        ALTER TABLE architecture_blueprints
            ADD CONSTRAINT fk_arch_blueprint_client
            FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Primary read pattern: "every blueprint belonging to my tenant, newest first".
CREATE INDEX IF NOT EXISTS idx_arch_blueprint_client
    ON architecture_blueprints (client_id, updated_at DESC);

-- ----------------------------------------------------------------------------
-- Backfill, but only where it is unambiguous.
--
-- On a single-client install (which is what the current prod DataSource
-- implies) every existing blueprint must belong to that client, so attributing
-- them preserves the customer's saved work. With two or more clients there is
-- no way to tell who authored what, so those rows are deliberately left
-- unattributed and become invisible rather than being handed to the wrong
-- tenant.
--
-- If you land this on a multi-client database and need to recover blueprints,
-- attribute them explicitly, e.g.
--     UPDATE architecture_blueprints SET client_id = <id> WHERE id IN (...);
-- ----------------------------------------------------------------------------
UPDATE architecture_blueprints
SET client_id = (SELECT id FROM client LIMIT 1)
WHERE client_id IS NULL
  AND (SELECT COUNT(*) FROM client) = 1;

COMMENT ON COLUMN architecture_blueprints.client_id IS
    'Owning tenant. NULL means unattributed (pre-tenancy row) and is never served.';
COMMENT ON COLUMN architecture_blueprints.created_by IS
    'app_user.id of the author, for attribution only — not an access boundary.';
