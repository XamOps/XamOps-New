# 04 — Backend Plan (Spring, `com.xammer.cloud.observability.*`)

Rule: everything new lives under `ROOT/xamops-service/src/main/java/com/xammer/cloud/observability/` (abbrev **OBS**). Nothing outside `observability.*` depends on it. Existing classes are used only as listed in 02 section 11. Component, entity and repository scanning needs no config: `CloudDashboardApplication` sits in `com.xammer.cloud` and no `@EntityScan`/`@EnableJpaRepositories` exists (checked), so sub-packages are scanned by default. Scheduling and async are already enabled (`@EnableScheduling`, `@EnableAsync` on the application class).

Absolute prefix used below: `OBS = /Users/admin/Desktop/xam-latest/XamOps-New/xamops-service/src/main/java/com/xammer/cloud/observability`, `RES = .../xamops-service/src/main/resources`, `TST = .../xamops-service/src/test/java/com/xammer/cloud/observability`.

## 1. Cross-cutting design decisions

1. **Tenant source of truth.** Controllers take `@AuthenticationPrincipal ClientUserDetails user`. `ObsAccessGuard.requireAccount(user, cloudAccountId)` wraps `CloudAccountAccessService.requireAccess` (the only chokepoint, per inspection) and returns an `ObsAccountScope` (clientId, CloudAccount). `clientId` always comes from `user.getClientId()`. Request bodies never carry a client id.
2. **No thread-local reliance.** Background jobs and async executors receive `clientId`/`cloudAccountId` explicitly (the existing `TenantContext` is a constant `"system"`).
3. **Permissions.** `ObsPermissionService.require(user, ObsPermission.LOGS_READ)` is called by `ObsAccessGuard` (`guard.logsRead(user, scope)`), so a controller method has a single line of authorization. Platform admin roles (`ROLE_SUPER_ADMIN`, `ROLE_XAMOPS`, `ROLE_ADMIN`) hold all permissions; `ROLE_XAMOPS` holds the six Connect-capable permissions in 06 section 11 (the one default table that `obs.rbac.defaults.*` mirrors).
4. **DTOs are records** (Java 17+; confirm the project's `java.version` in `xamops-service/pom.xml` at implementation; if < 17 use Lombok `@Value`). Entities follow the repo style (`@Entity`, `@Table(name=...)`, explicit `@Column`, Lombok `@Data`), JSON columns are `String` + `ObsJson` helper.
5. **Errors.** `ObsException(ObsErrorCode code, String message, Map<String,Object> details)`; `ObsExceptionHandler` is `@RestControllerAdvice(basePackages = "com.xammer.cloud.observability.controller")` at `Ordered.HIGHEST_PRECEDENCE` so it cannot alter other modules' behaviour. It maps `AccessDeniedException` to `FORBIDDEN` (same body as not-found), validation failures to `VALIDATION_FAILED`, AWS `S3Exception/SsmException/StsException` with status 403 to `AWS_ACCESS_DENIED` (424) and throttling to `AWS_THROTTLED`. Envelope: 06 section 1.
6. **Logging.** SLF4J (repo style `LoggerFactory`). INFO for lifecycle (job created/finished, destination status change), WARN for degraded dependencies with `requestId`, ERROR only for unexpected exceptions. Never log log-lines, queries, tokens or role external ids.
7. **Rate limiting.** Bucket4j via existing `ratelimit/` + `config/Bucket4jCacheConfig.java`: `obs-query` 10 req/s/user, `obs-control` 5 req/s/user, `obs-agent` 3 req/s/host, `obs-ingest-auth` unlimited (cached). Keys include client id.
8. **Idempotency.** Agent register is idempotent per `(cloud_account_id, host_key)`; install job creation accepts header `Idempotency-Key` (stored in a 10-minute Redis key) to prevent double-click duplicates.
9. **Transactions.** Service methods that write multiple tables are `@Transactional`; AWS and HTTP calls are made **outside** transactions (state is persisted first, then the call, then a short transaction to record the outcome).

## 2. Package layout and files

### 2.1 `config` — OBS/config

| File | Responsibility |
|---|---|
| `ObsProperties.java` | `@ConfigurationProperties(prefix="obs")`, nested records for `ingest`, `vl`, `vm`, `aws`, `agent`, `cold`, `alert`, `managedBucket`, `ai`, `rbac`, `internal` (section 4) |
| `ObsSecurityConfig.java` | `@Bean @Order(0) SecurityFilterChain obsMachineChain(HttpSecurity)`: `securityMatcher("/api/obs/v1/agent/**","/api/obs/v1/internal/**","/obs-agent/**")`, stateless, CSRF off, `/obs-agent/**` and `POST /api/obs/v1/agent/register` permitAll, `/agent/**` filtered by `IngestTokenAuthFilter`, `/internal/**` by `InternalKeyAuthFilter`, everything else in the matcher `denyAll` if no principal. Does **not** touch `config/SecurityConfig.java` |
| `ObsAsyncConfig.java` | Named `ThreadPoolTaskExecutor` beans: `obsInstallExecutor` (core 4, max 16), `obsQueryExecutor` (8/32, queue 200, caller-rejects → `RATE_LIMITED`), `obsAlertExecutor` (8/16), `obsNotifyExecutor` (4/8), `obsColdExecutor` (profile `obs-cold-worker` only; core = `obs.cold.max-concurrent`) |
| `ObsInternalConnectorConfig.java` | `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` that, when `obs.enabled=true` and `obs.internal.listen-port` is set, adds a second Tomcat `Connector` on that port (plain HTTP, bound to the pod/host private interface). `ObsSecurityConfig` permits `/api/obs/v1/internal/**` only for requests whose local port equals the private port; on the public port the filter answers 404 |
| `ObsHttpClientConfig.java` | `RestClient` beans `obsVlRestClient`, `obsVmRestClient`, `obsLlmRestClient` (timeouts: connect 2 s, read 30 s; read 3600 s variant for tail), connection pools |
| `ObsStartupValidator.java` | `@EventListener(ApplicationReadyEvent)`: fails fast (when `obs.enabled=true`) if required properties are missing; checks `max(cloud_account.id)` and `max(client.id)` < 2^32 (VictoriaLogs tenant limits) and logs a WARN above 2^31 |
| `ObsWebConfig.java` | Registers `ObsRequestIdFilter` (adds `X-Request-Id`), Jackson config for Instants as ISO-8601 millis |

Feature flag: `obs.enabled` (default `false` in `application.properties`, `true` in local/preprod). When false, `@ConditionalOnProperty` on controllers/schedulers keeps the module inert (Beta page and everything else unaffected).

### 2.2 `security` — OBS/security

| File | Responsibility |
|---|---|
| `ObsPermission.java` | enum of keys in 06 section 11 (`VIEW("obs.view")` ...) |
| `ObsPermissionService.java` | Resolves effective permissions: role defaults (`obs.rbac.defaults.<ROLE>` properties, `*` = all) ∪ grants in `obs_user_permission` minus `-`-prefixed denies. Cached 60 s in Redis per `(clientId,userId)`; `require()` throws `FORBIDDEN` |
| `ObsAccessGuard.java` | Single entry for controllers: `ObsAccountScope account(user, cloudAccountId, ObsPermission)`, `List<CloudAccount> accounts(user, List<Long> ids, ObsPermission)` (empty list = every account the user `canAccess` of `CloudAccountRepository.findByClientId`), `void clientPermission(user, ObsPermission)`. Enforces the pilot allow-list `obs.pilot.client-ids` (non-listed clients get 404 `NOT_FOUND`). Logs denied attempts at DEBUG |
| `ObsAccountScope.java` | record `(Long clientId, CloudAccount account)` |
| `TokenHasher.java` | SHA-256 hex, constant-time compare helper |
| `EnrollmentTokenService.java` | Mint (`xo_enr_` + 32 random bytes base64url), hash, validate (`expires_at`, `max_uses`, `revoked_at`), atomic `use_count` increment (`UPDATE ... WHERE use_count < max_uses`) |
| `IngestTokenService.java` | Mint (`xo_ing_`...), validate with Redis cache `obs:ing:{hash}` TTL 60 s (negative cache 10 s), rotate with 10-min overlap, revoke (cache evict), expiry job hook |
| `IngestTokenAuthFilter.java` | `OncePerRequestFilter` for `/api/obs/v1/agent/**`: reads `X-XamOps-Ingest-Token`, sets `AgentHostPrincipal` or 401 |
| `InternalKeyAuthFilter.java` | Guards `/api/obs/v1/internal/**`: requires `X-Obs-Internal-Key` equal to `obs.internal.key` (constant-time compare) **and**, when `obs.internal.require-private-network=true`, that the request arrived on the private connector (local port == `obs.internal.listen-port`) and `request.getRemoteAddr()` is inside `obs.internal.allowed-cidrs`. `X-Forwarded-For` is never trusted for this decision. Single rule: request on the public port, or from a non-allowed CIDR, gets **404** (path does not reveal itself); request on the private port from an allowed CIDR with a missing or wrong key gets **401**. nginx treats any non-2xx from `/_obs_auth` as deny: upstream 401/403 become 401 to the agent, upstream 404/5xx or timeout become 503 (fail closed, never expose ingest), so a misconfigured key cannot open the ingest path |
| `AgentHostPrincipal.java` | record `(hostId, clientId, cloudAccountId, hostKey)` |
| `InstanceIdentityVerifier.java` | Verifies the EC2 instance identity PKCS7 signature against bundled regional certs (`RES/observability/aws-instance-identity-certs.pem`); output `VERIFIED|UNVERIFIED|MISMATCH`. MVP: result recorded, mismatch of account id rejects (`IDENTITY_MISMATCH`), missing signature accepted as UNVERIFIED |

### 2.3 `domain` — OBS/domain (entities, one per table in 06 section 2)

`ObsConfigProfile`, `ObsHostGroup`, `ObsAgentEnrollmentToken`, `ObsHost`, `ObsIngestToken`, `ObsAgentRelease`, `ObsAgentRollout`, `ObsAgentCommand`, `ObsContainer`, `ObsInstallJob`, `ObsInstallJobTarget`, `ObsPermissionStack`, `ObsStorageDestination`, `ObsStorageUsageDaily`, `ObsArchiveManifest`, `ObsQueryJob`, `ObsSavedQuery`, `ObsSavedView`, `ObsDashboard`, `ObsLogPattern`, `ObsService`, `ObsServiceEdge`, `ObsNotificationChannel`, `ObsEscalationPolicy`, `ObsAlertRule`, `ObsAlertState`, `ObsIncident`, `ObsIncidentEvent`, `ObsNotificationDelivery`, `ObsMuteWindow`, `ObsSlo`, `ObsSloSnapshot`, `ObsInsight`, `ObsBaselineStat`, `ObsRedactionRule`, `ObsCostControl`, `ObsUsageHourly`, `ObsAuditLog`, `ObsTenantSettings`, `ObsUserPermission`, `ObsDeletionRequest` (41 entities, table = snake_case of class name with `obs_` prefix, e.g. `ObsHost` → `obs_host`).

Enums (OBS/domain/enums): `HostState`, `InstallMethod`, `InstallTargetState`, `InstallJobStatus`, `StorageMode`, `StorageStatus`, `QueryJobKind`, `QueryJobStatus`, `AlertRuleType`, `AlertSeverity`, `AlertStateValue`, `IncidentStatus`, `ChannelType`, `InsightType`, `LogType` (`application, container, system, auth, web, windows-event, metrics`), `AgentCommandType`, `ReleaseChannel`. Enum names match the strings in 06 exactly (stored as `VARCHAR`, `@Enumerated(EnumType.STRING)`).

### 2.4 `repository` — OBS/repository

One Spring Data JPA interface per entity (`ObsHostRepository`, ...). Naming rule: finders that return tenant data include `ClientId` (for example `findByIdAndClientId`, `findByClientIdAndCloudAccountIdIn`). Notable custom queries:

- `ObsHostRepository`: `findByCloudAccountIdAndHostKey`, paged filter query (state, region, tag via `labels_json LIKE`, search by name/id), `countByClientIdAndState`, `findSilent(Instant cutoff)`.
- `ObsAlertRuleRepository`: `@Query(nativeQuery) claimDue(int limit)` with `FOR UPDATE SKIP LOCKED` (used inside a transaction by `ObsAlertScheduler`).
- `ObsArchiveManifestRepository`: `findPlan(cloudAccountId, hostKeys, types, fromHour, toHour)`, `sumBytesByDay(...)`.
- `ObsIncidentRepository`: `findOpenByDedup(clientId, dedupKey)`.
- `ObsAuditLogRepository`: insert + paged read only (no update/delete methods; trigger enforces).

### 2.5 `dto` — OBS/dto (records)

- `dto/common`: `ScopeDto`, `TimeRangeDto`, `PageDto<T>`, `ErrorEnvelope`, `IdResponse`.
- `dto/onboarding`: `DiscoveryResponse`, `DiscoveredInstanceDto`, `PreflightCheckDto`, `PreflightRequest`, `CreateInstallJobRequest`, `InstallJobResponse`, `InstallTargetDto`, `InstallCommandRequest`, `InstallCommandResponse`, `SnippetRequest`, `SnippetResponse`, `TrustManifestDto`, `PermissionStackDto`, `PolicyPreviewDto`, `LaunchUrlResponse`.
- `dto/host`: `HostSummaryDto`, `HostDetailDto`, `HostPatchRequest`, `ProcessSnapshotDto`, `TimelineEventDto`, `HostGroupDto`, `ContainerDto`, `ServiceDto`, `ServiceMapDto`.
- `dto/agent`: `RegisterRequest`, `RegisterResponse`, `HeartbeatRequest`, `HeartbeatResponse`, `AgentCommandDto`, `DesiredConfigDto`, `ReleaseManifestDto`, `FleetResponse`, `RolloutRequest`, `BulkCommandRequest`, `ConfigProfileDto`.
- `dto/logs`: `LogSearchRequest`, `LogSearchResponse`, `LogRowDto`, `HistogramResponse`, `FacetsResponse`, `StatsResponse`, `PatternsResponse`, `ContextRequest`, `ValidateQueryRequest/Response`, `QueryJobRequest`, `QueryJobDto`, `SavedQueryDto`, `SavedViewDto`.
- `dto/metrics`: `MetricQueryRequest`, `MetricQueryResponse`, `MetricSeriesDto`, `MetricCatalogEntryDto`, `CorrelateRequest`, `CorrelateResponse`, `DashboardDto`.
- `dto/alerts`: `AlertRuleDto`, `RulePreviewRequest/Response`, `IncidentDto`, `IncidentDetailDto`, `ChannelDto`, `EscalationPolicyDto`, `MuteWindowDto`, `SloDto`, `SloBudgetDto`.
- `dto/storage`: `BucketDto`, `BucketListResponse`, `ValidateDestinationRequest`, `ValidationResultDto`, `CostEstimateDto`, `DestinationDto`, `StorageOverviewDto`, `UsagePointDto`, `MigrateRequest`.
- `dto/insight`: `InsightDto`, `RightsizingHintDto`, `NoisePatternDto`.
- `dto/ai`: `SummarizeRequest/Response`, `NlQueryRequest/Response`, `RcaRequest/Response`.
- `dto/settings`: `TenantSettingsDto`, `RedactionRuleDto`, `RedactionPreviewRequest/Response`, `CostControlDto`, `TokenDto`, `UserPermissionDto`, `AuditEntryDto`, `UsageDto`, `OverviewSummaryDto`.

Validation uses Jakarta annotations (`@NotNull`, `@Size`, `@Pattern`) plus `ObsValidators` for domain rules (instance-id regex reuses `RemoteCommandInputValidator.requireValidInstanceId`; region regex reused).

### 2.6 `controller` — OBS/controller

All `@RestController`, `@RequestMapping("/api/obs/v1")`, `@ConditionalOnProperty("obs.enabled")`. Each method: guard call → service call → DTO.

| Controller | Endpoints (06 section 4) |
|---|---|
| `ObsOverviewController` | `GET /overview/summary`, `GET /overview/fleet-health`, `GET /me/permissions`, `GET /me/accounts` |
| `ObsInsightController` | `GET /insights`, `POST /insights/{id}/dismiss`, `GET /insights/rightsizing`, `GET /insights/noise` |
| `ObsOnboardingController` | `GET /onboarding/accounts/{cloudAccountId}/discovery`, `POST /onboarding/preflight`, `POST /onboarding/jobs`, `GET /onboarding/jobs/{jobId}`, `POST /onboarding/jobs/{jobId}/targets/{targetId}/retry`, `POST .../cancel`, `POST .../rollback`, `POST /onboarding/install-command`, `POST /onboarding/snippets`, `GET /onboarding/trust-manifest` |
| `ObsPermissionStackController` | `GET|POST /accounts/{cloudAccountId}/permission-stack[/policy-preview|/launch-url|/verify]` |
| `ObsHostController` | `/hosts`, `/hosts/{hostId}`, `/hosts/{hostId}/processes`, `/hosts/{hostId}/timeline`, `PATCH /hosts/{hostId}`, `POST /hosts/{hostId}/disconnect` |
| `ObsHostGroupController` | `/host-groups` CRUD |
| `ObsContainerController` | `/containers`, `/containers/{containerId}` |
| `ObsServiceController` | `/services`, `/services/{serviceId}`, `/services/map` |
| `ObsAgentFleetController` | `/agents/fleet`, `/agents/releases`, `/agents/rollouts*`, `/agents/commands`, `/agents/config-profiles*` |
| `ObsLogController` | `/logs/search|histogram|facets|stats|context|patterns|validate|fields` |
| `ObsLogTailController` | `GET /stream/logs/tail` (returns `SseEmitter`) |
| `ObsQueryJobController` | `/query-jobs*` |
| `ObsSavedQueryController` / `ObsSavedViewController` | `/saved-queries`, `/saved-views` |
| `ObsMetricController` | `/metrics/catalog|query|labels|correlate` |
| `ObsDashboardController` | `/dashboards`, `/dashboards/suggested` |
| `ObsAlertRuleController` | `/alert-rules*` incl. `/preview` |
| `ObsIncidentController` | `/incidents*` |
| `ObsChannelController` | `/notification-channels*` |
| `ObsEscalationController` | `/escalation-policies*` |
| `ObsMuteWindowController` | `/mute-windows*` |
| `ObsSloController` | `/slos*` |
| `ObsAiController` | `/ai/summarize`, `/ai/nl-query`, `/ai/rca` |
| `ObsStorageController` | `/storage/overview`, `/storage/buckets`, `/storage/destinations*` (validate, create-bucket-url, migrate, usage, athena-ddl) |
| `ObsSettingsController` | `/settings/tenant|redaction-rules|cost-controls|tokens|permissions|audit`, `/usage` |
| `ObsAgentGatewayController` | machine: `/agent/register|config|heartbeat|commands/{id}/result|token/rotate|releases/latest|update-report|uninstalled` |
| `ObsInternalController` | machine: `/internal/ingest-auth|gateway/rules|archiver/destinations|archiver/manifests|archiver/status|usage|host-activity` |
| `ObsInstallScriptController` | `GET /obs-agent/install.sh` (classpath `observability/agent-install/obs_install.sh`, same pattern as `AgentInstallController.serveScript`, `text/plain`, `Cache-Control: no-cache`), `GET /obs-agent/download/{version}/manifest.json`, `GET /obs-agent/download/{version}/{file}` (302 to a 5-minute presigned URL from `getHostS3Client`, validates `file` against `^xamops-obs-agent_[a-z]+_[a-z0-9]+(\.exe)?$|^xamops-otelcol_...$`) |

`ObsExceptionHandler` lives in `OBS/exception`.

### 2.7 `service` — OBS/service

**fleet / onboarding**

| File | Responsibility |
|---|---|
| `service/onboarding/DiscoveryService.java` | Uses `InventoryProjectionService.isFresh/byType(cloudAccountId,"EC2 Instance")/activeRegions`; when not fresh or partial, per-region live `getEc2Client(account, region).describeInstances` (parallel, 10 s per region, results merged); enriches with `getSsmClient(...).describeInstanceInformation`, ASG membership (`getAutoScalingClient`), ECS/EKS clusters (`getEcsClient`, `getEksClient`); marks `alreadyConnected` from `obs_host`. Errors per region collected into `regionErrors`, never fail the call |
| `service/onboarding/PreflightService.java` | Level 1 checks (06/03 section 4); Level 2 via SSM document `XamOps-Obs-Preflight` when stack present, else instructs the installer `--preflight` |
| `service/onboarding/InstallJobService.java` | Creates job + targets in one transaction, mints enrollment token(s), enforces quota (`obs_tenant_settings.max_hosts`), audits |
| `service/onboarding/ObsInstallOrchestrator.java` | One-click: tag instances `xamops:obs=enabled` via the Operator role, `SendCommand` in region batches of 50 (`MaxConcurrency 20%`, `MaxErrors 10%`), maps SSM results to failure codes. Runs on `obsInstallExecutor`. Assumes the Operator role with `getStsClient(account, region).assumeRole` (ExternalId = `account.getExternalId()`), builds `SsmClient/Ec2Client` from those credentials (the connection role has no `SendCommand`) |
| `service/onboarding/InstallJobPoller.java` | `@Scheduled(fixedDelay=5000)`: advances `INSTALLING → WAITING_FOR_DATA → CONNECTED` using `GetCommandInvocation` and `obs_host.first_metric_at`; applies timeouts (10 min install, 5 min no data); finishes job states |
| `service/onboarding/CommandBuilder.java` | Builds the copy-paste command, user-data, launch template, Terraform, CFN, ECS, EKS, Packer snippets from templates under `RES/observability/templates/` |
| `service/onboarding/TrustManifestService.java` | Static + release-derived manifest (checksums from `obs_agent_release`) |
| `service/onboarding/PermissionStackService.java` | Quick-create URL builder (template URL from `obs.aws.permission-template-url`), `verify` (DescribeStacks through `getCloudFormationClient(account, region)`, outputs, test-assume 3 roles), policy preview generator (renders the same JSON documents as the template to avoid drift: single source `RES/observability/policies/*.json.tpl` also embedded into the CFN file by the release build) |
| `service/fleet/HostService.java` | List/detail/patch/disconnect, merges tags (`DescribeTags` via readonly role) into `labels_json` |
| `service/fleet/HostStateJob.java` | `@Scheduled(fixedDelay=30000)`: ENROLLED/CONNECTED → DELAYED (> 3 min no data) → SILENT (> 15 min); raises `ABSENCE` auto-incidents only via the alert engine rules |
| `service/fleet/ObsHostReconcileJob.java` | Daily: compare hosts to inventory; mark TERMINATED |
| `service/fleet/ContainerService.java`, `ServiceDiscoveryService.java` | Container registry upsert from `internal/host-activity` + metric labels; service discovery (listening ports + process names + images + nginx upstreams) writes `obs_service`/`obs_service_edge` |
| `service/fleet/OverviewService.java` | Aggregates fleet counts, KPIs (VM/VL queries cached in Redis 30 s), health score |
| `service/agent/AgentRegistrationService.java` | Register (idempotent), identity verification, host upsert, token issue, first config |
| `service/agent/AgentHeartbeatService.java` | Heartbeat processing, command delivery, config etag compare, `health_json` update (writes batched: at most one row update per host per 30 s) |
| `service/agent/ConfigProfileService.java` | CRUD + canonical JSON + etag + validation (06 section 9) + per-host merged desired config including active redaction/cost/sampling rules |
| `service/agent/ReleaseService.java`, `RolloutService.java` | Release registry (admin/CI posts a release row through an internal endpoint or Flyway data migration), staged rollouts (canary %, host hash bucketing), pause/rollback |
| `service/agent/AgentCommandService.java` | Queue/expire commands (expire after 24 h QUEUED) |

**storage**

| File | Responsibility |
|---|---|
| `service/storage/BucketCatalogService.java` | `ListBuckets` + `GetBucketLocation`/`GetPublicAccessBlock`/`GetBucketEncryption`/`GetBucketVersioning`/`GetObjectLockConfiguration` via `getS3Client(account, region)` (readonly role), parallel with a cap of 8, cache 60 s; AccessDenied on a sub-call becomes a `warnings[]` entry |
| `service/storage/DestinationValidationService.java` | The 9 checks of 03 section 8 using the Writer/Reader roles (`AwsClientProvider.getRoleS3Client(roleArn, externalId, region)`), KMS via `getKmsClient`. When `kmsKeyArn` is null it resolves the AWS-managed key ARN with `DescribeKey` on `alias/aws/s3` in the bucket region and persists it (`kms_key_arn`, `encryption_mode`); SSE-S3-only buckets yield a WARN and `encryption_mode=SSE_S3`; the role policies rendered from the stored ARN include `kms:ViaService` |
| `service/storage/LifecycleRuleGenerator.java` | Pure generator of the lifecycle JSON in 03 section 7: transitions emitted only when `Days < retention` (IA at 30, GLACIER_IR at 90), retention validated 7 to 3650, plus compaction-expiry and abort-MPU rules |
| `service/storage/DataDeletionJob.java` | `@Scheduled(fixedDelay=60000)`: claims `PENDING` rows of `obs_deletion_request` (`SKIP LOCKED`), deletes managed cold objects through `ManagedBucketService` by manifest (never touches customer-owned buckets; `HOT_EXPIRY_ONLY` requests just record that hot data expires with retention), updates `objects_deleted`/`status`, writes audit rows; retried up to 3 times then `FAILED` with `error` |
| `service/storage/CostEstimator.java` | Formula in 03; prices from `getPricingClient()` cached in Redis 24 h; fallback `RES/observability/prices-fallback.json` |
| `service/storage/DestinationService.java` | Create/update/activate destination (unique active per account), status transitions, audit |
| `service/storage/StorageUsageService.java` | Rolls `obs_archive_manifest` into `obs_storage_usage_daily` (nightly) and answers usage queries |
| `service/storage/MigrationService.java` | `MIGRATE` jobs (managed ↔ BYO) with `CopyObject` per manifest row, progress, cutover |
| `service/storage/AthenaDdlGenerator.java` | DDL in 03 section 6.3 |
| `service/storage/ManagedBucketService.java` | Managed bucket access through `getHostS3Client(region)`; per-tenant KMS data key alias creation via `getKmsClient`-equivalent host client (host account) |

**query**

| File | Responsibility |
|---|---|
| `query/xlq/XlqLexer.java`, `XlqParser.java` | Hand-written recursive-descent parser for the 06 section 8 grammar with its tokenizer rules (8.1: context-sensitive WORD/FIELD/MINUS/REGEX lexing, reserved words AND/OR/NOT/`exists(`), precedence NOT > AND > OR, `limit` rejected with the "use the page size" hint; produces `XlqAst` |
| `query/xlq/XlqAst.java` (sealed interface + records `AndNode`, `OrNode`, `NotNode`, `FieldCmp`, `TextTerm`, `ExistsNode`, `StatsStage`, `SortStage`, `TopStage`, `FieldsStage`, `DedupStage`, plus `GroupBucket` used only as a `stats ... by` group item; there is no limit stage and no bucket stage) | |
| `query/xlq/XlqValidator.java` | Limits (terms, depth, stages, regex count), field-name regex, reserved-field typing |
| `query/xlq/LogsQlCompiler.java` | AST → LogsQL with escaping; injects `{...}` stream filter from scope (`host_key`) |
| `query/xlq/DuckDbSqlCompiler.java` | AST → parameterised SQL (+ parameter list); regex via `regexp_matches` |
| `query/xlq/XlqFormatter.java` | AST → canonical XLQ (used for "formatted query" and saved-query dedupe) |
| `query/metrics/MetricCatalog.java` | Loads `RES/observability/metric-catalog.yaml` (id, unit, kind, promExpr template, labels, description, category) |
| `query/metrics/MetricQueryValidator.java`, `MetricsQlCompiler.java`, `PromQlGuard.java` | Validation per 06 section 6; compile; raw-PromQL allow-list parser |
| `service/logs/ObsLogQueryService.java` | Scope resolution, window checks (hot window, 7-day max), compile, **per-account fan-out** (below), call `ObsVlClient`, map rows. Applies redaction display rules (e.g. mask if user lacks `obs.logs.read`; the endpoint already requires it) |
| `service/logs/LogFanOutMerger.java` | Pure merge logic for multi-account log calls (06 section 5.4). VictoriaLogs addresses one tenant `(AccountID=client_id, ProjectID=cloud_account_id)` per call, so `ObsLogQueryService` authorises every id, then runs one `ObsVlClient` call per account in parallel on `obsQueryExecutor` (max 10 accounts else `TOO_MANY_ACCOUNTS`; per-call timeout 8 s, total budget 12 s). Merge: rows by `(ts desc, id desc)` cut to `limit`; histogram buckets and facet counts summed; `stats` merged only for `count/sum/min/max` and `top` (summed then re-cut), otherwise `STATS_NOT_MERGEABLE` when more than one account; cursor is a map `{cloudAccountId: perAccountCursor}` (missing key = exhausted); a failed or timed-out account is reported as `partial: true` + `failedAccounts[]` with HTTP 200, and only an all-account failure surfaces the error (`HOT_STORE_UNAVAILABLE` / `QUERY_TIMEOUT`) |
| `service/logs/PatternService.java` | `obs_log_pattern` queries + hot `stats` by `pattern_id` |
| `service/logs/ObsLogTailService.java` | Implements the 06 section 10 contract: validates params and rejects stages before the stream starts (JSON 4xx), composes `(q) AND levels AND hostKeys`, opens **one upstream tail per account** (max 10, counted as one stream), merges into one ordered stream with a per-stream `seq`, emits events `status/log/heartbeat/error/end`, re-checks permission and account access every 5 min, honours `Last-Event-ID` (inclusive resume, 60 s bound, `RESUMED_WITH_GAP`). Creates `SseEmitter(30*60*1000L)`, manages per-user/tenant stream counters in Redis (`INCR` with TTL), reads the VictoriaLogs tail stream on `obsQueryExecutor`, batching/rate-limit/heartbeat, cancels upstream on `onCompletion/onTimeout/onError` |
| `service/logs/SavedQueryService.java`, `SavedViewService.java` | CRUD; PRIVATE vs CLIENT visibility rules |
| `service/metrics/ObsMetricQueryService.java` | The metrics query entry point: validates a `MetricQuery`, resolves scope (per-account fan-out like logs, merged by series label set, max 10 accounts; VictoriaMetrics single mode filters by `xo_client`/`xo_account` so one call can carry all accounts), compiles via `MetricsQlCompiler`, calls `ObsVmClient`, applies compare-to-previous shift, labels, catalog |
| `service/metrics/CorrelationService.java` | Window widen, co-mover search (Pearson over top 30 candidate metrics from the same host family, 60 s step) |
| `service/metrics/DashboardService.java` | CRUD + `SuggestedDashboardService` (templates `RES/observability/dashboards/*.json` filtered by detected technologies) |
| `cold/ArchivePlanner.java` | From manifests: file list, bytes, storage classes, restore needs |
| `cold/ColdQueryJobService.java` | Job create (plan, cap checks, audit), status, cancel, results cursor, export (writes CSV/NDJSON to managed bucket under `exports/{clientId}/` 24 h lifecycle, returns signed URL) |
| `cold/ColdQueryRunner.java` | `@Profile("obs-cold-worker")` polls `QUEUED` jobs (`SKIP LOCKED`), runs DuckDB with `SET memory_limit`, `threads`, `s3_*` temp credentials from the Reader role, enforces timeout by `Statement.cancel` |
| `cold/DuckDbSessionFactory.java` | In-memory connection per job, `INSTALL/LOAD httpfs` (extension pre-bundled in the image, `autoinstall_known_extensions=false`), sets region, credentials, `enable_http_metadata_cache` |

**clients**

| File | Responsibility |
|---|---|
| `client/ObsVlClient.java` | **Only** class that talks to VictoriaLogs; every method takes `(TenantRef(clientId, cloudAccountId), …)` and sets `AccountID`/`ProjectID` headers; methods `query`, `hits`, `facets`, `statsRange`, `fieldNames`, `fieldValues`, `tail` (returns `InputStream`) |
| `client/ObsVmClient.java` | **Only** class that talks to VictoriaMetrics; appends `extra_filters[]` (single mode) or builds `/select/{accountID:projectID}/prometheus/...` (cluster mode); `queryRange`, `query`, `labels`, `labelValues`, `series` |
| `client/TenantRef.java` | record `(Long clientId, Long cloudAccountId)` |

**alerting / SLO / notifications**

| File | Responsibility |
|---|---|
| `alert/ObsAlertScheduler.java` | `@Scheduled(fixedDelay=15000)` claim → submit evaluations → reschedule `next_eval_at = now + interval` (+ jitter ≤ 10%) |
| `alert/RuleEvaluator.java` (interface) + `ThresholdEvaluator`, `RateOfChangeEvaluator`, `AbsenceEvaluator`, `AnomalyEvaluator`, `LogPatternEvaluator`, `CompositeEvaluator`, `ForecastEvaluator`, `SloBurnEvaluator` | One class per `AlertRuleType` returning `List<SeriesResult(seriesKey, value, breached, detail)>` |
| `alert/AlertStateMachine.java` | OK → PENDING (for-duration) → FIRING → OK; NO_DATA handling; flap suppression (3 transitions/10 min → hold) |
| `alert/IncidentService.java` | Open/update/resolve by dedup key (unique partial index), event log, auto-resolve after 2 OK evaluations, escalation steps |
| `alert/RulePreviewService.java` | Backtest by replaying evaluator over history windows with the query APIs (max 14 days, 1-min steps, timeout 20 s) |
| `alert/MuteService.java` | Evaluate mute windows (including RRULE recurrence via a small RFC-5545 subset parser, `ObsRrule`) and per-host `maintenance_until` |
| `notification/ObsNotificationService.java` | Fan-out per escalation step on `obsNotifyExecutor`, writes `obs_notification_delivery`, retries 3x with backoff (1 s, 5 s, 30 s) for 5xx/timeouts, no retry for 4xx |
| `notification/channel/SlackSender`, `TeamsSender`, `EmailSender`, `PagerDutySender`, `WebhookSender`, `SnsSender` | `NotificationSender` implementations. Email may delegate to the existing `EmailService`/`SnsNotificationService` only after confirming their APIs at implementation time (they exist at `service/EmailService.java`, `service/SnsNotificationService.java` but were not inspected; if unsuitable, `EmailSender` uses `JavaMailSender` directly, a decision D11). Webhook posts HMAC-SHA256 signature header `X-Pulse-Signature` |
| `notification/ChannelSecretCodec.java` | AES-256-GCM encrypt/decrypt `config_enc` with key from `obs.crypto.key` (base64, 32 bytes) |
| `slo/SloService.java`, `SloSnapshotJob.java` | Compute good/total (log or metric ratios), error budget, multi-window burn rates; snapshot every 5 min |

**smart**

| File | Responsibility |
|---|---|
| `smart/ObsBaselineJob.java` | Nightly 02:30 UTC: for each host and key metric (`host.cpu.usage`, `host.mem.used`, `host.disk.latency`, `host.net.bytes`, `host.load.1m`) pull 28 days at 5-min resolution, compute 168-bucket median/MAD, upsert `obs_baseline_stat` |
| `smart/AnomalyScorer.java` | Robust z-score, direction, duration; explanation string builder |
| `smart/ObsForecastJob.java`, `ForecastMath.java` | Hourly disk/memory forecasts (linear regression, lower-envelope for leaks, confidence from R²); writes `obs_insight` with `dedup_key` |
| `smart/PatternMiner.java` | Drain-style template tree; invoked by `ObsPatternMinerJob` every 5 min on a sample (≤ 5,000 lines per service) |
| `smart/NoiseAdvisor.java` | Top patterns by volume and projected storage saving |
| `smart/RightsizingAdvisor.java` | p95 CPU/memory over 14 days vs instance type (type vCPU/memory from `obs_host.instance_type` and `InventoryProjectionService` details); savings estimated via `getPricingClient()`; output includes a deep link to the existing XamOps rightsizing area (route constant `obs.links.rightsizing-path`, default `/finops/rightsizing`; verify route at implementation) |
| `ai/ObsAiService.java`, `EvidenceBuilder.java`, `LlmGateway.java` (interface), `HttpLlmGateway.java` | Evidence assembly (anomalies, new patterns, change events, top redacted error samples ≤ 40 lines/6 KB), prompt, grounded-citation post-check, tenant `ai_enabled` check, 20 s timeout, 1 retry |

**governance / audit / usage**

| File | Responsibility |
|---|---|
| `service/audit/ObsAuditService.java` | `record(clientId, userId, action, targetType, targetId, detail)`; synchronous insert in its own `REQUIRES_NEW` transaction; failure to audit an archive query/export **fails the operation** (fail-closed) |
| `service/usage/UsageService.java` | Ingest meters from `/internal/usage`, month-to-date, projected cost |
| `service/settings/RedactionService.java` | CRUD, preview (applies built-ins + rule to sample text, returns diff), builds gateway rules (`/internal/gateway/rules`) |
| `service/settings/CostControlService.java`, `TenantSettingsService.java`, `TokenAdminService.java` (list/revoke enrollment + ingest tokens) | |
| `service/maintenance/ObsRetentionJob.java` | Nightly: delete expired `obs_query_job` (7 d), `obs_notification_delivery` (90 d), `obs_incident_event` of resolved incidents (400 d), `obs_usage_hourly` (400 d), tokens EXPIRED→deleted (30 d) |
| `service/maintenance/ObsTokenExpiryJob.java` | Mark expired tokens, notify hosts whose rotation is overdue |
| `service/maintenance/ObsPermissionStackVerifyJob.java` | Daily re-verify of stacks; degrade status and raise an in-app banner flag |

### 2.8 `exception`, `util`

`exception/ObsException.java`, `ObsErrorCode.java` (enum with HTTP status + default message), `ObsExceptionHandler.java`; `util/ObsJson.java`, `ObsValidators.java`, `ObsIds.java` (hostKey sanitising `[A-Za-z0-9._~-]`), `ObsTime.java` (hour bucketing, hot-window math), `ObsRrule.java`.

### 2.9 Resources — RES/observability and RES/db/migration

- `RES/db/migration/V20261025_1__obs_core_fleet.sql` … `V20261025_6__obs_governance.sql` (exact content in 06 section 2; each starts with an explanatory comment header like `V20260818_1`). Before committing, list `RES/db/migration` and rename to a later date if anything newer than `V20261024_39` exists.
- `RES/observability/metric-catalog.yaml`, `builtin-redaction.yaml`, `aws-instance-identity-certs.pem`, `prices-fallback.json`, `policies/{trust,operator,writer,reader,bucket}.json.tpl`, `templates/{userdata.sh,launch-template.json,terraform.tf,cfn-association.yaml,ecs-task.json,eks-daemonset.yaml,packer.pkr.hcl}.tpl`, `dashboards/{host-overview,nginx,postgres,redis,java-jvm,docker,windows}.json`, `agent-install/obs_install.sh` (copy built from `xamops-agent/scripts/obs_install.sh` by the build, checked by a test that the two files are identical).

## 3. Non-Java deliverables of the backend plan

| Path | Content |
|---|---|
| `ROOT/xamops-obs-pipeline/` (new Go module `github.com/xamops/obs-pipeline`) | `cmd/hotwriter/main.go`, `cmd/archiver/main.go`, `cmd/compactor/main.go`; `internal/{kafka,vl,vm,parquet,s3,manifest,tenant,destinations,metrics,config}`; `Dockerfile`; unit tests with MinIO/Kafka testcontainers |
| `ROOT/xamops-agent/cmd/obs-supervisor/` + `internal/obs/**` + `scripts/obs_install.sh` + `obs-otelcol/builder-config.yaml` + `Dockerfile.obs` | Per 02 section 4.1 |
| `ROOT/cloudformation/xamops-observability-v1-permissions.yaml`, `xamops-observability-v1-bucket.yaml` | Per 03 section 3 |
| `ROOT/api-gateway/nginx-prod.conf` (edit) | New blocks in section 5 |
| `ROOT/deploy/obs/` (new; compose/helm for gateway collector, VictoriaLogs, VictoriaMetrics, Kafka topics, pipeline) | Infra manifests; owner and target environment per open decision D2 |
| `.github/workflows/ci-cd.yml` (edit, later phase) | Add jobs `obs-pipeline-test`, `obs-agent-build` mirroring `agent-build-verify` |

## 4. Configuration properties (`RES/application.properties`, overridden in `-local/-preprod/-prod`)

```properties
obs.enabled=false
# Pilot allow-list: empty = all clients; otherwise only these client ids (others get 404). Enforced in ObsAccessGuard.
obs.pilot.client-ids=
obs.internal.key=${OBS_INTERNAL_KEY:}
obs.internal.require-private-network=true
obs.crypto.key=${OBS_CRYPTO_KEY:}
obs.ingest.public-endpoint=https://obs-ingest.example.com
obs.ingest.enrollment-ttl-minutes=60
obs.ingest.asg-enrollment-ttl-days=90
obs.ingest.token-ttl-days=90
obs.ingest.token-cache-seconds=60
obs.vl.base-url=http://victorialogs:9428
obs.vl.hot-retention-days=14
obs.vm.base-url=http://victoriametrics:8428
obs.vm.mode=single
obs.aws.operator-principal-arn=
obs.aws.writer-principal-arn=
obs.aws.reader-principal-arn=
obs.aws.permission-template-url=https://xamops-public.s3.amazonaws.com/cloudformation/xamops-observability-v1-permissions.yaml
obs.aws.bucket-template-url=https://xamops-public.s3.amazonaws.com/cloudformation/xamops-observability-v1-bucket.yaml
obs.managed-bucket.name=
obs.managed-bucket.region=us-east-1
obs.agent.release-bucket=
obs.agent.default-channel=STABLE
obs.agent.latest-version=
obs.cold.max-concurrent=4
obs.cold.memory-limit-mb=2048
obs.cold.threads=4
obs.cold.timeout-seconds=120
obs.cold.default-scan-cap-gb=20
obs.alert.min-interval-seconds=30
obs.alert.max-rules-per-tenant=500
obs.ai.enabled=false
obs.ai.base-url=
obs.ai.model=
obs.ai.api-key=${OBS_AI_API_KEY:}
# Single default-grant table, identical to 06 section 11. Admin roles get every ObsPermission.
obs.rbac.defaults.ROLE_SUPER_ADMIN=*
obs.rbac.defaults.ROLE_XAMOPS=*
obs.rbac.defaults.ROLE_ADMIN=*
obs.rbac.defaults.ROLE_XAMOPS=obs.view,obs.logs.read,obs.logs.archive,obs.agents.manage,obs.storage.manage,obs.alerts.manage
obs.rbac.defaults.ROLE_USER=obs.view
# Private listener for the internal API (auth_request and pipeline callers); see section 5
obs.internal.listen-port=8081
# 8081 must not clash with another connector or the actuator: the base application.properties sets only server.port=8080, but application-uat.properties sets management.server.port=9090. Check every profile's management.server.port before fixing the environment values (D2).
obs.internal.allowed-cidrs=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16
# Query fan-out (06 section 5.4)
obs.query.max-accounts=10
obs.query.account-timeout-ms=8000
obs.query.total-budget-ms=12000
obs.links.rightsizing-path=/finops/rightsizing
```

(The template bucket URLs and principal ARNs are environment-specific placeholders; implementers must not invent values: they come from the infra owner, D2.) Secrets only via environment variables, matching how the repo already references env for secrets.

`pom.xml` additions (`xamops-service/pom.xml`, exact pins at implementation): `org.duckdb:duckdb_jdbc` (MIT), `org.yaml:snakeyaml` already transitive via Spring (reuse), Parquet is **not** needed in Java (archiver is Go; DuckDB reads Parquet). No other new Java dependency; `software.amazon.awssdk:ssm|s3|kms|cloudformation|sts|pricing|autoscaling|ecs|eks` are already present (the provider exposes clients for them).

## 5. nginx additions (`api-gateway/nginx-prod.conf`)

```nginx
# Obs control plane
location /api/obs/v1/ {
    proxy_pass http://xamops_service;
    proxy_read_timeout 300s;
    # same header set as /api/xamops/
}
# Obs SSE live tail (no buffering, long-lived)
location /api/obs/v1/stream/ {
    proxy_pass http://xamops_service;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}
# Installer and artifacts (public)
location /obs-agent/ {
    proxy_pass http://xamops_service;
    proxy_read_timeout 90s;
}
# Internal API must never be reachable from the internet
location /api/obs/v1/internal/ { return 404; }   # reachable only on the private listener used by auth_request / pipeline
```

**Internal upstream and private listener.** The new nginx `upstream` is defined next to the existing `xamops_service` upstream:

```nginx
upstream xamops_service_internal {
    server xamops-service:8081;    # obs.internal.listen-port; host/port are environment values (D2)
    keepalive 16;
}
```

The second Tomcat connector (`ObsInternalConnectorConfig`) listens on 8081 on the private network only. It is not published by the service's ingress or load balancer: the security-group/NetworkPolicy rule allows TCP 8081 only from the nginx gateway, the obs-pipeline workloads (hotwriter, archiver, compactor) and the config sidecar, and never from the internet. Defence in depth: `InternalKeyAuthFilter` also checks the key and source CIDR (section 2.2), and the public `location /api/obs/v1/internal/ { return 404; }` above stays. The ingest server block therefore reaches the auth endpoint only through this private upstream.

A separate server block for `obs-ingest.<domain>` carries `auth_request /_obs_auth;` (internal location proxying to `http://xamops_service_internal/api/obs/v1/internal/ingest-auth` with `proxy_set_header X-Obs-Internal-Key`), `client_max_body_size 16m`, `proxy_request_buffering off`, upstream to the gateway collector, and request headers `X-Obs-*` cleared before the auth call. The Vite dev proxy (`frontend-app/vite.config.ts`) needs `/api/obs` to reach the backend; confirm whether the existing `/api` proxy already covers it (it has `server.proxy`; do not assume).

## 6. Error handling per operation

| Operation | Failure condition | Class | Caller receives | Log |
|---|---|---|---|---|
| Any controller | Account not accessible | Recoverable | 403 `FORBIDDEN` (same body as not found) | DEBUG |
| Discovery | One region's `DescribeInstances` fails | Recoverable | 200 with `regionErrors[]` (retryable true) | WARN with region |
| Discovery | Inventory stale and all regions fail | Fatal for call | `AWS_ACCESS_DENIED` (HTTP 424) when the cause is AccessDenied, otherwise `AWS_THROTTLED` (HTTP 429) when throttled; never a bare 502 | WARN |
| Install job create | Quota exceeded | Fatal | 422 `QUOTA_EXCEEDED` | INFO |
| Install job create | Stack not verified and method SSM | Fatal | 409 `CONFLICT` with `details.fix=ENABLE_STACK` | INFO |
| Orchestrator | SSM `SendCommand` throttled | Recoverable | Target stays `PENDING`, retry with backoff (3x) then `FAILED(SSM_THROTTLED)` | WARN |
| Orchestrator | `AccessDenied` on assume Operator role | Fatal for job | Targets `FAILED(OPERATOR_ROLE_DENIED)`; stack status → `DEGRADED` | WARN |
| Register | Bad/expired token | Fatal for caller | 401/410/409 codes in 06 | INFO (no token value) |
| Heartbeat | DB unavailable | Recoverable | 503, agent retries (jittered) | ERROR |
| ingest-auth | Redis down | Degraded | Falls back to DB lookup; if DB down: 503 (nginx fails closed) | WARN |
| Log search | VictoriaLogs down/timeouts for every requested account | Recoverable | 503 `HOT_STORE_UNAVAILABLE` retryable | WARN, once per minute per cause |
| Log search | Some of several accounts fail or time out | Recoverable | 200 with `partial: true` and `failedAccounts[]` | WARN with account ids |
| Log search/tail | More than 10 accounts / non-mergeable stats across accounts / stage in tail | Fatal for call | 400 `TOO_MANY_ACCOUNTS` / `STATS_NOT_MERGEABLE` / `TAIL_UNSUPPORTED_STAGE` | none |
| Deletion job | S3 delete denied or fails on managed bucket | Recoverable | Request stays `RUNNING`, retried 3x, then `FAILED` with `error`; surfaced on Storage page | WARN |
| Log search | Syntax error | Fatal for call | 400 with position | none |
| Log tail | Upstream drops | Recoverable | `event: error{retryable:true}` then `end{ERROR}`; client reconnects with `Last-Event-ID` (06 section 10.2) | INFO |
| Archive job | Plan > cap | Fatal | 422 `SCAN_TOO_LARGE` | INFO |
| Archive job | S3 AccessDenied (role removed) | Fatal for job | Job `FAILED(AWS_ACCESS_DENIED)`; destination → `DEGRADED` | WARN |
| Archive job | DuckDB OOM/timeout | Fatal for job | `FAILED(QUERY_TIMEOUT)` or `FAILED(OUT_OF_MEMORY)` with hint to narrow | WARN |
| Bucket validate | Write test denied | Fatal for validation | Check `FAIL` with fix text, 200 response | INFO |
| Metrics query | Too many points | Fatal | 400 `TOO_MANY_POINTS` + suggested step | none |
| Alert eval | Backend query fails | Recoverable | Rule `last_eval_status=ERROR`; after 3 consecutive errors emits an internal incident "Rule X cannot evaluate" | WARN |
| Notification | 5xx/timeouts | Recoverable | Retry x3; delivery row FAILED; incident event | WARN |
| Notification | 4xx (bad webhook) | Fatal for that channel | Delivery FAILED, channel flagged `last_test_ok=false` | WARN |
| Audit write | DB failure on archive query/export | Fatal | Operation refused 503 | ERROR |
| AI | Provider down/timeout | Recoverable | 200 with `available=false` | WARN |
| Machine endpoints | Missing/invalid internal key | Fatal | 401 | WARN (rate-limited log) |

## 7. Input validation summary (external inputs)

| Input | Rules | On failure |
|---|---|---|
| `cloudAccountId` | Required, positive long, access-checked | 403/400 |
| Instance ids | `^i-[0-9a-f]{8,17}$`; ≤ 500 explicit | 400 `VALIDATION_FAILED` |
| Regions | `^[a-z]{2}(-[a-z]+)+-\d$` and within account's active regions | 400 |
| `from`/`to` | ISO-8601; `from < to`; hot ≤ 7 days; within retention windows | 400/409 |
| XLQ | ≤ 4,000 chars, grammar limits | 400 |
| Bucket / prefix | S3 naming regex, prefix no `..`, ≤ 200 chars, must not start with `/` | 400 |
| KMS ARN | `arn:aws:kms:<region>:<acct>:key/...` same account | 400 |
| Webhook URL | https only, host not private/loopback/link-local (SSRF guard; DNS resolved and re-checked at send) | 400 |
| Redaction regex | RE2-compatible, ≤ 512 chars, compile test with timeout | 400 |
| Agent payloads | Max body 256 KB, JSON schema, unknown fields ignored | 413/400 |
| Names/labels | ≤ 160 chars, no control chars | 400 |

## 8. Invariant ownership

| Invariant | Owning layer | Why there |
|---|---|---|
| Tenant/account access | `ObsAccessGuard` (calls `CloudAccountAccessService`) | Single chokepoint; controllers cannot forget it because services require an `ObsAccountScope` type |
| Hot-store tenant headers/filters | `ObsVlClient`, `ObsVmClient` | Only classes with network access |
| Query safety | `XlqValidator` + compilers | Before any backend sees user input |
| Token secrecy | `IngestTokenService`, `EnrollmentTokenService` | Hash only; never log or return after creation |
| Audit completeness | `ObsAuditService` called from `ColdQueryJobService`/`ExportService` before work starts | Fail-closed |
| One active destination per account | DB partial unique index + `DestinationService` | DB prevents races |
| One open incident per dedup key | DB partial unique index + `IncidentService` | Same |
| Append-only audit | DB trigger | Cannot be bypassed by application bugs |

## 9. Testing hooks (classes designed for test)

Pure/unit: `XlqParser`, `XlqValidator`, both compilers, `MetricsQlCompiler`, `PromQlGuard`, `ForecastMath`, `AnomalyScorer`, `PatternMiner`, `AlertStateMachine`, `CostEstimator`, `ObsRrule`, policy template renderers, `ObsIds`, `ChannelSecretCodec`, `TokenHasher`. Integration (Testcontainers Postgres already available): repositories (`claimDue` concurrency with two threads), controllers via MockMvc with two clients to prove cross-tenant denial, `ObsSecurityConfig` chain ordering (an `Authorization: Bearer` request to `/api/obs/v1/agent/heartbeat` must reach the filter and not be captured by the impersonation chain). Mocked AWS: `AwsClientProvider` mocked, SDK clients via Mockito or `localstack` for S3/SSM where practical. WireMock for VictoriaLogs/VictoriaMetrics/LLM/webhooks. Test file list (TST): `security/ObsAccessGuardTest`, `security/IngestTokenServiceTest`, `config/ObsSecurityChainOrderTest`, `query/xlq/XlqParserTest` (the 18 golden cases of 06 section 8.4), `XlqValidatorTest`, `LogsQlCompilerTest`, `DuckDbSqlCompilerTest`, `query/metrics/MetricsQlCompilerTest`, `PromQlGuardTest`, `client/ObsVlClientTenantHeaderTest`, `client/ObsVmClientTenantFilterTest`, `service/onboarding/DiscoveryServiceTest`, `PreflightServiceTest`, `InstallJobServiceTest`, `service/storage/DestinationValidationServiceTest` (incl. KMS ARN resolution and SSE-S3 warning), `LifecycleRuleGeneratorTest` (presets 7/30/31/90/91/180/365/3650 and rejection of 6/3651), `service/storage/DataDeletionJobTest`, `service/logs/LogFanOutMergerTest` (merge order, cursor map, partial failure, STATS_NOT_MERGEABLE), `service/logs/ObsLogTailServiceTest` (pre-stream errors, resume/dedup rules), `security/ObsPermissionDefaultsTest` (default table equals 06 section 11), `security/ConnectFlowRbacTest` (plain `ROLE_XAMOPS` user completes discovery, preflight, install job, bucket list, validate, stack launch), `security/InternalKeyAuthFilterTest` (public port gets 404, wrong key, wrong CIDR), `CostEstimatorTest`, `cold/ArchivePlannerTest`, `ColdQueryRunnerIT` (DuckDB on local Parquet fixtures), `alert/AlertStateMachineTest`, `ObsAlertSchedulerClaimIT`, `IncidentServiceDedupIT`, `smart/ForecastMathTest`, `AnomalyScorerTest`, `PatternMinerTest`, `controller/CrossTenantDenialIT`, `controller/AgentGatewayContractTest`, `service/audit/ObsAuditServiceFailClosedTest`, `migration/ObsMigrationsIT` (applies V20261025_1..6 on a clean Postgres and asserts table list equals 06).

## 10. Edge cases

- `cloud_account.id` or `client.id` ≥ 2^32: startup validator fails with a clear message (tenant headers are uint32).
- Same AWS account connected twice: all keys use `cloud_account_id`; S3 file names carry `ca{id}`.
- Host re-enrolled with a new instance id after AMI clone: `(cloud_account_id, host_key)` conflict → treated as a new host; old one goes SILENT then reconcile marks `TERMINATED`.
- Clock skew on agents: the gateway keeps `ts` but clamps timestamps > now+5 min to ingest time and sets attribute `ts_clamped=true`.
- Burst on enrollment (ASG scale-out of 500): register endpoint limited to 20 req/s/token-group with `429`+`Retry-After`; agents back off.
- Time zone: all server logic UTC; display zone is a UI preference.
- Disabled module (`obs.enabled=false`): controllers absent, `/api/obs/**` returns 404, jobs not scheduled; migrations still run (tables empty).

## 11. Responses to design review (iteration 2)
- Iteration 3 finding 1 (fixed): one rule for internal API auth (public port or non-allowed CIDR 404; private port, allowed CIDR, bad key 401; nginx denies on any non-2xx from `/_obs_auth`), see InternalKeyAuthFilter row and 06 error table. Finding 4 (fixed): 8081 clash check note added in section 5. Findings 2 and 3: name dropped from 02; version pins stay marked verify-at-implementation.
- Finding 5 (fixed): one RBAC default table (06 section 11); all `obs.rbac.defaults.<ROLE>` properties listed in section 4; `ConnectFlowRbacTest` and `ObsPermissionDefaultsTest` added (section 9).
- Finding 6 (fixed in 06, mirrored here): `XlqAst` has no limit/bucket stage, parser follows the tokenizer rules, 18 golden cases in `XlqParserTest`.
- Finding 7 (fixed): `ObsLogTailService` implements the full 06 section 10 contract.
- Finding 8 (fixed): per-account fan-out via `LogFanOutMerger` (max 10 accounts, partial results, cursor map); `ObsMetricQueryService` uses label filters in single mode.
- Finding 9 (fixed): `ObsMetricQueryService` (02 name kept), `DataDeletionJob`, `ObsDeletionRequest` entity, `xamops_service_internal` upstream, private Tomcat connector (`ObsInternalConnectorConfig`, port `obs.internal.listen-port`), network rule and `InternalKeyAuthFilter` behaviour (section 5, 2.1, 2.2).
- Finding 12 (fixed): discovery row now states 424 `AWS_ACCESS_DENIED` or 429 `AWS_THROTTLED`.
