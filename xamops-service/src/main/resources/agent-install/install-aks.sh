#!/usr/bin/env bash
#
# XamOps AKS onboarding — one command, safe to run repeatedly.
#
# This is the executable form of the in-app "AKS Setup Guide". Every command the guide asks a
# user to paste is reproduced here in the same order, with the same flags. If you change one,
# change the other: .github/workflows/setup-guide-validation.yml validates both.
#
# It lives under xamops-service/src/main/resources so the backend can serve it, unauthenticated
# and with no secrets in it, from GET /api/v1/agent/install/aks.sh — see
# AgentInstallScriptController. Azure Cloud Shell has no XamOps session, so a session-protected
# download would make `curl | bash` impossible; the server URL and API key arrive as flags that
# the setup guide fills in for the environment the user is signed in to.
#
# WHY THIS EXISTS
#   The guide is ~20 copy-paste steps and has to be repeated per cluster. A customer with five
#   clusters does it five times, and any transcription slip is silent rather than loud — a
#   cluster name typed differently in the Kubescape --set than in the agent ConfigMap does not
#   error, it files the data under the wrong cluster.
#
# RE-RUNNING IS THE NORMAL CASE, NOT A SPECIAL ONE
#   `helm upgrade --install` installs when absent and upgrades when present. `kubectl apply`
#   creates or updates. So a cluster set up with an older version of the guide converges to the
#   current one by running this — there is no uninstall step to perform first.
#
#   Three things genuinely do not self-heal, and are handled explicitly in repair_legacy():
#     1. the cost-exporter the old guide installed into the `default` namespace, whose image
#        (xamops/cost-exporter-azure) was never published, so it sits in ImagePullBackOff
#        forever and nothing in the current guide removes it
#     2. a Helm release left in pending-* state, which `helm upgrade` refuses to touch. The old
#        guide's `driver.kind=ebpf` is rejected by Falco 0.44 at render time, so Falco is the
#        usual casualty
#     3. KS_KEEP_LOCAL on the kubescape Deployment, which some troubleshooting advice suggested
#        and which guarantees scan results never reach in-cluster storage
#
# WHAT IT WILL NOT DO
#   It creates and updates its own objects only. It never deletes a namespace, never touches a
#   workload it did not install, and in `default` it addresses exactly three objects by name.
#
# Usage:
#   ./install-aks.sh --cluster NAME --resource-group RG [--subscription SUB_ID]
#   ./install-aks.sh --all [--subscription SUB_ID]
#   ./install-aks.sh --cluster NAME --resource-group RG --dry-run
#
set -uo pipefail

# ─── Versions ─────────────────────────────────────────────────────────────────────────────
# Bump STACK_VERSION on any change to what gets installed. It is stamped onto every namespace
# this script creates, so `kubectl get ns xamops-system -o jsonpath='{.metadata.labels}'`
# answers "which version of the setup is this cluster on?" without asking the customer.
STACK_VERSION="1.0.0"

AGENT_IMAGE="docker.io/xamops/agent:v2.1.0"

# Substituted when the script is served from the backend, so a customer never copies a URL or
# key by hand. Overridable by flag for standalone use.
XAMOPS_SERVER_URL="${XAMOPS_SERVER_URL:-__XAMOPS_SERVER_URL__}"
XAMOPS_API_KEY="${XAMOPS_API_KEY:-__XAMOPS_API_KEY__}"

DEFAULT_SERVER_URL="https://live.xamops.com"
DEFAULT_API_KEY="xamops_SecureAgentKey2024XamOps"

# Prometheus storage. The guide pins managed-csi; verified to exist during preflight and
# falls back to the cluster default if the customer's cluster does not have it.
STORAGE_CLASS="managed-csi"

HELM_TIMEOUT="10m"

# ─── Arguments ────────────────────────────────────────────────────────────────────────────
CLUSTER_NAME=""
RESOURCE_GROUP=""
SUBSCRIPTION_ID=""
ALL_CLUSTERS=false
DRY_RUN=false
ASSUME_YES=false
SKIP_VERIFY=false

usage() {
  cat <<'USAGE'
XamOps AKS onboarding

  --cluster NAME            AKS cluster name
  --resource-group RG       Resource group containing the cluster
  --subscription ID         Azure subscription id (default: current az subscription)
  --all                     Onboard every healthy AKS cluster in the subscription
  --server-url URL          XamOps backend base URL
  --api-key KEY             XamOps agent API key
  --storage-class NAME      StorageClass for the Prometheus PVC (default: managed-csi)
  --timeout DURATION        Helm timeout per release (default: 10m)
  --dry-run                 Print every command without changing anything
  --skip-verify             Skip the post-install verification pass
  --yes                     Do not prompt for confirmation
  -h, --help                This message

Examples
  ./install-aks.sh --cluster xamops-cluster --resource-group xamops-rg
  ./install-aks.sh --all
  ./install-aks.sh --all --dry-run
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --cluster)        CLUSTER_NAME="${2:-}"; shift 2 ;;
    --resource-group) RESOURCE_GROUP="${2:-}"; shift 2 ;;
    --subscription)   SUBSCRIPTION_ID="${2:-}"; shift 2 ;;
    --server-url)     XAMOPS_SERVER_URL="${2:-}"; shift 2 ;;
    --api-key)        XAMOPS_API_KEY="${2:-}"; shift 2 ;;
    --storage-class)  STORAGE_CLASS="${2:-}"; shift 2 ;;
    --timeout)        HELM_TIMEOUT="${2:-}"; shift 2 ;;
    --all)            ALL_CLUSTERS=true; shift ;;
    --dry-run)        DRY_RUN=true; shift ;;
    --skip-verify)    SKIP_VERIFY=true; shift ;;
    --yes|-y)         ASSUME_YES=true; shift ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

