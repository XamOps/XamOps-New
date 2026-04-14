#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# XamOps Observability — EKS Helm Setup Script
#
# STATUS: COMING SOON — Not tested yet. Do not use in production.
# ════════════════════════════════════════════════════════════════════════════

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║   XamOps EKS Helm Observability Setup — COMING SOON         ║"
echo "║                                                              ║"
echo "║   This setup method is under development and has not        ║"
echo "║   been tested yet.                                          ║"
echo "║                                                              ║"
echo "║   Available now:                                             ║"
echo "║     • EC2 / Linux VM  →  setup-ec2-observability.sh         ║"
echo "║                                                              ║"
echo "║   Coming soon:                                               ║"
echo "║     • EKS Manual      →  setup-eks-manual.sh                ║"
echo "║     • EKS Helm Chart  →  this script                        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
exit 0

# ════════════════════════════════════════════════════════════════════════════
# DRAFT — work in progress below, not executed
# ════════════════════════════════════════════════════════════════════════════

# Required env vars:
#   XAMOPS_OTEL_ENDPOINT  e.g. https://live-otel.xamops.com
#   XAMOPS_ORG_TOKEN      your OpenObserve org token
#   XAMOPS_AUTH_TOKEN     base64 of user:password
#
# Additionally:
#   CLUSTER_NAME          EKS cluster name
#   NAMESPACE             namespace to deploy into (default: xamops-observability)
#
# Planned steps:
#   1. Add OpenTelemetry Helm repo
#      helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
#      helm repo update
#
#   2. Create namespace + secret
#      kubectl create namespace $NAMESPACE
#      kubectl create secret generic xamops-otel-secret \
#        --from-literal=endpoint=$XAMOPS_OTEL_ENDPOINT \
#        --from-literal=org_token=$XAMOPS_ORG_TOKEN \
#        --from-literal=auth_token=$XAMOPS_AUTH_TOKEN \
#        -n $NAMESPACE
#
#   3. Install OTel Collector via Helm (DaemonSet mode for node logs+metrics)
#      helm install xamops-otel open-telemetry/opentelemetry-collector \
#        --namespace $NAMESPACE \
#        --values ./helm-values-eks.yaml
#
#   4. Verify
#      kubectl get pods -n $NAMESPACE
#      kubectl logs -n $NAMESPACE -l app=xamops-otel | tail -20
