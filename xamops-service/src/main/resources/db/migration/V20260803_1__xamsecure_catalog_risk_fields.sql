-- Upwind inventory CATALOG asset fields.
--
-- The sync previously called POST /v2/{org}/inventory/assets/search, which returns only
-- id/label/name/region/provider/account/resource-id. The documented endpoint is
-- /inventory/catalog/assets/search, whose ApiCatalogAsset also carries per-asset risk
-- rollups, high-privilege flags, sensitive-data classification and technology inventory
-- (with EOL/EOS dates). Those are the columns added here.

ALTER TABLE upwind_inventory_asset
    ADD COLUMN IF NOT EXISTS has_elevated_privilege     BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_pii_at_rest      BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_phi_at_rest      BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_pci_at_rest      BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_pii_in_transit   BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_phi_in_transit   BOOLEAN,
    ADD COLUMN IF NOT EXISTS sensitive_pci_in_transit   BOOLEAN,
    ADD COLUMN IF NOT EXISTS vuln_critical_count        INTEGER,
    ADD COLUMN IF NOT EXISTS vuln_high_count            INTEGER,
    ADD COLUMN IF NOT EXISTS vuln_medium_count          INTEGER,
    ADD COLUMN IF NOT EXISTS vuln_low_count             INTEGER,
    ADD COLUMN IF NOT EXISTS vuln_total_count           INTEGER,
    ADD COLUMN IF NOT EXISTS detection_critical_count   INTEGER,
    ADD COLUMN IF NOT EXISTS detection_high_count       INTEGER,
    ADD COLUMN IF NOT EXISTS detection_total_count      INTEGER,
    ADD COLUMN IF NOT EXISTS internet_exposure_type     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_internet_communication TIMESTAMP,
    ADD COLUMN IF NOT EXISTS technologies               VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS has_eol_technology         BOOLEAN;

-- The Identities view filters on elevated privilege across the client's accounts.
CREATE INDEX IF NOT EXISTS idx_upwind_asset_privilege
    ON upwind_inventory_asset (cloud_account_id, has_elevated_privilege);

-- API-security endpoints: sensitive-data findings and observed status codes, both of which
-- only the v2 /api-security/endpoints/search payload provides.
ALTER TABLE upwind_api_endpoint
    ADD COLUMN IF NOT EXISTS sensitive_data      VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS has_sensitive_data  BOOLEAN,
    ADD COLUMN IF NOT EXISTS status_codes        VARCHAR(256);
