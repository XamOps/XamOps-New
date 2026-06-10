#!/usr/bin/env bash
#
# postgres-backup-restore.sh
# Backs up or restores a PostgreSQL database to/from a local directory using
# the custom dump format. Supports rotation of old backups.
#
# Usage: ./postgres-backup-restore.sh <backup|restore> [restore-file]
#
# Parameters:
#   {{DB_NAME}}     - Database name
#   {{DB_USER}}     - Database user (must have appropriate privileges)
#   {{BACKUP_DIR}}  - Local directory for dumps (e.g. /var/backups/pg)
#
set -euo pipefail

DB_NAME="{{DB_NAME}}"
DB_USER="{{DB_USER}}"
BACKUP_DIR="{{BACKUP_DIR}}"
RETENTION=7
ACTION="${1:-backup}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${DB_NAME}" ]] || die "DB_NAME must be set."
[[ -n "${DB_USER}" ]] || die "DB_USER must be set."
[[ -n "${BACKUP_DIR}" ]] || die "BACKUP_DIR must be set."
command -v pg_dump >/dev/null 2>&1 || die "pg_dump not found."
command -v pg_restore >/dev/null 2>&1 || die "pg_restore not found."

mkdir -p "${BACKUP_DIR}"

case "${ACTION}" in
  backup)
    OUT="${BACKUP_DIR}/${DB_NAME}-$(date '+%Y%m%d-%H%M%S').dump"
    log "Backing up '${DB_NAME}' to ${OUT}..."
    pg_dump -U "${DB_USER}" -Fc "${DB_NAME}" -f "${OUT}"
    log "Backup complete ($(du -h "${OUT}" | cut -f1))."
    log "Pruning backups older than ${RETENTION} days..."
    find "${BACKUP_DIR}" -name "${DB_NAME}-*.dump" -type f -mtime +"${RETENTION}" -print -delete || true
    ;;
  restore)
    RESTORE_FILE="${2:-}"
    [[ -n "${RESTORE_FILE}" ]] || die "Usage: $0 restore <dump-file>"
    [[ -f "${RESTORE_FILE}" ]] || die "Restore file '${RESTORE_FILE}' not found."
    log "Restoring '${DB_NAME}' from ${RESTORE_FILE} (existing objects will be dropped)..."
    pg_restore -U "${DB_USER}" -d "${DB_NAME}" --clean --if-exists "${RESTORE_FILE}"
    log "Restore complete."
    ;;
  *)
    die "Unknown action '${ACTION}'. Use 'backup' or 'restore'."
    ;;
esac
