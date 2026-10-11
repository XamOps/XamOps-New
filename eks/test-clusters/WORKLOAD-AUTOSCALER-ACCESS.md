# Workload autoscaler cluster access (customer-performed)

What XamOps needs in order to read a cluster's workloads, read its Prometheus history and apply an
in-place pod resize. Everything below runs in the **customer** account `102227938259`, region
`ap-south-1`, against cluster `xamops-test-1`.

The chain is: XamOps app role → `sts:AssumeRole` (with external id) → customer IAM role → EKS
access entry → Kubernetes group → ClusterRole.

**The IAM role is granted `eks:DescribeCluster` and `eks:ListClusters` and nothing else.** It holds
no `pods/resize` permission, because AWS has no such permission — in-place resize is a Kubernetes
API call. The authority to mutate anything inside the cluster comes from
`xamops-workload-autoscaler-rbac.yaml` alone, and that ClusterRole grants exactly one write verb on
exactly one subresource (`patch` on `pods/resize`) plus `create` on `events` for the audit trail. It
grants no access to Secret objects and no `patch` on `apps/deployments`.

| Thing | Value |
|---|---|
| Account | `102227938259` |
| Region | `ap-south-1` |
| Cluster | `xamops-test-1` |
| IAM role | `XamOpsWorkloadAutoscalerRole-102227938259` |
| Trusted XamOps principal | `arn:aws:iam::605134457560:role/XamOps-App-EC2-Role` |
| External id | `40940876-6821-409a-a76e-a8b2f2a43486` |
| Kubernetes group | `xamops-workload-autoscaler` |
| Tags (every resource) | `Project=XamOps` `Purpose=CloudK8sFeatureTesting` `Owner=agent-provisioned` `CreatedOn=2026-10-11` `Ephemeral=true` |

---

## 1. IAM role

### 1a. Trust policy

Statement 1 is the real one. Statement 2 exists only so the proof in this repo could be run from a
laptop holding the account's own IAM user credentials — **that user cannot assume
`XamOps-App-EC2-Role`**, so without it the chain cannot be exercised locally. Remove it when the
proof is done (see teardown, step 5).

```bash
cat > /tmp/wa-trust.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "XamOpsAppRole",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::605134457560:role/XamOps-App-EC2-Role" },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": { "sts:ExternalId": "40940876-6821-409a-a76e-a8b2f2a43486" }
      }
    },
    {
      "Sid": "LocalVerificationOnlyRemoveAfterProof",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::102227938259:user/iam.autospotting" },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": { "sts:ExternalId": "40940876-6821-409a-a76e-a8b2f2a43486" }
      }
    }
  ]
}
JSON

aws iam create-role \
  --role-name XamOpsWorkloadAutoscalerRole-102227938259 \
  --description "Read-only EKS describe + in-cluster workload autoscaling for XamOps (ephemeral, feature testing)" \
  --assume-role-policy-document file:///tmp/wa-trust.json \
  --tags Key=Project,Value=XamOps \
         Key=Purpose,Value=CloudK8sFeatureTesting \
         Key=Owner,Value=agent-provisioned \
         Key=CreatedOn,Value=2026-10-11 \
         Key=Ephemeral,Value=true
```

### 1b. Inline policy — only the two `eks:` reads

`eks:DescribeCluster` is scoped to the one cluster being onboarded; add another cluster ARN to the
`Resource` list to onboard another. `eks:ListClusters` does not support resource-level permissions,
so it is a separate statement on `*`.

```bash
cat > /tmp/wa-policy.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DescribeOnboardedClusters",
      "Effect": "Allow",
      "Action": "eks:DescribeCluster",
      "Resource": "arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
    },
    {
      "Sid": "ListClusters",
      "Effect": "Allow",
      "Action": "eks:ListClusters",
      "Resource": "*"
    }
  ]
}
JSON

aws iam put-role-policy \
  --role-name XamOpsWorkloadAutoscalerRole-102227938259 \
  --policy-name XamOpsWorkloadAutoscalerEksRead \
  --policy-document file:///tmp/wa-policy.json
```

