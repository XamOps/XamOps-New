# CloudK8s test clusters — verification evidence

Date: 2026-10-09 · Region `ap-south-1` · Account `102227938259` (`arn:aws:iam::102227938259:user/iam.autospotting`)

Every block below is real captured output, not a summary of exit codes. Timestamps in
`get-metric-statistics` output are rendered in the local profile zone (IST, +05:30); the
`--start-time` / `--end-time` window is UTC.

## Kubeconfig contexts

```
$ aws eks update-kubeconfig --region ap-south-1 --name xamops-test-1
Updated context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 in /Users/apple/.kube/config
$ aws eks update-kubeconfig --region ap-south-1 --name xamops-test-2
Updated context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2 in /Users/apple/.kube/config
```

Both context names (used verbatim in every `kubectl --context` below):

| Cluster | Context name |
|---|---|
| `xamops-test-1` | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` |
| `xamops-test-2` | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2` |

`eksctl` also left its own aliases (`iam.autospotting@xamops-test-1.ap-south-1.eksctl.io`
and `…test-2…`) pointing at the same clusters. The ARN contexts are the ones used here.

## 1. Clusters exist and are ACTIVE

```
$ aws eks list-clusters --region ap-south-1
{
    "clusters": [
        "xamops-test-1",
        "xamops-test-2"
    ]
}
```

```
$ aws eks describe-cluster --region ap-south-1 --name xamops-test-1 --query 'cluster.{...}'
{
    "name": "xamops-test-1",
    "status": "ACTIVE",
    "version": "1.36",
    "platformVersion": "eks.14",
    "createdAt": "2026-10-09T12:10:19.025000+05:30",
    "oidcIssuer": "https://oidc.eks.ap-south-1.amazonaws.com/id/0A9C0230D0879EDBB699666A46D461BF"
}

$ aws eks describe-cluster --region ap-south-1 --name xamops-test-2 --query 'cluster.{...}'
{
    "name": "xamops-test-2",
    "status": "ACTIVE",
    "version": "1.35",
    "platformVersion": "eks.24",
    "createdAt": "2026-10-09T12:11:19.197000+05:30",
    "oidcIssuer": "https://oidc.eks.ap-south-1.amazonaws.com/id/E72B17E7A1F041C4781F250EE1E046C4"
}
```

Version drift between the two clusters (1.36 vs 1.35) is intentional — it gives fleet
version-drift / upgrade reporting something real to find.

## 2. Node groups ACTIVE, capacity type as designed

```
$ aws eks list-nodegroups --region ap-south-1 --cluster-name xamops-test-1
{ "nodegroups": [ "ng-ondemand-1" ] }

$ aws eks describe-nodegroup --region ap-south-1 --cluster-name xamops-test-1 \
    --nodegroup-name ng-ondemand-1 --query 'nodegroup.{...}'
{
    "nodegroupName": "ng-ondemand-1",
    "status": "ACTIVE",
    "capacityType": "ON_DEMAND",
    "instanceTypes": [ "t3.medium" ],
    "amiType": "AL2023_x86_64_STANDARD",
    "version": "1.36",
    "scalingConfig": { "minSize": 1, "maxSize": 4, "desiredSize": 4 }
}
```

```
$ aws eks list-nodegroups --region ap-south-1 --cluster-name xamops-test-2
{ "nodegroups": [ "ng-spot-1" ] }

$ aws eks describe-nodegroup --region ap-south-1 --cluster-name xamops-test-2 \
    --nodegroup-name ng-spot-1 --query 'nodegroup.{...}'
{
    "nodegroupName": "ng-spot-1",
    "status": "ACTIVE",
    "capacityType": "SPOT",
    "instanceTypes": [ "t3.medium", "t3a.medium", "t2.medium" ],
    "amiType": "AL2023_x86_64_STANDARD",
    "version": "1.35",
    "scalingConfig": { "minSize": 1, "maxSize": 4, "desiredSize": 2 }
}
```

**Cluster 2 reports `capacityType: SPOT`.** Spot capacity was available — there was no
fallback to on-demand. Both requested spot instances launched and went `Ready`.

### Deviation from the provisioned config: cluster 1 scaled 2 → 4 nodes

`xamops-test-1` was created with `desiredSize: 2`. The seeded workload mix does not fit
in 2 × `t3.medium`: a `t3.medium` reports `1930m` allocatable CPU, and after system
DaemonSets (`aws-node`, `kube-proxy`, `cloudwatch-agent`, `fluent-bit`) plus the seeded
requests, the scheduler hit 97% CPU requested per node and left real workloads Pending
that were never meant to be Pending:

