#!/usr/bin/env bash
#
# db-backup-s3.sh
# Dumps a PostgreSQL database, compresses it, and uploads it to an S3 bucket
# with a timestamped key. Removes the local dump afterward.
#
# Parameters:
#   {{DB_NAME}}        - Database name to back up
#   {{DB_HOST}}        - Database host (e.g. localhost)
#   {{BACKUP_BUCKET}}  - Target S3 bucket name (e.g. my-db-backups)
#
set -euo pipefail

DB_NAME="{{DB_NAME}}"
DB_HOST="{{DB_HOST}}"
BACKUP_BUCKET="{{BACKUP_BUCKET}}"
TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
TMP_DIR="$(mktemp -d)"
DUMP_FILE="${TMP_DIR}/${DB_NAME}-${TIMESTAMP}.sql.gz"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "${TMP_DIR}"; }
trap cleanup EXIT

# --- Validation -------------------------------------------------------------
[[ -n "${DB_NAME}" ]] || die "DB_NAME must be set."
[[ -n "${BACKUP_BUCKET}" ]] || die "BACKUP_BUCKET must be set."
command -v pg_dump >/dev/null 2>&1 || die "pg_dump not found."
command -v aws >/dev/null 2>&1 || die "aws CLI not found."
# PGPASSWORD should be supplied via environment or ~/.pgpass; never hardcode.
: "${PGUSER:=postgres}"

# --- Dump -------------------------------------------------------------------
log "Dumping database '${DB_NAME}' from ${DB_HOST}..."
pg_dump -h "${DB_HOST}" -U "${PGUSER}" "${DB_NAME}" | gzip -9 > "${DUMP_FILE}"
log "Dump created: $(du -h "${DUMP_FILE}" | cut -f1)"

# --- Upload -----------------------------------------------------------------
S3_KEY="s3://${BACKUP_BUCKET}/${DB_NAME}/${TIMESTAMP}/$(basename "${DUMP_FILE}")"
log "Uploading to ${S3_KEY}..."
aws s3 cp "${DUMP_FILE}" "${S3_KEY}" --only-show-errors

log "Backup complete: ${S3_KEY}"
