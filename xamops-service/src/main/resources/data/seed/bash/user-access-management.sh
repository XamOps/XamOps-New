#!/usr/bin/env bash
#
# user-access-management.sh
# Creates a system user, installs an SSH public key, and optionally grants
# passwordless sudo. Idempotent: safe to re-run.
#
# Parameters:
#   {{USERNAME}}        - Username to create / manage (e.g. deploy)
#   {{SSH_PUBLIC_KEY}}  - The user's SSH public key (ssh-ed25519 / ssh-rsa ...)
#   {{SUDO_ACCESS}}     - "yes" to grant sudo, "no" otherwise
#
set -euo pipefail

USERNAME="{{USERNAME}}"
SSH_PUBLIC_KEY="{{SSH_PUBLIC_KEY}}"
SUDO_ACCESS="{{SUDO_ACCESS}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ "${USERNAME}" =~ ^[a-z_][a-z0-9_-]*$ ]] || die "USERNAME '${USERNAME}' is not a valid Linux username."
[[ "${SSH_PUBLIC_KEY}" =~ ^(ssh-(rsa|ed25519)|ecdsa-) ]] || die "SSH_PUBLIC_KEY does not look like a valid public key."
[[ "${SUDO_ACCESS}" == "yes" || "${SUDO_ACCESS}" == "no" ]] || die "SUDO_ACCESS must be 'yes' or 'no'."

# --- Create user ------------------------------------------------------------
if id "${USERNAME}" >/dev/null 2>&1; then
  log "User '${USERNAME}' already exists; updating configuration."
else
  log "Creating user '${USERNAME}'..."
  useradd -m -s /bin/bash "${USERNAME}"
fi

# --- Install SSH key --------------------------------------------------------
SSH_DIR="/home/${USERNAME}/.ssh"
AUTH_KEYS="${SSH_DIR}/authorized_keys"
install -d -m 0700 -o "${USERNAME}" -g "${USERNAME}" "${SSH_DIR}"
if ! grep -qF "${SSH_PUBLIC_KEY}" "${AUTH_KEYS}" 2>/dev/null; then
  echo "${SSH_PUBLIC_KEY}" >> "${AUTH_KEYS}"
  log "SSH public key installed."
else
  log "SSH public key already present."
fi
chown "${USERNAME}:${USERNAME}" "${AUTH_KEYS}"
chmod 0600 "${AUTH_KEYS}"

# --- Sudo -------------------------------------------------------------------
SUDOERS_FILE="/etc/sudoers.d/${USERNAME}"
if [[ "${SUDO_ACCESS}" == "yes" ]]; then
  echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > "${SUDOERS_FILE}"
  chmod 0440 "${SUDOERS_FILE}"
  visudo -cf "${SUDOERS_FILE}" >/dev/null || { rm -f "${SUDOERS_FILE}"; die "Invalid sudoers entry; removed."; }
  log "Granted passwordless sudo to '${USERNAME}'."
else
  rm -f "${SUDOERS_FILE}"
  log "Ensured '${USERNAME}' has no managed sudo entry."
fi

log "User access management complete for '${USERNAME}'."
