# XamPulse local dev stack

A single-node, loopback-only stack for developing XamPulse: ClickHouse (Apache-2.0) and the OpenTelemetry Collector
gateway (Apache-2.0). Nothing here is for production: dev-only passwords, no TLS, one node.

## Bring up

```bash
cd deploy/xampulse
cp .env.example .env              # dev-only placeholder passwords; .env is git-ignored
docker compose up -d --wait       # ClickHouse on 127.0.0.1:8123/9000, OTLP gateway on 127.0.0.1:4318
./scripts/apply-schema.sh         # creates the xp_* tables, tenant row policies and the xp_reader user (idempotent)
./scripts/ingest-smoke.sh         # sends one log + one metric through the gateway and reads them back (cleans up)
docker compose down               # add -v to also delete the data volume
```

The backend applies the same DDL itself when it starts with `xampulse.enabled=true`. `apply-schema.sh` is for working
on the stack without the backend, and it is also what creates the `xp_reader` user (ClickHouse cannot attach a
SQL-defined role to an XML-defined user).

## Run the backend against it

```bash
set -a; . deploy/xampulse/.env; set +a
export XAMPULSE_ENABLED=true
export XAMPULSE_CLICKHOUSE_URL=jdbc:clickhouse://127.0.0.1:8123/xampulse
export XAMPULSE_CLICKHOUSE_READER_USER=xp_reader  XAMPULSE_CLICKHOUSE_READER_PASSWORD="$XP_CH_READER_PASSWORD"
export XAMPULSE_CLICKHOUSE_WRITER_USER=xp_writer  XAMPULSE_CLICKHOUSE_WRITER_PASSWORD="$XP_CH_WRITER_PASSWORD"
```

Leave `XAMPULSE_ENABLED` unset and the app boots exactly as before.

## How tenancy works here

Agents never choose their tenant. In production nginx authenticates the agent token and sets `X-Xp-Tenant`,
`X-Xp-Instance` and `X-Xp-Retain-Days`; the gateway first deletes any `xp.tenant_id`, `xp.instance_id`,
`xp.retain_days` and `xp.demo` the client put in the payload, then stamps them from those headers. Rows without a
tenant are dropped by the ClickHouse views. Locally you send the headers yourself (see `scripts/ingest-smoke.sh`).
The reader user is bound to `xp_reader_role`: it can only `SELECT`, and the row policies show it only rows whose
`tenant_id` equals the per-query `SQL_xp_tenant` setting (no setting means zero rows).

For the production nginx side, the internal secret lives in `/etc/nginx/xampulse-secret.conf`, a one-line file
(`proxy_set_header X-Xp-Internal "<secret>";`) that is deployed with the host and never committed.

## Demo data (OFF by default)

The seeder writes 24 h of **generated sample data** for six fake hosts (`demo-web-1..3`, `demo-api-1..2`,
`demo-db-1`) so the UI can be built and reviewed without real agents. It goes through the same OTLP gateway as real
telemetry, with episodes to look at: CPU steal on `demo-web-3`, a filling disk on `demo-db-1`, an OOM burst on
`demo-api-2`, and a new ERROR pattern that started 25 minutes ago on `demo-api-1`. It is deterministic (seed 42).

It is off unless you opt in, and it only talks to loopback:

```bash
export XAMPULSE_DEMO_ENABLED=true     # same switch name the backend guard uses
set -a; . deploy/xampulse/.env; set +a
python3 deploy/xampulse/demo/xp_demo_seed.py plan  --tenant 1      # counts only, writes nothing
python3 deploy/xampulse/demo/xp_demo_seed.py seed  --tenant 1      # --tenant is the cloud_account_id to attach it to
python3 deploy/xampulse/demo/xp_demo_seed.py purge --tenant 1      # removes exactly the demo rows of that tenant
```

How it cannot be confused with real data:

- Without `XAMPULSE_DEMO_ENABLED=true`, or with any non-loopback gateway / ClickHouse URL, it refuses and writes nothing.
- Every row is marked: instance ids start `i-demo`, hosts start `demo-`, logs carry `attrs['xp.demo']='true'`
  (stamped by the gateway from `X-Xp-Demo`, which nginx clears on the real ingest path so agents cannot set it),
  metrics carry the label `xp.demo=true`, and platform events are titled `[DEMO] ...`.
- `purge` deletes only rows with those markers, for one tenant, with a bound parameter.
- `docker-compose.yml` never seeds anything.

