# Upwind API — Integration Access Gaps & Requests

**Prepared by:** XamOps (Xammer) engineering
**For:** Upwind Product / API team
**Date:** July 2026

## Context

We are building a CNAPP console in the XamOps platform powered by the Upwind API,
syncing data for a single connected AWS account. We have successfully integrated a
broad set of Upwind capabilities, but several modules and data types return
`401 Unauthorized` or `404 Not Found` for our integration, which prevents full parity
with the Upwind console. This document lists exactly what we can and cannot access, so
your team can advise on scope/licensing changes or confirm intended limitations.

**Integration details (no secrets included):**
- Organization ID: `org_Zwq0e1xF9Au4AF0Q`
- OAuth client ID: `D0zAuPnDHhXgolmDiGewJtbM7nqJxwMx`
- Auth: OAuth2 client-credentials against `oauth.upwind.io`, audience `https://api.upwind.io`
- Connected cloud account: AWS `605134457560`

**Interpretation of status codes below:**
- `200 / 206` — data returns successfully (integrated).
- `401` — endpoint exists but our credential scope is **not authorized** to read it.
- `404` — endpoint/module is **not provisioned** for our integration.

---

## 0. Console feature-parity summary

How each Upwind console module maps to what we can deliver via the API today.

| Upwind console module | Replication status | Notes |
|---|---|---|
| **Home / Overview** | ✅ Replicated | Findings summary, top findings, threat funnel, vuln-by-severity, findings-over-time. |
| **Issues** | ✅ Replicated | Failing posture checks grouped into actionable issues with detail drawer + remediation. |
| **Vulnerabilities** | ✅ Replicated | Dashboard (unique CVEs, severity split, workload types, exploitable/EPSS, trends) + CVE drill-down. |
| **Configurations** | ✅ Replicated | Dashboard (score, severity summary, findings-over-time, top findings, per-framework scores) + Findings + Frameworks. |
| **Configurations → Rules** | ❌ No data | `configuration-rules` returns empty (see §4). |
| **APIs** | ⚠️ Partial | Endpoints funnel (total / internet-exposed / authenticated / unauthenticated), method breakdown, domains, sensitive-data classification, observed status codes, endpoint inventory. Still missing per-endpoint OWASP/CWE vulnerability classes (see §4). |
| **Threats** | ⚠️ Partial | Detections + policies replicated. Live event stream / noise-to-signal depth blocked (see §2). |
| **Components (SBOM)** | ✅ Replicated | Full package inventory with vulnerability + in-use rollups. |
| **Inventory → Catalog** | ✅ Replicated | Asset catalog by category with detail drawer. |
| **Inventory → Runtime Map** | ❌ Blocked | Needs runtime sensor connection telemetry. No endpoint exists (§11.1 — the paths in §2 are non-routes, not scope denials). We render a static config topology instead. |
| **Inventory → Graph** | ⚠️ Reachable, not built | Corrected 2026-09-07: relationship traversal and the edge-catalogue schema route both work on our credential (§11 / §11.1). Blocked only on the response not projecting the edge. |
| **Inventory → Identities** | ⚠️ Partial | The dedicated module returns `404`, but the inventory catalog exposes `high_privilege_risk.has_elevated_privilege`, so elevated-privilege assets are listed. Human vs non-human split and role-trust graph remain unavailable. |
| **Inventory → Images** | ❌ Not provisioned | `404` (see §3). |
| **Workflows** | ✅ Replicated | Workflow list. |
| **Identities (CIEM)** | ⚠️ Partial | Privilege flags from the inventory catalog; entitlement analysis and least-privilege recommendations remain unavailable. |
| **Data (DSPM)** | ⚠️ Partial | Dedicated endpoints `404`, but the inventory catalog carries `sensitive_data_at_rest`/`sensitive_data_in_transit` (PII/PHI/PCI) per asset and API endpoints carry per-endpoint findings, so classification counts are shown. Datastore-level scanning detail remains unavailable. |
| **AI (AI-SPM)** | ❌ Not provisioned | `404` (see §3). |
| **Code (IaC / SAST)** | ❌ Not provisioned | `404` (see §3). |

