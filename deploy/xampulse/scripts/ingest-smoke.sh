#!/usr/bin/env bash
# Round-trip check for the LOCAL stack: send one OTLP/JSON log and one gauge through the gateway with dev tenant
# headers, then read them back from ClickHouse. Also proves the gateway ignores a client-forged xp.tenant_id.
# The rows use tenant 424242 and carry xp.demo so they are plainly test data; they are removed at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
: "${XP_CH_WRITER_PASSWORD:?copy .env.example to .env first}"
TENANT=424242
NOW_NS="$(python3 -c 'import time; print(int(time.time()*1e9))')"
GATEWAY="http://127.0.0.1:4318"

ch() { curl -fsS --user "xp_writer:${XP_CH_WRITER_PASSWORD}" "http://127.0.0.1:8123/?database=xampulse" --data-binary "$1"; }

ATTRS='{"key":"service.name","value":{"stringValue":"smoke"}},{"key":"xp.tenant_id","value":{"stringValue":"999"}}'
curl -fsS -X POST "$GATEWAY/v1/logs" -H 'Content-Type: application/json' \
  -H "X-Xp-Tenant: $TENANT" -H 'X-Xp-Instance: i-demo-smoke' -H 'X-Xp-Retain-Days: 3' -H 'X-Xp-Demo: true' \
  -d "{\"resourceLogs\":[{\"resource\":{\"attributes\":[$ATTRS]},\"scopeLogs\":[{\"logRecords\":[{\"timeUnixNano\":\"$NOW_NS\",\"severityText\":\"INFO\",\"severityNumber\":9,\"body\":{\"stringValue\":\"demo smoke test\"}}]}]}]}" >/dev/null
curl -fsS -X POST "$GATEWAY/v1/metrics" -H 'Content-Type: application/json' \
  -H "X-Xp-Tenant: $TENANT" -H 'X-Xp-Instance: i-demo-smoke' -H 'X-Xp-Retain-Days: 3' -H 'X-Xp-Demo: true' \
  -d "{\"resourceMetrics\":[{\"resource\":{\"attributes\":[$ATTRS]},\"scopeMetrics\":[{\"metrics\":[{\"name\":\"system.cpu.utilization\",\"gauge\":{\"dataPoints\":[{\"timeUnixNano\":\"$NOW_NS\",\"asDouble\":0.5,\"attributes\":[{\"key\":\"state\",\"value\":{\"stringValue\":\"user\"}}]}]}}]}]}]}" >/dev/null

sleep 5   # gateway batch timeout is 2s
LOGS="$(ch "SELECT count() FROM xp_logs WHERE tenant_id = $TENANT AND instance_id = 'i-demo-smoke' AND attrs['xp.demo'] = 'true'")"
METRICS="$(ch "SELECT count() FROM xp_metric_points WHERE tenant_id = $TENANT AND instance_id = 'i-demo-smoke'")"
FORGED="$(ch "SELECT count() FROM xp_logs WHERE tenant_id = 999")"
echo "logs=$LOGS metrics=$METRICS forged_tenant_rows=$FORGED"

for t in xp_logs xp_metric_points xp_metrics_1m xp_metrics_5m xp_metrics_1h xp_log_volume_1m; do
  ch "ALTER TABLE $t DELETE WHERE tenant_id = $TENANT AND instance_id = 'i-demo-smoke' SETTINGS mutations_sync = 1" >/dev/null
done

[ "$LOGS" -ge 1 ] && [ "$METRICS" -ge 1 ] && [ "$FORGED" -eq 0 ] && echo "SMOKE OK" || { echo "SMOKE FAILED"; exit 1; }
