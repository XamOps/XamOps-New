# Two live EKS test clusters for CloudK8s feature verification

`xamops-test-1` (on-demand, K8s 1.36) and `xamops-test-2` (spot, K8s 1.35) were stood up in
`ap-south-1` on the default VPC, both carrying `metrics-server` and the
`amazon-cloudwatch-observability` add-on, with the deliberately-imperfect workload mix seeded on
cluster 1. The evidence files are verbatim command output rather than exit-code summaries, and
every success criterion is covered by output I could reconcile against the manifests. I
independently re-checked the two claims a reviewer has least reason to take on trust — that both
clusters exist and that cluster 2's node group is genuinely `SPOT` — and both hold, with no
on-demand fallback. The two real deviations (cluster 1 running 4 nodes instead of 2, and neither
`xamops-agent` nor `xamops-autoscaler` being deployed) are disclosed in the evidence with the
scheduler output and the exact `kubectl apply` error that motivated them, not worked around
silently.

**Watch for:** teardown's OIDC-provider cleanup can never fire in the real path — it reads the
issuer from `describe-cluster` *after* `eksctl delete cluster` has removed the cluster (confirmed),
and `DRY_RUN=1` takes a different branch so a dry run would print a reassuring "deleting OIDC
provider" line it will not reproduce for real (confirmed). Single-cluster teardown sweeps every
`Project=XamOps` load balancer, detached volume and security group in the region, not just the
named cluster's (confirmed). `teardown.sh` has never been executed, not even under `DRY_RUN=1`
(confirmed by its absence from the evidence). None of these invalidate the provisioning result.

**Verdict**: APPROVED

## High-level view

The clusters are real and the capacity split is real. `aws eks list-clusters` returns both names
and `describe-nodegroup` on `ng-spot-1` returns `capacityType: SPOT`, `status: ACTIVE` with the
diversified `t3.medium` / `t3a.medium` / `t2.medium` pool — so the single criterion most likely to
have quietly degraded into an on-demand fallback did not.

The one provisioning deviation is cluster 1 at 4 nodes rather than the 2 originally configured.
It is disclosed with the scheduler evidence that forced it (97% CPU requested per node, real
workloads Pending that were never meant to be), and the fix moved node count rather than shrinking
the 1-CPU request that *is* the rightsizing test case. `xamops-test-1.yaml` now declares
`desiredCapacity: 4` with that reasoning inline, so the config reproduces the verified state —
but `verification.md` §2 still says the file declares 2, which is now stale.

Workload evidence reconciles manifest-by-manifest. Every replica count in the live pod listing
matches its Deployment spec, the HPA is riding `maxReplicas: 5` at 62%/50% on genuine load, the
PDB reports `ALLOWED DISRUPTIONS: 0`, and exactly one pod is Pending with five captured
`FailedScheduling` events against a 32-CPU / 64Gi request. Container Insights evidence goes past
metric names into `get-metric-statistics` datapoints, including a load-bearing contrast pair —
`throttled-encoder` pinned at 100.0% of its CPU limit against `overprovisioned-api` at 0.002% of
its request.

`teardown.sh` meets what was asked of it: executable, idempotent, and fail-closed — every
ownership probe that errors out yields an empty tag string and skips rather than deletes. The
defects are in completeness and scope, not in the guard. The OIDC branch is dead in the success
path and cannot be recovered by re-running, because the second pass returns early at "cluster not
found". The LB, EBS and security-group sweeps match on `Project=XamOps` with no cluster scoping,
so `./teardown.sh xamops-test-2` reaches cluster 1's resources. The tag check is a substring
comparison, so a hypothetical `Project=XamOpsProd` resource would match. Log groups are the one
deletion with no tag check at all, which contradicts the "every resource is tag-checked" claim in
the script header and README. The script always exits 0, so a caller cannot tell a clean pass from
a partial one.

The agent assessment is the strongest artifact in the set. It states plainly that nothing was
deployed, gives three blockers with the real `kubectl apply --dry-run=server` rejection text,
distinguishes them from dry-run `namespace not found` noise, and then surfaces the actual gate —
the agent's only data sink is the production backend — as a decision with three costed options
instead of pointing two deliberately-broken test clusters at production and calling it a pass.

