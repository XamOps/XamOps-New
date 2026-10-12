#!/usr/bin/env bash
# Deploy / reconfigure the automation harness on one test cluster.
#
#   ./deploy.sh <cluster> stub                      # namespace, key Secret, stub
#   ./deploy.sh <cluster> policy policies/<f>.json  # swap the bundle the stub serves
#   ./deploy.sh <cluster> agent [AGENT_IMAGE=ref]   # xamops-agent -> stub
#   ./deploy.sh <cluster> autoscaler [VAR=val ...]  # xamops-autoscaler -> stub
#        VARs: DRY_RUN (default true) and ENABLE_EVICTOR, ENABLE_RIGHTSIZER,
#              ENABLE_RIGHTSIZER_APPLY, ENABLE_REBALANCER, ENABLE_HPA_MANAGER,
#              ENABLE_VPA_MANAGER, ENABLE_NODE_OPTIMIZER (default false);
#              AUTOSCALER_IMAGE (default: the pinned digest below)
#        AGENT_IMAGE / AUTOSCALER_IMAGE default to the last pinned ECR digests; pass
#        the refs printed by build-images.sh to deploy a new build.
#   ./deploy.sh <cluster> wait-policy policies/<f>.json  # block until the stub serves it
#
# <cluster> is xamops-test-1 or xamops-test-2. The harness API key is generated
# once, stored only in the cluster Secret xamops-system/xamops-stub-key, and
# rendered into manifests on stdin — it is never written to disk or to git.
# Nothing here can point at a real XamOps environment: the only server URL in
# the rendered manifests is the in-cluster stub Service.
set -euo pipefail

CLUSTER="${1:?cluster}"; ACTION="${2:?action}"; shift 2
case "$CLUSTER" in xamops-test-1|xamops-test-2) ;; *) echo "unknown cluster $CLUSTER" >&2; exit 2;; esac
CTX="arn:aws:eks:ap-south-1:102227938259:cluster/${CLUSTER}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K=(kubectl --context "$CTX")

key() {
  "${K[@]}" -n xamops-system get secret xamops-stub-key -o jsonpath='{.data.STUB_API_KEY}' | base64 -d
}

render() {  # render <file> [VAR=val ...] — substitutes __NAME__ tokens, prints to stdout
  local file="$1"; shift
  KEY="$(key)" CLUSTER="$CLUSTER" python3 - "$file" "$@" <<'EOF'
import os, sys
text = open(sys.argv[1]).read()
REPO = "102227938259.dkr.ecr.ap-south-1.amazonaws.com/xamops-cloudk8s-harness"
vals = {"DRY_RUN": "true", "ENABLE_EVICTOR": "false", "ENABLE_RIGHTSIZER": "false",
        "ENABLE_RIGHTSIZER_APPLY": "false",
        "ENABLE_REBALANCER": "false", "ENABLE_HPA_MANAGER": "false",
        "ENABLE_VPA_MANAGER": "false", "ENABLE_NODE_OPTIMIZER": "false",
        # pinned builds of main HEAD 1aabb2500b2a; override with the refs from build-images.sh
        "AUTOSCALER_IMAGE": REPO + ":autoscaler-1aabb2500b2a@sha256:2e8c9afe9d2b0142601ca1e3b720f5f12b5c3ea3e5c6f1385029f421676838ef",
        "AGENT_IMAGE": REPO + ":agent-1aabb2500b2a@sha256:2ebcb01683e045ca2a83d9f2eb3da50babe36bb6d6cfc5441edf73a677327802"}
for a in sys.argv[2:]:
    k, v = a.split("=", 1)
    if k not in vals:
        sys.exit(f"unknown variable {k}")
    vals[k] = v
vals["HARNESS_TEST_KEY"] = os.environ["KEY"]
vals["CLUSTER_NAME"] = os.environ["CLUSTER"]
for k, v in vals.items():
    text = text.replace(f"__{k}__", v)
import re
left = re.findall(r"__[A-Z][A-Z_]*__", text)
if left:
    sys.exit("unrendered tokens: " + ", ".join(sorted(set(left))))
sys.stdout.write(text)
EOF
}

case "$ACTION" in
  stub)
    "${K[@]}" apply -f "$HERE/stub/stub.yaml" --dry-run=client -o name >/dev/null
    "${K[@]}" get ns xamops-system >/dev/null 2>&1 || "${K[@]}" create ns xamops-system
    if ! "${K[@]}" -n xamops-system get secret xamops-stub-key >/dev/null 2>&1; then
      "${K[@]}" -n xamops-system create secret generic xamops-stub-key \
        --from-literal=STUB_API_KEY="xh-$(python3 -c 'import secrets;print(secrets.token_hex(24))')"
    fi
    "${K[@]}" -n xamops-system create configmap xamops-stub-code \
      --from-file=stub.py="$HERE/stub/stub.py" --dry-run=client -o yaml | "${K[@]}" apply -f -
    "${K[@]}" -n xamops-system get configmap xamops-stub-policy >/dev/null 2>&1 ||
      "${K[@]}" -n xamops-system create configmap xamops-stub-policy \
        --from-file=policy.json="$HERE/policies/00-default-bundle.json"
    "${K[@]}" apply -f "$HERE/stub/stub.yaml"
    "${K[@]}" -n xamops-system rollout status deploy/xamops-stub --timeout=180s
    ;;
  policy)
    f="${1:?policy file}"
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f"
    "${K[@]}" -n xamops-system create configmap xamops-stub-policy \
      --from-file=policy.json="$f" --dry-run=client -o yaml | "${K[@]}" apply -f -
    ;;
  wait-policy)
    f="${1:?policy file}"; want="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("_label",""))' "$f")"
    for _ in $(seq 1 60); do
      got="$("${K[@]}" -n xamops-system exec deploy/xamops-stub -- python3 -c \
        'import json;print(json.load(open("/etc/stub-policy/policy.json")).get("_label",""))' 2>/dev/null || true)"
      [[ "$got" == "$want" ]] && { echo "stub now serves policy '$want'"; exit 0; }
      sleep 5
    done
    echo "timed out waiting for policy '$want' (stub serves '$got')" >&2; exit 1
    ;;
  agent)
    render "$HERE/agent.yaml" "$@" | "${K[@]}" apply -f -
    "${K[@]}" -n xamops-system rollout status deploy/xamops-agent --timeout=180s
    ;;
  autoscaler)
    render "$HERE/autoscaler.yaml" "$@" | "${K[@]}" apply -f -
    # env comes from a ConfigMap via envFrom, so a flag change needs a new pod
    "${K[@]}" -n xamops-system rollout restart deploy/xamops-automation
    "${K[@]}" -n xamops-system rollout status deploy/xamops-automation --timeout=180s
    "${K[@]}" -n xamops-system get cm xamops-automation-config -o jsonpath='{.data}'; echo
    ;;
  *) echo "unknown action $ACTION" >&2; exit 2;;
esac
