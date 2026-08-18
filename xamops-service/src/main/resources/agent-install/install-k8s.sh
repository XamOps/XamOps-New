#!/usr/bin/env bash
#
# XamOps Kubernetes onboarding — one command per cluster, safe to run repeatedly.
#
# Handles AKS, EKS and GKE from a single file, selected with --provider. Roughly 85% of the work
# is identical across clouds — the five Helm installs, the agent RBAC/ConfigMap/Deployment, and
# verification do not differ — so keeping one script is what stops them drifting. We already
# learned that lesson from having the same bug in three separate guide modals.
#
# This is the executable form of the in-app setup guides. Every command a guide asks a user to
# paste is reproduced here in the same order with the same flags. If you change one, change the
# other: .github/workflows/setup-guide-validation.yml validates both.
#
# It lives under xamops-service/src/main/resources so the backend can serve it, unauthenticated
# and with no secrets in it, from GET /api/v1/agent/install/{aks,eks,gke}.sh — see
# AgentInstallScriptController. Cloud Shell has no XamOps session, so a session-protected
# download would make `curl | bash` impossible; the server URL and API key arrive as flags that
# the setup guide fills in for the environment the user is signed in to.
#
# RE-RUNNING IS THE NORMAL CASE, NOT A SPECIAL ONE
#   `helm upgrade --install` installs when absent and upgrades when present. `kubectl apply`
#   creates or updates. So a cluster set up with an older version of a guide converges to the
#   current one by running this — there is no uninstall step to perform first.
#
#   The things that genuinely do not self-heal are handled explicitly in repair_legacy().
#
# WHAT IT WILL NOT DO
#   It creates and updates its own objects only. It never deletes a namespace, never touches a
#   workload it did not install, and in `default` it addresses exactly three objects by name.
#   On EKS it never modifies IAM unless --setup-ebs-csi is passed. On GKE it never touches IAM
#   at all — the Persistent Disk CSI driver is already there.
#
# Usage:
#   ./install-k8s.sh --provider aks --cluster NAME --resource-group RG
#   ./install-k8s.sh --provider eks --cluster NAME --region REGION
#   ./install-k8s.sh --provider eks --all --region REGION
#   ./install-k8s.sh --provider gke --cluster NAME --location LOC --project PROJECT_ID
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

# Which cloud. Baked in per endpoint when served by the backend, so the customer-facing command
# does not need --provider; overridable for standalone use.
PROVIDER="${PROVIDER:-__PROVIDER__}"

# Prometheus storage. Left empty and resolved per provider: AKS already has managed-csi and GKE
# standard-rwo, EKS has to be given (or install) a CSI-backed default.
STORAGE_CLASS=""
AKS_STORAGE_CLASS="managed-csi"
EKS_STORAGE_CLASS="gp3"
GKE_STORAGE_CLASS="standard-rwo"

HELM_TIMEOUT="10m"

# ─── Arguments ────────────────────────────────────────────────────────────────────────────
CLUSTER_NAME=""
RESOURCE_GROUP=""
SUBSCRIPTION_ID=""
REGION=""
# GKE: the project, and the zone or region the cluster lives in. GKE clusters are addressed by
# location rather than region because a cluster may be zonal (us-central1-a) or regional
# (us-central1), and the two need different gcloud flags.
PROJECT_ID=""
LOCATION=""
# Set per cluster in detect_autopilot: Autopilot forbids the privileged workloads that Falco and
# node-exporter need, so those two are skipped rather than left permanently rejected by admission.
AUTOPILOT=false
# Resolved in preflight: subscription id for AKS, 12-digit account number for EKS, project id
# for GKE.
CLOUD_ACCOUNT_ID=""
ALL_CLUSTERS=false
DRY_RUN=false
ASSUME_YES=false
SKIP_VERIFY=false
# Opting in to an account-level IAM change. Deliberately NOT implied by --yes: consenting to
# unattended execution is not consenting to editing your IAM.
SETUP_EBS_CSI=false