# ─── Output ───────────────────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi

# Warnings and failures accumulate in files rather than arrays because each cluster is
# onboarded in a subshell — see onboard_cluster — so a fatal error on one cluster cannot take
# the rest of the run down with it. Array appends made in a subshell would be discarded.
NOTES_DIR="$(mktemp -d)"
WARN_FILE="$NOTES_DIR/warnings"
FAIL_FILE="$NOTES_DIR/failures"
: > "$WARN_FILE"; : > "$FAIL_FILE"
cleanup_notes() { rm -rf "$NOTES_DIR"; }
trap cleanup_notes EXIT

CURRENT_CLUSTER=""
note_prefix() { [ -n "$CURRENT_CLUSTER" ] && printf '[%s] ' "$CURRENT_CLUSTER"; }

step()  { printf '\n%s▶ %s%s\n' "$C_BOLD$C_BLUE" "$*" "$C_RESET"; }
info()  { printf '   %s\n' "$*"; }
dim()   { printf '   %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
ok()    { printf '   %s✔%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '   %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; printf '%s%s\n' "$(note_prefix)" "$*" >> "$WARN_FILE"; }
fail()  { printf '   %s✘%s %s\n' "$C_RED" "$C_RESET" "$*"; printf '%s%s\n' "$(note_prefix)" "$*" >> "$FAIL_FILE"; }
die()   { printf '\n%s✘ %s%s\n\n' "$C_RED$C_BOLD" "$*" "$C_RESET" >&2; printf '%s%s\n' "$(note_prefix)" "$*" >> "$FAIL_FILE"; exit 1; }

# Runs a command, or prints it under --dry-run. Never used for anything reading stdin.
#
# Output is captured rather than discarded so a failure can show the tail of what went wrong.
# Do NOT add `>/dev/null` at call sites: that redirects this function, which silences the
# dry-run echo and makes --dry-run print nothing at all.
RUN_LOG="$NOTES_DIR/last-command.log"
run() {
  if [ "$DRY_RUN" = true ]; then
    # %q so the printed line is safe to copy into a shell. Matters for arguments like
    # 'tolerations[0].operator=Exists', which zsh glob-expands and rejects when unquoted.
    printf '   %s$' "$C_DIM"
    printf ' %q' "$@"
    printf '%s\n' "$C_RESET"
    return 0
  fi
  : > "$RUN_LOG"
  "$@" >"$RUN_LOG" 2>&1
}

# Last few lines of the most recent run(), for diagnosing a failure in place.
run_tail() {
  [ -s "$RUN_LOG" ] || return 0
  printf '   %slast output:%s\n' "$C_DIM" "$C_RESET"
  tail -n 6 "$RUN_LOG" | sed 's/^/       /'
}

# kubectl apply with a manifest on stdin. Separate from run() because the manifest has to be
# visible under --dry-run to be reviewable, which is the whole point of the flag.
apply_manifest() {
  local label="$1"; shift
  local manifest
  manifest="$(cat)"
  if [ "$DRY_RUN" = true ]; then
    printf '   %s$ kubectl apply -f -   # %s%s\n' "$C_DIM" "$label" "$C_RESET"
    printf '%s\n' "$manifest" | sed 's/^/       /'
    return 0
  fi
  # Output is captured, not discarded, and the status is returned so callers can react. An
  # earlier version sent this to /dev/null and no caller checked, which meant a rejected
  # manifest still printed a tick — a tick has to mean the thing happened.
  : > "$RUN_LOG"
  if printf '%s\n' "$manifest" | kubectl apply -f - >"$RUN_LOG" 2>&1; then
    return 0
  fi
  return 1
}

have() { command -v "$1" >/dev/null 2>&1; }

# ─── Preflight ────────────────────────────────────────────────────────────────────────────
# The context the user was on before we started, so it can be handed back at the end.
ORIGINAL_CONTEXT=""

preflight() {
  step "Preflight"
  ORIGINAL_CONTEXT="$(kubectl config current-context 2>/dev/null || true)"

  local missing=()
  for t in az kubectl helm; do have "$t" || missing+=("$t"); done
  [ ${#missing[@]} -eq 0 ] || die "Missing required tool(s): ${missing[*]}"

  # helm 3.12+ — the guide's escaped-comma --set form depends on modern parsing.
  local hv hmajor hminor
  hv="$(helm version --short 2>/dev/null | sed 's/^v//' | cut -d+ -f1)"
  hmajor="${hv%%.*}"; hminor="$(echo "$hv" | cut -d. -f2)"
  if [ "${hmajor:-0}" -lt 3 ] || { [ "${hmajor:-0}" -eq 3 ] && [ "${hminor:-0}" -lt 12 ]; }; then
    die "helm 3.12+ required, found ${hv:-unknown}"
  fi
  ok "helm $hv"

  have jq || warn "jq not found — Kubescape result verification will be approximate"

  az account show >/dev/null 2>&1 || die "Not signed in to Azure. Run: az login"

  if [ -n "$SUBSCRIPTION_ID" ]; then
    run az account set --subscription "$SUBSCRIPTION_ID" \
      || die "Cannot select subscription $SUBSCRIPTION_ID"
  else
    SUBSCRIPTION_ID="$(az account show --query id -o tsv 2>/dev/null)"
  fi
  ok "subscription $SUBSCRIPTION_ID"

  # Resolve backend config. Placeholders mean the script was not served by the backend.
  case "$XAMOPS_SERVER_URL" in
    __XAMOPS_SERVER_URL__|"")
      XAMOPS_SERVER_URL="$DEFAULT_SERVER_URL"
      warn "No --server-url given; defaulting to $XAMOPS_SERVER_URL" ;;
  esac
  case "$XAMOPS_API_KEY" in
    __XAMOPS_API_KEY__|"")
      XAMOPS_API_KEY="$DEFAULT_API_KEY"
      warn "No --api-key given; using the shared default key" ;;
  esac
  XAMOPS_SERVER_URL="${XAMOPS_SERVER_URL%/}"

  # A loopback or private server URL cannot be reached from inside a cluster. Worse, the agent
  # serves its own health endpoint on 8080, so localhost:8080 makes it POST to itself and log a
  # misleading HTTP 404 rather than a connection error.
  case "$XAMOPS_SERVER_URL" in
    *localhost*|*127.0.0.1*|*://10.*|*://192.168.*|*://172.1[6-9].*|*://172.2[0-9].*|*://172.3[01].*)
      warn "$XAMOPS_SERVER_URL is not reachable from inside a cluster — expose it with a tunnel and pass --server-url" ;;
  esac
  ok "backend $XAMOPS_SERVER_URL"
}

# ─── Cluster connection ───────────────────────────────────────────────────────────────────
# Guide Step 1.1.
connect_cluster() {
  local cluster="$1" rg="$2"
  step "Connecting to $cluster ($rg)"

  run az aks get-credentials --resource-group "$rg" --name "$cluster" --overwrite-existing \
    || die "Could not fetch credentials for $cluster in $rg"

  if [ "$DRY_RUN" = true ]; then
    dim "dry-run: skipping context assertion and node check"
    return 0
  fi

  # get-credentials names the context after the cluster. Asserting it prevents the failure mode
  # where a stale current-context silently sends the whole install at the wrong cluster.
  local ctx
  ctx="$(kubectl config current-context 2>/dev/null)"
  [ "$ctx" = "$cluster" ] || die "kubectl context is '$ctx', expected '$cluster'"

  kubectl get nodes >/dev/null 2>&1 || die "Cannot reach the API server for $cluster"

  local nodes k8s
  nodes="$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')"
  k8s="$(kubectl version -o json 2>/dev/null | jq -r '.serverVersion.gitVersion' 2>/dev/null)"
  ok "context $ctx — $nodes node(s)${k8s:+, Kubernetes $k8s}"

  kubectl auth can-i create clusterrole >/dev/null 2>&1 \
    || warn "You may lack cluster-admin; ClusterRole creation could fail"

  # The guide pins managed-csi. Verify rather than assume, since a Pending PVC keeps Prometheus
  # from ever starting and the cause is not obvious from the pod status alone.
  if ! kubectl get storageclass "$STORAGE_CLASS" >/dev/null 2>&1; then
    local fallback
    fallback="$(kubectl get storageclass -o jsonpath='{range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{end}' 2>/dev/null)"
    if [ -n "$fallback" ]; then
      warn "StorageClass '$STORAGE_CLASS' not found; using cluster default '$fallback'"
      STORAGE_CLASS="$fallback"
    else
      die "StorageClass '$STORAGE_CLASS' not found and no default StorageClass exists"
    fi
  fi
  ok "storageClass $STORAGE_CLASS"
}

# ─── Legacy repair ────────────────────────────────────────────────────────────────────────
# Everything here is a no-op on a cluster that has never run an older guide.
repair_legacy() {
  step "Checking for leftovers from earlier versions of the guide"
  local found=false

  # 1. The cost-exporter whose image was never published. Addressed by exact name; nothing else
  #    in `default` is touched.
  local co=false
  for kind in deployment/cost-exporter service/cost-exporter serviceaccount/cost-exporter-sa cronjob/cost-exporter; do
    if kubectl -n default get "$kind" >/dev/null 2>&1; then co=true; fi
  done
  if [ "$co" = true ]; then
    found=true
    info "Removing the old cost-exporter from the default namespace"
    dim "its image docker.io/xamops/cost-exporter-azure was never published, so it can only ImagePullBackOff"
    run kubectl -n default delete deployment/cost-exporter service/cost-exporter \
      serviceaccount/cost-exporter-sa cronjob/cost-exporter --ignore-not-found >/dev/null 2>&1
    ok "cost-exporter removed"
  fi

  # 2. Releases stuck in a pending state. `helm upgrade` refuses these outright, so the install
  #    would fail with a confusing "another operation is in progress".
  local pair release ns status
  for pair in prometheus:monitoring falco:falco opencost:opencost \
              trivy-operator:trivy-system kubescape:kubescape; do
    release="${pair%%:*}"; ns="${pair##*:}"
    kubectl get ns "$ns" >/dev/null 2>&1 || continue
    status="$(helm status "$release" -n "$ns" -o json 2>/dev/null | jq -r '.info.status' 2>/dev/null)"
    [ -n "$status" ] && [ "$status" != "null" ] || continue
    case "$status" in
      pending-install|pending-upgrade|pending-rollback)
        found=true
        warn "$release is stuck in '$status' — uninstalling so it can be reinstalled cleanly"
        run helm uninstall "$release" -n "$ns" --wait --timeout "$HELM_TIMEOUT" || true
        ;;
      failed)
        # An upgrade over a failed release usually succeeds; leave it and let helm try.
        warn "$release is in 'failed' state — will attempt an in-place upgrade"
        ;;
    esac
  done

  # 3. KS_KEEP_LOCAL keeps Kubescape results in a file inside the pod, which guarantees the
  #    WorkloadConfigurationScanSummary objects stay empty and the dashboard shows no findings.
  if kubectl -n kubescape get deploy kubescape >/dev/null 2>&1; then
    if kubectl -n kubescape get deploy kubescape -o jsonpath='{.spec.template.spec.containers[*].env[*].name}' 2>/dev/null | grep -q KS_KEEP_LOCAL; then
      found=true
      info "Clearing KS_KEEP_LOCAL from the kubescape Deployment"
      run kubectl -n kubescape set env deployment/kubescape KS_KEEP_LOCAL- || true
      ok "KS_KEEP_LOCAL cleared"
    fi
  fi

  # 4. The old guide pointed the agent at Prometheus on :9090. The prometheus-community chart's
  #    server Service listens on 80, so the collector silently returned nothing. Rewritten by
  #    the ConfigMap apply below; flagged here so the change is visible in the log.
  if kubectl -n xamops-system get cm xamops-agent-config >/dev/null 2>&1; then
    if kubectl -n xamops-system get cm xamops-agent-config -o jsonpath='{.data.PROMETHEUS_URL}' 2>/dev/null | grep -q ':9090'; then
      found=true
      info "Agent PROMETHEUS_URL points at :9090; correcting to :80 (chart Service port)"
    fi
    local cur
    cur="$(kubectl -n xamops-system get deploy xamops-agent -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
    if [ -n "$cur" ] && [ "$cur" != "$AGENT_IMAGE" ]; then
      found=true
      info "Agent image $cur will be replaced by $AGENT_IMAGE"
    fi
  fi

  # Deliberately not phrased as "this is a first install": a cluster already onboarded with a
  # current guide also has nothing to repair, and claiming it was fresh is misleading.
  [ "$found" = true ] || ok "nothing to repair"
}

