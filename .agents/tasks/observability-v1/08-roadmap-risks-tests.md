# 08 — Roadmap, Risks, Tests, Coexistence, Open Decisions

## 1. Phases

Estimates assume a team of about 4 engineers (2 backend/Go, 1 backend/infra, 2 frontend counting part-time design) and are planning figures, not commitments. Each phase ends with an exit gate (section 1.4).

### 1.1 Phase MVP — "Connect, see, keep" (pilot, ~12 weeks)

Goal: one design-partner customer connects Linux EC2 servers in one step and sees logs and host metrics within 5 minutes, with data safely archived.

| Area | In scope |
|---|---|
| Agent | `xamops-obs-agent` + `xamops-otelcol` for Linux x86_64/arm64; enroll/register, heartbeat, config ETag poll, redaction built-ins, persistent queue, resource limits, self-uninstall; manual update only |
| Onboarding | Connect page: discovery (inventory + live fallback), pre-flight L1+L2, copy-paste install, one-click SSM with permission stack, trust panel, live rail, uninstall/rollback |
| Pipeline | nginx ingest edge + auth_request, gateway collector, Kafka, `hotwriter`, VictoriaLogs, VictoriaMetrics (single, enforced labels), `archiver` (Parquet) to **managed** bucket and **BYO** bucket (validation, roles, lifecycle JSON) |
| Logs | Explorer (search, facets, histogram, columns, drawer, context), live tail (SSE), saved queries, XLQ parser + LogsQL compiler, level detection, multiline/JSON parsing, system/auth/journald/nginx/apache/Docker JSON logs, export (hot, CSV/NDJSON up to 100k rows) |
| Metrics | Host CPU/memory/disk/network/load/processes/filesystem/inodes families, metric explorer, host detail charts, correlation button (metric → logs deep link) |
| Alerts | Threshold, absence, log-pattern; Slack, email, webhook; incidents with ack/resolve; mute windows (one-off) |
| Overview, Hosts, Agents (read-only), Storage (basic), Settings (tokens, redaction, access, audit) | Basic versions |
| Platform | RBAC (`obs.*`), audit log, per-host rate limits, cost meters (usage page), dark/light, command palette, keyboard shortcuts, designed states for MVP screens |
| Not in MVP | Archive search UI, patterns, anomaly/forecast/AI, SLO, containers page depth, service map, dashboards builder, rollouts, Windows |

### 1.2 Phase 1.0 GA (~+14 weeks after MVP)

| Area | Added |
|---|---|
| Logs | Archive search (DuckDB cold worker, plan preview, jobs, exports to 1M rows), patterns (Drain miner), compare-to-previous, saved views, NL query, AI summaries, stats/top/bucket stages |
| Metrics | Containers (Docker/containerd/ECS-on-EC2, EKS node-level), custom metrics (Prometheus scrape, OTLP loopback), dashboards (CRUD + suggested), RED/USE views, process drill-down |
| Smart | Anomaly detection with baselines, forecasts (disk full, memory leak), noise advisor, rightsizing hints, service auto-discovery + service map |
| Alerts | Anomaly, forecast, composite, SLO + burn alerts; Teams, PagerDuty, SNS; escalation; dedup/flap suppression; recurring mute windows; rule preview/backtest; AI RCA |
| Fleet | Staged rollouts with canary and automatic halt, remote config profiles, ASG integration (group tokens, State Manager association), Terraform/CFN/launch-template/Packer snippets, token auto-rotation, instance-identity verification, update signing (cosign/minisign) |
| Storage | Migrate managed ↔ BYO, Athena DDL, tiers and cost view, safe-fallback logic, dedicated-bucket CFN stack |
| Platform | VictoriaMetrics cluster, Windows Server (Event Log, hostmetrics, PowerShell installer), cost controls (caps, sampling), UX checklist pass, WCAG manual audit, load test at 2,000 hosts |

### 1.3 Phase 2.0 (~6 months after GA; direction, not scope-locked)

