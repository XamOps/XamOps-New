#!/usr/bin/env bash
#
# configure-swap.sh
# Creates and enables a swap file of a given size and makes it persistent
# across reboots. Idempotent: skips creation if swap already exists.
#
# Parameters:
#   {{SWAP_SIZE_GB}}  - Desired swap file size in gigabytes (e.g. 2)
#
set -euo pipefail

SWAP_SIZE_GB="{{SWAP_SIZE_GB}}"
SWAP_FILE="/swapfile"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${SWAP_SIZE_GB}" =~ ^[0-9]+$ ]] || die "SWAP_SIZE_GB must be a positive integer."
(( SWAP_SIZE_GB >= 1 )) || die "SWAP_SIZE_GB must be at least 1."

if swapon --show | grep -q "${SWAP_FILE}"; then
  log "Swap file ${SWAP_FILE} is already active. Nothing to do."
  swapon --show
  exit 0
fi

# --- Create swap ------------------------------------------------------------
log "Creating ${SWAP_SIZE_GB}G swap file at ${SWAP_FILE}..."
if fallocate -l "${SWAP_SIZE_GB}G" "${SWAP_FILE}" 2>/dev/null; then
  :
else
  log "fallocate unavailable; falling back to dd..."
  dd if=/dev/zero of="${SWAP_FILE}" bs=1M count=$(( SWAP_SIZE_GB * 1024 )) status=progress
fi

chmod 600 "${SWAP_FILE}"
mkswap "${SWAP_FILE}"
swapon "${SWAP_FILE}"

# --- Persist ----------------------------------------------------------------
if ! grep -q "^${SWAP_FILE}" /etc/fstab; then
  echo "${SWAP_FILE} none swap sw 0 0" >> /etc/fstab
  log "Added swap entry to /etc/fstab."
fi

# Tune swappiness for server workloads.
sysctl -w vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo "vm.swappiness=10" >> /etc/sysctl.conf

log "Swap configured:"
swapon --show
