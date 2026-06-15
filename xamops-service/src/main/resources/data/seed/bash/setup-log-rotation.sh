#!/usr/bin/env bash
#
# setup-log-rotation.sh
# Creates a logrotate configuration for an application log directory with a
# configurable retention period.
#
# Parameters:
#   {{LOG_PATH}}        - Glob of logs to rotate (e.g. /var/log/myapp/*.log)
#   {{RETENTION_DAYS}}  - Number of rotated copies (days) to keep (e.g. 14)
#
set -euo pipefail

LOG_PATH="{{LOG_PATH}}"
RETENTION_DAYS="{{RETENTION_DAYS}}"
CONF_NAME="xamops-managed"
CONF_FILE="/etc/logrotate.d/${CONF_NAME}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${LOG_PATH}" ]] || die "LOG_PATH must be set."
[[ "${RETENTION_DAYS}" =~ ^[0-9]+$ ]] || die "RETENTION_DAYS must be a positive integer."
(( RETENTION_DAYS >= 1 )) || die "RETENTION_DAYS must be at least 1."
command -v logrotate >/dev/null 2>&1 || die "logrotate is not installed."

# --- Write configuration ----------------------------------------------------
log "Writing logrotate config to ${CONF_FILE}..."
cat > "${CONF_FILE}" <<EOF
${LOG_PATH} {
    daily
    rotate ${RETENTION_DAYS}
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    dateext
    dateformat -%Y%m%d
}
EOF

log "Validating configuration with a dry run..."
logrotate -d "${CONF_FILE}"

log "Log rotation configured. logrotate runs daily via cron/systemd timer."
