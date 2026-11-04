#!/usr/bin/env bash
# PHASE 14 — multi-DB fail-closed protection (HEADLINE I15/I16) against EACH engine:
#   Postgres (CNPG primary pg-cnpg-2 + replica pg-cnpg-1 + emptyDir pg-unsafe-0), MySQL (mysql-0),
#   Mongo (mongo-0/1/2 RS), Redis (redis-0). Drive a FRESH LIVE rebalance via the REAL backend API
#   (POST …/rebalance) so the agent re-plans a drain over the DB nodes, then capture the per-DB
#   refusal reason and prove ZERO DB evictions for EACH engine. Also read the backend's own
#   evictor-candidates / node-fleet decisions. NEVER bypasses the API; NEVER hand-edits any DB.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

BASE=https://uat.xamops.com
CL=xamops-test-1
ACCTID=102227938259
DBNS=xamops-db-fixtures
SRC=/Users/apple/.config/xamops/uat-session
TMP=$(mktemp /tmp/uat-sess.XXXXXX); chmod 600 "$TMP"
SESSION_VAL=$(grep -oE 'SESSION=[A-Za-z0-9+/=_-]+' "$SRC" | head -1 | cut -d= -f2-)
[ -z "$SESSION_VAL" ] && SESSION_VAL=$(sed -E 's/.*SESSION=([A-Za-z0-9+/=_-]+).*/\1/' "$SRC" | head -1)
printf 'SESSION=%s' "$SESSION_VAL" > "$TMP"
COOKIE="Cookie: $(cat "$TMP")"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)

cleanup() {
  echo "=== [phase14 trap] cleanup ==="
  rm -f "$TMP" /tmp/p14-*.json 2>/dev/null
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase14 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase14"; exit 1; }

echo "=== DB inventory + creationTimestamp BEFORE (eviction = timestamp reset) ==="
k -n "$DBNS" get pods -o custom-columns='POD:.metadata.name,NODE:.spec.nodeName,START:.status.startTime,RESTARTS:.status.containerStatuses[0].restartCount' 2>/dev/null | grep -E 'pg-|mysql-|mongo-|POD'
k -n stress-db get pod redis-0 -o custom-columns='POD:.metadata.name,NODE:.spec.nodeName,START:.status.startTime,RESTARTS:.status.containerStatuses[0].restartCount' 2>/dev/null

echo "=== evicted counter BEFORE ==="
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
EVB=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  consolidation_pods_evicted_total BEFORE = ${EVB:-NA}"

echo "=== (API) backend evictor-candidates — does it mark any DB node evictable? ==="
curl -s -o /tmp/p14-cand.json -w "evictor-candidates HTTP %{http_code}\n" -H "$COOKIE" \
  "$BASE/api/eks/autoscaling/$CL/evictor-candidates?accountId=$ACCTID" 2>&1
head -c 600 /tmp/p14-cand.json 2>/dev/null; echo
echo "=== (API) rebalance-status BEFORE ==="
curl -s -o /tmp/p14-rbs.json -w "rebalance-status HTTP %{http_code}\n" -H "$COOKIE" \
  "$BASE/api/eks/autoscaling/$CL/rebalance-status?accountId=$ACCTID" 2>&1
head -c 400 /tmp/p14-rbs.json 2>/dev/null; echo

echo "=== (API) TRIGGER a FRESH rebalance (POST …/rebalance) — drives a live drain re-plan ==="
curl -s -o /tmp/p14-trig.json -w "POST rebalance HTTP %{http_code}\n" -X POST -H "$COOKIE" \
  -H "Content-Type: application/json" \
  "$BASE/api/eks/autoscaling/$CL/rebalance?accountId=$ACCTID" 2>&1
head -c 400 /tmp/p14-trig.json 2>/dev/null; echo

echo "=== observe agent re-plan over DB nodes — capture per-DB refusals (4 min) ==="
: > /tmp/p14-refusals.log
for i in 1 2 3 4; do
  sleep 55
  k -n xamops-system logs "$LEADER" --since=70s 2>/dev/null \
    | grep -iE 'refus|blocked|db-|protect|skip.*(pg-|mysql|mongo|redis)|rebalanc|drain' >> /tmp/p14-refusals.log
  echo "--- t+${i}m: new refusal/drain lines ---"
  tail -6 /tmp/p14-refusals.log 2>/dev/null | sed 's/^/  /'
done

echo "=== PER-ENGINE refusal evidence (grep the captured log) ==="
for eng in "pg-cnpg-2:Postgres-CNPG-primary" "pg-cnpg-1:Postgres-CNPG-replica" "pg-unsafe-0:Postgres-emptyDir" "mysql-0:MySQL" "mongo-0:Mongo" "mongo-1:Mongo" "mongo-2:Mongo" "redis-0:Redis"; do
  pod=${eng%%:*}; label=${eng##*:}
  line=$(grep -E "$pod" /tmp/p14-refusals.log 2>/dev/null | grep -iE 'refus|block|db-|protect|skip' | tail -1)
  echo "  [$label] $pod -> ${line:-<no explicit refusal line captured this window>}"
done

echo "=== VERDICT per engine: 0 evictions? (timestamp + restart unchanged) ==="
k -n "$DBNS" get pods -o custom-columns='POD:.metadata.name,START:.status.startTime,RESTARTS:.status.containerStatuses[0].restartCount' 2>/dev/null | grep -E 'pg-|mysql-|mongo-'
k -n stress-db get pod redis-0 -o custom-columns='POD:.metadata.name,START:.status.startTime,RESTARTS:.status.containerStatuses[0].restartCount' 2>/dev/null
echo "=== Evicted/preempt events in DB namespaces (must be none) ==="
echo "-- $DBNS --"; k -n "$DBNS" get events --field-selector reason=Evicted 2>/dev/null | tail -3 || echo "  none"
echo "-- stress-db --"; k -n stress-db get events --field-selector reason=Evicted 2>/dev/null | tail -3 || echo "  none"

echo "=== evicted counter AFTER ==="
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
EVA=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
RBA=$(curl -s localhost:19090/metrics 2>/dev/null | grep -E '^xamops_drains_total' | head -5)
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  consolidation_pods_evicted_total AFTER = ${EVA:-NA} (BEFORE ${EVB:-NA})"
echo "  drains_total:"; echo "$RBA" | sed 's/^/    /'
echo "PHASE14-DONE"
