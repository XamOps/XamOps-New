# Phase 2 Bug Hunt, Area D: auth / gateway / calls / websocket / agent / infra / CI

Method: read-only code and config review. No source modified, no build or test run, nothing committed. Secret values are never quoted: only property and variable key names.
Status meaning: `confirmed` = the behaviour is visible directly in the code or config I read. `suspected` = needs a runtime check, an environment fact, or an assumption about a library/AWS behaviour that I did not execute.
Paths are relative to the repo root. `SC` = `xamops-service/src/main/java/com/xammer/cloud/config/SecurityConfig.java`.

## Summary

| Severity | Count |
|---|---|
| Critical | 4 (D-01 to D-04) |
| High | 9 (D-05 to D-13) |
| Medium | 17 (D-14 to D-30) |
| Low | 9 (D-31 to D-39) |
| Total | 39 |

Confirmed vs suspected: suspected are D-06, D-17, D-21, D-22, D-28, D-29, D-32, D-35 (D-07 and D-30 are part confirmed, part suspected, as noted in each). Everything else is confirmed from the code or config.

Test-plan hypotheses: S-1 partly confirmed (anonymous `/topic/**` is open; `/user/queue/*` cross-user is not reachable). S-2 confirmed and worse than hypothesised (key never validated). S-3 confirmed as configuration. S-5 confirmed (the only bucket4j limiter guards a path that does not exist). S-7 partly confirmed. S-8 partly confirmed. S-11 confirmed for the Azure script (broken, not leaking). The Marketplace SNS webhook hypothesis is killed: `SnsNotificationService` verifies the signature and restricts `SubscribeURL` to `*.amazonaws.com`.

---

## Critical

### D-01 Unauthenticated DB-agent endpoints leak and overwrite stored DB credentials
- Severity: Critical. Status: confirmed.
- Where: `xamops-service/.../controller/AgentController.java` lines 268 (`POST /db/register`), 298 (`DELETE /db/register`), 626 (`POST /db/config`), 654-671 (`GET /db/config`, returns `dbPassword`), 680 (`GET /db/status`), 403 and 452 (`/status`), 721 (`GET /events/{id}`). Exposed by `SC:296` (`/api/v1/agent/**` is `permitAll`) and the `Authorization`-header chain explicitly skips `/api/v1/agent/`.
- Description: these handlers take no `Authorization` header, no `@AuthenticationPrincipal` and never call `agentIngestService.authenticate`. The inputs are `cloudAccountId` (a 12-digit AWS id) and `agentName`, both guessable. Any anonymous caller can read a tenant's saved DB host, user and password, overwrite them, register or delete DB agents, and read cluster agent status and events by sequential `clusterConfigId`.
- Repro / test idea: MockMvc with no auth: `GET /api/v1/agent/db/config?agentName=x&cloudAccountId=<existing>` should return 401/403; today it returns 200 with `dbPassword`. Same for `POST /db/config`, `DELETE /db/register`, `GET /events/1`.
- Fix: require `agentIngestService.authenticate(...)` plus `checkOwnership(principal, cloudAccountId, null)` on every `/db/*`, `/status*`, `/events/*` handler; or move the UI-facing ones (wizard polling, status, events) behind a session chain with `CloudAccountAccessService`. Never return `dbPassword` to the browser. Rotate any DB credentials saved so far.

### D-02 Media-ops `/browser-stream/**` WebSocket: no auth, any origin, spawns ffmpeg per connection
- Severity: Critical. Status: confirmed (code); resource exhaustion not executed.
- Where: `media-ops-service/.../config/BrowserStreamWebSocketConfig.java:25` (`setAllowedOriginPatterns("*")`), `config/SecurityConfig.java:53,101` (`/browser-stream/**` `permitAll` in both chains), `service/BrowserStreamHandler.java:48-110` and `:169-174`.
- Description: any internet client can open `wss://live.xamops.com/browser-stream/<anything>`. The last URL segment becomes `streamKey`, is not checked against `MediaStreamRepository` and is used as a directory name and process-map key. Each connection starts a `libx264` ffmpeg process and writes under `${mediaops.storage.base-dir}/streams/<key>`. There is no cap on sessions, so CPU and disk can be exhausted. Anyone who knows or guesses a key can also overwrite another user's live stream (the maps are keyed by `streamKey`, so a second session replaces the first's process entry). `*` origin also allows cross-site WebSocket hijacking from a victim's browser.
- Repro: open 50 sockets to random keys and watch `ps`/CPU; open two sockets with the same key. Test idea: handshake without a session must be rejected.
- Fix: require an authenticated principal in a `HandshakeInterceptor`, bind `streamKey` to a `MediaStream` row owned by the caller's client, cap concurrent sessions per client, restrict origins to the configured list, and validate the key against `[A-Za-z0-9_-]{8,64}`.

