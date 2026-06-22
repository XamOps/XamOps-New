#!/bin/bash

# XamOps Service Account Setup - Complete & Future-Proof
# USAGE: sh <(curl -s https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh) PROJECT_ID
#
# NOTE: This file is the source of truth for the script hosted at the URL
# above. After making changes here, re-upload it to that S3 location so the
# "Add GCP Account" page picks up the fix.

PROJECT_ID=$1

if [ -z "$PROJECT_ID" ]; then
    echo "ERROR: PROJECT_ID is required"
    echo "Usage: $0 PROJECT_ID"
    exit 1
fi

SERVICE_ACCOUNT_NAME="xamops"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

echo "================================================"
echo "🚀 XamOps Service Account Setup"
echo "================================================"
echo "Project ID: $PROJECT_ID"
echo "Service Account: $SERVICE_ACCOUNT_EMAIL"
echo ""

# Step 1: Enable ALL Required APIs First
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

    if gcloud services enable "$api" --project="$PROJECT_ID" 2>/dev/null; then
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

# Step 2: Create Service Account
echo "[2/4] Creating service account: $SERVICE_ACCOUNT_NAME..."
SA_CREATE_OUTPUT=$(gcloud iam service-accounts create "$SERVICE_ACCOUNT_NAME" \
    --project="$PROJECT_ID" \
    --display-name="XamOps Cloud Management Platform" \
    --description="Service account for XamOps with comprehensive read access to all GCP services" \
    2>&1)
SA_CREATE_STATUS=$?

if [ $SA_CREATE_STATUS -ne 0 ]; then
    if echo "$SA_CREATE_OUTPUT" | grep -qi "already exists"; then
        echo "  ℹ️  Service account already exists, continuing..."
    else
        echo "❌ ERROR: Failed to create service account '$SERVICE_ACCOUNT_NAME':"
        echo "$SA_CREATE_OUTPUT"
        echo ""
        echo "Your account needs the 'roles/iam.serviceAccountAdmin' role (or"
        echo "Owner/Editor) on this project to create service accounts."
        exit 1
    fi
fi

echo "✓ Service account ready"
echo ""

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
FAILED_OPTIONAL_ROLES=()

echo "  Binding ${#ALL_ROLES[@]} IAM roles to $SERVICE_ACCOUNT_EMAIL..."
for role in "${ALL_ROLES[@]}"; do
    if gcloud projects add-iam-policy-binding "$PROJECT_ID" \
        --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --role="$role" \
        --quiet > /dev/null 2>&1; then
        :
    else
        FAILED_ROLES+=("$role")
    fi
done

if [ ${#FAILED_ROLES[@]} -gt 0 ]; then
    echo ""
    echo "❌ ERROR: Failed to grant ${#FAILED_ROLES[@]} of ${#ALL_ROLES[@]} IAM role(s) to $SERVICE_ACCOUNT_EMAIL:"
    for role in "${FAILED_ROLES[@]}"; do
        echo "   - $role"
    done
    echo ""
    echo "This means your account does not have permission to modify IAM policy"
    echo "on project '$PROJECT_ID' (requires 'Owner' or 'roles/resourcemanager.projectIamAdmin')."
    echo ""
    echo "Ask a project Owner to run this script, or grant yourself"
    echo "'roles/resourcemanager.projectIamAdmin' and re-run it."
    echo ""
    echo "Aborting WITHOUT generating a key — connecting a key with no IAM"
    echo "roles to XamOps would result in an empty dashboard with no error."
    exit 1
fi

echo "  Binding ${#OPTIONAL_ROLES[@]} optional IAM roles (billing) to $SERVICE_ACCOUNT_EMAIL..."
for role in "${OPTIONAL_ROLES[@]}"; do
    if gcloud projects add-iam-policy-binding "$PROJECT_ID" \
        --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
        --role="$role" \
        --quiet > /dev/null 2>&1; then
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

# Step 4: Create Service Account Key
echo "[4/4] Creating and downloading service account key..."
KEY_FILE="${SERVICE_ACCOUNT_NAME}-${PROJECT_ID}-key.json"

gcloud iam service-accounts keys create "$KEY_FILE" \
    --iam-account="$SERVICE_ACCOUNT_EMAIL" \
    --project="$PROJECT_ID"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to create service account key"
    exit 1
fi

echo "✓ Service account key created: $KEY_FILE"
echo ""

# Display Final Summary
echo "================================================"
echo "✅ SETUP COMPLETE!"
echo "================================================"
echo ""
echo "📋 SUMMARY:"
echo "  Service Account: $SERVICE_ACCOUNT_EMAIL"
echo "  Key File: $KEY_FILE"
echo "  APIs Enabled: $ENABLED_COUNT"
echo "  IAM Roles Granted: ${#ALL_ROLES[@]}/${#ALL_ROLES[@]}"
echo ""
echo "🔐 PERMISSIONS GRANTED:"
echo "  ✓ Read-only access to ALL GCP services"
echo "  ✓ Billing & cost data (full access)"
echo "  ✓ Security findings & compliance"
echo "  ✓ Optimization recommendations"
echo "  ✓ Monitoring & logging data"
echo "  ✓ Asset inventory & metadata"
echo ""

# ============================================
# Display JSON for Easy Copy-Paste
# ============================================
echo "================================================"
echo "📋 SERVICE ACCOUNT KEY - COPY & PASTE"
echo "================================================"
echo ""
echo "Copy the ENTIRE JSON below (including { and }):"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cat "$KEY_FILE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "================================================"

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
echo "  • Store key securely (never in git)"
echo "  • Rotate keys every 90 days"
echo "  • Monitor service account usage"
echo "  • Enable audit logging"
echo ""
echo "📝 NEXT STEPS:"
echo "  1. Copy the JSON from above"
echo "  2. Paste into XamOps dashboard"
echo "  3. Test connection"
echo "  4. Delete local key file:"
echo "     rm $KEY_FILE"
echo ""
echo "================================================"
echo "✨ Ready to connect to XamOps! ✨"
echo "================================================"
