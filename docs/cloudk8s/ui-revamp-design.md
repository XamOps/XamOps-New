# CloudK8s UI revamp: observability and automation console

| | |
|---|---|
| Status | Design, iteration 1. Gated by design review before any code is written. |
| Branch / worktree | `feat/cloudk8s-ui-revamp` at `/Users/apple/Desktop/XamOps-New/.worktrees/cloudk8s-ui-revamp` (base `9c4ace7b3`). Not merged to `main`; left for owner review. |
| Scope | EKS first-class. GKE and AKS keep their current pages; a later optional slice adopts the shared components. |
| Inputs read | `CLAUDE.md`, `DESIGN.md`, impeccable + redesign skills, new-page checklist, mockup skill, WAR critique, feature audit (§2/§4/§5), roadmap (§2/§5.2), automation harness report (§1/§2/§5/§7/§10) and its captures, the orchestrator's two design updates (controller readiness; the Java vertical rightsizer), plus the source files cited inline. |
| External dependency | Workload autoscaling consumes the **Java vertical rightsizer** being built in `.worktrees/java-vertical-rightsizer`. Its API is **provisional** here (§11.5) until `.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md` is published; at design time neither the file nor the worktree exists yet. Everything that depends on it is isolated behind one adapter. |
| Stack (locked) | Frontend: React 19, TypeScript 5.9, Vite 5, Tailwind 3, react-router 7, `react-aria-components` 1.16 (Tabs/TabPanel, Tooltip, Dialog), `@iconify/react` with the generated offline bundle (`src/icons/bundle.ts`), vitest + Testing Library. Backend: Spring Boot 4.0.8, Java 25, Spring Data JPA, plain `mvn`. **No new npm or Maven dependencies.** |

---

## 1. Overview

The CloudK8s console becomes two surfaces. The fleet page (`/cloudk8s`) answers "which clusters need me, what do they cost, and how far is automation rolled out". The cluster page (`/eks-details`) keeps the URL but replaces ten flat tabs with seven primary sections, each with subtabs, and makes Automation a first-class section. Every number on screen comes from a named source with a freshness label. Anything the backend does not report renders as "not reported", never as zero.

Automation is the part that needs the most care. The dormant 93 KB autoscaling tab is not switched back on. It is decomposed into Automation subtabs that read from a fixed hook (no fake success, no fake rebalance completion), and every control is wrapped in one capability model. A control is either usable, or visibly disabled with a specific reason and a path to enable it. The server enforces the same rules: it refuses mutations from read-only connections, from users without permission and from demo identities, and it refuses (and never serves) a policy that turns on a controller that failed safety validation. The measured harness verdicts mean that, today, every live or apply control is held by the server, so the honest state of the product is "recommendations and analysis, no automated changes yet". The design makes that state look deliberate: a readiness checklist names each gate, its reason and its remedy.

Automation has two engines, and the UI says which one is behind each control. **Workload autoscaling** (vertical pod rightsizing) is the new Java engine in `xamops-service`. It reconciles per-workload policy, computes p95 recommendations from Prometheus with headroom, floors and hysteresis, and resizes pods in place through the Kubernetes `resize` subresource by calling the cluster's API server from the backend. It needs cluster API access and a reachable Prometheus, not the in-cluster agent. **Node automation** (consolidation, node optimizer, rebalancing, HPA manager) remains the Go automation agent, gated by `AGENT_NOT_INSTALLED`. The Go rightsizer is retired as a product engine: the UI no longer configures it and the server serves it disabled.

Small, justified backend changes ship with the UI: a guard interceptor and access service for `/api/eks/autoscaling/**` (tenant scoping plus both read-only gates), a policy normalizer with per-controller readiness configuration, a capabilities endpoint, a Container Insights freshness check, and a FAQ/permission-catalog update. Nothing under `cloudformation/`, `xamops-agent/`, `xamops-autoscaler/`, AutoSpotting, Login or the vertical engine's packages changes; the engine is consumed through its API only, and the shared pieces are listed as reconciliation points (§19).

---

## 2. What "read-only" means here (decision 2, resolved)

"Read-only role" covers two independent gates. **Either one disables every automation action.** Neither hides anything observational: recommendations, consolidation analysis, projected savings and policy values stay visible, because that visible value is the upgrade path.

| Gate | Meaning | Source of truth | Reason code |
|---|---|---|---|
| (a) Connection access level | The AWS account was connected with the read-only CloudFormation role (`xamops-readonly-role.yml`) rather than the read-write one. | `CloudAccount.accessType` (`"read-only"` / `"read-write"`), exposed to the UI as `Account.access`. See D1. | `READ_ONLY_CONNECTION` |
| (b) User permission | The signed-in XamOps user is not allowed to change CloudK8s automation. | New tab-access key `cloudops-cloudk8s-automation` in the existing `user_tab_access` model, plus role and demo checks, evaluated server-side. See D2. | `NO_USER_PERMISSION` (and `DEMO` for the shared demo identities) |

Gate (a) is a consent signal, not a technical dependency. The Go automation agent acts through its own in-cluster ClusterRole (`xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml:41-140`); the vertical engine acts through an EKS access entry mapped to a minimal ClusterRole. In both cases Kubernetes RBAC, not the AWS role's IAM policy, decides what can change in the cluster. A customer who chose read-only access has told us "observe, don't act", and cast.ai's read-only mode means the same, so we honour it for Kubernetes too. The copy says so plainly ("Read-only connection. Automation changes need read-write access.") and does not claim AWS permissions are missing.

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
| E10 | The "Connected" badge is true whenever `logs:DescribeLogGroups` succeeds: the response is discarded, so it does not even check the group exists. OTEL-based Container Insights writes nowhere XamOps looks. | `EksService.java:202-209`; harness §8 (8 vs 0 datapoints, 7.5 vs 20.2 min) | Freshness check with `logs:DescribeLogStreams`, which every role template grants (D3). |
| E11 | No self-service path upgrades read-only to read-write while keeping the connection. The change-set path picks the template matching the current access type and refuses to replace a connection role. A second, read-write connection for the same AWS account is impossible because the AWS account id is unique. Removing the connection first deletes its data in XamOps. | `StackUpdateService.java:345-363` ("never a read-write template on a read-only account"), `:93` (`CONNECTION_ROLES`); `CloudAccount.java:24-25` (`@Column(unique = true)` on `awsAccountId`); `AwsAccountService.deleteAccountById:511-548` (cluster configs, agent data, team access grants, financial records) | The read-only remedy is "Get read-write access": XamOps support first, the reconnect path with its cost stated, never a bare link to Add Account. The stack update is the remedy for missing AWS permissions (D1). |
| E12 | Demo routing is method-blind in `apiFetch` and does not route `/api/eks/autoscaling/**`, so demo sessions reach the real backend. The BillOps demo user is a platform admin with password `password`. | `apiClient.ts:149` (first pass ignores method), `main.tsx:29` (method-aware second pass); `DataInitializer.java:51-52` | Demo fixtures for every GET; every mutation shape returns `403 K8S_DEMO` regardless of method; server also refuses demo identities (§16). |
| E13 | The setup-guide CI job fails any `docker.io/xamops/...` image a guide references that is not on Docker Hub. The automation image is not CI-built. | `.github/workflows/setup-guide-validation.yml` ("Verify every docker.io/xamops image"); audit §2.5 | Automation agent guidance lives in a new component outside the validated files and gives no runnable install command (D13). |
| E14 | Kubescape, OpenCost and Karpenter data pushed by the agent is silently hidden: the display flags are only set by the dead kubeconfig endpoint. | `CloudK8sSecurityController.java:147-149` is the only writer of the flags; `EksDashboardService.java` gates on them | Data sources shows "Received, not displayed" instead of "not installed" (§10.7); flag fix is a follow-up. |
| E15 | Agent payload quirks: `namespaces[].deploymentCount` is always 0; `pendingPodsResolved` counts currently pending pods; deployments carry no requests; per-pod usage (`podMetrics`) is collected but never reaches the dashboard. | Harness §6; `AgentDataTransformerService.java:1062` parses `podMetrics`, `EksDashboardService` never reads it | Derive counts from `deployments[]`, never label "resolved", aggregate requests from pod containers, show usage only where reported (§10). |
| E16 | Owner decision: vertical rightsizing moves to a new Java engine (p95 from Prometheus, headroom, floors, hysteresis, in-place resize with zero restarts, skips mid-rollout, HPA-volatile and `xamops.io/lock: active` workloads). The Go rightsizer is no longer the product's vertical engine. | Orchestrator notification (2026-10-10); provisional contract §11.5 | Workload autoscaling is built on the engine's API through one adapter; Go rightsizer controls are removed and the server serves `workloadRightsizerPolicy.enabled: false` so two vertical engines never act on one workload (§6.6). |

---

## 4. Information architecture

### 4.1 Fleet: `/cloudk8s`

```
CloudK8s (fleet, selected AWS account)
├── Topbar: "CloudK8s" · account access chip (Read-only / Read-write) · Connect a cluster · Refresh
├── Summary strip (6 tiles)
│   ├── Clusters            count · "n need attention"
│   ├── Nodes               sum over reporting clusters · "from n of m clusters"
│   ├── Monthly cost        sum over clusters with a cost source · source label · "from n of m"
│   ├── Potential savings   Estimated, from eligible vertical-rightsizer recommendations only · "—" when none
│   ├── Automation          Workloads: "x of m clusters" with the vertical engine configured · Nodes: "y of m" with the automation agent
│   └── Needs attention     Not monitored · Data stale · Container Insights not receiving · Nodes not ready · Failed pods
├── Filter bar: search · region · status chips (Monitored, Not monitored, Stale, Needs attention) · Clear all · Export CSV
├── Cluster table (sortable, paginated 25/50/100)
└── Connect a cluster (side panel): XamOps agent setup · cluster API access · Prometheus · automation agent guidance
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
| Automation | two lines: Workloads from the vertical engine status (Off / Recommend / Auto counts, or the blocking engine reason); Nodes from capabilities `automationAgent` + `nodeAutomation.derivedState` | "Not configured" / "Not installed" |

The brief also listed an Access column. Access is per account, and the fleet shows one account (`CloudK8sPage.tsx:35` uses `selectedAccountIds[0]`), so a per-row Access column would repeat one value on every row. It is shown once, as the access chip in the topbar and summary strip, and becomes a column only when the fleet spans more than one account (follow-up F8). Version support badges are deferred (D15).

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
| | Workload autoscaling `workload-autoscaling` | Vertical rightsizer: per-workload mode, current vs recommended requests and limits per container, findings, apply method, approved apply, HPA conflicts | `GET /api/eks/automation/vertical/{c}` (provisional), `k8sHpas` |
| | Node autoscaling `node-autoscaling` | Consolidation analysis ("not yet reliable"), evictor settings, node optimizer settings, scale-out and node templates (not built) | `/evictor-candidates`, `/node-fleet`, policy |
| | Rebalancing `rebalancing` | What it does, why it is blocked, last reported status | `/rebalance-status`, policy |
| | Guardrails `guardrails` | Locked and editable safety guards, protected namespaces (Go agent policy) | policy |
| | Activity `activity` | One feed, three sources: vertical engine actions (with their `dryRun` flag), automation-agent reports (unverified), cluster events and XamOps changes | `GET /api/eks/automation/vertical/{c}/actions` (provisional); policy `recentEvents` |
| **Security** `security` | Vulnerabilities `vulnerabilities` (default) | Trivy (re-homed from Security tab) | `trivy` |
| | Compliance `compliance` | Kubescape (`KubescapeComplianceView`) | `kubescape` |
| | Runtime `runtime` | Falco (`RuntimeSecurityView`) | `falcoEvents`, `securityMetrics` |
| **Data sources** `data-sources` | Connections `connections` (default) | Every source with status, last data, what it powers, remedy; includes cluster API access and Prometheus for the vertical engine | capabilities; cluster list; vertical engine status |
| | Install `install` | Setup cards: XamOps agent guide parts, cluster API access, Prometheus via the API proxy, automation agent guidance | `EksIntegrationModal`; `ClusterAccessGuide`; `AutomationAgentGuide` |

The topbar keeps the cluster name as title (`EksDetailsPage.tsx:86`), back link, version and region, an agent freshness label, Guide, and Sync. A one-line status strip sits above the tabs: overall health, nodes ready, pods running, automation state, access. `?debug=true` still renders `EksDiagnosticsPanel` above the tabs.

### 4.3 Where today's content goes

| Today (`EksTabs.tsx:18-27`) | New home | Change |
|---|---|---|
| Overview | Overview › Summary | Composite score and "savings opportunity" removed (E8, E9); KPI tiles, namespace and top-pod panels kept |
| Performance | Nodes › Utilization | As is |
| Workloads | Workloads › Workloads | `clusterHealthScore` display removed; requests and recommendation columns added |
| Pods | Workloads › Pods | Pagination; usage columns only where reported |
| Containers | Workloads › Containers | As is, usage only where reported |
| Nodes | Nodes › Nodes | As is |
| Resources | Workloads › Resources (Events → Overview › Events) | Refined from the brief's "nodes/utilization": `EksResourcesTab.tsx:127-604` renders HPAs, PVCs, Services, Ingresses, Events and Jobs, which are workload objects, not node utilisation |
| Security | Security › Vulnerabilities / Compliance / Runtime | Split along its existing views |
| Cost | Cost › Cluster / Namespaces / Workloads / Nodes | Multipliers removed, sources labelled |
| Karpenter | Nodes › Karpenter | As is |
| (Autoscaling, commented out) | Automation › six subtabs | Rebuilt, not re-enabled (§9) |

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

| Reason | Meaning | Who computes it | Enforced by | Visible text (exact) | Remedy |
|---|---|---|---|---|---|
| `DEMO` | Shared demo identity | UI: `isDemoMode()`; server: username in demo set | UI + server | "Demo workspace. Changes aren't saved." | none |
| `PROVIDER_UNSUPPORTED` | GKE/AKS cluster | UI from account provider | UI (server only serves EKS) | "Automation is available for EKS clusters." | none |
| `CONTROLLER_NOT_READY` | The controller behind this control failed safety validation, or is not built | server config (§6.7), served by capabilities | server + UI | Per control, server-provided; generic: "Automated apply is disabled until this controller passes safety validation." | "Why?" explainer (not an enablement path) |
| `ANALYSE_ONLY` | Live consolidation (evictor) | same readiness config, key `evictor-live` | server + UI | "Live consolidation is disabled until pod-fit simulation is available." | "Why?" explainer |
| `NO_USER_PERMISSION` | User may not change automation | server (D2), served by capabilities | server + UI | "You don't have permission to change automation." | "Ask an administrator to grant CloudK8s automation in My Team." |
| `READ_ONLY_CONNECTION` | Account connected read-only | server (D1), served by capabilities | server + UI | "Read-only connection. Automation changes need read-write access." | "Get read-write access" → explainer with "Contact support" → `/tickets` (D1) |
| `CONNECTION_NOT_VERIFIED` | Account status is not `CONNECTED` | server, same rule as `AwsClientProvider.getAutoScalingWriteClient:231-238` | server + UI | "This connection isn't verified. Reconnect the account to change automation." | "Open Account Manager" → `/account-manager` |
| `AGENT_NOT_INSTALLED` | No automation agent heartbeat for this cluster (variant: the XamOps agent is missing too). Applies **only to Go-agent controls**: node autoscaling, consolidation, node optimizer, rebalancing, guardrails and the HPA manager | capabilities | UI only (see §6.8) | "The automation agent isn't installed on this cluster." / "This cluster isn't monitored yet. Install the XamOps agent first." | "Install the automation agent" → `data-sources/install#automation-agent` |
| `CLUSTER_ACCESS_NOT_CONFIGURED` | Vertical engine: no EKS access entry maps the XamOps role into the cluster | vertical engine | engine + UI | "XamOps can't reach this cluster's API yet. Add an EKS access entry for the XamOps role." | "Set up cluster access" → `data-sources/install#cluster-access` |
| `CLUSTER_UNREACHABLE` | Vertical engine: access is configured but the API server cannot be reached (for example, a private endpoint) | vertical engine | engine + UI | "XamOps can't reach this cluster's API server. Private endpoints need a network path from XamOps." | "See access options" → `data-sources/install#cluster-access` |
| `NO_METRICS_SOURCE` | Vertical engine: no Prometheus reachable through the Kubernetes API service proxy | vertical engine | engine + UI | "No Prometheus is reachable for this cluster, so XamOps can't compute recommendations." | "Set up Prometheus" → `data-sources/install#prometheus` |
| `INSUFFICIENT_HISTORY` | Vertical engine: not enough usage history for the policy window | vertical engine | engine + UI | "Not enough usage history yet. Recommendations need `<window>` of data." | none (waiting state; shows the window and, if the engine reports it, when it will be met) |
| `GITOPS_MANAGED` | Vertical engine: the workload is managed by Argo CD or Flux, so its template must not be patched | vertical engine (per workload) | engine + UI | "Managed by Argo CD or Flux. XamOps won't patch its template." | "Use in-place resize" (switch the apply method) |
| `MISSING_AWS_PERMISSION` | A data view needs an AWS action the role lacks (role predates the EKS read policy, or was edited) | backend status field (e.g. Container Insights `UNKNOWN` with reason) and the existing permission-gap feed | UI (data views only) | "XamOps can't read `<action>` in this account." | "Update connection" → `/account-manager` (stack update) |
| `UNKNOWN` | Capabilities, or the vertical engine status, could not be loaded | UI | UI (fail closed) | "Couldn't check automation access." | "Retry" |
| `GUARDRAIL_LOCKED` | A guardrail that cannot be loosened | server normalizer | server (UI renders locked rows as static text) | "Always on while automation lacks pod-fit simulation." | none |

