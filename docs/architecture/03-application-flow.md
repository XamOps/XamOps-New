# 03 — Application Flow

This is the centerpiece: end-to-end request flows through XamOps, each as a Mermaid
`sequenceDiagram` grounded in the controllers, clients and config actually read. Each flow
names the files that back it. Participants or edges that could not be confirmed from source are
marked **(unverified)**.

---

## 3.1 User login / authentication

Form login terminates in `xamops-service`. The security chain is defined in
`config/SecurityConfig.java` (`formLogin` → `loginPage("/login")`,
`.successHandler(authenticationSuccessHandler)`), with custom handlers in `security/Custom*.java`,
a `CustomUserDetailsService` (bcrypt), and 14-day remember-me (key `app.remember-me.key`).
Sessions are backed by Redis (`spring-session-data-redis`). SSO variants exist via
`GoogleAuthController` / `MicrosoftAuthController`.

```mermaid
sequenceDiagram
    actor U as User (browser)
    participant SPA as frontend-app (SPA)
    participant EDGE as Edge (nginx / gateway)
    participant XO as xamops-service
    participant R as Redis (session)
    participant DB as PostgreSQL (tenant)

    U->>SPA: enter credentials
    SPA->>EDGE: POST /login (form)
    EDGE->>XO: POST /login
    XO->>XO: SecurityFilterChain (main, @Order after impersonation)
    XO->>DB: CustomUserDetailsService load user
    XO->>XO: bcrypt verify
    alt success
        XO->>R: create session (JSESSIONID / SPRING_SECURITY_REMEMBER_ME)
        XO-->>EDGE: CustomAuthenticationSuccessHandler (redirect/200)
        EDGE-->>SPA: Set-Cookie session
    else failure
        XO-->>EDGE: CustomAuthenticationFailureHandler
    end
    SPA-->>U: dashboard / error
```

> Backing files: `xamops-service/src/main/java/com/xammer/cloud/config/SecurityConfig.java`
> (formLogin at `:335`, loginPage `:336`, successHandler `:337`),
> `security/CustomUserDetailsService`, `security/CustomAuthenticationSuccessHandler` /
> `...FailureHandler`, `controller/LoginOtpController.java`, `controller/PasswordResetController.java`.
> CSRF is disabled on the chain (`csrf.disable()`), documented in
> [06-cross-cutting-concerns.md](06-cross-cutting-concerns.md).

---

## 3.2 Cloud-account onboarding

Onboarding is driven by `AccountManagerController`, which also exposes a **public**
(`permitAll`) EventBridge callback used by the customer's AWS account after the onboarding
CloudFormation stack is deployed. The onboarding IAM roles are the CloudFormation templates
under `cloudformation/` (`xamops-readonly-role*.yml`, `xamops-readwrite-role*.yml`).

```mermaid
sequenceDiagram
    actor Admin as Customer admin
    participant SPA as frontend-app
    participant XO as xamops-service
    participant DB as PostgreSQL
    participant AWS as Customer AWS
    participant EB as AWS EventBridge

    Admin->>SPA: start onboarding
    SPA->>XO: AccountManagerController (create account record)
    XO->>DB: persist CloudAccount (pending)
    XO-->>SPA: CloudFormation stack template / link
    Admin->>AWS: deploy xamops-readonly/readwrite role stack
    AWS->>EB: stack event
    EB->>XO: POST /api/xamops/account-manager/eventbridge-callback (permitAll)
    XO->>DB: mark account connected / store role ARN
    XO-->>SPA: account active
```

> Backing files: `controller/AccountManagerController.java` (EventBridge callback),
> `config/SecurityConfig.java` (the callback path is in the `permitAll` block),
> `cloudformation/xamops-readonly-role*.yml`, `cloudformation/xamops-readwrite-role*.yml`.

---

## 3.3 Dashboard / cost data read