```
$ kubectl --context …xamops-test-1 describe nodes | grep -A 8 "Allocated resources"   # at desiredSize=2
Allocated resources:
  Resource           Requests      Limits
  cpu                1890m (97%)   2400m (124%)
  memory             1645Mi (49%)  2782Mi (84%)

$ kubectl --context …xamops-test-1 describe pod -n team-b php-apache-7cd5b774b6-4s8nf
  Warning  FailedScheduling  82s  default-scheduler  0/2 nodes are available: 2 Insufficient cpu. …
```

`10-overprovisioned.yaml` requesting `1` CPU × 2 replicas is the *point* of the
rightsizing test case, so shrinking it would have destroyed the scenario. Instead the
node group was scaled to its already-declared `maxSize: 4` — no config change, just a
scale operation:

```
$ aws eks update-nodegroup-config --region ap-south-1 --cluster-name xamops-test-1 \
    --nodegroup-name ng-ondemand-1 --scaling-config minSize=1,maxSize=4,desiredSize=4
{ "update": { "id": "6abf48b5-6a05-3364-a126-d02383bc64b1", "status": "InProgress", "type": "ConfigUpdate" } }
```

After this, only the deliberately unschedulable pod remains Pending (section 5).
`eks/test-clusters/xamops-test-1.yaml` still declares `desiredSize: 2`; re-creating the
cluster from that file needs the same scale-up, or `desiredSize: 4`, to seat the full mix.

## 3. Nodes Ready on both clusters

```
$ kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 get nodes -o wide
NAME                                           STATUS   ROLES    AGE   VERSION               INTERNAL-IP     EXTERNAL-IP      OS-IMAGE                        KERNEL-VERSION                            CONTAINER-RUNTIME
ip-172-31-18-172.ap-south-1.compute.internal   Ready    <none>   19m   v1.36.4-eks-3b4a6ca   172.31.18.172   3.110.152.237    Amazon Linux 2023.12.20260928   6.18.51-120.162.amzn2023.x86_64 (amd64)   containerd://2.2.7+unknown
ip-172-31-26-143.ap-south-1.compute.internal   Ready    <none>   19m   v1.36.4-eks-3b4a6ca   172.31.26.143   3.109.156.19     Amazon Linux 2023.12.20260928   6.18.51-120.162.amzn2023.x86_64 (amd64)   containerd://2.2.7+unknown
ip-172-31-43-198.ap-south-1.compute.internal   Ready    <none>   54m   v1.36.4-eks-3b4a6ca   172.31.43.198   13.201.26.219    Amazon Linux 2023.12.20260928   6.18.51-120.162.amzn2023.x86_64 (amd64)   containerd://2.2.7+unknown
ip-172-31-9-125.ap-south-1.compute.internal    Ready    <none>   54m   v1.36.4-eks-3b4a6ca   172.31.9.125    13.201.225.238   Amazon Linux 2023.12.20260928   6.18.51-120.162.amzn2023.x86_64 (amd64)   containerd://2.2.7+unknown

$ kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2 get nodes -o wide
NAME                                           STATUS   ROLES    AGE   VERSION               INTERNAL-IP     EXTERNAL-IP     OS-IMAGE                        KERNEL-VERSION                     CONTAINER-RUNTIME
ip-172-31-33-222.ap-south-1.compute.internal   Ready    <none>   52m   v1.35.8-eks-3b4a6ca   172.31.33.222   13.233.86.151   Amazon Linux 2023.12.20260928   6.12.110-135.201.amzn2023.x86_64   containerd://2.2.7+unknown
ip-172-31-5-198.ap-south-1.compute.internal    Ready    <none>   52m   v1.35.8-eks-3b4a6ca   172.31.5.198    13.235.77.181   Amazon Linux 2023.12.20260928   6.12.110-135.201.amzn2023.x86_64   containerd://2.2.7+unknown
```

4 Ready on cluster 1, 2 Ready on cluster 2. Nodes have public IPs (default VPC, public
subnets, no NAT gateway) as designed.

## 4. Observability add-ons ACTIVE on both clusters

`eks/test-clusters/install-observability.sh` ran to completion (exit 0):

