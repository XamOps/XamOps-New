#!/usr/bin/env bash
# Build xamops-autoscaler and xamops-agent for linux/amd64 from the committed
# tree at HEAD (not the working copy) and push them to a tagged ECR repository.
#
#   ./build-images.sh            # build + push, prints the image refs and digests
#   BUILDER=docker ./build-images.sh   # force the shipped Dockerfiles (needs a daemon)
#
# The build context is produced by `git archive HEAD`, so uncommitted edits in
# the checkout can never leak into an image, and the tag carries the commit SHA.
#
# BUILDER=ko (default when no Docker daemon answers) builds with ko, which needs
# no daemon. It reproduces what the two Dockerfiles do: CGO_ENABLED=0,
# -ldflags "-w -s" (+ the agent's version stamp), base gcr.io/distroless/static:nonroot.
set -euo pipefail

REGION="${REGION:-ap-south-1}"
REPO_NAME="${REPO_NAME:-xamops-cloudk8s-harness}"
ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
SHA="$(git -C "$ROOT" rev-parse HEAD)"
SHORT="${SHA:0:12}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
REPO="${REGISTRY}/${REPO_NAME}"
KO="${KO:-$(command -v ko || echo "$HOME/go/bin/ko")}"

if [[ -z "${BUILDER:-}" ]]; then
  if docker info >/dev/null 2>&1; then BUILDER=docker; else BUILDER=ko; fi
fi
echo "commit   ${SHA}"
echo "builder  ${BUILDER}"

# The two module trees must equal HEAD; otherwise the image would not be "main HEAD".
if ! git -C "$ROOT" diff --quiet HEAD -- xamops-agent xamops-autoscaler; then
  echo "refusing to build: xamops-agent/ or xamops-autoscaler/ differs from HEAD" >&2
  exit 1
fi

CTX="$(mktemp -d "${TMPDIR:-/tmp}/xamops-harness-build.XXXXXX")"
trap 'rm -rf "$CTX"' EXIT
if [[ "$BUILDER" == "ko" ]]; then
  # Private docker config so ko's registry credentials never land in ~/.docker.
  # (Not done for BUILDER=docker: the buildx CLI plugin is resolved via ~/.docker.)
  export DOCKER_CONFIG="$CTX/.docker"
  mkdir -p "$DOCKER_CONFIG"
fi
git -C "$ROOT" archive --format=tar HEAD xamops-agent xamops-autoscaler | tar -x -C "$CTX"

if ! aws ecr describe-repositories --region "$REGION" --repository-names "$REPO_NAME" >/dev/null 2>&1; then
  aws ecr create-repository --region "$REGION" --repository-name "$REPO_NAME" \
    --image-tag-mutability MUTABLE \
    --image-scanning-configuration scanOnPush=false \
    --tags Key=Project,Value=XamOps Key=Purpose,Value=CloudK8sFeatureTesting \
           Key=Owner,Value=agent-provisioned Key=CreatedOn,Value=2026-10-09 \
           Key=Ephemeral,Value=true >/dev/null
  echo "created  ECR repository ${REPO}"
fi

if [[ "$BUILDER" == "docker" ]]; then
  aws ecr get-login-password --region "$REGION" |
    docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
  docker buildx build --platform linux/amd64 \
    --label "org.opencontainers.image.revision=${SHA}" \
    -t "${REPO}:autoscaler-${SHORT}" --push "$CTX/xamops-autoscaler"
  docker buildx build --platform linux/amd64 \
    --build-arg "AGENT_VERSION=harness-${SHORT}" \
    --label "org.opencontainers.image.revision=${SHA}" \
    -t "${REPO}:agent-${SHORT}" --push "$CTX/xamops-agent"
else
  aws ecr get-login-password --region "$REGION" |
    "$KO" login "$REGISTRY" --username AWS --password-stdin
  cat > "$CTX/ko-autoscaler.yaml" <<EOF
defaultBaseImage: gcr.io/distroless/static:nonroot
builds:
- id: autoscaler
  main: ./cmd/autoscaler
  env: [CGO_ENABLED=0]
  ldflags: ["-w -s"]
EOF
  cat > "$CTX/ko-agent.yaml" <<EOF
defaultBaseImage: gcr.io/distroless/static:nonroot
builds:
- id: agent
  main: ./cmd/agent
  env: [CGO_ENABLED=0]
  ldflags: ["-w -s -X github.com/xamops/agent/internal/version.Version=harness-${SHORT}"]
EOF
  (cd "$CTX/xamops-autoscaler" && KO_CONFIG_PATH="$CTX/ko-autoscaler.yaml" KO_DOCKER_REPO="$REPO" \
     "$KO" build ./cmd/autoscaler --bare --platform=linux/amd64 --sbom=none \
       --image-label "org.opencontainers.image.revision=${SHA}" --tags "autoscaler-${SHORT}")
  (cd "$CTX/xamops-agent" && KO_CONFIG_PATH="$CTX/ko-agent.yaml" KO_DOCKER_REPO="$REPO" \
     "$KO" build ./cmd/agent --bare --platform=linux/amd64 --sbom=none \
       --image-label "org.opencontainers.image.revision=${SHA}" --tags "agent-${SHORT}")
fi

for t in "autoscaler-${SHORT}" "agent-${SHORT}"; do
  aws ecr describe-images --region "$REGION" --repository-name "$REPO_NAME" \
    --image-ids imageTag="$t" \
    --query 'imageDetails[0].[imageTags[0],imageDigest,imageSizeInBytes,imagePushedAt]' --output text
done
