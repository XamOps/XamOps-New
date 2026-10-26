#!/usr/bin/env bash
#
# Build and (optionally) push the XamOps CloudShell image.
#
# The image this produces is what cloudshell.image should point at. Build multi-arch so
# the same tag runs on amd64 and arm64 backend hosts.
#
# Usage:
#   ./build-and-push.sh                              # local single-arch build, no push
#   REGISTRY=myrepo IMAGE=xamops-shell TAG=2026-10-15 ./build-and-push.sh --push
#
# Env:
#   REGISTRY  registry/namespace, e.g. "123456789012.dkr.ecr.us-east-1.amazonaws.com"
#             or a Docker Hub user. Empty = local image only (cannot --push).
#   IMAGE     image name (default: xamops-shell)
#   TAG       image tag (default: today's date, e.g. 2026-10-15)
#   PLATFORMS build platforms (default: linux/amd64,linux/arm64)
#
# Flags:
#   --push    push to the registry (requires REGISTRY and `docker login`). Multi-arch
#             builds must be pushed; they cannot be loaded into the local daemon.
set -euo pipefail

cd "$(dirname "$0")"

REGISTRY="${REGISTRY:-}"
IMAGE="${IMAGE:-xamops-shell}"
TAG="${TAG:-$(date +%Y-%m-%d)}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

if [[ -n "${REGISTRY}" ]]; then
  REF="${REGISTRY%/}/${IMAGE}:${TAG}"
else
  REF="${IMAGE}:${TAG}"
fi

PUSH=false
for arg in "$@"; do
  [[ "${arg}" == "--push" ]] && PUSH=true
done

echo "Image reference : ${REF}"
echo "Platforms       : ${PLATFORMS}"
echo "Push            : ${PUSH}"
echo

if [[ "${PUSH}" == "true" ]]; then
  if [[ -z "${REGISTRY}" ]]; then
    echo "ERROR: --push requires REGISTRY to be set." >&2
    exit 1
  fi
  # Multi-arch build straight to the registry. Prints the pushed digest at the end.
  docker buildx build \
    --platform "${PLATFORMS}" \
    --tag "${REF}" \
    --push \
    .
  echo
  echo "Pushed ${REF}. Pin the digest in cloudshell.image for production:"
  docker buildx imagetools inspect "${REF}" --format '{{json .Manifest}}' 2>/dev/null \
    | jq -r '.digest // empty' 2>/dev/null | sed "s#^#  ${REGISTRY%/}/${IMAGE}@#" || true
else
  # Local build for the current host arch only, loaded into the daemon so you can
  # `docker run` it immediately. Use --push for a real multi-arch publish.
  docker build --tag "${REF}" .
  echo
  echo "Built ${REF} locally. Smoke test:"
  echo "  docker run --rm ${REF} bash -lc 'aws --version && session-manager-plugin --version && kubectl version --client && helm version && gcloud --version | head -1 && az version'"
fi
