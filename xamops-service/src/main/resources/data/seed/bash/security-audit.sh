#!/usr/bin/env bash
#
# security-audit.sh
# Runs a lightweight read-only security audit (users, listening ports, sudoers,
# world-writable files, failed logins) and writes a report.
#
# Parameters:
#   {{REPORT_PATH}}  - File to write the audit report to (e.g. /var/log/security-audit.txt)
#
set -euo pipefail

REPORT_PATH="{{REPORT_PATH}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${EUID}" -eq 0 ]] || die "This script must be run as root for a complete audit."
[[ -n "${REPORT_PATH}" ]] || die "REPORT_PATH must be set."
mkdir -p "$(dirname "${REPORT_PATH}")"

section() { printf '\n===== %s =====\n' "$1" >> "${REPORT_PATH}"; }

log "Writing security audit to ${REPORT_PATH}..."
: > "${REPORT_PATH}"
{
  echo "Security Audit Report"
  echo "Host: $(hostname -f 2>/dev/null || hostname)"
  echo "Generated: $(date)"
} >> "${REPORT_PATH}"

section "Users with login shells"
grep -E ':/(bin/bash|bin/sh|bin/zsh)$' /etc/passwd >> "${REPORT_PATH}" || true

section "Users with UID 0 (root-equivalent)"
awk -F: '$3 == 0 {print $1}' /etc/passwd >> "${REPORT_PATH}"

section "Sudoers entries"
grep -rhE '^[^#].*ALL' /etc/sudoers /etc/sudoers.d/ 2>/dev/null >> "${REPORT_PATH}" || true

section "Listening network ports"
(ss -tulpn 2>/dev/null || netstat -tulpn 2>/dev/null) >> "${REPORT_PATH}" || true

section "World-writable files in /etc"
find /etc -xdev -type f -perm -0002 2>/dev/null >> "${REPORT_PATH}" || true

section "Recent failed login attempts"
(lastb -n 20 2>/dev/null || echo "lastb unavailable") >> "${REPORT_PATH}"

section "Pending package security updates"
(apt-get -s upgrade 2>/dev/null | grep -i security || echo "none / not Debian-based") >> "${REPORT_PATH}"

log "Audit complete. Review ${REPORT_PATH}."