usage() {
  cat <<'USAGE'
XamOps Kubernetes onboarding (AKS / EKS / GKE)

  --provider aks|eks|gke    Which cloud (baked in when served from the XamOps guide)
  --cluster NAME            Cluster name
  --all                     Onboard every healthy cluster in the subscription (AKS), region (EKS)
                            or project (GKE)

  AKS only:
  --resource-group RG       Resource group containing the cluster
  --subscription ID         Azure subscription id (default: current az subscription)

  EKS only:
  --region REGION           AWS region (required)
  --setup-ebs-csi           Install the EBS CSI driver, which ATTACHES AN IAM POLICY to the node
                            group role. Off by default; without it the installer stops and prints
                            the commands for you to run instead.

  GKE only:
  --project PROJECT_ID      GCP project id (default: current gcloud project)
  --location LOC            Cluster zone (us-central1-a) or region (us-central1). Resolved from
                            the cluster name when omitted.

  --server-url URL          XamOps backend base URL
  --api-key KEY             XamOps agent API key
  --storage-class NAME      StorageClass for the Prometheus PVC
  --timeout DURATION        Helm timeout per release (default: 10m)
  --dry-run                 Print every command without changing anything
  --skip-verify             Skip the post-install verification pass
  --yes                     Do not prompt for confirmation
  -h, --help                This message

Examples
  ./install-k8s.sh --provider aks --cluster my-aks --resource-group my-rg
  ./install-k8s.sh --provider eks --cluster my-eks --region ap-south-1
  ./install-k8s.sh --provider eks --all --region ap-south-1 --dry-run
  ./install-k8s.sh --provider gke --cluster my-gke --location us-central1-a
  ./install-k8s.sh --provider gke --all --project my-project-123456
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --provider)       PROVIDER="${2:-}"; shift 2 ;;
    --cluster)        CLUSTER_NAME="${2:-}"; shift 2 ;;
    --resource-group) RESOURCE_GROUP="${2:-}"; shift 2 ;;
    --subscription)   SUBSCRIPTION_ID="${2:-}"; shift 2 ;;
    --region)         REGION="${2:-}"; shift 2 ;;
    --setup-ebs-csi)  SETUP_EBS_CSI=true; shift ;;
    --project)        PROJECT_ID="${2:-}"; shift 2 ;;
    --location)       LOCATION="${2:-}"; shift 2 ;;
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

# ─── helm ─────────────────────────────────────────────────────────────────────────────────
# 3.12+ because the guide's escaped-comma --set form depends on modern parsing.
HELM_MIN_MAJOR=3
HELM_MIN_MINOR=12
# Under $HOME so no sudo is needed and Cloud Shell's persistent home keeps it between
# sessions. /usr/local/bin would need sudo and is wiped when a Cloud Shell session recycles.
HELM_INSTALL_TO="$HOME/.xamops/bin"

helm_version() { helm version --short 2>/dev/null | sed 's/^v//' | cut -d+ -f1; }

helm_is_current() {
  local hv major minor
  hv="$(helm_version)"
  [ -n "$hv" ] || return 1
  major="${hv%%.*}"; minor="$(printf '%s' "$hv" | cut -d. -f2)"
  [ "${major:-0}" -gt "$HELM_MIN_MAJOR" ] && return 0
  [ "${major:-0}" -eq "$HELM_MIN_MAJOR" ] && [ "${minor:-0}" -ge "$HELM_MIN_MINOR" ]
}

# Installs helm instead of failing preflight. Neither AWS CloudShell nor Azure Cloud Shell
# ships helm, so "Missing required tool(s): helm" stopped every customer on their first run —
# a guaranteed dead end for a step the script can simply do itself.
ensure_helm() {
  if helm_is_current; then
    ok "helm $(helm_version)"
    return 0
  fi

  local found; found="$(helm_version)"
  if [ -n "$found" ]; then
    info "helm $found is older than ${HELM_MIN_MAJOR}.${HELM_MIN_MINOR} — installing a current build"
  else
    info "helm not found — installing"
  fi

  if [ "$DRY_RUN" = true ]; then
    info "would install helm into $HELM_INSTALL_TO"
    return 0
  fi

  have curl || die "helm is missing and curl is unavailable to install it. Install helm ${HELM_MIN_MAJOR}.${HELM_MIN_MINOR}+ manually: https://helm.sh/docs/intro/install/"

  local getter; getter="$(mktemp)"
  if ! curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 -o "$getter" 2>"$RUN_LOG"; then
    rm -f "$getter"; run_tail
    die "Could not download the helm installer. Install helm ${HELM_MIN_MAJOR}.${HELM_MIN_MINOR}+ manually: https://helm.sh/docs/intro/install/"
  fi

  mkdir -p "$HELM_INSTALL_TO"

  # PATH has to include the target directory BEFORE get-helm-3 runs. The installer verifies
  # its own work by invoking `helm` from PATH, so installing into a directory that is not on
  # PATH makes it place the binary correctly and then exit non-zero with
  # "helm not found. Is <dir> on your $PATH?". Only this shell is affected; the binary itself
  # outlives it under $HOME.
  case ":$PATH:" in
    *":$HELM_INSTALL_TO:"*) ;;
    *) PATH="$HELM_INSTALL_TO:$PATH"; export PATH ;;
  esac

  # Judged by outcome, not by exit status: get-helm-3 also exits non-zero for conditions that
  # are not failures here (notably "already latest" when a prior run installed it).
  HELM_INSTALL_DIR="$HELM_INSTALL_TO" USE_SUDO=false bash "$getter" >"$RUN_LOG" 2>&1 || true
  rm -f "$getter"
  hash -r 2>/dev/null || true

  if ! helm_is_current; then
    run_tail
    die "helm install failed. Install helm ${HELM_MIN_MAJOR}.${HELM_MIN_MINOR}+ manually: https://helm.sh/docs/intro/install/"
  fi
  ok "helm $(helm_version) — installed into $HELM_INSTALL_TO"
  info "add to your PATH to reuse it later: export PATH=\"$HELM_INSTALL_TO:\$PATH\""
}

