#!/usr/bin/env bash
# XamOps Disk Agent — EC2 installer
#
# Installs the XamOps disk agent on an EC2 instance so that actual disk
# usage (via df) is reported to XamOps without requiring SSM or IAM changes.
# The agent runs as a lightweight systemd service and reports every 5 minutes.
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
#   -t ACCOUNT_ID  Your 12-digit AWS account ID (required)
#   -b BASE_URL    XamOps API base URL (default: https://api.xamops.com)
#   -v VERSION     Agent version to install (default: latest)
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[xamops]${NC} $*"; }
warn()  { echo -e "${YELLOW}[xamops]${NC} $*"; }
error() { echo -e "${RED}[xamops]${NC} $*"; exit 1; }

# ── Baked-in XamOps connection (customers do NOT change these) ────────────────
XAMOPS_API_KEY="xamops_SecureAgentKey2024XamOps"

# ── Parse args ────────────────────────────────────────────────────────────────
CLOUD_ACCOUNT_ID=""
BASE_URL="https://api.xamops.com"
AGENT_VERSION="latest"

while getopts "t:b:v:" opt; do
  case $opt in
    t) CLOUD_ACCOUNT_ID="$OPTARG" ;;
    b) BASE_URL="$OPTARG" ;;
    v) AGENT_VERSION="$OPTARG" ;;
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
sudo tee "$ENV_FILE" > /dev/null <<EOF
XAMOPS_SERVER_URL=${BASE_URL}
XAMOPS_API_KEY=${XAMOPS_API_KEY}
CLOUD_ACCOUNT_ID=${CLOUD_ACCOUNT_ID}
CLUSTER_NAME=${CLUSTER_NAME}
LOG_LEVEL=info
COLLECT_INTERVAL=300
HEARTBEAT_INTERVAL=300

# Collector flags — disk-only mode
COLLECTOR_DISK=true
COLLECTOR_DB=false
COLLECTOR_TRIVY=false
COLLECTOR_KUBESCAPE=false
COLLECTOR_OPENCOST=false
COLLECTOR_KARPENTER=false
COLLECTOR_METRICS=false
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
info "Done! Disk agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-disk-agent"
echo "  Logs   : sudo journalctl -u xamops-disk-agent -f"
echo ""
echo "  Disk usage will appear in XamOps within 1-2 minutes."
echo "  Refresh the EBS Rightsizing page to see SHRINK recommendations."
echo ""