# ─── Part 1.2: Namespaces ─────────────────────────────────────────────────────────────────
install_namespaces() {
  step "Part 1.2 — Namespaces"
  # Labelled so the installed version is discoverable later, and so support can tell our
  # namespaces apart from ones the customer created.
  local ns
  for ns in monitoring falco opencost; do
    if ! apply_manifest "namespace/$ns" <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $ns
  labels:
    app.kubernetes.io/managed-by: xamops-installer
    xamops.io/stack-version: "$STACK_VERSION"
EOF
    then
      fail "could not create namespace/$ns"
      run_tail
      return 1
    fi
  done
  ok "monitoring, falco, opencost"
}

# ─── Part 1.3: Prometheus ─────────────────────────────────────────────────────────────────
install_prometheus() {
  step "Part 1.3 — Prometheus"
  run helm repo add prometheus-community https://prometheus-community.github.io/helm-charts || true
  run helm repo update prometheus-community || true

  local values
  values="$(mktemp)"
  cat > "$values" <<'EOF'
server:
  persistentVolume:
    enabled: true
    size: 20Gi
  retention: "15d"
alertmanager:
  enabled: false
pushgateway:
  enabled: false
nodeExporter:
  enabled: true
kube-state-metrics:
  enabled: true
extraScrapeConfigs: |
  - job_name: falco-sidekick
    static_configs:
      - targets: ['falco-falcosidekick.falco.svc.cluster.local:2801']
  - job_name: opencost
    static_configs:
      - targets: ['opencost.opencost.svc.cluster.local:9003']
EOF

  if [ "$DRY_RUN" = true ]; then
    dim "prometheus-values.yaml:"; sed 's/^/       /' "$values"
  fi

  # --reset-values is what makes an old install converge: without it helm carries forward
  # values from the previous release, including the scrape job for the cost-exporter that no
  # longer exists.
  if run helm upgrade --install prometheus prometheus-community/prometheus \
      --namespace monitoring \
      --values "$values" \
      --set server.persistentVolume.storageClass="$STORAGE_CLASS" \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "prometheus"
  else
    fail "prometheus install failed — kubectl -n monitoring get pods,pvc"
    run_tail
  fi
  rm -f "$values"
}

