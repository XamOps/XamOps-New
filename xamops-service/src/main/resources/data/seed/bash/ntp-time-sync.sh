#!/usr/bin/env bash
#
# ntp-time-sync.sh
# Configures time synchronization using systemd-timesyncd with a custom NTP
# server and verifies the clock is synchronized.
#
# Parameters:
#   {{NTP_SERVER}}  - NTP server to use (e.g. time.cloudflare.com)
#
set -euo pipefail

NTP_SERVER="{{NTP_SERVER}}"
CONF="/etc/systemd/timesyncd.conf"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${NTP_SERVER}" ]] || die "NTP_SERVER must be set."
command -v timedatectl >/dev/null 2>&1 || die "timedatectl is required (systemd)."

# --- Configure --------------------------------------------------------------
log "Setting NTP server to ${NTP_SERVER} in ${CONF}..."
if [[ -f "${CONF}" ]]; then
  if grep -qE '^\s*#?\s*NTP=' "${CONF}"; then
    sed -i -E "s|^\s*#?\s*NTP=.*|NTP=${NTP_SERVER}|" "${CONF}"
  else
    printf '\n[Time]\nNTP=%s\n' "${NTP_SERVER}" >> "${CONF}"
  fi
else
  printf '[Time]\nNTP=%s\n' "${NTP_SERVER}" > "${CONF}"
fi

log "Enabling NTP synchronization..."
timedatectl set-ntp true
systemctl restart systemd-timesyncd

sleep 3
log "Current time status:"
timedatectl status | grep -E 'NTP|synchronized|Time zone' || timedatectl status

log "Time synchronization configured with ${NTP_SERVER}."
