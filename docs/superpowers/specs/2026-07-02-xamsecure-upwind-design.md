# XamSecure — Upwind-Powered Security Section

**Date:** 2026-07-02
**Status:** Approved design, pending implementation plan

## Purpose

Xammer holds Upwind (CNAPP) licenses. XamSecure is a white-label layer: XamOps
customers see a native "XamSecure" section, while all security data comes from
Upwind's API behind the scenes. Customers never interact with (or see) Upwind.

## Key decisions

| Decision | Choice |
|---|---|
| Scope | Full mirror of Upwind console data, phased by API availability |
| Tenancy | Single Upwind org (Xammer's); per-customer filtering by cloud account ID against `CloudAccount` (awsAccountId / gcpProjectId / azureSubscriptionId) |
| Data flow | Runtime hybrid: near-real-time path for threat detections (60s incremental poll, webhook upgrade path), scheduled sync (4h) for scan-based domains |
| Placement | New "XamSecure" entry in the existing SecOps sidebar group |
| Credentials | Xammer's single Upwind OAuth2 client credential, held in server config only |

## Upwind API (verified via public integration docs)

- Base URL: `https://api.upwind.io` (US; EU/ME variants exist)
- Auth: OAuth2 client credentials — client ID + secret + organization ID
  (`org_…`), generated in Upwind console → Settings → Credentials
- Documented data domains: **vulnerability findings**, **configuration/posture
  findings** (severity + lookback filtering), **threat detections** (MITRE
  ATT&CK mapping, time-based filtering), **inventory assets** (AWS/Azure/GCP,
  with relationships to vulns/detections)
- Full API docs are behind console login (docs.upwind.io). API Security
  (endpoint catalog), Identity/CIEM, and compliance-framework endpoints are
  **unconfirmed** — verify once credentials exist.

### Open items to verify with Upwind console access
1. Whether webhook/notification destinations are available on the license
   (decides push vs 60s poll for detections).
2. Whether API Security and Identity data are exposed via the public API
   (gates the two Phase 2 tabs).
3. Whether posture findings carry compliance-framework mappings (gates whether
   the Compliance tab ships in Phase 1 or 2).
4. Rate limits and pagination specifics.

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

Tab architecture is a registry + one data hook per tab so Phase 2 tabs are
additive. All tabs are scoped by the account selector (customer's selected or
all accounts). Standard new-page checklist applies: route in `App.tsx`, Topbar
title, `apiClient` for all calls, Redis-backed caching hook
(`useXamSecureData`) for scan-based tabs; the Threats tab polls on a 60s
interval instead of long-lived cache.

Tables: filterable, severity chips in greys, row click opens a detail drawer
rendered from the finding's raw payload. "Last synced" timestamp visible per
tab; stale indicator when sync is failing.

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
  threats endpoint uncached or short-TTL

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
page with Overview/Threats/Vulnerabilities/Posture/Inventory tabs (+ Compliance
if framework mappings are in the posture payload).

**Phase 2 (after API verification):** API Security tab, Identity tab,
Compliance tab (if not in Phase 1), webhook ingestion replacing detection
polling.

## Out of scope (v1)
- Remediation actions pushed back to Upwind
- Historical trend charts (schema supports later via `first_seen`/`synced_at`)
- Per-customer Upwind orgs
- Customer-facing alerting/notifications from XamSecure findings