# ─── Part 1.4: Falco ──────────────────────────────────────────────────────────────────────
# modern_ebpf uses CO-RE and needs kernel 5.8+. Rather than install and react to a
# CrashLoopBackOff, read the node kernel versions and choose up front.
pick_falco_driver() {
  local kernels min_major min_minor k major minor
  if [ "$DRY_RUN" = true ]; then echo "modern_ebpf"; return; fi
  kernels="$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.nodeInfo.kernelVersion}{"\n"}{end}' 2>/dev/null)"
  [ -n "$kernels" ] || { echo "modern_ebpf"; return; }
  min_major=99; min_minor=99
  while IFS= read -r k; do
    [ -n "$k" ] || continue
    major="$(echo "$k" | cut -d. -f1)"; minor="$(echo "$k" | cut -d. -f2 | tr -cd '0-9')"
    [ -n "$major" ] && [ -n "$minor" ] || continue
    if [ "$major" -lt "$min_major" ] || { [ "$major" -eq "$min_major" ] && [ "$minor" -lt "$min_minor" ]; }; then
      min_major="$major"; min_minor="$minor"
    fi
  done <<< "$kernels"
  if [ "$min_major" -gt 5 ] || { [ "$min_major" -eq 5 ] && [ "$min_minor" -ge 8 ]; }; then
    echo "modern_ebpf"
  else
    echo "kmod"
  fi
}