An unknown code from either server is rendered generically: the server's `message` (plain text, length-capped) under the label "Not allowed", with the code in the tooltip, and the control stays disabled. New engine codes therefore degrade safely before the map learns them.

`MISSING_AWS_PERMISSION` never gates automation controls: Kubernetes automation does not use AWS data APIs. It gates data views that do. (The vertical engine authenticates to the cluster through the XamOps role and an access entry; failures there surface as `CLUSTER_ACCESS_NOT_CONFIGURED` or `CLUSTER_UNREACHABLE`.)

**Error codes that are not gates.** The refusal body (§6.6) also carries six codes that never disable a control and never appear in capabilities: `K8S_UNAUTHENTICATED` (401), `K8S_INVALID_REQUEST` (400, malformed path or query), `K8S_INVALID_POLICY` (400, `field` set, §13), `K8S_NO_ACCOUNT_ACCESS` (403, shown page-level as "You don't have access to this account."), `K8S_CLUSTER_NOT_REGISTERED` (404, "This cluster isn't registered with XamOps yet. Install the XamOps agent first.") and `K8S_INTERNAL` (500, fixed message). `K8sAutomationReason` holds both groups with a `gate()` flag. The frontend `ReasonCode` type covers the gates only; `K8sApiError` maps the error codes per §12.2. The shared `k8s-reason-codes.json` fixture (§17) lists both groups.

### 6.2 How each gate is sourced

- **Connection access level** (`READ_ONLY_CONNECTION`). `CloudAccount.accessType` (`CloudAccount.java:31`), written at onboarding (`AwsAccountService.java:145-152`) and already the gate for every other XamOps write path: `RightsizingApplyService.java:76`, `WasteDeleteService.java:77`, `GcpOptimizationService.java:2673`, `AwsClientProvider.java:231`. The UI already reads it as `Account.access` (`types/account.ts:12`, `RightsizingPage.tsx:26`, `WastePage.tsx:17`, `EbsRightsizingPage.tsx:67`). Anything other than `"read-write"` (case-insensitive), including null on legacy rows, is read-only. See D1.
- **User permission** (`NO_USER_PERMISSION`, `DEMO`). Computed server-side by `K8sAutomationAccessService` from the principal (`ClientUserDetails`), its roles, and `UserTabAccessRepository.findTabNamesByUserId` (`UserTabAccessRepository.java:16-17`). See D2.
- **Automation agent installed** (`AGENT_NOT_INSTALLED`). Computed by the capabilities endpoint from `agent_events` rows and `agent_data_cache` rows whose `agentVersion` ends with `-autoscaler` (the pusher hardcodes `1.0.0-autoscaler`, `pusher.go:31`; heartbeats and ingests both record it, `AgentController.java:376-377`, `AgentIngestService.java:456-487`). Connected if seen within 5 minutes (heartbeat every 60 s, `config.go:62`), stale after that, not installed if never seen.
- **XamOps agent** (variant of `AGENT_NOT_INSTALLED`, and the "Not monitored" state). The `k8s-resources` row's `collectedAt` in `agent_data_cache` (only the observer agent pushes it). Connected within 15 minutes, the same window as `KubernetesClusterConfig.isAgentActive:251-254`; push interval is 300 s.
- **Controller readiness** (`CONTROLLER_NOT_READY`, `ANALYSE_ONLY`). `xamops.k8s.automation.readiness.*` properties (§6.7), served by capabilities. The vertical engine's key is `vertical-rightsizing`; the engine must read the same property (reconciliation point R1).
- **Vertical engine gates** (`CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE`, `NO_METRICS_SOURCE`, `INSUFFICIENT_HISTORY`, `GITOPS_MANAGED`). From the engine's own `GET /api/eks/automation/vertical/{c}`: engine status (cluster-access status, metrics source, last cycle), cluster-level `gateReasons`, and per-workload reasons. The UI never infers them; it shows what the engine reports. When the engine's `gateReasons` also contain a write gate (for example `READ_ONLY_CONNECTION`), the UI takes the union with capabilities, so the stricter answer wins (R2).
- **AWS permission** (`MISSING_AWS_PERMISSION`). For Container Insights, the new status field carries it directly (D3). Account-wide gaps keep coming from the existing `/api/xamops/account-health/permissions?feature=KUBERNETES` feed (`usePermissionGaps.ts`) and the global `PermissionGapBanner`.
- **Demo**. `isDemoMode()` (`demoConfig.ts:34-36`) in the UI; the server checks the authenticated username against the same two emails (`LoginOtpController.java:71-72`, `DataInitializer.java:51-52`).
- **Provider**. The selected account's provider (`AccountContext.selectedAccountDisplay.provider`).

### 6.3 Action catalogue

Every automation control maps to one action id. The capability hook evaluates gates per action; the server enforces the same set per endpoint.

**Workload autoscaling (vertical engine).** Paths are relative to `/api/eks/automation/vertical/{c}` and provisional (§11.5).

| Action id | Control | API | Gates (in addition to the write gates) | Readiness key |
|---|---|---|---|---|
| `vertical.policy.recommend` | Per-workload mode Off / Recommend, and headroom, percentile, window | `PUT …/workloads/{ns}/{kind}/{name}/policy` with `mode: OFF` or `RECOMMEND` | `CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE`, `NO_METRICS_SOURCE` | — |
| `vertical.policy.auto` | Mode Auto | same with `mode: AUTO` | + readiness, + `INSUFFICIENT_HISTORY` | `vertical-rightsizing` |
| `vertical.method.persist` | Apply method "In place and persist (restarts pods)" | same with `applyMethod: IN_PLACE_AND_PERSIST` | + `GITOPS_MANAGED` (per workload); explicit opt-in dialog | — (takes effect only through `auto` or `apply`) |
| `vertical.apply` | Apply now (one-shot, approved) | `POST …/workloads/{ns}/{kind}/{name}/apply` | + readiness, + `INSUFFICIENT_HISTORY`, + `GITOPS_MANAGED` when the method persists | `vertical-rightsizing` |

`IN_PLACE` is the default method and needs no opt-in. Selecting `IN_PLACE_AND_PERSIST` is allowed in Recommend mode (it is configuration), but always through the opt-in dialog in §9.2, so nobody arrives in Auto with a restarting method by accident.

**Node automation (Go automation agent).** Paths are relative to `/api/eks/autoscaling`.

| Action id | Control | API | Gates (in addition to the write gates) | Readiness key |
|---|---|---|---|---|
| `automation.enable` | Node automation on/off switch | `PUT /{c}/enabled` | `AGENT_NOT_INSTALLED` | — |
| `automation.scope` | Scope selector (Full / Scoped / Paused) | `PUT /{c}/mode` | `AGENT_NOT_INSTALLED` | — |
| `agentRightsizer.edit` | None in the UI (retired, E16) | `PUT /{c}/workload-rightsizer` | always refused | `rightsizer-apply` (refusal message says retired) |
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

The **write gates** apply to every action in both tables, in order: `DEMO`, `PROVIDER_UNSUPPORTED`, `NO_USER_PERMISSION`, `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED`, and `UNKNOWN` when capabilities (or, for vertical actions, the engine status) failed to load.

Readiness evidence for each key: `vertical-rightsizing` (new engine, no live-test verdict yet; the owner flips it after that verdict); `rightsizer-apply` (harness §2, §7-B3: no floors, restart loop, QoS downgrade; now also retired, E16); `hpa-apply` (§2: fixed formula, PDB contradiction); `evictor-live` (§2, §7-B2: DaemonSet bail-out, no fit simulation); `rebalancer` (§2: fail-open env gate, non-idempotent; E3); `node-optimizer-actions` (§2: hardcoded savings and interruptibility, non-deterministic target, ignores `maxParallelDrains`); `scale-out` and `node-templates` (no Go controller reads `ScaleOutPolicy` or `NodeTemplates`: grep across `cmd/` and `internal/controller/` returns 0 uses; the agent never provisions nodes, audit §2.5).

Spot interruption handling (`handleSpotInterruptions`) stays allowed: it acts only on a node AWS is already reclaiming, and the orchestrator's not-ready list does not include it. Its copy notes it depends on `aws-node-termination-handler` labels (roadmap §5.2 item 5).

### 6.4 Precedence

When several reasons apply, the control shows the one that is hardest to remove, followed by "Also needs: …" for the rest. Fixing a lower reason would not help while a higher one holds.

1. `DEMO`
2. `PROVIDER_UNSUPPORTED`
3. `CONTROLLER_NOT_READY` / `ANALYSE_ONLY` / `GITOPS_MANAGED` (product rules; no customer setting lifts them, though `GITOPS_MANAGED` points to the in-place method)
4. `NO_USER_PERMISSION` (needs another person)
5. `READ_ONLY_CONNECTION`, then `CONNECTION_NOT_VERIFIED` (needs an AWS-side change)
6. `UNKNOWN` (retry)
7. `CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE`, `NO_METRICS_SOURCE`, `AGENT_NOT_INSTALLED` (needs setup)
8. `INSUFFICIENT_HISTORY` (needs time)

So a read-only customer looking at Live consolidation reads "Live consolidation is disabled until pod-fit simulation is available", because upgrading their connection would not enable it. The same customer looking at the node automation switch reads "Read-only connection…", and looking at a workload's Recommend mode on a cluster without an access entry reads "Read-only connection…" with "Also needs: cluster API access".

`GUARDRAIL_LOCKED` and `MISSING_AWS_PERMISSION` are not in this order because they never compete: locked guardrails render as static text, not as gated controls, and AWS permission gaps gate data views only (§6.1).

### 6.5 Edit versus commit

Each automation control has two layers. **Edit** is local draft interaction (moving a slider, typing a namespace). **Commit** is the server call (Save, the switch flip, a confirm dialog's primary button).

- `DEMO` blocks commit only. The demo can walk through the read-write experience: forms are editable, Save is visibly disabled with the demo reason. Nothing is sent.
- Every other reason blocks both edit and commit. The control renders the current server value, read-only, with the reason. A read-only user sees exactly which settings are in force.

Forms keep a draft separate from server state. "Unsaved changes" appears beside Save; "Discard" restores the server value. A failed save keeps the draft so the user can retry, and never changes server state on screen.

### 6.6 Server enforcement

**Guard interceptor.** `K8sAutomationGuardInterceptor` (a `HandlerInterceptor`, registered by `K8sAutomationWebConfig` for `/api/eks/autoscaling/**`, following `DevOpsRateLimitConfig`/`RateLimitInterceptor`) runs before every handler on that path, for every HTTP method:

1. Principal must be a `ClientUserDetails`, else `401 K8S_UNAUTHENTICATED` (Spring Security already requires authentication for this path, `SecurityConfig.java:330`; this is fail-closed backup).
2. `clusterName` path variable (from `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`) must match the EKS name rule, else `400 K8S_INVALID_REQUEST`.
3. `accountId` query parameter must be 1-12 digits, else `400 K8S_INVALID_REQUEST`.
4. `K8sAutomationAccessService.resolve(user, accountId, clusterName)` finds the AWS `CloudAccount` rows for the padded and raw id (the column is unique, so at most one of each; two exist only where the leading-zero quirk created both), keeps those the user `canAccess` (`CloudAccountAccessService.java:196`), and looks up the cluster config: first by the account's DB id, then by the AWS account number for legacy rows (B4). Nothing reachable: `403 K8S_NO_ACCOUNT_ACCESS`, with the same message whether the account is unknown or not the caller's (no existence oracle, matching `CloudAccountAccessService` practice). The context's account is the row whose DB id owns the config. When no row owns it (a legacy config, or no config yet) and both rows are entitled, the write gates use the stricter of the two: read-only if either is read-only, not verified if either is not `CONNECTED`. The same account step, without the cluster, is `resolveAccounts(user, accountId)`, which the capabilities endpoint uses.
5. The resolved `K8sAutomationRequestContext(user, accounts, config)` is stored as a request attribute (`accounts` holds the entitled rows, the owning row first). Controllers read it with `@RequestAttribute` and never parse `accountId` again. `EksAutoscalingService.findConfig` and its cross-account fallback are deleted.
6. For `GET`/`HEAD`/`OPTIONS`, stop here: reads need only entitlement.
7. For mutations, `writeDecision(user, accounts)` applies `DEMO`, `NO_USER_PERMISSION`, `READ_ONLY_CONNECTION`, `CONNECTION_NOT_VERIFIED` in that order (the account gates over the rows chosen in step 4); the first failure returns `403` with its code. A null config returns `404 K8S_CLUSTER_NOT_REGISTERED`.
8. Every mutation logs one INFO audit line on completion: user id, impersonation marker when present (`ImpersonationContext.getImpersonatedUserId()`), account DB id, cluster, method, path, HTTP status, code.

New endpoints added under this path later are covered by default; there is no per-method annotation to forget.

**Scope boundary with the vertical engine.** The interceptor is registered for `/api/eks/autoscaling/**` only. `/api/eks/automation/vertical/**` belongs to the engine workflow, which enforces its own gates and returns `403` with the same codes (orchestrator notification). This branch does not register anything on the engine's path, does not edit its packages, and does not change `SecurityConfig` (the engine's path is already authenticated by `anyRequest().authenticated()`, `SecurityConfig.java:334`). `K8sAutomationAccessService` and `K8sAutomationReason` are self-contained and public so the engine can call them instead of re-implementing the rules; whether it does is reconciliation point R2 (§19).

