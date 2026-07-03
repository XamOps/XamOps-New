# XamSecure — Upwind-Powered Security Section

**Date:** 2026-07-02
**Status:** Approved design, pending implementation plan

## Purpose

Xammer holds Upwind (CNAPP) licenses. XamSecure is a white-label layer: XamOps
customers see a native "XamSecure" section, while all security data comes from
Upwind's API behind the scenes. Customers never interact with (or see) Upwind.

**Parity goal: 1:1.** Every piece of data and every automation available in
the Upwind console (findings, detections, inventory, dashboards, onboarding,
sensor/agent deployment, response automations, notification rules, exceptions)
is to be replicated in XamSecure as-is. The only ceiling is Upwind's API
surface: XamSecure cannot show or do what the API does not expose. Features
found to be console-only during the parity audit (below) are flagged to the
product owner rather than silently dropped.

## Key decisions

| Decision | Choice |
|---|---|
| Scope | Full mirror of Upwind console data, phased by API availability |
| Tenancy | Single Upwind org (Xammer's); per-customer filtering by cloud account ID against `CloudAccount` (awsAccountId / gcpProjectId / azureSubscriptionId) |
| Data flow | Runtime hybrid: near-real-time path for threat detections (60s incremental poll, webhook upgrade path), scheduled sync (4h) for scan-based domains |
| Placement | New "XamSecure" entry in the existing SecOps sidebar group |
| Credentials | Xammer's single Upwind OAuth2 client credential, held in server config only |
| Onboarding | Customer self-service: connect cloud account to Upwind and deploy sensors/agents from within XamSecure |

## Upwind API — VERIFIED LIVE (2026-07-03, credential `api-xamsecure-sync`)

Credentials generated (Custom / Global Scope / Global Admin) and tested
against production. Client ID/secret live in gitignored `.env.local` at repo
root — **never commit them**.

- **Token:** `POST https://oauth.upwind.io/oauth/token`, form-encoded
  `grant_type=client_credentials&client_id=…&client_secret=…&audience=https://api.upwind.io`.
  Token TTL 24h. JWT carries `org_id` claim — org ID is derivable from the
  token itself.
- **Org ID:** `org_Zwq0e1xF9Au4AF0Q` (region `us`)
- **Verified working endpoints** (base `https://api.upwind.io/v1/organizations/{orgId}`):
  - `GET /threat-detections` → 200. Fields: `id`, `type`, `category`,
    `severity`, `status`, `title`, `description`,
    `resource{id,name,type,cloud_provider,cloud_account_id,internet_exposure}`,
    `first_seen_time`, `last_seen_time`, `occurrence_count`,
    `mitre_attacks[{tactic_id,tactic_name,technique_id,technique_name}]`
  - `GET /vulnerability-findings` → 206 paginated. Fields: `id`,
    `vulnerability{name,nvd_cve_id,nvd_cvss_v3_severity,nvd_cvss_v3_score,epss_score,exploitable,…}`,
    `image`, `package`, `remediation`, `source`, `first_seen_time`,
    `last_scan_time`, `resource{…,cloud_account_id,cloud_account_name,region,host_id}`
  - `GET /configuration-findings` → 200. Fields: `id`, `status` (FAIL/PASS),
    `severity`, `title`, `first_seen_time`, `last_seen_time`,
    `framework{id,status,version,title,cloud_provider}`,
    `check{id,title,remediation}`, `resource{…,cloud_account_id,risk_categories}`
    → **compliance frameworks are in the payload (CMMC observed), so the
    Compliance tab ships in Phase 1.**
- **Pagination:** RFC-5988 `Link` response headers (`rel="next"` with
  `page-token` cursor, `per-page` default 100). Follow `next` until absent.
- **Tenant filtering validated:** every domain carries
  `resource.cloud_account_id` (live AWS account `605134457560` observed).
- **Inventory:** `POST /inventory/search` exists but returns **403** with this
  credential despite `list:inventory-assets` in the token. Inventory tab
  deferred until permission resolved (check Custom role options in console or
  ask Upwind support).
- **Onboarding:** token includes `create/get/update/delete:byoc-credentials` —
  cloud-account onboarding ("bring your own cloud") appears API-supported;
  exact endpoint path not yet found (404 on obvious guesses — check
  authenticated docs).
- **No server-side time filter found** for detections (guessed params silently
  ignored) — the 60s poll fetches recent pages and de-dupes by `id`; exact
  filter param to be confirmed from authenticated docs.
- Credential types confirmed in console (Settings → Access management →
  Credentials): **Cloud account** (connect AWS/GCP/Azure), **Sensor** (Helm or
  Bash install), **Third-party Integration**, **API** (call the Upwind API
  service). Permission: Global Scope (Global Admin, default) or Custom.
- For the sync credential: use type **API**; prefer a Custom read-only scope if
  available, else Global Scope. Name credentials identifiably (e.g.
  `xamsecure-sync`).

### Open items (updated after live verification)
1. Whether webhook/notification destinations are available on the license
   (decides push vs 60s poll for detections).
2. Whether API Security and Identity data are exposed via the public API
   (gates the two Phase 2 tabs).
3. ~~Compliance-framework mappings~~ **RESOLVED: present in
   configuration-findings payload → Compliance tab in Phase 1.**
4. Rate limits; detections server-side time-filter parameter name.
5. Inventory access: `POST /inventory/search` 403s with current credential —
   fix permission (console Custom roles or Upwind support), then add the
   Inventory tab.
6. BYOC/sensor credential endpoints' exact paths (token has the permissions;
   paths behind authenticated docs).