Confirm:

```bash
aws iam get-role --role-name XamOpsWorkloadAutoscalerRole-102227938259
aws iam list-role-policies --role-name XamOpsWorkloadAutoscalerRole-102227938259
aws iam get-role-policy --role-name XamOpsWorkloadAutoscalerRole-102227938259 \
  --policy-name XamOpsWorkloadAutoscalerEksRead
```

---

## 2. EKS access entry

Access entries require the cluster's authentication mode to be `API` or `API_AND_CONFIG_MAP`:

```bash
aws eks describe-cluster --name xamops-test-1 --region ap-south-1 \
  --query cluster.accessConfig.authenticationMode --output text
```

If it prints `CONFIG_MAP`, switch it (one-way: you can move toward `API`, never back):

```bash
aws eks update-cluster-config --name xamops-test-1 --region ap-south-1 \
  --access-config authenticationMode=API_AND_CONFIG_MAP
```

Then map the role to the Kubernetes group:

```bash
aws eks create-access-entry \
  --cluster-name xamops-test-1 --region ap-south-1 \
  --principal-arn arn:aws:iam::102227938259:role/XamOpsWorkloadAutoscalerRole-102227938259 \
  --type STANDARD \
  --kubernetes-groups xamops-workload-autoscaler \
  --tags Project=XamOps,Purpose=CloudK8sFeatureTesting,Owner=agent-provisioned,CreatedOn=2026-10-11,Ephemeral=true

aws eks describe-access-entry \
  --cluster-name xamops-test-1 --region ap-south-1 \
  --principal-arn arn:aws:iam::102227938259:role/XamOpsWorkloadAutoscalerRole-102227938259
```

No `--access-policy-associations`: the access entry carries no AWS-managed Kubernetes policy, only
the group. All in-cluster permission comes from step 3.

---

## 3. Kubernetes RBAC

Applied with an admin kubeconfig, once per cluster:

```bash
aws eks update-kubeconfig --region ap-south-1 --name xamops-test-1
kubectl config use-context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1

kubectl apply -f eks/test-clusters/xamops-workload-autoscaler-rbac.yaml
kubectl get clusterrole,clusterrolebinding xamops-workload-autoscaler
```

The `services/proxy` rule is restricted by `resourceNames` to the Prometheus service, so the role
can proxy to Prometheus and to nothing else in the cluster. If your Prometheus Service is not
`prometheus-server:9090` in namespace `monitoring`, change that `resourceNames` entry — RBAC matches
the name exactly as it appears in the proxy URL.

The optional last rule (`autoscaling.k8s.io/verticalpodautoscalers`, read-only) is only needed for
the side-by-side "what upstream VPA would recommend" column in the UI. Delete the rule if upstream
VPA is not installed; nothing else depends on it.

---

## 4. Verify as the role

Nothing is trusted until it has been exercised as the role rather than as an admin.

