#!/usr/bin/env bash
#
# system-update.sh
# Applies OS package updates non-interactively on Debian/Ubuntu and optionally
# reboots if the kernel or core libraries require it.
#
# Parameters:
#   {{REBOOT_IF_REQUIRED}}  - "yes" to reboot when required, "no" to skip
#
set -euo pipefail

REBOOT_IF_REQUIRED="{{REBOOT_IF_REQUIRED}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${REBOOT_IF_REQUIRED}" == "yes" || "${REBOOT_IF_REQUIRED}" == "no" ]] || die "REBOOT_IF_REQUIRED must be 'yes' or 'no'."
command -v apt-get >/dev/null 2>&1 || die "apt-get not found; this script targets Debian/Ubuntu."

# --- Update -----------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
log "Refreshing package index..."
apt-get update -y

log "Upgrading packages..."
apt-get -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" dist-upgrade

log "Removing unused packages..."
apt-get -y autoremove --purge
apt-get -y autoclean

# --- Reboot handling --------------------------------------------------------
if [[ -f /var/run/reboot-required ]]; then
  log "A reboot is required to complete updates."
  if [[ "${REBOOT_IF_REQUIRED}" == "yes" ]]; then
    log "Rebooting now..."
    shutdown -r +1 "Rebooting to apply system updates"
  else
    log "Skipping reboot (REBOOT_IF_REQUIRED=no). Please schedule a reboot."
  fi
else
  log "No reboot required."
fi

log "System update complete."