```
==> Cluster xamops-test-1 (ap-south-1)
    metrics-server add-on already present.
==>     creating IRSA role xamops-test-1-cw-observability
…  deploying stack "eksctl-xamops-test-1-addon-iamserviceaccount-amazon-cloudwatch-cloudwatch-agent"
==>     installing amazon-cloudwatch-observability add-on
==> Cluster xamops-test-2 (ap-south-1)
    metrics-server add-on already present.
==>     creating IRSA role xamops-test-2-cw-observability
…  deploying stack "eksctl-xamops-test-2-addon-iamserviceaccount-amazon-cloudwatch-cloudwatch-agent"
==>     installing amazon-cloudwatch-observability add-on
==> Waiting for add-ons to become ACTIVE
    xamops-test-1/metrics-server: ACTIVE
    xamops-test-1/amazon-cloudwatch-observability: ACTIVE
    xamops-test-2/metrics-server: ACTIVE
    xamops-test-2/amazon-cloudwatch-observability: ACTIVE
```

The preferred path (`amazon-cloudwatch-observability` EKS managed add-on, Container
Insights with enhanced observability) worked on both K8s versions — no fallback to a
manual CloudWatch Agent + Fluent Bit install was needed.

```
$ aws eks list-addons --region ap-south-1 --cluster-name xamops-test-1
{ "addons": [ "amazon-cloudwatch-observability", "coredns", "kube-proxy", "metrics-server", "vpc-cni" ] }
    (identical list for xamops-test-2)

$ aws eks describe-addon --region ap-south-1 --cluster-name xamops-test-1 --addon-name metrics-server
{ "addonName": "metrics-server", "status": "ACTIVE", "addonVersion": "v0.9.0-eksbuild.11", "serviceAccountRoleArn": null }

$ aws eks describe-addon --region ap-south-1 --cluster-name xamops-test-1 \
    --addon-name amazon-cloudwatch-observability
{
    "addonName": "amazon-cloudwatch-observability",
    "status": "ACTIVE",
    "addonVersion": "v6.7.0-eksbuild.1",
    "serviceAccountRoleArn": "arn:aws:iam::102227938259:role/xamops-test-1-cw-observability"
}

$ aws eks describe-addon --region ap-south-1 --cluster-name xamops-test-2 \
    --addon-name amazon-cloudwatch-observability
{
    "addonName": "amazon-cloudwatch-observability",
    "status": "ACTIVE",
    "addonVersion": "v6.7.0-eksbuild.1",
    "serviceAccountRoleArn": "arn:aws:iam::102227938259:role/xamops-test-2-cw-observability"
}
```

Agent pods are up in `amazon-cloudwatch` on both clusters — 4 `cloudwatch-agent` +
4 `fluent-bit` + 1 controller on cluster 1, 2 + 2 + 1 on cluster 2 (one per node).

## 5. Seeded workloads running; exactly one pod intentionally Pending

