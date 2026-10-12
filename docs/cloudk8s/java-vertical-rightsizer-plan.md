# Workload autoscaling engine (Java) — foundation slice: design and implementation plan

Branch `feat/java-vertical-rightsizer`, worktree
`/Users/apple/Desktop/XamOps-New/.worktrees/java-vertical-rightsizer`, base `b4a9fe701`.
Module: `xamops-service`.

This document is the complete design for the foundation slice as well as the ordered implementation
plan. There is no separate design document.

**Vertical rules are implemented this run. Horizontal decisions are modelled and not implemented.
Nothing in this slice contacts a cluster or AWS.**

---

## 0. Why this engine exists, and what it must not repeat

Vertical rightsizing is moving out of the Go agent into Java because the Go **decision layer** was
measured and found wrong in ways that are not tuning problems. From the harness report
(`.agents/tasks/cloudk8s-automation-harness-2026-10-09/report.md`), on a real EKS 1.36.4 cluster:

- §1, §2 — 7 of 7 rightsizing rows were unsafe: 5 recommended a `1Mi`–`14Mi` memory request, 2
  recommended a `0m` CPU request, and the deliberately correctly-sized control workload
  (`prod-sim/rightsized-worker`, 80m/64Mi) was changed by **−32.8%**.
- §7-B3, live with `DRY_RUN=false` — it patched the **Deployment template** (full rolling
  replacement, new pod UIDs, 3 ReplicaSets), wrote **requests without limits** so QoS silently went
  `Guaranteed → Burstable`, and **re-patched the same workload on the next 60 s cycle** (1Mi, then
  4Mi): a self-sustaining restart loop. Restoring it needed manual intervention, because the cluster
  had repacked onto the freed capacity and the restored 1000m pod no longer fit.
- §7-B1 — in-place resize via `kubectl patch pod --subresource resize` works on this platform with
  the **same pod UID, same containerID and `restartCount: 0`**, including a `1Gi → 1Mi` memory step.
  The kubelet applied `1Mi` **without complaint**. This is the single most important measured fact in
  this plan: **the platform will not refuse an absurd recommendation, so the guard has to live in the
  recommendation engine.**
- §7-B3 also shows `applyThresholdPercent: 5` cannot stop a thrash loop, because `1Mi → 4Mi` is a
  300% delta. A relative-only band is structurally incapable of damping small absolute moves.
- §2 — the Go `hpa_manager` is BROKEN in the same way: its output is pure replica arithmetic
  (`min(2, replicas)` / `max(2 × replicas, 3)` / a fixed 80% target for every workload), the
  `p90Cpu` it collects is never used, it proposes an HPA for a pod that can never schedule, and
  `minReplicas: 2` for a workload whose PDB demands `minAvailable: 3`.
- §2, §1 — and the two interact: in **one** cycle, for `team-b/php-apache`, the rightsizer said cut
  the CPU request 200m → 144m while the HPA manager said raise the target 50% → 80% and max 5 → 10.
  Three numbers for one workload, from one code path, with no cross-reference.

So this slice builds the **decision layer**, and it builds it as **one** engine for workload
autoscaling rather than a vertical engine with a horizontal veto bolted on. The reason is in that
last measurement: **a CPU request is the HPA's utilisation denominator, so HPA/VPA coexistence cannot
be solved if the two decisions live in different components.** One component has to decide both.

The eleven verified bugs are tracked upstream; **in scope here: 3, 4, 7, 8 and the decision-layer
half of 5.** Each is made structurally impossible rather than merely fixed:

| Bug | Measured failure | Structural prevention |
|---|---|---|
| 3 | `Quantity.Value()` rounds `250m` to **1 core**; parse errors discarded (`qty, _ := resource.ParseQuantity`) | One canonical unit pair everywhere: **CPU in integer millicores, memory in integer bytes**. The single parse entry point `Quantities` **throws** and has no zero-valued fallback and no `parseOrDefault` overload. No engine type holds a resource value as a `String`, so a rounding `Value()`-equivalent cannot be written. §4.2 |
| 4 | idle p95 ≈ 0 produced ≈ 0 requests | Floors (25m / 250Mi) clamp decreases only; `IDLE` is a **finding, not a number**; the recommendation type rejects a null or non-positive request at construction. §4.3, §4.6 |
| 5 (decision half) | `php-apache` CPU request cut while a CPU HPA targets it | One engine, one reconciliation step (§4.8). A CPU-metric HPA **freezes the CPU request**; memory stays eligible; `HPA_CPU_MANAGED` is recorded per container. The same step is the extension point for the future co-adjustment rule. |
| 7 | wrote requests, never limits → QoS downgrade; in-place resize **rejects** a QoS-class change and a request above its limit | Recommendations are a **(request, limit) pair per container**, rejected at construction if they would change the QoS class or put a request above its limit. §4.7 |
| 8 | one metric per **Deployment name with no namespace**, applied identically to every container including sidecars | The engine is **per container**; the usage contract is namespace-scoped, carries an owner uid, and requires per-container keys. §5.3 |

---

## 1. Scope of this run

**Built:** the B7 TLS fix; a cluster-access abstraction with typed outcomes; the policy model + one
Flyway migration; the pure decision engine with table-driven tests over the measured reference
workloads; the usage-aggregate and executor **interfaces**; one read endpoint; the published API
contract; two safety flags defaulting to off.

**Deferred — interfaces defined, nothing implemented (restated in §9):** the usage-aggregate
providers (agent-side rolling window and PromQL); the executors (agent command queue and direct
in-place resize, canary, progressive rollout, status checking); the scheduled reconciler +
distributed lock; audit tables; server-side safety gates on write endpoints; template-persist mode;
GitOps detection; the horizontal decision rules; every live-cluster test.

**No AWS calls and no cluster contact in this run, including in tests.** `xamops-test-1` and
`xamops-test-2` belong to other workflows and must not be touched.

**Allowed to change:** new packages under `com.xammer.cloud.service.automation.workload`;
`K8sClientFactory.java` (B7 only); **one** Flyway migration; tests; and `application*.properties`
for exactly two new keys. `pom.xml` is **not** required — §2.4 records why.

> **Authorised deviation from the brief's package name.** The brief allowed
> `com.xammer.cloud.service.automation.vertical`. The orchestrator's architecture correction
> instructs that the engine be named for **workload autoscaling**, not vertical rightsizing alone,
> and that renaming happen now while nothing is built on it. Root package is therefore
> `com.xammer.cloud.service.automation.workload`, with vertical-specific types under
> `...workload.vertical`. **API paths are unchanged** (`/api/eks/automation/vertical/...`): they are
> a hard dependency of the parallel UI workflow and must match §11.5 of its design, not diverge.

**Forbidden (owned by other in-flight work):** `frontend-app/`; `EksAutoscalingController.java`,
`EksAutoscalingService.java`, `EksService.java` (call public methods, never edit);
`SecurityConfig.java`; `xamops-autoscaler/`, `xamops-agent/`; `cloudformation/`; any
AutoSpotting/Spot code; and the worktrees `.worktrees/cloudk8s-ui-revamp` and
`.worktrees/autoscaler-safety` (read-only).

---

## 2. Verified environment facts

Everything here was checked in this worktree before planning; plan items depend on it.

### 2.1 Build and test

No Maven wrapper. Java 25, Maven 3.9.12, surefire 3.5.6.

```bash
# compile (verified: exit 0, offline)
mvn -o -f /Users/apple/Desktop/XamOps-New/.worktrees/java-vertical-rightsizer/xamops-service/pom.xml compile -q

# one test class (verified: 14 tests, 0 failures, ~50 s)
mvn -o -f /Users/apple/Desktop/XamOps-New/.worktrees/java-vertical-rightsizer/xamops-service/pom.xml \
    test -Dtest=RightsizingOptionApplicabilityTest -DfailIfNoTests=false
```

`-o` (offline) works because every dependency is already in `~/.m2`; drop it if a resolution error
appears. Run from the repo root with the absolute `-f` path — CLAUDE.md records repeated failures
from running Maven and git in the wrong directory. macOS BSD `awk`/`sed`: use `python3` for any
nontrivial text processing.

### 2.2 Test infrastructure that already exists

- **H2** (`com.h2database:h2`, test scope) + `@DataJpaTest` +
  `@ContextConfiguration(classes = JpaSliceTestConfig.class)` +
  `@TestPropertySource({"spring.autoconfigure.exclude=", "spring.flyway.enabled=false"})` is the
  repo's JPA slice pattern (`CspBillingConfigRepositoryTest` and 11 more). **No Testcontainers
  anywhere in the module** — do not add it.
- **Opt-in local PostgreSQL** via `com.xammer.cloud.domain.type.PgTestSupport`:
  `LOBTEXT_TEST_JDBC_URL` + `LOBTEXT_TEST_USER`; `requireLocal()` raises a JUnit assumption when
  unset; `recreateDatabase(name)` gives a throwaway database; the URL must be
  localhost/127.0.0.1/::1 or the test fails hard. Classes are also gated with
  `@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")` so CI reports them
  skipped (`SchemaUpdateNoAlterPgTest`, `LobTextScriptsPgTest`). **This is the only route to a real
  PostgreSQL.** The shared remote dev DB at `13.127.62.153` must never be written to.
- `src/test/resources/application.properties` excludes the DataSource, Redis, Session, Kafka and
  Mail auto-configurations and disables Eureka, so plain unit tests need no Spring context.
- **No test in the module runs Flyway** (every JPA test sets `spring.flyway.enabled=false`). §7.7
  explains how the migration is verified given that.

### 2.3 Schema and migration conventions

- Latest migration: `V20261006_1__remove_azure_autospotting.sql`; convention
  `VYYYYMMDD_N__description.sql`; 85 files in `xamops-service/src/main/resources/db/migration/`.
- `spring.jpa.hibernate.ddl-auto=update` in **every** deployed profile (`local`, `uat`, `prod`,
  `preprod`); `spring.flyway.enabled=false` in `local` only; `spring.flyway.out-of-order=true` in
  prod. Tables can therefore be created by Hibernate or by Flyway depending on profile and ordering,
  which is why so many existing migrations are guarded with `to_regclass(...)`. The new migration
  uses `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` and is re-runnable (precedent:
  `V20260907_1__ticket_email_ingest.sql`).
- Tenancy column convention: `client_id BIGINT NOT NULL REFERENCES client(id) ON DELETE CASCADE`
  (`V20260821_2__security_notification.sql`, `V20260910_1__architecture_blueprint_tenancy.sql`). The
  `Client` entity's table is `client`, PK `id`.
- Migrations here are heavily commented with purpose, guards and reversal. Match that.

### 2.4 Kubernetes client — already present, nothing to add

`io.fabric8:kubernetes-client` 7.8.0 via `kubernetes-client-bom` (`k8s.client.version`), with
`kubernetes-httpclient-vertx` as the runtime HTTP client. Verified on the classpath:
`kubernetes-model-autoscaling` (HPA v2), `kubernetes-model-policy` (PDB), `kubernetes-model-apps`,
`kubernetes-model-core`, `kubernetes-model-metrics`. Verified API surface:

- `io.fabric8.kubernetes.api.model.Quantity` — `parse(String)`, `getNumericalAmount()`,
  **`static BigDecimal getAmountInBytes(Quantity)`** (base units: cores for CPU, bytes for memory),
  both declared `throws ArithmeticException`.
