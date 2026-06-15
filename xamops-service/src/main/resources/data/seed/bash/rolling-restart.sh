#!/usr/bin/env bash
#
# rolling-restart.sh
# Restarts a systemd-managed service across N logical instances one at a time,
# waiting for each to become active before proceeding to limit downtime.
#
# Parameters:
#   {{SERVICE_NAME}}  - Base systemd service name (e.g. myapp@ for templated units, or myapp)
#   {{INSTANCES}}     - Space-separated instance identifiers (e.g. "1 2 3") or "" for single unit
#
set -euo pipefail

SERVICE_NAME="{{SERVICE_NAME}}"
INSTANCES="{{INSTANCES}}"
SETTLE_SECONDS=5

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${SERVICE_NAME}" ]] || die "SERVICE_NAME must be set."
command -v systemctl >/dev/null 2>&1 || die "systemctl is required."

restart_unit() {
  local unit="$1"
  log "Restarting ${unit}..."
  systemctl restart "${unit}"
  sleep "${SETTLE_SECONDS}"
  if systemctl is-active --quiet "${unit}"; then
    log "${unit} is active."
  else
    die "${unit} failed to come back active. Halting rollout."
  fi
}

if [[ -z "${INSTANCES// /}" ]]; then
  restart_unit "${SERVICE_NAME}"
else
  for inst in ${INSTANCES}; do
    restart_unit "${SERVICE_NAME}${inst}"
  done
fi

log "Rolling restart complete."
