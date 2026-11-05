#!/usr/bin/env bash
# PHASE 16 — concurrency / double-writer races.
# The agent is leader-elected (single actor); drain budget capacity=1. Attack the single-writer
# invariant: rapidly kill/flap the leader while the rebalancer/consolidation are active, and sample
# at high frequency for ANY moment of (a) two pods both reporting is_leader=1 (split-brain), OR
# (b) drain_budget_inflight > 1 (two concurrent drains), OR (c) duplicate executed consolidation runs.
# Also race two policy writers (two concurrent PUTs) to see if the backend serializes them.
# No chaos object left behind; agent Deployment self-heals the killed pods.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

BASE=https://uat.xamops.com; CL=xamops-test-1; ACCTID=102227938259
SRC=/Users/apple/.config/xamops/uat-session
TMP=$(mktemp /tmp/uat-sess.XXXXXX); chmod 600 "$TMP"
SESSION_VAL=$(grep -oE 'SESSION=[A-Za-z0-9+/=_-]+' "$SRC" | head -1 | cut -d= -f2-)
printf 'SESSION=%s' "$SESSION_VAL" > "$TMP"; COOKIE="Cookie: $(cat "$TMP")"

cleanup() {
  echo "=== [phase16 trap] cleanup ==="
  rm -f "$TMP" 2>/dev/null
  pkill -f "port-forward.*1909" 2>/dev/null
  echo "=== [phase16 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase16"; exit 1; }

# sample ALL automation replicas' /metrics for leader + inflight. Returns worst-case counts.
MAXLEADERS=0; MAXINFLIGHT=0; SPLIT_SEEN=0
sample_all() {
  local pods; pods=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null)
  local leaders=0 inflight=0 port=19200 holders=""
  for p in $pods; do
    pkill -f "port-forward.*${port}:9090" 2>/dev/null; sleep 0.3
    k -n xamops-system port-forward "$p" ${port}:9090 >/tmp/pf-$port.log 2>&1 & sleep 2.5
    local m; m=$(curl -s localhost:${port}/metrics 2>/dev/null)
    pkill -f "port-forward.*${port}:9090" 2>/dev/null
    local isL; isL=$(echo "$m" | grep -E '^xamops_leader\{' | awk '{print $2}' | head -1)
    local inf; inf=$(echo "$m" | grep -E '^xamops_drain_budget_inflight' | awk '{print $2}' | head -1)
    local holder; holder=$(echo "$m" | grep -E '^xamops_leader\{' | sed -E 's/.*is_leader="([^"]+)".*/\1/' | head -1)
    [ "${isL:-0}" = "1" ] && { leaders=$((leaders+1)); holders="$holders ${holder}"; }
    [ -n "$inf" ] && [ "${inf%.*}" -gt "$inflight" ] 2>/dev/null && inflight=${inf%.*}
  done
  [ "$leaders" -gt "$MAXLEADERS" ] && MAXLEADERS=$leaders
  [ "$inflight" -gt "$MAXINFLIGHT" ] && MAXINFLIGHT=$inflight
  # split-brain = 2+ leaders with DIFFERENT holders
  local uniq; uniq=$(echo $holders | tr ' ' '\n' | sort -u | grep -c .)
  [ "$leaders" -ge 2 ] && [ "$uniq" -ge 2 ] && SPLIT_SEEN=1
  echo "  leaders=$leaders (holders:$holders uniq=$uniq) max-inflight=$inflight"
}

echo "=== BEFORE ==="
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null
sample_all

echo "=== (c) race TWO concurrent policy PUTs (double-writer at the API) ==="
# fire two conflicting consolidation PUTs simultaneously; backend must serialize, no corruption
curl -s -o /tmp/w1 -w 'writerA HTTP %{http_code}\n' -X PUT -H "$COOKIE" -H "Content-Type: application/json" \
  --data '{"enabled":true,"maxParallel":1}' "$BASE/api/eks/autoscaling/$CL/consolidation?accountId=$ACCTID" &
curl -s -o /tmp/w2 -w 'writerB HTTP %{http_code}\n' -X PUT -H "$COOKIE" -H "Content-Type: application/json" \
  --data '{"enabled":true,"maxParallel":1}' "$BASE/api/eks/autoscaling/$CL/consolidation?accountId=$ACCTID" &
wait
echo "  writerA: $(head -c 120 /tmp/w1 2>/dev/null)"; echo "  writerB: $(head -c 120 /tmp/w2 2>/dev/null)"
rm -f /tmp/w1 /tmp/w2

echo "=== (a)(b) leader-flap storm: kill leader repeatedly, trigger rebalance, sample for split-brain / inflight>1 ==="
for round in 1 2 3 4 5; do
  echo "--- round $round ---"
  # trigger a rebalance each round to keep the acting path hot
  curl -s -o /dev/null -X POST -H "$COOKIE" "$BASE/api/eks/autoscaling/$CL/rebalance?accountId=$ACCTID" 2>/dev/null
  # find and kill current leader
  LPOD=""
  for p in $(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null); do
    pkill -f "port-forward.*19201:9090" 2>/dev/null; sleep 0.3
    k -n xamops-system port-forward "$p" 19201:9090 >/tmp/pf.log 2>&1 & sleep 2.5
    il=$(curl -s localhost:19201/metrics 2>/dev/null | grep -E '^xamops_leader\{' | awk '{print $2}' | head -1)
    pkill -f "port-forward.*19201:9090" 2>/dev/null
    [ "${il:-0}" = "1" ] && LPOD=${p#pod/}
  done
  echo "  current leader = ${LPOD:-unknown} -> deleting"
  [ -n "$LPOD" ] && k -n xamops-system delete pod "$LPOD" --grace-period=1 --wait=false >/dev/null 2>&1
  # sample DURING the failover window (when split-brain would appear)
  for s in 1 2 3; do sleep 3; sample_all; done
done

echo "=== AFTER: settle + final invariant check ==="
sleep 20
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null
sample_all
k -n xamops-system get lease -o jsonpath='{range .items[*]}{.metadata.name}={.spec.holderIdentity}{"\n"}{end}' 2>/dev/null | grep -i xamops
echo "=== DB intact (no double-drain hit a DB) ==="
k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-' | sed 's/^/  DB pods Running: /'
k -n xamops-db-fixtures get events --field-selector reason=Evicted 2>/dev/null | tail -2 || echo "  no evict events"

echo ""
echo "=== RACE VERDICT ==="
echo "  MAX simultaneous leaders observed = $MAXLEADERS (want 1)"
echo "  split-brain (2 DIFFERENT holders at once) seen = $SPLIT_SEEN (want 0)"
echo "  MAX drain_budget_inflight observed = $MAXINFLIGHT (want <=1)"
echo "PHASE16-DONE"
