# XamOps QA Depth Testing: Test Plan (Phase 1 audit)

Scope: whole monorepo. No source was modified. Findings below are audit evidence from reading code and running the existing suites. "Hypothesis" means read in code but not yet proven by a failing test; the bug hunt (areas A to E) must confirm or kill each one with a test or a repro.

## 1. Layout verified

| Module | Stack | Main / test Java files | Notes |
|---|---|---|---|
| xamops-service | Spring Boot (Java 25 release), JPA, Flyway, Redis, Kafka, STOMP | 1372 / 386 | 139 `*Controller.java`, 96 Flyway files in `src/main/resources/db/migration`, 61 files with `@Scheduled` across the three Spring services |
| billops | Spring Boot, session + JWT impersonation | 243 / 37 | `InvoiceManagementService` 2989 lines, `TicketService` 1781, `GcpDataService` 1555 |
| media-ops-service | Spring Boot, ffmpeg, WebSocket | 75 / 6 | |
| permissions-common | shared lib | | built with `-am` |
| api-gateway | `nginx-prod.conf` plus a 1-class Spring shell | 1 / 1 | |
| service-registry | Eureka | 1 / 1 | |
| frontend-app | React + TS + Vite + vitest + RTL | 118 pages, 32 api files, 163 hooks | about 150 test files, mostly EKS/K8s, Cost, SOC2 demo |
| xamops-agent | Go | | 2 test files, both in `internal/collector` |
| infra | `cloudformation/` (readonly, readwrite, CUR, inventory, autospotting), `scripts/`, `.github/workflows/{ci-cd,cfn-validate,setup-guide-validation,livekit-uat}.yml` | | |
| not in scope list but present | forecast-service, marketplace-procurement-service, xamops-autoscaler, cloudshell, eks, far, jmeter-tests, postman | | `jmeter-tests` and `postman` are reusable for load and API checks |

## 2. Tooling (what actually works on this machine)

Machine: macOS. Installed: mvn 3.9.12, openjdk 25.0.4, node 25.6.1, npm 11.9.0, go (works), docker CLI present.

| Task | Command | Status observed |
|---|---|---|
| billops tests | `mvn -o test -B -pl billops -am` | WORKS. BUILD SUCCESS in 1m45s. 6 `*PgTest` classes are SKIPPED (Testcontainers needs Docker). |
| media-ops tests | `mvn -o test -B -pl media-ops-service -am` | RUNS, 3 failures (see Section 4, finding M-1, M-2). |
| xamops-service tests | `mvn -o test -B -pl xamops-service -am` | Long (more than 5 min). Baseline result in Section 4. Single class: `mvn -o test -B -pl xamops-service -am -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`. |
| Go agent tests | `cd xamops-agent && go test ./...` | WORKS. `internal/collector` ok; `cmd/agent`, `config`, `pusher`, `version` have no tests. |
| Frontend tests | `cd frontend-app && npx vitest run` (alias `npm test`) | BLOCKED here. `frontend-app/node_modules` is empty. `npm ci` fails: registry.npmjs.org times out (ETIMEDOUT), and `npm ci --offline` fails with ENOTCACHED for `dompurify`. Needs network, then `cd frontend-app && npm ci`. |
| Frontend type check / build | `cd frontend-app && npm run build` (runs icons:bundle, tsc, vite build) | BLOCKED by the same install problem. |
| Coverage (Java) | JaCoCo 0.8.15 is wired in xamops-service `pom.xml`; `mvn -o verify -pl xamops-service -am` writes `target/site/jacoco`. Not wired in billops or media-ops. | Not run. |
| Coverage (frontend) | `npm run test:coverage` | `@vitest/coverage-*` is not in devDependencies, so this will need `@vitest/coverage-v8` added. Unverified. |
| Docker-dependent tests | `*PgTest` (Testcontainers PostgreSQL) in xamops-service and billops | BLOCKED: docker daemon is not running. Start Docker Desktop, then rerun. These cover Flyway/schema, LOB text, partition lock, rollups. |
| Static checks | `mvn -o -q compile`; `go vet ./...`; `cfn-lint` (used by `cfn-validate.yml`, not checked locally); `nginx -t` needs nginx (not checked) | |
| CI today | `ci-cd.yml` runs `mvn test` per changed service, `npm test`, `go test ./...`. No coverage gate, no frontend `tsc` in the test job, no SAST or dependency scan. | |