Dashboard and cost reads go through `DashboardController` / `CostController` / `FinOpsController`.
These paths are rate-limited (bucket4j over jcache/Caffeine for `^/api/dashboard.*`) and served
from cached snapshots held in the `cached_data` table (large JSON stored as a legacy LOB/OID
TEXT column via `LegacyLobTextType`), falling back to live cloud SDK calls when the cache is
cold.

```mermaid
sequenceDiagram
    actor U as User
    participant SPA as frontend-app
    participant XO as xamops-service
    participant RL as RateLimit (bucket4j)
    participant C as Cache (Redis / Caffeine)
    participant DB as PostgreSQL (cached_data)
    participant CLOUD as AWS/GCP/Azure SDK

    U->>SPA: open dashboard
    SPA->>XO: GET /api/xamops/dashboard...
    XO->>RL: check bucket (^/api/dashboard.*)
    alt within limit
        XO->>C: lookup cached snapshot
        alt cache hit
            C-->>XO: snapshot
        else cache miss
            XO->>DB: read cached_data (LOB JSON)
            alt stale/absent
                XO->>CLOUD: live cost/inventory calls
                CLOUD-->>XO: results
                XO->>DB: write cached_data snapshot
            end
        end
        XO-->>SPA: dashboard JSON
    else rate limited
        XO-->>SPA: 429
    end
    SPA-->>U: render
```

> Backing files: `controller/DashboardController.java`, `controller/CostController.java`,
> `controller/FinOpsController.java`, `ratelimit/` package, `config/SecurityConfig.java`
> (rate-limit matcher), `domain/CachedData` + the `LegacyLobTextType` LOB mapping (see
> [04-data-architecture.md](04-data-architecture.md)).

---

## 3.4 Async / scheduled data ingestion

Background ingestion is driven by `@Scheduled` jobs configured through `config/SchedulingConfig.java`,
`config/SchedulingEnvironmentConfig.java` and `config/AsyncConfig.java`. These include AWS
inventory reconcile (`xamops.inventory.sync-interval-ms=300000`), SQS event polling
(`xamops.inventory.events.*`), AutoSpotting SQS, disk/security-finding schedulers, agent data
cleanup/silence alerts, and forecast-accuracy jobs. Intervals come from
`xamops-service/src/main/resources/application.properties`.

```mermaid
sequenceDiagram
    participant SCH as Scheduler (@Scheduled)
    participant SVC as Ingestion services
    participant SQS as AWS SQS (inventory events)
    participant CLOUD as Cloud SDK
    participant DB as PostgreSQL (cached_data)
    participant WS as STOMP broker

    loop every sync-interval-ms (300000)
        SCH->>SVC: trigger inventory reconcile
        SVC->>CLOUD: describe resources
        CLOUD-->>SVC: inventory
        SVC->>DB: upsert cached snapshot
    end
    loop event poll
        SCH->>SQS: poll inventory/autospotting events
        SQS-->>SVC: messages
        SVC->>DB: apply deltas
        SVC->>WS: push refresh ping to /topic
    end
```

> Backing files: `config/SchedulingConfig.java`, `config/SchedulingEnvironmentConfig.java`,
> `config/AsyncConfig.java`, and the `xamops.inventory.*` / `xamops.inventory.events.*` keys in
> `application.properties`.

---

## 3.5 WebSocket / real-time updates

`xamops-service` exposes a STOMP endpoint at `/xamops-ws` (SockJS). `/topic` destinations are
broadcast (e.g. dashboard inventory refresh pings); `/queue` destinations are user-targeted via
`convertAndSendToUser` (call signals at `/user/{id}/queue/calls`, security findings at
`/user/{id}/queue/security-findings`). Call signalling uses `call/CallController` and
`call/CallSignalController` (LiveKit), with `websocket/CallStompAuthInterceptor` on connect.