# ─── Preflight ────────────────────────────────────────────────────────────────────────────
# The context the user was on before we started, so it can be handed back at the end.
ORIGINAL_CONTEXT=""

preflight() {
  step "Preflight"
  ORIGINAL_CONTEXT="$(kubectl config current-context 2>/dev/null || true)"

  # Resolve the provider before anything else — every later check depends on it.
  case "$PROVIDER" in
    __PROVIDER__|"") PROVIDER="aks"
      warn "No --provider given; defaulting to aks" ;;
    aks|eks|gke) : ;;
    *) die "Unknown --provider '$PROVIDER' (expected aks, eks or gke)" ;;
  esac
  ok "provider $PROVIDER"

  local cloud_cli="az"
  case "$PROVIDER" in
    eks) cloud_cli="aws" ;;
    gke) cloud_cli="gcloud" ;;
  esac

  # helm is deliberately not in this list — ensure_helm installs it rather than failing.
  local missing=()
  for t in "$cloud_cli" kubectl; do have "$t" || missing+=("$t"); done
  [ ${#missing[@]} -eq 0 ] || die "Missing required tool(s): ${missing[*]}"

  ensure_helm

  have jq || warn "jq not found — Kubescape result verification will be approximate"

  if [ "$PROVIDER" = "aks" ]; then
    az account show >/dev/null 2>&1 || die "Not signed in to Azure. Run: az login"

    if [ -n "$SUBSCRIPTION_ID" ]; then
      run az account set --subscription "$SUBSCRIPTION_ID" \
        || die "Cannot select subscription $SUBSCRIPTION_ID"
    else
      SUBSCRIPTION_ID="$(az account show --query id -o tsv 2>/dev/null)"
    fi
    # For Azure the subscription id is what identifies the account to XamOps.
    CLOUD_ACCOUNT_ID="$SUBSCRIPTION_ID"
    ok "subscription $SUBSCRIPTION_ID"
    STORAGE_CLASS="${STORAGE_CLASS:-$AKS_STORAGE_CLASS}"

  elif [ "$PROVIDER" = "gke" ]; then
    gcloud auth list --filter=status:ACTIVE --format="value(account)" 2>/dev/null | grep -q . \
      || die "Not signed in to Google Cloud. Run: gcloud auth login"

    if [ -z "$PROJECT_ID" ]; then
      # Prints "(unset)" rather than failing when no project is configured, so both forms of
      # "no project" have to be treated as empty.
      PROJECT_ID="$(gcloud config get-value project 2>/dev/null | tr -d '\r')"
      case "$PROJECT_ID" in
        "(unset)") PROJECT_ID="" ;;
      esac
      [ -n "$PROJECT_ID" ] && info "using project from gcloud config: $PROJECT_ID"
    fi
    [ -n "$PROJECT_ID" ] \
      || die "--project is required for GKE (or run: gcloud config set project PROJECT_ID)"

    # The project id keys the cluster in XamOps, exactly as the 12-digit account number does for
    # EKS and the subscription id for AKS. It must match the project the GCP account was
    # onboarded with, or the agent's pushes have no account to attach to.
    CLOUD_ACCOUNT_ID="$PROJECT_ID"
    ok "project $PROJECT_ID"

    # --region is accepted as a synonym for --location so a command copied from the EKS shape
    # still does the right thing rather than silently ignoring the value.
    [ -z "$LOCATION" ] && LOCATION="$REGION"
    STORAGE_CLASS="${STORAGE_CLASS:-$GKE_STORAGE_CLASS}"

  else
    aws sts get-caller-identity >/dev/null 2>&1 \
      || die "Not authenticated to AWS. Configure credentials (aws configure / SSO / instance role)."

    # The 12-digit account number, not any XamOps-internal id — it keys the cluster in
    # agent_data_cache and has to match what the dashboard looks up.
    CLOUD_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)"
    [ -n "$CLOUD_ACCOUNT_ID" ] || die "Could not determine the AWS account id"
    ok "aws account $CLOUD_ACCOUNT_ID"

    # AWS is regional, so unlike a subscription there is no account-wide default to fall back on.
    if [ -z "$REGION" ]; then
      REGION="$(aws configure get region 2>/dev/null || true)"
      [ -n "$REGION" ] && info "using region from aws config: $REGION"
    fi
    [ -n "$REGION" ] || die "--region is required for EKS (no default found in your aws config)"
    ok "region $REGION"
    STORAGE_CLASS="${STORAGE_CLASS:-$EKS_STORAGE_CLASS}"
  fi

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
# Which gcloud flag addresses $LOCATION.
#
# A GCP zone is its region plus a letter suffix — us-central1-a versus us-central1 — so it has
# exactly one more dash-separated part. gcloud needs to be told which of the two it was given.
# Recent releases accept --location for either, but that flag is new enough that a customer's
# local gcloud may not have it, and counting the parts works on every version.
gke_location_flag() {
  case "$(printf '%s' "$LOCATION" | tr -cd '-' | wc -c | tr -d ' ')" in
    2) printf '%s' "--zone" ;;
    *) printf '%s' "--region" ;;
  esac
}

