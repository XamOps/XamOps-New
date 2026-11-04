# 02 — Service Architecture

One section per service. Each covers responsibility, key packages/modules, controllers (or
endpoints) grouped by domain with representative examples, external integrations, and
dependencies on other services. `xamops-service` is the core and gets the most depth; the
other twelve services are each treated as first-class.

---

## 2.1 xamops-service (core)

**Responsibility.** The platform core: multi-cloud (AWS/GCP/Azure) FinOps, security posture,
inventory, Kubernetes, alerts, auth/tenant, SRE and agent ingestion. Spring Boot 4 / Java 25,
Spring MVC (servlet), Eureka client. **Port 8080** (`xamops-service/src/main/resources/application.properties`,
`server.port=8080`). The Maven artifact is `cloud-dashboard` (`xamops-service/pom.xml`).

**Key packages** (`xamops-service/src/main/java/com/xammer/cloud/`):

- `config/` — `SecurityConfig`, `WebSocketConfig`, `KafkaConfig`, `AsyncConfig`,
  `SchedulingConfig`/`SchedulingEnvironmentConfig`, plus `config/multitenancy/` (tenant
  routing) and AWS/GCP config classes.
- `controller/` — **134 controller classes** (measured), with provider subpackages `aws/`,
  `azure/`, `gcp/`, `sre/`, `call/`, `calculator/`, `compai/`, `advice/`.
- `service/` — AWS SDK v2, GCP and Azure SDK clients, `ForecastingService`,
  `AgentIngestService`, SRE services, caching services, and the `service/automation/`
  Kubernetes automation subsystem.
- `repository/`, `domain/` (112 JPA entities), `dto/`, `security/`, `websocket/`, `event/`,
  `permissions/`, `ratelimit/`, `exception/`.

**External integrations.** AWS SDK v2 (many service clients), GCP libraries-bom, Azure
Resource Manager, Kafka, Redis, `forecast-service`, OpenObserve, LLM providers
(AnythingLLM/LiteLLM), LiveKit (calls), plus the fabric8 Kubernetes client used for in-cluster
pod resize (`service/automation/workload/execute/PodResizeClient.java`).

### Controllers grouped by domain (representative, not exhaustive)

With 134 controllers, they are grouped here; the full set lives under
`xamops-service/src/main/java/com/xammer/cloud/controller/`.

| Domain | Representative controllers |
|---|---|
| Cost / FinOps / billing | `CostController`, `CostingController`, `FinOpsController`, `FinOpsReportScheduleController`, `CurInsightsController`, `CurSourceAdminController`, `ReservationController`, `BillOpsAdminController`, `gcp/GcpFinOpsController`, `azure/AzureFinOpsController` |
| Forecasting | `ForecastingController`, `ForecastAccuracyController`, `gcp/GcpForecastingController` (→ `service/ForecastingService.java`) |
| Security / posture / compliance | `SecurityController`, `SecurityNotificationController`, `ComplianceOpsController`, `WarController`, `DspmController`/`DspmAdminController`, `XamSecureController`/`XamSecureOnboardingController`/`XamSecureWebhookController`, `CloudK8sSecurityController`, `gcp/GcpSecurityController`, `azure/AzureSecurityController` |
| Inventory / resources | `CloudlistController`, `CloudmapController`, `InventoryPlaneController`, `ResourceLabelController`, `S3BrowserController`, `gcp/GcpCloudlistController`, `azure/AzureCloudListController` |
| Rightsizing / waste / optimization | `XamOpsRightsizingController`, `AwsRightsizingController`, `EbsDiskRightsizingController`, `FlexComputeController`, `gcp/GcpRightsizingController`, `azure/AzureWasteController` |
| Kubernetes | `CloudK8sController`, `EksController`, `EksAutoscalingController`, `K8sCapabilitiesController`, `ClusterIntegrationController`, `gcp/GkeController`, `azure/AksController` |
| Scheduling / CloudSitter / spot | `CloudsitterController`, `AutoSpottingController`/`AutoSpottingAdminController`, `GcpAutoSpottingController`, `azure/AzureCloudsitterController` |
| Alerts / dashboard / metrics | `AlertsController`/`AlertsApiController`, `DashboardController`, `MetricsController`, `PerformanceInsightsController`, `gcp/GcpDashboardController`, `azure/AzureDashboardController` |
| Auth / tenant / users | `GoogleAuthController`, `MicrosoftAuthController`, `LoginOtpController`, `PasswordResetController`, `IdentityController`, `ClientController`, `TeamUserController`, `UserProfileController`, `SuperAdminController`, `AccountManagerController` |
| CI/CD & DevOps | `CicdController`/`CicdConfigurationController`/`CicdMetricsController`, `DevOpsScriptController`, `ScriptController`, `SonarQubeController` |
| SRE / AIOps / AI | `sre/SreDetectionController`, `sre/SreInvestigationController`, `AIOpsController`, `AiAdvisorController`, `ChatbotController`, `LlmProxyController`, `compai/CompAiController` |
| Agents / metering / marketplace | `AgentController`, `AgentInstallController`/`AgentInstallScriptController`/`AgentKeyController`, `MeteringController`, `IsvProductController`, `MarketplaceProcurementController`, `MarketOpsController` |
| Calls / realtime / cloudshell | `call/CallController`, `call/CallSignalController` (WebRTC/LiveKit signalling) |
| Infra actions | `CloudFormationController`, `RemediationController`, `MaintenanceController`, `DbOpsController`, `ObservabilityController` |
| Global error handlers | `advice/DevOpsScriptExceptionHandler`, `advice/MattersExceptionHandler` |