```mermaid
sequenceDiagram
    actor U as User (browser)
    participant SPA as frontend-app
    participant XO as xamops-service (STOMP)
    participant AUTH as CallStompAuthInterceptor
    participant BRK as Simple broker

    U->>SPA: open app
    SPA->>XO: WS connect /xamops-ws (SockJS)
    XO->>AUTH: authenticate STOMP CONNECT
    AUTH-->>XO: principal bound
    SPA->>XO: SUBSCRIBE /topic/dashboard/{acct}/inventory
    SPA->>XO: SUBSCRIBE /user/queue/security-findings
    Note over XO,BRK: ingestion/findings produce server-side events
    XO->>BRK: broadcast to /topic (inventory)
    XO->>BRK: convertAndSendToUser → /user/{id}/queue/security-findings
    BRK-->>SPA: push updates
    SPA-->>U: live UI update
```

> Backing files: `config/WebSocketConfig.java` (endpoint `/xamops-ws` `:27`, SockJS `:29`,
> broker design notes `:34`–`:42`), `websocket/CallStompAuthInterceptor.java`,
> `websocket/CallPresenceListener.java`, `controller/call/CallController.java`,
> `controller/call/CallSignalController.java`.

---

## 3.6 SRE detection via Kafka

SRE auto-detection is produced to and consumed from a Kafka topic. `AutoDetectionService`
publishes to the topic (`sre.kafka.topic`, default `sre.detection.events`) keyed by a partition
key; `SreDetectionConsumer` listens on the same topic with manual ack via a dedicated listener
container factory. Consumer group is `sre-rca-consumer`.

```mermaid
sequenceDiagram
    participant SRC as Detection source (schedulers / signals)
    participant ADS as AutoDetectionService
    participant K as Kafka (sre.detection.events)
    participant CON as SreDetectionConsumer
    participant INV as SRE investigation (sre/*)

    SRC->>ADS: detection signal
    ADS->>K: kafkaTemplate.send(topic, partitionKey, json)
    K-->>CON: @KafkaListener(topics=sre.detection.events)
    CON->>INV: start investigation
    INV-->>CON: result (manual ack)
```

> Backing files: `config/KafkaConfig.java` (consumer group `sre-rca-consumer` `:46`),
> `service/sre/AutoDetectionService.java` (topic value `:50`, `kafkaTemplate.send` `:173`),
> `service/sre/SreDetectionConsumer.java` (`@KafkaListener` topics
> `${sre.kafka.topic:sre.detection.events}` `:42`–`:44`),
> `controller/sre/SreDetectionController.java`, `controller/sre/SreInvestigationController.java`.

---

## 3.7 Impersonation ("Login As") — cross-service

billops **signs** an impersonation JWT (`ImpersonationTokenController`, secret
`app.impersonation.secret-key`); xamops and media-ops **verify** it through a dedicated
STATELESS filter chain ordered first (`@Order(1)`), implemented by `ImpersonationJwtFilter`.

```mermaid
sequenceDiagram
    actor Op as Operator (admin)
    participant BO as billops
    participant XO as xamops-service
    participant MO as media-ops-service

    Op->>BO: request "Login As" token
    BO->>BO: ImpersonationTokenController signs JWT (app.impersonation.secret-key)
    BO-->>Op: impersonation JWT
    Op->>XO: request with Bearer [impersonation JWT]
    XO->>XO: impersonationJwtFilterChain @Order(1) STATELESS
    XO->>XO: ImpersonationJwtFilter verifies signature
    XO-->>Op: acts as impersonated user
    Op->>MO: request with same JWT
    MO->>MO: verify (shared secret)
    MO-->>Op: acts as impersonated user
```

> Backing files: `config/SecurityConfig.java` (`impersonationJwtFilterChain` `@Order(1)` `:83`,
> STATELESS `:99`, filter wiring `:108`), `config/multitenancy/ImpersonationJwtFilter.java`,
> `security/JwtTokenProvider.java` / `security/JwtTokenFilter.java`; billops
> `controller/ImpersonationTokenController.java`; media-ops `app.impersonation.secret-key` in
> `media-ops-service/src/main/resources/application.properties`.

---

## 3.8 billops — CUR ingestion + reconciliation + dashboard read

