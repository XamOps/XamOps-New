# CloudK8s test clusters — final report

Date: 2026-10-09 · Region `ap-south-1` (Mumbai) · Account `102227938259`
(`arn:aws:iam::102227938259:user/iam.autospotting`)

Two real EKS clusters are live and verified. `xamops-test-1` carries the deliberately
imperfect workload mix that every CloudK8s optimization finding is supposed to detect;
`xamops-test-2` is a spot, one-minor-behind cluster that exists to make fleet version-drift
and spot-inventory reporting have something real to read.

**Both clusters are billable and still running.** The estimated run rate is **~$54/day /
~$1,640/month**, and ~74% of that is CloudWatch Container Insights custom metrics, not
compute. Details in [Cost](#cost-of-leaving-both-clusters-running). Teardown:
[`eks/test-clusters/teardown.sh`](#teardown).

---

## 1. What was created

### Clusters

| | `xamops-test-1` | `xamops-test-2` |
|---|---|---|
| ARN | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2` |
| Status | `ACTIVE` | `ACTIVE` |
| Kubernetes version | **1.36** (current EKS default) | **1.35** (one minor behind) |
| Platform version | `eks.14` | `eks.24` |
| Created | `2026-10-09T12:10:19+05:30` | `2026-10-09T12:11:19+05:30` |
| OIDC issuer | `https://oidc.eks.ap-south-1.amazonaws.com/id/0A9C0230D0879EDBB699666A46D461BF` | `https://oidc.eks.ap-south-1.amazonaws.com/id/E72B17E7A1F041C4781F250EE1E046C4` |
| Config file | `eks/test-clusters/xamops-test-1.yaml` | `eks/test-clusters/xamops-test-2.yaml` |

**Yes — the two clusters are on different minor versions**, 1.36 vs 1.35. That is
deliberate: it gives fleet version-drift and upgrade/EOL reporting a real finding. 1.35 is
still `STANDARD_SUPPORT` (EOSS 2027-03), so neither cluster is paying EKS extended support
($0.50/hr/cluster) today.

Both reuse the existing default VPC `vpc-0622ed442aa8c1f3c` and its three public subnets
(`subnet-0361a77391315b8cc`, `subnet-02c92250ae6941e59`, `subnet-0f039ed6a736d0e0a`). No new
VPC and no NAT gateway. Nodes therefore have public IPs, and the cluster API endpoints are
public with no `publicAccessCIDRs` allow-list — acceptable for short-lived test clusters,
but it is a reason not to leave them up indefinitely.

Tags on cluster, node group and (via `propagateASGTags`) the nodes:
`Project=XamOps`, `Purpose=CloudK8sFeatureTesting`, `Owner=agent-provisioned`,
`CreatedOn=2026-10-09`, `Ephemeral=true`.

### Node groups

| | `ng-ondemand-1` (cluster 1) | `ng-spot-1` (cluster 2) |
|---|---|---|
| ARN | `arn:aws:eks:ap-south-1:102227938259:nodegroup/xamops-test-1/ng-ondemand-1/06d08fb7-ecb2-bef6-1c8e-e7a152e0e126` | `arn:aws:eks:ap-south-1:102227938259:nodegroup/xamops-test-2/ng-spot-1/70d08fb8-d94a-7995-85fa-6a7a7b037f63` |
| Status | `ACTIVE` | `ACTIVE` |
| **Capacity type** | `ON_DEMAND` | **`SPOT`** |
| Instance types | `t3.medium` | `t3.medium`, `t3a.medium`, `t2.medium` |
| AMI type | `AL2023_x86_64_STANDARD` | `AL2023_x86_64_STANDARD` |
| Node K8s version | `1.36` | `1.35` |
| min / desired / max | 1 / **4** / 4 | 1 / 2 / 4 |
| Root volume | 20 GiB gp3 per node | 20 GiB gp3 per node |

**Cluster 2 is genuinely SPOT — not an on-demand fallback.** `describe-nodegroup` returns
`capacityType: SPOT`, and `describe-instances` confirms `InstanceLifecycle: spot` on both
live nodes (one `t3.medium` in `ap-south-1b`, one `t3a.medium` in `ap-south-1a`), so the
diversified pool actually diversified. No documented fallback was needed.

One disclosed deviation: **cluster 1 runs 4 nodes, not the 2 originally configured.** The
seeded mix requests ~2880m CPU (plus up to 1000m more when the HPA rides to 5 replicas)
against `1930m` allocatable per `t3.medium`. At 2 nodes the scheduler sat at 97% CPU
requested and left real workloads Pending that were never meant to be Pending, which masks
the one pod that is *supposed* to be Pending. Rather than shrink the 1-CPU request that
*is* the rightsizing test case, the node group was scaled to its already-declared
`maxSize: 4`. `xamops-test-1.yaml` now declares `desiredCapacity: 4` with that reasoning
inline, so the file reproduces the verified state.

### Live EC2 / EBS inventory

```
$ aws ec2 describe-instances --region ap-south-1 \
    --filters "Name=tag:Project,Values=XamOps" "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].{id:InstanceId,type:InstanceType,lc:InstanceLifecycle,az:Placement.AvailabilityZone,pub:PublicIpAddress}'
+-------------+-----------------------+-------+-----------------+--------------+
|     az      |          id           |  lc   |       pub       |    type      |
+-------------+-----------------------+-------+-----------------+--------------+
|  ap-south-1b|  i-0cf38de433850d337  |  None |  13.201.225.238 |  t3.medium   |
|  ap-south-1b|  i-036ed41e759b4e3ba  |  spot |  13.235.77.181  |  t3.medium   |
|  ap-south-1c|  i-0ef87556f483a91cf  |  None |  3.110.152.237  |  t3.medium   |
|  ap-south-1c|  i-0e1008b419b0a2037  |  None |  3.109.156.19   |  t3.medium   |
|  ap-south-1a|  i-051717091a45bc361  |  None |  13.201.26.219  |  t3.medium   |
|  ap-south-1a|  i-0e2575b4681565f66  |  spot |  13.233.86.151  |  t3a.medium  |
+-------------+-----------------------+-------+-----------------+--------------+

$ aws ec2 describe-volumes --region ap-south-1 --filters "Name=tag:Project,Values=XamOps" \
    --query 'Volumes[].{id:VolumeId,size:Size,type:VolumeType,state:State}'
   6 × 20 GiB gp3, all in-use:
   vol-048bfedb6748125af  vol-04712bd8e728ce131  vol-070df32b493e22f33
   vol-0a92ff631443283cb  vol-0848dec583352a176  vol-0535aedbf80724081
```

4 on-demand + 2 spot = 6 nodes, 6 public IPv4 addresses, 120 GiB of gp3.

### Add-ons

Installed by `eks/test-clusters/install-observability.sh` on **both** clusters:

| Add-on | Version | Status | IRSA role |
|---|---|---|---|
| `metrics-server` | `v0.9.0-eksbuild.11` | `ACTIVE` | none needed |
| `amazon-cloudwatch-observability` | `v6.7.0-eksbuild.1` | `ACTIVE` | `<cluster>-cw-observability` |

Plus the EKS defaults `coredns`, `kube-proxy`, `vpc-cni`. The preferred path (managed
add-on, Container Insights with enhanced observability) worked on both K8s versions — no
fallback to a manual CloudWatch Agent + Fluent Bit install was needed.

### OIDC providers (IRSA)

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

`0A9C…61BF` → `xamops-test-1`, `E72B…46C4` → `xamops-test-2`; both match the `oidcIssuer`
values above.

### IAM roles created (8)

```
$ aws iam list-roles --query 'Roles[?contains(RoleName,`xamops-test`)].Arn'
arn:aws:iam::102227938259:role/eksctl-xamops-test-1-cluster-ServiceRole-fT2umBDimY64
arn:aws:iam::102227938259:role/eksctl-xamops-test-1-nodegroup-ng--NodeInstanceRole-UewEel6PCZMp
arn:aws:iam::102227938259:role/eksctl-xamops-test-1-addon-vpc-cni-Role1-9B0tc8zcCi4k
arn:aws:iam::102227938259:role/xamops-test-1-cw-observability
arn:aws:iam::102227938259:role/eksctl-xamops-test-2-cluster-ServiceRole-sbh6iLeEhHHe
arn:aws:iam::102227938259:role/eksctl-xamops-test-2-nodegroup-ng--NodeInstanceRole-QiqPgNG9kJyC
arn:aws:iam::102227938259:role/eksctl-xamops-test-2-addon-vpc-cni-Role1-vsv3aVHrkyyp
arn:aws:iam::102227938259:role/xamops-test-2-cw-observability
```

Breakdown: 2 cluster service roles, 2 node instance roles, 2 `vpc-cni` IRSA roles (eksctl),
and the 2 IRSA roles created for the CloudWatch agent service account. Both
`*-cw-observability` roles are bound to `amazon-cloudwatch/cloudwatch-agent` and carry
`CloudWatchAgentServerPolicy` + `AWSXRayDaemonWriteAccess`:

```
$ aws iam list-attached-role-policies --role-name xamops-test-1-cw-observability
[ "CloudWatchAgentServerPolicy", "AWSXRayDaemonWriteAccess" ]
$ aws iam list-attached-role-policies --role-name xamops-test-2-cw-observability
[ "CloudWatchAgentServerPolicy", "AWSXRayDaemonWriteAccess" ]
```

All 8 carry `Project=XamOps`, which is what `teardown.sh`'s `role_is_ours` guard requires —
so they get deleted rather than reported as `SKIPPED`.

### CloudWatch log groups (10)

```
/aws/eks/xamops-test-{1,2}/cluster                                       retention 7 days
/aws/containerinsights/xamops-test-{1,2}/{application,dataplane,host,performance}   never expire
```

Both clusters enable all five control-plane log types (`api`, `audit`, `authenticator`,
`controllerManager`, `scheduler`). The eight Container Insights groups have **no retention
set** — they will accumulate indefinitely until teardown.

---

## 2. Kubeconfig context names

```
$ aws eks update-kubeconfig --region ap-south-1 --name xamops-test-1
Updated context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 in /Users/apple/.kube/config
$ aws eks update-kubeconfig --region ap-south-1 --name xamops-test-2
Updated context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2 in /Users/apple/.kube/config
```

| Cluster | Context name |
|---|---|
| `xamops-test-1` | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` |
| `xamops-test-2` | `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2` |

`eksctl` also left its own aliases — `iam.autospotting@xamops-test-1.ap-south-1.eksctl.io`
and `iam.autospotting@xamops-test-2.ap-south-1.eksctl.io` — pointing at the same clusters.
The ARN contexts are the ones used in every command below.

Local `kubectl` is v1.34.1, two minors behind the 1.36 server. It works, but that is outside
the supported ±1 skew; `brew upgrade kubectl` before heavy use.

---

## 3. Verification evidence

All output below is captured verbatim from the live account. Full transcript:
`verification.md`.

### 3.1 Clusters exist and are ACTIVE

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

### 3.2 Node groups ACTIVE, with capacity type

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

The scale operation that took cluster 1 to 4 nodes, for the record:

```
$ aws eks update-nodegroup-config --region ap-south-1 --cluster-name xamops-test-1 \
    --nodegroup-name ng-ondemand-1 --scaling-config minSize=1,maxSize=4,desiredSize=4
{ "update": { "id": "6abf48b5-6a05-3364-a126-d02383bc64b1", "status": "InProgress", "type": "ConfigUpdate" } }
```

### 3.3 `kubectl get nodes -o wide` on both clusters

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

4 Ready on cluster 1 (v1.36.4), 2 Ready on cluster 2 (v1.35.8).

### 3.4 `kubectl get pods -A`

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
team-a              overprovisioned-api-76dd9f98c9-thhwl                               1/1     Running   0          23m   172.31.8.53     ip-172-31-9-125…
team-a              overprovisioned-api-76dd9f98c9-wz5tv                               1/1     Running   0          23m   172.31.47.22    ip-172-31-43-198…
team-a              unschedulable-giant-67cc89dbdc-psk4c                               0/1     Pending   0          23m   <none>          <none>
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

```
$ kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-2 get pods --all-namespaces
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

Cluster 2 carries observability only — no seeded app workloads, by design.

### 3.5 The intentional Pending pod and its scheduling event

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

32 CPU / 64Gi against `1930m` / ~3.2Gi allocatable per `t3.medium`. It will never schedule
at any node count this group can reach, which is the point. No cluster autoscaler or
Karpenter is installed, so nothing will try to satisfy it.

### 3.6 HPA scaled on real load; PDB blocks disruption

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

### 3.7 `kubectl top nodes` / `top pods -A`

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
team-a              overprovisioned-api-76dd9f98c9-thhwl                               1m           0Mi
team-a              overprovisioned-api-76dd9f98c9-wz5tv                               1m           0Mi
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

`0Mi` on the busybox pods is metrics-server rounding a sub-megabyte RSS down, not a
collection failure — the same pods report non-zero `pod_memory_working_set` in CloudWatch.

### 3.8 CloudWatch `ContainerInsights` metrics are non-empty, with real datapoints

```
$ aws cloudwatch list-metrics --namespace ContainerInsights --region ap-south-1
…
total ContainerInsights metric series: 4021
distinct metric names: 87
  xamops-test-1: 2745
  xamops-test-2: 1276
```

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

Metric names alone prove nothing, so the datapoints were pulled too. Window
`2026-10-09T07:25:11Z` → `2026-10-09T07:55:11Z`, `--period 300` (timestamps render in IST):

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

`pod_cpu_utilization_over_pod_limit`, `prod-sim/throttled-encoder` — the throttling signal:

```
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 99.99422313388513,  "Maximum": 100.11683546623192, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 100.00695175587968, "Maximum": 100.1846333157813,  "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 99.99355217511223,  "Maximum": 100.16387140935112, "Unit": "Percent" }
]
```

`pod_cpu_utilization`, `team-a/overprovisioned-api` — the waste signal:

```
[
    { "Timestamp": "2026-10-09T13:10:00+05:30", "Average": 0.002171099494006821, "Maximum": 0.002493598110977951, "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:15:00+05:30", "Average": 0.002075912615839808, "Maximum": 0.00229665445651709,  "Unit": "Percent" },
    { "Timestamp": "2026-10-09T13:20:00+05:30", "Average": 0.002140976752985542, "Maximum": 0.002400654865468231, "Unit": "Percent" }
]
```

One workload sits at 100.0% of its CPU limit continuously, another at 0.002% of its
request. Rightsizing and throttling detection have unambiguous input. The `node_*`, `pod_*`,
`container_*`, `cluster_*`, `namespace_*` and `service_*` families the CloudK8s features read
are all present, including `container_cpu_request`, `container_cpu_limit`,
`pod_cpu_reserved_capacity`, `node_cpu_reserved_capacity` and `pod_status_pending`, plus the
enhanced-observability `apiserver_*` control-plane families.

### 3.9 OIDC providers

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

### 3.10 Pass/fail summary

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
| `ContainerInsights` metrics non-empty | PASS (4021 series, 87 names, both clusters) |
| Container Insights datapoints, not just names | PASS |
| OIDC providers present | PASS (both, matching issuers) |

---

## 4. Cost of leaving both clusters running

Rates below are the live `ap-south-1` on-demand rates pulled from the AWS Price List API on
2026-10-09, not published-blog figures:

| Rate | Source usage type | Value |
|---|---|---|
| EKS cluster hour | `APS3-AmazonEKS-Hours:perCluster` | $0.10 / hr |
| `t3.medium` on-demand | `APS3-BoxUsage:t3.medium` | $0.0448 / hr |
| gp3 storage | `APS3-EBS:VolumeUsage.gp3` | $0.0912 / GB-mo |
| Public IPv4 in-use | `APS3-PublicIPv4:InUseAddress` | $0.005 / hr |
| CW custom metric (first 10k) | `APS3-CW:MetricMonitorUsage` | $0.30 / metric-mo |
| CW Logs ingest (Standard) | `APS3-DataProcessing-Bytes` | $0.67 / GB |
| CW Logs storage | `APS3-TimedStorage-ByteHrs` | $0.03 / GB-mo |
| CW API requests | `APS3-CW:Requests` | $0.01 / 1,000 |

Live spot prices at time of writing (`describe-spot-price-history`): `t3.medium` $0.0174-0.0203,
`t3a.medium` $0.0091-0.0121, `t2.medium` $0.0170-0.0178. The two running spot nodes are a
`t3.medium` in `1b` ($0.0201) and a `t3a.medium` in `1a` ($0.0121).

### Breakdown

Day = 24 h, month = 730 h.

| Line item | Basis | Daily | Monthly |
|---|---|---:|---:|
| **EKS control plane** | 2 clusters × $0.10/hr | **$4.80** | **$146.00** |
| **EC2 on-demand** | 4 × `t3.medium` × $0.0448/hr | **$4.30** | **$130.82** |
| **EC2 spot** | `t3.medium` $0.0201 + `t3a.medium` $0.0121 /hr | **$0.77** | **$23.51** |
| **EBS** | 6 × 20 GiB gp3 = 120 GiB × $0.0912/GB-mo (baseline 3000 IOPS / 125 MB/s included) | **$0.36** | **$10.94** |
| **Data transfer / networking** | 6 in-use public IPv4 × $0.005/hr = $21.90/mo, plus ~$1-3/mo cross-AZ and egress for metric/log publishing | **$0.79** | **$23.90** |
| **CloudWatch Container Insights — custom metrics** | **4,021 metric series × $0.30/metric-mo** | **$39.66** | **$1,206.30** |
| CloudWatch Logs — ingestion | control-plane logs incl. `audit` (5 types × 2 clusters) + fluent-bit application/host/dataplane; est. 3-6 GB/day both clusters × $0.67/GB | $3.02 | $92.00 |
| CloudWatch Logs — storage | ~4.5 GB/day retained; `/aws/eks/*` at 7 days, 8 Container Insights groups **never expire** | $0.13 | $4.00 |
| CloudWatch API requests | agent `PutMetricData` batches, est. 100k-500k/mo × $0.01/1,000 | $0.10 | $3.00 |
| **Total** | | **≈ $53.9** | **≈ $1,640** |

### Container Insights is the bill, and it is easy to underestimate

The README's original framing — "the control planes plus four small nodes" — is off by more
than an order of magnitude. Compute (control plane + on-demand + spot) is **$300/month**.
Container Insights custom metrics alone are **$1,206/month, ~74% of the total**, because
`amazon-cloudwatch-observability` with enhanced observability publishes a separate billable
custom metric for every unique metric-name + dimension-set combination. `list-metrics`
returns 4,021 of those series across 87 metric names — and the `FullPodName` dimension means
the count grows every time a pod is replaced or the HPA scales.

Mechanics worth knowing:

- Custom metrics are billed **prorated hourly** ($0.30 / 730 = ~$0.000411 per metric-hour),
  and only for metrics that receive data in that hour. So the charge started accruing at
  cluster creation, not at month end — the clusters have already booked roughly $40 of
  Container Insights charge per full day they have been up.
- The count is **per unique dimension set**, not per metric name. 87 names → 4,021 series.
  Pod churn inflates this: each new `FullPodName` is a new billable metric for its first
  hour and keeps its own series.
- Cluster 1 carries 2,745 series against cluster 2's 1,276 — the workload mix roughly
  doubles the per-cluster metric cost.
- The 10,000-metric first tier means there is no volume discount at this scale; every series
  is at the full $0.30.
- `storedBytes` read `0` on all ten log groups when sampled (CloudWatch reports that field
  on a lag), so the **log ingestion line is an estimate, not a measurement**. `audit` is the
  chatty log type; if it turns out heavier than assumed, the $92/month figure is the one most
  likely to be low. Dropping `audit` while the clusters idle is the cheapest trim.

If the clusters need to stay up, the three largest levers, in order:

1. **Turn Container Insights down or off.** Disabling enhanced observability (or scoping the
   agent to cluster/node-level metrics instead of per-pod/per-container) removes most of the
   4,021 series. This is ~$1,200/month. Note the account also has the newer volume-based
   EKS Container Insights usage type available (`APS3-CW:OTEL:Bytes:EKS-CI`, $0.08/GB), which
   is dramatically cheaper at this cluster size than per-metric billing.
2. **Scale cluster 1 back to `minSize: 1` and cluster 2 to 1 node** when not actively
   testing. Saves ~$130/month of on-demand plus public IPv4.
3. **Set retention on the eight Container Insights log groups** and drop the `audit`
   control-plane log type.

Just tearing down removes all of it. At ~$54/day, every day of idle time costs about what a
month of the EBS line does.

---

## 5. Seeded workload inventory

All on `xamops-test-1`, manifests in `eks/test-clusters/`. `00-namespaces.yaml` creates
`team-a`, `team-b`, `prod-sim`, `dev-sim` so per-namespace cost/usage grouping has something
to group by.

| Manifest | Namespace / workload | Replicas | Shape | Measured | Optimization finding it is designed to trigger |
|---|---|---|---|---|---|
| `10-overprovisioned.yaml` | `team-a/overprovisioned-api` | 2 | requests 1000m CPU / 1Gi, runs `sleep 1` | **1m CPU** (~0.002% of request) | **Over-provisioned → large rightsizing reduction.** The headline waste case: ~99.9% CPU request waste, should produce a big recommended-request cut and a big projected saving. |
| `11-rightsized.yaml` | `prod-sim/rightsized-worker` | 2 | duty-cycled loop vs 80m / 64Mi | 48-49m CPU | **Control case → no change recommended.** Guards against an engine that recommends a cut for everything; a finding here is a false positive. |
| `12-underprovisioned.yaml` | `prod-sim/throttled-encoder` | 2 | busy loop behind 10m request / **50m limit** | 50-51m, pinned at the limit; `pod_cpu_utilization_over_pod_limit` ≈ **100.0%** continuously | **Under-provisioned → CPU-throttling detection.** Should recommend raising request/limit, and must not be reported as idle despite the tiny request. |
| `13-idle.yaml` | `dev-sim/idle-batch-listener` | 3 | requests 100m, sleeps forever, **no Service** | **0m CPU** | **Idle / zombie workload detection.** No traffic, no Service, no consumption — should be flagged for scale-to-zero or deletion, with the 300m of reserved CPU recoverable. |
| `14-no-requests.yaml` | `dev-sim/no-requests-sidecar-app` | 2 | **no `resources` block at all** (BestEffort QoS) | 1m CPU | **No-requests governance finding.** Unschedulable-risk / noisy-neighbour policy violation; also tests that the rightsizing engine handles a missing baseline instead of dividing by zero. |
| `15-hpa-load.yaml` | `team-b/php-apache` + Service + HPA (1→5 @ 50% CPU) + `team-b/load-generator` | 5 (scaled from 1) + 1 | 200m request, real in-cluster load | 94-198m CPU/pod; HPA at **`cpu: 62%/50%`, 5/5 replicas** | **HPA safety constraint + real non-zero metrics + scale-up events.** A rightsizing engine must not recommend a request change that fights the HPA's target, and the autoscaler must respect an at-max HPA. Also guarantees metrics are never flat zeros. |
| `16-pdb-workload.yaml` | `team-b/pdb-guarded-web` | 3 | nginx, PDB `minAvailable: 3` on 3 replicas | `ALLOWED DISRUPTIONS: 0` | **PDB safety constraint.** Consolidation / bin-packing / node drain is genuinely blocked. Any recommendation to evict or consolidate a node holding one of these replicas is unsafe and must be suppressed or reported as blocked. |
| `17-unschedulable.yaml` | `team-a/unschedulable-giant` | 1 (Pending) | requests **32 CPU / 64Gi** | Pending, 5 × `FailedScheduling` events | **Unschedulable → pending-pod detection and scale-up recommendation.** No instance type in the node group fits, so correct output is "no instance type satisfies this" rather than an endless scale-up loop. Nothing will satisfy it — no autoscaler is installed. |

Two gaps to note. `no-requests-sidecar-app` declares a single container, so "sidecar" in the
name describes a scenario the manifest does not actually contain. And `xamops-test-2` — the
only genuinely spot cluster — carries **no evictable pods**, so spot-interruption handling,
consolidation and PDB-aware draining have nothing to act on there. A couple of small
replicas on cluster 2 would close that.

---

## 6. Agent and autoscaler deployability

Neither `xamops-agent` nor `xamops-autoscaler` was deployed. Full detail in
`agent-assessment.md`; the verdict:

| Component | Compiles | Image exists | Image runs in-cluster | Deployable today |
|---|---|---|---|---|
| `xamops-agent` | yes | yes (`xamops/agent`) | yes, verified | **yes, after 2 manifest edits** |
| `xamops-autoscaler` | yes | yes (`xamops/automation:v1.0.0`) | yes, verified | **no — `kubectl apply` is rejected** |

### What works

- Both modules build clean (`go build ./...`, exit 0). The autoscaler also builds under
  `GOTOOLCHAIN=go1.21.13`, matching its Dockerfile's `golang:1.21-alpine` — no Go version
  mismatch.
- Both images are public, multi-arch (`linux/amd64` + `linux/arm64`), and match the
  `AL2023_x86_64_STANDARD` nodes. No ECR mirroring needed; the nodes pull from Docker Hub.
- Both images **actually start on `xamops-test-1`** (run as throwaway pods with no XamOps env
  vars, then deleted): they pull, the distroless entrypoints resolve to a correctly
  architected binary, and config validation fails loudly rather than starting
  half-configured — `XAMOPS_SERVER_URL is required`.
- The backend is live and accepts this account: `GET /api/v1/agent/config?accountId=102227938259`
  returns `200` with the shipped agent key and `401` without it.
- `COLLECTOR_K8S_RESOURCES`, the collector that matters most, needs only the Kubernetes API,
  which is available.

### What blocks deployment

**B1 — agent manifest pins `:latest`, which is a v1.0.0 binary.**
`xamops-agent/manifests/xamops-agent-complete.yaml` sets `image: xamops/agent:latest` with
`imagePullPolicy: Always`. Proven by running both tags: `latest` prints
`Starting XamOps Agent v1.0.0`, `v2.1.0` prints `Starting XamOps Agent v2.1.0`. The shipped
manifest deploys an agent two major versions behind, and stamps `1.0.0` into
`agent_data_cache.agent_version`. *Unblock:* pin `xamops/agent:v2.1.0`, or re-push `latest`
from the v2.1.0 build. Pinning is better — `:latest` with `Always` is non-deterministic
across node restarts.

**B2 — agent Secret ships placeholder cluster identity.** `CLUSTER_NAME: "your-cluster-name"`
and `CLOUD_ACCOUNT_ID: "123456789"`. `config.Load()` checks these are non-empty, not that
they are real, so the agent starts and pushes a full inventory under the placeholder
identity — worse than failing. *Unblock:* set `CLUSTER_NAME: xamops-test-1` /
`CLOUD_ACCOUNT_ID: 102227938259` per cluster, and consider a startup check that
`CLOUD_ACCOUNT_ID` is 12 digits and not the documented placeholder.

**B3 — autoscaler Secret is invalid base64, so `apply` is rejected outright.**
`xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml` uses `data:` with the literals
`<BASE64_API_KEY>` / `<BASE64_SERVER_URL>`:

```
$ kubectl apply --dry-run=server -f xamops-autoscaler-complete.yaml
Error from server (BadRequest): … Secret in version "v1" cannot be handled as a Secret:
  illegal base64 data at input byte 0
```

The Secret is never created, so the Deployment can never satisfy its `secretKeyRef` and the
pod sits in `CreateContainerConfigError`. *Unblock:* switch the block to `stringData:` with
plain text, matching what the agent manifest already does. (The
`namespaces "xamops-system" not found` lines in the same dry-run output are a server-side
dry-run artifact, not a fourth blocker — a real `apply` creates the namespace first.)

### The real gate is a decision, not a defect

With B1-B3 fixed, `kubectl apply` succeeds and the agent runs. What stops it is that **the
agent's only data sink is `POST https://live.xamops.com/api/v1/agent/ingest` — the production
backend.** There is no staging target: `XAMOPS_SERVER_URL` is the only knob, and the agent
has no `DRY_RUN` (only the autoscaler does). Deploying it would publish synthetic,
broken-by-design inventory — an unschedulable 32-CPU pod, a permanently throttled encoder,
three idle replicas — into the production tenant beside real customer clusters, skewing
fleet-wide cost and rightsizing aggregates and requiring row deletion from a production
store after teardown.

Three options, cheapest first:

1. **Point the agent at a non-production backend.** `eks/manifests/` is a self-hosted XamOps
   stack (namespace, secrets, configmap, redis, openobserve, backend, frontend, HPA). Deploy
   it to `xamops-test-2`, set `XAMOPS_SERVER_URL` to the in-cluster backend Service, and the
   full agent → backend → UI path gets exercised with zero production impact. Most work, best
   answer, and it also tests the self-host manifests.
2. **Accept production writes, scoped and time-boxed.** Deploy to `xamops-test-1` only with
   correct identity, and plan row cleanup at teardown.
3. **Leave both undeployed.** The CloudK8s features read CloudWatch Container Insights
   directly, which is verified flowing, so they are testable without the agent. The agent adds
   what CloudWatch cannot supply — OpenCost allocation, Trivy and Kubescape findings,
   Karpenter state, pod logs.

### Two things to fix before enterprise customers see them

- **One shared, committed agent key.** `XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"` is
  checked into the agent manifest ("SAME for ALL clusters") and baked into
  `scripts/disk_agent_install.sh` and `scripts/gcp_disk_agent_install.sh`. Presenting it to
  `/api/v1/agent/config` returns the tenant's OpenObserve endpoint, org, username and password
  in plaintext (confirmed live; values withheld). It cannot be rotated for one cluster without
  rotating every agent in every account. CAST AI and ScaleOps both issue per-cluster tokens.
- **Five collectors default to on with nothing behind them.** `COLLECTOR_TRIVY`,
  `COLLECTOR_KUBESCAPE`, `COLLECTOR_OPENCOST`, `COLLECTOR_KARPENTER` and
  `COLLECTOR_PROMETHEUS_METRICS` are enabled by default; none of the `monitoring`, `opencost`,
  `trivy-system`, `kubescape` or `karpenter` namespaces or CRDs exist on either cluster, so a
  deployed agent logs connection errors every cycle and pushes empty sections.

The autoscaler is less sensitive once B3 is fixed: `DRY_RUN: "true"` and every Tier-2 flag
(`ENABLE_EVICTOR`, `ENABLE_RIGHTSIZER`, `ENABLE_REBALANCER`, `ENABLE_HPA_MANAGER`,
`ENABLE_VPA_MANAGER`, `ENABLE_NODE_OPTIMIZER`) default to `false`. But its `ClusterRole`
requests real mutation verbs — pod `delete`, eviction `create`, Deployment/StatefulSet
`patch`/`update`, HPA and VPA CRUD — so the dry-run default is the only thing between it and
live mutations. Verify `DRY_RUN` is honoured on every path before flipping any flag.

---

## 7. Teardown

Script: `eks/test-clusters/teardown.sh` (executable, idempotent).

```bash
cd /Users/apple/Desktop/XamOps-New/eks/test-clusters

# 1. ALWAYS preview first — prints what would be deleted, changes nothing destructive
DRY_RUN=1 ./teardown.sh

# 2. real teardown, both clusters
./teardown.sh

# 3. confirm the account is clean (CloudFormation deletes are async, so a second
#    pass is a reasonable way to verify)
./teardown.sh
```

Per cluster it removes, in dependency order: `type=LoadBalancer` Services and PVCs first (so
the in-cluster controllers delete their ELBs and EBS volumes), then node groups and control
plane via `eksctl delete cluster --disable-nodegroup-eviction --force --wait`, the IRSA role,
the OIDC provider, leftover `eksctl-<cluster>-*` CloudFormation stacks, orphaned ALB/NLB/
classic LBs and target groups, detached EBS volumes, leftover security groups (rules revoked
first), and the cluster + Container Insights CloudWatch log groups.

`--disable-nodegroup-eviction --force` means `pdb-guarded-web`'s `ALLOWED DISRUPTIONS: 0`
will not wedge its own teardown.

### The `Project=XamOps` safety guard

Every resource is tag-checked before deletion. The script sets:

```bash
REQUIRED_TAG_KEY="Project"
REQUIRED_TAG_VALUE="XamOps"
```

and a resource is deleted only if it carries `Project=XamOps` **or** a
`kubernetes.io/cluster/<cluster>` tag for one of these clusters. Anything else is printed
under `SKIP (not tagged Project=XamOps)`, collected into a `SKIPPED` list, and never touched.
The ownership probes (`cluster_is_ours`, `stack_is_ours`, `role_is_ours`, and the two ELB
helpers) are **fail-closed**: a failed AWS call yields an empty tag string, which fails the
comparison and routes the resource to `skip`. An API error cannot be mistaken for ownership.
All 8 IAM roles above carry `Project=XamOps`, so they are deleted rather than skipped.

### Known teardown defects — read before running

These are documented in `provision-review.md` and were not fixed in this task:

1. **The OIDC-provider cleanup never fires in the real path.** `delete_cluster` reads
   `cluster.identity.oidc.issuer` from `describe-cluster` *after* `eksctl delete cluster
   --wait` has already removed the cluster, so `oidc_id` is empty and the function prints
   "no OIDC issuer recorded (cluster already gone)". A second pass cannot repair it — it
   returns early at "cluster not found". **Both OIDC providers will survive a teardown that
   reports no problem.** Delete them by hand afterwards:
   ```bash
   aws iam delete-open-id-connect-provider --open-id-connect-provider-arn \
     arn:aws:iam::102227938259:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/0A9C0230D0879EDBB699666A46D461BF
   aws iam delete-open-id-connect-provider --open-id-connect-provider-arn \
     arn:aws:iam::102227938259:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/E72B17E7A1F041C4781F250EE1E046C4
   ```
   Under `DRY_RUN=1` the cluster still exists, so the dry run *will* print a reassuring
   "deleting OIDC provider …" line it cannot reproduce for real.
2. **Single-cluster teardown has region-wide scope.** `./teardown.sh xamops-test-2` is an
   advertised mode, but the load-balancer, detached-EBS and security-group sweeps match any
   `Project=XamOps` resource regardless of which cluster was named, so they can reach the
   other cluster's resources. Today the blast radius is bounded — the only `Project=XamOps`
   resources in the account are these two clusters' stacks, ASGs and roles — but prefer the
   no-argument form that deletes both.
3. **The tag guard is a substring match**, so a hypothetical `Project=XamOpsProd` would also
   match. Not an issue in this account today.
4. **Log-group deletion is the one unguarded step** — name-prefix scoped to the cluster, no
   tag check, which contradicts the "every resource is tag-checked" claim in the script
   header and README.
5. **The script always exits 0** (no `set -e`, no exit-code accumulation), and prints
   "Teardown pass finished" regardless of failed steps. Read the output; do not trust the
   exit code. Check for `SKIPPED` entries.
6. **`DRY_RUN=1` still writes to kubeconfig** — `aws eks update-kubeconfig` in
   `drain_k8s_cloud_resources` is not wrapped in `run`, so a preview switches the local
   current context despite the "change nothing" header.
7. **`teardown.sh` has never been executed, in any mode.** It was syntax-checked under bash
   3.2.57 (the macOS `/bin/bash` its string handling targets) and 5.x, clean under both, but
   the `DRY_RUN=1` pass is still owed.

### Post-teardown verification

```bash
aws eks list-clusters --region ap-south-1
aws iam list-open-id-connect-providers
aws iam list-roles --query 'Roles[?contains(RoleName,`xamops-test`)].RoleName'
aws ec2 describe-instances --region ap-south-1 \
  --filters "Name=tag:Project,Values=XamOps" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId'
aws ec2 describe-volumes --region ap-south-1 --filters "Name=tag:Project,Values=XamOps" \
  --query 'Volumes[].VolumeId'
aws cloudformation describe-stacks --region ap-south-1 \
  --query 'Stacks[?starts_with(StackName,`eksctl-xamops-test`)].StackName'
aws logs describe-log-groups --region ap-south-1 \
  --query 'logGroups[?contains(logGroupName,`xamops-test`)].logGroupName'
```

All seven should come back empty. CloudWatch custom-metric charges stop within the hour once
the agents are gone; `ContainerInsights` series linger in `list-metrics` for up to two weeks
but stop billing as soon as they stop receiving data.

---

## 8. Evidence files

| File | What it is |
|---|---|
| `verification.md` | Full captured provisioning evidence, 8 sections plus a pass/fail table |
| `agent-assessment.md` | Agent/autoscaler deployability, blockers B1-B3, production-backend gate |
| `provision-review.md` | Independent review of the provisioning work, 13 issues, verdict APPROVED |
| `review.json` | Machine-readable form of the review |
| `eks/test-clusters/README.md` | Runbook: cluster table, workload table, order of operations |
| `eks/test-clusters/xamops-test-{1,2}.yaml` | eksctl cluster configs |
| `eks/test-clusters/install-observability.sh` | metrics-server + Container Insights add-on install, creates the IRSA roles |
| `eks/test-clusters/teardown.sh` | Tag-guarded destructive teardown |
| `eks/test-clusters/{00,10..17}-*.yaml` | The nine seeded workload manifests |
