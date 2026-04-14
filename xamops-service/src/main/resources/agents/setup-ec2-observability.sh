#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# XamOps Observability — EC2 / Linux VM Setup Script
# Works on: Amazon Linux 2/2023, RHEL, CentOS, Ubuntu, Debian
#
# Usage (all env vars required — no hardcoded defaults):
#   sudo XAMOPS_OTEL_ENDPOINT=https://live-otel.xamops.com \
#        XAMOPS_ORG_TOKEN=<org_token> \
#        XAMOPS_AUTH_TOKEN=<base64_user:pass> \
#        bash setup-ec2-observability.sh
#
#   Generate XAMOPS_AUTH_TOKEN:
#     echo -n "admin@xamops.local:YourPassword" | base64
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

# ── Configuration (must be provided via env vars — no defaults) ─────────────
OTEL_VERSION="${OTEL_VERSION:-0.103.0}"
OTEL_CONFIG_DIR="/etc/otelcol-contrib"

# Required — script will exit if not set
XAMOPS_OTEL_ENDPOINT="${XAMOPS_OTEL_ENDPOINT:-}"
XAMOPS_ORG_TOKEN="${XAMOPS_ORG_TOKEN:-}"
XAMOPS_AUTH_TOKEN="${XAMOPS_AUTH_TOKEN:-}"
OTEL_SERVICE_FILE="/usr/lib/systemd/system/otelcol-contrib.service"

# ── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Validate required env vars ───────────────────────────────────────────────
check_required_vars() {
  local missing=0
  if [ -z "$XAMOPS_OTEL_ENDPOINT" ]; then
    echo "  XAMOPS_OTEL_ENDPOINT  e.g. https://live-otel.xamops.com"
    missing=1
  fi
  if [ -z "$XAMOPS_ORG_TOKEN" ]; then
    echo "  XAMOPS_ORG_TOKEN      your OpenObserve org token"
    missing=1
  fi
  if [ -z "$XAMOPS_AUTH_TOKEN" ]; then
    echo "  XAMOPS_AUTH_TOKEN     base64 of user:password"
    echo "    Generate: echo -n 'user@example.com:password' | base64"
    missing=1
  fi
  if [ $missing -eq 1 ]; then
    echo ""
    echo "Usage:"
    echo "  sudo XAMOPS_OTEL_ENDPOINT=https://live-otel.xamops.com \\"
    echo "       XAMOPS_ORG_TOKEN=<token> \\"
    echo "       XAMOPS_AUTH_TOKEN=<base64> \\"
    echo "       bash $0"
    exit 1
  fi
}

# ── Root check ───────────────────────────────────────────────────────────────
[[ $EUID -ne 0 ]] && error "Run as root: sudo bash $0"

# ── Detect OS and package manager ────────────────────────────────────────────
detect_os() {
  if command -v rpm &>/dev/null && [ -f /etc/system-release ]; then
    PKG_TYPE="rpm"
    info "Detected RPM-based OS (Amazon Linux / RHEL / CentOS)"
  elif command -v dpkg &>/dev/null; then
    PKG_TYPE="deb"
    info "Detected DEB-based OS (Ubuntu / Debian)"
  else
    error "Unsupported OS — neither rpm nor dpkg found"
  fi
}

# ── Install OTel Collector Contrib ───────────────────────────────────────────
install_collector() {
  if command -v otelcol-contrib &>/dev/null; then
    INSTALLED_VER=$(otelcol-contrib --version 2>&1 | grep -oP '\d+\.\d+\.\d+' | head -1)
    if [ "$INSTALLED_VER" = "$OTEL_VERSION" ]; then
      success "otelcol-contrib $OTEL_VERSION already installed"
      return
    fi
    warn "Upgrading otelcol-contrib $INSTALLED_VER → $OTEL_VERSION"
  else
    info "Installing otelcol-contrib $OTEL_VERSION..."
  fi

  local base_url="https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v${OTEL_VERSION}"
  local arch
  arch=$(uname -m)
  case "$arch" in
    x86_64)  arch="amd64" ;;
    aarch64) arch="arm64" ;;
    *)       error "Unsupported architecture: $arch" ;;
  esac

  if [ "$PKG_TYPE" = "rpm" ]; then
    local pkg="otelcol-contrib_${OTEL_VERSION}_linux_${arch}.rpm"
    curl -sLO "${base_url}/${pkg}"
    rpm -Uvh --force "$pkg"
    rm -f "$pkg"
  else
    local pkg="otelcol-contrib_${OTEL_VERSION}_linux_${arch}.deb"
    curl -sLO "${base_url}/${pkg}"
    dpkg -i "$pkg"
    rm -f "$pkg"
  fi
  success "otelcol-contrib installed"
}

