# CloudK8s UI revamp: observability and automation console

| | |
|---|---|
| Status | Design, iteration 3. Revised after design review 2 (CHANGES_REQUESTED: 0 HIGH, 4 MEDIUM, 14 NIT); the response to every finding is in §23 (review 1's are summarised in §22). Gated by design review before any code is written. |
| Branch / worktree | `feat/cloudk8s-ui-revamp` at `/Users/apple/Desktop/XamOps-New/.worktrees/cloudk8s-ui-revamp` (base `9c4ace7b3`). `main` has since gained `b4a9fe701`, which adds automation-harness fixtures under `eks/test-clusters/harness/` only; no file cited here changed. Not merged to `main`; left for owner review. |
| Scope | EKS first-class. GKE and AKS keep their current pages; a later optional slice adopts the shared components. |
| Inputs read | `CLAUDE.md`, `DESIGN.md`, impeccable + redesign skills, new-page checklist, mockup skill, WAR critique, feature audit (§2/§4/§5), roadmap (§2/§5.2), automation harness report (§1/§2/§5/§7/§10) and its captures, the orchestrator's two design updates (controller readiness; the Java vertical rightsizer), design reviews 1 and 2 (`.agents/tasks/cloudk8s-ui-revamp-2026-10-09/design-review.md`, `design-review.json`), the vertical engine's published contract (`.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md`), its plan (`.worktrees/java-vertical-rightsizer/docs/cloudk8s/java-vertical-rightsizer-plan.md` §2.8, §4.5, §5.2, §6) and run report, the autoscaler-safety plan (`.worktrees/autoscaler-safety/.agents/tasks/plan.md`), plus the source files cited inline. |
| External dependency | Workload autoscaling consumes the **Java vertical rightsizer** (`feat/java-vertical-rightsizer`). Its API contract is **published and adopted** here (§11.5). In it only endpoint 1 (status and recommendations) is implemented; the policy, apply and actions endpoints are contract-only, with no handler mapped. The engine is switched off in every profile (`xamops.automation.vertical.enabled=false`). At iteration 3 the branch has four commits beyond its plan (`22bcec42d` cluster CA verification, `082ae85ab` cluster access and the executor contract, `a4ded71a7` the decision engine, `ad5581ecd` endpoint 1 and its two flags), plus uncommitted work in its worktree. Where its committed code differs from the contract, this design follows the code and raises the difference (R12, R14). Every write to the engine stays disabled until R1 and R2 are confirmed in its code (§6.8, D16). |
| Stack (locked) | Frontend: React 19, TypeScript 5.9, Vite 5, Tailwind 3, react-router 7, `react-aria-components` 1.16 (Tabs/TabPanel, Tooltip, Dialog), `@iconify/react` with the generated offline bundle (`src/icons/bundle.ts`), vitest + Testing Library. Backend: Spring Boot 4.0.8, Java 25, Spring Data JPA, plain `mvn`. **No new npm or Maven dependencies.** |

---

## 0. CloudK8s v3 — neutral design system (source of truth)

This section is the authoritative visual contract for every CloudK8s surface — the fleet page, the cluster page and its Automation and Cost sections — as shipped on the `feat/cloudk8s-debox` relayout. It is grounded in `DESIGN.md` §9 and answers critique P2 (the console still read as boxed and old-fashioned even after the charts landed). Where this section and any earlier prose in this document disagree about chrome, colour, iconography, surfaces or type, this section wins for CloudK8s surfaces.

**The parchment system is superseded here.** The Anthropic-Serif / parchment / terracotta identity described by the root `DESIGN.md` does not apply to CloudK8s surfaces and must not be reintroduced to them. This is an explicit supersession, not a repaint: the CloudK8s UI is **not** restyled toward parchment, serif display faces or warm terracotta accents. CloudK8s stays on the neutral system below. (Action B — adding a "SUPERSEDED for CloudK8s surfaces" banner to the root `DESIGN.md` itself — is a deferred follow-up tracked in this feature's findings; the root doc lives outside this worktree and is not edited here.)

### 0.1 Neutral chrome (no hues in chrome)

All structural chrome — surfaces, borders, dividers, and text — is black, white and grey only, expressed as `rgba(0,0,0,x)` and `rgba(255,255,255,x)` (and the equal-channel solids `bg-white` / `#141414`). No hue ever enters chrome: no coloured card backgrounds, no coloured borders, no coloured body text, no tinted section headers. A surface or a line is never used to signal state. This keeps the console calm and lets the rare moment of colour actually mean something.

### 0.2 Colour only for meaning, only through the tokens and the status/chart components

Colour appears for meaning — green = savings/good, amber = attention, red = failure, blue = in-progress/info — and only ever through the semantic channel: the `--k8s-*` tokens and the status/chart components. Chrome stays grey. There are no decorative hues, no gradients and no tinted tile backgrounds.

- The only source of hue is the `--k8s-*` triples defined in `src/styles/k8sSemanticTokens.css`. No component declares its own hex or named colour for status or series.
- Those tokens reach the screen only through the dedicated components: `StatusMark`, `StatusChip`, `KpiCard`, `ConfidenceChip`, and the chart components (`ChartCard`, `Bars`, `LanesTimeline`, `StackedArea`, `Sparkline`, `TimeSeriesChart`, `RangeBar`, the donut series, etc.). Chrome primitives such as `Surface` and the `typeScale` tokens carry **no** `text-status-*`, `text-series-*`, `text-feature-*` or `var(--k8s-*)` class; those are token-scope-restricted. Status/series colour enters a `Surface` only through the children a caller passes in.
- Enforcement is two guards that must stay green: `src/test/designRules.test.ts` (scans the guarded CloudK8s tree for hue / gradient / emoji / Font Awesome / token-scope-escape violations) and `src/components/CloudK8s/shared/charts/__tests__/colourAudit.test.tsx` (audits the chart colour usage against the token set). A new hue that is not a token, or a token used outside the allowed components, fails the build.

### 0.3 No gradient, no emoji, bundled `mdi:*` icons only

No gradients anywhere in the CloudK8s surfaces. No emoji in UI copy or labels. Icons come exclusively from the bundled Material Design Icon set via `@iconify/react` (`mdi:*`), produced by `scripts/generate-icon-bundle.mjs` as the first step of the build; no Font Awesome, no CDN icon fonts, no inline decorative glyphs. The same two guards above flag emoji and Font Awesome usage.

### 0.4 Surfaces, the single radius / border / shadow / padding, and the type scale

One primitive owns all card chrome: `Surface` (`src/components/CloudK8s/shared/Surface.tsx`). It collapses every panel into exactly three tiers, and `tier` is the only knob that changes weight:

- **hero** — no card; sits directly on the page background.
- **grouped** — one container (radius + border + background), **no** shadow.
- **table** — one container plus the single allowed elevation step.

The tokens `Surface` holds internally, never re-declared per page:

- **One radius:** `rounded-[10px]`.
- **One border:** `rgba(0,0,0,0.07)` in light, `rgba(255,255,255,0.09)` in dark (the 0.07–0.09 hairline).
- **Shadow on tier-3 (table) only:** `shadow-[0_1px_2px_rgba(0,0,0,0.04)]`; hero and grouped carry none.
- **One padding step:** `p-5` on grouped (tier-3 headers get their own `px-5 py-3.5`).
- **One divider level:** a single hairline (`rgba(0,0,0,0.06)` / `rgba(255,255,255,0.08)`), applied at most once per surface.

The type scale lives in `src/components/CloudK8s/shared/typeScale.ts` — one big-number role with exactly two sizes, plus three ranked supporting levels:

- **`HERO_NUMBER`** `text-[52px]` — the tier-1 hero number, **one per panel**.
- **`KPI_NUMBER`** `text-[22px]` — demoted strip / `KpiCard` / counts.
- Supporting: `EYEBROW` (`text-[11px]` uppercase), `TITLE` (`text-[14px]`), `BODY` (`text-[13px]`), `SECONDARY` (`text-[12px]`).

No two peers compete at the same size for the same meaning, and no panel shows more than one `HERO_NUMBER`.

### 0.5 StatusMark discipline — never colour alone (WCAG 1.4.1)

Status is always carried by **icon + label + weight together**, never by colour alone. `StatusMark` pairs an `mdi:*` glyph with a text label and the appropriate weight; the semantic token only tints that combination. This satisfies WCAG 1.4.1 (Use of Colour): a reader who cannot distinguish the hue still gets the state from the icon and the words. A coloured dot, a coloured cell, or a colour-only chip with no icon and no label is a violation and is not used on any CloudK8s surface.

---

## 1. Overview

The CloudK8s console becomes two surfaces. The fleet page (`/cloudk8s`) answers "which clusters need me, what do they cost, and how far is automation rolled out". The cluster page (`/eks-details`) keeps the URL but replaces ten flat tabs with seven primary sections, each with subtabs, and makes Automation a first-class section. Every number on screen comes from a named source with a freshness label. Anything the backend does not report renders as "not reported", never as zero.

Automation is the part that needs the most care. The dormant 93 KB autoscaling tab is not switched back on. It is decomposed into Automation subtabs that read from a fixed hook (no fake success, no fake rebalance completion), and every control is wrapped in one capability model. A control is either usable, or visibly disabled with a specific reason and a path to enable it. The server enforces the same rules on `/api/eks/autoscaling/**`: it refuses mutations from read-only connections, from users without permission, from impersonated sessions and from demo identities, and it refuses (and never serves) a policy that turns on a controller that failed safety validation. The measured harness verdicts mean that, today, every live or apply control except spot interruption handling is held by the server, so the honest state of the product is "recommendations and analysis, no automated changes yet". The design makes that state look deliberate: a readiness checklist names each gate, its reason and its remedy.

Automation has two engines, and the UI says which one is behind each control. **Workload autoscaling** (vertical pod rightsizing) is the new Java engine in `xamops-service`. It computes per-container recommendations from usage percentiles with headroom, floors and hysteresis, and will resize pods in place through the Kubernetes `resize` subresource. Its published contract makes the **XamOps agent's outbound command channel the primary execution path** and direct backend-to-API access an optional fast path, and it needs an in-cluster observer for usage history either way (contract §2.2). An apply is a submission whose outcome arrives later in the engine's actions feed (contract §3.2). The engine is switched off in every environment today, so for customers the honest state of Workload autoscaling is "not turned on in this environment yet" (§8.1 V7). **Node automation** (consolidation, node optimizer, rebalancing, HPA manager) remains the Go automation agent, gated by `AGENT_NOT_INSTALLED`. The Go rightsizer is retired as a product engine: the UI no longer configures it and the server serves it disabled. Only the engine's read endpoint exists, so every Workload autoscaling change stays disabled with a stated reason until the engine's own write endpoints are confirmed to enforce the same write gates and readiness flag (R1, R2). Recommendations stay visible wherever the engine reports them.

Small, justified backend changes ship with the UI: a guard interceptor and access service for `/api/eks/autoscaling/**` (tenant scoping plus both read-only gates), a policy normalizer with per-controller readiness configuration, a capabilities endpoint (which also reports whether the vertical engine is switched on, by reading the engine's own property, R9), an exact-match Container Insights check on the AWS call XamOps already makes (no new AWS call; freshness is follow-up F19), an ingest guard that stops agents overwriting the server-owned policy row, and a FAQ/permission-catalog update. Nothing under `cloudformation/`, `xamops-agent/`, `xamops-autoscaler/`, AutoSpotting, Login or the vertical engine's packages changes; the engine is consumed through its API only, and the shared pieces are listed as reconciliation points (§19).

---

## 2. What "read-only" means here (decision 2, resolved)

"Read-only role" covers two independent gates. **Either one disables every automation action.** Neither hides anything observational: recommendations, consolidation analysis, projected savings and policy values stay visible, because that visible value is the upgrade path.

| Gate | Meaning | Source of truth | Reason code |
|---|---|---|---|
| (a) Connection access level | The AWS account was connected with the read-only CloudFormation role (`xamops-readonly-role.yml`) rather than the read-write one. | `CloudAccount.accessType` (`"read-only"` / `"read-write"`), served by capabilities as `connection.accessLevel`. The gates and the access chip read only that value, so they can't disagree; `Account.access` carries the same field for other pages. See D1. | `READ_ONLY_CONNECTION` |
| (b) User permission | The signed-in XamOps user is not allowed to change CloudK8s automation. | New permission key `cloudops-cloudk8s-automation`, stored in the existing `user_tab_access` model, plus role, impersonation and demo checks (demo is decided by the user row's email), evaluated server-side and served by capabilities. See D2. | `NO_USER_PERMISSION` (and `DEMO` for the shared demo identities) |

Gate (a) is a consent signal, not a technical dependency. The Go automation agent acts through its own in-cluster ClusterRole (`xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml:41-140`); the vertical engine will act through the XamOps agent's command channel, or directly through an EKS access entry mapped to a minimal ClusterRole (contract §2.2, §6). In every case Kubernetes RBAC, not the AWS role's IAM policy, decides what can change in the cluster. A customer who chose read-only access has told us "observe, don't act", and cast.ai's read-only mode means the same, so we honour it for Kubernetes too. The copy says so plainly ("Read-only connection. Automation changes need read-write access.") and does not claim AWS permissions are missing.

---

## 3. Evidence that shapes the design

Every row was verified by reading the file; line numbers are from the worktree at `9c4ace7b3`.

| # | Finding | Evidence | Design consequence |
|---|---|---|---|
| E1 | Tenant isolation on `/api/eks/autoscaling/**` does not exist. The controller comment claims TenantFilter scopes data, but TenantFilter hardcodes one tenant. Any signed-in user can read or change any customer's cluster policy by naming an account and cluster. | `EksAutoscalingController.java:31` (claim), `TenantFilter.java:45-46` (`setCurrentTenant("system")`), raw `Long.parseLong(accountId)` at `:80,112,131,150…`, cross-account fallback `EksAutoscalingService.findConfig:306-328` | Guard interceptor resolves account + config through `CloudAccountAccessService.canAccess` before any handler runs (§6.6, D6). |
| E2 | Mutations fake success. Every save's `catch` applies the change locally and shows "saved (local)"; delete and mode change always "succeed"; `triggerRebalance` fabricates "Rebalance completed" after 5 s. | `useEksAutoscaling.ts:338,356,374,392,436,459,477,491-513,525` | New `useEksAutomation` hook: server state only, no optimistic writes, errors shown at the control, rebalance status only from polling (§11.2). |
| E3 | The rebalancer drains whenever the server says `status: "running"`, even with `ENABLE_REBALANCER=false`, and re-drains the same nodes every cycle. Nothing on the server ever resets the status. | Harness §2 "Rebalancer"; `main.go:163`; `EksAutoscalingService.triggerRebalance:249-258` sets `running`; no code resets it | Rebalance trigger and schedule blocked in UI and server; server forces `status: "idle"` on every save and every serve (§6.7). |
| E4 | Measured controller verdicts: rightsizer BROKEN (1Mi/0m recommendations, restart loop, QoS downgrade), HPA manager BROKEN (fixed formula), evictor BROKEN (any DaemonSet pod blocks the node, no fit simulation), node optimizer BROKEN (`savingsPct: 50`, `interruptible: true` hardcoded), VPA manager WORKS (9/9). | Harness §1, §2, §7; `node_optimizer.go:172-179` | Per-controller readiness gate `CONTROLLER_NOT_READY`, server-configured, default not ready (§6.7). |
| E5 | The agent-facing policy endpoint serves a partial bundle (`{"autoscalerMode":"full"}`) when none is saved. The tested agent image zeroes every safety guard on a partial bundle. | `AgentController.java:799`; harness §2 "Partial-bundle"; fix in Go client on `main` (`9c4ace7b3`) but not in deployed images | Normalizer serves a complete, readiness-enforced bundle on every path; every section save persists a complete bundle (§6.6). |
| E6 | Automation agent health is fake: components are marked healthy whenever the observer agent checked in. The default bundle claims mode `full` before anything is configured. | `EksAutoscalingService.buildAgentStatus:341-375` (`componentsHealthy = agentActive` at `:363`), `buildDefaultBundle:423-425` | Automation agent status comes from its own heartbeats (`agentVersion` ends with `-autoscaler`, `pusher.go:31`), computed by the capabilities endpoint (§11.4). The old component-health grid is dropped. |
| E7 | The snapshot rightsizing engine is a different engine from the measured Go rightsizer, was never scored, and returns nothing for agent clusters: it reads Prometheus-only `podDetails` and looks the cache up with region `"auto"`. | `EksAutoscalingService.java:637-760` (`:644` region, `:653` podDetails); `EksDashboardService.java:143` (podDetails only from Prometheus) | The UI stops reading `GET /{c}/rightsizing`; Workload autoscaling reads the vertical engine (E16). The endpoint stays, tenant-scoped, for API compatibility. |
| E8 | An unmonitored cluster shows a perfect health score. The backend shell hardcodes 100; the client composite score treats idle as unhealthy and utilisation as "cost score". | `EksDashboardService.java:1692`; `EksWorkloadsTab.tsx:111`; `useEksDashboard.ts:405-454` | Health is rule-based signals from real data; "Not monitored" state replaces zeros (D10). |
| E9 | Savings are flat multipliers; workload costs come from two hardcoded rates applied to usage the agent does not report (so they are $0 on agent clusters). | `EksCostTab.tsx:68-69,102,114,126` (rates), `:150` (`×0.3×30`), `:158` (`×0.15`); `useEksDashboard.ts:504` (`×0.6`) | Multipliers deleted; rates move to one module and are labelled "Estimated"; allocation uses requests (D9). |
| E10 | The "Connected" badge is true whenever `logs:DescribeLogGroups` succeeds: the response is discarded, so it does not even check the group exists. OTEL-based Container Insights writes nowhere XamOps looks. | `EksService.java:200-212` (call at `:205`, response unused); harness §8 (8 vs 0 datapoints, 7.5 vs 20.2 min) | Exact-name match on the same call, labelled "Configured · data freshness not checked". The freshness check is follow-up F19 (D3). |
| E11 | No self-service path upgrades read-only to read-write while keeping the connection. The change-set path picks the template matching the current access type and refuses to replace a connection role. A second, read-write connection for the same AWS account is impossible because the AWS account id is unique. Removing the connection first deletes its data in XamOps. | `StackUpdateService.java:345-363` ("never a read-write template on a read-only account"), `:93` (`CONNECTION_ROLES`); `CloudAccount.java:24-25` (`@Column(unique = true)` on `awsAccountId`); `AwsAccountService.deleteAccountById:511-548` (cluster configs, agent data, team access grants, financial records) | The read-only remedy is "Get read-write access": XamOps support first, the reconnect path with its cost stated, never a bare link to Add Account. The stack update is the remedy for missing AWS permissions (D1). |
| E12 | Demo routing is method-blind in `apiFetch` and does not route `/api/eks/autoscaling/**`, so demo sessions reach the real backend. Both demo identities share the password `password` and skip OTP; the BillOps one is a platform admin. Their usernames are "Xammer Admin" and "Xammer BillOps", not their emails, and the principal carries the username. | `apiClient.ts:149` (first pass ignores method), `main.tsx:29` (method-aware second pass); `DataInitializer.java:51-52`, `:78-93` (username reset on every start); `CustomUserDetailsService.java:56-62`; `LoginOtpController.java:70-73`, `:113` (OTP skipped by email) | Demo fixtures for every GET; every mutation shape returns `403 K8S_DEMO` regardless of method; the server refuses demo identities by the user row's **email**, never by username (D2, §16). |
| E13 | The setup-guide CI job fails any `docker.io/xamops/...` image a guide references that is not on Docker Hub. The automation image is not CI-built. | `.github/workflows/setup-guide-validation.yml` ("Verify every docker.io/xamops image"); audit §2.5 | Automation agent guidance lives in a new component outside the validated files and gives no runnable install command (D13). |
| E14 | Kubescape, OpenCost and Karpenter data pushed by the agent is silently hidden. The Kubescape and Karpenter display flags are set only by the dead kubeconfig endpoint; nothing sets `opencostEnabled` at all. | `CloudK8sSecurityController.java:148-149` is the only writer of two flags; no writer of `opencostEnabled` exists; `EksDashboardService.java` gates on all three | Data sources shows "Received, not displayed" instead of "not installed" (§10.7); the flag fix is F9. |
| E15 | Agent payload quirks: `namespaces[].deploymentCount` is always 0; `pendingPodsResolved` counts currently pending pods; deployments carry no requests; per-pod usage (`podMetrics`) is collected but never reaches the dashboard. | Harness §6; `AgentDataTransformerService.java:1062` parses `podMetrics`, `EksDashboardService` never reads it | Derive counts from `deployments[]`, never label "resolved", aggregate requests from pod containers, show usage only where reported (§10). |
| E16 | Owner decision: vertical rightsizing moves to a new Java engine (p95 from Prometheus, headroom, floors, hysteresis, in-place resize with zero restarts, skips mid-rollout, HPA-volatile and `xamops.io/lock: active` workloads). The Go rightsizer is no longer the product's vertical engine. | Orchestrator notification (2026-10-10); the engine's published contract (§11.5, E22) | Workload autoscaling is built on the engine's API through one adapter; Go rightsizer controls are removed and the server serves `workloadRightsizerPolicy.enabled: false` so two vertical engines never act on one workload (§6.6). |
| E17 | Both impersonation paths replace the principal with the target user, so a role or tab check sees the customer, not the admin. Super admins and BillOps admins can impersonate. | `ImpersonationFilter.java:47-73` (header path; allowed for `ROLE_SUPER_ADMIN` and `ROLE_BILLOPS_ADMIN`, `:95-132`); `ImpersonationJwtFilter.java:146-187` (BillOps "Login As"); both set `ImpersonationContext` before the handler runs (`:50`, `:187`) | Automation writes are refused while `ImpersonationContext.getImpersonatedUserId() != null` (D2). |
| E18 | The panels re-homed from today's tabs break the house rules: emoji, hundreds of hue classes and hex colours, status carried by colour alone, unknown values shown as 0, a global indigo stylesheet, and Font Awesome classes from a CDN instead of the bundled SVG icon set. | `EksKarpenterTab.tsx:324`, `EksNodesTab.tsx:257`, `:409` (emoji); `EksPerformanceTab.tsx:86`, `:89` (unknown as 0), `:279-284` and `KubescapeComplianceView.tsx:176-179` (colour-only status); `styles/eks-details.css` (669 lines, imported at `App.tsx:213`, scoped by `EksDetailsPage.tsx:149`); `index.html:18` (Font Awesome CDN); per-file counts in §14.1 | Every re-homed file goes through a conformance pass, and a vitest guard keeps the CloudK8s tree compliant (§14.1). |
| E19 | While node automation is off, `GET /{c}` returns no policy sections, and before the first enable no bundle is stored at all. | `EksAutoscalingService.getDashboard:105-125` | `GET /{c}` always returns the effective sections, marked stored or default (§6.6, §11.4). |
| E20 | XamOps configuration changes never reach the activity feed. `readEvents` returns the agent-pushed `autoscaling-events` row whenever it exists; its fallback reads the 20 newest `agent_events` of any type, which heartbeats fill. | `EksAutoscalingService.readEvents:504-524`, `buildEventsFromAgentLog:526-550` (page at `:528-531`); heartbeat rows `AgentController.java:376-377` | Changes are served separately as `recentChanges` from a type-filtered query (§9.6, §11.3). |
| E21 | Two integrity gaps around the policy row. Agent ingest stores any collector name, including `autoscaling-policy`, which no agent pushes. And the service parses agent payloads with a default, strict `ObjectMapper`, so one added key blanks the feed or the KPI tiles. | `AgentIngestService.processBatch:424-469` (upsert `:447-464`); `autoscaling-policy` is only read, by the automation agent (`xamops-autoscaler/internal/policy/client.go:37`); `EksAutoscalingService.java:93`, no `@JsonIgnoreProperties` under `dto/autoscaling/`; autoscaler-safety plan D2 | Ingest refuses the server-owned type (§6.6); the service's mapper ignores unknown properties (§11.3). |
| E22 | The vertical engine's published contract keeps every provisional path and field, and adds behaviour the UI has to follow: a global switch that is off in every profile (endpoint 1 then returns `200` with `engine.enabled: false`, a placeholder `clusterAccess: "NOT_CONFIGURED"` and `gateReasons: [CONTROLLER_NOT_READY, NO_METRICS_SOURCE]`); `executionPath` (`DIRECT` / `AGENT` / `NONE`), with the XamOps agent's channel as the primary path and `AGENT_NOT_INSTALLED` reused for it; an asynchronous apply (`202` with a `correlationId`, mandatory `dryRun`, at-least-once delivery, the outcome later in the actions feed); a paged actions feed with five outcomes; `recommended: null` meaning "no change recommended"; fixed enums for window and percentile; and a `403 K8S_CONTROLLER_NOT_READY` on every policy `PUT` while readiness is false. | `api-contract.md` §1, §2.1-2.4, §3.1-3.2, §4, §5; engine plan §2.8, §5.2; `xamops.automation.vertical.enabled=false` in all five `application*.properties` on the engine branch. Endpoint 1 as committed (`ad5581ecd`, `VerticalAutomationReadService.read`) differs in two ways: while the switch is off it sends `clusterAccess: null` and `direct.reason: null` ("not checked") rather than `NOT_CONFIGURED`, and it computes `executionPath` from the agent check-in even then, so a disabled engine can report `AGENT` | Built against the published contract from day one (D16, §11.5): states V7 and V8 (§8.1), execution-path gates (§6.2, §6.3), apply as a submission (§9.2, §11.2), five outcomes in Activity (§9.6), and capabilities reports the switch (R9). V7 is decided by `enabled` alone and nothing gates on `clusterAccess`, so both deviations are harmless. |
| E23 | An unmapped path under `/api/**` doesn't get a clean `404` here. `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` has no case for `NoResourceFoundException`, so on Spring Framework 7 an unknown path most likely returns `500` "An unexpected error occurred: No static resource …". Reasoned from the code, not run. The engine's endpoints 2-4 are unmapped today. | `GlobalExceptionHandler.java:40-78` (catch-all; message at `:74`); parent `spring-boot-starter-parent` 4.0.8 (`pom.xml`); contract §1 ("CONTRACT ONLY means no handler is mapped") | The UI never decides "not deployed" from the HTTP status alone. Capabilities says whether the engine is on, and responses are classified by `code` (§11.2, §12.2). |
| E24 | With no policy row a workload's mode is `OFF`, and `OFF` makes the engine skip it (`POLICY_OFF`) with no recommendation. So the engine reports no recommendations until someone saves Recommend for a workload, and the contract also refuses policy writes while readiness is false. | Contract §3.1 (default `OFF`); engine plan §4.5 ("the decision then carries no container recommendations"); on the engine branch, `WorkloadPolicyDefaults` and `V20261010_1__vertical_workload_policy.sql` ("the default mode is OFF") | The UI shows `POLICY_OFF` workloads as "Off · not evaluated" and says why (§9.2). R11 asks the engine owner for a `RECOMMEND` default, which never acts. |
| E25 | Both agents send heartbeats to the same endpoint and push to the same ingest, and every heartbeat and every push sets `lastAgentCheckin` and `agentVersion` on the cluster config. The engine reports its agent path from `lastAgentCheckin`, so the Go automation agent alone can make the XamOps agent's channel look available. | `AgentController.heartbeat:308-385` (`setLastAgentCheckin` at `:371`); `AgentIngestService.updateAgentStatus:475-480`; `xamops-agent/internal/pusher/pusher.go:138` and `xamops-autoscaler/internal/pusher/pusher.go:70-81` both post `/api/v1/agent/heartbeat`; contract §2.2 (`agent.lastSeenAt` is `lastAgentCheckin`) | The UI shows the engine's execution path as the engine reports it and never treats it as XamOps agent health. Capabilities keeps detecting the XamOps agent from its own data row (§6.2). R12 asks the engine to do the same. |

---

## 4. Information architecture

### 4.1 Fleet: `/cloudk8s`

```
CloudK8s (fleet, selected AWS account)
├── Topbar: "CloudK8s" · account access chip (Read-only / Read-write / Unknown, from capabilities) · Connect a cluster · Refresh
├── Summary strip (6 tiles)
│   ├── Clusters            count · "n need attention"
│   ├── Nodes               sum over reporting clusters · "from n of m clusters"
│   ├── Monthly cost        sum over clusters with a cost source · source label · "from n of m"
│   ├── Potential savings   Estimated, from eligible vertical-rightsizer recommendations only · "—" when none
│   ├── Automation          Workloads: "x of m clusters" set up for workload autoscaling (V3 or V4), or "Not available in this environment" (V7) · Nodes: "y of m" with the automation agent
│   └── Needs attention     Not monitored · Data stale · Nodes not ready · Failed or crash-looping pods
├── Filter bar: search · region · status chips (Monitored, Not monitored, Stale, Needs attention) · Clear all · Export CSV
├── Cluster table (sortable, paginated 25/50/100)
└── Connect a cluster (side panel): XamOps agent setup · usage metrics · direct cluster API access (optional) · automation agent guidance
```

Cluster table columns, with the source of each:

| Column | Source | Unknown renders as |
|---|---|---|
| Cluster (EKS icon from `resolveAwsServiceIcon('EKS Cluster')`, name, EKS status) | `GET /api/xamops/k8s/clusters` (`K8sClusterInfo`) | — |
| Region | same | — |
| Version | same (`version`) | — |
| Nodes (ready/total) | cluster dashboard, `k8sNodes` conditions | "—" + "Not monitored" |
| CPU (requested % of allocatable · used %) | dashboard: pod container requests vs node allocatable; used from `clusterOverview.cpuUsagePercent` when reported | "Used —" when usage not reported |
| Memory (same) | same | same |
| Monthly cost | cost source rule (D9) | "—" + "No cost source" |
| Potential savings | Estimated, eligible vertical-rightsizer rows (D9) | "—" |
| Data source | capabilities `agent` + `K8sClusterInfo.containerInsights` | "Not monitored" |
| Automation | two lines: Workloads from the vertical engine status (the D5 labels: Off / Recommend / Auto counts, or the state that blocks them); Nodes from capabilities `automationAgent` + `nodeAutomation.derivedState` | "Not available" (engine switched off, V7) / "Not installed" |

The brief also listed an Access column. Access is per account, and the fleet shows one account (`CloudK8sPage.tsx:35` uses `selectedAccountIds[0]`), so a per-row Access column would repeat one value on every row. It is shown once, as the access chip in the topbar and summary strip, and becomes a column only when the fleet spans more than one account (follow-up F8). The chip reads capabilities `connection.accessLevel`, the same value the gates use, and shows "Unknown" when capabilities failed. Version support badges are deferred (D15). Container Insights is optional (the XamOps agent is the working data path), and with D3 its state is "configured", never "receiving", so it is not an attention criterion.

### 4.2 Cluster page: `/eks-details`

Seven primary sections. Each primary section is a lazily loaded chunk; subtabs live inside it.

| Primary (`tab=`) | Subtab (`sub=`) | Content | Data source |
|---|---|---|---|
| **Overview** `overview` | Summary `summary` (default) | Health signals, freshness, KPI tiles, top findings (shared with Alerts), data-source chips | dashboard; capabilities |
| | Events `events` | Agent-collected Kubernetes events, Warning by default, search, type filter | `k8sEvents` |
| **Cost** `cost` | Cluster `cluster` (default) | Daily and monthly cost with source; unallocated capacity cost; potential savings (Estimated) | dashboard cost fields; vertical engine |
| | Namespaces `namespaces` | Cost per namespace (OpenCost, else Estimated from requests) + requested vs used where reported | `costMetrics.namespaceCosts`, `namespaceUtilization`, `k8sPods` |
| | Workloads `workloads` | Cost per workload, same sourcing | `costMetrics.workloadCosts`, `k8sPods` |
| | Nodes `nodes` | Cost per node with allocation method; capacity type from node labels | `costMetrics.nodeCosts`, `k8sNodes` |
| **Workloads** `workloads` | Workloads `workloads` (default) | Deployments, StatefulSets, DaemonSets: replicas, requests (aggregated from pods), usage where reported, efficiency, recommendation, per-workload automation mode | `k8sDeployments/StatefulSets/DaemonSets`, `k8sPods`, `topCpuPods`, vertical engine |
| | Pods `pods` | Pod table (re-homed Pods tab), paginated | `k8sPods` |
| | Containers `containers` | Container table (re-homed Containers tab) | `k8sPods`, `containerMetrics` |
| | Resources `resources` | Services, Ingresses, PVCs, Jobs, HPAs (re-homed Resources tab) | `k8sServices`, `k8sIngresses`, `k8sPvcs`, `k8sJobs`, `k8sHpas` |
| **Nodes** `nodes` | Nodes `nodes` (default) | Node table (re-homed Nodes tab) | `k8sNodes`, `nodeMetrics` |
| | Node groups `node-groups` | Grouped by `eks.amazonaws.com/nodegroup`, `karpenter.sh/nodepool`, else "Unmanaged" | `k8sNodes` labels |
| | Karpenter `karpenter` | Re-homed Karpenter tab | `karpenter` |
| | Utilization `utilization` | Re-homed Performance tab | `nodeUtilization`, `resourceUtilization` |
| **Automation** `automation` | Overview `overview` (default) | Access, then one readiness block per engine (vertical rightsizer; automation agent), controller readiness table, policy summary, dry-run state | capabilities; vertical engine status; `GET /api/eks/autoscaling/{c}` |
| | Workload autoscaling `workload-autoscaling` | Vertical rightsizer: per-workload mode, current vs recommended requests and limits per container, findings, apply method, approved apply (a submission, with dry run as the default), HPA conflicts | `GET /api/eks/automation/vertical/{c}` (published contract, §11.5), `k8sHpas` |
| | Node autoscaling `node-autoscaling` | Consolidation analysis ("not yet reliable"), evictor settings, node optimizer settings, scale-out and node templates (not built) | `/evictor-candidates`, `/node-fleet`, policy |
| | Rebalancing `rebalancing` | What it does, why it is blocked, last reported status | `/rebalance-status`, policy |
| | Guardrails `guardrails` | Locked and editable safety guards, protected namespaces (Go agent policy) | policy |
| | Activity `activity` | One feed, three sources: vertical engine actions (with their `dryRun` flag and outcome), automation-agent reports (unverified), cluster events and XamOps changes | `GET /api/eks/automation/vertical/{c}/actions` (contract-only today, §9.6); policy `recentEvents`, `recentChanges` |
| **Security** `security` | Vulnerabilities `vulnerabilities` (default) | Trivy (re-homed from Security tab) | `trivy` |
| | Compliance `compliance` | Kubescape (`KubescapeComplianceView`) | `kubescape` |
| | Runtime `runtime` | Falco (`RuntimeSecurityView`) | `falcoEvents`, `securityMetrics` |
| **Data sources** `data-sources` | Connections `connections` (default) | Every source with status, last data, what it powers, remedy; includes the vertical engine's execution path, direct API access and usage metrics | capabilities; cluster list; vertical engine status |
| | Install `install` | Setup cards: XamOps agent guide parts, usage metrics, direct cluster API access (optional), automation agent guidance | `EksIntegrationModal`; `ClusterAccessGuide`; `AutomationAgentGuide` |

The topbar keeps the cluster name as title (`EksDetailsPage.tsx:86`), back link, version and region, an agent freshness label, Guide, and Sync. A one-line status strip sits above the tabs: overall health, nodes ready, pods running, automation state, access (from capabilities `connection.accessLevel`, like the fleet chip; "Unknown" when capabilities failed). `?debug=true` still renders `EksDiagnosticsPanel` above the tabs.

### 4.3 Where today's content goes

| Today (`EksTabs.tsx:18-27`) | New home | Change |
|---|---|---|
| Overview | Overview › Summary | Composite score and "savings opportunity" removed (E8, E9); KPI tiles, namespace and top-pod panels kept and restyled |
| Performance | Nodes › Utilization | Restyled; efficiency shown only when both usages are reported, per-node usage stays `null` instead of `\|\| 0` (§14.1) |
| Workloads | Workloads › Workloads | `clusterHealthScore` display removed; requests and recommendation columns added |
| Pods | Workloads › Pods | Restyled; pagination; usage columns only where reported |
| Containers | Workloads › Containers | Restyled; usage only where reported |
| Nodes | Nodes › Nodes | Restyled; capacity type as text plus icon, `✓`/`!` become `StatusMark` |
| Resources | Workloads › Resources (Events → Overview › Events) | Refined from the brief's "nodes/utilization": `EksResourcesTab.tsx:127-604` renders HPAs, PVCs, Services, Ingresses, Events and Jobs, which are workload objects, not node utilisation |
| Security | Security › Vulnerabilities / Compliance / Runtime | Split along its existing views; restyled, compliance score no longer carried by colour |
| Cost | Cost › Cluster / Namespaces / Workloads / Nodes | Multipliers removed, sources labelled |
| Karpenter | Nodes › Karpenter | Restyled; capacity type as text plus icon |
| (Autoscaling, commented out) | Automation › six subtabs | Rebuilt, not re-enabled (§9) |

Nothing moves "as is". Every file whose content is re-homed goes through the conformance pass in §14.1 (neutral ladder, `StatusMark`, bundled icons, null handling) before its slice is done, and the design-rule guard in §14.1 fails the build if any of it regresses.

---

## 5. URLs and deep links

### 5.1 Scheme

`/eks-details?name=<cluster>&region=<region>&accountIds=<awsAccountId>&tab=<primary>&sub=<subtab>[&version=…][&debug=true]`

- `name`, `region`, `accountIds`, `version`, `debug` keep their current meaning (`EksDetailsPage.tsx:54-59`).
- `tab` and `sub` are owned by a new hook, `useEksSectionRoute`. Selecting a tab writes the URL with `navigate({ search }, { replace: true })`, the same convention as `hooks/useTabState.ts`, so links are shareable and Back leaves the page instead of stepping through tabs.
- Resolution: a known primary `tab` is used as is; an unknown `sub` falls back to that section's default sub; an unknown `tab` falls back to `overview/summary`. When the URL had to be corrected (legacy value, unknown value, or missing `sub`), the hook rewrites it once with `replace` and keeps every other parameter.
- `/eks-details` without `name` or `accountIds` redirects to `/cloudk8s` (today it renders a "Missing Parameters" error page, `EksDetailsPage.tsx:132-144`).

### 5.2 Legacy map

| Old `?tab=` | New `tab/sub` |
|---|---|
| `overview` | `overview/summary` |
| `performance` | `nodes/utilization` |
| `workloads` | `workloads/workloads` |
| `pods` | `workloads/pods` |
| `containers` | `workloads/containers` |
| `nodes` | `nodes/nodes` |
| `resources` | `workloads/resources` (refined, §4.3) |
| `security` | `security/vulnerabilities` |
| `cost` | `cost/cluster` |
| `karpenter` | `nodes/karpenter` |
| `autoscaling` | `automation/overview` |

The five legacy values that are also new primary names (`overview`, `cost`, `workloads`, `nodes`, `security`) resolve to the same primary with its default sub, so the map and the normal resolution agree.

### 5.3 Internal links, and what each becomes

| Link | Today | After |
|---|---|---|
| Fleet row click | `CloudK8sPage.tsx:97` (no tab) | Same URL; lands on `overview/summary` |
| EKS alerts | `EksAlertsTab.tsx:117` builds `&tab=${alert.targetTab}` with legacy values (`useEksAlertsData.ts:96-270`) | `EksAlertItem` gains `targetSub`, set by source: falco → `security/runtime`, kubescape → `security/compliance`, trivy → `security/vulnerabilities`, events → `overview/events`, nodes → `nodes/nodes`, pods → `workloads/pods`, karpenter → `nodes/karpenter`. Legacy `targetTab` still resolves through the map. |
| Command palette | `CommandPalette.tsx:58` "EKS Details" → bare `/eks-details` (broken today) | Entry removed; its keywords move to the CloudK8s entry (`:57`), whose keywords become `eks k8s kubernetes clusters automation rightsizing`. |
| Integration guide event | `EksDetailsPage.tsx:47-51` listens for `open-integration-guide`, dispatched only by the dormant tab | Removed with the dormant tab; the guide opens from the topbar Guide button and from Data sources › Install. |
| Sidebar | `Sidebar.tsx:385` marks CloudK8s active only on `/cloudk8s` | Active on `/cloudk8s`, `/eks-details`, `/gke-details`, `/aks-details` too. |
| Demo router, help modals, backend | Grep of `frontend-app/src`, `xamops-service/src/main`, `billops/src/main`: no other `eks-details` deep links | No change. `permissions.ts:93` maps `/eks-details` to `KUBERNETES` for permission gaps and stays. |

---

## 6. Capability-gating model

### 6.1 Reason codes

One enum, shared by the frontend and the backend. The server sends `code = "K8S_" + reason` on refusals; the UI maps both directions with one table.

| Reason | Meaning | Who computes it | Enforced by | Visible text (exact) | Short label | Remedy |
|---|---|---|---|---|---|---|
| `DEMO` | Shared demo identity | UI: `isDemoMode()`; server: the principal's user row (loaded by id) has an email in `DEMO_EMAILS`. Never the username, which is "Xammer Admin" / "Xammer BillOps" (E12) | UI + server | "Demo workspace. Changes aren't saved." | never listed (§6.4) | none |
| `PROVIDER_UNSUPPORTED` | GKE/AKS cluster | UI from account provider | UI (server only serves EKS) | "Automation is available for EKS clusters." | "an EKS cluster" | none |
| `CONTROLLER_NOT_READY` | The controller behind this control failed safety validation, or is not built. For `vertical.*` actions also: the engine's server-side checks aren't confirmed yet (the contract block, D16), and the engine's own `gateReasons` entry, which reads the same readiness property | server config (§6.7), served by capabilities; the engine's `gateReasons`; for the contract block, the adapter (`VERTICAL_WRITES_CONFIRMED = false`) | server + UI (the contract block is UI-only by nature: it exists because the server side isn't confirmed) | Per control, server-provided; generic: "Automated apply is disabled until this controller passes safety validation." Contract block: "Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed." | "safety validation" | "Why?" explainer (not an enablement path) |
| `ANALYSE_ONLY` | Live consolidation (evictor) | same readiness config, key `evictor-live` | server + UI | "Live consolidation is disabled until pod-fit simulation is available." | "safety validation" | "Why?" explainer |
| `NO_USER_PERMISSION` | User may not change automation. The server sends a `detail` that picks the copy: `TAB_GRANT` (restricted user without the permission key), `ROLE` (role can't change automation), `IMPERSONATION` (signed in as another user) | server (D2), served by capabilities as `user.reasons` + `user.detail` | server + UI | `TAB_GRANT`: "You don't have permission to change automation." · `ROLE`: "Your role can view automation but not change it." · `IMPERSONATION`: "Automation changes aren't available while you're signed in as another user." | "permission to change automation" | `TAB_GRANT`: "Ask your XamOps administrator to allow automation changes for your user." (text, no link: the admin screen is the admin's, not this user's) · `IMPERSONATION`: "Return to your own session to change automation." (text, no link) · `ROLE`: none |
| `READ_ONLY_CONNECTION` | Account connected read-only | server (D1), served by capabilities | server + UI | "Read-only connection. Automation changes need read-write access." | "read-write access" | "Get read-write access" → explainer with "Contact support" → `/tickets` (D1) |
| `CONNECTION_NOT_VERIFIED` | Account status is not `CONNECTED` (status only) | server: `!"CONNECTED".equalsIgnoreCase(account.getStatus())`. This is the status half of `AwsClientProvider.getAutoScalingWriteClient:233-237`; its `roleArn`/`externalId` checks don't apply, because Kubernetes automation never assumes the AWS role | server + UI | "This connection isn't verified. Reconnect the account to change automation." | "a verified connection" | "Open Account Manager" → `/account-manager` |
| `AGENT_NOT_INSTALLED` | An agent the control needs isn't connected. The UI sets a `detail` that picks the copy: `AUTOMATION_AGENT` (no automation agent heartbeat; node automation controls), `XAMOPS_AGENT` (no XamOps agent data; node automation controls on an unmonitored cluster), `EXECUTION_PATH` (vertical engine: `executionPath` is `NONE` and `executionPathDetail.agent.reason` is this code, contract §2.2) | capabilities (`AUTOMATION_AGENT`, `XAMOPS_AGENT`); the vertical engine (`EXECUTION_PATH`) | UI only for node automation (§6.8); engine + UI for `EXECUTION_PATH` (the engine refuses an apply with no path, contract §3.2) | `AUTOMATION_AGENT`: "The automation agent isn't installed on this cluster." · `XAMOPS_AGENT`: "This cluster isn't monitored yet. Install the XamOps agent first." · `EXECUTION_PATH`: "The XamOps agent isn't connected, so XamOps can't send changes to this cluster." plus one line for the direct path (the two rows below) | `AUTOMATION_AGENT`: "the automation agent" · `XAMOPS_AGENT`: "the XamOps agent" · `EXECUTION_PATH`: "a connected XamOps agent" | `AUTOMATION_AGENT`: "Install the automation agent" → `data-sources/install#automation-agent` · `XAMOPS_AGENT`, `EXECUTION_PATH`: "Set up monitoring" → `data-sources/install#xamops-agent`; `EXECUTION_PATH` adds "Or set up direct access" → `#cluster-access` when the direct reason is `CLUSTER_ACCESS_NOT_CONFIGURED` |
| `CLUSTER_ACCESS_NOT_CONFIGURED` | Vertical engine, direct path: no EKS access entry maps the XamOps role into the cluster. Never a primary reason: the agent path is primary, so this only explains why the optional direct path is missing | vertical engine (`executionPathDetail.direct.reason`) | engine + UI | Under `EXECUTION_PATH`: "Direct access isn't set up: no EKS access entry maps the XamOps role." In Data sources: "Not configured" | "cluster API access" | "Set up direct access" → `data-sources/install#cluster-access` |
| `CLUSTER_UNREACHABLE` | Vertical engine, direct path: access is configured but the API server can't be reached (for example, a private endpoint). Never a primary reason, as above | vertical engine (`executionPathDetail.direct.reason`) | engine + UI | Under `EXECUTION_PATH`: "Direct access isn't possible: the API server isn't reachable from XamOps (private endpoint)." In Data sources: "Unreachable" | "cluster API access" | "See access options" → `data-sources/install#cluster-access` |
| `NO_METRICS_SOURCE` | Vertical engine: no usage metrics source for this cluster (`metricsSource.reachable: false`) | vertical engine (`gateReasons`) | engine + UI | "No usage metrics source for this cluster, so XamOps can't compute recommendations." | "a usage metrics source" | "See metrics options" → `data-sources/install#usage-metrics` |
| `INSUFFICIENT_HISTORY` | Vertical engine: not enough usage history for the policy window (cluster-level gate, or a workload's `skipReason`, same spelling, contract §5.2) | vertical engine | engine + UI | "Not enough usage history yet. Recommendations need `<window>` of data." | "more usage history" | none (waiting state; shows the window and the engine's sample coverage when reported) |
| `GITOPS_MANAGED` | Vertical engine: the workload is managed by Argo CD or Flux, so its template must not be patched | vertical engine (per workload) | engine + UI | "Managed by Argo CD or Flux. XamOps won't patch its template." | "an in-place apply method" | "Use in-place resize" (switch the apply method) |
| `MISSING_AWS_PERMISSION` | A data view needs an AWS action the role lacks (role predates the EKS read policy, or was edited) | backend status field (e.g. Container Insights `UNKNOWN` with reason) and the existing permission-gap feed | UI (data views only) | "XamOps can't read `<action>` in this account." | "an updated connection" | "Update connection" → `/account-manager` (stack update) |
| `UNKNOWN` | Capabilities, or the vertical engine status, could not be loaded | UI | UI (fail closed) | "Couldn't check automation access." | "an access check" | "Retry" |
| `GUARDRAIL_LOCKED` | A guardrail that cannot be loosened | server normalizer | server (UI renders locked rows as static text) | "Always on while automation lacks pod-fit simulation." | never listed (never competes, §6.4) | none |

"Also needs: …" lists the short labels of the remaining reasons, comma-separated, each once (§6.4). An unknown code from either server is rendered generically: the server's `message` (plain text, length-capped) under the label "Not allowed", with the code in the tooltip, and the control stays disabled; its short label is "an access check". New engine codes therefore degrade safely before the map learns them. The engine's skip reasons, findings and container reasons are not gate codes (contract §5.2); they render as text through their own copy table (§9.2).

`MISSING_AWS_PERMISSION` never gates automation controls: Kubernetes automation does not use AWS data APIs. It gates data views that do. (The vertical engine's direct path authenticates to the cluster through the XamOps role and an access entry; failures there surface as `CLUSTER_ACCESS_NOT_CONFIGURED` or `CLUSTER_UNREACHABLE`.)

**Error codes that are not gates.** The refusal body (§6.6) also carries six codes that never disable a control and never appear in capabilities: `K8S_UNAUTHENTICATED` (401), `K8S_INVALID_REQUEST` (400, malformed path or query), `K8S_INVALID_POLICY` (400, `field` set, §13), `K8S_NO_ACCOUNT_ACCESS` (403, shown page-level as "You don't have access to this account."), `K8S_CLUSTER_NOT_REGISTERED` (404, "This cluster isn't registered with XamOps yet. Install the XamOps agent first.") and `K8S_INTERNAL` (500, fixed message). `K8sAutomationReason` holds both groups with a `gate()` flag. The frontend `ReasonCode` type covers the gates only; `K8sApiError` maps the error codes per §12.2. The shared fixture `xamops-service/src/test/resources/k8s/k8s-reason-codes.json` lists both groups and the three `NO_USER_PERMISSION` details. JUnit loads it from the classpath (`/k8s/k8s-reason-codes.json`); vitest reads it with `node:fs` from the repo-relative path (§17). CI checks out the whole repo for the frontend job (`ci-cd.yml:593`), so the path resolves there.

### 6.2 How each gate is sourced

- **Connection access level** (`READ_ONLY_CONNECTION`). `CloudAccount.accessType` (`CloudAccount.java:31`), written at onboarding (`AwsAccountService.java:145-152`) and already the gate for every other XamOps write path: `RightsizingApplyService.java:76`, `WasteDeleteService.java:77`, `GcpOptimizationService.java:2673`, `AwsClientProvider.java:231`. The UI already reads it as `Account.access` (`types/account.ts:12`, `RightsizingPage.tsx:26`, `WastePage.tsx:17`, `EbsRightsizingPage.tsx:67`). Anything other than `"read-write"` (case-insensitive), including null on legacy rows, is read-only. See D1.
- **User permission** (`NO_USER_PERMISSION`, `DEMO`). Computed server-side by `K8sAutomationAccessService`. It loads the `User` row by the principal's id (`ClientUserDetails.getId()`), and takes the email, role and tab rows from the database rather than from the session: the email decides `DEMO`, `ImpersonationContext` decides the impersonation case, the row's role (normalised with the `ROLE_` prefix, as `ImpersonationFilter.java:54` does, because `CustomUserDetailsService.java:54-55` grants the raw DB value and `User`'s default is `"USER"`, `User.java:65`) decides the role case, and `UserTabAccessRepository.findTabNamesByUserId` (`UserTabAccessRepository.java:16-17`) decides the grant. A principal without an id, or whose row can't be loaded, is refused (`NO_USER_PERMISSION`, WARN log). See D2.
- **Automation agent installed** (`AGENT_NOT_INSTALLED`). Computed by the capabilities endpoint. Recent presence: the newest `agent_events` row for the cluster whose `agentVersion` ends with `-autoscaler` **within the last 24 hours** (`findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc`), so the `LIKE '%-autoscaler'` filter runs only over one day of that cluster's rows (the table has single-column indexes only, `AgentEvent.java:12-15`). The pusher hardcodes `1.0.0-autoscaler` (`pusher.go:31`) and heartbeats record it (`AgentController.java:372-377`). "Ever seen": the `agent_data_cache` projection the endpoint already loads (rows whose `agentVersion` ends with `-autoscaler`, `AgentIngestService.java:452-461`), so no second history scan. Connected if seen within 5 minutes (heartbeat every 60 s, `config.go:62`); stale if seen within 24 hours or only in the cache, with the last time shown; not installed if neither source has it. Event pruning (`deleteOldEventsBatch`) can turn a long-gone agent into "not installed", which is accurate enough for a setup prompt. This detection never uses `KubernetesClusterConfig.lastAgentCheckin`, which every agent's heartbeat refreshes (E25).
- **XamOps agent** (`AGENT_NOT_INSTALLED` with detail `XAMOPS_AGENT`, and the "Not monitored" state). The `k8s-resources` row's `collectedAt` in `agent_data_cache` (only the observer agent pushes it). Connected within 15 minutes, the same window as `KubernetesClusterConfig.isAgentActive:251-254`; push interval is 300 s.
- **Clocks.** The two kinds of timestamp are converted before any comparison. Columns the server writes with `LocalDateTime.now()` (`agent_events.createdAt`, `AgentEvent.java:71,86-90`; `agent_data_cache.receivedAt`, `AgentDataCache.java:90-101`) are in the JVM's zone: `atZone(ZoneId.systemDefault()).toInstant()`. Agent-supplied `collectedAt` is UTC wall-clock (the XamOps agent sends `time.Now().UTC()`, `xamops-agent/internal/pusher/pusher.go:235`; the automation agent sends `time.Now()`, the container's zone, which is UTC unless its image sets one, `xamops-autoscaler/cmd/autoscaler/main.go:184`), parsed into a `LocalDateTime` (`AgentIngestPayload.java:134`): `atOffset(ZoneOffset.UTC).toInstant()`. The one exception is a batch without `collectedAt`, where ingest falls back to server time (`AgentIngestService.java:457,461`); neither agent omits it. Thresholds compare `Instant`s against the injected `clock.instant()`, and every timestamp in the capabilities response is serialized as an ISO-8601 UTC instant. On a UTC JVM, which production uses, both conversions agree; the rule is what keeps a development JVM in IST from marking every agent 5.5 hours old.
- **Controller readiness** (`CONTROLLER_NOT_READY`, `ANALYSE_ONLY`). `xamops.k8s.automation.readiness.*` properties (§6.7), served by capabilities. The vertical engine's key is `vertical-rightsizing`, and the engine binds the same property (engine plan D9; R1 is about its endpoints refusing on it).
- **Vertical engine switch** (V7). Capabilities reports `verticalEngine.enabled` from the engine's own property `xamops.automation.vertical.enabled` (R9, §11.3). When it isn't `true`, the UI makes no engine call at all.
- **Vertical engine gates** (`AGENT_NOT_INSTALLED` with detail `EXECUTION_PATH`, `NO_METRICS_SOURCE`, `INSUFFICIENT_HISTORY`, `GITOPS_MANAGED`, and the engine's `CONTROLLER_NOT_READY`). From the engine's `GET /api/eks/automation/vertical/{c}` (contract §2), evaluated in this order:
  1. `engine.enabled === false` → V7 (§8.1). No vertical control renders, so nothing is gated one by one.
  2. **Execution path.** `executionPath === "NONE"` → `AGENT_NOT_INSTALLED` with detail `EXECUTION_PATH` when `executionPathDetail.agent.reason` is `AGENT_NOT_INSTALLED`, carrying `directPath` = `executionPathDetail.direct.reason` for the second line. If the agent reason is some other code, that code is the reason; if both reasons are null or unrecognised, the generic unknown-code rendering applies. `DIRECT` or `AGENT` → no access gate. A missing or unrecognised `executionPath` → the generic rendering, fail closed. `clusterAccess` is never used for gating: it is a placeholder while the engine is off (contract §2.1) and describes only the optional direct path.
  3. **Cluster-level `gateReasons`** apply per action through the scoping table in §6.3.
  4. **Per workload**: `gitopsManaged`, `skipReason` and each container's `recommended` (§9.2).
  The UI never infers these; it shows what the engine reports. When the engine's `gateReasons` also contain a write gate (for example `READ_ONLY_CONNECTION`), the UI takes the union with capabilities, so the stricter answer wins. The union only makes the UI stricter; it guarantees nothing to API callers. That is why, until R1 and R2 are confirmed, the adapter also adds `CONTROLLER_NOT_READY` to every `vertical.*` action (D16).
- **AWS permission** (`MISSING_AWS_PERMISSION`). For Container Insights, the new status field carries it directly (D3). Account-wide gaps keep coming from the existing `/api/xamops/account-health/permissions?feature=KUBERNETES` feed (`usePermissionGaps.ts`) and the global `PermissionGapBanner`.
- **Demo**. `isDemoMode()` (`demoConfig.ts:34-36`, set at sign-in from the profile email by `activateDemoModeIfNeeded`) in the UI. On the server, `K8sAutomationAccessService.isDemo` compares the user row's email, trimmed and lower-cased, with `DEMO_EMAILS = {"demo.xamops@xammer.in", "demo.billops@xammer.in"}`. The constant mirrors the private set in `LoginOtpController.java:70-73`, which stays untouched (Login is out of scope); a reflection test fails if the two sets drift (§17). The username is never compared: it is "Xammer Admin" / "Xammer BillOps" (E12).
- **Impersonation**. `ImpersonationContext.getImpersonatedUserId() != null` on the request thread. Both the header path and the BillOps JWT path set it before any handler or interceptor runs (E17).
- **Provider**. The selected account's provider (`AccountContext.selectedAccountDisplay.provider`).

### 6.3 Action catalogue

Every automation control maps to one action id. The capability hook evaluates gates per action; the server enforces the same set per endpoint.

**Workload autoscaling (vertical engine).** Paths are relative to `/api/eks/automation/vertical/{c}` (published contract, §11.5). Endpoints 2 and 3 are contract-only today, so these controls are built and blocked (D16).

| Action id | Control | API | Gates (in addition to the write gates) | Readiness key |
|---|---|---|---|---|
| `vertical.policy.recommend` | Per-workload mode Off / Recommend, and headroom, percentile, window | `PUT …/workloads/{ns}/{kind}/{name}/policy` with `mode: OFF` or `RECOMMEND` | readiness: the contract refuses every policy `PUT` while it is false (§3.1; R10) | `vertical-rightsizing` |
| `vertical.policy.auto` | Mode Auto | same with `mode: AUTO` | + readiness, + execution path (§6.2), + `NO_METRICS_SOURCE` and `INSUFFICIENT_HISTORY` from `gateReasons` | `vertical-rightsizing` |
| `vertical.method.persist` | Apply method "In place and persist (restarts pods)" | same with `applyMethod: IN_PLACE_AND_PERSIST` | + readiness (it is a policy `PUT`), + `GITOPS_MANAGED` (per workload); explicit opt-in dialog | `vertical-rightsizing` |
| `vertical.apply` | Simulate or Apply now (one-shot, approved; `dryRun` chosen in the dialog) | `POST …/workloads/{ns}/{kind}/{name}/apply` | + readiness, + execution path, + `NO_METRICS_SOURCE` and `INSUFFICIENT_HISTORY`, + `GITOPS_MANAGED` when the method persists. Offered only for a workload with at least one container recommendation and no `skipReason` | `vertical-rightsizing` |

The UI gates Auto on the execution path and on metrics even though the contract would store an `AUTO` without them: an Auto mode that can't reach the cluster or measure anything does nothing, and the UI may be stricter than the engine. A dry run is gated like a real apply, because the contract refuses both while readiness is false (§3.2).

**Cluster-level `gateReasons` mapped to actions.** The engine reports gates per cluster, not per action, so the adapter scopes each code with one table:

| Code in `gateReasons` | Applies to |
|---|---|
| `CONTROLLER_NOT_READY` | every `vertical.*` write: the contract refuses policy writes (§3.1) and applies (§3.2) while readiness is false. If the engine owner answers R10 with "Off and Recommend are accepted while not ready", the change that flips `VERTICAL_WRITES_CONFIRMED` removes `vertical.policy.recommend` and `vertical.method.persist` from this row, and V4's "Off and Recommend editable" then holds before readiness |
| `NO_METRICS_SOURCE`, `INSUFFICIENT_HISTORY` | `vertical.policy.auto`, `vertical.apply` |
| `GITOPS_MANAGED` | `vertical.method.persist`, and `vertical.apply` with a persisting method |
| `CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE`, `AGENT_NOT_INSTALLED` | nothing directly: the execution-path rule (§6.2, step 2) decides, so a working agent path is never blocked by a missing direct path |
| `DEMO`, `NO_USER_PERMISSION`, `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED` | every `vertical.*` write, unioned with capabilities |
| anything else | every `vertical.*` write, generic rendering (fail closed) |

`IN_PLACE` is the default method and needs no opt-in. Selecting `IN_PLACE_AND_PERSIST` is configuration, allowed whenever policy writes are, but always through the opt-in dialog in §9.2, so nobody arrives in Auto with a restarting method by accident.

**Node automation (Go automation agent).** Paths are relative to `/api/eks/autoscaling`.

| Action id | Control | API | Gates (in addition to the write gates) | Readiness key |
|---|---|---|---|---|
| `automation.enable` | Node automation on/off switch | `PUT /{c}/enabled` | `AGENT_NOT_INSTALLED` | — |
| `automation.scope` | Scope selector (Full / Scoped / Paused) | `PUT /{c}/mode` | `AGENT_NOT_INSTALLED` | — |
| `agentRightsizer.edit` | None in the UI (retired, E16) | `PUT /{c}/workload-rightsizer` | always refused, `403 K8S_CONTROLLER_NOT_READY` with the retired message | none: retirement is unconditional code, not a readiness flag (§6.7) |
| `evictor.settings` | Analysis thresholds, grace period | `PUT /{c}/evictor-policy` with `analyseOnly: true` | `AGENT_NOT_INSTALLED` | — |
| `evictor.live` | Live consolidation | same with `analyseOnly: false` | + readiness (`ANALYSE_ONLY`) | `evictor-live` |
| `nodeOptimizer.settings` | Idle thresholds, spot interruption handling | `PUT /{c}/node-optimizer` | `AGENT_NOT_INSTALLED` | — |
| `nodeOptimizer.actions` | Auto-cordon, auto-drain, spot ratio target | same with those fields set | + readiness | `node-optimizer-actions` |
| `scaleOut.settings` | Scale-out policy | `PUT /{c}/scale-out-policy` | readiness (not built) | `scale-out` |
| `nodeTemplates.edit` | Node templates | `PUT`/`DELETE /{c}/node-templates/{id}` | readiness (not consumed) | `node-templates` |
| `rebalance.trigger` | Rebalance now | `POST /{c}/rebalance` | readiness | `rebalancer` |
| `rebalance.schedule` | Scheduled rebalancing | `PUT /{c}/rebalancer` with `scheduleEnabled: true` | readiness | `rebalancer` |
| `guardrails.edit` | Editable guardrails | `PUT /{c}/safety-guards` | `AGENT_NOT_INSTALLED`; locked rows are static | — |
| `hpa.apply` | Reserved for when HPA recommendations are served (F3) | — | readiness | `hpa-apply` |

The **write gates** apply to every action in both tables: `DEMO`, `PROVIDER_UNSUPPORTED`, `NO_USER_PERMISSION` (with its `TAB_GRANT`, `ROLE` or `IMPERSONATION` detail), `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED`, and `UNKNOWN` when capabilities (or, for vertical actions, the engine status) failed to load. Which one the control shows first is §6.4's job. Every `vertical.*` action additionally carries `CONTROLLER_NOT_READY` with the contract message while `VERTICAL_WRITES_CONFIRMED` is false (D16).

Readiness evidence for each key: `vertical-rightsizing` (new engine, no live-test verdict yet; the owner flips it after that verdict, and only once F17 is resolved, §6.7); the retired Go rightsizer (harness §2, §7-B3: no floors, restart loop, QoS downgrade; retired by E16, so it has no key); `hpa-apply` (§2: fixed formula, PDB contradiction); `evictor-live` (§2, §7-B2: DaemonSet bail-out, no fit simulation); `rebalancer` (§2: fail-open env gate, non-idempotent; E3); `node-optimizer-actions` (§2: hardcoded savings and interruptibility, non-deterministic target, ignores `maxParallelDrains`); `scale-out` and `node-templates` (no Go controller reads `ScaleOutPolicy` or `NodeTemplates`: grep across `cmd/` and `internal/controller/` returns 0 uses; the agent never provisions nodes, audit §2.5).

Spot interruption handling (`handleSpotInterruptions`) stays allowed: it acts only on a node AWS is already reclaiming, and the orchestrator's not-ready list does not include it. Its copy notes it depends on `aws-node-termination-handler` labels (roadmap §5.2 item 5).

### 6.4 Precedence

When several reasons apply, the control shows the one that is hardest to remove, followed by "Also needs: …" for the rest. Fixing a lower reason would not help while a higher one holds.

1. `PROVIDER_UNSUPPORTED`
2. `CONTROLLER_NOT_READY` / `ANALYSE_ONLY` / `GITOPS_MANAGED` (product rules; no customer setting lifts them, though `GITOPS_MANAGED` points to the in-place method)
3. `NO_USER_PERMISSION` (needs another person, or signing in as yourself)
4. `READ_ONLY_CONNECTION`, then `CONNECTION_NOT_VERIFIED` (needs an AWS-side change)
5. `UNKNOWN` (retry)
6. `AGENT_NOT_INSTALLED` (any detail), `NO_METRICS_SOURCE` (needs setup). `CLUSTER_ACCESS_NOT_CONFIGURED` and `CLUSTER_UNREACHABLE` are never primary: they only explain the direct path under `EXECUTION_PATH` (§6.1)
7. `INSUFFICIENT_HISTORY` (needs time)

**`DEMO` is the primary reason only when it is the only reason.** Otherwise `can()` puts it last in `also`, and `ReasonText` leaves it out of the "Also needs" line, because the demo banner already says "Demo workspace. Changes aren't saved." This is a display rule only. It is what lets each demo account show the state it is there to demonstrate (§8.2, D12): the read-write fixture's live controls read the readiness reason, and the read-only fixture's controls read "Read-only connection…". The server's `writeDecision` order is unchanged and still refuses `DEMO` first (§6.6), because a demo credential must never reach a deeper check.

`editable` follows the same reasons: it is true only when `DEMO` is the only reason (or there is none), so DEMO + READ_ONLY_CONNECTION is read-only, not an editable draft.

So a read-only customer looking at Live consolidation reads "Live consolidation is disabled until pod-fit simulation is available", because upgrading their connection would not enable it. The same customer looking at the node automation switch reads "Read-only connection…". Looking at a workload's Recommend mode they read the vertical contract message ("Workload autoscaling changes are off until…") with "Also needs: read-write access". Once `VERTICAL_WRITES_CONFIRMED` is true, and while `vertical-rightsizing` isn't ready, they read the readiness message with the same line; after that, "Read-only connection…". Looking at Auto on a cluster with no execution path, they read the contract message with "Also needs: read-write access, a connected XamOps agent".

**Each reason appears once.** `can()` de-duplicates `also` by code, keeping the first occurrence and its message, so the contract block's `CONTROLLER_NOT_READY` and the one from `gateReasons` or readiness never both appear. "Also needs" lists the short labels from §6.1.

`GUARDRAIL_LOCKED` and `MISSING_AWS_PERMISSION` are not in this order because they never compete: locked guardrails render as static text, not as gated controls, and AWS permission gaps gate data views only (§6.1).

### 6.5 Edit versus commit

Each automation control has two layers. **Edit** is local draft interaction (moving a slider, typing a namespace). **Commit** is the server call (Save, the switch flip, a confirm dialog's primary button).

- `DEMO` alone blocks commit only. The demo can walk through the read-write experience: forms are editable, Save is visibly disabled with the demo reason. Nothing is sent.
- Every other reason blocks both edit and commit. The control renders the current server value, greyed in place, with the reason. A read-only user sees exactly which settings are in force.

Four wrappers consume one `Capability` (§11.1):

- `GatedAction`: button-like controls. Kind `commit` (the default: Save, Apply now, a dialog's confirm) reads `allowed`. Kind `draft` (Add or Remove in a draft list, Discard) changes only the draft and reads `editable`.
- `GatedSwitch`: a switch that saves when toggled, such as Node automation on/off. Reads `allowed`.
- `GatedField`: draft fields that a separate Save commits: text, number, checkbox, a switch inside a form, select, slider. Reads `editable`.
- `GatedRadioGroup`: a draft radio group whose options can carry their own capabilities, such as Mode, where Auto is blocked on its own. Reads `editable`, per option and for the group.

**Anything that sends a request when operated is a commit** and uses `GatedAction` (kind `commit`) or `GatedSwitch`, never `GatedField` or `GatedRadioGroup`. That is what stops a `DEMO`-only user, who is `editable` but not `allowed`, from operating anything that saves. §9 names the wrapper for every control.

**A blocked control is greyed, never hidden or replaced by text.** Whatever the wrapper, it has the same form: the control itself (or a non-interactive replica of it) in the blocked style with the lock icon and its current value; `aria-disabled="true"` and `aria-describedby` (pointing at the reason) on its focusable element; still focusable; and activation does nothing. No wrapper ever sets `disabled` or `isDisabled`. The rule per control type is in §14.

Forms keep a draft separate from server state. "Unsaved changes" appears beside Save; "Discard" restores the server value. A failed save keeps the draft so the user can retry, and never changes server state on screen.

**A section save replaces the whole section.** The section DTOs use primitive fields with defaults (`EvictorPolicyDto.enabled = true`, `NodeOptimizerPolicyDto.handleSpotInterruptions = true`), so the server can't tell an omitted field from a default one. The UI therefore always sends the complete section: the current server value with the draft applied. Endpoint Javadoc states the same rule for API callers. One server-side exception protects customer data: an omitted or `null` `safetyGuards.criticalNamespaces` keeps the stored list instead of resetting it, so a partial body can't silently unprotect a customer's namespaces (§13). Rejecting incomplete bodies outright needs boxed request DTOs and is F20.

### 6.6 Server enforcement

**Guard interceptor.** `K8sAutomationGuardInterceptor` (a `HandlerInterceptor`, registered by `K8sAutomationWebConfig` for `/api/eks/autoscaling/**`, following `DevOpsRateLimitConfig`/`RateLimitInterceptor`) runs before every handler on that path, for every HTTP method:

1. Principal must be a `ClientUserDetails`, else `401 K8S_UNAUTHENTICATED` (Spring Security already requires authentication for this path, `SecurityConfig.java:330`; this is fail-closed backup).
2. The cluster path variable (from `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`: `clusterName`, or `cluster`, which is the vertical engine's name for it in its contract §2, so the engine can join `GUARDED_PATHS` without renaming) must match the EKS name rule, else `400 K8S_INVALID_REQUEST`.
3. `accountId` query parameter must be 1-12 digits, else `400 K8S_INVALID_REQUEST`.
4. `K8sAutomationAccessService.resolve(user, accountId, clusterName)` finds the AWS `CloudAccount` rows for the padded and raw id (the column is unique, so at most one of each; two exist only where the leading-zero quirk created both), keeps those the user `canAccess` (`CloudAccountAccessService.java:196`), and looks up the cluster config: first by the account's DB id, then by the AWS account number for legacy rows (B4). Nothing reachable: `403 K8S_NO_ACCOUNT_ACCESS`, with the same message whether the account is unknown or not the caller's (no existence oracle, matching `CloudAccountAccessService` practice). The context's account is the row whose DB id owns the config. When no row owns it (a legacy config, or no config yet) and both rows are entitled, the write gates use the stricter of the two: read-only if either is read-only, not verified if either is not `CONNECTED`. The same account step, without the cluster, is `resolveAccounts(user, accountId)`, which the capabilities endpoint uses.

   **The caller's `accountId` is only ever matched against `CloudAccount.awsAccountId`; it is never used as a `cloud_account_id`.** Both config lookups use values taken from an entitled row (its DB id, then its AWS account number). Today the caller's number goes straight into `findByCloudAccountIdAndClusterName` (`EksAutoscalingService.findConfig:306-310`, and `PUT /enabled` at `EksAutoscalingController.java:323-324`, which skips even `findConfig`). On modern rows that column holds the DB primary key, so naming another tenant's `CloudAccount.id` reads or changes that tenant's config. After this change that request matches no AWS account and gets `403 K8S_NO_ACCOUNT_ACCESS`.
5. The resolved `K8sAutomationRequestContext(user, accounts, config)` is stored as a request attribute (`accounts` holds the entitled rows, the owning row first). Controllers read it with `@RequestAttribute` and never parse `accountId` again. `EksAutoscalingService.findConfig` and its cross-account fallback are deleted.
6. For `GET`/`HEAD`/`OPTIONS`, stop here: reads need only entitlement.
7. For mutations, `writeDecision(user, accounts)` applies, in this order: `DEMO` (user row's email), `NO_USER_PERMISSION` with detail `IMPERSONATION` (impersonated session), `NO_USER_PERMISSION` with detail `ROLE`, `NO_USER_PERMISSION` with detail `TAB_GRANT`, `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED` (the account gates over the rows chosen in step 4). The first failure returns `403` with its code. A null config returns `404 K8S_CLUSTER_NOT_REGISTERED`.
8. Audit logging. Every refusal the interceptor writes is logged in `preHandle` before it returns `false`, because Spring doesn't call `afterCompletion` for a handler whose `preHandle` returned `false`: one INFO line (WARN for `K8S_NO_ACCOUNT_ACCESS`) with user id, impersonated user id when present, account DB id when resolved, cluster, method, path, status and code. Every mutation that reaches its handler logs the same INFO line in `afterCompletion` with the final status. Refusals thrown inside handlers (readiness, locked guardrails, validation) are logged by the controller's exception handler with the same fields.

New endpoints added under this path later are covered by default; there is no per-method annotation to forget.

**Endpoint matrix.** The 16 dormant endpoints (`EksAutoscalingController.java:70-423`), all behind the interceptor. Paths are relative to `/api/eks/autoscaling`; every request needs `accountId`.

| # | Method and path | Kind | Gates | Readiness key |
|---|---|---|---|---|
| 1 | `GET /{c}` | read | entitlement | — |
| 2 | `GET /{c}/rightsizing` | read (no longer used by the UI, E7) | entitlement | — |
| 3 | `GET /{c}/rebalance-status` | read | entitlement | — |
| 4 | `GET /{c}/node-fleet` | read | entitlement | — |
| 5 | `GET /{c}/evictor-candidates` | read | entitlement | — |
| 6 | `PUT /{c}/enabled` | mutation | entitlement + write gates | — |
| 7 | `PUT /{c}/mode` | mutation | entitlement + write gates | — |
| 8 | `PUT /{c}/scale-out-policy` | mutation | entitlement + write gates | `scale-out` (refused) |
| 9 | `PUT /{c}/evictor-policy` | mutation | entitlement + write gates | `evictor-live` when `analyseOnly: false` |
| 10 | `PUT /{c}/workload-rightsizer` | mutation | entitlement + write gates | always refused (retired) |
| 11 | `PUT /{c}/safety-guards` | mutation | entitlement + write gates + locked guardrails | — |
| 12 | `PUT /{c}/node-optimizer` | mutation | entitlement + write gates | `node-optimizer-actions` when an action field is on |
| 13 | `PUT /{c}/rebalancer` | mutation | entitlement + write gates | `rebalancer` when `scheduleEnabled: true` |
| 14 | `POST /{c}/rebalance` | mutation | entitlement + write gates | `rebalancer` (refused) |
| 15 | `PUT /{c}/node-templates/{templateId}` | mutation | entitlement + write gates | `node-templates` (refused) |
| 16 | `DELETE /{c}/node-templates/{templateId}` | mutation | entitlement + write gates | `node-templates` (refused) |

Five reads, eleven mutations. The interceptor treats every non-`GET`/`HEAD`/`OPTIONS` method as a mutation, so the classification can't drift from the code.

**Scope boundary with the vertical engine.** The interceptor is registered for `/api/eks/autoscaling/**` only. `/api/eks/automation/vertical/**` belongs to the engine workflow, which is expected to enforce its own gates and return `403` with the same codes (orchestrator notification). This branch does not register anything on the engine's path, does not edit its packages, and does not change `SecurityConfig` (the engine's path is already authenticated by `anyRequest().authenticated()`, `SecurityConfig.java:334`). `K8sAutomationAccessService` and `K8sAutomationReason` are self-contained and public so the engine can call them instead of re-implementing the rules. The engine can satisfy R2 in either of two ways: add its path to `K8sAutomationWebConfig.GUARDED_PATHS` (the interceptor accepts its `{cluster}` path variable and `accountId` query parameter as they are), or call `resolve` and `writeDecision` itself. Its contract plans the second: endpoints 2 and 3 run the `writeDecision` order first, then readiness (contract §3), which is not yet code. **Until R1 and R2 are confirmed, the server-side guarantee in §6.8 covers `/api/eks/autoscaling/**` only, and the UI blocks every vertical write (D16).**

**Policy normalizer.** `K8sAutomationPolicyNormalizer` owns the policy bundle's shape and safety:

- *Write path*: read the stored bundle (tolerant parse, unknown properties ignored), fill it to completeness, validate the incoming section (readiness refusals first, then locked guardrails, then ranges, §13), **replace** that section with the incoming one (§6.5: the UI always sends the complete section; `criticalNamespaces: null` keeps the stored list), keep server-owned fields (rebalancer `status`, `lastRebalanceAt`, `lastRebalanceSavingsPercent`, `lastRebalanceDuration`), normalize again, persist. Every save therefore writes a complete bundle (orchestrator decision 9).
- *Serve path to the agent*: `AgentController.getAutoscalingPolicy` (`:737-805`) returns `normalizer.forAgent(stored)` instead of the raw JSON or the partial fallback at `:799`. Readiness is enforced on serve through one coercion table (§6.7): whatever the stored bundle says (older code, a direct DB edit, or an agent-written row from before the ingest guard), the served bundle carries the safe value for every key that isn't ready. The WARN naming the config id and the coerced fields is logged once per (config id, coerced-field set) per process, not on every 60 s poll; the seen-set is a concurrent set capped at 1,000 entries and cleared when full. An unparseable stored bundle is served as the complete default with `autoscalerMode: "disabled"` (agent idles) and an ERROR log, also once per config id.
- *Serve path to the UI*: `GET /{c}` returns `normalizer.forUi(stored)`, which is the same effective policy the agent receives, so the UI shows what is in force rather than what is stored. It is returned **whether or not node automation is enabled**: `autoscalingEnabled`, every policy section, and `policySource: "stored" | "default"` (`"default"` when no bundle has been saved yet). Today a disabled cluster gets a minimal object with no sections (E19), which leaves nothing to show or edit before the first enable. The UI labels default values "Default · not saved".
- *Ingest guard*: `AgentIngestService.processBatch` refuses collector names in `SERVER_OWNED_COLLECTORS = {"autoscaling-policy"}` with `BatchResult.error("autoscaling-policy is written by XamOps, not by agents")` and a WARN with the cluster config id, before anything is written (E21). No agent pushes that type (only the automation agent reads it; the XamOps agent doesn't reference it), so nothing legitimate breaks, and the policy the UI shows can't be replaced by whoever holds an agent key. The rest of the batch is processed as today.
- *Completeness*: every section non-null; `safetyGuards.criticalNamespaces` defaults to `kube-system, monitoring, ingress-nginx, xamops-system` (union of the Java and Go defaults) and always contains `kube-system` and `xamops-system`; the Java bundle gains the two sections the Go bundle already has: `hpaManagerPolicy {enabled: false, analyseOnly: true, defaultMinReplicas: 1, defaultMaxReplicas: 10, defaultTargetCpuPct: 80}` and `vpaManagerPolicy {enabled: true, analyseOnly: true, updateMode: "Off"}`. The VPA manager is the one controller measured accurate (9/9), and analyse-only relays existing VPA objects without creating any.
- *Go rightsizer retired*: the normalizer always serves `workloadRightsizerPolicy.enabled: false` and `applyMode: "deferred"`, and `PUT /{c}/workload-rightsizer` is refused with `403 K8S_CONTROLLER_NOT_READY` and the message "The automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling." Two vertical engines acting on one workload (one resizing in place, one patching templates) would fight each other, and the Go rightsizer is measured unsafe (E4).
- *Enable*: `PUT /{c}/enabled {enabled: true}` persists the normalized default bundle when none exists, with `autoscalerMode: "full"`, the Go rightsizer off, evictor `analyseOnly: true`, node-optimizer actions off and rebalancer `status: "idle"`. The agent's first fetch is complete and analysis-only.

**Refusal format.** All refusals on this path and the capabilities endpoint use one body:

```json
{ "success": false, "code": "K8S_READ_ONLY_CONNECTION", "message": "Read-only connection. Automation changes need read-write access.", "field": null }
```

`K8sAutomationRefusedException(HttpStatus, K8sAutomationReason, message, field)` carries it; a controller-local `@ExceptionHandler` in `EksAutoscalingController` and `K8sCapabilitiesController` maps it, the same pattern AutoSpotting uses (`AutoSpottingBlockedException.java:5-13`). The interceptor writes the same JSON directly. `e.getMessage()` is no longer echoed to clients: not by the controller (today at `EksAutoscalingController.java:93,116,135…`), and not by `EksAutoscalingService.getRebalanceStatus`, whose parse-failure branch returns `{"status": "unknown", "error": e.getMessage()}` in a `200` body today (`:791`). Jackson's message there can quote the stored JSON. That branch returns `{"status": "unknown"}` and logs the message at WARN with the config id.

Some failures happen before the handler body runs, during argument binding, and today land in `GlobalExceptionHandler`, which returns `500` with `"An unexpected error occurred: " + ex.getMessage()` (`GlobalExceptionHandler.java:67-74`). Controller-local handlers take precedence over `@ControllerAdvice`, so both controllers add:

| Exception | Response |
|---|---|
| `HttpMessageNotReadableException` (malformed JSON, wrong JSON type) | `400 K8S_INVALID_POLICY`, `field` set from Jackson's path when it has one, fixed message "The request body isn't valid for this setting." |
| `MethodArgumentTypeMismatchException` | `400 K8S_INVALID_POLICY`, `field` = parameter name |
| `MissingServletRequestParameterException` | `400 K8S_INVALID_REQUEST`, `field` = parameter name |
| `K8sAutomationRefusedException` | its status, code, message and field |
| any other `Exception` | `500 K8S_INTERNAL`, fixed message "Couldn't save the change. Try again." for mutations and "Couldn't load automation data." for reads; ERROR log with the stack |

All 400s log at WARN with the field and never echo the exception message.

### 6.7 Controller readiness

Readiness is backend configuration, so a control turns on when its controller is fixed, with no UI change.

```properties
# application.properties (defaults; absent property = not ready)
xamops.k8s.automation.readiness.vertical-rightsizing=false
xamops.k8s.automation.readiness.hpa-apply=false
xamops.k8s.automation.readiness.vpa-apply=false
xamops.k8s.automation.readiness.evictor-live=false
xamops.k8s.automation.readiness.rebalancer=false
xamops.k8s.automation.readiness.node-optimizer-actions=false
xamops.k8s.automation.readiness.scale-out=false
xamops.k8s.automation.readiness.node-templates=false
```

Bound by `K8sAutomationReadinessProperties` (`@ConfigurationProperties(prefix = "xamops.k8s.automation.readiness")`, primitive `false` defaults). The user-facing message for each key is a constant in code, reviewed with the copy, not configuration:

| Key | Message |
|---|---|
| `vertical-rightsizing` | "Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. Recommendations stay visible." The contract refuses every vertical write while this key is false (§6.3), so the message covers modes and settings, not only Auto and apply |
| `hpa-apply` | "Automated HPA changes are disabled until the HPA manager passes safety validation." |
| `vpa-apply` | "XamOps doesn't create or update VPA objects yet." |
| `evictor-live` | "Live consolidation is disabled until pod-fit simulation is available." (reason `ANALYSE_ONLY`) |
| `rebalancer` | "Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly." |
| `node-optimizer-actions` | "Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation." |
| `scale-out` | "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does." |
| `node-templates` | "Not available: the automation agent doesn't use node templates yet." |

The retired Go rightsizer has no key. Its refusal ("The automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling.") and its serve-time coercion are unconditional code, so no property can bring back a second vertical engine. Un-retiring it would need code and UI changes anyway.

**Serve-time coercion table.** One table in `K8sAutomationPolicyNormalizer`, applied by both `forAgent` and `forUi`. For every key that is not ready, the served bundle carries these values regardless of what is stored. Several sections have no write endpoint (`hpaManagerPolicy`, `vpaManagerPolicy`), and a stored row can predate the normalizer, so this table, not the write path, is the kill switch. Field names are the Go agent's (`xamops-autoscaler/internal/policy/types.go`); the consuming lines are in `cmd/autoscaler/main.go`.

| Key (when not ready) | Served value | Agent behaviour it neutralises |
|---|---|---|
| Go rightsizer (always, retired) | `workloadRightsizerPolicy.enabled = false`, `applyMode = "deferred"` | rightsizer run and template patches (`:230`) |
| `evictor-live` | `evictorPolicy.analyseOnly = true` | live consolidation drains (`evictor.go:131`) |
| `hpa-apply` | `hpaManagerPolicy.analyseOnly = true` | None in the current agent: `:255-256` only analyses, and `HPAManager.Apply` (`internal/controller/hpa_manager.go:173`) is never called. Guards future apply paths (R6) |
| `vpa-apply` | `vpaManagerPolicy.analyseOnly = true`, `updateMode = "Off"` | None in the current agent: `:271-272` only collects, and `VPAManager.Apply` (`internal/controller/vpa_manager.go:152`) is never called. Guards future apply paths (R6) |
| `node-optimizer-actions` | `nodeOptimizerPolicy.autoCordonIdleNodes = false`, `autoDrainIdleNodes = false`, `spotRatioTargetPct = 0` | idle cordon (`:300`), idle drain (`:320`), spot-ratio drains and the Karpenter patch inside that branch (`:361-374`) |
| `rebalancer` | `rebalancer.status = "idle"`, `scheduleEnabled = false`, `enabled = false` | the `status == "running"` bypass (`:163`); `enabled = false` also closes the AND-gate the autoscaler-safety branch adds (R6) |
| always (locked guardrail) | `nodeOptimizerPolicy.maxParallelDrains = 1`; `safetyGuards.respectPodDisruptionBudgets = true`, `protectCriticalNamespaces = true`; `criticalNamespaces` ⊇ {`kube-system`, `xamops-system`} | drain fan-out and guard loosening |
| `scale-out`, `node-templates` | served unchanged | none: no Go controller reads them; refused on write instead |
| `vertical-rightsizing` | nothing in this bundle | the engine binds the property itself (engine plan D9); its endpoints refusing on it is R1 |

Spot interruption handling (`handleSpotInterruptions`, `:416`) is deliberately absent from the table (§6.3). Each row has a unit test that stores a bundle violating it and asserts the served value (§17).

When a key is true, the matching refusal and serve-time coercion disappear and the control's ready-state UI (confirm dialog with impact text, §9) is already built. Flipping a key needs evidence, not just a config change:

- `rebalancer`: F2 (controller fixes) **and** F17 (CSRF posture) must be resolved first. `POST /rebalance` can be triggered by a cross-site form post today, because CSRF is disabled (`SecurityConfig.java:156`) and production cookies are `SameSite=None` (`application-prod.properties:80`).
- `vertical-rightsizing`: the engine's live verdict, R1 and R2 confirmed, **and** F17 resolved, for the same reason (the engine's apply is a `POST`).
- Every other key: a passing live verdict for that controller (R6).

`vertical-rightsizing` is defined here and served by capabilities, but the refusal on the engine's own endpoints belongs to the engine. Both sides must read the same property name, so one flag flips both the UI and the server (R1). The UI can only ever be stricter than the server, so UI-side gating is not a guarantee for API callers. That is why R1 and R2 are release criteria for the vertical write controls, and why the adapter blocks every vertical write until they are confirmed (D16).

### 6.8 Invariants and who owns them

| Invariant | Owner | Why that layer |
|---|---|---|
| Only entitled users read or change a cluster's automation data | Server interceptor | Security; the UI cannot be trusted. |
| Read-only and unverified connections cannot change automation | Server on `/api/eks/autoscaling/**`, mirrored by UI. On the engine's path: the engine (R2; its contract plans the same order, but its write endpoints have no handler yet); until R2 is confirmed in code, UI only, which is why the UI blocks every vertical write meanwhile (D16) | Consent; must hold for API callers. |
| Users without the permission, demo identities (by email) and impersonated sessions cannot change automation | Same split as the row above | Security; demo credentials are shared and skip OTP (E12); impersonation would otherwise act with the customer's role (E17). |
| Only XamOps writes the policy row | Server ingest guard (`SERVER_OWNED_COLLECTORS`) | An agent key must not be able to replace the policy the UI shows and the agent receives (E21). |
| No not-ready controller can be enabled | Server on write (refuse) and on serve (coerce, one table, §6.7) | The rebalancer obeys the server, not its env flag (E3); the server is the kill switch, including for sections that have no write endpoint. |
| Served policy bundles are complete | Server normalizer | Older agent images zero missing guards (E5). |
| Rebalance status is server-owned | Server normalizer | A config save must never start a drain. |
| `AGENT_NOT_INSTALLED` (`AUTOMATION_AGENT`, `XAMOPS_AGENT`) disables node-automation controls | UI only | UX, not safety: storing a policy before the agent is installed is harmless, and the agent cannot fetch any policy until automation is enabled (`AgentController.java:787-791`). Enforcing it on the server would add a coupling with no safety gain. |
| A vertical change needs an execution path | Vertical engine (refuses an apply with no path, contract §3.2), mirrored by UI (`EXECUTION_PATH`, §6.2) | The engine owns how it reaches the cluster; the UI only explains it. |
| An apply is never shown as done before the engine reports an outcome | UI (`useVerticalRightsizer`, §11.2) | The apply is asynchronous and at-least-once (contract §3.2): a `202` is a submission, not a result. Showing it as success would repeat the B8 pattern. |
| Only one vertical engine acts on a workload | Server normalizer (serves the Go rightsizer disabled) | The Java engine is the product's vertical engine (E16); the Go agent must never patch the same workloads. |
| Vertical engine gates (access, metrics, history, GitOps, readiness on its endpoints) | Vertical engine, mirrored by UI | Its endpoints, its ownership boundary. The UI unions its reasons with capabilities, which can only make the UI stricter. |
| No vertical write is offered before the engine's server-side checks are confirmed | UI adapter (`VERTICAL_WRITES_CONFIRMED = false`) | The engine's write endpoints have no handler yet (contract §1); offering controls whose server side is unverified would make the UI the only gate (D16). |
| A restarting apply method is never chosen implicitly | UI (opt-in dialog) and engine (refuses persist on GitOps-managed workloads) | Restarting every pod is a different risk class from an in-place resize. |
| No fabricated numbers | UI derivations, unit-tested | Presentation rules over data the backend already sends. |
| Reason codes match on both sides | Contract test over a shared fixture (§17) | One table on each side; drift fails the test of whichever side's CI job runs (§17 states the limit). The engine adds no new gate codes (contract §5.1); its enum joins the fixture after both branches land (R3). |

---

## 7. Decisions

### D1. Connection access level: use `CloudAccount.accessType`; the remedy goes through support

A structured field exists, so nothing is inferred from role names. `CloudAccount.accessType` is set at onboarding and is the gate for every existing write path (§6.2). The UI already has it as `Account.access` from `AccountManagerController.mapToAccountDto:530-570` (`AccountDto.access`).

The brief assumed the change-set path upgrades read-only to read-write. It cannot, and a second connection cannot either (E11):

- `StackUpdateService.templateUrlFor:353-363` chooses the template for the account's *current* access type and its Javadoc says "never a read-write template on a read-only account"; the read-only and read-write stacks create differently named roles, and the service refuses changes that replace a connection role (`CONNECTION_ROLES`, `:93`).
- `CloudAccount.awsAccountId` is unique (`CloudAccount.java:24-25`). Add Account (`AwsAccountService.generateCloudFormationUrl:145-233`) creates a new `CloudAccount` row, so a read-write connection for an AWS account that is already connected fails when it is saved or verified.
- The only self-service path is to remove the connection in Account Manager and connect the account again with read-write. Removal deletes that connection's data in XamOps: cluster registrations and agent data, team members' access grants and financial records (`AwsAccountService.deleteAccountById:511-548`).

So:

- `READ_ONLY_CONNECTION` remedy: "Get read-write access" opens a short explainer in the Enable automation panel (§8.2): "XamOps can't switch a read-only connection to read-write in place. Ask XamOps support to switch it. Doing it yourself means removing this connection and connecting the account again, which deletes its history in XamOps. Recommendations stay visible meanwhile." Primary action "Contact support" → `/tickets` (the existing XamOps ticket page); secondary "Open Account Manager" → `/account-manager`. The UI never links straight to Add Account for this reason, because that path fails for a connected account.
- `MISSING_AWS_PERMISSION` remedy: "Update connection" → `/account-manager`, where `StackUpdateModal` (`components/AccountManager/StackUpdateModal.tsx`) runs the preview/execute flow. This is what the change-set path is for: bringing an older stack onto the current template (for example, adding `XamOpsPolicyEksFleetRead` from Phase 0).

Alternatives rejected: inferring from `roleArn` (`XamOpsReadOnlyRole` vs `XamOpsReadWriteRole`), because a structured field exists and environment suffixes (`-local`, `-uat`) make string matching brittle; building an in-place upgrade, because it is a CloudFormation and account-model change outside this scope (F11).

### D2. User permission: one new permission key, evaluated on the server

What exists: `User.role` is coarse (all XamOps customer users are `ROLE_XAMOPS`); `user_tab_access` is a per-user whitelist of sidebar keys where "no rows means no restriction" (`UserTabAccess.java:9-14`, `xamopsTabs.ts:1-13`); `user_cloud_account_access` is a per-user account whitelist enforced by `CloudAccountAccessService.canAccess`; there is no read/write distinction anywhere.

Decision: add the permission key `cloudops-cloudk8s-automation`, stored in `user_tab_access` like a tab key but **not** listed as a sidebar tab in `XAMOPS_MODULES` (see "Where the key lives" below). A user may change automation when all of these hold, checked in this order:

1. `canAccess(user, account)` is true (already required to see the cluster at all);
2. the user row, loaded by the principal's id, has an email outside `DEMO_EMAILS` (else `DEMO`). The username can't be used: the demo principals' usernames are "Xammer Admin" and "Xammer BillOps" (E12), so a username check would never match and the shared, OTP-free demo identity would pass every gate as an ordinary user of the first client;
3. the request is not an impersonated session: `ImpersonationContext.getImpersonatedUserId()` is null (else `NO_USER_PERMISSION`, detail `IMPERSONATION`). Both impersonation paths make the principal the target user (E17), so without this rule a BillOps admin could change a customer's automation by signing in as that customer, which rule 4 exists to prevent. This holds until the owner answers open question 1;
4. the user row's role, normalised to carry the `ROLE_` prefix (as `ImpersonationFilter.java:54` does; the DB can hold `"USER"`), is a XamOps-product role: `ROLE_XAMOPS`, `ROLE_XAMOPS_ADMIN`, `ROLE_SUPER_ADMIN`, `ROLE_ADMIN` or `ROLE_USER` (else `NO_USER_PERMISSION`, detail `ROLE`). BillOps-only principals (`ROLE_BILLOPS`, `ROLE_BILLOPS_ADMIN`, `ROLE_BILLOPS_ADMIN_SUB`) and unknown roles are refused: billing staff can reach every account through `canAccess`, and changing a customer's cluster automation is not a billing task;
5. the user has no `user_tab_access` rows (unrestricted), or has both `cloudops-cloudk8s` and `cloudops-cloudk8s-automation` (else `NO_USER_PERMISSION`, detail `TAB_GRANT`).

Why this shape: no schema change; the grant is managed in the two screens admins already use (My Team in `MyProfilePage.tsx:444` and the BillOps user manager in `BillOpsAdminUserManagerPage.tsx:261`, both render `XAMOPS_MODULES`); the server stores any key string (`TeamUserService.java:219-222, 304-310`; BillOps `AppUserService.java:133-138, 238-243`) and enforces the rule with an existing repository method. The `xamopsTabs.ts` comment warns that a new key is "fail-OPEN for a user with no rows, fail-CLOSED for a user with any". That is the right behaviour for a write permission: unrestricted users keep the platform's "no rows, no restriction" contract, and restricted users, who never had automation (the UI was dormant), must be granted it explicitly. Unlike `secops-xamlens`, no migration grants it.

**Where the key lives.** If the key were a tab inside the CloudOps module, it would be granted silently by "Select all" (`MyProfilePage.tsx:435` and `BillOpsAdminUserManagerPage.tsx:242`, both `[...ALL_XAMOPS_TAB_KEYS]`) and by the CloudOps module checkbox (`MyProfilePage.tsx:447-453`). An admin restricting a member's sidebar would hand out a write permission without seeing it. So:

- `constants/xamopsTabs.ts` exports `XAMOPS_TAB_PERMISSIONS = [{ key: 'cloudops-cloudk8s-automation', parent: 'cloudops-cloudk8s', label: 'Allow automation changes' }]`. It is not in `XAMOPS_MODULES`, so `ALL_XAMOPS_TAB_KEYS`, "Select all", module toggles and `makeXamTabGate` never include it.
- Both pickers render it as a nested checkbox under the CloudK8s tab row, shown only while CloudK8s is checked, with the hint "Lets this member change automation settings. Recommendations are visible without it."
- `normalizeAllowedTabs(keys)` (same file) drops a permission key whose parent tab is absent. Both pickers apply it whenever `allowedTabs` changes, so unchecking CloudK8s, its module or everything also removes the permission. This also prevents a member whose only row is the permission key, which `makeXamTabGate` would treat as "restricted to nothing" (`xamopsTabs.ts:151-162`).
- When no tab is selected (an unrestricted member), the section shows the line "Unrestricted members can change CloudK8s automation." so the platform's no-rows rule is visible where it applies.

Knock-on changes: because the key is not a sidebar tab, `FeatureFaqCatalog` needs no new entry, and `FeatureFaqCatalogTest` stays as is: it asserts the catalog equals the sidebar tab keys plus module keys (`containsExactlyInAnyOrderElementsOf`, `FeatureFaqCatalogTest.java:43-50`). The `cloudops-cloudk8s` description is rewritten for the new sections and states that automation changes need read-write access and the permission. Its keywords stay as they are: `rightsizing` is already a keyword of the FinOps Rightsizing and EBS entries (`FeatureFaqCatalog.java:49-56`), and adding it or `automation` would pull general questions away from them in `FeatureFaqMatcher`. The Sidebar is unaffected because it gates hardcoded keys (`Sidebar.tsx:385`).

Alternatives rejected: "primary users only" (no way to grant a team member); a new boolean column on `users` (schema migration plus new admin UI); a new role (roles are coarse and shared with BillOps).

The rule is computed once, on the server, and served by capabilities. The UI never re-implements it.

### D3. Container Insights: exact match on the existing call, labelled "Configured"; freshness deferred

Decision: make the existing check correct and label it honestly, with no new AWS call. The freshness check is follow-up F19.

A freshness check would be contained, since every role template already grants `logs:DescribeLogStreams` (`xamops-readonly-role.yml:365`, `xamops-readwrite-role.yml:359`, and the uat, live and local variants). It is still not taken here, for three reasons:

- It adds a new AWS API call per cluster on the cluster-list path. This work's scope rule is no new AWS calls, and F1 and F7 are deferred for exactly that reason.
- Inside CloudK8s, Container Insights feeds only a badge and a Data sources row. The XamOps agent is the working data path.
- CloudWatch Logs documents `lastEventTimestamp` and `lastIngestionTime` as eventually consistent ("typically less than an hour"). A 15-minute window would mark healthy clusters stale, and one measured pair (7.5 vs 20.2 min, harness §8) is not enough to set thresholds.

`EksService.checkContainerInsightsStatus` (`:200-212`) becomes:

1. Keep the existing `DescribeLogGroups(logGroupNamePrefix=/aws/containerinsights/{cluster}/performance)` call, and require an exact `logGroupName` match in its response, which today is discarded (`:205`). Match → `CONFIGURED`; no match → `NOT_CONFIGURED`. The first page suffices: the prefix is the full group name, so an exact match sorts first.
2. AccessDenied → `UNKNOWN` with `reason: "MISSING_AWS_PERMISSION"` and `action: "logs:DescribeLogGroups"`; the permission interceptor records the gap automatically. Throttling or other errors → `UNKNOWN` without a reason, WARN log.

Labels: `CONFIGURED` reads "Configured · data freshness not checked", with the tooltip "The log group exists. XamOps doesn't check yet whether Container Insights is sending data to it." `NOT_CONFIGURED` reads "Not set up". `UNKNOWN` reads "Unknown", plus the "Update connection" remedy when the reason is set. None of the three counts toward Needs attention: Container Insights is optional.

This does not detect the OTEL case the harness measured (group exists, data stale). The label stops claiming otherwise: it says the group is configured and that freshness isn't checked, which is exactly what XamOps knows.

`K8sClusterInfo` keeps `connected` (now `status == CONFIGURED`: the meaning it always implied, now actually checked) and adds `containerInsights: { status, checkedAt, reason, action }`. The list is cached in Redis for an hour (`EksService.java:154`), hence `checkedAt`; Refresh forces a recheck. Spring's ObjectMapper ignores unknown properties, so old cache entries and new ones interoperate. `AwsPermissionCatalog.java:119` adds `KUBERNETES` to the `logs` features, for the same reason Phase 0 added it to `cloudwatch` (`:114-118`): without it, a feature-scoped gap query drops the denial.

If the owner explicitly allows the new call, F19 adds `DescribeLogStreams` and the `RECEIVING` / `STALE` / `NOT_RECEIVING` states, with this section cited as the allowance. `STALE` stays out of Needs attention until its thresholds are validated on more clusters.

### D4. Controller readiness is a server gate, default not ready

Adopted from the orchestrator updates. Detailed in §6.7. Allowed today, within the write gates: viewing everything, including vertical-engine recommendations; node automation on/off and scope; evictor analysis settings; node-optimizer thresholds and spot interruption handling; guardrail edits within the locks; the VPA baseline (collected; display is F4). Built but blocked until R1 and R2 are confirmed (D16), and after that until `vertical-rightsizing` is ready, because the published contract refuses every policy write while it isn't (§6.3; R10 may lift that for Off and Recommend): per-workload modes, headroom, percentile, window and apply method on the vertical engine. Not ready: vertical Auto, Simulate and Apply now (`vertical-rightsizing`), and every Go-agent live or apply control.

### D5. Modes: each engine's own values, no invented modes

**Workload autoscaling (vertical engine).** Modes are the engine's per-workload values, shown as is: Off (`OFF`), Recommend (`RECOMMEND`), Auto (`AUTO`). Apply methods: "In place, no restart" (`IN_PLACE`, default) and "In place and persist (restarts pods)" (`IN_PLACE_AND_PERSIST`). Per-workload controls exist because the engine's policy model is per workload (`PUT …/workloads/{ns}/{kind}/{name}/policy`). The published contract has no cluster-level default policy: a workload without a row uses the engine's defaults (mode `OFF`, headroom 25%, p95, 24h, `IN_PLACE`; contract §3.1), reported as `provenance.policySource: "DEFAULTS"` and labelled "Default · not saved" in the drawer.

**Node automation (Go agent).** The backend mode values stay `full | scoped | disabled` (`EksAutoscalingController.java:267`). Their real semantics, from the agent: `disabled` makes the agent idle (`main.go:154`); `scoped` disables only the evictor (`main.go:201-203`); `full` runs every controller that is enabled in both the policy and the agent's env flags. The UI shows:

- **Node automation** switch → `autoscalingEnabled` (`PUT /enabled`). Off means the agent receives no policy (`AgentController.java:787-791`).
- **Scope** → mode: "Full" (`full`), "No consolidation" (`scoped`), "Paused" (`disabled`).
- **Apply behaviour** per controller → existing fields: evictor `analyseOnly`, node-optimizer action flags. The Go rightsizer's `applyMode` is no longer shown (retired).

The fleet's labels are derived, never stored; within each line the first matching row wins:

| Line | Derived state | Rule |
|---|---|---|
| Workloads | Not available | V7 (engine switched off) or V6 (not deployed) |
| | Not registered | V8 (`404 K8S_CLUSTER_NOT_REGISTERED`) |
| | No execution path | V0 (`executionPath: "NONE"`) |
| | No metrics | V2 (`NO_METRICS_SOURCE`) |
| | Collecting history | V3 (`INSUFFICIENT_HISTORY`) |
| | Off / Recommend / Auto (counts) | otherwise: workloads per mode, from the engine |
| Nodes | Not installed | automation agent never seen |
| | Off | `autoscalingEnabled` false, or mode `disabled` |
| | Analysis | enabled, mode `full`/`scoped`, evictor `analyseOnly: true`, node-optimizer actions off |
| | Automated | enabled and any live field on (unreachable while readiness holds, but the mapping is ready) |

The Go agent's `DRY_RUN` and `ENABLE_*` env flags are not reported to XamOps (`pusher.go:70-85` sends version only). The Overview says so: "Dry run: not reported by the automation agent (it ships with dry run on)". The vertical engine carries `dryRun` on every submission and every action entry, mandatory in both directions (contract §3.2, §4), so its activity is labelled exactly.

The Go policy has no per-workload scope (`AutomationPolicyBundle` has none; nor does `policy/types.go`). Node automation's only scoping lever stays protected namespaces in Guardrails.

### D6. Tenant scoping through one resolver

Detailed in §6.6. Two limitations are documented rather than solved. First, legacy configs keyed by the AWS account number (B4) are visible to every client that connected that AWS account; they all have legitimate access to it, and B4's migration (F6) removes the case. Second, configs are matched by cluster name with region `"auto"` (B5), so two clusters with the same name in different regions of one account share capability rows (F6).

### D7. Rebalancing is blocked end to end

The trigger is the most dangerous control in the product (E3). UI: "Rebalance now" and the schedule are disabled with the `rebalancer` message. Server: `POST /rebalance` returns `403 K8S_CONTROLLER_NOT_READY`; `PUT /rebalancer` refuses `scheduleEnabled: true` and ignores client values for server-owned fields; the normalizer forces `status: "idle"` on every save and every serve while `rebalancer` is not ready. That last point is what makes the in-cluster env flag irrelevant: the agent can no longer receive `running`.

### D8. Recommendations come from the vertical engine; labelled, flagged, never clamped

Workload autoscaling, the Workloads table's recommendation column and the potential-savings figures all read the vertical engine (`GET /api/eks/automation/vertical/{c}`, published contract). The old sources are no longer read by the UI:

| Source | Status in the UI | Why |
|---|---|---|
| Java vertical rightsizer | **Used.** Label "XamOps vertical rightsizer · p`<percentile>` over `<window>`, `<headroom>`% headroom", extended from `provenance` when present with "`<sampleCoverage>`% sample coverage · usage from `<usageSource>`". Quality line until `vertical-rightsizing` is ready: "Not yet validated on live clusters." | Owner decision (E16). |
| `GET /{c}/rightsizing` (Go rightsizer rows, or the backend snapshot engine as fallback, `EksAutoscalingService.getRightsizingData:591-625`) | **Not read.** The endpoint remains, tenant-scoped, for API compatibility. | The Go rightsizer is measured unsafe and retired (E4, E16). The snapshot engine was never scored and returns nothing on agent clusters (E7). Showing a second, conflicting recommendation per workload would be noise. |
| Upstream VPA (`vpa-recommendations`, measured 9/9) | **Follow-up F4.** Stored by the generic ingest, served by no endpoint. When served, it appears as a "VPA target" column beside the engine's recommendation. | Orchestrator decision 4. |

Row flags, computed client-side as a second line of defence, each with a tooltip and none of them altering the engine's numbers:

- **Below safe floor**: a recommended decrease to below 25m CPU or 250Mi memory (upstream VPA minimums, harness §1). The engine clamps decreases at exactly these floors and reports `FLOOR_CLAMPED` (engine `WorkloadPolicyDefaults`), so the flag should never fire; if it does, the row is flagged and no savings are computed from it.
- **Engine findings and reasons**: findings (`REQUESTS_UNSET`, `IDLE`, `THROTTLED`, `OOM_RISK`, `HPA_COEXISTENCE`, `NODE_CAPACITY_CAPPED`) render as chips, and container reasons as a second line, with the fixed copy in §9.2. Unknown values render as their code in plain text (contract §5.2: the list can grow). `REQUESTS_UNSET` rows never contribute savings; the engine marks their recommendation advisory.
- **HPA conflict**: the engine freezes the request that a resource-metric HPA scales on (`HPA_CPU_MANAGED`, `HPA_MEMORY_MANAGED`, finding `HPA_COEXISTENCE`; engine plan §4.8). As a second line of defence the UI checks `k8sHpas` (`targetKind`/`targetName`, `cpuTargetPct > 0`): a workload whose HPA scales on CPU but whose recommendation changes the CPU request without `HPA_CPU_MANAGED` shows "HPA `<name>` scales on CPU at `<n>`% of requests. Changing requests moves its scaling point." No savings are computed for it.
- **Skipped**: the engine's `skipReason` is the status ("Skipped: protected namespace", "Skipped: single replica", "Skipped: rollout in progress", "Skipped: locked by annotation", "Skipped: HPA activity"; `INSUFFICIENT_HISTORY` reads "Collecting history" and `POLICY_OFF` "Off · not evaluated", §9.2).

Values are shown exactly as the engine returns them; nothing is clamped or rounded beyond unit formatting.

### D9. Cost and savings: named sources only

**Cluster cost source**, first match wins, shown beside every cost figure:

1. `openCost.enabled` and `openCost.summary.totalDailyCost > 0` → "OpenCost (agent)".
2. `agentCostMetrics.totalHourlyCost > 0` → "Cost exporter (agent)". The agent reads `sum(node_total_hourly_cost)` or `opencost:cluster:hourly_cost` (`prometheus_metrics.go:221-233`).
3. `costMetrics.dailyCost > 0` otherwise → "XamOps cost exporter (Prometheus)". On this path the value is the exporter metric the backend queries in `PrometheusService.getClusterDailyCost:35-38`.
4. Otherwise "No cost source", value "—", remedy "Install OpenCost" (Data sources › Install).

Monthly figures use the backend's `monthlyCost` when present, else daily × 30, the backend's own convention (`EksDashboardService.java:650-651`); the tooltip says "Monthly = daily × 30".

**Namespace and workload cost**, first match wins:

1. `openCost.enabled === true` and the list is non-empty → "OpenCost" (`populateCostAllocationsFromOpenCost`, `EksDashboardService.java:1279-1426`).
2. The list is non-empty otherwise → "Estimated", method "The cost exporter's daily total, split by each namespace's (or workload's) CPU and memory usage". Only the Prometheus path fills it this way (`buildNamespaceCostAllocation:1078`, `buildWorkloadCostAllocation:1136`, set at `:1066-1069`).
3. Otherwise **Estimated** from requests: for each pod, `max(request, usage-if-reported)` CPU cores × `CPU_RATE_PER_VCPU_HOUR` + memory GiB × `MEMORY_RATE_PER_GIB_HOUR`, × 24 for daily. Requests come from pod containers, which the agent reports exactly (harness §6). Today's fallback multiplies *usage* by the rates (`EksCostTab.tsx:68-69,102,114,126`), and agent pods carry no usage, so it shows $0. `max(request, usage)` is OpenCost's allocation basis.

**Node cost**: OpenCost node allocations when present. Otherwise the agent path's split of the cluster total by CPU capacity (`EksDashboardService.java:691-719`), labelled "Allocated by CPU capacity". That path hardcodes `capacityType: "on-demand"` (`:707`), so capacity type always comes from `k8sNodes`.

**Rates module**: `frontend-app/src/utils/k8sPricing.ts` is the only place in the CloudK8s code where the two constants live: `CPU_RATE_PER_VCPU_HOUR = 0.0416`, `MEMORY_RATE_PER_GIB_HOUR = 0.00456`, a `RATE_PROVENANCE` string ("Origin not documented in code; not region- or Spot-aware") and `estimateCost(cpuCores, memGiB, hours)`. The provenance claims nothing more, because nothing more is known: 0.0416 is also exactly the t3.medium hourly price (`CloudSitterDashboard.tsx:27`), so it may not be a per-vCPU rate at all. Every Estimated figure's tooltip shows the method, both rates and that sentence. A future pricing engine replaces this module.

**Unallocated capacity cost** replaces "potential savings" in the cluster cost view (`useEksDashboard.ts:456-470` is deleted). It is shown only when the cost exporter reports the CPU/RAM split (`agentCostMetrics.cpuHourlyCost` and `memoryHourlyCost`) and requests and allocatable are known: `cpuHourly × (1 − cpuRequested/cpuAllocatable) + memHourly × (1 − memRequested/memAllocatable)`, × 24. Tooltip: "Cost of CPU and memory no pod has requested (OpenCost-style idle). Source: cost exporter + pod requests." The old heuristic treated unreported node usage as 0% and so counted whole nodes as waste.

**Potential savings** appears only as "Estimated", from vertical-engine recommendations that are eligible: a container whose current requests are set (non-null), a non-null `recommended` at or above the safe floors, no HPA conflict, no `skipReason`, no `REQUESTS_UNSET`, and a replica count known (the engine's `replicas`, else `k8sDeployments`/`k8sStatefulSets`). Per container: `max(0, currentCpuM − recCpuM) / 1000` cores × CPU rate + `max(0, currentMemMi − recMemMi) / 1024` GiB × memory rate, × replicas × 24 × 30. A container with `recommended: null` ("no change recommended") contributes nothing. Increases are not netted against decreases; they are listed separately as "Needs more" so a throttled workload is not hidden by savings elsewhere. Tooltip: "Estimated from N recommendations (XamOps vertical rightsizer, not yet validated) × reference rates. Savings are realised only when nodes are removed. M excluded: below safe floor, requests unset, HPA conflict, skipped." No realised-savings figure is shown anywhere; there is no ledger (F10). The engine's actions feed records what was applied, which is the input a future ledger needs. **Never rendered**, because nothing measures them: `kpis.costSavedThisWeek` and `kpis.nodesSavedToday` (the observer agent always sends 0, `autoscaling_metrics.go:22-30`); the rebalance status's `savingsPercent` (the automation agent builds `RebalanceStatus` without it, `main.go:168-175`); and the policy's `rebalancer.lastRebalanceSavingsPercent` (server-owned, and nothing calls `setLastRebalanceSavingsPercent`). The frontend types omit all four and a test asserts they never appear in rendered output.

Deleted outright: the namespace "high cost" recommendation (`EksCostTab.tsx:150`), the Karpenter recommendation (`:158`), the "idle workloads" recommendation (`:172-186`, which flags every workload whose usage is unreported), and `quickWins` (`useEksDashboard.ts:472-510`).

### D10. Health is rule-based signals, not a score

`clusterHealthScore`, `resourceScore`, `costScore`, `securityScore`, `healthScoreLabel`/`Color` and `clusterHealth` are deleted from `useEksDashboard.ts:310-454`, and `workloadHealth.clusterHealthScore` is no longer read. The backend keeps serving it, including the `100.0` default for a cluster with no resources (`EksDashboardService.java:1692`): the field is retained in the API for compatibility and never rendered by the EKS UI. The DTO is shared with `AgentK8sDashboardMapper`, which computes the score from pod counts (`:169`), and dashboard-service changes are outside this branch's allowed list. The M0 fixture test asserts the UI ignores the value; removing the default is F21. Health becomes a list of signals, each from data with its own count:

| Signal | Rule | Attention when |
|---|---|---|
| Nodes ready | `k8sNodes` with condition Ready=True / total | any not ready |
| Node pressure | Memory, Disk or PID pressure True | any |
| Pods | running / total from `clusterOverview`; failed; CrashLoopBackOff containers (`waitingReason`) | failed > 0 or crash-looping > 0 |
| Unschedulable | pods with `unschedulable: true` | any (informational only when Pending but schedulable) |
| Vulnerabilities | Trivy critical / high | shown separately as a security signal, not part of health |

Overall status: **Healthy** (no attention signals), **Needs attention (n)**, **Data stale** (the XamOps agent is stale; last-known values stay visible with a stale marker), or **Not monitored**.

**Not monitored** applies when capabilities report no XamOps agent data for the cluster, or, if capabilities failed, when the dashboard has no `k8sNodes` and `clusterOverview.totalNodes` is 0. It replaces the section content with one panel: "This cluster isn't monitored yet", what XamOps would show, and "Set up monitoring" (opens the guide's Quick Install). It never renders zeros as values.

**Unknown is not zero.** New code treats a missing metric as `null` and renders "—" with "Not reported by `<source>`". The quantity parsers moved to `utils/k8sQuantities.ts` return `null` for missing or unparseable input (today's `parseCpuMillicores`/`parseMemoryMiB`, `useEksDashboard.ts:13-35`, return 0, which is how unknown became zero). Request sums treat an unset request as 0 explicitly, since it reserves nothing, and count those containers separately for the "Requests unset" flag. Usage and capacity callers keep `null`. The move also corrects two parsing gaps: decimal suffixes (`k`, `M`, `G`, `T`) become powers of 1000 (today `M` is read as MiB), and memory accepts the `m` suffix, which Kubernetes uses when it serializes fractional byte quantities. The re-homed tables keep their structure, but usage columns render only when the dashboard reports usage (`topCpuPods`/`topMemoryPods`, `nodeUtilization`, `namespaceUtilization`); elsewhere they show "—". Per-pod usage exists in the agent payload but never reaches the dashboard (E15, F5). The same rule reaches into the re-homed Utilization panel: `EksPerformanceTab.tsx:86` averages CPU and memory usage into an "efficiency score" and `:89` turns a missing per-node value into 0 with `|| 0`, so an agent cluster that reports no usage shows "0% Under-utilized". After the conformance pass (§14.1) the score renders only when both cluster usages are reported, otherwise "—" with "Not reported by `<source>`"; per-node usage stays `null` and is left out of averages; and the 40-75% "optimal" band becomes a labelled `StatusMark`, not a green/amber/red ring (`:279-284`).

### D11. Dead and fake components

| Component | Decision | Evidence |
|---|---|---|
| `CloudK8sIntegrations.tsx` (+ `GcpCloudK8sIntegrations.tsx`, `AzureCloudK8sIntegrations.tsx`) | Delete. AWS: replaced by the summary strip and "Connect a cluster". GCP/Azure: replaced by a "Connect a cluster" card that opens their existing setup guides (`GcpCloudK8sHelpModal`, `AzureCloudK8sHelpModal`). | Hardcoded "COMING SOON" cards with disabled "Integration Pending" buttons (`CloudK8sIntegrations.tsx:44,57`) |
| `CloudK8sHelpModal.tsx` | Delete in S3, with its barrel export (`components/CloudK8s/index.tsx:8`, its only reference). Not wired up. | It documents the Prometheus-on-EC2 path that nothing can reach for EKS (audit B1), and tells customers to open security-group ports to `0.0.0.0/0` (`:155`). The live guide is `EksIntegrationModal`. |
| `EksHeader.tsx` | Delete in S3. | No importer anywhere in `src`; the page uses `useTopbar` actions. |
| `EksTabs.tsx`, `EksSummaryCards.tsx` | Replaced by `SectionTabs` and the status strip/Overview tiles. | — |
| `EksAutoscalingTab.tsx`, `useEksAutoscaling.ts` | Deleted in S3: only commented-out lines reference the tab (`EksDetailsPage.tsx:37`, `:204`), and only the tab imports the hook. S4 builds the Automation subtabs and `useEksAutomation` new, with the old files in git history. | E2, plus misleading copy ("Continuously simulates pod redistribution", `:591`; "Syncs every 15s", `:399`), purple/green status colours, mode buttons that save without confirmation. |

### D12. Demo

See §16. Two demo AWS accounts show both states: `demo-aws-001` read-write (editable drafts, commit disabled with `DEMO`; live controls show their readiness reason) and `demo-aws-002` read-only (everything greyed with `READ_ONLY_CONNECTION` and the read-only banner). Both work because `DEMO` is the primary reason only when it is the only one (§6.4), and because each demo cluster's capabilities entry states its agents (§16): the prod cluster reports the automation agent connected, so `AGENT_NOT_INSTALLED` doesn't take precedence over `DEMO`. Vertical writes show the contract message in both, as they would in production (D16): the demo must not show a capability customers don't have yet. The demo does switch the vertical engine on (`verticalEngine.enabled: true`) so it can show recommendations, labelled "Not yet validated on live clusters" as everywhere; its activity fixture holds simulations only, so it never shows an applied change no customer could have.

### D13. Onboarding: what each engine needs, shown separately

The published contract changes what workload autoscaling needs: the XamOps agent's channel is its primary path, an in-cluster observer is needed either way, and direct API access is an optional fast path (E22). Four of the Data sources › Install cards (§10.8 has the full order) cover what the engines need, each with its live status mark:

1. **XamOps agent** (required for monitoring, and the primary path for workload autoscaling changes, contract §2.2), anchor `xamops-agent`. Opens the guide's Quick Install (`initialSection: 'quick'`). Status from capabilities `agent`.
2. **Usage metrics for rightsizing** (vertical engine), anchor `usage-metrics`. Status from the engine's `metricsSource` ("Available · `<name>`" or "Not available"). The card names the two sources the engine is designed for without promising either: a rolling usage window kept by the in-cluster observer, or a Prometheus the backend reaches through the Kubernetes API service proxy, which needs direct access and the Prometheus Service named in the ClusterRole's `resourceNames` (`prometheus-server` unless the customer changes it, contract §6). It links to the guide's Monitoring stack part (`initialSection: 'part1'`), which installs Prometheus. The engine wires only a stub provider today, which always reports `NO_METRICS_SOURCE` (contract §2), so the card reads "Not available yet" while that holds.
3. **Direct cluster API access (optional)** (vertical engine), anchor `cluster-access`, component `ClusterAccessGuide`. Status from `executionPathDetail.direct`. Content from contract §6: the customer creates an EKS access entry for the XamOps role in this account (its ARN is `AccountDto.roleArn`, which the account list already returns, `AccountManagerController.java:560`; `types/account.ts` gains the optional field) and binds it to a ClusterRole with exactly the rules contract §6 lists, shown verbatim in a copyable, read-only block. The separate opt-in rule that `IN_PLACE_AND_PERSIST` needs (contract §6.1) sits below it with its consequence: "Restarts every pod when applying." For a private endpoint (`CLUSTER_UNREACHABLE`) the card reads "XamOps can't reach a private API server directly. Workload autoscaling uses the XamOps agent's channel instead." There is no runnable `aws eks create-access-entry` command: the contract doesn't name the Kubernetes user or group the entry maps to, and the guide won't invent one (R4).
4. **Automation agent** (node automation, preview), anchor `automation-agent`, component `AutomationAgentGuide`, described below. Not needed for workload autoscaling.

The `AutomationAgentGuide` component (Data sources › Install and the fleet's Connect panel) describes the agent honestly: what it does (reads this cluster's policy from XamOps every 60 seconds; dry run on by default; all six controllers off by default in its config), what it is for now (node consolidation analysis, the node optimizer, spot interruption handling and the VPA baseline; workload rightsizing has moved to the vertical engine), what it can do today (analysis only, per §6.7), what it requests in the cluster (read access to core, apps, batch and policy; write access to cordon nodes, evict and delete pods, patch Deployments and StatefulSets, and manage HPAs and VPAs), and what it needs (the XamOps agent, metrics-server, Prometheus for the node optimizer's fallback queries, optionally the VPA recommender). Status line: "Preview. The `xamops/automation:v1.0.0` image isn't built by XamOps CI yet, so there's no supported install command. Contact XamOps support to enable it for this cluster." It deliberately contains no `helm`/`kubectl` command and no `docker.io/xamops/...` reference, and lives outside the three files the setup-guide job validates (E13).

`EksIntegrationModal.tsx` gets an optional `initialSection` prop (`'quick' | 'overview' | 'part1' | 'part2' | 'part3' | 'part4' | 'part5'`, the full union of the state at `:209`; nothing links to `part4` today), the verification checklist at `:1308-1317` renamed to the new sections ("Nodes › Nodes", "Workloads › Pods", "Security", "Cost › Namespaces"), and the full conformance pass (§14.1): the header gradient (`:272`), the indigo checklist box (`:1309-1311`) and the rest of its 186 hue classes go to the neutral ladder, and its 86 Font Awesome icons become bundled icons. Only `className` strings and icon props change. No command, image tag or `AGENT_IMAGE_TAG`/`EKS_PLACEHOLDERS` export changes, so every step of the setup-guide job (tag agreement, `--set` lint, Falco flags, Docker Hub image check, `setup-guide-validation.yml:127-245`) and `guideVars.test.ts` keep passing.

### D14. Tables: pagination, not virtualization

No table or virtualization library is installed and none is added. A shared `K8sDataTable` gives sorting, search, filter chips, pagination (25/50/100) and CSV export. Pagination keeps the DOM bounded for large pod lists; derived rows are memoized per dashboard.

### D15. Version support badge: deferred

No backend response carries support status. `K8sClusterInfo` has `version` only, and `eks:DescribeClusterVersions`, granted by Phase 0, is called nowhere. A hardcoded release calendar would be unverifiable reference data that goes stale, so the fleet shows the version without a badge and "extended support / EOL" is not an attention criterion yet (F7).

### D16. Consuming the vertical engine through one adapter

The engine's contract is published and adopted (E22). Wire details stay in two files: `api/verticalRightsizerApi.ts` (paths, query parameters, request bodies, error parsing) and `api/verticalRightsizerAdapter.ts` (wire shape to the view model in §11.5). Components and hooks see only the view model.

Adopting the contract changed more than those two files, because three of its behaviours are flows, not shapes: the global switch, the execution path and the asynchronous apply. What changes in this iteration:

- the client, the adapter and `types/verticalRightsizer.ts` (contract shapes, §11.5);
- `useVerticalRightsizer`: apply is a submission, and status and actions fail independently (§11.2);
- `VerticalApplyDialog`: Simulate (default) or Apply (§9.2);
- the §8.1 states: V0 redefined as "no execution path", V1 folded into it, V7 and V8 added;
- the gate rules in §6.2 and §6.3, and the copy in §6.1;
- the Install cards (D13) and the fixtures, now in the contract's shapes (§16);
- capabilities, which reports whether the engine is switched on (R9).

Endpoints 2-4 have no handler yet. When they get one, the change that flips `VERTICAL_WRITES_CONFIRMED` re-checks them against this contract, and updates this same list if they differ.

Rules the adapter enforces:

- **Writes stay blocked until the engine's server side is confirmed.** `verticalRightsizerAdapter.ts` exports `VERTICAL_WRITES_CONFIRMED = false`. While it is false, the adapter adds `CONTROLLER_NOT_READY` with the message "Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed." to every `vertical.*` action (`vertical.policy.recommend`, `vertical.policy.auto`, `vertical.method.persist`, `vertical.apply`), whatever capabilities say. Recommendations, findings, HPA conflicts and Estimated savings stay visible. The constant flips to `true` only in a change where endpoints 2 and 3 have handlers, the reviewer has read the engine's code and tests and confirmed both release criteria, and R10 is answered so the gate scoping in §6.3 is known:
  - **R1**: the engine's write endpoints refuse while `xamops.k8s.automation.readiness.vertical-rightsizing` is false: at least `AUTO` and apply (contract §3.1 refuses every policy `PUT`; R10).
  - **R2**: the engine's mutating endpoints refuse `DEMO`, `NO_USER_PERMISSION` (all three details), `READ_ONLY_CONNECTION` and `CONNECTION_NOT_VERIFIED` with `403` and those codes, through `K8sAutomationAccessService` or `GUARDED_PATHS` (§6.6). The contract plans this order (§3); it isn't code yet.
  It is a code constant, not a server flag, because the fact it records is about code: the engine's checks exist or they don't. A test pins it to `false` until that change, so it can't flip by accident. When the contract block and a readiness block both apply, the control shows the contract message, because it holds even after readiness flips; `can()` keeps one `CONTROLLER_NOT_READY` (§6.4), and the readiness message takes over once the block lifts.
- **Unknown enum values** (modes, methods, windows, findings, reasons, skip reasons, outcomes, submission states, execution paths) pass through as `{ kind: 'unknown', raw }` and render as plain text, never as a known state.
- **`recommended: null` means "no change recommended"** (contract §2.3), not "couldn't compute": the cell reads "No change recommended" with the container's reasons (for example "Change too small to act on"). "No recommendation" is kept for a workload with a `skipReason` and for `INSUFFICIENT_HISTORY`.
- **Resources are integers with `null` for unset**: millicores and MiB (contract §2.3). The adapter keeps them as numbers, and formatting goes through `utils/k8sQuantities.ts` so a value reads the same as on the dashboard. A `0` breaks the contract ("a `0` must be treated as a bug"), so the adapter turns it into `null` and flags the container "Invalid value reported". That container gets no savings and no Simulate or Apply.
- **QoS**: the engine preserves the QoS class and refuses an apply that would change it (engine plan §4.7, contract §3.2). The UI keeps its "Guaranteed → Burstable" check as a second line of defence, since the measured Go rightsizer downgraded QoS silently (harness §7-B3). A flagged row is not offered Simulate or Apply.
- **Fields the UI reads but doesn't show**: `autoApplyEligible` and `horizontal` are fixed this run (`false`, `NOT_EVALUATED`; contract §2.3) and are not displayed. `provenance` feeds the source label (D8).

Every call carries `accountId` (the 12-digit AWS account number, never a primary key, contract §7) and `region`. The page has `region` in its URL; the fleet has it from `K8sClusterInfo`.

---

## 8. State matrix

### 8.1 Cluster states by section

States come from three independent sources: capabilities (XamOps agent, automation agent, readiness), the vertical engine's status, and the dashboard. The tables keep them on separate axes because they combine freely: a cluster can have the vertical engine fully configured and no automation agent, or the reverse. "Normal" means the section renders its data with source and freshness labels.

**Monitoring axis (XamOps agent).**

| State | Fleet row | Overview | Cost | Workloads / Nodes / Security | Data sources |
|---|---|---|---|---|---|
| **M0 Not monitored** (listed by EKS, no XamOps agent data) | Nodes, CPU, memory, cost "—"; Data source "Not monitored"; counted in Needs attention | "Not monitored" panel with Set up monitoring; no tiles | "Not monitored" panel | "Not monitored" panel | Agent "Not installed" with Set up |
| **M1 Monitored** | Normal | Normal | Normal | Normal | Agent "Connected · data 4 min old" |
| **M2 Stale** (> 15 min) | Values with a stale marker; "Stale · 3 h"; Needs attention | Status "Data stale"; tiles keep last values with the marker | Same | Same, plus a stale banner per section | Agent "Stale", last data time, troubleshooting link |
| **Dashboard request failed** | Row error mark with the message; cluster still listed | Section error panel with the message and Retry | Same | Same | Unaffected (uses capabilities) |

**Workload autoscaling axis (vertical engine).** Evaluated top to bottom; the first match is the state (§11.2 implements the order). It is independent of the monitoring axis except through the engine's agent path, which needs the XamOps agent (contract §2.2).

| State | Detected by | Fleet "Workloads" line | Automation › Overview | Workload autoscaling | Data sources |
|---|---|---|---|---|---|
| **V7 Off in this environment** | capabilities `verticalEngine.enabled` isn't `true` (no engine call is made), or endpoint 1 returns `engine.enabled: false` | "Not available" | Engine block: "Workload autoscaling isn't turned on in this environment yet." No setup remedies: none of them would help | The same line and a short description of what the feature will do; no table, no controls | The vertical rows and Install cards are hidden |
| **V6 Not deployed** | a codeless `404` from endpoint 1 (capabilities says on, the endpoint doesn't exist) | "Not available" | "Workload autoscaling isn't available in this environment yet." | Same panel, no controls | Rows hidden |
| **V8 Not registered** | capabilities doesn't list the cluster (no call is made), or `404 K8S_CLUSTER_NOT_REGISTERED` | "Not registered" | "This cluster isn't registered with XamOps yet. Install the XamOps agent first." + "Set up monitoring" | Same panel | As M0 |
| **V5 Failed to load** | any other failure: network, timeout, `5xx` (coded or not), `400`, an unparseable body | "—" with the error mark | "Couldn't load" + Retry | Error panel + Retry; every vertical control blocked (`UNKNOWN`) | Rows "Unknown" |
| **V0 No execution path** | `executionPath: "NONE"` | "No execution path" | XamOps agent channel "Not connected" + "Set up monitoring"; direct access with its reason (§9.1) | The table if the engine lists workloads, recommendations visible, Auto and Simulate/Apply blocked with `AGENT_NOT_INSTALLED` (`EXECUTION_PATH`); otherwise a setup panel listing what the engine needs, each with its status | Execution path "None" |
| **V2 No metrics source** | `gateReasons` contains `NO_METRICS_SOURCE` | "No metrics" | Usage metrics "Not available" | The table if workloads are listed, cells "No recommendation · no usage metrics"; otherwise the setup panel | Usage metrics "Not available" |
| **V3 Collecting history** | `gateReasons` contains `INSUFFICIENT_HISTORY` | "Collecting history" | Engine block shows the window | Rows read "Collecting history", with sample coverage when `provenance` reports it. Mode controls follow V4's rule; Auto and Simulate/Apply are also blocked by `INSUFFICIENT_HISTORY` | Execution path and usage metrics available |
| **V4 Ready to recommend** | none of the above | "Recommend 12 · Off 30 · Auto 0" | Engine block complete; readiness line (§9.1) | Full table. Until `VERTICAL_WRITES_CONFIRMED` (D16): every mode, policy and method control blocked with the contract message. After it, while `vertical-rightsizing` isn't ready: blocked with the readiness message, because the contract refuses policy writes until then (§6.3; R10 may lift that for Off and Recommend). Once ready: editable within the write gates | Both rows available, last cycle time |

Iteration 2's V1 (Unreachable) is folded into V0: under the contract an unreachable API server only rules out the optional direct path, and a cluster has no execution path only when the agent path is missing too. Several states can hold at once (V0 and V2 usually do); the panel lists every unmet prerequisite with its status, and the first state in the order names it.

**Every workload Off.** With no policy rows the engine's default mode is Off, and the engine skips Off workloads with `POLICY_OFF` and no recommendation (E24). So a V4 cluster can list workloads and show no recommendations at all. The table then says so above the rows: "Workloads start Off, so the engine doesn't evaluate them yet. Recommendations appear once a workload is set to Recommend." followed by the reason the mode can't be changed yet. R11 asks the engine owner to default to Recommend, which never acts.

**Node automation axis (Go automation agent).**

| State | Fleet "Nodes" line | Automation › Overview | Node autoscaling / Rebalancing / Guardrails | Data sources |
|---|---|---|---|---|
| **N0 Not installed** | "Not installed" | Agent block unchecked, remedy "Install the automation agent" | Analysis panels "Needs the automation agent"; controls disabled (`AGENT_NOT_INSTALLED`) | Automation agent "Not installed" |
| **N1 Connected, node automation off** | "Off" | Switch enabled if write gates pass | Settings shown from `GET /{c}`, which returns every section while off (E19); unsaved defaults are labelled "Default · not saved" (`policySource: "default"`). Editable within write gates; saved values are used once node automation is on | "Connected · 40 s ago" |
| **N2 On** (derived Analysis) | "Analysis" | Switch on, scope shown | Settings editable within write gates; live controls disabled (`ANALYSE_ONLY` / `CONTROLLER_NOT_READY`); rebalancing blocked | As N1 |
| **N3 Stale** (> 5 min) | "Analysis · agent stale" | Banner: "The automation agent last reported 2 h ago. Changes apply when it reconnects." | Controls follow N1/N2 rules | "Stale" |

**Provider.** GKE and AKS keep their own pages in slice 1; when the shared components are adopted, every automation control shows `PROVIDER_UNSUPPORTED`.

### 8.2 Account and user overlays for automation controls

| Overlay | Banner | Settings (per-workload Off/Recommend and policy; node scope, evictor, node optimizer, guardrails) | Auto, Apply now, live and rebalance controls | Recommendations, analysis, projected savings |
|---|---|---|---|---|
| Read-write, permitted | none | Node automation settings editable when the automation agent's prerequisites are met. Vertical settings blocked with the contract message until `VERTICAL_WRITES_CONFIRMED` (D16), then with the readiness message until `vertical-rightsizing` is ready (§6.3, R10), then editable when the engine's prerequisites are met | Disabled with the readiness reason | Visible |
| **Read-only connection** | "Read-only view. Recommendations and projected savings are shown; automation changes are off." + "Enable automation" | Greyed values, `READ_ONLY_CONNECTION` | Disabled; shows the readiness reason first (§6.4), with "Also needs: read-write access" | Visible |
| **No user permission** (`TAB_GRANT` or `ROLE`) | "You can view automation but not change it." + "Enable automation" | Greyed values, `NO_USER_PERMISSION` with the detail's copy | Disabled; readiness reason first | Visible |
| **Impersonated session** (`IMPERSONATION`) | "You're signed in as another user. Automation is view-only in this session. Return to your own session to change automation." | Greyed values, `NO_USER_PERMISSION` / `IMPERSONATION` | Disabled; readiness reason first | Visible |
| **Connection not verified** | "This connection isn't verified." + "Open Account Manager" | Greyed values, `CONNECTION_NOT_VERIFIED` | Disabled | Visible |
| **Demo, read-write fixture** | "Demo workspace. Changes aren't saved." | Editable drafts (`DEMO` is the only reason, so `editable: true`); Save, switches and confirm buttons disabled with `DEMO` | Disabled with the readiness reason; `DEMO` sits in `also` and is not repeated (§6.4) | Visible (fixtures) |
| **Demo, read-only fixture** | Read-only banner, plus the demo banner | Greyed values with `READ_ONLY_CONNECTION` as the reason (`DEMO` in `also`) | As read-only | Visible |
| **Capabilities unavailable** | "Couldn't check automation access." + Retry | Disabled (`UNKNOWN`), fail closed | Disabled | Visible if their own requests succeed |

"Enable automation" opens a small panel listing the readiness checklist rows that are not met, each with its remedy. It is the path to enablement for every overlay, so the user is never left with a lock and no explanation.

---

## 9. Automation section

Built in the black/white/grey house style (§14). Every control is rendered through `GatedAction`, `GatedSwitch`, `GatedField` or `GatedRadioGroup` (§11.1) from one capability object, and this section names the wrapper for each control (§6.5). Ready-state confirm dialogs are built now so a readiness flip needs no UI change.

### 9.1 Overview

1. **Access** (shared by both engines): connection access ("Read-write" / "Read-only" + "Get read-write access") and your permission ("You can change automation" / "View only" with the `TAB_GRANT` remedy text, "View only: your role can't change automation", or "View only while signed in as another user"), plus the XamOps agent row ("Connected · data 4 min old" / "Not installed" + "Set up monitoring").
2. **Workload autoscaling · XamOps vertical rightsizer**, a readiness block with status marks. In V7, V6, V8 and V5 the block is that state's single line from §8.1 and nothing else. Otherwise:
   - Execution path: "Through the XamOps agent" (`AGENT`), "Direct to the API server" (`DIRECT`), or "No path" + "Set up monitoring" (`NONE`). With the agent path, the engine's `agent.lastSeenAt` is shown as "last check-in `<time>`, reported by the engine"; it isn't XamOps agent health, which the Access row shows (E25).
   - Direct access (optional): "Configured" / "Not configured" + "Set up direct access" / "Unreachable (private endpoint)", from `executionPathDetail.direct`.
   - Usage metrics: "Available · `<name>`" / "Not available" + "See metrics options".
   - Last cycle: "`<time>` · `<n>` workloads evaluated", the engine's last error, or "No cycle has run yet" (the engine has no scheduler yet, contract §2.1).
   - Readiness, while the contract block holds (D16): "Recommendations only. Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed." After it, while `vertical-rightsizing` isn't ready: "Recommendations only. Workload autoscaling changes are disabled until the vertical rightsizer passes live validation."
   - Modes in use: "Auto 0 · Recommend 12 · Off 30", linking to Workload autoscaling.
3. **Node automation · automation agent**, a readiness block:
   - Automation agent: "Connected · 40 s ago · v1.0.0-autoscaler" / "Not installed" + "Install the automation agent".
   - **Node automation** switch (`automation.enable`, `GatedSwitch`: it saves when toggled). Turning it on opens a confirm dialog: "Turn on node automation for `<cluster>`? The automation agent will receive this cluster's policy and start node analysis. Live changes stay off: each is disabled until its controller passes safety validation." Turning it off saves without a dialog, since it is the safe direction.
   - **Scope** (`automation.scope`): a `GatedRadioGroup` draft with "Full", "No consolidation" and "Paused", and its own Save (`GatedAction`). Saving "Paused" opens "Pause node automation for `<cluster>`? The automation agent idles until you choose another scope." Saving "Full" opens "Run every node automation controller that's turned on for `<cluster>`? Live changes stay off until each controller passes safety validation." "No consolidation" only turns the evictor off (D5), so it saves without a dialog. The old tab saved modes on click with no confirmation (D11).
4. **What automation can do on this cluster today**: one row per capability with Analysis and Apply columns and the server message for anything not ready. Workload rightsizing (vertical engine): "Recommendations" / "Disabled until live validation passes", or "Not turned on in this environment" in V7. Node consolidation: "Analysis not yet reliable" / "Disabled until pod-fit simulation is available". Node optimizer: "Idle-node thresholds" / "Disabled". Rebalancing: "—" / "Blocked". HPA manager: "Not shown yet" / "Disabled". Scale-out and node templates: "Not built". VPA baseline: "Collected by the automation agent (display coming)". Spot interruption handling: "On / Off" (protective).
5. **Dry run**: every vertical submission and action entry carries `dryRun`. Simulate is the default in the apply dialog (§9.2), and each Activity entry is labelled (§9.6). The automation agent: "Not reported by the automation agent. It ships with dry run on." (D5)

### 9.2 Workload autoscaling (vertical engine)

All data comes from endpoint 1 (`GET /api/eks/automation/vertical/{c}`) through the adapter (D16). In V7, V6, V8 and V5 the subtab is that state's single panel (§8.1).

- **Header**: engine label and quality line (D8), last cycle, execution-path and usage-metrics status marks, and filters for mode (Off / Recommend / Auto), status, findings and namespace.
- **Workloads table** (`K8sDataTable`, one row per workload, expandable to containers): Workload, Namespace, Kind, Mode, CPU request current → recommended, Memory request current → recommended, Limits (shown when the recommendation changes them), QoS (with the "Guaranteed → Burstable" flag, D16), Findings, HPA, Est. monthly savings (eligible rows only, "Estimated"), Last action, Status, and the row action. The expanded row lists each container with current and recommended requests and limits and the container's reasons.
- **Status column**, first match wins:

  | Engine reports | Status |
  |---|---|
  | `skipReason: POLICY_OFF` | "Off · not evaluated" |
  | `skipReason: INSUFFICIENT_HISTORY` | "Collecting history" (and "No recommendation" in the value cells) |
  | any other `skipReason` | "Skipped: `<reason>`" (copy below) |
  | every container `recommended: null` | "No change recommended", with the reasons |
  | mode `AUTO` | "Auto · in place" or "Auto · persist" |
  | otherwise | "Recommendation" |

- **Copy for the engine's enums** (contract §5.2). These are not gate codes; they render as text, and an unknown value renders as its code. One table in `verticalCopy.ts` (§11.1):

  | Kind | Value → text |
  |---|---|
  | Skip reason | `PROTECTED_NAMESPACE` "protected namespace" · `SINGLE_REPLICA` "single replica" · `ROLLOUT_IN_PROGRESS` "rollout in progress" · `LOCKED` "locked by annotation (`xamops.io/lock`)" · `HPA_VOLATILE` "HPA scaled recently" |
  | Finding | `REQUESTS_UNSET` "Requests unset" · `IDLE` "Idle" · `THROTTLED` "CPU throttled" · `OOM_RISK` "OOM kills in window" · `HPA_COEXISTENCE` "HPA-managed" · `NODE_CAPACITY_CAPPED` "Capped by node size" |
  | Container reason | `HPA_CPU_MANAGED` "CPU request held: an HPA scales on CPU" · `HPA_MEMORY_MANAGED` "Memory request held: an HPA scales on memory" · `FLOOR_CLAMPED` "Held at the safe floor" · `BELOW_FLOOR_NO_RAISE` "Below the floor; not raised without demand" · `LIMIT_RAISED` "Limit raised" · `DEMAND_CENSORED_BY_LIMIT` "Usage capped by the limit" · `NODE_CAPACITY_CAPPED` "Capped by node size" · `QOS_PRESERVED_NO_CHANGE` "Unchanged to keep the QoS class" · `COOLDOWN` "Waiting after a recent change" · `WITHIN_HYSTERESIS` "Change too small to act on" · `REQUESTS_UNSET` "Requests unset" |

- **Per-workload policy** (drawer opened from the row) is a form, committed by one Save:
  - Mode: `GatedRadioGroup` with Off and Recommend (`vertical.policy.recommend`) and Auto (`vertical.policy.auto`), so Auto can be blocked on its own.
  - Headroom: `GatedField` NumberField, whole percent 0-100.
  - Percentile: `GatedField` Select: p50, p90, p95, p99.
  - Window: `GatedField` Select: 6 h, 12 h, 24 h, 48 h, 72 h, 7 days (`168h`).
  - Apply method: `GatedRadioGroup`, below.
  - Save: `GatedAction`, using the capability of the strictest value in the draft (`vertical.policy.auto` when the draft mode is Auto, `vertical.method.persist` when the draft method persists, else `vertical.policy.recommend`).
  
  Values come from the workload's `policy`, labelled "Default · not saved" when `provenance.policySource` is `"DEFAULTS"`. The enums and ranges are the contract's (§3.1, §13); the engine's `400` message is shown at its `field`. Today the drawer opens with every field blocked: the contract message while `VERTICAL_WRITES_CONFIRMED` is false (D16), then the readiness message while `vertical-rightsizing` isn't ready (§6.3).
- **Apply methods**, two radio options with their consequence in the label, never a bare enum:
  - "In place, no restart" (`IN_PLACE`, default): "Resizes running pods through the resize subresource. New replicas start at the template values and are resized on the next cycle."
  - "In place and persist (restarts pods)" (`IN_PLACE_AND_PERSIST`, `vertical.method.persist`): choosing it opens an opt-in dialog: "This also patches the `<kind>` template, which restarts every pod in `<name>`. Use it when new replicas must start at the new size. XamOps refuses this for workloads managed by Argo CD or Flux." The confirm button reads "Restart pods when applying". On a GitOps-managed workload the option is blocked with `GITOPS_MANAGED`. Persisting also needs a separate, opt-in ClusterRole rule in the cluster (contract §6.1); Install shows it (D13).
- **Simulate or apply** (`vertical.apply`, `GatedAction`), the row action. Offered only for a workload with at least one container recommendation, no `skipReason`, and no row flag (QoS change, invalid value, below floor). It opens `VerticalApplyDialog`:
  - Per container: current → recommended requests and limits, exactly as the engine recommended (no edits in this release); the method and its consequence ("No restart" or "Restarts every pod"); HPA warnings and findings.
  - A choice between "Simulate (dry run)", selected by default, and "Apply to the cluster". The confirm button reads "Run simulation" or "Apply now". Applying is never the default (§14).
  - Confirm sends `POST …/apply` with `{ containers, applyMethod, dryRun }`. `containers` holds every container with a non-null recommendation and its four recommended values, nulls included. `applyMethod` is the workload's policy method. While the request is in flight the confirm button is blocked with "Submitting…", so a double click can't send two.
  - The result is announced with `role="status"`. **A `202` is never shown as "Saved" or "Applied"**:

    | Response | Shown |
    |---|---|
    | `202`, `state: ACCEPTED` | "Submitted · waiting for the result" ("Simulation submitted · waiting for the result" for a dry run); polling starts |
    | `202`, `state: DUPLICATE` | "Already submitted · waiting for the result"; polling follows the returned `correlationId` |
    | `202`, `state: REFUSED` | "Not submitted: the engine refused this change." No polling |
    | `400`, `403`, `404` | The mapped reason or field message at the button, as for every mutation (§12.2) |
    | Network failure or timeout after sending | "Couldn't confirm the submission. Check Activity before trying again." Nothing is retried automatically, because the engine can't yet tell a retry from a second submission (R13) |

  - Polling reads endpoint 4 every 10 s for up to 10 min and looks for that `correlationId` in the newest page. It shows only the outcome the engine reports, with §9.6's labels, and stops at the first terminal outcome. After 10 min: "No result yet. The XamOps agent applies at most one change per check-in, so it may still arrive. It will appear in Activity." Polling stops on unmount, and endpoint 1 is refetched after a terminal outcome.
- **Empty and blocked states**: §8.1 decides the panel; the table never renders zeros for workloads the engine has not evaluated.
- **HPAs and conflicts**: HPAs from `k8sHpas` (target, min/max/current replicas, CPU target) listed under the table, each linked to the workload rows it conflicts with (D8).

The Go rightsizer has no controls anywhere in the UI (E16). If a stored Go policy still carries rightsizer settings, they are not shown; the server serves that controller disabled.

### 9.3 Node autoscaling

- **Consolidation analysis** (`/evictor-candidates`): banner "Consolidation analysis isn't reliable yet. The evictor rejects any node running a DaemonSet pod, which excludes nearly every node in a real cluster, and it doesn't check whether evicted pods would fit elsewhere." Then the candidate table (node, CPU and memory requested % of allocatable, pods, evaluation result, skip reason) labelled "Reported by the automation agent". An empty list is shown as "No analysis reported", never as "No candidates".

The evictor and node optimizer panels below are forms: their fields are `GatedField` drafts (sliders, number fields, switches), each with its own action's capability, committed by the panel's one Save (`GatedAction`). Save uses the capability of the strictest value in the draft, as the policy drawer does (§9.2): `evictor.live` when the draft turns live consolidation on, `nodeOptimizer.actions` when it turns an action on, else the panel's settings action.

- **Evictor settings** (`evictor.settings`): target CPU and memory utilisation (sliders), grace period, cycle interval, aggressive mode. Like every node-automation panel, it shows the effective policy from `GET /{c}`, with "Default · not saved" while `policySource` is `"default"`.
- **Live consolidation** (`evictor.live`): a switch in the evictor form, blocked with `ANALYSE_ONLY`.
- **Node fleet**: on-demand and Spot node counts and instance types from `k8sNodes` (capacity type from node labels). Node-optimizer `savingsPct`, `interruptible` and `estimatedSavingsPct` are never rendered: they are constants (`node_optimizer.go:172-179`, harness §2). The frontend type omits them and a test asserts they never appear in output.
- **Node optimizer settings** (`nodeOptimizer.settings`): idle CPU and memory thresholds, spot interruption handling (a switch, with the NTH dependency note). The `nodeOptimizer.actions` fields in the same form (auto-cordon and auto-drain switches, Spot ratio target slider) are blocked with their message. Max parallel drains is a locked row reading "1 (fixed)".
- **Scale-out and node templates**: the current stored values as a read-only summary under "Not built: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does." Node template add, edit and delete are disabled (`node-templates`).

### 9.4 Rebalancing

An explainer of what rebalancing would do, then the block, stated plainly: "Rebalancing is blocked. The current rebalancer drains nodes whenever XamOps marks a rebalance as running, ignores the agent's own off switch, and repeats the drain every minute. XamOps won't send that instruction until it's fixed." "Rebalance now" (`GatedAction`) and the schedule form (a `GatedField` switch and cron field with its Save) are blocked (`rebalancer`). The last status from `GET /rebalance-status` is shown when present, labelled "Reported by the automation agent · unverified (dry-run state not reported)"; its `nodesDrained` is a number (harness capture), not a list. Its `savingsPercent` is never rendered (D9). The ready-state UI exists: a confirm dialog, then "Requested, waiting for the automation agent" while polling `rebalance-status` every 10 s for up to 30 min. It never shows "completed" unless the server reports it, and even then marks it unverified.

### 9.5 Guardrails

- **Locked** (static rows with a lock mark and "Always on"): respect PodDisruptionBudgets; protect `kube-system` and `xamops-system`; protect critical namespaces; max parallel drains 1; graceful draining.
- **Editable** (`guardrails.edit`):
  - "Protect single-replica workloads" and "Protect StatefulSets" are `GatedSwitch`es: each saves when toggled. Turning a protection on saves directly. Turning one off opens a confirm dialog naming the effect, and saves only on confirm.
  - Protected namespaces are a draft list: a `GatedField` text input with Add, and a Remove control on each chip. Add and Remove only change the draft, so they are `GatedAction`s of kind `draft` (they read `editable`). The list is committed by Save (`GatedAction`). Names are DNS-1123, at most 50. A Save that removes any namespace opens the confirm dialog first: "Nodes running pods in `<ns>` become consolidation and rebalancing candidates once those controllers can act."
- **Scope note**: these guardrails are the automation agent's policy and apply to node automation. The vertical engine has its own per-workload policy and skip rules (lock annotation, rollouts, HPA volatility); a line under the panel says so and links to Workload autoscaling. Unifying the two is F12.

### 9.6 Activity

One feed, newest first, with a source filter (Workload autoscaling · Node automation · Cluster events · XamOps changes) and search.

**Vertical engine actions** come from endpoint 4, `GET /api/eks/automation/vertical/{c}/actions?limit=50&before=` (contract §4). The response is `{ entries, nextBefore }`; "Load older" requests `before=nextBefore` and disappears when `nextBefore` is null. Endpoint 4 is contract-only today. Its failure is isolated: if it fails while endpoint 1 works, Activity shows "Workload autoscaling activity isn't available yet." in place of these entries, and nothing else on the page changes (§11.2). In V7 it isn't called at all.

Every entry carries `dryRun`, so the run marker is exact, and an outcome:

| Field | Value | Shown |
|---|---|---|
| `dryRun` | `true` | "Simulated": nothing changed in the cluster |
| | `false` | "Live" |
| | missing | "Unverified" (defensive; the contract says it is always present) |
| `outcome` | `APPLIED` | "Applied" with the method ("in place" / "in place and persisted, pods restarted"); for a simulation, "Would apply" |
| | `PARTIALLY_APPLIED` | "Partly applied" (simulation: "Would partly apply"), with each container's `status` listed |
| | `REFUSED` | "Refused" |
| | `FAILED` | "Failed" |
| | `EXPIRED` | "Expired, not applied": the command waited past its deadline and was dropped rather than applied late |
| | anything else | the raw value, no success styling |

Only `APPLIED` with `dryRun: false` carries the success check; everything else uses the neutral or error marks (§14). Each entry also shows the workload, the container changes (`from` → `to`), `triggeredBy` as reported (a user, or the engine's own trigger), "Attempt `<n>`" when `attempt` > 1 (delivery is at-least-once), and the time.

**XamOps changes** come from `recentChanges`, a separate list in `GET /{c}` (§11.4). It is read with `agentEventRepository.findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(id, "autoscaling_")`, so heartbeats can't crowd it out (E20). Each entry carries the event type (`autoscaling_config_change`, `autoscaling_rebalance_triggered`), the message written by `logEvent` (now including "by `<username>`"), and its time as an ISO-8601 instant in UTC (`createdAt` is a server-local `LocalDateTime`, converted with the JVM zone) so it merges correctly with the agents' RFC 3339 UTC timestamps. Marker: "Changed in XamOps".

**Agent-reported events** come from `recentEvents`: the agent-pushed `autoscaling-events` row only. Its `agent_events` fallback is removed, because it would now duplicate `recentChanges`. Activity merges both lists by timestamp, newest first. `recentEvents` entries are classified by `classifyAutomationEvent()` (§11.1) using the identifiers each producer actually writes:

| Class | Rule | Marker |
|---|---|---|
| Automation agent report | `id` matches `^(rebalance\|evictor\|rightsizer\|node-opt-cordon\|idle-drain\|idle-drain-fail\|spot-convert\|spot-convert-fail\|spot-interrupt)-\d+$` (`main.go:188-423`) | "Unverified": the agent doesn't report whether it was a dry run. "Simulated" only where the payload says so (`node-drain-progress` `reason: "dry-run"`, once served, F4). |
| Cluster event | message begins with `[Reason]` and `id` is `<name>-<uid>` (`autoscaling_metrics.go:151-155`) | "Kubernetes event", observed, not an XamOps action |
| Unrecognised | anything else | "Reported", no success styling |

Automation agent reports never render as completed actions: `severity: "success"` is shown as "Reported", not with a success check. A note at the top: "Events from the automation agent don't say whether they were dry runs. XamOps marks them Unverified." The agent and the automation agent write the same `autoscaling-events` row, so the newest push wins (`AgentIngestService.java:447-464`). The feed says "Latest report from either agent" and F4 separates them.

---

## 10. Observability sections: content rules

Each panel header carries a `SourceLabel`: source name, then freshness. For example "Agent · k8s-resources · 4 min ago (dashboard assembled 2 min ago)".

### 10.1 Overview › Summary

Status strip and health signals (D10); tiles: Nodes ready, Pods running (with pending and failed), CPU and memory requested % of allocatable (from pod requests and node allocatable, the existing `derivedNodeRequestMetrics`/`derivedPodResourceMetrics`) and used % when reported, Cost today and monthly with source, Critical/high vulnerabilities (Trivy). **Top findings**: the five most severe items from `extractAlerts` (exported from `useEksAlertsData.ts:78`), so Overview and the Alerts page agree, each deep-linking to its section, with "View all in Alerts". Kept panels: namespace utilisation and top CPU/memory pods.

### 10.2 Overview › Events

`k8sEvents`: type (Warning first), reason, object, message, count, last seen. Filter Warning/Normal, search, pagination. Source "Agent · k8s-resources".

### 10.3 Cost

Per D9. Every figure has its source; "Estimated" figures show method and rates in the tooltip; no multiplier-based recommendations remain.

### 10.4 Workloads

- **Workloads table**: name, namespace, kind, replicas ready/desired, CPU and memory requests summed from pod containers (deployments carry none, harness §6), CPU usage from `topCpuPods` where present ("—": "Usage is reported for the top 20 pods only"), efficiency (usage ÷ requests) only when both are known, recommendation summary and mode from the vertical engine (joined on namespace/kind/name; "—" when the engine has no entry, and "Not available" in V7, V6 and V8), and a link to the row in Workload autoscaling. Namespace deployment counts are derived from `k8sDeployments`, never from `namespaces[].deploymentCount` (always 0, harness §6).
- **Pods**, **Containers**: re-homed panels with `K8sDataTable` pagination; usage columns follow D10.
- **Resources**: re-homed `EksResourcesTab` lists other than events.

### 10.5 Nodes

- **Nodes**: re-homed. **Utilization**: re-homed Performance tab.
- **Node groups**: group `k8sNodes` by `eks.amazonaws.com/nodegroup` ("Managed node group"), then `karpenter.sh/nodepool` ("Karpenter NodePool"), else "Unmanaged". Columns: group, type, nodes ready/total, instance types, on-demand/Spot counts, zones, allocatable CPU and memory, requested %. Source "Agent · node labels". Note: "Scaling limits and AMI details need the EKS node-group API (not yet available in XamOps)." That is F1, which is where the "update connection" handling for roles without `XamOpsPolicyEksFleetRead` will live; this view makes no AWS call, so it has no AccessDenied path.
- **Karpenter**: re-homed; "Karpenter not detected" stays.

### 10.6 Security

Vulnerabilities (Trivy), Compliance (Kubescape), Runtime (Falco), split from `EksSecurityTab` along its existing views. When Kubescape data exists but the display flag is off (E14): "Kubescape data is being received but display is turned off for this cluster. Contact XamOps support to turn it on." That is honest until F9 fixes the flag.

### 10.7 Data sources › Connections

| Source | Status from | States shown |
|---|---|---|
| XamOps agent | capabilities `agent` (`k8s-resources.collectedAt`, agent version from that row) | Connected / Stale / Not installed |
| Prometheus (in cluster, read by the agent) | `prometheus-metrics.collectedAt` | Receiving / Not reporting; note: "XamOps doesn't connect to Prometheus directly for EKS" (audit B1) |
| OpenCost | `opencost-cost.collectedAt` + `displayed` flag | Receiving · shown / Received, not displayed / Not reporting |
| Trivy | `trivy-vulnerabilities.collectedAt` | Receiving / Not reporting |
| Kubescape | `kubescape-compliance.collectedAt` + `displayed` | as OpenCost |
| Karpenter | `karpenter-autoscaling.collectedAt` + `displayed` (the agent reports "not installed" cleanly, harness §6) | Installed / Not installed / Not reporting |
| Falco | `prometheus-metrics` freshness + `falcoEvents` presence | Receiving / No events / Not reporting |
| Container Insights (AWS) | `K8sClusterInfo.containerInsights` (D3) | Configured · data freshness not checked / Not set up / Unknown (with the `MISSING_AWS_PERMISSION` remedy when set). Never "Receiving": XamOps doesn't check freshness yet (F19) |
| Workload autoscaling path (vertical engine) | vertical engine `executionPath` and `executionPathDetail` | Through the XamOps agent / Direct to the API server / No path / Unknown, with the direct path's state on a second line (Configured / Not configured / Unreachable); powers Workload autoscaling changes |
| Usage metrics (vertical engine) | vertical engine `metricsSource` | Available (with the source name) / Not available / Unknown; powers Workload autoscaling recommendations |
| Automation agent | capabilities `automationAgent` | Connected / Stale / Not installed; powers node automation |

Each row also lists "Powers: Cost › Namespaces, …" and a "Set up" link to the matching Install card. The two vertical-engine rows are hidden in V7 and V6 (§8.1). The Prometheus row and the usage-metrics row are different things and say so: the agent queries Prometheus inside the cluster for the dashboard, while the vertical engine needs a usage history, kept in the cluster or read from Prometheus through the API proxy (D13).

### 10.8 Data sources › Install

Cards in order: XamOps agent (required; also workload autoscaling's primary path) → "Open setup guide" (`initialSection: 'quick'`); Monitoring stack → `part1`; Security tools → `part2`; Usage metrics for rightsizing → status plus a link to `part1` (D13); Direct cluster API access (optional) → `ClusterAccessGuide` inline (D13); Automation agent (preview, node automation) → `AutomationAgentGuide` inline (D13); Verify → `part5`. The two vertical-engine cards are hidden in V7 and V6, where no setup would help. Each card shows its live status mark next to its title. The fleet's "Connect a cluster" panel reuses the same cards with a cluster-name field and a region select; both pre-fill the guide through the modal's existing `clusterName` and `region` props (`EksIntegrationModal.tsx:21-27,227-228`), so the placeholder handling (`EKS_PLACEHOLDERS`) is unchanged.

---

## 11. Component architecture

### 11.1 Frontend files

The structure follows the repo's existing layout (`pages/`, `components/<Area>/`, `hooks/`, `api/`, `types/`, `utils/`, `demo/`). No new top-level folder.

```
frontend-app/src/
├── pages/
│   ├── CloudK8sPage.tsx                 rewritten: fleet (summary strip, table, connect panel)
│   ├── EksDetailsPage.tsx               rewritten: shell (route, providers, header, tabs, lazy sections)
│   ├── GcpCloudK8sPage.tsx              fake cards → "Connect a cluster" card opening GcpCloudK8sHelpModal
│   └── AzureCloudK8sPage.tsx            fake cards → "Connect a cluster" card opening AzureCloudK8sHelpModal
├── api/
│   ├── k8sAutomationApi.ts              new: capabilities + /api/eks/autoscaling/** (typed, K8sApiError)
│   ├── verticalRightsizerApi.ts         new: engine client for the published contract (§11.5)
│   └── verticalRightsizerAdapter.ts     new: wire → view model (D16)
├── hooks/
│   ├── useK8sCapabilities.tsx           new: K8sCapabilitiesProvider + useK8sCapabilities()
│   ├── useEksSectionRoute.ts            new: tab/sub URL state + legacy map (§5)
│   ├── useEksAutomation.ts              new: Go-agent automation data and mutations (replaces useEksAutoscaling.ts)
│   ├── useVerticalRightsizer.ts         new: engine status + workloads and actions (independent reads), policy save,
│   │                                    apply submission and outcome polling (§11.2)
│   ├── useCloudK8sFleet.ts              new: fleet orchestration with bounded fan-out
│   ├── useEksDashboard.ts               modified: fabricated fields and tab state removed (D9, D10)
│   └── useEksAlertsData.ts              modified: export extractAlerts; targetSub
├── types/
│   ├── k8sAutomation.ts                 new: ReasonCode, ActionId, Capability, CapabilitiesDto, K8sRefusal
│   ├── verticalRightsizer.ts            new: wire types (published contract) + view model
│   ├── account.ts                       modified: optional `roleArn`, already in the response (D13)
│   └── eksTypes.ts                      modified: section/sub types; openCost, agentCostMetrics;
│                                        corrected EvictorCandidate and RebalanceStatus shapes
├── utils/
│   ├── k8sQuantities.ts                 new: parse/format CPU and memory (moved from useEksDashboard), formatAge
│   ├── k8sPricing.ts                    new: the only home of the two reference rates (D9)
│   ├── k8sDerivations.ts                new: monitoring state, health signals, workload aggregation,
│   │                                    node groups, cost source, unallocated cost, savings eligibility,
│   │                                    HPA conflicts, QoS change
│   ├── k8sEvents.ts                     new: classifyAutomationEvent (§9.6)
│   └── csvExport.ts                     new: toCsv + download, with formula-injection guard
├── components/CloudK8s/
│   ├── shared/
│   │   ├── GatedAction.tsx              any button-like control (render prop); kind `commit` reads `allowed`, `draft` reads `editable`
│   │   ├── GatedSwitch.tsx              commit-on-toggle switch: RAC Switch when allowed, blocked switch replica otherwise
│   │   ├── GatedField.tsx               draft fields: text, number, checkbox, switch, select, slider (reads `editable`)
│   │   ├── GatedRadioGroup.tsx          draft radio group with per-option capabilities (WAI-ARIA radio group, §14)
│   │   ├── ReasonText.tsx               reason line + remedy link, always in the DOM, id for aria-describedby
│   │   ├── reasonCopy.ts                ReasonCode (+ detail) → { text, short, remedy } (§6.1), single table
│   │   ├── AccessBanner.tsx             read-only / no-permission / demo / unknown banners
│   │   ├── EnableAutomationPanel.tsx    the "Enable automation" path (unmet rows + remedies)
│   │   ├── StatusMark.tsx               icon + label + weight status (§14)
│   │   ├── SourceLabel.tsx              "Source · freshness" with absolute time in tooltip
│   │   ├── K8sDataTable.tsx             sort, search, filter chips, pagination, CSV (D14)
│   │   ├── SectionTabs.tsx              react-aria Tabs/TabList/Tab/TabPanel, primary and sub variants
│   │   ├── MetricTile.tsx               KPI tile with null-aware value and source
│   │   └── NotMonitoredPanel.tsx
│   ├── fleet/  FleetSummaryStrip.tsx · FleetClusterTable.tsx · ConnectClusterPanel.tsx
│   ├── setup/  SetupCards.tsx · ClusterAccessGuide.tsx · AutomationAgentGuide.tsx
│   ├── CloudK8sHeader.tsx               modified: access chip, Connect, Refresh; greyscale
│   └── index.tsx                        barrel updated
├── components/EksDetails/
│   ├── ClusterHeader.tsx                topbar actions: back, version · region, agent freshness, Guide, Sync
│   ├── ClusterStatusStrip.tsx           health · nodes · pods · automation · access
│   ├── sections/                        one lazy default export per primary tab
│   │   ├── OverviewSection.tsx          + overview/SummaryPanel.tsx (from EksOverviewTab), overview/EventsPanel.tsx
│   │   ├── CostSection.tsx              + cost/{Cluster,Namespace,Workload,Node}CostPanel.tsx (from EksCostTab)
│   │   ├── WorkloadsSection.tsx         + workloads/WorkloadsPanel.tsx (from EksWorkloadsTab), workloads/ResourcesPanel.tsx
│   │   ├── NodesSection.tsx             + nodes/NodeGroupsPanel.tsx
│   │   ├── AutomationSection.tsx        + automation/{Overview,WorkloadAutoscaling,NodeAutoscaling,
│   │   │                                  Rebalancing,Guardrails,Activity}Panel.tsx,
│   │   │                                  automation/VerticalPolicyDrawer.tsx, automation/VerticalApplyDialog.tsx
│   │   │                                  (Simulate or Apply, submission states), automation/PersistOptInDialog.tsx,
│   │   │                                  automation/verticalCopy.ts (engine enums → text, §9.2)
│   │   ├── SecuritySection.tsx          + security/VulnerabilitiesPanel.tsx (from EksSecurityTab)
│   │   └── DataSourcesSection.tsx       + datasources/ConnectionsPanel.tsx, datasources/InstallPanel.tsx
│   ├── tabs/                            kept, restyled (§14.1) and embedded: EksPodsTab, EksContainersTab, EksNodesTab,
│   │                                    EksKarpenterTab, EksPerformanceTab, EksResourcesTab (events removed),
│   │                                    KubescapeComplianceView, RuntimeSecurityView
│   ├── EksIntegrationModal.tsx          modified: initialSection, greyscale header, checklist copy (D13)
│   └── EksDiagnosticsPanel.tsx          restyled (§14.1); behaviour and the `?debug=true` entry unchanged
├── components/Alerts/EksAlertsTab.tsx   modified: URL with sub
├── components/Shared/CommandPalette.tsx modified: entry removed, keywords merged
├── components/Sidebar/Sidebar.tsx       modified: CloudK8s active on detail pages
├── constants/xamopsTabs.ts              modified: XAMOPS_TAB_PERMISSIONS + normalizeAllowedTabs (D2); XAMOPS_MODULES unchanged
├── pages/MyProfilePage.tsx              modified: nested "Allow automation changes" checkbox under CloudK8s (D2)
├── pages/BillOpsAdminUserManagerPage.tsx modified: the same nested checkbox (D2)
├── App.tsx                              modified: `import './styles/eks-details.css'` (:213) removed
├── styles/eks-details.css               deleted (§14.1)
├── test/designRules.test.ts             new: design-rule guard with its PENDING_FLEET_REWRITE list (§14.1), next to `test/setup.ts`
└── demo/
    ├── demoRouter.ts                    modified: capabilities, autoscaling, vertical engine routes (§16)
    ├── k8sAutomationMockData.ts         new: fixtures
    ├── xamopsMockData.ts                modified: node labels, a not-monitored shell for staging
    └── demoRouter.test.ts               extended
```

Deleted, by slice (§18):

- **S3**: `EksAutoscalingTab.tsx` and `useEksAutoscaling.ts` (dead: the tab is referenced only by commented-out lines, `EksDetailsPage.tsx:37`, `:204`, and only the tab imports the hook); `EksHeader.tsx` (no importer); `CloudK8sHelpModal.tsx` and its barrel line (`components/CloudK8s/index.tsx:8`, its only reference); `EksTabs.tsx` and `EksSummaryCards.tsx` (replaced by the new shell); `EksOverviewTab.tsx`, `EksCostTab.tsx`, `EksWorkloadsTab.tsx`, `EksSecurityTab.tsx` (their content moves into the panels named above, restyled); `styles/eks-details.css`. The new shell does not set the `eks-details-page` class (`EksDetailsPage.tsx:149`); nothing else uses it.
- **S5**: `CloudK8sClusterTable.tsx` (replaced by `FleetClusterTable`), `CloudK8sIntegrations.tsx`, `GcpCloudK8sIntegrations.tsx`, `AzureCloudK8sIntegrations.tsx`.

S4 builds the Automation subtabs and `useEksAutomation` new; the deleted tab stays readable in git history.

**Shared component contracts.**

```ts
// types/k8sAutomation.ts
export type ReasonCode =
  | 'DEMO' | 'PROVIDER_UNSUPPORTED' | 'CONTROLLER_NOT_READY' | 'ANALYSE_ONLY' | 'GITOPS_MANAGED'
  | 'NO_USER_PERMISSION' | 'READ_ONLY_CONNECTION' | 'CONNECTION_NOT_VERIFIED' | 'UNKNOWN'
  | 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | 'NO_METRICS_SOURCE' | 'AGENT_NOT_INSTALLED'
  | 'INSUFFICIENT_HISTORY' | 'MISSING_AWS_PERMISSION' | 'GUARDRAIL_LOCKED';

export type ReasonDetail =
  | 'TAB_GRANT' | 'ROLE' | 'IMPERSONATION'                  // NO_USER_PERMISSION
  | 'AUTOMATION_AGENT' | 'XAMOPS_AGENT' | 'EXECUTION_PATH'; // AGENT_NOT_INSTALLED

export interface Gate {
  reason: ReasonCode;
  detail?: ReasonDetail;   // picks the copy, short label and remedy (§6.1)
  message?: string;        // server-provided text (readiness, engine refusals) overrides reasonCopy text
  directPath?: 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | null; // EXECUTION_PATH's second line
  raw?: string;            // a server code the UI doesn't know: reason is 'UNKNOWN', shown as "Not allowed" + message
}

export interface Capability {
  allowed: boolean;        // commit allowed
  editable: boolean;       // draft edits allowed (true when DEMO is the only gate)
  gate: Gate | null;       // primary gate by §6.4 precedence
  also: Gate[];            // remaining gates in precedence order, one per reason code (§6.4)
}

// GatedAction.tsx: any button-like control
interface GatedActionProps {
  capability: Capability;
  kind?: 'commit' | 'draft';     // commit (default) reads `allowed`; draft (Add, Remove, Discard) reads `editable`
  label: string;                 // names the popover lock: "Why is <label> unavailable?"
  children: (p: { 'aria-disabled'?: 'true'; 'aria-describedby'?: string;
                  onActivate: (fn: () => void) => () => void }) => React.ReactElement;
  reasonPlacement?: 'inline' | 'popover';   // inline under the control; popover in dense table cells
}

// GatedSwitch.tsx: a switch that saves when toggled; reads `allowed`
interface GatedSwitchProps {
  capability: Capability;
  label: string;
  isSelected: boolean;
  onChange: (next: boolean) => void;  // called only when allowed; the caller confirms if needed, then saves
  pending?: boolean;                  // while saving: blocked, described by "Saving…"
  reasonPlacement?: 'inline' | 'popover';
}

// GatedField.tsx: draft fields committed by a separate Save; reads `editable`
type GatedFieldProps = { capability: Capability; label: string; reasonPlacement?: 'inline' | 'popover' } & (
  | { type: 'text' | 'number';
      children: (p: { isReadOnly: boolean; showSteppers: boolean;
                      inputProps: { 'aria-disabled'?: 'true'; 'aria-describedby'?: string } }) => React.ReactElement }
  | { type: 'checkbox' | 'switch'; isSelected: boolean; children: React.ReactElement }   // the RAC control
  | { type: 'select'; readOnlyValue: string; children: React.ReactElement }
  | { type: 'slider'; readOnlyValue: string; value: number; minValue: number; maxValue: number;
      children: React.ReactElement }
);

// GatedRadioGroup.tsx: draft radio group; reads `editable` for the group and per option
interface GatedRadioGroupProps<V extends string> {
  label: string;
  value: V;
  onChange: (v: V) => void;
  capability: Capability;                         // blocks every option when not editable
  options: Array<{ value: V; label: string; description?: string; capability?: Capability }>;
  reasonPlacement?: 'inline' | 'popover';
}
```

**Allowed or editable**, each wrapper renders the normal control: the caller's button, RAC `Switch`, the RAC field passed as `children`, or the radio group.

**Blocked**, each wrapper renders the greyed form from §6.5 and never the bare `disabled` attribute or `isDisabled`:

| Wrapper or `type` | Blocked rendering |
|---|---|
| `GatedAction` | The caller's control with `aria-disabled="true"`, `aria-describedby` and the lock icon; it keeps focus and hover, and `onActivate` makes activation a no-op. Plain form validation (an empty required field) uses the same pattern with the validation message as the description. |
| `GatedSwitch`, `GatedField` `checkbox` / `switch` | A replica, not the RAC control: `<button type="button" role="switch">` (or `role="checkbox"`) with `aria-checked`, `aria-disabled="true"`, `aria-describedby` and `aria-label`, drawn like the control in the blocked style with the lock icon. Click and Space do nothing. RAC's `isReadOnly` isn't used, because it emits `aria-readonly`, not `aria-disabled` (`@react-aria/toggle/dist/useToggle.mjs:66`). |
| `GatedField` `text` / `number` | The RAC field with `isReadOnly`, so the value can't be typed over, and `inputProps` on its `Input`: `aria-disabled="true"` and `aria-describedby`. RAC's `Input` passes those props through to the `<input>` (`react-aria-components/dist/Input.mjs`). The local `aria-describedby` replaces the one RAC would set, and a blocked field shows no validation error, so nothing is lost. A blocked NumberField renders without its stepper buttons, which RAC would give `isDisabled`. |
| `GatedField` `select` / `slider` | RAC 1.16 has no read-only mode for either (`SelectProps` omits `isReadOnly`; Slider has only `isDisabled`). So: a focusable `role="group"` with `aria-label="<label>, <readOnlyValue>"`, `aria-disabled="true"` and `aria-describedby`, containing a greyed, `aria-hidden` replica (the select trigger with its chevron, or the slider track with its thumb at `value`) and the value as visible text. A missing `readOnlyValue` is a type error. |
| `GatedRadioGroup` | `role="radiogroup"` with `aria-labelledby`; each option is `role="radio"` with `aria-checked`. The checked option is the one tab stop. Arrow keys move focus through every option, blocked ones included, and check the focused option only when it is editable; Space does the same. A blocked option carries `aria-disabled="true"`, the lock icon and `aria-describedby` to its own reason. When the group capability is blocked, the radiogroup carries `aria-disabled="true"` and `aria-describedby` as well. It is custom because RAC's `RadioGroup` can block one option only with `isDisabled`, which takes it out of focus and so hides its reason. |

`ReasonText` is always in the DOM, so every `aria-describedby` resolves. With `reasonPlacement: 'inline'` it shows under the control. With `'popover'` (dense table cells) it is visually hidden (`sr-only`, still in the accessibility tree), and a separate lock button, its own tab stop labelled "Why is `<label>` unavailable?", opens a popover (`DialogTrigger` + `Popover`) that shows the same text and the remedy visibly. Every wrapper follows the same rule. `ReasonText` renders the primary gate's text and remedy, then "Also needs: …" with the short labels of `also` (§6.1), leaving out `DEMO` (§6.4).

`K8sDataTable<T>` props: `rows`, `columns: ColumnDef<T>[]` (`id`, short `header`, `cell`, `sortValue` returning number | string | null, `csv`, `align`, `priority: 'essential' | 'normal' | 'low'`, `hideBelowPx`), `rowKey`, `caption` (accessible), `search?: (row) => string`, `filters?: FilterDef<T>[]`, `pageSize` (25 default, 50, 100), `initialSort`, `exportName`, `source`, `loading`, `error`, `emptyState`, optional `expandRow`. Null sort values always sort last. Header buttons carry `aria-sort`. Changing search or filters resets to page 1.

- **Column hiding**: only `low` columns may set `hideBelowPx`, and the type allows it only there. A column whose cell holds a gated control, a `StatusMark` or the row action must be `essential`, so visible-disabled controls never disappear at narrow widths. A test renders the Workload autoscaling table at 1280 px and asserts every gated control is present.
- **CSV export**: the filtered, sorted rows. String cells that start with `=`, `+`, `-`, `@`, a tab or a carriage return are prefixed with `'` (OWASP CSV injection guidance). Numbers are written as numbers, so negative values stay numeric.

`SectionTabs` uses `Tabs`, `TabList`, `Tab` and `TabPanel` from `react-aria-components`. The existing `AriaTabGroup` (`components/ui/Tabs.tsx`) renders no `TabPanel`, so its tabs point `aria-controls` at panels that do not exist; it is left alone because other pages use it. Primary tabs use its `neutral` look; subtabs use the `segmented` look (both already greyscale).

### 11.2 Hooks and data flow

```
EksDetailsPage (URL: name, region, accountIds, tab, sub, debug)
 ├─ useEksSectionRoute()                 → { tab, sub, select(tab, sub), hrefFor }
 ├─ K8sCapabilitiesProvider(accountId)   → GET /api/xamops/k8s/capabilities
 ├─ useEksDashboard(...)                 → existing dashboard fetch, cache, sync + polling
 ├─ ClusterHeader, ClusterStatusStrip
 └─ SectionTabs → <Suspense><Lazy Section eksData cluster /></Suspense>
       ├─ AutomationSection → useEksAutomation(cluster)     (Go agent APIs)
       │                    → useVerticalRightsizer(cluster) (engine APIs)
       └─ WorkloadsSection, CostSection → useVerticalRightsizer(cluster) (shared 60 s cache)
```

- **`useK8sCapabilities`**. `status: 'loading' | 'ready' | 'error'`, `forCluster(name)`, `verticalEngineEnabled`, `can(actionId, ctx?)`, `refetch()`. `can` combines account and user gates, readiness, the cluster's agent state and, for vertical actions, the engine view passed in `ctx` (execution path, `gateReasons` scoped by the §6.3 table, the workload's own fields). It returns a `Capability` in §6.4 order, with `also` de-duplicated by reason code (the first occurrence and its message are kept). Account gates come from the cluster's own `writeGate` when capabilities lists the cluster, else from the top-level `writeGate` (§11.4). While loading, every action is blocked without a reason line (a skeleton); on error, every write is blocked with `UNKNOWN`. Demo mode adds `DEMO`: it is the primary reason only when no other reason applies, otherwise it goes last in `also`, and `editable` is true only when `DEMO` is the sole reason (§6.4). Non-AWS providers add `PROVIDER_UNSUPPORTED`. For `vertical.*` actions the adapter's contract block adds `CONTROLLER_NOT_READY` (D16). The provider refetches on mount, on Retry, and after any refusal whose code is a gate, because a gate changed underneath the page.
- **`useEksAutomation(cluster)`**. Reads `GET /{c}`, `/evictor-candidates`, `/node-fleet`, `/rebalance-status` in parallel (`Promise.allSettled`). Each failure becomes that panel's error state; `fetchData` no longer swallows everything (today `useEksAutoscaling.ts:285-320`, with the silent `catch` at `:316`). Mutations: `saveSection(section, body)`, `setEnabled(on)`, `setScope(mode)`, `triggerRebalance()`. Each one sets `pending[section]`, calls the API, and on success refetches and shows "Saved · `<time>`" next to the control. On failure it shows the mapped reason or message at the control, keeps the draft, changes no server-backed state, and never shows a success toast. There are no optimistic updates, so there is nothing to roll back. `triggerRebalance` polls `GET /rebalance-status` (10 s, up to 30 min, backoff to 60 s after errors, "Status unavailable" after three failures) and renders only what the server returns. It is unreachable while `rebalancer` is not ready.
- **`useVerticalRightsizer(cluster)`**. Makes no request at all unless capabilities report `verticalEngine.enabled: true` and list the cluster as registered; otherwise it returns V7 or V8 directly (§8.1). It has two independent reads, each with its own 60 s cache entry (keys in §15), state and error:
  - `status`: endpoint 1 (engine status, `gateReasons`, workloads). Its outcome decides the §8.1 state, classified by `code`, never by HTTP status alone (E23): codeless `404` → V6; `404 K8S_CLUSTER_NOT_REGISTERED` → V8; `403 K8S_NO_ACCOUNT_ACCESS`, or a `403` with no code → the page-level "You don't have access to this account." (the engine's entitlement refusal currently reaches `GlobalExceptionHandler.handleAccessDeniedException`, which sends no code, R14); any other failure → V5; `200` → V7, V0, V2, V3 or V4 from the body.
  - `actions`: endpoint 4, requested only when `status` succeeded and the engine is enabled. Any failure stays inside Activity ("Workload autoscaling activity isn't available yet."); a codeless `404` or `5xx` isn't retried for the rest of the page's life, since endpoint 4 has no handler today. The table, the Overview block and the capabilities never depend on it.
  
  Mutations:
  - `setPolicy(ref, policy)` follows the same rules as `useEksAutomation`: no optimistic update, the error at the control, the draft kept, "Saved · `<time>`" only after a `2xx`, then a refetch of `status`.
  - `apply(ref, { containers, applyMethod, dryRun })` is a submission (§9.2). A `202` sets the row's submission state to `submitted` with its `correlationId` (or `duplicate`), or `refused`, and never to "saved" or "applied". It then polls `actions` every 10 s for up to 10 min, matching `correlationId` in the newest page, and moves to `outcome` with the reported `outcome` and `dryRun`, or to `timed-out`. A network failure after sending sets `unconfirmed`, and the hook never resends (R13). After a terminal outcome it refetches `status`. Polling stops on unmount and on an account or cluster change.
- **`useCloudK8sFleet(accountId)`**. 1) cluster list; 2) capabilities; 3) per-cluster dashboards through `createLimiter(4)` (`utils/concurrencyLimit.ts`), writing through `EksDashboardCacheContext`, **only for clusters that capabilities report as registered with XamOps agent data** (`agent.status` `CONNECTED` or `STALE`). A dashboard cache miss runs `syncClusterDashboard` synchronously (`EksController.java:92-96`), the Prometheus path for non-agent clusters, so fetching dashboards for clusters that would render M0 anyway would start N slow syncs for nothing. If capabilities fail, no dashboards and no engine status are fetched; rows show "—" and the banner "Couldn't load monitoring status." with Retry. 4) Vertical engine status, through the same limiter, only when `verticalEngine.enabled` is true, and only for clusters capabilities list as registered. Unregistered clusters show "Not registered" without a request (the engine would answer `404 K8S_CLUSTER_NOT_REGISTERED` for them, contract §2.4). Each response is classified by `code` as in `useVerticalRightsizer`, per row: `K8S_CLUSTER_NOT_REGISTERED` marks that row "Not registered" and the fan-out continues; a coded or codeless `5xx` marks that row V5 and continues; only a **codeless** `404` (the endpoint doesn't exist) stops the remaining engine calls and marks every row V6. No `K8S_*` code ever stops the fan-out. Rows render immediately and fill in as each request finishes. Fleets over 40 clusters fetch only the current table page, and the summary says "Summary covers the clusters on this page" (aggregate endpoint is F13). An `AbortController` cancels in-flight requests on account change or unmount.
- **`useEksDashboard`** keeps fetch, cache, sync and polling, and the request derivations (`derivedNodeRequestMetrics`, `derivedPodResourceMetrics`, `normalizedPods`, counts). It loses `activeTab`, the composite score fields, `potentialSavings` and `quickWins`. Sync polling keeps its 3 s × 40 loop; a timeout now shows "Sync is taking longer than expected. The latest data will appear when it finishes." instead of a console warning.

### 11.3 Backend classes

| Class | Change | Notes |
|---|---|---|
| `security/K8sAutomationAccessService` | new | `resolveAccounts(user, awsAccountId)` → entitled rows (or `403 K8S_NO_ACCOUNT_ACCESS`); `resolve(user, awsAccountId, clusterName)` → `K8sAutomationRequestContext`; `writeDecision(user, accounts)` → ordered reasons with detail, stricter row wins (§6.6 steps 4 and 7); `userDecision(user)` → the user part only, for capabilities. `static final Set<String> DEMO_EMAILS` (mirrors `LoginOtpController.java:70-73`); `isDemo` loads the `User` by `ClientUserDetails.getId()` through `UserRepository` and compares the trimmed, lower-cased email; role from the same row, normalised with the `ROLE_` prefix; impersonation from `ImpersonationContext`. A missing id or row fails closed (`NO_USER_PERMISSION`, WARN). Uses `CloudAccountAccessService.canAccess`, `CloudAccountRepository`, `KubernetesClusterConfigRepository`, `UserRepository`, `UserTabAccessRepository`. Public and self-contained so the engine can reuse it (R2). |
| `security/K8sAutomationGuardInterceptor` | new | §6.6. Writes refusals itself and logs them in `preHandle`; logs completed mutations in `afterCompletion`. |
| `security/K8sAutomationRequestContext` | new | record `(ClientUserDetails user, List<CloudAccount> accounts, KubernetesClusterConfig config)` with `primaryAccount()` (the owning row, else the first); `ATTRIBUTE` constant. |
| `config/K8sAutomationWebConfig` | new | `WebMvcConfigurer.addInterceptors` for `GUARDED_PATHS = List.of("/api/eks/autoscaling/**")`; the engine may add its path there to satisfy R2 (§6.6). |
| `config/K8sAutomationReadinessProperties` | new | §6.7, including `vertical-rightsizing`. |
| `exception/K8sAutomationReason` | new | enum of the §6.1 gate codes and the six error codes, with `code()` = `K8S_` + name, `gate()`, HTTP status and default message. |
| `exception/K8sAutomationRefusedException` | new | status, reason, message, field. |
| `service/K8sAutomationPolicyNormalizer` | new | §6.6, §6.7: `forAgent(storedJson)`, `forUi(storedJson)` → `(bundle, policySource)`, `replaceSection(current, section, body)`, `validate(section, body)`, `defaults()`, and the coercion table as one `List<Coercion>` keyed by readiness key. Own `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES` off. Coercion and parse-failure logs deduplicated per (config id, field set). |
| `service/K8sCapabilitiesService` | new | Builds `K8sCapabilitiesDto`. Entitlement and account gates come through `K8sAutomationAccessService.resolveAccounts` and `writeDecision`, so capabilities and the interceptor cannot disagree. `Clock` is injected for the thresholds, and every comparison follows the clock rule in §6.2: agent `collectedAt` → `atOffset(ZoneOffset.UTC)`, server-written columns → `atZone(ZoneId.systemDefault())`, `Instant`s compared against `clock.instant()`, every timestamp serialized as a UTC instant. `verticalEngine.enabled` = `environment.getProperty("xamops.automation.vertical.enabled", Boolean.class, false)` through the injected Spring `Environment`. That is the engine's own switch (contract §1); reading the property by name needs no compile-time dependency on the engine's classes, and an absent property (engine not merged) means `false` (R9). |
| `controller/K8sCapabilitiesController` | new | `GET /api/xamops/k8s/capabilities`. **Synchronous**: it returns `ResponseEntity`, never `CompletableFuture` or `@Async`, unlike its neighbour `CloudK8sController`. `ImpersonationContext` is a thread-local set by the impersonation filters on the request thread (`ImpersonationContext.java:5`), so work handed to another thread could read a missing or stale impersonation id and report writes as allowed. |
| `dto/k8s/K8sCapabilitiesDto` | new | §11.4. |
| `dto/autoscaling/AutomationPolicyBundle` (+ `HpaManagerPolicyDto`, `VpaManagerPolicyDto`) | new | Moved out of `EksAutoscalingService.PolicyBundle` (`:388-397`) unchanged in JSON shape, plus the two Go sections. |
| `dto/k8s/ContainerInsightsStatusDto` | new | `status` (`CONFIGURED` / `NOT_CONFIGURED` / `UNKNOWN`), `checkedAt`, `reason`, `action` (D3). |
| `controller/EksAutoscalingController` | modified | Reads `K8sAutomationRequestContext`; no `Long.parseLong(accountId)`, and `PUT /enabled` no longer does its own lookup (`:323-324`); refusals via the exception; `POST /rebalance` and `PUT /scale-out-policy`, `/node-templates/**` refused while not ready, `/workload-rightsizer` always (§6.3); controller-local handlers for binding errors (§6.6); no `e.getMessage()` in responses. |
| `service/EksAutoscalingService` | modified | Methods take `KubernetesClusterConfig`; `findConfig` and `requireAutoscalingEnabled` removed; saves go through the normalizer; `getDashboard` always returns `autoscalingEnabled`, every section from `forUi`, `policySource`, `recentEvents` (agent row only; the `agent_events` fallback is removed) and `recentChanges` (E19, E20); `setAutoscalingEnabled` persists the default bundle on first enable; `summarize(config)` for capabilities; `logEvent` details include the acting user ("Evictor policy updated by `<username>`"). Its `ObjectMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES`, so an added key in an agent payload no longer blanks the feed or KPI tiles (E21, R6). `getRebalanceStatus` returns `{"status": "unknown"}` when the stored status can't be parsed, and logs the parse message at WARN with the config id instead of returning it (`:791`, §6.6). |
| `service/AgentIngestService` | modified | `processBatch` refuses `SERVER_OWNED_COLLECTORS = Set.of("autoscaling-policy")` with a `BatchResult.error` and a WARN, before any write (§6.6). No other change. |
| `controller/AgentController` | modified | `getAutoscalingPolicy` serves `normalizer.forAgent(...)` in place of `:793-805`. No other change. |
| `service/EksService`, `dto/k8s/K8sClusterInfo` | modified | D3. |
| `repository/AgentDataCacheRepository` | modified | projection query `findTypeTimestampsByClusterConfigIdIn(ids)` returning `dataType, collectedAt, itemCount, agentVersion` without loading `data_json`. |
| `repository/AgentEventRepository` | modified | `findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(Long, String, LocalDateTime)`, called with `now − 24h` (§6.2); `findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(Long, String)` for `recentChanges`. Spring Data escapes `%` and `_` in `StartingWith`/`EndingWith` values, so `autoscaling_` matches literally. |
| `service/faq/FeatureFaqCatalog` | modified | D2: only the `cloudops-cloudk8s` description is rewritten (new sections; automation changes need read-write access and the permission). No new entry, keywords unchanged, `FeatureFaqCatalogTest` unchanged. |
| `permissions-common/.../AwsPermissionCatalog` | modified | `logs` → `+ KUBERNETES` (D3). Build note: `mvn -f permissions-common/pom.xml install` before `mvn -f xamops-service/pom.xml compile`, or `mvn -pl xamops-service -am compile` from the root aggregator. |

### 11.4 API contracts owned by this branch

**`GET /api/xamops/k8s/capabilities?accountId=<awsAccountId>`** (authenticated by `SecurityConfig.java:328`).

```json
{
  "schemaVersion": 1,
  "accountId": "605134457560",
  "generatedAt": "2026-10-10T09:30:00Z",
  "connection": { "accessLevel": "READ_ONLY", "status": "CONNECTED" },
  "user": { "canChangeAutomation": false, "reasons": ["NO_USER_PERMISSION"], "detail": "TAB_GRANT" },
  "writeGate": { "allowed": false, "reasons": ["NO_USER_PERMISSION", "READ_ONLY_CONNECTION"] },
  "readiness": {
    "vertical-rightsizing":  { "ready": false, "reason": "CONTROLLER_NOT_READY", "message": "Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. Recommendations stay visible." },
    "evictor-live":          { "ready": false, "reason": "ANALYSE_ONLY", "message": "Live consolidation is disabled until pod-fit simulation is available." },
    "rebalancer":            { "ready": false, "reason": "CONTROLLER_NOT_READY", "message": "Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly." }
  },
  "verticalEngine": { "enabled": false },
  "clusters": [
    {
      "clusterName": "xamops-test-1",
      "registered": true,
      "connection": { "accessLevel": "READ_ONLY", "status": "CONNECTED" },
      "writeGate": { "allowed": false, "reasons": ["NO_USER_PERMISSION", "READ_ONLY_CONNECTION"] },
      "dataSourceMode": "agent",
      "agent": { "status": "CONNECTED", "lastDataAt": "2026-10-10T09:26:41Z", "version": "v2.1.0" },
      "automationAgent": { "status": "NOT_INSTALLED", "lastSeenAt": null, "version": null },
      "nodeAutomation": { "enabled": false, "mode": null, "evictorAnalyseOnly": null, "derivedState": "NOT_INSTALLED" },
      "dataSources": [
        { "type": "k8s-resources", "collectedAt": "2026-10-10T09:26:41Z", "items": 1, "displayed": true },
        { "type": "kubescape-compliance", "collectedAt": "2026-10-10T09:21:02Z", "items": 22, "displayed": false }
      ]
    }
  ]
}
```

The example is abridged: `readiness` always lists every key in §6.7 (eight keys; the retired Go rightsizer has none), with `ready: true` and no message for keys that are ready. `verticalEngine.enabled` is the engine's own switch, read by name (§11.3, R9); when it is `false` the UI makes no engine call. Every timestamp is an ISO-8601 UTC instant (§6.2). `connection.accessLevel` is `READ_WRITE` only when `accessType` is `"read-write"` (case-insensitive) and `READ_ONLY` otherwise, including null (D1). `user.detail` is `TAB_GRANT`, `ROLE` or `IMPERSONATION` when `user.reasons` holds `NO_USER_PERMISSION`, else null (a demo identity reports `user.reasons: ["DEMO"]` and no detail).

**Two entitled rows** (padded and raw AWS id, §6.6 step 4) are the only case where the account gates can differ between clusters. Each cluster entry carries its own `connection` and `writeGate`, computed from the row that owns its config. The top-level `connection` and `writeGate` use the stricter of all entitled rows (read-only if any is read-only; not verified if any isn't `CONNECTED`) and apply to clusters without a config, which can't be written anyway (`404`). With one row, which is every normal account, top level and cluster entries agree. `clusters` lists every config resolved for the account (modern and legacy rows, de-duplicated by name, modern first); clusters EKS lists without a config are simply absent and the UI treats them as M0. Status thresholds: agent `CONNECTED` ≤ 15 min, else `STALE`; automation agent `CONNECTED` ≤ 5 min, else `STALE`; `NOT_INSTALLED` when never seen; `UNKNOWN` when that cluster's queries failed (the rest of the response still returns). Refusals: `400 K8S_INVALID_REQUEST` (format), `403 K8S_NO_ACCOUNT_ACCESS`. No internal ids are exposed.

**`/api/eks/autoscaling/**`** keeps its paths. Changes are the interceptor (§6.6), the readiness refusals (§6.3), validation (§13), the uniform refusal body, and three additive fields on `GET /{c}`:

```json
{
  "autoscalingEnabled": false,
  "policySource": "default",
  "evictorPolicy": { "enabled": true, "analyseOnly": true, "...": "every section, effective values (forUi)" },
  "recentEvents": [ { "id": "spot-convert-1791563637", "timestamp": "2026-10-10T08:12:44Z", "type": "node-replace", "severity": "info", "message": "…" } ],
  "recentChanges": [ { "id": "88123", "timestamp": "2026-10-10T08:20:05Z", "eventType": "autoscaling_config_change", "message": "Evictor policy updated by jane.doe" } ]
}
```

Every section is present whether or not node automation is enabled (E19). `PUT` and `POST` successes return `{ "success": true, "message": "…" }` as today; the UI refetches rather than trusting the body.

**`GET /api/v1/agent/autoscaling-policy`** (agent-facing) always returns a complete, normalized `AutomationPolicyBundle` (§6.6).

**`GET /api/xamops/k8s/clusters`** adds `containerInsights` per cluster (D3); `connected` keeps its meaning for old consumers.

### 11.5 Vertical engine (consumed; published contract)

Source: `.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md` (published 2026-10-10 18:36), which replaces iteration 2's provisional version of this section. Every path and field the provisional version named survived; the contract added fields and behaviour (E22). Paths are relative to `/api/eks/automation/vertical`. Every call takes `accountId` (1-12 digits, the AWS account number) and `region`.

| # | Call | Status on the engine branch | Used for |
|---|---|---|---|
| 1 | `GET /{cluster}` | Implemented, read-only. Switched off by `xamops.automation.vertical.enabled=false`, in which case it makes no AWS or cluster call and returns status with an empty `workloads` | Engine status, `gateReasons`, workloads (§8.1, §9.2) |
| 2 | `PUT /{cluster}/workloads/{namespace}/{kind}/{name}/policy` | Contract only, no handler | Policy drawer save (blocked, D16) |
| 3 | `POST /{cluster}/workloads/{namespace}/{kind}/{name}/apply` | Contract only, no handler | Simulate or apply (blocked, D16) |
| 4 | `GET /{cluster}/actions?limit=50&before=` | Contract only, no handler | Activity and apply outcomes (failure isolated, §11.2) |

Status codes (contract §2.4): `200`; `400 K8S_INVALID_REQUEST` or `K8S_INVALID_POLICY` with `field`; `401`; `403` with a gate code or `K8S_NO_ACCOUNT_ACCESS` (as committed, the entitlement refusal arrives without a code, R14); `404 K8S_CLUSTER_NOT_REGISTERED`; `500 K8S_INTERNAL`; plus `202` for endpoint 3. The refusal body is `{ code, message, field? }`. Codes carry the `K8S_` prefix in a refusal's `code` and no prefix inside payloads (`gateReasons`, reasons, `executionPathDetail`); the UI accepts both (R3). An unmapped path may not produce a clean `404` (E23), so responses are classified by `code` (§11.2).

Wire types (`types/verticalRightsizer.ts`), as the contract defines them:

```ts
// Endpoint 1
export interface VerticalStatusWire {
  engine: {
    enabled: boolean; ready: boolean;
    clusterAccess: 'CONFIGURED' | 'NOT_CONFIGURED' | 'UNREACHABLE' | null; // direct path only; null = not checked (switch off, E22)
    executionPath: 'DIRECT' | 'AGENT' | 'NONE';
    executionPathDetail: {
      direct: { available: boolean; reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | null };
      agent:  { available: boolean; reason: 'AGENT_NOT_INSTALLED' | null; lastSeenAt: string | null };
    };
    metricsSource: { reachable: boolean; name: string | null };
    lastCycle: { at: string | null; evaluated: number | null; error: string | null };
  };
  gateReasons: string[];                  // shared gate codes, no prefix
  workloads: VerticalWorkloadWire[];
}
export interface VerticalWorkloadWire {
  ref: { namespace: string; kind: string; name: string };
  policy: { mode: 'OFF' | 'RECOMMEND' | 'AUTO'; headroomPct: number; percentile: 50 | 90 | 95 | 99;
            window: '6h' | '12h' | '24h' | '48h' | '72h' | '168h'; applyMethod: 'IN_PLACE' | 'IN_PLACE_AND_PERSIST' };
  replicas: number | null;
  hpa: { present: boolean; name: string | null } | null;
  gitopsManaged: boolean | null;
  skipReason: string | null;              // §9.2 copy; free-form, can grow
  findings: string[];                     // §9.2 copy; free-form, can grow
  containers: Array<{ name: string; qosClass: string | null; current: Resources;
                      recommended: Resources | null;          // null = no change recommended
                      reasons: string[] }>;
  lastAction: { at: string; dryRun: boolean; outcome: string } | null;
  autoApplyEligible: boolean;             // false for every workload this run; not displayed
  provenance?: { percentile: number; windowHours: number; windowStart: string; windowEnd: string;
                 sampleCoverage: number; usageSource: string; policySource: 'WORKLOAD_ROW' | 'DEFAULTS' };
  horizontal?: { state: string };         // NOT_EVALUATED this run; not displayed
}
export type Resources = { cpuRequestM: number | null; memRequestMi: number | null;   // integer millicores and MiB;
                          cpuLimitM: number | null; memLimitMi: number | null };     // null = unset, never 0

// Endpoint 2 body: the policy above. Endpoint 3:
export interface ApplyRequest { containers: Array<{ name: string } & Resources>;
                                applyMethod: 'IN_PLACE' | 'IN_PLACE_AND_PERSIST'; dryRun: boolean }
export interface ApplySubmission { correlationId: string; state: 'ACCEPTED' | 'DUPLICATE' | 'REFUSED'; dryRun: boolean }  // 202

// Endpoint 4
export interface ActionsPage {
  entries: Array<{ at: string; correlationId: string; workloadRef: { namespace: string; kind: string; name: string };
                   containerChanges: Array<{ name: string; from: Resources; to: Resources; status: string }>;
                   method: 'IN_PLACE' | 'IN_PLACE_AND_PERSIST'; dryRun: boolean;
                   outcome: 'APPLIED' | 'PARTIALLY_APPLIED' | 'REFUSED' | 'FAILED' | 'EXPIRED';
                   attempt: number; triggeredBy: string }>;
  nextBefore: string | null;
}
```

The adapter turns these into the view model the components use. Every enum becomes `KnownValue | Unknown` (`type Unknown = { kind: 'unknown'; raw: string }`), so a value the contract adds later renders as plain text, never as a known state. Every field the UI needs is nullable in the view model, so a response that lacks one degrades instead of failing: savings fall back to `k8sDeployments` replicas, and the persist option relies on the engine's `GITOPS_MANAGED` refusal instead of being blocked up front. The view model adds what the UI derives:

```ts
export interface VerticalEngineView {
  state: 'V0' | 'V2' | 'V3' | 'V4' | 'V7';               // from a 200; V5, V6, V8 come from the call itself (§11.2)
  enabled: boolean; ready: boolean;
  executionPath: 'DIRECT' | 'AGENT' | 'NONE' | Unknown;
  directPath: { available: boolean; reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | Unknown | null };
  agentPath: { available: boolean; reason: ReasonCode | Unknown | null; lastSeenAt: string | null };
  metricsSource: { reachable: boolean; name: string | null };
  lastCycle: { at: string | null; evaluated: number | null; error: string | null };
  gateReasons: Array<ReasonCode | Unknown>;
  workloads: VerticalWorkload[];   // the wire workload with enums wrapped, a 0 in Resources turned into null and
                                   // flagged (D16), plus derived flags: qosChange, belowFloor, invalidValue, hpaConflict
}
```

---

## 12. Error handling

### 12.1 Backend

| Operation | Failure | Recoverable? | Caller receives | Log |
|---|---|---|---|---|
| Interceptor: principal | not `ClientUserDetails` | no | `401 K8S_UNAUTHENTICATED` | WARN (should be unreachable behind Spring Security) |
| Interceptor: input format | bad `clusterName` or `accountId` | yes (caller fixes input) | `400 K8S_INVALID_REQUEST`, `field` set | DEBUG |
| Interceptor: entitlement | unknown account, not the caller's, or no AWS row | no | `403 K8S_NO_ACCOUNT_ACCESS`, same message for every case | WARN with user id and requested cluster |
| Interceptor: write gates | demo (by email), impersonated session, role, missing grant, read-only, not verified | no (needs a different identity, session or connection) | `403 K8S_DEMO` / `K8S_NO_USER_PERMISSION` (with `detail`) / `K8S_READ_ONLY_CONNECTION` / `K8S_CONNECTION_NOT_VERIFIED` | INFO audit line, written in `preHandle` |
| Interceptor: user row | principal has no id, or its `User` row is missing | no | `403 K8S_NO_USER_PERMISSION` (fail closed) | WARN with the principal name |
| Request binding | malformed JSON or wrong JSON type | yes (caller fixes body) | `400 K8S_INVALID_POLICY`, `field` from Jackson's path when present | WARN |
| Request binding | path or query type mismatch | yes | `400 K8S_INVALID_POLICY`, `field` = parameter | WARN |
| Request binding | required parameter missing (capabilities; the interceptor catches it first on its path) | yes | `400 K8S_INVALID_REQUEST`, `field` = parameter | WARN |
| Interceptor: cluster | no config for a mutation | no | `404 K8S_CLUSTER_NOT_REGISTERED` | INFO |
| Section save: readiness | not-ready live or apply value, or a retired controller | no | `403 K8S_CONTROLLER_NOT_READY` / `K8S_ANALYSE_ONLY` with the readiness message | INFO |
| Section save: locked guardrail | loosening a locked field | no | `403 K8S_GUARDRAIL_LOCKED`, `field` set | INFO |
| Section save: ranges | out-of-range or missing value | yes | `400 K8S_INVALID_POLICY`, `field` and a sentence | WARN |
| Section save: persistence | DB error, serialization error | yes (retry) | `500 K8S_INTERNAL`, fixed message | ERROR with stack |
| `POST /rebalance` | rebalancer not ready | no | `403 K8S_CONTROLLER_NOT_READY` | INFO |
| GET endpoints | config missing | n/a | Existing behaviour: `404` for `GET /{c}`; empty bodies for the others | DEBUG |
| GET endpoints | unexpected exception | yes | `500 K8S_INTERNAL`, fixed message | ERROR |
| `GET /rebalance-status` | stored status can't be parsed | yes | `200 {"status": "unknown"}`, no exception text | WARN with config id and the parse message |
| Capabilities: vertical engine switch | property absent (engine not merged) or not a boolean | n/a | `verticalEngine.enabled: false` | none when absent; WARN once when unparseable |
| Capabilities: per-cluster query | agent-events or data-cache query fails for one cluster | yes | That cluster's `agent`/`automationAgent` status `UNKNOWN`; the rest returns | WARN |
| Capabilities: account lookup | DB unavailable | yes | `500 K8S_INTERNAL` | ERROR |
| Agent policy serve | stored bundle needed coercion | yes | Normalized bundle | WARN with config id and coerced fields, once per (config id, field set) per process |
| Agent policy serve | stored bundle unparseable | yes | Complete default with `autoscalerMode: "disabled"` (agent idles) | ERROR, once per config id per process |
| Agent ingest | batch for a server-owned collector (`autoscaling-policy`) | no | That batch's result is `error` ("autoscaling-policy is written by XamOps, not by agents"); the other batches are processed | WARN with config id |
| Container Insights check | no exact-name match in the `DescribeLogGroups` response | n/a | `NOT_CONFIGURED` | DEBUG |
| Container Insights check | AccessDenied | no (needs a stack update) | `UNKNOWN` + `reason: MISSING_AWS_PERMISSION` + action; the permission interceptor records the gap | INFO |
| Container Insights check | throttling or other AWS error | yes | `UNKNOWN`, no reason | WARN |

### 12.2 Frontend

| Operation | Failure | User sees | State | Log |
|---|---|---|---|---|
| Capabilities load | network, 5xx, timeout | Banner "Couldn't check automation access." + Retry; writes blocked with `UNKNOWN` | Reads continue | `console.warn` |
| Capabilities load | 403 | "You don't have access to this account." (page-level) | All sections show it | — |
| Dashboard load | any error | Section error panel with the message and Retry; status strip shows "Data unavailable" | Previous cache shown if any, marked stale | existing |
| Sync | trigger fails | Inline error under Sync with the message | No polling | existing |
| Sync | polling exceeds 2 min | "Sync is taking longer than expected…" | Polling stops | — |
| Vertical engine | capabilities report `verticalEngine.enabled: false` | V7 panel ("Workload autoscaling isn't turned on in this environment yet.") | No engine request is made | — |
| Vertical engine status | `200` with `engine.enabled: false` | V7 panel | Actions not requested | — |
| Vertical engine status | `404` with code `K8S_CLUSTER_NOT_REGISTERED` | V8 panel ("This cluster isn't registered with XamOps yet…") + Set up monitoring; in the fleet, that row only | Fan-out continues | — |
| Vertical engine status | `404` with no `code` | V6 panel ("isn't available in this environment yet") | The fleet stops its remaining engine calls | `console.warn` once |
| Vertical engine status | `403 K8S_NO_ACCOUNT_ACCESS`, or `403` with no code (R14) | The page-level message | — | `console.warn` |
| Vertical engine status | any other failure (network, timeout, `5xx` with or without a code, `400`, unparseable body) | V5 panel + Retry; every vertical control blocked (`UNKNOWN`); in the fleet, that row only | Fan-out continues | `console.warn` |
| Vertical engine actions | any failure while status succeeded | Activity only: "Workload autoscaling activity isn't available yet." | Table, Overview and controls unaffected; a codeless `404` or `5xx` isn't retried for the page's life | `console.warn` once |
| Any vertical write | `VERTICAL_WRITES_CONFIRMED` is false (D16) | The control is blocked with "Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed." | No request is sent | — |
| Simulate or apply | `202`, `state: ACCEPTED` / `DUPLICATE` | "Submitted · waiting for the result" / "Already submitted · waiting for the result" (`role="status"`); never "Saved" or "Applied" | Polls actions for the `correlationId` (10 s, up to 10 min) | — |
| Simulate or apply | `202`, `state: REFUSED` | "Not submitted: the engine refused this change." | No polling | — |
| Simulate or apply | network failure or timeout after sending | "Couldn't confirm the submission. Check Activity before trying again." | Never resent automatically (R13) | `console.error` |
| Simulate or apply | polling reaches 10 min without an entry | "No result yet… It will appear in Activity." | Polling stops | — |
| Simulate or apply | terminal outcome reported | The outcome label from §9.6 (`APPLIED`, `PARTIALLY_APPLIED`, `REFUSED`, `FAILED`, `EXPIRED`), with the dry-run marker | Endpoint 1 refetched | — |
| Any automation mutation | 403 with a known gate code | Reason text and remedy at the control; capabilities refetch | Draft kept; server state unchanged | — |
| Any automation mutation | 403 with an unknown code | "Not allowed" + the server message (plain text, ≤ 300 chars) | Draft kept | `console.warn` with the code |
| Any automation mutation | 400 | Field message inline at the field | Draft kept | — |
| Any automation mutation | 403 `K8S_NO_ACCOUNT_ACCESS` or 404 `K8S_CLUSTER_NOT_REGISTERED` | The page-level message for that code (§6.1), with "Set up monitoring" for the 404 | Draft kept; capabilities refetch | `console.warn` with the code |
| Any automation mutation | 409, 5xx, network, timeout | "Not saved. `<message>`. Your changes are still in the form." + Retry | Draft kept | `console.error` |
| Any automation mutation except Simulate or apply | `2xx` | "Saved · `<time>`" beside the control (`role="status"`) | Refetched from the server | — |
| Rebalance status polling | three consecutive failures | "Status unavailable" with the last known status and time | Polling continues with backoff | — |
| Fleet fan-out | a cluster's dashboard fails | Row error mark + tooltip with the message; summary says "n clusters couldn't be loaded" | Others continue | — |

`K8sApiError` (from `k8sAutomationApi.ts` and `verticalRightsizerApi.ts`) carries `status`, `code`, `reason` (code without the prefix), `message`, `field`. Both clients call `apiFetch` (`utils/apiClient.ts`) directly and read the JSON body, rather than parsing the `"HTTP <status>: <body>"` message the convenience wrapper throws (the workaround `autospottingErrors.ts` needs).

---

## 13. Input validation

| Input | Rule | On failure |
|---|---|---|
| URL `name` | `^[0-9A-Za-z][A-Za-z0-9_-]{0,99}$` (EKS cluster name rule) | Redirect to `/cloudk8s` with "That cluster link is invalid." |
| URL `accountIds` | 1-12 digits, or a demo id (`demo-aws-…`) in demo mode | Redirect to `/cloudk8s` |
| URL `region` | `^[a-z]{2}(-gov)?-[a-z]+-\d$`; missing → `us-east-1` (existing default, `EksDetailsPage.tsx:55`) | Invalid → redirect to `/cloudk8s` |
| URL `tab`, `sub` | enums (§4.2) or legacy values (§5.2) | Corrected with `replace` |
| Path `clusterName` (server) | same EKS rule | `400 K8S_INVALID_REQUEST` |
| Query `accountId` (server) | `^[0-9]{1,12}$` | `400 K8S_INVALID_REQUEST` |
| Path `templateId` | `^[A-Za-z0-9_-]{1,64}$` (checked after readiness, which refuses first today) | `400` |
| `PUT /mode` body | `mode` required, `full` / `scoped` / `disabled` | `400 K8S_INVALID_POLICY` |
| `PUT /enabled` body | `enabled` required boolean | `400 K8S_INVALID_POLICY` |
| Evictor policy | `analyseOnly` must be `true` (not ready); `targetUtilizationCpu`, `targetUtilizationMemory` 10-95; `nodeGracePeriodMinutes` 1-1440; `nodeCycleIntervalSeconds` 30-3600; `enabled`, `aggressiveMode` boolean | `403 K8S_ANALYSE_ONLY`; ranges `400` |
| Node optimizer policy | `autoCordonIdleNodes`, `autoDrainIdleNodes` false and `spotRatioTargetPct` 0 (not ready); `maxParallelDrains` 1; `idleCpuThresholdPct`, `idleMemThresholdPct` 1-50; `handleSpotInterruptions`, `karpenterEnabled`, `enabled` boolean | `403 K8S_CONTROLLER_NOT_READY`; ranges `400` |
| Safety guards | `respectPodDisruptionBudgets` and `protectCriticalNamespaces` must stay true; `criticalNamespaces` omitted or `null` keeps the stored list (a partial body must not unprotect namespaces, §6.5); an explicit list has ≤ 50 entries, each DNS-1123 (`^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$`), trimmed, lower-cased, de-duplicated; `kube-system` and `xamops-system` added if missing (tightening, silent) | `403 K8S_GUARDRAIL_LOCKED`; bad names `400` with the offending entry |
| Any section body | replaces the whole section (§6.5); the UI always sends the complete section | Not detectable server-side with primitive DTOs; F20 |
| Rebalancer config | `scheduleEnabled` false (not ready); `gracefulDraining` true (locked); `drainTimeoutMinutes` 5-60; `status`, `lastRebalance*` ignored (server-owned); `scheduleCron` ≤ 64 chars, stored only | `403`; ranges `400` |
| Scale-out, node templates, Go rightsizer | not accepted while not ready / retired | `403 K8S_CONTROLLER_NOT_READY` |
| Vertical policy (UI side; contract §3.1) | `mode` ∈ {`OFF`, `RECOMMEND`, `AUTO`}; `headroomPct` an integer 0-100; `percentile` ∈ {50, 90, 95, 99}; `window` ∈ {`6h`, `12h`, `24h`, `48h`, `72h`, `168h`}; `applyMethod` ∈ {`IN_PLACE`, `IN_PLACE_AND_PERSIST`}. Percentile and window are Selects offering only those values; headroom is a NumberField with step 1 | Inline field error, Save blocked with the error as its description; the engine's `400 K8S_INVALID_POLICY` message shown at its `field` |
| Vertical apply body (UI side; contract §3.2) | built from the engine's recommendation only: every container with a non-null `recommended`, its four values unchanged (integers or `null`, never `0`); `applyMethod` from the workload's policy; `dryRun` a boolean, always sent | Never user-edited, so nothing to show; a workload with an invalid value is not offered Simulate or Apply (D16) |
| Protected namespace entry (UI) | same DNS-1123 rule, no duplicates | Inline error, Add stays aria-disabled with the error as its description |
| CSV export | string cells starting with `=`, `+`, `-`, `@`, tab or carriage return prefixed with `'`; numeric cells written as numbers | — |

The UI mirrors the server's ranges so users see errors before saving; the server remains authoritative.

---

## 14. Visual and accessibility rules

CLAUDE.md governs: chrome stays black, white and grey, colour appears only for meaning; no emojis; same service, same icon; short labels; restrained. DESIGN.md's type and spacing scale applies; its accent and semantic colours (terracotta, error crimson, focus blue) are superseded for chrome, which stays greyscale; colour now appears for meaning (green/amber/red/blue) through the `--k8s-*` tokens; status is still never carried by colour alone (colour always paired with an aria-hidden icon + a visible word, enforced by colourAudit check #3).

- **Neutral ladder** on white (the WAR critique measured the ladder, `.impeccable/critique/…warpage…md:70-72`): primary text `rgba(0,0,0,0.88)`; secondary text, reason text and disabled labels at least `rgba(0,0,0,0.6)` (≈ 5.7:1); nothing that carries meaning below `rgba(0,0,0,0.55)` (≈ 4.7:1); `≤ 0.3` only for borders and decorative glyphs.
- **Type**: the app's system sans stack (ui-sans-serif, system-ui, sans-serif); sizes 11 (uppercase labels/eyebrows), 12 (metadata, secondary), 13 (table/body), 14 (card/group titles), 16 (section headings), 20 (strip-cell figures) and 28 (the dominant hero figure); `tabular-nums` for figures. The owner's converge review reversed the earlier "hero and strip share one figure size" decision: the hero figure (28) out-weighs the strip-cell figures (20), so hierarchy is weight, not placement alone (`typeScale.ts` `HERO_FIGURE` / `FIGURE`). Seven ranks in all; nothing smaller than 11 px; uppercase only at 11 px with modest tracking.
- **Status marks** (`StatusMark`): icon + label + weight. Healthy or receiving: `mdi:check-circle` filled, label weight 600. Attention or stale: `mdi:clock-alert-outline`, label plus age. Error: `mdi:alert-octagon` filled, weight 700, 1px black border. Not set up or not installed: `mdi:circle-off-outline`, secondary text. Unknown: `mdi:help-circle-outline`. Simulated: `mdi:flask-outline` + "Simulated". Unverified: `mdi:progress-question` + "Unverified".
- **Disabled automation controls** are distinguishable from ordinary grey UI because they always carry `mdi:lock-outline` and a visible reason line. The control keeps its normal outline at reduced fill rather than fading to near-white.
- **Blocked controls use `aria-disabled`, without exception.** Every gated control, commit or draft, carries `aria-disabled="true"` and `aria-describedby` (pointing at its reason) on its focusable element when blocked, or on the focusable group that stands for it (Select, Slider). It stays focusable, shows its current value, and is greyed in place rather than hidden or replaced by text. Text and number inputs also keep RAC's `isReadOnly`, so the value can't be typed over. Switches, checkboxes, selects, sliders and radio groups render a non-interactive replica, because RAC's read-only modes emit `aria-readonly` or don't exist. The bare `disabled` attribute and `isDisabled` are never used on a gated control. The per-type forms are in §11.1. This is the literal rule, so it needs no exception from the owner.
- **Focus**: 2 px `rgba(0,0,0,0.88)` ring with 2 px offset on every interactive element, including `aria-disabled` ones.
- **Tabs**: WAI-ARIA tabs through `react-aria-components` (`tablist` / `tab` / `tabpanel`, roving tabindex, arrow keys, Home and End, `aria-selected`). Two labelled tablists: "Cluster sections" and "`<Section>` views". Panels have `aria-labelledby` back to their tab.
- **Tables**: `<table>` with `<caption>` (visually hidden where a visible title exists), `scope="col"`, sortable headers as buttons with `aria-sort`, pagination controls with labels, row expansion with `aria-expanded`.
- **Live regions**: save results and refusals use `role="status"`; errors that block a section use `role="alert"` once.
- **Dialogs**: the existing `AlertDialog` (`components/ui/AlertDialog.tsx`) for confirmations; focus moves to the dialog and returns on close; the destructive or restarting choice is never the default button.
- **Icons**: from the generated bundle (`npm run icons:bundle` runs on build). EKS uses `resolveAwsServiceIcon('EKS Cluster')` everywhere (`utils/awsServiceIcons.ts`, the single source for AWS service icons); Kubernetes objects use `resourceMark('kubernetes_…')` (`utils/resourceBranding.tsx`). Font Awesome (loaded from a CDN, `index.html:18`) is not used anywhere in the CloudK8s tree: new code never adds it, and the conformance pass replaces it in re-homed files (§14.1).
- **Layout**: works at 1280 px and up. The summary strip wraps 6 → 3 columns below 1440 px. Tables scroll horizontally inside their card, and only `low`-priority columns hide below 1440 px (`hideBelowPx`). Columns holding a gated control, a status or the row action are `essential` and never hide (§11.1).
- **Motion**: 150-200 ms opacity transitions on panel change only; none with `prefers-reduced-motion: reduce`. No page-load choreography.
- **Copy**: sentence case, short button labels ("Save", "Apply now", "Retry", "Set up"), no exclamation marks, no "Oops".

### 14.1 Conformance pass and the design-rule guard

Nothing is re-homed "as is" (E18). Every file whose content moves into the new sections is restyled in the slice that moves it. Measured on the worktree at iteration 3 with the guard's own rules (Tailwind chromatic or `slate` classes, non-grey `#rgb`/`#rrggbb` literals, chromatic `rgb()`/`rgba()`, lines with emoji, Font Awesome classes, gradients):

| File (new home) | Slice | Hue classes | Non-grey hex | Chromatic rgb | Emoji lines | Font Awesome | Gradients |
|---|---|---|---|---|---|---|---|
| `EksIntegrationModal.tsx` (Data sources › Install) | S3 | 186 | 0 | 0 | 0 | 86 | 4 |
| `EksContainersTab.tsx` (Workloads › Containers) | S3 | 97 | 0 | 0 | 0 | 23 | 0 |
| `EksKarpenterTab.tsx` (Nodes › Karpenter) | S3 | 81 | 0 | 0 | 1 (`:324`) | 27 | 0 |
| `EksResourcesTab.tsx` (Workloads › Resources) | S3 | 66 | 3 | 0 | 0 | 6 | 0 |
| `RuntimeSecurityView.tsx` (Security › Runtime) | S3 | 60 | 27 | 0 | 0 | 18 | 0 |
| `KubescapeComplianceView.tsx` (Security › Compliance) | S3 | 59 | 4 | 0 | 0 | 18 | 0 |
| `EksNodesTab.tsx` (Nodes › Nodes) | S3 | 42 | 14 | 1 | 2 (`:257`, `:409`) | 20 | 0 |
| `EksWorkloadsTab.tsx` → `WorkloadsPanel` | S3 | 26 | 0 | 0 | 0 | 6 | 0 |
| `EksDiagnosticsPanel.tsx` (`?debug=true`, restyled in place) | S3 | 13 | 4 | 2 | 0 | 2 | 0 |
| `EksPerformanceTab.tsx` (Nodes › Utilization) | S3 | 0 | 58 | 17 | 0 | 0 | 0 |
| `EksOverviewTab.tsx` → `SummaryPanel` | S3 | 0 | 53 | 17 | 0 | 2 | 0 |
| `EksSecurityTab.tsx` → `VulnerabilitiesPanel` | S3 | 0 | 44 | 12 | 0 | 1 | 0 |
| `EksPodsTab.tsx` (Workloads › Pods) | S3 | 2 | 26 | 0 | 0 | 13 | 0 |
| `EksCostTab.tsx` → cost panels | S3 | 0 | 16 | 7 | 0 | 1 | 0 |
| `hooks/useEksDashboard.ts` (health colours, deleted with the score, D10) | S3 | 0 | 10 | 0 | 0 | 0 | 0 |
| `pages/EksDetailsPage.tsx` (rewritten shell) | S3 | 0 | 5 | 3 | 0 | 7 | 0 |
| `CloudK8sHeader.tsx` (fleet topbar; its one hue class is a colour-only status dot, `:15`) | S5 | 1 | 0 | 0 | 0 | 0 | 0 |
| `styles/eks-details.css` (global, 669 lines; deleted) | S3 | — | 135 colour values, including indigo tab hover `#4f46e5` and health-score gradients | — | — | — | — |

Files that are deleted rather than re-homed fail these rules today too; deleting them is their fix. Counts are hue / hex / rgb / emoji / Font Awesome / gradients.

- Deleted in S3: `EksAutoscalingTab.tsx` (175 / 22 / 2 / 0 / 34 / 0), `EksHeader.tsx` (10 / 0 / 0 / 0 / 9 / 1), `CloudK8sHelpModal.tsx` (20 / 13 / 0 / 0 / 0 / 0), `EksTabs.tsx` (0 / 0 / 0 / 0 / 11 / 0) and `EksSummaryCards.tsx` (0 / 4 / 0 / 0 / 0 / 0). `useEksAutoscaling.ts` passes but is deleted with the tab.
- Deleted in S5: `CloudK8sClusterTable.tsx` (10 / 3 / 0 / 0 / 0 / 0) and `CloudK8sIntegrations.tsx` (9 / 4 / 0 / 0 / 0 / 0).

The rest of the guarded scope (`useEksAlertsData.ts`, `CloudK8sPage.tsx`, the barrel) is already clean. The chromatic-rgb count includes one space-separated value (`rgb(124 58 237)`, `EksAutoscalingTab.tsx:145`); the guard matches both the comma and the space syntax.

The pass, per file:

1. **Colour**: hue classes, non-grey hex values, chromatic `rgb()`/`rgba()` values and gradients become the §14 neutral ladder.
2. **Status**: anything that carries status by colour becomes a `StatusMark` (icon + label + weight). This covers the efficiency ring and pill (`EksPerformanceTab.tsx:279-284`), the Kubescape score colours (`KubescapeComplianceView.tsx:176-179`, `:293-294`), and `✓` / `!` (`EksNodesTab.tsx:409`).
3. **Emoji**: "⚡ Spot" and "💎 On-Demand" (`EksKarpenterTab.tsx:324`, `EksNodesTab.tsx:257`) become plain text chips: "Spot", "On-demand", or the raw value.
4. **Icons**: Font Awesome classes become bundled Iconify ids (`mdi:*`), mapped per usage. `CollapsibleSection`'s `icon` prop in the integration modal takes an Iconify id. `npm run icons:bundle` picks the new ids up from the literal scan (`scripts/generate-icon-bundle.mjs`).
5. **Null handling**: unknown values stay `null` and render "—" with their source (D10). Here that means the efficiency score and per-node usage in `EksPerformanceTab.tsx:86-89`.
6. **Global CSS**: `styles/eks-details.css` is deleted, with its import (`App.tsx:213`) and the `eks-details-page` class (`EksDetailsPage.tsx:149`). Checked: all 122 selectors are scoped to `.eks-details-page` except four `.health-badge*` rules, and no component uses `health-badge`, so no other page changes.

Only presentation changes. Data flow, commands and copy that the setup-guide job checks stay byte-identical (D13).

**The guard.** `frontend-app/src/test/designRules.test.ts` (vitest, Node `fs`) scans `components/EksDetails/**`, `components/CloudK8s/**`, `pages/CloudK8sPage.tsx`, `pages/EksDetailsPage.tsx`, `hooks/useEks*`, `hooks/useK8s*`, `hooks/useCloudK8sFleet.ts`, `hooks/useVerticalRightsizer.ts` and `utils/k8s*`, skipping `*.test.*`. It fails with `file:line` for each of:

- Tailwind colour utilities (`text|bg|border|from|via|to|ring|fill|stroke|divide|outline|shadow|accent|decoration|placeholder|caret`) in a chromatic family or `slate`. `gray`, `neutral`, `zinc`, `stone`, `black`, `white` and arbitrary `rgba(0,0,0,a)` / `rgba(255,255,255,a)` values pass.
- `#rgb` / `#rrggbb` literals whose channels differ, and `rgb()`/`rgba()` with unequal channels, in either the comma or the space-separated syntax.
- `bg-gradient-` and `linear-gradient(`.
- Emoji: `/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}]/u` (this range includes `✓`).
- Font Awesome: `/\bfa[srb]? fa-[a-z0-9-]+/`.

There is no inline escape hatch. A false positive, such as an anchor id that happens to be hex, is fixed by renaming it.

**Scope by slice, and the one time-boxed exception.** In S2 the guard scans only the files S2 creates. From S3 on it scans the whole scope above, except the fleet files S5 replaces or restyles. Those sit in one exported list in the test file:

```ts
export const PENDING_FLEET_REWRITE = [
  'components/CloudK8s/CloudK8sClusterTable.tsx',   // replaced by FleetClusterTable in S5
  'components/CloudK8s/CloudK8sIntegrations.tsx',   // deleted in S5
  'components/CloudK8s/CloudK8sHeader.tsx',         // restyled in S5
] as const;
```

The list is a named exception, reviewed in the diff, not an inline marker, and two tests keep it honest. A listed file that no longer exists fails, so S5 has to remove each entry as it deletes the file. A listed file that already passes every rule fails too, so an entry can't outlive its fix. S5's "Done when" includes "`PENDING_FLEET_REWRITE` is empty"; S5 then removes the list and its two tests. The guard is part of the S3, S4 and S5 "Done when" (§18).

---

## 15. Performance

- **Code splitting**: each primary section is `React.lazy(() => import('../components/EksDetails/sections/<X>Section'))` inside a `Suspense` boundary with a section-shaped skeleton and an `ErrorBoundary` (`components/Shared/ErrorBoundary.tsx`). `EksIntegrationModal` (69 KB source) is lazy too, loaded on first open. Target: the `EksDetailsPage` chunk is at most 150 kB minified (327.66 kB today, audit §2.1), and no section's code is in it. The `vite:preloadError` reload listener (`main.tsx:143`) stays and covers the extra chunks.
- **Fan-out**: the fleet uses `createLimiter(4)` for dashboards and engine status. It fetches dashboards only for clusters with XamOps agent data (a miss would trigger a synchronous sync, §11.2). It makes no engine call while `verticalEngine.enabled` is false, which is every environment today, and otherwise calls only registered clusters, stopping only on a codeless `404`. It auto-fetches at most 40 clusters (page-scoped beyond that) and aborts on account change.
- **Caching**: dashboards through `EksDashboardCacheContext` (5 min); capabilities per account in the provider, refetched on mount; automation and engine GETs 60 s in module scope. Module-scope caches are keyed `userKey|accountId|region|clusterName`, where `userKey` is the signed-in profile email (`AuthContext.userProfile.email`) plus the `impersonateUserId` when present, so an entry cached for one identity or impersonation is never served to another. They are also cleared when the selected account, the profile email or the impersonation id changes; logout leaves the app through a full navigation, which drops module state. Nothing refetches on subtab switch.
- **Derivations**: memoized per dashboard object; workload aggregation is a single pass over pods; tables paginate.
- **Polling**: only sync (existing) and the ready-state rebalance status; both stop on unmount and on terminal states.

---

## 16. Demo mode

Two layers, because `apiFetch` consults the router once without the HTTP method (`apiClient.ts:149`) and the patched `window.fetch` consults it again with the method (`main.tsx:29`):

1. **The UI never sends demo mutations.** `isDemoMode()` adds `DEMO` to every commit; editing drafts still works.
2. **The router answers every path anyway.** All new routes are added in `routeXamops` before the existing catch-alls (`demoRouter.ts:398-405` for `/api/xamops/{cloudk8s,eks,gke,aks}` and `:468-469` for `/api/xamops/k8s/`). Mutation URLs are matched by path shape, not by method, so the method-blind first pass cannot return a GET fixture for a PUT.

| Route (regex on the path) | Fixture |
|---|---|
| `/api/xamops/k8s/capabilities` | per `accountId`: `demo-aws-001` read-write and permitted; `demo-aws-002` read-only. Both: readiness as in production (all not ready), `verticalEngine: { enabled: true }`, and the same cluster entries. **prod-eks-cluster**: `agent` CONNECTED, `automationAgent` CONNECTED (`1.0.0-autoscaler`, seen 40 s ago), `nodeAutomation` `{ enabled: true, mode: "full", evictorAnalyseOnly: true, derivedState: "ANALYSIS" }` (N2). **eu-eks-cluster**: `agent` CONNECTED, `automationAgent` CONNECTED, `nodeAutomation` `{ enabled: false, derivedState: "OFF" }` (N1). **staging-eks-cluster**: no entry, so it is unregistered (M0, V8). Timestamps are generated relative to now, so freshness labels stay current |
| `/api/eks/autoscaling/[^/?]+(\?\|$)` | prod: enabled Go-agent dashboard, `policySource: "stored"`, effective policies, KPIs (spot ratio, utilisation; never "saved" figures), mixed `recentEvents` and a few `recentChanges` ("Evictor policy updated by …"); eu: node automation off with every section present and `policySource: "default"` (N1); staging: `404` |
| `/api/eks/autoscaling/[^/]+/(evictor-candidates\|node-fleet\|rebalance-status\|rightsizing)` | evictor candidates shaped like the harness capture (DaemonSet skip reasons), node fleet counts, `{status: "idle"}`, empty rightsizing |
| `/api/eks/autoscaling/[^/]+/(scale-out-policy\|evictor-policy\|workload-rightsizer\|safety-guards\|node-optimizer\|rebalancer\|mode\|enabled\|rebalance)(\?\|$)` and `/api/eks/autoscaling/[^/]+/node-templates/` | `403 { success: false, code: "K8S_DEMO", message: "Demo workspace. Changes aren't saved." }` |
| `/api/eks/automation/vertical/[^/?]+(\?\|$)` | In the contract's shapes (§11.5), all with `enabled: true`, `ready: false` and `CONTROLLER_NOT_READY` in `gateReasons`. **prod** (V4): `executionPath: "AGENT"`, direct `CLUSTER_ACCESS_NOT_CONFIGURED`, usage metrics available, a last cycle; 9 workloads with `provenance`, mixing Recommend and Off, including one `POLICY_OFF` row, one `REQUESTS_UNSET` with an advisory recommendation, one `IDLE`, one `THROTTLED` with `LIMIT_RAISED`, one HPA-managed with `HPA_CPU_MANAGED`, one `LOCKED`, one GitOps-managed, and one whose containers all have `recommended: null` with `WITHIN_HYSTERESIS`. **eu** (V2): `executionPath: "AGENT"`, metrics not available, `NO_METRICS_SOURCE`, no workloads. **staging**: `404 { code: "K8S_CLUSTER_NOT_REGISTERED" }` (never requested while capabilities omit it, but routed for safety) |
| `/api/eks/automation/vertical/[^/]+/actions` | prod: `{ entries, nextBefore: null }` with simulations only (`dryRun: true`): one `APPLIED`, one `PARTIALLY_APPLIED`, one `EXPIRED`, one with `attempt: 2`. No live entry, because no customer can have one yet (D12). eu: `{ entries: [], nextBefore: null }` |
| `/api/eks/automation/vertical/[^/]+/workloads/.+/(policy\|apply)` | `403 K8S_DEMO` (never reached: the UI blocks both, D16) |
| `/api/xamops/eks/staging-eks-cluster/dashboard` | the all-zeros shell, `clusterHealthScore: 100` included, to show that the UI ignores it (M0) |

Fixture numbers follow the shapes captured by the harness (§2 and `captures/phase5/*`) and use believable, uneven values. They are demo-only and never reachable outside a demo session (`demoConfig.ts:13-16`). `demoAccounts` gains `access: 'read-write'` and `access: 'read-only'` on the two AWS demo accounts so `Account.access` agrees with the capabilities fixture.

The capabilities fixture reports the demo user as permitted (`user.canChangeAutomation: true`), as a real permitted customer would see it; the UI adds `DEMO` itself. With §6.4's rule, `demo-aws-001` then shows editable node-automation drafts on prod-eks-cluster with Save disabled by `DEMO` (the connected automation agent is what leaves `DEMO` as the only gate there), live controls disabled by their readiness reason, and vertical controls blocked by the contract message. `demo-aws-002` shows everything greyed with `READ_ONLY_CONNECTION`, except where a product rule ranks higher (§6.4). Recommendations, analysis and Estimated savings are visible in both. A component test renders the Automation section against each fixture and asserts those primary reasons.

---

## 17. Testing

Nothing here needs an AWS call or a live cluster; fixtures come from the harness captures and the engine's published contract.

**Frontend (vitest + Testing Library, `npm run test`).**

| Area | What is tested |
|---|---|
| `useEksSectionRoute` | all eleven legacy values; unknown `tab` and `sub`; missing `sub`; other params preserved; `replace` used |
| `useK8sCapabilities.can` | each reason in isolation; §6.4 precedence for every pair that can co-occur; `DEMO` alone → primary gate `DEMO`, `editable: true`, `allowed: false`; DEMO + ANALYSE_ONLY → primary `ANALYSE_ONLY` and `also` holds `DEMO`; DEMO + READ_ONLY_CONNECTION → primary `READ_ONLY_CONNECTION`, `editable: false`; `NO_USER_PERMISSION` carries `detail` and picks its copy; per-cluster `writeGate` overrides the top level; fail-closed on error; provider unsupported; union with engine `gateReasons`; **`also` de-duplicated**: the contract block plus `CONTROLLER_NOT_READY` from `gateReasons` and from readiness gives one `CONTROLLER_NOT_READY`, carrying the contract message; **execution path**: `NONE` with agent reason `AGENT_NOT_INSTALLED` → `AGENT_NOT_INSTALLED` / `EXECUTION_PATH` with `directPath` set, on `vertical.policy.auto` and `vertical.apply` only; `AGENT` with direct `CLUSTER_UNREACHABLE` → no access gate; a missing `executionPath` → fail closed; **scoping (§6.3)**: `NO_METRICS_SOURCE` and `INSUFFICIENT_HISTORY` never reach `vertical.policy.recommend`; `CONTROLLER_NOT_READY` from `gateReasons` reaches every `vertical.*` write; an unrecognised code reaches every `vertical.*` write as `UNKNOWN` with `raw` |
| `GatedAction` | blocked: `aria-disabled="true"`, never `disabled`; `aria-describedby` resolves to an element in the DOM in both inline and popover placement; activation is a no-op; focusable; remedy rendered; the popover lock is its own tab stop named "Why is `<label>` unavailable?" and opens with Enter and Space; kind `draft` is operable for a `DEMO`-only capability, kind `commit` isn't |
| `GatedSwitch` | allowed: the RAC `Switch` renders and toggles; blocked: the replica renders instead, with `role="switch"`, `aria-checked` matching the value, `aria-disabled="true"`, focusable, `aria-describedby` resolving, click and Space doing nothing, no `aria-readonly` and no `disabled` anywhere in the output; `pending` blocks it with "Saving…" |
| `GatedField` | not editable: `text` / `number` → the `<input>` has `readonly`, `aria-disabled="true"` and a resolving `aria-describedby`, keeps its value, and a NumberField renders no stepper buttons; `checkbox` / `switch` → the replica with the matching role, `aria-checked` and `aria-disabled="true"`; `select` / `slider` → a focusable `role="group"` with `aria-label` containing the label and the value, `aria-disabled="true"`, a resolving `aria-describedby`, and an `aria-hidden` replica present and visible; in every case no `disabled` or `isDisabled` appears, the replicas carry no `aria-readonly` (a blocked NumberField's input may, from RAC's spinbutton, `@react-aria/spinbutton/dist/useSpinButton.mjs:170`, but always next to `aria-disabled="true"`), and the control is not replaced by text alone; a `DEMO`-only capability leaves every type editable; a missing `readOnlyValue` for `select` and `slider` fails type-checking (a `@ts-expect-error` test) |
| `GatedRadioGroup` | `role="radiogroup"` with one tab stop on the checked option; arrow keys reach a blocked option and don't check it, and check an editable one; Space checks only an editable option; a blocked option has `aria-disabled="true"` and its own resolving `aria-describedby`; a blocked group carries `aria-disabled="true"` on the radiogroup; Mode with only Auto blocked still lets Off and Recommend be chosen when editable |
| `verticalRightsizerAdapter` contract block | `VERTICAL_WRITES_CONFIRMED` is `false` (pinned); every `vertical.*` action gets `CONTROLLER_NOT_READY` with the contract message regardless of capabilities; recommendations still render |
| `normalizeAllowedTabs`, tab pickers | permission key dropped when `cloudops-cloudk8s` is absent; "Select all" and the CloudOps module toggle never add it; the nested checkbox shows only while CloudK8s is checked; unchecking CloudK8s removes both keys |
| Module caches | keys include `userKey`, account, region and cluster; changing account, profile email or impersonation id clears them; an entry cached under one impersonation id is not served under another |
| `K8sDataTable` | `low` is the only priority that can hide; at 1280 px every gated control, status and row action in the Workload autoscaling table is present |
| Design rules (`test/designRules.test.ts`) | the guard itself (§14.1); a self-test that a temporary fixture string with `text-indigo-500`, `#4f46e5`, `rgba(220,38,38,0.1)`, `rgb(124 58 237)`, `💎` and `fas fa-server` is caught, so the regexes can't silently stop matching; every `PENDING_FLEET_REWRITE` entry exists and still fails at least one rule |
| `reasonCopy` | every `ReasonCode`, and every `detail` of `NO_USER_PERMISSION` and `AGENT_NOT_INSTALLED`, has text and a short label; "Also needs" lists short labels, each once, and never `DEMO`; unknown codes render generically ("Not allowed" + message, short label "an access check") |
| `verticalCopy` | every skip reason, finding and container reason in contract §5.2 has copy; an unknown value renders as its code |
| `k8sDerivations` | monitoring state, including the all-zeros shell with `clusterHealthScore: 100` → M0; health signals; requests aggregated from pod containers; namespace deployment counts from `deployments[]`; node groups from labels; cost source order; unallocated cost only with split and known requests; savings eligibility (floor, unset, HPA conflict, skipped, replicas); QoS change detection |
| `k8sPricing` | the two rates exist only here within the CloudK8s tree: the test greps `components/{EksDetails,CloudK8s}/**`, `hooks/useEks*` and `utils/k8s*` (not all of `src/`: `CloudSitterDashboard.tsx:27` legitimately holds `'t3.medium': 0.0416`) |
| `k8sQuantities` | binary and decimal suffixes; `m` on CPU and memory; missing and unparseable input → `null`; `250Mi` equals `0.25Gi`; the dashboard's request totals are unchanged for millicore and binary-suffixed input (the only change is the decimal-suffix fix) |
| `k8sEvents.classifyAutomationEvent` | the captured `rebalance-1791563637` and `spot-convert-1791563637` entries → automation agent, unverified; Kubernetes `[Reason]` events; numeric XamOps ids |
| `verticalRightsizerAdapter` | fixtures copied from the contract (§2.1 disabled status, the §2.3 workload, an actions page, each `202` state) → view model; the disabled status → V7, not V0 or V2, both as the contract shows it (`clusterAccess: "NOT_CONFIGURED"`, `NO_METRICS_SOURCE`) and as `ad5581ecd` sends it (`clusterAccess: null`, `direct.reason: null`, `executionPath: "AGENT"`); unknown enums pass through as unknown; missing numbers stay null; a `0` in `Resources` → `null` plus the invalid-value flag; `recommended: null` → "No change recommended", while `skipReason: INSUFFICIENT_HISTORY` → "No recommendation"; every `POLICY_OFF` → the "Workloads start Off" line |
| `useEksAutomation`, `useVerticalRightsizer` | failed save shows the error and leaves server state unchanged; no success notice on failure; 403 with a gate code triggers a capabilities refetch; rebalance status changes only from polled responses (fake timers); no `(local)` strings anywhere (grep test). **Vertical**: `verticalEngine.enabled: false` → no engine request at all; an unregistered cluster → no request, V8; a `404` with no code → V6; `404 K8S_CLUSTER_NOT_REGISTERED` → V8; a `403` with no code or with `K8S_NO_ACCOUNT_ACCESS` → the page-level message; a `500` → V5; an actions failure leaves the table, the Overview block and every capability unchanged and shows the Activity line. **Apply** (fake timers): `202 ACCEPTED` shows "Submitted · waiting for the result" and never "Saved" or "Applied"; polling finds the `correlationId` and shows the reported outcome, including `PARTIALLY_APPLIED` and `EXPIRED`, with the dry-run marker; `DUPLICATE` polls the returned id; `REFUSED` doesn't poll; a network failure after sending shows the unconfirmed message and sends nothing more; 10 min without an entry shows the timeout line and stops; the dialog defaults to Simulate and the request carries `dryRun: true` |
| `useCloudK8sFleet` | with the engine enabled: unregistered clusters are never requested and read "Not registered"; a registered cluster answering `404 K8S_CLUSTER_NOT_REGISTERED` marks that row and the rest are still fetched; a coded or codeless `500` marks that row V5 and the rest are still fetched; a codeless `404` stops the remaining calls and marks them V6; with the engine disabled, no engine request is made; capabilities failure → no dashboards and no engine requests |
| Never-rendered fields | `savingsPct`, `interruptible`, `estimatedSavingsPct`, `costSavedThisWeek`, `nodesSavedToday`, the rebalance status's `savingsPercent` and `rebalancer.lastRebalanceSavingsPercent` never appear in rendered output, with fixtures that set each to a non-zero value |
| Demo router | every new GET shape returns its fixture; every mutation shape returns `403 K8S_DEMO` regardless of method; capabilities differ by account; existing `demoRouter.test.ts` cases still pass |
| `csvExport` | quoting, newlines; string cells starting with `=`, `+`, `-`, `@`, `\t`, `\r` get the `'` prefix; the number `-5` stays numeric |
| Sections | each renders its loading, empty, error and normal states from fixtures; Not-monitored panel replaces content |
| Contract fixture | `xamops-service/src/test/resources/k8s/k8s-reason-codes.json`, read with `node:fs` by `src/test/reasonCodes.contract.test.ts` (path `../../../xamops-service/src/test/resources/k8s/k8s-reason-codes.json`, resolved from `import.meta.url`), shared with the backend test below, lists every code and `NO_USER_PERMISSION` detail. Each side's test fails when its own code list differs from the fixture. The limit is CI: the frontend job runs only when `frontend-app/` changes (`ci-cd.yml:71-75`), so a backend-only change to the enum and the fixture runs only the JUnit side, and the vitest side catches the gap at the next frontend change. Until then the UI renders the new code generically and keeps the control blocked (§6.1), so drift is safe. Running the vitest side on fixture changes is F22 |

**Backend (JUnit 5, Mockito, AssertJ, `mvn -pl xamops-service -am test` from the root aggregator).** Plain unit tests constructing the classes with mocks, following `ComplianceOpsControllerAuthorizationTest`: each denial asserts both the refusal and that the service was never reached.

| Class | What is tested |
|---|---|
| `K8sAutomationAccessService` | entitlement via `canAccess`; padded and raw AWS ids; modern then legacy config lookup; the owning row decides the account gates, and without an owner the stricter of the padded and raw rows does; **demo by email**: a principal with username "Xammer Admin" whose user row has email `demo.xamops@xammer.in` (and "Xammer BillOps" / `demo.billops@xammer.in`, with mixed case and spaces) is `DEMO`, while a non-demo user whose username is the string `demo.xamops@xammer.in` is not; a reflection test asserts `DEMO_EMAILS` equals `LoginOtpController`'s private `DEMO_EMAILS` and fails with "DEMO_EMAILS moved; update K8sAutomationAccessService" if the field disappears; **impersonation**: with `ImpersonationContext` set, by the header path (`X-Impersonate-User`, admin session) and by the JWT path (BillOps "Login As" token), writes get `NO_USER_PERMISSION` / `IMPERSONATION` even when the target user is unrestricted `ROLE_XAMOPS`; **roles**: DB role `"XAMOPS"` and `"USER"` normalise to `ROLE_XAMOPS` and `ROLE_USER`; BillOps-only and unknown roles refused with detail `ROLE`; unrestricted users allowed; restricted users need both keys (detail `TAB_GRANT`); missing id or row fails closed; read-only and unverified connections; null `accessType` is read-only; `CONNECTION_NOT_VERIFIED` depends on status only (blank `roleArn` with status `CONNECTED` passes) |
| `K8sAutomationGuardInterceptor` | GET passes after resolution; every write gate returns its code for PUT, POST and DELETE; bad inputs → 400; a handler whose path variable is named `cluster` (the engine's name) resolves exactly like `clusterName`; unknown account → 403 with the same message as not-yours; **cross-tenant id**: `accountId` equal to another tenant's `CloudAccount.id`, with a modern config owned by that id present, → `403 K8S_NO_ACCOUNT_ACCESS` and the service is never called, for `GET /{c}`, `PUT /evictor-policy` and `PUT /enabled`; missing config → 404; refusal logged from `preHandle` (log captor) and completed mutations from `afterCompletion` |
| `K8sAutomationPolicyNormalizer` | partial stored JSON → complete bundle (null `safetyGuards`, null `criticalNamespaces`, missing HPA/VPA sections); `kube-system` and `xamops-system` always present; one test per coercion-table row (§6.7): a stored bundle violating it is served with the safe value by both `forAgent` and `forUi`, and flipping that key to ready serves the stored value; Go rightsizer disabled on serve even with every key ready; spot interruption handling passes through; rebalancer `status` forced idle and client values ignored; `criticalNamespaces: null` in a save keeps the stored list; unparseable JSON → default with `disabled`; the coercion WARN is logged once for repeated serves of the same bundle and again when the field set changes |
| `EksAutoscalingService.getDashboard` | node automation disabled with no stored bundle → every section present, `policySource: "default"`; disabled with a stored bundle → stored sections, `policySource: "stored"`; `recentChanges` lists a config change while the agent's `autoscaling-events` row exists and 50 newer heartbeat rows are present; timestamps are UTC instants; an extra unknown key in the agent's events row doesn't empty `recentEvents` |
| `AgentIngestService` | a batch with collector `autoscaling-policy` returns an error result, writes nothing, and the other batches in the same push are stored |
| Controller binding errors | malformed JSON → `400 K8S_INVALID_POLICY`; a string where a number is expected → `400 K8S_INVALID_POLICY` with `field`; missing `accountId` on capabilities → `400 K8S_INVALID_REQUEST`; no response body contains the exception message, including `GET /rebalance-status` over a stored status that isn't valid JSON, which returns `{"status": "unknown"}` and logs the parse message at WARN |
| Section saves (orchestrator decision 9) | for each section endpoint: save with a partial body → the stored JSON has every section and a non-empty `criticalNamespaces` |
| `EksAutoscalingController` | `POST /rebalance` → 403 and the service is never called; scale-out, node templates and Go rightsizer → 403; no `e.getMessage()` in 500 bodies |
| `AgentController.getAutoscalingPolicy` | no stored policy → complete normalized default (not `{"autoscalerMode":"full"}`); stored `running` → served `idle` |
| `K8sCapabilitiesService` | statuses at the thresholds (injected `Clock`); `-autoscaler` detection: an event 4 min old → `CONNECTED`, 3 h old → `STALE`, none in 24 h but a cache row → `STALE` with that time, nothing → `NOT_INSTALLED`, and the repository is called with `now − 24h`; per-cluster failure → `UNKNOWN` without failing the response; readiness map complete (eight keys); `user.detail` set for each refusal kind, including `IMPERSONATION` when `ImpersonationContext` is set on the calling thread; two entitled rows with different access → top level read-only, each cluster's `writeGate` from its owning row; **`verticalEngine.enabled`**: property `true` → `true`, `false` → `false`, absent → `false`, unparseable → `false` with one WARN (a `MockEnvironment`); **clocks**: with `TimeZone.setDefault(Asia/Kolkata)` (restored after the test), a `k8s-resources` row collected 4 min ago in UTC → `CONNECTED`, an `-autoscaler` event created 4 min ago in server time → `CONNECTED`, and both serialize as the same UTC instants as on a UTC JVM |
| `EksService` Container Insights | exact-name match → `CONFIGURED`; a response holding only `/aws/containerinsights/{cluster}/performance-old` → `NOT_CONFIGURED`; empty response → `NOT_CONFIGURED`; AccessDenied → `UNKNOWN` + `MISSING_AWS_PERMISSION` + action; throttling → `UNKNOWN` without reason; no `DescribeLogStreams` call is made (mock verifies) |
| `FeatureFaqCatalogTest` | unchanged and passing: the permission key is not a sidebar tab (D2) |
| Reason codes | `/k8s/k8s-reason-codes.json` loaded from the test classpath (`xamops-service/src/test/resources/k8s/`) matches `K8sAutomationReason` and the detail values |

**Build and CI.** `npm run build` (includes `icons:bundle` and `tsc`) passes, and the chunk report shows the §15 target. `mvn -pl xamops-service -am test` passes. The setup-guide workflow still passes: commands, image tags and the validated files' `docker.io/xamops` references are unchanged by the integration modal's restyle. `AzureCloudK8sHelpModal.substitution.test.ts`, `guideVars.test.ts` and `FeatureFaqCatalogTest` are untouched and pass. `test/designRules.test.ts` passes over the whole CloudK8s tree from S3 on, minus `PENDING_FLEET_REWRITE`, which is empty after S5.

**Visual check.** Per the mockup skill: run the dev server in demo mode, walk both demo accounts through every section and state, and save screenshots to the task's `screenshots/` folder (`.agents/tasks/cloudk8s-ui-revamp-2026-10-09/screenshots/`), never the repo root.

**Not testable here.** Behaviour against a real cluster and the vertical engine's live behaviour. The engine's own verdict decides `vertical-rightsizing`.

---

## 18. Delivery slices

Each slice builds, passes its tests, and leaves the product working.

| Slice | Contents | Done when |
|---|---|---|
| **S1 Backend safety** | Access service (email-based demo, impersonation, role normalisation, details), interceptor (refusal logging in `preHandle`), refusal format and controller-local binding handlers, normalizer (completeness, coercion table, Go rightsizer retired, rebalancer status, `forUi`), readiness properties, agent-policy serve path, `GET /{c}` always returning sections plus `policySource` and `recentChanges`, tolerant `ObjectMapper` in `EksAutoscalingService`, ingest guard, capabilities endpoint (details, per-cluster `writeGate`), bounded repository queries, FAQ description, permission-catalog mapping, Container Insights exact-match check | All backend tests in §17 pass; `/api/eks/autoscaling/**` refuses cross-tenant (including another tenant's `CloudAccount.id`), read-only, demo-by-email and impersonated writes; the agent can never receive a partial or unsafe bundle; no new AWS API call exists |
| **S2 Frontend foundations** | Types, both API clients and the adapter for the published contract (with `VERTICAL_WRITES_CONFIRMED = false`), `useK8sCapabilities`, `useEksSectionRoute`, `SectionTabs`, `GatedAction`/`GatedSwitch`/`GatedField`/`GatedRadioGroup`/`ReasonText`/`reasonCopy`, `StatusMark`, `SourceLabel`, `K8sDataTable`, utils, module caches, demo routes and fixtures in the contract's shapes, `XAMOPS_TAB_PERMISSIONS` with the nested checkbox in both pickers, `test/designRules.test.ts` (scanning only the files S2 creates) | Unit tests pass; the only user-visible change is the nested "Allow automation changes" checkbox |
| **S3 Cluster page** | Shell, header, status strip, lazy sections for Overview, Cost, Workloads, Nodes, Security, Data sources; **conformance pass on every re-homed file (§14.1)**, including `EksDiagnosticsPanel.tsx` in place; **deletion of the dead and replaced files** (§11.1: `EksAutoscalingTab.tsx`, `useEksAutoscaling.ts`, `EksHeader.tsx`, `CloudK8sHelpModal.tsx` and its barrel line, `EksTabs.tsx`, `EksSummaryCards.tsx`, the four re-homed tab files, `styles/eks-details.css`); legacy links; fabricated numbers removed; Not-monitored state; Integration modal changes; alerts deep links; palette and sidebar | Every current tab's data is reachable in its new home; old links resolve; the design-rule guard passes over the full CloudK8s tree except the three `PENDING_FLEET_REWRITE` files, and that list's two tests pass; setup-guide job passes; demo walkthrough screenshots |
| **S4 Automation** | Overview, Workload autoscaling (vertical engine: V0-V8, Simulate or Apply as a submission, five outcomes in Activity), Node autoscaling, Rebalancing, Guardrails, Activity; `useEksAutomation`, `useVerticalRightsizer`, built new (the old tab and hook went in S3) | Every control renders through the gating model; no fake success remains, and no `202` reads as success; both demo accounts show their states (§16); the design-rule guard passes as in S3. **Release criteria for the vertical write controls**: endpoints 2 and 3 have handlers, R1 and R2 are confirmed by reading the engine's code and tests, and R10 is answered; only then does `VERTICAL_WRITES_CONFIRMED` flip (D16). Until then S4 ships with those controls blocked with the contract message, which is a complete, releasable state |
| **S5 Fleet** | Summary strip, cluster table, connect panel, setup cards; fake cards removed on all three providers; `CloudK8sClusterTable.tsx` and `CloudK8sIntegrations.tsx` deleted; `CloudK8sHeader.tsx` restyled | Fleet loads progressively; CSV export works; GCP and Azure pages unchanged apart from the connect card; **`PENDING_FLEET_REWRITE` is empty** and removed with its tests, so the guard covers the whole tree |
| **S6 (optional) GKE/AKS** | Shared components on the GCP and Azure pages | Not part of this branch's acceptance |

The engine's contract is adopted from S2 (D16). If the engine changes it when endpoints 2-4 get handlers, the change that flips `VERTICAL_WRITES_CONFIRMED` absorbs the difference in the files D16 lists.

---

## 19. Reconciliation points and follow-ups

### 19.1 Reconciliation with other workstreams

| # | With | Point |
|---|---|---|
| R1 | Vertical engine | **Release criterion for the vertical write controls.** Read readiness from `xamops.k8s.automation.readiness.vertical-rightsizing` (default false), the property this branch defines, and refuse writes while it is false: at least `AUTO` and apply. The engine already binds the property (engine plan D9) and its contract refuses every policy `PUT` and apply while it is false (§3); what is left is code, because endpoints 2 and 3 have no handler yet. One flag then flips UI and server together. On merge, keep one copy of the property in each `application*.properties`. |
| R2 | Vertical engine | **Release criterion for the vertical write controls.** Every mutating engine endpoint refuses `DEMO` (by email), `NO_USER_PERMISSION` (all three details, including impersonation), `READ_ONLY_CONNECTION` and `CONNECTION_NOT_VERIFIED` with `403` and the shared codes, and resolves accounts with the `awsAccountId`-only rule. It can do so by adding its path to `GUARDED_PATHS` or by calling `resolve` and `writeDecision`. Until R1 and R2 are confirmed, `VERTICAL_WRITES_CONFIRMED` stays false and the UI offers no vertical write (D16); the union of reasons alone is not enough, because it only makes the UI stricter. |
| R3 | Vertical engine | **Settled by the contract** (§5.1): the engine adds no new gate codes, sends them without the `K8S_` prefix in payloads and with it in refusals, and checks its enum against the nine shared names in `WorkloadReasonCodeContractTest`. The shared `k8s-reason-codes.json` belongs to this branch; once both branches land, the engine's test reads it instead of its own literal list. |
| R4 | Vertical engine | **Partly settled.** `ClusterAccessGuide` renders the contract's ClusterRole (§6, §6.1) and its customer-performed access-entry steps (§6.2). Still open: the Kubernetes user or group the access entry maps the XamOps role to, without which the guide can't offer a runnable `aws eks create-access-entry` command (D13). |
| R5 | Vertical engine | **Adopted in iteration 3** (§11.5, D16). When endpoints 2-4 get handlers, the change that flips `VERTICAL_WRITES_CONFIRMED` re-checks them against the contract. |
| R6 | `fix/autoscaler-controller-safety` (worktree `autoscaler-safety`, in progress) | When Go controllers pass live re-tests, readiness keys flip. Its plan (`plan.md` D2-D20) adds, or asks the backend for: a new `autoscaling-actions` collector with `dryRun` on every record (once the backend serves it, Activity's "Unverified" becomes exact; F4); new optional policy fields (`rebalancer.operationId`, `workloadRightsizerPolicy.minCpuMillicores`/`minMemoryMib`, `hpaManagerPolicy.lookbackHours`/`idleCpuMillicoresPerPod`), which the normalizer drops until they are added to `AutomationPolicyBundle` (safe: Go zero values are off or defaulted); a rebalancer AND-gate that includes `rebalancer.enabled` (the coercion table already serves `false`); `xamops-system` in the default critical namespaces (already in §6.6); `ignoreUnknown` on the strict DTOs (the service mapper change in §11.3 covers it). It keeps the strict collectors' key sets frozen, so nothing here breaks meanwhile. |
| R7 | Vertical engine | **Addressed on the engine branch.** `K8sClientFactory` disabled TLS verification (audit B7, `K8sClientFactory.java:23,49`). The engine branch's first code commit, `22bcec42d`, makes it verify the cluster CA and fail closed, and the engine's endpoints come after it on the same branch, so no deployment of the engine lacks the fix. Nothing is left for this branch; it was flagged because the UI presents the direct path as trustworthy. |
| R8 | Another session's uncommitted edits to `LoginOtpController.java` in the main checkout | The field `DEMO_EMAILS` still exists there, so the reflection test (§17) keeps working after that work lands. If it moves, the test fails with a pointer, by design. |
| R9 | Vertical engine | Capabilities reads the engine's switch, `xamops.automation.vertical.enabled`, by name through Spring's `Environment`, with `false` when absent (§11.3). If the engine renames the property, capabilities reports `false` and the UI shows V7: safe, but the engine owner has to tell this branch. |
| R10 | Vertical engine | **Readiness scope.** The contract refuses every policy `PUT` with `403 K8S_CONTROLLER_NOT_READY` while readiness is false (§3.1), yet calls `AUTO` "storable but inert". This design follows the refusal (§6.3), so customers can't move a workload to Recommend before readiness flips. Asked: accept `OFF` and `RECOMMEND` while not ready, since neither acts. If the engine agrees, the change that flips `VERTICAL_WRITES_CONFIRMED` narrows the §6.3 row, and V4's "Off and Recommend editable" holds before readiness. |
| R11 | Vertical engine | **Default mode.** With no policy row a workload is `OFF`, and the engine skips `OFF` workloads without a recommendation (E24), so a newly connected cluster shows none. Asked: default to `RECOMMEND`, which never acts, so recommendations (and the upgrade value a read-only customer sees) appear without a write. Until then the table says why it is empty (§8.1). |
| R12 | Vertical engine | **Agent-path signal.** `executionPathDetail.agent` is derived from `lastAgentCheckin` through `KubernetesClusterConfig.isAgentActive`, and the automation agent's heartbeat and pushes refresh that column too (E25). Asked: derive it from the XamOps agent's own data (its `k8s-resources` row, or its agent version), since that is the agent that will run the command queue. Also: `lastSeenAt` converts `lastAgentCheckin` with `ZoneOffset.UTC` (`VerticalAutomationReadService.agentPath`, `ad5581ecd`), but the column is written with server-local `LocalDateTime.now()` (`AgentController.java:371`), so on a non-UTC JVM the time is off by the zone offset; the clock rule in §6.2 is the fix. The UI labels the time "reported by the engine" and never uses it as XamOps agent health. |
| R13 | Vertical engine | **Idempotent submissions.** Endpoint 3's body has no `correlationId`; the engine creates it. A submission whose `202` is lost in transit can't be retried safely, so the UI never retries one (§9.2). Asked: accept an optional client-generated `correlationId` (or an `Idempotency-Key` header), which would make a retry answer `DUPLICATE`. |
| R14 | Vertical engine | **Entitlement refusal has no code.** Endpoint 1 calls `CloudAccountAccessService.requireAwsAccess`, which throws `AccessDeniedException`, and no handler on the engine's controller maps it, so `GlobalExceptionHandler.handleAccessDeniedException` answers `403 {"message": "Access is denied"}` with no `code`. The contract promises `403 K8S_NO_ACCOUNT_ACCESS`, and `RefusalDto.NO_ACCOUNT_ACCESS` exists unused. Asked: a controller-local handler. Until then the UI treats a codeless `403` from the engine as no account access (§11.2). |

### 19.2 Follow-ups (not in this branch)

| # | Follow-up | Why it is not here |
|---|---|---|
| F1 | Node-group, add-on and Fargate views from the EKS API (Phase 0 granted the actions), with AccessDenied → "Update connection" for older stacks | New backend calls; outside the allowed changes |
| F2 | Rebalancer safety: fit simulation, idempotency, completion state, env and policy gates AND-ed (harness §10 items 2 and 8) before `rebalancer` flips | Go agent; another workstream |
| F3 | Serve `hpa-recommendations`, labelled "Rule-based", flagging `minReplicas` below a PDB's `minAvailable` | New endpoint |
| F4 | Serve `vpa-recommendations` (measured 9/9) beside the engine's recommendation; serve `node-drain-progress` (it carries `reason: "dry-run"`); split the shared `autoscaling-events` row by producer | New endpoints and an ingest change |
| F5 | Surface `k8s-resources.podMetrics` in the dashboard so workload efficiency covers every pod | Dashboard DTO change |
| F6 | Single representation for `cloud_account_id` (B4) and region keying for configs and caches (B5) | Data migration |
| F7 | EKS version support status via `DescribeClusterVersions` | New backend call |
| F8 | Multi-account fleet and an Access column | Fleet scope |
| F9 | Set Kubescape, OpenCost and Karpenter display flags when the agent pushes that data | Ingest change |
| F10 | Savings ledger (baseline and realised savings), using the engine's actions feed | New service (roadmap C3) |
| F11 | In-place read-only → read-write connection upgrade that keeps the connection's history (deploy the read-write role, then update `accessType` and `roleArn`); until then the remedy goes through support (D1) | CloudFormation and account model |
| F12 | One guardrail model across the Go agent and the vertical engine | Two engines, two owners |
| F13 | Backend fleet aggregate endpoint | Avoids N dashboard calls; the brief deferred it |
| F14 | Authenticate `/api/v1/agent/status*`: it is under the `/api/v1/agent/**` permitAll (`SecurityConfig.java:278`) and returns any cluster's agent status by id | Security, pre-existing, outside this path |
| F15 | Entitlement checks on `EksController` and `CloudK8sController`: they resolve any AWS account id without `canAccess` (`EksController.java:35-41`), the same class of gap as E1 | Security, **high priority**, outside the allowed change list |
| F16 | Optimistic concurrency on policy saves: section saves are read-modify-write on one JSON row, so two admins saving different sections at once can lose one change | Rare; needs a version column |
| F17 | Platform CSRF posture: CSRF is disabled (`SecurityConfig.java:156`) and production sets `SameSite=None` (`application-prod.properties:80`), so a cross-site form POST carries the session. This branch's POST endpoints are refused while not ready. **Resolving F17 is a precondition for flipping `rebalancer` and `vertical-rightsizing`** (§6.7) | Platform-wide |
| F18 | Fix or remove the snapshot rightsizing engine (E7) | Superseded by the vertical engine; removal is a backend change |
| F19 | Container Insights freshness: `DescribeLogStreams` with `RECEIVING` / `STALE` / `NOT_RECEIVING`, thresholds validated on more than one cluster pair, `STALE` kept out of Needs attention until then (D3) | New AWS call; needs the owner's explicit allowance |
| F20 | Reject incomplete section bodies server-side (boxed request DTOs or a `JsonNode` completeness check) so API callers can't reset fields by omission (§6.5) | Changes every section DTO; the UI already sends complete sections |
| F21 | Stop the backend serving `clusterHealthScore: 100.0` for a cluster with no resources (`EksDashboardService.java:1692`): `null` there, after checking every consumer of the shared DTO | Dashboard-service change, outside the allowed list; the EKS UI no longer reads the field (D10) |
| F22 | Run the frontend contract test when the shared reason-code fixture changes: in `ci-cd.yml`'s changes job, also set `frontend=true` for `xamops-service/src/test/resources/k8s/` | Editing the workflow makes that CI run treat every service as changed, by the workflow's own rule (`ci-cd.yml:51-54`); drift is safe meanwhile (§17) |
| F23 | Return a clean `404` for unmapped `/api/**` paths: a `NoResourceFoundException` case in `GlobalExceptionHandler` (E23) | Platform-wide error handling; the UI classifies by `code`, so it doesn't depend on it |

---

## 20. Assumptions and open questions

Assumptions made rather than asked (the design review can overturn them):

1. **BillOps-only roles may not change customer automation, and nobody may while impersonating** (D2). Platform admins with a XamOps role (`ROLE_SUPER_ADMIN`, `ROLE_XAMOPS_ADMIN`) may, when signed in as themselves.
2. **The new permission is not back-filled** for restricted users (D2): automation was never available, so nobody loses anything.
3. **Connection status other than `CONNECTED` blocks automation writes** (`CONNECTION_NOT_VERIFIED`), matching the status half of the existing write-client rule; its `roleArn`/`externalId` checks don't apply to Kubernetes automation.
4. **Pre-configuring node automation without the agent is allowed by the server** and blocked only in the UI (§6.8).
5. **Spot interruption handling stays allowed** (§6.3): it is protective and not on the orchestrator's not-ready list.
6. **The vertical engine's published contract holds when endpoints 2-4 get handlers.** Its read endpoint matches the contract today; for the rest only the contract exists. §11.5's degradation rules cover missing fields, and the change that flips `VERTICAL_WRITES_CONFIRMED` re-checks them (D16).
7. **No new AWS API call is allowed in this branch**, so Container Insights is reported as configured or not, never as receiving (D3, F19).
8. **XamOps support handles read-only → read-write switches** (D1). The remedy copy says "Ask XamOps support to switch it" and does not promise how support does it or that history is kept.
9. **Restricted members get the automation permission only by an explicit, visible grant** (D2): a nested checkbox that "Select all" and module toggles never tick.
10. **Workload autoscaling ships view-only** until the engine's server-side checks are confirmed (D16), and stays so until `vertical-rightsizing` is ready, because the contract refuses policy writes before then (R10). Recommendations and Estimated savings carry the value wherever the engine reports them.
11. **For customers, Workload autoscaling reads "isn't turned on in this environment yet" (V7)** while the engine's switch is off, which it is in every profile today. The demo turns it on to show recommendations, labelled "Not yet validated on live clusters" (D12).
12. **The UI may be stricter than the engine**: it blocks Auto without an execution path or usage metrics, which the contract would store (§6.3). An Auto mode that can't act or measure does nothing, so blocking it loses nothing.

Open questions for the owner:

1. Should XamOps staff ever change a customer's Kubernetes automation, for example during support sessions? (This design's answer: SUPER_ADMIN and XAMOPS_ADMIN may when signed in as themselves; BillOps may not; nobody may while impersonating a customer user.)
2. F15 (read-path entitlement on the EKS dashboard and cluster list) is outside this branch's allowed changes but is the same class of issue as E1. Approve it as an immediate follow-up, or fold it into this branch?
3. Can support switch a connection's access level without removing it (customer deploys the read-write role; support updates `accessType` and `roleArn`)? If yes, the explainer can say history is kept; if no, F11 becomes the priority for any customer who wants automation.
4. May a later branch add `logs:DescribeLogStreams` calls for Container Insights freshness (F19)? Every role template already grants the action; the question is only whether a new AWS call is acceptable.

---

## 21. Traceability to the orchestrator updates

**Controller readiness update.**

| # | Decision | Where |
|---|---|---|
| 1 | Server-driven per-controller readiness gate | §6.7, §6.3, D4 |
| 2 | Rebalance trigger disabled in UI and blocked in backend | D7, §9.4, §6.6 |
| 3 | Activity never shows simulated actions as real | §9.6 |
| 4 | Recommendation display: floors flagged, no clamping, VPA beside it, snapshot engine not implied validated | D8 (floors, no clamping, snapshot engine not read); VPA column once served (F4) |
| 5 | HPA/VPA conflict warning | D8, §9.2 |
| 6 | HPA manager output "Rule-based" with PDB check | F3 (not served today) |
| 7 | No node-optimizer savings or interruptibility | §9.3 |
| 8 | Consolidation "not yet reliable", never "no candidates" | §9.3 |
| 9 | Complete policy bundles on every save, with a test | §6.6, §17 |
| 10 | Agent payload bugs (deployment counts, "resolved", requests) | E15, §10.4 |
| 11 | "Applies by restarting pods"; in-place apply | §9.2 (persist method), D16 |

**Vertical rightsizer update.**

| # | Decision | Where |
|---|---|---|
| 1 | Workload autoscaling built on the engine's API; new codes in the copy map | D8, D16, §9.2, §11.5, §6.1 |
| 2 | Apply methods: `IN_PLACE` default, `IN_PLACE_AND_PERSIST` labelled, opt-in, refused for GitOps | §9.2, §6.3 |
| 3 | Install: cluster API access and Prometheus as separate rows; `AGENT_NOT_INSTALLED` only for Go-agent controls | Revised by the published contract, which makes the XamOps agent's channel the primary path (E22): Install shows the XamOps agent, usage metrics and optional direct access separately from the automation agent, and `AGENT_NOT_INSTALLED` carries a detail naming which agent (D13, §10.7, §10.8, §6.1) |
| 4 | Readiness key `vertical-rightsizing`, not ready by default | §6.7, R1 |
| 5 | Demo fixtures for the engine's GETs; mutations behind `DEMO` | §16 |
| 6 | Ownership boundary; self-contained helpers; reconciliation points | §6.6, §19.1 |

---

## 22. Responses to design review 1

Review 1 (CHANGES_REQUESTED, 2 HIGH, 8 MEDIUM, 15 NIT) was written to `.agents/tasks/cloudk8s-ui-revamp-2026-10-09/design-review.md`, which review 2 has since replaced. This table records iteration 2's answers and is kept for traceability. Every finding was re-checked against the worktree before it was answered; all 25 were addressed and none ignored. The evidence behind those decisions is in E17-E21 (§3).

Three of these answers were revised in iteration 3, and §23 is authoritative for them: row 7 (gated fields used RAC read-only modes, and Select/Slider became text; now every blocked control carries `aria-disabled` and is greyed in place, §23 finding 3), row 19 (`window` validated as any non-empty string; now the contract's enums, §23 finding 1), and row 22 (the fleet stopped at the first engine `404`; now responses are classified by `code`, §23 finding 2).

| # | Sev. | Finding | Response | Where |
|---|---|---|---|---|
| 1 | HIGH | Server DEMO gate compares the username and fails open | **Addressed.** Demo is decided by the user row's email (loaded by principal id) against `DEMO_EMAILS`, mirroring `LoginOtpController.java:70-73`, which stays untouched. Tests use username "Xammer Admin" with the demo email, plus a reflection test that the two sets match. | E12, §6.1, §6.2, D2 rule 2, §6.6 step 7, §11.3, §17, R8 |
| 2 | HIGH | Panels re-homed "as is" break the design rules | **Addressed, extended.** No "as is" remains; a conformance pass covers every re-homed file with measured counts, deletes `styles/eks-details.css` and the `eks-details-page` class (all selectors checked as scoped), fixes the integration modal's checklist box and its other hue classes, and applies D10 null handling to `EksPerformanceTab`. Beyond the finding: Font Awesome classes become bundled icons (CLAUDE.md's SVG icon rule), and gradients are banned. `test/designRules.test.ts` guards the tree and has a self-test; it is in S3 and S4 "Done when". | E18, §4.3, D10, D13, §11.1, §14.1, §17, §18 |
| 3 | MED | Impersonation bypasses the BillOps exclusion | **Addressed.** While `ImpersonationContext.getImpersonatedUserId() != null`, writes get `NO_USER_PERMISSION` / `IMPERSONATION` with the suggested message; capabilities mirror it. Tests cover the header path and the JWT path. Open question 1 updated. | E17, §6.1, §6.6 step 7, D2 rule 3, §8.2, §17, §20 |
| 4 | MED | DEMO-first precedence contradicts the promised demo states | **Addressed.** In `can()`, `DEMO` is primary only when it is the only reason, otherwise last in `also` (and not repeated under "Also needs"); `editable` is true only when DEMO is alone. The server order is unchanged. Tests for both pairs named in the review. | §6.4, §8.2, §11.2, D12, §16, §17 |
| 5 | MED | Node-automation settings unreadable while automation is off | **Addressed.** `GET /{c}` always returns `autoscalingEnabled`, every section from `forUi` (the same effective policy the agent gets), and `policySource`; the UI labels defaults "Default · not saved". Test added. | E19, §6.6, §8.1 N1, §9.3, §11.3, §11.4, §17 |
| 6 | MED | "XamOps changes" never reach Activity | **Addressed.** `recentChanges` comes from the suggested type-filtered repository query; Activity merges it with `recentEvents` by time. Also: the `agent_events` fallback is removed (it would duplicate), and change timestamps are UTC instants so the merge orders correctly. Test with the agent row and newer heartbeats present. | E20, §9.6, §11.3, §11.4, §17 |
| 7 | MED | No gating contract for form fields; popover can't satisfy `aria-describedby` | **Addressed.** `GatedField` uses RAC `isReadOnly` for TextField, NumberField, RadioGroup, Checkbox and Switch. `Select` and `Slider`, which lack a read-only mode in RAC 1.16 (checked in `@react-types`), render their value as text. `ReasonText` is always in the DOM (`sr-only` in popover mode), and the lock is its own labelled tab stop. Commit-on-toggle switches use `GatedSwitch` (reads `allowed`). Tests added. | §6.5, §11.1, §17 |
| 8 | MED | "Must hold for API callers" not guaranteed for vertical writes | **Addressed.** R1 and R2 are release criteria for the vertical write controls. Until they are confirmed, `VERTICAL_WRITES_CONFIRMED = false` makes the adapter block every `vertical.*` action with `CONTROLLER_NOT_READY` and the suggested message; recommendations stay visible. §6.8's server guarantee is scoped to `/api/eks/autoscaling/**`. The engine can satisfy R2 by joining `GUARDED_PATHS`. | §1, §6.1, §6.2, §6.6, §6.8, D4, D16, §8, §9.2, §18 S4, §19 R1/R2 |
| 9 | MED | Serve-time kill switch has no field mapping for every key | **Addressed, adjusted.** One coercion table in the normalizer, each row tested on serve. Two adjustments: the retired Go rightsizer is coerced **unconditionally** and its `rightsizer-apply` key is removed, so no property can bring back a second vertical engine; and the `rebalancer` row also serves `enabled = false`, which closes the AND-gate the autoscaler-safety branch adds. The ingest guard (15) also closes the agent-ingest path the finding names. | §6.3, §6.6, §6.7, §11.3, §11.4, §17 |
| 10 | MED | D3 adds a new AWS API call | **Addressed with the fallback.** I found no allowance for the call, so the design keeps `DescribeLogGroups`, requires an exact name match, and reports `CONFIGURED` / `NOT_CONFIGURED` / `UNKNOWN`, labelled "Configured · data freshness not checked". Container Insights is no longer an attention criterion. Freshness is F19 and open question 4. | E10, §1, §4.1, D3, §10.7, §11.3, §12.1, §17, §19 F19, §20 |
| 11 | NIT | 16-endpoint review and the dual-identifier hazard | **Addressed.** Endpoint matrix (5 reads, 11 mutations); the stated rule that `accountId` is only matched against `CloudAccount.awsAccountId`; `PUT /enabled`'s own lookup removed; a cross-tenant-id test for `GET`, `PUT` and `PUT /enabled`; per-cluster `writeGate` with a stricter top level. | §6.6, §11.3, §11.4, §17 |
| 12 | NIT | Role names, remedy text, CONNECTION_NOT_VERIFIED rule | **Addressed.** Role from the user row with `ROLE_` normalisation; `user.detail` (`TAB_GRANT` / `ROLE` / `IMPERSONATION`) picks copy and remedy; `CONNECTION_NOT_VERIFIED` is status only, citation corrected. | §6.1, §6.2, D2, §11.4, §17, §20 |
| 13 | NIT | Error paths escaping the uniform body | **Addressed.** Refusals logged in `preHandle`; controller-local handlers for the three binding exceptions and for `Exception`; coercion and parse logs deduplicated per (config id, field set). | §6.6, §12.1, §17 |
| 14 | NIT | "Merge the section" is ambiguous | **Addressed, extended.** A section save replaces the whole section, and the UI sends complete sections. Also, an omitted `criticalNamespaces` keeps the stored list, so a partial API body can't unprotect namespaces. Full server-side completeness checking is F20. | §6.5, §6.6, §13, §19 F20 |
| 15 | NIT | Agents can overwrite the policy row | **Addressed (implemented, not backlogged).** The ingest refuses `autoscaling-policy`. Verified that neither agent pushes it (both only read it), so nothing legitimate breaks. Test added. | E21, §6.6, §6.8, §11.3, §12.1, §17 |
| 16 | NIT | Unbounded wildcard scan for automation-agent detection | **Addressed.** The query is bounded with `CreatedAtAfter(now − 24h)`; "ever seen" comes from the `agent_data_cache` projection already loaded. | §6.2, §11.3, §17 |
| 17 | NIT | `k8sPricing` guard would fail; provenance overclaims | **Addressed.** The guard is scoped to the CloudK8s tree; the provenance string is shortened and notes that 0.0416 equals the t3.medium price. | D9, §17 |
| 18 | NIT | CSV guard and `hideBelowPx` | **Addressed.** Tab and carriage return added, string cells only. Column `priority` is introduced; only `low` columns hide, and gated, status and action columns are `essential`. Test at 1280 px. | §11.1, §13, §14, §17 |
| 19 | NIT | `window` has two types | **Addressed.** Validated as a non-empty string; format left to the engine. | §9.2, §13 |
| 20 | NIT | Shared fixture has no location | **Addressed.** `xamops-service/src/test/resources/k8s/k8s-reason-codes.json`; JUnit loads it from the classpath; vitest reads it with `node:fs` (CI checks out the full repo). | §6.1, §17 |
| 21 | NIT | Access chip source and cache keys | **Addressed.** The chip and status strip read capabilities `connection.accessLevel` ("Unknown" on failure). Caches are keyed `userKey\|accountId\|region\|clusterName` and cleared on account, profile or impersonation change. | §4.1, §4.2, §15, §17 |
| 22 | NIT | Fleet fan-out triggers synchronous syncs | **Addressed.** Dashboards are fetched only for clusters with XamOps agent data; vertical status for every listed EKS cluster, stopping at the first `404`. | §11.2, §15 |
| 23 | NIT | New key inside the tab pickers | **Addressed with the nested checkbox.** The key lives in `XAMOPS_TAB_PERMISSIONS`, outside `XAMOPS_MODULES`, so "Select all" and module toggles never grant it; `normalizeAllowedTabs` drops it without its parent. As a consequence there is no new FAQ entry, no keyword overlap, and `FeatureFaqCatalogTest` is unchanged. | D2, §11.1, §11.3, §17, §20 |
| 24 | NIT | F17 as a precondition for POST-triggered keys | **Addressed.** Resolving F17 is a precondition for flipping `rebalancer` and `vertical-rightsizing`. | §6.3, §6.7, §19 F17 |
| 25 | NIT | §1 overstates what the server holds back | **Addressed.** Now reads "every live or apply control except spot interruption handling". | §1 |

**Corrections to assumptions the review marked wrong or partly right.** Each is corrected where the finding is answered above, and three also stand alone:

- the integration modal's `initialSection` union now includes `part4` (D13);
- E14 now states that nothing sets `opencostEnabled`;
- the k8s-resources push interval the review couldn't trace is 300 s by default (`xamops-agent/internal/config/config.go:85`), as §6.2 assumed.

**Not verifiable at iteration 2.** These remained unverifiable then: everything about the vertical engine's contract (no code, no contract file; published since, §23), the brief's allowance list beyond the task text (finding 10 was resolved without needing it), and the cast.ai comparison in §2.

---

## 23. Responses to design review 2

Review: `.agents/tasks/cloudk8s-ui-revamp-2026-10-09/design-review.md` and `design-review.json` (CHANGES_REQUESTED, 0 HIGH, 4 MEDIUM, 14 NIT). All 18 findings are addressed in this iteration; none is backlogged or ignored. Each was re-checked against the code or the contract before it was answered, and the "Response" column says where the answer differs from the suggested fix and why. The new evidence is E22-E25 (§3).

| # | Sev. | Finding | Response | Where |
|---|---|---|---|---|
| 1 | MED | The published engine contract changes flows, not only the adapter | **Addressed: contract adopted now**, with `VERTICAL_WRITES_CONFIRMED` still `false`. (a) **Switch**: V7 "Off in this environment", evaluated first, with no setup remedies and the vertical Data sources rows and Install cards hidden. Capabilities reports the switch too (finding 2), so no engine call is made while it is off. (b) **Execution path**: gates come from `executionPath` and `executionPathDetail`, never from `clusterAccess`. `NONE` gives `AGENT_NOT_INSTALLED` with detail `EXECUTION_PATH` and XamOps agent copy and remedy. Direct-path codes only explain the optional path. §1, D13 and Install now describe the agent-primary model. (c) **Async apply**: `apply(ref, { containers, applyMethod, dryRun })`. Simulate is the default in the dialog. `202` reads "Submitted · waiting for the result", outcomes are polled by `correlationId`, `DUPLICATE` and `REFUSED` have their own copy, and nothing is resent automatically. (d) **Readiness scope**: the scoping table in §6.3 follows the contract, so `CONTROLLER_NOT_READY` from the engine blocks every policy `PUT`; V4 now says so. R10 asks the engine to accept Off and Recommend, the review's alternative. (e) **Actions feed**: `{ entries, nextBefore }` with paging, all five outcomes (`PARTIALLY_APPLIED` "Partly applied", `EXPIRED` "Expired, not applied"), `triggeredBy` and "Attempt n". (f) **`recommended: null`** reads "No change recommended"; "No recommendation" is kept for skip reasons and `INSUFFICIENT_HISTORY`. (g) **Enums** validated as the contract defines them. Beyond the finding: the default mode Off means no recommendations until a write (E24, R11); the agent-path signal can come from the automation agent's heartbeat, and its time is converted with the wrong zone (E25, R12); a lost `202` can't be retried safely (R13); endpoint 1 as committed (`ad5581ecd`) sends `clusterAccess: null` while off and a codeless `403` for an unentitled caller, both handled (E22, R14); and every engine enum (skip reasons, findings, reasons) has copy. | E22, E24, E25, §1, §4.1, §4.2, §6.1, §6.2, §6.3, §6.4, D4, D5, D8, D13, D16, §8.1, §8.2, §9.1, §9.2, §9.6, §10.7, §10.8, §11.1, §11.2, §11.5, §12.2, §13, §16, §17, R1, R5, R10-R14 |
| 2 | MED | The engine 404 rule marks the whole fleet unavailable | **Addressed.** Capabilities serves `verticalEngine.enabled`, read with `environment.getProperty("xamops.automation.vertical.enabled", Boolean.class, false)` (R9); while it is false no engine request is made. Responses are classified by `code`: `K8S_CLUSTER_NOT_REGISTERED` marks that row only (V8); a coded or codeless `5xx` marks that row V5; only a codeless `404` stops the fan-out. One step further than the suggested fix: clusters capabilities doesn't list aren't requested at all, since the engine would answer "not registered" for them. Status and actions are independent reads, and an actions failure stays in Activity. E23 records the likely `500` for unmapped paths, and F23 is the platform fix. The suggested tests are in §17. | E23, §6.2, §8.1, §11.2, §11.3, §11.4, §12.1, §12.2, §15, §17, R9, F23 |
| 3 | MED | Gated switches and fields don't use `aria-disabled`; Select and Slider disappear | **Addressed, without needing an exception.** Every blocked control now carries `aria-disabled="true"` and `aria-describedby` and is greyed in place. `GatedSwitch` and blocked checkboxes and switches render the suggested `role="switch"` (or `role="checkbox"`) replica. Text and number inputs keep `isReadOnly` and add `aria-disabled` through RAC's `Input`; NumberField drops its steppers, which RAC would give `isDisabled`. Select and Slider render the suggested greyed `aria-hidden` replica inside a focusable `role="group"`, whose `aria-label` also carries the value. The review's version kept read-only modes for some draft fields and asked for the owner's acknowledgement. This version needs none: no blocked control relies on `aria-readonly` alone, so the literal rule holds everywhere, which matters because this step can't wait for the owner. Radio groups needed per-option blocking (Auto within Mode, persist within the apply method), which RAC can only do with `isDisabled`, so `GatedRadioGroup` is a small custom WAI-ARIA radio group. Tests updated. | §6.5, §11.1, §14, §17 |
| 4 | MED | S3's exit criterion can't be met | **Addressed as suggested.** The dead files (`EksAutoscalingTab.tsx`, `useEksAutoscaling.ts`, `EksHeader.tsx`, `CloudK8sHelpModal.tsx`) and the replaced shell files are deleted in S3. `EksDiagnosticsPanel.tsx` is restyled in S3. `PENDING_FLEET_REWRITE` lists the fleet files S5 rewrites, and two tests guard it: a listed file must exist and must still fail a rule. S5's "Done when" requires the list to be empty. `CloudK8sHeader.tsx` is on the list too: it fails the guard (a colour-only status dot, `:15`) and is restyled in S5, which the review's list missed. `CloudK8sHelpModal.tsx` isn't on it, because it is dead and goes in S3. | D11, §11.1, §14.1, §17, §18 |
| 5 | NIT | Capabilities compares two clocks without saying how | **Addressed as suggested.** The rule is in §6.2, the `K8sCapabilitiesService` row and §11.4, and the test runs under `TimeZone.setDefault(Asia/Kolkata)`. | §6.2, §11.3, §11.4, §17 |
| 6 | NIT | Two coercion rows cite agent behaviour that doesn't exist | **Addressed as suggested.** Verified: `main.go:255-256` only analyses and `:271-272` only collects; neither `Apply` is called. | §6.7 |
| 7 | NIT | §14.1 under-counts what the guard flags | **Addressed.** A chromatic-rgb column and pass rule 1 now include it. Re-measured with the guard's rules: 61 values, not 60. The extra one is space-separated (`rgb(124 58 237)`, `EksAutoscalingTab.tsx:145`), so the guard is specified to match both syntaxes. | §14.1 |
| 8 | NIT | "Also needs" has no copy, and `also` can repeat a code | **Addressed.** §6.1 has a short label per reason and per detail, and `can()` de-duplicates `also` by code, keeping the first message. Two labels differ from the suggestion: `NO_METRICS_SOURCE` is "a usage metrics source", because under the contract the source needn't be Prometheus; and `AGENT_NOT_INSTALLED` has one label per detail, because it now names two different agents. | §6.1, §6.4, §11.1, §17 |
| 9 | NIT | V3 and §9.1 contradict D16 | **Addressed.** V3 follows V4's rule, now stated against both the contract block and readiness (finding 1d). §9.1 reads "Recommendations only…" with the message for whichever block holds. | §8.1, §9.1 |
| 10 | NIT | Commit model for Scope and guardrail toggles unspecified | **Addressed as suggested.** Scope is a draft radio group plus Save, with confirm dialogs for Paused and Full. Guardrail toggles are `GatedSwitch`, and loosening confirms first. §6.5 adds the general rule (anything that sends a request is a commit) and a `draft` kind for Add and Remove in the namespace list. §9 names the wrapper for every control. | §6.5, §9.1, §9.3, §9.4, §9.5 |
| 11 | NIT | Rebalance savings figures are always zero | **Addressed as suggested.** Verified: `main.go:168-175` builds `RebalanceStatus` without `SavingsPercent`. Both fields are on D9's never-rendered list, with the test. | D9, §9.4, §17 |
| 12 | NIT | The reason-code contract test doesn't run on backend-only changes | **Addressed by rewording, with the CI change as F22.** Editing `ci-cd.yml` makes that run treat every service as changed (`ci-cd.yml:51-54`), which reaches far beyond this nit, and drift already degrades safely. §6.8 and §17 now state the real guarantee. | §6.8, §17, F22 |
| 13 | NIT | The capabilities endpoint must run on the request thread | **Addressed as suggested**, with the reason, plus a test that impersonation set on the calling thread is reported. | §11.3, §17 |
| 14 | NIT | The backend still serves the fabricated health score | **Addressed by the second option**: D10 states that the field is retained for compatibility and never rendered. The DTO is shared with `AgentK8sDashboardMapper`, which computes the score from pod counts (`:169`), and dashboard-service changes are outside the allowed list. Setting `null` is F21. | D10, F21 |
| 15 | NIT | The impersonation reason has a remedy | **Addressed as suggested.** | §6.1 |
| 16 | NIT | One cost label and one predicate are imprecise | **Addressed as suggested.** Source 3 is labelled "XamOps cost exporter (Prometheus)". Namespace and workload costs read "OpenCost" only when `openCost.enabled` is true and the list is non-empty. The Prometheus path's usage-weighted split is labelled "Estimated" with its method. | D9 |
| 17 | NIT | The demo capabilities fixture needs per-cluster agent states | **Addressed as suggested**, using the demo's real cluster names. The vertical fixtures move to the contract's shapes: prod V4, eu V2, staging not registered, and simulations only in the actions fixture. | D12, §16 |
| 18 | NIT | One more exception message reaches clients | **Addressed as suggested**, with the case added to the "no exception message in bodies" test. | §6.6, §11.3, §12.1, §17 |

**Corrections to assumptions the review marked wrong or partly right.** All are corrected where their finding is answered: adapter-only adoption and "no agent needed" (finding 1), "404 means not deployed" (finding 2), the coercion evidence (finding 6), `EksDiagnosticsPanel` "unchanged" (finding 4), and "both sides fail" (finding 12). Two partly-right points are corrected as well. The guarded-scope counts now include chromatic rgb (finding 7). The claim that both agents read `autoscaling-policy` is narrowed to what matters: only the automation agent reads it, and no agent pushes it (§6.6).

**Not verifiable at iteration 3.** What this application returns for an unmapped `/api/**` path (E23, reasoned from `GlobalExceptionHandler`, not run); the production JVM time zone (finding 5, made irrelevant by the clock rule); the engine's write endpoints, which have no handlers, so R1, R2 and R10 can't be checked in code; whether the engine accepts R10-R14; the engine worktree's uncommitted changes, which were not read; and the cast.ai read-only comparison in §2. Endpoint 1 was checked against its committed code (`ad5581ecd`) by reading, not by running it. No build or test was run for this document; it changes no code.