Two posture items the evidence does not mention: both clusters expose a public EKS API endpoint
with no `publicAccessCIDRs` allow-list and run nodes with public IPs, and both enable five
control-plane log types whose CloudWatch ingest is outside the README's "control planes plus four
small nodes" cost claim.

<details>
<summary>Issues (13)</summary>

1. **OIDC cleanup unreachable** — `delete_cluster` reads `cluster.identity.oidc.issuer` from
   `describe-cluster` after `eksctl delete cluster --wait` has already removed the cluster, so the
   branch never fires and re-running returns early at "cluster not found". Move the issuer lookup
   above the `eksctl delete` call and cache it.
2. **DRY_RUN masks the OIDC bug** — under `DRY_RUN=1` the cluster still exists, so that branch
   prints "deleting OIDC provider …" and gives assurance the real path will not honour. Fixing
   item 1 removes the divergence.
3. **Single-cluster teardown has region-wide scope** — the LB, detached-EBS and security-group
   sweeps accept any `Project=XamOps` resource regardless of which cluster is named, so
   `./teardown.sh xamops-test-2` can delete cluster 1's resources. Require the
   `kubernetes.io/cluster/<cluster>` tag in those three sweeps.
4. **Tag guard is a substring match** — `[[ "$tags" == *"Project=XamOps"* ]]` also matches
   `Project=XamOpsProd` or any longer value. Compare the extracted value exactly.
5. **Log-group deletion is unguarded** — `delete_log_groups` deletes by name prefix with no tag
   check, contradicting the "every resource is tag-checked" claim in the script header and README.
   Add the check or amend both claims.
6. **SC2318 wrong-target risk in `delete_log_groups`** — `local cluster="$1" lg=".../${cluster}/..."`
   expands `${cluster}` before the local assignment takes effect; it currently resolves to the
   global loop variable of the same value, masking the bug. Split into two `local` statements.
7. **Teardown always exits 0** — no `set -e` and no exit-code accumulation, and the "Teardown pass
   finished" line prints regardless of failed steps or `SKIPPED` entries. Track a failure flag and
   exit non-zero.
8. **Teardown never executed** — no `DRY_RUN=1` pass appears in the evidence. I syntax-checked it
   under bash 3.2 and 5.x instead; run `DRY_RUN=1 ./teardown.sh` before the real teardown.
9. **`DRY_RUN=1` writes to kubeconfig** — `aws eks update-kubeconfig` in
   `drain_k8s_cloud_resources` is not wrapped in `run`, so a preview switches the local context
   despite the "change nothing" header. Wrap it or guard the whole function.
10. **Stale note in verification.md** — §2 says `xamops-test-1.yaml` "still declares
    `desiredSize: 2`"; the file now declares `desiredCapacity: 4`. Update the note.
11. **Unbudgeted audit-log ingest** — both clusters enable `api`/`audit`/`authenticator`/
    `controllerManager`/`scheduler` logging at 7-day retention; the README cost claim covers only
    control planes and nodes. Note it, or drop `audit` while the clusters idle.
12. **Public API endpoint and public node IPs** — `clusterEndpoints.publicAccess: true` with no
    `publicAccessCIDRs` and `privateNetworking: false` on both clusters. Acceptable for ephemeral
    clusters, but restrict the CIDR list or keep the lifetime short.
13. **Live shared credential restated** — `agent-assessment.md` quotes the shared agent API key
    verbatim. It was already committed in the manifest, so there is no new exposure, but reference
    it by name; per-cluster token rotation belongs in its own task.

</details>

<details>
<summary>Details</summary>

## Spot capacity is genuine, confirmed against the live account

The spot criterion is the one with a plausible silent-degradation path — `eksctl` falls back to
on-demand when a pool is short — so I re-ran it rather than trust the transcript:

```
$ aws eks list-clusters --region ap-south-1
{ "clusters": [ "xamops-test-1", "xamops-test-2" ] }

$ aws eks describe-nodegroup --region ap-south-1 --cluster-name xamops-test-2 \
    --nodegroup-name ng-spot-1
{ "status": "ACTIVE", "capacityType": "SPOT",
  "instanceTypes": ["t3.medium","t3a.medium","t2.medium"], "version": "1.35",
  "scaling": { "minSize": 1, "maxSize": 4, "desiredSize": 2 } }
```