```
$ kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 get pods --all-namespaces -o wide
NAMESPACE           NAME                                                              READY   STATUS    RESTARTS   AGE   IP              NODE
amazon-cloudwatch   amazon-cloudwatch-observability-controller-manager-67ddff6trmlh   1/1     Running   0          25m   172.31.8.234    ip-172-31-9-125…
amazon-cloudwatch   cloudwatch-agent-2wt4x                                            1/1     Running   0          19m   172.31.18.172   ip-172-31-18-172…
amazon-cloudwatch   cloudwatch-agent-frf6p                                            1/1     Running   0          25m   172.31.9.125    ip-172-31-9-125…
amazon-cloudwatch   cloudwatch-agent-k9tkl                                            1/1     Running   0          25m   172.31.43.198   ip-172-31-43-198…
amazon-cloudwatch   cloudwatch-agent-tg7sh                                            1/1     Running   0          19m   172.31.26.143   ip-172-31-26-143…
amazon-cloudwatch   fluent-bit-62tnn                                                  1/1     Running   0          19m   172.31.26.143   ip-172-31-26-143…
amazon-cloudwatch   fluent-bit-gtb4w                                                  1/1     Running   0          25m   172.31.9.125    ip-172-31-9-125…
amazon-cloudwatch   fluent-bit-k9zn4                                                  1/1     Running   0          19m   172.31.18.172   ip-172-31-18-172…
amazon-cloudwatch   fluent-bit-xt7rw                                                  1/1     Running   0          25m   172.31.43.198   ip-172-31-43-198…
dev-sim             idle-batch-listener-b6d6898cd-hl9qh                               1/1     Running   0          23m   172.31.20.206   ip-172-31-18-172…
dev-sim             idle-batch-listener-b6d6898cd-t4vbd                               1/1     Running   0          23m   172.31.1.79     ip-172-31-9-125…
dev-sim             idle-batch-listener-b6d6898cd-wf97t                               1/1     Running   0          23m   172.31.18.77    ip-172-31-18-172…
dev-sim             no-requests-sidecar-app-55c8f955d9-4wcq6                          1/1     Running   0          23m   172.31.4.165    ip-172-31-9-125…
dev-sim             no-requests-sidecar-app-55c8f955d9-9vvmt                          1/1     Running   0          23m   172.31.36.230   ip-172-31-43-198…
kube-system         aws-node-7jpj4                                                    2/2     Running   0          54m   172.31.43.198   ip-172-31-43-198…
kube-system         aws-node-8r8sd                                                    2/2     Running   0          54m   172.31.9.125    ip-172-31-9-125…
kube-system         aws-node-kghs6                                                    2/2     Running   0          19m   172.31.18.172   ip-172-31-18-172…
kube-system         aws-node-vhzhv                                                    2/2     Running   0          19m   172.31.26.143   ip-172-31-26-143…
kube-system         coredns-67db859b96-62chd                                          1/1     Running   0          58m   172.31.47.31    ip-172-31-43-198…
kube-system         coredns-67db859b96-6tzbx                                          1/1     Running   0          58m   172.31.38.165   ip-172-31-43-198…
kube-system         kube-proxy-6bmfb                                                  1/1     Running   0          19m   172.31.18.172   ip-172-31-18-172…
kube-system         kube-proxy-6mnch                                                  1/1     Running   0          54m   172.31.9.125    ip-172-31-9-125…
kube-system         kube-proxy-87vhn                                                  1/1     Running   0          19m   172.31.26.143   ip-172-31-26-143…
kube-system         kube-proxy-l5h85                                                  1/1     Running   0          54m   172.31.43.198   ip-172-31-43-198…
kube-system         metrics-server-665685856d-nrdz2                                   1/1     Running   0          51m   172.31.0.98     ip-172-31-9-125…
kube-system         metrics-server-665685856d-wq9mz                                   1/1     Running   0          51m   172.31.47.140   ip-172-31-43-198…
prod-sim            rightsized-worker-7cb96c7ddf-bmw56                                1/1     Running   0          23m   172.31.37.218   ip-172-31-43-198…
prod-sim            rightsized-worker-7cb96c7ddf-vjf5s                                1/1     Running   0          23m   172.31.2.143    ip-172-31-9-125…
prod-sim            throttled-encoder-8b999cc87-64nmg                                 1/1     Running   0          23m   172.31.36.74    ip-172-31-43-198…
prod-sim            throttled-encoder-8b999cc87-h66p7                                 1/1     Running   0          23m   172.31.6.4      ip-172-31-9-125…
team-a              overprovisioned-api-76dd9f98c9-thhwl                              1/1     Running   0          23m   172.31.8.53     ip-172-31-9-125…
team-a              overprovisioned-api-76dd9f98c9-wz5tv                              1/1     Running   0          23m   172.31.47.22    ip-172-31-43-198…
team-a              unschedulable-giant-67cc89dbdc-psk4c                              0/1     Pending   0          23m   <none>          <none>
team-b              load-generator-5d6ff98784-j4cbv                                   1/1     Running   0          23m   172.31.36.245   ip-172-31-43-198…
team-b              pdb-guarded-web-867645d8d-gm95f                                   1/1     Running   0          23m   172.31.0.11     ip-172-31-9-125…
team-b              pdb-guarded-web-867645d8d-qvltl                                   1/1     Running   0          23m   172.31.27.220   ip-172-31-18-172…
team-b              pdb-guarded-web-867645d8d-rf4p4                                   1/1     Running   0          23m   172.31.30.20    ip-172-31-18-172…
team-b              php-apache-7cd5b774b6-4s8nf                                       1/1     Running   0          23m   172.31.23.90    ip-172-31-18-172…
team-b              php-apache-7cd5b774b6-5dw6r                                       1/1     Running   0          18m   172.31.17.191   ip-172-31-18-172…
team-b              php-apache-7cd5b774b6-d85pn                                       1/1     Running   0          18m   172.31.24.117   ip-172-31-26-143…
team-b              php-apache-7cd5b774b6-gx2r7                                       1/1     Running   0          18m   172.31.16.92    ip-172-31-26-143…
team-b              php-apache-7cd5b774b6-q4g6t                                       1/1     Running   0          18m   172.31.16.70    ip-172-31-26-143…
```

Cluster 2 carries observability only (no seeded app workloads, by design):

