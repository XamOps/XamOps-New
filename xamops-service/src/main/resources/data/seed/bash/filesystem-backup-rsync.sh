#!/usr/bin/env bash
#
# filesystem-backup-rsync.sh
# Performs an incremental filesystem backup using rsync to a local or remote
# destination, preserving permissions and deleting removed files.
#
# Parameters:
#   {{SOURCE_PATH}}  - Directory to back up (e.g. /var/www)
#   {{DEST_PATH}}    - Destination path (e.g. /backups/www or /srv/backups)
#   {{REMOTE_HOST}}  - Optional remote "user@host" for offsite backup; "" for local
#
set -euo pipefail

SOURCE_PATH="{{SOURCE_PATH}}"
DEST_PATH="{{DEST_PATH}}"
REMOTE_HOST="{{REMOTE_HOST}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${SOURCE_PATH}" ]] || die "SOURCE_PATH must be set."
[[ -n "${DEST_PATH}" ]] || die "DEST_PATH must be set."
[[ -d "${SOURCE_PATH}" ]] || die "SOURCE_PATH '${SOURCE_PATH}' does not exist."
command -v rsync >/dev/null 2>&1 || die "rsync is not installed."

# rsync flags: archive, compress, human-readable, delete extraneous, partial+progress.
RSYNC_OPTS=(-aHz --human-readable --delete --partial --info=stats1)

if [[ -n "${REMOTE_HOST}" ]]; then
  TARGET="${REMOTE_HOST}:${DEST_PATH}"
  RSYNC_OPTS+=(-e "ssh -o BatchMode=yes")
  log "Backing up '${SOURCE_PATH}' to remote '${TARGET}'..."
else
  TARGET="${DEST_PATH}"
  mkdir -p "${DEST_PATH}"
  log "Backing up '${SOURCE_PATH}' to local '${TARGET}'..."
fi

# Trailing slash on source copies contents, not the parent dir itself.
rsync "${RSYNC_OPTS[@]}" "${SOURCE_PATH%/}/" "${TARGET%/}/"

log "Backup completed successfully."