That matches `verification.md` §2 field for field, including the `desiredSize: 2` that the config
declares. The same spot-check confirmed `xamops-test-1-cw-observability` carries
`Project=XamOps`, which is what teardown's `role_is_ours` guard requires — so the IRSA roles will
be deleted rather than reported as `SKIPPED`.

## The 2 → 4 node deviation

The deviation most likely to have been hidden was not. The evidence carries the `describe nodes`
output at the original size (`cpu 1890m (97%)`), the `FailedScheduling` event on a `php-apache`
replica that was never meant to be Pending, and the `update-nodegroup-config` call that moved
`desiredSize` to the already-declared `maxSize: 4` — no limit raised, no request shrunk, so
`10-overprovisioned.yaml`'s 1-CPU rightsizing case survives intact. `verification.md` §2 predates
the config edit and still tells the next reader the file declares 2; they would scale up a cluster
already at 4.

## Workload mix reconciles against the manifests

Each seeded Deployment's replica count matches the live pod listing exactly: `overprovisioned-api`
2, `rightsized-worker` 2, `throttled-encoder` 2, `idle-batch-listener` 3,
`no-requests-sidecar-app` 2, `pdb-guarded-web` 3, `load-generator` 1, `php-apache` 5 (HPA, from
`replicas: 1`), `unschedulable-giant` 1 Pending, across the four namespaces in
`00-namespaces.yaml`. All nine manifests are accounted for.

The Pending pod is the criterion with a specific shape, and it is met: `requests {cpu: 32, memory:
64Gi}` read back off the live object, five `FailedScheduling` events captured verbatim including
the intermediate `0/3 nodes … untolerated taint(s)` entries from the scale-up, and the standing
`0/4 nodes are available: 4 Insufficient cpu, 4 Insufficient memory`. With no autoscaler installed
nothing will try to satisfy it, so it stays Pending as intended.

`pdb-guarded-web` sets `minAvailable: 3` on 3 replicas, which reports `ALLOWED DISRUPTIONS: 0` —
genuinely blocking a naive drain. Worth noting that `teardown.sh` already passes
`--disable-nodegroup-eviction --force` to `eksctl delete cluster`, so this PDB will not wedge its
own teardown.

One cosmetic mismatch: `no-requests-sidecar-app` declares a single container, so the "sidecar" in
the name describes a scenario the manifest does not actually contain.

## Container Insights evidence goes past metric names

`list-metrics` returning 3991 series across 87 names proves the namespace is populated, not that
values are flowing. `node_cpu_utilization` returns three consecutive 5-minute datapoints per
cluster (14.8% avg on cluster 1, 2.5% on cluster 2, tracking the `kubectl top nodes` output), and
the two scenario metrics land where the seeded workloads should put them:

```
pod_cpu_utilization_over_pod_limit  prod-sim/throttled-encoder   ~100.00%
pod_cpu_utilization                 team-a/overprovisioned-api   ~0.002%
```

`throttled-encoder` requests 10m with a 50m limit behind an unbounded busy loop, and
`overprovisioned-api` requests a full CPU to run `sleep 1` — the manifests predict exactly this
spread, so throttling detection and rightsizing both have unambiguous input.

Two readings that could have passed as clean are instead flagged with their explanation: the `0Mi`
memory on the busybox pods (sub-megabyte rounding, cross-checked against non-zero
`pod_memory_working_set`) and `storedBytes: 0` on the Container Insights log groups.

## teardown.sh: the guard holds, the cleanup does not finish

Ownership probes are fail-closed throughout. `cluster_is_ours`, `stack_is_ours`, `role_is_ours`
and the two ELB helpers all pipe a failed AWS call into an empty string, which fails the tag
comparison and routes the resource to `skip` — an API error cannot be mistaken for ownership.

The OIDC step, though, cannot work:

```bash
run eksctl delete cluster -f "$cfg" --region "$REGION" \
  --disable-nodegroup-eviction --force --wait
...
oidc_id=$(aws eks describe-cluster --region "$REGION" --name "$cluster" \
  --query 'cluster.identity.oidc.issuer' --output text 2>/dev/null | sed 's|https://||')
if [[ -n "${oidc_id:-}" && "$oidc_id" != "None" ]]; then
  ...
else
  info "no OIDC issuer recorded (cluster already gone)"
fi
```

