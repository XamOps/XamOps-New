# Destroy-It Campaign — running cost estimate

Workflow: wf_d2ab28b44080e183
Cluster: `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` (ap-south-1 / Mumbai)
HARD COST CAP: **$200 total.** DURATION CAP: **3 days.**

> Estimates only — EC2 on-demand list prices for ap-south-1, rounded. Spot is variable (~60-70% off
> on-demand). This file is updated as the campaign runs; AWS Cost Explorer is the authority at reconcile.

## Baseline cluster cost (posture not yet flipped)

Nodes observed (via `kubectl get nodes`, context-pinned to xamops-test-1):

| Nodegroup | Count | Instance | Capacity | ~On-demand $/hr (ea, ap-south-1) | ~$/hr subtotal |
|---|---|---|---|---|---|
| ng-observability-1 | 1 | t3.large  | on-demand | 0.0832 | 0.0832 |
| ng-ondemand-1      | 4 | t3.medium | on-demand | 0.0416 | 0.1664 |
| ng-stateful-1      | 3 | t3.medium | on-demand | 0.0416 | 0.1248 |
| ng-spot-1          | 1 | t3.medium | spot      | ~0.015 (variable) | ~0.015 |
| **EKS control plane** | 1 | — | — | 0.10 | 0.10 |

- Compute (nodes) ≈ **$0.389/hr** on-demand + ~$0.015/hr spot ≈ **$0.404/hr**.
- + EKS control plane **$0.10/hr**.
- **Baseline total ≈ $0.504/hr ≈ $12.10/day ≈ $36.3 over the 3-day cap window** (compute+control plane only;
  excludes EBS, data transfer, NAT, Cost Explorer API calls).

## Campaign increment (to be tracked once FULL LIVE)

| Item | Est. cost | Notes |
|---|---|---|
| Agent image pull / rollout | ~$0 | GHCR pull, no AWS charge |
| Node churn (drain/terminate/replace by node-optimizer/rebalancer/evictor) | TBD | each replacement = partial-hour on-demand; watch count |
| Spot interruptions / re-launch on ng-spot-1 | TBD | variable |
| EBS churn from rescheduled stateful pods | TBD | gp3 volume create/delete |
| Cost Explorer API ($0.01/call) | low | mock=false in uat |

## Update — FULL LIVE flipped 2026-10-28

- Posture is now FULL LIVE (mode=full, DRY_RUN=false, 6 agent gates live). No node churn has
  completed yet: the evictor/consolidation live paths are currently blocked by an agent-SA RBAC gap
  (cannot list persistentvolumes), so no drains/terminations have executed. One rebalance was
  triggered (status=running) but no node has been removed/replaced as of this update.
- CI image builds / UAT redeploys: GHCR + GitHub Actions, no AWS charge.
- Baseline compute+control-plane still accruing at ≈ $0.50/hr. Elapsed campaign wall-clock so far
  (2026-10-27 flip-prep → 2026-10-28 go-live) is well under the 3-day cap.

**Running total so far: ≈ $12–18 (baseline cluster only; ≈ $0.50/hr × ~24–36h of campaign window).
Campaign-attributable increment ≈ $0 (no churn executed yet).**
Budget remaining against $200 cap: **≈ $182–188.**

_Last updated: 2026-10-28, immediately after FULL LIVE flip (pre-attack-phase)._

## Resume update — wf_11056589d901fb99 (go-live finish)

- Re-verified posture and re-drove the per-cluster PUTs (enabled, mode=full, evictor live, rebalancer,
  node-optimizer incl. handleSpotInterruptions, consolidation enabled). All accepted (HTTP 200).
- The earlier agent-SA PV RBAC gap is **RESOLVED**: `auth can-i list persistentvolumes …xamops-automation`
  = yes, 0 PV-forbidden errors in the last 2m; evictor/rebalancer/consolidation can now act.
- Gate verdict (agent log + `xamops_gate_decision`): LIVE = consolidation-actions, interruption, evictor,
  rebalancer, node-optimizer cordon/idle-drain. NOT live (by design, not legitimately forceable on this
  agent image) = rightsizer, rightsizer-apply (retired), hpa (analysis-only), vpa (no gate). PAUSED for owner.
- No chaos/load applied in this step → **campaign-attributable increment still ≈ $0**; baseline only.
  Budget remaining ≈ $182–188 against the $200 cap.

