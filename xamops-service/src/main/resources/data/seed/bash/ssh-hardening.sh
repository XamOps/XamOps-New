#!/usr/bin/env bash
#
# ssh-hardening.sh
# Applies SSH daemon hardening: disables root login and password auth,
# sets a custom port, and restricts access to named users.
#
# Parameters:
#   {{SSH_PORT}}       - SSH port to listen on (e.g. 2222)
#   {{ALLOWED_USERS}}  - Space-separated list of users permitted to log in (e.g. "deploy admin")
#
set -euo pipefail

SSH_PORT="{{SSH_PORT}}"
ALLOWED_USERS="{{ALLOWED_USERS}}"
SSHD_CONFIG="/etc/ssh/sshd_config"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${SSH_PORT}" =~ ^[0-9]+$ ]] || die "SSH_PORT must be numeric."
(( SSH_PORT >= 1 && SSH_PORT <= 65535 )) || die "SSH_PORT must be between 1 and 65535."
[[ -n "${ALLOWED_USERS// /}" ]] || die "ALLOWED_USERS cannot be empty (you could lock yourself out)."
[[ -f "${SSHD_CONFIG}" ]] || die "${SSHD_CONFIG} not found."

# --- Backup -----------------------------------------------------------------
BACKUP="${SSHD_CONFIG}.bak.$(date '+%Y%m%d%H%M%S')"
log "Backing up ${SSHD_CONFIG} to ${BACKUP}..."
cp -p "${SSHD_CONFIG}" "${BACKUP}"

# --- Helper to set or replace a directive -----------------------------------
set_option() {
  local key="$1" value="$2"
  if grep -qiE "^\s*#?\s*${key}\b" "${SSHD_CONFIG}"; then
    sed -i -E "s|^\s*#?\s*${key}\b.*|${key} ${value}|I" "${SSHD_CONFIG}"
  else
    echo "${key} ${value}" >> "${SSHD_CONFIG}"
  fi
}

log "Applying hardening directives..."
set_option "Port" "${SSH_PORT}"
set_option "PermitRootLogin" "no"
set_option "PasswordAuthentication" "no"
set_option "ChallengeResponseAuthentication" "no"
set_option "PubkeyAuthentication" "yes"
set_option "X11Forwarding" "no"
set_option "MaxAuthTries" "3"
set_option "ClientAliveInterval" "300"
set_option "ClientAliveCountMax" "2"
set_option "AllowUsers" "${ALLOWED_USERS}"

# --- Validate config before reloading ---------------------------------------
log "Validating sshd configuration..."
if ! sshd -t; then
  log "Validation failed; restoring backup."
  cp -p "${BACKUP}" "${SSHD_CONFIG}"
  die "sshd config invalid; original restored."
fi

log "Reloading SSH service..."
systemctl reload ssh 2>/dev/null || systemctl reload sshd

log "SSH hardening complete. Confirm you can log in on port ${SSH_PORT} BEFORE closing this session."
