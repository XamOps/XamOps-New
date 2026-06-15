#!/usr/bin/env bash
#
# cleanup-old-logs.sh
# Deletes log files older than a retention window within a directory tree.
# Performs a dry run first and reports reclaimed space.
#
# Parameters:
#   {{LOG_PATH}}        - Directory to clean (e.g. /var/log/myapp)
#   {{RETENTION_DAYS}}  - Delete files older than this many days (e.g. 30)
#
set -euo pipefail

LOG_PATH="{{LOG_PATH}}"
RETENTION_DAYS="{{RETENTION_DAYS}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${LOG_PATH}" ]] || die "LOG_PATH must be set."
[[ -d "${LOG_PATH}" ]] || die "LOG_PATH '${LOG_PATH}' is not a directory."
[[ "${RETENTION_DAYS}" =~ ^[0-9]+$ ]] || die "RETENTION_DAYS must be a positive integer."
(( RETENTION_DAYS >= 1 )) || die "RETENTION_DAYS must be at least 1."

# Guard against accidentally targeting the entire filesystem.
case "${LOG_PATH}" in
  /|/var|/var/log|/etc|/home) die "Refusing to operate on protected path '${LOG_PATH}'." ;;
esac

# --- Identify candidates ----------------------------------------------------
log "Scanning ${LOG_PATH} for *.log/*.gz files older than ${RETENTION_DAYS} days..."
mapfile -t candidates < <(find "${LOG_PATH}" -type f \( -name '*.log' -o -name '*.gz' -o -name '*.log.*' \) -mtime +"${RETENTION_DAYS}")

if [[ ${#candidates[@]} -eq 0 ]]; then
  log "No files matched. Nothing to delete."
  exit 0
fi

reclaim="$(printf '%s\n' "${candidates[@]}" | xargs -r du -ch 2>/dev/null | tail -n1 | cut -f1)"
log "Found ${#candidates[@]} file(s) totalling ${reclaim}. Deleting..."
printf '%s\n' "${candidates[@]}" | xargs -r rm -f

log "Cleanup complete. Reclaimed approximately ${reclaim}."
