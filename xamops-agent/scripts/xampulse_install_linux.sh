#!/bin/bash
# XamPulse agent installer for Linux.
# Source of truth for the SSM document body in xamops-xampulse*.yml and for GET /agentinstall/xampulse.sh.
# It contains no secret: the agent proves its identity later with its own instance-profile credentials.
# {{ ReleaseBaseUrl }} and {{ DesiredStateUrl }} are SSM document parameters (replaced by Systems Manager)
# or replaced by the XamOps server when the script is served directly.
set -eu

RELEASE_BASE_URL="{{ ReleaseBaseUrl }}"
DESIRED_STATE_URL="{{ DesiredStateUrl }}"
STATE_DIR="/var/lib/xampulse"
INSTALL_DIR="/opt/xampulse"
LOG_DIR="/var/log/xampulse"
MARKER="${STATE_DIR}/disabled"
INSTALLED_SHA_FILE="${STATE_DIR}/installed.sha256"
COSIGN_PUBKEY="/etc/xampulse/cosign.pub"
UNIT="/etc/systemd/system/xampulse-agent.service"

log() { echo "xampulse-install: $*"; }

if [ "$(id -u)" != "0" ]; then
  log "must run as root"
  exit 1
fi
if [ "$(uname -s)" != "Linux" ]; then
  log "this installer supports Linux only"
  exit 0
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1; then
  log "curl and sha256sum are required"
  exit 1
fi

# --- instance identity from IMDSv2 only (no IMDSv1 fallback) ---------------------------------
IMDS_TOKEN="$(curl -fsS --max-time 3 -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)"
if [ -z "${IMDS_TOKEN}" ]; then
  log "IMDSv2 token unavailable; not an EC2 instance or IMDS is disabled. Nothing to do."
  exit 0
fi
IDENTITY="$(curl -fsS --max-time 3 -H "X-aws-ec2-metadata-token: ${IMDS_TOKEN}" \
  "http://169.254.169.254/latest/dynamic/instance-identity/document" 2>/dev/null || true)"
json_field() { echo "${IDENTITY}" | sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n 1; }
ACCOUNT_ID="$(json_field accountId)"
INSTANCE_ID="$(json_field instanceId)"
if [ -z "${ACCOUNT_ID}" ] || [ -z "${INSTANCE_ID}" ]; then
  log "could not read the instance identity document. Nothing to do."
  exit 0
fi

# --- ask the server whether this instance should have the agent (desired state) --------------
ANSWER="$(curl -fsS --max-time 10 -G "${DESIRED_STATE_URL}" \
  --data-urlencode "accountId=${ACCOUNT_ID}" --data-urlencode "instanceId=${INSTANCE_ID}" 2>/dev/null | tr -d '[:space:]' || true)"

if [ "${ANSWER}" = "SKIP" ]; then
  mkdir -p "${STATE_DIR}"
  : > "${MARKER}"
  if [ -e "${UNIT}" ] || [ -d "${INSTALL_DIR}" ]; then
    systemctl stop xampulse-agent.service >/dev/null 2>&1 || true
    systemctl disable xampulse-agent.service >/dev/null 2>&1 || true
    rm -f "${UNIT}" "${STATE_DIR}/installed.sha256"
    rm -rf "${INSTALL_DIR}"
    systemctl daemon-reload >/dev/null 2>&1 || true
  fi
  log "desired state is SKIP; collection is off for this instance."
  exit 0
fi
if [ "${ANSWER}" != "INSTALL" ]; then
  # Server unreachable or unexpected answer: honour the local marker, never install on a guess.
  if [ -e "${MARKER}" ]; then
    log "desired state unavailable and the disabled marker is present; leaving things as they are."
  else
    log "desired state unavailable; not installing now. An agent that is already running is left alone."
  fi
  exit 0
fi
rm -f "${MARKER}"

# --- platform ------------------------------------------------------------------------------------
case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) log "unsupported architecture $(uname -m)"; exit 1 ;;
esac
if ! command -v systemctl >/dev/null 2>&1; then
  log "systemd is required"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
AGENT_FILE="xampulse-agent_linux_${ARCH}"
COLLECTOR_FILE="xampulse-otelcol_linux_${ARCH}"