```bash
CREDS=$(aws sts assume-role \
  --role-arn arn:aws:iam::102227938259:role/XamOpsWorkloadAutoscalerRole-102227938259 \
  --external-id 40940876-6821-409a-a76e-a8b2f2a43486 \
  --role-session-name xamops-wa-proof \
  --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text)
export AWS_ACCESS_KEY_ID=$(echo "$CREDS" | cut -f1)
export AWS_SECRET_ACCESS_KEY=$(echo "$CREDS" | cut -f2)
export AWS_SESSION_TOKEN=$(echo "$CREDS" | cut -f3)
aws sts get-caller-identity

aws eks describe-cluster --name xamops-test-1 --region ap-south-1 --query cluster.status --output text

# kubeconfig deliberately outside the repo; .gitignore refuses *.kubeconfig anyway
aws eks update-kubeconfig --region ap-south-1 --name xamops-test-1 \
  --kubeconfig /tmp/wa-proof.kubeconfig --alias wa-proof

K="kubectl --kubeconfig /tmp/wa-proof.kubeconfig"
$K get pods -A
$K auth can-i get pods --all-namespaces                   # yes
$K auth can-i patch pods --subresource=resize -A          # yes
$K auth can-i create events --all-namespaces              # yes
$K auth can-i get secrets --all-namespaces                # MUST print: no
$K auth can-i patch deployments --all-namespaces          # MUST print: no
$K auth can-i patch pods --all-namespaces                 # MUST print: no (resize subresource only)
$K auth can-i delete pods --all-namespaces                # MUST print: no

# the proxy check: resource/resourceName positionally, subresource via --subresource
$K auth can-i get services/prometheus-server:9090 --subresource=proxy -n monitoring             # yes
$K auth can-i get services/prometheus-kube-state-metrics:8080 --subresource=proxy -n monitoring # no

# the real usage source, proxied through the API server
$K get --raw '/api/v1/namespaces/monitoring/services/prometheus-server:9090/proxy/api/v1/query?query=up'
```

Two syntax traps worth knowing, because the wrong form reads as a false failure:

- `auth can-i patch pods/resize` is parsed as *a pod named `resize`* and prints `no`. The
  subresource form is `auth can-i patch pods --subresource=resize`.
- for `services/proxy`, the service name is the positional *resourceName*
  (`get services/<name> --subresource=proxy`), not a trailing argument.

Only the name form that appears in the proxy URL matters. The URL XamOps builds is
`/api/v1/namespaces/monitoring/services/prometheus-server:9090/proxy/...`, so
`prometheus-server:9090` is the one `resourceNames` entry the manifest keeps. The bare
`prometheus-server` form is not usable (the Service exposes no endpoint on the default port, so the
proxy answers `ServiceUnavailable`), and `http:prometheus-server:9090` is a shape this backend never
builds — both were dropped after the live check, and both now return `Forbidden`.

The ClusterRole deliberately grants `services/proxy` but **not** `get` on the Service object
itself, so a "does this Service exist" pre-flight read will be `Forbidden` while the proxied query
through the same Service succeeds. Add `get` on `services` (restricted by `resourceNames` the same
way) only if you want that pre-flight probe.

---

## 5. Teardown

```bash
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN   # back to the admin identity

kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 \
  delete -f eks/test-clusters/xamops-workload-autoscaler-rbac.yaml

aws eks delete-access-entry \
  --cluster-name xamops-test-1 --region ap-south-1 \
  --principal-arn arn:aws:iam::102227938259:role/XamOpsWorkloadAutoscalerRole-102227938259

aws iam delete-role-policy \
  --role-name XamOpsWorkloadAutoscalerRole-102227938259 \
  --policy-name XamOpsWorkloadAutoscalerEksRead

aws iam delete-role --role-name XamOpsWorkloadAutoscalerRole-102227938259

rm -f /tmp/wa-proof.kubeconfig /tmp/wa-trust.json /tmp/wa-policy.json /tmp/wa-trust-prod-only.json
```

### Keeping the role but dropping only the temporary local-verification trust

If the role stays in place for XamOps to use, drop the second trust statement:

```bash
cat > /tmp/wa-trust-prod-only.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "XamOpsAppRole",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::605134457560:role/XamOps-App-EC2-Role" },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": { "sts:ExternalId": "40940876-6821-409a-a76e-a8b2f2a43486" }
      }
    }
  ]
}
JSON

aws iam update-assume-role-policy \
  --role-name XamOpsWorkloadAutoscalerRole-102227938259 \
  --policy-document file:///tmp/wa-trust-prod-only.json
```

`xamops-test-2` is intentionally not onboarded: it carries no access entry and no ClusterRole, so
`aws eks describe-access-entry --cluster-name xamops-test-2 --principal-arn <role>` returns
`ResourceNotFoundException`.