**Policy normalizer.** `K8sAutomationPolicyNormalizer` owns the policy bundle's shape and safety:

- *Write path*: read the stored bundle (tolerant parse, unknown properties ignored), fill it to completeness, validate the incoming section (readiness refusals first, then locked guardrails, then ranges, §13), merge the section, keep server-owned fields (rebalancer `status`, `lastRebalanceAt`, `lastRebalanceSavingsPercent`, `lastRebalanceDuration`), normalize again, persist. Every save therefore writes a complete bundle (orchestrator decision 9).
- *Serve path*: `AgentController.getAutoscalingPolicy` (`:737-805`) returns `normalizer.forAgent(stored)` instead of the raw JSON or the partial fallback at `:799`. Readiness is enforced on serve as well: if a stored bundle has a not-ready value (from older code or a direct DB edit), the served bundle carries the safe value and the server logs a WARN naming the config id and the coerced fields. An unparseable stored bundle is served as the complete default with `autoscalerMode: "disabled"` (agent idles) and an ERROR log.
- *Completeness*: every section non-null; `safetyGuards.criticalNamespaces` defaults to `kube-system, monitoring, ingress-nginx, xamops-system` (union of the Java and Go defaults) and always contains `kube-system` and `xamops-system`; the Java bundle gains the two sections the Go bundle already has: `hpaManagerPolicy {enabled: false, analyseOnly: true, defaultMinReplicas: 1, defaultMaxReplicas: 10, defaultTargetCpuPct: 80}` and `vpaManagerPolicy {enabled: true, analyseOnly: true, updateMode: "Off"}`. The VPA manager is the one controller measured accurate (9/9), and analyse-only relays existing VPA objects without creating any.
- *Go rightsizer retired*: the normalizer always serves `workloadRightsizerPolicy.enabled: false` and `applyMode: "deferred"`, and `PUT /{c}/workload-rightsizer` is refused with `403 K8S_CONTROLLER_NOT_READY` and the message "The automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling." Two vertical engines acting on one workload (one resizing in place, one patching templates) would fight each other, and the Go rightsizer is measured unsafe (E4).
- *Enable*: `PUT /{c}/enabled {enabled: true}` persists the normalized default bundle when none exists, with `autoscalerMode: "full"`, the Go rightsizer off, evictor `analyseOnly: true`, node-optimizer actions off and rebalancer `status: "idle"`. The agent's first fetch is complete and analysis-only.

**Refusal format.** All refusals on this path and the capabilities endpoint use one body:

```json
{ "success": false, "code": "K8S_READ_ONLY_CONNECTION", "message": "Read-only connection. Automation changes need read-write access.", "field": null }
```

`K8sAutomationRefusedException(HttpStatus, K8sAutomationReason, message, field)` carries it; a controller-local `@ExceptionHandler` in `EksAutoscalingController` and `K8sCapabilitiesController` maps it, the same pattern AutoSpotting uses (`AutoSpottingBlockedException.java:5-13`). The interceptor writes the same JSON directly. Unexpected exceptions return `500 K8S_INTERNAL` with a fixed message ("Couldn't save the change. Try again.") and an ERROR log with the stack; `e.getMessage()` is no longer echoed to clients (it is today at `EksAutoscalingController.java:93,116,135…`).

### 6.7 Controller readiness

Readiness is backend configuration, so a control turns on when its controller is fixed, with no UI change.