**Legend:** ✅ Replicated · ⚠️ Partial (data gaps) · ❌ Blocked / not available.

---

## 1. Working today (for reference)

| Capability | Endpoint | Status |
|---|---|---|
| Threat detections | `GET /v1/organizations/{org}/threat-detections` | 200 |
| Threat policies | `GET /v1/organizations/{org}/threat-policies` | 200 |
| Workflows | `GET /v1/organizations/{org}/workflows` | 200 |
| SBOM packages | `GET /v1/organizations/{org}/sbom-packages` | 200 |
| API security endpoints | `GET /v1/organizations/{org}/apisecurity-endpoints` | 206 (paged) |
| Configuration frameworks | `GET /v1/organizations/{org}/configuration-frameworks` | 200 |
| Configuration findings | `GET /v1/organizations/{org}/configuration-findings` | 200 |
| Vulnerability findings | `GET /v1/organizations/{org}/vulnerability-findings` | 206 (paged) |
| Inventory assets (catalog) | `POST /v2/organizations/{org}/inventory/assets/search` | 200 |

---

## 2. Blocked by credential scope — `401` (endpoint exists; please authorize)

These are all **runtime sensor** data. They power Upwind's Runtime Map, Graph, and the
live event/traffic views, which we currently cannot replicate.

| Upwind feature | Endpoint | Status | Request |
|---|---|---|---|
| Inventory → Runtime Map | `GET /v2/organizations/{org}/inventory/topology` | 401 | Authorize read scope |
| Inventory → connections | `GET /v2/organizations/{org}/inventory/connections` | 401 | Authorize read scope |
| Inventory → Graph | `GET /v2/organizations/{org}/inventory/graph` | 401 | Authorize read scope |
| Inventory → runtime | `GET /v2/organizations/{org}/inventory/runtime` | 401 | Authorize read scope |
| Runtime event stream | `GET /v2/organizations/{org}/events` | 401 | Authorize read scope |

**Question for Upwind:** Are these accessible via API at all for partner integrations,
or is runtime topology/traffic intentionally console-only? If accessible, which OAuth
scope / entitlement do we need added to our client?

---

## 3. Not provisioned — `404` (module not enabled for our integration)

| Upwind module | Endpoints tried | Status |
|---|---|---|
| Identities (CIEM) | `identities`, `cloud-identities`, `iam-identities` | 404 |
| Data Security (DSPM) | `data-security-findings`, `data-classification`, `sensitive-data`, `datastores` | 404 |
| AI Security (AI-SPM) | `ai-findings`, `ai-inventory`, `ai-models` | 404 |
| Code / IaC | `code-findings`, `iac-findings`, `repositories`, `code-repositories` | 404 |
| Images | `images`, `container-images` | 404 |

**Question for Upwind:** Are these modules API-exposed under different paths, or are
they gated by licensing/plan? If licensed, what are the correct endpoint paths and
required scopes?

---

## 4. Field-level gaps within accessible modules

| Area | Gap | Impact |
|---|---|---|
| API Security | RESOLVED for classification: we were reading the v1 `apisecurity-endpoints` GET, whose payload has only method, URI, auth state, internet exposure and domains. The documented v2 `POST /api-security/endpoints/search` additionally returns `risk_overview.sensitive_data[]` (category + type) and `status_codes[]`, both now synced and shown. Still absent from either version: per-endpoint API-vulnerability / OWASP / CWE fields. | "Sensitive endpoints by data classification" is built. "API vulnerabilities" / "OWASP-CWE" panels remain unbuildable. |
| Configurations | `configuration-rules` returns `200` but an **empty array**. | The console shows ~2,226 rules; we receive none, so a "Rules" view can't be populated. |
| Threats | Detections are available, but the underlying runtime event stream is `401` (see §2). | Limited depth for the noise-to-signal funnel / threat-story detail. |
| Auth state | Many endpoints have no `authentication.state` (neither AUTHENTICATED nor UNAUTHENTICATED). | Endpoints are reported as "unclassified" rather than authenticated/unauthenticated. Is an explicit state expected once traffic is observed? |

---

## 5. Partner-embedded onboarding & sensor deployment (key ask)