### D-03 Read-write customer role grants IAM user and policy management on `*` (privilege escalation)
- Severity: Critical. Status: confirmed (template content); impact follows from AWS IAM semantics.
- Where: `cloudformation/xamops-readwrite-role.yml:698-731` (`XamOpsPolicyIAMManage`, attached at `:907`); same in `-uat.yml:713`, `.live.yml:505`, `.local.yml:655`.
- Description: the role the XamOps backend assumes in every read-write customer account allows `iam:CreateUser`, `CreateAccessKey`, `CreateLoginProfile`, `UpdateLoginProfile`, `AttachUserPolicy`, `PutUserPolicy`, `AttachGroupPolicy`, `PutGroupPolicy`, `AddUserToGroup` with `Resource: '*'` and no permissions boundary. A holder can create a user, attach `AdministratorAccess`, mint access keys and own the account. A compromise of the XamOps principal role, or of the ExternalId, becomes full account takeover for every read-write customer.
- Repro / test idea: extend `ReadWriteTemplateContractTest` to fail if any statement allows `iam:Attach*`, `iam:Put*Policy`, `iam:Create(AccessKey|LoginProfile)` or `iam:UpdateAssumeRolePolicy` on `*`. Run `cfn-lint` / IAM Access Analyzer policy validation on the rendered role.
- Fix: remove the policy, or scope to `arn:aws:iam::*:user/xamops-managed/*` with a `iam:PermissionsBoundary` condition and deny `iam:Attach*` of `AdministratorAccess`/`IAMFullAccess`. If user lifecycle is not a shipped feature, delete it.

### D-04 Metering `GET /api/metering/customers/{customerId}/events` never validates the API key
- Severity: Critical. Status: confirmed.
- Where: `xamops-service/.../controller/MeteringController.java:188-203`; route is `permitAll` at `SC:305-306`.
- Description: the handler only checks `X-Api-Key` is non-blank, then returns `usageEventService.getRecentEvents(productCode, customerId)`. The comment "Validate API key belongs to this product" is not implemented (`getEventStats(productCode)` only checks the product exists). Any caller with any non-empty header reads any ISV product's per-customer usage events.
- Repro: `curl -H 'X-Api-Key: x' '/api/metering/customers/<id>/events?productCode=<code>'` returns 200 with events. Test: wrong key and key of another product must return 401/403.
- Fix: look up the key hash, require it to match `productCode`, compare in constant time, and scope `customerId` to that product. Related: `GET /api/metering/buyer/usage?cloudAccountId=` (line ~257) is session-authenticated but takes any `cloudAccountId` with no ownership check (see D-29).

---

## High

### D-05 `POST /api/dbops/aws/{accountId}/explain-query` has no tenant or role check
- Severity: High. Status: confirmed.
- Where: `xamops-service/.../controller/DbOpsController.java:526-560` (whole class has no `@PreAuthorize`, no `CloudAccountAccessService`, no `ClientUserDetails`).
- Description: any authenticated user from any tenant can queue an `ExplainRequest` for any `accountId`, any `agentName`, any `dsnAlias` and arbitrary `queryText`. `getExplainResult` (`:572+`) loads by request id only.
- Repro: log in as tenant A, POST to tenant B's account id; the row is saved with status PENDING.
- Fix: resolve the account through the access service for the caller's client, validate `agentName` belongs to that account, and reject query text that is not a single `SELECT`/`WITH`/`INSERT/UPDATE/DELETE` statement.

### D-06 Go agent concatenates server-supplied query text into `EXPLAIN` and executes it on the customer DB
- Severity: High. Status: suspected (code is clear; multi-statement behaviour of `lib/pq` simple-query path not executed).
- Where: `xamops-agent/internal/pusher/pusher.go:288` (`"EXPLAIN (FORMAT JSON) "+cmd.QueryText`) and `:296` (MySQL).
- Description: with D-05 the text is attacker-controlled. `QueryRowContext` with no args uses the simple query protocol, which lib/pq accepts multiple statements on, so `SELECT 1; DROP TABLE x` would run the second statement with the agent's DB credentials. `statement_timeout` limits time, not effect. Even single-statement `EXPLAIN` of a `SELECT pg_sleep()` or function with side effects is not safe.
- Repro / test: unit test `execExplain` against a Postgres container with `QueryText="SELECT 1; CREATE TABLE pwn(i int)"` and assert no table; also assert the agent refuses text containing `;`.
- Fix: agent-side allow-list (single statement, starts with SELECT/WITH, no `;`), run inside `BEGIN READ ONLY` and roll back, connect with a read-only role.

