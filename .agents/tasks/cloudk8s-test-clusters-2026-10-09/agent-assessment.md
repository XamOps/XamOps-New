# xamops-agent / xamops-autoscaler — deployability assessment

Date: 2026-10-09 · Target clusters `xamops-test-1` (K8s 1.36) and `xamops-test-2` (K8s 1.35),
`ap-south-1`, account `102227938259`.

Scope: can these two components actually be deployed onto the test clusters, and if not,
exactly what blocks it. Neither was deployed. The reason is in
[What actually blocks deployment](#what-actually-blocks-deployment) — it is a decision for
you, not a technical dead end.

## Verdict

| Component | Compiles | Image exists | Image runs in-cluster | Deployable today |
|---|---|---|---|---|
| `xamops-agent` | yes | yes | yes, verified | **yes, after 2 manifest edits** |
| `xamops-autoscaler` | yes | yes | yes, verified | **no — manifest `apply` fails** |

Both are closer to deployable than a blocker report usually implies. There is no missing
registry and no unreachable backend. What stops a clean `kubectl apply` is unsubstituted
placeholder values shipped in the manifests, plus one stale image tag.

## What was verified

### Both modules compile

```
$ cd /Users/apple/Desktop/XamOps-New/xamops-agent && go build ./...
   (no output, exit 0)

$ cd /Users/apple/Desktop/XamOps-New/xamops-autoscaler && go build ./...
   (no output, exit 0)

$ go version
go version go1.26.1 darwin/arm64
```

The autoscaler `Dockerfile` pins `golang:1.21-alpine` and its `go.mod` declares `go 1.21`.
That pairing is sound — the highest Go version any dependency requires is 1.21
(`google.golang.org/protobuf`; the `k8s.io/*` tree at `v0.28.4` needs only 1.20):

```
$ go list -m -f '{{.Path}} {{.GoVersion}}' all | sort -k2 -V | tail -4
k8s.io/client-go 1.20
k8s.io/metrics 1.20
github.com/xamops/automation 1.21
google.golang.org/protobuf 1.21

$ GOTOOLCHAIN=go1.21.13 go build ./...
go: downloading go1.21.13 (darwin/arm64)
   (no output, exit 0)
```

So the autoscaler image builds from its own Dockerfile. No Go version mismatch, unlike the
one the agent Dockerfile comments describe having hit and fixed.

### Images exist and are pullable

Both are public on Docker Hub, multi-arch (`linux/amd64` + `linux/arm64`), which matches
the `AL2023_x86_64_STANDARD` nodes:

| Repository | Tags | Last pushed |
|---|---|---|
| `xamops/agent` | `latest`, `v2.1.0`, `v2.0.0`, `1.0.0` | `v2.1.0` → 2026-08-11 · `latest` → **2026-03-19** |
| `xamops/automation` | `v1.0.0` | 2026-02-23 |

Note the autoscaler manifest references `xamops/automation`, not `xamops/autoscaler`.
A `xamops/autoscaler` repo also exists on Docker Hub but has no usable tag listing; the
manifest's `xamops/automation:v1.0.0` is the correct, present image.

No ECR repositories exist in this account (`aws ecr describe-repositories` → `[]`), so
nothing needs to be mirrored — the nodes pull from Docker Hub directly, which worked.

### Both images actually start on the cluster

Run as throwaway pods on `xamops-test-1` with **no** XamOps env vars, so they exercise
image pull + binary exec + config validation and perform no network write to any backend:

```
$ kubectl run agent-imagetest --image=xamops/agent:v2.1.0 --restart=Never -n default
$ kubectl logs agent-imagetest
2026/10/09 07:43:00 Starting XamOps Agent v2.1.0
2026/10/09 07:43:00 Failed to load config: XAMOPS_SERVER_URL is required
   state: {"terminated":{"exitCode":1,"reason":"Error"}}

$ kubectl run autoscaler-imagetest --image=xamops/automation:v1.0.0 --restart=Never -n default
$ kubectl logs autoscaler-imagetest
2026/10/09 07:45:06 Starting XamOps Automation Agent 1.0.0-automation
2026/10/09 07:45:06 Config error: XAMOPS_SERVER_URL is required
```

This settles several things at once: the images pull, the distroless entrypoints resolve to
a correctly-architected binary (the `ENTRYPOINT`/`TARGETARCH` hazard the agent Dockerfile
warns about is not present in the published images), and config validation fails loudly
rather than starting half-configured. All three test pods were deleted afterwards.

### The backend is reachable and accepts this account

```
$ curl -o /dev/null -w "%{http_code}" https://live.xamops.com/
200

$ curl -w "%{http_code}" -H "Authorization: Bearer <shipped agent key>" \
    "https://live.xamops.com/api/v1/agent/config?accountId=102227938259"
200   → {"openobserve":{"url":"https://live-otel.xamops.com","org":"default",
          "username":"<redacted>","password":"<redacted>"}}

$ curl -w "%{http_code}" "https://live.xamops.com/api/v1/agent/config?accountId=102227938259"
401   (no Authorization header)
```

`GET /api/v1/agent/config` is the agent's `pusher.FetchConfig()` call. It returns 200 for
account `102227938259` with the key that ships in the manifest, and 401 without it. So
auth works end to end and the account is not rejected. `GET /api/v1/agent/commands`
returns `400 {"error":"agentName query parameter or X-Agent-Name header is required"}`,
which is the expected response to a hand-rolled call missing that parameter — the endpoint
is live.

## Blocking findings

### B1 — agent manifest pins `:latest`, which is a v1.0.0 binary (agent)

`xamops-agent/manifests/xamops-agent-complete.yaml`:

```yaml
image: xamops/agent:latest
imagePullPolicy: Always
```

`latest` was last pushed 2026-03-19; `v2.1.0` on 2026-08-11. Proven by running both:

```
xamops/agent:latest  → "Starting XamOps Agent v1.0.0"
xamops/agent:v2.1.0  → "Starting XamOps Agent v2.1.0"
```

So the shipped manifest deploys an agent **two major versions behind** the current build,
and `imagePullPolicy: Always` makes it re-pull that stale digest on every restart. Any
collector added after March 2026 is simply absent from what gets deployed. Because the
version string is stamped into `agent_data_cache.agent_version`, the backend would also
record `1.0.0` and attribute v1 behaviour to a cluster the operator believes is on v2.1.

Unblock: pin `image: xamops/agent:v2.1.0`, or re-push `latest` from the v2.1.0 build.
Pinning is preferable — `:latest` with `Always` is non-deterministic across node restarts.

### B2 — agent credentials Secret ships placeholder cluster identity (agent)

```yaml
stringData:
  XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"
  XAMOPS_SERVER_URL: "https://live.xamops.com"
  CLUSTER_NAME: "your-cluster-name"      # placeholder
  CLOUD_ACCOUNT_ID: "123456789"          # placeholder
```

`config.Load()` checks these are non-empty, but not that they are real:

```go
if cfg.ClusterName == ""    { return nil, errors.New("CLUSTER_NAME is required") }
if cfg.CloudAccountID == "" { return nil, errors.New("CLOUD_ACCOUNT_ID is required") }
```

Applied as-is, the agent starts happily and pushes a full cluster inventory under cluster
name `your-cluster-name` and account `123456789`. That is worse than failing: it writes
mislabelled rows into the backend rather than refusing.

Unblock (per cluster): `CLUSTER_NAME: "xamops-test-1"`, `CLOUD_ACCOUNT_ID: "102227938259"`.
Worth considering a startup check that `CLOUD_ACCOUNT_ID` is 12 digits and not the
documented placeholder, so a forgotten substitution fails fast.

### B3 — autoscaler Secret is invalid base64, so `apply` is rejected (autoscaler)

`xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml`:

```yaml
type: Opaque
data:
  XAMOPS_API_KEY: <BASE64_API_KEY>
  XAMOPS_SERVER_URL: <BASE64_SERVER_URL>
```

Those literals are not base64, and the key is `data:` (which requires base64) rather than
`stringData:`. The API server refuses the object:

```
$ kubectl apply --dry-run=server -f xamops-autoscaler-complete.yaml
Error from server (BadRequest): … Secret in version "v1" cannot be handled as a Secret:
  illegal base64 data at input byte 0
```

This is a hard failure, not a wrong value: the Secret never gets created, so the Deployment
can never satisfy its `secretKeyRef` and the pod stays in `CreateContainerConfigError`.
This is the one finding here that makes `kubectl apply -f` of a shipped manifest fail
outright.

Unblock: either base64-encode real values, or switch the block to `stringData:` with plain
text — which is what the agent manifest already does. Making the two manifests consistent
on `stringData:` removes the whole class of error.

**Read the rest of that dry-run output carefully.** Both manifests also emit a run of
`namespaces "xamops-system" not found` errors under `--dry-run=server`. Those are an
artifact of server-side dry-run — the `Namespace` object is not really created, so the
namespaced objects after it have nothing to validate against. They are **not** blockers;
a real `apply` creates the namespace first and they disappear.

## What actually blocks deployment

With B1-B3 fixed, `kubectl apply` succeeds and the agent runs. The remaining gate is not
technical:

**The agent's only data sink is `POST https://live.xamops.com/api/v1/agent/ingest` — the
production XamOps backend.** Deploying it points two ephemeral, deliberately broken test
clusters at production: `xamops-test-1` and `xamops-test-2` would appear as real clusters in
the production tenant, carrying synthetic inventory — an unschedulable 32-CPU pod, a
permanently throttled encoder, three idle replicas — alongside genuine customer clusters.
The seeded workloads exist to be *wrong*; that data landing in production would make
fleet-wide cost and rightsizing aggregates wrong too, and clearing it means deleting rows
from a production store after teardown.

The configuration has no staging target. `XAMOPS_SERVER_URL` is the only knob and it is set
to `https://live.xamops.com`; there is no `DRY_RUN` on the agent (only the autoscaler has
one). So the choice is production or nothing.

I did not make that call. Options, cheapest first:

1. **Point the agent at a non-production backend.** `eks/manifests/` (namespace, secrets,
   configmap, redis, openobserve, backend, frontend, HPA) is a self-hosted XamOps stack.
   Deploy it to `xamops-test-2`, set `XAMOPS_SERVER_URL` to the in-cluster backend Service,
   and the full agent → backend → UI path gets exercised with zero production impact. Most
   work, best answer, and it also tests the self-host manifests.
2. **Accept production writes, scoped and time-boxed.** Deploy to `xamops-test-1` only, with
   correct identity, and plan the row cleanup for teardown. Fastest path to seeing the real
   CloudK8s features render real data.
3. **Leave both undeployed.** The CloudK8s features read CloudWatch Container Insights
   directly (verified flowing in `verification.md`), so they are testable without the agent.
   The agent adds the collectors CloudWatch cannot supply — OpenCost allocation, Trivy and
   Kubescape findings, Karpenter state, pod logs.

Tell me which and I will finish it.

The autoscaler is less sensitive: `DRY_RUN: "true"` and every Tier-2 flag
(`ENABLE_EVICTOR`, `ENABLE_RIGHTSIZER`, `ENABLE_REBALANCER`, `ENABLE_HPA_MANAGER`,
`ENABLE_VPA_MANAGER`, `ENABLE_NODE_OPTIMIZER`) default to `false`, so once B3 is fixed it
can be deployed and will make no cluster changes until explicitly enabled. Its RBAC
`ClusterRole` does request real mutation verbs — pod `delete`, deployment/statefulset
`patch`/`update`, eviction `create`, HPA and VPA full CRUD — so the dry-run default is the
only thing standing between it and live mutations. Verify `DRY_RUN` is honoured on every
path before flipping any flag.

## Non-blocking findings worth fixing

### Security: one shared key, committed, unlocks the log store

`XAMOPS_API_KEY: "xamops_SecureAgentKey2024XamOps"` is checked into
`manifests/xamops-agent-complete.yaml` with the comment "SAME for ALL clusters (DO NOT
CHANGE)", and the same literal is baked into `scripts/disk_agent_install.sh` and
`scripts/gcp_disk_agent_install.sh`.

