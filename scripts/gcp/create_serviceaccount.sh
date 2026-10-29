#!/bin/bash

# XamOps Service Account Setup - Complete & Future-Proof
#
# USAGE (read-only):   bash <(curl -s https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh) PROJECT_ID
# USAGE (read-write):  bash <(curl -s https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh) PROJECT_ID read-write
#
# Options (anywhere after the script name):
#   --no-key                     Do not create a service account key.
#   --refresh                    The service account must exist already. Re-applies its roles and
#                                per-service-account grants; skips API enablement and key creation.
#   --dry-run                    Print every gcloud command that would change something instead of
#                                running it. Read-only discovery still runs. No key is created.
#   --revoke-legacy-roles        read-write only. Removes the five project-wide write roles earlier
#                                versions of this script granted: roles/compute.instanceAdmin.v1,
#                                roles/iam.serviceAccountUser, roles/container.admin,
#                                roles/storage.objectAdmin, roles/monitoring.alertPolicyEditor.
#                                Runs only after the replacement grants below succeeded, and
#                                only when every instance group, template and VM could be listed.
#   --service-account-name NAME  Service account to create or update (default: xamops).
#   -h, --help                   Show usage.
#
# Who can run it: project Owner, or Project IAM Admin + Service Account Admin + Service Account
# Key Admin + Service Usage Admin. Read-write also needs Role Administrator (roles/iam.roleAdmin)
# for the two custom roles, and Compute Viewer (included in Owner) to find the service accounts
# your instance groups and VMs run as.
#
# What read-write grants, in addition to the read-only roles:
#   * custom role xamopsAutoSpotting (Spot conversion of managed instance groups):
#       compute.disks.create, compute.images.useReadOnly, compute.instanceGroupManagers.get,
#       compute.instanceGroupManagers.update, compute.instanceTemplates.create,
#       compute.instanceTemplates.delete, compute.instanceTemplates.get,
#       compute.instanceTemplates.useReadOnly, compute.instances.create,
#       compute.instances.setLabels, compute.instances.setMetadata, compute.instances.setTags,
#       compute.subnetworks.use, compute.subnetworks.useExternalIp
#   * custom role xamopsComputeOps (Cloudsitter start/stop, VM and disk rightsizing):
#       compute.disks.delete, compute.disks.resize, compute.instances.setMachineType,
#       compute.instances.start, compute.instances.stop
#   * roles/iam.serviceAccountUser on each service account (in this project) that a non-GKE
#     managed instance group's templates or a non-GKE VM runs as, and on the default compute
#     service account. Never project-wide. Service accounts owned by another project are not
#     granted; the script prints the command for that project's owner.
# Budgets need roles/billing.costsManager on the billing account, which no mode grants.
#
# Re-run with --refresh after adding instance templates or VMs that run as a new service
# account, or when XamOps announces a new version of its roles.
#
# The key is written to a file with permissions 600 and never printed.
#
# NOTE: This file is the source of truth for the script hosted at the URL
# above. After making changes here, re-upload it to that S3 location so the
# "Add GCP Account" page picks up the fix.

# This script is bash-only. Under sh/dash it would stop at the first array with a syntax
# error, so say how to run it instead.
if [ -z "${BASH_VERSION:-}" ]; then
    echo "This script needs bash. Run: bash <(curl -s https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh) PROJECT_ID [read-only|read-write]" >&2
    exit 1
fi

SCRIPT_URL="https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh"

# Dry-run lines are written to fd 3, a copy of the terminal, so they stay visible when the
# command runs inside $(...).
exec 3>&1

usage() {
    echo "Usage: bash <(curl -s $SCRIPT_URL) PROJECT_ID [read-only|read-write] [options]"
    echo ""
    echo "Options:"
    echo "  --no-key                     Do not create a service account key."
    echo "  --refresh                    Re-apply roles and per-service-account grants to an existing"
    echo "                               service account. No API enablement, no key."
    echo "  --dry-run                    Print every change instead of making it. No key."
    echo "  --revoke-legacy-roles        read-write only: remove the five project-wide write roles"
    echo "                               earlier versions of this script granted."
    echo "  --service-account-name NAME  Service account to create or update (default: xamops)."
    echo "  -h, --help                   Show this help."
}

usage_error() {
    echo "ERROR: $1" >&2
    echo "" >&2
    usage >&2
    exit 1
}

# ------------------------------------------------------------------
# Arguments. Everything is parsed and validated before the first gcloud call.
# ------------------------------------------------------------------
NAME_PATTERN='^[a-z][-a-z0-9]{4,28}[a-z0-9]$'
PROJECT_ID=""
ACCESS_TYPE=""
SERVICE_ACCOUNT_NAME="xamops"
NO_KEY=0
REFRESH=0
DRY_RUN=0
REVOKE_LEGACY=0
POSITIONAL_COUNT=0

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --no-key)
            NO_KEY=1
            ;;
        --refresh)
            REFRESH=1
            ;;
        --dry-run)
            DRY_RUN=1
            ;;
        --revoke-legacy-roles)
            REVOKE_LEGACY=1
            ;;
        --service-account-name)
            if [ $# -lt 2 ]; then
                usage_error "--service-account-name needs a value"
            fi
            SERVICE_ACCOUNT_NAME="$2"
            shift
            ;;
        --service-account-name=*)
            SERVICE_ACCOUNT_NAME="${1#*=}"
            ;;
        -*)
            usage_error "Unknown option '$1'"
            ;;
        *)
            POSITIONAL_COUNT=$((POSITIONAL_COUNT + 1))
            if [ "$POSITIONAL_COUNT" -eq 1 ]; then
                PROJECT_ID="$1"
            elif [ "$POSITIONAL_COUNT" -eq 2 ]; then
                ACCESS_TYPE="$1"
            else
                usage_error "Unexpected argument '$1'"
            fi
            ;;
    esac
    shift
done

ACCESS_TYPE="${ACCESS_TYPE:-read-only}"

if [ -z "$PROJECT_ID" ]; then
    usage_error "PROJECT_ID is required"
fi

if ! [[ "$PROJECT_ID" =~ $NAME_PATTERN ]]; then
    usage_error "PROJECT_ID must be 6-30 lowercase letters, digits or hyphens, starting with a letter (got: '$PROJECT_ID')"
fi

if [ "$ACCESS_TYPE" != "read-only" ] && [ "$ACCESS_TYPE" != "read-write" ]; then
    usage_error "ACCESS_TYPE must be 'read-only' or 'read-write' (got: '$ACCESS_TYPE')"
fi

if [ "$REVOKE_LEGACY" -eq 1 ] && [ "$ACCESS_TYPE" != "read-write" ]; then
    usage_error "--revoke-legacy-roles only applies to read-write"
fi