# ── Detect Docker ─────────────────────────────────────────────────────────────
detect_docker() {
  if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
    DOCKER_INSTALLED=true
    success "Docker detected — container log access granted"
  else
    DOCKER_INSTALLED=false
    warn "Docker not detected — skipping container log collection"
  fi
}

# ── Detect available log files ────────────────────────────────────────────────
detect_log_files() {
  SYSLOG_FILES=()
  AUTH_FILES=()
  NGINX_FILES=()

  # Syslog
  [ -f /var/log/messages ] && SYSLOG_FILES+=("/var/log/messages")
  [ -f /var/log/syslog ]   && SYSLOG_FILES+=("/var/log/syslog")

  # Auth
  [ -f /var/log/secure ]   && AUTH_FILES+=("/var/log/secure")
  [ -f /var/log/auth.log ] && AUTH_FILES+=("/var/log/auth.log")

  # Nginx
  [ -f /var/log/nginx/access.log ] && NGINX_FILES+=("/var/log/nginx/access.log")
  [ -f /var/log/nginx/error.log ]  && NGINX_FILES+=("/var/log/nginx/error.log")

  info "Found log files: syslog=${#SYSLOG_FILES[@]} auth=${#AUTH_FILES[@]} nginx=${#NGINX_FILES[@]}"
}

# ── Build include list for a receiver ────────────────────────────────────────
yaml_include_list() {
  local files=("$@")
  for f in "${files[@]}"; do
    echo "      - $f"
  done
}