Test libraries present: JUnit 5 + Mockito + spring-boot-starter-test in all Spring modules; H2 and Testcontainers PostgreSQL in xamops-service only; vitest 4 + RTL + user-event + jsdom in frontend. No Playwright or Cypress. No `spring-security-test` found in the poms (grep for the artifact found none), so the bug hunt must build `MockMvc` auth with filters by hand or add it as a test-scope dependency.

Rules for the bug hunt: put new tests next to the existing ones and follow their style. Do not edit production code in the hunt step unless the fix is separately approved. Never print secret values; refer to keys by name.

## 3. Coverage map and gaps

Existing tests are concentrated in K8s/EKS automation, CUR helpers, inventory, call hardening, SOC2/WAR demo data and ticket sanitizer units. Large production classes with no test file mentioning them (by class name) are the gap list.

xamops-service, over 400 lines, untested: `EksDashboardService` 2854, `PrometheusService` 2324, `ProactiveCacheRefreshService` 1986, `AIOpsService` 1579, `DbOpsCostService` 1522, `WarControlCatalog` / `AzureWarControlCatalog` / `GcpWarControlCatalog` (about 1500 each), `MarketOpsService` 1437, `MarketplaceBillingExtractionService` 1266, `AzureCostInsightsService` 1180, `OpenCostService` 1118, `GcpCostInsightsService` 991, `TicketService` 982, `LlmProxyService` 962, `AzureReservationService` 954, `AzureDiskRightsizingService` 781, `IamPrivilegeAnalyzer` 692. Also no dedicated tests found for `XamOpsRightsizingService`, `GcpClientProvider`, `RedisCacheService`, `WebSocketConfig`, `CallStompAuthInterceptor`, `MeteringController`.

billops, untested: `TicketService` 1781, `GcpDataService` 1555, `SupportAdminController` 890, `AwsBillPdfParser` 734, `GoogleChatService`, `TicketController`, `ExternalMessagingService`, `MessagingWebhookController`, `BillopsController`, `SlackOAuthController`. `InvoiceManagementService` is exercised only indirectly (Holding cost, PrivatePricing, FinalizeExchangeRate, DiscountAttribution tests); PDF/Excel generation and tax math are not directly pinned.

media-ops: 6 test files for 75 classes; security config and WebSocket auth untested.

Go agent: only collector has tests; `pusher` (network, retries, auth key) and `config` have none.

Frontend: strong on EKS/K8s, Cost drawers, SOC2. Thin on BillOps invoice/ticket pages, GCP/Azure cost pages, `CallContext`, auth/login flows, and `src/api` (32 files, about 12 with tests).

Authorization coverage signal: 1131 `accountId` parameters in controllers across the three Spring modules; only 17 files in xamops-service reference `CloudAccountAccessService`, and about 130 of 139 controllers do not reference it. Some of those do their own checks, so this is a risk indicator, not proof.

## 4. Baseline observations so far (evidence)

| ID | Observation | Evidence | Type |
|---|---|---|---|
| M-1 | `FFmpegServiceTest.resolveOverlayPosition_null_returnsDefault` and `resolveDrawtextPosition_null_returnsDefault` fail: expected `10:10` / `x=10:y=10`, got `W-w-10:H-h-10` / `x=w-tw-10:y=h-th-10` | `mvn -o test -pl media-ops-service -am`; log in surefire reports | Test and code disagree. Decide which is intended (default = bottom-right or top-left) and fix one side. |
| M-2 | `FFmpegServiceTest.buildTranscodeCommand_subtitleBurnIn_includesSubtitleFilter` errors: local ffmpeg lacks libass | same run | Environment, but the test should skip itself (assumption) instead of failing on machines without libass. |
| B-1 | 6 billops PG tests skipped | surefire "Skipped" lines | Docker down. Not a defect. |
| F-1 | Frontend suite cannot be run offline | see Tooling | Environment. |
| X-1 | xamops-service full suite baseline | `/tmp/qa/mvn-xamops.log` (see Section 4a) | pending |

