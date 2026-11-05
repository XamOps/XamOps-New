#!/usr/bin/env bash
# PHASE 17b (REPLACED-phase, CORRECTED injection) — SIGKILL agent repeatedly mid-drain -> verify
# RecoverOrphans leaves no half-drained node.
# CORRECTION vs 17a: RecoverOrphans reads the node ANNOTATION xamops.io/drain-state (executor.go:742),
# NOT a label. 17a set a label (ignored — test artifact, not a bug). Here we induce the REAL orphan by
# setting the ANNOTATION drain-state 'drained'->'draining' on an already-cordoned, xamops-owned, empty
# ng-ondemand node (keeps drain-cordoned-by=xamops-automation so RecoverOrphans should also UNCORDON).
# Then SIGKILL the leader x5 (delete --grace-period=0 --force) and verify the NEW leader's
# RecoverOrphans marks it drain-state=aborted and uncordons it -> NO node left 'draining'.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

N=ip-172-31-2-6.ap-south-1.compute.internal

cleanup() {
  echo "=== [phase17b trap] cleanup ==="
  # Leave the node in a terminal state. If RecoverOrphans aborted+uncordoned it (the correct outcome),
  # nothing to do. If still 'draining' (agent failed to recover), restore to a terminal 'drained'
  # annotation + keep cordon so we don't leave an inconsistent orphan behind.
  st=$(k get node "$N" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null)
  if [ "$st" = "draining" ]; then
    echo "  node still 'draining' — restoring annotation to 'drained' (recording non-recovery)"
    k annotate node "$N" xamops.io/drain-state=drained --overwrite >/dev/null 2>&1
  fi
  pkill -f "port-forward.*1909" 2>/dev/null
  echo "=== [phase17b trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase17b"; exit 1; }

gst() { k get node "$N" -o jsonpath='{.metadata.annotations.xamops\.io/drain-state}' 2>/dev/null; }
guns() { k get node "$N" -o jsonpath='{.spec.unschedulable}' 2>/dev/null; }

echo "=== BEFORE ==="
echo "  $N: unsched=$(guns) ann.drain-state=$(gst) cordoned-by=$(k get node "$N" -o jsonpath='{.metadata.annotations.xamops\.io/drain-cordoned-by}' 2>/dev/null)"
echo "  non-DS pods on node: $(k get pods -A --field-selector spec.nodeName=$N --no-headers 2>/dev/null | grep -vcE 'kube-proxy|aws-node|ebs-csi|chaos-daemon|node-exporter|cloudwatch-agent|fluent-bit')"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
ORPH_B=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_orphans_recovered_total' | awk '{print $2}')
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  orphans_recovered_total BEFORE = ${ORPH_B:-NA}"

echo "=== INDUCE real orphan: ANNOTATION drain-state drained -> draining ==="
k annotate node "$N" xamops.io/drain-state=draining --overwrite >/dev/null 2>&1
echo "  now: ann.drain-state=$(gst) unsched=$(guns)"

echo "=== SIGKILL leader x5 (grace-period=0 --force = no graceful shutdown) ==="
for r in 1 2 3 4 5; do
  LPOD=""
  for p in $(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null); do
    pkill -f "port-forward.*19203:9090" 2>/dev/null; sleep 0.3
    k -n xamops-system port-forward "$p" 19203:9090 >/tmp/pf.log 2>&1 & sleep 2.2
    il=$(curl -s localhost:19203/metrics 2>/dev/null | grep -E '^xamops_leader\{' | awk '{print $2}' | head -1)
    pkill -f "port-forward.*19203:9090" 2>/dev/null
    [ "${il:-0}" = "1" ] && LPOD=${p#pod/}
  done
  echo "  round $r SIGKILL ${LPOD:-?}"
  [ -n "$LPOD" ] && k -n xamops-system delete pod "$LPOD" --grace-period=0 --force --wait=false >/dev/null 2>&1
  sleep 7
  echo "    ann.drain-state=$(gst) unsched=$(guns)"
done

echo "=== settle: new leader should run RecoverOrphans on startup ==="
for i in 1 2 3 4 5 6; do
  sleep 25
  echo "  t+$((i*25))s: ann.drain-state=$(gst) unsched=$(guns)"
  L=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
  k -n xamops-system logs "$L" --since=35s 2>/dev/null | grep -iE 'orphan|recover|aborted|draining|reconcile' | tail -3 | sed 's/^/    log: /'
  # stop early if recovered
  s=$(gst); [ "$s" = "aborted" ] && { echo "  -> RECOVERED (drain-state=aborted)"; break; }
done

echo "=== FINAL ==="
FS=$(gst); FU=$(guns)
echo "  node final: drain-state=$FS unsched=$FU"
STUCK=$(k get nodes -o json 2>/dev/null | python3 -c '
import json,sys
d=json.load(sys.stdin); st=[]
for n in d["items"]:
    s=n["metadata"].get("annotations",{}).get("xamops.io/drain-state","")
    if s=="draining": st.append(n["metadata"]["name"].split(".")[0])
print(" ".join(st) if st else "NONE")')
echo "  nodes still annotated draining (half-drained) = $STUCK"
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
ORPH_A=$(curl -s localhost:19090/metrics 2>/dev/null | grep '^xamops_orphans_recovered_total' | awk '{print $2}')
curl -s localhost:19090/metrics 2>/dev/null | grep -E 'recover|orphan' | grep -vE '^#' | sed 's/^/    metric: /'
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "  orphans_recovered_total AFTER = ${ORPH_A:-NA} (BEFORE ${ORPH_B:-NA})"
echo "  agent:"; k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | sed 's/^/    /'
echo "  DB pods Running: $(k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-')"
if [ "$FS" = "aborted" ] && [ "${FU:-false}" != "true" ]; then
  echo "  VERDICT: RecoverOrphans PASS — orphan reconciled to aborted + uncordoned, no half-drained node."
elif [ "$FS" = "aborted" ]; then
  echo "  VERDICT: RecoverOrphans marked aborted (cordon state: $FU)."
else
  echo "  VERDICT: node NOT recovered (still $FS) — investigate."
fi
echo "PHASE17b-DONE"