# ── Write OTel config ─────────────────────────────────────────────────────────
write_config() {
  mkdir -p "$OTEL_CONFIG_DIR"

  # Build receiver list and pipeline receiver list dynamically
  local log_receivers=()
  local receiver_blocks=""

  # System logs receiver (only if files exist)
  if [ ${#SYSLOG_FILES[@]} -gt 0 ]; then
    log_receivers+=("filelog/system")
    local syslog_includes
    syslog_includes=$(yaml_include_list "${SYSLOG_FILES[@]}")
    receiver_blocks+="
  filelog/system:
    include:
${syslog_includes}
    start_at: end
    multiline:
      line_start_pattern: '^\\w{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}'
    operators:
      - type: regex_parser
        regex: '^(?P<timestamp>\\w{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(?P<host>\\S+)\\s+(?P<process>[^\\[]+)(?:\\[(?P<pid>\\d+)\\])?\\s*:\\s*(?P<message>.*)$'
        timestamp:
          parse_from: attributes.timestamp
          layout_type: gotime
          layout: 'Jan _2 15:04:05'
        on_error: send
      - type: severity_parser
        parse_from: attributes.message
        on_error: send
      - type: add
        field: attributes.source
        value: syslog
"
  fi

  # Auth logs receiver (only if files exist)
  if [ ${#AUTH_FILES[@]} -gt 0 ]; then
    log_receivers+=("filelog/auth")
    local auth_includes
    auth_includes=$(yaml_include_list "${AUTH_FILES[@]}")
    receiver_blocks+="
  filelog/auth:
    include:
${auth_includes}
    start_at: end
    operators:
      - type: regex_parser
        regex: '^(?P<timestamp>\\w{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(?P<host>\\S+)\\s+(?P<process>[^\\[]+)(?:\\[(?P<pid>\\d+)\\])?\\s*:\\s*(?P<message>.*)$'
        timestamp:
          parse_from: attributes.timestamp
          layout_type: gotime
          layout: 'Jan _2 15:04:05'
        on_error: send
      - type: add
        field: attributes.source
        value: auth
      - type: add
        field: attributes.log.type
        value: security
"
  fi

  # Nginx logs receiver (only if files exist)
  if [ ${#NGINX_FILES[@]} -gt 0 ]; then
    log_receivers+=("filelog/nginx")
    local nginx_includes
    nginx_includes=$(yaml_include_list "${NGINX_FILES[@]}")
    receiver_blocks+="
  filelog/nginx:
    include:
${nginx_includes}
    start_at: end
    operators:
      - type: regex_parser
        regex: '^(?P<client_ip>\\S+)\\s+\\S+\\s+\\S+\\s+\\[(?P<timestamp>[^\\]]+)\\]\\s+\"(?P<method>\\w+)\\s+(?P<path>\\S+)\\s+\\S+\"\\s+(?P<status>\\d{3})\\s+(?P<bytes>\\d+)'
        timestamp:
          parse_from: attributes.timestamp
          layout_type: gotime
          layout: '02/Jan/2006:15:04:05 -0700'
        on_error: send
      - type: add
        field: attributes.source
        value: nginx
      - type: add
        field: attributes.service.name
        value: nginx
"
  fi

  # Docker logs receiver (only if Docker is installed)
  if [ "$DOCKER_INSTALLED" = true ]; then
    log_receivers+=("filelog/docker")
    receiver_blocks+="
  filelog/docker:
    include:
      - /var/lib/docker/containers/*/*.log
    include_file_path: true
    include_file_name: false
    start_at: end
    operators:
      - type: json_parser
        timestamp:
          parse_from: attributes.time
          layout_type: gotime
          layout: '2006-01-02T15:04:05.999999999Z07:00'
        on_error: send
      - type: move
        from: attributes.log
        to: body
      - type: add
        field: attributes.source
        value: docker
      - type: severity_parser
        parse_from: body
        preset: default
        on_error: send
"
  fi

  # Build pipeline receivers string
  local pipeline_receivers
  if [ ${#log_receivers[@]} -gt 0 ]; then
    pipeline_receivers=$(printf '%s, ' "${log_receivers[@]}")
    pipeline_receivers="[${pipeline_receivers%, }]"
  else
    pipeline_receivers="[]"
    warn "No log sources detected — logs pipeline will be empty"
  fi

  # Write final config
  cat > "$OTEL_CONFIG_DIR/config.yaml" << EOF
# ════════════════════════════════════════════════════════════════════════════
# XamOps OTel Collector Config — auto-generated by setup-ec2-observability.sh
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# ════════════════════════════════════════════════════════════════════════════

receivers:
  # ── Log Receivers (auto-detected) ──────────────────────────────────────────
${receiver_blocks}
  # ── Host Metrics ────────────────────────────────────────────────────────────
  hostmetrics:
    collection_interval: 30s
    scrapers:
      cpu:
        metrics:
          system.cpu.utilization:
            enabled: true
      memory:
        metrics:
          system.memory.utilization:
            enabled: true
      disk: {}
      network: {}
      load: {}
      filesystem:
        exclude_mount_points:
          mount_points: [/proc, /sys, /dev, /run, /snap]
          match_type: strict
      process:
        mute_process_name_error: true
        mute_process_exe_error: true
        mute_process_io_error: true
      paging: {}

  # ── OTLP Receiver — application traces ─────────────────────────────────────
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 5s
    limit_mib: 512
    spike_limit_mib: 128

  resourcedetection:
    detectors: [ec2, system]
    timeout: 5s
    override: false

  resource:
    attributes:
      - key: service.name
        value: ec2-host
        action: upsert
      - key: source
        value: ec2
        action: upsert
      - key: deployment.environment
        value: prod
        action: upsert
      - key: service.instance.id
        from_attribute: host.id
        action: upsert

  filter/healthcheck:
    logs:
      exclude:
        match_type: regexp
        bodies:
          - '.*GET /health.*'
          - '.*GET /healthz.*'
          - '.*GET /readyz.*'
          - '.*GET /livez.*'
          - '.*ELB-HealthChecker.*'
          - '.*kube-probe.*'

  batch:
    timeout: 5s
    send_batch_size: 512
    send_batch_max_size: 1024

exporters:
  otlphttp:
    endpoint: "${XAMOPS_OTEL_ENDPOINT}/api/${XAMOPS_ORG_TOKEN}"
    headers:
      Authorization: "Basic ${XAMOPS_AUTH_TOKEN}"
      ngrok-skip-browser-warning: "true"
    compression: gzip
    retry_on_failure:
      enabled: true
      initial_interval: 5s
      max_interval: 30s
      max_elapsed_time: 300s
    sending_queue:
      enabled: true
      queue_size: 1000
      num_consumers: 4

extensions:
  health_check:
    endpoint: 127.0.0.1:13133

service:
  telemetry:
    metrics:
      address: 127.0.0.1:8888
  extensions: [health_check]
  pipelines:
    logs:
      receivers: ${pipeline_receivers}
      processors: [memory_limiter, resourcedetection, resource, filter/healthcheck, batch]
      exporters: [otlphttp]
    metrics:
      receivers: [hostmetrics]
      processors: [memory_limiter, resourcedetection, resource, batch]
      exporters: [otlphttp]
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, resource, batch]
      exporters: [otlphttp]
EOF

  success "Config written to $OTEL_CONFIG_DIR/config.yaml"
}

# ── Run as root (needed for Docker log access) ────────────────────────────────
patch_service_user() {
  if grep -q "^User=" "$OTEL_SERVICE_FILE" 2>/dev/null; then
    sed -i 's/^User=.*/User=root/' "$OTEL_SERVICE_FILE"
    success "Service set to run as root (required for Docker log access)"
  fi
  # Also remove Group restriction if present
  sed -i '/^Group=otelcol/d' "$OTEL_SERVICE_FILE" 2>/dev/null || true
}

# ── Validate config ────────────────────────────────────────────────────────────
validate_config() {
  info "Validating config..."
  if otelcol-contrib validate --config="$OTEL_CONFIG_DIR/config.yaml" 2>&1; then
    success "Config is valid"
  else
    error "Config validation failed — check $OTEL_CONFIG_DIR/config.yaml"
  fi
}

# ── Enable and start service ───────────────────────────────────────────────────
start_service() {
  systemctl daemon-reload
  systemctl enable otelcol-contrib
  systemctl restart otelcol-contrib
  sleep 3

  if systemctl is-active --quiet otelcol-contrib; then
    success "otelcol-contrib is running"
  else
    systemctl status otelcol-contrib --no-pager
    error "Service failed to start — check logs: journalctl -u otelcol-contrib -n 50"
  fi
}

# ── Verify data is flowing ─────────────────────────────────────────────────────
verify() {
  info "Waiting 20s for first export cycle..."
  sleep 20

  local metrics_sent
  metrics_sent=$(curl -s http://127.0.0.1:8888/metrics | grep 'otelcol_exporter_sent_metric_points' | grep -v '^#' | awk '{print $2}' || echo "0")

  local logs_accepted
  logs_accepted=$(curl -s http://127.0.0.1:8888/metrics | grep 'otelcol_receiver_accepted_log_records' | grep -v '^#' | awk '{print $2}' || echo "0")

  echo ""
  echo "════════════════════════════════════════"
  echo "  XamOps Observability Setup Complete"
  echo "════════════════════════════════════════"
  echo "  Metrics sent:    ${metrics_sent:-0}"
  echo "  Logs accepted:   ${logs_accepted:-0}"
  echo "  Endpoint:        ${XAMOPS_OTEL_ENDPOINT}"
  echo "  Log sources:     ${log_receivers[*]:-none}"
  echo "════════════════════════════════════════"

  if [ "${metrics_sent:-0}" = "0" ]; then
    warn "No metrics sent yet — may still be initializing. Check: journalctl -u otelcol-contrib -f"
  else
    success "Data is flowing to OpenObserve!"
  fi
}

# ── Main ───────────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo "════════════════════════════════════════"
  echo "  XamOps Observability Setup"
  echo "  OTel Collector v${OTEL_VERSION}"
  echo "════════════════════════════════════════"
  echo ""

  check_required_vars
  detect_os
  install_collector
  detect_docker
  detect_log_files
  write_config
  patch_service_user
  validate_config
  start_service
  verify
}

main "$@"
