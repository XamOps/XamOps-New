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

_Last updated: pre-scale, setup step. Actual added node count recorded below after scaling._
