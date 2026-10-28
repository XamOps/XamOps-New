#!/usr/bin/env bash
# PHASE 2 — scale (~200). Push fleet pod count from 145 toward ~200 by scaling a batch of
# deploys to 2 replicas, under k6 load. Breaking point = pods go Pending (IP / 17-pod-ceiling
# bound on t3.medium — nodegroup growth is FORBIDDEN, so this is the real ceiling).
# Trap scales every touched deploy back to 1 and deletes the k6 job.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

SCALED_FILE=/tmp/p2-scaled.txt
: > "$SCALED_FILE"
cleanup() {
  echo "=== [phase2 trap] cleanup: scaling touched deploys back to 1 ==="
  while read -r d; do
    [ -n "$d" ] && k -n stress scale "deploy/$d" --replicas=1 >/dev/null 2>&1
  done < "$SCALED_FILE"
  k -n stress delete job k6-p2-scale --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  echo "=== [phase2 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase2"; exit 1; }

# baseline
echo "=== baseline ==="
BEFORE=$(k -n stress get pods --no-headers 2>/dev/null | grep -c Running)
echo "fleet running pods before: $BEFORE ; pending: $(k -n stress get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' ')"

# launch background k6 load for the duration
k -n stress delete job k6-p2-scale --ignore-not-found >/dev/null 2>&1
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p2-scale, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "2"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p2-scale, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "60"}
            - {name: RAMP_UP, value: "15s"}
            - {name: HOLD, value: "150s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p2-scale launched"
sleep 16

# INJECT: scale a batch to 2 replicas (adds ~up to 55 pods -> target ~200)
echo "=== INJECT: scaling 55 fleet deploys to 2 replicas (target ~200 pods) ==="
for d in $(k -n stress get deploy -o name 2>/dev/null | grep fleet- | head -55); do
  name=${d#deployment.apps/}
  echo "$name" >> "$SCALED_FILE"
  k -n stress scale "$d" --replicas=2 >/dev/null 2>&1
done

# observe scheduling for up to ~2min
for i in 1 2 3 4 5 6; do
  sleep 18
  RUN=$(k -n stress get pods --no-headers 2>/dev/null | grep -c Running)
  PEND=$(k -n stress get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' ')
  echo "t+$((i*18))s  running=$RUN  pending=$PEND"
done

echo "=== pending pod reasons (breaking-point evidence) ==="
k -n stress get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | head -3 | awk '{print $1}' | while read p; do
  [ -n "$p" ] && echo "--- $p ---" && k -n stress describe pod "$p" 2>/dev/null | grep -A3 "Events:" | tail -3
done

echo "=== k6 still healthy under the scale churn? (wait + summarize) ==="
k -n stress wait --for=condition=complete job/k6-p2-scale --timeout=120s 2>&1 | tail -1 || true
k -n stress logs job/k6-p2-scale 2>&1 | tee /tmp/p2-k6.log | \
  grep -E "http_req_failed|http_req_duration\.|checks_succeeded|http_reqs\.\.|iterations\.\." | tail -10

echo "=== FINAL counts ==="
echo "running=$(k -n stress get pods --no-headers 2>/dev/null | grep -c Running) pending=$(k -n stress get pods --field-selector=status.phase=Pending --no-headers 2>/dev/null | wc -l | tr -d ' ')"
# trap restores replicas=1
