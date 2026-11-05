# XamOps Observability 1.0 ("XamPulse") — Planning Index

Status: design complete for Phase 2 (planning documents only). No production code was written and no existing source file was edited. All files live in `/Users/admin/Desktop/xam-latest/XamOps-New/.agents/tasks/observability-v1/`.

## 1. Executive summary

XamPulse is a new, separate observability product inside XamOps. It sits beside the existing Observability (Beta) page and does not read, reuse or change it. It has its own sidebar section, routes (`/observability-v1/*`), APIs (`/api/obs/v1/*`), tables (`obs_*`), Java package (`com.xammer.cloud.observability.*`) and frontend folder (`frontend-app/src/features/observability-v1/`).

What the customer gets:

- **One-step onboarding.** Start from an AWS account already connected to XamOps. XamPulse discovers EC2 (plus ECS/EKS clusters), runs pre-flight checks, and installs an agent either with one click (SSM) or with a copy-paste command that needs no new AWS permissions. A live rail goes from "Waiting for first data" to "Connected". A Trust panel shows exactly what is installed, what is collected, the IAM policy JSON, the network footprint (outbound 443 only), and how to uninstall.
- **Their own storage.** Logs and 1-minute metric rollups are archived as ZSTD Parquet (or JSONL) in the customer's S3 bucket (existing or created for them) at `s3://bucket/xamops-logs/{account}/{region}/{host-or-service}/{type}/yyyy/mm/dd/hh/`, with SSE-KMS, block public access, lifecycle tiering and least-privilege roles that cannot delete. A XamOps-managed bucket is the zero-setup fallback, with a migrate action.
- **Fast search plus archive search.** Recent data (default 14 days) is in VictoriaLogs and VictoriaMetrics; older data is searched from the customer bucket via DuckDB, with a scan plan shown before running.
- **Deep host and container metrics**, log explorer with live tail, patterns, saved queries, metric-to-log correlation in one click, alerts (threshold, anomaly, log pattern, forecast, absence, composite, SLO burn), incidents with dedup and escalation, service auto-discovery and map, fleet and agent management, cost visibility and controls, and opt-in AI (summaries, natural-language queries, RCA grounded in evidence).
- **A distinctive, accessible UI** ("Signal on ink"): dark and light themes, designed empty/loading/error/waiting states, global time picker with compare-to-previous, drill-down from every chart, command palette and keyboard shortcuts.

Stack (locked on approval): Go supervisor plus custom OpenTelemetry Collector on hosts; OTLP/HTTP over 443; nginx `auth_request` ingest edge; OTel gateway; Kafka; Go `hotwriter`/`archiver`/`compactor`; VictoriaLogs; VictoriaMetrics; DuckDB for archive queries; Spring Boot (existing `xamops-service`) for control plane and query API; PostgreSQL with Flyway; React 19 with react-query, uPlot, recharts (shared CloudK8s primitives) and cytoscape. No AGPL components.

## 2. Document index

| File | Contents |
|---|---|
| `00-inspection-notes.md` | Verified repo facts and reusable shared pieces (input from Phase 1) |
| `01-research-report.md` | Research, stack recommendation, agent decision (a short design-phase addendum, section 12, was appended) |
| `02-architecture.md` | Components, diagram, ingestion, hot and cold stores, query plane, alerting, tenancy invariants, scale and cost, failure modes |
| `03-onboarding-and-s3-design.md` | Onboarding flow and states, IAM roles and policies (JSON), pre-flight checks, install methods (SSM, copy-paste, ASG, IaC), bucket layout, lifecycle, validation, fallback, uninstall, trust manifest |
| `04-backend-plan.md` | File-by-file Spring plan under `com.xammer.cloud.observability.*`, migrations, endpoints per controller, security chain, config properties, nginx, error handling and validation tables, invariants, tests |
| `05-frontend-plan.md` | Stack decisions, shared-file edits, routes, every file, hooks, state, types, API client, charts, theming tokens, accessibility rules |
| `06-data-model-and-api-contract.md` | Exact DDL (41 tables), telemetry schema, full endpoint catalogue, JSON examples, MetricQuery, error codes, XLQ grammar, agent config, SSE contract, permissions, machine endpoints |
| `07-ui-ux-spec.md` | Visual identity, information architecture, ASCII wireframes with all states for each key screen, interaction spec, microcopy, UX review checklist |
| `08-roadmap-risks-tests.md` | MVP / 1.0 / 2.0 phases with exit gates, coexistence plan, risk register, test plan, decisions |
| [`09-build-order.md`](09-build-order.md) | Ordered, dependency-aware build sequence per milestone (MVP, 1.0, 2.0): files, parallel worktree tracks, verification commands, Definition of Done |

