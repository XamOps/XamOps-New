#!/usr/bin/env bash
# PHASE 9 — autoscaler conflict: an HPA scaling on the SAME signal the agent acts on.
# The agent's consolidation/node-optimizer acts on node packing / utilization (wants to REMOVE nodes
# when under-utilized). We stand up a CPU HPA on a chunk of the fleet and then drive load UP then DOWN
# so the HPA ADDS replicas (more pods -> fuller nodes) while, in the idle valley, consolidation wants
# to pack/drain. Goal: detect a fight (agent drains a node the HPA just filled / HPA fights a cordon /
# thrash of consolidation executed<->revert, or any eviction of HPA-owned pods).
# Trap deletes the HPA + k6 and restores replica counts.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

HPA_TARGETS=$(k -n stress get deploy -o name 2>/dev/null | grep -E 'over-|over-heavy' | sed 's#deployment.apps/##' | head -6)

cleanup() {
  echo "=== [phase9 trap] cleanup ==="
  for d in $HPA_TARGETS; do
    k -n stress delete hpa "$d" --ignore-not-found >/dev/null 2>&1
    k -n stress scale deploy "$d" --replicas=1 >/dev/null 2>&1
  done
  k -n stress delete job k6-p9-hi --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase9 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase9"; exit 1; }
prom_pf_start

LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
agent_metrics() {
  pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
  k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf-agent.log 2>&1 & sleep 4
  curl -s http://localhost:19090/metrics 2>/dev/null
  pkill -f "port-forward.*19090:9090" 2>/dev/null
}

echo "=== BEFORE snapshot ==="
M0=$(agent_metrics)
echo "$M0" | grep -E '^xamops_consolidation_runs_total|^xamops_consolidation_pods_evicted_total|^xamops_consolidation_action_rate|^xamops_drains_total|^xamops_drain_budget_inflight' | sort
NODES0=$(k get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
echo "nodes BEFORE = $NODES0"
echo "ng-ondemand-1 desired BEFORE = $(aws eks describe-nodegroup --cluster-name xamops-test-1 --nodegroup-name ng-ondemand-1 --region "$REGION" --query 'nodegroup.scalingConfig.desiredSize' --output text 2>/dev/null)"

# INJECT: create an HPA (CPU 50%) on each of the over-profile deploys, maxReplicas 4.
echo "=== INJECT: HPA cpu=50% on fleet over-profile deploys (the agent's packing signal) ==="
for d in $HPA_TARGETS; do
  cat <<YAML | k apply -f - >/dev/null 2>&1
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: $d, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "9"}}
spec:
  scaleTargetRef: {apiVersion: apps/v1, kind: Deployment, name: $d}
  minReplicas: 1
  maxReplicas: 4
  metrics:
    - type: Resource
      resource: {name: cpu, target: {type: Utilization, averageUtilization: 50}}
YAML
done
echo "HPAs created on: $(echo $HPA_TARGETS | tr '\n' ' ')"
k -n stress get hpa 2>&1 | grep -E 'NAME|over' | head

# k6 HIGH load to push CPU > 50% so HPA scales UP
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p9-hi, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "9"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p9-hi, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "120"}
            - {name: RAMP_UP, value: "20s"}
            - {name: HOLD, value: "150s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.01"}
          resources: {requests: {cpu: 200m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p9-hi launched (high load to trigger HPA scale-up)"

# Watch HPA + agent for the scale-up window
for i in 1 2 3 4 5 6; do
  sleep 25
  echo "--- t+$((i*25))s HPA state ---"
  k -n stress get hpa 2>/dev/null | grep -E 'over' | awk '{print $1, $3, $6"/"$7, "rep="$8}'
done

echo "=== PEAK: fleet replica + node state ==="
k -n stress get deploy 2>/dev/null | grep -E 'over' | awk '{print $1, $2}'
echo "nodes now = $(k get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')"

# Let load wind down -> HPA will want to scale down; in the valley consolidation wants to pack.
echo "=== waiting for k6 to finish (load valley forms) ==="
k -n stress wait --for=condition=complete job/k6-p9-hi --timeout=140s 2>&1 | tail -1 || true
k -n stress logs job/k6-p9-hi 2>&1 | grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded" | tail -4
echo "=== valley observation (HPA scaledown + agent) 90s ==="
sleep 90
k -n stress get hpa 2>/dev/null | grep -E 'over' | awk '{print $1, $6"/"$7, "rep="$8}'

echo "=== AFTER snapshot ==="
M1=$(agent_metrics)
echo "$M1" | grep -E '^xamops_consolidation_runs_total|^xamops_consolidation_pods_evicted_total|^xamops_consolidation_action_rate|^xamops_drains_total|^xamops_drain_budget_inflight' | sort
NODES1=$(k get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
echo "nodes AFTER = $NODES1 (before $NODES0)"
echo "ng-ondemand-1 desired AFTER = $(aws eks describe-nodegroup --cluster-name xamops-test-1 --nodegroup-name ng-ondemand-1 --region "$REGION" --query 'nodegroup.scalingConfig.desiredSize' --output text 2>/dev/null)"
echo "=== DB pods intact? ==="
k -n xamops-db-fixtures get pods 2>/dev/null | grep -E 'pg-|mysql-|mongo-' | awk '{print $1,$3}'
echo "=== any Evicted/preempt events in stress (HPA-pod eviction by agent)? ==="
k -n stress get events --field-selector reason=Evicted 2>/dev/null | tail -5
echo "PHASE9-DONE"