### 4a. xamops-service baseline
Pending when this plan was written; the run was still green through 305 test classes with no failures at the last check. The bug-hunt step must rerun `mvn -o test -B -pl xamops-service -am` and record the final count.

## 5. Security hypotheses found while auditing (to be proven in the hunt)

Each has file evidence. None has been exploited or proven by a test yet.

| ID | Hypothesis | Where | Test to write |
|---|---|---|---|
| S-1 | STOMP: only `CONNECT` is inspected. A missing or invalid token still connects ("connecting without identity"), and there is no `SUBSCRIBE` or `SEND` authorization. `/xamops-ws` and `/ws/**` are `permitAll`. Anonymous clients may subscribe to `/topic/**` (tickets, cloudlist refresh) and possibly `/user/**` call signaling. | `websocket/CallStompAuthInterceptor.java`, `config/WebSocketConfig.java`, `config/SecurityConfig.java` | Channel-interceptor test: anonymous CONNECT then SUBSCRIBE `/topic/tickets`, and user A subscribing to user B's call queue. Expect rejection. |
| S-2 | `/api/metering/customers/**` is `permitAll` for all methods; auth is only an `X-Api-Key` header check inside the controller. Verify that the key is bound to the `customerId` in the path (IDOR) and compared in constant time. | `SecurityConfig.java` line near 306, `MeteringController.java` line 188 | MockMvc: key of customer A reading customer B events. |
| S-3 | CSRF is disabled in billops, media-ops and xamops-service while the browser session cookie authenticates. Safety then depends on cookie `SameSite` and CORS. | the three `SecurityConfig` files | Inspect `Set-Cookie` flags from login; test cross-origin POST with credentials. |
| S-4 | IDOR: 1131 `accountId` params vs 17 files using `CloudAccountAccessService`. High-value targets: cost, rightsizing apply/execute, account-manager, security remediation, s3-browser, cloudshell, dbops, secrets/agent keys. | controllers | Parameterized test: user of tenant A calling every `@RequestMapping` with tenant B accountId, expect 403/404. Scan controllers for ones that never resolve the account through the access service. |
| S-5 | Rate limiting is configured for `/api/dashboard.*` only (10/min, bucket4j). Nothing found for login, OTP (`LoginOtpController`), password reset, LLM proxy, metering ingest. nginx has no `limit_req`. | `application.properties` lines 236-246, `nginx-prod.conf` | Brute-force loop against login/OTP in a test; assert lockout or 429. |
| S-6 | Ticket HTML is sanitized in controllers (billops `TicketController`, `TicketNoteController`, `CannedResponseController`; xamops `TicketController`, `EmailTicketIngestService`). Inbound paths that do not call the sanitizer: billops `MessagingWebhookController` / `ExternalMessagingService` (Slack/Teams/Google Chat), any service-level `TicketService` create path, subject/title fields. Frontend renders via `dangerouslySetInnerHTML` in `TicketDetailPage`, `BillOpsTicketDetailPage`, `GcpSecurityPage`, `CloudMapPage`, `DevOpsInTheBoxPage`, `StoryNarrative`. | listed files | Payload corpus (`<img onerror>`, `javascript:` href, SVG, CSS) through every ticket write path; RTL tests that each `dangerouslySetInnerHTML` site routes through `utils/sanitizeHtml.ts`. |
| S-7 | Webhook bypass lists (`/api/billops/webhook/**`, Slack OAuth callback, `/api/xamsecure/webhook/**`, EventBridge callback, `/api/v1/agent/**`) skip session auth; signature or secret check lives inside handlers. | `SecurityConfig` files | Each: no secret, wrong secret, replay, oversized body. |
| S-8 | nginx: `/livekit/` proxies to a hard-coded public IP over plain HTTP; `/api/cicd/` is rewritten to `/cicd/` upstream while `SecurityConfig` protects `/api/cicd/**` (verify the mapping matches); no `limit_req`; `/api/` paths outside `/api/xamops`, `/api/billops`, `/api/admin`, `/api/ai*`, `/api/forecast`, `/api/media-ops` (for example `/api/gcp`, `/api/azure`, `/api/eks`, `/api/auth`, `/api/sre`, `/api/dbops`, `/api/metering`) have no gateway location and would fall to `location /` (the frontend). Needs checking against how the frontend actually calls them. | `api-gateway/nginx-prod.conf` | Run nginx -t if available; trace each controller prefix to a location block. |
| S-9 | Cloud credentials: `AwsClientProvider` uses `externalId` for AssumeRole (good). Check that `externalId`, role ARNs, and GCP service-account JSON never reach logs, API responses or cache keys; check `GcpClientProvider` caching keyed by account, not shared. | `AwsClientProvider.java`, `GcpClientProvider.java`, DTOs | Log-capture test with a fake secret; serialize every account DTO and assert no credential fields. |
| S-10 | Redis/Spring cache keys must contain the tenant/account. 49 cache annotations; `PricingService` keys are region/type only (correct, public data). Audit the rest. | `@Cacheable` sites, `RedisCacheService`, `CurInsightCache` | Two accounts, same params, assert no cross-read. |
| S-11 | Unauthenticated file/script endpoints: `/agentinstall/*`, `/agent/download/**`, `/api/llm-proxy/**`, `/api/aws/**` (calculator only: confirm nothing else maps under `/api/aws`; only `AwsCalculatorController` found). | `SecurityConfig` | Path-traversal and auth probes. |

