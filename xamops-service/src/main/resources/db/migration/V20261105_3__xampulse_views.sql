-- XamPulse saved views (dashboards and share links are not part of 1.0).

CREATE TABLE IF NOT EXISTS xp_saved_view (
    id BIGSERIAL PRIMARY KEY,
    cloud_account_id BIGINT NOT NULL REFERENCES cloud_account(id),
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    shared BOOLEAN NOT NULL DEFAULT FALSE,
    state_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_xp_saved_view_account ON xp_saved_view (cloud_account_id, owner_user_id);
