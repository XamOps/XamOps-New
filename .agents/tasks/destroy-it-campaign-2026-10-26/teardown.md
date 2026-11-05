# Destroy-It Campaign — Teardown Step: ABORTED (plan changed) — Current-State Inventory

Workflow: `wf_199ed20b57d3620b`
Cluster (pinned on EVERY call): `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` (ap-south-1, acct 102227938259)
Caller asserted before any action: `aws sts get-caller-identity` = account **102227938259** (`arn:aws:iam::102227938259:user/iam.autospotting`).

## OUTCOME: NOTHING WAS DELETED — teardown aborted on owner's change-of-plan

This step began as a cleanup/teardown run. **Mid-run the owner issued a CHANGE OF PLAN:** a UI-driven
destructive test campaign will REUSE this environment, so the reusable pieces must stay up. The teardown was
stopped **before any destructive action was taken.**

**Confirmed: ZERO destructive operations were issued in this step.** Every command run was read-only
inventory / verification (`describe`, `get`, `list`, `sts get-caller-identity`). No `delete`, `uninstall`,
`patch`, `scale`, `delete-nodegroup`, or `delete-volume` was ever executed. Therefore **every
campaign-created resource survives, intact and running.** There is nothing for the next workflow to rebuild.

## Guardrails honored
- Every kubectl/helm/aws call pinned to `--context arn:aws:eks:...:cluster/xamops-test-1` / `--region ap-south-1`.
  The ambient current-context had DRIFTED to `iam.autospotting@k8opt-chaos.ap-south-1.eksctl.io` — it was
  **never used and never changed**. The pinned endpoint was cross-checked against the EKS API
  (`describe-cluster` endpoint `0A9C0230...` == kubeconfig server) to prove the pinned context is truly
  xamops-test-1, not test-2.
- Never touched xamops-test-2, AutoSpotting, /k8opt, k8opt-chaos, the control plane, the VPC, or IAM.
  (Note: two CLASSIC ELBs exist in the account — `aef0a305...` svc `monitoring/kps-grafana` and
  `ad45139364...` svc `chaos-mesh/chaos-dashboard` — both tagged `kubernetes.io/cluster/k8opt-chaos owned`,
  i.e. they belong to the **k8opt-chaos** cluster, NOT xamops-test-1. Left untouched per guardrail.)
- ng-ondemand-1, the xamops-system automation agent + RBAC, the data agent, kube-system, and the UAT
  readiness keys / backend mode were not changed.
- No git push.

---

## CURRENT STATE (authoritative, pinned context) — everything KEPT

### Namespaces — ALL PRESENT (kept)
| Namespace | Status | Age | Campaign-created? | Disposition |
|---|---|---|---|---|
| `chaos-mesh` | Active | 23h | yes (Chaos Mesh v2.8.7) | **KEPT** |
| `stress` | Active | 23h | yes (145-deploy fleet + fleet-http svc + PDBs) | **KEPT** |
| `stress-db` | Active | 23h | yes (redis-0 + PVC) | **KEPT** |
| `break-observability` | Active | 3d11h | yes (kube-prometheus-stack) | **KEPT** |
| `xamops-db-fixtures` | Active | 3d18h | yes (Postgres CNPG / MySQL / Mongo) | **KEPT** |
| `cnpg-system` | Active | 3d18h | yes (CNPG operator) | **KEPT** |
| `monitoring` | Active | 19d | NO — pre-existing (older than campaign) | untouched |

### Helm releases (pinned context)
| Release | Namespace | Chart | Campaign-created? | Disposition |
|---|---|---|---|---|
| `chaos-mesh` | chaos-mesh | chaos-mesh-2.8.7 | yes | **KEPT** |
| `kube-prometheus-stack` | break-observability | kube-prometheus-stack-92.13.1 | yes | **KEPT** |
| `aws-node-termination-handler` | kube-system | nth-0.27.6 | pre-existing (NTH) | untouched |
| `prometheus` | monitoring | prometheus-29.36.1 | NO (19d) | untouched |
| `vpa` | vpa | vpa-5.1.0 | NO (19d) | untouched |

### Observability stack — KEPT (running, still internet-exposed by design per new plan)
- Grafana svc `kube-prometheus-stack-grafana` (ns `break-observability`) = **LoadBalancer**, still fronted by
  the PUBLIC AWS **NLB** `a9e0a9a7b25c54ddb8627736899e760d-072c2f2422090f4c.elb.ap-south-1.amazonaws.com`
  (`aws elbv2 describe-load-balancers` → Name `a9e0a9a7b25c54ddb8627736899e760d`, Type `network`, State
  `active`). **KEPT.**
