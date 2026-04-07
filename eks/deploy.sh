#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy.sh
# Deploys (or updates) the full XamOps stack to EKS.
# Run from the repo root: ./eks/deploy.sh
#
# Prerequisites:
#   - kubectl configured for your EKS cluster
#     eksctl utils write-kubeconfig --cluster xamops-test --region ap-south-1
#   - AWS_ACCOUNT_ID env var set (or set below)
#   - Images pushed with build-and-push.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_REGION="${AWS_REGION:-ap-south-1}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
ECR_BASE="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

MANIFESTS_DIR="$(cd "$(dirname "$0")/manifests" && pwd)"

echo "=== XamOps EKS Deploy ==="
echo "Cluster : $(kubectl config current-context)"
echo "Account : ${AWS_ACCOUNT_ID}"
echo ""

# ── Patch image placeholders in manifests ─────────────────────────────────────
patch_image() {
  local FILE="$1"
  sed "s|<AWS_ACCOUNT_ID>|${AWS_ACCOUNT_ID}|g" "${FILE}"
}

# ── Step 1: Namespace + Secrets + ConfigMap ───────────────────────────────────
echo "[1/6] Applying namespace, secrets, configmap..."
kubectl apply -f "${MANIFESTS_DIR}/00-namespace.yaml"
kubectl apply -f "${MANIFESTS_DIR}/01-secrets.yaml"
kubectl apply -f "${MANIFESTS_DIR}/02-configmap.yaml"

# ── Step 2: Redis ─────────────────────────────────────────────────────────────
echo "[2/6] Deploying Redis..."
kubectl apply -f "${MANIFESTS_DIR}/03-redis.yaml"

# ── Step 3: OpenObserve ───────────────────────────────────────────────────────
echo "[3/6] Deploying OpenObserve..."
kubectl apply -f "${MANIFESTS_DIR}/04-openobserve.yaml"

# ── Step 4: Backend ───────────────────────────────────────────────────────────
echo "[4/6] Deploying backend (xamops-service)..."
patch_image "${MANIFESTS_DIR}/05-backend.yaml" | kubectl apply -f -
# Force pull latest image if tag is 'latest'
if [[ "${IMAGE_TAG}" == "latest" ]]; then
  kubectl rollout restart deployment/xamops-service -n xamops
fi

# ── Step 5: Frontend ──────────────────────────────────────────────────────────
echo "[5/6] Deploying frontend (xamops-frontend)..."
patch_image "${MANIFESTS_DIR}/06-frontend.yaml" | kubectl apply -f -
if [[ "${IMAGE_TAG}" == "latest" ]]; then
  kubectl rollout restart deployment/xamops-frontend -n xamops
fi

# ── Step 6: HPA ───────────────────────────────────────────────────────────────
echo "[6/6] Applying HPA..."
kubectl apply -f "${MANIFESTS_DIR}/07-hpa.yaml"

# ── Wait for rollouts ─────────────────────────────────────────────────────────
echo ""
echo "Waiting for rollouts to complete..."
kubectl rollout status deployment/redis          -n xamops --timeout=120s
kubectl rollout status deployment/openobserve    -n xamops --timeout=180s
kubectl rollout status deployment/xamops-service -n xamops --timeout=300s
kubectl rollout status deployment/xamops-frontend -n xamops --timeout=120s

# ── Print status ──────────────────────────────────────────────────────────────
echo ""
echo "=== Deployment status ==="
kubectl get pods -n xamops -o wide
echo ""
echo "=== Services ==="
kubectl get svc -n xamops
echo ""

# Get the NLB DNS for the frontend
FRONTEND_DNS=$(kubectl get svc xamops-frontend -n xamops \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending...")
echo "Frontend URL: http://${FRONTEND_DNS}"
echo ""
echo "✓ Deploy complete!"
echo "  Point your DNS (live.xamops.com) to: ${FRONTEND_DNS}"