## 6. Risk-ranked test matrix

Priority: P0 = money or cross-tenant exposure, do first. P1 = data loss or wrong decisions. P2 = quality. Types: U unit, I integration (Spring slice or MockMvc), C contract, S security, E2E/UI, L load, M manual exploratory.

| # | Feature / module | Key code | Test types | Pri | Existing | Gap to close |
|---|---|---|---|---|---|---|
| 1 | Invoice math: line items, discounts, holding cost, private pricing, GST (intra vs inter state), FX, rounding | billops `InvoiceManagementService`, `PrivatePricingCalculator`, `fetchUsdToInrRate` | U, I, property tests | P0 | Holding, PrivatePricing (24), Discount, Finalize rate | Mixed `double` and `BigDecimal` (about 175 BigDecimal refs, plus `double` for exchange rate and `serviceAmount.doubleValue()`): rounding drift, zero/negative amounts, missing rate fallback, external FX API failure (rate fetched from a third-party URL), finalize idempotency, PDF total equals DB total |
| 2 | Invoice PDF/Excel/AWS bill PDF parsing | `AwsBillPdfParser`, `BillopsController` export, `Content-Disposition` filename | U, fuzz | P0 | none | malformed PDF, huge file, filename injection |
| 3 | Cross-tenant isolation (billops, media-ops, xamops) | `CloudAccountAccessService`, controllers with `accountId`/`clientId` | S, I | P0 | `CloudAccountAccessServiceTest`, a few controller security tests | S-4 sweep; billops `CloudAccount` access; media-ops job/file ownership |
| 4 | AuthN/AuthZ rules and filter chains | three `SecurityConfig`, `ImpersonationJwtFilter` (2 copies + media-ops copy), `TrialEnforcementFilter` | S, I | P0 | JwtFilter tests (basic), AdminCloudFront/DemoUsers security tests | Role matrix per endpoint prefix, expired/forged/other-audience impersonation JWT, trial-expired bypass paths, `/billops/**` role rules, CSRF (S-3) |
| 5 | WebSocket and call endpoints | S-1, `CallService`, `CallSignalController`, `CallWsTokenService`, LiveKit token minting | S, I | P0 | `CallServiceHardeningTest`, repo tests | S-1; token reuse/expiry; joining someone else's call; LiveKit room names |
| 6 | Cloud credential handling | S-9 | S, U | P0 | `AwsClientProvider*Test` (4 classes) | `GcpClientProvider` none; log/response leak tests |
| 7 | CUR cost math and basis (unblended/amortized, charge types, reconciliation) | `CurAdvancedMetricsService` (2379 lines), `CurCostDataSource`, `CurSql`, `ResourceCostJoin` | U, I | P0 | many cur tests (CostBasis, ResourceCostJoin, Advanced metrics) | SQL injection through account/tag/column inputs (`CurSql`, `CurTagColumns`), partial month, currency, negative credits/refunds, empty CUR, Athena failure |
| 8 | GCP cost and CSP billing | `GcpCostService`, `GcpCostInsightsService`, `CspBillingQueryService`, `CspBillingConfigController` | U, I | P0 | `GcpCostServiceCspTest`, `GcpFinOpsReportServiceTest`, anomaly test | Insights service has no test; BigQuery query building with user input; CSP markup math |
| 9 | Azure cost insights and reservations | `AzureCostInsightsService`, `AzureReservationService` | U | P1 | none | pagination, currency, missing subscription |
| 10 | Rightsizing (EC2, EBS, GCP, Azure) and apply/execute | `XamOpsRightsizingService`, `EbsDiskRightsizingService`, `RightsizingApplyService`, `EbsExpandExecutorService`, `EbsShrinkExecutorService`, `AwsRightsizingController` | U, I, S | P0 (writes to customer cloud) | EBS savings/expand, applicability | `XamOpsRightsizingService` untested; shrink/expand safety checks, savings sign, authorization on apply, dry-run default |
| 11 | Ticket flows (billops and xamops) incl. HTML sanitization, attachments, reminders, email ingest | `TicketService` x2, `TicketController` x2, `TicketHtmlSanitizer` x2 (duplicate classes), `TicketAttachmentPolicy`, `EmailTicketIngestService`, `TicketReminderScheduler` | U, S, I | P0 | Sanitizer tests, attachment policy, reminder scheduler | S-6; two diverging copies of the sanitizer; attachment path traversal; reminder duplicates |
| 12 | External messaging webhooks (Slack/Teams/Google Chat) | `MessagingWebhookController`, `ExternalMessagingService`, `SlackOAuthController`, `WebhookUrlValidator` | S, I | P0 | `WebhookUrlValidatorTest` only | signature verify, SSRF through outbound URL, OAuth state |
| 13 | Rate limiting and abuse controls | S-5, `Bucket4jCacheConfig`, `DevOpsRateLimitConfig` | S, L | P0 | none | login/OTP/password reset/LLM proxy |
| 14 | Flyway migrations and schema | 96 migrations; prod `spring.flyway.out-of-order=true`; local `flyway.enabled=false`; legacy `V2__Create_Capacity...` plus date versions | I (PG), M | P0 | `*PgTest` (need Docker), `MigrationRulesTest` | Run full migrate on empty PG and on a copy of prod-shaped data; idempotency; duplicate-version check; destructive statements; root-level `database_migration_*.sql` files live outside Flyway (drift risk) |
| 15 | Schedulers (61 files) | `FinOpsCron`, `BudgetAlertScheduler`, `AutoSpottingScheduler`, `AwsInventoryScheduler`, `MonthlyDigestScheduler`, `EntitlementCheckScheduler`, `DiskAlertScheduler`, `TicketReminderScheduler` | U, I | P1 | reminder, inventory scheduling tests | multi-instance double-fire (no ShedLock seen: verify), exception swallowing, time zone, idempotent notifications |
| 16 | Redis and cache correctness | S-10, `RedisCacheService`, `CacheConfig`, `CurInsightCache`, `DatabaseCacheService`, LOB cache | I | P1 | LobText tests, CurInsightCache indirect | key scoping, TTL, serialization of `ClientUserDetails` (mixins), eviction |
| 17 | Security findings, WAR, compliance, SOC2 | `SecurityFindingIngestService`, `IamPrivilegeAnalyzer`, `WarReportAssembler`, `AwsWarService`, `GcpWarService`, `WarControlCatalog` x3, `ComplianceOpsController` | U, I | P1 | Ingest, Severity, Notifier, Detector, Exposure tests | WAR scoring math, control catalog consistency (ids unique, mapping complete), `IamPrivilegeAnalyzer` untested, severity downgrade |
| 18 | Inventory and CloudMap | `AwsInventoryReconciler`, `InventoryEventIngestService`, `InventoryQueueAuthorizer`, `CloudMapService`, `ConnectedResourceService` | U, I | P1 | Region coverage, account scoping, queue authorizer | event replay/duplicate, deleted resources, cross-account ARN in event, graph size limits |
| 19 | Agent/K8s ingest and automation | `AgentController`, `AgentIngestService`, `AgentApiKeyService`, `K8sAutomation*` guards, Go `pusher` | S, I, U | P1 | many automation tests, 2 Go test files | agent key auth (missing/other-tenant key), payload size, `install-k8s.sh` and `agent_install.sh` integrity (`InstallScriptIntegrity`), Go `pusher` retries |
| 20 | Role CloudFormation templates | `cloudformation/xamops-readonly-role*.yml`, `xamops-readwrite-role*.yml`, `xamops-mpa-cur-role.yml`, `xamops-stack-updater.yml`, inventory/autospotting nested | static, C | P0 | `cfn-validate.yml`, `ReadWriteTemplateContractTest` in CI | `Resource: *` with write actions, missing ExternalId condition, trust to wrong principal, drift between `.yml`, `-local`, `-uat` variants (4 copies in Downloads too) |
| 21 | CI/CD and deployment | `.github/workflows/ci-cd.yml`, Dockerfiles, `Jenkinsfile` | review | P1 | | No coverage gate; secrets handling in workflow; `sed -i` on Dockerfile in pipeline; image tag pinning |
| 22 | Frontend: billing pages | `BillOpsAdminInvoiceDetailPage`, `BillOpsTicketDetailPage`, `BillOps/*` | U, E2E | P0 | holding cost, pricing, dashboard tests | totals display vs API, rounding, currency format, empty/error states |
| 23 | Frontend: cost pages | `GcpCostPage`, Azure cost, `ChargesByServiceSection`, `curInsightsApi.ts`, `gcpCostInsightsApi.ts` | U, E2E | P1 | charges-by-service and basis tests, `xamopsMockData.cost.test.ts` | GCP/Azure cost pages, abort/race on account switch, NaN/null display |
| 24 | Frontend: auth and route guards, `CallContext`, localStorage use (106 files reference storage) | `src/contexts`, `apiClient.ts` | U, S | P1 | `loginAsAccountDashboard.test`, `trialAdminFetchPatch.test` | token in storage, logout clears state, 401/403 handling, demo mode leakage (`src/demo`) into prod |
| 25 | Frontend: XSS render sites (7) | S-6 | U | P0 | `sanitizeHtml.test.ts` | per-site test |
| 26 | Frontend: general pages (118) | `src/pages` | smoke render, a11y | P2 | about 20 page tests | one smoke render per page with mocked API, error boundary, console-error budget |
| 27 | media-ops transcode | `FFmpegService`, `TranscodeJobValidator`, `MediaOpsController`, file upload (nginx allows 2g) | U, S | P1 | 6 files, 3 failing | command injection through file names/options, path traversal in `/media/**` (permitAll), upload limits, M-1/M-2 |
| 28 | Performance and load | dashboard endpoints, CUR queries, `ProactiveCacheRefreshService`, thread pools | L | P2 | `jmeter-tests/` present | run existing JMeter plans on UAT only; thread-leak regression (`.agents/tasks/thread-leak-fix`) |

