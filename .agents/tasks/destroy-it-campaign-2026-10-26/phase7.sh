#!/usr/bin/env bash
# PHASE 7 — network latency + REAL spot/node churn via AWS FIS send-spot-instance-interruptions.
# Re-resolves ng-spot-1 by BOTH tags and ABORTS if the set != exactly the xamops-test-1 ng-spot-1
# instance(s). NEVER touches xamops-test-2. Trap deletes NetworkChaos + k6 + FIS template.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

ROLE=destroy-it-fis-spot-role
TPL_ID=""
cleanup() {
  echo "=== [phase7 trap] cleanup ==="
  k -n stress delete networkchaos p7-net-latency --ignore-not-found >/dev/null 2>&1
  k -n stress delete job k6-p7-net --ignore-not-found >/dev/null 2>&1
  [ -n "$TPL_ID" ] && aws fis delete-experiment-template --region "$REGION" --id "$TPL_ID" >/dev/null 2>&1
  prom_pf_stop
  echo "=== [phase7 trap] done ==="
}
trap cleanup EXIT INT TERM

assert_ctx || { echo "ABORT phase7"; exit 1; }

# ---- SAFETY: re-resolve ng-spot-1 by BOTH tags; abort if not exactly the xamops-test-1 spot set ----
echo "=== re-resolve ng-spot-1 (BOTH tags) ==="
SPOT_JSON=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:eks:cluster-name,Values=xamops-test-1" \
            "Name=tag:eks:nodegroup-name,Values=ng-spot-1" \
            "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output json 2>/dev/null)
SPOT=()
while IFS= read -r line; do [ -n "$line" ] && SPOT+=("$line"); done < <(echo "$SPOT_JSON" | python3 -c 'import sys,json;[print(x) for x in json.load(sys.stdin)]')
echo "resolved spot instances: ${SPOT[*]:-<none>}"
if [ "${#SPOT[@]}" -eq 0 ]; then echo "ABORT: no ng-spot-1 instance resolved"; exit 1; fi
# cross-check NONE of them belong to test-2
T2=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:eks:cluster-name,Values=xamops-test-2" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null)
for s in "${SPOT[@]}"; do
  if echo "$T2" | grep -qw "$s"; then echo "ABORT: $s is in xamops-test-2 — refusing"; exit 1; fi
done
# build resource ARNs
ARNS=""
for s in "${SPOT[@]}"; do ARNS="$ARNS\"arn:aws:ec2:${REGION}:${ACCT}:instance/${s}\","; done
ARNS="[${ARNS%,}]"
echo "FIS target ARNs: $ARNS"

# ---- network latency chaos on stress under k6 ----
cat <<'YAML' | k -n stress apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: k6-p7-net, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "7"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1800
  template:
    metadata: {labels: {app: k6-p7-net, xamops.io/campaign: destroy-it}}
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
            - {name: HOLD, value: "160s"}
            - {name: RAMP_DOWN, value: "10s"}
            - {name: SLEEP, value: "0.1"}
          resources: {requests: {cpu: 150m, memory: 256Mi}, limits: {memory: 512Mi}}
          volumeMounts: [{name: script, mountPath: /scripts}]
      volumes: [{name: script, configMap: {name: k6-fleet-script}}]
YAML
echo "k6-p7-net launched"; sleep 11

echo "=== INJECT (a): NetworkChaos latency 200ms±50ms on 40% stress pods, 120s ==="
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata: {name: p7-net-latency, namespace: stress}
spec:
  action: delay
  mode: fixed-percent
  value: "40"
  selector:
    namespaces: [stress]
    labelSelectors: {app: fleet-http}
  delay: {latency: "200ms", correlation: "50", jitter: "50ms"}
  duration: "120s"
YAML
echo "NetworkChaos applied"

# capture node state before interruption
echo "=== nodes BEFORE FIS (ng-spot-1) ==="
k get nodes -l eks.amazonaws.com/nodegroup=ng-spot-1 2>&1
SPOT_NODE_BEFORE=$(k get nodes -l eks.amazonaws.com/nodegroup=ng-spot-1 -o name 2>/dev/null | wc -l | tr -d ' ')

