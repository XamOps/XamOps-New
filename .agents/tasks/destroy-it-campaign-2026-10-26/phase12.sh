#!/usr/bin/env bash
# PHASE 12 — PVC disk exhaustion. Fill the redis-0 PVC (2Gi, gp3-fixtures, isolated in stress-db) to
# 100% and observe:
#   (1) the pod's write path fails / disk-pressure behavior,
#   (2) the agent does NOT evict/drain the disk-full stateful pod (DB protection must hold),
#   (3) RECORD the v1 gap: no ONLINE PVC expansion by the agent/backend even though the CSI SC
#       (gp3-fixtures) advertises allowVolumeExpansion=true.
# Trap deletes the filler file inside the pod (frees the disk) and verifies redis healthy again.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

RPOD=redis-0; RNS=stress-db
cleanup() {
  echo "=== [phase12 trap] cleanup: free the disk ==="
  k -n "$RNS" exec "$RPOD" -- sh -c 'rm -f /data/fill.bin 2>/dev/null; sync' >/dev/null 2>&1
  k -n "$RNS" exec "$RPOD" -- sh -c 'df -h /data 2>/dev/null | tail -1' 2>&1 | sed 's/^/  after-free: /'
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase12 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase12"; exit 1; }

echo "=== SC expansion capability (the v1 gap context) ==="
k get sc gp3-fixtures -o jsonpath='{.metadata.name}: allowVolumeExpansion={.allowVolumeExpansion}{"\n"}' 2>&1

echo "=== BEFORE: redis disk + health + agent evicted counter ==="
k -n "$RNS" exec "$RPOD" -- sh -c 'df -h /data | tail -1' 2>&1 | sed 's/^/  /'
k -n "$RNS" exec "$RPOD" -- redis-cli ping 2>&1 | sed 's/^/  redis ping: /'
PVC_CAP_B=$(k -n "$RNS" get pvc redis-data -o jsonpath='{.status.capacity.storage}' 2>/dev/null)
echo "  PVC capacity BEFORE = $PVC_CAP_B"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
EVB=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  consolidation_pods_evicted_total BEFORE = ${EVB:-NA}"

echo "=== INJECT: fill /data to 100% (dd until ENOSPC) ==="
k -n "$RNS" exec "$RPOD" -- sh -c '
  # write until no space left; capture the error
  dd if=/dev/zero of=/data/fill.bin bs=1M 2>/tmp/dd.err || true
  echo "---dd result---"; tail -2 /tmp/dd.err
  echo "---df after fill---"; df -h /data | tail -1
' 2>&1 | sed 's/^/  /'

echo "=== observe: redis write behavior at 100% full ==="
k -n "$RNS" exec "$RPOD" -- sh -c '
  redis-cli ping 2>&1 | sed "s/^/ping: /"
  # force a write that must persist -> on a full disk redis BGSAVE/AOF will error
  redis-cli set diskfull-probe 1 2>&1 | sed "s/^/set: /"
  redis-cli bgsave 2>&1 | sed "s/^/bgsave: /"
' 2>&1 | sed 's/^/  /'

echo "=== (2) DB PROTECTION: does the agent evict the disk-full redis pod? (watch 90s) ==="
for i in 1 2 3; do
  sleep 30
  st=$(k -n "$RNS" get pod "$RPOD" -o jsonpath='{.status.phase}:{.status.containerStatuses[0].restartCount}' 2>/dev/null)
  ev=$(k -n "$RNS" get events --field-selector involvedObject.name=$RPOD 2>/dev/null | grep -iE 'evict|preempt|drain' | tail -2)
  echo "  t+$((i*30))s redis phase:restarts=$st  evict-events=[${ev:-none}]"
done

echo "=== (3) v1 ONLINE EXPANSION gap: did capacity auto-grow? ==="
PVC_CAP_A=$(k -n "$RNS" get pvc redis-data -o jsonpath='{.status.capacity.storage}' 2>/dev/null)
echo "  PVC capacity AFTER = $PVC_CAP_A (BEFORE $PVC_CAP_B) -> expansion by agent/backend: $([ "$PVC_CAP_A" = "$PVC_CAP_B" ] && echo NONE || echo CHANGED)"
echo "  (SC supports expansion, but v1 agent/backend performs NO online expansion — gap recorded)"

echo "=== agent AFTER (counter + health) ==="
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
EVA=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  consolidation_pods_evicted_total AFTER = ${EVA:-NA} (BEFORE ${EVB:-NA})"
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | sed 's/^/  agent: /'
echo "PHASE12-DONE"