## Resume update #2 — owner Option A + RBAC prerequisite landed (wf_11056589d901fb99)

- Owner chose Option A (proceed with the 5 acting controllers; do NOT un-retire rightsizer / add
  HPA-VPA writers / hand-edit Postgres).
- Landed BUG-RBAC-001 (HIGH) before the acting phases: the shipped agent ClusterRole was missing
  `persistentvolumes` AND `clusters.postgresql.cnpg.io` reads. Fixed in repo manifest (commit cb3c7dbbd)
  and applied live (RBAC-only `kubectl apply`, context+account asserted — no workload/node change).
  Verified 0 forbidden errors, collect completes, 5 gates still live.
- Cost impact of the RBAC fix = **$0** (RBAC object only; no node churn, no API billing). No chaos/load
  run in this step. Baseline still ~$0.50/hr; campaign-attributable increment ≈ $0. Budget remaining
  ≈ $182–188 against the $200 cap. The attack phases (observability/chaos/load/fleet) run in the
  subsequent step and will update this file as churn accrues.

## Setup-stage update — observability + chaos + fleet + dashboard step (wf_d2ab28b44080e183)

Context re-asserted before any write: account **102227938259**, region **ap-south-1**, context pinned
`arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` (current-context had drifted to k8opt-chaos —
NOT used). All writes `--context`-pinned.

### Reused (no new cost)
- **Observability**: reused the existing `kube-prometheus-stack` in ns `break-observability`
  (Grafana emptyDir, Prometheus 15d on gp3 `gp3-fixtures`) + the `ng-observability-1` t3.large node.
  No second stack installed.
- **DB fixtures**: reused `xamops-db-fixtures` (Postgres CNPG, MySQL, Mongo) — already running.

### New cloud resources created this step (for teardown)
| Resource | Type | Est. cost | Notes |
|---|---|---|---|
| Grafana public LB | AWS NLB/CLB (1) | ~$0.025/hr (~$0.60/day) + LCU | from switching `kube-prometheus-stack-grafana` svc to `LoadBalancer` |
| ng-ondemand-1 scale-up | EC2 t3.medium ×N added | $0.0416/hr each | to clear the t3.medium 17-pod VPC-CNI ceiling for the ~200-pod fleet |
| Chaos Mesh | pods only (no LB/EBS) | ~$0 | ns chaos-mesh, namespace-filtered to stress*/xamops-system |
| k6 Jobs | pods only (ephemeral) | ~$0 | grafana/k6 in-cluster |
| Redis (stress-db) | pod + 1 small gp3 PVC | ~$0.01/hr | DB-case fixture addition |
| Secrets (grafana admin reused) | n/a | $0 | strong random admin password rotated into existing `grafana-admin` secret |

### Projection (attack window assumed ~48h; hard stop at 3-day cap)
- Nodegroup scale-up (worst case 14 added t3.medium to seat 200 pods): 14 × $0.0416 = **$0.582/hr**
  → ~$14 over 24h, ~$28 over 48h.
- Grafana LB: ~$0.60/day → ~$1.2 over 48h.
- Baseline (existing nodes + control plane): ≈ $0.50/hr → ~$24 over 48h.
- **Projected total through a 48h attack window ≈ $12–18 (spent) + ~$28 (scale) + ~$1 (LB) + ~$24 (baseline)
  ≈ $65–71.** Well under the **$200** cap (remaining headroom ≈ $130+).

### ACTUALS (post-scale)

- **ng-ondemand-1 scaled 4 → 26** (22 added t3.medium on-demand) to clear the t3.medium 17-pod
  VPC-CNI ceiling (prefix-delegation is OFF, confirmed). Added compute = 22 × $0.0416 = **$0.915/hr**.
  Needed because the fleet is pod-count-bound (17/node) AND CPU-bound (over-provisioned profiles),
  not because of raw resource need.
- Grafana public NLB: ~$0.025/hr + LCU ≈ **$0.60–0.80/day**.
- Redis EBS (2Gi gp3) ≈ negligible (~$0.0003/hr).
- Baseline (control plane + pre-existing nodes) still ≈ $0.50/hr.

**New running compute rate ≈ $0.50 (baseline) + $0.915 (scale) ≈ $1.42/hr + ~$0.03/hr LB.**

