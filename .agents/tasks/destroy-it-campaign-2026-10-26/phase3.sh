#!/usr/bin/env bash
# PHASE 3 — memory-OOM-race. Inject chaos-mesh StressChaos memory on a batch of burst-profile
# fleet pods, pushing them past the 256Mi limit to force OOMKills, under k6 load. Breaking point =
# OOMKilled restarts climb / CrashLoopBackOff / serving degrades. Trap deletes the StressChaos + job.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase3 trap] cleanup ==="
  k -n stress delete stresschaos p3-oom-mem --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p3-oom --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  echo "=== [phase3 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase3"; exit 1; }

echo "=== baseline OOM restarts on burst pods ==="
k -n stress get pods -l xamops.io/profile=burst --no-headers 2>/dev/null | awk '{s+=$4} END{print "burst pods:",NR," total restarts:",s+0}'

# k6 load
k -n stress delete job k6-p3-oom --ignore-not-found >/dev/null 2>&1
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p3-oom, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "3"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p3-oom, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "60"}
            - {name: RAMP_UP, value: "10s"}
            - {name: HOLD, value: "140s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p3-oom launched"
sleep 11

# INJECT: StressChaos memory — 400MB workers (> 256Mi limit) on up to 10 burst pods for 120s
echo "=== INJECT: StressChaos memory (400MB > 256Mi limit) on burst pods ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: StressChaos
metadata:
  name: p3-oom-mem
  namespace: stress
spec:
  mode: fixed-percent
  value: "40"
  selector:
    namespaces: [stress]
    labelSelectors:
      xamops.io/profile: burst
  stressors:
    memory:
      workers: 2
      size: "400MB"
  duration: "120s"
YAML
echo "StressChaos p3-oom-mem applied"

# observe OOM over ~2min
for i in 1 2 3 4 5 6; do
  sleep 20
  OOM=$(k -n stress get pods -l xamops.io/profile=burst -o json 2>/dev/null | python3 -c '
import sys,json
d=json.load(sys.stdin); killed=restarts=term=0
for p in d["items"]:
  for cs in p.get("status",{}).get("containerStatuses",[]):
    restarts+=cs.get("restartCount",0)
    ls=cs.get("lastState",{}).get("terminated",{})
    if ls.get("reason")=="OOMKilled": killed+=1
    st=cs.get("state",{})
    if "waiting" in st and st["waiting"].get("reason") in ("CrashLoopBackOff",): term+=1
print(f"restarts={restarts} oomkilled_lastState={killed} crashloop={term}")')
  echo "t+$((i*20))s  $OOM"
done

echo "=== OOMKilled evidence (describe) ==="
k -n stress get pods -l xamops.io/profile=burst -o json 2>/dev/null | python3 -c '
import sys,json
d=json.load(sys.stdin)
for p in d["items"]:
  for cs in p.get("status",{}).get("containerStatuses",[]):
    ls=cs.get("lastState",{}).get("terminated",{})
    if ls.get("reason")=="OOMKilled":
      print(p["metadata"]["name"],"OOMKilled exit",ls.get("exitCode"),"restarts",cs.get("restartCount"))' | head -12

echo "=== serving health under OOM race (k6) ==="
k -n stress wait --for=condition=complete job/k6-p3-oom --timeout=90s 2>&1 | tail -1 || true
k -n stress logs job/k6-p3-oom 2>&1 | tee /tmp/p3-k6.log | \
  grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded|http_reqs\.\." | tail -8
# trap cleans chaos + job