curl -fsS --max-time 60 -o "${TMP}/SHA256SUMS" "${RELEASE_BASE_URL}/latest/SHA256SUMS"

# Release signature: verified when cosign and a pinned public key are present on the host, otherwise
# the checksum list is trusted over TLS only and that is logged.
if command -v cosign >/dev/null 2>&1 && [ -r "${COSIGN_PUBKEY}" ]; then
  curl -fsS --max-time 60 -o "${TMP}/SHA256SUMS.sig" "${RELEASE_BASE_URL}/latest/SHA256SUMS.sig"
  cosign verify-blob --key "${COSIGN_PUBKEY}" --signature "${TMP}/SHA256SUMS.sig" "${TMP}/SHA256SUMS" >/dev/null
  log "release signature verified"
else
  log "WARNING: release signature not verified (cosign or ${COSIGN_PUBKEY} missing); using checksum over TLS only"
fi

SUMS_DIGEST="$(sha256sum "${TMP}/SHA256SUMS" | cut -d' ' -f1)"
if [ -r "${INSTALLED_SHA_FILE}" ] && [ "$(cat "${INSTALLED_SHA_FILE}")" = "${SUMS_DIGEST}" ] \
   && systemctl is-active --quiet xampulse-agent.service; then
  log "already up to date"
  exit 0
fi

for f in "${AGENT_FILE}" "${COLLECTOR_FILE}"; do
  curl -fsS --max-time 300 -o "${TMP}/${f}" "${RELEASE_BASE_URL}/latest/${f}"
  EXPECTED="$(grep "[[:space:]]${f}\$" "${TMP}/SHA256SUMS" | head -n 1 | cut -d' ' -f1)"
  ACTUAL="$(sha256sum "${TMP}/${f}" | cut -d' ' -f1)"
  if [ -z "${EXPECTED}" ] || [ "${EXPECTED}" != "${ACTUAL}" ]; then
    log "checksum mismatch for ${f}; aborting"
    exit 1
  fi
done

# --- install ---------------------------------------------------------------------------------------
if ! id xampulse >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin xampulse
fi
for g in adm systemd-journal docker; do
  if getent group "${g}" >/dev/null 2>&1; then usermod -a -G "${g}" xampulse || true; fi
done
mkdir -p "${INSTALL_DIR}" "${STATE_DIR}" "${LOG_DIR}"
chown xampulse:xampulse "${STATE_DIR}" "${LOG_DIR}"
chmod 750 "${STATE_DIR}"

install -m 0755 "${TMP}/${AGENT_FILE}" "${INSTALL_DIR}/xampulse-agent.new"
install -m 0755 "${TMP}/${COLLECTOR_FILE}" "${INSTALL_DIR}/xampulse-otelcol.new"
mv -f "${INSTALL_DIR}/xampulse-agent.new" "${INSTALL_DIR}/xampulse-agent"
mv -f "${INSTALL_DIR}/xampulse-otelcol.new" "${INSTALL_DIR}/xampulse-otelcol"

SERVER_URL="$(echo "${DESIRED_STATE_URL}" | sed 's#/api/v1/agent/xampulse/.*##')"

cat > "${UNIT}" <<EOF
[Unit]
Description=XamPulse agent
After=network-online.target
Wants=network-online.target
ConditionPathExists=!${MARKER}

[Service]
User=xampulse
Group=xampulse
ExecStart=${INSTALL_DIR}/xampulse-agent run --server ${SERVER_URL} --state-dir ${STATE_DIR} --otelcol ${INSTALL_DIR}/xampulse-otelcol
Restart=always
RestartSec=5
RestartPreventExitStatus=78
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=${STATE_DIR} ${LOG_DIR}
CapabilityBoundingSet=CAP_DAC_READ_SEARCH
AmbientCapabilities=CAP_DAC_READ_SEARCH

[Install]
WantedBy=multi-user.target
EOF

echo "${SUMS_DIGEST}" > "${INSTALLED_SHA_FILE}"
systemctl daemon-reload
systemctl enable xampulse-agent.service >/dev/null 2>&1 || true
systemctl restart xampulse-agent.service
log "installed"