Projection over a 48h attack window from here:
- Scale-up: $0.915/hr × 48h ≈ **$44**
- Baseline: $0.50/hr × 48h ≈ **$24**
- Grafana LB: ≈ **$1.5**
- Already spent to date: ≈ **$12–18**
- **Projected campaign total ≈ $82–88 through a 48h window.** Under the **$200** cap;
  remaining headroom ≈ **$110+**. NO cap breach → no warning raised.

> Cost control lever for teardown / if budget tightens: scale ng-ondemand-1 back to 4
> (`eksctl scale nodegroup ... --nodes 4`) drops ~$0.915/hr immediately.

_Last updated: setup step complete (actual 22 nodes added), 2026-10-28._

## Setup FINISH — Option B take-over (wf_11056589d901fb99, setup step)

Prior run (wf_d2ab28b44080e183) stood down; this run owns the cluster+lock and reconciled to the
half-built state (did NOT double-build). Context re-asserted before every write: account **102227938259**,
region **ap-south-1**, context pinned to the xamops-test-1 ARN (current-context drift to k8opt-chaos NOT used).

### Actual live node count (the governing cost driver)
- `kubectl get nodes` = **31 nodes**: **30 × t3.medium** + **1 × t3.large** (ng-observability-1).
- `ng-ondemand-1` had drifted UP on its own (18 → 26 desired; max had been bumped to 28) while pods
  scheduled. **Capped it: minSize=4, maxSize=26, desiredSize=26** (EKS update id `07520bdd-5a1c-…`,
  applied — live scalingConfig now max=26) so the group cannot keep climbing. No eviction (desired
  left at current count). Other nodegroups unchanged: ng-observability-1 1/1, ng-spot-1 1 (max2),
  ng-stateful-1 3 (max6).

### Actual hourly burn (on-demand list, ap-south-1)
| Item | Rate | Subtotal |
|---|---|---|
| 30 × t3.medium on-demand | $0.0416/hr ea | $1.248/hr |
| 1 × t3.large (observability) | $0.0832/hr | $0.083/hr |
| ~1 × t3.medium spot (ng-spot-1) | ~$0.015/hr | ~$0.015/hr |
| EKS control plane | $0.10/hr | $0.10/hr |
| Grafana public NLB | ~$0.025/hr + LCU | ~$0.025/hr |
| Redis gp3 PVC + misc EBS | ~$0.01/hr | ~$0.01/hr |
| **Total** | | **≈ $1.48/hr ≈ $35.5/day** |

### Projection vs the $200 cap
- Already spent to date (baseline window): ≈ **$12–18**.
- 48h attack window at $1.48/hr ≈ **$71**; full 3-day-cap worst case ≈ 72h × $1.48 ≈ **$107**.
- **Projected total: ≈ $18 + $71 ≈ $89 (48h) ; ≈ $18 + $107 ≈ $125 (full 3-day cap).**
  **Both well under the $200 cap** (headroom ≈ $75–110). Trajectory does NOT exceed $200 → no STOP/warn.

### What was created/finished this step (for teardown)
- **Grafana public LB**: REUSED (already live from helm rev4) — `kube-prometheus-stack-grafana`
  LoadBalancer (NLB) in ns `break-observability`. Not reinstalled.
- **Chaos Mesh** (ns `chaos-mesh`, helm rev2, v2.8.7): controller-manager (1 replica), chaos-daemon
  DaemonSet (containerd runtime), dashboard, dns-server. Pods only — no LB/EBS. `enableFilterNamespace=true`;
  injection gated by ANNOTATION `chaos-mesh.org/inject=enabled` on stress/stress-db/xamops-system ONLY
  (never kube-system). ServiceMonitor scrapes the `http` (10080) port → Prometheus target health=up.
- **k6 Job** `stress/k6-fleet-load` (grafana/k6, remote-writes to kube-prometheus-stack). Ephemeral, ~$0.
- **Dashboard** ConfigMap `break-observability/destroy-it-dashboard` (sidecar-provisioned). $0.
- **Fleet reconciled**: scaled the 200-replica fleet DOWN to **145 schedulable workloads** (1 replica each,
  all 6 profiles over/over-heavy/under/mid/burst/right ~24-25 each, 17 PDBs) — **~200 was INFEASIBLE on
  t3.medium within the VPC-CNI 17-pod/node IP ceiling without further node cost**. Redis-0 now Running in
  stress-db. DB fixtures (CNPG Postgres, MySQL, Mongo) reused. Total cluster pods ≈ 448.