# GKE Autopilot manages the nodes itself and rejects privileged pods, hostPath volumes and
# hostNetwork at admission. Falco and Prometheus's node-exporter need all three, so they are
# skipped there rather than installed into a permanent rejection loop.
detect_autopilot() {
  AUTOPILOT=false
  [ "$PROVIDER" = "gke" ] || return 0
  if [ "$DRY_RUN" = true ]; then
    dim "dry-run: assuming a Standard cluster (Autopilot would skip Falco and node-exporter)"
    return 0
  fi

  local mode
  mode="$(gcloud container clusters describe "$CLUSTER_NAME" "$(gke_location_flag)" "$LOCATION" \
          --project "$PROJECT_ID" --format='value(autopilot.enabled)' 2>/dev/null | tr -d '\r')"
  case "$mode" in
    True|true)
      AUTOPILOT=true
      warn "Autopilot cluster — Falco and node-exporter will be skipped (both need privileged host access)"
      dim "everything else installs normally; node metrics come from kube-state-metrics and cadvisor"
      ;;
    *) ok "Standard cluster" ;;
  esac
}

# Guide Step 1.1.
connect_cluster() {
  local cluster="$1" rg="$2"
  step "Connecting to $cluster ($rg)"

  if [ "$PROVIDER" = "aks" ]; then
    run az aks get-credentials --resource-group "$rg" --name "$cluster" --overwrite-existing \
      || die "Could not fetch credentials for $cluster in $rg"
  elif [ "$PROVIDER" = "gke" ]; then
    run gcloud container clusters get-credentials "$cluster" \
      "$(gke_location_flag)" "$LOCATION" --project "$PROJECT_ID" \
      || die "Could not fetch credentials for $cluster in $LOCATION (project $PROJECT_ID)"
  else
    # --alias is load-bearing: without it the context is named after the cluster ARN, and the
    # assertion below (which is what stops us onboarding the wrong cluster) could never match.
    run aws eks update-kubeconfig --region "$REGION" --name "$cluster" --alias "$cluster" \
      || die "Could not fetch credentials for $cluster in $REGION"
  fi

  if [ "$DRY_RUN" = true ]; then
    dim "dry-run: skipping context assertion and node check"
    return 0
  fi

  # get-credentials names the context after the cluster. Asserting it prevents the failure mode
  # where a stale current-context silently sends the whole install at the wrong cluster.
  #
  # gcloud is the exception: it has no --alias, and names the context
  # gke_PROJECT_LOCATION_CLUSTER. That name is deterministic, so it is asserted as-is rather
  # than renaming a context in the user's kubeconfig.
  local ctx expected="$cluster"
  [ "$PROVIDER" = "gke" ] && expected="gke_${PROJECT_ID}_${LOCATION}_${cluster}"
  ctx="$(kubectl config current-context 2>/dev/null)"
  [ "$ctx" = "$expected" ] || die "kubectl context is '$ctx', expected '$expected'"

  kubectl get nodes >/dev/null 2>&1 || die "Cannot reach the API server for $cluster"

  local nodes k8s
  nodes="$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')"
  k8s="$(kubectl version -o json 2>/dev/null | jq -r '.serverVersion.gitVersion' 2>/dev/null)"
  ok "context $ctx — $nodes node(s)${k8s:+, Kubernetes $k8s}"

  kubectl auth can-i create clusterrole >/dev/null 2>&1 \
    || warn "You may lack cluster-admin; ClusterRole creation could fail"

}