**Representative endpoints (confirmed):** `AgentController` is mapped at `/api/v1/agent`
(`controller/AgentController.java`) with `POST /ingest`, `POST /heartbeat`, `POST /db/register`,
`GET /config`, `GET /commands`, `GET /autoscaling-policy`. `AccountManagerController` exposes a
public `POST /api/xamops/account-manager/eventbridge-callback` (onboarding callback).

**Inter-service dependencies.** Calls `forecast-service` (via `ForecastingService`), serves
`xamops-agent` and `xamops-autoscaler` over `/api/v1/agent/**`, verifies billops-signed
impersonation JWTs, and produces/consumes the Kafka SRE topic. Depends on the
`permissions-common` library (declared in `xamops-service/pom.xml`).

---

## 2.2 billops

**Responsibility.** Billing/invoices, CUR (Cost and Usage Report) ingestion and
reconciliation, credit requests, support tickets, external messaging (Slack/Teams/Google
Chat), and it is the impersonation **signer**. Spring Boot 4 / Spring MVC, Eureka client.
**Port 8082** (`billops/src/main/resources/application.properties`).

**Key packages** (`billops/src/main/java/com/xammer/billops/`): `config/` (Security, Aws, S3,
Cache, Scheduling, `multitenancy/`), `controller/`, `service/`, `domain/`, `repository/`,
`security/`, `util/`. It also shares the `com/xammer/cloud/{domain,security}` tenant/cloud-account
model with xamops.

**Controllers by domain** (`billops/.../controller/`): billing/invoice (`BillopsController`,
`DashboardController`, `AdminInvoiceController`, `InvoiceViewController`, `CurAdminController`);
tickets/support (`TicketController`, `TicketNoteController`, `SupportAdminController`,
`CannedResponseController`); credit/agreements (`AgreementController`); onboarding/intake
(`AccountManagerController`, `ClientIntakeController`, `TrialAdminController`); users/admin
(`AuthController`, `UserManagementController`, `TeamUserController`, `SuperAdminController`);
impersonation (`ImpersonationTokenController` — **signs** the JWT); integrations
(`SlackOAuthController`, `TeamsController`, `GoogleChatController`,
`MessagingIntegrationController`, `MessagingWebhookController`).

**Key services.** `CurIngestionService` (reads customer CUR from S3, writes `AWS_CUR_*` rows
into the shared `cached_data` table), `CostService`/`GcpCostService`/`AzureCostService`,
`CurSnapshotScheduler`, `DashboardCacheScheduler`, `ProactiveCacheRefreshService`,
`EncryptionService`, `ExternalMessagingService`. Source:
`billops/src/main/java/com/xammer/billops/service/CurIngestionService.java`.

**Inter-service dependencies.** Signs impersonation JWTs verified by xamops and media-ops;
shares `cloud_account` / `cached_data` tables with xamops; integrates AWS Cost Explorer/CUR,
Slack/Teams/Google Chat, SMTP, S3, Redis, Eureka. **Not routed by api-gateway** — the gateway
route table lists only xamops and media-ops (see §2.4); the frontend reaches billops directly
(Vite dev proxy → `:8082`).