```properties
# application.properties (defaults; absent property = not ready)
xamops.k8s.automation.readiness.vertical-rightsizing=false
xamops.k8s.automation.readiness.rightsizer-apply=false
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
| `vertical-rightsizing` | "Automatic and one-click resizing are disabled until the vertical rightsizer passes live validation. Recommendations are available." |
| `rightsizer-apply` | "The automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling." (always refused; the key stays so an old stored `immediate` value is still coerced on serve) |
| `hpa-apply` | "Automated HPA changes are disabled until the HPA manager passes safety validation." |
| `vpa-apply` | "XamOps doesn't create or update VPA objects yet." |
| `evictor-live` | "Live consolidation is disabled until pod-fit simulation is available." (reason `ANALYSE_ONLY`) |
| `rebalancer` | "Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly." |
| `node-optimizer-actions` | "Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation." |
| `scale-out` | "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does." |
| `node-templates` | "Not available: the automation agent doesn't use node templates yet." |

When a key is true, the matching refusal and serve-time coercion disappear and the control's ready-state UI (confirm dialog with impact text, §9) is already built. Flipping `rebalancer` to true is not enough on its own to make rebalancing safe; F2 lists what the controller and server must do first.

`vertical-rightsizing` is defined here and served by capabilities, but the refusal on the engine's own endpoints belongs to the engine. Both sides must read the same property name, so one flag flips both the UI and the server (R1). Until the engine confirms it reads this property, the UI still disables Auto and Apply from capabilities, and the engine's own `gateReasons` are unioned in (§6.2), so a mismatch can only make the UI stricter, never looser.

### 6.8 Invariants and who owns them

| Invariant | Owner | Why that layer |
|---|---|---|
| Only entitled users read or change a cluster's automation data | Server interceptor | Security; the UI cannot be trusted. |
| Read-only and unverified connections cannot change automation | Server, mirrored by UI | Consent; must hold for API callers. |
| Users without the permission, and demo identities, cannot change automation | Server, mirrored by UI | Security; demo credentials are shared (E12). |
| No not-ready controller can be enabled | Server on write (refuse) and on serve (coerce) | The rebalancer obeys the server, not its env flag (E3); the server is the kill switch. |
| Served policy bundles are complete | Server normalizer | Older agent images zero missing guards (E5). |
| Rebalance status is server-owned | Server normalizer | A config save must never start a drain. |
| `AGENT_NOT_INSTALLED` disables Go-agent controls | UI only | UX, not safety: storing a policy before the agent is installed is harmless, and the agent cannot fetch any policy until automation is enabled (`AgentController.java:787-791`). Enforcing it on the server would add a coupling with no safety gain. |
| Only one vertical engine acts on a workload | Server normalizer (serves the Go rightsizer disabled) | The Java engine is the product's vertical engine (E16); the Go agent must never patch the same workloads. |
| Vertical engine gates (access, metrics, history, GitOps, readiness on its endpoints) | Vertical engine, mirrored by UI | Its endpoints, its ownership boundary. The UI unions its reasons with capabilities. |
| A restarting apply method is never chosen implicitly | UI (opt-in dialog) and engine (refuses persist on GitOps-managed workloads) | Restarting every pod is a different risk class from an in-place resize. |
| No fabricated numbers | UI derivations, unit-tested | Presentation rules over data the backend already sends. |
| Reason codes match on both sides | Contract test over a shared fixture (§17) | One table on each side; drift fails a test. The engine's codes join the same fixture (R3). |

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

### D2. User permission: one new tab-access key, evaluated on the server

What exists: `User.role` is coarse (all XamOps customer users are `ROLE_XAMOPS`); `user_tab_access` is a per-user whitelist of sidebar keys where "no rows means no restriction" (`UserTabAccess.java:9-14`, `xamopsTabs.ts:1-13`); `user_cloud_account_access` is a per-user account whitelist enforced by `CloudAccountAccessService.canAccess`; there is no read/write distinction anywhere.

Decision: add `cloudops-cloudk8s-automation` (label "CloudK8s automation (change)") to the CloudOps module in `XAMOPS_MODULES`. A user may change automation when all of these hold:

1. `canAccess(user, account)` is true (already required to see the cluster at all);
2. the username is not a demo identity (`DEMO`);
3. the user holds at least one XamOps-product role: `ROLE_XAMOPS`, `ROLE_XAMOPS_ADMIN`, `ROLE_SUPER_ADMIN`, `ROLE_ADMIN` or `ROLE_USER`. BillOps-only principals (`ROLE_BILLOPS`, `ROLE_BILLOPS_ADMIN`, `ROLE_BILLOPS_ADMIN_SUB`) are refused: billing staff can reach every account through `canAccess`, and changing a customer's cluster automation is not a billing task;
4. the user has no `user_tab_access` rows (unrestricted), or has both `cloudops-cloudk8s` and `cloudops-cloudk8s-automation`.

Why this shape: no schema change; the grant is managed in the two screens admins already use (My Team in `MyProfilePage.tsx:444` and the BillOps user manager in `BillOpsAdminUserManagerPage.tsx:261`, both render `XAMOPS_MODULES`); the server enforces it with an existing repository method. The `xamopsTabs.ts` comment warns that a new key is "fail-OPEN for a user with no rows, fail-CLOSED for a user with any". That is the right behaviour for a write permission: unrestricted users keep the platform's "no rows, no restriction" contract, and restricted users, who never had automation (the UI was dormant), must be granted it explicitly. Unlike `secops-xamlens`, no migration grants it.

Knock-on changes: `FeatureFaqCatalog` gains an entry for the key and `FeatureFaqCatalogTest.EXPECTED_TAB_KEYS` gains the key (the test mirrors `ALL_XAMOPS_TAB_KEYS`). The Sidebar is unaffected because it gates hardcoded keys (`Sidebar.tsx:385`).

Alternatives rejected: "primary users only" (no way to grant a team member); a new boolean column on `users` (schema migration plus new admin UI); a new role (roles are coarse and shared with BillOps).

The rule is computed once, on the server, and served by capabilities. The UI never re-implements it.

### D3. Container Insights: implement the freshness check

It is contained, and every role template already grants the action. `logs:DescribeLogStreams` is in `xamops-readonly-role.yml:365`, `xamops-readwrite-role.yml:359` and the uat, live and local variants (one match each). No template edit is needed.

`EksService.checkContainerInsightsStatus` (`:202-213`) becomes:

1. `DescribeLogGroups(prefix=/aws/containerinsights/{cluster}/performance)` and require an exact `logGroupName` match. No match → `NOT_CONFIGURED`.
2. `DescribeLogStreams(logGroupName, orderBy=LastEventTime, descending=true, limit=5)`; `lastActivity` = max over those streams of `lastIngestionTime` and `lastEventTimestamp`.
3. `RECEIVING` if `lastActivity` is within 15 minutes; `STALE` within 24 hours; `NOT_RECEIVING` beyond that or with no streams.
4. AccessDenied on either call → `UNKNOWN` with `reason: "MISSING_AWS_PERMISSION"` and the action name; the permission interceptor records the gap automatically. Throttling or other errors → `UNKNOWN` without a reason, WARN log.

15 minutes is twice the healthy control's measured lag (7.5 min, harness §8) and catches the OTEL case measured at 20.2 min. AWS documents `lastEventTimestamp` as eventually consistent, so the badge always shows the raw "last event" time and the check time in its tooltip ("CloudWatch can report the latest event late"). The two thresholds are constants in `EksService` with a comment pointing at this section.

`K8sClusterInfo` keeps `connected` (now `status == RECEIVING`, so old consumers stay correct) and adds `containerInsights: { status, lastEventAt, checkedAt, reason, action }`. The list is cached in Redis for an hour (`EksService.java:154`), hence `checkedAt`; Refresh forces a recheck. Spring's ObjectMapper ignores unknown properties, so old cache entries and new ones interoperate. `AwsPermissionCatalog.java:119` adds `KUBERNETES` to the `logs` features, for the same reason Phase 0 added it to `cloudwatch` (`:114-118`): without it, a feature-scoped gap query drops the denial.

Labelling honestly as "Configured" was the fallback; it is not needed.

### D4. Controller readiness is a server gate, default not ready

Adopted from the orchestrator updates. Detailed in §6.7. Allowed today: viewing everything; per-workload Off and Recommend modes and their headroom, percentile and window on the vertical engine; choosing an apply method; node automation on/off and scope; evictor analysis settings; node-optimizer thresholds and spot interruption handling; guardrail edits within the locks; the VPA baseline (collected; display is F4). Not ready: vertical Auto and Apply now (`vertical-rightsizing`), and every Go-agent live or apply control.

### D5. Modes: each engine's own values, no invented modes

**Workload autoscaling (vertical engine).** Modes are the engine's per-workload values, shown as is: Off (`OFF`), Recommend (`RECOMMEND`), Auto (`AUTO`). Apply methods: "In place, no restart" (`IN_PLACE`, default) and "In place and persist (restarts pods)" (`IN_PLACE_AND_PERSIST`). Per-workload controls exist because the engine's policy model is per workload (`PUT …/workloads/{ns}/{kind}/{name}/policy`). If the final contract adds a cluster default policy, it appears as a "Default for new workloads" row at the top of the table, with the same controls (§11.5).

**Node automation (Go agent).** The backend mode values stay `full | scoped | disabled` (`EksAutoscalingController.java:267`). Their real semantics, from the agent: `disabled` makes the agent idle (`main.go:154`); `scoped` disables only the evictor (`main.go:201-203`); `full` runs every controller that is enabled in both the policy and the agent's env flags. The UI shows:

- **Node automation** switch → `autoscalingEnabled` (`PUT /enabled`). Off means the agent receives no policy (`AgentController.java:787-791`).
- **Scope** → mode: "Full" (`full`), "No consolidation" (`scoped`), "Paused" (`disabled`).
- **Apply behaviour** per controller → existing fields: evictor `analyseOnly`, node-optimizer action flags. The Go rightsizer's `applyMode` is no longer shown (retired).

The fleet's labels are derived, never stored:

| Line | Derived state | Rule |
|---|---|---|
| Workloads | Not configured | engine reports `CLUSTER_ACCESS_NOT_CONFIGURED`, `CLUSTER_UNREACHABLE` or `NO_METRICS_SOURCE` |
| | Off / Recommend / Auto (counts) | counts of workloads per mode from the engine |
| Nodes | Not installed | automation agent never seen |
| | Off | `autoscalingEnabled` false, or mode `disabled` |
| | Analysis | enabled, mode `full`/`scoped`, evictor `analyseOnly: true`, node-optimizer actions off |
| | Automated | enabled and any live field on (unreachable while readiness holds, but the mapping is ready) |

The Go agent's `DRY_RUN` and `ENABLE_*` env flags are not reported to XamOps (`pusher.go:70-85` sends version only). The Overview says so: "Dry run: not reported by the automation agent (it ships with dry run on)". The vertical engine reports `dryRun` on every action (§9.6), so its activity is labelled exactly.

The Go policy has no per-workload scope (`AutomationPolicyBundle` has none; nor does `policy/types.go`). Node automation's only scoping lever stays protected namespaces in Guardrails.

### D6. Tenant scoping through one resolver

Detailed in §6.6. Two limitations are documented rather than solved. First, legacy configs keyed by the AWS account number (B4) are visible to every client that connected that AWS account; they all have legitimate access to it, and B4's migration (F6) removes the case. Second, configs are matched by cluster name with region `"auto"` (B5), so two clusters with the same name in different regions of one account share capability rows (F6).

### D7. Rebalancing is blocked end to end

The trigger is the most dangerous control in the product (E3). UI: "Rebalance now" and the schedule are disabled with the `rebalancer` message. Server: `POST /rebalance` returns `403 K8S_CONTROLLER_NOT_READY`; `PUT /rebalancer` refuses `scheduleEnabled: true` and ignores client values for server-owned fields; the normalizer forces `status: "idle"` on every save and every serve while `rebalancer` is not ready. That last point is what makes the in-cluster env flag irrelevant: the agent can no longer receive `running`.

### D8. Recommendations come from the vertical engine; labelled, flagged, never clamped

Workload autoscaling, the Workloads table's recommendation column and the potential-savings figures all read the vertical engine (`GET /api/eks/automation/vertical/{c}`, provisional). The old sources are no longer read by the UI:

| Source | Status in the UI | Why |
|---|---|---|
| Java vertical rightsizer | **Used.** Label "XamOps vertical rightsizer · p`<percentile>` over `<window>`, `<headroom>`% headroom". Quality line until `vertical-rightsizing` is ready: "Not yet validated on live clusters." | Owner decision (E16). |
| `GET /{c}/rightsizing` (Go rightsizer rows, or the backend snapshot engine as fallback, `EksAutoscalingService.getRightsizingData:591-625`) | **Not read.** The endpoint remains, tenant-scoped, for API compatibility. | The Go rightsizer is measured unsafe and retired (E4, E16). The snapshot engine was never scored and returns nothing on agent clusters (E7). Showing a second, conflicting recommendation per workload would be noise. |
| Upstream VPA (`vpa-recommendations`, measured 9/9) | **Follow-up F4.** Stored by the generic ingest, served by no endpoint. When served, it appears as a "VPA target" column beside the engine's recommendation. | Orchestrator decision 4. |

Row flags, computed client-side as a second line of defence, each with a tooltip and none of them altering the engine's numbers:

- **Below safe floor**: recommended CPU < 25m or memory < 250Mi (upstream VPA minimums, harness §1). The engine has its own floors; if its output is ever below these, the row is flagged and no savings are computed from it.
- **Engine findings**: `REQUESTS_UNSET`, `IDLE`, `THROTTLED` and any others the engine sends render as finding chips with fixed copy ("Requests unset", "Idle", "CPU throttled"); unknown findings render as their code in plain text. `REQUESTS_UNSET` rows never contribute savings.
- **HPA conflict**: the engine reports HPA presence per workload; the UI also checks `k8sHpas` (`targetKind`/`targetName`, `cpuTargetPct > 0`). Any workload with an HPA and a recommended CPU request change shows: "HPA `<name>` scales on CPU at `<n>`% of requests. Changing requests moves its scaling point." No savings computed for it.
- **Skipped**: mid-rollout, HPA-volatile and `xamops.io/lock: active` workloads show the engine's skip reason as status ("Skipped: rollout in progress", "Skipped: HPA activity", "Skipped: locked by annotation").

Values are shown exactly as the engine returns them; nothing is clamped or rounded beyond unit formatting.

### D9. Cost and savings: named sources only

**Cluster cost source**, first match wins, shown beside every cost figure:

1. `openCost.enabled` and `openCost.summary.totalDailyCost > 0` → "OpenCost (agent)".
2. `agentCostMetrics.totalHourlyCost > 0` → "Cost exporter (agent)". The agent reads `sum(node_total_hourly_cost)` or `opencost:cluster:hourly_cost` (`prometheus_metrics.go:221-233`).
3. `costMetrics.dailyCost > 0` otherwise → "XamOps backend".
4. Otherwise "No cost source", value "—", remedy "Install OpenCost" (Data sources › Install).

Monthly figures use the backend's `monthlyCost` when present, else daily × 30, the backend's own convention (`EksDashboardService.java:650-651`); the tooltip says "Monthly = daily × 30".

**Namespace and workload cost**: `costMetrics.namespaceCosts`/`workloadCosts` when OpenCost populated them (`populateCostAllocationsFromOpenCost`, `EksDashboardService.java:1279-1426`). Otherwise **Estimated**: for each pod, `max(request, usage-if-reported)` CPU cores × `CPU_RATE_PER_VCPU_HOUR` + memory GiB × `MEMORY_RATE_PER_GIB_HOUR`, × 24 for daily. Requests come from pod containers, which the agent reports exactly (harness §6). Today's fallback multiplies *usage* by the rates (`EksCostTab.tsx:68-69,102,114,126`), and agent pods carry no usage, so it shows $0. `max(request, usage)` is OpenCost's allocation basis.

**Node cost**: OpenCost node allocations when present. Otherwise the agent path's split of the cluster total by CPU capacity (`EksDashboardService.java:691-719`), labelled "Allocated by CPU capacity". That path hardcodes `capacityType: "on-demand"` (`:707`), so capacity type always comes from `k8sNodes`.

**Rates module**: `frontend-app/src/utils/k8sPricing.ts` is the only place the two constants live: `CPU_RATE_PER_VCPU_HOUR = 0.0416`, `MEMORY_RATE_PER_GIB_HOUR = 0.00456`, a `RATE_PROVENANCE` string ("Reference on-demand rates; not region- or Spot-aware; origin not documented in code") and `estimateCost(cpuCores, memGiB, hours)`. Every Estimated figure's tooltip shows the method and both rates. A future pricing engine replaces this module.

**Unallocated capacity cost** replaces "potential savings" in the cluster cost view (`useEksDashboard.ts:456-470` is deleted). It is shown only when the cost exporter reports the CPU/RAM split (`agentCostMetrics.cpuHourlyCost` and `memoryHourlyCost`) and requests and allocatable are known: `cpuHourly × (1 − cpuRequested/cpuAllocatable) + memHourly × (1 − memRequested/memAllocatable)`, × 24. Tooltip: "Cost of CPU and memory no pod has requested (OpenCost-style idle). Source: cost exporter + pod requests." The old heuristic treated unreported node usage as 0% and so counted whole nodes as waste.

**Potential savings** appears only as "Estimated", from vertical-engine recommendations that are eligible: a container with requests set, a recommendation at or above the safe floors, no HPA conflict, not skipped, and a replica count known (from the engine if it sends one, else `k8sDeployments`/`k8sStatefulSets`). Per container: `max(0, currentCpu − recCpu)` cores × CPU rate + `max(0, currentMem − recMem)` GiB × memory rate, × replicas × 24 × 30. Increases are not netted against decreases; they are listed separately as "Needs more" so a throttled workload is not hidden by savings elsewhere. Tooltip: "Estimated from N recommendations (XamOps vertical rightsizer, not yet validated) × reference rates. Savings are realised only when nodes are removed. M excluded: below safe floor, requests unset, HPA conflict, skipped." No realised-savings figure is shown anywhere; there is no ledger (F10). The engine's actions feed records what was applied, which is the input a future ledger needs. `kpis.costSavedThisWeek` and `kpis.nodesSavedToday` are never rendered. The observer agent always sends 0 for them (`autoscaling_metrics.go:22-30`).

Deleted outright: the namespace "high cost" recommendation (`EksCostTab.tsx:150`), the Karpenter recommendation (`:158`), the "idle workloads" recommendation (`:172-186`, which flags every workload whose usage is unreported), and `quickWins` (`useEksDashboard.ts:472-510`).

### D10. Health is rule-based signals, not a score

`clusterHealthScore`, `resourceScore`, `costScore`, `securityScore`, `healthScoreLabel`/`Color` and `clusterHealth` are deleted from `useEksDashboard.ts:310-454`, and `workloadHealth.clusterHealthScore` is no longer read. Health becomes a list of signals, each from data with its own count:

| Signal | Rule | Attention when |
|---|---|---|
| Nodes ready | `k8sNodes` with condition Ready=True / total | any not ready |
| Node pressure | Memory, Disk or PID pressure True | any |
| Pods | running / total from `clusterOverview`; failed; CrashLoopBackOff containers (`waitingReason`) | failed > 0 or crash-looping > 0 |
| Unschedulable | pods with `unschedulable: true` | any (informational only when Pending but schedulable) |
| Vulnerabilities | Trivy critical / high | shown separately as a security signal, not part of health |

Overall status: **Healthy** (no attention signals), **Needs attention (n)**, **Data stale** (the XamOps agent is stale; last-known values stay visible with a stale marker), or **Not monitored**.

**Not monitored** applies when capabilities report no XamOps agent data for the cluster, or, if capabilities failed, when the dashboard has no `k8sNodes` and `clusterOverview.totalNodes` is 0. It replaces the section content with one panel: "This cluster isn't monitored yet", what XamOps would show, and "Set up monitoring" (opens the guide's Quick Install). It never renders zeros as values.

**Unknown is not zero.** New code treats a missing metric as `null` and renders "—" with "Not reported by `<source>`". The quantity parsers moved to `utils/k8sQuantities.ts` return `null` for missing or unparseable input (today's `parseCpuMillicores`/`parseMemoryMiB`, `useEksDashboard.ts:13-35`, return 0, which is how unknown became zero). Request sums treat an unset request as 0 explicitly, since it reserves nothing, and count those containers separately for the "Requests unset" flag. Usage and capacity callers keep `null`. The move also corrects two parsing gaps: decimal suffixes (`k`, `M`, `G`, `T`) become powers of 1000 (today `M` is read as MiB), and memory accepts the `m` suffix, which Kubernetes uses when it serializes fractional byte quantities. The re-homed tables keep their structure, but usage columns render only when the dashboard reports usage (`topCpuPods`/`topMemoryPods`, `nodeUtilization`, `namespaceUtilization`); elsewhere they show "—". Per-pod usage exists in the agent payload but never reaches the dashboard (E15, F5).

### D11. Dead and fake components

| Component | Decision | Evidence |
|---|---|---|
| `CloudK8sIntegrations.tsx` (+ `GcpCloudK8sIntegrations.tsx`, `AzureCloudK8sIntegrations.tsx`) | Delete. AWS: replaced by the summary strip and "Connect a cluster". GCP/Azure: replaced by a "Connect a cluster" card that opens their existing setup guides (`GcpCloudK8sHelpModal`, `AzureCloudK8sHelpModal`). | Hardcoded "COMING SOON" cards with disabled "Integration Pending" buttons (`CloudK8sIntegrations.tsx:44,57`) |
| `CloudK8sHelpModal.tsx` | Delete (and its barrel export). Not wired up. | It documents the Prometheus-on-EC2 path that nothing can reach for EKS (audit B1), and tells customers to open security-group ports to `0.0.0.0/0` (`:155`). The live guide is `EksIntegrationModal`. |
| `EksHeader.tsx` | Delete. | No importer anywhere in `src`; the page uses `useTopbar` actions. |
| `EksTabs.tsx`, `EksSummaryCards.tsx` | Replaced by `SectionTabs` and the status strip/Overview tiles. | — |
| `EksAutoscalingTab.tsx`, `useEksAutoscaling.ts` | Decomposed into Automation subtabs and `useEksAutomation`; files deleted. | E2, plus misleading copy ("Continuously simulates pod redistribution", `:591`; "Syncs every 15s", `:399`), purple/green status colours, mode buttons that save without confirmation. |

### D12. Demo

See §16. Two demo AWS accounts show both states: `demo-aws-001` read-write (editable drafts, commit disabled with `DEMO`) and `demo-aws-002` read-only (everything greyed with `READ_ONLY_CONNECTION` and the banner).

### D13. Onboarding: three things to set up, shown as three things

Workload autoscaling and node automation have different prerequisites, so Data sources › Install shows them separately:

1. **Cluster API access** (vertical engine), anchor `cluster-access`, component `ClusterAccessGuide`. What it is: an EKS access entry for the XamOps IAM role, mapped to the minimal ClusterRole the engine workflow publishes. Status comes from the engine (`clusterAccess`). Private API endpoints get their own note, driven by `CLUSTER_UNREACHABLE`. The access-entry command and the ClusterRole manifest are rendered from the engine's published contract (R4); until it is published, the card states the requirement and shows the live status, with no command.
2. **Prometheus for rightsizing** (vertical engine), anchor `prometheus`. A Prometheus the backend can reach through the Kubernetes API service proxy. Status from the engine's `metricsSource`. The card links to the guide's Monitoring stack part (`initialSection: 'part1'`), which already installs Prometheus.
3. **Automation agent** (node automation), anchor `automation-agent`, component `AutomationAgentGuide`, described below. It is not needed for workload autoscaling.

The `AutomationAgentGuide` component (Data sources › Install and the fleet's Connect panel) describes the agent honestly: what it does (reads this cluster's policy from XamOps every 60 seconds; dry run on by default; all six controllers off by default in its config), what it is for now (node consolidation analysis, the node optimizer, spot interruption handling and the VPA baseline; workload rightsizing has moved to the vertical engine), what it can do today (analysis only, per §6.7), what it requests in the cluster (read access to core, apps, batch and policy; write access to cordon nodes, evict and delete pods, patch Deployments and StatefulSets, and manage HPAs and VPAs), and what it needs (the XamOps agent, metrics-server, Prometheus for the node optimizer's fallback queries, optionally the VPA recommender). Status line: "Preview. The `xamops/automation:v1.0.0` image isn't built by XamOps CI yet, so there's no supported install command. Contact XamOps support to enable it for this cluster." It deliberately contains no `helm`/`kubectl` command and no `docker.io/xamops/...` reference, and lives outside the three files the setup-guide job validates (E13).

`EksIntegrationModal.tsx` gets an optional `initialSection` prop (`'quick' | 'overview' | 'part1' | 'part2' | 'part3' | 'part5'`, matching the state at `:209`), a greyscale header (the gradient at `:272` goes), and the verification checklist at `:1314-1317` renamed to the new sections ("Nodes › Nodes", "Workloads › Pods", "Security", "Cost › Namespaces"). No command, image tag or `AGENT_IMAGE_TAG`/`EKS_PLACEHOLDERS` export changes, so the setup-guide job and `guideVars.test.ts` keep passing.

### D14. Tables: pagination, not virtualization

No table or virtualization library is installed and none is added. A shared `K8sDataTable` gives sorting, search, filter chips, pagination (25/50/100) and CSV export. Pagination keeps the DOM bounded for large pod lists; derived rows are memoized per dashboard.

### D15. Version support badge: deferred

No backend response carries support status. `K8sClusterInfo` has `version` only, and `eks:DescribeClusterVersions`, granted by Phase 0, is called nowhere. A hardcoded release calendar would be unverifiable reference data that goes stale, so the fleet shows the version without a badge and "extended support / EOL" is not an attention criterion yet (F7).

### D16. Consuming the vertical engine through one adapter

The engine's contract is provisional, so every engine-specific detail lives in two files: `api/verticalRightsizerApi.ts` (paths, query parameters, request bodies, error parsing) and `api/verticalRightsizerAdapter.ts` (maps the wire shape to the view model in §11.5). Components and hooks only see the view model. When the final contract is published, those two files and their fixtures change; nothing else should.

Rules the adapter enforces:

- Unknown enum values (modes, methods, findings, reasons, access statuses) pass through as `{ kind: 'unknown', raw }` and render as plain text, never as a known state.
- Missing numbers stay `null`; a container without a recommendation shows "No recommendation" with the engine's reason.
- Quantities are parsed with the same helpers as the dashboard (`utils/k8sQuantities.ts`), so `250Mi` and `0.25Gi` compare equal.
- Requests and limits are both carried. The UI shows limits beside requests and flags a QoS class change ("Guaranteed → Burstable") when the recommendation would cause one: the measured Go rightsizer downgraded QoS silently (harness §7-B3), and the new engine's output is shown with the same check.

The engine also needs the cluster's `region` (the notification says it takes "the same account/region query parameters the other EKS endpoints use"). The page already has `region` in its URL; the fleet has it from `K8sClusterInfo`.

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

**Workload autoscaling axis (vertical engine).** Independent of the XamOps agent: the engine reads Prometheus and the API server directly.

| State | Fleet "Workloads" line | Automation › Overview | Workload autoscaling | Data sources |
|---|---|---|---|---|
| **V0 Cluster access not configured** | "Not configured" | Engine block: cluster access unchecked, remedy "Set up cluster access" | Table hidden; one panel explaining what the engine does and the two prerequisites, with status for each | Cluster API access "Not configured" |
| **V1 Unreachable** (private endpoint, network) | "Unreachable" | Same, with `CLUSTER_UNREACHABLE` copy | Same | Cluster API access "Unreachable" with the last error time if the engine reports it |
| **V2 No metrics source** | "No metrics" | Prometheus unchecked | Table shown if the engine lists workloads without recommendations; recommendation cells "No recommendation · no metrics source" | Prometheus "Not reachable" |
| **V3 Collecting history** | "Collecting history" | Engine block shows the window | Recommendations missing per container with `INSUFFICIENT_HISTORY`; Recommend allowed, Auto and Apply disabled for that reason too | Both rows connected |
| **V4 Ready to recommend** | "Recommend 12 · Off 30 · Auto 0" | Engine block complete; readiness row "Recommend only until live validation passes" | Full table; Off/Recommend editable within write gates; Auto and Apply now disabled (`CONTROLLER_NOT_READY`) | Both rows connected, last cycle time |
| **V5 Engine status failed to load** | "—" with the error | Engine block shows "Couldn't load" + Retry | Error panel + Retry; every vertical control disabled (`UNKNOWN`) | Rows show "Unknown" |
| **V6 Engine not deployed** (endpoint 404 during rollout) | "—" | Engine block: "Workload autoscaling isn't available in this environment yet." | Same panel, no controls | Rows hidden |

**Node automation axis (Go automation agent).**

| State | Fleet "Nodes" line | Automation › Overview | Node autoscaling / Rebalancing / Guardrails | Data sources |
|---|---|---|---|---|
| **N0 Not installed** | "Not installed" | Agent block unchecked, remedy "Install the automation agent" | Analysis panels "Needs the automation agent"; controls disabled (`AGENT_NOT_INSTALLED`) | Automation agent "Not installed" |
| **N1 Connected, node automation off** | "Off" | Switch enabled if write gates pass | Settings editable within write gates; they are saved and used once node automation is on | "Connected · 40 s ago" |
| **N2 On** (derived Analysis) | "Analysis" | Switch on, scope shown | Settings editable within write gates; live controls disabled (`ANALYSE_ONLY` / `CONTROLLER_NOT_READY`); rebalancing blocked | As N1 |
| **N3 Stale** (> 5 min) | "Analysis · agent stale" | Banner: "The automation agent last reported 2 h ago. Changes apply when it reconnects." | Controls follow N1/N2 rules | "Stale" |

**Provider.** GKE and AKS keep their own pages in slice 1; when the shared components are adopted, every automation control shows `PROVIDER_UNSUPPORTED`.

### 8.2 Account and user overlays for automation controls

| Overlay | Banner | Settings (per-workload Off/Recommend and policy; node scope, evictor, node optimizer, guardrails) | Auto, Apply now, live and rebalance controls | Recommendations, analysis, projected savings |
|---|---|---|---|---|
| Read-write, permitted | none | Editable when the engine's or the agent's prerequisites are met | Disabled with the readiness reason | Visible |
| **Read-only connection** | "Read-only view. Recommendations and projected savings are shown; automation changes are off." + "Enable automation" | Read-only values, `READ_ONLY_CONNECTION` | Disabled; shows the readiness reason first (§6.4), with "Also needs: read-write access" | Visible |
| **No user permission** | "You can view automation but not change it." + "Enable automation" | Read-only values, `NO_USER_PERMISSION` | Disabled; readiness reason first | Visible |
| **Connection not verified** | "This connection isn't verified." + "Open Account Manager" | Read-only, `CONNECTION_NOT_VERIFIED` | Disabled | Visible |
| **Demo, read-write fixture** | "Demo workspace. Changes aren't saved." | Editable drafts; Save, switches and confirm buttons disabled with `DEMO` | Disabled with the readiness reason | Visible (fixtures) |
| **Demo, read-only fixture** | Read-only banner | As read-only | As read-only | Visible |
| **Capabilities unavailable** | "Couldn't check automation access." + Retry | Disabled (`UNKNOWN`), fail closed | Disabled | Visible if their own requests succeed |

"Enable automation" opens a small panel listing the readiness checklist rows that are not met, each with its remedy. It is the path to enablement for every overlay, so the user is never left with a lock and no explanation.

---

## 9. Automation section

Built in the black/white/grey house style (§14). Every control is rendered through `GatedAction` or `GatedSwitch` (§11.1) from one capability object. Ready-state confirm dialogs are built now so a readiness flip needs no UI change.

### 9.1 Overview

1. **Access** (shared by both engines): connection access ("Read-write" / "Read-only" + "Get read-write access") and your permission ("You can change automation" / "View only" + "Ask an administrator"), plus the XamOps agent row ("Connected · data 4 min old" / "Not installed" + "Set up monitoring").
2. **Workload autoscaling · XamOps vertical rightsizer**, a readiness block with status marks:
   - Cluster API access: "Configured" / "Not configured" / "Unreachable" + "Set up cluster access".
   - Prometheus: "Reachable · `<source>`" / "Not reachable" + "Set up Prometheus".
   - Last cycle: "`<time>` · `<n>` workloads evaluated" (or the engine's last error).
   - Readiness: "Recommend only. Automatic and one-click resizing are disabled until the vertical rightsizer passes live validation."
   - Modes in use: "Auto 0 · Recommend 12 · Off 30", linking to Workload autoscaling.
3. **Node automation · automation agent**, a readiness block:
   - Automation agent: "Connected · 40 s ago · v1.0.0-autoscaler" / "Not installed" + "Install the automation agent".
   - Node automation for this cluster: the on/off switch (`automation.enable`) and the scope selector (`automation.scope`). Turning it on opens a confirm dialog: "Turn on node automation for `<cluster>`? The automation agent will receive this cluster's policy and start node analysis. Live changes stay off: each is disabled until its controller passes safety validation."
4. **What automation can do on this cluster today**: one row per capability with Analysis and Apply columns and the server message for anything not ready. Workload rightsizing (vertical engine): "Recommend" / "Disabled until live validation passes". Node consolidation: "Analysis not yet reliable" / "Disabled until pod-fit simulation is available". Node optimizer: "Idle-node thresholds" / "Disabled". Rebalancing: "—" / "Blocked". HPA manager: "Not shown yet" / "Disabled". Scale-out and node templates: "Not built". VPA baseline: "Collected by the automation agent (display coming)". Spot interruption handling: "On / Off" (protective).
5. **Dry run**: vertical engine actions carry `dryRun` and are labelled per action (§9.6). The automation agent: "Not reported by the automation agent. It ships with dry run on." (D5)

### 9.2 Workload autoscaling (vertical engine)

All data comes from `GET /api/eks/automation/vertical/{c}` through the adapter (D16).

- **Header**: engine label and quality line (D8), last cycle time, cluster-access and metrics-source status marks, and a filter for mode (Off / Recommend / Auto), findings and namespace.
- **Workloads table** (`K8sDataTable`, one row per workload, expandable to containers): Workload, Namespace, Kind, Mode, CPU request current → recommended, Memory request current → recommended, Limits (shown when the recommendation changes them), QoS (with the "Guaranteed → Burstable" flag, D16), Findings, HPA, Est. monthly savings (eligible rows only, "Estimated"), Last action, Status. The expanded row lists each container with current and recommended requests and limits and the engine's reasons for that container.
- **Status column**: "Recommendation" (Recommend mode), "Auto · in place" (Auto, once ready), "Off", or the engine's skip reason (mid-rollout, HPA-volatile, locked by `xamops.io/lock: active`), or "No recommendation · `<reason>`".
- **Per-workload policy** (drawer opened from the row): Mode (`vertical.policy.recommend` / `vertical.policy.auto`), headroom, percentile, window (`vertical.policy.recommend`), apply method (`vertical.method.persist` for the restarting option). Field ranges come from the final contract; until then the drawer validates only that values are present and numeric, and shows the engine's 400 message inline.
- **Apply methods**, shown as two radio options with their consequence in the label, never as a bare enum:
  - "In place, no restart" (`IN_PLACE`, default): "Resizes running pods through the resize subresource. New replicas start at the template values and are resized on the next cycle."
  - "In place and persist (restarts pods)" (`IN_PLACE_AND_PERSIST`): selecting it opens an opt-in dialog: "This also patches the `<kind>` template, which restarts every pod in `<name>`. Use it when new replicas must start at the new size. XamOps refuses this for workloads managed by Argo CD or Flux." The confirm button reads "Restart pods when applying". On GitOps-managed workloads the option is disabled with `GITOPS_MANAGED`.
- **Apply now** (`vertical.apply`), per row: disabled while `vertical-rightsizing` is not ready. Its ready-state confirm dialog shows, per container, current → recommended requests and limits, the method and its consequence ("No restart" or "Restarts every pod"), QoS changes, HPA warnings and the engine's findings. On success the row refetches and the action appears in Activity with its `dryRun` marker.
- **Empty and blocked states**: V0–V6 in §8.1 decide the panel; the table never renders zeros for workloads the engine has not evaluated.
- **HPAs and conflicts**: HPAs from `k8sHpas` (target, min/max/current replicas, CPU target) listed under the table, each linked to the workload rows it conflicts with (D8).

The Go rightsizer has no controls anywhere in the UI (E16). If a stored Go policy still carries rightsizer settings, they are not shown; the server serves that controller disabled.

### 9.3 Node autoscaling

- **Consolidation analysis** (`/evictor-candidates`): banner "Consolidation analysis isn't reliable yet. The evictor rejects any node running a DaemonSet pod, which excludes nearly every node in a real cluster, and it doesn't check whether evicted pods would fit elsewhere." Then the candidate table (node, CPU and memory requested % of allocatable, pods, evaluation result, skip reason) labelled "Reported by the automation agent". An empty list is shown as "No analysis reported", never as "No candidates".
- **Evictor settings** (`evictor.settings`): target CPU and memory utilisation, grace period, cycle interval, aggressive mode.
- **Live consolidation** (`evictor.live`): disabled, `ANALYSE_ONLY`.
- **Node fleet**: on-demand and Spot node counts and instance types from `k8sNodes` (capacity type from node labels). Node-optimizer `savingsPct`, `interruptible` and `estimatedSavingsPct` are never rendered: they are constants (`node_optimizer.go:172-179`, harness §2). The frontend type omits them and a test asserts they never appear in output.
- **Node optimizer settings** (`nodeOptimizer.settings`): idle CPU and memory thresholds, spot interruption handling (with the NTH dependency note). `nodeOptimizer.actions` (auto-cordon, auto-drain, Spot ratio target) is disabled with its message. Max parallel drains reads "1 (fixed)".
- **Scale-out and node templates**: the current stored values as a read-only summary under "Not built: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does." Node template add, edit and delete are disabled (`node-templates`).

### 9.4 Rebalancing

An explainer of what rebalancing would do, then the block, stated plainly: "Rebalancing is blocked. The current rebalancer drains nodes whenever XamOps marks a rebalance as running, ignores the agent's own off switch, and repeats the drain every minute. XamOps won't send that instruction until it's fixed." "Rebalance now" and "Schedule" are disabled (`rebalancer`). The last status from `GET /rebalance-status` is shown when present, labelled "Reported by the automation agent · unverified (dry-run state not reported)"; its `nodesDrained` is a number (harness capture), not a list. The ready-state UI exists: a confirm dialog, then "Requested, waiting for the automation agent" while polling `rebalance-status` every 10 s for up to 30 min. It never shows "completed" unless the server reports it, and even then marks it unverified.

### 9.5 Guardrails

- **Locked** (static rows with a lock mark and "Always on"): respect PodDisruptionBudgets; protect `kube-system` and `xamops-system`; protect critical namespaces; max parallel drains 1; graceful draining.
- **Editable** (`guardrails.edit`): protect single-replica workloads; protect StatefulSets; protected namespaces (add and remove, DNS-1123 names, at most 50). Tightening saves directly. Loosening (turning a protection off or removing a namespace) opens a confirm dialog naming the effect: "Nodes running pods in `<ns>` become consolidation and rebalancing candidates once those controllers can act."
- **Scope note**: these guardrails are the automation agent's policy and apply to node automation. The vertical engine has its own per-workload policy and skip rules (lock annotation, rollouts, HPA volatility); a line under the panel says so and links to Workload autoscaling. Unifying the two is F12.

### 9.6 Activity

One feed, newest first, with a source filter (Workload autoscaling · Node automation · Cluster events · XamOps changes) and search.

**Vertical engine actions** come from `GET /api/eks/automation/vertical/{c}/actions` (provisional). Every entry carries `dryRun`, so the marker is exact:

| Entry | Marker |
|---|---|
| `dryRun: true` | "Simulated": nothing changed in the cluster |
| `dryRun: false`, succeeded | "Applied" with method ("in place" / "in place and persisted, pods restarted") |
| `dryRun: false`, failed or refused | "Failed" / "Refused" with the engine's reason |
| `dryRun` missing | "Unverified" (defensive; the contract says it is always present) |

Each entry shows workload, container changes (before → after), who or what triggered it (Auto cycle or the approving user), and time.

**Automation agent events** come from `recentEvents`, classified by `classifyAutomationEvent()` (§11.1) using the identifiers each producer actually writes:

| Class | Rule | Marker |
|---|---|---|
| Automation agent report | `id` matches `^(rebalance\|evictor\|rightsizer\|node-opt-cordon\|idle-drain\|idle-drain-fail\|spot-convert\|spot-convert-fail\|spot-interrupt)-\d+$` (`main.go:188-423`) | "Unverified": the agent doesn't report whether it was a dry run. "Simulated" only where the payload says so (`node-drain-progress` `reason: "dry-run"`, once served, F4). |
| Cluster event | message begins with `[Reason]` and `id` is `<name>-<uid>` (`autoscaling_metrics.go:151-155`) | "Kubernetes event", observed, not an XamOps action |
| XamOps change | numeric `id` from the `agent_events` fallback (`EksAutoscalingService.buildEventsFromAgentLog:526-549`) | "Changed in XamOps" |

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

- **Workloads table**: name, namespace, kind, replicas ready/desired, CPU and memory requests summed from pod containers (deployments carry none, harness §6), CPU usage from `topCpuPods` where present ("—": "Usage is reported for the top 20 pods only"), efficiency (usage ÷ requests) only when both are known, recommendation summary and mode from the vertical engine (joined on namespace/kind/name; "—" when the engine has no entry or is not configured), and a link to the row in Workload autoscaling. Namespace deployment counts are derived from `k8sDeployments`, never from `namespaces[].deploymentCount` (always 0, harness §6).
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
| Container Insights (AWS) | `K8sClusterInfo.containerInsights` (D3) | Receiving / Stale / Not receiving / Not set up / Unknown (with `MISSING_AWS_PERMISSION` remedy) |
| Cluster API access (vertical engine) | vertical engine status `clusterAccess` | Configured / Not configured / Unreachable / Unknown; powers Workload autoscaling |
| Prometheus via API proxy (vertical engine) | vertical engine status `metricsSource` | Reachable (with the source name) / Not reachable / Unknown; powers Workload autoscaling recommendations |
| Automation agent | capabilities `automationAgent` | Connected / Stale / Not installed; powers node automation |

Each row also lists "Powers: Cost › Namespaces, …" and a "Set up" link to the matching Install card. The two Prometheus rows are different paths and say so: the agent queries Prometheus inside the cluster; the vertical engine reaches it from the backend through the Kubernetes API service proxy.

### 10.8 Data sources › Install

Cards in order: XamOps agent (required) → "Open setup guide" (`initialSection: 'quick'`); Monitoring stack → `part1`; Security tools → `part2`; Cluster API access → `ClusterAccessGuide` inline (D13); Prometheus for rightsizing → status plus a link to `part1`; Automation agent (preview, node automation) → `AutomationAgentGuide` inline (D13); Verify → `part5`. Each card shows its live status mark next to its title. The fleet's "Connect a cluster" panel reuses the same cards with a cluster-name field and a region select; both pre-fill the guide through the modal's existing `clusterName` and `region` props (`EksIntegrationModal.tsx:21-27,227-228`), so the placeholder handling (`EKS_PLACEHOLDERS`) is unchanged.

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
│   ├── verticalRightsizerApi.ts         new: provisional engine client (§11.5)
│   └── verticalRightsizerAdapter.ts     new: wire → view model (D16)
├── hooks/
│   ├── useK8sCapabilities.tsx           new: K8sCapabilitiesProvider + useK8sCapabilities()
│   ├── useEksSectionRoute.ts            new: tab/sub URL state + legacy map (§5)
│   ├── useEksAutomation.ts              new: Go-agent automation data and mutations (replaces useEksAutoscaling.ts)
│   ├── useVerticalRightsizer.ts         new: engine status, workloads, actions, mutations
│   ├── useCloudK8sFleet.ts              new: fleet orchestration with bounded fan-out
│   ├── useEksDashboard.ts               modified: fabricated fields and tab state removed (D9, D10)
│   └── useEksAlertsData.ts              modified: export extractAlerts; targetSub
├── types/
│   ├── k8sAutomation.ts                 new: ReasonCode, ActionId, Capability, CapabilitiesDto, K8sRefusal
│   ├── verticalRightsizer.ts            new: provisional wire types + view model
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
│   │   ├── GatedAction.tsx              wraps any button-like control (render prop)
│   │   ├── GatedSwitch.tsx              role="switch" with the same gating
│   │   ├── ReasonText.tsx               reason line + remedy link, id for aria-describedby
│   │   ├── reasonCopy.ts                ReasonCode → { text, remedy } (§6.1), single table
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
│   │   │                                  automation/VerticalPolicyDrawer.tsx, automation/VerticalApplyDialog.tsx,
│   │   │                                  automation/PersistOptInDialog.tsx
│   │   ├── SecuritySection.tsx          + security/VulnerabilitiesPanel.tsx (from EksSecurityTab)
│   │   └── DataSourcesSection.tsx       + datasources/ConnectionsPanel.tsx, datasources/InstallPanel.tsx
│   ├── tabs/                            kept and embedded: EksPodsTab, EksContainersTab, EksNodesTab,
│   │                                    EksKarpenterTab, EksPerformanceTab, EksResourcesTab (events removed),
│   │                                    KubescapeComplianceView, RuntimeSecurityView
│   ├── EksIntegrationModal.tsx          modified: initialSection, greyscale header, checklist copy (D13)
│   └── EksDiagnosticsPanel.tsx          unchanged
├── components/Alerts/EksAlertsTab.tsx   modified: URL with sub
├── components/Shared/CommandPalette.tsx modified: entry removed, keywords merged
├── components/Sidebar/Sidebar.tsx       modified: CloudK8s active on detail pages
├── constants/xamopsTabs.ts              modified: cloudops-cloudk8s-automation (D2)
└── demo/
    ├── demoRouter.ts                    modified: capabilities, autoscaling, vertical engine routes (§16)
    ├── k8sAutomationMockData.ts         new: fixtures
    ├── xamopsMockData.ts                modified: node labels, a not-monitored shell for staging
    └── demoRouter.test.ts               extended
```

