#!/usr/bin/env bash
# PHASE 17 (REPLACED) — SIGKILL the agent repeatedly mid-drain -> verify RecoverOrphans leaves no
# half-drained node.
# The fleet is over-subscribed so no node is naturally drainable; to exercise the orphan-recovery
# reconciler for real we induce a genuine HALF-DRAINED state on an ALREADY-cordoned, ALREADY-empty
# ng-ondemand test node (ip-172-31-2-6: unschedulable=true, only DaemonSet pods left) by flipping its
# agent-owned drain-state label 'drained' -> 'draining' (non-terminal, i.e. as if the agent crashed
# between cordon and drain-complete). Then SIGKILL the leader REPEATEDLY (delete --grace-period=0 =
# no graceful shutdown) and verify RecoverOrphans reconciles the node to a CONSISTENT terminal state
# (drained or uncordoned) with NO node left stuck 'draining' and no stuck eviction. DB untouched.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

N=ip-172-31-2-6.ap-south-1.compute.internal
ORIG_STATE=""

cleanup() {
  echo "=== [phase17 trap] cleanup: restore node label to its original terminal state ==="
  # restore to 'drained' (its real prior terminal state) if the agent didn't already reconcile it
  cur=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-state}' 2>/dev/null)
  if [ "$cur" = "draining" ]; then
    echo "  node still 'draining' — restoring to 'drained' (agent did not reconcile; recording that)"
    k label node "$N" xamops.io/drain-state=drained --overwrite >/dev/null 2>&1
  fi
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase17 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase17"; exit 1; }

echo "=== BEFORE: node + orphan counter ==="
ORIG_STATE=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-state}' 2>/dev/null)
ORIG_OP=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-operation}' 2>/dev/null)
ORIG_UNS=$(k get node "$N" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)
echo "  $N: unsched=$ORIG_UNS drain-state=$ORIG_STATE op=$ORIG_OP"
echo "  non-DS pods on node (must be empty/DS-only to be a safe orphan target):"
k get pods -A --field-selector spec.nodeName=$N --no-headers 2>/dev/null | grep -vE 'kube-proxy|aws-node|ebs-csi|chaos-daemon|node-exporter|cloudwatch-agent|fluent-bit' | sed 's/^/    /' || echo "    (none)"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
ORPH_B=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_orphans_recovered_total' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  orphans_recovered_total BEFORE = ${ORPH_B:-NA}"

echo "=== INDUCE half-drained: drain-state drained -> draining (non-terminal, crash-mid-drain) ==="
k label node "$N" xamops.io/drain-state=draining --overwrite >/dev/null 2>&1
echo "  now: drain-state=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-state}' 2>/dev/null) unsched=$(k get node "$N" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)"

echo "=== SIGKILL the leader repeatedly (grace-period=0, no graceful shutdown) x5 ==="
for r in 1 2 3 4 5; do
  LPOD=""
  for p in $(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null); do
    pkill -f "port-forward.*19202:9090" 2>/dev/null; sleep 0.3
    k -n xamops-system port-forward "$p" 19202:9090 >/tmp/pf.log 2>&1 & sleep 2.2
    il=$(curl -s localhost:19202/metrics 2>/dev/null | grep -E '^xamops_leader\{' | awk '{print $2}' | head -1)
    pkill -f "port-forward.*19202:9090" 2>/dev/null
    [ "${il:-0}" = "1" ] && LPOD=${p#pod/}
  done
  echo "  round $r: SIGKILL leader ${LPOD:-?}"
  [ -n "$LPOD" ] && k -n xamops-system delete pod "$LPOD" --grace-period=0 --force --wait=false >/dev/null 2>&1
  sleep 8
  # watch the node state react to each crash/restart
  st=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-state}' 2>/dev/null)
  echo "    node drain-state after round $r = ${st:-<none>}  unsched=$(k get node "$N" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)"
done

echo "=== settle + let RecoverOrphans run (new leader reconciles on startup) ==="
for i in 1 2 3 4; do
  sleep 30
  st=$(k get node "$N" -o jsonpath='{.metadata.labels.xamops\.io/drain-state}' 2>/dev/null)
  uns=$(k get node "$N" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)
  echo "  t+$((i*30))s: node drain-state=${st:-<none>} unsched=${uns:-false}"
  LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
  k -n xamops-system logs "$LEADER" --since=40s 2>/dev/null | grep -iE 'orphan|recover|reconcile|half|draining|resume|stuck' | tail -4 | sed 's/^/    log: /'
done

echo "=== FINAL invariant: NO node stuck 'draining' anywhere ==="
STUCK=$(k get nodes -o json 2>/dev/null | python3 -c '
import json,sys
d=json.load(sys.stdin); stuck=[]
for n in d["items"]:
    s=n["metadata"].get("labels",{}).get("xamops.io/drain-state","")
    if s and s not in ("drained",""):  # anything non-terminal
        stuck.append(n["metadata"]["name"].split(".")[0]+":"+s)
print(" ".join(stuck) if stuck else "NONE")')
echo "  nodes in a non-terminal drain-state = $STUCK"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
ORPH_A=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_orphans_recovered_total' | awk '{print $2}')
INF_A=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_drain_budget_inflight' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  orphans_recovered_total AFTER = ${ORPH_A:-NA} (BEFORE ${ORPH_B:-NA})"
echo "  drain_budget_inflight AFTER = ${INF_A:-NA}"
echo "=== agent + DB health ==="
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | sed 's/^/  agent: /'
echo "  DB pods Running: $(k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-')"
echo "PHASE17-DONE"