---

## 2.3 media-ops-service

**Responsibility.** FFmpeg transcoding, HLS/RTMP streaming, browser live-streaming, and S3
media storage; it is the impersonation **verifier**. Spring Boot 4 / Spring MVC + STOMP
WebSocket. **Port 8084** (`media-ops-service/src/main/resources/application.properties`).

**Key packages** (`media-ops-service/src/main/java/com/xammer/mediaops/`): `config/`
(Security, WebSocket, BrowserStreamWebSocket, Async, AwsSts, RedisSession, WebMvc),
`controller/`, `service/`, `domain/`, `dto/`, `repository/`, `security/`, `validation/`. Shares
`com/xammer/cloud/{config,security}`.

**Controllers.** `MediaOpsController`, `BatchController`, `PresetController`, `UserS3Controller`,
`NotificationController`.

**Key services.** `FFmpegService`, `TranscodeJobService`, `MediaStreamService`,
`BrowserStreamHandler`, `PriorityQueueService`, `SchedulerService`,
`MediaStorageService`/`S3StorageService`/`AccountS3Service`, `FileProbeService`,
`SourceUrlResolver`, `NotificationService`.

**Realtime.** STOMP endpoint `/media-ops-ws` with a simple broker `/topic` and app prefix
`/app` for job-progress (`config/WebSocketConfig.java`); browser live-stream WebSocket via
`BrowserStreamWebSocketConfig` + `BrowserStreamHandler`.

**Inter-service dependencies.** Verifies billops-signed impersonation JWTs
(`app.impersonation.secret-key`), shares Redis session store, uses S3
(`xamops-document-bucket` under `media-ops/`), registers with Eureka. **Routed via
api-gateway**: `/api/media-ops/**` and `/media-ops-ws/**` (see §2.4).

---

## 2.4 api-gateway

**Responsibility.** Reactive (WebFlux/Netty) edge; routes by path to Eureka `lb://` targets.
**Port 8080** (`api-gateway/src/main/resources/application.properties`). The only Java class is
`ApiGatewayApplication.java` — routing is entirely config-driven.

**Route table (confirmed, `api-gateway/src/main/resources/application.properties`):**

| Index | Predicate | Target |
|---|---|---|
| `[0]` | `Path=/api/xamops/**` | `lb://xamops-service` |
| `[1]` | `Path=/api/media-ops/**` | `lb://media-ops-service` |
| `[2]` | `Path=/media-ops-ws/**` | `lb://media-ops-service` |

Upload sizing: `spring.codec.max-in-memory-size=55MB`.

**Note on asymmetry.** There is **no billops route** in the gateway table. billops is reached
directly (not through the gateway). In EKS, the SPA nginx proxies `/api/`, `/login`, `/logout`
and WebSocket traffic straight to `xamops-service:8080` (`eks/manifests/06-frontend.yaml`), so
the gateway's role in the EKS topology is narrower than the dev-proxy picture suggests.

---

## 2.5 service-registry

**Responsibility.** Netflix Eureka server; the registry every Eureka client registers with.
**Port 8761**. Config: `register-with-eureka=false`, `fetch-registry=false`,
`spring.profiles.active=local` (`service-registry/src/main/resources/application.properties`);
dependency `spring-cloud-starter-netflix-eureka-server` (`service-registry/pom.xml`).

**Clients that register with it:** xamops-service, billops, media-ops-service, api-gateway
(each declares `eureka.client.service-url.defaultZone`).

---

## 2.6 permissions-common (shared library)

**Responsibility.** Cloud **permission-gap detection** — records which customer IAM actions
are being denied so a service can explain why data is empty — plus a shared Redis store and an
outbound SMS/WhatsApp provider RestClient (`com.xammer.cloud.messaging`). It is **not** a web
app: the `spring-boot-maven-plugin` is skipped (`permissions-common/pom.xml`, `<skip>true</skip>`).

**Dependencies:** spring-context, spring-web (RestClient), spring-boot-starter-data-redis,
jackson, AWS `sdk-core`/`aws-core`/`sts`.

**Consumers:** xamops-service (declared dependency in `xamops-service/pom.xml`), billops,
media-ops-service.

> The concrete interceptor/consumer class names were **not confirmed** from source during
> documentation; the role above is grounded in `permissions-common/pom.xml` (description +
> dependencies). Treat the exact interceptor class name as **(unverified)**.

---

