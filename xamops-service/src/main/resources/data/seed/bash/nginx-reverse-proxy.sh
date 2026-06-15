#!/usr/bin/env bash
#
# nginx-reverse-proxy.sh
# Installs nginx (if needed) and configures a reverse proxy virtual host that
# forwards a domain to an upstream application server.
#
# Parameters:
#   {{DOMAIN_NAME}}    - Public server name (e.g. app.example.com)
#   {{UPSTREAM_HOST}}  - Upstream host/IP to proxy to (e.g. 127.0.0.1)
#   {{UPSTREAM_PORT}}  - Upstream port (e.g. 3000)
#
set -euo pipefail

DOMAIN_NAME="{{DOMAIN_NAME}}"
UPSTREAM_HOST="{{UPSTREAM_HOST}}"
UPSTREAM_PORT="{{UPSTREAM_PORT}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${DOMAIN_NAME}" ]] || die "DOMAIN_NAME must be set."
[[ -n "${UPSTREAM_HOST}" ]] || die "UPSTREAM_HOST must be set."
[[ "${UPSTREAM_PORT}" =~ ^[0-9]+$ ]] || die "UPSTREAM_PORT must be numeric."

if ! command -v nginx >/dev/null 2>&1; then
  log "Installing nginx..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y && apt-get install -y nginx
fi

SITE="/etc/nginx/sites-available/${DOMAIN_NAME}"
log "Writing reverse proxy config to ${SITE}..."
cat > "${SITE}" <<EOF
server {
    listen 80;
    server_name ${DOMAIN_NAME};

    location / {
        proxy_pass http://${UPSTREAM_HOST}:${UPSTREAM_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 60s;
    }
}
EOF

ln -sf "${SITE}" "/etc/nginx/sites-enabled/${DOMAIN_NAME}"

log "Testing nginx configuration..."
nginx -t

log "Reloading nginx..."
systemctl reload nginx

log "Reverse proxy for ${DOMAIN_NAME} -> ${UPSTREAM_HOST}:${UPSTREAM_PORT} is live."
