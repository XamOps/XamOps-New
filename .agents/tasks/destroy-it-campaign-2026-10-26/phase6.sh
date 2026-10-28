#!/usr/bin/env bash
# PHASE 6 — leader-failover / HA. Kill the agent leader mid-op; verify failover with NO split-brain
# (exactly one xamops_leader=1 at all times) and no double-action. Uses a chaos-mesh Schedule to do
# the kill, then MANDATORY delete-the-schedule + verify-gone (OPS-CHAOS-003 lesson).
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase6 trap] cleanup (DELETE kill schedule — mandatory) ==="
  k -n xamops-system delete schedule p6-leader-kill --ignore-not-found >/dev/null 2>&1
  k -n xamops-system delete podchaos -l managed-by=chaos-mesh --field-selector metadata.name=p6-leader-kill >/dev/null 2>&1
  # belt-and-braces: remove any podchaos spawned by the schedule
  for pc in $(k -n xamops-system get podchaos -o name 2>/dev/null | grep p6-leader-kill); do
    k -n xamops-system delete "$pc" >/dev/null 2>&1
  done
  prom_pf_stop
  echo "=== [phase6 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase6"; exit 1; }

leader_of() {  # prints the holderIdentity + count of pods reporting leader=1
  k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null
}
count_leaders() {  # count pods currently reporting xamops_leader ... 1
  local c=0
  for p in $(k -n xamops-system get pods -o name 2>/dev/null | grep automation); do
    k -n xamops-system port-forward "$p" 19090:9090 >/tmp/pf6.log 2>&1 & local PF=$!; sleep 3
    v=$(curl -s http://localhost:19090/metrics 2>/dev/null | grep -E '^xamops_leader\{' | awk '{print $2}')
    [ "$v" = "1" ] && c=$((c+1))
    kill $PF 2>/dev/null; sleep 1
  done
  echo "$c"
}

echo "=== BEFORE: leader lease = $(leader_of) ; pods reporting leader=1 : $(count_leaders) ==="
OLD_LEADER=$(leader_of)

# INJECT: chaos-mesh Schedule that kills the current leader pod once
echo "=== INJECT: chaos Schedule p6-leader-kill (one-shot pod-kill of leader) ==="
cat <<YAML | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: Schedule
metadata: {name: p6-leader-kill, namespace: xamops-system}
spec:
  schedule: "@every 10m"
  historyLimit: 1
  concurrencyPolicy: Forbid
  type: PodChaos
  podChaos:
    action: pod-kill
    mode: one
    selector:
      namespaces: [xamops-system]
      labelSelectors: {app: xamops-automation}
      fieldSelectors: {"metadata.name": "${OLD_LEADER}"}
YAML
echo "schedule applied; forcing immediate first fire by direct delete of leader (deterministic)"
# The schedule fires @every 10m; to make the failover deterministic & prompt, delete the leader now.
k -n xamops-system delete pod "$OLD_LEADER" --grace-period=1 >/dev/null 2>&1
echo "leader $OLD_LEADER deleted"

# observe failover
for i in 1 2 3 4 5 6; do
  sleep 10
  NL=$(leader_of)
  echo "t+$((i*10))s  lease-holder=$NL"
  [ -n "$NL" ] && [ "$NL" != "$OLD_LEADER" ] && break
done

echo "=== AFTER: pods reporting leader=1 (must be exactly 1 = NO split-brain) ==="
sleep 8
NLEAD=$(count_leaders)
echo "pods reporting xamops_leader=1 : $NLEAD"
echo "=== new lease holder ==="; leader_of; echo
echo "=== automation pods status ==="; k -n xamops-system get pods 2>/dev/null | grep automation

echo "=== double-action check: consolidation runs counter jump during failover ==="
NEWLEADERPOD=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
k -n xamops-system port-forward "pod/$NEWLEADERPOD" 19090:9090 >/tmp/pf6.log 2>&1 & PF=$!; sleep 4
curl -s http://localhost:19090/metrics 2>/dev/null | grep -E '^xamops_consolidation_runs_total|^xamops_leader\{' | head
kill $PF 2>/dev/null

# MANDATORY: delete the schedule and verify gone
echo "=== DELETE kill schedule + verify gone (mandatory) ==="
k -n xamops-system delete schedule p6-leader-kill --ignore-not-found 2>&1
sleep 3
echo "schedules remaining in xamops-system:"; k -n xamops-system get schedule 2>&1 | tail -2
echo "podchaos remaining in xamops-system:"; k -n xamops-system get podchaos 2>&1 | tail -2
