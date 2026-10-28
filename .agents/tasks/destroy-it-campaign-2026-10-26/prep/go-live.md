# Destroy-It Campaign — Go-Live posture (FULL LIVE) on xamops-test-1

Workflow (resume): wf_11056589d901fb99  (supersedes prior wf_d2ab28b44080e183)
Date (UTC): 2026-10-28
Target cluster: `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` ONLY

> STATUS: **GO-LIVE DRIVEN — 5 of the 8 named controllers are LIVE; 3 cannot reach LIVE on the shipped
> agent image through any legitimate surface (retired/analysis-only/no-gate by design). PAUSED for an
> owner decision on those 3 per the step's explicit "don't bypass the API / don't hand-edit Postgres" rule.**
> The prior PAUSE (consolidation write-route + spot-interruption guard) is RESOLVED: both product changes
> shipped at origin/main HEAD `eb629c7cb` and the owner directed "allow all automations by default."

---

## RESOLVED decisions (previously paused, now shipped)

- **Consolidation write-route** — shipped. `PUT /api/eks/autoscaling/{clusterName}/consolidation`
  (`ConsolidationPolicyDto`) exists at `eb629c7cb`, gated by `normalizer.validate(Section.CONSOLIDATION, …)`
  → refuses `enabled=true` unless `consolidation-actions` readiness is ready. Persisted via
  `EksAutoscalingService.saveConsolidationPolicy` → `saveSection(Section.CONSOLIDATION, …)`, and
  `replaceSection` persists the consolidation section (eb629c7cb fix).
- **spot-interruption-actions in UAT** — shipped at `91d505a7c`. `application-uat.properties` sets
  `xamops.k8s.automation.readiness.spot-interruption-actions=true` (UAT ONLY; base default stays false,
  prod/preprod/local still forbidden — `K8sAutomationReadinessPropertiesTest` updated accordingly).

## Context / account assertions (re-verified this run, before every action)

- `aws sts get-caller-identity --region ap-south-1` → Account **102227938259** (user `iam.autospotting`). PASS.
- kube context PINNED on every call: `--context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1`
  (resolves to that ARN). Shared current-context had DRIFTED to
  `iam.autospotting@k8opt-chaos.ap-south-1.eksctl.io` — NOT used, never changed.
- `GET /api/xamops/k8s/clusters?accountIds=102227938259` → `xamops-test-1` ACTIVE + connected (v1.36).
- Blast radius enforced = xamops-test-1 only. xamops-test-2 / k8opt-chaos / AutoSpotting / control-plane / VPC / IAM untouched.

## CI status — eb629c7cb

- CI run **40637895309** (push, headSha `eb629c7cb`, "persist consolidation section in replaceSection")
  → **completed / success**. `[xamops-service] Build & Test`, `Containers Test`, `Build & Push Docker Image`,
  `Deploy to UAT` all success.
- `[xamops-autoscaler] Build & Push Docker Image` was **skipped** at eb629c7cb (and at 91d505a7c) — correct:
  the changed-service detector saw no Go changes. `git diff b8827ad62..eb629c7cb -- xamops-autoscaler/` is EMPTY.
  All three campaign commits (b8827ad62, 91d505a7c, eb629c7cb) touch only backend Java + UAT properties + a
  manifest — **not the agent binary.** The last run that actually built+pushed the autoscaler image is
  **40501618881 (b8827ad62)**, conclusion success, autoscaler build+verify success.

## Agent image rolled

- The agent binary at `eb629c7cb` **is identical** to the binary at `b8827ad62` (no Go diff). So the
  "eb629c7cb agent digest" is the b8827ad62 build; there is no newer digest to roll to.
- Live Deployment `xamops-system/xamops-automation` = **2/2**, both pods on
  `ghcr.io/xamops/xamops-autoscaler@sha256:5897349fae3d6607c2e019098b736bc0ec44f22887afb29f72f44b1dcfe3897d`
  (the b8827ad62 = eb629c7cb-equivalent build). **Already current — rolling would be a no-op; not rolled.**

## Agent ConfigMap `xamops-automation-config` — verified full-live (not re-applied)

`DRY_RUN=false`; `ENABLE_CONSOLIDATION/EVICTOR/HPA_MANAGER/NODE_OPTIMIZER/REBALANCER/RIGHTSIZER/`
`RIGHTSIZER_APPLY/VPA_MANAGER=true`; `ENABLE_LEADER_ELECTION=true`. Matches target; no drift.
Leader = pod `xamops-automation-6478774c5b-q8kjl`.

