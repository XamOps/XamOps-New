#!/usr/bin/env zsh
# E5 — STATEFUL / DB GAPS (OBSERVE-ONLY). Chaos Mesh only on xamops-db-fixtures.
# ng-stateful-1 is FENCED (cordoned) from any live drain. Agent stays DRY_RUN=true,
# node-optimizer analyse-only — we RECORD the decision, never let it act.
# Trap teardown is installed BEFORE the first mutation and is idempotent.
set -u
setopt sh_word_split 2>/dev/null || true

CTX="arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
REGION="ap-south-1"
ACCT="102227938259"
NS="xamops-db-fixtures"
EV="/Users/apple/Desktop/XamOps-New/.agents/tasks/break-it-campaign-2026-10-25/evidence/E5-stateful"
MAN="/Users/apple/Desktop/XamOps-New/.agents/tasks/break-it-campaign-2026-10-25/e5-manifests"
STATEFUL_NODES=(ip-172-31-23-193 ip-172-31-4-95 ip-172-31-46-10)
RUNLOG="$EV/00-run.log"
SENTINEL="$EV/TEARDOWN_DONE"

mkdir -p "$EV"
log() { print -r -- "[$(date -u +%H:%M:%S)] $*" | tee -a "$RUNLOG"; }
k() { kubectl --context "$CTX" "$@"; }

# ---- guards -----------------------------------------------------------------
ctx_guard() {
  local acct cur
  acct=$(aws sts get-caller-identity --region "$REGION" --query Account --output text 2>/dev/null)
  if [[ "$acct" != "$ACCT" ]]; then log "FATAL ctx_guard: account $acct != $ACCT"; exit 3; fi
}
assert_fence() {
  # every stateful node MUST be cordoned (unschedulable=true) during the whole run
  local n bad=0
  for n in "${STATEFUL_NODES[@]}"; do
    local u
    u=$(k get node "$n.ap-south-1.compute.internal" -o jsonpath='{.spec.unschedulable}' 2>/dev/null)
    if [[ "$u" != "true" ]]; then log "FENCE BREACH: $n unschedulable=$u"; bad=1; fi
  done
  return $bad
}

# ---- teardown (idempotent, best-effort) -------------------------------------
run_teardown() {
  [[ -f "$SENTINEL" ]] && return 0
  set +e
  log "=== TEARDOWN START ==="
  # 1) delete every E5 chaos experiment CRD in the DB fixtures ns
  for kind in networkchaos podchaos stresschaos; do
    k -n "$NS" delete "$kind" --all --ignore-not-found --timeout=60s 2>&1 | tee -a "$RUNLOG"
  done
  # 2) uncordon the fenced stateful nodes
  for n in "${STATEFUL_NODES[@]}"; do
    k uncordon "$n.ap-south-1.compute.internal" 2>&1 | tee -a "$RUNLOG"
  done
  # 3) remove the campaign-added chaos-mesh inject annotation (residue)
  k annotate ns "$NS" chaos-mesh.org/inject- 2>&1 | tee -a "$RUNLOG"
  # 4) prove the restore contract: all 7 DB pods Running, uninterrupted
  log "--- post-teardown DB pod state ---"
  k -n "$NS" get pods -o wide 2>&1 | tee -a "$RUNLOG" "$EV/90-teardown-db-pods.txt"
  log "--- post-teardown node schedulability ---"
  for n in "${STATEFUL_NODES[@]}"; do
    k get node "$n.ap-south-1.compute.internal" -o jsonpath='{.metadata.name}{" unschedulable="}{.spec.unschedulable}{"\n"}' 2>&1 | tee -a "$RUNLOG" "$EV/90-teardown-nodes.txt"
  done
  # 5) confirm agent config still baseline (DRY_RUN=true) — we never touched it
  k -n xamops-system get cm xamops-automation-config -o jsonpath='{.data.DRY_RUN}{"\n"}' 2>&1 | tee -a "$RUNLOG"
  log "=== TEARDOWN DONE ==="
  : > "$SENTINEL"
}
trap 'run_teardown' EXIT INT TERM

