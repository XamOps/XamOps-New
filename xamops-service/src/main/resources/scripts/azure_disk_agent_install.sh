#!/usr/bin/env bash
# XamOps Azure Disk Agent — Azure VM installer
#
# Installs the XamOps disk agent on an Azure Virtual Machine so that actual
# disk usage (via df) is reported to XamOps without requiring Azure Monitor Agent.
# The agent runs as a lightweight systemd service and reports every 5 minutes.
#
# Usage (one-liner from XamOps UI):
#
#   curl -fsSL https://api.xamops.com/agentinstall/azure_disk_agent_install.sh | \
#     sudo bash -s -- -a <xamops-account-id>
#
# Or download and run:
#   curl -fsSL https://api.xamops.com/agentinstall/azure_disk_agent_install.sh -o azure_agent_install.sh
#   sudo bash azure_agent_install.sh -a <xamops-account-id>
#
# Options:
#   -a ACCOUNT_ID  Your XamOps account ID (required, numeric)
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
XAMOPS_ACCOUNT_ID=""
BASE_URL="https://api.xamops.com"
AGENT_VERSION="latest"

while getopts "a:b:v:" opt; do
  case $opt in
    a) XAMOPS_ACCOUNT_ID="$OPTARG" ;;
    b) BASE_URL="$OPTARG" ;;
    v) AGENT_VERSION="$OPTARG" ;;
    *) error "Unknown option: -$OPTARG" ;;
  esac
done

[[ -z "$XAMOPS_ACCOUNT_ID" ]] && error "-a <xamops-account-id> is required — your XamOps account ID (numeric)"

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

# ── Resolve Azure VM identity from IMDS ───────────────────────────────────────
IMDS_BASE="http://169.254.169.254/metadata/instance/compute"
IMDS_API="?api-version=2021-02-01&format=text"
IMDS_HEADER='-H "Metadata:true"'

info "Fetching Azure instance metadata..."

VM_NAME=$(curl -sf -H "Metadata:true" \
  "${IMDS_BASE}/name${IMDS_API}" 2>/dev/null || true)
[[ -z "$VM_NAME" ]] && error "Could not determine Azure VM name. Is this running on an Azure VM?"
info "VM name: ${VM_NAME}"

SUBSCRIPTION_ID=$(curl -sf -H "Metadata:true" \
  "${IMDS_BASE}/subscriptionId${IMDS_API}" 2>/dev/null || true)
[[ -z "$SUBSCRIPTION_ID" ]] && error "Could not determine Azure subscription ID."
info "Subscription ID: ${SUBSCRIPTION_ID}"

RESOURCE_GROUP=$(curl -sf -H "Metadata:true" \
  "${IMDS_BASE}/resourceGroupName${IMDS_API}" 2>/dev/null || true)
info "Resource group: ${RESOURCE_GROUP:-unknown}"

CLUSTER_NAME="azure-disk-agent-${VM_NAME}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
XAMOPS_API_URL="${BASE_URL}"

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
ENV_FILE=/etc/xamops/azure-disk-agent.env

info "Writing config to ${ENV_FILE}"
sudo tee "$ENV_FILE" > /dev/null <<EOF
XAMOPS_SERVER_URL=${BASE_URL}
XAMOPS_API_URL=${XAMOPS_API_URL}
XAMOPS_API_KEY=${XAMOPS_API_KEY}
XAMOPS_ACCOUNT_ID=${XAMOPS_ACCOUNT_ID}
CLOUD_ACCOUNT_ID=${XAMOPS_ACCOUNT_ID}
VM_NAME=${VM_NAME}
SUBSCRIPTION_ID=${SUBSCRIPTION_ID}
RESOURCE_GROUP=${RESOURCE_GROUP}
CLUSTER_NAME=${CLUSTER_NAME}
CLOUD_PROVIDER=AZURE
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

# ── Install the disk-usage reporter script ────────────────────────────────────
REPORTER_SCRIPT=/usr/local/bin/xamops-azure-disk-report.sh
info "Installing disk usage reporter to ${REPORTER_SCRIPT}..."

sudo tee "$REPORTER_SCRIPT" > /dev/null <<'REPORTER'
#!/usr/bin/env bash
# XamOps Azure Disk Usage Reporter — runs every 5 minutes via cron
set -euo pipefail

source /etc/xamops/azure-disk-agent.env

