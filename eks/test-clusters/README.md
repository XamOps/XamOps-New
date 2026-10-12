# CloudK8s feature-testing clusters (ephemeral, agent-provisioned)

Two real EKS clusters in **ap-south-1**, account `102227938259`, built to exercise
the CloudK8s features (rightsizing, idle detection, throttling, pending pods,
spot/on-demand mix, version drift). Everything here is billable and **ephemeral** —
run `./teardown.sh` when done.

## Clusters

| Cluster | K8s | Capacity | Instance types | min/desired/max |
|---|---|---|---|---|
| `xamops-test-1` | 1.36 | ON_DEMAND (`spot: false`) | `t3.medium` | 1 / 4 / 4 |
| `xamops-test-2` | 1.35 | SPOT (`spot: true`) | `t3.medium`, `t3a.medium`, `t2.medium` | 1 / 2 / 4 |

`xamops-test-1` needs 4 nodes, not 2: the seeded mix requests ~2880m CPU (plus up to
1000m more when the `team-b` HPA rides to `maxReplicas: 5`) against `1930m` allocatable per
`t3.medium`. On 2 nodes the scheduler sat at 97% CPU requested and left real workloads
Pending, which masks the one pod that is *supposed* to be Pending. See the comment on
`desiredCapacity` in `xamops-test-1.yaml`. `xamops-test-2` carries observability only, so
2 nodes is enough there.

Version choice: `aws eks describe-cluster-versions` reports **1.36 as the EKS
default** (1.37 is newest, 1.33 and below are already in extended support).
`xamops-test-1` sits on the default 1.36; `xamops-test-2` sits one minor behind on
1.35 (still STANDARD_SUPPORT, EOSS 2027-03) so fleet version-drift and
upgrade/EOL reporting have something real to find.

Both clusters reuse the **existing default VPC** `vpc-0622ed442aa8c1f3c` and its
three public subnets (`subnet-0361a77391315b8cc` / `subnet-02c92250ae6941e59` /
`subnet-0f039ed6a736d0e0a`). No new VPC, no NAT gateway — public nodes only, which
keeps the bill to the control planes plus four small nodes.

Everything is tagged `Project=XamOps`, `Purpose=CloudK8sFeatureTesting`,
`Owner=agent-provisioned`, `CreatedOn=2026-10-09`, `Ephemeral=true` on the
cluster, the node group and (via `propagateASGTags`) the nodes.

> The older `eks/cluster-config.yaml` in the parent directory is stale (dead VPC,
> K8s 1.29, `t2.small`). It was left untouched; use the files here instead.

## Order of operations

```bash
cd eks/test-clusters

# 1. clusters (~15 min each; they can run in parallel in separate shells)
eksctl create cluster -f xamops-test-1.yaml
eksctl create cluster -f xamops-test-2.yaml

# 2. metrics-server + CloudWatch Container Insights (enhanced observability)
#    on both clusters, incl. the IRSA role for the cloudwatch-agent SA
./install-observability.sh

# 3. workload mix on xamops-test-1 only
aws eks update-kubeconfig --region ap-south-1 --name xamops-test-1
kubectl apply -f 00-namespaces.yaml
kubectl apply -f 10-overprovisioned.yaml -f 11-rightsized.yaml \
               -f 12-underprovisioned.yaml -f 13-idle.yaml \
               -f 14-no-requests.yaml -f 15-hpa-load.yaml \
               -f 16-pdb-workload.yaml -f 17-unschedulable.yaml

# 4. when finished
./teardown.sh
```

## Workload mix (xamops-test-1)

| File | Namespace | Scenario | What it should trigger |
|---|---|---|---|
| `00-namespaces.yaml` | — | `team-a`, `team-b`, `prod-sim`, `dev-sim` | per-namespace cost/usage grouping |
| `10-overprovisioned.yaml` | team-a | 1 CPU / 1Gi requested, near-idle process | large rightsizing reduction |
| `11-rightsized.yaml` | prod-sim | duty-cycled loop vs 80m / 64Mi | control case: no change recommended |
| `12-underprovisioned.yaml` | prod-sim | full-tilt loop behind 10m req / 50m limit | CPU-throttling detection |
| `13-idle.yaml` | dev-sim | 3 replicas sleeping, no Service | idle / zombie workload detection |
| `14-no-requests.yaml` | dev-sim | no requests or limits at all | governance / missing-requests policy |
| `15-hpa-load.yaml` | team-b | php-apache + Service + HPA (1→5 @ 50% CPU) + in-cluster load generator | real non-zero metrics, HPA scaling, scale-up events |
| `16-pdb-workload.yaml` | team-b | 3 nginx replicas with `minAvailable: 3` | consolidation / drain blocked by PDB |
| `17-unschedulable.yaml` | team-a | requests 32 CPU / 64Gi | Pending pod, `FailedScheduling`, scale-up recommendation |