if ! [[ "$SERVICE_ACCOUNT_NAME" =~ $NAME_PATTERN ]]; then
    usage_error "--service-account-name must be 6-30 lowercase letters, digits or hyphens, starting with a letter (got: '$SERVICE_ACCOUNT_NAME')"
fi

SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

CREATE_KEY=1
if [ "$NO_KEY" -eq 1 ] || [ "$REFRESH" -eq 1 ] || [ "$DRY_RUN" -eq 1 ]; then
    CREATE_KEY=0
fi

DRY_RUN_NOTE=""
if [ "$DRY_RUN" -eq 1 ]; then
    DRY_RUN_NOTE=" (dry run: not applied)"
fi

# Read-write custom roles. "v1" in the description is the role version; bump it, and the
# permission list, together.
AUTOSPOTTING_ROLE_ID="xamopsAutoSpotting"
AUTOSPOTTING_ROLE_TITLE="XamOps AutoSpotting"
AUTOSPOTTING_ROLE_DESCRIPTION="xamops-as-role v1: Spot conversion of managed instance groups by XamOps AutoSpotting. Managed by the XamOps onboarding script; re-run it to update."
# setLabels, setTags and subnetworks.useExternalIp: a live MIG PATCH was refused without each one when
# the template carried labels, network tags or an external IP.
AUTOSPOTTING_ROLE_PERMISSIONS="compute.disks.create,compute.images.useReadOnly,compute.instanceGroupManagers.get,compute.instanceGroupManagers.update,compute.instanceTemplates.create,compute.instanceTemplates.delete,compute.instanceTemplates.get,compute.instanceTemplates.useReadOnly,compute.instances.create,compute.instances.setLabels,compute.instances.setMetadata,compute.instances.setTags,compute.subnetworks.use,compute.subnetworks.useExternalIp"

COMPUTE_OPS_ROLE_ID="xamopsComputeOps"
COMPUTE_OPS_ROLE_TITLE="XamOps Compute Operations"
COMPUTE_OPS_ROLE_DESCRIPTION="xamops-ops-role v1: Cloudsitter start/stop, VM rightsizing and disk rightsizing by XamOps. Managed by the XamOps onboarding script."
COMPUTE_OPS_ROLE_PERMISSIONS="compute.disks.delete,compute.disks.resize,compute.instances.setMachineType,compute.instances.start,compute.instances.stop"

# Project-wide write roles granted by earlier versions of this script. Nothing in XamOps needs
# them any more; --revoke-legacy-roles removes them.
LEGACY_WRITE_ROLES=(
    "roles/compute.instanceAdmin.v1"
    "roles/iam.serviceAccountUser"
    "roles/container.admin"
    "roles/storage.objectAdmin"
    "roles/monitoring.alertPolicyEditor"
)

# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

# Every gcloud call that changes something goes through run, so --dry-run can print it
# instead of running it.
run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '  [dry-run] gcloud' >&3
        printf ' %q' "$@" >&3
        printf '\n' >&3
        return 0
    fi
    gcloud "$@"
}

# Sleeps, except in a dry run.
pause() {
    if [ "$DRY_RUN" -eq 0 ]; then
        sleep "$1"
    fi
}

# Read-only gcloud call with one retry after 10 s. Prints the output; fails when both
# attempts fail. stdin is closed so gcloud cannot eat the input of a surrounding read loop.
gcloud_read() {
    if GCLOUD_READ_OUTPUT=$(gcloud "$@" 2>/dev/null < /dev/null); then
        printf '%s\n' "$GCLOUD_READ_OUTPUT"
        return 0
    fi
    pause 10
    if GCLOUD_READ_OUTPUT=$(gcloud "$@" 2>/dev/null < /dev/null); then
        printf '%s\n' "$GCLOUD_READ_OUTPUT"
        return 0
    fi
    return 1
}

# Checks that a service account exists before anything is granted on it. Returns 0 when it
# exists, 1 when IAM answers NOT_FOUND, and 2 on any other error, kept in SA_CHECK_ERROR: a
# permission or transient error says nothing about whether the account exists, so only
# NOT_FOUND counts as absent. Tried twice, like gcloud_read.
SA_CHECK_ERROR=""
check_service_account() {
    SA_CHECK_ERROR=""
    SA_CHECK_ATTEMPT=1
    while [ "$SA_CHECK_ATTEMPT" -le 2 ]; do
        if SA_CHECK_OUTPUT=$(gcloud iam service-accounts describe "$1" --project="$PROJECT_ID" \
            2>&1 < /dev/null); then
            return 0
        fi
        if printf '%s\n' "$SA_CHECK_OUTPUT" | grep -q "NOT_FOUND"; then
            return 1
        fi
        SA_CHECK_ERROR="$SA_CHECK_OUTPUT"
        if [ "$SA_CHECK_ATTEMPT" -eq 1 ]; then
            pause 10
        fi
        SA_CHECK_ATTEMPT=$((SA_CHECK_ATTEMPT + 1))
    done
    return 2
}

echo "================================================"
echo "🚀 XamOps Service Account Setup"
echo "================================================"
echo "Project ID:  $PROJECT_ID"
echo "Access Type: $ACCESS_TYPE"
echo "Service Account: $SERVICE_ACCOUNT_EMAIL"
if [ "$DRY_RUN" -eq 1 ]; then
    echo "Mode:        dry run (nothing is changed, no key is created)"
elif [ "$REFRESH" -eq 1 ]; then
    echo "Mode:        refresh (roles and grants only, no key)"
fi
echo ""

# Step 1: Enable ALL Required APIs First
if [ "$REFRESH" -eq 1 ]; then
    echo "[1/4] Enabling GCP APIs... skipped (--refresh)"
    echo ""
else
echo "[1/4] Enabling GCP APIs (this may take 2-3 minutes)..."
echo "  This step ensures all services are accessible"
echo ""

