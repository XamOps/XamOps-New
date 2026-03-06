-- Add 'enabled' column to vmss_spot_config table
-- This column tracks whether Azure AutoSpotting is enabled for a given VMSS (user intent).
-- It is the source of truth for enabled state across browser reloads.
-- Run on: LOCAL, UAT, LIVE (all environments)

-- Create the table if it doesn't exist (in case it was never migrated from ddl-auto=update)
CREATE TABLE IF NOT EXISTS vmss_spot_config (
    id                       BIGSERIAL PRIMARY KEY,
    account_id               BIGINT NOT NULL REFERENCES cloud_account(id),
    resource_group           VARCHAR(255) NOT NULL,
    vmss_name                VARCHAR(255) NOT NULL,
    region                   VARCHAR(255) NOT NULL DEFAULT '',
    allowed_vm_skus          TEXT,
    spot_allocation_strategy VARCHAR(255) DEFAULT 'mixed',
    spot_percentage          INTEGER,
    on_demand_count          INTEGER,
    enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT vmss_spot_config_unique UNIQUE (account_id, resource_group, vmss_name)
);

-- Add 'enabled' column if the table already exists but the column is missing
ALTER TABLE vmss_spot_config
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN vmss_spot_config.enabled IS
    'Whether Azure AutoSpotting is active for this VMSS. Source of truth for toggle state.';