`CurIngestionService` reads a customer's CUR 2.0 export from S3 and writes `AWS_CUR_*` rows into
the **shared** `cached_data` table (shared with xamops); closed months are reconciled against
Cost Explorer. `CurSnapshotScheduler` / `DashboardCacheScheduler` refresh snapshots. The billops
dashboard reads back from `cached_data`.

```mermaid
sequenceDiagram
    participant SCH as CurSnapshotScheduler
    participant CIS as CurIngestionService
    participant S3 as S3 (CUR 2.0 export)
    participant CE as AWS Cost Explorer
    participant DB as PostgreSQL (cached_data)
    participant BOD as billops DashboardController
    actor U as User

    SCH->>CIS: trigger CUR ingest
    CIS->>S3: read CUR parquet/csv
    S3-->>CIS: usage/cost rows
    CIS->>DB: write AWS_CUR_* into cached_data
    opt closed month
        CIS->>CE: fetch totals
        CE-->>CIS: totals
        CIS->>DB: reconcile
    end
    U->>BOD: GET billops dashboard
    BOD->>DB: read cached_data (AWS_CUR_*)
    BOD-->>U: cost dashboard
```

> Backing files: `billops/src/main/java/com/xammer/billops/service/CurIngestionService.java`,
> `billops/.../service/CurSnapshotScheduler`, `DashboardCacheScheduler`,
> `billops/.../controller/DashboardController.java` / `CurAdminController.java`,
> `billops/src/main/resources/application.properties` (`billops.cur.enabled=true`).

---

## 3.9 media-ops — transcode submit → progress → output

A transcode job is submitted via `MediaOpsController` / `BatchController`, queued by
`PriorityQueueService`, processed by `TranscodeJobService` + `FFmpegService`, with progress
pushed over STOMP `/media-ops-ws` (`/topic`), and output stored via
`MediaStorageService`/`S3StorageService` and served under `/media/*`.

```mermaid
sequenceDiagram
    actor U as User
    participant SPA as frontend-app
    participant MO as media-ops-service
    participant Q as PriorityQueueService
    participant FF as FFmpegService
    participant S3 as S3 (xamops-document-bucket/media-ops)
    participant WS as STOMP /media-ops-ws

    U->>SPA: submit transcode job
    SPA->>MO: POST /api/media-ops/... (MediaOpsController/BatchController)
    MO->>Q: enqueue job
    SPA->>MO: WS connect /media-ops-ws, SUBSCRIBE /topic
    Q->>FF: run FFmpeg (TranscodeJobService)
    loop progress
        FF->>WS: publish progress to /topic
        WS-->>SPA: progress %
    end
    FF->>S3: store HLS/MP4 output
    MO->>WS: completion event
    WS-->>SPA: done
    SPA-->>U: play output /media/*
```

> Backing files: `media-ops-service/.../controller/MediaOpsController.java`, `BatchController.java`,
> `media-ops-service/.../service/{TranscodeJobService,FFmpegService,PriorityQueueService,MediaStorageService}.java`,
> `media-ops-service/.../config/WebSocketConfig.java`.

---

## 3.10 api-gateway — edge routing

```mermaid
sequenceDiagram
    participant C as Client
    participant GW as api-gateway (WebFlux)
    participant EU as Eureka (service-registry)
    participant DS as Downstream service

    C->>GW: GET /api/xamops/** (or /api/media-ops/**, /media-ops-ws/**)
    GW->>EU: resolve lb://xamops-service
    EU-->>GW: instance(s)
    GW->>DS: forward request
    DS-->>GW: response
    GW-->>C: response
```

> Backing files: `api-gateway/src/main/resources/application.properties` (routes `[0]`–`[2]`,
> `lb://` URIs). Reminder: no billops route exists at the gateway (§2.4).

---

## 3.11 service-registry — registration & discovery

```mermaid
sequenceDiagram
    participant SVC as Eureka client (xamops/billops/mediaops/gateway)
    participant EU as service-registry (Eureka :8761)
    participant GW as api-gateway

    SVC->>EU: register (defaultZone)
    loop heartbeat
        SVC->>EU: renew lease
    end
    GW->>EU: fetch registry
    EU-->>GW: instances for lb:// names
```