# ── Collect disk usage ────────────────────────────────────────────────────────
# Build JSON array of mount point stats from df output
DISK_ARRAY="["
FIRST=1
while IFS= read -r line; do
  # df -P columns: Filesystem  Size  Used  Avail  Capacity%  Mounted-on
  DEVICE=$(echo "$line" | awk '{print $1}')
  SIZE_KB=$(echo "$line" | awk '{print $2}')
  USED_KB=$(echo "$line" | awk '{print $3}')
  AVAIL_KB=$(echo "$line" | awk '{print $4}')
  USED_PCT=$(echo "$line" | awk '{gsub(/%/,""); print $5}')
  MOUNT=$(echo "$line" | awk '{print $6}')

  # Skip pseudo-filesystems
  case "$DEVICE" in
    tmpfs|devtmpfs|udev|sysfs|proc|cgroup*|overlay|none|shm) continue ;;
  esac

  SIZE_GB=$(awk "BEGIN {printf \"%.2f\", $SIZE_KB/1048576}")
  USED_GB=$(awk "BEGIN {printf \"%.2f\", $USED_KB/1048576}")
  FREE_GB=$(awk "BEGIN {printf \"%.2f\", $AVAIL_KB/1048576}")

  # instanceId must carry the VM name: AzureDiskRightsizingService looks mounts up by
  # azure-disk-agent:{cloudAccountId}:{vmName}.
  [[ $FIRST -eq 0 ]] && DISK_ARRAY+=","
  DISK_ARRAY+="{\"instanceId\":\"${VM_NAME}\",\"device\":\"${DEVICE}\",\"mountPoint\":\"${MOUNT}\",\"sizeGb\":${SIZE_GB},\"usedGb\":${USED_GB},\"freeGb\":${FREE_GB},\"usedPercent\":${USED_PCT}}"
  FIRST=0
done < <(df -Pk 2>/dev/null | tail -n +2)

DISK_ARRAY+="]"

# ── Build payload ─────────────────────────────────────────────────────────────
# Shape matches AgentIngestPayload, consumed by POST /api/v1/agent/ingest with
# provider=AZURE. This previously posted a bespoke payload to
# /api/azure/disks/agent-push/{id} — an endpoint that does not exist — so no Azure
# disk utilization ever reached the server and the whole SHRINK/EXPAND feature was inert.
PAYLOAD="{\"cloudAccountId\":\"${XAMOPS_ACCOUNT_ID}\",\"provider\":\"AZURE\",\"batches\":[{\"collector\":\"disk-usage\",\"items\":${DISK_ARRAY}}]}"

# ── POST to XamOps ────────────────────────────────────────────────────────────
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
  -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${XAMOPS_API_KEY}" \
  --data "${PAYLOAD}" \
  "${XAMOPS_API_URL}/api/v1/agent/ingest" 2>/dev/null || echo "000")

if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "204" ]]; then
  echo "[xamops] disk report sent successfully (HTTP ${HTTP_CODE})"
else
  echo "[xamops] WARNING: disk report failed (HTTP ${HTTP_CODE})" >&2
fi
REPORTER

sudo chmod +x "$REPORTER_SCRIPT"

# ── Install systemd service ───────────────────────────────────────────────────
info "Installing systemd service..."
sudo tee /etc/systemd/system/xamops-disk-agent.service > /dev/null <<'UNIT'
[Unit]
Description=XamOps Disk Agent (Azure)
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
EnvironmentFile=/etc/xamops/azure-disk-agent.env
# Wait for Azure IMDS before starting — can take 20-30s after instance boot/restart
ExecStartPre=/bin/sh -c 'i=0; until curl -sf --max-time 3 -H "Metadata:true" "http://169.254.169.254/metadata/instance/compute/name?api-version=2021-02-01&format=text" >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 20 ] && exit 1; sleep 3; done'
ExecStart=/usr/local/bin/xamops-agent
Restart=always
RestartSec=15
User=root

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload

# ── Systemd preset — prevents cloud-init/preset-all from disabling the service ──
sudo mkdir -p /etc/systemd/system-preset
sudo tee /etc/systemd/system-preset/90-xamops-disk-agent.preset > /dev/null <<'PRESET'
enable xamops-disk-agent.service
PRESET

sudo systemctl enable xamops-disk-agent
sudo systemctl restart xamops-disk-agent

# ── Cron fallback — ensures agent starts on boot even if systemd enable is lost ──
CRON_LINE_SVC="@reboot root sleep 30 && systemctl is-active --quiet xamops-disk-agent || systemctl start xamops-disk-agent"
echo "$CRON_LINE_SVC" | sudo tee /etc/cron.d/xamops-disk-agent-watchdog > /dev/null
sudo chmod 644 /etc/cron.d/xamops-disk-agent-watchdog

# ── Install cron job for disk usage reporting (every 5 minutes) ───────────────
info "Installing cron job for disk usage reporting..."
CRON_ENTRY="*/5 * * * * root /usr/local/bin/xamops-azure-disk-report.sh >> /var/log/xamops-disk-report.log 2>&1"
CRON_FILE=/etc/cron.d/xamops-azure-disk-report

sudo tee "$CRON_FILE" > /dev/null <<EOF
# XamOps Azure disk usage reporter — runs every 5 minutes
${CRON_ENTRY}
EOF
sudo chmod 644 "$CRON_FILE"

# Run once immediately so data appears in XamOps without waiting
info "Running initial disk report..."
/usr/local/bin/xamops-azure-disk-report.sh || warn "Initial report failed — will retry on next cron cycle"

# ── Done ──────────────────────────────────────────────────────────────────────
info "Done! Azure Disk agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-disk-agent"
echo "  Logs   : sudo journalctl -u xamops-disk-agent -f"
echo "  Report : sudo tail -f /var/log/xamops-disk-report.log"
echo ""
echo "  Disk usage will appear in XamOps within 1-2 minutes."
echo "  Refresh the Azure Disk Rightsizing page to see SHRINK recommendations."
echo ""
