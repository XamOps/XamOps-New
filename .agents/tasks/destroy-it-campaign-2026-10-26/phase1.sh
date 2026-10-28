#!/usr/bin/env bash
# PHASE 1 — zero-downtime-under-traffic.
# Drive real in-cluster k6 load at fleet-http (145 backends), then roll a batch of fleet
# deployments UNDER traffic. Breaking point = sustained http_req_failed rate climbs / checks fail.
# Trap tears down the k6 Job and undoes any paused rollouts on exit/interrupt.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

ROLLED=()
cleanup() {
  echo "=== [phase1 trap] cleanup ==="
  # delete the k6 load job
  k -n stress delete job k6-p1-zerodt --ignore-not-found >/dev/null 2>&1
  # resume any rollouts we paused (none paused in this phase, but belt-and-braces)
  for d in "${ROLLED[@]:-}"; do
    k -n stress rollout resume "deploy/$d" >/dev/null 2>&1
  done
  prom_pf_stop
  echo "=== [phase1 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase1: context/identity assertion failed"; exit 1; }

# ---- launch k6 load (90s window: 20s ramp, 60s hold, 10s down) ----
k -n stress delete job k6-p1-zerodt --ignore-not-found >/dev/null 2>&1
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-p1-zerodt
  namespace: stress
  labels: {xamops.io/campaign: destroy-it, xamops.io/role: k6-load, xamops.io/phase: "1"}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p1-zerodt, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "80"}
            - {name: RAMP_UP, value: "20s"}
            - {name: HOLD, value: "60s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 200m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes:
        - name: script
          configMap: {name: k6-fleet-script}
YAML
echo "k6-p1-zerodt launched"

# wait for k6 pod running + ramp
k -n stress wait --for=condition=ready pod -l app=k6-p1-zerodt --timeout=60s 2>&1 | tail -1
sleep 22  # let ramp finish, load at hold

# ---- INJECT: roll a batch of 12 fleet deploys under traffic (staggered) ----
echo "=== INJECT: rolling 12 fleet deploys under traffic ==="
BATCH=$(k -n stress get deploy -o name 2>/dev/null | grep fleet- | head -12)
for d in $BATCH; do
  k -n stress rollout restart "$d" >/dev/null 2>&1
done
echo "restarted: $(echo "$BATCH" | wc -l | tr -d ' ') deploys"

# observe during the roll (k6 still holding load)
sleep 35
echo "=== rollout status (sample) ==="
for d in $(echo "$BATCH" | head -4); do
  k -n stress rollout status "$d" --timeout=40s 2>&1 | tail -1
done

# ---- wait for k6 to finish and capture authoritative summary BEFORE any cleanup ----
echo "=== waiting for k6 job to complete ==="
k -n stress wait --for=condition=complete job/k6-p1-zerodt --timeout=120s 2>&1 | tail -1 || \
  k -n stress wait --for=condition=failed job/k6-p1-zerodt --timeout=5s 2>&1 | tail -1
echo "=== k6 SUMMARY (authoritative) ==="
k -n stress logs job/k6-p1-zerodt 2>&1 | tee /tmp/p1-k6.log | \
  grep -E "http_req_failed|http_req_duration|checks_|checks\.|✓|✗|iterations|http_reqs|data_received" | tail -30

echo "=== fleet availability after roll ==="
k -n stress get deploy -o json 2>/dev/null | python3 -c '
import sys,json
d=json.load(sys.stdin)
tot=avail=0
for x in d["items"]:
    tot+=1
    s=x.get("status",{})
    if s.get("availableReplicas",0)>=1: avail+=1
print(f"fleet deploys available: {avail}/{tot}")'
# cleanup via trap
