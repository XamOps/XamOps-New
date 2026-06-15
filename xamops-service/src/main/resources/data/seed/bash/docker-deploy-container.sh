#!/usr/bin/env bash
#
# docker-deploy-container.sh
# Pulls an image and (re)deploys a single container with a restart policy and
# port mapping, replacing any existing container of the same name.
#
# Parameters:
#   {{IMAGE}}           - Image reference (e.g. nginx:1.25 or myorg/api:2.3.1)
#   {{CONTAINER_NAME}}  - Name for the running container (e.g. api)
#   {{HOST_PORT}}       - Host port to publish (e.g. 8080)
#   {{CONTAINER_PORT}}  - Container port to map to (e.g. 80)
#
set -euo pipefail

IMAGE="{{IMAGE}}"
CONTAINER_NAME="{{CONTAINER_NAME}}"
HOST_PORT="{{HOST_PORT}}"
CONTAINER_PORT="{{CONTAINER_PORT}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${IMAGE}" ]] || die "IMAGE must be set."
[[ "${CONTAINER_NAME}" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || die "CONTAINER_NAME is invalid."
[[ "${HOST_PORT}" =~ ^[0-9]+$ ]] || die "HOST_PORT must be numeric."
[[ "${CONTAINER_PORT}" =~ ^[0-9]+$ ]] || die "CONTAINER_PORT must be numeric."
command -v docker >/dev/null 2>&1 || die "docker is required."

# --- Deploy -----------------------------------------------------------------
log "Pulling image ${IMAGE}..."
docker pull "${IMAGE}"

if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  log "Removing existing container '${CONTAINER_NAME}'..."
  docker rm -f "${CONTAINER_NAME}" >/dev/null
fi

log "Starting container '${CONTAINER_NAME}' (${HOST_PORT}->${CONTAINER_PORT})..."
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  -p "${HOST_PORT}:${CONTAINER_PORT}" \
  "${IMAGE}"

sleep 2
if docker ps --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  log "Container '${CONTAINER_NAME}' is running."
  docker ps --filter "name=^${CONTAINER_NAME}$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
else
  log "Container failed to start. Recent logs:"
  docker logs --tail 30 "${CONTAINER_NAME}" || true
  die "Deployment failed."
fi
