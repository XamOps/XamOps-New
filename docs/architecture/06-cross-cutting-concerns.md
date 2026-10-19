# 06 — Cross-Cutting Concerns

## Security & authentication

The `xamops-service` security model is defined in
`config/SecurityConfig.java`, which registers **two** `SecurityFilterChain` beans and enables
method security (`@EnableMethodSecurity`, `SecurityConfig.java:39`).

### Two filter chains

1. **Impersonation JWT chain** — `@Order(1)`, **STATELESS**
   (`SecurityConfig.java:83`,`:99`). It matches impersonation requests carrying a billops-issued
   JWT, verifies them with `ImpersonationJwtFilter` added before `UsernamePasswordAuthenticationFilter`
   (`SecurityConfig.java:108`), and skips session read/write entirely. CSRF is disabled on it
   (`:97`).
2. **Main session chain** — `securityFilterChain` (`SecurityConfig.java:114`). Form login
   (`loginPage("/login")`, `successHandler`, `:335`–`:337`), session-backed (Redis), 14-day
   remember-me (`app.remember-me.key`). CSRF is disabled here too (`:156`).

### Authorization model

A large request-matcher matrix separates **public** (`permitAll`) paths from authenticated
ones. Public paths are machine-called endpoints secured by their own key/signature rather than
a session — e.g. agent install scripts (`/agentinstall/agent_install.sh`, `:184`), agent
downloads (`/agent/download/**`, `:186`), health (`/actuator/health` `:189`, `/api/health`
`:192`, `/api/maintenance/status` `:193`), auth endpoints (`/api/auth/**`, `:165`), and the
onboarding EventBridge callback. Everything else requires authentication. Some paths were
tightened over time ("Previously permitAll, now authenticated", `SecurityConfig.java:303`).

### JWT, trials, and access control (the `security/` package)

- **JWT:** `security/JwtTokenProvider.java`, `security/JwtTokenFilter.java` (jjwt).
- **Trial enforcement:** `security/TrialEnforcementFilter.java`.
- **IDOR / cloud-account access:** `security/CloudAccountAccessService.java` (backed by the
  `database_migration_user_cloud_account_access.sql` table) ensures a user may only act on
  cloud accounts they are granted.
- **Kubernetes automation guard:** `security/K8sAutomationAccessService.java`,
  `security/K8sAutomationGuardInterceptor.java`, `security/K8sAutomationRequestContext.java` —
  gate the destructive in-cluster automation paths.
- **User details:** `security/CustomUserDetailsService.java`, `security/ClientUserDetails.java`,
  with custom success/failure/logout handlers (`security/Custom*.java`).

### HTTP security headers & CORS

On the main chain (`SecurityConfig.java`):

- `frameOptions.sameOrigin()` (`:124`) — replaces the previous `disable()` to prevent
  clickjacking via framing.
- `contentSecurityPolicy(...)` (`:138`) and `referrerPolicy(...)` (`:148`).
- `StrictHttpFirewall` (`:24`) hardens request parsing.
- **CORS allowlist** (`corsConfigurationSource`, `:370`–`:384`): `http://localhost:5173`,
  `https://live.xamops.com`, `https://prep.xamops.com`, `https://uat.xamops.com`, plus ngrok
  wildcards (`*.ngrok-free.dev`, `*.ngrok.io`), applied to `/**`.

> **CSRF note.** CSRF protection is disabled on both chains (`csrf.disable()`). For a
> cookie-session SPA this is a deliberate trade-off (the SPA uses a custom header / same-site
> posture), but it is worth tracking as a security follow-up.

## Multi-tenant isolation

Tenant isolation is enforced at the datasource layer (see
[04-data-architecture.md](04-data-architecture.md)): `TenantRoutingDataSource` +
`TenantContext` route each request to the correct physical database, defaulting to the
`system` datasource when no tenant is bound (`config/multitenancy/TenantRoutingDataSource.java`).
Row-level scoping is reinforced by `security/CloudAccountAccessService.java` and
`security/ClientUserDetails.java`. WebSocket design reinforces this too: tenant-sensitive data
(security findings, call signals) is delivered only on per-user `/queue` destinations, never on
broadcast `/topic` destinations, because `/topic/dashboard/{awsAccountId}/inventory` is
guessable (`config/WebSocketConfig.java:34`–`:42`).