Our goal is for a customer to **onboard their cloud account and deploy Upwind sensors
directly from within XamOps** — without leaving for the Upwind console — so XamOps can
act as an embedded/OEM delivery surface for Upwind. This requires programmatic
onboarding and sensor-deployment APIs, most of which are not currently available to us.

**What we probed:**

| Capability needed | Endpoint(s) tried | Status |
|---|---|---|
| List / manage connected cloud accounts | `GET /v1/organizations/{org}/cloud-accounts` | 401 (exists, scope-blocked) |
| Create a cloud-account connection | `cloud-account-connections`, `connections` | 404 |
| Cloud integrations management | `integrations` | 404 |
| Webhook integration (we use this) | `integration-webhooks` | 200 |
| Sensor / agent deployment | `sensors`, `agents`, `sensor-installations` | 404 |
| Onboarding flow | `onboarding` | 404 |
| Cluster onboarding | `clusters` | 404 |

**What we need from Upwind to enable embedded onboarding:**

1. **Programmatic cloud-account connection** — an API to initiate and complete an
   AWS/Azure/GCP account connection (e.g., generate the CloudFormation/Terraform/role
   trust artifacts, return the external ID, and confirm connection status) so a customer
   can connect their account from the XamOps UI. `cloud-accounts` currently returns
   `401`; please advise the scope, and whether a *create/connect* API exists.

2. **Sensor / agent deployment artifacts via API** — API-retrievable install artifacts
   and commands (eBPF sensor Helm chart / values, DaemonSet manifests, install script,
   CloudFormation for the managed sensor) plus deployment/health status, so XamOps can
   present and track sensor rollout per account/cluster.

3. **Multi-tenant / partner provisioning model** — can XamOps programmatically create
   and manage **per-customer organizations (sub-tenants)** under a partner account, each
   with its own connected cloud accounts and sensors? What is the OEM/reseller
   provisioning model and the credential/scope structure for it?

4. **Partner credentials & scopes** — the OAuth scopes / partner API key model required
   for the above (account provisioning, sensor deployment, tenant management), and any
   partner-program prerequisites.

---

## 6. Summary of requests

1. Confirm whether runtime data (`inventory/topology`, `connections`, `graph`, `runtime`, `events`) is API-accessible for partner integrations; if so, provide the required scope/entitlement.
2. Confirm whether Identities (CIEM), Data (DSPM), AI (AI-SPM), Code/IaC, and Images are API-exposed; provide correct paths and licensing requirements.
3. Advise whether API-security endpoints can include sensitive-data classification and vulnerability/OWASP fields.
4. Confirm expected behavior of `configuration-rules` (empty array) for our org.
5. **Embedded onboarding:** provide (or confirm availability of) APIs to connect a customer's cloud account from XamOps, retrieve/deploy Upwind sensors, and manage per-customer tenants — plus the partner scopes/credentials required (see §5).

## Methodology

All results above were obtained via authenticated `GET`/`POST` requests using our
client-credentials token (audience `https://api.upwind.io`) with header
`User-Agent: XamOps/1.0`, against organization `org_Zwq0e1xF9Au4AF0Q`. Status codes are
as returned by `api.upwind.io` at the time of testing (July 2026).

---

## 7. Update — follow-up after Upwind's response (July 23, 2026)

Upwind engineering confirmed: (a) the 6-permission JWT claim is **not** the authorization
mechanism, so our credential is not scope-restricted to those 6; (b) `/shiftleft/ci-scans`
and `/shiftleft/build-results` are **not valid routes** (inferred from permission names) —
the correct route is `GET /v1/organizations/{orgId}/shiftleft/results/{fingerprint}`; and
(c) auth is working (several endpoints return 200 on the same token). They asked for exact
evidence on the two remaining 401s. Findings below.

### 7.1 `/events` — still 401 (evidence for Upwind)

- **Request:** `GET https://api.upwind.io/v2/organizations/org_Zwq0e1xF9Au4AF0Q/events`
- **Headers sent:** `Authorization: Bearer <client-credentials token>`, `User-Agent: XamOps/1.0`
- **Sent at (UTC):** 2026-07-23T09:42:56Z
- **Response `date` header:** Thu, 23 Jul 2026 09:42:57 GMT
- **HTTP status:** `401`
- **Response body:**
  ```json
  {"code":"401","message":"The authorization credentials provided for the request are invalid. Check the value of the Authorization HTTP request header."}
  ```