install_falco() {
  step "Part 1.4 — Falco + FalcoSidekick"
  run helm repo add falcosecurity https://falcosecurity.github.io/charts || true
  run helm repo update falcosecurity || true

  local driver
  driver="$(pick_falco_driver)"
  if [ "$driver" = "kmod" ]; then
    warn "Oldest node kernel is below 5.8 — using driver.kind=kmod instead of modern_ebpf"
  else
    dim "driver.kind=modern_ebpf (CO-RE; driver.kind=ebpf was removed in Falco 0.44)"
  fi

  # replicaCount=1 is deliberate: the Prometheus job above scrapes the sidekick Service by DNS,
  # so extra replicas split the event counters across pods and the totals jump around.
  if run helm upgrade --install falco falcosecurity/falco \
      --namespace falco \
      --set driver.kind="$driver" \
      --set falco.json_output=true \
      --set falcosidekick.enabled=true \
      --set falcosidekick.config.prometheus.enabled=true \
      --set falcosidekick.service.port=2801 \
      --set falcosidekick.replicaCount=1 \
      --set 'tolerations[0].operator=Exists' \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "falco (driver.kind=$driver)"
  else
    fail "falco install failed — kubectl -n falco get pods"
    run_tail
  fi
}

# ─── Part 1.5: OpenCost ───────────────────────────────────────────────────────────────────
install_opencost() {
  step "Part 1.5 — OpenCost"
  run helm repo add opencost https://opencost.github.io/opencost-helm-chart || true
  run helm repo update opencost || true

  local values
  values="$(mktemp)"
  cat > "$values" <<EOF
opencost:
  prometheus:
    internal:
      enabled: true
      serviceName: prometheus-server
      namespaceName: monitoring
      port: 80
  cloudCost:
    enabled: true
  exporter:
    azure:
      subscriptionId: "$SUBSCRIPTION_ID"
EOF

  if [ "$DRY_RUN" = true ]; then
    dim "opencost-values.yaml:"; sed 's/^/       /' "$values"
  fi

  if run helm upgrade --install opencost opencost/opencost \
      --namespace opencost \
      --values "$values" \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "opencost"
  else
    fail "opencost install failed — kubectl -n opencost get pods"
    run_tail
  fi
  rm -f "$values"
}

# ─── Part 2.1: Trivy ──────────────────────────────────────────────────────────────────────
install_trivy() {
  step "Part 2.1 — Trivy Operator"
  run helm repo add aquasecurity https://aquasecurity.github.io/helm-charts/ || true
  run helm repo update aquasecurity || true

  # The commas in trivy.severity must reach Helm escaped. Helm treats a bare comma as a
  # separator between key=value pairs, so the unescaped form is parsed as one key plus four
  # malformed ones and the install errors out.
  if run helm upgrade --install trivy-operator aquasecurity/trivy-operator \
      --namespace trivy-system --create-namespace \
      --set trivy.ignoreUnfixed=false \
      --set 'trivy.severity=UNKNOWN\,LOW\,MEDIUM\,HIGH\,CRITICAL' \
      --set operator.vulnerabilityScanner.enabled=true \
      --set operator.configAuditScannerEnabled=true \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "trivy-operator"
  else
    fail "trivy-operator install failed — kubectl -n trivy-system get pods"
    run_tail
  fi
}

# ─── Part 2.2: Kubescape ──────────────────────────────────────────────────────────────────
install_kubescape() {
  step "Part 2.2 — Kubescape"
  run helm repo add kubescape https://kubescape.github.io/helm-charts/ || true
  run helm repo update kubescape || true

  # No --set account: supplying an account ships results to Kubescape Cloud instead of writing
  # them to in-cluster storage, which is where the agent reads them from.
  if run helm upgrade --install kubescape kubescape/kubescape-operator \
      --namespace kubescape --create-namespace \
      --set clusterName="$CLUSTER_NAME" \
      --set capabilities.continuousScan=enable \
      --set storage.enabled=true \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "kubescape (clusterName=$CLUSTER_NAME)"
  else
    fail "kubescape install failed — kubectl -n kubescape get pods"
    run_tail
    return
  fi

  # Configuration scans otherwise run on a daily schedule, so a fresh install shows an empty
  # Security tab for up to 24h. Trigger one now.
  if [ "$DRY_RUN" = true ]; then
    dim "\$ kubectl -n kubescape create job --from=cronjob/kubescape-scheduler xamops-initial-scan"
    return
  fi
  if kubectl -n kubescape get cronjob kubescape-scheduler >/dev/null 2>&1; then
    kubectl -n kubescape delete job xamops-initial-scan --ignore-not-found >/dev/null 2>&1
    if kubectl -n kubescape create job --from=cronjob/kubescape-scheduler xamops-initial-scan >/dev/null 2>&1; then
      info "Initial configuration scan triggered"
    fi
  else
    warn "cronjob/kubescape-scheduler not found; first scan will follow the chart's own schedule"
  fi
}

