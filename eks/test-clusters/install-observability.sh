#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────
# Installs observability on BOTH test clusters:
#   1. Kubernetes Metrics Server  -> EKS managed add-on "metrics-server"
#      (required by the HPA in 15-hpa-load.yaml and by `kubectl top`)
#   2. amazon-cloudwatch-observability add-on (Container Insights with
#      enhanced observability) + its required IRSA role for the
#      cloudwatch-agent service account.
#
# Run only once both clusters report status ACTIVE.
# Idempotent: re-running skips anything already present.
# ─────────────────────────────────────────────────────────
set -euo pipefail

REGION="${REGION:-ap-south-1}"
CLUSTERS=("xamops-test-1" "xamops-test-2")
TAGS="Project=XamOps,Purpose=CloudK8sFeatureTesting,Owner=agent-provisioned,CreatedOn=2026-10-09,Ephemeral=true"
CW_POLICY="arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
XRAY_POLICY="arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"

log() { printf '\n==> %s\n' "$*"; }

for cluster in "${CLUSTERS[@]}"; do
  log "Cluster ${cluster} (${REGION})"

  status=$(aws eks describe-cluster --region "$REGION" --name "$cluster" \
    --query 'cluster.status' --output text 2>/dev/null || echo "MISSING")
  if [[ "$status" != "ACTIVE" ]]; then
    echo "    status=${status}; skipping (cluster must be ACTIVE)."
    continue
  fi

  # kubeconfig context
  aws eks update-kubeconfig --region "$REGION" --name "$cluster" >/dev/null

  # ---- 1. metrics-server add-on (no IAM needed) ----
  if aws eks describe-addon --region "$REGION" --cluster-name "$cluster" \
       --addon-name metrics-server >/dev/null 2>&1; then
    echo "    metrics-server add-on already present."
  else
    log "    installing metrics-server add-on"
    aws eks create-addon --region "$REGION" --cluster-name "$cluster" \
      --addon-name metrics-server \
      --resolve-conflicts OVERWRITE \
      --tags "Project=XamOps,Purpose=CloudK8sFeatureTesting,Owner=agent-provisioned,CreatedOn=2026-10-09,Ephemeral=true" >/dev/null
  fi

  # ---- 2. IRSA role for the cloudwatch-agent service account ----
  # The amazon-cloudwatch-observability add-on creates the service account
  # itself, so only the role is created here (--role-only).
  role_name="${cluster}-cw-observability"
  if aws iam get-role --role-name "$role_name" >/dev/null 2>&1; then
    echo "    IAM role ${role_name} already exists."
  else
    log "    creating IRSA role ${role_name}"
    eksctl create iamserviceaccount \
      --cluster "$cluster" --region "$REGION" \
      --namespace amazon-cloudwatch --name cloudwatch-agent \
      --role-name "$role_name" --role-only \
      --attach-policy-arn "$CW_POLICY" \
      --attach-policy-arn "$XRAY_POLICY" \
      --tags "$TAGS" \
      --approve
  fi
  role_arn=$(aws iam get-role --role-name "$role_name" --query 'Role.Arn' --output text)

  # ---- 3. amazon-cloudwatch-observability add-on ----
  if aws eks describe-addon --region "$REGION" --cluster-name "$cluster" \
       --addon-name amazon-cloudwatch-observability >/dev/null 2>&1; then
    echo "    amazon-cloudwatch-observability add-on already present."
  else
    log "    installing amazon-cloudwatch-observability add-on"
    aws eks create-addon --region "$REGION" --cluster-name "$cluster" \
      --addon-name amazon-cloudwatch-observability \
      --service-account-role-arn "$role_arn" \
      --resolve-conflicts OVERWRITE \
      --tags "Project=XamOps,Purpose=CloudK8sFeatureTesting,Owner=agent-provisioned,CreatedOn=2026-10-09,Ephemeral=true" >/dev/null
  fi
done

log "Waiting for add-ons to become ACTIVE"
for cluster in "${CLUSTERS[@]}"; do
  for addon in metrics-server amazon-cloudwatch-observability; do
    if aws eks describe-addon --region "$REGION" --cluster-name "$cluster" \
         --addon-name "$addon" >/dev/null 2>&1; then
      aws eks wait addon-active --region "$REGION" \
        --cluster-name "$cluster" --addon-name "$addon" 2>/dev/null \
        && echo "    ${cluster}/${addon}: ACTIVE" \
        || echo "    ${cluster}/${addon}: not ACTIVE yet, check manually"
    fi
  done
done

log "Done. Verify with:"
echo "  kubectl top nodes"
echo "  kubectl get pods -n amazon-cloudwatch"
echo "  kubectl get hpa -n team-b"