## 7. Bug-hunt split

Each area produces: failing-test-first evidence for every confirmed bug, a list of tests added, and `findings` rated Critical/High/Medium/Low with file:line. Hunters write tests only; they do not change production code.

- Area A: billops + invoices + tickets. Matrix rows 1, 2, 11, 12, 3 (billops side), 4 (billops side), 22. Hypotheses S-6, S-7 (billops webhooks), S-3. Start files: `InvoiceManagementService`, `TicketService`, `TicketController`, `MessagingWebhookController`, `ExternalMessagingService`, `SupportAdminController`, `AwsBillPdfParser`, `GcpDataService`. Commands: `mvn -o test -B -pl billops -am`.
- Area B: xamops cost, CUR, GCP, Azure, rightsizing. Rows 7, 8, 9, 10, 23. Start files: `CurAdvancedMetricsService`, `CurSql`, `CurTagColumns`, `GcpCostService`, `GcpCostInsightsService`, `CspBillingQueryService`, `AzureCostInsightsService`, `XamOpsRightsizingService`, `EbsDiskRightsizingService`, `RightsizingApplyService`. Commands: `mvn -o test -B -pl xamops-service -am -Dtest='Cur*Test,Gcp*Test,...'`.
- Area C: security, WAR, compliance, inventory, CloudMap. Rows 17, 18, 16 (cache keys), 6 (credential leakage in DTOs/logs), S-4 sweep for those controllers, S-9, S-10. Start files: `IamPrivilegeAnalyzer`, `WarReportAssembler`, `*WarControlCatalog`, `SecurityFindingIngestService`, `AwsInventoryReconciler`, `InventoryEventIngestService`, `CloudMapService`, `ComplianceOpsController`.
- Area D: auth, gateway, calls, websocket, agent, infra, CI, media-ops, migrations, schedulers. Rows 4, 5, 13, 14, 15, 19, 20, 21, 27. Hypotheses S-1, S-2, S-3, S-5, S-7, S-8, S-11, M-1, M-2. Needs Docker for row 14. Commands: `mvn -o test -B -pl xamops-service,media-ops-service -am`, `cd xamops-agent && go test ./... && go vet ./...`, `cfn-lint cloudformation/*.yml` if installed.
- Area E: frontend pages, components, api layer. Rows 22 to 26, S-6 (render sites). Blocked until `npm ci` works (needs network). When unblocked: `cd frontend-app && npx vitest run && npx tsc --noEmit`. Start files: the seven `dangerouslySetInnerHTML` files, `src/api/curInsightsApi.ts`, `gcpCostInsightsApi.ts`, `azureCostInsightsApi.ts`, `src/contexts/CallContext.tsx`, `src/utils/apiClient.ts`, `BillOpsAdminInvoiceDetailPage.tsx`, `GcpCostPage.tsx`.

## 8. Execution order and exit criteria

1. Unblock tooling: start Docker, restore npm access (`cd frontend-app && npm ci`). Rerun all four suites for a clean baseline.
2. Hunt P0 rows first (A to D in parallel is safe because they write disjoint test files; they share one worktree so commit sequentially).
3. Each confirmed bug gets: a test that fails on current code (mark with `@Disabled("BUG-nn")` only if the fix is deferred, so CI stays green and the bug stays visible), severity, repro, suggested fix.
4. Exit: all P0 rows have at least one new passing or documented-failing test; a final report lists confirmed bugs, killed hypotheses, still-unverifiable items (anything needing Docker, network, real cloud accounts, UAT).

## 9. Not verifiable from this machine

Real AWS, GCP or Azure behavior (STS assume-role, Athena, BigQuery), UAT/prod nginx behavior, LiveKit media, Redis cluster behavior, Kafka, Postgres-specific Flyway behavior until Docker is up, and anything in the frontend runtime until `node_modules` is installed.