- **Note:** this 401 response carries **no `trace-id` header**, whereas our `400` responses
  from valid routes (e.g. `threats/stories/search`) *do* include `trace-id`. This suggests
  `/events` is being rejected at the gateway/edge (possibly not a recognized route) rather
  than by application-level authorization — similar to the shiftleft paths. Please confirm
  the correct events endpoint path, if one is exposed.

### 7.2 `/v2/threats/stories/search` — NOT an access issue (resolved on our side)

This endpoint is **accessible and authorized**. Our earlier 401 report was inaccurate; the
real behavior is a `400` caused by an **unsupported filter field on our side**:

- With `conditions: [{ field: "cloud_account_id", ... }]` → `400`:
  ```json
  {"errors":[{"code":"InvalidParameter","message":"Invalid condition field 'cloud_account_id'. Supported fields: severity, status, create_time, update_time","field":"conditions.field"}]}
  ```
- With a supported field, e.g. `conditions: [{ field: "severity", operator: "in", value: ["CRITICAL","HIGH","MEDIUM","LOW"] }]` → `200`:
  ```json
  {"items":[],"metadata":{"limit":5}}
  ```

We were filtering stories by `cloud_account_id`, which the endpoint rejects. **Fixed on our
side** — we now filter by a supported field and post-filter by account in code. No action
needed from Upwind here.

### 7.3 Re-verification needed for the §3 `404` modules

Given that two of our endpoints were inferred rather than documented, the `404`s in §3
(Identities, Data, AI, Code/IaC, Images) may likewise be **incorrect paths** rather than
unprovisioned modules. Could you share the documented endpoint paths (or the OpenAPI spec)
for these modules so we can re-test against the canonical routes? We'll re-run and report.

The `shiftleft` correction is noted: we will use
`GET /v1/organizations/{orgId}/shiftleft/results/{fingerprint}`.

---

## 8. Audit against the published Management REST API v2 spec (2026-08-03)

The full v2 OpenAPI reference resolved several items above. This section records what we
were calling **incorrectly**, and what remains genuinely unavailable. Every claim below was
verified against the live API, not just the document.

### 8.1 Defects found and fixed

| # | What was wrong | Documented / correct form | Impact before the fix |
|---|---|---|---|
| 1 | Inventory sync called `POST /v2/{org}/inventory/assets/search` | `POST /v2/{org}/inventory/catalog/assets/search` | Both return `200`, which is why this went unnoticed. The flat search returns only id/label/name/region/provider/account/resource-id. The catalog adds `vulnerability_risk`, `detection_risk`, `network_risk`, `high_privilege_risk.has_elevated_privilege`, `sensitive_data_at_rest`, `sensitive_data_in_transit`, `technologies` (with EOL/EOS dates). **This is why we believed privilege and data classification were unobtainable.** |
| 2 | Inventory paged at `limit=100` | Documented max is `200` | Twice the requests for the same walk. |
| 3 | Inventory filtered per `cloud_provider` | The catalog supports `cloud_account_id`; `cloud_provider` is **not** a catalog filter field and returns `200` with an empty `items[]` | Would have silently produced an empty inventory after the endpoint switch. The sync now iterates accounts. |
| 4 | API security read the v1 `GET /apisecurity-endpoints` | `POST /v2/{org}/api-security/endpoints/search` | Missing `risk_overview.sensitive_data[]` and `status_codes[]`, i.e. the whole PII/PCI panel. |
| 5 | Webhook create posted to `POST /v1/{org}/integration-webhooks` with `vendor: "CUSTOM"` and `authentication.type: "BEARER"` | `POST /v2/{org}/integrations/webhooks`, `vendor: "custom"`, `authentication.type: "bearer"`, vendor fields nested under `config` | Undocumented path and uppercase enum values. Registration is now also idempotent (searches first) and supports patch/delete. |
| 6 | Token host `oauth.upwind.io` | Documented host is `auth.upwind.io` | Both answer (verified live), but we were depending on an undocumented alias. |
| 7 | No `429` handling — `exchangeWithRetry` retried only 5xx and I/O errors | Every v2 operation documents a `429` "Rate Limit Exceeded" response | A single rate-limit rejection failed the whole sync domain. Now retried, honouring `Retry-After`. |