- `grafana-admin` Secret (ns `break-observability`, 2 keys) — **KEPT** (NOT deleted).
- Prometheus / kube-state-metrics / node-exporter / operator / grafana-image-renderer — all **KEPT**.

### Nodegroups (authoritative `aws eks describe-nodegroup`) — ALL KEPT
| Nodegroup | status | min/desired/max | capacity | Disposition |
|---|---|---|---|---|
| ng-ondemand-1 | ACTIVE | 1 / 4 / 4 | ON_DEMAND | KEPT (baseline — was never a delete target) |
| ng-spot-1 | ACTIVE | 0 / 1 / 2 | SPOT | **KEPT** (was pending-decision; owner says keep) |
| ng-stateful-1 | ACTIVE | 3 / 3 / 6 | ON_DEMAND | **KEPT** |
| ng-observability-1 | ACTIVE | 1 / 1 / 1 | ON_DEMAND | **KEPT** |

Nodes visible via pinned context: ng-observability-1 ×1, ng-ondemand-1 ×4, ng-spot-1 ×1, ng-stateful-1 ×3.

### PVCs + backing EBS volumes — ALL KEPT (none deleted; volumes still present)
| Namespace | PVC | Size | SC | PV | EBS volume | Disposition |
|---|---|---|---|---|---|---|
| break-observability | prometheus-...-prometheus-0 | 20Gi | gp3-fixtures | pvc-580bf3b1 | vol-09bef92e4f8f54680 | KEPT |
| stress-db | redis-data | 2Gi | gp3-fixtures | pvc-8cf664d0 | vol-0f732dc4e0e833a2d | KEPT |
| xamops-db-fixtures | data-mongo-0 | 1Gi | gp3-fixtures | pvc-56c90301 | vol-0b7ef26650f198235 | KEPT |
| xamops-db-fixtures | data-mongo-1 | 1Gi | gp3-fixtures | pvc-889e0ccf | vol-04c8529f461469638 | KEPT |
| xamops-db-fixtures | data-mongo-2 | 1Gi | gp3-fixtures | pvc-684dbb8d | vol-065ea324351b3d0e3 | KEPT |
| xamops-db-fixtures | data-mysql-0 | 1Gi | gp3-fixtures | pvc-681467e1 | vol-05e1399ee70a9353b | KEPT |
| xamops-db-fixtures | pg-cnpg-1 | 1Gi | gp3-fixtures | pvc-aa5b66c4 | vol-0a21454e87bd8798e | KEPT |
| xamops-db-fixtures | pg-cnpg-2 | 1Gi | gp3-fixtures | pvc-7fdd8069 | vol-081e8503268dbd1b4 | KEPT |

### StorageClass
- `gp3-fixtures` (ebs.csi.aws.com, Delete, WaitForFirstConsumer, allowVolumeExpansion) — **KEPT** (still in use
  by all the PVCs above).

### Already-gone from earlier campaign steps (verified this step, NOT acted on now)
- FIS experiment templates: `aws fis list-experiment-templates` → **empty** (none).
- FIS scoped IAM roles `destroy-it-fis-spot-role` + `destroy-it-p23-fis-spot`: `aws iam get-role` →
  **NoSuchEntity** (both already deleted by the campaign; verified, nothing to do).

---

## Reconciliation vs resources.md / report.md
Every campaign-created resource listed in the consolidated teardown inventory (report.md STEP 4.8 items 7-13
and resources.md) is **present and KEPT**: Grafana public NLB, kube-prometheus-stack (+ Prometheus 20Gi PVC),
Chaos Mesh, the `stress` fleet, `stress-db` redis + PVC, `xamops-db-fixtures` DBs + 6 PVCs, `cnpg-system`,
`gp3-fixtures` SC, ng-observability-1, ng-stateful-1, ng-spot-1/NTH, ng-ondemand-1. **Nothing
campaign-created was removed in this step.** The environment is fully intact for the UI-driven destructive
re-test.

## FINAL monthly cost delta removed
**$0.00 / month removed** — no resource was deleted, so no cost was reclaimed. The campaign's running cost
footprint (observability NLB + stack, chaos-mesh, fleet, DB fixtures, gp3 PVCs/EBS, ng-observability-1 +
ng-stateful-1 + ng-spot-1, ng-ondemand-1 at 1/4/4) remains in place by owner decision for the reuse campaign.

_Teardown step aborted on owner change-of-plan; current-state inventory captured for the next workflow._
