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
