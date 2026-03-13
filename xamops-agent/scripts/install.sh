#!/usr/bin/env bash
# XamOps DB Agent — EC2 installer
#
# Values explained:
#   CLOUD_ACCOUNT_ID  = your AWS account ID (12-digit number visible in AWS Console top-right)
#                       must match the account you connected in XamOps dashboard
#   CLUSTER_NAME      = any label you choose to identify this agent in XamOps (e.g. "prod-rds")
#   DB_DSN_0          = your RDS connection string — XamOps never stores this, it stays on your EC2
#                       MySQL:    xamops_user:pass@tcp(yourdb.cluster.us-east-1.rds.amazonaws.com:3306)/dbname
#                       Postgres: postgres://xamops_user:pass@yourdb.us-east-1.rds.amazonaws.com:5432/dbname?sslmode=require
#
# Usage (single line):
#
#   CLOUD_ACCOUNT_ID="123456789012" \
#   CLUSTER_NAME="prod-rds" \
#   DB_DSN_0="xamops_user:pass@tcp(yourdb.cluster.us-east-1.rds.amazonaws.com:3306)/mydb" \
#   bash -c "$(curl -fsSL https://api.xamops.com/agent/install.sh)"
#
# Optional env vars:
#   DB_DSN_1, DB_DSN_2, ...   — additional DB instances
#   AGENT_VERSION             — pin a specific release (default: latest)
#   INSTALL_DIR               — where to put the binary (default: /usr/local/bin)
#   LOG_LEVEL                 — debug|info|warn (default: info)
#   COLLECT_INTERVAL          — seconds between collections (default: 300)
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Colours ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[xamops]${NC} $*"; }
warn()  { echo -e "${YELLOW}[xamops]${NC} $*"; }
error() { echo -e "${RED}[xamops]${NC} $*"; exit 1; }

# ── Baked-in XamOps connection (customers do NOT change these) ────────────────
XAMOPS_SERVER_URL="https://api.xamops.com"
XAMOPS_API_KEY="xamops_SecureAgentKey2024XamOps"

# ── Validate customer-provided vars ──────────────────────────────────────────
: "${CLOUD_ACCOUNT_ID:?   ERROR: CLOUD_ACCOUNT_ID is required (your 12-digit AWS account ID)}"
: "${CLUSTER_NAME:?       ERROR: CLUSTER_NAME is required (any label, e.g. prod-rds)}"

# At least one DSN must be provided
if [[ -z "${DB_DSN_0:-}" ]]; then
  error "DB_DSN_0 is required — your RDS connection string (e.g. user:pass@tcp(host:3306)/db)"
fi

# ── Defaults ─────────────────────────────────────────────────────────────────
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
AGENT_VERSION="${AGENT_VERSION:-latest}"
LOG_LEVEL="${LOG_LEVEL:-info}"
COLLECT_INTERVAL="${COLLECT_INTERVAL:-300}"
HEARTBEAT_INTERVAL="${HEARTBEAT_INTERVAL:-60}"

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

# ── Download binary ───────────────────────────────────────────────────────────
BINARY_NAME="xamops-agent_${OS}_${ARCH}"
DOWNLOAD_URL="${XAMOPS_SERVER_URL}/agent/download/${AGENT_VERSION}/${BINARY_NAME}"

info "Downloading XamOps agent ${AGENT_VERSION}..."
curl -fsSL "$DOWNLOAD_URL" -o /tmp/xamops-agent
chmod +x /tmp/xamops-agent
sudo mv /tmp/xamops-agent "${INSTALL_DIR}/xamops-agent"
info "Binary installed to ${INSTALL_DIR}/xamops-agent"

# ── Write env file (no credentials in systemd unit) ──────────────────────────
sudo mkdir -p /etc/xamops
ENV_FILE=/etc/xamops/agent.env

info "Writing config to ${ENV_FILE}"
sudo tee "$ENV_FILE" > /dev/null <<EOF
XAMOPS_SERVER_URL=${XAMOPS_SERVER_URL}
XAMOPS_API_KEY=${XAMOPS_API_KEY}
CLOUD_ACCOUNT_ID=${CLOUD_ACCOUNT_ID}
CLUSTER_NAME=${CLUSTER_NAME}
LOG_LEVEL=${LOG_LEVEL}
COLLECT_INTERVAL=${COLLECT_INTERVAL}
HEARTBEAT_INTERVAL=${HEARTBEAT_INTERVAL}

# Collector flags — DB-only mode
COLLECTOR_DB=true
COLLECTOR_TRIVY=false
COLLECTOR_KUBESCAPE=false
COLLECTOR_OPENCOST=false
COLLECTOR_KARPENTER=false
COLLECTOR_METRICS=false

# DB instances — add more as DB_DSN_1, DB_DSN_2, ...
DB_DSN_0=${DB_DSN_0}
EOF

# Append optional extra DSNs if provided
for i in $(seq 1 19); do
  var="DB_DSN_${i}"
  val="${!var:-}"
  if [[ -n "$val" ]]; then
    echo "DB_DSN_${i}=${val}" | sudo tee -a "$ENV_FILE" > /dev/null
  fi
done

# Lock down — contains credentials
sudo chmod 600 "$ENV_FILE"
sudo chown root:root "$ENV_FILE"

# ── Install systemd service ───────────────────────────────────────────────────
info "Installing systemd service..."
sudo tee /etc/systemd/system/xamops-agent.service > /dev/null <<'UNIT'
[Unit]
Description=XamOps DB Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/xamops/agent.env
ExecStart=/usr/local/bin/xamops-agent
Restart=always
RestartSec=30
User=nobody
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable xamops-agent
sudo systemctl restart xamops-agent

# ── Done ──────────────────────────────────────────────────────────────────────
info "Done! Agent is running."
echo ""
echo "  Status : sudo systemctl status xamops-agent"
echo "  Logs   : sudo journalctl -u xamops-agent -f"
echo "  Config : sudo cat /etc/xamops/agent.env"
echo ""