### 8.2 Still genuinely unavailable

Not present in the v2 reference at all, so these are feature requests rather than
integration bugs:

- **Runtime events / live event stream.** Only `POST /v2/{org}/events/shift-left/search` exists; there is no runtime event search.
- **Topology / graph / connections.** No endpoint. The Runtime Map cannot be reproduced; we render a static config topology instead.
- **Per-endpoint OWASP / CWE classes** on API-security endpoints.
- **Built-in configuration rule list** (only custom-rule create/patch is exposed).
- **Cloud-accounts management** (v1-only, and `401` for us).
- **Scope-bound credentials.** Scopes are readable via `POST /v2/{org}/access-management/scopes/search`, but a credential cannot be bound to one.
- **A dedicated attack-surface endpoint.** Ours is derived from posture findings.

### 8.3 Deliberately not migrated

- `POST /v2/{org}/configurations/frameworks/search` returns `state`, `framework_rollout_state`, `platforms` and `framework_type`, but **not** `compliance_score` or `last_scan_time`. The v1 `/configuration-frameworks` list carries both, which the Compliance tab needs. Migrating would lose data.
- `POST /v2/{org}/configurations/findings/search` — **investigated and rejected**, see §9. v1 carries everything v2 does except `cloud_account_tags`, and v2's search payload is actually *poorer* (it drops `cloud_account_name`).

---

## 9. Posture findings: why v1 stays (investigated 2026-08-04)

Field-by-field diff of the same findings through both APIs, plus an id-stability check.

### 9.1 The migration risk I had flagged was wrong

I deferred this on the grounds that differing finding ids would make the first v2 sync insert
a parallel row set and archive all ~19.4k existing rows. **That is not the case.** 10/10 v1
finding ids resolved through `GET /v2/{org}/configurations/findings/{id}`, and a side-by-side
on `fnd-ab4fdd58…` matched on `resource.id`, `rule.title` and timestamp. The two APIs share
one id space.

### 9.2 v2 adds almost nothing, and loses something

Genuinely new in v2: `resource.cloud_account_tags[]` (which v2 can also *filter* on as
`key=value`) and `framework.revision`.

Everything else that looked new is a rename or relocation:

| v1 | v2 |
|---|---|
| `check.{title,description,remediation,id}` | `rule.{…}` — identical values |
| `last_seen_time` | `evaluation_time` |
| `last_sync_time` | `resource.sync_time` |
| `resource.cloud_provider` | `resource.cloud_provider_name` |
| `resource.risk_categories[]` | top-level `risk_categories[]` |

**v2's search payload drops `resource.cloud_account_name`** (0/100 items; the detail route
has it, the search list does not). A sync consumes the search endpoint, so migrating loses a
field we display today.

Migrating would also require normalising case — v2 lowercases `status`, `severity`,
`framework.status` and `resource.type` (`AWS_S3_BUCKET` → `aws_s3_bucket`) — and its `limit`
caps at 100 against v1's 1000, taking a full walk from ~20 requests to ~195.

**Conclusion: do not migrate.** The only unreachable field is `cloud_account_tags`.

### 9.3 Kubernetes context needed no migration at all

`resource.cluster_id` and `resource.namespace` are **already in the v1 payload** — confirmed
on a `KUBERNETES_DEPLOYMENT` finding, identical values in both versions
(`cluster_id=4122e4ce-…`, `namespace=pr-3193`). We were simply never mapping them, so K8s
findings displayed an opaque composite resource id with no cluster or namespace. Now mapped
and shown in the Configurations table and finding drawer.

### 9.4 The real defect: PASS findings were never fetched