Traces/APM (OTLP traces, VictoriaTraces or Jaeger v2, RED from traces, trace-log-metric pivots), eBPF auto-instrumentation (Beyla/OBI, opt-in), EKS workload depth and Fargate sidecar, log-derived metrics, database slow-log parsers, continuous profiling (Parca/OTel eBPF profiler, Apache-2.0), synthetic checks, multi-region ingest and VictoriaLogs cluster, per-tenant hot retention (evaluate VictoriaLogs Enterprise or ClickHouse), OpAMP for remote config, direct-to-S3 "private mode" for regulated tenants, status pages, deploy/change-event overlays (CloudTrail), cross-account buckets.

### 1.4 Exit gates

| Phase | Gate (all must pass) |
|---|---|
| MVP | (1) Time-to-first-data median ≤ 5 min over 10 fresh installs on AL2023/Ubuntu 22.04 x86+arm; (2) cross-tenant denial suite green; (3) 7-day soak: 20 hosts, zero data loss reported, agent CPU < 3% avg and RSS < 300 MiB; (4) BYO S3 validation catches each seeded misconfiguration (public, wrong role, KMS deny); (5) uninstall leaves no files/units/listeners (scripted check); (6) design-rule test passes with the feature added to `GUARDED_PATHS`; (7) UX checklist items 1–7 pass |
| 1.0 | Load test: 2,000 simulated hosts × 1 MB/min logs + 250 series, p95 hot query < 1.5 s, live tail latency p95 < 3 s, archiver lag p95 < 10 min; chaos suite passes; pen-test findings of High/Critical fixed; manual a11y audit done; Windows and ARM matrices green; full UX checklist |
| 2.0 | Defined at 1.0 retrospective |

## 2. Coexistence and migration plan (Beta page stays untouched)

1. **Separate everything.** Own sidebar section, own routes `/observability-v1/*`, own module key `observability-v1` and tab keys `observability-v1-<tab>` (the Beta section uses different keys of the form `obs-*`; no overlap), own API `/api/obs/v1/*`, own tables `obs_*`, own package `com.xammer.cloud.observability.*`, own frontend folder. The Beta page, `/observability/*` route, its components and backend clients are not read, modified or reused.
2. **Two shared-file hazards handled explicitly** (the complete list of edits to existing shared files is in 05 section 2: `App.tsx`, `Sidebar.tsx`, `iconMap.ts` incl. `fa-heart-pulse`, `xamopsTabs.ts`, `designRules.test.ts` guard list, `nginx-prod.conf`, `vite.config.ts` if needed, `application*.properties`).
   - *Sidebar prefix collision:* the existing auto-open logic uses `path.startsWith('/observability')`, which also matches `/observability-v1/...`. The XamPulse change adds the `-v1` check first and excludes `-v1` from the Beta check (05 section 2). A vitest case asserts that visiting `/observability-v1/logs` opens only the XamPulse group and `/observability/logs` opens only Beta.
   - *Tab-gating default:* per the inspection notes, a user with **no assigned tabs is unrestricted**, so the new module would appear for everyone at deploy. Mitigation: frontend build flag `VITE_OBS_V1_ENABLED` (default off in prod until launch) hides the section and routes; backend `obs.enabled=false` makes `/api/obs/**` return 404. Both flip together at launch per environment. (Check how the repo reads Vite env flags at implementation and follow that pattern.)
3. **No data migration.** Beta and XamPulse have independent data. Customers connect servers to XamPulse through the new flow. If a host already runs the legacy XamOps disk agent, XamPulse installs side by side (different binary/unit/dir/token), pre-flight shows an info chip.
4. **No cross-links by default.** Neither product links to the other in 1.0. A later, optional decision (D12) is whether to retire Beta after XamPulse GA; that is a product decision, not part of this plan.
5. **Rollout stages.** (a) Dev/local with `obs.enabled=true`; (b) preprod with the design-partner account; (c) prod dark (flags off, infra up, migrations applied); (d) prod pilot for allow-listed clients via per-user tab assignment (assign `observability-v1-*` keys to pilot users; also set a tenant allow-list property `obs.pilot.client-ids` enforced by `ObsAccessGuard` so a client outside the list gets 404 even if tabs are unrestricted); (e) GA flip.
6. **Rollback.** Turning flags off hides UI and API; Flyway migrations are additive (tables only) and can stay. Agents keep running and buffering locally for up to the queue cap; if rollback is long, ingest edge returns 503 and agents retry (queue limited, drops counted).
7. **Database migrations** are new files only (`V20261025_1` … `V20261025_6`); no existing table altered (the one `ALTER TABLE` is on an Obs table inside the same migration chain).