APIS=(
    # Core Infrastructure
    "compute.googleapis.com"
    "storage.googleapis.com"
    "storage-api.googleapis.com"

    # Databases & Storage
    "bigquery.googleapis.com"
    "sqladmin.googleapis.com"
    "spanner.googleapis.com"
    "datastore.googleapis.com"
    "redis.googleapis.com"

    # Containers & Serverless
    "container.googleapis.com"
    "cloudfunctions.googleapis.com"
    "run.googleapis.com"
    "appengine.googleapis.com"

    # Data Analytics
    "dataflow.googleapis.com"
    "dataproc.googleapis.com"
    "dataplex.googleapis.com"
    "datacatalog.googleapis.com"
    "composer.googleapis.com"

    # AI/ML
    "aiplatform.googleapis.com"
    "ml.googleapis.com"
    "notebooks.googleapis.com"

    # Messaging & Integration
    "pubsub.googleapis.com"
    "cloudscheduler.googleapis.com"
    "cloudtasks.googleapis.com"
    "eventarc.googleapis.com"

    # Developer Tools
    "cloudbuild.googleapis.com"
    "artifactregistry.googleapis.com"
    "sourcerepo.googleapis.com"
    "containerregistry.googleapis.com"

    # Networking
    "dns.googleapis.com"
    "servicedirectory.googleapis.com"
    "networkmanagement.googleapis.com"
    "networksecurity.googleapis.com"

    # Security & Identity
    "cloudkms.googleapis.com"
    "secretmanager.googleapis.com"
    "securitycenter.googleapis.com"
    "iam.googleapis.com"
    "iamcredentials.googleapis.com"
    "certificatemanager.googleapis.com"
    "privateca.googleapis.com"

    # Monitoring & Operations
    "monitoring.googleapis.com"
    "logging.googleapis.com"
    "cloudtrace.googleapis.com"
    "cloudprofiler.googleapis.com"
    "clouderrorreporting.googleapis.com"

    # Billing & Management
    "cloudbilling.googleapis.com"
    "cloudresourcemanager.googleapis.com"
    "recommender.googleapis.com"
    "cloudasset.googleapis.com"
    "cloudcommerceconsumerprocurement.googleapis.com"

    # API Management
    "apigateway.googleapis.com"
    "apigee.googleapis.com"
    "endpoints.googleapis.com"
    "serviceusage.googleapis.com"
    "servicemanagement.googleapis.com"

    # VM Management
    "osconfig.googleapis.com"
    "oslogin.googleapis.com"

    # Migration & Transfer
    "storagetransfer.googleapis.com"
    "transferappliance.googleapis.com"
    "vmmigration.googleapis.com"

    # Workflows
    "workflows.googleapis.com"

    # Healthcare (if needed)
    "healthcare.googleapis.com"

    # Retail (if needed)
    "retail.googleapis.com"
)

