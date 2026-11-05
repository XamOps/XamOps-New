# 09 — Build Order (MVP, 1.0, 2.0)

Planning only. No code or existing source file was changed to produce this document. It sequences the contracts already approved in 02 to 08. File names come from 04 (backend, pipeline, agent, CFN) and 05 (frontend); where a name is abbreviated here, the full path is in those documents. Phase scope and exit gates come from 08 section 1; this file does not redefine them.

Abbreviations: `ROOT` = `/Users/admin/Desktop/xam-latest/XamOps-New`; `OBS` = `ROOT/xamops-service/src/main/java/com/xammer/cloud/observability`; `TST` = `ROOT/xamops-service/src/test/java/com/xammer/cloud/observability`; `RES` = `ROOT/xamops-service/src/main/resources`; `OBSFE` = `ROOT/frontend-app/src/features/observability-v1`; `FE` = `ROOT/frontend-app/src`; `AGENT` = `ROOT/xamops-agent`; `PIPE` = `ROOT/xamops-obs-pipeline`.

## 0. Real build and test commands (found in the repo)

| Area | Evidence | Command |
|---|---|---|
| Java (all modules) | root `pom.xml` (`java.version` 25; modules include `xamops-service`); no `mvnw` in the repo, so system Maven | `cd ROOT && mvn -pl xamops-service -am -DskipTests compile` (build), `mvn -pl xamops-service test -Dtest='com.xammer.cloud.observability.**.*Test'` (unit), `mvn -pl xamops-service verify -Dtest='com.xammer.cloud.observability.**.*' -Dsurefire.failIfNoSpecifiedTests=false` (incl. `*IT`, Testcontainers needs Docker) |
| Frontend | `frontend-app/package.json` scripts: `build` = `icons:bundle && tsc && vite build`, `test` = `vitest run` | `cd ROOT/frontend-app && npm ci && npm run build` and `npx vitest run src/features/observability-v1 src/test/designRules.test.ts` (feature plus design-rule guard) and `npm test` (full suite, before merge) |
| Go agent | `xamops-agent/go.mod` (`module github.com/xamops/agent`, `go 1.25.0`), CI job `agent-build-verify` in `.github/workflows/ci-cd.yml` | `cd AGENT && go build ./... && go vet ./... && go test ./...` |
| Go pipeline | new module `PIPE/go.mod` (`github.com/xamops/obs-pipeline`), created in step P1 | `cd PIPE && go build ./... && go vet ./... && go test ./...` |
| Collector build | `AGENT/obs-otelcol/builder-config.yaml` (new) | `cd AGENT && ocb --config obs-otelcol/builder-config.yaml` then size-budget check script (R3: under 100 MB target) |
| CFN | new templates in `ROOT/cloudformation/` | `aws cloudformation validate-template --template-body file://<file>` (needs AWS credentials; if not available, mark "not verified" in findings) |
| Migrations | `RES/db/migration`, latest on disk at design time `V20261024_39`; Flyway runs in the Spring test context | `ObsMigrationsIT` (below) |

Rules for every step: run the step's verification before committing; commit locally per step; no push. Existing-file edits are limited to the list in 05 section 2 plus `api-gateway/nginx-prod.conf`, `application*.properties`, `xamops-service/pom.xml` (duckdb, at 1.0) and `.github/workflows/ci-cd.yml`. Re-list `RES/db/migration` right before creating the migrations; if anything sorts after `V20261024_39`, rename the six files to a later date (04 section 2.9).

## 1. Gate before any build (not code)

- **G0 (D2, blocks MVP start):** an owner for Kafka (MSK), VictoriaLogs, VictoriaMetrics, gateway collectors and the ingest domain. Without it, code tracks A to D can start but the E2E gate cannot pass. Fallback from R1: gateway disk queue feeding `hotwriter` and `archiver` through a second exporter.
- **G1:** week-3 spike on VictoriaMetrics single-node tenant enforcement via `extra_filters` (R7). Failure means cluster edition from MVP; it changes only `ObsVmClient` and `deploy/obs/`.
- **G2:** confirm implementation-time checks from 00-README section 5 (tenant table name for FKs, `AccountContext` numeric PK, `VITE_OBS_V1_ENABLED` convention, demo router, `EmailService` API). Record answers in `AGENT`-side step findings, not by guessing.