## 3. Risk register

Likelihood (L) / Impact (I): H/M/L. "Owner" is a role.

| ID | Risk | L | I | Mitigation | Trigger / fallback | Owner |
|---|---|---|---|---|---|---|
| R1 | No production Kafka broker exists (only client config seen) | M | H | Decide infra in D2; MVP can start with a gateway disk queue feeding `hotwriter` and `archiver` through a second collector exporter (file-based handoff) if MSK is not approved | Slip > 2 weeks → file-queue variant with 30-min buffer | Infra |
| R2 | VictoriaLogs/VM hosting cost or ownership unresolved | M | H | Pilot sizing in 02 section 6; single node each; cost dashboard | No owner by week 2 → pilot on one EC2 per store with documented backups | Eng manager |
| R3 | Trimmed collector binary still too large for small hosts | M | M | `ocb` allow-list; target < 100 MB; UPX not used; measure in week 2 | > 150 MB → drop optional receivers per profile; ship Fluent Bit logs-only profile | Agent lead |
| R4 | Alpha OTel components (journald, docker_stats, redaction for logs/metrics) regress | M | M | Pin versions; soak tests; redaction via beta `transform`; Fluent Bit `systemd` fallback behind supervisor | Soak failure → switch source implementation without UI change | Agent lead |
| R5 | Redaction misses a secret format | M | H | Built-in corpus tests (AWS keys, JWT, bearer, private keys, Luhn cards, auth headers), host + gateway redaction, preview tool, customer rules, "report a leak" runbook | Leak reported → hot purge by stream/time via VL delete API (enterprise) or restart with filter; customer bucket guidance | Security |
| R6 | VictoriaLogs OSS has global retention only | H | M | Uniform hot window (14 d); per-customer retention lives in their S3 lifecycle; clear UI copy | Customers demanding longer hot → evaluate Enterprise/ClickHouse in 2.0 | Product |
| R7 | VM single-node tenant enforcement via `extra_filters` is insufficient | L | H | Spike week 3 (attempt bypass with `{__name__=~...}`, subqueries, `/federate`); cluster edition at GA; only Spring can reach VM | Spike failure → cluster edition from MVP | Backend lead |
| R8 | Windows support slips | M | M | Windows is GA scope, not MVP; separate matrix; MSI/PowerShell installer | Slip → ship GA Linux-only, Windows in 1.1 (D5) | Agent lead |
| R9 | Update trust: unsigned binaries | M | H | SHA-256 verification in MVP, signatures at GA, key custody decision (D9), manifest signing, rollback | No signing key by GA → block auto-update, manual only | Security |
| R10 | Customer IAM friction (CFN stack approval stalls) | H | M | Copy-paste path needs no AWS permissions; managed storage needs none; policy preview and plain language | Funnel metric: stack approval drop-off > 40% → add Terraform/guided video | Product |
| R11 | Small-files explosion in S3 | M | M | 5 min/64 MB batching, hourly compaction, tag-expire, per-host/type/hour prefix | Object count per tenant alarm > 5M | Pipeline lead |
| R12 | DuckDB cold query memory/IO contention | M | M | Per-job memory/thread/time caps, dedicated profile pods, plan preview, per-tenant concurrency 2 | Queue age > 60 s alarm → scale workers or offload to Athena | Backend lead |
| R13 | Alert evaluator throughput | M | M | Batching by tenant/interval, jitter, claim with SKIP LOCKED, shard replicas | Eval lag p95 > 60 s alarm | Backend lead |
| R14 | AI hallucination or data exposure | M | H | Evidence-only prompts, citation post-check, redaction, opt-in, kill switch, no training | Any incident → disable `obs.ai.enabled` | Security |
| R15 | Single-tenant DB mode means isolation is application-level | M | H | Explicit `client_id` columns, repository finder convention, guard types, cross-tenant IT in CI | Any failure blocks release | Backend lead |
| R16 | Auth-chain capture (`Authorization` header on machine endpoints) | L | H | Dedicated `@Order(0)` chain; use `X-XamOps-Ingest-Token`; test in CI | Test red = release blocker | Backend lead |
| R17 | Charting/perf on large datasets | M | M | uPlot, virtualization, server caps, decimation | Frame budget regressions in perf test | Frontend lead |
| R18 | Design-rule guard rejects new tokens | L | L | Add tokens and guard path deliberately in the same PR; contrast pairs | n/a | Frontend lead |
| R19 | Licensing drift (a dependency changes to AGPL/BSL) | L | H | License allow-list check in CI for Go and npm and Maven additions; pin versions | Change detected → pin/replace | Eng manager |
| R20 | Logs contain personal data (GDPR/DPDP) | M | H | Customer-owned storage, redaction, RBAC on content, audit, deletion workflow, DPA, region of managed bucket (D8) | Legal review before GA | Legal |
| R21 | Pricing/packaging undefined | M | M | Usage meters exist from MVP; D4 | Block GA marketing, not engineering | Product |
| R22 | Agent harms host performance | L | H | systemd `CPUQuota=25%`, `MemoryMax`, memory_limiter, nice 10, soak CPU < 3% | Gate in MVP exit | Agent lead |
| R23 | Time skew or backfill bursts produce wrong hour partitions | M | L | Event-time partitioning with late-arrival window (2 h) and a `late` flag; compaction re-opens at most the previous 2 hours | n/a | Pipeline lead |
| R24 | Cross-region buckets cause data-transfer cost | M | L | Warning in validation; same-region recommended; cost estimate includes it | n/a | Product |