## 2.7 forecast-service (Python)

**Responsibility.** Prophet-based ML cost/usage forecasting. **Port 5002** locally (reached by
xamops via `forecast.service.url`). Stack: Flask 3 + flask-cors + Prophet 1.1.5 + cmdstanpy +
pandas/numpy + gunicorn (`forecast-service/requirements.txt`).

**Endpoints (confirmed, `forecast-service/app.py`):** `GET /health` (`:49`), `POST /forecast/cost`
(`:60`), `POST /forecast/usage` (`:138`). Model in `forecast-service/models/prophet_forecaster.py`;
env config in `forecast-service/config.py`.

**Caller.** xamops `service/ForecastingService.java` (RestTemplate → `${forecast.service.url}`),
used by `ForecastingController`, `ForecastAccuracyController` and `gcp/GcpForecastingController`.

---

## 2.8 marketplace-procurement-service (Python)

**Responsibility.** AWS Marketplace catalog + agreements/invoices/payments procurement API.
**Port 5003** (`marketplace-procurement-service/Dockerfile`, `ENV PORT=5003`/`EXPOSE 5003`;
`app.py` `int(os.getenv('PORT', 5003))`). Stack: Flask 3 + flask-cors + boto3/botocore +
gunicorn; AWS wrapper `marketplace-procurement-service/aws_client.py`.

**Endpoints (confirmed, `marketplace-procurement-service/app.py`):** `GET /health` plus a set of
`POST /procurement/{catalog/*, agreements/*}` routes (search, agreements list/describe/terms/
invoices/payments/cancel, catalog product/listing/offer/purchase-options, adjustments).

**Caller.** xamops `MarketplaceProcurementController` / `MarketOpsController`. The exact Java
client class for the HTTP call is **(unverified)**.

---

## 2.9 xamops-autoscaler (Go)

**Responsibility.** In-cluster Kubernetes autoscaling — vertical/horizontal scaling, node
optimization, eviction/rebalance — driven by a policy fetched from xamops. `module
github.com/xamops/automation`, **Go 1.21** (`xamops-autoscaler/go.mod`). Health server on
**:8081** (`cmd/autoscaler/main.go`, `http.ListenAndServe(":8081", mux)`; `Dockerfile` `EXPOSE 8081`).

**Key packages** (`xamops-autoscaler/internal/`): `controller/` (`node_optimizer`, `evictor`,
`rightsizer`, `hpa_manager`, `vpa_manager`, `rebalancer`), `fitsim/` (bin-pack simulation),
`drain/` (planner/executor/budget), `gate/`, `state/ledger`, `policy/client`, `pusher/`,
`telemetry/`, `config/`. Entry `cmd/autoscaler/main.go` (in-cluster `rest.InClusterConfig`,
dynamic client for the VPA CRD, optional metrics-server). Manifest
`manifests/xamops-autoscaler-complete.yaml`.

**Integration (confirmed).** `policy/client.go` does `GET {serverURL}/api/v1/agent/autoscaling-policy?clusterName=&cloudAccountId=`
with `Authorization: Bearer <apiKey>`; on 404/unreachable it falls back to `DefaultBundle()`. A
`DRY_RUN` gate exists in `cmd/autoscaler/main.go`. Backend side:
`controller/AgentController.java` serves `GET /autoscaling-policy` (policy stored in the
`agent_data_cache` table, dataType `autoscaling-policy`).

---

## 2.10 xamops-agent (Go)

**Responsibility.** In-cluster/on-host collector: gathers K8s + DB + disk + security + cost
telemetry and pushes it to xamops. `module github.com/xamops/agent`, **Go 1.25**
(`xamops-agent/go.mod`). Health server default **:8080** (`internal/config/config.go`,
`HealthPort` default `8080`, env `HEALTH_PORT`).

**Key packages** (`xamops-agent/internal/`): `collector/` (k8s_resources, disk,
prometheus_metrics, opencost, karpenter, autoscaling_metrics, trivy, kubescape, sre_watcher,
log_watcher, and `db_*` for postgres/mysql/mongo/redis), `pusher/`, `config/`, `version/`.
Entry `cmd/agent/main.go`. Installers under `scripts/`; manifest
`manifests/xamops-agent-complete.yaml`.

