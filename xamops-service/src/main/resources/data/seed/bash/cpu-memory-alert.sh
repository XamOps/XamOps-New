#!/usr/bin/env bash
#
# cpu-memory-alert.sh
# Checks current CPU and memory utilization and posts a JSON alert to a webhook
# (e.g. Slack/Teams/Discord incoming webhook) when thresholds are breached.
#
# Parameters:
#   {{CPU_THRESHOLD}}  - CPU usage percent that triggers an alert (e.g. 90)
#   {{MEM_THRESHOLD}}  - Memory usage percent that triggers an alert (e.g. 90)
#   {{WEBHOOK_URL}}    - Webhook URL to POST the alert to
#
set -euo pipefail

CPU_THRESHOLD="{{CPU_THRESHOLD}}"
MEM_THRESHOLD="{{MEM_THRESHOLD}}"
WEBHOOK_URL="{{WEBHOOK_URL}}"
HOST="$(hostname -f 2>/dev/null || hostname)"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${CPU_THRESHOLD}" =~ ^[0-9]+$ ]] || die "CPU_THRESHOLD must be numeric."
[[ "${MEM_THRESHOLD}" =~ ^[0-9]+$ ]] || die "MEM_THRESHOLD must be numeric."
[[ "${WEBHOOK_URL}" =~ ^https?:// ]] || die "WEBHOOK_URL must be a valid URL."
command -v curl >/dev/null 2>&1 || die "curl is required."

# --- Sample -----------------------------------------------------------------
cpu_usage="$(top -bn1 | awk '/Cpu\(s\)/ {printf "%.0f", 100 - $8}')"
mem_usage="$(free | awk '/Mem:/ {printf "%.0f", $3/$2*100}')"
log "Current CPU=${cpu_usage}% MEM=${mem_usage}%"

breaches=()
(( cpu_usage >= CPU_THRESHOLD )) && breaches+=("CPU at ${cpu_usage}% (>= ${CPU_THRESHOLD}%)")
(( mem_usage >= MEM_THRESHOLD )) && breaches+=("Memory at ${mem_usage}% (>= ${MEM_THRESHOLD}%)")

if [[ ${#breaches[@]} -eq 0 ]]; then
  log "Within thresholds. No alert sent."
  exit 0
fi

message="Resource alert on ${HOST}: $(IFS='; '; echo "${breaches[*]}")"
log "Posting alert to webhook..."
curl -fsS -X POST -H 'Content-Type: application/json' \
  --data "$(printf '{"text":"%s"}' "${message}")" \
  "${WEBHOOK_URL}" >/dev/null

log "Alert delivered: ${message}"
