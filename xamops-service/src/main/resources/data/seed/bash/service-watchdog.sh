#!/usr/bin/env bash
#
# service-watchdog.sh
# Monitors a systemd service and automatically restarts it if it has stopped.
# Intended to run from cron or as a long-running loop.
#
# Parameters:
#   {{SERVICE_NAME}}    - systemd unit to monitor (e.g. nginx)
#   {{CHECK_INTERVAL}}  - Seconds between checks in loop mode; 0 = check once
#
set -euo pipefail

SERVICE_NAME="{{SERVICE_NAME}}"
CHECK_INTERVAL="{{CHECK_INTERVAL}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${SERVICE_NAME}" ]] || die "SERVICE_NAME must be set."
[[ "${CHECK_INTERVAL}" =~ ^[0-9]+$ ]] || die "CHECK_INTERVAL must be a non-negative integer."
command -v systemctl >/dev/null 2>&1 || die "systemctl is required."
systemctl list-unit-files | grep -q "^${SERVICE_NAME}\." || die "Unit '${SERVICE_NAME}' not found."

check_once() {
  if systemctl is-active --quiet "${SERVICE_NAME}"; then
    log "${SERVICE_NAME} is active."
  else
    log "${SERVICE_NAME} is DOWN. Attempting restart..."
    systemctl restart "${SERVICE_NAME}"
    sleep 3
    if systemctl is-active --quiet "${SERVICE_NAME}"; then
      log "${SERVICE_NAME} successfully restarted."
    else
      log "ERROR: ${SERVICE_NAME} failed to restart. Manual intervention required."
    fi
  fi
}

if [[ "${CHECK_INTERVAL}" -eq 0 ]]; then
  check_once
else
  log "Watching ${SERVICE_NAME} every ${CHECK_INTERVAL}s. Press Ctrl-C to stop."
  while true; do
    check_once
    sleep "${CHECK_INTERVAL}"
  done
fi