- `io.fabric8.kubernetes.client.Config` — `isTrustCerts()` / `setTrustCerts(boolean)`,
  `setDisableHostnameVerification(boolean)`, and inherited from `SundrioConfig`:
  `getCaCertData()` / `setCaCertData(String)`, `getCaCertFile()` / `setCaCertFile(String)`.

**`pom.xml` is not touched.** Everything needed — including `keytool` and
`com.sun.net.httpserver.HttpsServer` for the TLS test — ships with the JDK or is already a
dependency.

### 2.5 B7: where the TLS hole is, and who shares it

`xamops-service/src/main/java/com/xammer/cloud/service/K8sClientFactory.java` (78 lines, 2 methods):

- `createFromKubeconfig(String)` — line ~23: `config.setTrustCerts(true)`, commented *"Disable SSL
  verification to prevent PKIX path validation errors"*.
- `createFromComponents(String apiServer, String caCertData, String token)` — line ~49:
  `.withTrustCerts(true) // FIX: Trust certs here as well`.

Both disable TLS verification for **every** customer API-server connection. Call sites (grepped
across the module):

| Call site | Method | CA availability |
|---|---|---|
| `CloudK8sSecurityController` :66 (Kubescape), :107 (Karpenter), :151 (connection test) | `createFromKubeconfig` | the stored kubeconfig's `certificate-authority-data` |
| `EksDashboardService` :823 (Kubescape, Trivy, Karpenter, OpenCost direct path) | `createFromKubeconfig` | same |
| `EksService.getKubernetesClientForCustomerCluster` :309 | `createFromComponents` | `describeCluster().certificateAuthority().data()` — base64 PEM |

`EksService:289-313` has **no callers** in the module today (verified by grep), but it is public API
and must keep working. The EKS path already fetches the real CA and then throws the benefit away.

### 2.6 Tenancy, cluster lookup and the B4 ambiguity

- `CloudAccount` — `id` (PK), `awsAccountId` (unique, 12-digit string), `accessType`, `status`,
  `provider`, and `client` (`@JoinColumn(name = "client_id", nullable = false)`).
- `CloudAccountAccessService.requireAwsAccess(ClientUserDetails, String awsAccountId)` resolves the
  12-digit AWS account number (padded **and** raw, AWS rows only), enforces tenancy via `canAccess`,
  and throws `AccessDeniedException` otherwise. This is the existing correct entry point;
  `AutoSpottingController` documents the same contract. `ClientUserDetails` exposes `getId()`,
  `getClientId()`, `getTenantId()`.
- `KubernetesClusterConfig` (`kubernetes_cluster_config`) — `id` (PK),
  **`cloudAccountId` (the B4 ambiguity: DB PK on modern rows, AWS account number on legacy rows)**,
  `clusterName`, `region`, `dataSourceMode`, `kubeconfigYaml`, `autoscalingEnabled`, `isPrivate`,
  `lastAgentCheckin`, `agentStatus`, `isAgentActive()` (15-minute window). Repository has
  `findByCloudAccountIdAndClusterName(Long, String)` and `findByClusterName(String)`.
- `GlobalExceptionHandler` handles `AccessDeniedException` (403) and `IllegalArgumentException`
  (400); a controller-local `@ExceptionHandler` wins over its catch-all (`AutoSpottingController`
  precedent).

### 2.7 Security path coverage (SecurityConfig is read-only)

`SecurityConfig.java` lists `/api/eks/autoscaling/**` in the explicit `.authenticated()` block
(:330) and ends with **`.anyRequest().authenticated()`**, with
`defaultAuthenticationEntryPointFor(401, /api/**)`. So `/api/eks/automation/**` is authenticated by
the catch-all and an unauthenticated call returns **401**, not a redirect. CSRF is disabled
platform-wide (a known platform follow-up, UI design F17); this run adds no mutating endpoint, so it
adds no CSRF exposure.

`frontend-app/src/demo/demoRouter.ts` has a catch-all `url.includes('/api/xamops/k8s/')` returning
demo cluster data, so an `/api/xamops/k8s/…` prefix **would be intercepted in demo mode**. The
engine must not use it. `/api/eks/automation/…` matches no demo rule.

### 2.8 The agent channel, and why it is the primary execution path

Verified in this worktree:

- `xamops-agent/internal/pusher/pusher.go:182` polls **`GET /api/v1/agent/commands`** outbound-only,
  processes **at most one command per heartbeat cycle**, and at `:214` **skips any command whose type
  it does not recognise**. A new command type is therefore forward-compatible: an old agent ignores
  it instead of failing.
- `AgentController` (`@RequestMapping("/api/v1/agent")`) already serves `/ingest` (:146),
  `/heartbeat` (:308), `/config` (:510), `/commands` (:559) and `/autoscaling-policy` (:737). Every
  one is initiated **from inside the cluster**, so **no inbound reachability is required** — the
  channel already works for private-endpoint clusters today.
- **Caveat, stated honestly:** the existing `/commands` implementation is the **DB agent's**
  `ExplainRequest` queue, addressed by `agentName` and authorised with the *global* API key. Its own
  source comment records that `agentName` is not an account or a cluster, so there is nothing to bind
  a scoped key against. A **cluster-scoped** command queue for workload autoscaling is therefore new
  work (deferred), modelled on a proven outbound-only pattern rather than on an existing
  cluster-scoped queue.
- `xamops-agent/internal/collector/k8s_resources.go:384-397` already collects per-pod, per-container
  usage (`PodMetrics` from `metrics.k8s.io`) across all namespaces, on the agent's collection
  interval. The harness measured `podMetrics` covering 51 of 51 running pods with per-container CPU
  in nanocores and memory in Ki.
- `AgentDataCache` (`agent_data_cache`) is **`@UniqueConstraint(columnNames = {"cluster_config_id",
  "data_type"})`** with a single `collected_at`: one row per cluster per data type, **each push
  overwrites the last**. There is **no history retention anywhere in the backend.** A backend
  time-series store is roadmap C6, effort XL.

Two conclusions follow, and they shape §5:

1. **The agent-mediated path is primary; direct backend-to-API is an optional fast path.** The
   executor interface must fit a **command-queue, asynchronous, at-least-once** model — correlation
   id, outcome arriving on a separate later request, retry and idempotency semantics, terminal
   timeout. A synchronous `apply()` that returns the result would have to be redone to support the
   primary path.
2. **"Agentless vertical rightsizing" is largely illusory.** Percentile history has to be computed
   from inside the cluster regardless, because the backend retains no history and will not soon.
   Direct mode removes the need for an in-cluster *actuator*, never for an in-cluster *observer*.

### 2.9 Reference PromQL for the deferred metrics path

`eks/test-clusters/harness/queries-controllers.promql` (26 lines) records the exact queries each Go
component sends plus the signals none of them query. Carried into §5.4 as the starting point for the
next run. Nothing is implemented against it here.

---

## 3. Design decisions

### D1. One engine for workload autoscaling; vertical implemented, horizontal modelled

`WorkloadAutoscalingEngine.decide(WorkloadSnapshot, WorkloadPolicy, NodeCapacity) → WorkloadDecision`.
The decision type carries **both** a vertical part (per-container request/limit targets —
implemented) and a horizontal part (`minReplicas`, `maxReplicas`, target utilisation — modelled,
always `NOT_EVALUATED` this run), and they are combined by one internal reconciliation step (§4.8).

Rationale: the measured failure was not that either controller was individually miscalibrated — it
was that **two controllers rewrote one workload in one cycle with zero cross-reference**, and the
CPU request they disagreed about *is* the HPA's denominator. A veto bolted onto a vertical-only
engine reproduces that split, just with one side silent. Modelling both outputs now, with the
reconciliation as a named function, means the horizontal rules land as an implementation of an
existing extension point rather than as a second engine.

### D2. The engine is a pure function; every I/O concern is an interface

No I/O, no Spring dependencies, plain Java records in and out. The nine reference workloads are then
assertable as literal fixtures in millisecond unit tests with no cluster. The engine's input records
are **ours**, not fabric8 types; fabric8 appears only at provider boundaries. This also keeps the
engine free of `Quantity` strings (bug 3).

### D3. One unit pair, one parser, no silent zero

CPU is `int` millicores, memory is `long` bytes, and `Quantities` is the only place a Kubernetes
quantity string is read. It throws `QuantityParseException` carrying the offending string and its
field. There is no `parseOrDefault`. `Quantity.getAmountInBytes` returns base units, so CPU millicores
is `getAmountInBytes(q).multiply(1000).setScale(0, HALF_UP)` — `250m` → `250`, exactly, never `1000`.

### D4. Percentile computation is measurement, not decision — it lives outside the engine

`UsageHistoryProvider` returns **pre-aggregated per-container percentiles over a window**
(p50/p90/p95/p99/max, plus sample coverage and explicit window bounds), from whichever source
supplies them: an agent-side rolling window (as upstream VPA does with decaying histograms) or a
PromQL query. It is deliberately **not** designed around raw time-series access, because the backend
has none (§2.8) and will not for a long time. Aggregating observations is not deciding; the engine
still owns every decision — floors, hysteresis, QoS, coexistence, findings.

Consequence for honesty: a `PercentileSet` that does not contain the policy's percentile **throws**
rather than substituting a neighbour. "Never swallow a parse failure" extends to "never silently
substitute a percentile you were not given."

### D5. Canonical identifier for new tables: `kubernetes_cluster_config.id` + `client.id`

**Decision: new tables carry `cluster_config_id BIGINT NOT NULL REFERENCES
kubernetes_cluster_config(id)` and `client_id BIGINT NOT NULL REFERENCES client(id)`. They never
carry a `cloud_account_id` column.**

`kubernetes_cluster_config.cloud_account_id` holds two different kinds of identifier (audit B4): on
modern rows the `cloud_account` primary key, on legacy rows the AWS account number. A new table that
copied it would inherit a column whose meaning depends on the row's age and would make tenant scoping
a guess. `kubernetes_cluster_config.id` is a surrogate key, unambiguous by construction, that already
uniquely identifies a registered cluster. `client_id` is stored explicitly — denormalised on purpose
— so tenant scoping never traverses the ambiguous column; it is resolved once at write time from the
entitled `CloudAccount.getClient().getId()`.

Request-time lookup mirrors the rule the UI design states for the Go endpoints: the caller's
`accountId` is only ever matched against `CloudAccount.awsAccountId`, **never** used as a
`cloud_account_id`. The cluster config is then found by `account.getId()` first and, for legacy rows,
by the entitled row's own AWS account number parsed as a `Long` — both values taken from a row the
caller is entitled to. The platform-wide B4 fix stays UI-design follow-up F6.

### D6. Policy is per workload, tenant-scoped, absent-row-means-defaults

One row per `(cluster_config_id, namespace, workload_kind, workload_name)`. No row means defaults
apply, so there is no backfill and a cluster registered tomorrow behaves like one registered last
year (the `security_notification_preference` precedent). Container-level policy overrides are
deferred: recommendations are per container, the *policy* is per workload, which is what the UI's
§11.5 view model expects.

Defaults live in exactly two places: SQL column defaults (so a row written by any path is safe) and
`WorkloadPolicyDefaults` Java constants (so the no-row case matches). The protected-namespace set and
all user-facing reason text are Java constants reviewed with the code — the same reasoning the UI
design gives for its readiness messages — which keeps the new property surface to exactly the two
mandated flags.

### D7. `AUTO` is storable but not honourable this run

