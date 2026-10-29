#!/usr/bin/env bash
# XamOps Disk Agent — EC2 installer
#
# Installs the XamOps disk agent on an EC2 instance. Collects disk usage,
# streams logs to OpenObserve, and auto-triggers SRE investigations on anomalies
# (OOM kills, failed services, high disk usage). Runs as a systemd service.
#
# Usage (one-liner from XamOps UI):
#
#   curl -fsSL https://api.xamops.com/agentinstall/agent_install.sh | \
#     sudo bash -s -- -t <cloud-account-token>
#
# Or download and run:
#   curl -fsSL https://api.xamops.com/agentinstall/agent_install.sh -o agent_install.sh
#   sudo bash agent_install.sh -t <cloud-account-token>
#
# Options:
#   -t ACCOUNT_ID        Your 12-digit AWS account ID (required)
#   -b BASE_URL          XamOps API base URL (default: https://api.xamops.com)
#   -v VERSION           Agent version to install (default: latest)
#   -o OPENOBSERVE_URL   OpenObserve base URL for log shipping (optional)
#                        e.g. http://my-openobserve-host:5080
#   -u OO_USERNAME       OpenObserve username (default: root@example.com)
#   -p OO_PASSWORD       OpenObserve password (enables log watcher when set)
#   -s OO_STREAM         OpenObserve stream name (default: ec2-logs)
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[xamops]${NC} $*"; }
warn()  { echo -e "${YELLOW}[xamops]${NC} $*"; }
error() { echo -e "${RED}[xamops]${NC} $*"; exit 1; }

# ── XamOps connection (SEC-6: key supplied via env, not baked in) ─────────────
: "${XAMOPS_API_KEY:?paste the account-scoped key from the XamOps wizard}"

# ── Parse args ────────────────────────────────────────────────────────────────
CLOUD_ACCOUNT_ID=""
BASE_URL="https://api.xamops.com"
AGENT_VERSION="latest"
OPENOBSERVE_URL=""
OPENOBSERVE_USERNAME="root@example.com"
OPENOBSERVE_PASSWORD=""
OPENOBSERVE_STREAM="ec2-logs"

while getopts "t:b:v:o:u:p:s:" opt; do
  case $opt in
    t) CLOUD_ACCOUNT_ID="$OPTARG" ;;
    b) BASE_URL="$OPTARG" ;;
    v) AGENT_VERSION="$OPTARG" ;;
    o) OPENOBSERVE_URL="$OPTARG" ;;
    u) OPENOBSERVE_USERNAME="$OPTARG" ;;
    p) OPENOBSERVE_PASSWORD="$OPTARG" ;;
    s) OPENOBSERVE_STREAM="$OPTARG" ;;
    *) error "Unknown option: -$OPTARG" ;;
  esac
done

[[ -z "$CLOUD_ACCOUNT_ID" ]] && error "-t <aws-account-id> is required — your 12-digit AWS account ID (visible top-right in AWS Console)"

# ── Detect OS/arch ────────────────────────────────────────────────────────────
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  ARCH="amd64" ;;
  aarch64) ARCH="arm64" ;;
  arm64)   ARCH="arm64" ;;
  *)       error "Unsupported architecture: $ARCH" ;;
esac

info "Detected: ${OS}/${ARCH}"

# ── Resolve EC2 instance identity ─────────────────────────────────────────────
info "Fetching EC2 instance metadata..."
IMDS_TOKEN=$(curl -sf -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null || true)

if [[ -n "$IMDS_TOKEN" ]]; then
  INSTANCE_ID=$(curl -sf -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/instance-id)
  AWS_ACCOUNT_ID=$(curl -sf -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/identity-credentials/ec2/info \
    | grep -o '"AccountId"[[:space:]]*:[[:space:]]*"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"' || true)