# ─── Storage ──────────────────────────────────────────────────────────────────────────────
# A Pending PVC keeps Prometheus from ever starting, and the cause is not obvious from the pod
# status, so this is verified rather than assumed. It is also the single most common EKS
# onboarding failure, because unlike AKS there is no CSI StorageClass out of the box.
ensure_storage_class() {
  if [ "$DRY_RUN" = true ]; then
    dim "dry-run: skipping storage class check (would use $STORAGE_CLASS)"
    return 0
  fi

  if kubectl get storageclass "$STORAGE_CLASS" >/dev/null 2>&1; then
    ok "storageClass $STORAGE_CLASS"
    return 0
  fi

  # Any existing CSI-backed default is preferable to installing anything.
  local fallback
  fallback="$(kubectl get storageclass -o jsonpath='{range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{end}' 2>/dev/null)"
  if [ -n "$fallback" ]; then
    warn "StorageClass '$STORAGE_CLASS' not found; using cluster default '$fallback'"
    STORAGE_CLASS="$fallback"
    return 0
  fi

  # AKS ships managed-csi and GKE ships standard-rwo, so reaching here on either means the
  # cluster's storage has been changed in a way this script should not guess at.
  if [ "$PROVIDER" != "eks" ]; then
    die "StorageClass '$STORAGE_CLASS' not found and no default StorageClass exists"
  fi

  # EKS with no usable StorageClass. Fixing this means attaching an IAM policy to the node group
  # role, which is an account-level change. A script downloaded over curl must not do that
  # silently, so the default is to stop and hand the commands over.
  if [ "$SETUP_EBS_CSI" != true ]; then
    fail "no CSI StorageClass on this cluster, so Prometheus's volume can never bind"
    printf '\n   %sThis needs the EBS CSI driver, which requires an IAM change.%s\n' "$C_BOLD" "$C_RESET"
    printf '   Either re-run with %s--setup-ebs-csi%s, or run these yourself:\n\n' "$C_BOLD" "$C_RESET"
    local role
    role="$(eks_nodegroup_role "$CLUSTER_NAME")"
    cat <<EOF
     aws iam attach-role-policy \\
       --role-name ${role:-<NODEGROUP_ROLE_NAME>} \\
       --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy

     aws eks create-addon --region $REGION --cluster-name $CLUSTER_NAME \\
       --addon-name aws-ebs-csi-driver --resolve-conflicts OVERWRITE

     aws eks wait addon-active --region $REGION --cluster-name $CLUSTER_NAME \\
       --addon-name aws-ebs-csi-driver

EOF
    printf '   More restrictive alternative: create a dedicated role and use IRSA on the\n'
    printf '   ebs-csi-controller-sa ServiceAccount instead of the node role.\n'
    return 1
  fi

  setup_ebs_csi || return 1
  ok "storageClass $STORAGE_CLASS"
}

# Best-effort discovery of the node group's IAM role name, so the user does not have to find it.
eks_nodegroup_role() {
  local cluster="$1" ng arn
  ng="$(aws eks list-nodegroups --region "$REGION" --cluster-name "$cluster" \
        --query 'nodegroups[0]' --output text 2>/dev/null)"
  [ -n "$ng" ] && [ "$ng" != "None" ] || return 0
  arn="$(aws eks describe-nodegroup --region "$REGION" --cluster-name "$cluster" \
         --nodegroup-name "$ng" --query 'nodegroup.nodeRole' --output text 2>/dev/null)"
  [ -n "$arn" ] && printf '%s' "${arn##*/}"
}