`mode` accepts `OFF | RECOMMEND | AUTO` because the UI's policy drawer needs the enum. With no
executor and with `vertical-rightsizing` readiness false, the read endpoint reports
`CONTROLLER_NOT_READY` and every decision carries `autoApplyEligible: false`. Nothing in this slice
can act on a cluster, so a stored `AUTO` is inert by construction, not by a flag check.

### D8. Reason codes are shared verbatim; the shared JSON fixture is not created here

The nine shared codes (§6.2) are declared as a Java enum and listed verbatim in the published
contract. The UI design (§6.1, R3) places a shared fixture at
`xamops-service/src/test/resources/k8s/k8s-reason-codes.json` — **that file belongs to the UI
branch**, and creating it here would conflict on merge. Instead a unit test asserts the engine enum's
constant names equal the nine strings exactly, and the contract records the handshake. Codes appear
**without** the `K8S_` prefix in payload bodies and **with** it in refusal `code` fields; the UI
parses both (R3). The engine adds **no** new shared gate codes: the agent-path state reuses the UI's
existing `AGENT_NOT_INSTALLED`, and direct-path failures reuse `CLUSTER_ACCESS_NOT_CONFIGURED` /
`CLUSTER_UNREACHABLE`.

### D9. Readiness reads the property the UI branch defines

`xamops.k8s.automation.readiness.vertical-rightsizing`, default `false` (UI design §6.7, R1), so one
flag flips the UI and the engine together. The engine binds it with a one-field
`@ConfigurationProperties` rather than depending on the UI branch's
`K8sAutomationReadinessProperties`, which does not exist on this base; when both land, the binding
can be swapped for that bean without behaviour change. Both branches will add the same key — on
merge, keep one copy.

### D10. The read endpoint never fabricates

With the global flag off (the default) the endpoint makes **no** cluster or AWS call: it returns
engine status plus gate reasons and an **empty** `workloads` array. With the flag on but no usage
provider implemented, the stub reports `NO_METRICS_SOURCE` and `workloads` stays empty. The endpoint
never emits a zero, a placeholder or an unevaluated workload — the measured `1Mi` fabricated baseline
for `no-requests-sidecar-app` is exactly the failure being designed out. The engine→DTO mapping is
proven by unit tests injecting a fake provider that returns the §7 fixtures.

### D11. API path `/api/eks/automation/vertical/...`

Matches the UI's provisional contract §11.5 exactly (a hard dependency — the published contract must
match, not diverge), is authenticated by `.anyRequest().authenticated()` (§2.7, verified by test in
item 9), and avoids the demo-router catch-all. It is deliberately **not** under
`/api/eks/autoscaling/**`: that prefix is the Go agent's policy surface, owned by
`EksAutoscalingController` (forbidden to edit), where the UI branch registers its guard interceptor.
Separate prefix, separate ownership boundary. The path keeps the word `vertical` because the UI is
already built against it; the *engine* is named for workload autoscaling, and the horizontal
endpoints, when they exist, will sit beside it under `/api/eks/automation/`.

---

## 4. The decision engine

### 4.1 Shape

```
com.xammer.cloud.service.automation.workload
  WorkloadAutoscalingEngine   decide(WorkloadSnapshot, WorkloadPolicy, NodeCapacity) -> WorkloadDecision

  ── inputs ────────────────────────────────────────────────────────────────────
  WorkloadSnapshot      ref, replicas, rolloutInProgress, lockAnnotationActive,
                        hpa (nullable), pdbMinAvailable (nullable), gitopsManaged (nullable),
                        containers[]
  WorkloadRef           namespace, kind, name, ownerUid        (namespace is mandatory)
  ContainerSnapshot     name, ResourceEnvelope current, ContainerUsageAggregate usage
  ResourceEnvelope      Integer cpuRequestMillis, Integer cpuLimitMillis,
                        Long memRequestBytes,   Long memLimitBytes      (null == unset)
  HpaView               name, minReplicas, maxReplicas, currentReplicas,
                        cpuResourceMetric (nullable), memResourceMetric (nullable), lastScaleTime
  NodeCapacity          maxAllocatableCpuMillis, maxAllocatableMemBytes, usableFraction
  WorkloadPolicy        mode, headroomPct, percentile, windowHours, floors, hysteresis,
                        applyMethod, cooldownMinutes, maxLimitIncreaseFactor,
                        protectSingleReplica, lastAppliedAt (nullable)

  ── outputs ───────────────────────────────────────────────────────────────────
  WorkloadDecision      ref, SkipReason (nullable), findings[], Provenance,
                        autoApplyEligible,
                        VerticalDecision vertical,          // implemented
                        HorizontalDecision horizontal       // modelled: NOT_EVALUATED this run
  VerticalDecision      containerDecisions[]
  ContainerDecision     containerName, qosClass, ResourceEnvelope current,
                        ResourceEnvelope recommended (nullable), reasons[], ChangeKind
  HorizontalDecision    state (NOT_EVALUATED | NO_CHANGE | CHANGE), Integer minReplicas,
                        Integer maxReplicas, Integer targetCpuUtilizationPct, reasons[]
  Provenance            percentile, windowHours, windowStart, windowEnd, sampleCoverage,
                        usageSource, evaluatedAt, policySource (WORKLOAD_ROW | DEFAULTS)
```

`ResourceEnvelope` distinguishes **unset** (`null`) from **zero**. The measured bug reported
`currentCpuRequest: "0m"` / `currentMemRequest: "1Mi"` for a container that had neither; that state is
unrepresentable here.

A `recommended` envelope is validated at construction and rejects: a null or non-positive request; a
request above its own limit; and any pairing that would change the container's QoS class relative to
`current`. An invalid recommendation therefore cannot exist as a value, let alone be served or
applied.

`HorizontalDecision` is constructed only as `NOT_EVALUATED` this run; a factory for the other states
exists and is unreferenced, and a test asserts the engine returns `NOT_EVALUATED` for every fixture.
That keeps the modelled half honest: it is a declared extension point, not a half-built feature.

### 4.2 Units and parsing (bug 3)

`Quantities` is the only quantity reader:

```java
static int    cpuMillis(String q)    // "250m" -> 250 ; "1" -> 1000 ; "0.25" -> 250
static long   memBytes(String q)     // "250Mi" -> 262144000 ; "1Gi" -> 1073741824
static String formatCpu(int millis)  // 250 -> "250m"
static String formatMem(long bytes)  // 262144000 -> "250Mi" when exact, else a byte count
```

Implemented over `Quantity.parse` + `Quantity.getAmountInBytes`, `BigDecimal` throughout,
`setScale(0, HALF_UP)` for millicores. Any `IllegalArgumentException`, `ArithmeticException`, null or
blank becomes a thrown `QuantityParseException` naming the offending string and its field. No
overload returns a default.

### 4.3 Target computation, per container

1. `target = usage.percentileAt(policy.percentile) * (1 + headroomPct/100)` — CPU from the CPU
   percentile set, memory from the memory percentile set. Defaults: percentile **p95**, window
   **24h**, headroom **25%**. The Go engine used CPU p80 and `max_over_time` for memory; roadmap
   §5.4 C4 notes `max_over_time` overshoots, and the harness shows p80 understates a throttled
   workload. One rule for both resources, and the value used is reported in `Provenance`.
2. **Direction** is decided against the *current request*, never against zero: `INCREASE`,
   `DECREASE`, `NONE`.
3. **Floors clamp decreases only** (bug 4). With floor `f`, current `c`, computed target `t`:
   - `t < f` and `c > f` → recommend `f` (a legitimate, smaller decrease).
   - `t < f` and `c <= f` → **no recommendation** for that resource. Increases are driven only by
     measured demand, never by the floor's existence.
   - `t >= f` → recommend `t`.
   Floors are **25m CPU / 250Mi memory**: upstream VPA's `--pod-min-cpu-millicores` /
   `--pod-min-memory-mb` defaults. The harness measured VPA stopping at exactly `25m / 250Mi` on five
   of nine workloads, which is both the justification for the values and the reason the Go engine's
   `1m / 1Mi` output was identifiably wrong.
4. **Upper bound.** The recommended request is capped so the pod's total requests (this container's
   recommendation plus the other containers' current-or-recommended requests) fit the largest
   schedulable node times `usableFraction` (default **0.9**). When the cap bites, the capped value is
   emitted with reason `NODE_CAPACITY_CAPPED`. Reference: `team-a/unschedulable-giant` asked for
   32 CPU / 64Gi on nodes with 1930m / 3294Mi allocatable and has been `Pending` for 8 hours.
5. **Hysteresis** (§4.4) decides whether the surviving delta is worth acting on.
6. The result becomes a `(request, limit)` pair via the QoS rules (§4.7).
7. **Cooldown.** If `lastAppliedAt` is within `cooldownMinutes` (default **60**), the container
   decision is suppressed with reason `COOLDOWN`.

### 4.4 Hysteresis — the chosen thresholds, and why

A change is recommended only when it exceeds **both** a relative **and** an absolute threshold, and
the thresholds are **asymmetric by direction**.

| Resource | Direction | Relative threshold | Absolute threshold |
|---|---|---|---|
| CPU | increase | **≥ 15%** of the current request | **≥ 25m** |
| CPU | decrease | **≥ 30%** of the current request | **≥ 100m** |
| Memory | increase | **≥ 15%** | **≥ 32Mi** |
| Memory | decrease | **≥ 30%** | **≥ 128Mi** |

Policy columns: `min_increase_pct`, `min_decrease_pct`, `min_increase_cpu_millicores`,
`min_decrease_cpu_millicores`, `min_increase_memory_bytes`, `min_decrease_memory_bytes`.

**Why `AND`, not `OR`.** The control workload `prod-sim/rightsized-worker` (80m/64Mi) was measured
being cut by **−32.8%**, i.e. by **26m**. A relative-only band cannot suppress that without being set
above ~33%, which would also suppress a genuine 30% decrease on a 1000m container (300m — real
money). An absolute-only band cannot suppress a `1Mi → 4Mi` thrash step on a tiny container. Both
must be exceeded, so the suppressed region is "small in percentage terms **or** small in absolute
terms" — exactly where acting costs more churn than it returns capacity. This is also what harness
§10 item 1 asks for: *suppress a 32% drift on a control workload like `rightsized-worker` when the
absolute delta is small.*

**Why 100m for a CPU decrease.** The measured false positive was 26m; 100m is ~4× that, so the
control case sits well inside the band rather than on its edge. It is also the smallest decrease that
can pay for itself: on the measured node shape (t3.medium, **1930m allocatable**) 100m is ~5% of a
node, so freeing less than that per container cannot aggregate into a drained node without hundreds
of containers. Below 100m a recommendation buys churn, not capacity.

**Why 25m for a CPU increase.** One floor-unit — the smallest CPU quantity this engine will express
at all. An increase relieves measured pressure rather than speculatively reclaiming, so the band is
one unit instead of a node fraction. This asymmetry is what lets `prod-sim/throttled-encoder`
(10m → 63m, a 53m step) be recommended while the control workload's 26m decrease is not: similar in
magnitude, opposite in risk.

**Why 15% / 30% relative.** Upstream VPA treats ~10% as a significant change. The harness measured
agent-vs-`kubectl top` CPU jitter up to ~17% on individual nodes (`382m` vs `317m`), so a 10% band
sits inside the measurement noise floor; 15% clears it for increases. Decreases get 30% because a
decrease is the direction that causes outages, and because §7-B1 proved the kubelet applies even a
`1Mi` memory request **in place, with no restart and no complaint** — there is no platform backstop
below this number.

