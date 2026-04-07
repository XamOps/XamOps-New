#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy-agents.sh
# Deploys the XamOps unified observability agent into the EKS cluster.
# Single OTel-based agent: pod logs + host metrics + cluster metrics.
#
# Usage:
#   ./eks/agents/deploy-agents.sh
#
# Requirements:
#   - kubectl configured for the target cluster
#   - XamOps backend running (xamops-service pod must be Ready)
#   - XAMOPS_EMAIL and XAMOPS_PASSWORD env vars set (XamOps login credentials)
#     OR O2_ORG_ID set manually
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

AGENTS_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Config ────────────────────────────────────────────────────────────────────
O2_EMAIL="${O2_EMAIL:-aditya@xammer.in}"
O2_PASSWORD="${O2_PASSWORD:-Complexpass#123}"
O2_URL="${O2_URL:-http://openobserve.xamops.svc.cluster.local:5080}"

XAMOPS_EMAIL="${XAMOPS_EMAIL:-}"
XAMOPS_PASSWORD="${XAMOPS_PASSWORD:-}"

echo "=== XamOps Unified Observability Agent Deploy ==="
echo "Cluster: $(kubectl config current-context)"
echo ""

# ── Step 1: Get Org ID from XamOps API ───────────────────────────────────────
O2_ORG_ID="${O2_ORG_ID:-}"

if [[ -z "$O2_ORG_ID" ]] && [[ -n "$XAMOPS_EMAIL" ]] && [[ -n "$XAMOPS_PASSWORD" ]]; then
  echo "[1/5] Fetching Org ID from XamOps API..."

  kubectl port-forward -n xamops svc/xamops-service 18080:8080 &
  PF_PID=$!
  sleep 3

  COOKIE_FILE=$(mktemp)
  LOGIN_RESP=$(curl -s -c "$COOKIE_FILE" -X POST http://localhost:18080/login \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=${XAMOPS_EMAIL}&password=${XAMOPS_PASSWORD}" \
    -w "\n%{http_code}" 2>/dev/null || echo "000")
  HTTP_CODE=$(echo "$LOGIN_RESP" | tail -1)

  if [[ "$HTTP_CODE" == "200" ]] || [[ "$HTTP_CODE" == "302" ]]; then
    CONFIG_RESP=$(curl -s -b "$COOKIE_FILE" http://localhost:18080/api/observability/config 2>/dev/null || echo "{}")
    O2_ORG_ID=$(echo "$CONFIG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('orgId',''))" 2>/dev/null || echo "")
    echo "  Org ID: ${O2_ORG_ID}"
  else
    echo "  Warning: Login failed (HTTP ${HTTP_CODE}). Set O2_ORG_ID env var manually."
  fi

  rm -f "$COOKIE_FILE"
  kill "$PF_PID" 2>/dev/null || true
  wait "$PF_PID" 2>/dev/null || true
fi

if [[ -z "$O2_ORG_ID" ]]; then
  echo ""
  echo "ERROR: O2_ORG_ID is not set."
  echo ""
  echo "Options:"
  echo "  1. Set it manually:"
  echo "     export O2_ORG_ID=<your-org-id>   # from Observability -> Overview -> Org ID"
  echo "     ./eks/agents/deploy-agents.sh"
  echo ""
  echo "  2. Let this script fetch it automatically:"
  echo "     export XAMOPS_EMAIL=your@email.com"
  echo "     export XAMOPS_PASSWORD=yourpassword"
  echo "     ./eks/agents/deploy-agents.sh"
  exit 1
fi

# ── Step 2: Compute credentials ───────────────────────────────────────────────
echo "[2/5] Computing credentials..."
O2_AUTH_BASE64=$(echo -n "${O2_EMAIL}:${O2_PASSWORD}" | base64)

# ── Step 3: Create namespace + RBAC ──────────────────────────────────────────
echo "[3/5] Applying namespace and RBAC..."
kubectl apply -f "${AGENTS_DIR}/01-rbac.yaml"

# ── Step 4: Create secret with org ID + auth ─────────────────────────────────
echo "[4/5] Creating agent credentials secret..."
kubectl create secret generic o2-agent-credentials \
  --namespace xamops-system \
  --from-literal=O2_URL="${O2_URL}" \
  --from-literal=O2_ORG_ID="${O2_ORG_ID}" \
  --from-literal=O2_AUTH_BASE64="${O2_AUTH_BASE64}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "  Secret applied with Org ID: ${O2_ORG_ID}"

# ── Step 5: Deploy unified agent ──────────────────────────────────────────────
echo "[5/5] Deploying XamOps unified agent..."
kubectl apply -f "${AGENTS_DIR}/02-xamops-agent.yaml"

# ── Wait for rollouts ─────────────────────────────────────────────────────────
echo ""
echo "Waiting for agents to be ready..."
kubectl rollout status daemonset/xamops-node-agent    -n xamops-system --timeout=120s
kubectl rollout status deployment/xamops-cluster-agent -n xamops-system --timeout=120s

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Agent Status ==="
kubectl get pods -n xamops-system -o wide
echo ""
echo "Deployed!"
echo ""
echo "Data flow:"
echo "  Pod logs         -> xamops-node-agent DaemonSet    -> OpenObserve logs"
echo "  Host metrics     -> xamops-node-agent DaemonSet    -> OpenObserve metrics"
echo "  Kubelet stats    -> xamops-node-agent DaemonSet    -> OpenObserve metrics"
echo "  Cluster metrics  -> xamops-cluster-agent Deployment -> OpenObserve metrics"
echo "  K8s events       -> xamops-cluster-agent Deployment -> OpenObserve logs"
echo ""
echo "View in XamOps -> Observability:"
echo "  Logs:    stream 'k8s_container_logs' (pod logs)"
echo "  Logs:    stream 'k8s_events'         (cluster events)"
echo "  Metrics: system_cpu_utilization, system_memory_utilization"
echo "  Metrics: k8s_pod_cpu_request_utilization, k8s_container_memory_working_set"
echo ""
