#!/usr/bin/env bash
# PHASE 10b — harder api-server 429 pressure. Each of 12 Jobs forks 15 concurrent curl loops hitting the
# in-cluster apiserver REST endpoint directly with uncached list (limit=0, resourceVersion unset) to
# maximise etcd-read load and provoke APF 429s. Count HTTP 429 at the client directly.
set -uo pipefail
source /Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26/_lib.sh

cleanup() {
  echo "=== [phase10b trap] cleanup ==="
  k -n stress delete job -l xamops.io/phase=10b --ignore-not-found >/dev/null 2>&1
  prom_pf_stop
  pkill -f "port-forward.*19090:9090" 2>/dev/null
  echo "=== [phase10b trap] done ==="
}
trap cleanup EXIT INT TERM
assert_ctx || { echo "ABORT phase10b"; exit 1; }
prom_pf_start

echo "=== apiserver 429 + inflight BEFORE ==="
prom_query 'sum(apiserver_request_total{code="429"})'
prom_query 'sum(apiserver_current_inflight_requests)'

# 12 jobs x 15 concurrent curl loops = 180-way concurrent uncached LIST against kubernetes.default
echo "=== INJECT: 12 jobs x 15 concurrent uncached LIST loops (180-way) ==="
for i in $(seq 1 12); do
cat <<YAML | k apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata: {name: api-burst-$i, namespace: stress, labels: {xamops.io/campaign: destroy-it, xamops.io/phase: "10b"}}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 1200
  activeDeadlineSeconds: 180
  template:
    metadata: {labels: {app: api-burst, xamops.io/phase: "10b"}}
    spec:
      restartPolicy: Never
      serviceAccountName: api-hammer
      containers:
        - name: burst
          image: bitnami/kubectl:latest
          command: ["/bin/bash","-c"]
          args:
            - |
              T=\$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
              A=https://kubernetes.default.svc
              CA=/var/run/secrets/kubernetes.io/serviceaccount/ca.crt
              hit() {
                local c=0 e=0 end=\$((SECONDS+120))
                while [ \$SECONDS -lt \$end ]; do
                  code=\$(curl -s -o /dev/null -w '%{http_code}' --cacert \$CA -H "Authorization: Bearer \$T" \
                    "\$A/api/v1/pods?limit=0" 2>/dev/null)
                  c=\$((c+1)); [ "\$code" = "429" ] && e=\$((e+1))
                done
                echo "\$c \$e" >> /tmp/res
              }
              : > /tmp/res
              for w in \$(seq 1 15); do hit & done
              wait
              tot=\$(awk '{c+=\$1;e+=\$2} END{print c" "e}' /tmp/res)
              echo "BURST done: total-429 => \$tot"
          resources: {requests: {cpu: 150m, memory: 128Mi}, limits: {memory: 256Mi}}
YAML
done
echo "12 burst jobs launched (180-way concurrency)"

PEAK=0
for i in 1 2 3 4 5 6; do
  sleep 25
  INFLIGHT=$(prom_query 'sum(apiserver_current_inflight_requests)' | sed -E 's/.*= //')
  R429=$(prom_query 'sum(rate(apiserver_request_total{code="429"}[1m]))' | sed -E 's/.*= //')
  echo "--- t+$((i*25))s inflight=$INFLIGHT  429rate/s=$R429 ---"
  k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null | awk '{print "  agent:",$1,$3,"restarts="$4}'
done

echo "=== client-observed 429s (authoritative breaking-point evidence) ==="
TOTREQ=0; TOT429=0
for i in $(seq 1 12); do
  line=$(k -n stress logs job/api-burst-$i 2>/dev/null | grep 'BURST done' | sed 's/.*=> //')
  echo "  burst-$i: $line"
  r=$(echo $line | awk '{print $1}'); e=$(echo $line | awk '{print $2}')
  TOTREQ=$((TOTREQ + ${r:-0})); TOT429=$((TOT429 + ${e:-0}))
done
echo "=== TOTAL client requests=$TOTREQ  client-observed-429=$TOT429 ==="
echo "=== apiserver 429 + APF rejects AFTER ==="
prom_query 'sum(apiserver_request_total{code="429"})'
prom_query 'sum(apiserver_flowcontrol_rejected_requests_total)'
echo "=== agent AFTER ==="
k -n xamops-system get pods -l app=xamops-automation --no-headers 2>/dev/null
k -n xamops-system get lease -o jsonpath='{range .items[*]}{.metadata.name}={.spec.holderIdentity}{"\n"}{end}' 2>/dev/null | grep -i xamops
echo "PHASE10b-DONE"