**Why memory decreases get 4× the increase band (128Mi vs 32Mi).** A CPU shortfall throttles; a
memory shortfall OOM-kills immediately and irreversibly. 128Mi is about half the 250Mi floor and ~4%
of the measured 3294Mi node allocatable. 32Mi for increases is the smallest memory step worth writing
and sits below the control workload's 64Mi request, so an increase band cannot be cleared by rounding
noise on a small container.

**Convergence.** The measured thrash loop (cycle 1 wrote 1Mi, cycle 2 wrote 4Mi) is structurally
impossible here for four independent reasons: floors stop the target collapsing to a value that
violently changes the next measurement; hysteresis is always evaluated against the **current**
request, so a second cycle computing the same target sees a zero delta and recommends nothing;
`cooldownMinutes` suppresses a second recommendation inside the window; and the deferred executor's
command carries a terminal timeout so a late apply cannot land against stale measurements (§5.2). A
test asserts the fixed point directly: feeding a decision's own output back as `current` produces no
recommendation.

### 4.5 Gates — evaluated before any arithmetic

In order; the first match sets `skipReason`, and the decision then carries **no** container
recommendations.

| Gate | Condition | `skipReason` |
|---|---|---|
| Protected namespace | namespace ∈ {`kube-system`, `monitoring`, `amazon-cloudwatch`, `vpa`, `xamops-system`} | `PROTECTED_NAMESPACE` |
| Single replica | `replicas <= 1` and `protectSingleReplica` | `SINGLE_REPLICA` |
| Mid-rollout | `rolloutInProgress` (`generation != status.observedGeneration`) | `ROLLOUT_IN_PROGRESS` |
| Lock annotation | `xamops.io/lock: active` on the workload | `LOCKED` |
| HPA volatile | `hpa.lastScaleTime` within the volatility window (default 5 min) | `HPA_VOLATILE` |
| Insufficient history | `sampleCoverage < 0.5`, or the window is shorter than 2h | `INSUFFICIENT_HISTORY` |
| Policy off | `mode == OFF` | `POLICY_OFF` |

The protected set is a Java constant, not a property, and not per-workload-overridable: a
per-workload row cannot unprotect `kube-system`. The lock annotation key is `xamops.io/lock` (the Go
reference used `omniscaler.io/lock`); `xamops.io/lock: active` is what the UI already renders (UI
design §9.2). `PROTECTED_NAMESPACE` and `SINGLE_REPLICA` are the two guards the Go rightsizer **did**
honour correctly (harness §1, §2) — kept, and moved into the engine so both the vertical and the
future horizontal half read one rule. The Go HPA manager not applying `protectSingleReplicas` while
the rightsizer did is precisely the inconsistency one engine removes.

### 4.6 Findings — observations, not numbers

`findings[]` is the UI's workload-level finding list. A finding never implies a recommendation.

- **`REQUESTS_UNSET`** — any container with no CPU **or** no memory request (QoS `BestEffort`, or a
  partially-set `Burstable`). The engine emits an **advisory** recommendation from measured usage with
  `autoApplyEligible: false` and reason `REQUESTS_UNSET`, and never a fabricated current value.
  Reference: `dev-sim/no-requests-sidecar-app`, reported by the Go engine as `0m / 1Mi` and "fixed" to
  `1m / 1Mi` with 0% savings.
- **`IDLE`** — `cpuPercentile <= max(1m, 1% of the current CPU request)` **and** the memory
  percentile at or below its floor. Emits `IDLE` and **no request recommendation**: the honest remedy
  is scale-to-zero or deletion, which this engine does not do. Reference:
  `dev-sim/idle-batch-listener` (3 replicas, no traffic, no Service) — the Go engine recommended a
  `0m` CPU request and an HPA with `minReplicas: 2`, pinning an idle workload to two replicas forever.
- **`THROTTLED`** — `cpuThrottleRatio >= 0.2`. Measured usage is then **censored by the limit**, so
  the request target is derived from the limit rather than from the capped usage, and the limit is
  raised (§4.7). Reference: `prod-sim/throttled-encoder`, 10m request / 50m limit, throttle ratio
  **1.0** — the Go engine raised the request to 56m from usage pinned at the 50m limit and left the
  pod throttled at 1.0, because it never writes limits.
- **`OOM_RISK`** — `oomKillsInWindow > 0`; forces that container's memory direction to increase-only.
- **`HPA_COEXISTENCE`** — a resource-metric HPA targets the workload (§4.8).
- **`NODE_CAPACITY_CAPPED`** — the §4.3-step-4 cap bit.

### 4.7 QoS preservation and limits (bug 7)

QoS class is computed with Kubernetes' own rule over the pod's containers: `Guaranteed` when every
container has CPU and memory requests equal to its limits; `Burstable` when something is set but not
all equal; `BestEffort` when nothing is set.

- **`Guaranteed`** — requests and limits move **together** to the same value, for CPU and memory. If
  one resource cannot move (for example CPU frozen by an HPA, §4.8) then that resource's limit does
  not move either and `QOS_PRESERVED_NO_CHANGE` is recorded. An HPA on a `Guaranteed` workload
  therefore means **no CPU change at all**, because moving the request alone would downgrade the
  class — the exact silent downgrade measured in §7-B3 (`Guaranteed → Burstable`, requests 1m/4Mi
  against limits 1 CPU/1Gi).
- **`Burstable` with limits set** — the request:limit ratio is preserved. New limit =
  `clamp(round(newRequest × currentRatio), newRequest, currentLimit × maxLimitIncreaseFactor)` with
  `maxLimitIncreaseFactor` default **4.0**, then the §4.3-step-4 node cap. A limit is never lowered
  below the recommended request, so an in-place resize can never fail validation on "request exceeds
  limit". `LIMIT_RAISED` is recorded whenever a limit moves.
  Worked example — `throttled-encoder`: request 10m, limit 50m, ratio 5.0, throttle ratio 1.0. The
  limit censors usage, so the target request comes from the limit: `50m × 1.25 = 63m`, which lands on
  upstream VPA's independently-computed 63m target — a useful corroboration. New limit =
  `min(63 × 5, 50 × 4) = min(315m, 200m) = 200m`. Findings `THROTTLED`; reasons `LIMIT_RAISED`,
  `DEMAND_CENSORED_BY_LIMIT`. Bounded, deterministic, and it actually relieves the throttling instead
  of leaving the pod at ratio 1.0.
- **`Burstable` with no limit on a resource** — the limit stays absent; only the request moves.
- **`BestEffort` / any unset request** — `REQUESTS_UNSET`, advisory only, `autoApplyEligible: false`.
- A recommendation that would change the class is rejected at construction (§4.1). In-place resize
  rejects a QoS-class change, so this is a correctness requirement for the deferred executor, not a
  style preference.

### 4.8 Coexistence reconciliation (bug 5, decision half) — one function, one owner

`reconcile(VerticalDraft, HorizontalDraft, HpaView) → (VerticalDecision, HorizontalDecision)` is the
single place where the two halves meet. The HPA is matched on `scaleTargetRef` kind + name **in the
same namespace**, never on a name prefix.

Implemented this run (the conservative half of roadmap item 3):

- A **`Resource: cpu`** HPA metric (`Utilization` or `AverageValue`) **freezes the CPU request**: no
  CPU request recommendation, reason `HPA_CPU_MANAGED` on every container, finding
  `HPA_COEXISTENCE`. The request is the HPA's utilisation denominator, so changing it silently moves
  the HPA's set point — measured as the `php-apache` case, where cutting 200m → 144m would have moved
  measured utilisation from ~65% to ~90% at unchanged load while a second controller raised the HPA
  target from 50% to 80%.
- A **`Resource: memory`** HPA metric freezes the memory request the same way with
  `HPA_MEMORY_MANAGED`.
- Memory stays eligible when only CPU is HPA-managed, and vice versa.
- On a `Guaranteed` workload the freeze extends to that resource's limit (§4.7).

Modelled, not implemented — documented here so the extension point is concrete rather than a
gesture. The alternative to freezing is **co-adjustment**: preserve the effective trigger point
`request × targetUtilisation` when the request moves, i.e.
`newTargetUtilPct = round(oldRequestMillis × oldTargetUtilPct / newRequestMillis)`, clamped to a
sane band (e.g. 10–90) and refused when the clamp would bind or when a PDB's `minAvailable` would
exceed the resulting `minReplicas`. For `php-apache` (200m at 50%, target request 144m) that is
`200 × 50 / 144 ≈ 69%`. Co-adjustment requires writing the HPA, which this engine does not yet own;
when it does, it lands inside `reconcile` and nowhere else. The horizontal draft also carries the
PDB floor (`minReplicas >= pdbMinAvailable`), which is the guard the Go HPA manager lacked when it
proposed `minReplicas: 2` for `pdb-guarded-web` under `minAvailable: 3`.

### 4.9 Cadence

The Go reference's requeue cadence is kept as a pure, testable function; the scheduler itself is
deferred:

```java
enum ReconcileOutcome { NORMAL, VOLATILE, METRICS_FAILURE, DISABLED }
Duration ReconcileCadence.next(ReconcileOutcome o)   // 30m / 2m / 1m / 1h
```

A unit test pins the four values. The deferred reconciler consumes this and nothing else decides
timing.

### 4.10 Provenance

Every `WorkloadDecision` carries `percentile`, `windowHours`, `windowStart`, `windowEnd`,
`sampleCoverage`, `usageSource`, `evaluatedAt` and `policySource`; every `ContainerDecision` carries
its `reasons[]`. The UI renders source and freshness on every panel (UI design §10), and the
harness's central complaint about the Go output is that the numbers arrive with no stated basis.

---

## 5. Interfaces defined, not implemented

### 5.1 `ClusterAccessProvider` (slice 2)

```java
package com.xammer.cloud.service.automation.workload.access;

public interface ClusterAccessProvider {
    ClusterAccess open(ClusterTarget target) throws ClusterAccessException;   // caller closes
    boolean supports(ClusterTarget target);
}

public record ClusterTarget(Long clientId, Long clusterConfigId, String awsAccountId,
                            String clusterName, String region) {}

public final class ClusterAccess implements AutoCloseable {     // wraps KubernetesClient + metadata
    KubernetesClient client(); String apiServerUrl(); String clusterName(); String region();
}

public class ClusterAccessException extends Exception {
    public enum Kind { CLUSTER_ACCESS_NOT_CONFIGURED, CLUSTER_UNREACHABLE }
    public Kind kind();                                          // 1:1 with the shared reason codes
}
```

Two implementations:

- **`EksClusterAccessProvider`** (direct fast path) — re-implements the logic at
  `EksService.java:289-313` in this package. **`EksService` is not edited**: the provider injects
  `CloudAccountRepository`, `AwsClientProvider` and `EksTokenGenerator` itself, calls
  `eks.describeCluster(b -> b.name(clusterName)).cluster()`, takes `endpoint()` and
  `certificateAuthority().data()`, mints the token with
  `eksTokenGenerator.generateTokenForCluster(...)`, and builds the client through the **fixed**
  `K8sClientFactory.createFromComponents`. Failure mapping: a missing cluster config, an
  `AccessDenied`/`ResourceNotFound` from `describeCluster`, or a 401/403 from the API server →
  `CLUSTER_ACCESS_NOT_CONFIGURED`; a connect timeout, DNS failure or private-endpoint
  unreachability → `CLUSTER_UNREACHABLE`. Generic `RuntimeException`s are never leaked: the two typed
  kinds are the only outcomes, which is what makes the UI's gate rendering possible.
