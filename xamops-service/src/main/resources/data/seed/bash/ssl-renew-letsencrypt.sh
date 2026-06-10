#!/usr/bin/env bash
#
# ssl-renew-letsencrypt.sh
# Renews Let's Encrypt certificates via certbot and reloads the web server only
# if a certificate was actually renewed. Safe to run from cron.
#
# Parameters:
#   {{DOMAIN_NAME}}  - Primary domain for the certificate (e.g. example.com)
#   {{CERT_EMAIL}}   - Contact email for expiry notices (e.g. admin@example.com)
#
set -euo pipefail

DOMAIN_NAME="{{DOMAIN_NAME}}"
CERT_EMAIL="{{CERT_EMAIL}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root (use sudo)."
[[ -n "${DOMAIN_NAME}" ]] || die "DOMAIN_NAME must be set."
[[ "${CERT_EMAIL}" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || die "CERT_EMAIL is invalid."
command -v certbot >/dev/null 2>&1 || die "certbot is not installed."

CERT_PATH="/etc/letsencrypt/live/${DOMAIN_NAME}/fullchain.pem"

if [[ ! -f "${CERT_PATH}" ]]; then
  log "No existing certificate for ${DOMAIN_NAME}; requesting a new one..."
  certbot certonly --nginx --non-interactive --agree-tos \
    -m "${CERT_EMAIL}" -d "${DOMAIN_NAME}"
else
  log "Attempting renewal for ${DOMAIN_NAME}..."
  # --deploy-hook only fires when a cert is actually renewed.
  certbot renew --cert-name "${DOMAIN_NAME}" \
    --deploy-hook "systemctl reload nginx" --non-interactive
fi

log "Certificate status:"
certbot certificates --cert-name "${DOMAIN_NAME}" 2>/dev/null | grep -E 'Expiry|Domains' || true

log "SSL renewal check complete for ${DOMAIN_NAME}."
