#!/usr/bin/env bash
#
# install-fail2ban.sh
# Installs and configures fail2ban with an SSH jail to block brute-force
# attempts. Writes a local jail override so package updates don't clobber it.
#
# Parameters:
#   {{BANTIME}}   - Ban duration in seconds (e.g. 3600)
#   {{MAXRETRY}}  - Failed attempts before a ban (e.g. 5)
#
set -euo pipefail

BANTIME="{{BANTIME}}"
MAXRETRY="{{MAXRETRY}}"
JAIL_LOCAL="/etc/fail2ban/jail.local"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${BANTIME}" =~ ^[0-9]+$ ]] || die "BANTIME must be numeric (seconds)."
[[ "${MAXRETRY}" =~ ^[0-9]+$ ]] || die "MAXRETRY must be numeric."
(( MAXRETRY >= 1 )) || die "MAXRETRY must be at least 1."

# --- Install ----------------------------------------------------------------
if ! command -v fail2ban-server >/dev/null 2>&1; then
  log "Installing fail2ban..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y && apt-get install -y fail2ban
fi

# --- Configure --------------------------------------------------------------
log "Writing jail configuration to ${JAIL_LOCAL}..."
cat > "${JAIL_LOCAL}" <<EOF
[DEFAULT]
bantime  = ${BANTIME}
findtime = 600
maxretry = ${MAXRETRY}
backend  = systemd

[sshd]
enabled = true
EOF

log "Enabling and restarting fail2ban..."
systemctl enable fail2ban
systemctl restart fail2ban

sleep 2
log "Current jail status:"
fail2ban-client status sshd || true

log "fail2ban configured: bantime=${BANTIME}s, maxretry=${MAXRETRY}."