## 2. Parallelisation model

Tracks run in separate git worktrees off the same base branch, one worktree per track, each committing locally. Tracks never share a worktree (shared index and build output would race). Merge order is defined per milestone. Sequential inside a track.

| Track | Worktree | Owns (no overlap with other tracks) |
|---|---|---|
| A. Backend core | `wt-obs-be` | `OBS/**`, `TST/**`, `RES/observability/**`, `RES/db/migration/V20261025_*`, `application*.properties`, `xamops-service/pom.xml`, `api-gateway/nginx-prod.conf` |
| B. Agent | `wt-obs-agent` | `AGENT/cmd/obs-supervisor/**`, `AGENT/internal/obs/**`, `AGENT/scripts/obs_install.sh`, `AGENT/obs-otelcol/**`, `AGENT/Dockerfile.obs` |
| C. Pipeline | `wt-obs-pipe` | `PIPE/**`, `ROOT/deploy/obs/**` |
| D. Frontend | `wt-obs-fe` | `OBSFE/**`, the shared-file edits in 05 section 2 (`App.tsx`, `Sidebar.tsx`, `iconMap.ts`, `xamopsTabs.ts`, `designRules.test.ts`, `package.json`, `package-lock.json`, `vite.config.ts` if needed, demo router stub) |
| E. AWS templates | `wt-obs-cfn` | `ROOT/cloudformation/xamops-observability-v1-permissions.yaml`, `xamops-observability-v1-bucket.yaml` |

Cross-track contract is 06 (JSON, endpoints, agent config, Parquet schema). To parallelise safely, each track codes against 06 and the first integration point is the contract tests listed in each milestone. The `obs_install.sh` copy served by the backend (`RES/observability/agent-install/obs_install.sh`) is a byte-identical copy of the agent track's `scripts/obs_install.sh`; do it at integration (step M3), not in parallel.

---

## 3. MVP ("Connect, see, keep")

### 3.1 Dependency graph

```
G0/G1 ──────────────────────────────────────────────┐
A1 migrations → A2 config/security/domain/repos → A3 clients+query(XLQ) ─┬→ A5 onboarding+storage ─┬→ A7 alerts ─→ A8 settings/audit/usage
                                  │                                    └→ A4 logs/metrics/hosts ─┘
B1 supervisor core → B2 collector+render+redaction → B3 installer+update/uninstall ──┐
C1 module+kafka+hotwriter → C2 archiver(+manifest) → C3 deploy/obs                  ├→ M3 integration → M4 gate
E1 CFN permissions + bucket ────────────────────────────────────────────────────────┤
D1 shell/tokens/routes → D2 api+types → D3 connect → D4 hosts/logs/metrics → D5 alerts/storage/settings/overview ┘
```

### 3.2 Ordered steps and exact files

**Track A (backend).** Each step compiles with `mvn -pl xamops-service -am -DskipTests compile` and passes the named tests.

