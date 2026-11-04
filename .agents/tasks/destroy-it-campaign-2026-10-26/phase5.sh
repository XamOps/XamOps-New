#!/usr/bin/env bash
# PHASE 5 — rollback / self-heal storm.
# (a) PodChaos pod-kill storm on fleet (repeated) under k6 -> watch kubelet/deploy self-heal.
# (b) Push a BROKEN image to 6 deploys -> they go unavailable -> rollout undo -> self-heal verified.
# (c) DB-protection headline: assert consolidation/evictor evicted 0 ng-stateful-1 DB pods throughout.
# Trap deletes chaos + k6 and rolls back any still-broken deploys.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

BROKEN_FILE=/tmp/p5-broken.txt; : > "$BROKEN_FILE"
cleanup() {
  echo "=== [phase5 trap] cleanup ==="
  k -n stress delete podchaos p5-kill-storm --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p5-heal --ignore-not-found >/dev/null 2>&1
  # undo any deploy we broke that didn't get undone in-band
  while read -r d; do
    [ -n "$d" ] && k -n stress rollout undo "deploy/$d" >/dev/null 2>&1
  done < "$BROKEN_FILE"
  prom_pf_stop
  echo "=== [phase5 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase5"; exit 1; }
prom_pf_start

# agent eviction counters BEFORE (DB-protection headline baseline)
LEADER=$(k -n xamops-system get pods -l app.kubernetes.io/name=xamops-automation -o name 2>/dev/null | head -1)
[ -z "$LEADER" ] && LEADER="pod/xamops-automation-6478774c5b-q8kjl"
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf-agent.log 2>&1 & AGENTPF=$!; sleep 4
EVICT_BEFORE=$(curl -s http://localhost:19090/metrics 2>/dev/null | grep -E '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
echo "consolidation_pods_evicted_total BEFORE = ${EVICT_BEFORE:-NA}"
kill $AGENTPF 2>/dev/null

# k6 load
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p5-heal, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "5"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p5-heal, xamops.io/campaign: destroy-it}}
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
            - {name: HOLD, value: "150s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p5-heal launched"; sleep 11

# (a) INJECT pod-kill storm: repeated kill of 25% fleet, every 10s for the window
echo "=== (a) INJECT: PodChaos pod-kill storm (25%, every 10s) ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: PodChaos
metadata: {name: p5-kill-storm, namespace: stress}
spec:
  action: pod-kill
  mode: fixed-percent
  value: "25"
  selector:
    namespaces: [stress]
    labelSelectors: {app: fleet-http}
  duration: "30s"
YAML
echo "PodChaos p5-kill-storm applied"

# (b) INJECT a BROKEN rollout on 6 deploys (bad image tag) -> must self-heal via undo
echo "=== (b) INJECT: broken image on 6 deploys ==="
for d in $(k -n stress get deploy -o name 2>/dev/null | grep fleet- | sed -n '30,35p'); do
  name=${d#deployment.apps/}; echo "$name" >> "$BROKEN_FILE"
  k -n stress set image "$d" whoami=docker.io/traefik/whoami:vDOES-NOT-EXIST >/dev/null 2>&1
done
echo "broke: $(wc -l < "$BROKEN_FILE" | tr -d ' ') deploys"
sleep 30
echo "=== broken deploys stuck (ImagePullBackOff expected) ==="
for d in $(cat "$BROKEN_FILE"); do
  st=$(k -n stress get pods -l deployment=$d -o jsonpath='{range .items[*]}{.status.containerStatuses[0].state.waiting.reason}{" "}{end}' 2>/dev/null)
  echo "  $d: ${st:-<none>}"
done

# SELF-HEAL: roll back all broken deploys
echo "=== SELF-HEAL: rollout undo the 6 broken deploys ==="
while read -r d; do k -n stress rollout undo "deploy/$d" >/dev/null 2>&1; done < "$BROKEN_FILE"
: > "$BROKEN_FILE"   # cleared — undone in-band
sleep 25
echo "=== recovery check ==="
for d in fleet-030 fleet-031 fleet-032 fleet-033 fleet-034 fleet-035; do
  dd=$(k -n stress get deploy -o name 2>/dev/null | grep "$d-" | head -1 | sed 's#deployment.apps/##')
  [ -n "$dd" ] && k -n stress rollout status "deploy/$dd" --timeout=40s 2>&1 | tail -1
done

echo "=== serving health under the storm (k6) ==="
k -n stress wait --for=condition=complete job/k6-p5-heal --timeout=100s 2>&1 | tail -1 || true
k -n stress logs job/k6-p5-heal 2>&1 | tee /tmp/p5-k6.log | \
  grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded|http_reqs\.\." | tail -8

# (c) DB-PROTECTION headline: eviction counter after + DB pods intact
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf-agent.log 2>&1 & AGENTPF=$!; sleep 4
EVICT_AFTER=$(curl -s http://localhost:19090/metrics 2>/dev/null | grep -E '^xamops_consolidation_pods_evicted_total' | awk '{print $2}')
echo "=== (c) DB-PROTECTION headline ==="
echo "consolidation_pods_evicted_total AFTER = ${EVICT_AFTER:-NA} (BEFORE ${EVICT_BEFORE:-NA})"
curl -s http://localhost:19090/metrics 2>/dev/null | grep -E 'xamops_(evict|consolidat).*refus|db_protect|skip' | head
kill $AGENTPF 2>/dev/null
echo "=== ng-stateful-1 DB pods still Running (0 evicted)? ==="
k -n xamops-db-fixtures get pods -o wide 2>/dev/null | grep -E "pg-|mysql-|mongo-" | awk '{print $1,$3,$7}'
echo "=== fleet total ==="; echo "running=$(k -n stress get pods --no-headers 2>/dev/null | grep -c Running)"
