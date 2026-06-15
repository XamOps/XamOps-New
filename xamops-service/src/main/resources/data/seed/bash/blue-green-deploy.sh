#!/usr/bin/env bash
#
# blue-green-deploy.sh
# Performs a blue-green deployment for a containerized app. Starts the new
# ("green") container, health-checks it, then switches traffic and retires the
# old ("blue") container. Rolls back if the health check fails.
#
# Parameters:
#   {{APP_NAME}}     - Logical app name / image to deploy (e.g. myorg/web:1.4.2)
#   {{HEALTH_URL}}   - Health endpoint to probe (e.g. http://localhost:%PORT%/health)
#   {{BLUE_PORT}}    - Port currently serving live traffic (e.g. 8080)
#   {{GREEN_PORT}}   - Port for the new release (e.g. 8081)
#
set -euo pipefail

APP_NAME="{{APP_NAME}}"
HEALTH_URL="{{HEALTH_URL}}"
BLUE_PORT="{{BLUE_PORT}}"
GREEN_PORT="{{GREEN_PORT}}"
MAX_WAIT=60

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${APP_NAME}" ]] || die "APP_NAME must be set."
[[ "${BLUE_PORT}" =~ ^[0-9]+$ && "${GREEN_PORT}" =~ ^[0-9]+$ ]] || die "Ports must be numeric."
[[ "${BLUE_PORT}" != "${GREEN_PORT}" ]] || die "BLUE_PORT and GREEN_PORT must differ."
command -v docker >/dev/null 2>&1 || die "docker is required."
command -v curl >/dev/null 2>&1 || die "curl is required."

GREEN_NAME="app-green-${GREEN_PORT}"
BLUE_NAME="app-blue-${BLUE_PORT}"

# --- Start green ------------------------------------------------------------
log "Starting green container '${GREEN_NAME}' on port ${GREEN_PORT}..."
docker rm -f "${GREEN_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${GREEN_NAME}" -p "${GREEN_PORT}:8080" "${APP_NAME}"

# --- Health check -----------------------------------------------------------
probe_url="${HEALTH_URL//%PORT%/${GREEN_PORT}}"
log "Waiting for green to become healthy at ${probe_url}..."
healthy=false
for (( i=1; i<=MAX_WAIT; i++ )); do
  if curl -fsS --max-time 3 "${probe_url}" >/dev/null 2>&1; then
    healthy=true
    break
  fi
  sleep 1
done

if [[ "${healthy}" != true ]]; then
  log "Green failed health check after ${MAX_WAIT}s. Rolling back."
  docker rm -f "${GREEN_NAME}" >/dev/null 2>&1 || true
  die "Deployment aborted; blue remains live on ${BLUE_PORT}."
fi

# --- Switch over ------------------------------------------------------------
log "Green is healthy. Retiring blue container '${BLUE_NAME}'..."
docker rm -f "${BLUE_NAME}" >/dev/null 2>&1 || true
docker rename "${GREEN_NAME}" "${BLUE_NAME}"

log "Deployment of '${APP_NAME}' complete. Update your load balancer to point at port ${GREEN_PORT} if needed."