- **A1. Migrations.** `RES/db/migration/V20261025_1__obs_core_fleet.sql`, `V20261025_2__obs_onboarding_storage.sql`, `V20261025_3__obs_explore_content.sql`, `V20261025_4__obs_alerting_slo.sql`, `V20261025_6__obs_governance.sql` (content from 06 section 2). `V20261025_5__obs_smart_insights.sql` is deferred to 1.0 (the MVP has no insights). Test: `TST/migration/ObsMigrationsIT`. Verify: `mvn -pl xamops-service verify -Dtest=ObsMigrationsIT` creates the MVP table set on a clean Postgres.
- **A2. Foundation.** `config/ObsProperties`, `ObsSecurityConfig` (`@Order(0)` chain), `ObsAsyncConfig`, `ObsInternalConnectorConfig`, `ObsHttpClientConfig`, `ObsStartupValidator`, `ObsWebConfig`; `security/*` (all 10 files in 04 section 2.2 except `InstanceIdentityVerifier` can ship UNVERIFIED-only); `exception/*`; `util/*`; `domain/**` entities and enums for the MVP tables; `repository/**`; `dto/common`; `application.properties` keys (`obs.enabled=false`). Tests: `ObsAccessGuardTest`, `IngestTokenServiceTest`, `ObsPermissionDefaultsTest`, `ObsSecurityChainOrderTest`, `InternalKeyAuthFilterTest`. Verify: those tests pass and the whole existing `xamops-service` test suite still passes (`mvn -pl xamops-service test`) to prove the `@Order(0)` chain changes nothing else.
- **A3. Clients and query language.** `client/{TenantRef,ObsVlClient,ObsVmClient}`; `query/xlq/{XlqLexer,XlqParser,XlqAst,XlqValidator,LogsQlCompiler,XlqFormatter}`; `query/metrics/{MetricCatalog,MetricQueryValidator,MetricsQlCompiler,PromQlGuard}`; `RES/observability/metric-catalog.yaml`, `builtin-redaction.yaml`. (`DuckDbSqlCompiler` is 1.0.) Tests: `XlqParserTest` (18 golden cases), `XlqValidatorTest`, `LogsQlCompilerTest`, `MetricsQlCompilerTest`, `PromQlGuardTest`, `ObsVlClientTenantHeaderTest`, `ObsVmClientTenantFilterTest`. Verify: these tests pass.
- **A4. Fleet, agent gateway, logs and metrics APIs.** (needs A2, A3)
  - agent machine side: `controller/{ObsAgentGatewayController,ObsInternalController,ObsInstallScriptController}`, `service/agent/{AgentRegistrationService,AgentHeartbeatService,ConfigProfileService,AgentCommandService}`, `dto/agent`.
  - fleet: `controller/{ObsHostController,ObsHostGroupController,ObsOverviewController}`, `service/fleet/{HostService,HostStateJob,ObsHostReconcileJob,OverviewService}`, `dto/host`, `dto/settings` (`OverviewSummaryDto`).
  - logs and metrics: `controller/{ObsLogController,ObsLogTailController,ObsSavedQueryController,ObsSavedViewController,ObsMetricController}`, `service/logs/{ObsLogQueryService,LogFanOutMerger,ObsLogTailService,SavedQueryService,SavedViewService}`, `service/metrics/{ObsMetricQueryService,CorrelationService}`, `dto/logs`, `dto/metrics`.
  - Tests: `LogFanOutMergerTest`, `ObsLogTailServiceTest`, `AgentGatewayContractTest`, `CrossTenantDenialIT` (extend as controllers land). Verify: those pass.
- **A5. Onboarding and storage.** (needs A2; parallel with A4 inside the track only if two people share the worktree, otherwise after A4)
  - `controller/{ObsOnboardingController,ObsPermissionStackController,ObsStorageController}`, `service/onboarding/{DiscoveryService,PreflightService,InstallJobService,ObsInstallOrchestrator,InstallJobPoller,CommandBuilder,TrustManifestService,PermissionStackService}`, `service/storage/{BucketCatalogService,DestinationValidationService,LifecycleRuleGenerator,DataDeletionJob,CostEstimator,DestinationService,StorageUsageService,ManagedBucketService}`, `dto/onboarding`, `dto/storage`, `RES/observability/policies/{trust,operator,writer,reader,bucket}.json.tpl`, `templates/userdata.sh.tpl`, `prices-fallback.json`.
  - Tests: `DiscoveryServiceTest`, `PreflightServiceTest`, `InstallJobServiceTest`, `DestinationValidationServiceTest`, `LifecycleRuleGeneratorTest`, `DataDeletionJobTest`, `CostEstimatorTest`, `ConnectFlowRbacTest`. Verify: pass.
  - `MigrationService` and `AthenaDdlGenerator` are 1.0.
