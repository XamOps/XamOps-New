#!/usr/bin/env bash
#
# Deploys the inventory event queue for one environment.
#
# The queue name, region and stack name are all derived from the environment's own
# application-<env>.properties, because that file is what the backend reads at runtime.
# Typing them by hand is how an environment ends up deployed against a queue it does not
# listen on: the stack succeeds, events flow to the wrong queue, and the only symptom is
# that resources take 60s to appear instead of 5s. Deriving them means the two cannot
# disagree without someone editing the properties file, which is the thing that should
# decide it.
#
#   ./scripts/deploy-inventory-engine.sh local
#   ./scripts/deploy-inventory-engine.sh uat
#   ./scripts/deploy-inventory-engine.sh prod --dry-run
#
set -euo pipefail

ENV="${1:-}"
DRY_RUN="${2:-}"

if [[ -z "$ENV" ]]; then
  echo "usage: $0 <local|uat|preprod|prod> [--dry-run]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/xamops-service/src/main/resources/application-${ENV}.properties"
TEMPLATE="$ROOT/cloudformation/xamops-inventory-engine.yaml"

[[ -f "$PROPS" ]]    || { echo "No properties file for '$ENV': $PROPS" >&2; exit 1; }
[[ -f "$TEMPLATE" ]] || { echo "Template missing: $TEMPLATE" >&2; exit 1; }

# The last uncommented definition wins, matching how Spring resolves a repeated key.
# '|| true' because an environment that declares no queue is a valid state, not a
# failure — but under 'set -e' with pipefail an empty grep would kill the script here,
# before the explanation below ever prints.
QUEUE_URL="$(grep -E '^[[:space:]]*xamops\.inventory\.events\.queue-url[[:space:]]*=' "$PROPS" \
             | tail -1 | cut -d= -f2- | tr -d '[:space:]' || true)"

if [[ -z "$QUEUE_URL" ]]; then
  echo "'$ENV' declares no xamops.inventory.events.queue-url." >&2
  echo "It inherits the shared queue from application.properties, which is deployed by" >&2
  echo "the 'prod' environment. Nothing to deploy here." >&2
  exit 1
fi

# https://sqs.<region>.amazonaws.com/<account>/<name>.fifo
REGION="$(sed -E 's#^https://sqs\.([a-z0-9-]+)\.amazonaws\.com/.*#\1#' <<<"$QUEUE_URL")"
QUEUE_NAME="${QUEUE_URL##*/}"
BASE_NAME="${QUEUE_NAME%.fifo}"

if [[ "$REGION" == "$QUEUE_URL" || -z "$BASE_NAME" ]]; then
  echo "Could not parse region and queue name from: $QUEUE_URL" >&2
  exit 1
fi

# Tag value only; 'development' is the template's AllowedValue for anything pre-production.
case "$ENV" in
  prod) CFN_ENV="production" ;;
  uat|preprod) CFN_ENV="staging" ;;
  *) CFN_ENV="development" ;;
esac

STACK="xamops-inventory-engine"
[[ "$ENV" == "prod" ]] || STACK="xamops-inventory-engine-${ENV}"

cat <<EOF
Environment   : $ENV
Properties    : ${PROPS#"$ROOT/"}
Queue URL     : $QUEUE_URL
  -> region   : $REGION
  -> base name: $BASE_NAME
Stack         : $STACK
Tag           : $CFN_ENV
EOF

if [[ "$DRY_RUN" == "--dry-run" ]]; then
  echo
  echo "Dry run; nothing deployed."
  exit 0
fi

echo
aws cloudformation deploy \
  --template-file "$TEMPLATE" \
  --stack-name "$STACK" \
  --parameter-overrides "QueueBaseName=$BASE_NAME" "Environment=$CFN_ENV" \
  --capabilities CAPABILITY_IAM \
  --region "$REGION"

echo
echo "Queue is live:"
aws sqs get-queue-url --queue-name "$BASE_NAME.fifo" --region "$REGION" --output text
