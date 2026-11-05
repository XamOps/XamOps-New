#!/usr/bin/env bash
# E3 — AGENT RESILIENCE (Chaos Mesh on xamops-system) — break-it campaign
# One script. Trap-based teardown installed BEFORE the first mutation.
# Every kubectl pins --context to xamops-test-1. Chaos injects ONLY on xamops-system
# (namespace-filter ON). NEVER kube-system / xamops-test-2 / control plane / IAM / VPC.
set -u -o pipefail

# ---- constants -------------------------------------------------------------
CTX="arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
REGION="ap-south-1"
ACCT="102227938259"
NS_AGENT="xamops-system"
NS_CHAOS="chaos-testing"
SEL="app=xamops-automation"       # agent pod selector (the ONLY chaos target)
LEASE="xamops-automation-leader"
NS_OBS="break-observability"
EV="/Users/apple/Desktop/XamOps-New/.agents/tasks/break-it-campaign-2026-10-25/evidence"
FDIR="$EV/E3-faults"
STREAMS="$EV/E3-streams"
GRAF="$EV/grafana"
SOAKWATCH="/tmp/break-it-bin/soakwatch"
TEARDOWN_SENTINEL="$EV/E3-TEARDOWN_DONE"
LOG="$FDIR/00-run.log"