Deleted: `EksAutoscalingTab.tsx`, `useEksAutoscaling.ts`, `EksTabs.tsx`, `EksSummaryCards.tsx`, `EksHeader.tsx`, `EksOverviewTab.tsx`, `EksCostTab.tsx`, `EksWorkloadsTab.tsx`, `EksSecurityTab.tsx` (their content moves into the panels named above), `CloudK8sClusterTable.tsx` (replaced by `FleetClusterTable`), `CloudK8sIntegrations.tsx`, `GcpCloudK8sIntegrations.tsx`, `AzureCloudK8sIntegrations.tsx`, `CloudK8sHelpModal.tsx`.

**Shared component contracts.**

```ts
// types/k8sAutomation.ts
export type ReasonCode =
  | 'DEMO' | 'PROVIDER_UNSUPPORTED' | 'CONTROLLER_NOT_READY' | 'ANALYSE_ONLY' | 'GITOPS_MANAGED'
  | 'NO_USER_PERMISSION' | 'READ_ONLY_CONNECTION' | 'CONNECTION_NOT_VERIFIED' | 'UNKNOWN'
  | 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | 'NO_METRICS_SOURCE' | 'AGENT_NOT_INSTALLED'
  | 'INSUFFICIENT_HISTORY' | 'MISSING_AWS_PERMISSION' | 'GUARDRAIL_LOCKED';

export interface Capability {
  allowed: boolean;          // commit allowed
  editable: boolean;         // draft edits allowed (true for DEMO-only blocks)
  reason: ReasonCode | null; // primary reason by §6.4 precedence
  also: ReasonCode[];        // remaining reasons, in precedence order
  message?: string;          // server-provided text (readiness, engine) overrides reasonCopy text
}

// GatedAction.tsx
interface GatedActionProps {
  capability: Capability;
  children: (p: { 'aria-disabled'?: true; 'aria-describedby'?: string;
                  onActivate: (fn: () => void) => () => void }) => React.ReactElement;
  reasonPlacement?: 'inline' | 'popover';   // inline under the control; popover in dense table cells
}
```