### Parity audit (first task once credentials exist)

Walk the authenticated API docs (docs.upwind.io) and the console side by side;
produce a feature-parity matrix: every console page/automation → its API
endpoint(s) → target XamSecure tab/feature → phase. Known console areas to
cover beyond the four documented domains: dashboards, Kubernetes/containers
views, image/registry scanning, API Security (endpoint catalog, API threats),
Identity/CIEM, compliance frameworks, sensors management, response
automations, notification/integration rules, ignore/exception rules, events
timeline. Console-only features (no API) are listed with a proposed
workaround or explicitly deferred by the product owner. The matrix becomes
the Phase 2+ backlog.

## UI

New sidebar item **XamSecure** under SecOps (shield SVG icon, black/white/grey
theme, no emojis). Routes to `XamSecurePage.tsx` with Upwind-mirroring tabs:

| Tab | Content | Phase |
|---|---|---|
| Overview | Risk summary cards (threats by severity, vuln counts, posture count, asset count), latest-detections strip | 1 |
| Threats | Runtime detections feed: time, severity, MITRE tactic, resource, status; newest first; 60s auto-refresh with live indicator; detail drawer | 1 |
| Vulnerabilities | CVE findings: severity, exploitability, fix availability, affected resource/image; severity + fix-available filters | 1 |
| Posture | Misconfiguration findings: severity, rule name, resource, provider | 1 |
| Compliance | Posture findings grouped by framework (CIS, ISO 27001, …) | 1 if API supports, else 2 |
| Inventory | Monitored assets with linked vuln/detection counts | 1 |
| API Security | Endpoint catalog + API threats | 2 (pending API confirmation) |
| Identity | CIEM / identity risks | 2 (pending API confirmation) |
| Onboarding | Self-service: connect cloud account, deploy sensors, coverage status | 1 |

Tab architecture is a registry + one data hook per tab so Phase 2 tabs are
additive. All tabs are scoped by the account selector (customer's selected or
all accounts). Standard new-page checklist applies: route in `App.tsx`, Topbar
title, `apiClient` for all calls, Redis-backed caching hook
(`useXamSecureData`) for scan-based tabs; the Threats tab polls on a 60s
interval instead of long-lived cache.

Tables: filterable, severity chips in greys, row click opens a detail drawer
rendered from the finding's raw payload. "Last synced" timestamp visible per
tab; stale indicator when sync is failing.

### Onboarding tab (customer self-service)

Mirrors the Upwind onboarding flow, white-labeled in the XamOps UI:

1. **Connect cloud account** — customer picks one of their XamOps
   `CloudAccount`s; XamSecure provisions the Upwind connection (via API if
   supported) and presents the required cloud-side step (CloudFormation stack
   for AWS — same pattern as XamOps's existing `cloudformation/` onboarding —
   or the gcloud/az script Upwind provides). Connection status shown per
   account: Not connected / Pending / Connected.
2. **Deploy sensors** — per connected account/cluster, XamSecure obtains a
   sensor credential and renders the Helm or Bash install command with a copy
   button. Post-install, sensor/coverage status is shown per cluster.
3. **Coverage view** — which accounts/clusters are connected, sensor health,
   what's unmonitored.

**Fallback if credential creation isn't API-exposed:** a Xammer admin
pre-generates Cloud account / Sensor credentials in the Upwind console and
registers them in an admin-side XamSecure panel; the customer-facing flow is
unchanged (they still click Connect / copy install commands), only the
credential provisioning is manual behind the scenes.

