#!/usr/bin/env bash
# PHASE 8 — flapping (swinging usage). Oscillate load + resource pressure in several high/low cycles
# and watch whether the agent's rebalancer/consolidation THRASHES (repeated act/revert) or stays
# stable. Evidence = delta of xamops_consolidation_runs_total{status}, action_rate, drain inflight,
# evicted (must stay 0 on DBs), plus pod restart churn. Trap removes chaos + k6.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase8 trap] cleanup ==="
  k -n stress delete stresschaos p8-flap --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p8-flap-hi k6-p8-flap-lo --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  echo "=== [phase8 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase8"; exit 1; }

snap_agent() {  # print the thrash-relevant counters from the leader
  local LEADER; LEADER=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
  k -n xamops-system port-forward "pod/$LEADER" 19090:9090 >/tmp/pf8.log 2>&1 & local PF=$!; sleep 4
  curl -s http://localhost:19090/metrics 2>/dev/null | grep -E \
    '^xamops_consolidation_runs_total|^xamops_consolidation_pods_evicted_total|^xamops_consolidation_action_rate|^xamops_drain_budget_inflight|^xamops_cycle_duration_seconds_count' | grep -v '#'
  kill $PF 2>/dev/null
}

echo "=== BEFORE flapping — agent counters ==="
snap_agent | tee /tmp/p8-before.txt

# a reusable k6 job template function
launch_k6() {  # $1=name $2=vus $3=hold
  cat <<YAML | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: $1, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "8"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 900
  template:
    metadata: {labels: {app: $1, xamops.io/campaign: destroy-it}}
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run","/scripts/fleet.js"]
          env:
            - {name: TARGET, value: "http://fleet-http.stress.svc.cluster.local/"}
            - {name: VUS, value: "$2"}
            - {name: RAMP_UP, value: "5s"}
            - {name: HOLD, value: "$3"}
            - {name: RAMP_DOWN, value: "5s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 100m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
}

# INJECT: flapping stress — intermittent CPU stress that turns on/off (chaos-mesh handles on; we cycle load)
echo "=== INJECT: flapping load + intermittent CPU stress, 4 swing cycles ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: StressChaos
metadata: {name: p8-flap, namespace: stress}
spec:
  mode: fixed-percent
  value: "30"
  selector:
    namespaces: [stress]
    labelSelectors: {xamops.io/profile: burst}
  stressors:
    cpu: {workers: 2, load: 90}
  duration: "180s"
YAML
echo "StressChaos p8-flap applied (burst profile)"

for cyc in 1 2 3 4; do
  echo "--- SWING $cyc: HIGH load ---"
  k -n stress delete job k6-p8-flap-lo --ignore-not-found >/dev/null 2>&1
  launch_k6 k6-p8-flap-hi 90 20s
  sleep 28
  k -n stress delete job k6-p8-flap-hi --ignore-not-found >/dev/null 2>&1
  echo "--- SWING $cyc: LOW/idle load ---"
  launch_k6 k6-p8-flap-lo 5 15s
  sleep 22
  k -n stress delete job k6-p8-flap-lo --ignore-not-found >/dev/null 2>&1
  # mid-flap agent snapshot
  echo "[cyc$cyc] $(snap_agent | tr '\n' ' ')"
done

echo "=== AFTER flapping — agent counters ==="
snap_agent | tee /tmp/p8-after.txt

echo "=== THRASH analysis (before vs after) ==="
python3 - <<'PY'
def load(f):
    d={}
    for ln in open(f):
        ln=ln.strip()
        if not ln or ln.startswith('#'): continue
        parts=ln.rsplit(' ',1)
        if len(parts)==2:
            try: d[parts[0]]=float(parts[1])
            except: pass
    return d
b=load('/tmp/p8-before.txt'); a=load('/tmp/p8-after.txt')
keys=sorted(set(list(b)+list(a)))
for k in keys:
    bv=b.get(k,0); av=a.get(k,0)
    if av!=bv:
        print(f"DELTA {k}: {bv} -> {av}  (+{av-bv:g})")
# the headline thrash signals:
runs_skipped_b=sum(v for k,v in b.items() if 'runs_total' in k and 'skipped' in k)
runs_exec_b=sum(v for k,v in b.items() if 'runs_total' in k and 'executed' in k)
runs_skipped_a=sum(v for k,v in a.items() if 'runs_total' in k and 'skipped' in k)
runs_exec_a=sum(v for k,v in a.items() if 'runs_total' in k and 'executed' in k)
ev_b=sum(v for k,v in b.items() if 'pods_evicted' in k); ev_a=sum(v for k,v in a.items() if 'pods_evicted' in k)
print(f"\nSUMMARY: consolidation runs skipped {runs_skipped_b}->{runs_skipped_a}, executed {runs_exec_b}->{runs_exec_a}, pods_evicted {ev_b}->{ev_a}")
print("THRASH?" , "NO (no executed runs, no evictions = stable, correctly refused)" if (runs_exec_a-runs_exec_b)==0 and (ev_a-ev_b)==0 else "POSSIBLE — investigate executed/evicted jump")
PY

echo "=== pod restart churn during flap (stress) ==="
k -n stress get pods --no-headers 2>/dev/null | awk '{s+=$4} END{print "total restarts across stress:",s+0}'
echo "=== DB pods intact ==="
k -n xamops-db-fixtures get pods 2>/dev/null | grep -E "pg-|mysql-|mongo-[0-9]" | awk '{print $1,$3}'
# trap cleans
