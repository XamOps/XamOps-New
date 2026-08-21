-- ─────────────────────────────────────────────────────────────────────────────
-- Per-account agent API keys (F6)
--
-- Until now every agent in every customer environment authenticated with one
-- shared key (xamops.agent.api-key), which is baked into the public install
-- scripts and the frontend bundle. Because nothing bound that key to an account,
-- the account id in a request was self-asserted: any holder of the key could
-- push data as any cluster, or ask GET /api/v1/agent/config?accountId=<other>
-- for another tenant's ingestion credential.
--
-- Kubernetes clusters already had somewhere to keep their own key
-- (kubernetes_cluster_config.agent_api_key, added in V20260217). VM agents —
-- disk and database — have no cluster row, so there was nowhere to put one.
-- This adds that home on cloud_account, which is also the right scope for them:
-- a disk agent belongs to an account, not to a cluster.
--
-- Nothing is populated here. Keys are minted on demand, and the legacy shared
-- key keeps working until xamops.agent.legacy-global-key.enabled is set false,
-- so this migration cannot break a running fleet.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE cloud_account
    ADD COLUMN IF NOT EXISTS agent_api_key VARCHAR(64);

ALTER TABLE cloud_account
    ADD COLUMN IF NOT EXISTS agent_key_issued_at TIMESTAMP;

-- Authentication looks a key up directly, so this index is on the hot path for
-- every agent request. Unique because a key must resolve to exactly one account —
-- a duplicate would make the caller's identity ambiguous, which is the property
-- this whole change exists to establish.
CREATE UNIQUE INDEX IF NOT EXISTS ux_cloud_account_agent_api_key
    ON cloud_account (agent_api_key)
    WHERE agent_api_key IS NOT NULL;