`GatedAction` never sets the `disabled` attribute. A blocked control keeps focus and hover, gets `aria-disabled="true"` and `aria-describedby` pointing at the `ReasonText` id, shows the lock icon, and its activation is a no-op. In table cells the lock is a focusable button opening a popover with the reason and remedy (`react-aria-components` `DialogTrigger` + `Popover`). Plain form validation (an empty required field) uses the same pattern with the validation message as the description.

`K8sDataTable<T>` props: `rows`, `columns: ColumnDef<T>[]` (`id`, short `header`, `cell`, `sortValue` returning number | string | null, `csv`, `align`, `hideBelowPx`), `rowKey`, `caption` (accessible), `search?: (row) => string`, `filters?: FilterDef<T>[]`, `pageSize` (25 default, 50, 100), `initialSort`, `exportName`, `source`, `loading`, `error`, `emptyState`, optional `expandRow`. Null sort values always sort last. Header buttons carry `aria-sort`. Changing search or filters resets to page 1. CSV exports the filtered, sorted rows; cells starting with `= + - @` are prefixed with `'`.

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

- **`useK8sCapabilities`**. `status: 'loading' | 'ready' | 'error'`, `forCluster(name)`, `can(actionId, ctx?)`, `refetch()`. `can` combines account and user gates, readiness, the cluster's agent state and, for vertical actions, the engine status passed in `ctx`; it returns a `Capability` in §6.4 order. While loading, every action is blocked without a reason line (a skeleton); on error, every write is blocked with `UNKNOWN`. Demo mode adds `DEMO` (commit only). Non-AWS providers add `PROVIDER_UNSUPPORTED`. The provider refetches on mount, on Retry, and after any refusal whose code is a gate, because a gate changed underneath the page.
- **`useEksAutomation(cluster)`**. Reads `GET /{c}`, `/evictor-candidates`, `/node-fleet`, `/rebalance-status` in parallel (`Promise.allSettled`). Each failure becomes that panel's error state; `fetchData` no longer swallows everything (today `useEksAutoscaling.ts:285-320`, with the silent `catch` at `:316`). Mutations: `saveSection(section, body)`, `setEnabled(on)`, `setScope(mode)`, `triggerRebalance()`. Each one sets `pending[section]`, calls the API, and on success refetches and shows "Saved · `<time>`" next to the control. On failure it shows the mapped reason or message at the control, keeps the draft, changes no server-backed state, and never shows a success toast. There are no optimistic updates, so there is nothing to roll back. `triggerRebalance` polls `GET /rebalance-status` (10 s, up to 30 min, backoff to 60 s after errors, "Status unavailable" after three failures) and renders only what the server returns. It is unreachable while `rebalancer` is not ready.
- **`useVerticalRightsizer(cluster)`**. Reads engine status and workloads (one GET) and actions (one GET) with a 60 s per-cluster cache. Mutations `setPolicy(ref, policy)` and `apply(ref)` follow the same rules as above. After `apply`, it refetches workloads and actions.
- **`useCloudK8sFleet(accountId)`**. 1) cluster list; 2) capabilities; 3) per-cluster dashboards through `createLimiter(4)` (`utils/concurrencyLimit.ts`), writing through `EksDashboardCacheContext`; 4) per-cluster vertical status for clusters where it matters, through the same limiter. Rows render immediately and fill in as each request finishes. Fleets over 40 clusters fetch only the current table page, and the summary says "Summary covers the clusters on this page" (aggregate endpoint is F13). An `AbortController` cancels in-flight requests on account change or unmount.
- **`useEksDashboard`** keeps fetch, cache, sync and polling, and the request derivations (`derivedNodeRequestMetrics`, `derivedPodResourceMetrics`, `normalizedPods`, counts). It loses `activeTab`, the composite score fields, `potentialSavings` and `quickWins`. Sync polling keeps its 3 s × 40 loop; a timeout now shows "Sync is taking longer than expected. The latest data will appear when it finishes." instead of a console warning.

### 11.3 Backend classes

| Class | Change | Notes |
|---|---|---|
| `security/K8sAutomationAccessService` | new | `resolveAccounts(user, awsAccountId)` → entitled rows (or `403 K8S_NO_ACCOUNT_ACCESS`); `resolve(user, awsAccountId, clusterName)` → `K8sAutomationRequestContext`; `writeDecision(user, accounts)` → ordered reasons, stricter row wins (§6.6 step 4); `canChangeAutomation(user)`. Uses `CloudAccountAccessService.canAccess`, `CloudAccountRepository`, `KubernetesClusterConfigRepository`, `UserTabAccessRepository`. Public and self-contained so the engine can reuse it (R2). |
| `security/K8sAutomationGuardInterceptor` | new | §6.6. |
| `security/K8sAutomationRequestContext` | new | record `(ClientUserDetails user, List<CloudAccount> accounts, KubernetesClusterConfig config)` with `primaryAccount()` (the owning row, else the first); `ATTRIBUTE` constant. |
| `config/K8sAutomationWebConfig` | new | `WebMvcConfigurer.addInterceptors` for `/api/eks/autoscaling/**`. |
| `config/K8sAutomationReadinessProperties` | new | §6.7, including `vertical-rightsizing`. |
| `exception/K8sAutomationReason` | new | enum of the §6.1 gate codes and the six error codes, with `code()` = `K8S_` + name, `gate()`, HTTP status and default message. |
| `exception/K8sAutomationRefusedException` | new | status, reason, message, field. |
| `service/K8sAutomationPolicyNormalizer` | new | §6.6: `forAgent(storedJson)`, `merge(current, section, body)`, `validate(section, body)`, `defaults()`. Own `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES` off. |
| `service/K8sCapabilitiesService` | new | builds `K8sCapabilitiesDto`; entitlement and account gates through `K8sAutomationAccessService.resolveAccounts` and `writeDecision`, so capabilities and the interceptor cannot disagree; `Clock` injected for thresholds. |
| `controller/K8sCapabilitiesController` | new | `GET /api/xamops/k8s/capabilities`. |
| `dto/k8s/K8sCapabilitiesDto` | new | §11.4. |
| `dto/autoscaling/AutomationPolicyBundle` (+ `HpaManagerPolicyDto`, `VpaManagerPolicyDto`) | new | Moved out of `EksAutoscalingService.PolicyBundle` (`:388-397`) unchanged in JSON shape, plus the two Go sections. |
| `dto/k8s/ContainerInsightsStatusDto` | new | `status, lastEventAt, checkedAt, reason, action`. |
| `controller/EksAutoscalingController` | modified | Reads `K8sAutomationRequestContext`; no `Long.parseLong(accountId)`; refusals via the exception; `POST /rebalance` and `PUT /scale-out-policy`, `/node-templates/**`, `/workload-rightsizer` refused while not ready (§6.3); no `e.getMessage()` in responses. |
| `service/EksAutoscalingService` | modified | Methods take `KubernetesClusterConfig`; `findConfig` and `requireAutoscalingEnabled` removed; saves go through the normalizer; `setAutoscalingEnabled` persists the default bundle on first enable; `summarize(config)` for capabilities; `logEvent` details include the acting user ("Evictor policy updated by `<username>`"). |
| `controller/AgentController` | modified | `getAutoscalingPolicy` serves `normalizer.forAgent(...)` in place of `:793-805`. No other change. |
| `service/EksService`, `dto/k8s/K8sClusterInfo` | modified | D3. |
| `repository/AgentDataCacheRepository` | modified | projection query `findTypeTimestampsByClusterConfigIdIn(ids)` returning `dataType, collectedAt, itemCount, agentVersion` without loading `data_json`. |
| `repository/AgentEventRepository` | modified | `findFirstByClusterConfigIdAndAgentVersionEndingWithOrderByCreatedAtDesc(Long, String)`. |
| `service/faq/FeatureFaqCatalog` (+ test) | modified | D2; the `cloudops-cloudk8s` description is rewritten for the new sections. |
| `permissions-common/.../AwsPermissionCatalog` | modified | `logs` → `+ KUBERNETES` (D3). Build note: `mvn -f permissions-common/pom.xml install` before `mvn -f xamops-service/pom.xml compile`, or `mvn -pl xamops-service -am compile` from the root aggregator. |

### 11.4 API contracts owned by this branch

