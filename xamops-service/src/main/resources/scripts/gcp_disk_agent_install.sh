#!/usr/bin/env bash
# XamOps GCP Disk Agent — GCE installer
#
# Installs the XamOps disk agent on a GCP Compute Engine instance so that actual
# disk usage (via df) is reported to XamOps without requiring Cloud Monitoring Agent.
# The agent runs as a lightweight systemd service and reports every 5 minutes.
#
# Usage (one-liner from XamOps UI):
#
#   curl -fsSL https://api.xamops.com/agentinstall/gcp_disk_agent_install.sh | \
#     sudo bash -s -- -t <gcp-project-id>
#
# Or download and run:
#   curl -fsSL https://api.xamops.com/agentinstall/gcp_disk_agent_install.sh -o gcp_agent_install.sh
#   sudo bash gcp_agent_install.sh -t <gcp-project-id>
#
# Options:
#   -t PROJECT_ID  Your GCP project ID (required, e.g. my-project-123)
#   -b BASE_URL    XamOps API base URL (default: https://api.xamops.com)
#   -v VERSION     Agent version to install (default: latest)
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[xamops]${NC} $*"; }
warn()  { echo -e "${YELLOW}[xamops]${NC} $*"; }
error() { echo -e "${RED}[xamops]${NC} $*"; exit 1; }

# ── Baked-in XamOps connection ────────────────────────────────────────────────
XAMOPS_API_KEY="xamops_SecureAgentKey2024XamOps"

# ── Parse args ────────────────────────────────────────────────────────────────
GCP_PROJECT_ID=""
BASE_URL="https://api.xamops.com"
AGENT_VERSION="latest"

while getopts "t:b:v:" opt; do
  case $opt in
    t) GCP_PROJECT_ID="$OPTARG" ;;
    b) BASE_URL="$OPTARG" ;;
    v) AGENT_VERSION="$OPTARG" ;;
    *) error "Unknown option: -$OPTARG" ;;
  esac
done

[[ -z "$GCP_PROJECT_ID" ]] && error "-t <gcp-project-id> is required — your GCP project ID (e.g. my-project-123)"

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

# ── Resolve GCE instance identity ─────────────────────────────────────────────
info "Fetching GCE instance metadata..."
INSTANCE_NAME=$(curl -sf -H "Metadata-Flavor: Google" \
  "http://169.254.169.254/computeMetadata/v1/instance/name" 2>/dev/null || true)

[[ -z "$INSTANCE_NAME" ]] && error "Could not determine GCE instance name. Is this running on a GCE instance?"
info "Instance: ${INSTANCE_NAME}"

CLUSTER_NAME="gcp-disk-agent-${INSTANCE_NAME}"
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
ENV_FILE=/etc/xamops/gcp-disk-agent.env

info "Writing config to ${ENV_FILE}"
sudo tee "$ENV_FILE" > /dev/null <<EOF
XAMOPS_SERVER_URL=${BASE_URL}
XAMOPS_API_KEY=${XAMOPS_API_KEY}
CLOUD_ACCOUNT_ID=${GCP_PROJECT_ID}
CLUSTER_NAME=${CLUSTER_NAME}
CLOUD_PROVIDER=GCP
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
Description=XamOps Disk Agent (GCP)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/xamops/gcp-disk-agent.env
# Wait for GCE metadata service before starting — can take 20-30s after instance boot/restart
ExecStartPre=/bin/sh -c 'i=0; until curl -sf --max-time 3 -H "Metadata-Flavor: Google" http://169.254.169.254/computeMetadata/v1/instance/name >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 20 ] && exit 1; sleep 3; done'
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
info "Done! GCP Disk agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-disk-agent"
echo "  Logs   : sudo journalctl -u xamops-disk-agent -f"
echo ""
echo "  Disk usage will appear in XamOps within 1-2 minutes."
echo "  Refresh the GCP Disk Rightsizing page to see SHRINK recommendations."
echo ""
