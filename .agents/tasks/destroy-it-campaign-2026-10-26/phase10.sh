#!/usr/bin/env bash
# PHASE 10 — api-server 429 pressure (APF / control-plane throttling).
# Spawn many concurrent heavy LIST calls from in-cluster Jobs (kubectl list pods/events/cm across all ns,
# resourceVersion=0 to hit the apiserver cache then watch bursts) to drive API Priority&Fairness to
# emit 429 "Too Many Requests". Observe: does the AGENT degrade gracefully (keeps a single leader, no
# crashloop, backs off) while the apiserver is under 429 pressure? Record apiserver 429 counts + agent health.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase10 trap] cleanup ==="
  k -n stress delete job -l xamops.io/phase=10 --ignore-not-found >/dev/null 2>&1
  k -n stress delete sa api-hammer --ignore-not-found >/dev/null 2>&1
  k delete clusterrolebinding api-hammer-view --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase10 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase10"; exit 1; }
prom_pf_start

LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
echo "leader = $LEADER"
agent_health_before=$(k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null)
echo "=== agent BEFORE ==="; echo "$agent_health_before"
RESTARTS_B=$(echo "$agent_health_before" | awk '{sum+=$4} END{print sum+0}')
LEASE_B=$(k -n xamops-system get lease -o jsonpath='{range .items[*]}{.metadata.name}={.spec.holderIdentity}{"\n"}{end}' 2>/dev/null | grep -i xamops | head)
echo "lease holder BEFORE: $LEASE_B"

# apiserver 429 counter BEFORE (from the apiserver's own metrics via Prometheus)
echo "=== apiserver 429 (apiserver_request_total code=429) BEFORE ==="
prom_query 'sum(apiserver_request_total{code="429"})'

# RBAC for the hammer: read-only view
k create sa api-hammer -n stress >/dev/null 2>&1
k create clusterrolebinding api-hammer-view --clusterrole=view --serviceaccount=stress:api-hammer >/dev/null 2>&1

# INJECT: 8 parallel hammer Jobs, each a tight loop of expensive uncached LISTs (resourceVersion unset
# => served from etcd, heaviest path) against all namespaces.
echo "=== INJECT: 8 parallel api-hammer Jobs (uncached LIST storm) ==="
for i in $(seq 1 8); do
cat <<YAML | k apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: api-hammer-$i, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "10"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  activeDeadlineSeconds: 240
  template:
    metadata: {labels: {app: api-hammer, xamops.io/phase: "10"}}
    spec:
      restartPolicy: Never
      serviceAccountName: api-hammer
      containers:
        - name: hammer
          image: bitnami/kubectl:latest
          command: ["/bin/bash","-c"]
          args:
            - |
              end=\$((SECONDS+180))
              n=0; e429=0
              while [ \$SECONDS -lt \$end ]; do
                # uncached heavy lists across all namespaces, no limit
                if ! kubectl get pods -A -o name >/dev/null 2>/tmp/e; then grep -q 429 /tmp/e && e429=\$((e429+1)); fi
                if ! kubectl get events -A -o name >/dev/null 2>/tmp/e; then grep -q 429 /tmp/e && e429=\$((e429+1)); fi
                if ! kubectl get configmaps -A -o name >/dev/null 2>/tmp/e; then grep -q 429 /tmp/e && e429=\$((e429+1)); fi
                n=\$((n+3))
              done
              echo "HAMMER done: requests=\$n observed-429=\$e429"
          resources: {requests: {cpu: 100m, memory: 128Mi}, limits: {memory: 256Mi}}
YAML
done
echo "8 hammer jobs launched"

# Observe apiserver 429 climb + agent health through the storm
for i in 1 2 3 4 5 6; do
  sleep 30
  echo "--- t+$((i*30))s ---"
  echo -n "apiserver 429 total: "; prom_query 'sum(apiserver_request_total{code="429"})' | tr '\n' ' '; echo
  echo -n "apiserver 429 rate/s (1m): "; prom_query 'sum(rate(apiserver_request_total{code="429"}[1m]))' | tr '\n' ' '; echo
  k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | awk '{print "  agent:",$1,$2,$3,"restarts="$4}'
done

echo "=== collect hammer observed-429 ==="
for i in $(seq 1 8); do
  k -n stress logs job/api-hammer-$i 2>/dev/null | grep "HAMMER done" | sed "s/^/  hammer-$i: /"
done

echo "=== apiserver 429 AFTER ==="
prom_query 'sum(apiserver_request_total{code="429"})'
echo "=== APF rejected requests (flowcontrol) ==="
prom_query 'sum(apiserver_flowcontrol_rejected_requests_total)'

echo "=== agent AFTER ==="
agent_health_after=$(k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null)
echo "$agent_health_after"
RESTARTS_A=$(echo "$agent_health_after" | awk '{sum+=$4} END{print sum+0}')
echo "agent restarts BEFORE=$RESTARTS_B AFTER=$RESTARTS_A"
LEASE_A=$(k -n xamops-system get lease -o jsonpath='{range .items[*]}{.metadata.name}={.spec.holderIdentity}{"\n"}{end}' 2>/dev/null | grep -i xamops | head)
echo "lease holder AFTER: $LEASE_A"

# did the agent log throttling / did it keep working?
echo "=== agent log tail (throttle/backoff evidence) ==="
k -n xamops-system logs "$LEADER" --since=5m 2>/dev/null | grep -iE 'throttl|429|too many|rate limit|retry|backoff' | tail -10
echo "  (gate still live?)"
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf-agent.log 2>&1 & sleep 4
curl -s http://localhost:19090/metrics 2>/dev/null | grep -E 'xamops_gate_decision\{controller="(consolidation-actions|evictor|rebalancer|interruption)"' | grep -E 'live|analyse'
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "PHASE10-DONE"