## 3. Key design decisions (short list)

1. New supervisor in the existing `xamops-agent` repo plus a separate custom OTel Collector process; REST poll for config (no OpAMP yet).
2. Machine endpoints use a dedicated header and a dedicated `@Order(0)` security chain, so `SecurityConfig.java` is not edited and the impersonation chain cannot capture agent calls.
3. Tenancy key is `client_id` + `cloud_account_id` (PK), mapped to VictoriaLogs `(AccountID, ProjectID)`; identity comes from the token at ingest and the session at query.
4. Live tail via SSE (not the shared STOMP broker).
5. Archive writer role cannot delete; compaction uses tags and lifecycle expiry.
6. Alert evaluator in Spring with `FOR UPDATE SKIP LOCKED` claims.
7. Feature-local react-query client without persistence, so log data never reaches browser storage.
8. Two hazards in shared code are handled explicitly: the sidebar's `startsWith('/observability')` would also match `/observability-v1`, and users with no assigned tabs see every module. Both are covered in 05 section 2 and 08 section 2.

## 4. Open decisions needing your input

| ID | Question | My default if you do not answer |
|---|---|---|
| D1 | Product name in the UI (XamPulse is a working title) | XamPulse |
| D2 | Who hosts and pays for Kafka (MSK), VictoriaLogs, VictoriaMetrics, gateway collectors and the ingest domain, and in which region. No Observability infra exists in the repo and no Kafka broker was confirmed. **This gates the start of the MVP.** | No default (needs an infra owner) |
| D3 | Fast-search window | 14 days (disk cost scales linearly; older logs stay searchable via your S3) |
| D4 | Pricing / entitlement model and default host cap | Per-host with fair-use GB, 500-host cap per tenant |
| D5 | Windows Server in 1.0 GA or later | GA (adds about 3 to 4 weeks) |
| D6 | What can standard users (`ROLE_XAMOPS`) do by default? | Read logs, run archive searches and complete the whole Connect flow; exports, settings, audit and AI are admin-only; `ROLE_USER` is view-only (06 section 11), all changeable by `obs.rbac.defaults.<ROLE>` properties |
| D7 | May AI features send short, redacted excerpts to the platform LLM gateway? | Off until each tenant opts in |
| D8 | Managed fallback bucket: region, owning account, and whether it is the default for new tenants | Managed by default, so connecting needs zero AWS steps |
| D12 | What happens to the Beta page after XamPulse GA | Keep untouched; revisit after GA |

Decided defaults you may veto: D9 (agent artifacts signed with a KMS-backed key, SHA-256 manifest in MVP), D10 (automatic fallback to managed storage after 24 h of failed BYO writes, on by default), D11 (email via existing `EmailService` if it fits), D13 (single-region ingest in 1.0). Details are in 08 section 5.

## 5. Things I could not verify

- Whether a production Kafka broker or any VictoriaMetrics/VictoriaLogs infrastructure exists (not in the repo).
- The exact referenced table and column name of the tenant table for the `client_id` foreign keys (written as `client(id)` from `CloudAccount.client`); the migration author must confirm.
- Whether `AccountContext`'s `Account` type already carries the numeric PK (the plan adds `GET /api/obs/v1/me/accounts` so the UI never depends on it).
- Existing `EmailService`, `SnsNotificationService` and the platform LLM gateway APIs were not inspected (flagged as implementation-time checks D7, D11).
- Frontend env-flag convention for `VITE_OBS_V1_ENABLED` and the demo-mode router behaviour for unknown `/api/obs` calls (implementation-time checks, noted in 05 and 08).
- Library versions (uPlot, @tanstack/react-virtual, duckdb_jdbc, collector build) are to be pinned at implementation time; the research report's version numbers date from 2026-10-30.
- Accessibility: the plan encodes WCAG-oriented rules, but conformance needs manual assistive-technology testing and expert review.

