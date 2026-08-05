-- Kubernetes context on configuration findings.
--
-- resource.cluster_id and resource.namespace are already present in the v1
-- configuration-findings payload (verified live 2026-08-04 against a KUBERNETES_DEPLOYMENT
-- finding: cluster_id=4122e4ce-… namespace=pr-3193). They were simply never mapped, so
-- Kubernetes findings displayed an opaque composite resource id with no cluster or
-- namespace. No API migration is needed to populate these.

ALTER TABLE upwind_posture_finding
    ADD COLUMN IF NOT EXISTS cluster_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS namespace  VARCHAR(255);

-- Grouping findings by cluster/namespace is the point of having these columns.
CREATE INDEX IF NOT EXISTS idx_upwind_posture_cluster
    ON upwind_posture_finding (cloud_account_id, cluster_id);

-- The sync now also fetches PASS findings, so this table holds FAIL, PASS and RESOLVED
-- rows where it previously held only FAIL and RESOLVED. Every query that presents findings
-- as problems filters status = 'FAIL' (see UpwindPostureFindingRepository); this index
-- keeps those filtered reads cheap now that PASS roughly doubles the row count.
CREATE INDEX IF NOT EXISTS idx_upwind_posture_account_status
    ON upwind_posture_finding (cloud_account_id, status);
