#!/bin/bash
# Removes the XamPulse agent from this Linux host and leaves the disabled marker so that the
# 30-minute SSM association (or the curl one-liner) cannot silently reinstall it while the server
# says SKIP. Re-running the installer after XamOps answers INSTALL removes the marker.
set -eu

STATE_DIR="/var/lib/xampulse"
INSTALL_DIR="/opt/xampulse"
UNIT="/etc/systemd/system/xampulse-agent.service"

if [ "$(id -u)" != "0" ]; then
  echo "xampulse-uninstall: must run as root"
  exit 1
fi

systemctl stop xampulse-agent.service >/dev/null 2>&1 || true
systemctl disable xampulse-agent.service >/dev/null 2>&1 || true
rm -f "${UNIT}"
systemctl daemon-reload >/dev/null 2>&1 || true
rm -rf "${INSTALL_DIR}"
mkdir -p "${STATE_DIR}"
rm -f "${STATE_DIR}"/agent-token.json "${STATE_DIR}"/installed.sha256
: > "${STATE_DIR}/disabled"
echo "xampulse-uninstall: removed; collection stays off until the server answers INSTALL"
