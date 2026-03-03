-- Legacy columns exist in prod with NOT NULL constraints but are no longer
-- mapped in the KubernetesCluster JPA entity. New INSERT statements
-- (triggered by enabling monitoring on a first-time cluster) fail because
-- Hibernate does not include these columns, violating the NOT NULL constraint.

-- Fix 1: Make cluster_arn nullable (it's a string ARN, NULL is safe)
ALTER TABLE kubernetes_cluster ALTER COLUMN cluster_arn DROP NOT NULL;

-- Fix 2: Give otel_connected a default so INSERTs without it succeed
ALTER TABLE kubernetes_cluster ALTER COLUMN otel_connected SET DEFAULT false;
ALTER TABLE kubernetes_cluster ALTER COLUMN otel_connected DROP NOT NULL;