## UAT readiness keys — verified present/true at eb629c7cb (application-uat.properties ONLY)

`vertical-rightsizing=true`, `hpa-apply=true`, `vpa-apply=true`, `evictor-live=true`, `rebalancer=true`,
`node-optimizer-actions=true`, `spot-interruption-actions=true`, `consolidation-actions=true`. All 8 present/true.

## Per-cluster policy PUTs executed this run (all HTTP 200)

- `PUT …/enabled {"enabled":true}` → "Node automation turned on"
- `PUT …/mode {"mode":"full"}` → "scope set to full"
- `PUT …/evictor-policy {analyseOnly:false,…}` → accepted (readiness allowed live)
- `PUT …/rebalancer {scheduleEnabled:true,…}` → accepted
- `PUT …/node-optimizer {autoCordon/autoDrain/handleSpotInterruptions:true,…}` → accepted
- `PUT …/consolidation {enabled:true,…}` → accepted (the NEW route; readiness allowed live)
- `PUT …/workload-rightsizer {enabled:true,applyMode:immediate}` → **403 K8S_CONTROLLER_NOT_READY**
  ("the automation agent's rightsizer is retired. Workload rightsizing is configured in Workload autoscaling.")
- No `…/hpa-manager` or `…/vpa-manager` PUT route exists (400 clusterName invalid = not a mapped path).

---

## GATE-LOG PROOF (authoritative 8-controller verdict)

Source 1 — leader pod gate line (`2026/10/28 05:52:18`, pod clock; after the PUTs):

```
Gates — rebalancer=live | evictor=live | nodeopt=analyse cordon=live idleDrain=live
spotRatio=off karpenter=off interruption=live | rightsizer=off (workloadRightsizerPolicy.enabled=false)
apply=off (workloadRightsizerPolicy.enabled=false) | hpa=off (hpaManagerPolicy.enabled=false)
| consolidation=analyse (look-only) actions=live (all gates open (DRY_RUN=false))
```

Source 2 — `xamops_gate_decision` metric (scraped :9090/metrics via port-forward):

```
xamops_gate_decision{controller="consolidation-actions",mode="live"} 1
xamops_gate_decision{controller="interruption",mode="live"} 1
xamops_gate_decision{controller="evictor",mode="live"} 1
xamops_gate_decision{controller="rebalancer",mode="live"} 1
xamops_gate_decision{controller="cordon",mode="live"} 1
xamops_gate_decision{controller="idle-drain",mode="live"} 1
xamops_gate_decision{controller="node-optimizer",mode="analyse"} 1
xamops_gate_decision{controller="consolidation",mode="analyse"} 1   (look-only phase; never acts)
xamops_gate_decision{controller="rightsizer",mode="off"} 1
xamops_gate_decision{controller="rightsizer-apply",mode="off"} 1
xamops_gate_decision{controller="hpa-manager",mode="off"} 1
xamops_gate_decision{controller="spot-ratio",mode="off"} 1
xamops_gate_decision{controller="karpenter",mode="off"} 1
(no vpa controller series exists in this agent image)
```

### Verdict vs the 8 named controllers

| # | Named controller            | Gate verdict                    | LIVE? |
|---|-----------------------------|---------------------------------|-------|
| 1 | consolidation-actions       | `consolidation-actions=live`    | ✅ LIVE |
| 2 | interruption (spot)         | `interruption=live`             | ✅ LIVE |
| 3 | evictor                     | `evictor=live`                  | ✅ LIVE |
| 4 | rebalancer                  | `rebalancer=live`               | ✅ LIVE |
| 5 | node-optimizer              | parent `analyse`; `cordon=live`+`idle-drain=live` | ⚠️ ACTIONS LIVE (parent gate is analyse-labelled; its live sub-gates act) |
| 6 | rightsizer                  | `rightsizer=off` — **retired**  | ❌ NOT live (by design) |
| 7 | rightsizer-apply            | `rightsizer-apply=off` — retired| ❌ NOT live (by design) |
| 8 | hpa                         | `hpa-manager=off` — analysis-only ("never Live") | ❌ NOT live (by design) |
| + | vpa                         | **no acting gate in this image**| ❌ no gate exists |

RBAC note: the earlier `persistentvolumes is forbidden` errors (05:46–05:49) are RESOLVED —
`kubectl auth can-i list persistentvolumes --as=…xamops-automation` = **yes**, 0 PV-forbidden errors in the
last 2m. Evictor/rebalancer/consolidation can act.

