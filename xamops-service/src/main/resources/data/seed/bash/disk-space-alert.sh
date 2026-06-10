#!/usr/bin/env bash
#
# disk-space-alert.sh
# Checks all mounted filesystems and sends an email alert for any that exceed
# a usage threshold. Designed to be run from cron.
#
# Parameters:
#   {{THRESHOLD_PERCENT}}  - Usage percentage that triggers an alert (e.g. 85)
#   {{ALERT_EMAIL}}        - Email address to notify (e.g. ops@example.com)
#
set -euo pipefail

THRESHOLD_PERCENT="{{THRESHOLD_PERCENT}}"
ALERT_EMAIL="{{ALERT_EMAIL}}"
HOSTNAME_FQDN="$(hostname -f 2>/dev/null || hostname)"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ "${THRESHOLD_PERCENT}" =~ ^[0-9]+$ ]] || die "THRESHOLD_PERCENT must be numeric."
(( THRESHOLD_PERCENT >= 1 && THRESHOLD_PERCENT <= 99 )) || die "THRESHOLD_PERCENT must be 1-99."
[[ "${ALERT_EMAIL}" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || die "ALERT_EMAIL is not a valid address."

# --- Check ------------------------------------------------------------------
alerts=""
while read -r fs size used avail pct mount; do
  pct_num="${pct%\%}"
  if (( pct_num >= THRESHOLD_PERCENT )); then
    alerts+="  ${mount} (${fs}) is at ${pct} (used ${used} of ${size})"$'\n'
  fi
done < <(df -P -h --local 2>/dev/null | awk 'NR>1 {print $1, $2, $3, $4, $5, $6}')

if [[ -z "${alerts}" ]]; then
  log "All filesystems below ${THRESHOLD_PERCENT}%. No alert sent."
  exit 0
fi

subject="[DISK ALERT] ${HOSTNAME_FQDN}: filesystem(s) over ${THRESHOLD_PERCENT}%"
body="Disk usage alert on ${HOSTNAME_FQDN} at $(date):"$'\n\n'"${alerts}"

log "Threshold exceeded; sending alert to ${ALERT_EMAIL}."
if command -v mail >/dev/null 2>&1; then
  printf '%s\n' "${body}" | mail -s "${subject}" "${ALERT_EMAIL}"
else
  log "WARNING: 'mail' command not found. Alert details:"
  printf '%s\n' "${body}"
fi