**Integration (confirmed).** `pusher/pusher.go` does `POST {serverURL}/api/v1/agent/ingest`
(JSON `AgentPayload`, with retries, 60s client). Backend side `controller/AgentController.java`
(`@RequestMapping("/api/v1/agent")`) handles `POST /ingest`, `POST /heartbeat`,
`POST /db/register`, `GET /config`, `GET /commands` → `service/AgentIngestService.java` and
related transformer/health services. The agent also ships to OpenObserve
(`collector/openobserve_shipper.go`).

---

## 2.11 k8opt (Python)

**Classification.** A standalone **Python 3.12** Kubernetes cost-optimization engine/operator +
API (pure stdlib, no pip deps). **Port 8080** (`k8opt/Dockerfile`, `EXPOSE 8080`,
`python -m k8optimizer.cli --serve --port 8080`). Source: `k8opt/Dockerfile`,
`k8opt/pyproject.toml`.

**Key modules** (`k8opt/k8optimizer/`): `server.py`, `api.py`, `cli.py`, `controller.py`,
`recommender.py`, `resize.py`, `autoscaling.py`, `cost.py`, `safety.py`, `rca.py`,
`providers.py`, `models.py`, `store.py` (SQLite). Deploy manifests under `k8opt/deploy/`.

**Relationship to the platform.** This engine is conceptually adjacent to xamops K8s vertical
rightsizing. **However**, the in-process xamops vertical-rightsizing write path is implemented
directly against the Kubernetes API via fabric8 in
`service/automation/workload/execute/PodResizeClient.java` (a `PATCH` to the pod `resize`
subresource) — **no HTTP client in xamops that calls the k8opt `--serve` API was found**.
Treat any direct xamops→k8opt call as **(unverified)**; k8opt is documented as a standalone
subsystem.

---

## 2.12 opensre (Python / LangGraph)

**Classification.** A standalone agentic SRE investigation subsystem — its own LangGraph
project with a separate LICENSE, SECURITY.md, docker-compose files and `pyproject.toml`.
LangGraph API on **port 2024** (`opensre/opensre-source/Dockerfile`, `EXPOSE 2024`, base image
`langchain/langgraph-api`). Source: `opensre/opensre-source/langgraph.json`.

**Key modules** (`opensre/opensre-source/app/`): `graph_pipeline.py` (`build_graph`),
`webapp.py`, `auth/`, `detection/`, `investigation_*`, `analytics/`, `pipeline/`, `nodes/`,
`tools/`, `integrations/`, `guardrails/`, `masking/`, `sandbox/`, `remote/`, `deployment/`.
Entry `app/main.py`. Infra: `Dockerfile`, `docker-compose.{opensre,database,rabbitmq,testing}.yml`.

**Relationship to the platform.** Related in purpose to xamops SRE (`controller/sre/*`, the
Kafka `sre.detection.events` stream). The exact wiring between xamops and opensre was **not
confirmed** from source; classify it as an adjacent/optional subsystem — **(unverified)**.

---

## 2.13 frontend-app (React/Vite SPA)

**Classification.** A **Vite + React 19 + TypeScript + Tailwind 3** SPA
(`frontend-app/package.json` — `@vitejs/plugin-react`, `react` 19, `tailwindcss` 3;
`frontend-app/vite.config.ts`; `src/App.tsx`). A legacy static `js/` folder (calculator,
vendor) sits alongside the React app.

**Key dirs** (`frontend-app/src/`): `api/*.ts` (typed clients — `azure*Api`, `gcp*Api`,
`curInsightsApi`, `identityApi`, `k8sAutomationApi`, `verticalRightsizer*`, `soc2Api`,
`stackApi`, and more), `components/`, `contexts/`, `hooks/`, `constants/`; plus `index.html`.

**Transport (confirmed, `frontend-app/vite.config.ts`).** In dev, a Vite proxy routes:
`/api/xamops`→`:8080`; `/api/billops` and `/api/admin`→`:8082`; `/api/media-ops` and `^/media/`
→`:8084`; WebSocket `/xamops-ws` + `/terminal`→`:8080`, `/media-ops-ws` + `/browser-stream`
→`:8084`; it also rewrites session cookies and adds per-feature routes (`/api/autospotting`,
`/api/cicd`, `/api/sre`, `/api/llm-proxy`, `/api/xamsecure`, …). In EKS, nginx fronts the SPA and
proxies `/api/`, `/login`, `/logout` and WebSocket to `xamops-service:8080`
(`eks/manifests/06-frontend.yaml`).