### STOP/warn check
- Trajectory ≤ ~$125 through the 3-day cap < $200 → **no stop required.** Will keep updating as attack
  churn accrues. Restore step must return ng-ondemand-1 to baseline **1/4/4** (min/desired/max per
  baseline table: desired 4) and chaos/stress/k6/LB removed.

_Last updated: setup finish (Option B), after capping ng-ondemand-1 max=26._

## Attack-phase update #1 (wf_11056589d901fb99) — Phases 1-4 complete

Context re-asserted before every action (acct 102227938259, region ap-south-1, pinned xamops-test-1
ARN; ambient drift to k8opt-chaos ignored, never used). Faults confined to ns stress/stress-db.

- **Node count UNCHANGED** through phases 1-4: 31 nodes (30× t3.medium + 1× t3.large). ng-ondemand-1
  stayed at/under its cap (26) — Phase 2 deliberately hit the scheduling ceiling (23 Pending) rather
  than growing nodes. **No node churn, no new EC2, no FIS fired yet.**
- Loads were ephemeral in-cluster k6 Jobs (grafana/k6) — pod-only, ~$0 AWS charge.
- Chaos was pods-only StressChaos (mem/cpu) via existing chaos-mesh — ~$0.
- Cleaned up 3 orphaned chaos objects from a prior run (incl. an ACTIVE pod-kill) — no cost effect.

**Burn rate unchanged ≈ $1.48/hr (baseline+scale-holdover+LB).** Campaign-attributable increment for
phases 1-4 ≈ **$0** (no churn). Spent-to-date estimate ≈ **$20-26** (baseline cluster wall-clock).
Projected total through the 3-day cap still ≈ **$125** < $200 cap. **No cap breach → no warn.**
Phases 5-8 (self-heal storm, leader-failover, FIS spot churn, flapping) may add node churn — tracked next.

_Last updated: after Phase 4 (CPU/CFS), wf_11056589d901fb99._

## Attack-phase update #2 (wf_11056589d901fb99) — Phases 5-8 + FIS complete

- **Phase 7 fired REAL AWS FIS** `send-spot-instance-interruptions` on EXACTLY `i-0025a665eb405673e`
  (xamops-test-1 ng-spot-1, resolved by BOTH tags; test-2 never touched). That spot instance was
  terminated and the ASG launched 1 replacement spot node. Spot ≈ $0.015/hr → partial-hour churn
  ≈ **<$0.05 total**. A scoped FIS IAM role was created and **deleted** in teardown ($0).
- Phases 5/6/8 = pod-only chaos (StressChaos/PodChaos/Schedule) + ephemeral k6 Jobs → **~$0 AWS**.
- **Node count unchanged** (31) apart from the single spot terminate+replace; ng-ondemand-1 held at 26.
- Added a second Grafana dashboard (ConfigMap only) → $0.

**Campaign-attributable increment across all 8 phases ≈ <$0.10 (spot churn only).**
Dominant cost remains the baseline+scale-holdover burn ≈ $1.48/hr. Spent-to-date estimate (wall-clock)
≈ **$24-32**; projected through the 3-day cap ≈ **$125** < **$200 cap**. **No cap breach → no warn.**

> Teardown lever unchanged: scale ng-ondemand-1 back to 4 (−$0.915/hr) + remove Grafana LB/chaos/k6/redis.

_Last updated: after Phase 8 (flapping) + FIS teardown, wf_11056589d901fb99._

## Attack-phase update #3 (wf_11056589d901fb99) — Phases 9-12 complete

Context re-asserted before every mutation (acct 102227938259, region ap-south-1, pinned xamops-test-1 ARN;
ambient drift to k8opt-chaos ignored). Faults confined to ns stress / stress-db / xamops-system.