TOTAL_APIS=${#APIS[@]}
CURRENT_API=0
ENABLED_COUNT=0

for api in "${APIS[@]}"; do
    CURRENT_API=$((CURRENT_API + 1))
    printf "  [%d/%d] Enabling %s..." "$CURRENT_API" "$TOTAL_APIS" "$api"

    if run services enable "$api" --project="$PROJECT_ID" 2>/dev/null; then
        ENABLED_COUNT=$((ENABLED_COUNT + 1))
        echo " ✓"
    else
        echo " (skipped)"
    fi
done

echo ""
echo "✓ APIs enabled: $ENABLED_COUNT/$TOTAL_APIS"
echo ""

if [ "$ENABLED_COUNT" -eq 0 ]; then
    echo "❌ ERROR: No APIs could be enabled for project '$PROJECT_ID'."
    echo "This usually means billing is not enabled on this project, or your"
    echo "account does not have the 'serviceusage.services.enable' permission"
    echo "(requires Owner, Editor, or Service Usage Admin)."
    echo ""
    echo "Fix this and re-run the script before connecting to XamOps."
    exit 1
fi
fi

# Step 2: Create Service Account
SA_WAS_CREATED=0
if [ "$REFRESH" -eq 1 ]; then
    echo "[2/4] Checking service account: $SERVICE_ACCOUNT_NAME..."
    if ! gcloud iam service-accounts describe "$SERVICE_ACCOUNT_EMAIL" \
        --project="$PROJECT_ID" > /dev/null 2>&1; then
        echo "❌ ERROR: --refresh needs an existing service account, and"
        echo "   $SERVICE_ACCOUNT_EMAIL was not found."
        echo "   Run the script once without --refresh to create it."
        exit 1
    fi
else
echo "[2/4] Creating service account: $SERVICE_ACCOUNT_NAME..."
SA_CREATE_OUTPUT=$(run iam service-accounts create "$SERVICE_ACCOUNT_NAME" \
    --project="$PROJECT_ID" \
    --display-name="XamOps Cloud Management Platform" \
    --description="Service account for XamOps with comprehensive read access to all GCP services" \
    2>&1)
SA_CREATE_STATUS=$?

SA_WAS_CREATED=1

if [ $SA_CREATE_STATUS -ne 0 ]; then
    if echo "$SA_CREATE_OUTPUT" | grep -qi "already exists"; then
        echo "  ℹ️  Service account already exists, continuing..."
        SA_WAS_CREATED=0
    else
        echo "❌ ERROR: Failed to create service account '$SERVICE_ACCOUNT_NAME':"
        echo "$SA_CREATE_OUTPUT"
        echo ""
        echo "Your account needs the 'roles/iam.serviceAccountAdmin' role (or"
        echo "Owner/Editor) on this project to create service accounts."
        exit 1
    fi
fi

# A freshly created service account is not immediately visible to every IAM
# backend. Binding roles right away makes the first few calls fail with
# "Service account ... does not exist". Wait until it is readable.
if [ "$DRY_RUN" -eq 0 ]; then
    printf "  Waiting for service account to propagate..."
    SA_READY=0
    SA_WAIT_ATTEMPT=0
    while [ "$SA_WAIT_ATTEMPT" -lt 15 ]; do
        if gcloud iam service-accounts describe "$SERVICE_ACCOUNT_EMAIL" \
            --project="$PROJECT_ID" > /dev/null 2>&1; then
            SA_READY=1
            break
        fi
        SA_WAIT_ATTEMPT=$((SA_WAIT_ATTEMPT + 1))
        printf "."
        sleep 2
    done

    if [ "$SA_READY" -eq 1 ]; then
        echo " ✓"
    else
        echo " (timed out, continuing anyway)"
    fi

    # Even once readable, the IAM policy service can lag a few seconds behind for
    # a brand-new account. Small one-time buffer; the per-role retry covers the rest.
    if [ "$SA_WAS_CREATED" -eq 1 ]; then
        sleep 5
    fi
fi
fi

echo "✓ Service account ready"
echo ""

# ------------------------------------------------------------------
# IAM binding helpers
# ------------------------------------------------------------------
# IAM policy writes are a read-modify-write of the whole policy and can fail
# transiently: propagation lag on a new service account, a stale read after a
# preceding write ("There were concurrent policy changes"), or setIamPolicy
# rate limits when looping over many roles. Retry with backoff and keep the
# real gcloud error so failures can be reported accurately instead of guessed.

# Older gcloud releases do not support --condition. Probe once so the flag can
# be added when available (it suppresses the interactive condition prompt on
# policies that already have conditional bindings).
CONDITION_FLAG=""
if gcloud projects add-iam-policy-binding --help 2>/dev/null | grep -q -- "--condition"; then
    CONDITION_FLAG="--condition=None"
fi

IAM_BIND_ERROR=""

# Runs one IAM policy change (the gcloud arguments) with retry and fail-fast rules.
iam_policy_change() {
    BIND_ATTEMPT=1
    BIND_MAX_ATTEMPTS=4
    IAM_BIND_ERROR=""

    while [ "$BIND_ATTEMPT" -le "$BIND_MAX_ATTEMPTS" ]; do
        BIND_OUTPUT=$(run "$@" 2>&1)
        BIND_STATUS=$?

        if [ $BIND_STATUS -eq 0 ]; then
            return 0
        fi

        IAM_BIND_ERROR="$BIND_OUTPUT"

        # Deterministic failures never succeed on retry — fail fast so the
        # user sees the real cause in seconds instead of minutes of backoff.
        #   * permission / org-policy denials
        #   * conditional-binding prompt (policy already has conditional
        #     bindings and gcloud needs a --condition answer; older gcloud
        #     without --condition support hits this and cannot be answered
        #     non-interactively)
        #   * unsupported flag / bad invocation
        if echo "$BIND_OUTPUT" | grep -qiE "PERMISSION_DENIED|does not have permission|violates constraint|Policy update access denied|Cannot prompt during non-interactive|specify a condition|condition is required|bindings with conditions|unrecognized arguments"; then
            return 1
        fi

        BIND_ATTEMPT=$((BIND_ATTEMPT + 1))
        if [ "$BIND_ATTEMPT" -le "$BIND_MAX_ATTEMPTS" ]; then
            sleep $((BIND_ATTEMPT * 3))
        fi
    done

    return 1
}

# Grants a project-level role to the XamOps service account.
bind_role() {
    iam_policy_change projects add-iam-policy-binding "$PROJECT_ID" \
        --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --role="$1" \
        ${CONDITION_FLAG:+"$CONDITION_FLAG"} \
        --quiet
}

# Lets the XamOps service account act as one service account (roles/iam.serviceAccountUser
# granted on that service account only, not on the project).
bind_sa_role() {
    iam_policy_change iam service-accounts add-iam-policy-binding "$1" \
        --project="$PROJECT_ID" \
        --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --role="roles/iam.serviceAccountUser" \
        ${CONDITION_FLAG:+"$CONDITION_FLAG"} \
        --quiet
}

# Removes a project-level role from the XamOps service account.
unbind_role() {
    iam_policy_change projects remove-iam-policy-binding "$PROJECT_ID" \
        --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --role="$1" \
        ${CONDITION_FLAG:+"$CONDITION_FLAG"} \
        --quiet
}

# Creates the project custom role, or brings an existing one to exactly this definition.
# update sets the exact permission list, so re-runs and version bumps converge.
CUSTOM_ROLE_ERROR=""
CUSTOM_ROLE_RESULT=""
ensure_custom_role() {
    CR_ID="$1"
    CR_TITLE="$2"
    CR_DESCRIPTION="$3"
    CR_PERMISSIONS="$4"
    CUSTOM_ROLE_ERROR=""
    CUSTOM_ROLE_RESULT=""

    if ! CR_DELETED=$(gcloud iam roles describe "$CR_ID" --project="$PROJECT_ID" \
        --format="value(deleted)" 2>/dev/null); then
        if ! CR_OUTPUT=$(run iam roles create "$CR_ID" \
            --project="$PROJECT_ID" \
            --title="$CR_TITLE" \
            --description="$CR_DESCRIPTION" \
            --permissions="$CR_PERMISSIONS" \
            --stage=GA \
            --quiet 2>&1); then
            # A role ID that is still reserved after deletion fails with e.g. "FAILED_PRECONDITION:
            # You can't create a role_id (ID) which has been marked for deletion."
            if echo "$CR_OUTPUT" | grep -qiE "37 days|recently deleted|was deleted|been deleted|deleted state|marked for deletion"; then
                CUSTOM_ROLE_ERROR="The role ID $CR_ID is still being deleted: Google Cloud reserves a deleted custom role ID for 37 days. Wait until then, or undelete it within 7 days of deletion (gcloud iam roles undelete $CR_ID --project=$PROJECT_ID) and re-run this script.
$CR_OUTPUT"
            else
                CUSTOM_ROLE_ERROR="$CR_OUTPUT"
            fi
            return 1
        fi
        CUSTOM_ROLE_RESULT="created"
        return 0
    fi

    if [ "$CR_DELETED" = "True" ]; then
        if ! CR_OUTPUT=$(run iam roles undelete "$CR_ID" --project="$PROJECT_ID" --quiet 2>&1); then
            CUSTOM_ROLE_ERROR="$CR_OUTPUT"
            return 1
        fi
    fi

    if ! CR_OUTPUT=$(run iam roles update "$CR_ID" \
        --project="$PROJECT_ID" \
        --title="$CR_TITLE" \
        --description="$CR_DESCRIPTION" \
        --permissions="$CR_PERMISSIONS" \
        --stage=GA \
        --quiet 2>&1); then
        CUSTOM_ROLE_ERROR="$CR_OUTPUT"
        return 1
    fi
    CUSTOM_ROLE_RESULT="updated"
    return 0
}

# Reports a custom role that could not be created or updated, and stops without a key.
custom_role_failed() {
    echo ""
    echo "❌ ERROR: Failed to create or update the custom role $1:"
    echo "$CUSTOM_ROLE_ERROR" | sed 's/^/     /'
    echo ""
    echo "Creating custom roles needs 'roles/iam.roleAdmin' (Role Administrator)"
    echo "or Owner on '$PROJECT_ID'."
    echo ""
    echo "Aborting WITHOUT generating a key — read-write mode requires its write roles."
    exit 1
}

# Number of comma-separated permissions in a list.
permission_count() {
    printf '%s\n' "$1" | tr ',' '\n' | grep -c .
}

# Step 3: Grant Comprehensive Permissions
echo "[3/4] Granting comprehensive read-only access..."

# Core roles that provide 90%+ coverage
CORE_ROLES=(
    "roles/viewer"                          # Read access to ALL resources
    "roles/recommender.viewer"              # Optimization recommendations
    "roles/securitycenter.adminViewer"      # Security findings
    "roles/iam.securityReviewer"            # IAM policy review
    "roles/monitoring.viewer"               # Metrics & monitoring
    "roles/logging.viewer"                  # Log viewing
    "roles/cloudasset.viewer"               # Asset inventory
)

# Optional roles — failures here are non-fatal (require billing account admin)
OPTIONAL_ROLES=(
    "roles/billing.viewer"                  # Billing data access
)

# Enhanced service-specific roles for better data access
SERVICE_ROLES=(
    "roles/compute.viewer"
    "roles/bigquery.dataViewer"
    "roles/bigquery.metadataViewer"
    "roles/bigquery.resourceViewer"
    "roles/cloudsql.viewer"
    "roles/storage.objectViewer"
    "roles/container.clusterViewer"
    "roles/aiplatform.viewer"
    "roles/pubsub.viewer"
    "roles/dataplex.viewer"
    "roles/cloudscheduler.viewer"
    "roles/osconfig.viewer"
    "roles/cloudfunctions.viewer"
    "roles/run.viewer"
    "roles/appengine.appViewer"
    "roles/cloudbuild.builds.viewer"
    "roles/artifactregistry.reader"
    "roles/cloudkms.viewer"
    "roles/secretmanager.viewer"
    "roles/dns.reader"
)

ALL_ROLES=("${CORE_ROLES[@]}" "${SERVICE_ROLES[@]}")
FAILED_ROLES=()
FAILED_ROLE_ERRORS=()
FAILED_OPTIONAL_ROLES=()

echo "  Binding ${#ALL_ROLES[@]} IAM roles to $SERVICE_ACCOUNT_EMAIL..."

# Circuit breaker: if several roles in a row fail, the cause is systemic
# (permissions, org policy, conditional-binding prompt) rather than specific
# to one role. Stop immediately instead of working through all 27 roles.
CONSECUTIVE_FAILS=0
SYSTEMIC_ABORT=0
ROLES_ATTEMPTED=0

for role in "${ALL_ROLES[@]}"; do
    ROLES_ATTEMPTED=$((ROLES_ATTEMPTED + 1))
    if bind_role "$role"; then
        CONSECUTIVE_FAILS=0
    else
        FAILED_ROLES+=("$role")
        FAILED_ROLE_ERRORS+=("$IAM_BIND_ERROR")
        CONSECUTIVE_FAILS=$((CONSECUTIVE_FAILS + 1))
        if [ "$CONSECUTIVE_FAILS" -ge 3 ]; then
            SYSTEMIC_ABORT=1
            break
        fi
    fi
done

if [ ${#FAILED_ROLES[@]} -gt 0 ]; then
    echo ""
    echo "❌ ERROR: Failed to grant ${#FAILED_ROLES[@]} of ${#ALL_ROLES[@]} IAM role(s) to $SERVICE_ACCOUNT_EMAIL"
    if [ "$SYSTEMIC_ABORT" -eq 1 ]; then
        echo "   Stopped early: 3 roles failed in a row, so the cause affects"
        echo "   every role rather than a specific one. The remaining"
        echo "   $((${#ALL_ROLES[@]} - ROLES_ATTEMPTED)) role(s) were not attempted."
    fi
    echo ""
    FAILED_INDEX=0
    for role in "${FAILED_ROLES[@]}"; do
        echo "   - $role"
        echo "${FAILED_ROLE_ERRORS[$FAILED_INDEX]}" | sed 's/^/     /'
        echo ""
        FAILED_INDEX=$((FAILED_INDEX + 1))
    done
    echo "Read the gcloud error above — it states the actual cause. Common ones:"
    echo ""
    echo "  * PERMISSION_DENIED / 'Policy update access denied'"
    echo "      Your account cannot modify IAM policy on '$PROJECT_ID'. Ask a"
    echo "      project Owner to run this script, or grant yourself"
    echo "      'roles/resourcemanager.projectIamAdmin' and re-run it."
    echo ""
    echo "  * 'Service account ... does not exist'"
    echo "      Propagation lag. Simply re-run the script; the account now exists."
    echo ""
    echo "  * 'violates constraint' / org policy"
    echo "      An organization policy blocks this role (basic roles such as"
    echo "      roles/viewer are restricted in some orgs). Your org admin must"
    echo "      allow it, or XamOps can run with the predefined roles only."
    echo ""
    echo "  * 'There were concurrent policy changes' / RESOURCE_EXHAUSTED"
    echo "      Transient. Re-run the script."
    echo ""
    echo "  * 'bindings with conditions' / 'specify a condition'"
    echo "      The project already has conditional IAM bindings and your gcloud"
    echo "      is too old to support '--condition=None'. Update gcloud"
    echo "      ('gcloud components update'), or bind roles manually adding"
    echo "      '--condition=None' to each command."
    echo ""
    echo "Aborting WITHOUT generating a key — connecting a key with no IAM"
    echo "roles to XamOps would result in an empty dashboard with no error."
    exit 1
fi

echo "  Binding ${#OPTIONAL_ROLES[@]} optional IAM roles (billing) to $SERVICE_ACCOUNT_EMAIL..."
for role in "${OPTIONAL_ROLES[@]}"; do
    if bind_role "$role"; then
        :
    else
        FAILED_OPTIONAL_ROLES+=("$role")
    fi
done

if [ ${#FAILED_OPTIONAL_ROLES[@]} -gt 0 ]; then
    echo ""
    echo "⚠️  WARNING: Failed to grant ${#FAILED_OPTIONAL_ROLES[@]} optional role(s) — billing data will be limited:"
    for role in "${FAILED_OPTIONAL_ROLES[@]}"; do
        echo "   - $role"
    done
    echo ""
    echo "  roles/billing.viewer requires billing account admin access."
    echo "  To enable full billing data in XamOps, a billing account admin can run:"
    echo ""
    echo "    BILLING_ACCOUNT=\$(gcloud billing projects describe $PROJECT_ID --format='value(billingAccountName)' | sed 's|billingAccounts/||')"
    echo "    gcloud billing accounts add-iam-policy-binding \"\$BILLING_ACCOUNT\" \\"
    echo "      --member=\"serviceAccount:$SERVICE_ACCOUNT_EMAIL\" \\"
    echo "      --role=\"roles/billing.viewer\""
    echo ""
    echo "  Continuing without billing.viewer — all other permissions were granted."
    echo ""
fi

GRANTED=$((${#ALL_ROLES[@]} - ${#FAILED_ROLES[@]}))
echo "✓ Permissions granted successfully ($GRANTED/${#ALL_ROLES[@]} required roles)"
echo ""

# Step 3b: Least-privilege write access when read-write mode is selected
GRANTED_SAS=()
FOREIGN_SAS=()
LEGACY_PRESENT=()
REVOKED_LEGACY=()
DISCOVERY_FAILED=0
LEGACY_CHECK_FAILED=0

if [ "$ACCESS_TYPE" = "read-write" ]; then
    echo "  [Read & Write] Granting write access for AutoSpotting, scheduling and rightsizing..."

    # 1-2. The two custom roles.
    if ensure_custom_role "$AUTOSPOTTING_ROLE_ID" "$AUTOSPOTTING_ROLE_TITLE" \
        "$AUTOSPOTTING_ROLE_DESCRIPTION" "$AUTOSPOTTING_ROLE_PERMISSIONS"; then
        echo "  ✓ Custom role $AUTOSPOTTING_ROLE_ID $CUSTOM_ROLE_RESULT$DRY_RUN_NOTE"
    else
        custom_role_failed "$AUTOSPOTTING_ROLE_ID"
    fi
    if ensure_custom_role "$COMPUTE_OPS_ROLE_ID" "$COMPUTE_OPS_ROLE_TITLE" \
        "$COMPUTE_OPS_ROLE_DESCRIPTION" "$COMPUTE_OPS_ROLE_PERMISSIONS"; then
        echo "  ✓ Custom role $COMPUTE_OPS_ROLE_ID $CUSTOM_ROLE_RESULT$DRY_RUN_NOTE"
    else
        custom_role_failed "$COMPUTE_OPS_ROLE_ID"
    fi

    # 3. Bind them. bind_role's retry covers custom-role propagation.
    for CUSTOM_ROLE in "$AUTOSPOTTING_ROLE_ID" "$COMPUTE_OPS_ROLE_ID"; do
        if ! bind_role "projects/$PROJECT_ID/roles/$CUSTOM_ROLE"; then
            echo ""
            echo "❌ ERROR: Failed to grant projects/$PROJECT_ID/roles/$CUSTOM_ROLE"
            echo "   (attempted 4 times with backoff)"
            echo ""
            echo "$IAM_BIND_ERROR" | sed 's/^/     /'
            echo ""
            echo "Aborting WITHOUT generating a key — read-write mode requires all write roles."
            echo "If it is a permission error, ask a project Owner to run this script or grant"
            echo "yourself 'roles/resourcemanager.projectIamAdmin' and re-run it."
            exit 1
        fi
    done
    echo "  ✓ Custom roles granted to $SERVICE_ACCOUNT_EMAIL"

    # 4. Service Account User on exactly the service accounts XamOps has to act as: the ones
    #    non-GKE instance groups' templates and non-GKE VMs run as, and the default compute
    #    service account. GKE node pools are excluded the same way XamOps excludes them
    #    (name prefix gke- or template label goog-gke-node).
    echo "  Finding the service accounts your instance groups and VMs run as..."

    PROJECT_NUMBER=""
    if PROJECT_NUMBER_OUTPUT=$(gcloud_read projects describe "$PROJECT_ID" --format="value(projectNumber)"); then
        PROJECT_NUMBER=$(printf '%s' "$PROJECT_NUMBER_OUTPUT" | tr -d '[:space:]')
    fi
    DEFAULT_COMPUTE_SA=""
    if [ -n "$PROJECT_NUMBER" ]; then
        DEFAULT_COMPUTE_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
    else
        DISCOVERY_FAILED=1
    fi

    # Instance templates of every non-GKE managed instance group, zonal and regional,
    # top-level and in versions[]. get() keeps the full URL: gcloud's value() shows the
    # top-level instanceTemplate as a bare name, which loses the region of a regional template.
    TEMPLATE_URLS=""
    if MIG_TEMPLATES=$(gcloud_read compute instance-groups managed list --project="$PROJECT_ID" \
        --filter="NOT name ~ ^gke-" --format="get(instanceTemplate)"); then
        TEMPLATE_URLS="$MIG_TEMPLATES"
    else
        DISCOVERY_FAILED=1
    fi
    if MIG_VERSION_TEMPLATES=$(gcloud_read compute instance-groups managed list --project="$PROJECT_ID" \
        --filter="NOT name ~ ^gke-" --flatten="versions[]" --format="value(versions.instanceTemplate)"); then
        TEMPLATE_URLS="$TEMPLATE_URLS
$MIG_VERSION_TEMPLATES"
    else
        DISCOVERY_FAILED=1
    fi

    SA_CANDIDATES=""
    while IFS= read -r TEMPLATE_URL; do
        if [ -z "$TEMPLATE_URL" ]; then
            continue
        fi
        TEMPLATE_NAME="${TEMPLATE_URL##*/}"
        TEMPLATE_SCOPE="--global"
        case "$TEMPLATE_URL" in
            */regions/*/instanceTemplates/*)
                TEMPLATE_REGION="${TEMPLATE_URL#*/regions/}"
                TEMPLATE_REGION="${TEMPLATE_REGION%%/*}"
                TEMPLATE_SCOPE="--region=$TEMPLATE_REGION"
                ;;
        esac
        if ! TEMPLATE_LABELS=$(gcloud_read compute instance-templates describe "$TEMPLATE_NAME" \
            --project="$PROJECT_ID" "$TEMPLATE_SCOPE" --format="value(properties.labels)"); then
            DISCOVERY_FAILED=1
            continue
        fi
        case "$TEMPLATE_LABELS" in
            *goog-gke-node*)
                continue
                ;;
        esac
        if TEMPLATE_SAS=$(gcloud_read compute instance-templates describe "$TEMPLATE_NAME" \
            --project="$PROJECT_ID" "$TEMPLATE_SCOPE" --format="value(properties.serviceAccounts[].email)"); then
            SA_CANDIDATES="$SA_CANDIDATES
$TEMPLATE_SAS"
        else
            DISCOVERY_FAILED=1
        fi
    done <<< "$(printf '%s\n' "$TEMPLATE_URLS" | sort -u)"

    # Service accounts of non-GKE VMs, which Cloudsitter and rightsizing start, stop and resize.
    if VM_SAS=$(gcloud_read compute instances list --project="$PROJECT_ID" \
        --filter="NOT labels.goog-gke-node:*" --flatten="serviceAccounts[]" \
        --format="value(serviceAccounts.email)"); then
        SA_CANDIDATES="$SA_CANDIDATES
$VM_SAS"
    else
        DISCOVERY_FAILED=1
    fi

    SA_CANDIDATES="$SA_CANDIDATES
$DEFAULT_COMPUTE_SA"

    # Normalise: one per line, "default" is the default compute service account, no blanks.
    SA_NORMALIZED=""
    while IFS= read -r CANDIDATE; do
        CANDIDATE=$(printf '%s' "$CANDIDATE" | tr -d '[:space:]')
        if [ "$CANDIDATE" = "default" ]; then
            CANDIDATE="$DEFAULT_COMPUTE_SA"
        fi
        if [ -n "$CANDIDATE" ]; then
            SA_NORMALIZED="$SA_NORMALIZED
$CANDIDATE"
        fi
    done <<< "$(printf '%s\n' "$SA_CANDIDATES" | tr ';' '\n')"

    # Classify: only service accounts of this project are granted here.
    LOCAL_SAS=()
    while IFS= read -r SA_EMAIL; do
        if [ -z "$SA_EMAIL" ]; then
            continue
        fi
        case "$SA_EMAIL" in
            *@"$PROJECT_ID".iam.gserviceaccount.com|"$PROJECT_ID"@appspot.gserviceaccount.com)
                LOCAL_SAS+=("$SA_EMAIL")
                ;;
            *)
                if [ -n "$DEFAULT_COMPUTE_SA" ] && [ "$SA_EMAIL" = "$DEFAULT_COMPUTE_SA" ]; then
                    LOCAL_SAS+=("$SA_EMAIL")
                else
                    FOREIGN_SAS+=("$SA_EMAIL")
                fi
                ;;
        esac
    done <<< "$(printf '%s\n' "$SA_NORMALIZED" | sort -u)"

    if [ "$DISCOVERY_FAILED" -eq 1 ]; then
        echo ""
        echo "⚠️  WARNING: Some instance groups, templates or VMs could not be listed (each"
        echo "   listing was tried twice). Service Account User is granted only on the service"
        echo "   accounts that were found, including the default compute service account."
        echo "   Re-run with --refresh once listing works:"
        echo "     bash <(curl -s $SCRIPT_URL) $PROJECT_ID read-write --refresh"
        echo ""
    fi

    FAILED_SAS=()
    FAILED_SA_ERRORS=()
    for SA_EMAIL in "${LOCAL_SAS[@]}"; do
        check_service_account "$SA_EMAIL"
        SA_CHECK_STATUS=$?
        if [ "$SA_CHECK_STATUS" -eq 1 ]; then
            echo "  ℹ️  $SA_EMAIL does not exist (a template or VM still names it); skipped"
            continue
        fi
        if [ "$SA_CHECK_STATUS" -ne 0 ]; then
            FAILED_SAS+=("$SA_EMAIL")
            FAILED_SA_ERRORS+=("Could not check that this service account exists (tried twice; only NOT_FOUND means it is gone):
$SA_CHECK_ERROR")
            continue
        fi
        if bind_sa_role "$SA_EMAIL"; then
            GRANTED_SAS+=("$SA_EMAIL")
        else
            FAILED_SAS+=("$SA_EMAIL")
            FAILED_SA_ERRORS+=("$IAM_BIND_ERROR")
        fi
    done

    if [ ${#FAILED_SAS[@]} -gt 0 ]; then
        echo ""
        echo "❌ ERROR: Failed to grant Service Account User on ${#FAILED_SAS[@]} service account(s):"
        FAILED_INDEX=0
        for SA_EMAIL in "${FAILED_SAS[@]}"; do
            echo "   - $SA_EMAIL"
            echo "${FAILED_SA_ERRORS[$FAILED_INDEX]}" | sed 's/^/     /'
            echo ""
            FAILED_INDEX=$((FAILED_INDEX + 1))
        done
        echo "Checking a service account and granting on it needs 'roles/iam.serviceAccountAdmin' or Owner."
        echo ""
        echo "Aborting WITHOUT generating a key — AutoSpotting would fail on these groups."
        exit 1
    fi
    echo "  ✓ Service Account User granted on ${#GRANTED_SAS[@]} service account(s)"

    if [ ${#FOREIGN_SAS[@]} -gt 0 ]; then
        echo ""
        echo "  ℹ️  ${#FOREIGN_SAS[@]} service account(s) belong to another project and were not granted."
        echo "     The owner of that project can run:"
        for SA_EMAIL in "${FOREIGN_SAS[@]}"; do
            echo "       gcloud iam service-accounts add-iam-policy-binding $SA_EMAIL --member=\"serviceAccount:$SERVICE_ACCOUNT_EMAIL\" --role=\"roles/iam.serviceAccountUser\""
        done
    fi

    # 5. Project-wide write roles from earlier versions of this script.
    if CURRENT_PROJECT_ROLES=$(gcloud projects get-iam-policy "$PROJECT_ID" \
        --flatten="bindings[].members" \
        --filter="bindings.members=serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --format="value(bindings.role)" 2>/dev/null < /dev/null); then
        for LEGACY_ROLE in "${LEGACY_WRITE_ROLES[@]}"; do
            if printf '%s\n' "$CURRENT_PROJECT_ROLES" | grep -qxF -- "$LEGACY_ROLE"; then
                LEGACY_PRESENT+=("$LEGACY_ROLE")
            fi
        done
    else
        LEGACY_CHECK_FAILED=1
    fi

    if [ "$REVOKE_LEGACY" -eq 1 ]; then
        if [ "$LEGACY_CHECK_FAILED" -eq 1 ]; then
            echo ""
            echo "❌ ERROR: Could not read the IAM policy of '$PROJECT_ID', so the legacy"
            echo "   project-wide write roles were not checked or removed. The new grants above"
            echo "   are in place. Re-run with --refresh --revoke-legacy-roles."
            exit 1
        fi
        # Project-wide Service Account User may be what lets XamOps act as the service accounts
        # that could not be listed, so nothing is removed until discovery has worked.
        if [ "$DISCOVERY_FAILED" -eq 1 ] && [ ${#LEGACY_PRESENT[@]} -gt 0 ]; then
            echo ""
            echo "❌ ERROR: The legacy project-wide write roles were not removed: some instance groups,"
            echo "   templates or VMs could not be listed (see the warning above). Without project-wide"
            echo "   Service Account User, AutoSpotting would fail on groups whose service accounts were"
            echo "   not found. The new grants above are in place. Re-run once listing works:"
            echo "     bash <(curl -s $SCRIPT_URL) $PROJECT_ID read-write --refresh --revoke-legacy-roles"
            exit 1
        fi
        FAILED_REVOKE=()
        for LEGACY_ROLE in "${LEGACY_PRESENT[@]}"; do
            if unbind_role "$LEGACY_ROLE"; then
                REVOKED_LEGACY+=("$LEGACY_ROLE")
            else
                FAILED_REVOKE+=("$LEGACY_ROLE")
                echo "$IAM_BIND_ERROR" | sed 's/^/     /'
            fi
        done
        if [ ${#FAILED_REVOKE[@]} -gt 0 ]; then
            echo ""
            echo "❌ ERROR: Failed to remove ${#FAILED_REVOKE[@]} legacy role(s): ${FAILED_REVOKE[*]}"
            echo "   The new grants above are in place. Re-run with --refresh --revoke-legacy-roles."
            exit 1
        fi
        if [ ${#REVOKED_LEGACY[@]} -gt 0 ]; then
            echo "  ✓ Removed ${#REVOKED_LEGACY[@]} legacy project-wide write role(s)"
        fi
    fi

    echo "✓ Write permissions granted"
    echo ""
fi

# Step 4: Create Service Account Key
KEY_PATH=""
if [ "$CREATE_KEY" -eq 0 ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "[4/4] Creating service account key... skipped (--dry-run)"
    elif [ "$REFRESH" -eq 1 ]; then
        echo "[4/4] Creating service account key... skipped (--refresh)"
    else
        echo "[4/4] Creating service account key... skipped (--no-key)"
    fi
    echo ""
else
    echo "[4/4] Creating service account key..."
    # Never overwrite a key file: a second run gets a timestamped name.
    KEY_FILE="${SERVICE_ACCOUNT_NAME}-${PROJECT_ID}-key.json"
    if [ -e "$KEY_FILE" ]; then
        KEY_FILE="${SERVICE_ACCOUNT_NAME}-${PROJECT_ID}-key-$(date +%Y%m%d%H%M%S).json"
    fi
    if [ -e "$KEY_FILE" ]; then
        echo "❌ ERROR: $KEY_FILE already exists; not overwriting it. Re-run in a moment."
        exit 1
    fi

    # The key is readable by you only from the moment it exists.
    if ! KEY_OUTPUT=$(umask 077 && run iam service-accounts keys create "$KEY_FILE" \
        --iam-account="$SERVICE_ACCOUNT_EMAIL" \
        --project="$PROJECT_ID" 2>&1); then
        echo "❌ ERROR: Failed to create service account key:"
        echo "$KEY_OUTPUT" | sed 's/^/     /'
        exit 1
    fi
    chmod 600 "$KEY_FILE"
    KEY_PATH="$(pwd)/$KEY_FILE"

    echo "✓ Service account key saved: $KEY_PATH (permissions 600, not shown here)"
    echo ""
fi

# Display Final Summary
echo "================================================"
if [ "$DRY_RUN" -eq 1 ]; then
    echo "✅ DRY RUN COMPLETE — nothing was changed"
else
    echo "✅ SETUP COMPLETE!"
fi
echo "================================================"
echo ""
echo "📋 SUMMARY:"
echo "  Service Account: $SERVICE_ACCOUNT_EMAIL"
if [ -n "$KEY_PATH" ]; then
    echo "  Key File: $KEY_PATH"
else
    echo "  Key File: none created"
fi
echo "  Access Type: $ACCESS_TYPE"
if [ "$REFRESH" -eq 0 ]; then
    echo "  APIs Enabled: $ENABLED_COUNT"
fi
echo "  IAM Roles Granted: ${#ALL_ROLES[@]}/${#ALL_ROLES[@]}"
echo ""
echo "🔐 PERMISSIONS GRANTED:"
echo "  ✓ Read-only access to ALL GCP services"
if [ ${#FAILED_OPTIONAL_ROLES[@]} -gt 0 ]; then
echo "  ⚠️  Billing & cost data: limited (not granted: ${FAILED_OPTIONAL_ROLES[*]}; see the command above)"
else
echo "  ✓ Billing & cost data (full access)"
fi
echo "  ✓ Security findings & compliance"
echo "  ✓ Optimization recommendations"
echo "  ✓ Monitoring & logging data"
echo "  ✓ Asset inventory & metadata"
if [ "$ACCESS_TYPE" = "read-write" ]; then
echo "  ✓ Custom role $AUTOSPOTTING_ROLE_ID ($(permission_count "$AUTOSPOTTING_ROLE_PERMISSIONS") permissions): Spot conversion of instance groups (AutoSpotting)"
echo "  ✓ Custom role $COMPUTE_OPS_ROLE_ID ($(permission_count "$COMPUTE_OPS_ROLE_PERMISSIONS") permissions): VM start/stop (Cloudsitter), VM and disk rightsizing"
echo "  ✓ Service Account User on ${#GRANTED_SAS[@]} service account(s), not project-wide:"
for SA_EMAIL in "${GRANTED_SAS[@]}"; do
echo "      - $SA_EMAIL"
done
if [ ${#FOREIGN_SAS[@]} -gt 0 ]; then
echo "  ⚠️  Not granted (owned by another project; see the commands above):"
for SA_EMAIL in "${FOREIGN_SAS[@]}"; do
echo "      - $SA_EMAIL"
done
fi
if [ "$LEGACY_CHECK_FAILED" -eq 1 ]; then
echo "  ⚠️  Could not check for the legacy project-wide write roles."
elif [ ${#REVOKED_LEGACY[@]} -gt 0 ]; then
echo "  ✓ Removed legacy project-wide write roles: ${REVOKED_LEGACY[*]}"
elif [ ${#LEGACY_PRESENT[@]} -gt 0 ]; then
echo "  ⚠️  Still holds legacy project-wide write roles XamOps no longer needs:"
for LEGACY_ROLE in "${LEGACY_PRESENT[@]}"; do
echo "      - $LEGACY_ROLE"
done
echo "     Remove them with:"
echo "       bash <(curl -s $SCRIPT_URL) $PROJECT_ID read-write --refresh --revoke-legacy-roles"
fi
echo "  ℹ️  Budgets need roles/billing.costsManager on the billing account, granted separately."
echo "  ℹ️  Re-run with --refresh after adding templates or VMs that run as a new service account."
fi
echo ""

echo "📦 YOUR APPLICATION CAN NOW ACCESS:"
echo "  ✓ Compute Engine (VMs, networks, disks)"
echo "  ✓ Cloud Storage (buckets, objects)"
echo "  ✓ BigQuery (datasets, tables, jobs)"
echo "  ✓ Cloud SQL (database instances)"
echo "  ✓ Kubernetes Engine (GKE clusters)"
echo "  ✓ Cloud Functions (serverless functions)"
echo "  ✓ Vertex AI (AI/ML models)"
echo "  ✓ Pub/Sub (topics, subscriptions)"
echo "  ✓ Dataplex (data lakes)"
echo "  ✓ Cloud Scheduler (cron jobs)"
echo "  ✓ VM Manager (patch management)"
echo "  ✓ Cloud Build (build history)"
echo "  ✓ Artifact Registry (container images)"
echo "  ✓ App Engine (applications)"
echo "  ✓ Cloud KMS (encryption keys)"
echo "  ✓ Secret Manager (secrets)"
echo "  ✓ Cloud DNS (DNS zones)"
echo "  ✓ Security Command Center (findings)"
echo "  ✓ IAM (users, roles, policies)"
echo "  ✓ ... and 50+ more services"
echo ""
echo "⚠️  SECURITY REMINDERS:"
if [ -n "$KEY_PATH" ]; then
echo "  • Store key securely (never in git)"
fi
echo "  • Rotate keys every 90 days"
echo "  • Monitor service account usage"
echo "  • Enable audit logging"
echo ""
if [ -n "$KEY_PATH" ]; then
echo "📝 NEXT STEPS:"
if command -v cloudshell > /dev/null 2>&1; then
echo "  1. Download the key file:"
echo "       cloudshell download \"$KEY_PATH\""
echo "  2. Open the downloaded file and paste its full contents into"
echo "     \"Service Account Key (JSON)\" in XamOps"
else
echo "  1. Open the key file in an editor:"
echo "       $KEY_PATH"
echo "  2. Paste its full contents into \"Service Account Key (JSON)\" in XamOps"
fi
echo "  3. Test connection"
echo "  4. Delete the key file:"
echo "       rm -f \"$KEY_PATH\""
echo ""
echo "  Each run without --no-key creates a new key. List existing keys with:"
echo "    gcloud iam service-accounts keys list --iam-account=$SERVICE_ACCOUNT_EMAIL"
echo ""
fi
echo "================================================"
echo "✨ Ready to connect to XamOps! ✨"
echo "================================================"
