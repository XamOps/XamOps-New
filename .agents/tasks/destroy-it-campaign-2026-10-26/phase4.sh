#!/usr/bin/env bash
# PHASE 4 — CPU-throttling, measured with the REAL CFS metric
# container_cpu_cfs_throttled_periods_total (+ elapsed periods for the throttle RATIO).
# Inject chaos-mesh CPU StressChaos on the over-profile pods (500m CPU limit) and burn > limit,
# under k6 load. Breaking point = sustained high CFS throttle ratio / p95 latency blow-up.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase4 trap] cleanup ==="
  k -n stress delete stresschaos p4-cpu-throttle --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p4-cpu --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  echo "=== [phase4 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase4"; exit 1; }
prom_pf_start

SEL='container_cpu_cfs_throttled_periods_total{namespace="stress",pod=~"fleet-.*over.*"}'
echo "=== CFS BEFORE (sum throttled periods over over-profile) ==="
BEFORE=$(prom_query "sum($SEL)" | grep -oE '= [0-9.e+]+' | awk '{print $2}')
echo "before_throttled_periods_sum = ${BEFORE:-NA}"

# k6 load
k -n stress delete job k6-p4-cpu --ignore-not-found >/dev/null 2>&1
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p4-cpu, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "4"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p4-cpu, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "80"}
            - {name: RAMP_UP, value: "10s"}
            - {name: HOLD, value: "140s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.05"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p4-cpu launched"; sleep 11

# INJECT: CPU StressChaos — 4 workers busy-loop (far exceeds 500m limit) on over-profile pods, 120s
echo "=== INJECT: CPU StressChaos (4 workers, 100% load) on over-profile pods, 120s ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: StressChaos
metadata: {name: p4-cpu-throttle, namespace: stress}
spec:
  mode: fixed-percent
  value: "50"
  selector:
    namespaces: [stress]
    labelSelectors: {xamops.io/profile: over}
  stressors:
    cpu: {workers: 4, load: 100}
  duration: "120s"
YAML
echo "StressChaos p4-cpu-throttle applied"

# observe the REAL CFS throttle RATIO during injection
for i in 1 2 3 4 5 6; do
  sleep 20
  echo "--- t+$((i*20))s ---"
  echo -n "throttle_ratio (throttled/elapsed, 30s rate, over-profile avg): "
  prom_query 'avg(rate(container_cpu_cfs_throttled_periods_total{namespace="stress",pod=~"fleet-.*over.*"}[30s]) / clamp_min(rate(container_cpu_cfs_periods_total{namespace="stress",pod=~"fleet-.*over.*"}[30s]),0.001))' | tail -1
done

echo "=== CFS AFTER (sum throttled periods) ==="
AFTER=$(prom_query "sum($SEL)" | grep -oE '= [0-9.e+]+' | awk '{print $2}')
echo "after_throttled_periods_sum = ${AFTER:-NA}"
python3 -c "b=float('${BEFORE:-0}'); a=float('${AFTER:-0}'); print(f'DELTA throttled periods during phase = {a-b:.0f}')" 2>/dev/null || true

echo "=== peak throttle ratio over the window (max_over_time) ==="
prom_query 'max(max_over_time((rate(container_cpu_cfs_throttled_periods_total{namespace="stress",pod=~"fleet-.*over.*"}[30s]) / clamp_min(rate(container_cpu_cfs_periods_total{namespace="stress",pod=~"fleet-.*over.*"}[30s]),0.001))[2m:15s]))' | tail -1

echo "=== serving health under throttle (k6) ==="
k -n stress wait --for=condition=complete job/k6-p4-cpu --timeout=90s 2>&1 | tail -1 || true
k -n stress logs job/k6-p4-cpu 2>&1 | tee /tmp/p4-k6.log | \
  grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded|http_reqs\.\." | tail -8
# trap cleans chaos + job