Presenting it to `GET /api/v1/agent/config` returns the OpenObserve endpoint, org,
username and password in plaintext (confirmed, 200 — values withheld here). So anyone who
can read that manifest or either install script can retrieve the log-store credentials for
the tenant, and the agent's bearer token cannot be rotated for one cluster without
rotating it for every agent in every account.

This is the finding I would fix before enterprise customers see it; CAST AI and ScaleOps
both issue per-cluster tokens. Shape of a fix: per-cluster tokens minted at onboarding,
scoped to one `cloud_account_id`, revocable individually; have `/api/v1/agent/config`
return a short-lived scoped OpenObserve token instead of the admin password. Not in scope
here — flagging it, not changing it.

### Five collectors are on by default with nothing behind them

The shipped ConfigMap enables `COLLECTOR_TRIVY`, `COLLECTOR_KUBESCAPE`,
`COLLECTOR_OPENCOST`, `COLLECTOR_KARPENTER` and `COLLECTOR_PROMETHEUS_METRICS`, and points
`PROMETHEUS_URL` at `http://prometheus-server.monitoring.svc.cluster.local:9090`. None of
that exists on either test cluster:

```
$ kubectl get ns monitoring opencost trivy-system kubescape karpenter
Error from server (NotFound): namespaces "monitoring" not found
Error from server (NotFound): namespaces "opencost" not found
Error from server (NotFound): namespaces "trivy-system" not found
Error from server (NotFound): namespaces "kubescape" not found
Error from server (NotFound): namespaces "karpenter" not found

$ kubectl get crd | grep -iE "trivy|aquasec|kubescape|karpenter|verticalpod"
   (no matches)
```