`GET /v1/{org}/configuration-findings` **defaults to `status=FAIL`** when no status is
supplied, which is what the sync did. So the table only ever held FAIL rows, and
`getCompliance` read `counts.getOrDefault("PASS", 0L)` — meaning **every per-framework
passing count was structurally zero**, and any pass rate derived from it was wrong.

The endpoint does serve PASS via `?status=PASS`, and it rejects multiple statuses in one
request (HTTP 400 for both `status=FAIL&status=PASS` and `status=FAIL,PASS`), so each status
needs its own walk. PASS volume is comparable to FAIL.

Fixed: the sync now walks FAIL then PASS, with reconciliation gated on *both* completing.
Two consequences worth noting:

- A check moving FAIL → PASS is now visible as PASS, instead of vanishing from the FAIL fetch and being archived as RESOLVED.
- The table now holds PASS rows, so **every query that presents findings as problems must filter `status = 'FAIL'`**. Three did not (`findByCloudAccountIdIn…` paged finders, and `findDistinctFindings`) and would have shown passing checks as failures. The unused finders were deleted and `findDistinctFindings` now filters; there is a standing note on the repository interface.

### 9.5 Concurrency hardening this forced

Making posture walk two statuses lengthened the full sync, which collided with the lease
design. `FULL_LEASE` was 45 minutes; a run that overruns it lets a second ASG instance
acquire the lease and start while the first is still working. Both would then call
`markResolvedNotSyncedSince(runStart)` against their own start times, and the later run
archives rows the earlier one had just written — the same mass-archiving failure as the
original pagination bug, from a different cause.

Two changes:

- `UpwindSyncLease.stillHeld(name)` re-checks ownership, and the guard is consulted immediately before **every** mark-resolved pass (vulnerabilities, posture, inventory, API endpoints). A run that has lost its lease skips archiving and logs why; stale rows are cleared by the next clean run. It fails **closed** on error, because skipping an archive is harmless while archiving without the lease is not — except where the lease table is absent entirely, in which case there is no second instance to collide with and the guard is permissive so single-instance deployments keep reconciling.
- The TTL moved to 90 minutes and became configurable via `upwind.sync.full-lease-minutes`. The guard, not the TTL, is the actual safety net.

---

## 10. Measured, not estimated (2026-08-04)

Every figure below is from a live end-to-end walk replicating the sync's own page sizes.

### 10.1 Full-sync API time: 73 minutes

| Domain | Items | Pages | Time |
|---|---|---|---|
| vulnerability-findings | 126,808 | 127 | 1,074s (17.9m) |
| configuration-findings FAIL | 41,000 | 42 | 554s (9.2m) |
| configuration-findings PASS | 39,000+ | 39 | 930s (15.5m) |
| inventory catalog (Xammer) | 1,433 | 8 | 13s |
| inventory catalog (Onfinance) | 800+ | 4 | 747s |
| api-security endpoints (Xammer) | 1,000+ | 10 | 918s |
| api-security endpoints (Onfinance) | 4,200 | 42 | 153s |
| **TOTAL** | | | **4,389s = 73.2 min** |

Consequences:

- The old 45-minute `FULL_LEASE` would have been **exceeded on every run**, so the overlapping-run archiving corruption described in §9.5 was not hypothetical. The ownership guard is load-bearing.
- 90 minutes is only just above the measured time. With `fixedDelay = 1h` the full sync effectively runs continuously. **The full-sync interval should be lengthened** (the webhook already delivers near-real-time changes; the full sync is a reconciliation safety net). This is not yet done.
- Volume is far higher than the ~19.4k previously assumed: ~207k finding rows upserted per run, each a `findByUpwindId` + `save`, i.e. ~400k DB round-trips. Batch upserts are worth considering.
- Several segments hit `Connection reset by peer` and read timeouts under sustained load. `exchangeWithRetry` covers transient failures; when retries are exhausted the exception propagates and reconciliation is correctly skipped, so a partial fetch cannot archive anything.

### 10.2 Console vs API counts were never a discrepancy

The Upwind console showed **6 findings / 4 CVEs** for Vulnerabilities while the API returned 126,808. Cause: **account scope**, not a view filter.

`GET /v1/{org}/vulnerability-findings?cloud-account-id=605134457560` is honoured and returns
exactly **6 findings** — matching the console precisely. The 126,808 is org-wide and almost
entirely Onfinance (598515435126).

