# Runbook: legacy large-object (LOB) text cutover

Moves production from the August images to `main` without losing the four shared
`TEXT` columns that August stored as PostgreSQL large-object (LO) references, then
converts those columns to plain text.

**In-scope columns** (design §3.3):

| Table | Column |
|---|---|
| `cached_data` | `json_data` |
| `cloudsitter_policies` | `schedule_json` |
| `github_integration_config` | `encrypted_pat` |
| `sonarqube_project` | `encrypted_token` |

**Three phases**

| Phase | What it is | Section |
|---|---|---|
| **R** | Rehearse the whole thing on an isolated clone of production | [§R](#phase-r-rehearse-on-an-isolated-prod-clone) |
| **D** | Deploy `main` in one window, then soak in `legacy` write mode | [§D](#phase-d-deploy-main-in-one-window-then-soak) |
| **C** | Format cutover: switch to `inline`, run Script N | [§C](#phase-c-format-cutover) |

**FR-5.3 order.** The seven required steps map onto the phases as follows. Do not
reorder them.

| FR-5.3 item | What | Where |
|---|---|---|
| 1 | Census / read-only pre-checks | R step 1, D1, [C1](#c1-read-only-pre-checks) |
| 2 | Deploy `main` everywhere, in `legacy` mode | [D5](#d5-start-main) |
| 3 | August retired; no August image can still write | [D3](#d3-window-start), [C2](#c2-august-retired) |
| 4 | Switch the write mode to `inline` | [C3](#c3-switch-to-inline) |
| 5 | GCP marker delete and one-boot recompute | [D6](#d6-gcp-recompute), verified at [C5](#c5-gcp-verification) |
| 6 | Run Script N (the conversion) | [C4](#c4-script-n) |
| 7 | Post-checks, derived Redis flush, cleanup | [C4](#c4-script-n), [C6](#c6-derived-redis-keys), [C7](#c7-later) |

---

## §8.0 Safety block — read before running anything

1. **Every step names its host and its database.** DB steps run **per database**.
   Production has one, `postgres`.
2. **Secrets by key name only.** Never `echo`, `cat` or paste a secret value, and
   never put one in a ticket or a chat. Compare two values with `cmp -s` and print
   only `same` / `differs`.
3. **Never run `vacuumlo`** while any in-scope text column or any backup table still
   holds LO references. Nothing in this runbook calls `lo_unlink`. `vacuumlo` treats
   an OID it cannot find in a scanned column as an orphan and deletes the object; the
   backup tables and the un-converted columns are exactly the places it does not scan.
4. **`SPRING_JPA_HIBERNATE_DDLAUTO=none` on every production boot** of billops and
   xamops-service, until a follow-up sets both prod profiles to `none` (requirements
   R1). Both prod profiles still say `update`, and the app connects as a superuser.
   This is an environment override only — **no profile file is edited** (NFR-2, AC-15).
   Never apply an unreviewed `ALTER … TYPE`, and never use `validate` during the window.
5. **Images are built from a clean checkout** of the merged commit and tagged with its
   SHA. Never `:latest`. Push to the private registry only.
6. **The rehearsal clone holds production data.** Keep it isolated and
   access-restricted, and destroy it when phase R ends.
7. **psql 10 or newer** is required for `census-lob-text.sql` (it uses `\if` and
   `:'variable'` quoting). The three writing scripts run on any psql. Check with
   `psql --version`.

### Flags

Six settings govern this cutover. All six are set by **environment variable**; no
committed profile file sets any of them (`ProfileFlagsTest`, AC-15).

| Property | Env var | Service | Default | Effect | Prod setting |
|---|---|---|---|---|---|
| `xammer.lob-text.write-mode` | `XAMMER_LOBTEXT_WRITEMODE` | billops, xamops-service | `legacy` | `legacy`: every changed write creates a new large object and stores its OID as text, which August can read. `inline`: plain text, and an OID-shaped value is refused | `legacy`, set explicitly, from D5 until C3; `inline` from C3 |
| `xammer.lob-text.convert-on-read.enabled` | `XAMMER_LOBTEXT_CONVERTONREAD_ENABLED` | xamops-service | `false` | Inlines an LO-reference row when it is read through `resolvePayload`, preserving `last_updated`. Ignored in `legacy` mode | `false` — Script N (C4) does the conversion instead |
| `gcp.billing.net-cost-cache-migration.enabled` | `GCP_BILLING_NETCOSTCACHEMIGRATION_ENABLED` | xamops-service | `false` (was `true`) | One-time GCP cache flush: Redis eviction, `cached_data` delete, then writes the marker `GCP_NET_COST_MIGRATION::v1-credits-netted` | `true` for the single D6 boot only |
| `gcp.billing.history-snapshot-on-startup.enabled` | `GCP_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED` | xamops-service | `false` (was ungated) | Startup refill of GCP cost history from BigQuery | `true` for the single D6 boot; `true` in a brand-new environment |
| `aws.billing.history-snapshot-on-startup.enabled` | `AWS_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED` | xamops-service | `false` (was ungated) | Startup refill of AWS cost history | `false`. Trigger it by hand instead (`BillOpsAdminController:1216`) |
| `spring.jpa.hibernate.ddl-auto` | `SPRING_JPA_HIBERNATE_DDLAUTO` | billops, xamops-service | `update` in both prod profiles | Hibernate schema update, executed as a superuser | `none` on every boot |

Startup confirmation: billops and xamops each log `LOB-text write mode: <mode>
(configured|default)`, and xamops additionally logs `LOB-text startup flags: …` with
the four flag states (`LobTextStartupReporter`).

---

## Phase R: rehearse on an isolated prod clone

Phase R is not optional. It is where the DDL dry run, the lock measurement and both
rollback drills happen.

### R-setup: the clone

**Host:** a new EC2 instance plus a new DB instance/volume, in a subnet of their own.

```bash
# On your workstation / the ops host.
# 1. Take the snapshot the D2 way (see D2) from prod, and restore it onto the clone DB.
#    It MUST carry the large objects. Verify a pg_dump-based copy:
pg_restore -l /path/to/prod.dump | grep -c BLOB      # must be > 0
```

Required properties of the clone, all of which must be true before the first boot:

* **Its own Redis.** Never point the clone at production Redis.
* **Security group: egress to the clone DB and that Redis only.** No cloud-provider
  APIs, no SMTP, no SMS/WhatsApp, no webhooks.
* **No instance role.** AWS access in this application is role-based, so with no role
  attached the AWS SDK cannot reach any account.
* **Credentials scrubbed** — see R step 1 below.

> **Why these three guards matter.** The Cloudsitter enforcers are **not** gated by
> `scheduling.enabled`: `CloudsitterService:1042`, `AzureCloudsitterService:201` and
> `GcpCloudsitterService:218` run on their own schedules. With working credentials
> and egress, a clone holding production policies would start and stop **real
> customer VMs**. Removing egress, the instance role and the stored provider
> credentials closes all three paths.

### R-config

With no instance role the `optional:` AWS Secrets Manager property imports resolve to
nothing, so every key the prod profiles read from a secret must be supplied as an
environment variable of the same name.

```bash
# On the clone app host. Values come from your own throwaway material, not from prod.
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL='jdbc:postgresql://<clone-db>:5432/postgres'
export SPRING_DATASOURCE_USERNAME='<clone-db-user>'
export SPRING_DATASOURCE_PASSWORD='<clone-db-password>'   # set, never echoed
export SPRING_JPA_HIBERNATE_DDLAUTO=none
# A throwaway 32-byte key, generated here. Do NOT copy the prod key unless the team
# approves it in writing. With a throwaway key, stored ciphertext decrypts to null,
# which is the intended, safe outcome for a rehearsal.
export APP_ENCRYPTION_SECRET_KEY="$(openssl rand -base64 32)"
```

`SPRING_DATASOURCE_*` outranks the `${host}`-style values in the profile, so the clone
cannot drift back onto a production host.

### R steps — run in order, record every result

#### R1. Baselines, then scrub

```bash
# On the clone DB host. Database: postgres.
export PAGER=cat PSQLRC=/dev/null
psql -X -v ON_ERROR_STOP=1 -d postgres -c 'SELECT version();'
psql -X -v ON_ERROR_STOP=1 -d postgres -f scripts/db/lob-text/census-lob-text.sql  > R1-census.txt

# Report §6 queries: what a rollback would no longer be able to read.
psql -X -d postgres -c "SELECT id, account_name, provider FROM cloud_account
  WHERE gcp_service_account_key LIKE 'enc:v1:%' OR azure_client_secret LIKE 'enc:v1:%';"
psql -X -d postgres -c "SELECT count(*) FROM ticket_reply WHERE user_id IS NULL;"

# Flyway: nothing may be pending, and no rank may be >= 75.
psql -X -d postgres -c "SELECT installed_rank, version, description, success
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

**Then scrub the provider credentials.** August stored these two in plaintext.

```sql
-- On the clone DB host ONLY. Never on production.
BEGIN;
UPDATE cloud_account SET gcp_service_account_key = NULL WHERE gcp_service_account_key IS NOT NULL;
UPDATE cloud_account SET azure_client_secret     = NULL WHERE azure_client_secret     IS NOT NULL;
COMMIT;
SELECT count(*) FROM cloud_account
 WHERE gcp_service_account_key IS NOT NULL OR azure_client_secret IS NOT NULL;  -- expect 0
```

#### R2. DDL dry run

Both prod profiles still carry `ddl-auto=update`. This step shows exactly what
Hibernate 7.2 **would** do, without letting it do anything.

```bash
# On the clone app host, once per service. <svc> is billops or xamops.
docker run --rm \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_JPA_HIBERNATE_DDLAUTO=none \
  -e SPRING_DATASOURCE_URL -e SPRING_DATASOURCE_USERNAME -e SPRING_DATASOURCE_PASSWORD \
  -e APP_ENCRYPTION_SECRET_KEY \
  -e SPRING_APPLICATION_JSON='{"spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action":"update","spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target":"/tmp/<svc>-update.sql"}' \
  -v /tmp/ddl:/tmp <registry>/<svc>:<sha>
cat /tmp/ddl/<svc>-update.sql
```

`SPRING_APPLICATION_JSON` is used because an environment-variable map key loses its
dashes, and these property names contain them.

Classify **every** generated statement:

| Class | Action |
|---|---|
| Additive and wanted (a new column, a new index) | Apply by hand at [D5 step 1](#d5-start-main) |
| Unwanted | Drop it; it is not applied |
| **A type change** (`ALTER … TYPE`) | **Never apply unreviewed.** It goes to the R1 follow-up |

Expect the four in-scope columns to be absent from the script: Hibernate compares the
normalised `columnDefinition` (`text`) with the database type name, so a `TEXT` column
mapped as `TEXT` produces no statement (AC-35, `SchemaUpdateNoAlterPgTest`).

#### R3. Phase D on the clone

Run [D4](#d4-redis) then [D5](#d5-start-main) against the clone, with the clone's own
Redis, and then the D5 pre-traffic checks. Also confirm:

* xamops Flyway has nothing pending and every checksum validates (`FlywayConfig`
  migrates with validation at startup; a checksum mismatch aborts the boot);
* media-ops-service applies `V13__add_source_key.sql`;
* **the forced re-login works**: a browser holding an August session lands on the
  login page rather than on an error.

Record each service's startup time.

> Without egress, the GCP refill in D6 cannot reach BigQuery, so it will fail here.
> That is expected. The rehearsal covers the **marker delete** and the **flush**; the
> real refill is verified in production at [D6](#d6-gcp-recompute).

#### R4. Rollback drill A (soak-time rollback)

Stop the `main` containers and start the **current August tags**, with the run
configuration recorded in D1. Then check:

* the billops admin dashboard renders, with every account;
* a Cloudsitter policy page loads and shows a decoded schedule.

This proves that `legacy`-mode writes from `main` are readable by August — the
property that makes an image swap a complete rollback during the soak.

#### R5. Restart `main`, then C3–C4

```bash
# Clone DB. Watermark first.
psql -X -d postgres -c 'SELECT max(oid) FROM pg_largeobject_metadata;'   # = W
# Switch every clone instance to inline, restart, confirm the log line, wait 30+ min.
psql -X -v ON_ERROR_STOP=1 -v since_oid=<W> -d postgres -f scripts/db/lob-text/census-lob-text.sql
# Then Script N, with the apps running, and with lock waits visible.
psql -X -d postgres -c "ALTER SYSTEM SET log_lock_waits = on;" -c "SELECT pg_reload_conf();"
time psql -X -v ON_ERROR_STOP=1 -d postgres \
  -c "SET lobtext.confirm = 'august-retired-and-inline-mode'" \
  -f scripts/db/lob-text/normalize-lob-text-columns.sql
```

Record: the duration, the per-column `converted` / `skipped_*` counts, and the lock
waits. Sample waits with:

```sql
SELECT now(), pid, wait_event_type, wait_event, left(query, 80)
  FROM pg_stat_activity WHERE wait_event_type = 'Lock';
```

#### R6. Rollback drill B (post-C3 rollback)

```bash
psql -X -v ON_ERROR_STOP=1 -d postgres \
  -c "SET lobtext.confirm = 'rollback-lob-text'" \
  -f scripts/db/lob-text/rollback-lob-text.sql
```

Then boot the FR-7 dual-format images, **if they exist** ([§8.4](#fr-7-august-line-hotfix-recommendation)),
against this post-C3 clone. If they do not exist, record that the only post-C3
rollback is a snapshot restore or a fix-forward, and carry that into the C3 go/no-go.

#### R7. Restore drill

Time a full restore of the D2 snapshot type onto a fresh volume. That time is the
recovery-time figure in the rollback matrix.

#### R8. Destroy the clone

Terminate the instance, delete the DB and its volumes, delete the clone Redis, and
delete any snapshot copy made for the clone. Record that it is gone.

### Lock policy

`normalize-lob-text-columns.sql` sets `SET LOCAL lock_timeout = '5s'` itself (§3.7).
If it waits longer than that behind an application transaction it **aborts and rolls
back the whole run** rather than queueing — and a re-run is safe, because it is
idempotent. The reverse also holds: an application write to a row Script N has already
touched waits until Script N commits.

If the duration measured in R5 is longer than the team will accept for those waits,
run [C4](#c4-script-n) with billops and xamops-service **stopped**.

---

## Phase D: deploy `main` in one window, then soak

### D0 Preconditions

* Phase R completed, with R2 classified, R4 and R6 passed, and R5's duration accepted.
* The D2 rollback assets exist and have been verified.
* The deploy window covers **all four** services: billops, xamops-service,
  media-ops-service and frontend-app.

### D1 Read-only checks, the day before

#### Inventory

```bash
# On every prod app host.
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
# Capture the full run configuration of each container; main reuses it verbatim.
for c in $(docker ps --format '{{.Names}}'); do docker inspect "$c" > "inspect-$c.json"; done
# If the EKS prod manifests are live:
kubectl get deploy -A -o wide
```

```sql
-- On the prod DB host. Database: postgres.
SELECT client_addr, usename, application_name, count(*)
  FROM pg_stat_activity WHERE datname = 'postgres' GROUP BY 1,2,3 ORDER BY 1;
```

**Every `client_addr` must map to a container you just listed.** An address you cannot
account for is a stop-and-investigate finding: it means something else writes to this
database, and it will keep writing the August format after the window.

#### Secrets — key names only

```bash
# On the prod app host, which holds the app role.
for s in prod/xamops_db prod/xamops_shared prod/xamops_app; do
  echo "== $s"
  aws secretsmanager get-secret-value --secret-id "$s" --query SecretString --output text | jq -r 'keys[]'
done
# From prod/xamops_db print the host and database name only (open question §9.2):
aws secretsmanager get-secret-value --secret-id prod/xamops_db --query SecretString --output text | jq -r '.host, .dbname'
```

Confirm that host **is** the current production database.

#### Keys — compared without printing

```bash
getkey() { aws secretsmanager get-secret-value --secret-id "$1" --query SecretString \
             --output text | jq -r ".$2"; }

# Presence. app_encryption_secret_key must exist in BOTH:
#   prod/xamops_shared  (billops reads it, application-prod.properties:45)
#   prod/xamops_app     (xamops reads it, application-prod.properties:158)
getkey prod/xamops_shared app_encryption_secret_key >/dev/null && echo "shared: present"
getkey prod/xamops_app    app_encryption_secret_key >/dev/null && echo "app: present"

# 32 bytes after base64 decoding, in each:
getkey prod/xamops_shared app_encryption_secret_key | base64 -d | wc -c   # expect 32
getkey prod/xamops_app    app_encryption_secret_key | base64 -d | wc -c   # expect 32

# Equal to each other, and equal to the August xamops literal:
cmp -s <(getkey prod/xamops_shared app_encryption_secret_key) \
       <(getkey prod/xamops_app    app_encryption_secret_key) && echo same || echo DIFFERS
cmp -s <(getkey prod/xamops_app app_encryption_secret_key) \
       <(git show b80f62ecc:xamops-service/src/main/resources/application-prod.properties \
           | sed -n 's/^app\.encryption\.secret-key=//p') && echo same || echo DIFFERS

# app_impersonation_secret_key must exist in prod/xamops_shared, which billops (:34),
# xamops (:169) and media-ops (:28) all read:
getkey prod/xamops_shared app_impersonation_secret_key >/dev/null && echo "impersonation: present"
```

**A `DIFFERS` on either comparison is a stop.** A different encryption key makes every
stored provider credential decrypt to `null`, which silently breaks every cloud
integration. Resolve it before the window.

#### IAM

```bash
# On the prod app host.
aws sts get-caller-identity          # must show the application role
```

All three `get-secret-value` listings above must succeed **from that host**. The
property imports are `optional:`, so a denied read does not fail the boot — it
surfaces much later as an unresolved `${…}` placeholder. Any Secrets Manager
tightening from report §7.1 must keep this role allowed.

#### Images

Build all four from a **clean clone** of the merged commit — never from a working tree,
which may carry uncommitted profile edits.

```bash
git clone --depth 1 --branch <merged-branch> <repo-url> /tmp/build && cd /tmp/build
SHA=$(git rev-parse --short HEAD)
docker build -t <registry>/billops:$SHA            -f billops/Dockerfile .
docker build -t <registry>/xamops-service:$SHA     -f Dockerfile .
docker build -t <registry>/media-ops-service:$SHA  -f media-ops-service/Dockerfile .
docker build -t <registry>/frontend-app:$SHA       -f frontend-app/Dockerfile .
```

#### Baselines

Record, so D5 and C1 can be compared against them:

```bash
psql -X -v ON_ERROR_STOP=1 -d postgres -f scripts/db/lob-text/census-lob-text.sql > D1-census.txt
```

```sql
-- Report §6.
SELECT id, account_name, provider FROM cloud_account
 WHERE gcp_service_account_key LIKE 'enc:v1:%' OR azure_client_secret LIKE 'enc:v1:%';
SELECT count(*) FROM ticket_reply WHERE user_id IS NULL;
-- The R1 column check: these must still match the August mappings.
SELECT table_name, column_name, data_type, character_maximum_length
  FROM information_schema.columns
 WHERE table_schema = 'public'
   AND (table_name, column_name) IN (('agreement','status'), ('support_message','cloud_provider'),
        ('support_message','sender_username'), ('support_message','status'),
        ('support_ticket_status','status'), ('ticket','source'), ('user_tab_access','tab_name'))
 ORDER BY 1, 2;
-- Flyway ranks.
SELECT installed_rank, version, description, success FROM flyway_schema_history
 ORDER BY installed_rank DESC LIMIT 10;
-- One AWS and one GCP total, to compare after the boot.
SELECT cache_key, json_data FROM cached_data WHERE cache_key LIKE 'AWS_MTD_COST::%' LIMIT 5;
SELECT split_part(cache_key, '::', 2) AS project, count(*) AS months
  FROM cached_data WHERE cache_key LIKE 'GCP_MONTHLY_TOTAL::%' GROUP BY 1 ORDER BY 1;
```

### D2 Rollback assets, just before the window

1. **A restorable snapshot that includes the large objects.** Either
   * an EBS snapshot of the DB volumes, taken with PostgreSQL stopped, or between
     `pg_backup_start()` and `pg_backup_stop()`; or
   * `pg_dump -Fc -d postgres -f prod-<date>.dump` — a whole-database dump includes
     large objects. **Verify it:** `pg_restore -l prod-<date>.dump | grep -c BLOB`
     must be greater than 0.
2. **Optional: an operator-run credential copy.** This is the only way to restore an
   `enc:v1:` account without re-entering it, so take it if the report §6 query above
   returned any row.
   ```sql
   CREATE TABLE cloud_account_cred_backup_<date> AS
     SELECT id, gcp_service_account_key, azure_client_secret FROM cloud_account;
   REVOKE ALL ON cloud_account_cred_backup_<date> FROM PUBLIC;
   ```
   **Drop it when the soak ends** — it is a plaintext credential table.
3. **The current August tags**, with their D1 run configurations, plus the FR-7
   dual-format images if they exist.

### D3 Window start

1. **Announce the forced re-login.** Every user will be signed out: D4 deletes the
   Spring Session store, because August's session documents (Boot 2.7.5 / Security
   5.7) are not guaranteed to deserialize under Boot 4.
2. **Stop every August container before any `main` container starts.** All four:
   ```bash
   docker stop billops xamops-service media-ops-service frontend-app
   ```
3. **Prove nothing is left.**
   ```bash
   docker ps --format '{{.Names}}\t{{.Image}}'      # no August tag remains
   ```
   ```sql
   SELECT client_addr, usename, application_name, count(*)
     FROM pg_stat_activity WHERE datname = 'postgres' GROUP BY 1,2,3;
   ```
   Only your own psql session may remain. A surviving August writer during the soak
   is harmless (it writes `legacy` too); a surviving August writer **after C3** writes
   a format `main` can still read but is a mixed state, and an August *reader* after
   C4 breaks. Mixed images are what caused the incident. Stop them all.

### D4 Redis

Clear these keys **before any `main` container starts**.

**Why:** August's Spring Session documents and its default-typed cache entries may not
deserialize under Boot 4. No service registers a `CacheErrorHandler`, so a
deserialization failure **fails the request** instead of counting as a cache miss.

```bash
# On the prod Redis host, or wherever redis-cli can reach it.
# 1. List the prefixes first. Read-only.
redis-cli --scan | awk -F: '{print $1}' | sort | uniq -c | sort -rn
```

```bash
# 2. Delete. UNLINK frees memory in the background, so it does not stall Redis.
redis-cli --scan --pattern 'spring:session:*'      | xargs -r redis-cli UNLINK
redis-cli --scan --pattern 'billops:admin:dashboard:*' | xargs -r redis-cli UNLINK

# 3. Every @Cacheable keyspace. No custom key prefix is configured, so the key is
#    "<cacheName>::<key>". Generate the list from the code you are deploying:
#      git grep -hE '@Cacheable|@CacheEvict|@CachePut' -- '*/src/main/java/*'
#    The 38 cache names in this build are:
for c in awsInvoice cloudWatchLogsStoragePrice creditRequests dashboardData \
         devops_scripts dynamoDbStoragePrice ebsPrice ebsSnapshotPrice ec2Price \
         ecrStoragePrice efsPrice eksClusterPrice elastiCachePrice elasticIpPrice \
         elbPrice gcpCredentials gcpCredentialsByBillingAccount gcpDashboard \
         gcpIamResources gcpResourceCosts hourlyOnDemandPrice hourlySpotPrice \
         invoiceDtos invoices metrics-ec2 metrics-lambda metrics-rds metrics-s3 \
         natGatewayPrice rdsPrice rdsSnapshotPrice resourceDetail resources \
         s3StoragePrice spotPrice tickets tickets_by_category users; do
  redis-cli --scan --pattern "${c}::*" | xargs -r redis-cli UNLINK
done
```

**`FLUSHDB` is allowed only if** the prefix listing in step 1 shows nothing but keys
belonging to these services. If anything else shares the instance, do not flush.

### D5 Start `main`

1. **Apply the reviewed additive DDL** from [R2](#r2-ddl-dry-run), if there was any.
   Nothing else.
2. **Start in this order**, each with its D1 run configuration plus
   `SPRING_PROFILES_ACTIVE=prod`:
   1. service-registry (if used)
   2. xamops-service
   3. billops
   4. media-ops-service
   5. frontend-app
3. **billops and xamops-service also get:**
   ```bash
   -e SPRING_PROFILES_ACTIVE=prod \
   -e XAMMER_LOBTEXT_WRITEMODE=legacy \
   -e SPRING_JPA_HIBERNATE_DDLAUTO=none
   ```
   **xamops-service additionally gets all four job flags, explicitly `false`:**
   ```bash
   -e XAMMER_LOBTEXT_CONVERTONREAD_ENABLED=false \
   -e GCP_BILLING_NETCOSTCACHEMIGRATION_ENABLED=false \
   -e GCP_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED=false \
   -e AWS_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED=false
   ```
   Set them even though those are the defaults: the explicit value is what the startup
   log line confirms, and it survives a future default change.
4. **Flyway.** xamops should have nothing pending (`V20260929_2` was applied on Oct 1).
   media-ops-service applies `V13__add_source_key.sql`.
5. **Before opening traffic, check all four:**
   ```bash
   docker logs billops        2>&1 | grep 'LOB-text write mode'
   docker logs xamops-service 2>&1 | grep -E 'LOB-text write mode|LOB-text startup flags'
   ```
   * both must log `LOB-text write mode: legacy (configured)`;
   * xamops must log `LOB-text startup flags:` with four `false` values;
   ```sql
   SELECT client_addr, usename, application_name, count(*)
     FROM pg_stat_activity WHERE datname = 'postgres' GROUP BY 1,2,3;
   ```
   * only `main` containers appear;
   * the billops admin dashboard returns **200** and lists **every** account, with the
     AWS and GCP totals recorded in D1 (a missing account or a zero total is a stop);
   * a Cloudsitter policy `GET` returns **decoded JSON**, not a number.

### D6 GCP recompute

FR-5.3 step 5. Run it here, in the window.

> **The marker already exists in production** (report §4.3):
> `GCP_NET_COST_MIGRATION::v1-credits-netted`. While it exists, `main`'s flush
> **skips**, so the GCP months stay as they are. **Delete it first**, before the boot
> that is meant to run the flush. Without this delete, step 2 below does nothing.

```sql
-- On the prod DB host. Database: postgres.
SELECT cache_key, json_data FROM cached_data
 WHERE cache_key = 'GCP_NET_COST_MIGRATION::v1-credits-netted';          -- confirm it exists
DELETE FROM cached_data WHERE cache_key = 'GCP_NET_COST_MIGRATION::v1-credits-netted';
```

```bash
# 2. Restart xamops-service ONCE with both GCP flags true. Everything else unchanged.
docker rm -f xamops-service
docker run -d --name xamops-service <recorded run config> \
  -e SPRING_PROFILES_ACTIVE=prod -e XAMMER_LOBTEXT_WRITEMODE=legacy \
  -e SPRING_JPA_HIBERNATE_DDLAUTO=none \
  -e GCP_BILLING_NETCOSTCACHEMIGRATION_ENABLED=true \
  -e GCP_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED=true \
  -e AWS_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED=false \
  -e XAMMER_LOBTEXT_CONVERTONREAD_ENABLED=false \
  <registry>/xamops-service:<sha>

# 3. Wait for the refill to finish.
docker logs -f xamops-service 2>&1 | grep -m1 '\[GcpSnapshot\] Auto-snapshot complete'
```

```sql
-- Verify before turning the flags off: the months must be populated and non-zero.
SELECT split_part(cache_key, '::', 2) AS project, count(*) AS months,
       count(*) FILTER (WHERE json_data = '0.0' OR json_data = '0') AS zeros
  FROM cached_data WHERE cache_key LIKE 'GCP_MONTHLY_TOTAL::%' GROUP BY 1 ORDER BY 1;
SELECT cache_key FROM cached_data
 WHERE cache_key = 'GCP_NET_COST_MIGRATION::v1-credits-netted';   -- the marker is back
```

```bash
# 4. Restart with BOTH flags false, then drop the dashboard cache and refresh.
docker rm -f xamops-service && docker run -d --name xamops-service <same, both flags false> …
redis-cli --scan --pattern 'billops:admin:dashboard:*' | xargs -r redis-cli UNLINK
```

If the team would rather **keep** the mixed net months (through September) and gross
months (from Oct 2) instead of recomputing, record that decision and its effect on the
cost trend, and skip to [C5](#c5-gcp-verification).

### D7 Open traffic and soak

* Stay in `legacy` mode for **at least 7 days**. Record the end date.
* Re-run the report §6 queries **daily**:
  ```sql
  SELECT id, account_name, provider FROM cloud_account
   WHERE gcp_service_account_key LIKE 'enc:v1:%' OR azure_client_secret LIKE 'enc:v1:%';
  SELECT count(*) FROM ticket_reply WHERE user_id IS NULL;
  ```
  Each new `enc:v1:` row raises the cost of a rollback, because August cannot decrypt
  that prefix. A rising count is a reason to shorten the soak, not to extend it.

### Rollback matrix

| State at rollback time | Path | Caveats |
|---|---|---|
| **D5–D6, before traffic** | Stop `main`, repeat [D4](#d4-redis), start the August tags with their D1 run configurations. | Clean. Nothing has been written yet. |
| **Soak (`legacy`)** | The same image swap. All four columns stay readable, because `legacy` writes are large objects. Then run the report §6 queries. | **`enc:v1:` credentials:** August cannot decrypt the `enc:v1:` prefix. Restore those accounts from the D2 credential copy, or re-enter them by hand. **User-less ticket replies:** `ticket_reply.user_id IS NULL` stays 0 while email ingest is off (`xamops.tickets.email-ingest.enabled` defaults to `false`); if it is non-zero, those replies will not render on August. |
| **After C3 (`inline`)** | Plain August images **cannot read inline rows**. Use the FR-7 dual-format images, or restore the D2 snapshot. | This is the point of no return for plain August images. **Inline rows** written after C3 are the problem, not the converted ones. |
| **After C4 (Script N)** | `rollback-lob-text.sql` restores **only the rows it converted**, from `lob_text_backup_20261005`. Rows written inline since C3 are untouched, so the "After C3" row still applies on top of it. | Run it with `SET lobtext.confirm = 'rollback-lob-text'`. Keep the backup table. |
| **Snapshot restore** | Restore the D2 snapshot. | **Every write since D2 is lost.** The recovery point is the snapshot time; [R7](#r7-restore-drill) measured the recovery time. |

**Rules**

* **Stay in `legacy` until the soak ends.** It is the only mode in which an image swap
  is a complete rollback.
* **C3 is the point of no return** for plain August images.
* Redis compatibility runs one way only: after any rollback to August, repeat
  [D4](#d4-redis) before starting the August containers, and announce the re-login again.

---

## Phase C: format cutover

### C1 Read-only pre-checks

```bash
# Prod DB host. Database: postgres.
export PAGER=cat
psql --version                                        # must be 10 or newer
psql -X -d postgres -c 'SELECT version();'            # open question §9.1, recorded only
psql -X -v ON_ERROR_STOP=1 -d postgres -f scripts/db/lob-text/census-lob-text.sql > C1-census.txt
```

```sql
-- Report §6.
SELECT id, account_name, provider FROM cloud_account
 WHERE gcp_service_account_key LIKE 'enc:v1:%' OR azure_client_secret LIKE 'enc:v1:%';
SELECT count(*) FROM ticket_reply WHERE user_id IS NULL;
-- R1 column check (same query as D1).
SELECT table_name, column_name, data_type, character_maximum_length
  FROM information_schema.columns
 WHERE table_schema = 'public'
   AND (table_name, column_name) IN (('agreement','status'), ('support_message','cloud_provider'),
        ('support_message','sender_username'), ('support_message','status'),
        ('support_ticket_status','status'), ('ticket','source'), ('user_tab_access','tab_name'))
 ORDER BY 1, 2;
-- Flyway: nothing pending, no rank >= 75.
SELECT installed_rank, version, description, success FROM flyway_schema_history
 ORDER BY installed_rank DESC LIMIT 10;
```

**Fix every row the census reports under `oid_shaped_content`** before C4. That is a
large object whose *decoded text* is itself OID-shaped (design D11), so converting it
would store a value the FR-6 guard then refuses. Fix it at the source — for example,
re-save the Cloudsitter policy with a valid schedule — and re-run the census.

### C2 August retired

Repeat the [D1 inventory](#inventory) checks, and nothing else: `docker ps` on every
host, `kubectl get deploy -A -o wide` if EKS is live, and the `pg_stat_activity`
`client_addr` mapping. **Every address must map to a `main` container.** This is the
gate for FR-5.3 item 3.

### C3 Switch to `inline`

#### Go / no-go

* The soak ended with no rollback trigger, **and**
* either the FR-7 dual-format images passed [R6](#r6-rollback-drill-b-post-c3-rollback),
  **or** the team has recorded, in writing, that a rollback after C3 means a snapshot
  restore or a fix-forward.

#### Watermark

```sql
-- Prod DB. Record this value as W, before any instance is restarted.
SELECT max(oid) FROM pg_largeobject_metadata;
```

#### Switch

Set `XAMMER_LOBTEXT_WRITEMODE=inline` on **every** billops and xamops-service
instance, restart them, and confirm on each one:

```bash
docker logs <container> 2>&1 | grep 'LOB-text write mode'   # LOB-text write mode: inline (configured)
```

#### Check: no legacy writer is left

Wait **at least 30 minutes** after the last restart — the schedulers run every 10
minutes — then:

```bash
psql -X -v ON_ERROR_STOP=1 -v since_oid=<W> -d postgres -f scripts/db/lob-text/census-lob-text.sql
```

Every column must report **0** references above `W`. A legacy writer — a missed `main`
instance, or a surviving August image — mints a new large object on each changed write,
and every new OID is above `W`. If any count is non-zero:

```sql
SELECT pid, client_addr, usename, application_name, state, left(query, 120)
  FROM pg_stat_activity WHERE datname = 'postgres';
```

Find the writer, stop it, and repeat the check. `last_updated` cannot prove this, since
a legacy writer updates it the same way an inline writer does. Script N's final
assertion is the last gate.

### C4 Script N

Run it **in each database** (production has one, `postgres`), in the low-write window,
or with billops and xamops-service stopped if R5's duration was longer than the team
accepts for lock waits (see [the lock policy](#lock-policy)).

```bash
# Prod DB host. Database: postgres.
psql -X -v ON_ERROR_STOP=1 -d postgres \
  -c "SET lobtext.confirm = 'august-retired-and-inline-mode'" \
  -f scripts/db/lob-text/normalize-lob-text-columns.sql
```

The script refuses to run without that exact confirmation phrase, sets
`lock_timeout = '5s'`, and runs as one transaction: any error rolls the whole run back.

**Post-checks:**

1. Read the `NOTICE` totals: per column `converted`, `skipped_missing`,
   `skipped_unreadable`, `skipped_undecodable`, `skipped_oid_shaped`,
   `concurrent_changed`, and the `lobtext|run|all|all|converted|<n>` total.
2. Re-run the census, twice:
   ```bash
   psql -X -v ON_ERROR_STOP=1 -d postgres -f scripts/db/lob-text/census-lob-text.sql
   psql -X -v ON_ERROR_STOP=1 -v since_oid=<W> -d postgres -f scripts/db/lob-text/census-lob-text.sql
   ```
   Expect **0 convertible** references, and **0 above `W`**.
3. **Keep `lob_text_backup_20261005`.** It is the only input to
   `rollback-lob-text.sql`. Do not drop it until the team signs off (see
   [C7](#c7-later)).

### C5 GCP verification

Verify that [D6](#d6-gcp-recompute) took effect:

```sql
SELECT cache_key FROM cached_data
 WHERE cache_key = 'GCP_NET_COST_MIGRATION::v1-credits-netted';          -- exists again
SELECT split_part(cache_key, '::', 2) AS project, count(*) AS months,
       count(*) FILTER (WHERE json_data IN ('0', '0.0')) AS zeros
  FROM cached_data WHERE cache_key LIKE 'GCP_MONTHLY_TOTAL::%' GROUP BY 1 ORDER BY 1;
```

The months must be **net** of credits, with no unexpected zeros. If D6 was deferred,
run it now exactly as written there — including **the marker delete first**.

### C6 Derived Redis keys

Production is already covered by [D4](#d4-redis) and [D6](#d6-gcp-recompute). In
**every other environment** where a pre-fix `main` build ran, the derived caches were
computed from the bad values and must go. The patterns are the ones recorded in
`database_migration_cached_data_lob_oid_repair.sql` (originally lines 59-61):

```bash
redis-cli --scan --pattern 'costHistory-*'             | xargs -r redis-cli UNLINK
redis-cli --scan --pattern 'DASHBOARD_COST*'           | xargs -r redis-cli UNLINK
redis-cli --scan --pattern 'lastMonthCost-*'           | xargs -r redis-cli UNLINK
redis-cli --scan --pattern 'billops:admin:dashboard:*' | xargs -r redis-cli UNLINK
```

```sql
-- And the snapshots derived from them, so they recompose from repaired data:
DELETE FROM cached_data WHERE cache_key LIKE 'FINOPS_COST_HISTORY::%';
```

### C7 Later

* **Orphan large objects.** Clean them up **only** with explicit reference checks
  against **every** in-scope text column **and every backup table**
  (`lob_text_backup_20261005`, `cached_data_lob_oid_backup`, and any
  `cached_data_inline_backup_*`).

  > ⚠️ **Do not run `vacuumlo`.** It scans only the columns it is told about and
  > deletes every other large object as an orphan. The backup tables hold live OIDs
  > that it will not see, so a `vacuumlo` run makes `rollback-lob-text.sql`
  > unrecoverable. Nothing in this runbook or in any of the four scripts calls
  > `lo_unlink`.

* **Script D** (`dev-ops-script-lob-to-inline.sql`) converts the DevOps-library
  columns, which are out of the critical path, and can run at any time:
  ```bash
  psql -X -v ON_ERROR_STOP=1 -d postgres \
    -c "SET lobtext.confirm = 'convert-dev-ops-script'" \
    -f scripts/db/lob-text/dev-ops-script-lob-to-inline.sql
  ```
* **Drop the D2 credential copy** if you took one:
  `DROP TABLE cloud_account_cred_backup_<date>;`
* Follow-ups: set both prod profiles to `ddl-auto=none` (R1); flip the code default to
  `inline` once Script N has run everywhere; add a `CacheErrorHandler` so a
  deserialization failure counts as a cache miss.

---

## FR-7 August-line hotfix: recommendation

**Nothing is built for this in the current change. This section is a recommendation.**

**Recommendation: build it this week, billops first,** unless the report §7.1 access
controls are already in place and verified. It has two uses:

1. it keeps the August line readable if a pre-fix `main` build writes an inline row; and
2. it is the **only cheap rollback image after C3** — so build it **before C3** at the
   latest, covering all four columns.

**Deciding factors**

* **Report §7.1 status.** While any non-production or pre-fix `main` host can reach the
  production database, a single inline row re-triggers the 500. Today those paths
  include unrestricted Secrets Manager access, `pg_hba` open to `0.0.0.0/0`, the
  superuser password in `Jenkinsfile`, git history and the images, and the removed base
  datasource in older `main` builds.
* **FR-3 = option B.** `main` builds that contain this change no longer break August's
  storage format. **Earlier** `main` builds — `:latest` images, local jars — still
  write inline and still flush.
* **Report §6.** Neither option B nor the hotfix fixes `enc:v1:` credentials or
  user-less ticket replies.

**Outline** (report §7.3 (b)+(c))

* Branch from `8a618e7eb` (billops) and `b80f62ecc` (xamops-service). Build with
  Java 17 / Boot 2.7.5 (`billops/Dockerfile:2,16 @8a618e7eb`).
* **(c) Dashboard resilience.** Remove `@Transactional` at
  `AdminDashboardService:123,347`, add a `findAllWithClient` fetch join, and raise the
  DEBUG catches to WARN with the cache key in the message.
* **(b) Dual-format read.** Add a Hibernate 5.6 `UserType` on `CachedData.jsonData`,
  and on the three xamops columns, which its rollback role requires. It reads with
  `getString` plus the D12 candidate rule and the §3.1 non-raising statement, and
  still **writes with `setClob`**, so the August format is preserved.
* Use a separate tag, for example `billops:17aug-lobfix1`. Diff `docker inspect`
  against the current image and reuse the identical run configuration. Rollback is
  simply the previous tag.
* Drop the hard-coded datasource lines (`application-prod.properties:6-8 @8a618e7eb`).
* Push to the private registry only.

---

## Appendix: the four scripts

| Script | What | Confirmation phrase |
|---|---|---|
| `scripts/db/lob-text/census-lob-text.sql` | Read-only inventory of all four columns, plus `-v since_oid=<W>` for the C3 watermark check. `BEGIN READ ONLY`. **psql 10+** | none |
| `scripts/db/lob-text/normalize-lob-text-columns.sql` | **Script N.** Converts the four in-scope columns from LO references to plain text, preserving `last_updated`, with a backup row per conversion | `august-retired-and-inline-mode` |
| `scripts/db/lob-text/rollback-lob-text.sql` | Restores the rows Script N (or Script D) converted, from `lob_text_backup_20261005` | `rollback-lob-text` |
| `scripts/db/lob-text/dev-ops-script-lob-to-inline.sql` | **Script D.** The same conversion for the DevOps-library columns (`dev_ops_script`, `devops_script`) | `convert-dev-ops-script` |

All four are **manual**, run **per database** by an operator. Nothing in the
application invokes them, and they are not Flyway migrations (AC-25). Each one starts
with `\set ON_ERROR_STOP on` and runs as a single transaction.
