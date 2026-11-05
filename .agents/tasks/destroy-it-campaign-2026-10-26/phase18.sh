#!/usr/bin/env bash
# PHASE 18 — stale-state / time / identity.
# (A) STALE OPERATION-ID / replay: re-POST the same rebalance; agent must NOT re-run a completed
#     operation-id (anti-replay of stale state). Confirm gen-latch holds.
# (B) FOREIGN IDENTITY orphan: stamp a node with drain-state=draining but drain-cordoned-by = a
#     FOREIGN identity (NOT 'xamops-automation'). RecoverOrphans must mark aborted but must NOT
#     uncordon a node it does not own (executor.go:745 `ours` check). Verifies identity-scoped action.
# (C) STALE/EXPIRED leader: after a leadership transfer, confirm the OLD (killed) identity cannot still
#     act — the lease holder is the only actor (fencing). Use lease transitions + single is_leader.
# Trap restores the test node annotations to a terminal state.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

BASE=https://uat.xamops.com; CL=xamops-test-1; ACCTID=102227938259
SRC=/Users/apple/.config/xamops/uat-session
TMP=$(mktemp /tmp/uat-sess.XXXXXX); chmod 600 "$TMP"
SESSION_VAL=$(grep -oE 'SESSION=[A-Za-z0-9+/=_-]+' "$SRC" | head -1 | cut -d= -f2-)
printf 'SESSION=%s' "$SESSION_VAL" > "$TMP"; COOKIE="Cookie: $(cat "$TMP")"
# use a DIFFERENT already-cordoned empty node for the foreign-identity test
NF=ip-172-31-29-155.ap-south-1.compute.internal

cleanup() {
  echo "=== [phase18 trap] cleanup ==="
  # restore foreign-identity test node to terminal 'drained' + its real xamops cordoned-by, keep cordon
  st=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null)
  if [ "$st" = "draining" ] || [ "$st" = "aborted" ]; then
    k annotate node "$NF" xamops.io/drain-state=drained xamops.io/drain-cordoned-by=xamops-automation --overwrite >/dev/null 2>&1
    echo "  restored $NF annotations to drained/xamops-automation"
  fi
  rm -f "$TMP" 2>/dev/null
  pkill -f "port-forward.*1909" 2>/dev/null
  echo "=== [phase18 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase18"; exit 1; }

echo "=========================================================="
echo "=== (A) STALE OPERATION-ID / replay protection ==="
echo "=========================================================="
echo "rebalance-status BEFORE:"
curl -s -H "$COOKIE" "$BASE/api/eks/autoscaling/$CL/rebalance-status?accountId=$ACCTID" 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  op=",d.get("operationId"),"status=",d.get("status"),"nodesDrained=",d.get("nodesDrained"),"reason=",d.get("reason"))' 2>&1
echo "re-POST the SAME rebalance 3x (replay) — agent must NOT re-run a completed op:"
for i in 1 2 3; do
  curl -s -o /dev/null -w "  replay $i -> HTTP %{http_code}\n" -X POST -H "$COOKIE" "$BASE/api/eks/autoscaling/$CL/rebalance?accountId=$ACCTID" 2>/dev/null
done
sleep 10
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
echo "agent log — does it re-run or refuse the completed op?:"
k -n xamops-system logs "$LEADER" --since=70s 2>/dev/null | grep -iE 'rebalanc|already completed|not re-run|gen-' | tail -4 | sed 's/^/  /'
echo "rebalance-status AFTER (nodesDrained must NOT grow from replay):"
curl -s -H "$COOKIE" "$BASE/api/eks/autoscaling/$CL/rebalance-status?accountId=$ACCTID" 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  op=",d.get("operationId"),"status=",d.get("status"),"nodesDrained=",d.get("nodesDrained"))' 2>&1

echo ""
echo "=========================================================="
echo "=== (B) FOREIGN-IDENTITY orphan (identity-scoped recovery) ==="
echo "=========================================================="
echo "BEFORE $NF: unsched=$(k get node "$NF" -o jsonpath='{.spec.unschedulable}' 2>/dev/null) state=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null) by=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-cordoned-by}' 2>/dev/null)"
echo "INDUCE: drain-state=draining BUT drain-cordoned-by=some-other-operator (FOREIGN identity)"
k annotate node "$NF" xamops.io/drain-state=draining xamops.io/drain-cordoned-by=some-other-operator --overwrite >/dev/null 2>&1
echo "  now: state=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null) by=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-cordoned-by}' 2>/dev/null) unsched=$(k get node "$NF" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)"
echo "wait for RecoverOrphans to see it (2 cycles):"
for i in 1 2 3; do
  sleep 35
  st=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null)
  uns=$(k get node "$NF" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)
  echo "  t+$((i*35))s: state=$st unsched=$uns"
  [ "$st" = "aborted" ] && break
done
FB_STATE=$(k get node "$NF" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null)
FB_UNS=$(k get node "$NF" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)
echo "RESULT: state=$FB_STATE unsched=$FB_UNS"
if [ "$FB_STATE" = "aborted" ] && [ "${FB_UNS:-false}" = "true" ]; then
  echo "  PASS: foreign-owned node marked aborted but LEFT CORDONED (agent did NOT uncordon a node it didn't own)."
elif [ "$FB_STATE" = "aborted" ] && [ "${FB_UNS:-false}" != "true" ]; then
  echo "  *** CONCERN: agent UNCORDONED a FOREIGN-owned node (identity check bypassed?) — investigate ***"
else
  echo "  note: state=$FB_STATE (not yet reconciled this window)"
fi

echo ""
echo "=========================================================="
echo "=== (C) STALE/EXPIRED leader — fencing ==="
echo "=========================================================="
echo "lease BEFORE: holder=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null) transitions=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.leaseTransitions}' 2>/dev/null) durationSec=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.leaseDurationSeconds}' 2>/dev/null)"
OLD=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
echo "SIGKILL current leader $OLD; the OLD identity must stop acting, only the NEW holder acts:"
k -n xamops-system delete pod "$OLD" --grace-period=0 --force --wait=false >/dev/null 2>&1
sleep 18
NEW=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
TR=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.leaseTransitions}' 2>/dev/null)
echo "lease AFTER: holder=$NEW transitions=$TR"
echo "exactly ONE is_leader across replicas (no stale leader still claiming)?"
LC=0
for p in $(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null); do
  pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 0.3
  k -n xamops-system port-forward "$p" 19090:9090 >/tmp/pf.log 2>&1 & sleep 2.5
  il=$(curl -s localhost:19090/metrics 2>/dev/null | grep -E '^xamops_leader\{' | awk '{print $2}' | head -1)
  h=$(curl -s localhost:19090/metrics 2>/dev/null | grep -E '^xamops_leader\{' | sed -E 's/.*is_leader="([^"]+)".*/\1/' | head -1)
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "  ${p#pod/}: is_leader=$il holder=$h"
  [ "${il:-0}" = "1" ] && LC=$((LC+1))
done
echo "  simultaneous leaders = $LC (want 1); old=$OLD new=$NEW (changed=$([ "$OLD" != "$NEW" ] && echo yes || echo 'no — same pod name reused'))"
echo "DB intact: $(k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-') pods; evicted=$(k -n xamops-db-fixtures get events --field-selector reason=Evicted 2>/dev/null | grep -c Evicted)"
echo "PHASE18-DONE"