After a successful `--wait` delete, `describe-cluster` fails, `oidc_id` is empty, and the function
prints a line that reads like a benign no-op. A second pass cannot repair it either —
`delete_cluster` returns at the top with "cluster not found — already deleted" before reaching
this block. Both OIDC providers survive a teardown that reports no problem. Hoisting the lookup
above the `eksctl delete` fixes it.

The `DRY_RUN` asymmetry is what makes this worth fixing rather than noting: in a preview the
cluster still exists, so this branch resolves the issuer and prints `deleting OIDC provider
arn:aws:iam::…` — the dry run advertises behaviour the real run will not perform.

Separately, `aws eks update-kubeconfig` inside `drain_k8s_cloud_resources` is called directly
rather than through `run`, so `DRY_RUN=1` still rewrites the local kubeconfig and switches the
current context, against the header's "change nothing".

## Teardown scope is wider than its argument

`./teardown.sh xamops-test-2` is an advertised mode, but three sweeps ignore which cluster was
named. `has_required_tag_list` returns success on its first condition:

```bash
[[ "$tags" == *"${REQUIRED_TAG_KEY}=${REQUIRED_TAG_VALUE}"* ]] && return 0
[[ -n "$cluster" && "$tags" == *"kubernetes.io/cluster/${cluster}="* ]] && return 0
```

The LB sweep enumerates every load balancer in the region, the EBS sweep every
`available`/`creating`/`error` volume, and the SG sweep adds a region-wide
`tag:Project=XamOps` filter — then each asks only "is this tagged `Project=XamOps`?". Both test
clusters carry that tag, so a single-cluster teardown reaches the other one's resources.

Today the blast radius is bounded: the account inventory captured at `2026-10-09T08:03Z` shows the
only `Project=XamOps` resources in the account are these clusters' 8 CloudFormation stacks, 2 ASGs
and 8 IAM roles, and the concurrent autospotting E2E resources in the same region are tagged
`xamops-autospot-e2e=true` instead. The exposure is to future `Project=XamOps` infrastructure —
including the self-hosted stack in `eks/manifests/` that `agent-assessment.md` proposes deploying
to `xamops-test-2`.

The first condition is also a substring test, so `Project=XamOpsProd` or `Project=XamOpsStaging`
would satisfy it. The cluster-tag condition is anchored by its trailing `=` and does not have that
problem.

`delete_log_groups` is the only deletion with no tag check; it is name-prefix scoped to the
cluster, which keeps it tight, but the header and README both claim every resource is tag-checked.
That function also carries the one latent wrong-target bug, which shellcheck flags as SC2318:

```bash
local cluster="$1" lg="/aws/eks/${cluster}/cluster"
```

`${cluster}` is expanded before the `local` assignment lands, so it picks up the global loop
variable — which currently holds the same value, masking the defect. Called with a `$1` that
differs from the enclosing loop variable, the first half of the function would delete one
cluster's `/aws/eks/<name>/cluster` group while the second half correctly targets the other's
Container Insights groups.

## What the evidence does not cover

`teardown.sh` has never run, in any mode — a destructive script of this size whose first execution
would be the real one, when a `DRY_RUN=1` pass costs nothing. The criterion itself is about the
script's properties (executable, idempotent, refuses untagged resources), and those I could
establish by reading, so this is a finding rather than a rejection. In place of a run I parsed it
under bash 3.2.57 (the macOS `/bin/bash` its `SKIPPED` string handling targets) and the 5.x on
`PATH` — clean under both, and `install-observability.sh` too — so no syntax or version-compat
error is waiting at teardown time. The dry run is still owed, with the caveat above that it will
not exercise the OIDC path the real run takes.

`xamops-test-2` carries observability only. Documented as design, and it does deliver the
version-drift and spot-inventory cases, but the spot cluster has no evictable pods, so
spot-interruption handling, consolidation and PDB-aware draining have nothing to act on there — on
the one cluster that is actually spot. A couple of small replicas would close that.

Neither `xamops-agent` nor `xamops-autoscaler` was deployed, so no end-to-end agent → backend →
UI path has been exercised.

## Agent assessment is honest about the gate it hit