# ─── Part 3.1: RBAC ───────────────────────────────────────────────────────────────────────
install_agent_rbac() {
  step "Part 3.1 — Agent RBAC (read-only)"
  if ! apply_manifest "xamops-agent-rbac" <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: xamops-system
  labels:
    app.kubernetes.io/managed-by: xamops-installer
    xamops.io/stack-version: "$STACK_VERSION"
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: xamops-agent-sa
  namespace: xamops-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: xamops-agent-role
rules:
  - apiGroups: [""]
    resources: [pods, nodes, namespaces, services, endpoints, persistentvolumes,
                persistentvolumeclaims, resourcequotas, limitranges, serviceaccounts,
                events, configmaps]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, daemonsets, statefulsets, replicasets]
    verbs: [get, list, watch]
  - apiGroups: [batch]
    resources: [jobs, cronjobs]
    verbs: [get, list, watch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses, networkpolicies]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling]
    resources: [horizontalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [autoscaling.k8s.io]
    resources: [verticalpodautoscalers]
    verbs: [get, list, watch]
  - apiGroups: [metrics.k8s.io]
    resources: [nodes, pods]
    verbs: [get, list]
  - apiGroups: [storage.k8s.io]
    resources: [storageclasses]
    verbs: [get, list, watch]
  - apiGroups: [apiextensions.k8s.io]
    resources: [customresourcedefinitions]
    verbs: [get, list]
  - apiGroups: [aquasecurity.github.io]
    resources: [vulnerabilityreports, configauditreports, exposedsecretreports,
                rbacassessmentreports, infraassessmentreports, clustercompliancereports,
                clusterconfigauditreports, clusterinfraassessmentreports,
                clusterrbacassessmentreports]
    verbs: [get, list, watch]
  - apiGroups: [spdx.softwarecomposition.kubescape.io]
    resources: [configurationscansummaries, vulnerabilitymanifestsummaries,
                workloadconfigurationscans, workloadconfigurationscansummaries,
                vulnerabilitymanifests, applicationprofiles, applicationactivities,
                networkneighborhoods, generatednetworkpolicies, knownservers,
                openvulnerabilityexchangecontainers, seccompprofiles]
    verbs: [get, list, watch]
  - apiGroups: [kubescape.io]
    resources: [workloadconfigurationscans, workloadconfigurationscansummaries]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: xamops-agent-binding
subjects:
  - kind: ServiceAccount
    name: xamops-agent-sa
    namespace: xamops-system
roleRef:
  kind: ClusterRole
  name: xamops-agent-role
  apiGroup: rbac.authorization.k8s.io
EOF
  then
    fail "agent RBAC could not be applied — the agent cannot read the cluster without it"
    run_tail
    return 1
  fi
  ok "namespace, service account, cluster role, binding"
}

# ─── Part 3.2: ConfigMap + Secret ─────────────────────────────────────────────────────────
install_agent_config() {
  step "Part 3.2 — Agent ConfigMap and Secret"
  # CLUSTER_NAME must match the Kubescape --set clusterName exactly; both come from the same
  # variable here, which is the main reason this script exists.
  if ! apply_manifest "xamops-agent-config" <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: xamops-agent-config
  namespace: xamops-system
data:
  COLLECT_INTERVAL: "300"
  PUSH_INTERVAL: "300"
  HEARTBEAT_INTERVAL: "60"
  XAMOPS_SERVER_URL: "$XAMOPS_SERVER_URL"
  CLUSTER_NAME: "$CLUSTER_NAME"
  CLOUD_ACCOUNT_ID: "$SUBSCRIPTION_ID"
  CLOUD_PROVIDER: "AZURE"
  COLLECTOR_K8S_RESOURCES: "true"
  COLLECTOR_PROMETHEUS_METRICS: "true"
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:80"
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_OPENCOST: "true"
  COLLECTOR_KARPENTER: "false"
EOF
  then
    fail "agent ConfigMap could not be applied"
    run_tail
    return 1
  fi

  if [ "$DRY_RUN" = true ]; then
    printf '   %s$ kubectl apply -f -   # xamops-agent-secret (XAMOPS_API_KEY redacted)%s\n' "$C_DIM" "$C_RESET"
  else
    # Applied separately and never echoed, so the key does not land in terminal scrollback or
    # CI logs.
    printf '%s\n' "apiVersion: v1
kind: Secret
metadata:
  name: xamops-agent-secret
  namespace: xamops-system
type: Opaque
stringData:
  XAMOPS_API_KEY: \"$XAMOPS_API_KEY\"" | kubectl apply -f - >/dev/null
  fi
  ok "config for cluster '$CLUSTER_NAME' → $XAMOPS_SERVER_URL"
}

# ─── Part 3.3: Agent Deployment ───────────────────────────────────────────────────────────
install_agent() {
  step "Part 3.3 — Agent Deployment"
  if ! apply_manifest "xamops-agent-deployment" <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: xamops-agent
  namespace: xamops-system
  labels:
    app: xamops-agent
    xamops.io/stack-version: "$STACK_VERSION"
spec:
  replicas: 1
  selector:
    matchLabels:
      app: xamops-agent
  template:
    metadata:
      labels:
        app: xamops-agent
      annotations:
        # Forces a rollout when only the ConfigMap changed, which a Deployment would otherwise
        # ignore, leaving the agent running with stale settings.
        xamops.io/config-revision: "$(printf '%s|%s|%s|%s' "$XAMOPS_SERVER_URL" "$CLUSTER_NAME" "$SUBSCRIPTION_ID" "$STACK_VERSION" | cksum | cut -d' ' -f1)"
    spec:
      serviceAccountName: xamops-agent-sa
      containers:
        - name: xamops-agent
          image: $AGENT_IMAGE
          imagePullPolicy: Always
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 500m
              memory: 256Mi
          envFrom:
            - configMapRef:
                name: xamops-agent-config
            - secretRef:
                name: xamops-agent-secret
EOF
  then
    fail "agent Deployment could not be applied"
    run_tail
    return 1
  fi

  if [ "$DRY_RUN" = true ]; then
    ok "agent $AGENT_IMAGE (dry-run)"
    return
  fi

  if kubectl -n xamops-system rollout status deploy/xamops-agent --timeout=180s >/dev/null 2>&1; then
    ok "agent $AGENT_IMAGE running"
  else
    fail "agent did not become ready — kubectl -n xamops-system describe pod -l app=xamops-agent"
  fi
}