## 6. Verification
Iteration 2 (response to `design-review.json` / `design-review.md`, verdict CHANGES_REQUESTED, 0 HIGH, 9 MEDIUM, 4 NIT). All 13 findings were addressed; none required changing a user-stated requirement. Responses are listed at the end of 02, 03, 04, 05, 06 and 08.
1. **Findings fixed:** 1 (Reader BucketMeta split), 2 (UntagOnlyOurMarker DeleteTags statement), 3 (LifecycleRuleGenerator, retention 7 to 3650, tests), 4 (KMS ARN resolution, ViaService, SSE headers, SSE-S3 behaviour, `encryption_mode`), 5 (single RBAC table, all `obs.rbac.defaults.<ROLE>` properties, Connect-flow RBAC test), 6 and 11 (XLQ tokenizer, reserved words, precedence, single `limit` in the body, `bucket` only as group item, 18 golden tests), 7 (full tail contract, fetch-streaming client), 8 (multi-account fan-out with partial results), 9 (`ObsMetricQueryService`, `DataDeletionJob` + `obs_deletion_request`, `xamops_service_internal` upstream and private connector), 10 (verify-at-implementation notes on version pins), 12 (424/429 wording), 13 (`fa-heart-pulse` iconMap entry in the shared-file edit list). Nothing was backlogged or ignored.
2. **Scripted consistency check after the edits:** 41 `CREATE TABLE obs_*` in 06, every one has an entity in 04 (new: `ObsDeletionRequest`); no `obs_*` name in 02 to 08 is missing from the DDL (only extras: trigger function `obs_audit_immutable`, script `obs_install.sh`); every `/api/obs/v1/...` path in 02 to 05 appears in 06; all JSON blocks in 02 to 08 parse (03 policy blocks parse after substituting placeholders); no unresolved references to `ObsDataDeletionJob` or `LimitStage`/`BucketStage` remain.
3. **Latest migration on disk re-checked:** `V20261024_39__account_cur_stack_identity.sql`; `V20261025_1..6` still sort after it and the migration set is unchanged (the new table lives in `_6`).
4. **Shared pieces:** paths verified in iteration 1 (see 00-inspection-notes.md) were not changed; `iconMap.ts` absence of a heart/pulse entry was taken from the reviewer's grep and from 05 section 3.3, not re-grepped.
5. **Not verified:** IAM policies, lifecycle JSON, the XLQ grammar and DDL were not executed against AWS, a parser or a database; third-party version pins remain unverifiable from the repo; infra existence (D2) is still an open decision.
6. **Deliverables present and non-trivial:** 00-README.md, 02-architecture.md, 03-onboarding-and-s3-design.md, 04-backend-plan.md, 05-frontend-plan.md, 06-data-model-and-api-contract.md, 07-ui-ux-spec.md, 08-roadmap-risks-tests.md (`wc -c` at the end of iteration 2: README 11 KB, 02 29 KB, 03 36 KB, 04 51 KB, 05 36 KB, 06 78 KB, 07 58 KB, 08 28 KB).
7. **Not done by design:** no code, SQL files, CloudFormation templates or configs were created in the repo; only files under `.agents/tasks/observability-v1/` were written.

### Iteration 3 (design-review.json: 1 MEDIUM, 3 NIT)
- Finding 1 fixed: one internal-API auth rule (404 on public port or non-allowed CIDR; 401 on private port with bad key; nginx denies on any non-2xx from `/_obs_auth`) written in 04 (filter row), 06 (error section) and 08 (test 10e).
- Finding 2: OpenObserve name removed from 02. It remains only in 01 as a licence rejection (01 not edited).
- Finding 3: version pins stay marked verify-at-implementation.
- Finding 4: 04 section 5 now notes 8081 clash check; grep showed `server.port=8080` in application.properties and `management.server.port=9090` in application-uat.properties only.
- Files re-checked present with `ls`: 00 to 08 all exist and are non-trivial (sizes unchanged order of magnitude). Nothing outside `.agents/tasks/observability-v1/` was written.