`15-hpa-load.yaml` is adapted from `../stress-test.yaml` (same php-apache +
HPA + Service), moved into `team-b` and given a load generator so no manual
`kubectl run` is needed and metrics are never flat zeros.

## Observability

`install-observability.sh` installs, on **both** clusters:

- EKS managed add-on `metrics-server` (`v0.9.0-eksbuild.11`) — required by the HPA
  and `kubectl top`. No IAM needed.
- EKS managed add-on `amazon-cloudwatch-observability` (`v6.7.0-eksbuild.1`) —
  Container Insights with enhanced observability. Requires IRSA: the script creates
  role `<cluster>-cw-observability` (`--role-only`, bound to
  `amazon-cloudwatch/cloudwatch-agent`) with `CloudWatchAgentServerPolicy` and
  `AWSXRayDaemonWriteAccess`, then passes it as `--service-account-role-arn`.

The script is idempotent and skips clusters that are not yet `ACTIVE`.

## Teardown

`./teardown.sh` (`DRY_RUN=1 ./teardown.sh` to preview, or pass a single cluster
name) removes, per cluster: `type=LoadBalancer` Services and PVCs first — so the
in-cluster controllers delete their ELBs and EBS volumes — then node groups and
control plane via eksctl, the IRSA role, the OIDC provider, leftover
`eksctl-<cluster>-*` CloudFormation stacks, orphaned ALB/NLB/classic LBs and
target groups, detached EBS volumes, leftover security groups (rules revoked
first) and the cluster + Container Insights CloudWatch log groups.

Every resource is tag-checked for `Project=XamOps` (or a
`kubernetes.io/cluster/<cluster>` tag) before deletion; anything else is reported
under `SKIPPED` and never touched. Re-running is safe — missing resources count as
already deleted, and CloudFormation deletes are async, so a second pass is a
reasonable way to confirm the account is clean.

## Verified state (2026-10-09)

Both clusters were built, seeded and verified end to end. Real captured command output
lives in `.agents/tasks/cloudk8s-test-clusters-2026-10-09/verification.md`:

- both clusters `ACTIVE` (1.36 / 1.35), node groups `ACTIVE`, all nodes `Ready`
- `xamops-test-2` reports a genuine `capacityType: SPOT` — spot capacity was available,
  no fallback to on-demand
- `amazon-cloudwatch-observability` `v6.7.0-eksbuild.1` and `metrics-server`
  `v0.9.0-eksbuild.11` both `ACTIVE` on both clusters, IRSA bound
- `ContainerInsights` carries 3991 metric series across 87 metric names, with real
  datapoints — `throttled-encoder` sits at 100.0% of its CPU limit,
  `overprovisioned-api` at 0.002% of its request
- the `team-b` HPA scaled 1 → 5 on real load; `pdb-guarded-web` reports
  `ALLOWED DISRUPTIONS: 0`; exactly one pod (`unschedulable-giant`) is Pending with
  `FailedScheduling`

Agent and autoscaler deployability is assessed in the sibling `agent-assessment.md`.
Neither was deployed — both compile and their images run in-cluster, but the manifests
ship placeholder values and the agent's only data sink is the production backend.

## Notes

- Local `kubectl` is v1.34.1, two minors behind the 1.36 server. It works, but it
  is outside the supported ±1 skew; `brew upgrade kubectl` before heavy use.
- Spot on `xamops-test-2` is a genuine managed-node-group `capacityType: SPOT`
  (eksctl `spot: true`), not just a tag — confirmed via `aws eks describe-nodegroup`
  on the live cluster, not just `--dry-run`.
- Context names are the full cluster ARNs, e.g.
  `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1`. eksctl also leaves
  `iam.autospotting@xamops-test-1.ap-south-1.eksctl.io` aliases pointing at the same
  clusters.
