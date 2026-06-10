#!/usr/bin/env bash
#
# install-docker.sh
# Installs Docker Engine and Docker Compose plugin on Debian/Ubuntu hosts.
#
# Parameters (replaced by the customization engine):
#   {{DOCKER_VERSION}}   - Docker Engine version to pin (e.g. 5:24.0.7-1~ubuntu.22.04~jammy) or "latest"
#   {{COMPOSE_VERSION}}  - Docker Compose plugin version (e.g. v2.24.5)
#
set -euo pipefail

DOCKER_VERSION="{{DOCKER_VERSION}}"
COMPOSE_VERSION="{{COMPOSE_VERSION}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
command -v apt-get >/dev/null 2>&1 || die "apt-get not found; this script targets Debian/Ubuntu."

# --- Install ----------------------------------------------------------------
log "Updating package index..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg lsb-release

log "Adding Docker's official GPG key..."
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

log "Configuring the Docker apt repository..."
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
  > /etc/apt/sources.list.d/docker.list

apt-get update -y

if [[ "${DOCKER_VERSION}" == "latest" ]]; then
  log "Installing the latest Docker Engine..."
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  log "Installing pinned Docker Engine version ${DOCKER_VERSION}..."
  apt-get install -y \
    "docker-ce=${DOCKER_VERSION}" \
    "docker-ce-cli=${DOCKER_VERSION}" \
    containerd.io docker-buildx-plugin docker-compose-plugin
fi

log "Enabling and starting the Docker service..."
systemctl enable --now docker

log "Verifying installation..."
docker --version
docker compose version || log "Compose plugin reports: ${COMPOSE_VERSION} expected"

log "Docker installation complete. Add users to the 'docker' group with: usermod -aG docker <user>"