## 4. Test plan

### 4.1 Layers and tooling (aligned with what the repo has)

| Layer | Tooling | Scope |
|---|---|---|
| Java unit | JUnit 5, Mockito (existing test layout `xamops-service/src/test/java/...`) | Parser, compilers, guards, state machines, math, codecs (04 section 9 list) |
| Java integration | Spring Boot Test + Testcontainers PostgreSQL (already a dependency), MockMvc, WireMock | Repositories, controllers, security chain, migrations, scheduler claims |
| Go | `go test`, testcontainers-go (Kafka, MinIO), golden files | Supervisor (config render, update/rollback), archiver (layout, Parquet schema, manifests), hotwriter, compactor |
| Frontend unit/component | vitest + @testing-library/react (existing), colocated `__tests__/` | Hooks, utils, pages, a11y roles |
| Design rules | `FE/test/designRules.test.ts` with the feature path added | No gradients/emoji/FA/raw hex; contrast pairs; status never colour-only |
| E2E / system | docker-compose profile `obs-e2e` (agent container → nginx → gateway → Kafka → hotwriter → VL/VM; archiver → MinIO) + scripted checks; browser E2E only if the repo already has a runner (none was found; adding one is a decision for the implementation phase) | Golden path |
| Performance | k6 (HTTP) + custom OTLP load generator (`otelgen`-style) | Ingest and query load |
| Security | Unit-level negative tests + external pen test before GA | Auth, tenancy, injection |
| Accessibility | Automated role/focus tests + manual screen reader pass (VoiceOver, NVDA) and keyboard-only pass | UI |
| Manual UX | UX checklist (07 section 8), 5-person onboarding timing, think-aloud sessions | UX |

### 4.2 Must-have test cases (by risk)

**Tenancy and security**
1. Two clients, two accounts each: every list/detail/search/metrics/tail/storage/alert endpoint returns 403/404-equivalent for the other client's ids; request with the other client's `cloudAccountIds` in scope fails entirely.
2. Same AWS account connected by two clients: separate hosts, destinations, manifests; Parquet `tenant_hash` differs; cold query of one never returns rows of the other even if sharing a bucket.
3. Ingest: payload with forged `xo.client`/`xo.account` attributes and forged `X-Obs-*` headers is overwritten (check hot store labels and archive rows).
4. Token lifecycle: revoked, expired, rotating-overlap, replayed enrollment (use count), IP anomaly behaviour; ingest-auth cache eviction ≤ 60 s.
5. Injection corpus for XLQ → LogsQL and → SQL (quotes, backticks, `|` stages, comment tokens, unicode, 10 KB inputs, regex bombs); assert AST-only compilation and bound parameters; PromQL guard rejects `{__name__=~".+"}` without tenant filter bypass, `/federate`, admin endpoints.
6. `@Order(0)` chain test: Bearer-bearing request to `/api/obs/v1/agent/**` reaches the filter; same request to another path is still handled by the impersonation chain (no behaviour change for existing endpoints; run a smoke subset of existing security tests).
7. Webhook SSRF: private IPs, DNS rebinding (resolve at send), redirects not followed.
8. Audit: archive query and export create rows; audit failure refuses the operation; trigger blocks UPDATE/DELETE.
9. Redaction regression corpus (≥ 200 samples: AWS keys, JWT, bearer, PEM, cards with Luhn, emails, Authorization/Cookie headers, connection strings) run at host config render and gateway rules.