```
$ kubectl --context …xamops-test-2 get pods --all-namespaces
NAMESPACE           NAME                                                              READY   STATUS    RESTARTS   AGE
amazon-cloudwatch   amazon-cloudwatch-observability-controller-manager-5f4bb96ltf7w   1/1     Running   0          24m
amazon-cloudwatch   cloudwatch-agent-5gh44                                            1/1     Running   0          24m
amazon-cloudwatch   cloudwatch-agent-s86dq                                            1/1     Running   0          24m
amazon-cloudwatch   fluent-bit-4g4l8                                                  1/1     Running   0          24m
amazon-cloudwatch   fluent-bit-b9jpx                                                  1/1     Running   0          24m
kube-system         aws-node-njx6h                                                    2/2     Running   0          52m
kube-system         aws-node-zjdqh                                                    2/2     Running   0          52m
kube-system         coredns-bf6759cdf-d2zgr                                           1/1     Running   0          56m
kube-system         coredns-bf6759cdf-qc6z4                                           1/1     Running   0          56m
kube-system         kube-proxy-88dq2                                                  1/1     Running   0          52m
kube-system         kube-proxy-zzwzk                                                  1/1     Running   0          52m
kube-system         metrics-server-5d67bf6f79-2dvj5                                   1/1     Running   0          50m
kube-system         metrics-server-5d67bf6f79-vj4cx                                   1/1     Running   0          50m
```

### The intentional Pending pod, with its scheduling-failure event

```
$ kubectl --context …xamops-test-1 get pods -A --field-selector=status.phase=Pending
NAMESPACE   NAME                                   READY   STATUS    RESTARTS   AGE
team-a      unschedulable-giant-67cc89dbdc-psk4c   0/1     Pending   0          23m

$ kubectl --context …xamops-test-1 get pod -n team-a -l app=unschedulable-giant \
    -o jsonpath='{.items[0].spec.containers[0].resources}'
{"requests":{"cpu":"32","memory":"64Gi"}}

$ kubectl --context …xamops-test-1 describe pod -n team-a -l app=unschedulable-giant
Events:
  Type     Reason            Age                 From               Message
  ----     ------            ----                ----               -------
  Warning  FailedScheduling  23m                 default-scheduler  0/2 nodes are available: 2 Insufficient cpu, 2 Insufficient memory. no new claims to deallocate, preemption: 0/2 nodes are available: 2 Preemption is not helpful for scheduling.
  Warning  FailedScheduling  19m                 default-scheduler  0/3 nodes are available: 1 node(s) had untolerated taint(s), 2 Insufficient cpu, 2 Insufficient memory. no new claims to deallocate, preemption: 0/3 nodes are available: 3 Preemption is not helpful for scheduling.
  Warning  FailedScheduling  19m (x3 over 19m)   default-scheduler  0/4 nodes are available: 2 Insufficient cpu, 2 Insufficient memory, 2 node(s) had untolerated taint(s). no new claims to deallocate, preemption: 0/4 nodes are available: 4 Preemption is not helpful for scheduling.
  Warning  FailedScheduling  19m                 default-scheduler  0/4 nodes are available: 1 node(s) had untolerated taint(s), 3 Insufficient cpu, 3 Insufficient memory. no new claims to deallocate, preemption: 0/4 nodes are available: 4 Preemption is not helpful for scheduling.
  Warning  FailedScheduling  4m1s (x7 over 19m)  default-scheduler  0/4 nodes are available: 4 Insufficient cpu, 4 Insufficient memory. no new claims to deallocate, preemption: 0/4 nodes are available: 4 Preemption is not helpful for scheduling.
```

`32` CPU / `64Gi` against `1930m` / `~3.2Gi` allocatable per `t3.medium`. It will never
schedule at any node count this group can reach, which is the intent: pending-pod
detection, "no instance type fits" reporting, scale-up recommendation. No cluster
autoscaler or Karpenter is installed, so nothing will try to satisfy it.

### HPA actually scaled, and the PDB actually blocks

```
$ kubectl --context …xamops-test-1 get hpa -A
NAMESPACE   NAME         REFERENCE               TARGETS        MINPODS   MAXPODS   REPLICAS   AGE
team-b      php-apache   Deployment/php-apache   cpu: 62%/50%   1         5         5          23m

$ kubectl --context …xamops-test-1 get pdb -A
NAMESPACE     NAME              MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
kube-system   coredns           N/A             1                 1                     59m
kube-system   metrics-server    N/A             1                 1                     52m
team-b        pdb-guarded-web   3               N/A               0                     23m
```

The HPA is riding at its `maxReplicas: 5` with measured CPU above the 50% target — the
in-cluster load generator is doing real work, not producing flat zeros.
`pdb-guarded-web` shows `ALLOWED DISRUPTIONS: 0`, so a naive drain / consolidation of any
node holding one of its replicas is genuinely blocked.

## 6. metrics-server returns real numbers on both clusters