The three blockers are concrete and reproducible: `:latest` resolving to a v1.0.0 binary (proven by
running both tags and diffing the startup banner), placeholder `CLUSTER_NAME` / `CLOUD_ACCOUNT_ID`
that pass the non-empty check and would write mislabelled inventory rather than refuse, and an
autoscaler Secret using `data:` with non-base64 literals that the API server rejects outright. The
`namespaces "xamops-system" not found` lines from the same dry-run are correctly identified as a
server-side dry-run artifact instead of being counted as a fourth blocker.

The part that matters for this gate is the stop. The agent's only sink is
`https://live.xamops.com/api/v1/agent/ingest`, there is no staging target in its configuration,
and deploying it would publish synthetic broken-by-design inventory into the production tenant
alongside real customer clusters. The assessment surfaces that as a decision with three costed
options and does not pick one — the opposite of a silent workaround.

Two security observations ride along. The autoscaler's `ClusterRole` requests real mutation verbs
(pod `delete`, eviction `create`, Deployment/StatefulSet `patch`, HPA and VPA CRUD) with
`DRY_RUN: "true"` as the only thing between it and live changes, flagged with the right instruction
to verify `DRY_RUN` is honoured on every path before flipping a flag. And the shared agent key
committed in the manifest is confirmed by live `curl` to return the account's OpenObserve
credentials — those values are redacted in the write-up, but the key itself is quoted verbatim.
It was already committed, so this is not new exposure; a document describing the problem still does
not need to reproduce the secret.

## Posture items the evidence skips

Both eksctl configs set `clusterEndpoints.publicAccess: true` with no `publicAccessCIDRs`, and
`privateNetworking: false` puts nodes in public subnets with public IPs (visible in the
`get nodes -o wide` output). The endpoint is IAM-authenticated and `ssh.allow: false` keeps SSH
closed, so this is ordinary public-EKS exposure rather than an open cluster — but it is a new
internet-reachable control-plane endpoint in a live account with no CIDR restriction, and the
README frames the public-subnet choice purely as a NAT-gateway cost saving.

Both configs also enable all five control-plane log types at 7-day retention. `audit` is the chatty
one, and its CloudWatch ingest is outside the README's "control planes plus four small nodes" cost
statement.

</details>

<details>
<summary>File map</summary>

| File | What it is |
|---|---|
| `eks/test-clusters/xamops-test-1.yaml` | eksctl config, on-demand `t3.medium`, K8s 1.36, `desiredCapacity: 4` with the sizing rationale inline |
| `eks/test-clusters/xamops-test-2.yaml` | eksctl config, `spot: true` with three instance types, K8s 1.35 |
| `eks/test-clusters/install-observability.sh` | idempotent `metrics-server` + `amazon-cloudwatch-observability` add-on install, creates the `<cluster>-cw-observability` IRSA role |
| `eks/test-clusters/teardown.sh` | tag-guarded destructive teardown; guard fail-closed, OIDC step unreachable, three sweeps region-scoped |
| `eks/test-clusters/00-namespaces.yaml` | `team-a`, `team-b`, `prod-sim`, `dev-sim` |
| `eks/test-clusters/10-overprovisioned.yaml` | 1 CPU / 1Gi requested, near-idle — rightsizing case |
| `eks/test-clusters/11-rightsized.yaml` | duty-cycled loop at 80m — control case |
| `eks/test-clusters/12-underprovisioned.yaml` | busy loop behind 10m req / 50m limit — throttling case |
| `eks/test-clusters/13-idle.yaml` | 3 sleeping replicas, no Service — idle detection |
| `eks/test-clusters/14-no-requests.yaml` | BestEffort, no resources block — governance case |
| `eks/test-clusters/15-hpa-load.yaml` | php-apache + Service + HPA 1→5 + in-cluster load generator |
| `eks/test-clusters/16-pdb-workload.yaml` | 3 nginx replicas, `minAvailable: 3` — drain-blocked case |
| `eks/test-clusters/17-unschedulable.yaml` | 32 CPU / 64Gi request — permanent Pending case |
| `eks/test-clusters/README.md` | runbook, cluster table, workload table, verified-state summary |
| `.agents/tasks/cloudk8s-test-clusters-2026-10-09/verification.md` | captured provisioning evidence, 8 sections plus a pass/fail table |
| `.agents/tasks/cloudk8s-test-clusters-2026-10-09/agent-assessment.md` | agent/autoscaler deployability, blockers B1-B3, production-backend gate |

</details>
