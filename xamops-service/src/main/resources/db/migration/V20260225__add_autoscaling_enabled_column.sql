-- =============================================
-- Add autoscaling_enabled column to kubernetes_cluster_config
-- Run on: LOCAL, UAT, LIVE (all environments)
-- =============================================

ALTER TABLE kubernetes_cluster_config
ADD COLUMN IF NOT EXISTS autoscaling_enabled BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN kubernetes_cluster_config.autoscaling_enabled IS 'Whether XamOps autoscaler is enabled for this cluster';