## Configuration & service discovery

- **Eureka** (`service-registry`, port 8761) is the discovery registry; xamops, billops,
  media-ops and api-gateway each register via `eureka.client.service-url.defaultZone`.
- The **api-gateway** resolves `lb://` service names from the registry
  (`api-gateway/src/main/resources/application.properties`).
- Configuration is Spring `application.properties` + `application-<profile>.properties` per
  service, with environment-variable overrides (e.g. `${OPENOBSERVE_URL:...}`).

## Observability & logging

- **OpenObserve** is the telemetry sink (`openobserve.url`, `openobserve.external.url`,
  `openobserve.admin.*` in `xamops-service/src/main/resources/application.properties:221`–`:226`;
  deployed via `eks/manifests/04-openobserve.yaml`). The Go agent also ships to OpenObserve
  (`xamops-agent/internal/collector/openobserve_shipper.go`). `ObservabilityController` exposes
  observability features in-app.
- **Actuator** health is public (`/actuator/health`), used by the Docker `HEALTHCHECK`.
- **Logging** carries the tenant in MDC (`logging.pattern.console`), so logs are
  tenant-attributable.

## Error handling

Centralized exception handling uses `@RestControllerAdvice`-style handlers:

- `controller/advice/GlobalExceptionHandler.java` — global handler.
- `controller/advice/DevOpsScriptExceptionHandler.java`, `controller/advice/MattersExceptionHandler.java`
  — feature-scoped handlers.
- Typed exceptions under `controller/advice/` / related packages:
  `AuthentikUnauthorizedException`, `GithubAuthException`, `GithubRepoNotFoundException`,
  `K8sAutomationRefusedException` (+ `K8sAutomationReason`), `NoUserPermissionDetail`.

## The `permissions-common` shared library

`permissions-common` (see [02-service-architecture.md](02-service-architecture.md) §2.6) is a
shared Java library — **not** a web app — that provides cloud **permission-gap detection**: when
a customer's IAM role denies an AWS SDK call, the gap is classified and stored in Redis so any
consuming service can explain empty data to the user. It also hosts the outbound SMS/WhatsApp
RestClient (`com.xammer.cloud.messaging`). Consumers: xamops-service (declared dependency in
`xamops-service/pom.xml`), billops, media-ops-service. The concrete interceptor class name is
**(unverified)** — grounded in `permissions-common/pom.xml`.

## Frontend ↔ backend transport

- **Dev:** the Vite proxy (`frontend-app/vite.config.ts`) routes `/api/xamops`→`:8080`,
  `/api/billops`+`/api/admin`→`:8082`, `/api/media-ops`+`/media/`→`:8084`, and WebSocket
  (`/xamops-ws`, `/terminal`, `/media-ops-ws`, `/browser-stream`), rewriting session cookies.
- **EKS:** nginx in the frontend pod proxies `/api/`, `/login`, `/logout` and WebSocket to
  `xamops-service:8080` (`eks/manifests/06-frontend.yaml`) — i.e. the SPA talks to xamops
  directly, not through api-gateway, in the EKS topology.

## Secrets-in-source risks (flagged, values not reproduced)

Several files embed credentials or sensitive host details in source. These should move to a
secret manager; the docs intentionally do not copy the values:

- `Jenkinsfile` — a GHCR login credential echoed into `docker login`, a hardcoded SIT EC2 host,
  and `StrictHostKeyChecking=no` (`Jenkinsfile:15`,`:33`–`:34`).
- `application-local.properties` / other `application*.properties` — local admin passwords and
  endpoints appear in plaintext (e.g. an OpenObserve admin password in the local profile).

Rotating these and sourcing them from `eks/manifests/01-secrets.yaml` (or an external secret
store) is the recommended remediation.