**Query language, fan-out and tail (review findings 6 to 8)**
10a. XLQ: the 18 golden parser cases of 06 section 8.4 (precedence, hyphen vs NOT, path as bare word, regex only after `~`, quoted reserved words, `limit` rejected, `stats` terminal) plus the injection corpus in case 5.
10b. Multi-account fan-out: merge order and cursor map, histogram/facet summation, `STATS_NOT_MERGEABLE` for `avg/p95/count_unique` across accounts, 11 accounts → `TOO_MANY_ACCOUNTS`, one failing account → `partial: true` with `failedAccounts[]`, all failing → 503.
10c. Live tail contract: each pre-stream error of 06 section 10.1 returns a readable JSON body (fetch client), a stage is rejected with `TAIL_UNSUPPORTED_STAGE`, `levels`/`hostKeys` are AND-ed, resume with `Last-Event-ID` is inclusive and de-duplicated by row id, a stale cursor yields `RESUMED_WITH_GAP`, revoked permission ends the stream with `PERMISSION_REVOKED`.
10d. Connect-flow RBAC: a user holding only the default `ROLE_XAMOPS` grants completes discovery, pre-flight, install job, bucket listing, destination validation and permission-stack launch (`ConnectFlowRbacTest`); `ROLE_USER` is refused every onboarding endpoint; default table equals 06 section 11 (`ObsPermissionDefaultsTest`).
10e. Internal API: a request to `/api/obs/v1/internal/**` on the public port gets 404, on the private port from an allowed CIDR without or with a wrong key 401 (nginx maps any non-2xx from `/_obs_auth` to deny), from a non-allowed CIDR 404; `ingest-auth` is reachable only through `xamops_service_internal`.
10f. IAM and lifecycle generators: rendered Operator/Writer/Reader/bucket policies parse as valid JSON and contain `BucketMeta` (unconditioned `GetBucketLocation`), `UntagOnlyOurMarker` (`aws:TagKeys` only) and `kms:ViaService`; `LifecycleRuleGeneratorTest` for retention 7/30/31/90/91/180/365/3650 (transitions only when `Days < retention`), 6 and 3651 rejected; AWS-managed key resolved through `DescribeKey` and stored; archiver sends `x-amz-server-side-encryption` on every put and multipart (Go test); SSE-S3-only bucket yields WARN and no KMS statement.
10g. Data deletion: `DataDeletionJob` processes `obs_deletion_request` rows, never touches customer buckets, audits, and retries then fails visibly.

**Onboarding and storage**
10. Discovery: fresh inventory, stale inventory with live fallback, partial region failure, 0 instances, 5,000 instances (pagination/selector).
11. Pre-flight matrix: each `PF_*` code is produced by a seeded host fixture; messages and fixes asserted.
12. Install job state machine: success, SSM timeout, checksum mismatch (tampered artifact), egress blocked, no data after install, cancel mid-way, roll back all.
13. Copy-paste installer on AL2, AL2023, Ubuntu 20.04/22.04/24.04, Debian 11/12, RHEL/Rocky 8/9, SUSE 15 × x86_64/arm64: idempotent re-run, dry-run prints plan without changes, `--preflight`, uninstall leaves no residue (find/ss/systemctl checks).
14. BYO bucket validation seeded failures: public access on, no Block Public Access, wrong region, role missing, KMS key denies, Object Lock, lifecycle overlap, bucket in other account.
15. Archiver: layout exactness against the spec (`yyyy/mm/dd/hh`, naming, `type` enum), Parquet schema, ZSTD, 5-min/64-MB flush, compaction + tag, manifest rows equal to objects, late-arrival handling, S3 AccessDenied → degraded → failed → fallback to managed (when enabled) → recovery and backfill from Kafka offsets.
16. Cold query: plan preview accuracy (files/bytes within 5%), caps (scan, memory, time), cancel, Glacier-class warnings, JSONL variant, cross-hour boundaries, compacted vs part files precedence, export limits.

