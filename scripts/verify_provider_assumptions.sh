#!/usr/bin/env bash
#
# Verifies the five provider string/ID assumptions in the recommendation engine against
# live cloud APIs. READ ONLY — issues only list/describe calls, mutates nothing.
#
# Prerequisites:
#   gcloud auth login && gcloud config set project <YOUR_PROJECT>
#   az login
#
# Usage:
#   ./scripts/verify_provider_assumptions.sh [GCP_PROJECT_ID] [AZURE_SUBSCRIPTION_ID]

set -uo pipefail
export PATH="/opt/homebrew/bin:$PATH"

GCP_PROJECT="${1:-$(gcloud config get-value project 2>/dev/null)}"
AZ_SUB="${2:-}"

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
info() { printf '  ....  %s\n' "$1"; }
hdr()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ─────────────────────────────────────────────────────────────────────────────
hdr "1. GCP memory metric type (GcpMetricsService.MEMORY_METRIC_TYPES)"
# Asserted: the Ops Agent publishes agent.googleapis.com/memory/percent_used, and the
# previously hardcoded compute.googleapis.com/instance/memory/percent_used is not a real
# GCE metric. If the second one exists and the first does not, the original code was right.
if [[ -z "$GCP_PROJECT" ]]; then
  fail "no GCP project set — run: gcloud config set project <PROJECT_ID>"
else
  info "project: $GCP_PROJECT"
  TOKEN=$(gcloud auth print-access-token 2>/dev/null)
  if [[ -z "$TOKEN" ]]; then
    fail "no gcloud access token — run: gcloud auth login"
  else
    for MT in "agent.googleapis.com/memory/percent_used" \
              "compute.googleapis.com/instance/memory/percent_used"; do
      COUNT=$(curl -s --max-time 25 -H "Authorization: Bearer $TOKEN" \
        "https://monitoring.googleapis.com/v3/projects/${GCP_PROJECT}/metricDescriptors?filter=metric.type%3D%22${MT//\//%2F}%22" \
        | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('metricDescriptors',[])))" 2>/dev/null || echo "ERR")
      if [[ "$COUNT" == "ERR" ]]; then
        fail "$MT -> query failed (Monitoring API enabled? monitoring.viewer granted?)"
      elif [[ "$COUNT" == "0" ]]; then
        info "$MT -> descriptor NOT present"
      else
        pass "$MT -> descriptor present"
      fi
    done
    info "interpretation: agent.* present => the fix is correct."
    info "                only compute.* present => revert MEMORY_METRIC_TYPES order."
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
hdr "2. GCP Cloud SQL billing service id + SKU description match"
# Asserted: CLOUD_SQL_SERVICE = "services/9662-B51E-5089" and descriptions of the form
# "Custom Instance Core running in <City>".
if [[ -n "${TOKEN:-}" ]]; then
  RESP=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN" \
    "https://cloudbilling.googleapis.com/v1/services/9662-B51E-5089/skus?pageSize=200" 2>/dev/null)
  echo "$RESP" | python3 - <<'PY'
import json, sys
raw = sys.stdin.read()
try:
    data = json.loads(raw)
except Exception:
    print("  FAIL  could not parse Cloud Billing response"); sys.exit()
if "error" in data:
    print(f"  FAIL  service id rejected: {data['error'].get('message','')[:110]}")
    sys.exit()
skus = data.get("skus", [])
print(f"  ....  SKUs returned on first page: {len(skus)}")
core = [s for s in skus if "Custom Instance Core running in" in s.get("description", "")]
ram  = [s for s in skus if "Custom Instance RAM running in"  in s.get("description", "")]
if core or ram:
    print(f"  PASS  description pattern matches ({len(core)} Core, {len(ram)} RAM)")
    for s in (core[:2] + ram[:2]):
        print(f"        {s.get('description')}")
else:
    print("  FAIL  no SKU matched 'Custom Instance Core/RAM running in <City>'")
    print("        sample descriptions actually returned:")
    for s in skus[:6]:
        print(f"        - {s.get('description')}")
PY
else
  fail "skipped — no gcloud token"
fi

# ─────────────────────────────────────────────────────────────────────────────
hdr "3. GCP recommender IDs (resolveRecommendationType substrings)"
# Asserted: recommendation names contain 'machineTypeRecommender', 'idleResourceRecommender', etc.
if [[ -n "${GCP_PROJECT:-}" && -n "${TOKEN:-}" ]]; then
  for RID in google.compute.instance.MachineTypeRecommender \
             google.compute.instance.IdleResourceRecommender \
             google.cloudsql.instance.OverprovisionedRecommender; do
    CODE=$(curl -s --max-time 25 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
      "https://recommender.googleapis.com/v1/projects/${GCP_PROJECT}/locations/global/recommenders/${RID}/recommendations?pageSize=1")
    case "$CODE" in
      200) pass "$RID -> reachable (200)";;
      403) fail "$RID -> 403; enable Recommender API / grant recommender.viewer";;
      404) info "$RID -> 404 (not valid at 'global', may be regional)";;
      *)   info "$RID -> HTTP $CODE";;
    esac
  done
else
  fail "skipped — no project or token"
fi

# ─────────────────────────────────────────────────────────────────────────────
hdr "4. Azure serverless SQL detection (currentServiceObjectiveName)"
# Asserted: serverless shows as 'GP_S_Gen5_x'; Hyperscale as 'HS_...'.
if ! az account show >/dev/null 2>&1; then
  fail "not logged in to Azure — run: az login"
else
  [[ -n "$AZ_SUB" ]] && az account set --subscription "$AZ_SUB" >/dev/null 2>&1
  info "subscription: $(az account show --query name -o tsv 2>/dev/null)"
  SERVERS=$(az sql server list --query "[].{n:name,g:resourceGroup}" -o tsv 2>/dev/null)
  if [[ -z "$SERVERS" ]]; then
    info "no Azure SQL servers in this subscription — assumption untestable here"
  else
    while IFS=$'\t' read -r NAME RG; do
      [[ -z "$NAME" ]] && continue
      az sql db list --server "$NAME" --resource-group "$RG" \
        --query "[].{db:name, objective:currentServiceObjectiveName, edition:edition, pool:elasticPoolName}" \
        -o tsv 2>/dev/null | while IFS=$'\t' read -r DB OBJ ED POOL; do
          printf '        db=%-22s objective=%-18s edition=%-14s pool=%s\n' "$DB" "$OBJ" "$ED" "${POOL:-—}"
        done
    done <<< "$SERVERS"
    info "check: serverless DBs should show objective starting 'GP_S_'"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
hdr "5. AWS Cost Explorer service names (RESERVABLE_SERVICES)"
info "not checked here — needs AWS creds, and Cost Explorer bills \$0.01/call."
info "verify from the running app instead:"
info "  grep 'Found .* reservation recommendations for' application.log"
info "  grep 'No reservation recommendations for'        application.log"

hdr "Done — all calls above were read-only."