```
$ kubectl --context …xamops-test-1 top nodes
NAME                                           CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
ip-172-31-18-172.ap-south-1.compute.internal   283m         14%      609Mi           18%
ip-172-31-26-143.ap-south-1.compute.internal   468m         24%      585Mi           17%
ip-172-31-43-198.ap-south-1.compute.internal   218m         11%      659Mi           20%
ip-172-31-9-125.ap-south-1.compute.internal    189m         9%       695Mi           21%

$ kubectl --context …xamops-test-2 top nodes
NAME                                           CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
ip-172-31-33-222.ap-south-1.compute.internal   56m          2%       611Mi           18%
ip-172-31-5-198.ap-south-1.compute.internal    52m          2%       603Mi           18%
```

```
$ kubectl --context …xamops-test-1 top pods -A
NAMESPACE           NAME                                                              CPU(cores)   MEMORY(bytes)
amazon-cloudwatch   amazon-cloudwatch-observability-controller-manager-67ddff6trmlh   2m           22Mi
amazon-cloudwatch   cloudwatch-agent-2wt4x                                            3m           70Mi
amazon-cloudwatch   cloudwatch-agent-frf6p                                            4m           119Mi
amazon-cloudwatch   cloudwatch-agent-k9tkl                                            4m           80Mi
amazon-cloudwatch   cloudwatch-agent-tg7sh                                            3m           62Mi
amazon-cloudwatch   fluent-bit-62tnn                                                  4m           36Mi
amazon-cloudwatch   fluent-bit-gtb4w                                                  4m           35Mi
amazon-cloudwatch   fluent-bit-k9zn4                                                  4m           35Mi
amazon-cloudwatch   fluent-bit-xt7rw                                                  3m           35Mi
dev-sim             idle-batch-listener-b6d6898cd-hl9qh                               0m           0Mi
dev-sim             idle-batch-listener-b6d6898cd-t4vbd                               0m           0Mi
dev-sim             idle-batch-listener-b6d6898cd-wf97t                               0m           0Mi
dev-sim             no-requests-sidecar-app-55c8f955d9-4wcq6                          1m           0Mi
dev-sim             no-requests-sidecar-app-55c8f955d9-9vvmt                          1m           0Mi
kube-system         aws-node-7jpj4                                                    3m           55Mi
kube-system         aws-node-8r8sd                                                    4m           55Mi
kube-system         aws-node-kghs6                                                    2m           55Mi
kube-system         aws-node-vhzhv                                                    3m           55Mi
kube-system         coredns-67db859b96-62chd                                          8m           14Mi
kube-system         coredns-67db859b96-6tzbx                                          7m           14Mi
kube-system         kube-proxy-6bmfb                                                  1m           12Mi
kube-system         kube-proxy-6mnch                                                  1m           12Mi
kube-system         kube-proxy-87vhn                                                  1m           12Mi
kube-system         kube-proxy-l5h85                                                  1m           12Mi
kube-system         metrics-server-665685856d-nrdz2                                   4m           19Mi
kube-system         metrics-server-665685856d-wq9mz                                   3m           19Mi
prod-sim            rightsized-worker-7cb96c7ddf-bmw56                                49m          0Mi
prod-sim            rightsized-worker-7cb96c7ddf-vjf5s                                48m          0Mi
prod-sim            throttled-encoder-8b999cc87-64nmg                                 50m          0Mi
prod-sim            throttled-encoder-8b999cc87-h66p7                                 51m          0Mi
team-a              overprovisioned-api-76dd9f98c9-thhwl                              1m           0Mi
team-a              overprovisioned-api-76dd9f98c9-wz5tv                              1m           0Mi
team-b              load-generator-5d6ff98784-j4cbv                                   17m          1Mi
team-b              pdb-guarded-web-867645d8d-gm95f                                   0m           2Mi
team-b              pdb-guarded-web-867645d8d-qvltl                                   0m           2Mi
team-b              pdb-guarded-web-867645d8d-rf4p4                                   0m           2Mi
team-b              php-apache-7cd5b774b6-4s8nf                                       118m         12Mi
team-b              php-apache-7cd5b774b6-5dw6r                                       116m         12Mi
team-b              php-apache-7cd5b774b6-d85pn                                       94m          11Mi
team-b              php-apache-7cd5b774b6-gx2r7                                       198m         12Mi
team-b              php-apache-7cd5b774b6-q4g6t                                       103m         13Mi

$ kubectl --context …xamops-test-2 top pods -A
NAMESPACE           NAME                                                              CPU(cores)   MEMORY(bytes)
amazon-cloudwatch   amazon-cloudwatch-observability-controller-manager-5f4bb96ltf7w   2m           20Mi
amazon-cloudwatch   cloudwatch-agent-5gh44                                            11m          99Mi
amazon-cloudwatch   cloudwatch-agent-s86dq                                            7m           65Mi
amazon-cloudwatch   fluent-bit-4g4l8                                                  2m           36Mi
amazon-cloudwatch   fluent-bit-b9jpx                                                  4m           35Mi
kube-system         aws-node-njx6h                                                    3m           54Mi
kube-system         aws-node-zjdqh                                                    3m           54Mi
kube-system         coredns-bf6759cdf-d2zgr                                           1m           11Mi
kube-system         coredns-bf6759cdf-qc6z4                                           2m           11Mi
kube-system         kube-proxy-88dq2                                                  1m           12Mi
kube-system         kube-proxy-zzwzk                                                  1m           11Mi
kube-system         metrics-server-5d67bf6f79-2dvj5                                   3m           17Mi
kube-system         metrics-server-5d67bf6f79-vj4cx                                   4m           17Mi
```

