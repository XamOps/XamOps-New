#!/usr/bin/env bash
#
# schedule-automated-backups.sh
# Installs (or updates) a cron entry that runs a backup script on a schedule,
# idempotently. Logs output to a dedicated file.
#
# Parameters:
#   {{BACKUP_SCRIPT}}  - Absolute path to the backup script to schedule
#   {{CRON_SCHEDULE}}  - Cron expression (e.g. "0 2 * * *" for daily at 02:00)
#
set -euo pipefail

BACKUP_SCRIPT="{{BACKUP_SCRIPT}}"
CRON_SCHEDULE="{{CRON_SCHEDULE}}"
CRON_LOG="/var/log/xamops-backup.log"
CRON_TAG="# xamops-managed-backup"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${BACKUP_SCRIPT}" ]] || die "BACKUP_SCRIPT must be set."
[[ -f "${BACKUP_SCRIPT}" ]] || die "BACKUP_SCRIPT '${BACKUP_SCRIPT}' does not exist."
[[ -x "${BACKUP_SCRIPT}" ]] || die "BACKUP_SCRIPT '${BACKUP_SCRIPT}' is not executable."
# Validate cron has exactly five fields.
[[ "$(echo "${CRON_SCHEDULE}" | awk '{print NF}')" -eq 5 ]] || die "CRON_SCHEDULE must have 5 fields."
command -v crontab >/dev/null 2>&1 || die "crontab is not available."

# --- Install / update cron entry --------------------------------------------
NEW_LINE="${CRON_SCHEDULE} ${BACKUP_SCRIPT} >> ${CRON_LOG} 2>&1 ${CRON_TAG}"

log "Updating crontab for current user ($(whoami))..."
# Preserve existing entries except a previous managed line, then append the new one.
( crontab -l 2>/dev/null | grep -vF "${CRON_TAG}" || true; echo "${NEW_LINE}" ) | crontab -

log "Scheduled backup installed:"
crontab -l | grep -F "${CRON_TAG}"
log "Output will be logged to ${CRON_LOG}."