**Product behaviour**
17. Log explorer: facets counts equal result counts, histogram brush to range, context lines, live tail rates (throttle at 200/s with accurate dropped counter), pause/resume ring buffer, reconnect with `Last-Event-ID`, 30-min cap, 5-stream limit.
18. Metric correlate: known synthetic incident (iowait spike + error burst) returns deep link containing the right host/window and the new pattern.
19. Alerts: each rule type with synthetic series; for-duration, flap suppression, dedup (one incident per key), escalation timing, mute windows (one-off, recurring across DST), notification retries/timeouts, composite cycle rejection, preview count equals actual replay.
20. Forecast accuracy on synthetic disk fill (linear, seasonal) within ±10% ETA; anomaly false-positive rate < 1 per host per week on 28-day replay of stationary-with-seasonality data.
21. AI: ungrounded sentence removal, redacted prompts (assert no secret in outbound payload), toggle off sends nothing.
22. Migrations: apply `V20261025_1..6` on a clean DB and on a copy of the current schema; table list equals 06; re-run idempotency not required (Flyway).

**Frontend**
23. Sidebar: only XamPulse group opens on `/observability-v1/*`; Beta unaffected; tab gating (`observability-v1-*`) hides entries; flag off hides everything.
24. Connect page: select-all-matching, blocked rows unselectable, one-click disabled without stack, progress rail announcements, copy command, success; no secrets rendered after reload.
25. No log content in `localStorage`/`sessionStorage`/persisted query cache (assert after a search).
26. States: every screen has loading/empty/error/waiting/noPermission tests via `ObsStateBlock` variants.
27. Keyboard and focus: palette, drawers (trap/restore), tables (`j/k/x/Enter`), shortcuts disabled while typing.
28. Performance: 10k-row virtual list and 10k-point chart interaction within frame budget (profiling in CI optional, manual per release).

**Resilience (chaos, pre-GA)**
29. Kill Kafka for 20 min (gateway queue holds, no loss); kill VictoriaLogs (archive continues, UI degraded copy); kill hotwriter (lag recovers); revoke the Writer role; throttle S3; kill ingest-auth (fail closed, agents retry, no tenant bleed); agent host network partition 30 min (queue then drop-oldest with counters).

### 4.3 Test data and fixtures

Synthetic generators for nginx/apache/syslog/auth/JSON app/Java stack traces/Docker logs/Windows events; metric fixtures with known spikes; two-tenant seed script; recorded VictoriaLogs/VictoriaMetrics responses; Parquet fixtures produced by the Go archiver and consumed by the Java DuckDB tests (contract test keeps both sides in sync).

### 4.4 CI integration

Add jobs mirroring `agent-build-verify` in `.github/workflows/ci-cd.yml`: `obs-backend-test` (Maven module tests filtered to `observability`), `obs-pipeline-test` (Go), `obs-agent-build` (cross-compile + checksums + size budget check), `obs-frontend-test` (vitest for the feature + design rules), `obs-license-check`. The size budget job fails if the collector binary exceeds the agreed limit.

## 5. Decisions

### 5.1 Decisions made in this plan (override by exception only)