The seeded scenarios separate cleanly in these numbers:

| Scenario | Request | Measured | Reads as |
|---|---|---|---|
| `overprovisioned-api` | 1000m | **1m** | ~99.9% CPU waste |
| `rightsized-worker` | 80m | **48-49m** | correctly sized, no change |
| `throttled-encoder` | 10m req / **50m limit** | **50-51m** | pinned at its limit → throttled |
| `idle-batch-listener` | 100m | **0m** | idle / zombie |
| `no-requests-sidecar-app` | none (BestEffort) | 1m | missing requests, governance |
| `php-apache` (HPA) | 200m | 94-198m | real load, scaled 1→5 |

`0Mi` on the busybox pods is metrics-server rounding a sub-megabyte RSS down, not a
collection failure — the same pods report non-zero `pod_memory_working_set` in
CloudWatch.

## 7. CloudWatch Container Insights metrics are flowing

```
$ aws cloudwatch list-metrics --namespace ContainerInsights --region ap-south-1
…
total ContainerInsights metric series: 3991
distinct metric names: 87
  xamops-test-1: 2744
  xamops-test-2: 1247
```

Non-empty for both clusters. First three series verbatim:

```
$ aws cloudwatch list-metrics --namespace ContainerInsights --region ap-south-1 --query 'Metrics[0:3]'
[
    {
        "Namespace": "ContainerInsights",
        "MetricName": "pod_status_scheduled",
        "Dimensions": [
            { "Name": "PodName",     "Value": "php-apache" },
            { "Name": "FullPodName", "Value": "php-apache-7cd5b774b6-gx2r7" },
            { "Name": "ClusterName", "Value": "xamops-test-1" },
            { "Name": "Namespace",   "Value": "team-b" }
        ]
    },
    {
        "Namespace": "ContainerInsights",
        "MetricName": "pod_interface_network_tx_dropped",
        "Dimensions": [
            { "Name": "PodName",     "Value": "metrics-server" },
            { "Name": "FullPodName", "Value": "metrics-server-5d67bf6f79-2dvj5" },
            { "Name": "ClusterName", "Value": "xamops-test-2" },
            { "Name": "Namespace",   "Value": "kube-system" }
        ]
    },
    {
        "Namespace": "ContainerInsights",
        "MetricName": "apiserver_storage_list_duration_seconds",
        "Dimensions": [
            { "Name": "resource",    "Value": "instrumentations.cloudwatch.aws.amazon.com" },
            { "Name": "ClusterName", "Value": "xamops-test-2" }
        ]
    }
]
```

The `node_*`, `pod_*`, `container_*`, `cluster_*`, `namespace_*` and `service_*` families
the CloudK8s features read are all present, including `container_cpu_request`,
`container_cpu_limit`, `pod_cpu_reserved_capacity`, `pod_cpu_utilization_over_pod_limit`,
`node_cpu_reserved_capacity` and `pod_status_pending`. The enhanced-observability
`apiserver_*` control-plane families are present too.

### Metric names alone prove nothing — these are real datapoints

Window `2026-10-09T07:25:11Z` → `2026-10-09T07:55:11Z`, `--period 300`:

```
$ aws cloudwatch get-metric-statistics --region ap-south-1 --namespace ContainerInsights \
    --metric-name node_cpu_utilization --dimensions Name=ClusterName,Value=xamops-test-1 \
    --start-time … --end-time … --period 300 --statistics Average Maximum
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 14.887655717478292, "Maximum": 26.243549462337107, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 14.859588171621828, "Maximum": 23.869444387029304, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 14.846600677359898, "Maximum": 23.482255003860534, "Unit": "Percent" }
]

   … same query, Value=xamops-test-2
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 2.5273716579110745, "Maximum": 3.462935480307175,  "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 2.4489479970361963, "Maximum": 3.2988190853222834, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 2.5416832550994153, "Maximum": 3.7591541008579172, "Unit": "Percent" }
]
```