# ─── Part 4: Verification ─────────────────────────────────────────────────────────────────
verify() {
  step "Part 4 — Verification ($(kubectl config current-context 2>/dev/null))"
  if [ "$DRY_RUN" = true ]; then dim "skipped under --dry-run"; return; fi

  local ns total ready
  for ns in monitoring falco opencost trivy-system kubescape xamops-system; do
    total="$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    ready="$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | awk '$3=="Running"||$3=="Completed"' | wc -l | tr -d ' ')"
    if [ "$total" = "0" ]; then
      fail "$ns: no pods"
    elif [ "$ready" = "$total" ]; then
      ok "$ns: $ready/$total pods ready"
    else
      warn "$ns: only $ready/$total pods ready (scanners can take a few minutes)"
    fi
  done

  # Prometheus is the agent's data source, so query it the way the agent will rather than
  # trusting that the pod is merely Running.
  local q
  q="$(kubectl -n monitoring exec deploy/prometheus-server -c prometheus-server -- \
        wget -qO- 'http://localhost:9090/api/v1/query?query=up' 2>/dev/null | head -c 4000)"
  if printf '%s' "$q" | grep -q '"status":"success"'; then
    ok "Prometheus is answering queries"
  else
    warn "Could not query Prometheus in-cluster; check: kubectl -n monitoring get pods"
  fi

  # Trivy and Kubescape populate over minutes, so absence here is not a failure.
  local vulns
  vulns="$(kubectl get vulnerabilityreports -A --no-headers 2>/dev/null | wc -l | tr -d ' ')"
  info "Trivy VulnerabilityReports: $vulns (first scan takes 5–15 min)"

  if have jq; then
    local summ ctl
    summ="$(kubectl get workloadconfigurationscansummaries -A -o json 2>/dev/null | jq '.items | length' 2>/dev/null)"
    ctl="$(kubectl get workloadconfigurationscansummaries -A -o json 2>/dev/null | jq '[.items[] | select(.spec.controls != null)] | length' 2>/dev/null)"
    if [ "${summ:-0}" -gt 0 ] && [ "${ctl:-0}" -eq 0 ]; then
      # Counting rows is not enough: Kubescape creates a summary per workload as soon as it
      # sees one, with controls still null until results are written.
      warn "Kubescape has ${summ} summaries but 0 with controls — scan results are not reaching storage yet"
      dim "if this persists: kubectl -n kubescape logs deploy/kubescape --tail=60"
    else
      info "Kubescape summaries: ${summ:-0} (${ctl:-0} with controls evaluated)"
    fi
  fi

  # Check the image the running pod actually uses, rather than grepping the log for a startup
  # line. The log approach gave a false warning on an already-running agent: its boot line is
  # long past the --tail window, so a healthy cluster looked broken.
  local running
  running="$(kubectl -n xamops-system get pods -l app=xamops-agent \
    -o jsonpath='{.items[0].spec.containers[0].image}' 2>/dev/null)"
  if [ -z "$running" ]; then
    fail "no agent pod found in xamops-system"
  elif [ "$running" = "$AGENT_IMAGE" ]; then
    ok "agent pod is running $running"
  else
    warn "agent pod is running $running but this installer expects $AGENT_IMAGE"
  fi

  # A version line is a bonus when the pod restarted during this run; its absence on a
  # long-running pod means nothing, so it is reported as information rather than a warning.
  local ver
  ver="$(kubectl -n xamops-system logs -l app=xamops-agent --tail=400 2>/dev/null \
    | grep -o 'Starting XamOps Agent.*' | tail -1)"
  [ -n "$ver" ] && info "agent boot line: $ver"

  if kubectl -n xamops-system logs -l app=xamops-agent --tail=200 2>/dev/null | grep -qiE 'connection refused|no such host|timeout|401|403'; then
    warn "agent logs contain connection or auth errors — verify $XAMOPS_SERVER_URL is reachable from the cluster"
  fi
}

# ─── Per-cluster orchestration ────────────────────────────────────────────────────────────
onboard_cluster() {
  CLUSTER_NAME="$1"
  RESOURCE_GROUP="$2"
  CURRENT_CLUSTER="$1"

  printf '\n%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"
  printf '%s  %s  (resource group: %s)%s\n' "$C_BOLD" "$CLUSTER_NAME" "$RESOURCE_GROUP" "$C_RESET"
  printf '%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"

  connect_cluster "$CLUSTER_NAME" "$RESOURCE_GROUP"
  repair_legacy
  install_namespaces
  install_prometheus
  install_falco
  install_opencost
  install_trivy
  install_kubescape
  install_agent_rbac
  install_agent_config
  install_agent
  [ "$SKIP_VERIFY" = true ] || verify
}