- **`KubeconfigClusterAccessProvider`** (test path) — the stored kubeconfig, or an explicitly
  supplied API-server URL + CA + ServiceAccount token, for the deferred live-test slice. Same typed
  outcomes.

Selection is by `supports(...)`; no provider is `@Primary` this run, and neither is invoked while the
global flag is false.

### 5.2 `WorkloadActionExecutor` — asynchronous command queue, defined, zero implementations

The agent-mediated path is **primary** (§2.8), so the interface is shaped around a command the agent
fetches, applies, and reports on **later**:

```java
package com.xammer.cloud.service.automation.workload.execute;

public interface WorkloadActionExecutor {
    ExecutionPath path();                                    // AGENT (primary) | DIRECT (fast path)
    boolean supports(ClusterTarget target);

    /** Enqueue. Returns as soon as the command is durable; never the applied result. */
    ActionSubmission submit(ActionCommand command) throws ClusterAccessException;

    /** The outcome arrives on a separate, later request. Empty until the agent reports. */
    Optional<ActionOutcome> poll(String correlationId);

    /** Best-effort; a command already in flight may still land. */
    void cancel(String correlationId);
}

public record ActionCommand(
        String correlationId,              // caller-generated, stable -> the idempotency key
        ClusterTarget target, WorkloadRef ref,
        List<ContainerDecision> containerDecisions,
        HorizontalDecision horizontal,     // modelled; NOT_EVALUATED today
        ApplyMethod method,                // IN_PLACE | IN_PLACE_AND_PERSIST
        boolean dryRun,                    // mandatory, both directions
        String expectedObservedRevision,   // optimistic precondition; a replay is a no-op
        Instant notValidAfter) {}          // terminal timeout

public record ActionSubmission(String correlationId, SubmitState state, String detail) {}
public enum SubmitState { ACCEPTED, DUPLICATE, REFUSED }

public record ActionOutcome(String correlationId, boolean dryRun, Terminal terminal,
                            Map<String, ContainerApplyStatus> perContainer,
                            Instant reportedAt, int attempt, String detail) {}
public enum Terminal { APPLIED, PARTIALLY_APPLIED, REFUSED, FAILED, EXPIRED }
```

Why each field exists, grounded in a measurement or a verified repo fact:

- **`correlationId` + `expectedObservedRevision`** — delivery is **at-least-once**: the agent polls,
  applies, then may fail before reporting, so the next poll re-delivers. The correlation id is the
  idempotency key and the revision precondition makes a replay a no-op instead of a second patch. The
  Go rebalancer was measured **non-idempotent**, re-draining the same two nodes every 60 s with no
  completion state.
- **outcome arrives via `poll`, not as a return value** — `pusher.go:182` fetches commands
  outbound-only and processes at most one per heartbeat cycle; nothing can block on an in-cluster
  apply. Designing `apply()` to return the result would have to be redone for the primary path.
- **`notValidAfter`** — a command that sat in the queue past its window **expires** rather than being
  applied late against stale measurements. This is the queue-side half of the anti-thrash design in
  §4.4.
- **`dryRun` on both the command and the outcome** — harness §2/§5 measured the Go rebalancer
  emitting `severity: "success"` / `"Rebalance completed: 2 nodes drained"` under `DRY_RUN=true` with
  no marker: an audit trail of events that never happened. Every entry in the deferred actions feed
  carries it.
- **`ExecutionPath.AGENT` first** — `pusher.go:214` skips unrecognised command types, so introducing
  a new workload-autoscaling command type is forward-compatible with already-deployed agents.
- **`ApplyMethod.IN_PLACE` default** — §7-B1 proved the resize subresource changes CPU *and* memory
  with the same pod UID, same containerID and `restartCount: 0`. `IN_PLACE_AND_PERSIST` exists
  because the pod-scoped resize leaves the template untouched, so new replicas start at the old size;
  it restarts every pod and is opt-in (UI design §9.2).

**No implementation is written this run.** Neither a `DIRECT` nor an `AGENT` executor bean exists, so
no code path can act on a cluster.

### 5.3 `UsageHistoryProvider` — pre-aggregated percentiles, defined, plus one stub

```java
package com.xammer.cloud.service.automation.workload.usage;

public interface UsageHistoryProvider {
    String sourceName();
    /** @return one aggregate per container name; never one value reused across containers. */
    Map<String, ContainerUsageAggregate> aggregates(UsageQuery query) throws UsageUnavailableException;
}

public record UsageQuery(ClusterTarget target, WorkloadRef ref, List<String> containerNames,
                         int percentile, int windowHours) {}

public record ContainerUsageAggregate(
        String containerName,
        PercentileSet cpuMillis,          // p50, p90, p95, p99, max — millicores
        PercentileSet memBytes,           // p50, p90, p95, p99, max — bytes
        double cpuThrottleRatio,
        int oomKillsInWindow,
        Instant windowStart, Instant windowEnd,
        double sampleCoverage,            // observed / expected samples for the window
        String sourceName) {}

public record PercentileSet(Map<Integer, Long> values, Long max) {
    /** @throws PercentileUnavailableException when this exact percentile was not supplied. */
    long at(int percentile);
}

public class UsageUnavailableException extends Exception {
    public enum Kind { NO_METRICS_SOURCE, INSUFFICIENT_HISTORY }
}
```

Contract requirements, written into the Javadoc and asserted by the stub's tests:

