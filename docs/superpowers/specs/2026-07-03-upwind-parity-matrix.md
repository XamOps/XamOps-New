# Upwind → XamSecure Parity Matrix (Phase 2+ backlog)

**Date:** 2026-07-03. Source: full public API docs extracted from docs.upwind.io
(86 REST API pages; sitemap + webpack chunks). Endpoints marked ✅ were tested
live against production with the `api-xamsecure-sync` credential.

## Auth / transport facts (supersede earlier guesses)

- Token: `POST https://oauth.upwind.io/oauth/token` (docs also cite
  `auth.upwind.io`), form-encoded client credentials. Audience must match the
  regional API base (`https://api.upwind.io` for US). TTL 24h.
- **WAF requires a real User-Agent on v2 POST endpoints** — default curl/Java
  agents get nginx 403 HTML. Always send e.g. `User-Agent: XamOps/1.0`.
- v1 list endpoints support query params: `severity=CRITICAL`, `page`,
  `per-page` (≤ 100 implied), pagination via `Link` headers.
- v2 search endpoints: `POST …/search?limit=N&sort=field:desc` with body
  `{"conditions":[{"field":"…","operator":"eq|gte|exists","value":[…]}]}`;
  cursor pagination via `metadata.next_cursor`. **Values are case-sensitive**
  (`"AWS"` matches, `"aws"` doesn't, for inventory cloud_provider).

## Matrix

| Upwind console area | API (base `/v1|/v2/organizations/{org}`) | Verified | XamSecure target |
|---|---|---|---|
| Threat detections | `GET /v1/threat-detections` (+`severity`, paging); `GET/PATCH /v1/threat-detections/{id}` (update = status changes) | ✅ list | Phase 1 ✅ shipped; Phase 2: detection status actions (`update-threat-detection`) |
| Threat stories | `POST /v2/threats/stories/search?limit&sort=create_time:desc` + conditions | — | Phase 2: Stories view; `sort=create_time:desc` + conditions likely enables **incremental polling** (fixes the "no time filter" gap) |
| Threat policies | `GET /v1/threat-policies`, `PATCH /v1/threat-policies/{id}` | — | Phase 3: policy management UI |
| Vulnerabilities | `GET /v1/vulnerability-findings`, `GET …/{id}` | ✅ | Phase 1 ✅ shipped |
| SBOM | `GET /v1/sbom-packages`, `GET …/{package-path}` | — | Phase 3: package/SBOM explorer |
| Posture / configurations | `GET /v1/configuration-findings`, `GET …/{id}`; v2 `POST /configurations/findings/search` | ✅ v1 | Phase 1 ✅ shipped |
| Compliance frameworks | `GET /v1/configuration-frameworks` (+POST/PATCH); v2 `frameworks/search`, create/update framework | — | Phase 1 groups by payload framework; Phase 2: framework CRUD (custom frameworks) |
| Custom rules | `GET/POST /v1/configurations/custom-rules`, `PATCH/DELETE …/{rule-id}` | — | Phase 3: custom posture rules UI |
| **Inventory** | `POST /v2/inventory/assets/search` (conditions body); `GET /v2/inventory/assets/{id}`; `GET /v2/inventory/schema/{label}`; catalog variants | ✅ **works** — earlier 403 was wrong path + missing User-Agent, NOT permissions | **Phase 2 (unblocked): Inventory tab** — fields: id, asset_category/sub_category, cloud_provider, cloud_resource_id, name, region, label; filter per tenant via `cloud_account_id` condition |
| API Security | `GET /v1/apisecurity-endpoints` (API catalog) | — | Phase 2: API Security tab |
| Events | `GET /v1/events`, `GET /v1/events` list; shift-left search | — | Phase 2: events timeline |
| **Cloud account onboarding** | `GET/POST /v1/cloud-accounts`, `PATCH/DELETE /v1/cloud-accounts/{account-id}` | — | **Phase 2: fully automated self-service onboarding** (replaces admin-registered fallback) |
| **Webhooks (real-time)** | `GET/POST /v1/integration-webhooks`, `PATCH/DELETE …/{webhook-id}` | — | **Phase 2: create webhook → XamOps ingest endpoint; retire/reduce 60s poll** |
| Workflows (automations) | `POST /v1/workflows` (create), `GET` list/by-id, `PATCH/DELETE` | — | Phase 3: response automation management |
| Identity (CIEM) | v2 `access-management`: scopes/roles/groups/members CRUD + search | — | Phase 3: identity tab (note: this is Upwind org access mgmt; CIEM cloud-identity data may be under inventory/stories — confirm) |
| Shift-left / CI scanning | `GET /v1/shiftleft/results/{fingerprint}`, search shift-left events | — | Phase 3+ (only if XamOps adds CI-scan white-labeling) |

## Corrections to Phase 1 assumptions

1. **Inventory 403 root cause**: wrong path (`/inventory/search` → correct
   `/v2/inventory/assets/search`) + WAF blocking default curl UA. The
   credential's permissions were sufficient all along.
2. **Detections incremental sync**: v1 has `severity`/`page`/`per-page` params
   (no time filter), but v2 stories search supports `sort=create_time:desc` +
   conditions — evaluate v2 for the runtime feed in Phase 2.
3. **Onboarding automation is real**: cloud-accounts CRUD exists; the
   console-assisted fallback shipped in Phase 1 can be upgraded.
4. UpwindApiClient must send an explicit `User-Agent` header (done).

## Phase 2 shortlist (recommended order)

1. Inventory tab (endpoint verified; add v2 search support + UA header to client)
2. Webhook integration → real-time detections push
3. Automated cloud-account onboarding (`create-cloud-account`)
4. API Security tab + Events timeline
5. Detection status actions (acknowledge/resolve from XamSecure)

## Phase 2 endpoint verification (2026-07-05, live)

All calls need `User-Agent: XamOps/1.0` (WAF). Base `/v1|/v2/organizations/{org}`.

- **Inventory** `POST /v2/inventory/assets/search` ✅ — body needs `conditions`
  (case-sensitive values). Tenant filter: add a `cloud_account_id eq [id]`
  condition ALONGSIDE another condition (e.g. `label` or `cloud_provider`);
  `cloud_account_id` alone → 500 (server bug), so always pair it. Item fields:
  `id, asset_category, asset_sub_category, cloud_provider, cloud_account_id,
  cloud_resource_id, name, region, label` (+ type-specific fields). Cursor
  paging via `metadata.next_cursor`. Schema per label at
  `GET /v2/inventory/schema/{label}` (confirms `cloud_account_id` queryable).
  NOTE: `GET /v2/inventory/assets/{id}` returned 401 in testing — use search,
  not by-id, for detail (or re-verify auth).
- **API Security** `GET /v1/apisecurity-endpoints` ✅ 206. Supports query
  params incl. **`cloud-account-id`** (verified — returns filtered rows),
  `method`, `has-vulnerability`, `has-sensitive-data`, `has-internet-ingress`,
  `cloud-provider`, `resource-type`, `domain`, `cluster-id`, `namespace`,
  `per-page`, `page-token`. Item fields: `id, method, uri, resource_id,
  first_seen_time, last_seen_time, risk_overview{internet_exposure...}`. Items
  carry `resource_id` not `cloud_account_id` — **tenant-filter via the
  `cloud-account-id` query param**, not post-filtering.
- **Webhooks** `GET/POST/PATCH/DELETE /v1/integration-webhooks` ✅ (GET 200,
  empty list). Create body schema: `{name, vendor, config:{authentication:
  {type: BEARER|API_KEY, token|api_key, headers[]}, url, ...}}`; vendor enum
  `DATADOG|SPLUNK|MSTEAMS|TINES|CUSTOM`. For XamOps ingest use `vendor=CUSTOM`
  with our webhook URL + a shared secret/bearer.
- **Threat policies** `GET /v1/threat-policies` ✅ 200 (real policies:
  `id, category, scope, display_name, open_issues, managed...`).
- **Detection update** `PATCH /v1/threat-detections/{id}` ✅ (docs). Mutable
  field: `status` (enum incl. `ARCHIVED`). This is the acknowledge/resolve
  action.
- **Events** `GET /v1/events` → **401** with current credential (needs a
  permission/scope the sync creds lack). Defer Events timeline or use v2
  `POST /v2/events` search; flag credential scope for Upwind.
- **Cloud accounts** `GET /v1/cloud-accounts` → **401** (same — creds lack the
  cloud-account scope). Automated onboarding (`POST /v1/cloud-accounts`) is
  **blocked on a credential scope**; keep Phase 1's admin-registered fallback
  until scope is granted. Verify in Upwind console (Custom role → cloud-account
  read/write permissions) before building.

### Impact on Phase 2 plan
- Inventory tab: build now (verified).
- API Security tab: build now (verified, native tenant filter).
- Webhooks real-time: build now (verified); ingest controller + CUSTOM webhook.
- Detection actions: build now (PATCH verified via docs).
- Events timeline + automated cloud-account onboarding: **gated on credential
  scope (401)** — carry into the plan as "verify scope first, else defer".