else
  # IMDSv1 fallback
  INSTANCE_ID=$(curl -sf http://169.254.169.254/latest/meta-data/instance-id)
  AWS_ACCOUNT_ID=""
fi

[[ -z "$INSTANCE_ID" ]] && error "Could not determine EC2 instance ID. Is this running on an EC2 instance?"
info "Instance: ${INSTANCE_ID}"

CLUSTER_NAME="disk-agent-${INSTANCE_ID}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"

# ── Download binary ───────────────────────────────────────────────────────────
BINARY_NAME="xamops-agent_${OS}_${ARCH}"
DOWNLOAD_URL="${BASE_URL}/agent/download/${AGENT_VERSION}/${BINARY_NAME}"

info "Downloading XamOps agent ${AGENT_VERSION}..."
EXTRA_CURL_ARGS=()
[[ "$BASE_URL" == *"ngrok"* ]] && EXTRA_CURL_ARGS=('-H' 'ngrok-skip-browser-warning: true')
curl -fsSL "${EXTRA_CURL_ARGS[@]}" "$DOWNLOAD_URL" -o /tmp/xamops-agent
chmod +x /tmp/xamops-agent
sudo mv /tmp/xamops-agent "${INSTALL_DIR}/xamops-agent"
info "Binary installed to ${INSTALL_DIR}/xamops-agent"

# ── Write env file ───────────────────────────────────────────────────────────
sudo mkdir -p /etc/xamops
ENV_FILE=/etc/xamops/disk-agent.env

info "Writing config to ${ENV_FILE}"

# Enable log watcher only when an OpenObserve password was supplied
LOG_WATCHER_ENABLED=false
[[ -n "$OPENOBSERVE_PASSWORD" ]] && LOG_WATCHER_ENABLED=true

sudo tee "$ENV_FILE" > /dev/null <<EOF
XAMOPS_SERVER_URL=${BASE_URL}
XAMOPS_API_KEY=${XAMOPS_API_KEY}
CLOUD_ACCOUNT_ID=${CLOUD_ACCOUNT_ID}
CLUSTER_NAME=${CLUSTER_NAME}
LOG_LEVEL=info
COLLECT_INTERVAL=300
HEARTBEAT_INTERVAL=300

# Collector flags — disk + log watcher mode
COLLECTOR_DISK=true
COLLECTOR_DB=false
COLLECTOR_TRIVY=false
COLLECTOR_KUBESCAPE=false
COLLECTOR_OPENCOST=false
COLLECTOR_KARPENTER=false
COLLECTOR_METRICS=false
COLLECTOR_SRE_WATCHER=true

# Log watcher — streams journald logs to OpenObserve + auto-triggers SRE investigations
COLLECTOR_LOG_WATCHER=${LOG_WATCHER_ENABLED}
LOG_WATCHER_INTERVAL=60
LOG_WATCHER_BATCH_SIZE=500
OPENOBSERVE_URL=${OPENOBSERVE_URL}
OPENOBSERVE_ORG=default
OPENOBSERVE_USERNAME=${OPENOBSERVE_USERNAME}
OPENOBSERVE_PASSWORD=${OPENOBSERVE_PASSWORD}
OPENOBSERVE_STREAM=${OPENOBSERVE_STREAM}
EOF

sudo chmod 600 "$ENV_FILE"
sudo chown root:root "$ENV_FILE"

# ── Install systemd service ───────────────────────────────────────────────────
info "Installing systemd service..."
sudo tee /etc/systemd/system/xamops-disk-agent.service > /dev/null <<'UNIT'
[Unit]
Description=XamOps Disk Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/xamops/disk-agent.env
# Wait for EC2 metadata service before starting — IMDS can take 20-30s after instance boot/restart
ExecStartPre=/bin/sh -c 'i=0; until curl -sf --max-time 3 http://169.254.169.254/latest/meta-data/instance-id >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 20 ] && exit 1; sleep 3; done'
ExecStart=/usr/local/bin/xamops-agent
Restart=always
RestartSec=15
# Disable start-rate limit so systemd never permanently stops retrying after repeated boot failures
StartLimitIntervalSec=0
User=root

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable xamops-disk-agent
sudo systemctl restart xamops-disk-agent

# ── Done ──────────────────────────────────────────────────────────────────────
info "Done! XamOps agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-disk-agent"
echo "  Logs   : sudo journalctl -u xamops-disk-agent -f"
echo ""
echo "  Disk usage will appear in XamOps within 1-2 minutes."
echo "  Refresh the EBS Rightsizing page to see SHRINK recommendations."
echo ""
if [[ "$LOG_WATCHER_ENABLED" == "true" ]]; then
  info "Log watcher enabled — shipping journald logs to OpenObserve at ${OPENOBSERVE_URL}"
  echo "  Stream  : ${OPENOBSERVE_STREAM}"
  echo "  OOM kills, disk alerts, and failed services will auto-trigger SRE investigations."
  echo ""
else
  warn "Log watcher disabled (no -p <openobserve-password> supplied)."
  echo "  To enable later, edit ${ENV_FILE} and set:"
  echo "    COLLECTOR_LOG_WATCHER=true"
  echo "    OPENOBSERVE_URL=http://<host>:5080"
  echo "    OPENOBSERVE_PASSWORD=<password>"
  echo "  Then: sudo systemctl restart xamops-disk-agent"
  echo ""
fi