### BLOCKER (why PAUSED) — 3-4 controllers cannot reach LIVE legitimately on this image

- **rightsizer / rightsizer-apply**: the agent's rightsizer is RETIRED. The backend's
  `WORKLOAD_RIGHTSIZER` validate path throws `CONTROLLER_NOT_READY (RETIRED_RIGHTSIZER_MESSAGE)` and
  refuses `workloadRightsizerPolicy.enabled=true` (403). gate.go `RightsizerAnalysis`/`RightsizerApply`
  therefore stay `off`. Real vertical rightsizing lives in a different subsystem ("Workload autoscaling").
- **hpa-manager**: gate.go comment + behavior — "the HPA manager is analysis-only: it is never Live."
  No per-cluster HPA PUT route exists to set `hpaManagerPolicy.enabled=true`, and even enabled it caps at
  `analyse`.
- **vpa-manager**: there is NO VPA acting gate in this agent image (no `xamops_gate_decision{controller="vpa…"}`
  series; no VPA gate func in gate.go). `vpa-apply` readiness is true but nothing consumes it to a live gate here.

Forcing any of these to "live" would require hand-editing stored Postgres policy JSON or bypassing the
retired-rightsizer API refusal — both explicitly forbidden by the step ("do NOT bypass the API or hand-edit
Postgres"). So the run is PAUSED for an owner decision (send_message 'warning').

---

## RBAC prerequisite for the acting phases — DONE (owner Option A)

BUG-RBAC-001 (HIGH) landed. The shipped agent ClusterRole was missing TWO reads the running code
lists cluster-scoped:
- `persistentvolumes` (evictor fit-snapshot + consolidation collect), and
- `clusters.postgresql.cnpg.io` (CNPG Postgres primary/replica/standby DB-awareness).

Fixed in repo `xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml` (added both read rules,
get/list/watch only) AND applied live to xamops-test-1 (RBAC-only apply, context+account asserted).
Commit `cb3c7dbbd`. Verified after fix: 0 `forbidden` lines in a full poll cycle; consolidation
collect completes (`look-only: 0 empty, 0 drain, 5 blocked, 4 kept of 9 node(s)`); all 5 acting gates
still live. `auth can-i list persistentvolumes`=yes, `auth can-i list clusters.postgresql.cnpg.io`=yes.
Full repro / file:lines in `../bugs.md`. Follow-up noted there: the upgrade path should re-apply the
ClusterRole with every image roll so an image-only upgrade can't leave RBAC behind.

Honest headline for the feature table (NOT 8/8): **5 acting controllers LIVE** (consolidation-actions,
interruption/spot, evictor, rebalancer, node-optimizer cordon+idle-drain); **rightsizer + rightsizer-apply**
= off (agent rightsizer RETIRED; real apply is the Java vertical engine); **hpa** = off (analysis-only by
design, never Live); **vpa** = off (no acting gate / writer on this build). Each recorded in bugs.md
(BUG-GATE-002/003/004).

## Dashboard URLs for the NEXT step to fill

- Grafana: currently `kube-prometheus-stack-grafana` in ns `break-observability` is **ClusterIP only**
  (no public LoadBalancer address yet). When the observability/chaos setup step exposes it via a public
  LoadBalancer, record here: **Grafana URL + admin-password secret location** (secret
  `kube-prometheus-stack-grafana` key `admin-password` in ns `break-observability`, verify at fill time).
- Chaos Mesh: **not yet installed** (no `chaos-*` namespace present). The setup step installs it and
  records the **Chaos Mesh dashboard URL** here.
- Baseline Grafana dashboard JSON captured at `prep/baseline-dashboard.json`.

## Cookie

- Read BY PATH from `/Users/apple/.config/xamops/uat-session` (chmod 600), copied to a private chmod-600 temp
  `/tmp/uat-sess.*`, used via explicit `Cookie: SESSION=…` header (curl `-b <file>` mis-parsed the raw header).
  Contents never printed/logged. Temp deleted by the final restore step.
- Verified: read-only GETs 200 (accounts, clusters); all live PUTs 200.

## Cluster lock

- `/Users/apple/Desktop/XamOps-New/.agents/tasks/xamops-test-1.lock` TAKEN OVER by this run
  (wf_11056589d901fb99). Released only by the final restore step.