> Backing files: `service-registry/src/main/resources/application.properties`
> (`register-with-eureka=false`, `fetch-registry=false`), each client's `eureka.client.service-url.defaultZone`.

---

## 3.12 permissions-common — permission-gap detection

```mermaid
sequenceDiagram
    participant SVC as xamops/billops/mediaops
    participant AWS as AWS SDK
    participant GAP as permissions-common (gap classifier)
    participant R as Redis (gap store)
    participant UI as Frontend

    SVC->>AWS: SDK call (customer role)
    AWS-->>SVC: AccessDenied
    SVC->>GAP: classify denied action
    GAP->>R: store permission gap
    UI->>SVC: load data
    SVC->>R: read gaps
    SVC-->>UI: data + "missing permission" explanation
```

> Backing files: `permissions-common/pom.xml` (role + Redis + AWS deps). The concrete
> interceptor/consumer class names are **(unverified)**; the gap-classification edge is
> inferred from the library's description and dependencies.

---

## 3.13 forecast-service — cost forecasting

```mermaid
sequenceDiagram
    actor U as User
    participant XO as xamops ForecastingController
    participant FS as ForecastingService (RestTemplate)
    participant PY as forecast-service (Flask)
    participant M as Prophet model

    U->>XO: request cost forecast
    XO->>FS: build historical series
    FS->>PY: POST /forecast/cost (${forecast.service.url})
    PY->>M: Prophet fit/predict
    M-->>PY: forecast
    PY-->>FS: forecast JSON
    FS-->>XO: forecast
    XO-->>U: forecast chart
```

> Backing files: `xamops-service/.../service/ForecastingService.java` (RestTemplate →
> `${forecast.service.url}`), `forecast-service/app.py` (`/forecast/cost` `:60`, `/forecast/usage`
> `:138`), `forecast-service/models/prophet_forecaster.py`.

---

## 3.14 marketplace-procurement-service — procurement

```mermaid
sequenceDiagram
    actor U as User
    participant XO as xamops MarketplaceProcurementController
    participant PY as marketplace-procurement (Flask :5003)
    participant AWS as AWS Marketplace APIs

    U->>XO: browse catalog / agreements
    XO->>PY: POST /procurement/catalog/* or /procurement/agreements/*
    PY->>AWS: boto3 Marketplace call (aws_client.py)
    AWS-->>PY: result
    PY-->>XO: JSON
    XO-->>U: catalog/agreement view
```

> Backing files: `xamops-service/.../controller/MarketplaceProcurementController.java` /
> `MarketOpsController.java`, `marketplace-procurement-service/app.py`,
> `marketplace-procurement-service/aws_client.py`. The exact Java HTTP client class is
> **(unverified)**.

---

## 3.15 xamops-autoscaler — policy-driven autoscaling loop

```mermaid
sequenceDiagram
    participant AS as xamops-autoscaler (in-cluster)
    participant PC as policy/client.go
    participant XO as xamops AgentController
    participant DB as agent_data_cache
    participant K8S as Kubernetes API

    AS->>PC: fetch policy on boot/interval
    PC->>XO: GET /api/v1/agent/autoscaling-policy (Bearer apiKey)
    XO->>DB: read policy (dataType=autoscaling-policy)
    DB-->>XO: policy
    XO-->>PC: policy JSON
    alt unreachable / 404
        PC-->>AS: DefaultBundle()
    end
    AS->>AS: controllers (rightsizer/vpa/hpa/node_optimizer/evictor/rebalancer)
    AS->>AS: fitsim + drain + gate (honor DRY_RUN)
    AS->>K8S: apply actions
    AS->>XO: push telemetry (pusher/telemetry)
```

> Backing files: `xamops-autoscaler/internal/policy/client.go`,
> `xamops-autoscaler/cmd/autoscaler/main.go` (DRY_RUN, in-cluster config),
> `xamops-service/.../controller/AgentController.java` (`GET /autoscaling-policy`).