setup_ebs_csi() {
  step "EBS CSI driver (explicitly requested with --setup-ebs-csi)"

  local role
  role="$(eks_nodegroup_role "$CLUSTER_NAME")"
  [ -n "$role" ] || { fail "could not determine the node group IAM role for $CLUSTER_NAME"; return 1; }
  info "node group role: $role"

  # Idempotent: attaching an already-attached policy is a no-op in IAM.
  run aws iam attach-role-policy --role-name "$role" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
    || { fail "could not attach AmazonEBSCSIDriverPolicy to $role"; run_tail; return 1; }
  ok "AmazonEBSCSIDriverPolicy attached"

  # create-addon fails if it already exists; that is fine, so the result is not fatal.
  run aws eks create-addon --region "$REGION" --cluster-name "$CLUSTER_NAME" \
    --addon-name aws-ebs-csi-driver --resolve-conflicts OVERWRITE || true
  run aws eks wait addon-active --region "$REGION" --cluster-name "$CLUSTER_NAME" \
    --addon-name aws-ebs-csi-driver \
    || { fail "the aws-ebs-csi-driver addon did not become active"; run_tail; return 1; }
  ok "aws-ebs-csi-driver addon active"

  # Demote any existing default. A patch, never a delete — existing PVCs keep working.
  local sc
  for sc in gp2 gp3; do
    kubectl get storageclass "$sc" >/dev/null 2>&1 || continue
    run kubectl patch storageclass "$sc" \
      -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' || true
  done

  apply_manifest "storageclass/gp3" <<'EOF'
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  fsType: ext4
  encrypted: "true"
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete
allowVolumeExpansion: true
EOF
  STORAGE_CLASS="gp3"
  ok "gp3 StorageClass created as cluster default"
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

  # node-exporter is a DaemonSet with hostPath mounts of /proc and /sys — exactly what Autopilot
  # refuses. Disabling it up front keeps the release healthy; kube-state-metrics and cadvisor
  # still cover the node metrics the agent reports.
  local node_exporter="true"
  if [ "$AUTOPILOT" = true ]; then
    node_exporter="false"
    dim "Autopilot: installing without node-exporter"
  fi

  local values
  values="$(mktemp)"
  cat > "$values" <<EOF
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
  enabled: $node_exporter
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

# ─── Part 1.4 (EKS): Metrics Server ───────────────────────────────────────────────────────
# AKS provides metrics-server as a managed addon and GKE has it built in on both Standard and
# Autopilot; EKS does not. The agent uses it for real-time pod/node usage, and `kubectl top`
# stops working without it.
install_metrics_server() {
  [ "$PROVIDER" = "eks" ] || return 0

  step "Metrics Server"
  if kubectl get apiservice v1beta1.metrics.k8s.io >/dev/null 2>&1; then
    ok "already present, skipping"
    return 0
  fi

  run helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ || true
  run helm repo update metrics-server || true

  # The quoted index matters: unquoted args[0] is glob-expanded by zsh and never reaches helm.
  if run helm upgrade --install metrics-server metrics-server/metrics-server \
      --namespace kube-system \
      --set 'args[0]=--kubelet-insecure-tls' \
      --reset-values --wait --timeout "$HELM_TIMEOUT"; then
    ok "metrics-server"
  else
    fail "metrics-server install failed — kubectl -n kube-system get pods -l app.kubernetes.io/name=metrics-server"
    run_tail
  fi
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

  # Falco reads syscalls, which means a privileged DaemonSet on every node. Autopilot does not
  # allow one, and the failure is an admission rejection several minutes into the install rather
  # than anything the user can fix, so say so once and move on.
  if [ "$AUTOPILOT" = true ]; then
    warn "Skipping Falco — GKE Autopilot does not permit the privileged DaemonSet it requires"
    dim "runtime security events are unavailable on Autopilot; Trivy and Kubescape are not affected"
    return 0
  fi

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

  local values exporter_block
  case "$PROVIDER" in
    eks)
      exporter_block="    aws:
      region: \"$REGION\"" ;;
    gke)
      exporter_block="    gcp:
      projectId: \"$PROJECT_ID\"" ;;
    *)
      exporter_block="    azure:
      subscriptionId: \"$CLOUD_ACCOUNT_ID\"" ;;
  esac

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
$exporter_block
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
  #
  # concurrentScanJobsLimit is lowered from the chart default of 10. Each scan pod reserves 100m
  # CPU and ~100M memory, so the default reserves over a full vCPU in bursts and starves small
  # clusters. Scans still all complete; they queue instead of flooding the scheduler.
  if run helm upgrade --install trivy-operator aquasecurity/trivy-operator \
      --namespace trivy-system --create-namespace \
      --set trivy.ignoreUnfixed=false \
      --set 'trivy.severity=UNKNOWN\,LOW\,MEDIUM\,HIGH\,CRITICAL' \
      --set operator.vulnerabilityScanner.enabled=true \
      --set operator.configAuditScannerEnabled=true \
      --set operator.concurrentScanJobsLimit=3 \
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
  #
  # vulnerabilityScan=disable drops kubevuln, which requests 1000Mi + 300m — the single largest
  # reservation in the whole stack, and enough to be unschedulable on a 2-node cluster. Its only
  # output is vulnerabilitymanifestsummaries, which the agent never reads: the collector lists
  # workloadconfigurationscansummaries alone and emits one batch, kubescape-compliance. Image
  # CVEs come from Trivy instead.
  if run helm upgrade --install kubescape kubescape/kubescape-operator \
      --namespace kubescape --create-namespace \
      --set clusterName="$CLUSTER_NAME" \
      --set capabilities.continuousScan=enable \
      --set capabilities.vulnerabilityScan=disable \
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

  # Karpenter is AWS-only, so enabling its collector elsewhere just logs failures every cycle.
  # GKE's equivalent, Node Auto Provisioning, is read from the GCP API rather than the cluster.
  local cloud_provider collect_karpenter
  case "$PROVIDER" in
    eks) cloud_provider="AWS";   collect_karpenter="true" ;;
    gke) cloud_provider="GCP";   collect_karpenter="false" ;;
    *)   cloud_provider="AZURE"; collect_karpenter="false" ;;
  esac

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
  CLOUD_ACCOUNT_ID: "$CLOUD_ACCOUNT_ID"
  CLOUD_PROVIDER: "$cloud_provider"
  COLLECTOR_K8S_RESOURCES: "true"
  COLLECTOR_PROMETHEUS_METRICS: "true"
  PROMETHEUS_URL: "http://prometheus-server.monitoring.svc.cluster.local:80"
  COLLECTOR_TRIVY: "true"
  COLLECTOR_KUBESCAPE: "true"
  COLLECTOR_OPENCOST: "true"
  COLLECTOR_KARPENTER: "$collect_karpenter"
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
        xamops.io/config-revision: "$(printf '%s|%s|%s|%s' "$XAMOPS_SERVER_URL" "$CLUSTER_NAME" "$CLOUD_ACCOUNT_ID" "$STACK_VERSION" | cksum | cut -d' ' -f1)"
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
    # Falco was deliberately not installed on Autopilot, so an empty namespace there is the
    # expected outcome rather than a failure that should fail the whole run.
    if [ "$ns" = "falco" ] && [ "$AUTOPILOT" = true ]; then
      dim "falco: skipped on Autopilot"
      continue
    fi
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
  CURRENT_CLUSTER="$1"

  # The second field of a target carries whatever locates the cluster for this provider: the
  # resource group on AKS, the zone or region on GKE, and nothing on EKS (--region covers it).
  RESOURCE_GROUP=""
  if [ "$PROVIDER" = "gke" ]; then
    LOCATION="$2"
  else
    RESOURCE_GROUP="$2"
  fi

  printf '\n%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"
  if [ "$PROVIDER" = "gke" ]; then
    printf '%s  %s  (location: %s, project: %s)%s\n' \
      "$C_BOLD" "$CLUSTER_NAME" "$LOCATION" "$PROJECT_ID" "$C_RESET"
  elif [ -n "$RESOURCE_GROUP" ]; then
    printf '%s  %s  (resource group: %s)%s\n' "$C_BOLD" "$CLUSTER_NAME" "$RESOURCE_GROUP" "$C_RESET"
  else
    printf '%s  %s  (region: %s)%s\n' "$C_BOLD" "$CLUSTER_NAME" "$REGION" "$C_RESET"
  fi
  printf '%s══════════════════════════════════════════════════════════════%s\n' "$C_BOLD" "$C_RESET"

  connect_cluster "$CLUSTER_NAME" "${RESOURCE_GROUP:-$LOCATION}"
  # Which components are installable is a property of the cluster, so it is read before anything
  # is installed rather than reacted to afterwards.
  detect_autopilot
  # Before anything is installed: Prometheus cannot start without a bindable volume, and on EKS
  # this may stop the run to ask for IAM consent.
  ensure_storage_class || return 1
  repair_legacy
  install_namespaces
  install_prometheus
  install_metrics_server
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
  printf '%sXamOps Kubernetes onboarding%s  (stack %s, agent %s)\n' "$C_BOLD" "$C_RESET" "$STACK_VERSION" "$AGENT_IMAGE"
  [ "$DRY_RUN" = true ] && printf '%sDRY RUN — nothing will be changed%s\n' "$C_YELLOW" "$C_RESET"

  preflight

  local -a targets=()

  if [ "$ALL_CLUSTERS" = true ] && [ "$PROVIDER" = "aks" ]; then
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

  elif [ "$ALL_CLUSTERS" = true ] && [ "$PROVIDER" = "gke" ]; then
    # A GKE project spans locations, so --all means every cluster in the project regardless of
    # which zone or region it sits in.
    step "Discovering GKE clusters in project $PROJECT_ID"
    local name loc status
    while IFS=$'\t' read -r name loc status; do
      [ -n "${name:-}" ] || continue
      if [ "$status" != "RUNNING" ]; then
        # A cluster still provisioning, or one that is suspended, has no usable API server;
        # attempting it just hangs on the credential fetch.
        warn "skipping $name — status=$status"
        continue
      fi
      targets+=("$name|$loc")
      info "queued $name ($loc)"
    done < <(gcloud container clusters list --project "$PROJECT_ID" \
             --format="value(name,location,status)" 2>/dev/null)
    [ ${#targets[@]} -gt 0 ] || die "No running GKE clusters found in project $PROJECT_ID"

  elif [ "$ALL_CLUSTERS" = true ]; then
    # EKS is region-scoped: there is no account-wide list, so --all means "this region".
    step "Discovering EKS clusters in $REGION"
    local name status
    while read -r name; do
      [ -n "${name:-}" ] || continue
      status="$(aws eks describe-cluster --region "$REGION" --name "$name" \
                --query 'cluster.status' --output text 2>/dev/null)"
      if [ "$status" != "ACTIVE" ]; then
        warn "skipping $name — status=$status"
        continue
      fi
      targets+=("$name|")
      info "queued $name"
    done < <(aws eks list-clusters --region "$REGION" --query 'clusters[]' --output text 2>/dev/null | tr '\t' '\n')
    [ ${#targets[@]} -gt 0 ] || die "No active EKS clusters found in region $REGION"

  else
    [ -n "$CLUSTER_NAME" ] || die "--cluster is required (or use --all)"
    if [ "$PROVIDER" = "eks" ]; then
      # Nothing to resolve: an EKS cluster is identified by name within a region.
      local st
      st="$(aws eks describe-cluster --region "$REGION" --name "$CLUSTER_NAME" \
            --query 'cluster.status' --output text 2>/dev/null)"
      [ -n "$st" ] && [ "$st" != "None" ] \
        || die "No EKS cluster named '$CLUSTER_NAME' in region $REGION"
      [ "$st" = "ACTIVE" ] || die "Cluster '$CLUSTER_NAME' is not ACTIVE (status=$st)"
      targets+=("$CLUSTER_NAME|")

    elif [ "$PROVIDER" = "gke" ]; then
      if [ -z "$LOCATION" ]; then
        # Cluster names are unique per location, not per project, so resolve rather than guess.
        # Picking the first match would silently onboard the wrong cluster, and the symptom —
        # data under the right name from the wrong place — is very hard to trace back to here.
        local locs loc_count
        locs="$(gcloud container clusters list --project "$PROJECT_ID" \
                --filter="name=$CLUSTER_NAME" --format="value(location)" 2>/dev/null \
                | tr -d '\r' | sed '/^$/d')"
        loc_count="$(printf '%s\n' "$locs" | sed '/^$/d' | wc -l | tr -d ' ')"
        case "$loc_count" in
          0) die "No GKE cluster named '$CLUSTER_NAME' in project $PROJECT_ID" ;;
          1) LOCATION="$locs"; info "resolved location: $LOCATION" ;;
          *) printf '   %s\n' "$locs" | sed 's/^/     - /'
             die "'$CLUSTER_NAME' exists in $loc_count locations; pass --location to disambiguate" ;;
        esac
      else
        local gst
        gst="$(gcloud container clusters describe "$CLUSTER_NAME" "$(gke_location_flag)" "$LOCATION" \
               --project "$PROJECT_ID" --format='value(status)' 2>/dev/null | tr -d '\r')"
        [ -n "$gst" ] \
          || die "No GKE cluster named '$CLUSTER_NAME' at $LOCATION in project $PROJECT_ID"
        [ "$gst" = "RUNNING" ] || die "Cluster '$CLUSTER_NAME' is not RUNNING (status=$gst)"
      fi
      targets+=("$CLUSTER_NAME|$LOCATION")

    elif [ -z "$RESOURCE_GROUP" ]; then
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
      targets+=("$CLUSTER_NAME|$RESOURCE_GROUP")
    else
      targets+=("$CLUSTER_NAME|$RESOURCE_GROUP")
    fi
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
  printf '   provider           : %s\n' "$PROVIDER"
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