The backend's `XamPulseDemoGuard` fails startup if `XAMPULSE_DEMO_ENABLED=true` under `prod`, `production`, `uat`,
`preprod` or `sit`. The in-app "DEMO DATA" banner, the `/demo/seed` endpoints and the Postgres starter alert rules and
incident are not part of this stack (see `.agents/tasks/observability-1-0/slice-d-notes.md`).

## Reader profile limits (V009) and the boot check
`V009__reader_profile_constraints.sql` tightens `xp_reader_profile` (idempotent `ALTER SETTINGS PROFILE IF EXISTS`): the
reader cannot raise `max_execution_time` (MAX 30) or `max_rows_to_read` (MAX 2,000,000,000), and `max_result_rows` and
`max_memory_usage` are immutable. Honest limit: a reader session can still set `SQL_xp_tenant`, because the backend
executor sets it on every statement. Tenant isolation therefore still rests on `TenantScopedQueryBuilder` and
`ClickHouseExecutor.assertSafe` (rejects `;`, `SET`, `SETTINGS`); V009 only removes every other lever. Per-tenant
ClickHouse users are the real fix and are a go-live design item.

V009 keeps `readonly = 2`. `readonly = 1` has not been exercised with clickhouse-jdbc 0.11.0, so it is a follow-up to
try against a live stack, then apply with `ALTER SETTINGS PROFILE IF EXISTS xp_reader_profile SETTINGS readonly = 1;`
(`SQL_xp_tenant` would need `CHANGEABLE_IN_READONLY` first).

On start the backend runs `SELECT getSetting('SQL_xp_tenant') ... SETTINGS SQL_xp_tenant = '0'` on the reader pool. An
"unknown setting" answer means `custom_settings_prefixes=SQL_xp_` is missing in the server config and startup fails
with that message (otherwise every tenant read would silently return zero rows). If ClickHouse is down the check logs a
warning and retries every five minutes. The local stack ships the broader `SQL_` prefix, which also passes.

## Round trip against the live stack
`ClickHouseRoundTripIT` (tag `containers`) writes rows for reserved tenants 9000001/9000002 through the writer, reads
them back through the real executor, checks tenant isolation, runs every query builder shape the services use and
checks that the reader cannot raise limits. It skips cleanly when ClickHouse is not reachable or the passwords are
missing (it reads `XP_CH_READER_PASSWORD` / `XP_CH_WRITER_PASSWORD` from the environment or `deploy/xampulse/.env`).
```bash
cd deploy/xampulse && docker compose up -d --wait && ./scripts/apply-schema.sh && ./scripts/ingest-smoke.sh && cd ../..
mvn -B -pl xamops-service test -Dtest=ClickHouseRoundTripIT -Dgroups=containers -Dsurefire.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
```

## Ingest quota at the edge
Over `ingest_quota_gb_day` (GiB per UTC day, from the `xp_log_volume_1m` rollup, cached 60 s, fails open if ClickHouse
errors) the auth endpoint answers a valid agent with `429` + `Retry-After` when called directly. Behind nginx
`auth_request` any status other than 2xx, 401 and 403 becomes a 500, so nginx sends `X-Xp-Auth-Mode: nginx` and the
endpoint answers `403` (with `Retry-After` and `X-Xp-Quota: exceeded`); `error_page 403 = @xp_quota` turns that into the
`429` + `Retry-After: 300` the collector sees. Settings: `XAMPULSE_QUOTA_ENABLED`, `XAMPULSE_QUOTA_CACHE_SECONDS`,
`XAMPULSE_QUOTA_MAX_RETRY_AFTER_SECONDS`.

## Archive search run (OFF by default)
`POST /archive-searches/run`, `GET` and `DELETE /archive-searches/{id}` exist only when `XAMPULSE_ARCHIVE_ENABLED=true`
(404 otherwise). The Parquet reader (`ClickHouseS3ArchiveSearchExecutor`) is unit-tested at the SQL and URL level only:
it has never run against a real ClickHouse or S3, and it hands short-lived archive-role credentials to the ClickHouse
server in the request, so that server must be on a trusted network. Limits: `XAMPULSE_ARCHIVE_MAX_SEARCH_FILES` (2000),
`XAMPULSE_ARCHIVE_MAX_SEARCH_ROWS` (1000), `XAMPULSE_ARCHIVE_SEARCH_TIMEOUT_SEC` (120). Keep it off until a UAT run.

## Tests

```bash
python3 -m unittest discover -s deploy/xampulse/demo -v     # seeder logic
mvn -q -B -pl xamops-service test -Dtest=DeployFilesContractTest   # compose / gateway / users contract
docker compose -f deploy/xampulse/docker-compose.yml config -q     # needs a .env (copy .env.example)
```
