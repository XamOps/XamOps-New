-- kubernetes_cluster_config.kubeconfig_encrypted is NOT NULL on UAT, but no entity in this
-- repository maps it. KubernetesClusterConfig has no such field, so every insert leaves it
-- null and fails:
--
--   ERROR: null value in column "kubeconfig_encrypted" of relation "kubernetes_cluster_config"
--          violates not-null constraint
--
-- That breaks agent auto-registration on POST /api/v1/agent/heartbeat and /ingest
-- (AgentIngestService.findOrAutoRegisterClusterConfig), so no new cluster can register. The
-- column was not added by any commit in this repository; the UAT profile runs
-- ddl-auto=update and three services share xamops_db_uat, so it was most likely added by a
-- deployed build that is not in this history.
--
-- Relax the constraint and keep the column and its data. Guarded so it is a no-op on any
-- database without the column (local, fresh schemas, other environments), and safe to re-run.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'kubernetes_cluster_config'
          AND column_name = 'kubeconfig_encrypted'
          AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE kubernetes_cluster_config ALTER COLUMN kubeconfig_encrypted DROP NOT NULL;
    END IF;
END $$;
