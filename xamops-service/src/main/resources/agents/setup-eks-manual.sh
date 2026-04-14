#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# XamOps Observability — EKS Manual Setup Script
#
# STATUS: COMING SOON — Not tested yet. Do not use in production.
# ════════════════════════════════════════════════════════════════════════════

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║   XamOps EKS Observability Setup — COMING SOON              ║"
echo "║                                                              ║"
echo "║   This setup method is under development and has not        ║"
echo "║   been tested yet.                                          ║"
echo "║                                                              ║"
echo "║   Available now:                                             ║"
echo "║     • EC2 / Linux VM  →  setup-ec2-observability.sh         ║"
echo "║                                                              ║"
echo "║   Coming soon:                                               ║"
echo "║     • EKS Manual      →  this script                        ║"
echo "║     • EKS Helm Chart  →  setup-eks-helm.sh                  ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
exit 0

# ════════════════════════════════════════════════════════════════════════════
# DRAFT — work in progress below, not executed
# ════════════════════════════════════════════════════════════════════════════

# Required env vars (same as EC2 script):
#   XAMOPS_OTEL_ENDPOINT  e.g. https://live-otel.xamops.com
#   XAMOPS_ORG_TOKEN      your OpenObserve org token
#   XAMOPS_AUTH_TOKEN     base64 of user:password
#
# Additionally:
#   CLUSTER_NAME          EKS cluster name
#   NAMESPACE             namespace to deploy into (default: xamops-observability)
#
# Planned steps:
#   1. Create namespace
#   2. Create secret with XAMOPS_AUTH_TOKEN + XAMOPS_OTEL_ENDPOINT
#   3. Apply RBAC (ClusterRole + ClusterRoleBinding for log/metric access)
#   4. Deploy OTel Collector as DaemonSet (one pod per node)
#      - Collects: node metrics, pod logs, container logs
#      - Mounts: /var/log/pods, /var/lib/docker/containers, /proc, /sys
#   5. Deploy OTel Collector as Deployment (cluster-level metrics)
#      - Collects: kube-state-metrics, API server metrics
#   6. Verify: check pods running + metrics flowing
