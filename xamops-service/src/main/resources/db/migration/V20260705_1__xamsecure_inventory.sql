CREATE TABLE upwind_inventory_asset (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    asset_category VARCHAR(64),
    asset_sub_category VARCHAR(64),
    label VARCHAR(128),
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    cloud_resource_id VARCHAR(512),
    name VARCHAR(256),
    region VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_uia_account ON upwind_inventory_asset (cloud_account_id);
CREATE INDEX idx_uia_category ON upwind_inventory_asset (asset_category);
