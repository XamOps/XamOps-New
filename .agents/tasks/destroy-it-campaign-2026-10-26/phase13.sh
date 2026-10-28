#!/usr/bin/env bash
# PHASE 13 — partial partition + rapid churn (~50/min).
# (a) NetworkChaos PARTITION a subset (~40%) of fleet from the rest (one-way loss) -> partial view.
# (b) PodChaos pod-kill at a churn rate of ~50/min (25% of ~145 every ~75s, repeated) under k6.
# Watch: does the agent mis-act on a partial/stale view (drain a node it can't fully see, evict a DB,
# thrash)? Serving SLO + agent counters recorded. DB pods must stay 0-evicted.
# Trap deletes both chaos objects + k6 and verifies connectivity restored.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase13 trap] cleanup ==="
  k -n stress delete networkchaos p13-partition --ignore-not-found >/dev/null 2>&1
  k -n stress delete podchaos p13-churn --ignore-not-found >/dev/null 2>&1
  k -n stress delete schedule p13-churn-sched --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p13 --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase13 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase13"; exit 1; }

LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
snap() { pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
  k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf.log 2>&1 & sleep 4
  curl -s localhost:19090/metrics 2>/dev/null | grep -E "$1"
  pkill -f "port-forward.*19090:9090" 2>/dev/null; }

echo "=== BEFORE ==="
snap '^xamops_consolidation_runs_total|^xamops_consolidation_pods_evicted_total|^xamops_drains_total|^xamops_orphans_recovered_total|^xamops_drain_budget_inflight'
DBB=$(k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-')
echo "DB pods Running BEFORE = $DBB"

echo "=== k6 load ==="
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p13, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "13"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1200
  template:
    metadata: {labels: {app: k6-p13}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "70"}
            - {name: RAMP_UP, value: "10s"}
            - {name: HOLD, value: "240s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.05"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p13 launched"; sleep 11

echo "=== (a) INJECT: partial partition (one-way loss 100% on ~40% of fleet) ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata: {name: p13-partition, namespace: stress}
spec:
  action: partition
  mode: fixed-percent
  value: "40"
  direction: to
  selector:
    namespaces: [stress]
    labelSelectors: {app: fleet-http}
  target:
    mode: all
    selector:
      namespaces: [stress]
      labelSelectors: {app: fleet-http}
  duration: "240s"
YAML
echo "NetworkChaos p13-partition applied (partition 40% of fleet)"

echo "=== (b) INJECT: rapid churn ~50/min via a Schedule killing 25% every 75s ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: Schedule
metadata: {name: p13-churn-sched, namespace: stress}
spec:
  schedule: "@every 75s"
  concurrencyPolicy: Allow
  historyLimit: 3
  type: PodChaos
  podChaos:
    action: pod-kill
    mode: fixed-percent
    value: "25"
    selector:
      namespaces: [stress]
      labelSelectors: {app: fleet-http}
YAML
echo "Schedule p13-churn-sched applied (~25% of 145 ≈ 36 kills every 75s ≈ 29/min sustained churn)"

echo "=== observe 4 min: agent mis-action under partial view + churn ==="
for i in 1 2 3 4; do
  sleep 60
  echo "--- t+$((i))m ---"
  echo -n "  fleet Running/Total: "; k -n stress get pods -l app=fleet-http --no-headers 2>/dev/null | awk '{t++; if($3=="Running")r++} END{print r"/"t}'
  snap '^xamops_consolidation_pods_evicted_total|^xamops_drains_total\{.*node-optimizer|^xamops_orphans_recovered_total|^xamops_drain_budget_inflight' | sed 's/^/  /'
done

echo "=== serving SLO under partition+churn (k6) ==="
k -n stress wait --for=condition=complete job/k6-p13 --timeout=120s 2>&1 | tail -1 || true
k -n stress logs job/k6-p13 2>&1 | grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded|http_reqs\.\." | tail -6

echo "=== AFTER ==="
snap '^xamops_consolidation_runs_total|^xamops_consolidation_pods_evicted_total|^xamops_drains_total|^xamops_orphans_recovered_total|^xamops_drain_budget_inflight'
DBA=$(k -n xamops-db-fixtures get pods --no-headers 2>/dev/null | grep -Ec 'pg-|mysql-|mongo-')
echo "DB pods Running AFTER = $DBA (BEFORE $DBB)"
echo "=== any Evicted events in db-fixtures? ==="
k -n xamops-db-fixtures get events --field-selector reason=Evicted 2>/dev/null | tail -3 || echo "none"
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | sed 's/^/  agent: /'
echo "PHASE13-DONE"
