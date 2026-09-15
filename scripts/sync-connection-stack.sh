#!/usr/bin/env bash
#
# Brings a connection stack's parameters in line with what an environment expects.
#
# New accounts already get this right — the onboarding launch URL carries the environment's
# queue. Stacks created before that carry whatever they were created with, and CloudFormation
# stores parameter values per stack: editing a template default never reaches them. The only
# way to change one is an update that passes the value, which up to now has meant someone
# reading a URL off a screen and typing it into a console form. That is how an environment
# ends up pointed at another environment's queue, which fails silently — the events go to a
# queue nobody reads and resources merely appear a minute late instead of five seconds late.
#
# Everything here is derived from application-<env>.properties, the same file the backend
# reads at runtime, so the two cannot disagree.
#
#   ./scripts/sync-connection-stack.sh local XamOps-Connection-xammer-local
#   ./scripts/sync-connection-stack.sh local XamOps-Connection-xammer-local --apply
#
set -euo pipefail

ENV="${1:-}"
STACK="${2:-}"
APPLY="${3:-}"
REGION="${STACK_REGION:-us-east-1}"

if [[ -z "$ENV" || -z "$STACK" ]]; then
  echo "usage: $0 <local|uat|preprod|prod> <stack-name> [--apply]" >&2
  echo "       STACK_REGION=<region> to override (default us-east-1)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/xamops-service/src/main/resources/application-${ENV}.properties"
[[ -f "$PROPS" ]] || { echo "No properties file for '$ENV': $PROPS" >&2; exit 1; }

prop() {
  grep -E "^[[:space:]]*$1[[:space:]]*=" "$PROPS" 2>/dev/null \
    | tail -1 | cut -d= -f2- | tr -d '[:space:]' || true
}

# Falls back to the shared default, which is what an environment without its own queue
# should legitimately use.
QUEUE_URL="$(prop 'xamops\.inventory\.events\.queue-url')"
if [[ -z "$QUEUE_URL" ]]; then
  QUEUE_URL="$(grep -E '^[[:space:]]*xamops\.inventory\.events\.queue-url[[:space:]]*=' \
               "$ROOT/xamops-service/src/main/resources/application.properties" \
               | tail -1 | cut -d= -f2- | tr -d '[:space:]' || true)"
fi
[[ -n "$QUEUE_URL" ]] || { echo "No inventory queue URL for '$ENV'." >&2; exit 1; }

QUEUE_REGION="$(sed -E 's#^https://sqs\.([a-z0-9-]+)\.amazonaws\.com/.*#\1#' <<<"$QUEUE_URL")"
[[ "$QUEUE_REGION" != "$QUEUE_URL" ]] || { echo "Cannot parse region from $QUEUE_URL" >&2; exit 1; }

# What this environment expects. Anything not listed keeps whatever the stack already has —
# ExternalId above all, because rewriting it changes the role's trust policy and silently
# disconnects the account.
declare -a WANT_KEYS=(XamOpsInventoryQueueUrl XamOpsInventoryRegion EnableInventoryEvents)
declare -a WANT_VALS=("$QUEUE_URL"            "$QUEUE_REGION"       "true")

CURRENT_JSON="$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
                --query 'Stacks[0].Parameters' --output json)"
STATUS="$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
          --query 'Stacks[0].StackStatus' --output text)"

echo "Stack   : $STACK ($REGION)"
echo "Status  : $STATUS"
echo "Env     : $ENV  [${PROPS#"$ROOT/"}]"
echo

case "$STATUS" in
  *ROLLBACK_FAILED)
    echo "Stack is $STATUS and will refuse an update." >&2
    echo "Run: aws cloudformation continue-update-rollback --stack-name $STACK --region $REGION" >&2
    exit 1 ;;
  *IN_PROGRESS)
    echo "Stack is $STATUS — wait for it to settle." >&2
    exit 1 ;;
esac

# Build the parameter list: explicit values for what this environment dictates, previous
# values for everything else the stack already declares.
PARAMS=()
CHANGES=0
printf '%-28s %s\n' "PARAMETER" "CHANGE"
printf '%-28s %s\n' "---------" "------"

while IFS=$'\t' read -r key cur; do
  want=""
  for i in "${!WANT_KEYS[@]}"; do
    [[ "${WANT_KEYS[$i]}" == "$key" ]] && want="${WANT_VALS[$i]}" && break
  done

  if [[ -n "$want" ]]; then
    if [[ "$cur" == "$want" ]]; then
      printf '%-28s already correct\n' "$key"
    else
      printf '%-28s %s\n' "$key" "$cur  ->  $want"
      CHANGES=$((CHANGES + 1))
    fi
    PARAMS+=("ParameterKey=$key,ParameterValue=$want")
  else
    PARAMS+=("ParameterKey=$key,UsePreviousValue=true")
  fi
done < <(jq -r '.[] | [.ParameterKey, .ParameterValue] | @tsv' <<<"$CURRENT_JSON")

echo
if [[ "$CHANGES" -eq 0 ]]; then
  echo "Nothing to change — this stack already matches '$ENV'."
  exit 0
fi

if [[ "$APPLY" != "--apply" ]]; then
  echo "$CHANGES parameter(s) would change. Re-run with --apply to submit."
  exit 0
fi

echo "Submitting…"
aws cloudformation update-stack \
  --stack-name "$STACK" \
  --region "$REGION" \
  --use-previous-template \
  --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
  --parameters "${PARAMS[@]}"

echo
echo "Watching (Ctrl-C is safe, the update continues)…"
aws cloudformation wait stack-update-complete --stack-name "$STACK" --region "$REGION" \
  && echo "Done." \
  || {
       echo "Update did not complete. Most recent failures:" >&2
       aws cloudformation describe-stack-events --stack-name "$STACK" --region "$REGION" \
         --query "StackEvents[?contains(ResourceStatus,'FAILED')].[LogicalResourceId,ResourceStatusReason]" \
         --output table | head -20
       exit 1
     }