# ================= BASELINE + FENCE (first mutation) =========================
: > "$RUNLOG"
log "E5 start. ctx=$CTX"
ctx_guard

log "--- record DB pod baseline (UIDs + start times prove uninterrupted) ---"
k -n "$NS" get pods -o custom-columns='NAME:.metadata.name,UID:.metadata.uid,START:.status.startTime,NODE:.spec.nodeName,PHASE:.status.phase,RESTARTS:.status.containerStatuses[0].restartCount' 2>&1 \
  | tee "$EV/10-db-pods-before.txt"

log "--- FENCE: cordon the 3 ng-stateful-1 nodes (belt+braces; no drain flag is armed) ---"
for n in "${STATEFUL_NODES[@]}"; do
  k cordon "$n.ap-south-1.compute.internal" 2>&1 | tee -a "$RUNLOG"
done
if ! assert_fence; then log "FATAL: fence not established"; exit 4; fi
log "FENCE OK — all 3 stateful nodes cordoned."

log "--- enable Chaos Mesh injection on $NS (annotation; F-E3-1 defect fix) ---"
k annotate ns "$NS" chaos-mesh.org/inject=enabled --overwrite 2>&1 | tee -a "$RUNLOG"

# capture_decision <tag>: record agent gate + consolidation decision + DB-node analysis
capture_decision() {
  local tag="$1" p
  local leader
  leader=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
  log "[$tag] leader=$leader"
  {
    echo "### $tag @ $(date -u +%H:%M:%SZ)  leader=$leader"
    echo "--- latest gate + consolidation lines (leader) ---"
    for p in $(k -n xamops-system get pods -l app=xamops-automation -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
      k -n xamops-system logs "$p" --since=90s 2>/dev/null \
        | grep -iE "Consolidation \(look-only\)|node-optimizer|idle-drain|cordon=|spot-ratio|rightsizer|evict|Drain budget|actor=" \
        | tail -8
    done
    echo "--- DB pods now (survival check) ---"
    k -n "$NS" get pods -o wide 2>/dev/null
    echo ""
  } | tee -a "$EV/20-decisions.txt"
}

inject() {
  local file="$1" name="$2" hold="$3"
  log ">>> inject $name (hold ${hold}s)"
  assert_fence || { log "FENCE BREACH before $name — abort"; exit 5; }
  k apply -f "$file" 2>&1 | tee -a "$RUNLOG"
  sleep 5
  k -n "$NS" get networkchaos,podchaos,stresschaos "$name" -o wide 2>&1 | tee -a "$RUNLOG"
  sleep "$hold"
  capture_decision "after-$name"
  k delete -f "$file" --ignore-not-found 2>&1 | tee -a "$RUNLOG"
  sleep 10
}

log "===== BASELINE DECISION (no fault) ====="
capture_decision "baseline-no-fault"

log "===== F1: CNPG replica replication-lag (NetworkChaos delay) ====="
inject "$MAN/f1-cnpg-replica-delay.yaml" e5-f1-cnpg-replica-delay 150

log "===== F2: kill CNPG primary (operator failover) ====="
inject "$MAN/f2-cnpg-primary-kill.yaml" e5-f2-cnpg-primary-kill 60

log "===== F3: kill Mongo primary (replica-set re-election) ====="
inject "$MAN/f3-mongo-primary-kill.yaml" e5-f3-mongo-primary-kill 60

log "===== F4: memory-stress a Mongo DB pod (rightsizer floor vs WT cache) ====="
inject "$MAN/f4-mongo-mem-stress.yaml" e5-f4-mongo-mem-stress 120

log "===== POST: final DB pod state (compare UIDs to baseline) ====="
k -n "$NS" get pods -o custom-columns='NAME:.metadata.name,UID:.metadata.uid,START:.status.startTime,NODE:.spec.nodeName,PHASE:.status.phase,RESTARTS:.status.containerStatuses[0].restartCount' 2>&1 \
  | tee "$EV/30-db-pods-after.txt"

log "E5 experiments complete — teardown runs on exit."