So a deployed agent would log connection errors every cycle for five of its collectors and
push empty sections, while `COLLECTOR_K8S_RESOURCES` (the one that matters most) works fine
off the Kubernetes API alone. Either default these to `false` and let onboarding switch them
on, or detect the dependency and skip quietly. The ConfigMap comments say Prometheus is
required but the default is still `true`, so the out-of-box experience is a wall of errors.

`ENABLE_VPA_MANAGER` on the autoscaler has the same shape — it needs the VPA CRD, which is
not installed — but it at least defaults to `false`.

### Cosmetic: `getEnvRequired` does not require anything

Both modules define:

```go
func getEnvRequired(key string) string { return os.Getenv(key) }
```

It is a plain `os.Getenv` with a name that promises enforcement. The actual enforcement is
the explicit `if cfg.X == ""` block further down `Load()`, which does work. Harmless today;
the next required field added via `getEnvRequired` without a matching check will silently
default to empty. Either fold the check in or rename it.

## Reproducing this

```bash
# compile
cd xamops-agent && go build ./...
cd ../xamops-autoscaler && go build ./... && GOTOOLCHAIN=go1.21.13 go build ./...

# images exist
curl -s https://hub.docker.com/v2/repositories/xamops/agent/tags
curl -s https://hub.docker.com/v2/repositories/xamops/automation/tags

# images run (deleted afterwards)
kubectl --context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1 \
  run agent-imagetest --image=xamops/agent:v2.1.0 --restart=Never -n default
kubectl logs agent-imagetest; kubectl delete pod agent-imagetest

# manifests validate (ignore the xamops-system NotFound noise — dry-run artifact)
kubectl apply --dry-run=server -f xamops-agent/manifests/xamops-agent-complete.yaml
kubectl apply --dry-run=server -f xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml

# backend is live
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer <key from manifest>" \
  "https://live.xamops.com/api/v1/agent/config?accountId=102227938259"
```

Cluster-side evidence for the clusters themselves is in `verification.md`.
