#!/usr/bin/env bash
#
# health-check-automation.sh
# Polls an HTTP health endpoint with retries and exponential backoff. Exits 0
# when healthy, non-zero otherwise. Suitable for CI gates and cron checks.
#
# Parameters:
#   {{HEALTH_URL}}   - Health endpoint URL (e.g. https://example.com/healthz)
#   {{MAX_RETRIES}}  - Maximum number of attempts (e.g. 5)
#
set -euo pipefail

HEALTH_URL="{{HEALTH_URL}}"
MAX_RETRIES="{{MAX_RETRIES}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${HEALTH_URL}" =~ ^https?:// ]] || die "HEALTH_URL must start with http:// or https://"
[[ "${MAX_RETRIES}" =~ ^[0-9]+$ ]] || die "MAX_RETRIES must be a positive integer."
(( MAX_RETRIES >= 1 )) || die "MAX_RETRIES must be at least 1."
command -v curl >/dev/null 2>&1 || die "curl is required."

# --- Probe with backoff -----------------------------------------------------
delay=2
for (( attempt=1; attempt<=MAX_RETRIES; attempt++ )); do
  log "Attempt ${attempt}/${MAX_RETRIES}: probing ${HEALTH_URL}..."
  http_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${HEALTH_URL}" || echo "000")"
  if [[ "${http_code}" =~ ^2[0-9][0-9]$ ]]; then
    log "Healthy (HTTP ${http_code})."
    exit 0
  fi
  log "Unhealthy (HTTP ${http_code})."
  if (( attempt < MAX_RETRIES )); then
    log "Retrying in ${delay}s..."
    sleep "${delay}"
    delay=$(( delay * 2 ))
  fi
done

die "Service did not become healthy after ${MAX_RETRIES} attempts."