CPU-throttling signal, `pod_cpu_utilization_over_pod_limit` for `prod-sim/throttled-encoder`:

```
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 99.99422313388513,  "Maximum": 100.11683546623192, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 100.00695175587968, "Maximum": 100.1846333157813,  "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 99.99355217511223,  "Maximum": 100.16387140935112, "Unit": "Percent" }
]
```

Waste signal, `pod_cpu_utilization` for `team-a/overprovisioned-api`:

```
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 0.002171099494006821, "Maximum": 0.002493598110977951, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 0.002075912615839808, "Maximum": 0.00229665445651709,  "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 0.002140976752985542, "Maximum": 0.002400654865468231, "Unit": "Percent" }
]
```

Load-bearing contrast: one workload sits at 100.0% of its CPU limit continuously, another
at 0.002% of its request. Rightsizing and throttling detection have unambiguous input.

`pod_cpu_utilization` for `team-b/php-apache` (6.26-7.56%) and
`cluster_number_of_running_pods` (41.0 on cluster 1, 13.0 on cluster 2) also return
datapoints matching the live `kubectl` state.

Container Insights log groups exist for both clusters
(`/aws/containerinsights/<cluster>/{application,dataplane,host,performance}`). `storedBytes`
read `0` when sampled — CloudWatch reports that field on a lag, and the metric datapoints
above confirm the agent is successfully publishing.

## 8. OIDC providers (IRSA)

```
$ aws iam list-open-id-connect-providers
{
    "OpenIDConnectProviderList": [
        {
            "Arn": "arn:aws:iam::102227938259:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/0A9C0230D0879EDBB699666A46D461BF"
        },
        {
            "Arn": "arn:aws:iam::102227938259:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/E72B17E7A1F041C4781F250EE1E046C4"
        }
    ]
}
```

The two IDs match the `oidcIssuer` values in section 1 — `0A9C…61BF` is `xamops-test-1`,
`E72B…46C4` is `xamops-test-2`. The IRSA roles consumed by the add-on:

```
$ aws iam get-role --role-name xamops-test-1-cw-observability
{ "RoleName": "xamops-test-1-cw-observability", "Arn": "arn:aws:iam::102227938259:role/xamops-test-1-cw-observability" }
$ aws iam list-attached-role-policies --role-name xamops-test-1-cw-observability
[ "CloudWatchAgentServerPolicy", "AWSXRayDaemonWriteAccess" ]

$ aws iam get-role --role-name xamops-test-2-cw-observability
{ "RoleName": "xamops-test-2-cw-observability", "Arn": "arn:aws:iam::102227938259:role/xamops-test-2-cw-observability" }
$ aws iam list-attached-role-policies --role-name xamops-test-2-cw-observability
[ "CloudWatchAgentServerPolicy", "AWSXRayDaemonWriteAccess" ]
```

## Summary

| Check | Result |
|---|---|
| Both clusters exist, ACTIVE | PASS (1.36 / 1.35) |
| Node groups ACTIVE | PASS |
| Cluster 2 capacity type SPOT | PASS — real spot, no on-demand fallback |
| Nodes Ready, both clusters | PASS (4 + 2) |
| `amazon-cloudwatch-observability` add-on, both | PASS (`v6.7.0-eksbuild.1`, ACTIVE, IRSA bound) |
| `metrics-server` add-on, both | PASS (`v0.9.0-eksbuild.11`, ACTIVE) |
| Seeded workloads Running | PASS (all 9 manifests applied; every intended replica Running) |
| Exactly one pod Pending, with `FailedScheduling` | PASS |
| HPA scaled on real load | PASS (1 → 5, 62%/50%) |
| PDB blocks disruption | PASS (`ALLOWED DISRUPTIONS: 0`) |
| `kubectl top nodes` / `top pods -A`, both | PASS, real numbers |
| `ContainerInsights` metrics non-empty | PASS (3991 series, 87 names, both clusters) |
| Container Insights datapoints, not just names | PASS |
| OIDC providers present | PASS (both, matching issuers) |

One deviation to carry forward: `xamops-test-1` runs 4 nodes, not the 2 its eksctl config
declares (section 2). Nothing else fell back, failed, or was worked around.

Agent / autoscaler deployability is assessed separately in `agent-assessment.md`.
Teardown: `eks/test-clusters/teardown.sh` (`DRY_RUN=1` to preview). Both clusters are
billable and still running.