The same query parameter is **ignored** on `configuration-findings` and `threat-detections`
(both return all accounts regardless), so only vulnerabilities can be scoped server-side.

### 10.3 Upwind's compliance score IS the finding pass rate

Counted CMMC in full: 5,289 FAIL + 13,360 PASS = 18,649 → **71.6% pass**.
Upwind reports `compliance_status.score = 71`. Same definition, so the pass counts unlocked
by the PASS fix reconcile with Upwind's own number.

### 10.4 Native query filters verified on PostgreSQL

`findDistinctFindings` and `findExposureFailings` cannot run on the H2 slice DB. Both were
executed verbatim against a throwaway PostgreSQL 15 instance seeded with FAIL/PASS/RESOLVED
rows plus a framework-fan-out duplicate:

- `findDistinctFindings` → 1 row (would be 3 without the status filter)
- `findExposureFailings` → 2 rows, both FAIL (would be 4 without it)

Fan-out duplicates from `findExposureFailings` are harmless: `getAttackSurface` accumulates
into `Set<String>` collections keyed by resource id.

### 10.5 Vulnerability payload is much richer than we persist

The v1 finding carries, unused today: `image.{name,tag,uri,digest,os_name,os_version}`,
`package.{name,version,type,framework,in_use,introduced_by_layers}`,
`resource.{cluster_id,cluster_name,namespace,resource_tags}`,
`resource.internet_exposure.ingress.{exposed,active_communication}`,
`vulnerability.{epss_severity,exploitable_functions_in_use,known_exploit_sources,
nvd_cvss_v2_*,nvd_cvss_v4_*,cve_first_seen_time,sbom_artifact_locations}`, and `source`.

Measured distribution over 12,000 findings: `package.in_use` 93%, internet-exposed 37.9%,
`exploitable` 24.7%, exposed+in_use+exploitable 7.4%, `exploitable_functions_in_use` 0.42%.
Persisting these would enable a runtime-exposure view equivalent to Upwind's saved views.
Not implemented.

---

## 11. Correction — against Upwind's full published API surface (2026-09-07)

The §8 audit used the v2 reference only. Upwind publishes OpenAPI operation definitions for **all
12 of their APIs — 84 operations** (mirrored at `apis.io/providers/upwind`, spec header states
operation content is verbatim from `docs.upwind.io/restapi/v1/introduction`). Reading the whole
surface corrects two claims above and identifies documented routes we never called.

**Two claims here are wrong.**

1. **§8.2 "Topology / graph / connections. No endpoint."** Half right. There is no *connections*
   endpoint and the Runtime Map still cannot be reproduced, but two graph routes are published and
   are called nowhere in our code:
   - `POST /v2/{org}/inventory/assets/search` — documented as **asset-relationship** search,
     optimised for graph traversal, accepting **nested conditions** (100 req/min/org). We had
     dismissed this path as the "thin flat search" and moved to the catalog search; the catalog
     search is the flat one, and this is the graph one.
   - `GET /v2/{org}/inventory/schema` and `/schema/{label-name}` — per asset label, a field list
     where a field's type is either primitive or **another asset label** (their example:
     `aws_ec2_instance.clusters : aws_eks_cluster`). Those typed fields are the edge catalogue.

   So the note in `XamSecureService.getAssetRelationships` that "nested conditions are rejected in
   every shape" was established against the catalog search, which does not document nesting.
   Inventory → **Graph** is therefore probably reachable; Inventory → **Runtime map** is not.

2. **§8.2 "there is no runtime event search."** The documented read route is
   `GET /v1/{org}/threat-events`, which we never tried — our 401s in §2 and §7.1 were against
   `/v1/events` and `/v2/{org}/events`, neither of which is a documented read path
   (`POST /v1/{org}/events` is ingest). It filters on `cloud-account-id`, `category`, `severity`,
   `upwind-asset-id` and first/last-seen time ranges; categories include `NETWORK_ACTIVITY` and
   `PROCESS_EXECUTION`, and fields include `source_ips`, `domain`, `bad_reputation_ips` and
   process-tree context.