| # | Decision | Rationale |
|---|---|---|
| A1 | Name/route namespace `XamPulse` / `/observability-v1/*` | Matches brief; name is a constant |
| A2 | Logs: VictoriaLogs; metrics: VictoriaMetrics; cold: customer S3 Parquet + DuckDB | 01 recommendation; Apache-2.0; no AGPL |
| A3 | Agent: new supervisor + custom OTel Collector; REST poll for config (no OpAMP in 1.0) | 01 section 7 |
| A4 | Machine endpoints on a dedicated `@Order(0)` security chain; header `X-XamOps-Ingest-Token` | Avoid impersonation chain capture; no edits to `SecurityConfig.java` |
| A5 | Alert evaluator in Spring with `SKIP LOCKED` claims | Single model for all rule types; no new dependency |
| A6 | Live tail: SSE, not STOMP | STOMP broker not tenant-safe |
| A7 | uPlot for dense series, shared recharts for KPIs, cytoscape for map | Consistent with repo; no commercial chart lib |
| A8 | Feature-local QueryClient without persistence | No log data in browser storage |
| A9 | Writer role has no delete; compaction uses tag + lifecycle | Least privilege |
| A10 | Cold search is asynchronous, plan-first, capped | Cost and safety |
| A11 | Unit of tenancy: `client_id` + `cloud_account_id` PK | Inspection finding 0.1 |
| A12 | IDs are BIGSERIAL numbers; `tenant` for VL/VM = (client_id, cloud_account_id) | Fits uint32 tenant model |

### 5.2 Open decisions needing the user (summarised again in 00-README)

| ID | Decision | Options | Default if no answer | Effect of the choice |
|---|---|---|---|---|
| D1 | Product name shown in UI | XamPulse / "Observability" / other | XamPulse | Copy and brand only; one constant |
| D2 | Infra: who hosts and pays for Kafka (MSK), VictoriaLogs, VictoriaMetrics, gateway collectors, ingest domain `obs-ingest.<domain>` and in which region | Existing EKS/ECS vs new EC2 vs managed | Block: needs infra owner | Gates MVP start; R1/R2 |
| D3 | Hot (fast search) window | 7 / 14 / 30 days | 14 days | Disk cost scales linearly; older logs still searchable via archive |
| D4 | Pricing and host cap defaults | Per-host + fair-use GB / per-GB / bundle | Per-host with 500 hosts cap per tenant default | Entitlement code, usage page copy |
| D5 | Windows Server in 1.0 GA or 1.1 | GA / later | GA | Schedule +3–4 weeks if GA |
| D6 | Who can read log content by default | All users / operators only | `ROLE_XAMOPS` (standard customer user) can read logs, run archive searches and complete the whole Connect flow; `ROLE_USER` only sees `obs.view`; exports, tenant settings, audit and AI are admin-only (06 section 11). Admin-configurable through `obs.rbac.defaults.<ROLE>` | Privacy posture; property change only. Onboarding must stay reachable for the dominant customer role |
| D7 | AI features: allowed to send short redacted excerpts to the platform LLM gateway | Allowed / disabled | Disabled until each tenant opts in | Whether AI summary, NL query, RCA ship in GA |
| D8 | Managed fallback bucket: region(s), ownership account, and whether it is the default for new tenants | Default managed / force BYO | Managed default so connect needs zero AWS steps | Privacy positioning; data residency |
| D12 | Future of the Beta page after XamPulse GA | Keep / deprecate | Keep untouched; revisit after GA | None for this plan |

### 5.3 Decided defaults the user may veto

| ID | Default | Notes |
|---|---|---|
| D9 | Agent artifacts signed with a KMS-backed key (cosign/minisign) held by the release pipeline; SHA-256 manifest in MVP | Needs a key custodian |
| D10 | "Safe fallback" to managed storage after 24 h of failed BYO writes is ON by default, customer can disable | Prevents silent data loss; some customers may prefer drop |
| D11 | Email notifications via existing `EmailService` if its API fits, otherwise direct `JavaMailSender` | Verify at implementation |
| D13 | Single-region ingest for 1.0 | Multi-region is 2.0 |

## 6. Definition of done for the whole programme

1. All 8 documents' contracts implemented and cross-document names match (checked by a script that greps routes, endpoints, table names and class names across 04/05/06/07 and the code).
2. Exit gates of the relevant phase met.
3. Operational runbooks exist: onboarding failures, S3 AccessDenied, agent silent, pipeline lag, hot store recovery, secret leak response, token compromise.
4. Observability of the observability: dashboards and alerts for ingest rate, auth failures, Kafka lag, archiver lag, destination health, VL/VM disk, query latency, evaluator lag.
5. Security review and pen test complete; licence check clean.

## 7. Responses to design review (iteration 2)
Findings 3, 5, 6, 7, 8, 9 each got test coverage (cases 10a to 10g in section 4.2). D6 default updated to match the single RBAC table.