- **A6. Nginx and properties.** Edit `ROOT/api-gateway/nginx-prod.conf` (blocks in 04 section 5), `application-local.properties`/`-preprod`/`-prod` (`obs.*` values; check each profile's `management.server.port` before fixing 8081, per 04 section 5). Verify: `nginx -t -c` against the edited file if nginx is available (else state "not verified"), plus the backend compile.
- **A7. Alerting (MVP types only).** `controller/{ObsAlertRuleController,ObsIncidentController,ObsChannelController,ObsMuteWindowController}`, `alert/{ObsAlertScheduler,RuleEvaluator,ThresholdEvaluator,AbsenceEvaluator,LogPatternEvaluator,AlertStateMachine,IncidentService,RulePreviewService,MuteService}`, `util/ObsRrule` (one-off windows only in MVP), `notification/{ObsNotificationService,ChannelSecretCodec}`, `notification/channel/{SlackSender,EmailSender,WebhookSender}`, `dto/alerts`. Tests: `AlertStateMachineTest`, `ObsAlertSchedulerClaimIT`, `IncidentServiceDedupIT`. Verify: pass.
- **A8. Settings, audit, usage.** `controller/ObsSettingsController`, `service/audit/ObsAuditService`, `service/usage/UsageService`, `service/settings/{RedactionService,CostControlService,TenantSettingsService,TokenAdminService}`, `service/maintenance/{ObsRetentionJob,ObsTokenExpiryJob,ObsPermissionStackVerifyJob}`. Test: `ObsAuditServiceFailClosedTest`. Verify: pass; then run the whole observability test set.

**Track B (agent).** `go build ./... && go vet ./... && go test ./...` in `AGENT` after each step; the legacy `cmd/agent` must still build and its tests pass.

- **B1. Supervisor core.** `cmd/obs-supervisor/main.go`, `internal/obs/{enroll,identity,configfetch,heartbeat,health}/`. Verify: unit tests for enrollment, ETag config poll, heartbeat against an `httptest` server using the 06 section 9 shapes.
- **B2. Collector and rendering.** `obs-otelcol/builder-config.yaml`, `internal/obs/{render,supervise,redactrules}/` (render collector YAML from desired config, restart on change, built-in redaction corpus tests from 08 case 9, resource limits). Verify: `go test ./internal/obs/...`; `ocb` build produces the Linux amd64/arm64 binaries and the size budget script passes.
- **B3. Installer, update, uninstall.** `scripts/obs_install.sh` (flags `--preflight`, `--dry-run`, `--uninstall`), `internal/obs/{update,uninstall}/`, `Dockerfile.obs`. Verify: `go test ./internal/obs/...`, shellcheck on the script if installed, and a container-based dry-run/uninstall residue check (08 case 13) on at least Ubuntu 22.04 and Amazon Linux 2023.

**Track C (pipeline).**

- **C1. Module and hotwriter.** `PIPE/go.mod`, `PIPE/cmd/hotwriter/main.go`, `PIPE/internal/{config,kafka,tenant,vl,vm,metrics}`, `PIPE/Dockerfile`. Verify: `go test ./...` with Kafka testcontainer and recorded VL/VM responses.
- **C2. Archiver.** `PIPE/cmd/archiver/main.go`, `PIPE/internal/{parquet,s3,manifest,destinations}`. Verify: `go test ./...` against MinIO: layout exactness, ZSTD Parquet schema (06), 5-min/64-MB flush, manifest rows, sends `x-amz-server-side-encryption`, AccessDenied then degraded then managed fallback. (`cmd/compactor` is 1.0, but the MVP exit gate relies only on part files, which queries can read.)
- **C3. Deploy manifests.** `ROOT/deploy/obs/**` (compose profile `obs-e2e` with agent container, nginx, gateway collector, Kafka, VL, VM, MinIO, pipeline; helm or compose for the chosen D2 target). Verify: `docker compose -f ROOT/deploy/obs/docker-compose.obs-e2e.yml config` is valid and `up` brings services healthy.

**Track E (AWS).**

- **E1.** `cloudformation/xamops-observability-v1-permissions.yaml` (Operator, Writer, Reader roles, SSM docs, no `s3:DeleteObject` on Writer) and `xamops-observability-v1-bucket.yaml` (BPA, SSE-KMS, lifecycle). Verify: `aws cloudformation validate-template` and the A5 policy-template tests render byte-equal policy JSON (08 case 10f).

**Track D (frontend).** Each step: `cd frontend-app && npx tsc --noEmit && npx vitest run src/features/observability-v1 src/test/designRules.test.ts`.

- **D1. Shell, tokens, routes, shared edits.** Install exact-pinned `uplot` and `@tanstack/react-virtual`; `OBSFE/{ObservabilityV1Shell.tsx,index.ts}`, `constants/{tabs,sidebarIcons,queryKeys,permissions,shortcuts,sources,limits}.ts`, `styles/obsTokens.css`, `state/*`, `hooks/{useObsScope,useTimeRange,useCompareRange,useObsUrlState,useObsPermissions,usePrefersReducedMotion,useDebouncedValue,useInterval,useClipboard,useElementSize,useObsShortcuts}.ts`, `components/shell/*`, `utils/{format,time,validators,permissions,storageKeys}.ts`; shared edits in `App.tsx`, `Sidebar.tsx` (including the `-v1` prefix-collision fix), `iconMap.ts`, `xamopsTabs.ts`, `designRules.test.ts`, demo router stub. Tests: sidebar prefix test (08 case 23), `ObsStateBlock.test`, `ObsTimePicker.test`, `ObsCommandPalette.test`, `format.test`, `time.test`. Verify: `npm run build` succeeds (this also runs `icons:bundle`), listed tests pass, `npm test` shows no new failures versus base.
- **D2. API client and types.** `OBSFE/api/{obsClient,overviewApi,onboardingApi,hostsApi,agentsApi,logsApi,metricsApi,alertsApi,storageApi,settingsApi}.ts`, `types/*.ts` (MVP-relevant), `utils/sse.ts`. Verify: tsc clean; `useLogTail` and `sse` tests when D4 lands.
- **D3. Connect flow.** `pages/{ConnectPage,StorageConnectPage}.tsx`, `components/connect/*`, `hooks/{useDiscovery,useInstallJob,useInstallCommand,usePermissionStack,useBuckets,useDestinationValidation}.ts`. Verify: `ConnectPage.test`, `useInstallJob.test`, design rules.
- **D4. Hosts, logs, metrics.** `pages/{HostsPage,HostDetailPage,LogsPage,MetricsPage}.tsx`, `components/{hosts,logs,metrics,charts,common}/*` (MVP subset: no `PatternsView`, `ArchiveSearchPanel`, `ArchivePlanCard`, `ArchiveJobProgress`, `AiSummaryButton`, `NlQueryBox`, dashboards components), `hooks/{useHosts,useHostDetail,useHostProcesses,useHostTimeline,useFleet,useLogSearch,useLogHistogram,useLogFacets,useLogTail,useQueryValidation,useSavedQueries,useSavedViews,useMetricCatalog,useMetricSeries,useCorrelation}.ts`, `utils/{xlq,facets,colors,health,download}.ts`. Verify: `useLogTail.test`, `xlq.test`, `facets.test`, `LogsPage.test` (hot path), design rules, `npm run build`.
- **D5. Overview, alerts, storage, agents (read-only), settings.** `pages/{OverviewPage,AlertsPage,AlertRulesPage,AlertRuleEditorPage,ChannelsPage,MutesPage,StoragePage,AgentsPage,SettingsPage,ObsNotFound}`, `components/{overview,alerts (Threshold/Absence/LogPattern forms only),storage (basic),agents (read-only table),settings}/*`, `hooks/{useAlertRules,useIncidents,useChannels,useMutes,useRulePreview,useStorageOverview,useUsage,useSettings}.ts`. Verify: tsc, vitest for the feature, `npm run build`.

### 3.3 MVP integration and merge order

1. **M1.** Merge E1, then A1 to A8 in order, then D1 to D5 (D can merge once its tests pass against mocked APIs; it does not need A merged). Re-run `npm test` and `mvn -pl xamops-service test` after each merge.
2. **M2.** Merge B1 to B3 and C1 to C3 (independent of A/D at the code level).
3. **M3. Integration.** Copy `AGENT/scripts/obs_install.sh` to `RES/observability/agent-install/obs_install.sh` and add the identical-file test; run the `obs-e2e` compose stack: fresh agent container enrols, sends logs and metrics, appears Connected in the API, logs searchable, a Parquet object lands in MinIO with a manifest row.
4. **M4. Gate.** Run the MVP exit gates of 08 section 1.4 (items 1 to 7). Add the CI jobs `obs-backend-test`, `obs-pipeline-test`, `obs-agent-build`, `obs-frontend-test` to `.github/workflows/ci-cd.yml` here (this is the only CI edit in MVP).

### 3.4 MVP verification command set

```
cd ROOT && mvn -pl xamops-service -am test -Dtest='com.xammer.cloud.observability.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false
cd ROOT && mvn -pl xamops-service verify -Dtest='com.xammer.cloud.observability.**.*IT' -Dsurefire.failIfNoSpecifiedTests=false
cd ROOT/frontend-app && npm ci && npm run build && npx vitest run src/features/observability-v1 src/test/designRules.test.ts && npm test
cd ROOT/xamops-agent && go build ./... && go vet ./... && go test ./...
cd ROOT/xamops-obs-pipeline && go build ./... && go vet ./... && go test ./...
docker compose -f ROOT/deploy/obs/docker-compose.obs-e2e.yml --profile obs-e2e up --abort-on-container-exit   # scripted golden path
```

(The compose file name is chosen in C3; update this line if C3 names it differently.)

### 3.5 MVP Definition of Done

- All MVP files above exist, build, and their tests pass on the commands in 3.4; existing test suites show no new failures.
- 08 section 1.4 MVP gates 1 to 7 pass (time-to-first-data, cross-tenant suite, 7-day soak, BYO validation of seeded misconfigurations, uninstall residue check, design-rule test with the feature in `GUARDED_PATHS`, UX checklist items 1 to 7).
- Beta page unchanged: `git diff` of the MVP touches no file under `FE/components/Observability/`, `FE/pages/ObservabilityPage.tsx`, `FE/styles/observability.css`; sidebar test proves group isolation.
- `obs.enabled=false` and `VITE_OBS_V1_ENABLED` off hide everything (08 case 23); enabled in local/preprod only.
- Runbooks exist for onboarding failure, S3 AccessDenied, agent silent, pipeline lag.
- Findings recorded for every item in 00-README section 5 that was resolved or still open.

---

## 4. Release 1.0 GA

Starts after the MVP gate. Order inside each track; tracks stay parallel in worktrees. Everything below is additive to MVP files.

### 4.1 Steps and files

**Track A (backend)**
- **A9. Migration** `V20261025_5__obs_smart_insights.sql`; entities `ObsInsight`, `ObsBaselineStat` completed. Verify `ObsMigrationsIT` equals the 41-table list in 06.
- **A10. Archive search.** `query/xlq/DuckDbSqlCompiler`, `cold/{ArchivePlanner,ColdQueryJobService,ColdQueryRunner,DuckDbSessionFactory}`, `controller/ObsQueryJobController`, `service/logs/PatternService`, `smart/{PatternMiner,ObsPatternMinerJob}`, `xamops-service/pom.xml` (`org.duckdb:duckdb_jdbc`, exact pin, licence check). Tests: `DuckDbSqlCompilerTest`, `ArchivePlannerTest`, `ColdQueryRunnerIT`, `PatternMinerTest`. Needs C4 for the real Parquet contract test.
- **A11. Smart.** `smart/{ObsBaselineJob,AnomalyScorer,ObsForecastJob,ForecastMath,NoiseAdvisor,RightsizingAdvisor}`, `controller/ObsInsightController`, `dto/insight`, `alert/{AnomalyEvaluator,ForecastEvaluator,CompositeEvaluator,RateOfChangeEvaluator,SloBurnEvaluator}`. Tests: `ForecastMathTest`, `AnomalyScorerTest`.
- **A12. SLO, escalation, channels.** `controller/{ObsSloController,ObsEscalationController}`, `slo/{SloService,SloSnapshotJob}`, `notification/channel/{TeamsSender,PagerDutySender,SnsSender}`, full `ObsRrule` recurrence incl. DST. Tests: rule-type matrix with synthetic series, `CostEstimator` unchanged.
- **A13. Containers, services, dashboards.** `controller/{ObsContainerController,ObsServiceController,ObsDashboardController}`, `service/fleet/{ContainerService,ServiceDiscoveryService}`, `service/metrics/{DashboardService,SuggestedDashboardService}`, `RES/observability/dashboards/*.json`.
- **A14. Fleet management and storage depth.** `controller/ObsAgentFleetController`, `service/agent/{ReleaseService,RolloutService}`, `service/storage/{MigrationService,AthenaDdlGenerator}`, `InstanceIdentityVerifier` full verification, `RES/observability/aws-instance-identity-certs.pem`, remaining `templates/*.tpl` (launch template, terraform, CFN association, ECS, EKS, packer).
- **A15. AI.** `controller/ObsAiController`, `ai/{ObsAiService,EvidenceBuilder,LlmGateway,HttpLlmGateway}`, `dto/ai`; remains off until D7 is answered and tenant opt-in is set.
- **A16. Cluster mode.** Config switch in `ObsVmClient` for VictoriaMetrics cluster paths (no new class).

**Track B (agent)** — B4: Windows (`internal/obs/**` platform files, PowerShell installer, MSI), containers receivers in builder config and render, Prometheus scrape and OTLP loopback profile, ASG token support, update signing verification (cosign/minisign), staged-rollout client behaviour (canary, halt report), token auto-rotation. Verify: `go test ./...` on linux and windows (`GOOS=windows go vet ./...`), collector size budget, install matrix of 08 case 13 on all distros and both architectures.

**Track C (pipeline)** — C4: `PIPE/cmd/compactor/main.go` and compaction tagging; cold-query contract fixtures (Parquet produced by the Go archiver consumed by the Java DuckDB tests); backfill and fallback-to-managed logic (D10); VM cluster deployment in `deploy/obs/`; autoscaling on lag. Verify: `go test ./...` plus chaos suite of 08 case 29.

**Track E (AWS)** — E2: dedicated-bucket stack parameters, State Manager association template, Terraform module snippet outputs. Verify: validate-template and policy rendering tests.

**Track D (frontend)**
- **D6. Archive search and patterns.** `components/logs/{ArchiveSearchPanel,ArchivePlanCard,ArchiveJobProgress,PatternsView,PatternRow,SavedQueriesView,LogsExportMenu,AiSummaryButton,NlQueryBox}.tsx`, `hooks/{useQueryJob,useLogPatterns}.ts`, routes `/logs/patterns`, `/logs/saved`, `/logs/archive/:jobId`, compare-to-previous in `ObsTimePicker`.
- **D7. Metrics depth, containers, services, dashboards.** `pages/{ContainersPage,ContainerDetailPage,ServicesPage,ServiceDetailPage,DashboardPage}.tsx`, `components/{containers,services}/*`, `components/metrics/{DashboardsView,DashboardGrid,DashboardWidget,SuggestedDashboards}.tsx`, `hooks/{useContainers,useServices,useServiceMap,useDashboards}.ts`.
- **D8. Alerts completion and smart UI.** `pages/{SlosPage}`, `components/alerts/{AnomalyForm,ForecastForm,CompositeForm,SloBurnForm,EscalationEditor,RcaPanel,SloCard,ErrorBudgetBar,SlosView}`, `components/overview/{InsightsCard,NoisySourcesCard,CostCard}`, `hooks/{useSlos,useInsights,useAi}.ts`.
- **D9. Fleet and storage depth.** `components/agents/{VersionDriftBar,RolloutPanel,ReleaseList,ConfigProfileEditor,BulkActionBar,AgentHealthDrawer,TokenRotationCard}`, `components/storage/{MigrateDialog,AthenaDdlDialog,TierDistribution,PermissionStackCard}`, `components/settings/{AiSettings,CostControlsSettings}`.
- **D10. Polish.** Full UX checklist (07 section 8), keyboard and focus tests (08 case 27), perf test (case 28), a11y pass; `npm test` clean.

### 4.2 1.0 merge order

1. A9 → A10 → A11 → A12 → A13 → A14 → A15 → A16 (A10 before A11 is only required for pattern-based insights; otherwise A11 to A13 are independent and may merge in any order).
2. C4 and B4 before the cold-query contract test is turned on.
3. D6 to D10 each after the API it consumes is merged, but can be developed earlier against 06.
4. Final integration: full `obs-e2e` run including archive search, rollout with canary and halt, and the chaos suite.

### 4.3 1.0 verification

```
cd ROOT && mvn -pl xamops-service verify -Dtest='com.xammer.cloud.observability.**.*' -Dsurefire.failIfNoSpecifiedTests=false
cd ROOT/frontend-app && npm ci && npm run build && npm test
cd ROOT/xamops-agent && go build ./... && go vet ./... && go test ./... && GOOS=windows GOARCH=amd64 go vet ./internal/obs/...
cd ROOT/xamops-obs-pipeline && go build ./... && go vet ./... && go test ./...
k6 run ROOT/deploy/obs/loadtest/ingest-and-query.js      # 2,000 simulated hosts; file created in C4
```

### 4.4 1.0 Definition of Done

- All MVP DoD items still hold and the 41-table migration set matches 06.
- 08 section 1.4 1.0 gate: load test (p95 hot query under 1.5 s, tail p95 under 3 s, archiver lag p95 under 10 min at 2,000 hosts), chaos suite, pen-test High/Critical fixed, manual accessibility audit (VoiceOver, NVDA, keyboard-only; automated tests alone do not count), Windows and ARM matrices green, full UX checklist.
- All 08 section 4.2 test cases (1 to 29) implemented and green, including the injection corpus, fan-out, tail contract, IAM and lifecycle generators.
- D4, D5 (pricing, Windows scope), D7 (AI), D8, D9 to D11, D13 answered or defaults accepted in writing.
- Runbooks complete (08 section 6 item 3), licence check job green, "observability of the observability" dashboards and alerts live.
- Legal review of retention and personal-data handling (R20) done before GA.

---

## 5. Release 2.0 (direction, not scope-locked; defined at the 1.0 retrospective)

Order by dependency, each item its own feature branch and worktree once scoped. File names below are indicative, to be fixed when 2.0 is specified.

1. **Multi-region ingest and per-tenant hot retention.** Needs decision on VictoriaLogs Enterprise vs ClickHouse (R6). Touches `deploy/obs/`, `ObsVlClient`, `PIPE/internal/{kafka,tenant}`.
2. **Traces/APM.** Collector traces pipeline in `AGENT/obs-otelcol/builder-config.yaml`; trace store (VictoriaTraces or Jaeger v2) in `deploy/obs/`; new `client/ObsTraceClient`, `controller/ObsTraceController`, migration `V2026xxxx_*__obs_traces.sql`; frontend `OBSFE/pages/TracesPage.tsx` and trace-log-metric pivots.
3. **eBPF auto-instrumentation, profiling, log-derived metrics, DB slow-log parsers.** Agent-side first (opt-in profiles), then metric catalog entries and UI panels.
4. **EKS workload depth and Fargate sidecar.** Extends `templates/eks-daemonset.yaml.tpl`, `components/containers/*`.
5. **OpAMP remote config, direct-to-S3 private mode, cross-account buckets.** Changes the agent config contract (06 section 9) and archiver destinations; needs a design addendum first.
6. **Synthetic checks, status pages, deploy-event overlays (CloudTrail).** New controllers and `ObsAnnotationLayer` sources.

2.0 verification: the 1.0 command set plus per-feature tests defined in the 2.0 spec.

2.0 Definition of Done: a written 2.0 scope document approved after the 1.0 retrospective; each shipped item has its own contract addendum in 06, migrations (additive only), tests in the 1.0 command set, an updated risk register and an updated UX checklist; no regression of the 1.0 gates (rerun load test and a11y audit for UI changes).

---

## 6. Rollout order per environment (all milestones)

1. Local with `obs.enabled=true`, `VITE_OBS_V1_ENABLED=true`.
2. Preprod with the design-partner account.
3. Prod dark (migrations applied, flags off, infra up).
4. Prod pilot: assign `observability-v1-*` tabs to pilot users and set `obs.pilot.client-ids`.
5. GA flag flip. Rollback is flag-off; migrations are additive and stay.

## 7. Open items that can stall the plan

- D2 (infra owner) blocks the MVP E2E gate; G1 spike may force VM cluster edition.
- Version pins (uPlot, `@tanstack/react-virtual`, `duckdb_jdbc`, OTel collector, Go libs) are verified at install time, not now.
- CFN `validate-template` and the nginx syntax check need tools or credentials that may be absent locally; the step must then record "not verified" in its findings.
- Time estimates are in 08 section 1 and are not repeated here.