**`GET /api/xamops/k8s/capabilities?accountId=<awsAccountId>`** (authenticated by `SecurityConfig.java:328`).

```json
{
  "schemaVersion": 1,
  "accountId": "605134457560",
  "generatedAt": "2026-10-10T09:30:00Z",
  "connection": { "accessLevel": "READ_ONLY", "status": "CONNECTED" },
  "user": { "canChangeAutomation": false, "reasons": ["NO_USER_PERMISSION"] },
  "writeGate": { "allowed": false, "reasons": ["NO_USER_PERMISSION", "READ_ONLY_CONNECTION"] },
  "readiness": {
    "vertical-rightsizing":  { "ready": false, "reason": "CONTROLLER_NOT_READY", "message": "Automatic and one-click resizing are disabled until the vertical rightsizer passes live validation. Recommendations are available." },
    "evictor-live":          { "ready": false, "reason": "ANALYSE_ONLY", "message": "Live consolidation is disabled until pod-fit simulation is available." },
    "rebalancer":            { "ready": false, "reason": "CONTROLLER_NOT_READY", "message": "Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly." }
  },
  "clusters": [
    {
      "clusterName": "xamops-test-1",
      "registered": true,
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

The example is abridged: `readiness` always lists every key in §6.7, with `ready: true` and no message for keys that are ready. `connection.accessLevel` is `READ_WRITE` only when `accessType` is `"read-write"` (case-insensitive) and `READ_ONLY` otherwise, including null (D1). `clusters` lists every config resolved for the account (modern and legacy rows, de-duplicated by name, modern first); clusters EKS lists without a config are simply absent and the UI treats them as M0. Status thresholds: agent `CONNECTED` ≤ 15 min, else `STALE`; automation agent `CONNECTED` ≤ 5 min, else `STALE`; `NOT_INSTALLED` when never seen; `UNKNOWN` when that cluster's queries failed (the rest of the response still returns). Refusals: `400 K8S_INVALID_REQUEST` (format), `403 K8S_NO_ACCOUNT_ACCESS`. No internal ids are exposed.

**`/api/eks/autoscaling/**`** keeps its paths and success shapes. Changes are the interceptor (§6.6), the readiness refusals (§6.3), validation (§13) and the uniform refusal body. `PUT` and `POST` successes return `{ "success": true, "message": "…" }` as today; the UI refetches rather than trusting the body.

**`GET /api/v1/agent/autoscaling-policy`** (agent-facing) always returns a complete, normalized `AutomationPolicyBundle` (§6.6).

**`GET /api/xamops/k8s/clusters`** adds `containerInsights` per cluster (D3); `connected` keeps its meaning for old consumers.

### 11.5 Vertical engine (consumed; provisional)

From the orchestrator's notification. Paths, fields and names are provisional until `.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md` is published; only `verticalRightsizerApi.ts`, `verticalRightsizerAdapter.ts`, `types/verticalRightsizer.ts` and the demo fixtures change when it is.

| Call | Purpose |
|---|---|
| `GET /api/eks/automation/vertical/{cluster}?accountId=&region=` | engine status (enabled, ready, cluster-access status, metrics source, last cycle), `gateReasons`, workloads |
| `PUT /api/eks/automation/vertical/{cluster}/workloads/{namespace}/{kind}/{name}/policy?accountId=&region=` | mode `OFF`/`RECOMMEND`/`AUTO`, headroom, percentile, window, apply method |
| `POST /api/eks/automation/vertical/{cluster}/workloads/{namespace}/{kind}/{name}/apply?accountId=&region=` | one-shot approved apply of the current recommendation |
| `GET /api/eks/automation/vertical/{cluster}/actions?accountId=&region=` | audit and activity, each entry with `dryRun` |

Refusals are `403` with the shared codes (§6.1), plus the engine's five. The UI parses `code` with or without the `K8S_` prefix (R3).

View model the components use (the adapter's output):

```ts
export interface VerticalEngineView {
  engine: {
    enabled: boolean | null; ready: boolean | null;
    clusterAccess: 'CONFIGURED' | 'NOT_CONFIGURED' | 'UNREACHABLE' | { kind: 'unknown'; raw: string } | null;
    metricsSource: { reachable: boolean; name: string | null } | null;
    lastCycle: { at: string | null; evaluated: number | null; error: string | null } | null;
  };
  gateReasons: Array<ReasonCode | { kind: 'unknown'; raw: string }>;
  workloads: VerticalWorkload[];
}
export interface VerticalWorkload {
  ref: { namespace: string; kind: string; name: string };
  policy: { mode: 'OFF' | 'RECOMMEND' | 'AUTO' | Unknown; headroomPct: number | null;
            percentile: number | null; window: string | null; applyMethod: 'IN_PLACE' | 'IN_PLACE_AND_PERSIST' | Unknown };
  replicas: number | null;
  hpa: { present: boolean; name: string | null } | null;
  gitopsManaged: boolean | null;
  skipReason: string | null;              // mid-rollout, HPA-volatile, lock annotation
  findings: Array<'REQUESTS_UNSET' | 'IDLE' | 'THROTTLED' | Unknown>;
  containers: Array<{
    name: string; qosClass: string | null;
    current: Resources; recommended: Resources | null; reasons: string[];
  }>;
  lastAction: { at: string; dryRun: boolean | null; outcome: string } | null;
}
type Resources = { cpuRequestM: number | null; memRequestMi: number | null; cpuLimitM: number | null; memLimitMi: number | null };
type Unknown = { kind: 'unknown'; raw: string };
```

If the final contract lacks a field the UI uses (for example `replicas` or `gitopsManaged`), the feature that needs it degrades: savings fall back to `k8sDeployments` replicas, and the persist option relies on the engine's `GITOPS_MANAGED` refusal instead of being disabled up front.

---

## 12. Error handling

### 12.1 Backend

| Operation | Failure | Recoverable? | Caller receives | Log |
|---|---|---|---|---|
| Interceptor: principal | not `ClientUserDetails` | no | `401 K8S_UNAUTHENTICATED` | WARN (should be unreachable behind Spring Security) |
| Interceptor: input format | bad `clusterName` or `accountId` | yes (caller fixes input) | `400 K8S_INVALID_REQUEST`, `field` set | DEBUG |
| Interceptor: entitlement | unknown account, not the caller's, or no AWS row | no | `403 K8S_NO_ACCOUNT_ACCESS`, same message for every case | WARN with user id and requested cluster |
| Interceptor: write gates | demo, no permission, read-only, not verified | no (needs a different identity or connection) | `403 K8S_DEMO` / `K8S_NO_USER_PERMISSION` / `K8S_READ_ONLY_CONNECTION` / `K8S_CONNECTION_NOT_VERIFIED` | INFO audit line |
| Interceptor: cluster | no config for a mutation | no | `404 K8S_CLUSTER_NOT_REGISTERED` | INFO |
| Section save: readiness | not-ready live or apply value, or a retired controller | no | `403 K8S_CONTROLLER_NOT_READY` / `K8S_ANALYSE_ONLY` with the readiness message | INFO |
| Section save: locked guardrail | loosening a locked field | no | `403 K8S_GUARDRAIL_LOCKED`, `field` set | INFO |
| Section save: ranges | out-of-range or missing value | yes | `400 K8S_INVALID_POLICY`, `field` and a sentence | WARN |
| Section save: persistence | DB error, serialization error | yes (retry) | `500 K8S_INTERNAL`, fixed message | ERROR with stack |
| `POST /rebalance` | rebalancer not ready | no | `403 K8S_CONTROLLER_NOT_READY` | INFO |
| GET endpoints | config missing | n/a | Existing behaviour: `404` for `GET /{c}`; empty bodies for the others | DEBUG |
| GET endpoints | unexpected exception | yes | `500 K8S_INTERNAL`, fixed message | ERROR |
| Capabilities: per-cluster query | agent-events or data-cache query fails for one cluster | yes | That cluster's `agent`/`automationAgent` status `UNKNOWN`; the rest returns | WARN |
| Capabilities: account lookup | DB unavailable | yes | `500 K8S_INTERNAL` | ERROR |
| Agent policy serve | stored bundle needed coercion | yes | Normalized bundle | WARN with config id and coerced fields |
| Agent policy serve | stored bundle unparseable | yes | Complete default with `autoscalerMode: "disabled"` (agent idles) | ERROR |
| Container Insights check | log group missing | n/a | `NOT_CONFIGURED` | DEBUG |
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
| Vertical engine status | 404 | V6 panel ("not available in this environment yet") | Vertical controls hidden behind the panel | — |
| Vertical engine status | other error | V5 panel + Retry; vertical writes blocked (`UNKNOWN`) | — | `console.warn` |
| Any automation mutation | 403 with a known gate code | Reason text and remedy at the control; capabilities refetch | Draft kept; server state unchanged | — |
| Any automation mutation | 403 with an unknown code | "Not allowed" + the server message (plain text, ≤ 300 chars) | Draft kept | `console.warn` with the code |
| Any automation mutation | 400 | Field message inline at the field | Draft kept | — |
| Any automation mutation | 403 `K8S_NO_ACCOUNT_ACCESS` or 404 `K8S_CLUSTER_NOT_REGISTERED` | The page-level message for that code (§6.1), with "Set up monitoring" for the 404 | Draft kept; capabilities refetch | `console.warn` with the code |
| Any automation mutation | 409, 5xx, network, timeout | "Not saved. `<message>`. Your changes are still in the form." + Retry | Draft kept | `console.error` |
| Any automation mutation | success | "Saved · `<time>`" beside the control (`role="status"`) | Refetched from the server | — |
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
| Safety guards | `respectPodDisruptionBudgets` and `protectCriticalNamespaces` must stay true; `criticalNamespaces` ≤ 50 entries, each DNS-1123 (`^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$`), trimmed, lower-cased, de-duplicated; `kube-system` and `xamops-system` added if missing (tightening, silent) | `403 K8S_GUARDRAIL_LOCKED`; bad names `400` with the offending entry |
| Rebalancer config | `scheduleEnabled` false (not ready); `gracefulDraining` true (locked); `drainTimeoutMinutes` 5-60; `status`, `lastRebalance*` ignored (server-owned); `scheduleCron` ≤ 64 chars, stored only | `403`; ranges `400` |
| Scale-out, node templates, Go rightsizer | not accepted while not ready / retired | `403 K8S_CONTROLLER_NOT_READY` |
| Vertical policy (UI side) | mode in the engine's enum; headroom, percentile and window present and numeric; final ranges from the contract (R5) | Inline field error; the engine's `400` message shown at the field |
| Protected namespace entry (UI) | same DNS-1123 rule, no duplicates | Inline error, Add stays aria-disabled with the error as its description |
| CSV export | cells starting with `= + - @` prefixed with `'` | — |

The UI mirrors the server's ranges so users see errors before saving; the server remains authoritative.

---

## 14. Visual and accessibility rules

CLAUDE.md governs: black, white and grey only; no emojis; same service, same icon; short labels; restrained. DESIGN.md's type and spacing scale applies; its accent and semantic colours (terracotta, error crimson, focus blue) are superseded by greyscale, so **status is never carried by colour**.

- **Neutral ladder** on white (the WAR critique measured the ladder, `.impeccable/critique/…warpage…md:70-72`): primary text `rgba(0,0,0,0.88)`; secondary text, reason text and disabled labels at least `rgba(0,0,0,0.6)` (≈ 5.7:1); nothing that carries meaning below `rgba(0,0,0,0.55)` (≈ 4.7:1); `≤ 0.3` only for borders and decorative glyphs.
- **Type**: the app's Inter stack; sizes 12 (labels, metadata), 13 (table body), 14 (body, panel titles), 16 (section headings), 20 and 24 (tile values); `tabular-nums` for figures. Nothing smaller than 12 px; uppercase only at 12 px with modest tracking.
- **Status marks** (`StatusMark`): icon + label + weight. Healthy or receiving: `mdi:check-circle` filled, label weight 600. Attention or stale: `mdi:clock-alert-outline`, label plus age. Error: `mdi:alert-octagon` filled, weight 700, 1px black border. Not set up or not installed: `mdi:circle-off-outline`, secondary text. Unknown: `mdi:help-circle-outline`. Simulated: `mdi:flask-outline` + "Simulated". Unverified: `mdi:progress-question` + "Unverified".
- **Disabled automation controls** are distinguishable from ordinary grey UI because they always carry `mdi:lock-outline` and a visible reason line. The control keeps its normal outline at reduced fill rather than fading to near-white.
- **Focus**: 2 px `rgba(0,0,0,0.88)` ring with 2 px offset on every interactive element, including `aria-disabled` ones.
- **Tabs**: WAI-ARIA tabs through `react-aria-components` (`tablist` / `tab` / `tabpanel`, roving tabindex, arrow keys, Home and End, `aria-selected`). Two labelled tablists: "Cluster sections" and "`<Section>` views". Panels have `aria-labelledby` back to their tab.
- **Tables**: `<table>` with `<caption>` (visually hidden where a visible title exists), `scope="col"`, sortable headers as buttons with `aria-sort`, pagination controls with labels, row expansion with `aria-expanded`.
- **Live regions**: save results and refusals use `role="status"`; errors that block a section use `role="alert"` once.
- **Dialogs**: the existing `AlertDialog` (`components/ui/AlertDialog.tsx`) for confirmations; focus moves to the dialog and returns on close; the destructive or restarting choice is never the default button.
- **Icons**: from the generated bundle (`npm run icons:bundle` runs on build). EKS uses `resolveAwsServiceIcon('EKS Cluster')` everywhere (`utils/awsServiceIcons.ts`, the single source for AWS service icons); Kubernetes objects use `resourceMark('kubernetes_…')` (`utils/resourceBranding.tsx`). Font Awesome classes are not added in new code.
- **Layout**: works at 1280 px and up. The summary strip wraps 6 → 3 columns below 1440 px. Tables scroll horizontally inside their card, and low-priority columns hide below 1440 px (`hideBelowPx`).
- **Motion**: 150-200 ms opacity transitions on panel change only; none with `prefers-reduced-motion: reduce`. No page-load choreography.
- **Copy**: sentence case, short button labels ("Save", "Apply now", "Retry", "Set up"), no exclamation marks, no "Oops".

---

## 15. Performance

- **Code splitting**: each primary section is `React.lazy(() => import('../components/EksDetails/sections/<X>Section'))` inside a `Suspense` boundary with a section-shaped skeleton and an `ErrorBoundary` (`components/Shared/ErrorBoundary.tsx`). `EksIntegrationModal` (69 KB source) is lazy too, loaded on first open. Target: the `EksDetailsPage` chunk is at most 150 kB minified (327.66 kB today, audit §2.1), and no section's code is in it. The `vite:preloadError` reload listener (`main.tsx:143`) stays and covers the extra chunks.
- **Fan-out**: the fleet uses `createLimiter(4)` for dashboards and engine status, auto-fetches at most 40 clusters (page-scoped beyond that), and aborts on account change.
- **Caching**: dashboards through `EksDashboardCacheContext` (5 min); capabilities per account in the provider; automation and engine GETs 60 s per cluster in module scope; nothing refetches on subtab switch.
- **Derivations**: memoized per dashboard object; workload aggregation is a single pass over pods; tables paginate.
- **Polling**: only sync (existing) and the ready-state rebalance status; both stop on unmount and on terminal states.

---

## 16. Demo mode

Two layers, because `apiFetch` consults the router once without the HTTP method (`apiClient.ts:149`) and the patched `window.fetch` consults it again with the method (`main.tsx:29`):

1. **The UI never sends demo mutations.** `isDemoMode()` adds `DEMO` to every commit; editing drafts still works.
2. **The router answers every path anyway.** All new routes are added in `routeXamops` before the existing catch-alls (`demoRouter.ts:398-405` for `/api/xamops/{cloudk8s,eks,gke,aks}` and `:468-469` for `/api/xamops/k8s/`). Mutation URLs are matched by path shape, not by method, so the method-blind first pass cannot return a GET fixture for a PUT.

| Route (regex on the path) | Fixture |
|---|---|
| `/api/xamops/k8s/capabilities` | per `accountId`: `demo-aws-001` read-write and permitted; `demo-aws-002` read-only; both with readiness as in production (all not ready) |
| `/api/eks/autoscaling/[^/?]+(\?\|$)` | prod and eu: enabled Go-agent dashboard with normalized policies, KPIs (spot ratio, utilisation; never "saved" figures) and mixed `recentEvents`; staging: `404` |
| `/api/eks/autoscaling/[^/]+/(evictor-candidates\|node-fleet\|rebalance-status\|rightsizing)` | evictor candidates shaped like the harness capture (DaemonSet skip reasons), node fleet counts, `{status: "idle"}`, empty rightsizing |
| `/api/eks/autoscaling/[^/]+/(scale-out-policy\|evictor-policy\|workload-rightsizer\|safety-guards\|node-optimizer\|rebalancer\|mode\|enabled\|rebalance)(\?\|$)` and `/api/eks/autoscaling/[^/]+/node-templates/` | `403 { success: false, code: "K8S_DEMO", message: "Demo workspace. Changes aren't saved." }` |
| `/api/eks/automation/vertical/[^/?]+(\?\|$)` | prod: engine configured, Prometheus reachable, 9 workloads mixing Recommend and Off, findings (`REQUESTS_UNSET`, `IDLE`, `THROTTLED`), one HPA-managed workload with a CPU change, one skipped by the lock annotation, one GitOps-managed; eu: `CLUSTER_ACCESS_NOT_CONFIGURED`; staging: `NO_METRICS_SOURCE` |
| `/api/eks/automation/vertical/[^/]+/actions` | entries with `dryRun: true` and `dryRun: false` |
| `/api/eks/automation/vertical/[^/]+/workloads/.+/(policy\|apply)` | `403 K8S_DEMO` |
| `/api/xamops/eks/staging-eks-cluster/dashboard` | the all-zeros shell, `clusterHealthScore: 100` included, to show that the UI ignores it (M0) |

Fixture numbers follow the shapes captured by the harness (§2 and `captures/phase5/*`) and use believable, uneven values. They are demo-only and never reachable outside a demo session (`demoConfig.ts:13-16`). `demoAccounts` gains `access: 'read-write'` and `access: 'read-only'` on the two AWS demo accounts so `Account.access` agrees with the capabilities fixture.

---

## 17. Testing

Nothing here needs an AWS call or a live cluster; fixtures come from the harness captures and the provisional contract.

**Frontend (vitest + Testing Library, `npm run test`).**

| Area | What is tested |
|---|---|
| `useEksSectionRoute` | all eleven legacy values; unknown `tab` and `sub`; missing `sub`; other params preserved; `replace` used |
| `useK8sCapabilities.can` | each reason in isolation; §6.4 precedence for every pair that can co-occur; demo is commit-only; fail-closed on error; provider unsupported; union with engine `gateReasons` |
| `GatedAction`, `GatedSwitch` | `aria-disabled` and never `disabled`; `aria-describedby` resolves to visible reason text; activation is a no-op; focusable; remedy rendered; popover variant keyboard-reachable |
| `reasonCopy` | every `ReasonCode` has text; unknown codes render generically |
| `k8sDerivations` | monitoring state, including the all-zeros shell with `clusterHealthScore: 100` → M0; health signals; requests aggregated from pod containers; namespace deployment counts from `deployments[]`; node groups from labels; cost source order; unallocated cost only with split and known requests; savings eligibility (floor, unset, HPA conflict, skipped, replicas); QoS change detection |
| `k8sPricing` | the two rates exist only here (a test greps `src/` for the literals outside this module) |
| `k8sQuantities` | binary and decimal suffixes; `m` on CPU and memory; missing and unparseable input → `null`; `250Mi` equals `0.25Gi`; the dashboard's request totals are unchanged for millicore and binary-suffixed input (the only change is the decimal-suffix fix) |
| `k8sEvents.classifyAutomationEvent` | the captured `rebalance-1791563637` and `spot-convert-1791563637` entries → automation agent, unverified; Kubernetes `[Reason]` events; numeric XamOps ids |
| `verticalRightsizerAdapter` | provisional fixture → view model; unknown enums pass through as unknown; missing numbers stay null |
| `useEksAutomation`, `useVerticalRightsizer` | failed save shows the error and leaves server state unchanged; no success notice on failure; 403 with a gate code triggers a capabilities refetch; rebalance status changes only from polled responses (fake timers); no `(local)` strings anywhere (grep test) |
| Node optimizer fields | `savingsPct`, `interruptible` and `estimatedSavingsPct` never appear in rendered output |
| Demo router | every new GET shape returns its fixture; every mutation shape returns `403 K8S_DEMO` regardless of method; capabilities differ by account; existing `demoRouter.test.ts` cases still pass |
| `csvExport` | quoting, newlines, formula-injection prefix |
| Sections | each renders its loading, empty, error and normal states from fixtures; Not-monitored panel replaces content |
| Contract fixture | `k8s-reason-codes.json`, shared with the backend test below, lists every code; both sides fail if either adds a code without the other |

**Backend (JUnit 5, Mockito, AssertJ, `mvn -pl xamops-service -am test` from the root aggregator).** Plain unit tests constructing the classes with mocks, following `ComplianceOpsControllerAuthorizationTest`: each denial asserts both the refusal and that the service was never reached.

| Class | What is tested |
|---|---|
| `K8sAutomationAccessService` | entitlement via `canAccess`; padded and raw AWS ids; modern then legacy config lookup; the owning row decides the account gates, and without an owner the stricter of the padded and raw rows does; demo identities; BillOps-only roles refused; unrestricted users allowed; restricted users need both keys; read-only and unverified connections; null `accessType` is read-only |
| `K8sAutomationGuardInterceptor` | GET passes after resolution; every write gate returns its code for PUT, POST and DELETE; bad inputs → 400; unknown account → 403 with the same message as not-yours; missing config → 404; audit line written |
| `K8sAutomationPolicyNormalizer` | partial stored JSON → complete bundle (null `safetyGuards`, null `criticalNamespaces`, missing HPA/VPA sections); `kube-system` and `xamops-system` always present; Go rightsizer always disabled on serve; every readiness key enforced on write (refuse) and on serve (coerce); rebalancer `status` forced idle and client values ignored; unparseable JSON → default with `disabled` |
| Section saves (orchestrator decision 9) | for each section endpoint: save with a partial body → the stored JSON has every section and a non-empty `criticalNamespaces` |
| `EksAutoscalingController` | `POST /rebalance` → 403 and the service is never called; scale-out, node templates and Go rightsizer → 403; no `e.getMessage()` in 500 bodies |
| `AgentController.getAutoscalingPolicy` | no stored policy → complete normalized default (not `{"autoscalerMode":"full"}`); stored `running` → served `idle` |
| `K8sCapabilitiesService` | statuses at the thresholds (injected `Clock`); `-autoscaler` detection from events and data rows; per-cluster failure → `UNKNOWN` without failing the response; readiness map complete |
| `EksService` Container Insights | exact-name match; no streams; fresh, stale, not receiving; AccessDenied → `UNKNOWN` + reason; throttling → `UNKNOWN` |
| `FeatureFaqCatalogTest` | updated `EXPECTED_TAB_KEYS` with `cloudops-cloudk8s-automation` |
| Reason codes | the shared `k8s-reason-codes.json` matches `K8sAutomationReason` |

**Build and CI.** `npm run build` (includes `icons:bundle` and `tsc`) passes, and the chunk report shows the §15 target. `mvn -pl xamops-service -am test` passes. The setup-guide workflow still passes: commands, image tags and the validated files' `docker.io/xamops` references are unchanged. `AzureCloudK8sHelpModal.substitution.test.ts` and `guideVars.test.ts` are untouched.

**Visual check.** Per the mockup skill: run the dev server in demo mode, walk both demo accounts through every section and state, and save screenshots to the task's `screenshots/` folder (`.agents/tasks/cloudk8s-ui-revamp-2026-10-09/screenshots/`), never the repo root.

**Not testable here.** Behaviour against a real cluster and the vertical engine's live behaviour. The engine's own verdict decides `vertical-rightsizing`.

---

## 18. Delivery slices

Each slice builds, passes its tests, and leaves the product working.

| Slice | Contents | Done when |
|---|---|---|
| **S1 Backend safety** | Access service, interceptor, refusal format, normalizer (completeness, readiness, Go rightsizer retired, rebalancer status), readiness properties, agent-policy serve path, capabilities endpoint, repository queries, FAQ key and test, permission-catalog mapping, Container Insights freshness | All backend tests in §17 pass; `/api/eks/autoscaling/**` refuses cross-tenant and read-only writes; the agent can never receive a partial or unsafe bundle |
| **S2 Frontend foundations** | Types, both API clients and the adapter, `useK8sCapabilities`, `useEksSectionRoute`, `SectionTabs`, `GatedAction`/`GatedSwitch`/`ReasonText`/`reasonCopy`, `StatusMark`, `SourceLabel`, `K8sDataTable`, utils, demo routes and fixtures | Unit tests pass; nothing user-visible changes yet |
| **S3 Cluster page** | Shell, header, status strip, lazy sections for Overview, Cost, Workloads, Nodes, Security, Data sources; legacy links; fabricated numbers removed; Not-monitored state; Integration modal changes; alerts deep links; palette and sidebar | Every current tab's data is reachable in its new home; old links resolve; demo walkthrough screenshots |
| **S4 Automation** | Overview, Workload autoscaling (vertical engine), Node autoscaling, Rebalancing, Guardrails, Activity; `useEksAutomation`, `useVerticalRightsizer`; dormant tab and hook deleted | Every control renders through the gating model; no fake success remains; both demo accounts show their states |
| **S5 Fleet** | Summary strip, cluster table, connect panel, setup cards; fake cards removed on all three providers; `CloudK8sHelpModal` deleted | Fleet loads progressively; CSV export works; GCP and Azure pages unchanged apart from the connect card |
| **S6 (optional) GKE/AKS** | Shared components on the GCP and Azure pages | Not part of this branch's acceptance |

The vertical engine's final contract can land at any point; S2's adapter and S4's Workload autoscaling panel absorb it (R5).

---

## 19. Reconciliation points and follow-ups

### 19.1 Reconciliation with other workstreams

| # | With | Point |
|---|---|---|
| R1 | Vertical engine | Read readiness from `xamops.k8s.automation.readiness.vertical-rightsizing` (default false), the property this branch defines. One flag then flips UI and server together. |
| R2 | Vertical engine | Reuse `K8sAutomationAccessService` (`resolve`, `writeDecision`) for tenant scoping and the write gates, or match its rules exactly. Until confirmed, the UI unions both answers. |
| R3 | Vertical engine | Add the engine's five codes to `K8sAutomationReason` and the shared `k8s-reason-codes.json`, or publish the engine's own list so both fixtures agree. Agree whether codes carry the `K8S_` prefix (the UI accepts both). |
| R4 | Vertical engine | The access-entry steps, the minimal ClusterRole and private-endpoint guidance that `ClusterAccessGuide` renders come from the engine's published contract. |
| R5 | Vertical engine | Final API contract replaces §11.5. Only the client, adapter, types and fixtures change. |
| R6 | `fix/autoscaler-controller-safety` (worktree `autoscaler-safety`) | When Go controllers are fixed, readiness keys flip; if the agent starts reporting `dryRun` or `DRY_RUN`, Activity's "Unverified" becomes exact and the Overview's dry-run line changes. Any policy-bundle field changes must be mirrored in `AutomationPolicyBundle` and the normalizer. |
| R7 | Vertical engine | The engine calls customer API servers from the backend. `K8sClientFactory` disables TLS verification today (audit B7, `K8sClientFactory.java:23,49`); the engine must verify the cluster CA. Flagged here because the UI will present that connection as trustworthy. |

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
| F17 | Platform CSRF posture: CSRF is disabled (`SecurityConfig.java:156`) and production sets `SameSite=None` (`application-prod.properties:80`), so a cross-site form POST carries the session. This branch's POST endpoints are refused while not ready, but the platform needs a CSRF decision | Platform-wide |
| F18 | Fix or remove the snapshot rightsizing engine (E7) | Superseded by the vertical engine; removal is a backend change |

---

## 20. Assumptions and open questions

Assumptions made rather than asked (the design review can overturn them):

1. **BillOps-only roles may not change customer automation** (D2). Platform admins with a XamOps role (`ROLE_SUPER_ADMIN`, `ROLE_XAMOPS_ADMIN`) may.
2. **The new permission is not back-filled** for restricted users (D2): automation was never available, so nobody loses anything.
3. **Connection status other than `CONNECTED` blocks automation writes** (`CONNECTION_NOT_VERIFIED`), matching the existing write-client rule.
4. **Pre-configuring node automation without the agent is allowed by the server** and blocked only in the UI (§6.8).
5. **Spot interruption handling stays allowed** (§6.3): it is protective and not on the orchestrator's not-ready list.
6. **The vertical engine's contract is as provisionally described**, including region and account query parameters and per-workload policy; §11.5's degradation rules cover missing fields.
7. **Container Insights thresholds** of 15 minutes and 24 hours (D3) are a judgement from one measurement pair; they are constants, easy to tune.
8. **XamOps support handles read-only → read-write switches** (D1). The remedy copy says "Ask XamOps support to switch it" and does not promise how support does it or that history is kept.

Open questions for the owner:

1. Should XamOps staff ever change a customer's Kubernetes automation, for example during support sessions? (Today's answer: SUPER_ADMIN and XAMOPS_ADMIN may, BillOps may not.)
2. F15 (read-path entitlement on the EKS dashboard and cluster list) is outside this branch's allowed changes but is the same class of issue as E1. Approve it as an immediate follow-up, or fold it into this branch?
3. Can support switch a connection's access level without removing it (customer deploys the read-write role; support updates `accessType` and `roleArn`)? If yes, the explainer can say history is kept; if no, F11 becomes the priority for any customer who wants automation.

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
| 3 | Install: cluster API access and Prometheus as separate rows; `AGENT_NOT_INSTALLED` only for Go-agent controls | D13, §10.7, §10.8, §6.1 |
| 4 | Readiness key `vertical-rightsizing`, not ready by default | §6.7, R1 |
| 5 | Demo fixtures for the engine's GETs; mutations behind `DEMO` | §16 |
| 6 | Ownership boundary; self-contained helpers; reconciliation points | §6.6, §19.1 |