### D-07 STOMP `/xamops-ws`: anonymous subscribe and publish on cross-tenant `/topic/**`
- Severity: High. Status: confirmed for subscribe (data sent is visible in code); suspected for client-side publish (Spring simple-broker behaviour, not executed).
- Where: `SC:249` (`/xamops-ws/**` permitAll), `websocket/CallStompAuthInterceptor.java:42-62` (only `CONNECT`, missing/invalid token still connects), `config/WebSocketConfig.java:49-63`; senders at `InventoryChangeNotifier.java:58,85`, `CloudListService.java:279,284`, `GcpDataService.java:845,849`, `TicketService.java:111,119`, `DashboardUpdateService.java:36`.
- Description: there is no `SUBSCRIBE`/`SEND` authorisation. An unauthenticated client can subscribe to `/topic/cloudlist-refresh`, `/topic/gcp-cloudlist-refresh` and the inventory-change topic (every tenant's `accountId` plus add/remove counts), and to `/topic/dashboard/<awsAccountId>/inventory` (resource counts). With the simple broker, a client `SEND` to a broker destination is relayed to subscribers, so forged "refresh-complete"/inventory payloads can be injected into other tenants' dashboards. `/user/queue/calls` is safe: Spring resolves it per session principal.
- Repro: SockJS/STOMP client with no token, `SUBSCRIBE /topic/cloudlist-refresh`, trigger a refresh in another tenant. Test idea: `ChannelInterceptor` unit test that anonymous `SUBSCRIBE` to `/topic/**` is rejected.
- Fix: authenticate on CONNECT (reject without a valid token), authorise SUBSCRIBE per destination (`/topic/dashboard/{id}/...` only for accounts of the principal's client), drop client `SEND` to `/topic`, and move tenant data to `/user/queue`.

### D-08 Eureka registry is publicly proxied at `live.xamops.com/eureka/` and has no authentication
- Severity: High. Status: confirmed (config); impact on discovery suspected.
- Where: `api-gateway/nginx-prod.conf:118-122`; `service-registry/pom.xml` has no `spring-security`; `service-registry/src/main/resources/application*.properties` have no security keys.
- Description: anyone on the internet can read the registry (internal hostnames, ports, instance ids) and `POST/DELETE /eureka/apps/...` to register a rogue instance or drop real ones. Billops and xamops-service register with Eureka (`EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` in `ci-cd.yml:~311`), so poisoned entries can redirect any `lb://` call (the Spring gateway routes in `api-gateway/src/main/resources/application.properties`). Prod `application-prod.properties` also sets `logging.level.org.springframework.security=TRACE`.
- Fix: delete the `/eureka/` location from the public gateway (or `allow` an internal CIDR only), add Spring Security basic auth on the registry and credentials in client URLs.

### D-09 `POST /api/xamops/account-manager/eventbridge-callback` is unauthenticated and trusts the payload
- Severity: High. Status: confirmed (code); exploitability depends on knowing a PENDING `externalId`.
- Where: `SC:192`, `controller/AccountManagerController.java:165-185`, `service/AwsAccountService.java:483-520`.
- Description: authentication is only "the caller knows an `externalId`" (a UUID shown to the customer, and by AWS design not a secret). `awsAccountId` and `roleArn` come straight from the body: they are not checked against each other or against the stack, and not signed. An attacker with a pending id can bind the row to a role in their own account but labelled with a victim's `awsAccountId` (the column is unique, so the victim's later real onboarding then fails), or flip PENDING/FAILED rows. Error text echoes the submitted `externalId` (`"No account found for externalId: ..."`) and the controller returns `e.getMessage()` to the caller.
- Test idea: callback with `roleArn` account segment different from `awsAccountId` must be 400; unknown `externalId` must return a generic 404.
- Fix: require `roleArn` account id == `awsAccountId`, verify via `sts:GetCallerIdentity` after assume, bind callback to a one-time secret (not the ExternalId) or verify the CloudFormation `ResponseURL`/SNS signature, and return generic errors.

### D-10 CSRF disabled everywhere while the session cookie is `SameSite=None`
- Severity: High. Status: confirmed (config); per-endpoint exploitability suspected.
- Where: `SC:113,174`; `billops/.../config/SecurityConfig.java` (`csrf.disable()` in both chains); `media-ops-service/.../config/SecurityConfig.java` (both chains); `xamops-service/src/main/resources/application-prod.properties:85-86`, `-uat:66`, `-preprod:40` (`same-site=None`, `secure=true`).
- Description: with `SameSite=None` the browser sends the `JSESSIONID`/`remember-me` cookies on cross-site requests. CORS only blocks reading responses and non-simple requests; it does not stop a cross-site `<form method=POST>` to `/logout`, form-encoded or `text/plain` bodies, or GET endpoints that change state. Spring parses JSON bodies for `@RequestBody` only with `application/json`, so JSON endpoints are mostly protected by the preflight, but any `@RequestParam`/form endpoint (login, logout, uploads, `/api/billops/credits` multipart) is open.
- Repro: host an HTML page on another origin that auto-submits `POST https://live.xamops.com/logout` and a multipart upload while logged in. Test: MockMvc POST without CSRF token and with a forged `Origin` to every form-consuming mapping.
- Fix: use `SameSite=Lax` unless cross-site embedding is required, add an `Origin`/`Referer` check filter or enable `CookieCsrfTokenRepository` with the SPA sending `X-XSRF-TOKEN`.

### D-11 Committed credentials in tracked `application*.properties` (key names only)
- Severity: High. Status: confirmed (values present and non-empty in git; whether they are live in each environment is unknown).
- Where and key names (literal value in file, not an env placeholder):
  - `xamops-service/.../application.properties`: `spring.mail.password:264`, `anythingllm.api.key:569`, `cloudidr.auth.password:607`, `xamops.agent.api-key:621`, `opensre.langsmith.api-key:627`. Dev-default tokens: `xamops.calls.ws-token.secret:884` (default value is an obviously dev-only secret).
  - `application-prod.properties`: `gemini.api.key:59` (a real-looking Google API key as the `${GEMINI_API_KEY:...}` default), `anythingllm.api.key:184`.
  - `application-uat.properties`: `spring.mail.password:103`, `anythingllm.api.key:137`, `xamops.agent.api-key:165`, `authentik.master-token:180`, `identity.encryption-key:181`, `google.oauth.client-secret:183`.
  - `application-preprod.properties`: `spring.datasource.password:8`, `spring.mail.password:65`, `anythingllm.api.key:94`.
  - `application-local.properties`: `spring.datasource.password:8`, `groq.api.key:68`, `cloudidr.auth.password:170`, `xamops.agent.api-key:175`, `openobserve.admin.password:188`, `authentik.master-token:205`, `identity.encryption-key:206`, `google.oauth.client-secret:208`, `microsoft.oauth.client-secret:217`, `xamops.calls.livekit.api-secret:227`, `app.encryption.secret-key:126`, `app.impersonation.secret-key:127`, `app.remember-me.key:128`.
  - `billops/.../application.properties`: `spring.mail.password:171`; `-preprod`: `spring.datasource.password:8`, `spring.mail.password:48`, `anythingllm.api.key:76`; `-local`: `spring.datasource.password:8`, `app.impersonation.secret-key:69`, `app.encryption.secret-key:70`.
  - `media-ops-service/.../application-local.properties`: `spring.datasource.password:6`, `app.impersonation.secret-key:40`.
  - `application-prod.properties` of media-ops: `spring.datasource.password:22` has a non-empty default.
  - Comparison without printing values: UAT equals local for `openobserve.admin.password`, `google.oauth.client-secret`, `xamops.agent.api-key`, `authentik.master-token`, `identity.encryption-key`; the base `xamops.agent.api-key` equals local. So UAT secrets are reused from dev.
- Fix: rotate everything listed (treat as leaked: it is in git history), replace with env/Secrets Manager placeholders with empty defaults, add a pre-commit/CI secret scan (gitleaks) and a startup check that fails when a secret equals a known dev default.

### D-12 Legacy shared agent key is on by default, published, and `/commands` is not tenant-bound
- Severity: High. Status: confirmed.
- Where: `service/AgentIngestService.java:102-112` (`xamops.agent.legacy-global-key.enabled:true`, fallback default string in `@Value`), `:171-208`; `resources/scripts/{disk,gcp_disk,azure_disk}_agent_install.sh:38-39` (`LEGACY_SHARED_KEY`), `resources/agent-install/install-k8s.sh:55,415` (`DEFAULT_API_KEY` used when no key is supplied); `controller/AgentController.java:569-600` (`/commands`).
- Description: the shared key is public by the code's own comments, yet it still authenticates and `AgentPrincipal.canAct` returns true for it on every account. `GET /api/v1/agent/commands?agentName=X` accepts any valid key and returns the oldest PENDING explain request for that name, so a holder of any tenant's key (or the public one) can read other tenants' queued SQL and flip it to IN_FLIGHT, starving that tenant's agent (a comment in the code admits it). `getClientIp` (`:138`) trusts the first `X-Forwarded-For` entry, which a client controls (nginx appends to it), so the IP in the audit log is spoofable.
- Fix: set `legacy-global-key.enabled=false` by default and migrate; bind `/commands` to `principal.providerAccountId` through `ExplainRequest.cloudAccountId`; use `request.getRemoteAddr()` with a trusted-proxy filter.

### D-13 No brute-force protection on `/login` or the email OTP login
- Severity: High. Status: confirmed (absence); OTP budget behaviour confirmed in code.
- Where: `SC:259-262` (formLogin), `security/CustomAuthenticationFailureHandler.java:20-27`, `controller/LoginOtpController.java:91-123` (initiate) and `:165-205` (verify), `controller/PasswordResetController.java`.
- Description: there is no lockout, delay or per-IP limit on password login. `initiate-login` has no rate limit, so it can be used to flood a user's inbox, and it distinguishes known from unknown email (`401 Invalid email` vs `200 OTP_REQUIRED`), which enumerates users. Each `initiate-login` call issues a new OTP and calls `clearOtpFailures`, so the guess budget per code is renewed at will: an attacker alternates initiate and N guesses indefinitely, paying only the email volume. Only `registerOtpFailure` is bounded per code.
- Repro: loop 1000 `initiate-login` calls for one email, expect 429; loop 100 wrong passwords on `/login`, expect lockout.
- Fix: add per-email and per-IP limits on initiate, a cool-down before a new OTP can be issued (and do not reset the failure counter on re-issue), account lockout or exponential delay for `/login`, uniform response for unknown email.

---

## Medium

### D-14 The only bucket4j rate limiter protects a path that does not exist
- Severity: Medium. Status: confirmed.
- Where: `xamops-service/src/main/resources/application.properties:236-246` (filter URL `^/api/dashboard.*`, 10 per minute), `config/Bucket4jCacheConfig.java`. `grep` finds no controller mapping or frontend call for `/api/dashboard`. The other limiter (`ratelimit/RateLimitInterceptor.java`) is registered only for `/api/scripts/**` and `/api/customizations/**` (`config/DevOpsRateLimitConfig.java:24`), fails open, and buckets all unauthenticated callers under one `"anonymous"` key. The bucket4j JCache cache is created with `new MutableConfiguration<>()` (no expiry, no size bound).
- Test idea: assert every public/auth endpoint (login, OTP, forgot-password, metering ingest, llm-proxy, agent ingest) returns 429 after N calls.
- Fix: put limits on auth endpoints, metering ingest, `/api/llm-proxy/**`, agent ingest keyed by API key and IP; configure an expiring cache; add `limit_req` in nginx.

### D-15 `GET /agentinstall/azure_disk_agent_install.sh` is not permitted anonymously
- Severity: Medium. Status: confirmed (code); runtime 401 not executed.
- Where: `SC:203-204` permits only `agent_install.sh` and `gcp_disk_agent_install.sh`; `controller/AgentInstallController.java:52-55` serves the Azure script as "public, no auth".
- Description: `curl -fsSL .../agentinstall/azure_disk_agent_install.sh | sudo bash` gets 401 (curl `-f` fails, or HTML login redirect is piped to bash). The Azure disk-agent install flow cannot work for customers.
- Test: MockMvc anonymous GET to all three script URLs expects 200.
- Fix: add the Azure path to the permit list (or `/agentinstall/*`).

### D-16 Media files and media-ops topics are readable without authentication by guessing integer ids
- Severity: Medium. Status: confirmed (code); data sensitivity depends on usage.
- Where: `media-ops-service/.../config/SecurityConfig.java:53,101` (`/media/**`, `/media-ops-ws/**` permitAll), `config/WebMvcConfig.java:28-40` (`/media/jobs/<id>/...` from a sequential id, CORS `*`), `service/NotificationService.java:102` (`/topic/media-ops/notifications/<clientId>`), `service/TranscodeJobService.java:556` (`/topic/media-ops/jobs/<jobId>`).
- Description: any customer's transcoded output is downloadable by iterating job ids; job progress and per-client notifications are subscribable by anyone. `cors.allowed-origins` in `application.properties:47` is localhost-only and the prod/uat profiles do not override it (`WebSocketConfig` default is `*`), so prod WebSocket origin behaviour is unverified.
- Fix: use unguessable path tokens (UUID) or signed URLs for `/media`, authenticate STOMP CONNECT and authorise per-client topics.

### D-17 AWS client cache is keyed by account id only; role ARN / ExternalId changes are not reflected; read path may assume without an ExternalId
- Severity: Medium. Status: suspected.
- Where: `xamops-service/.../service/AwsClientProvider.java:257` (`accountId:Class:region`), `:189-203` (`getCredentialsProvider` passes `externalId` unconditionally, including null), `:304-305` (the blank-ExternalId guard is only on the write path), `:454-456` (`ROLE_CREDS` key embeds the ExternalId).
- Description: if a role ARN or ExternalId changes (re-onboarding, `connectViaCallback`) a client cached for 4 hours (`expire-after-access`, kept alive by use) keeps the old credentials unless `evictAccount` is called; the callback path does call it, other update paths may not. With a null `externalId` the SDK assumes the role without the condition, so a role whose trust policy has no ExternalId (older templates) stays exposed to the confused-deputy pattern that ExternalId exists to prevent.
- Test idea: unit test two `CloudAccount` objects with the same `awsAccountId` and different role ARN or ExternalId, assert different clients; assert `getCredentialsProvider` throws on blank ExternalId.
- Fix: include a hash of `roleArn`+`externalId` in the cache key, require a non-blank ExternalId everywhere.

### D-18 `GcpClientProvider.getCredentials` `@Cacheable` is a private self-invoked method, so it is never cached
- Severity: Medium (perf and DB load), Low (security). Status: confirmed (Spring AOP semantics: proxies do not intercept private or self-invoked methods).
- Where: `xamops-service/.../service/gcp/GcpClientProvider.java:150-170`.
- Description: every `getXxxClient` call re-reads the account row, decrypts `gcpServiceAccountKey` and re-parses the JSON key. Clients (`AssetServiceClient`, `BigQuery`, etc.) are returned as unmanaged gRPC channels. The annotation also hints at an intent to cache a `GoogleCredentials` (holding the private key) in the configured cache, which would put key material in Redis if it ever started working; the lookup by `findByGcpProjectId` is tenant-blind too.
- Test: spy on `CloudAccountRepository`, call two getters, assert one `findByGcpProjectId`.
- Fix: cache inside the class with Caffeine (like `AwsClientProvider`), key on project plus key fingerprint, evict on key rotation, never put credentials into Redis, and close clients.

### D-19 Install scripts: unverified binary download as root, fixed temp path, unpinned helm installer
- Severity: Medium. Status: confirmed.
- Where: `xamops-service/src/main/resources/scripts/disk_agent_install.sh:105-112` (download to `/tmp/xamops-agent`, `chmod +x`, `sudo mv`, no checksum or signature; `-b` lets the base URL be overridden), `agent-install/install-k8s.sh:280` (downloads `get-helm-3` from `raw.githubusercontent.com/helm/helm/main/...` and runs it), `static/agent/download/latest/*` (two ~50 MB binaries committed to git).
- Description: a compromised host or a plain-HTTP `-b` base gives root code execution on customer VMs and cluster admins. The predictable `/tmp` name allows a local pre-placed symlink to redirect the write/mv before `sudo`.
- Fix: publish a SHA-256 (or cosign signature) next to the binary and verify before install, use `mktemp`, pin the helm installer to a release tag and checksum, drop binaries from git into release storage.

### D-20 `install-k8s.sh` falls back to a baked-in shared agent key
- Severity: Medium. Status: confirmed.
- Where: `agent-install/install-k8s.sh:55` (`DEFAULT_API_KEY`), `:413-415` (used when the placeholder `__XAMOPS_API_KEY__` was not substituted).
- Description: a download that skipped server-side substitution silently installs an agent with the public shared key (see D-12) instead of failing.
- Fix: exit with an error when no scoped key is present.

### D-21 Stack-updater role has `iam:*` on `XamOps*` roles and policies plus `events:* lambda:* logs:* cur:*` on `*`
- Severity: Medium. Status: suspected (depends on who can influence the template in `s3://xamopscftemplate`).
- Where: `cloudformation/xamops-stack-updater.yml:116-157`.
- Description: CloudFormation (assumed by XamOps via `iam:PassRole`) can create or rewrite any role named `XamOps*`/`xamops*`, including its own and the read-write role, without a permissions boundary. Whoever can write the template object in `xamopscftemplate` (or a template URL parameter) can obtain AdministratorAccess in every connected account on the next update. The file itself acknowledges the trade-off.
- Fix: add a permissions boundary and a deny on attaching anything but the known managed policies, protect the bucket (versioning, MFA delete, narrow writers), pin template hashes in the change-set step.

### D-22 `ReadOnlyAccess` managed policy attached to both customer roles
- Severity: Medium. Status: suspected (contents of the AWS managed policy come from my knowledge of AWS, not verified in this run).
- Where: `cloudformation/xamops-readonly-role.yml:195`, `xamops-readwrite-role.yml:901`, and the `-uat`/`-local`/`.live` variants.
- Description: the AWS-managed `ReadOnlyAccess` includes data-plane reads (for example S3 object reads and DynamoDB item reads). A cost/ops platform does not need them, and they widen blast radius if the role is abused. The custom `XamOpsComprehensiveReadOnlyPolicy` already enumerates needs.
- Fix: replace with `ViewOnlyAccess`-style metadata policies plus the specific actions used; check with IAM Access Analyzer unused-access findings.

### D-23 Trust policies name the whole XamOps account root for some roles
- Severity: Low-Medium (rated Medium). Status: confirmed.
- Where: `cloudformation/autospotting-customer-account.yaml:110-115`, `autospotting-customer.yaml:191-196`, `xamops-isv-metering-role.yml:73-78` (`...:root` with ExternalId) versus the role-scoped principal used by the main templates. `xamops-mpa-cur-role.yml:84` and `xamops-readwrite-role.live.yml:193` hard-code `XamOps-App-EC2-Role`, while `-readonly-role.yml` takes a `XamOpsPrincipalRoleName` parameter (drift between variants).
- Description: `:root` trusts every principal in the XamOps account that has `sts:AssumeRole`, not only the app role, so any other workload or engineer there who knows the ExternalId can assume the customer role. `ExternalId` has only `MinLength: 10` (no pattern, no `NoEcho`).
- Fix: trust the specific role ARN, add `NoEcho`, and add a contract test that all variants use the same principal parameterisation.

### D-24 Production edge: public LiveKit over plain HTTP to a fixed IP, and LiveKit credentials shared between UAT and prod
- Severity: Medium. Status: confirmed.
- Where: `api-gateway/nginx-prod.conf:153-164` (`proxy_pass http://13.207.123.90:7880/`), `docker-compose.livekit.prod.yml:7-8` ("prod uses the same LIVEKIT_API_KEY/SECRET as UAT"), `:60-61` (`livekit/livekit-server:latest`), `livekit.prod.yaml` (TURN enabled, no TLS), `LiveKitTokenService.java:52` (token TTL default 14400 s).
- Description: signalling, including the `access_token` query parameter, travels over the public internet unencrypted between the gateway and the LiveKit host. A token minted in UAT is valid in prod, so UAT compromise or a leaked UAT token joins prod rooms (rooms are named per ticket). Image tag `latest` makes rollouts non-reproducible. The `/livekit/` location is also reachable on the `demo.xamops.com` server name.
- Fix: terminate TLS on LiveKit (or a private route/VPC peering), separate keys per environment, pin the image tag, shorten the join-token TTL and rely on server-side re-issue.

### D-25 nginx: unauthenticated unlimited uploads, no request limits, spoofable forwarded IP
- Severity: Medium. Status: confirmed.
- Where: `api-gateway/nginx-prod.conf:218-233` and `frontend-app/nginx.conf:94-107` (`/api/billops/credits`: `client_max_body_size 0; proxy_request_buffering off`), `nginx-prod.conf:341` (`2g` on `/api/media-ops/`), whole file (no `limit_req`/`limit_conn`), `nginx-prod.conf:119-128` and every location (`X-Forwarded-For $proxy_add_x_forwarded_for`; the app takes the first value).
- Description: any anonymous client can stream an unbounded body to the billops credits endpoint before authentication happens, and large bodies to media-ops. `/api/billops/credits` is also routed inside the frontend nginx to `xamops-service:8080` rather than billops (copy-paste mismatch with the gateway route; unreachable path if the request ever reaches it). Prod gateway sets `X-Real-IP $remote_addr`, which behind the ALB is the ALB address, so per-IP logic sees one IP.
- Fix: cap bodies per route, add `limit_req_zone` on `/login`, `/api/auth/`, agent ingest, use `real_ip_header X-Forwarded-For` with `set_real_ip_from <ALB CIDR>`, and route credits to the right upstream in `frontend-app/nginx.conf`.

### D-26 CI/CD deploys disable SSH host key checking and publish internal ports
- Severity: Medium. Status: confirmed.
- Where: `.github/workflows/ci-cd.yml:274,284,290,577,587,593,778,788,794,...` (`StrictHostKeyChecking no`, repeated for bastion and UAT host), `:310` (`-p 8082:8082`, same pattern for other services).
- Description: a man-in-the-middle on the path to the bastion receives the deploy SSH key use and the injected secrets (`APP_IMPERSONATION_SECRET_KEY`, `APP_ENCRYPTION_SECRET_KEY`, `LIVEKIT_API_SECRET`, `CALLS_WS_TOKEN_SECRET` are expanded by GitHub into the remote command line, so they are visible in the remote process list and in `docker inspect`). Private keys are written to the runner with a heredoc.
- Fix: store the host keys in a `known_hosts` secret and use `StrictHostKeyChecking yes`; pass secrets with `--env-file` read from stdin or a secrets manager; bind published ports to `127.0.0.1` when nginx is local.

### D-27 CI change detection misses shared modules and ships images that skip type-check
- Severity: Medium. Status: confirmed.
- Where: `.github/workflows/ci-cd.yml:61-120` (only `^billops/`, `^xamops-service/`, `^media-ops-service/`, etc. flip service flags; no rule for `permissions-common/`, root `pom.xml`, `service-registry/`, `api-gateway/`), `:704-740` (frontend image job runs `npm install` instead of `npm ci`, then `sed -i` patches the Dockerfile to `vite build` so `tsc` is bypassed), deploys pull `:latest` not the SHA tag (`:298`, `:601`, `:802`).
- Description: a change to `permissions-common` or the parent `pom.xml` neither tests nor redeploys the services that depend on it. Type errors can ship in the frontend image. A rollback or concurrent push can deploy a different image than the one that was tested.
- Fix: add shared paths to the filter, use `npm ci`, run `npx tsc --noEmit` in the test job and stop patching the Dockerfile, deploy `sha-${{ github.sha }}`.

### D-28 Demo accounts log in without OTP on the production host
- Severity: Medium. Status: suspected (privileges of the demo users not checked).
- Where: `controller/LoginOtpController.java:75-78,110-113`.
- Description: `demo.xamops@xammer.in` and `demo.billops@xammer.in` receive a full session from `initiate-login` with no second factor for anyone who calls it. `demo.xamops.com` is served by the same backend as live (nginx comment), so if those users can reach real data or write actions this is an open door.
- Test: call `initiate-login` with each email and assert the session's accessible accounts are demo-only and write actions are blocked (`MockDataGuard`).
- Fix: gate on host (`DemoHostResolver`) and make demo users read-only and tenant-isolated.

### D-29 `GET /api/metering/buyer/usage?cloudAccountId=` has no ownership check
- Severity: Medium. Status: suspected (service not read).
- Where: `controller/MeteringController.java:253-262` (route requires a session but no `CloudAccountAccessService`; `BuyerUsageService` not inspected).
- Fix: verify the account belongs to the caller's client before querying.

### D-30 Dockerfiles: services run as root, healthchecks call `wget` that the base images lack
- Severity: Medium (root), Low (healthcheck). Status: confirmed for root (no `USER` in `Dockerfile`, `billops/Dockerfile`, `xamops-service/Dockerfile`, `media-ops-service/Dockerfile`, `service-registry/Dockerfile`, `frontend-app/Dockerfile`); suspected for healthcheck.
- Where: `billops/Dockerfile:31`, `xamops-service/Dockerfile:36` use `wget -q -O - http://localhost:...`; `ci-cd.yml:327-329` states the JRE base image has neither curl nor wget, and the xamops-service image only installs python3/pip. The root `Dockerfile` also bundles AWS CLI, Azure CLI (`curl | bash`), gcloud and prowler in the same root process that holds customer role credentials. `cloudshell/Dockerfile:45` turns off apt TLS verification during bootstrap. `frontend-app/Dockerfile:62` uses `nginx:1.25-alpine`, `nginx-prod.conf` runs `nginx:latest`.
- Description: a code-execution bug in any service becomes root in the container; containers likely stay `unhealthy` because the probe binary is missing (not run here).
- Fix: add a non-root `USER`, use a JVM-based or `bash /dev/tcp` probe, pin base image digests.

---

## Low

### D-31 CORS lists wildcard ngrok origins through `setAllowedOrigins`
- Severity: Low. Status: confirmed.
- Where: `SC:409-410`.
- Description: `CorsConfiguration.setAllowedOrigins` does exact matching, so `https://*.ngrok-free.dev` and `https://*.ngrok.io` never match (dead config, local dev will fail CORS). If someone fixes it with `setAllowedOriginPatterns` while `allowCredentials=true` stays on, any ngrok tenant gets credentialed access to production. The list is hard-coded and is not environment-specific (prod allows localhost and demo/uat origins).
- Fix: build the list from `cors.allowed-origins` per profile, no ngrok in prod.

### D-32 Impersonation JWT: role taken from the token, deleted user still authenticates, key shared across three services
- Severity: Low. Status: suspected.
- Where: `config/multitenancy/ImpersonationJwtFilter.java:150-190` (also the billops and media-ops copies).
- Description: authorities come from the `role` claim, not from the DB; if the target user was deleted, `userRepository.findById` returns null and the request still authenticates with `clientId=null` until the token expires. One HMAC key is shared by billops, xamops-service and media-ops, so compromising any of them mints a token for any user and role. No audience claim, so a token for one service is accepted by the others.
- Fix: re-read role from DB, reject unknown users, add `aud`, shorter TTL (currently hard-coded to 8 hours in `ImpersonationTokenController`; make it configurable with a short default, e.g. 60 min. A ready patch is in `reverted-impersonation-ttl.patch`, not applied).

### D-33 Log injection and verbose security logging
- Severity: Low. Status: confirmed.
- Where: `security/CustomAuthenticationFailureHandler.java:22` (raw `username` parameter logged), `LoginOtpController.java:103` (raw email), `billops/.../application-prod.properties:87` and `-uat:51` (`logging.level.org.springframework.web.cors=DEBUG`), `service-registry/.../application-prod.properties` (`org.springframework.security=TRACE`), `xamops-service/.../application.properties:551` (`com.fasterxml.jackson=DEBUG` as base, prod overrides to WARN).
- Fix: sanitise CR/LF, drop debug levels in prod profiles.

### D-34 Go agent: any URL scheme, no response caps, retries on 4xx, constant readiness
- Severity: Low. Status: confirmed.
- Where: `xamops-agent/internal/config/config.go:137` (only checks non-empty `XAMOPS_SERVER_URL`), `internal/pusher/pusher.go:49-77` (retries 401/403/413 the same as 5xx), `:153-171`, `cmd/agent/main.go:126-143` (`/ready` always 200, health listens on all interfaces).
- Description: an `http://` URL sends the API key in clear. Permanent client errors waste retries. Readiness does not reflect push health.
- Fix: require `https` unless explicitly overridden, stop retrying on 4xx other than 408/429, report readiness from the last successful push, bind health to loopback or a pod IP only.

### D-35 Maintenance toggle routes land on billops
- Severity: Low. Status: suspected.
- Where: `controller/MaintenanceController.java:24-35` (`/api/admin/maintenance/*`) versus `api-gateway/nginx-prod.conf:247-257` (`/api/admin/` proxies to billops); no billops handler found for it (grep on `maintenance` in billops sources returned nothing).
- Impact: the super admin cannot switch maintenance mode through the production gateway. Verify with a request against prod gateway config.

### D-36 Hard-coded identifiers and fallbacks in infrastructure code
- Severity: Low. Status: confirmed.
- Where: `AwsClientProvider.java:316` (host role ARN with a 12-digit account id), `cloudformation/xamops-readonly-role.yml:~45` (default `XamOpsEventBusArn` containing the prod account id), `nginx-prod.conf:154` (public IP).
- Fix: move to config so UAT/prod cannot cross-wire.

### D-37 `CallWsTokenService` secret has a committed development default
- Severity: Low. Status: confirmed (base property); prod override uses a secret-manager placeholder.
- Where: `application.properties:884` (`xamops.calls.ws-token.secret`); UAT inherits the default unless `CALLS_WS_TOKEN_SECRET` is set (CI sets it in the deploy step at `ci-cd.yml:630`).
- Impact is limited (token only carries a call identity), but forged tokens would let someone receive another user's ring signals, if they also know a userId. Remove the default so startup fails when unset.

### D-38 CI: PR workflow runs with `packages: write` at workflow level and uses floating action tags
- Severity: Low. Status: confirmed.
- Where: `.github/workflows/ci-cd.yml:12-14`, `uses: actions/*@v4`, `docker/setup-buildx-action@v3`.
- Fix: scope `packages: write` to the push jobs and pin actions to commit SHAs.

### D-39 Install-script `curl | bash` pattern and `/tmp` usage in docs, `ARCH` mapping only handles x86_64/arm64
- Severity: Low. Status: confirmed (documentation of the pattern in `disk_agent_install.sh:10-11`, `AgentInstallController.java:19`).
- Description: standard but unsigned; combined with D-19 there is no integrity check. Covered by the D-19 fix.

---

## Not found / killed
- Marketplace SNS webhook: signature verified (`SnsNotificationService.java:87`) and `SubscribeURL` host-checked (`:249-254`).
- `/api/v1/agent/install-info`: now requires a session and ownership (`AgentController.java:481-512`).
- `/api/v1/agent/ingest`, `/heartbeat`, `/config`, `/autoscaling-policy`: authenticated and ownership-checked, with constant-time comparison of the legacy key.
- Billops support attachments are no longer in the `web.ignoring()` list (comment in `billops/.../SecurityConfig.java`).
- `/user/queue/calls`: cannot be used to read another user's call signals; the user destination resolves per session principal.
- `CloudAccount.gcpServiceAccountKey` and `azureClientSecret` are stored encrypted and `@JsonIgnore`d. `externalId` (`CloudAccount.java:27-28`) is not `@JsonIgnore`d: worth a serialisation test (area C) because it is the credential-binding value.

## Items not verifiable here
Prod nginx behaviour (`nginx -t` not run), real IAM effect of the templates (`cfn-lint`/Access Analyzer not run), Docker image contents (daemon off), LiveKit runtime, effective environment values behind `${VAR:default}` placeholders, `lib/pq` multi-statement behaviour, SockJS origin handling behind the ALB.
