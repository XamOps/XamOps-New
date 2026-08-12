-- Catalog fields we were dropping on every inventory asset.
--
-- Scoped to what the API actually populates, measured 2026-08-11 across both accounts:
-- private_ip_addresses (404 assets), tags (477), protected_by (111), public_ip_addresses (50).
--
-- Deliberately NOT added: the network_risk timestamp subfields the OpenAPI schema documents
-- (last_access_from_internet_time, IMDSv1/v2, last_communication_with_database_time). Every one
-- was empty on every asset, and internet_exposure_type — already present since V20260803_1 — is
-- the only network_risk field carrying values. Columns that are always null imply a data source
-- we do not have.
--
-- No raw_payload backfill here, unlike V20260804_2. That migration's jsonb cast was verified
-- against PostgreSQL 15 in Docker before shipping; the Docker daemon was unavailable when this
-- one was written, and an unverified jsonb cast can abort the migration on a single malformed
-- payload. These columns cost nothing to wait for: the next inventory sync populates them.
ALTER TABLE upwind_inventory_asset
    ADD COLUMN IF NOT EXISTS private_ip_addresses VARCHAR(512),
    ADD COLUMN IF NOT EXISTS public_ip_addresses  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS protected_by         VARCHAR(128),
    ADD COLUMN IF NOT EXISTS cloud_tags           VARCHAR(1024);

-- Internet-reachable assets are the ones worth finding fast, and the partial index keeps it
-- small: only 137 of ~6,100 assets carry an exposure type.
CREATE INDEX IF NOT EXISTS idx_upwind_asset_exposure
    ON upwind_inventory_asset (cloud_account_id, internet_exposure_type)
    WHERE internet_exposure_type IS NOT NULL;
