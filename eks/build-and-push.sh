#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build-and-push.sh
# Builds Docker images and pushes them to AWS ECR.
# Run from the repo root: ./eks/build-and-push.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Configuration — edit these ────────────────────────────────────────────────
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
AWS_REGION="${AWS_REGION:-ap-south-1}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

ECR_BASE="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
BACKEND_REPO="xamops-service"
FRONTEND_REPO="xamops-frontend"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== XamOps EKS Build & Push ==="
echo "Account : ${AWS_ACCOUNT_ID}"
echo "Region  : ${AWS_REGION}"
echo "Tag     : ${IMAGE_TAG}"
echo "ECR Base: ${ECR_BASE}"
echo ""

# ── Step 1: Authenticate Docker to ECR ───────────────────────────────────────
echo "[1/5] Authenticating to ECR..."
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_BASE}"

# ── Step 2: Create ECR repos if they don't exist ─────────────────────────────
echo "[2/5] Ensuring ECR repositories exist..."
for REPO in "${BACKEND_REPO}" "${FRONTEND_REPO}"; do
  aws ecr describe-repositories --repository-names "${REPO}" --region "${AWS_REGION}" > /dev/null 2>&1 \
    || aws ecr create-repository --repository-name "${REPO}" --region "${AWS_REGION}" \
         --image-scanning-configuration scanOnPush=true \
         --encryption-configuration encryptionType=AES256 \
       && echo "  Created: ${REPO}"
done

# ── Step 3: Build backend ─────────────────────────────────────────────────────
echo "[3/5] Building backend (Maven + Docker)..."
cd "${REPO_ROOT}/xamops-service"
mvn clean package -DskipTests -q
cd "${REPO_ROOT}"
docker build \
  --platform linux/amd64 \
  -t "${ECR_BASE}/${BACKEND_REPO}:${IMAGE_TAG}" \
  -t "${ECR_BASE}/${BACKEND_REPO}:$(git rev-parse --short HEAD)" \
  -f xamops-service/Dockerfile \
  xamops-service/
echo "  Backend image built."

# ── Step 4: Build frontend ────────────────────────────────────────────────────
echo "[4/5] Building frontend (npm + Docker)..."
docker build \
  --platform linux/amd64 \
  -t "${ECR_BASE}/${FRONTEND_REPO}:${IMAGE_TAG}" \
  -t "${ECR_BASE}/${FRONTEND_REPO}:$(git rev-parse --short HEAD)" \
  -f frontend-app/Dockerfile \
  frontend-app/
echo "  Frontend image built."

# ── Step 5: Push both images ──────────────────────────────────────────────────
echo "[5/5] Pushing images to ECR..."
docker push "${ECR_BASE}/${BACKEND_REPO}:${IMAGE_TAG}"
docker push "${ECR_BASE}/${BACKEND_REPO}:$(git rev-parse --short HEAD)"
docker push "${ECR_BASE}/${FRONTEND_REPO}:${IMAGE_TAG}"
docker push "${ECR_BASE}/${FRONTEND_REPO}:$(git rev-parse --short HEAD)"

echo ""
echo "✓ Done! Images pushed:"
echo "  ${ECR_BASE}/${BACKEND_REPO}:${IMAGE_TAG}"
echo "  ${ECR_BASE}/${FRONTEND_REPO}:${IMAGE_TAG}"
echo ""
echo "Next: update image tags in manifests and run ./eks/deploy.sh"
