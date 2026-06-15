#!/usr/bin/env bash
#
# configure-firewall-ufw.sh
# Configures a baseline UFW firewall: deny inbound by default, allow SSH and
# a customizable list of service ports.
#
# Parameters:
#   {{SSH_PORT}}       - SSH port to allow (e.g. 22)
#   {{ALLOWED_PORTS}}  - Space-separated list of extra ports to open (e.g. "80/tcp 443/tcp")
#
set -euo pipefail

SSH_PORT="{{SSH_PORT}}"
ALLOWED_PORTS="{{ALLOWED_PORTS}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${SSH_PORT}" =~ ^[0-9]+$ ]] || die "SSH_PORT must be numeric, got '${SSH_PORT}'."
(( SSH_PORT >= 1 && SSH_PORT <= 65535 )) || die "SSH_PORT must be between 1 and 65535."

if ! command -v ufw >/dev/null 2>&1; then
  log "ufw not found; installing..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y && apt-get install -y ufw
fi

# --- Configure --------------------------------------------------------------
log "Resetting UFW to a known baseline..."
ufw --force reset

log "Setting default policies: deny incoming, allow outgoing..."
ufw default deny incoming
ufw default allow outgoing

log "Allowing SSH on port ${SSH_PORT}/tcp (with rate limiting)..."
ufw limit "${SSH_PORT}/tcp"

for port in ${ALLOWED_PORTS}; do
  [[ "${port}" =~ ^[0-9]+(/(tcp|udp))?$ ]] || die "Invalid port spec '${port}'."
  log "Allowing ${port}..."
  ufw allow "${port}"
done

log "Enabling UFW..."
ufw --force enable

log "Current firewall status:"
ufw status verbose