- **Pre-aggregated, not raw.** The provider returns percentiles over a window, computed by whoever
  owns the history — an agent-side rolling window (upstream VPA's decaying-histogram approach) or a
  PromQL `quantile_over_time`. The backend retains no history (§2.8: `agent_data_cache` is unique on
  `(cluster_config_id, data_type)`, each push overwrites), and building a store is roadmap C6
  (effort XL), so an interface shaped around raw series would be unimplementable.
- **`PercentileSet.at` throws** when the requested percentile is absent (D4). A provider that only
  computes p90 cannot silently answer a p95 policy.
- **Per container** (bug 8), keyed by container name, with sidecars distinct.
- **Namespace-scoped and owner-selected** — `UsageQuery` carries the namespace inside `WorkloadRef`
  plus `ownerUid`; implementations must resolve pods through owner references
  (Deployment → ReplicaSet → Pod), never by a `pod=~"name-.*"` regex. The Go rightsizer's query was
  `pod=~"overprovisioned-api-.*"` with the workload identified by Deployment name alone.
- **`sampleCoverage` and explicit window bounds are mandatory**, so `INSUFFICIENT_HISTORY` is computed
  rather than guessed. The harness ran with only 4h52m of history, which is exactly the case this
  field exists to surface.

`StubUsageHistoryProvider` (`sourceName() == "none"`) always throws `NO_METRICS_SOURCE`. It is the
bean wired this run, and it is what makes the read endpoint honest rather than fabricated.

### 5.4 Reference PromQL for the deferred metrics path

From `eks/test-clusters/harness/queries-controllers.promql`, recorded so the next run starts from
measured queries rather than inventing them. Per container, owner-scoped (the `pod=~` selector must be
replaced by an owner-derived pod list):

```promql
quantile_over_time(0.95, rate(container_cpu_usage_seconds_total{namespace="<ns>",pod=~"<owner-pods>",container="<c>"}[5m])[24h:]) * 1000
quantile_over_time(0.95, container_memory_working_set_bytes{namespace="<ns>",pod=~"<owner-pods>",container="<c>"}[24h:])
rate(container_cpu_cfs_throttled_periods_total{namespace="<ns>",container="<c>"}[5m])
  / rate(container_cpu_cfs_periods_total{namespace="<ns>",container="<c>"}[5m])
kube_pod_container_resource_requests{namespace="<ns>"}
kube_pod_container_resource_limits{namespace="<ns>",container="<c>"}
```

Two deliberate departures from the Go queries: **p95, not p80**, for CPU; and a **memory percentile,
not `max_over_time`** (roadmap C4: `max_over_time` is conservative and overshoots). The throttling
ratio and the requests/limits series are the signals the harness noted **no** XamOps controller
queries today, and they are what make `THROTTLED` and `REQUESTS_UNSET` computable. Reached through
the Kubernetes API service proxy, restricted by `resourceNames` to the Prometheus service (§6.5).
The agent-side alternative needs no Prometheus at all: it already collects per-pod, per-container
`PodMetrics` (`k8s_resources.go:384-397`) and would maintain the rolling window in memory.
**Nothing is implemented against either option now.**

---

## 6. The published API contract

This section is the specification for
`/Users/apple/Desktop/XamOps-New/.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md`,
written as plan item 1 and announced immediately, because the parallel UI workflow is blocked on it
(UI design §11.5, R5: when it lands, only `verticalRightsizerApi.ts`,
`verticalRightsizerAdapter.ts`, `types/verticalRightsizer.ts` and the demo fixtures change).

### 6.1 Endpoints

| # | Endpoint | This run |
|---|---|---|
| 1 | `GET /api/eks/automation/vertical/{cluster}?accountId=&region=` | **IMPLEMENTED** (read) |
| 2 | `PUT /api/eks/automation/vertical/{cluster}/workloads/{namespace}/{kind}/{name}/policy?accountId=&region=` | **CONTRACT ONLY** |
| 3 | `POST /api/eks/automation/vertical/{cluster}/workloads/{namespace}/{kind}/{name}/apply?accountId=&region=` | **CONTRACT ONLY** |
| 4 | `GET /api/eks/automation/vertical/{cluster}/actions?accountId=&region=` | **CONTRACT ONLY** |

Marking must be unmissable — a per-endpoint banner, not a footnote. `accountId` is the **12-digit AWS
account number**, never a primary key (§D5). Status codes: `200`; `400 K8S_INVALID_REQUEST` (bad
cluster name or `accountId` format) and `400 K8S_INVALID_POLICY` (with `field`) on writes;
`401 K8S_UNAUTHENTICATED`; `403` with a gate code; `404 K8S_CLUSTER_NOT_REGISTERED`;
`500 K8S_INTERNAL` with a fixed message. Refusal body:
`{ "code": "K8S_…", "message": "…", "field": "…"? }`.

### 6.2 Reason codes — shared VERBATIM with the UI

`READ_ONLY_CONNECTION`, `NO_USER_PERMISSION`, `CONTROLLER_NOT_READY`, `DEMO`,
`CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE`, `NO_METRICS_SOURCE`,
`INSUFFICIENT_HISTORY`, `GITOPS_MANAGED`.

Emitted **without** the `K8S_` prefix inside payload bodies (`gateReasons`, per-workload reasons) and
**with** it in a refusal's `code`; the UI accepts both (R3). The contract states that the engine
introduces **no new shared gate codes**: the agent-path state reuses the UI's existing
`AGENT_NOT_INSTALLED` and direct-path failures reuse the two cluster-access codes. It also records
the D8 handshake about `k8s-reason-codes.json`.

Engine-internal enums that are **not** gate codes and are documented as free-form strings the UI
renders as text: `SkipReason` (`PROTECTED_NAMESPACE`, `SINGLE_REPLICA`, `ROLLOUT_IN_PROGRESS`,
`LOCKED`, `HPA_VOLATILE`, `INSUFFICIENT_HISTORY`, `POLICY_OFF`), `Finding` (`REQUESTS_UNSET`, `IDLE`,
`THROTTLED`, `OOM_RISK`, `HPA_COEXISTENCE`, `NODE_CAPACITY_CAPPED`) and container `reasons`
(`HPA_CPU_MANAGED`, `HPA_MEMORY_MANAGED`, `FLOOR_CLAMPED`, `BELOW_FLOOR_NO_RAISE`, `LIMIT_RAISED`,
`DEMAND_CENSORED_BY_LIMIT`, `NODE_CAPACITY_CAPPED`, `QOS_PRESERVED_NO_CHANGE`, `COOLDOWN`,
`WITHIN_HYSTERESIS`, `REQUESTS_UNSET`). The UI already renders unknown enum values as
`{ kind: 'unknown', raw }`, so this list can grow safely.

### 6.3 Response shape of endpoint 1

Field names match UI design §11.5 exactly, with the additions the orchestrator requires:

```jsonc
{
  "engine": {
    "enabled": false,                      // xamops.automation.vertical.enabled
    "ready": false,                        // xamops.k8s.automation.readiness.vertical-rightsizing
    "clusterAccess": "NOT_CONFIGURED",     // CONFIGURED | NOT_CONFIGURED | UNREACHABLE
    "executionPath": "NONE",               // DIRECT | AGENT | NONE   (NEW)
    "executionPathDetail": {               //                          (NEW)
      "direct":  { "available": false, "reason": "CLUSTER_ACCESS_NOT_CONFIGURED" },
      "agent":   { "available": false, "reason": "AGENT_NOT_INSTALLED", "lastSeenAt": null }
    },
    "metricsSource": { "reachable": false, "name": null },
    "lastCycle": { "at": null, "evaluated": null, "error": null }
  },
  "gateReasons": ["CONTROLLER_NOT_READY", "NO_METRICS_SOURCE"],
  "workloads": []
}
```

`executionPath` and `executionPathDetail` exist so the UI can explain to a private-endpoint customer
**why direct mode is unavailable and what the agent path requires**, using only already-specified
codes: `CLUSTER_UNREACHABLE` for a private endpoint with no network path, `CLUSTER_ACCESS_NOT_CONFIGURED`
for a missing EKS access entry, and the UI's own `AGENT_NOT_INSTALLED` for the agent channel.
`executionPath` is `NONE` when neither is available. The contract states plainly that **the agent path
is the primary one and direct is an optional fast path**, and that an in-cluster observer is required
either way because the backend retains no usage history (§2.8).

Per workload, the shape is UI §11.5's `VerticalWorkload` verbatim: `ref{namespace,kind,name}`,
`policy{mode,headroomPct,percentile,window,applyMethod}`, `replicas`, `hpa{present,name}`,
`gitopsManaged`, `skipReason`, `findings[]`, `containers[{name,qosClass,current,recommended,reasons}]`,
`lastAction{at,dryRun,outcome}`; plus two engine additions the UI can ignore safely:
`provenance{percentile,windowHours,windowStart,windowEnd,sampleCoverage,usageSource,policySource}`
and `horizontal{state:"NOT_EVALUATED"}`. `Resources` is
`{cpuRequestM, memRequestMi, cpuLimitM, memLimitMi}` with `null` for unset — **never `0`**, and the
contract says so explicitly, citing the measured fabricated `1Mi`.

### 6.4 Write endpoints (contract only)

- **Endpoint 2** body: `{ mode, headroomPct, percentile, window, applyMethod }`. Ranges, documented
  as the validation the implementation will enforce: `mode ∈ {OFF,RECOMMEND,AUTO}`;
  `headroomPct ∈ [0,100]`; `percentile ∈ {50,90,95,99}`; `window ∈ {6h,12h,24h,48h,72h,168h}`;
  `applyMethod ∈ {IN_PLACE, IN_PLACE_AND_PERSIST}`. `IN_PLACE_AND_PERSIST` on a GitOps-managed
  workload is refused `403 GITOPS_MANAGED`.
- **Endpoint 3** body: `{ containers: [{name, cpuRequestM, memRequestMi, cpuLimitM, memLimitMi}], applyMethod, dryRun }`,
  response `{ correlationId, state, dryRun }` with the outcome arriving later in endpoint 4 — the
  asynchronous shape of §5.2, so the UI is built against it from day one rather than against a
  synchronous reply that will not exist. Refused `403 CONTROLLER_NOT_READY` while readiness is false.
- **Endpoint 4** entries: `{ at, correlationId, workloadRef, containerChanges[], method, dryRun,
  outcome, attempt, triggeredBy }`. **`dryRun` is always present**, per harness §5.

### 6.5 Minimal ClusterRole (documented, not applied)

```yaml
# Read — everything the engine needs to decide
- apiGroups: [""]            resources: [pods, namespaces]                 verbs: [get, list, watch]
- apiGroups: ["apps"]        resources: [deployments, replicasets]         verbs: [get, list, watch]
- apiGroups: ["autoscaling"] resources: [horizontalpodautoscalers]         verbs: [get, list, watch]
- apiGroups: ["policy"]      resources: [poddisruptionbudgets]             verbs: [get, list, watch]
# Act — zero-restart resize only
- apiGroups: [""]            resources: [pods/resize]                      verbs: [patch]
# Metrics — one named Service, nothing else
- apiGroups: [""]            resources: [services/proxy]                   verbs: [get]
                             resourceNames: [prometheus-server]
# Observability
- apiGroups: [""]            resources: [events]                           verbs: [create]
```

A **separate, opt-in** ClusterRole carries `apps/deployments: [patch]` and is required only for
`IN_PLACE_AND_PERSIST`. No `secrets`, in any role. Rationale to state in the contract: §7-B1 proved
the resize subresource is sufficient for zero-restart rightsizing, and §7-B3 showed that granting
`deployments: patch` by default is what let the Go rightsizer force a rolling replacement — so the
restarting capability is a distinct grant the customer opts into. `resourceNames` on
`services/proxy` prevents the engine's credential from proxying to arbitrary in-cluster services.

The contract also records that **the customer performs EKS access-entry setup themselves**: the
XamOps customer role lacks `eks:CreateAccessEntry`, and `cloudformation/` is out of scope for this
run. Steps, the ClusterRole above and private-endpoint guidance are what UI design R4's
`ClusterAccessGuide` renders.

---

## 7. Implementation plan

Ordered by dependency. Each item leaves the module compiling and the test suite green.

Verification commands throughout (absolute paths; run from the repo root):

```bash
MVN="mvn -o -f /Users/apple/Desktop/XamOps-New/.worktrees/java-vertical-rightsizer/xamops-service/pom.xml"
$MVN compile -q                                     # module compiles
$MVN test -Dtest=<Class> -DfailIfNoTests=false      # one class (~50 s)
$MVN test                                           # whole module (final gate)
```

- [ ] **1. Publish the API contract and notify immediately.**
      Write `/Users/apple/Desktop/XamOps-New/.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md`
      to the spec in §6: four endpoints with per-endpoint IMPLEMENTED / CONTRACT-ONLY banners, the
      nine shared reason codes verbatim, the §6.3 response shape including `executionPath` and
      `executionPathDetail`, the §6.4 write bodies with the asynchronous `correlationId` shape, the
      §6.5 minimal ClusterRole plus the separate opt-in patch role, the customer-performs-access-entry
      note, and the D8 reason-code-fixture handshake. **Then call `send_message` with severity `info`
      carrying the absolute path — before starting item 2, not at the end of the run.** This is the
      first item because it unblocks a parallel workflow and depends only on decisions already fixed
      in this document.
      Files: `.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md` (outside the
      worktree — never committed).
      Verify: the file exists and every path in it matches UI design §11.5 (`GET /api/eks/automation/vertical/{cluster}`,
      the `policy`, `apply` and `actions` sub-paths); the `info` notification was sent.

- [ ] **2. B7: trust the cluster's real CA in `K8sClientFactory`; remove trust-all.**
      In `createFromComponents`, drop `.withTrustCerts(true)`, require a non-blank `caCertData`,
      validate it parses to at least one X.509 certificate (base64-decode when it is base64, accept
      raw PEM otherwise, via `CertificateFactory.getInstance("X.509").generateCertificates`), and set
      it with `withCaCertData`. In `createFromKubeconfig`, drop `config.setTrustCerts(true)` and
      **fail closed** when the parsed `Config` has neither `getCaCertData()` nor `getCaCertFile()`:
      throw a new nested `K8sClientFactory.TlsTrustException` (an `IllegalStateException` subclass, so
      `GlobalExceptionHandler` keeps returning a 4xx/5xx rather than a stack trace) whose message
      names the cluster-config field to fix. **No trust-all fallback, no system property escape
      hatch.** Leave `EksService.java` untouched. Update the misleading comments.
      Files: `xamops-service/src/main/java/com/xammer/cloud/service/K8sClientFactory.java`;
      new `xamops-service/src/test/java/com/xammer/cloud/service/K8sClientFactoryTlsTest.java`.
      The test starts a `com.sun.net.httpserver.HttpsServer` on `127.0.0.1` with a keystore generated
      at test time by `keytool` (`-genkeypair -keyalg RSA -storetype PKCS12 -dname CN=localhost
      -ext SAN=dns:localhost,ip:127.0.0.1`), exports the server certificate as PEM, and generates a
      second unrelated keystore for the wrong-CA case. Three assertions: (a) the **correct** CA → a
      client call completes the TLS handshake; (b) a **wrong** CA → the call throws and the throwable
      chain contains a `javax.net.ssl.SSLException` or a message matching
      `(?i)certificat|handshake|PKIX`; (c) a kubeconfig with no CA and no `certificate-authority`
      → `TlsTrustException`. `Assumptions.assumeTrue` on `keytool` being present, mirroring
      `PgTestSupport`'s opt-in style. No network egress, no cluster.
      Verify: `$MVN test -Dtest=K8sClientFactoryTlsTest -DfailIfNoTests=false` passes, **and**
      `$MVN compile -q` succeeds — the shared call sites (`CloudK8sSecurityController` :66/:107/:151,
      `EksDashboardService` :823, `EksService` :309) must still compile unchanged. Record in the
      commit message that the kubeconfig path now fails closed for a kubeconfig with no CA, which is
      a behaviour change for any customer who uploaded one.

- [ ] **3. Units, engine value types and cadence (pure, no Spring).**
      Create `Quantities` + `QuantityParseException` (§4.2); the input records `WorkloadRef`,
      `ResourceEnvelope`, `ContainerSnapshot`, `WorkloadSnapshot`, `HpaView`, `NodeCapacity`,
      `WorkloadPolicy`; the output records `WorkloadDecision`, `VerticalDecision`,
      `ContainerDecision`, `HorizontalDecision`, `Provenance`; the enums `SkipReason`, `Finding`,
      `ContainerReason`, `ChangeKind`, `QosClass`, `WorkloadMode`, `ApplyMethod`,
      `ReconcileOutcome`; and `ReconcileCadence`. `ResourceEnvelope.recommended(...)` performs the
      §4.1 construction-time validation (positive request, request ≤ limit, QoS class unchanged).
      Files: `xamops-service/src/main/java/com/xammer/cloud/service/automation/workload/**` (new);
      new `.../workload/QuantitiesTest.java`, `.../workload/ResourceEnvelopeValidationTest.java`,
      `.../workload/ReconcileCadenceTest.java`.
      Tests: `250m → 250` and formats back to `"250m"`; `"1" → 1000`; `"0.25" → 250`;
      `"250Mi" → 262144000`; `"1Gi" → 1073741824`; `null`, `""`, `"abc"`, `"250x"` each throw
      `QuantityParseException`; a zero or negative recommended request is rejected; a request above
      its limit is rejected; a `Guaranteed`→`Burstable` pairing is rejected; cadence returns
      30m/2m/1m/1h.
      Verify: `$MVN test -Dtest='Quantities*,ResourceEnvelope*,ReconcileCadence*' -DfailIfNoTests=false` passes.

- [ ] **4. `WorkloadAutoscalingEngine` — vertical rules, the reconciliation step, and the fixture suite.**
      Implement §4.3–§4.9: gates, per-container targets, floors (decrease-only clamp, never raise to
      the floor), node-capacity cap, asymmetric relative-AND-absolute hysteresis, cooldown, QoS
      preservation with limit raising, findings, and `reconcile(...)` with the CPU/memory HPA freeze.
      `HorizontalDecision` is returned as `NOT_EVALUATED` for every input; the `CHANGE` factory stays
      unreferenced with a Javadoc pointing at §4.8's co-adjustment arithmetic as the extension point.
      Depends on item 3.
      Files: `.../workload/WorkloadAutoscalingEngine.java`, `.../workload/HysteresisBand.java`,
      `.../workload/QosRules.java`, `.../workload/WorkloadPolicyDefaults.java` (floors, bands,
      protected namespaces, volatility window — Java constants per D6);
      new `.../workload/WorkloadAutoscalingEngineTest.java` (table-driven, one case per §8 row) and
      `.../workload/ReferenceWorkloads.java` (the measured fixtures, each with a source citation in a
      comment).
      Verify: `$MVN test -Dtest='WorkloadAutoscalingEngineTest' -DfailIfNoTests=false` — every §8 case
      passes, including the convergence fixed-point case and `HorizontalDecision == NOT_EVALUATED`.

- [ ] **5. Usage contract: `UsageHistoryProvider`, `ContainerUsageAggregate`, `PercentileSet`, stub.**
      Create the §5.3 types and `StubUsageHistoryProvider` (`@Service`, `sourceName() == "none"`,
      always throws `NO_METRICS_SOURCE`). Javadoc states the per-container, namespace-scoped,
      owner-selected, pre-aggregated contract and why raw series are out (§2.8). Depends on item 3
      (the engine reads `ContainerUsageAggregate`), so in practice items 3-5 are written together and
      item 4's fixtures already use these types — keep them separate commits for reviewability.
      Files: `.../workload/usage/**` (new);
      new `.../workload/usage/PercentileSetTest.java`, `.../workload/usage/StubUsageHistoryProviderTest.java`.
      Tests: `at(95)` returns the supplied value; `at(99)` throws `PercentileUnavailableException`
      when only p50/p90/p95 were supplied; the stub throws `NO_METRICS_SOURCE` with that `Kind`.
      Verify: `$MVN test -Dtest='PercentileSetTest,StubUsageHistoryProviderTest' -DfailIfNoTests=false` passes.

- [ ] **6. Cluster access and the executor interface.**
      Create §5.1 (`ClusterAccessProvider`, `ClusterTarget`, `ClusterAccess`,
      `ClusterAccessException` with the two typed kinds, `EksClusterAccessProvider`,
      `KubeconfigClusterAccessProvider`) and §5.2 (`WorkloadActionExecutor`, `ActionCommand`,
      `ActionSubmission`, `ActionOutcome`, `ExecutionPath`, `SubmitState`, `Terminal`,
      `ContainerApplyStatus`) — **interface and records only, no executor implementation and no
      executor bean**. `EksClusterAccessProvider` injects `CloudAccountRepository`,
      `AwsClientProvider` and `EksTokenGenerator` and builds its client via the item-2
      `K8sClientFactory`; `EksService` is not edited. Depends on items 2 and 3.
      Files: `.../workload/access/**`, `.../workload/execute/**` (new);
      new `.../workload/access/ClusterAccessFailureMappingTest.java`,
      `.../workload/execute/ActionCommandContractTest.java`.
      Tests: a missing cluster config and a simulated `describeCluster` AccessDenied both map to
      `CLUSTER_ACCESS_NOT_CONFIGURED`; a simulated connect timeout maps to `CLUSTER_UNREACHABLE`;
      no raw `RuntimeException` escapes `open(...)` (assert via a stubbed collaborator — **no AWS
      SDK call is made**, the AWS clients are mocked); `ActionCommand` rejects a blank
      `correlationId` and a `notValidAfter` in the past; a Spring context test asserts **no**
      `WorkloadActionExecutor` bean exists.
      Verify: `$MVN test -Dtest='ClusterAccessFailureMappingTest,ActionCommandContractTest' -DfailIfNoTests=false` passes;
      `$MVN compile -q` succeeds.

- [ ] **7. Policy persistence: entity, repository, service, and the one Flyway migration.**
      New entity `WorkloadVerticalPolicy` → table `vertical_workload_policy` with the §D5 canonical
      ids and the §4.4 defaults as column defaults; `WorkloadVerticalPolicyRepository`
      (`findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName`,
      `findByClusterConfigId`); `WorkloadPolicyService.resolve(...)` returning the row's values or
      `WorkloadPolicyDefaults` with `policySource` set accordingly. Migration
      `V20261010_1__vertical_workload_policy.sql`, commented in the house style (purpose, the B4
      decision, guards, reversal), using `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`
      (§2.3). Columns: `id`, `client_id` → `client(id)` `ON DELETE CASCADE`, `cluster_config_id` →
      `kubernetes_cluster_config(id)` `ON DELETE CASCADE`, `namespace`, `workload_kind`,
      `workload_name`, `mode` (`'OFF'`), `headroom_pct` (25), `percentile` (95), `window_hours` (24),
      `cpu_floor_millicores` (25), `memory_floor_bytes` (262144000), `min_increase_pct` (15),
      `min_decrease_pct` (30), `min_increase_cpu_millicores` (25), `min_decrease_cpu_millicores`
      (100), `min_increase_memory_bytes` (33554432), `min_decrease_memory_bytes` (134217728),
      `apply_method` (`'IN_PLACE'`), `cooldown_minutes` (60), `max_limit_increase_factor` (4.00),
      `protect_single_replica` (TRUE), `node_capacity_fraction` (0.900), `last_applied_at`,
      `created_at`, `updated_at`; `UNIQUE (cluster_config_id, namespace, workload_kind,
      workload_name)`; index `(client_id, cluster_config_id)`. **No `cloud_account_id` column.**
      Files: `.../workload/policy/WorkloadVerticalPolicy.java`,
      `.../workload/policy/WorkloadVerticalPolicyRepository.java`,
      `.../workload/policy/WorkloadPolicyService.java`,
      `xamops-service/src/main/resources/db/migration/V20261010_1__vertical_workload_policy.sql`;
      new `xamops-service/src/test/java/com/xammer/cloud/repository/WorkloadVerticalPolicyRepositoryTest.java`
      (H2 `@DataJpaTest` + `JpaSliceTestConfig` + `spring.flyway.enabled=false`, per §2.2) and
      `.../workload/policy/WorkloadPolicyServiceTest.java`.
      Verify: `$MVN test -Dtest='WorkloadVerticalPolicyRepositoryTest,WorkloadPolicyServiceTest' -DfailIfNoTests=false`
      passes (round-trip + the unique constraint rejecting a duplicate + defaults applied when no row
      exists). **Migration verification, given that no repo test runs Flyway:** add
      `.../repository/VerticalPolicyMigrationPgTest.java` following `SchemaUpdateNoAlterPgTest` —
      `@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")`,
      `PgTestSupport.recreateDatabase("vertical_policy_mig")`, create the two referenced tables
      (`client`, `kubernetes_cluster_config`) minimally, run the migration file twice through plain
      JDBC, and assert the table, the unique index and every column default exist and that the second
      run is a no-op. It **skips** when the env var is unset, which is the repo's accepted pattern; to
      run it locally: `LOBTEXT_TEST_JDBC_URL=jdbc:postgresql://localhost:55432/postgres $MVN test
      -Dtest=VerticalPolicyMigrationPgTest`. Never point it at `13.127.62.153`.

- [ ] **8. The two safety flags, defaulting to off.**
      Add exactly two keys to each of `application.properties`, `application-local.properties`,
      `application-uat.properties`, `application-prod.properties`, `application-preprod.properties`,
      in one commented block at the end of each file:
      `xamops.automation.vertical.enabled=false` and
      `xamops.k8s.automation.readiness.vertical-rightsizing=false`. Bind them with
      `WorkloadAutoscalingProperties` (`@ConfigurationProperties`, primitive `false` defaults, so an
      absent property is "off" and "not ready"). Note in the block comment that the readiness key is
      also defined by the UI branch (D9) and that one copy should survive the merge.
      Files: the five `application*.properties`;
      `.../workload/WorkloadAutoscalingProperties.java`;
      new `.../workload/WorkloadAutoscalingPropertiesTest.java`.
      Tests: absent properties bind to `false`/`false`; a parity test reads all five
      `application*.properties` from disk and asserts both keys are present and literally `false` in
      each (so a future profile cannot silently default to on).
      Verify: `$MVN test -Dtest=WorkloadAutoscalingPropertiesTest -DfailIfNoTests=false` passes.

- [ ] **9. The read endpoint, and the full-module gate.**
      `VerticalAutomationController` (`@RestController`,
      `@RequestMapping("/api/eks/automation/vertical")`) with one handler:
      `GET /{cluster}?accountId=&region=`. It resolves the account with
      `CloudAccountAccessService.requireAwsAccess(...)`, finds the cluster config by
      `account.getId()` then by the entitled row's AWS account number for legacy rows (§D5), and
      delegates to `VerticalAutomationReadService`, which assembles the §6.3 payload: engine status
      from `WorkloadAutoscalingProperties`; `executionPath` / `executionPathDetail` from the cluster
      config (`isPrivate`, `isAgentActive()`, `lastAgentCheckin`) and the configured access providers
      — **without opening a client while the global flag is false**; `gateReasons`; and `workloads`
      from the engine, which is empty while the stub usage provider reports `NO_METRICS_SOURCE`.
      A controller-local `@ExceptionHandler` maps `ClusterAccessException` and
      `UsageUnavailableException` to the §6.1 refusal body. Depends on items 3-8.
      Files: `.../workload/api/VerticalAutomationController.java`,
      `.../workload/api/VerticalAutomationReadService.java`, `.../workload/api/dto/**`;
      new `.../workload/api/VerticalAutomationReadServiceTest.java` and
      `.../controller/VerticalAutomationControllerSecurityTest.java`.
      Tests: with the flag off, the payload has `enabled:false`, `ready:false`,
      `gateReasons` containing `CONTROLLER_NOT_READY`, `workloads: []`, and **no** provider is
      invoked (asserted on mocks); with a **fake** usage provider returning the §8 fixtures and a
      fake access provider, the DTO mapping produces §11.5-shaped workloads with `null` (never `0`)
      for unset resources, `provenance` populated and `horizontal.state == "NOT_EVALUATED"`; the
      security test is a `@WebMvcTest`-style check that an unauthenticated
      `GET /api/eks/automation/vertical/test-cluster?accountId=102227938259` returns **401** (proving
      §2.7's `anyRequest().authenticated()` coverage without editing `SecurityConfig`), and that a
      caller authenticated as another tenant gets **403**.
      Verify: `$MVN test -Dtest='VerticalAutomationReadServiceTest,VerticalAutomationControllerSecurityTest' -DfailIfNoTests=false`
      passes, then the final gate: **`$MVN test`** — the whole module, no new failures versus the
      baseline. Finally `git -C /Users/apple/Desktop/XamOps-New/.worktrees/java-vertical-rightsizer status`
      must show only intended files, and the branch stays **unmerged and unpushed**.

---

## 8. Test plan the implementation must satisfy

Table-driven, one case per rule, in `WorkloadAutoscalingEngineTest`, with fixtures built from the
**measured** reference workloads in `ReferenceWorkloads` (harness §1, §6, §7). Every fixture carries a
source citation in a comment, so a future reader can tell a measurement from an invention.

| # | Fixture (measured) | Input | Required outcome |
|---|---|---|---|
| 1 | `team-a/overprovisioned-api` | 1000m/1Gi req+limit (Guaranteed), p95 CPU ≈ 0.05m, p95 mem ≈ 0.4Mi, VPA target 25m/250Mi | Large decrease, **floored to 25m / 250Mi**; never `1Mi`, never `0m`; `FLOOR_CLAMPED`; requests **and** limits move together, QoS stays `Guaranteed` |
| 2 | `prod-sim/rightsized-worker` — **the control** | 80m/64Mi, p95 CPU ≈ 48m → target 60m, p95 mem ≈ 41Mi → target 51Mi; VPA 63m/250Mi | **No recommendation at all.** CPU: −25% but only −20m, below the 100m absolute band (`WITHIN_HYSTERESIS`). Memory: target below the 250Mi floor while current is also below it → `BELOW_FLOOR_NO_RAISE`. A second assertion repeats the case with the **measured** −32.8% CPU drift (target 54m, −26m) and still expects no recommendation — this is the regression test for the measured false positive |
| 3 | `prod-sim/throttled-encoder` | 10m req / 50m limit, throttle ratio **1.0**, usage pinned at the limit; VPA 63m/250Mi | Finding `THROTTLED`; request → **63m** (limit-derived, not usage-derived); limit → **200m** (ratio 5.0 capped at 4× the current limit); reasons `LIMIT_RAISED`, `DEMAND_CENSORED_BY_LIMIT`; request ≤ limit; QoS stays `Burstable`. Memory: current 32Mi below the floor → no memory change |
| 4a | `team-b/php-apache` | 200m/64Mi, **CPU HPA at 50%**; VPA 182m/250Mi | **No CPU-request change**, with reason `HPA_CPU_MANAGED` on every container and finding `HPA_COEXISTENCE` recorded |
| 4b | `php-apache`, raised-demand variant | same shape, p95 CPU raised so the CPU target would be ~400m — an increase far past the band | Still **no CPU-request change**, still `HPA_CPU_MANAGED`. This proves the HPA rule is load-bearing rather than hysteresis passing the test by accident |
| 5 | `dev-sim/no-requests-sidecar-app` | no requests, no limits, two containers | Finding `REQUESTS_UNSET`; `autoApplyEligible == false`; `current` reports **`null`** for every field, never `0m`/`1Mi`; the advisory recommendation is per container, and the sidecar's value differs from the main container's |
| 6 | `dev-sim/idle-batch-listener` | 100m/128Mi, 3 replicas, no traffic, p90 CPU 0 | Finding `IDLE`; **no request recommendation** (no near-zero request, no `0m`) |
| 7 | `team-b/load-generator` | 1 replica, 50m/32Mi | Excluded: `skipReason == SINGLE_REPLICA`, no container decisions |
| 8 | Guaranteed workload, genuine decrease | 2000m/2Gi req == limit, target 800m/1Gi | Requests **and** limits both move to the target; QoS still `Guaranteed`; a mutation test proves that moving only the request is rejected at construction |
| 9 | CPU value `250m` | any container at `250m` | Parses to `250`, formats back to `"250m"`, **never becomes 1 core** anywhere in the pipeline |
| 10 | Protected namespaces | one fixture per namespace in `kube-system`, `monitoring`, `amazon-cloudwatch`, `vpa`, `xamops-system` | `skipReason == PROTECTED_NAMESPACE`; a per-workload policy row cannot override it |
| 11 | Convergence / fixed point | feed case 1's own output back as `current` | **No recommendation** on the second pass — the structural answer to the measured 1Mi→4Mi thrash loop |
| 12 | Node-capacity cap | target 4000m against 1930m allocatable | Capped to `0.9 × 1930m` minus sibling requests, reason `NODE_CAPACITY_CAPPED` |
| 13 | Insufficient history | `sampleCoverage = 0.3` | `skipReason == INSUFFICIENT_HISTORY`, no decisions |
| 14 | Horizontal half | every fixture above | `horizontal.state == "NOT_EVALUATED"`, always |

Plus, outside the engine suite:

- **B7 TLS** (item 2): wrong CA fails, correct CA succeeds, no-CA kubeconfig fails closed.
- **Parse failures** (item 3): `null`, `""`, `"abc"`, `"250x"` all throw; no path returns a default.
- **Percentile honesty** (item 5): `at(99)` on a set without p99 throws.
- **Access failure mapping** (item 6): both typed kinds, no raw `RuntimeException` escaping.
- **Migration** (item 7): applies cleanly, twice, against the repo's opt-in local PostgreSQL;
  H2 slice test for the entity round-trip and the unique constraint.
- **Flag parity** (item 8): both keys present and `false` in all five `application*.properties`.
- **Security coverage** (item 9): unauthenticated → 401; another tenant → 403.

---

## 9. Deferred — next-run scope

Interfaces exist; nothing is implemented. Listed explicitly so the next run has a starting line.

1. **Usage-aggregate providers.** The agent-side rolling window (primary: the agent already collects
   per-container `PodMetrics`, and the backend retains no history) and a PromQL provider over §5.4's
   queries through the API service proxy. Both implement `UsageHistoryProvider`.
2. **Executors.** The **agent command queue** (primary): a cluster-scoped command table, a new
   command type served through `/api/v1/agent/commands`, an outcome-report endpoint, at-least-once
   delivery with the `correlationId` idempotency key, and the `notValidAfter` expiry sweep. The
   existing `/commands` handler is the DB agent's `ExplainRequest` queue keyed by `agentName` with a
   global API key — cluster scoping is new work and its own security review (§2.8). Then the
   **direct** executor: in-place resize via the `pods/resize` subresource, canary, progressive
   rollout and status checking.
3. **Horizontal decision rules** inside `reconcile` — load-aware `minReplicas`/`maxReplicas`/target
   utilisation from the percentile sets, the PDB `minAvailable` floor, skipping unschedulable
   workloads, and the §4.8 co-adjustment alternative to freezing.
4. **Scheduled reconciler + distributed lock**, consuming `ReconcileCadence`.
5. **Audit tables** and the actions feed behind endpoint 4, every row carrying `dryRun`.
6. **Server-side safety gates** on the write endpoints — the UI design's `writeDecision` order
   (`DEMO`, `NO_USER_PERMISSION`, `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED`), reusing
   `K8sAutomationAccessService` when the UI branch lands (R2).
7. **Template-persist mode** (`IN_PLACE_AND_PERSIST`) and its separate opt-in ClusterRole.
8. **GitOps detection** (Argo CD / Flux ownership) feeding `GITOPS_MANAGED`.
9. **All live-cluster tests**, including the live validation that flips
   `xamops.k8s.automation.readiness.vertical-rightsizing` to true.

---

## 10. Risks, assumptions and known gaps

- **The kubeconfig path now fails closed.** Item 2 makes a kubeconfig with no CA (or
  `insecure-skip-tls-verify`) an error instead of a silent trust-all. If a customer uploaded such a
  kubeconfig, their Kubescape/Trivy/Karpenter/OpenCost direct path stops working with a clear message
  instead of working insecurely. That is the intended trade; it must be in the commit message and the
  release note. The alternative — a trust-all fallback — would make the fix decorative.
- **Reference fixtures are reconstructed, not replayed.** The harness recorded decisions and VPA
  targets, not every raw percentile. Where a p95 is needed and only a p80 or a `max` was captured,
  the fixture states the derivation in a comment. Case 2 deliberately asserts the outcome at **both**
  the reconstructed target and the measured −32.8% drift, so the suppression does not depend on the
  reconstruction being exact.
- **Hysteresis thresholds are a judgement calibrated on one cluster shape** (t3.medium, 1930m /
  3294Mi allocatable). They are policy columns with SQL defaults precisely so they can be tuned per
  tenant without a code change. The asymmetry and the AND are the structural claims; the specific
  numbers are tunable.
- **`application*.properties` will conflict with the UI branch** on
  `xamops.k8s.automation.readiness.vertical-rightsizing`. Both branches must define it (R1); on merge,
  keep one copy. Flagged in the block comment the coder adds.
- **The shared reason-code fixture is not created here** (D8) to avoid a merge conflict on a file the
  UI branch owns. The engine's enum is asserted against the nine literal strings instead; folding it
  into `k8s-reason-codes.json` is a follow-up once both branches land.
- **No endpoint-4 data exists this run**, so the UI's Activity feed for workload autoscaling will be
  empty. The contract marks the endpoint CONTRACT ONLY; the UI's documented degradation path covers
  an absent feed.
- **`accountId` is the AWS account number, and legacy `cloud_account_id` rows still exist.** The
  read endpoint's two-step lookup (§D5) handles them, but a cluster config whose
  `cloud_account_id` holds an AWS number belonging to a *different* tenant's row would be
  unreachable rather than mis-served — fail-closed, and the platform-wide fix stays F6.
- **Assumption:** `ApplyMethod.IN_PLACE` is the default and needs no opt-in, matching UI design §6.3.
- **Assumption:** EKS ≥ 1.33 for the resize subresource. The harness measured 1.36.4. The deferred
  executor must gate on the cluster's minor version (roadmap C4) — the decision layer does not, since
  a recommendation is valid regardless of how it is applied.

---

## 11. Headline decisions, restated

1. **Hysteresis.** A change is recommended only when it clears **both** a relative **and** an
   absolute threshold, asymmetric by direction: **CPU increase ≥ 15% and ≥ 25m; CPU decrease ≥ 30%
   and ≥ 100m; memory increase ≥ 15% and ≥ 32Mi; memory decrease ≥ 30% and ≥ 128Mi.** The absolute
   half is what suppresses the measured −32.8% / −26m false positive on `rightsized-worker`; the
   asymmetry is what still lets `throttled-encoder`'s +53m increase through. Floors are **25m /
   250Mi** (upstream VPA's defaults, and the values VPA was measured stopping at), clamping decreases
   only and never raising a workload that already sits below them.
2. **Canonical identifier.** New tables carry **`cluster_config_id` → `kubernetes_cluster_config(id)`
   and `client_id` → `client(id)`, and never a `cloud_account_id` column**, because that column holds
   a DB primary key on modern rows and an AWS account number on legacy ones (audit B4). The caller's
   `accountId` is only ever matched against `CloudAccount.awsAccountId`.
3. **One engine, agent-first execution.** The decision layer is a single **workload autoscaling**
   engine — vertical rules implemented, horizontal modelled, coexistence reconciled in one function —
   because the CPU request is the HPA's denominator and two engines cannot agree on it. Execution is
   designed agent-mediated-first (outbound-only, asynchronous, at-least-once, idempotent, expiring),
   with direct backend-to-API as an optional fast path, because the agent channel already reaches
   private-endpoint clusters and because usage history has to be computed inside the cluster either
   way.