**White-label limitation (accepted):** install commands, Helm chart names, and
cloud IAM roles are Upwind's own artifacts — the Upwind name is visible to
customers at the infrastructure level even though the XamOps UI never mentions
it.

## Backend (`xamops-service`)

### UpwindApiClient
- OAuth2 client-credentials token fetch, in-memory cache, refresh on expiry
- Paginated GETs against `api.upwind.io`
- Config via properties/env: `UPWIND_CLIENT_ID`, `UPWIND_CLIENT_SECRET`,
  `UPWIND_ORG_ID`, `UPWIND_BASE_URL`. Never stored in DB or exposed to
  frontend.

### Data model (Flyway migration)
Four tables: `upwind_vulnerability_finding`, `upwind_threat_detection`,
`upwind_posture_finding`, `upwind_inventory_asset`. Common columns:

- `upwind_id` (unique — upsert key)
- `cloud_provider`, `cloud_account_id` (tenant filter column, indexed)
- resource name/ID, `severity`, `status`
- `first_seen`, `last_seen`, `synced_at`
- `raw_payload` JSONB — full Upwind object, so detail drawers and future
  columns need no schema change

### Sync — UpwindSyncService + UpwindSyncScheduler
- **Detections:** every 60s, incremental via API time filter + stored cursor.
  This is the runtime path.
- **Vulns / posture / inventory:** every 4h (configurable), upsert by
  `upwind_id`; records absent from the latest sync are marked resolved.
- **Webhook upgrade path:** `POST /api/xamsecure/webhook/upwind`
  (shared-secret validated) replaces the 60s poll if the license includes
  webhook destinations. Designed in, built only when confirmed.

### Read API — XamSecureController
`/api/xamsecure/summary | threats | vulnerabilities | posture | compliance |
inventory`

- Every endpoint resolves the authenticated customer's `CloudAccount` IDs and
  filters `WHERE cloud_account_id IN (…)`. Org-wide data never leaves the
  backend unfiltered.
- Server-side pagination + severity/status filters
- Redis caching per account for scan-based endpoints (`RedisCacheService`);
  threats endpoint short-TTL (≤60s)

### Onboarding API — XamSecureOnboardingController
`/api/xamsecure/onboarding/…`

- `POST /connect` — provision the Upwind connection for one of the customer's
  own `CloudAccount`s (ownership enforced server-side); returns the cloud-side
  setup artifact (CloudFormation launch URL / script)
- `GET /status` — connection + sensor/coverage status per account
- `POST /sensor-credential` — obtain sensor credential and rendered Helm/Bash
  install command for a connected account/cluster
- New table `upwind_account_connection`: `cloud_account_id`, status
  (NOT_CONNECTED / PENDING / CONNECTED), sensor coverage summary, timestamps.
  Sensor credentials (if stored at all in the fallback flow) are encrypted at
  rest and only ever rendered inside install commands for the owning customer.

### Error handling
- Token/sync failures: log, retry with backoff, admin alert on persistent
  failure
- Upwind unreachable: serve last-synced data with stale indicator; never a
  customer-facing error page
- Per-domain sync state (last success, last error) persisted for the "last
  synced" UI and ops debugging

### Testing
- Unit: token lifecycle (mocked HTTP), upsert/mark-resolved logic, cursor
  handling
- **Tenant isolation (non-negotiable):** controller tests proving customer A
  cannot receive findings for accounts they don't own
- Frontend: vitest for hook + tab rendering with mocked apiClient

## Phasing

**Phase 1:** UpwindApiClient, migrations, sync scheduler, read API, sidebar +
page with Overview/Threats/Vulnerabilities/Posture/Inventory/Onboarding tabs
(+ Compliance if framework mappings are in the posture payload). Onboarding
ships with the console-assisted fallback if credential creation isn't
API-exposed.

**Phase 2 (after parity audit):** API Security tab, Identity tab, Compliance
tab (if not in Phase 1), webhook ingestion replacing detection polling, fully
automated credential provisioning for onboarding.

**Phase 3+ (parity backlog from the audit matrix):** remaining console
features and automations — response automations, notification rules,
ignore/exception management, Kubernetes/container views, events timeline, and
anything else the audit surfaces — until 1:1 parity is reached for everything
the API exposes.

## Out of scope for Phase 1 (moves to parity backlog, not dropped)
- Remediation/response actions pushed back to Upwind
- Historical trend charts (schema supports later via `first_seen`/`synced_at`)
- Customer-facing alerting/notifications from XamSecure findings

## Permanently out of scope
- Per-customer Upwind orgs (single-org, account-ID-filtered model chosen)
- Hiding the Upwind name inside install commands / IAM roles (accepted
  white-label limitation)
