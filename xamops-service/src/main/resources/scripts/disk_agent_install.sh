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
#   -k API_KEY     Account-scoped agent key from XamOps (recommended).
#                  Without it the agent falls back to the shared legacy key, which
#                  cannot prove which account it belongs to and is being retired.
#   -b BASE_URL    XamOps API base URL (default: https://api.xamops.com)
#   -v VERSION     Agent version to install (default: latest)
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[xamops]${NC} $*"; }
warn()  { echo -e "${YELLOW}[xamops]${NC} $*"; }
error() { echo -e "${RED}[xamops]${NC} $*"; exit 1; }

# ── Legacy shared key ─────────────────────────────────────────────────────────
# Every agent everywhere authenticated with this one value, and it ships in this
# public script — so it is not a secret and it proves nothing about which account
# a request belongs to. Prefer -k with an account-scoped key from
# XamOps → the account's agent settings. This fallback exists only so that
# installs documented before scoped keys existed keep working.
LEGACY_SHARED_KEY="xamops_SecureAgentKey2024XamOps"

# ── Parse args ────────────────────────────────────────────────────────────────
CLOUD_ACCOUNT_ID=""
XAMOPS_API_KEY=""
BASE_URL="https://api.xamops.com"
AGENT_VERSION="latest"

while getopts "t:k:b:v:" opt; do
  case $opt in
    t) CLOUD_ACCOUNT_ID="$OPTARG" ;;
    k) XAMOPS_API_KEY="$OPTARG" ;;
    b) BASE_URL="$OPTARG" ;;
    v) AGENT_VERSION="$OPTARG" ;;
    *) error "Unknown option: -$OPTARG" ;;
  esac
done

[[ -z "$CLOUD_ACCOUNT_ID" ]] && error "-t <aws-account-id> is required — your 12-digit AWS account ID (visible top-right in AWS Console)"

if [[ -z "$XAMOPS_API_KEY" ]]; then
  XAMOPS_API_KEY="$LEGACY_SHARED_KEY"
  warn "No -k <api-key> given — using the shared legacy key."
  warn "That key is published in this script and is being retired. Get an"
  warn "account-scoped key from XamOps and re-run with -k <api-key>."
else
  info "Using account-scoped agent key (${XAMOPS_API_KEY:0:12}...)"
fi

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
HEALTH_PORT=9091

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
StartLimitIntervalSec=0

[Service]
Type=simple
EnvironmentFile=/etc/xamops/disk-agent.env
# Wait for EC2 metadata service before starting — IMDS can take 20-30s after instance boot/restart
ExecStartPre=/bin/sh -c 'i=0; until (T=$(curl -sf -X PUT --max-time 3 http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) && curl -sf --max-time 3 -H "X-aws-ec2-metadata-token: $T" http://169.254.169.254/latest/meta-data/instance-id >/dev/null 2>&1) || curl -sf --max-time 3 http://169.254.169.254/latest/meta-data/instance-id >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 20 ] && exit 1; sleep 3; done'
ExecStart=/usr/local/bin/xamops-agent
Restart=always
RestartSec=15
User=root

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload

# ── Systemd preset — prevents cloud-init/preset-all from disabling the service ──
# Without this, `systemctl preset-all` (run by some OS updates/cloud-init scripts)
# resets unknown services to their default preset (disabled), overriding our enable.
sudo mkdir -p /etc/systemd/system-preset
sudo tee /etc/systemd/system-preset/90-xamops-disk-agent.preset > /dev/null <<'PRESET'
enable xamops-disk-agent.service
PRESET

sudo systemctl enable xamops-disk-agent
sudo systemctl restart xamops-disk-agent

# ── Cron fallback — ensures agent starts on boot even if systemd enable is lost ──
# Belt-and-suspenders: if the unit ever ends up disabled (e.g. OS re-image,
# manual preset reset), this cron will start the agent at every system boot.
CRON_LINE="@reboot root sleep 30 && systemctl is-active --quiet xamops-disk-agent || systemctl start xamops-disk-agent"
CRON_FILE="/etc/cron.d/xamops-disk-agent"
echo "$CRON_LINE" | sudo tee "$CRON_FILE" > /dev/null
sudo chmod 644 "$CRON_FILE"

# ── Done ──────────────────────────────────────────────────────────────────────
info "Done! Disk agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-disk-agent"
echo "  Logs   : sudo journalctl -u xamops-disk-agent -f"
echo ""
echo "  Disk usage will appear in XamOps within 1-2 minutes."
echo "  Refresh the EBS Rightsizing page to see SHRINK recommendations."
echo ""
