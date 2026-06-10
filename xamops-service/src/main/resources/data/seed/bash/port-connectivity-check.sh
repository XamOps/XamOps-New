#!/usr/bin/env bash
#
# port-connectivity-check.sh
# Checks TCP connectivity from this host to a target host across a set of ports.
# Exits non-zero if any port is unreachable.
#
# Parameters:
#   {{TARGET_HOST}}  - Hostname or IP to test (e.g. db.internal)
#   {{PORTS}}        - Space-separated TCP ports to check (e.g. "5432 6379 443")
#
set -euo pipefail

TARGET_HOST="{{TARGET_HOST}}"
PORTS="{{PORTS}}"
TIMEOUT=5

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Validation -------------------------------------------------------------
[[ -n "${TARGET_HOST}" ]] || die "TARGET_HOST must be set."
[[ -n "${PORTS// /}" ]] || die "PORTS must contain at least one port."

check_port() {
  local host="$1" port="$2"
  if command -v nc >/dev/null 2>&1; then
    nc -z -w "${TIMEOUT}" "${host}" "${port}" >/dev/null 2>&1
  else
    # Pure-bash fallback using /dev/tcp.
    timeout "${TIMEOUT}" bash -c ">/dev/tcp/${host}/${port}" >/dev/null 2>&1
  fi
}

failures=0
for port in ${PORTS}; do
  [[ "${port}" =~ ^[0-9]+$ ]] || die "Invalid port '${port}'."
  if check_port "${TARGET_HOST}" "${port}"; then
    log "OK    ${TARGET_HOST}:${port} reachable"
  else
    log "FAIL  ${TARGET_HOST}:${port} unreachable"
    failures=$(( failures + 1 ))
  fi
done

if (( failures > 0 )); then
  die "${failures} port(s) unreachable on ${TARGET_HOST}."
fi
log "All ports reachable on ${TARGET_HOST}."