**§2 should be re-read.** Four of its five 401s (`inventory/topology`, `inventory/connections`,
`inventory/graph`, `inventory/runtime`) appear in **no** published Upwind spec, and the fifth
(`events`) is a wrong path. As §7.1 already suspected, these were gateway 401s for routes that do
not exist. Asking Upwind to "authorize" them was asking for the wrong thing.

**Confirmed absent, not mis-pathed.** Nothing for Identities/CIEM, Images, Data/DSPM, AI or a
code-findings listing appears anywhere in the 84 operations. §3's 404s and §7.3's request for
canonical paths are settled: those modules are not API-exposed, so they are product asks.

**Also documented and unused:** `POST/PATCH/DELETE /v1/{org}/cloud-accounts` (relevant to the
partner-brief P1.2 onboarding ask — but still no GET list), full scope CRUD at
`/v2/{org}/access-management/scopes` (re-establish P1.1 against it),
`GET /v1/{org}/configuration-rules` (documented, so our empty array is an entitlement/data
question), `GET /v2/{org}/threats/stories` as a plain list, per-id detail routes for configuration
findings / vulnerability findings / detections / SBOM packages, and the whole
`threats/rule-definitions` and `threats/policies/{id}/rules` surface including bulk operations.

### 11.1 Live verification, same day

All of the above was then tested against this org with our production credential. Results in full:
`docs/2026-09-07-upwind-api-access-for-ui-parity.md` §4. Headlines:

- **Relationship traversal works.** `POST /v2/{org}/inventory/assets/search` returns 200 with nested
  conditions, is scopeable by `label`, and accepts multi-hop nesting. `GET /v2/{org}/inventory/schema`
  returned **320 asset labels, 224 with edge fields, 621 edge fields, 165 related types**. Both on the
  credential we already hold. The `getAssetRelationships` note about nested conditions being rejected
  was measured against the catalog search, which is the wrong route.
  - Caveat that keeps the Graph tab incomplete: the response contains **no nested values**, so it
    filters by relationship without projecting the edge. Ask #1 is now simply "return the matched
    related entity id".
  - Gotcha: `cloud_resource_id` is a full ARN in the catalog search and a bare id
    (`i-0613ecd23d0912db7`) in the relationship search, for the same asset. Joining them needs
    normalisation.
- **`GET /v1/{org}/threat-events` → 200**, honouring `cloud-account-id`. But `category=NETWORK_ACTIVITY`
  is a 400 and **0 of 200 sampled events** are network events (API_SECURITY 104, CLOUD_TRAIL 83,
  PROCESS 8, CLOUD_LOGS 4, FILE_EVENTS 1). Useful new depth for Threats; no help for the Runtime map.
- **`POST /v1/{org}/cloud-accounts` exists** — empty body returns 400 with `trace-id` and
  `{"provider":…,"name":…,"config":"must not be null"}`. Nothing was created. §5's "create → 404" is
  wrong. `GET` on the same collection is 401-no-trace-id: no list route.
- **Scopes already do per-tenant isolation.** 5 scopes returned, four of them filtered as
  `{"attribute":"cloud_account_id","operator":"in","values":[…]}` (OnFinance, Xammer, GSA ×2), and
  members carry per-scope role grants. Only *credential* binding is missing —
  `access-management/credentials|api-clients|service-accounts|api-keys` are all 401-no-trace-id.
  P1.1 becomes "extend an existing mechanism", not "build one".
- **The five §2 401s are non-routes.** All six probed Runtime-map paths returned 401 **without a
  `trace-id` header**, the same signature as routes that provably do not exist, while every genuine
  validation error on this credential carries one. `/v1/events` returns a plain 404. §7.1's suspicion
  is confirmed: **withdraw the "please authorize" framing on all five** — there is nothing to
  authorize.
- `GET /v1/{org}/configuration-rules` → **200 `[]`**, confirmed again. Entitlement, not path.
- Reachable and unused, all 200: `GET /v2/{org}/threats/stories` (list),
  `GET /v2/{org}/configurations/asset-examples/{kind}`, `POST /v2/{org}/threats/rule-definitions/search`.
