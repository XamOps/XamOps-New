#!/usr/bin/env bash
# Shared helpers for the destroy-it campaign attack phases.
# Pins context + region on EVERY call; asserts account before destructive AWS/FIS ops.
# NEVER switches the global kube context. NEVER touches xamops-test-2 / k8opt / AutoSpotting.

export CTX="arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
export REGION="ap-south-1"
export ACCT="102227938259"
export CAMP="/Users/apple/Desktop/XamOps-New/.agents/tasks/destroy-it-campaign-2026-10-26"

k() { kubectl --context "$CTX" "$@"; }
h() { helm --kube-context "$CTX" "$@"; }

# assert_ctx: verify pinned context resolves to xamops-test-1 AND caller is acct 102227938259.
# ABORT (return 1) the single call if not — caller decides to skip/continue.
assert_ctx() {
  local resolved acct
  resolved="$(kubectl --context "$CTX" config view --minify -o jsonpath='{.clusters[0].name}' 2>/dev/null)"
  if [ "$resolved" != "$CTX" ]; then
    echo "ABORT: pinned context did not resolve to xamops-test-1 (got: $resolved)" >&2
    return 1
  fi
  acct="$(aws sts get-caller-identity --region "$REGION" --query Account --output text 2>/dev/null)"
  if [ "$acct" != "$ACCT" ]; then
    echo "ABORT: caller account is $acct, expected $ACCT" >&2
    return 1
  fi
  echo "ctx-ok: $resolved | acct $acct"
  return 0
}

# prom_query: run an instant PromQL query via a transient port-forward to in-cluster Prometheus.
# Usage: prom_query 'promql expression'  -> prints compact "<labels> <value>" lines.
PROM_PF_PORT=19091
prom_pf_start() {
  pkill -f "port-forward.*kube-prometheus-stack-prometheus 1909" 2>/dev/null
  kubectl --context "$CTX" -n break-observability port-forward svc/kube-prometheus-stack-prometheus ${PROM_PF_PORT}:9090 >/tmp/pf-prom.log 2>&1 &
  sleep 4
}
prom_pf_stop() { pkill -f "port-forward.*kube-prometheus-stack-prometheus 1909" 2>/dev/null; }
prom_query() {
  local q="$1"
  curl -s "http://localhost:${PROM_PF_PORT}/api/v1/query" --data-urlencode "query=$q" 2>/dev/null | python3 -c '
import sys,json
try:
    d=json.load(sys.stdin)
except Exception as e:
    print("QUERY-ERROR",e); sys.exit(0)
if d.get("status")!="success":
    print("QUERY-FAIL",d.get("error","")); sys.exit(0)
r=d["data"]["result"]
if not r:
    print("(empty)")
for x in r[:25]:
    m=dict(x["metric"]); name=m.pop("__name__","")
    lbl=",".join("%s=%s"%(k,v) for k,v in m.items())
    print("%s{%s} = %s" % (name, lbl, x["value"][1]))'
}
