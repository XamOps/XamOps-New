#!/bin/bash
# =============================================================================
# AutoSpotting Customer Multi-Region Deployment Script
# Deploys autospotting-customer.yaml to ALL available regions in a customer
# account. IAM roles are created only once (first region); all others skip them.
#
# Architecture: EventBridge cross-account forwarding (no Lambda required).
# Events from every region flow directly to the XamOps host EventBridge bus
# in XammerRegion (ap-south-1), then route to SQS for backend processing.
# =============================================================================
set -e

# --- Configuration -----------------------------------------------------------
STACK_NAME="autospotting-customer"
TEMPLATE_FILE="$(dirname "$0")/autospotting-customer.yaml"
XAMMER_ACCOUNT_ID="${XAMMER_ACCOUNT_ID:-605134457560}"
XAMMER_REGION="${XAMMER_REGION:-ap-south-1}"
ENABLE_AUTOSPOTTING="${ENABLE_AUTOSPOTTING:-true}"
AWS_PROFILE="${AWS_PROFILE:-default}"
# Optional: space-separated list of regions to skip (e.g. "cn-north-1 us-gov-west-1")
SKIP_REGIONS="${SKIP_REGIONS:-}"
# Optional: deploy only these regions instead of all (e.g. "ap-south-1 us-east-1")
ONLY_REGIONS="${ONLY_REGIONS:-}"
# -----------------------------------------------------------------------------

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# --- Discover all opted-in regions -------------------------------------------
get_regions() {
  if [ -n "$ONLY_REGIONS" ]; then
    echo "$ONLY_REGIONS"
    return
  fi

  aws ec2 describe-regions \
    --filters "Name=opt-in-status,Values=opt-in-not-required,opted-in" \
    --query "Regions[].RegionName" \
    --output text \
    --profile "$AWS_PROFILE" 2>/dev/null | tr '\t' '\n' | sort
}

# --- Deploy stack in one region ----------------------------------------------
deploy_region() {
  local region="$1"
  local create_iam="$2"

  log "Deploying to $region (CreateIAMRole=$create_iam) ..."

  aws cloudformation deploy \
    --stack-name "$STACK_NAME" \
    --template-file "$TEMPLATE_FILE" \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$region" \
    --profile "$AWS_PROFILE" \
    --parameter-overrides \
        XammerAccountId="$XAMMER_ACCOUNT_ID" \
        XammerRegion="$XAMMER_REGION" \
        EnableAutoSpotting="$ENABLE_AUTOSPOTTING" \
        CreateIAMRole="$create_iam" \
    --no-fail-on-empty-changeset 2>&1

  if [ $? -eq 0 ]; then
    ok "$region ✓"
    return 0
  else
    error "$region ✗"
    return 1
  fi
}

# --- Main --------------------------------------------------------------------
echo ""
echo "========================================================"
echo "  AutoSpotting Customer Multi-Region Deployment"
echo "  Architecture  : EventBridge cross-account (no Lambda)"
echo "  Xammer Account: $XAMMER_ACCOUNT_ID"
echo "  Xammer Region : $XAMMER_REGION (central EventBridge bus)"
echo "  AWS Profile   : $AWS_PROFILE"
echo "========================================================"
echo ""

if [ ! -f "$TEMPLATE_FILE" ]; then
  error "Template not found: $TEMPLATE_FILE"
  exit 1
fi

ALL_REGIONS=$(get_regions)
if [ -z "$ALL_REGIONS" ]; then
  error "Could not retrieve regions. Check AWS credentials / profile."
  exit 1
fi

REGIONS=()
for r in $ALL_REGIONS; do
  if echo "$SKIP_REGIONS" | grep -qw "$r"; then
    warn "Skipping $r (in SKIP_REGIONS)"
    continue
  fi
  REGIONS+=("$r")
done

log "Regions to deploy: ${#REGIONS[@]}"
echo "  ${REGIONS[*]}" | tr ' ' '\n' | sed 's/^/    /'
echo ""

SUCCEEDED=()
FAILED=()
FIRST_REGION=true

for region in "${REGIONS[@]}"; do
  if [ "$FIRST_REGION" = true ]; then
    # First region: create IAM roles (global, only needed once)
    if deploy_region "$region" "true"; then
      SUCCEEDED+=("$region")
    else
      FAILED+=("$region")
    fi
    FIRST_REGION=false
  else
    # Subsequent regions: IAM roles already exist, skip creation
    if deploy_region "$region" "false"; then
      SUCCEEDED+=("$region")
    else
      FAILED+=("$region")
    fi
  fi
done

# --- Summary -----------------------------------------------------------------
echo ""
echo "========================================================"
echo "  Deployment Summary"
echo "========================================================"
ok  "Succeeded (${#SUCCEEDED[@]}): ${SUCCEEDED[*]}"
if [ ${#FAILED[@]} -gt 0 ]; then
  error "Failed    (${#FAILED[@]}): ${FAILED[*]}"
  echo ""
  warn "Failed regions can be retried individually:"
  for r in "${FAILED[@]}"; do
    echo "  AWS_PROFILE=$AWS_PROFILE ONLY_REGIONS=$r $(basename "$0")"
  done
  exit 1
fi

echo ""
ok "All regions deployed successfully."
echo ""
echo "Next steps:"
echo "  1. Ensure the host stack is deployed (autospotting-host.yaml in ap-south-1)"
echo "  2. Events now flow: Customer EventBridge -> XamOps bus -> SQS -> Backend"
echo "  3. Go to XamOps dashboard and enable AutoSpotting on an ASG"
echo "  4. Check EventBridge custom bus in host account: autospotting-events"