# ─── Main ─────────────────────────────────────────────────────────────────────────────────
main() {
  printf '%sXamOps AKS onboarding%s  (stack %s, agent %s)\n' "$C_BOLD" "$C_RESET" "$STACK_VERSION" "$AGENT_IMAGE"
  [ "$DRY_RUN" = true ] && printf '%sDRY RUN — nothing will be changed%s\n' "$C_YELLOW" "$C_RESET"

  preflight

  local -a targets=()

  if [ "$ALL_CLUSTERS" = true ]; then
    step "Discovering AKS clusters in subscription $SUBSCRIPTION_ID"
    local name rg state
    while IFS=$'\t' read -r name rg state; do
      [ -n "${name:-}" ] || continue
      if [ "$state" != "Succeeded" ]; then
        # A cluster that never finished provisioning has no reachable API server; attempting it
        # just hangs on credential fetch.
        warn "skipping $name — provisioningState=$state"
        continue
      fi
      targets+=("$name|$rg")
      info "queued $name ($rg)"
    done < <(az aks list --query "[].[name,resourceGroup,provisioningState]" -o tsv 2>/dev/null)
    [ ${#targets[@]} -gt 0 ] || die "No healthy AKS clusters found in subscription $SUBSCRIPTION_ID"
  else
    [ -n "$CLUSTER_NAME" ] || die "--cluster is required (or use --all)"
    if [ -z "$RESOURCE_GROUP" ]; then
      # Cluster names are only unique within a resource group, so resolve all matches and
      # refuse to guess. Picking the first would silently onboard the wrong cluster, and the
      # symptom — data under the wrong name — is very hard to trace back to here.
      local matches count
      matches="$(az aks list --query "[?name=='$CLUSTER_NAME'].resourceGroup" -o tsv 2>/dev/null | tr -d '\r' | sed '/^$/d')"
      count="$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d ' ')"
      case "$count" in
        0) die "No AKS cluster named '$CLUSTER_NAME' in subscription $SUBSCRIPTION_ID" ;;
        1) RESOURCE_GROUP="$matches"; info "resolved resource group: $RESOURCE_GROUP" ;;
        *) printf '   %s\n' "$matches" | sed 's/^/     - /'
           die "'$CLUSTER_NAME' exists in $count resource groups; pass --resource-group to disambiguate" ;;
      esac
    fi
    targets+=("$CLUSTER_NAME|$RESOURCE_GROUP")
  fi

  if [ "$ASSUME_YES" != true ] && [ "$DRY_RUN" != true ] && [ -t 0 ]; then
    printf '\nAbout to onboard %s cluster(s) to %s. Continue? [y/N] ' "${#targets[@]}" "$XAMOPS_SERVER_URL"
    local reply; read -r reply
    case "$reply" in y|Y|yes|YES) ;; *) die "Cancelled." ;; esac
  fi

  # Each cluster runs in a subshell so a fatal error on one — unreachable API server, missing
  # StorageClass — is recorded and the remaining clusters still get processed. Onboarding five
  # clusters and aborting on the second would be worse than the manual guide it replaces.
  local t name rg ok_count=0
  for t in "${targets[@]}"; do
    name="${t%%|*}"; rg="${t##*|}"
    if ( onboard_cluster "$name" "$rg" ); then
      ok_count=$((ok_count + 1))
    else
      printf '   %s✘ %s aborted — continuing with the remaining clusters%s\n' "$C_RED" "$name" "$C_RESET"
    fi
  done

  # ─── Summary ───
  printf '\n%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"
  printf '%s  Summary%s\n' "$C_BOLD" "$C_RESET"
  printf '%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"
  printf '   clusters succeeded : %s of %s\n' "$ok_count" "${#targets[@]}"
  printf '   backend            : %s\n' "$XAMOPS_SERVER_URL"
  printf '   agent image        : %s\n' "$AGENT_IMAGE"

  local nwarn nfail
  nwarn="$(wc -l < "$WARN_FILE" | tr -d ' ')"
  nfail="$(wc -l < "$FAIL_FILE" | tr -d ' ')"

  if [ "$nwarn" -gt 0 ]; then
    printf '\n   %s%s warning(s)%s\n' "$C_YELLOW" "$nwarn" "$C_RESET"
    sed 's/^/     - /' "$WARN_FILE"
  fi

  if [ "$nfail" -gt 0 ]; then
    printf '\n   %s%s failure(s)%s\n' "$C_RED" "$nfail" "$C_RESET"
    sed 's/^/     - /' "$FAIL_FILE"
    printf '\n   Re-running this script is safe and is the normal way to retry.\n\n'
    exit 1
  fi

  report_context

  printf '\n   %sDone.%s First data appears in XamOps within ~5 minutes; cost data takes 15–20.\n' "$C_GREEN" "$C_RESET"
  printf '   Re-running this script at any time is safe and idempotent.\n\n'
}

# Onboarding switches kubectl context per cluster, so whatever the user runs next would silently
# hit the last cluster processed. Hand the original context back, and say so either way —
# a follow-up `kubectl get pods` against the wrong cluster is genuinely confusing to debug.
report_context() {
  [ "$DRY_RUN" = true ] && return 0
  local now
  now="$(kubectl config current-context 2>/dev/null || true)"
  if [ -n "$ORIGINAL_CONTEXT" ] && [ "$ORIGINAL_CONTEXT" != "$now" ]; then
    if kubectl config use-context "$ORIGINAL_CONTEXT" >/dev/null 2>&1; then
      printf '\n   kubectl context restored to %s%s%s (was switched per cluster during the run).\n' \
        "$C_BOLD" "$ORIGINAL_CONTEXT" "$C_RESET"
      return 0
    fi
  fi
  if [ -n "$now" ]; then
    printf '\n   kubectl context is now %s%s%s — commands you run next will target that cluster.\n' \
      "$C_BOLD" "$now" "$C_RESET"
  fi
}

main "$@"