# ---- INJECT (b): FIS send-spot-instance-interruptions on EXACTLY the resolved set ----
echo "=== INJECT (b): create FIS experiment template (spot interruption, 2m warning) ==="
ROLE_ARN=$(aws iam get-role --role-name "$ROLE" --query 'Role.Arn' --output text 2>/dev/null)
TPL=$(cat <<JSON
{
  "description": "destroy-it P7 spot interruption on xamops-test-1 ng-spot-1",
  "roleArn": "${ROLE_ARN}",
  "stopConditions": [{"source":"none"}],
  "actions": {
    "interrupt": {
      "actionId": "aws:ec2:send-spot-instance-interruptions",
      "parameters": {"durationBeforeInterruption":"PT2M"},
      "targets": {"SpotInstances":"ng-spot-1-set"}
    }
  },
  "targets": {
    "ng-spot-1-set": {
      "resourceType": "aws:ec2:spot-instance",
      "resourceArns": ${ARNS},
      "selectionMode": "ALL"
    }
  },
  "tags": {"campaign":"destroy-it","cluster":"xamops-test-1","phase":"7"}
}
JSON
)
TPL_ID=$(aws fis create-experiment-template --region "$REGION" --cli-input-json "$TPL" --query 'experimentTemplate.id' --output text 2>&1)
echo "template id: $TPL_ID"
if ! echo "$TPL_ID" | grep -qE '^EXT'; then echo "ABORT: template create failed: $TPL_ID"; exit 1; fi

echo "=== start FIS experiment ==="
EXP_ID=$(aws fis start-experiment --region "$REGION" --experiment-template-id "$TPL_ID" --query 'experiment.id' --output text 2>&1)
echo "experiment id: $EXP_ID"

# observe the interruption + node churn + agent handling
for i in 1 2 3 4 5 6 7 8; do
  sleep 20
  ST=$(aws fis get-experiment --region "$REGION" --id "$EXP_ID" --query 'experiment.state.status' --output text 2>/dev/null)
  SPOTSTATE=$(aws ec2 describe-instances --region "$REGION" --instance-ids "${SPOT[0]}" --query 'Reservations[].Instances[].State.Name' --output text 2>/dev/null)
  SNODES=$(k get nodes -l eks.amazonaws.com/nodegroup=ng-spot-1 --no-headers 2>/dev/null | awk '{print $2}' | tr '\n' ',')
  echo "t+$((i*20))s  fis=$ST  spot-instance=$SPOTSTATE  spot-nodes=[$SNODES]"
done

echo "=== agent interruption handling (gate + counters) ==="
LEADER=$(k -n xamops-system get lease xamops-automation-leader -o jsonpath='{.spec.holderIdentity}' 2>/dev/null)
k -n xamops-system port-forward "pod/$LEADER" 19090:9090 >/tmp/pf7.log 2>&1 & PF=$!; sleep 4
curl -s http://localhost:19090/metrics 2>/dev/null | grep -E 'xamops_gate_decision\{controller="interruption"|xamops_consolidation_pods_evicted_total|xamops_orphans_recovered' | head
kill $PF 2>/dev/null

echo "=== DB pods still intact during churn? ==="
k -n xamops-db-fixtures get pods 2>/dev/null | grep -E "pg-|mysql-|mongo-[0-9]" | awk '{print $1,$3}'

echo "=== serving under net-latency + spot churn (k6) ==="
k -n stress wait --for=condition=complete job/k6-p7-net --timeout=90s 2>&1 | tail -1 || true
k -n stress logs job/k6-p7-net 2>&1 | tee /tmp/p7-k6.log | \
  grep -E "http_req_failed\.|http_req_duration\.|checks_succeeded|http_reqs\.\." | tail -8

echo "=== nodes AFTER (ng-spot-1) ==="
k get nodes -l eks.amazonaws.com/nodegroup=ng-spot-1 2>&1
# trap deletes chaos+job+template