mkdir -p "$FDIR" "$STREAMS" "$GRAF"
: > "$LOG"
log(){ echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$LOG"; }

kc(){ kubectl --context "$CTX" "$@"; }

# ---- guards (asserted before every mutation) -------------------------------
ctx_guard(){
  local cur acct
  acct="$(aws sts get-caller-identity --region "$REGION" --query Account --output text 2>/dev/null)"
  if [ "$acct" != "$ACCT" ]; then log "FATAL ctx_guard: account $acct != $ACCT"; exit 90; fi
  # confirm the pinned cluster is reachable and is xamops-test-1
  if ! kc version >/dev/null 2>&1; then log "FATAL ctx_guard: cannot reach $CTX"; exit 91; fi
}
# refuse to ever address a non-xamops-system namespace for chaos
ns_guard(){
  local ns="$1"
  if [ "$ns" != "$NS_AGENT" ]; then log "FATAL ns_guard: chaos target ns=$ns is not $NS_AGENT"; exit 92; fi
}

# ---- metrics helpers (port-forward; never print secrets) -------------------
PROM_PF=""
prom_up(){
  kc -n "$NS_OBS" port-forward svc/kube-prometheus-stack-prometheus 19092:9090 >/tmp/e3-pf-prom.log 2>&1 &
  PROM_PF=$!; sleep 4
}
prom_down(){ [ -n "$PROM_PF" ] && kill "$PROM_PF" 2>/dev/null; PROM_PF=""; }
promq(){ curl -s --max-time 8 "http://localhost:19092/api/v1/query?query=$1" | jq -c '.data.result' 2>/dev/null; }

# agent metrics snapshot straight off both pods (:9090)
agent_metrics(){
  local outfile="$1"
  : > "$outfile"
  local pods; pods="$(kc -n "$NS_AGENT" get pods -l "$SEL" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null)"
  for p in $pods; do
    local lp=$((20000 + RANDOM % 2000))
    kc -n "$NS_AGENT" port-forward "pod/$p" "$lp:9090" >/tmp/e3-pf-$p.log 2>&1 &
    local pf=$!; sleep 3
    echo "### pod $p" >> "$outfile"
    curl -s --max-time 6 "http://localhost:$lp/metrics" \
      | grep -E "^xamops_(leader|cycle_duration_seconds_(count|sum)|drains_total|policy_fetch_errors_total|orphans_recovered_total|drain_budget_inflight)" \
      >> "$outfile" 2>/dev/null
    kill "$pf" 2>/dev/null; wait "$pf" 2>/dev/null
  done
}

# capture leader/readiness/pod state into a numbered evidence file
capture_state(){
  local tag="$1"
  { echo "=== $tag @ $(date -u +%FT%TZ) ==="
    echo "--- pods ---";  kc -n "$NS_AGENT" get pods -l "$SEL" -o wide
    echo "--- lease ---"; kc -n "$NS_AGENT" get lease "$LEASE" -o jsonpath='{.spec.holderIdentity}{"\n"}'
  } >> "$FDIR/$tag.txt" 2>&1
  agent_metrics "$FDIR/$tag-metrics.txt"
}

# grafana PNG via render API (never print the password)
grafana_snapshot(){
  local tag="$1"
  local pw user pf lp=19093
  pw="$(kc -n "$NS_OBS" get secret grafana-admin -o jsonpath='{.data.admin-password}' 2>/dev/null | base64 -d)"
  user="$(kc -n "$NS_OBS" get secret grafana-admin -o jsonpath='{.data.admin-user}' 2>/dev/null | base64 -d)"
  [ -z "$user" ] && user="admin"
  kc -n "$NS_OBS" port-forward svc/kube-prometheus-stack-grafana "$lp:80" >/tmp/e3-pf-graf.log 2>&1 &
  pf=$!; sleep 4
  # list dashboards, snapshot the agent dashboard JSON model + a render if possible
  curl -s --max-time 8 -u "$user:$pw" "http://localhost:$lp/api/search?query=agent" \
     > "$GRAF/$tag-search.json" 2>/dev/null
  local uid; uid="$(jq -r '.[0].uid // empty' "$GRAF/$tag-search.json" 2>/dev/null)"
  if [ -n "$uid" ]; then
    curl -s --max-time 10 -u "$user:$pw" "http://localhost:$lp/api/dashboards/uid/$uid" \
       > "$GRAF/$tag-dashboard-model.json" 2>/dev/null
    # render PNG (requires image renderer; capture whatever comes back)
    curl -s --max-time 25 -u "$user:$pw" \
       "http://localhost:$lp/render/d-solo/$uid/?orgId=1&from=now-30m&to=now&panelId=1&width=1000&height=400" \
       -o "$GRAF/$tag.png" 2>/dev/null
    if [ ! -s "$GRAF/$tag.png" ] || head -c 16 "$GRAF/$tag.png" | grep -qv PNG 2>/dev/null; then
      echo "render API returned no PNG (image-renderer not installed); dashboard JSON model captured instead" > "$GRAF/$tag-render-note.txt"
      rm -f "$GRAF/$tag.png" 2>/dev/null
    fi
  fi
  pw=""; user=""
  kill "$pf" 2>/dev/null; wait "$pf" 2>/dev/null
}

wait_ready_2(){
  # wait up to $1 sec for 2/2 ready + a single lease holder
  local budget="${1:-180}" t=0 ready holder
  while [ "$t" -lt "$budget" ]; do
    ready="$(kc -n "$NS_AGENT" get deploy xamops-automation -o jsonpath='{.status.readyReplicas}' 2>/dev/null)"
    holder="$(kc -n "$NS_AGENT" get lease "$LEASE" -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)"
    if [ "$ready" = "2" ] && [ -n "$holder" ]; then echo "recovered: ready=2 holder=$holder"; return 0; fi
    sleep 5; t=$((t+5))
  done
  echo "NOT recovered within ${budget}s: ready=$ready holder=$holder"; return 1
}

# ---- TEARDOWN (idempotent; trap installed before first mutation) -----------
run_teardown(){
  set +e
  if [ -f "$TEARDOWN_SENTINEL" ]; then return 0; fi
  log "=== TEARDOWN: deleting all Chaos Mesh experiment CRDs in $NS_CHAOS ==="
  for kind in podchaos stresschaos networkchaos timechaos dnschaos iochaos httpchaos kernelchaos blockchaos jvmchaos; do
    kc -n "$NS_CHAOS" delete "$kind" --all --wait=false >/dev/null 2>&1
  done
  # stop any lingering soakwatch
  [ -n "${WATCH_PID:-}" ] && kill "$WATCH_PID" 2>/dev/null
  prom_down
  sleep 5
  log "=== TEARDOWN: confirm agent 2/2 + single leader ==="
  wait_ready_2 240 | tee -a "$LOG"
  { echo "=== post-teardown @ $(date -u +%FT%TZ) ==="
    kc -n "$NS_AGENT" get pods -l "$SEL" -o wide
    echo "--- lease holder ---"
    kc -n "$NS_AGENT" get lease "$LEASE" -o jsonpath='{.spec.holderIdentity}{"\n"}'
    echo "--- remaining chaos experiments (want: none) ---"
    for kind in podchaos stresschaos networkchaos timechaos dnschaos; do
      echo "[$kind]"; kc -n "$NS_CHAOS" get "$kind" 2>&1
    done
  } > "$FDIR/99-teardown-final.txt" 2>&1
  touch "$TEARDOWN_SENTINEL"
  log "=== TEARDOWN complete ==="
}
trap 'run_teardown' EXIT INT TERM

# ---- fault helpers ---------------------------------------------------------
# apply a chaos manifest (string), hold $hold sec, then delete it; capture around it.
# usage: run_fault <num-tag> <hold-sec> <manifest-file>
run_fault(){
  local tag="$1" hold="$2" mf="$3"
  ctx_guard
  # safety: assert the manifest targets ONLY xamops-system
  if grep -qE 'namespaces:' "$mf"; then
    if ! grep -qE "^\s*-\s*$NS_AGENT\s*$" "$mf"; then
      log "FATAL run_fault $tag: manifest does not scope selector to $NS_AGENT"; return 1
    fi
  fi
  log "FAULT $tag: apply (hold ${hold}s)"
  capture_state "$tag-before"
  kc -n "$NS_CHAOS" apply -f "$mf" > "$FDIR/$tag-apply.txt" 2>&1
  cp "$mf" "$FDIR/$tag-manifest.yaml"
  # mid-fault sample
  sleep $(( hold / 2 ))
  capture_state "$tag-mid"
  local half=$(( hold - hold/2 )); [ "$half" -lt 1 ] && half=1
  sleep "$half"
  log "FAULT $tag: release (delete chaos CRD)"
  kc -n "$NS_CHAOS" delete -f "$mf" --wait=false >> "$FDIR/$tag-apply.txt" 2>&1
  capture_state "$tag-after"
  wait_ready_2 180 | tee -a "$FDIR/$tag-recovery.txt" | tee -a "$LOG"
}

# =============================================================================
log "########## E3 START ##########"
ctx_guard
log "ctx_guard OK (account $ACCT, cluster xamops-test-1 reachable)"

# re-verify we still hold the lock exclusively
LOCK=/Users/apple/Desktop/XamOps-New/.agents/tasks/xamops-test-1.lock
if ! grep -q 'break-it-campaign-destructive' "$LOCK" 2>/dev/null; then
  log "FATAL: cluster lock not held by this campaign"; exit 93
fi
log "lock held by break-it-campaign-destructive"

# baseline snapshot
capture_state "01-baseline"
grafana_snapshot "E3-00-baseline"

# start soakwatch recording across the whole E3 window
log "starting soakwatch recording -> $STREAMS"
"$SOAKWATCH" watch --context "$CTX" --out "$STREAMS" \
   --duration 90m --agent-namespace "$NS_AGENT" --agent-selector "$SEL" --lease "$LEASE" --append \
   >"$FDIR/01-soakwatch.log" 2>&1 &
WATCH_PID=$!
sleep 8
log "soakwatch pid=$WATCH_PID"

MF=/Users/apple/Desktop/XamOps-New/.agents/tasks/break-it-campaign-2026-10-25/e3-manifests

# run a Schedule-based repeated kill: apply, hold, delete the Schedule
run_schedule(){
  local tag="$1" hold="$2" mf="$3"
  ctx_guard
  log "FAULT $tag: apply Schedule (hold ${hold}s)"
  capture_state "$tag-before"
  kc -n "$NS_CHAOS" apply -f "$mf" > "$FDIR/$tag-apply.txt" 2>&1
  cp "$mf" "$FDIR/$tag-manifest.yaml"
  prom_up
  local t=0
  while [ "$t" -lt "$hold" ]; do
    sleep 10; t=$((t+10))
    {
      echo "--- t=${t}s @ $(date -u +%FT%TZ) ---"
      echo "pods:"
      kc -n "$NS_AGENT" get pods -l "$SEL" --no-headers 2>&1
      echo "lease-holder: $(kc -n "$NS_AGENT" get lease "$LEASE" -o jsonpath='{.spec.holderIdentity}' 2>&1)"
      echo "prom sum(xamops_leader)=$(promq 'sum(xamops_leader)')"
      echo "prom count(xamops_leader==1)=$(promq 'count(xamops_leader==1)')"
    } >> "$FDIR/$tag-during.txt" 2>&1
  done
  prom_down
  log "FAULT $tag: delete Schedule"
  kc -n "$NS_CHAOS" delete -f "$mf" --wait=false >> "$FDIR/$tag-apply.txt" 2>&1
  # also purge any child podchaos the schedule spawned
  kc -n "$NS_CHAOS" delete podchaos --all --wait=false >> "$FDIR/$tag-apply.txt" 2>&1
  capture_state "$tag-after"
  agent_metrics "$FDIR/$tag-after-metrics.txt"
  wait_ready_2 240 | tee -a "$FDIR/$tag-recovery.txt" | tee -a "$LOG"
}

# ======================= FAULT SEQUENCE =======================
# F1a: kill (mode:one, leader churn) every 30s for 2 min
run_schedule  "10-F1a-kill-30s" 120 "$MF/f1a-kill-30s-schedule.yaml"
grafana_snapshot "E3-10-F1a"

# F1b: escalate — kill every 10s for 90s
run_schedule  "11-F1b-kill-10s" 90  "$MF/f1b-kill-10s-schedule.yaml"
grafana_snapshot "E3-11-F1b"

# F2: kill BOTH replicas at once (one-shot pod-kill, mode:all)
log "FAULT 20-F2: kill both replicas (mode:all)"
capture_state "20-F2-before"
kc -n "$NS_CHAOS" apply -f "$MF/f2-kill-both.yaml" > "$FDIR/20-F2-apply.txt" 2>&1
cp "$MF/f2-kill-both.yaml" "$FDIR/20-F2-manifest.yaml"
sleep 5; capture_state "20-F2-mid"
kc -n "$NS_CHAOS" delete -f "$MF/f2-kill-both.yaml" --wait=false >> "$FDIR/20-F2-apply.txt" 2>&1
capture_state "20-F2-after"
wait_ready_2 240 | tee -a "$FDIR/20-F2-recovery.txt" | tee -a "$LOG"
grafana_snapshot "E3-20-F2"

# F3a: CPU stress 90s
run_fault "30-F3a-cpu-stress" 90 "$MF/f3a-cpu-stress.yaml"
grafana_snapshot "E3-30-F3a"

# F3b: memory stress toward OOM 120s (BUG-001: 256Mi ceiling)
run_fault "31-F3b-mem-stress-oom" 120 "$MF/f3b-mem-stress-oom.yaml"
grafana_snapshot "E3-31-F3b"

# F4a: network delay 90s
run_fault "40-F4a-net-delay" 90 "$MF/f4a-net-delay.yaml"
# F4b: network loss 90s
run_fault "41-F4b-net-loss" 90 "$MF/f4b-net-loss.yaml"
# F4e: partition agent<->apiserver 90s
run_fault "42-F4e-partition-apiserver" 90 "$MF/f4e-net-partition-apiserver.yaml"
grafana_snapshot "E3-42-apiserver"

# F4c: partition agent<->backend 5 min (fail-closed proof)
log "FAULT 43-F4c: backend-unreachable 5min (fail-closed)"
capture_state "43-F4c-before"
kc -n "$NS_CHAOS" apply -f "$MF/f4c-net-partition-backend-5m.yaml" > "$FDIR/43-F4c-apply.txt" 2>&1
cp "$MF/f4c-net-partition-backend-5m.yaml" "$FDIR/43-F4c-manifest.yaml"
sleep 150; capture_state "43-F4c-mid"
# capture policy-fetch-error metric + agent logs showing fail-closed
agent_metrics "$FDIR/43-F4c-fetcherr-metrics.txt"
kc -n "$NS_AGENT" logs -l "$SEL" --tail=120 --prefix 2>&1 | grep -iE 'policy|fetch|default|disabled|unreachable|mode' | tail -60 > "$FDIR/43-F4c-agent-log.txt" 2>&1
sleep 160
kc -n "$NS_CHAOS" delete -f "$MF/f4c-net-partition-backend-5m.yaml" --wait=false >> "$FDIR/43-F4c-apply.txt" 2>&1
capture_state "43-F4c-after"
wait_ready_2 180 | tee -a "$FDIR/43-F4c-recovery.txt" | tee -a "$LOG"
grafana_snapshot "E3-43-F4c"

# F4d: partition agent<->backend 30 min (long fail-closed)
log "FAULT 44-F4d: backend-unreachable 30min (long fail-closed)"
capture_state "44-F4d-before"
kc -n "$NS_CHAOS" apply -f "$MF/f4d-net-partition-backend-30m.yaml" > "$FDIR/44-F4d-apply.txt" 2>&1
cp "$MF/f4d-net-partition-backend-30m.yaml" "$FDIR/44-F4d-manifest.yaml"
# sample at 5/15/28 min
for m in 300 600 780; do
  sleep "$m"
  { echo "=== +sample @ $(date -u +%FT%TZ) ==="; } >> "$FDIR/44-F4d-samples.txt"
  agent_metrics "$FDIR/44-F4d-metrics-$m.txt"
  kc -n "$NS_AGENT" logs -l "$SEL" --tail=60 --prefix 2>&1 | grep -iE 'policy|fetch|default|disabled|unreachable|mode|action' | tail -30 >> "$FDIR/44-F4d-samples.txt" 2>&1
done
kc -n "$NS_CHAOS" delete -f "$MF/f4d-net-partition-backend-30m.yaml" --wait=false >> "$FDIR/44-F4d-apply.txt" 2>&1
capture_state "44-F4d-after"
wait_ready_2 180 | tee -a "$FDIR/44-F4d-recovery.txt" | tee -a "$LOG"
grafana_snapshot "E3-44-F4d"

# F5: clock skew (T4 dwell) 120s
run_fault "50-F5-clock-skew" 120 "$MF/f5-clock-skew.yaml"
grafana_snapshot "E3-50-F5"

log "########## ALL FAULTS COMPLETE — stopping soakwatch ##########"
# stop soakwatch cleanly so streams flush
kill -TERM "$WATCH_PID" 2>/dev/null
wait "$WATCH_PID" 2>/dev/null
sleep 3

# run the committed invariants checker on recorded streams
log "running soak checker on $STREAMS"
"$SOAKWATCH" check --dir "$STREAMS" --max-parallel-drains 1 --leader-tolerance 5s \
   --report "$STREAMS/invariants-report.json" > "$FDIR/90-invariants.txt" 2>&1
echo "soakwatch check exit=$?" >> "$FDIR/90-invariants.txt"

# final metrics + leader proof
capture_state "91-final"
grafana_snapshot "E3-91-final"

log "########## E3 EXECUTION DONE (teardown runs on exit) ##########"