- **Phase 9 cost side-finding:** `ng-ondemand-1` scalingConfig had **drifted up to max=40 / desired=34**
  (from the setup cap of 26) — but only **30 nodes actually run** (no cluster-autoscaler/Karpenter pod
  exists; no runaway launch). **Re-capped** to `min4 / desired30 / max30` (EKS update `bf0c06eb-764c-…`,
  context+acct asserted) to freeze at the real count and block any climb toward 40. This did NOT evict
  (30=30). The +4 nodes over the 26 baseline = +4 × $0.0416 ≈ **+$0.17/hr** holdover (drift, not this
  phase's doing). RESTORE step must still return ng-ondemand-1 to baseline 1/4/4.
- **Phases 10-12:** pod-only / API-only faults — 8+12 ephemeral API-storm Jobs (phase 10), 18 backend
  policy PUTs + 1 short-lived adversarial pod (phase 11), a 1.9GB fill-and-delete on an existing gp3
  volume (phase 12). **No node churn, no new EC2, no FIS.** Campaign-attributable increment ≈ **$0**.

- **Node count:** 35 total (30× t3.medium ng-ondemand + 1× t3.large + 1 spot + 3 stateful). Burn rate
  now ≈ $1.48/hr baseline+holdover + ~$0.17/hr for the 4 drifted nodes ≈ **$1.65/hr**.
- **Spent-to-date estimate (wall-clock)** ≈ **$30–40**; projected through the 3-day cap ≈ **$130** < **$200
  cap**. **No cap breach → no warn.** Headroom ≈ $70+.

_Last updated: after Phase 12 (PVC exhaustion), wf_11056589d901fb99._

## Attack-phase update #4 (wf_11056589d901fb99) — Phases 13-18 complete (campaign 9-18 DONE)

Context re-asserted before every mutation (acct 102227938259, region ap-south-1, pinned xamops-test-1 ARN).

- **Phases 13-18 were all pod-only / API-only / node-label faults:** chaos-mesh NetworkChaos+Schedule+
  PodChaos (phase 13, pods only), backend API reads+triggers (phases 14/16/18), repeated leader-pod
  deletes (phases 16/17/18 — reschedules on existing nodes), and node annotation edits on already-cordoned
  EMPTY nodes (phases 17/18). **No node churn, no new EC2, no FIS, no eviction.** Campaign-attributable
  increment for phases 13-18 ≈ **$0**.
- **Node count unchanged:** 35 total (30× t3.medium ng-ondemand + 1 t3.large + 1 spot + 3 stateful).
  ng-ondemand-1 held at the Phase-9 re-cap (min4/desired30/max30). Burn ≈ **$1.65/hr** (baseline + the
  4 drifted nodes + LB).
- **Spent-to-date estimate (wall-clock)** ≈ **$36-46**; projected through the 3-day cap ≈ **$130-140** <
  **$200 cap**. **No cap breach → no warn.** Headroom ≈ $60+.

> Teardown lever unchanged: the RESTORE step returns ng-ondemand-1 to baseline 1/4/4 (−$1.08/hr vs 30
> nodes) and removes chaos/stress/k6/Grafana-LB/redis.

_Last updated: after Phase 18 (stale-state/time/identity) — phases 9-18 ALL COMPLETE, wf_11056589d901fb99._

## Phase 2 RE-RUN — node growth AUTHORIZED (owner lifted no-growth, within $200 cap)

**RESTORE BASELINE (MANDATORY final target, capture before any scale):**
ng-ondemand-1 ORIGINAL baseline = **min 1 / desired 4 / max 4**. Final restore MUST return it to 1/4/4
regardless of how high it goes during the run. (Current pre-rerun state was already min4/desired26/max26
from setup.) Other nodegroups unchanged and NOT grown for this: ng-stateful-1 3/3/6 (DB pool — stays 3),
ng-spot-1 0/1/2, ng-observability-1 1/1/1.

**Why the ceiling (confirmed):** `ENABLE_PREFIX_DELEGATION=false` → t3.medium hard-capped at 17 pods/node
(VPC-CNI). 6 nodes already sit at 17/17. Phase-2 breaking point was BOTH this IP ceiling AND CPU-request
exhaustion ("22 Insufficient cpu"). A straight node-count bump clears both; prefix-delegation or a larger
instance (e.g. t3.xlarge = 58 pods/node) would seat ~200 on far fewer nodes but is a bigger change — noted,
not required.

**Scale plan:** raise ng-ondemand-1 desired 26→34, max 26→40 (headroom). t3.medium on-demand $0.0416/hr.
- At desired=34: ng-ondemand compute ≈ $1.41/hr; full-cluster burn ≈ **$1.63/hr**.
- Scale/soak window kept SHORT (seat 200 + verify SLO, ~2-4h, not a 48h hold).
- Spent-to-date ≈ $30. Even a 48h hold at $1.63/hr ≈ +$78 → **~$108 total < $200 cap.** A 4h window ≈ +$7.
- **Projected total ≈ $37-108 depending on hold < $200 → NO cap breach → proceeding without warn.**

_Recorded immediately BEFORE scaling ng-ondemand-1 for the Phase-2 re-run._