---

## 3.16 xamops-agent — collect → push ingestion

```mermaid
sequenceDiagram
    participant AG as xamops-agent (in-cluster/on-host)
    participant COL as collector/*
    participant PU as pusher/pusher.go
    participant XO as xamops AgentController
    participant IS as AgentIngestService
    participant DB as agent_data_cache
    participant OO as OpenObserve

    loop interval
        AG->>COL: collect k8s/db/disk/security/cost
        COL-->>AG: AgentPayload
        AG->>PU: push
        PU->>XO: POST /api/v1/agent/ingest (retries, 60s)
        XO->>IS: transform + persist
        IS->>DB: write agent_data_cache
        XO-->>PU: 200
        AG->>OO: ship logs/metrics (openobserve_shipper)
    end
    AG->>XO: POST /api/v1/agent/heartbeat
```

> Backing files: `xamops-agent/internal/pusher/pusher.go` (POST `/api/v1/agent/ingest`),
> `xamops-agent/internal/collector/openobserve_shipper.go`,
> `xamops-service/.../controller/AgentController.java` (`/ingest`, `/heartbeat`),
> `xamops-service/.../service/AgentIngestService.java`.

---

## 3.17 k8opt — Kubernetes rightsizing (standalone engine)

```mermaid
sequenceDiagram
    participant OP as Operator / caller (unverified)
    participant API as k8opt api.py (--serve :8080)
    participant REC as recommender.py / resize.py / safety.py
    participant ST as store.py (SQLite)
    participant K8S as Kubernetes

    OP->>API: request recommendation
    API->>REC: compute recommendation
    REC->>ST: read/write history
    ST-->>REC: data
    REC-->>API: recommendation
    API-->>OP: JSON
    opt apply
        API->>K8S: resize workload
    end
```

> Backing files: `k8opt/Dockerfile` (`--serve --port 8080`), `k8opt/k8optimizer/{server,api,recommender,resize,safety,store}.py`.
> **Caveat:** no xamops HTTP client calling this API was found; xamops' own pod-resize path is
> in-process via `service/automation/workload/execute/PodResizeClient.java`. The caller is
> **(unverified)**.

---

## 3.18 opensre — agentic SRE investigation (standalone subsystem)

```mermaid
sequenceDiagram
    participant EV as Detection event
    participant WA as app/webapp.py (LangGraph :2024)
    participant G as graph_pipeline.build_graph
    participant N as detection/investigation/nodes/tools
    participant GR as guardrails + masking
    participant OUT as output

    EV->>WA: investigation request
    WA->>G: build graph
    G->>N: run detection → investigation pipeline
    N->>GR: enforce guardrails / mask secrets
    GR-->>N: sanitized
    N-->>G: findings
    G->>OUT: investigation result
    OUT-->>WA: response
```

> Backing files: `opensre/opensre-source/langgraph.json`, `opensre/opensre-source/app/graph_pipeline.py`
> (`build_graph`), `app/webapp.py`, `app/{detection,nodes,tools,guardrails,masking}/`.
> **Caveat:** platform wiring to xamops is **(unverified)**.

---

## 3.19 frontend-app — SPA request to backend

```mermaid
sequenceDiagram
    actor U as User
    participant C as React component
    participant API as src/api/*.ts (fetch)
    participant PX as Vite proxy (dev) / nginx (EKS)
    participant BE as Backend service

    U->>C: interaction
    C->>API: typed client call
    API->>PX: fetch /api/xamops|billops|media-ops/...
    PX->>BE: proxy to :8080 / :8082 / :8084 (dev) or xamops-service:8080 (EKS)
    BE-->>PX: response (+ Set-Cookie rewrite in dev)
    PX-->>API: response
    API-->>C: typed result
    C-->>U: render
```

> Backing files: `frontend-app/vite.config.ts` (dev proxy + cookie rewrite),
> `frontend-app/src/api/*.ts`, `eks/manifests/06-frontend.yaml` (EKS nginx proxy to
> `xamops-service:8080`).
