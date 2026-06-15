#!/usr/bin/env bash
#
# collect-system-metrics.sh
# Collects CPU, memory, disk, and load metrics and appends them as a single
# line to an output file (CSV-friendly) for ingestion by monitoring tooling.
#
# Parameters:
#   {{METRICS_OUTPUT}}    - Path to the metrics output file (e.g. /var/log/metrics.csv)
#   {{INTERVAL_SECONDS}}  - Sampling interval in seconds; 0 = run once and exit
#
set -euo pipefail

METRICS_OUTPUT="{{METRICS_OUTPUT}}"
INTERVAL_SECONDS="{{INTERVAL_SECONDS}}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${METRICS_OUTPUT}" ]] || die "METRICS_OUTPUT must be set."
[[ "${INTERVAL_SECONDS}" =~ ^[0-9]+$ ]] || die "INTERVAL_SECONDS must be a non-negative integer."

mkdir -p "$(dirname "${METRICS_OUTPUT}")"
if [[ ! -f "${METRICS_OUTPUT}" ]]; then
  echo "timestamp,cpu_pct,mem_used_pct,disk_root_pct,load_1m" > "${METRICS_OUTPUT}"
fi

sample() {
  local ts cpu mem disk load
  ts="$(date '+%Y-%m-%dT%H:%M:%S%z')"
  cpu="$(top -bn1 | awk '/Cpu\(s\)/ {printf "%.1f", 100 - $8}')"
  mem="$(free | awk '/Mem:/ {printf "%.1f", $3/$2*100}')"
  disk="$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')"
  load="$(awk '{print $1}' /proc/loadavg)"
  echo "${ts},${cpu},${mem},${disk},${load}" >> "${METRICS_OUTPUT}"
  log "Sampled: cpu=${cpu}% mem=${mem}% disk=${disk}% load=${load}"
}

if [[ "${INTERVAL_SECONDS}" -eq 0 ]]; then
  sample
else
  log "Sampling every ${INTERVAL_SECONDS}s. Press Ctrl-C to stop."
  while true; do
    sample
    sleep "${INTERVAL_SECONDS}"
  done
fi
