# XamOps → Upwind: embedded customer onboarding — API requirements

**From:** Xammer / XamOps engineering
**To:** Upwind Product & API team
**Date:** 2026-09-07
**Organization:** `org_Zwq0e1xF9Au4AF0Q` (region `us`)
**Accounts referenced:** AWS `605134457560`, `598515435126`, `080310056620`
**Credential:** the OAuth client-credentials API client previously shared with your team

## Our priority

**We need to onboard our customers onto Upwind from inside XamSecure, programmatically.**

XamSecure is a security module in the XamOps platform, powered by Upwind and sold by us. The
customer journey has to begin and end in our product: they pick one of their cloud accounts, click
connect, deploy a sensor, and see findings — without visiting the Upwind console or handling Upwind
credentials.

We have this working for AWS today, but it is built on **undocumented routes we found by reading
your onboarding Lambda**, and it requires placing **one shared credential set into every customer's
environment** because no per-tenant credential API exists. Both of those are blockers to selling at
scale, and neither is something we can solve on our side.

Everything else in this document is secondary. §2 is the ask.

| Priority | Request | § |
|---|---|---|
| ~~P1~~ | ~~Document `POST /v1/{org}/cloud-accounts`~~ — **withdrawn**, the route is BYOC-only | 2.1 |
| **P1** | Publish the four `integration.upwind.io` onboarding routes we currently depend on | 2.2 |
| **P1** | An API to mint **per-account sensor credentials** — none exists | 2.3 |
| **P1** | Let an API/sensor credential be **bound to a scope** — the scope mechanism already ships | 2.4 |
| **P2** | A cloud-account **list/status** route — `GET` is not a route today | 2.5 |
| **P2** | Sensor **deployment artifacts and health** via API (K8s, ECS, host) | 2.6 |
| **P2** | Fix the onboarding template's **no-op Delete handler** (affects all your customers) | 2.7 |
| **P2** | The **OEM / sub-tenant provisioning** model | 2.8 |
| P3 | Onboarding failure diagnostics and an escalation path | 2.9 |
| P4 | Feature parity asks: inventory graph, runtime map, unexposed modules, compliance scoping | 4–6 |
| P4 | Data-quality findings | 7 |

---

## 1. What we have built, and exactly how

So the gaps in §2 are concrete rather than abstract.

| Capability | How | State |
|---|---|---|
| Self-service AWS account onboarding | Pre-filled CloudFormation quick-create URL; the stack self-registers | **Live, proven end to end** |
| Connection reconciliation | Poll `GET integration.upwind.io/v1/{org}/cloud-credentials` | Live |
| Host sensor | Your "Connect a host" script rendered server-side | Built |
| Disconnect | `DELETE …/cloud-credentials/{id}` plus local cleanup | Built |
| Per-customer entitlement | Our own admin toggle gating who may connect | Live |
| Findings sync | Detections every 60s; vulnerabilities, posture, inventory, API endpoints every 6h | Live |
| Real-time detections | Webhook to our ingest endpoint (`vendor: custom`, bearer auth) | Live |
| Kubernetes / ECS sensors | — | **Not built — no API** |
| Azure / GCP self-service | — | Not built |

### The flow as implemented

1. Customer selects one of their own AWS accounts in XamOps.
2. We return a CloudFormation quick-create URL pre-filled with `OrganizationId`, `TrustedAccount`
   (`340457201789`), `UpwindRegion`, and a Cloud-account `ClientId`/`ClientSecret`. The customer
   types nothing; the credential is injected server-side and never displayed to them.
3. The stack's `Custom::UpwindGeneratedToken` mints the external ID and
   `Custom::UpwindRegisteredAccount` registers the account. Neither step requires a call from us.
4. We poll `GET /cloud-credentials` and flip the account to Connected.

**Proven:** account `080310056620` was onboarded this way on 2026-08-24 — stack `CREATE_COMPLETE`,
registration `connected`, cloud scanner enabled, inventory assets flowing.

### Why this is not good enough to scale

The credential in step 2 is **the same one for every customer**, because there is no API to create
one per account. We checked what it can do before shipping, by decoding the tokens:

- Cloud-account credential (`type=cloud_account`): `create/get/update/delete:credentials` and
  `get:external-ids` on `integration.upwind.io`; **zero permissions** on `api.upwind.io`.
- Sensor credential (`type=agent`): **zero permissions on both hosts**.

So there is no cross-tenant **read** risk, which is why we were comfortable going live. The residual
risk is **integrity**: a customer who extracts the cloud credential from their own stack could
deregister another customer's account in our organization, and one who extracts the sensor
credential could enrol a rogue sensor or submit false telemetry. We would like to close that before
scaling the motion, and we cannot close it from our side.

---

## 2. What we need for onboarding

### 2.1 (withdrawn) `POST /v1/{org}/cloud-accounts` — resolved, and it is not what we hoped

**This ask is withdrawn.** We had found the route by probing (an empty body returns
`400 {"provider":"must not be null","name":"must not be null","config":"must not be null"}`) and
asked you to publish the per-provider request body so we could onboard AWS accounts
programmatically.

Having now read the published schema: **the route is BYOC-only.** `provider` is
`enum: [BYOC]`, and `config` is titled *BYOC* with `infrastructure_types`
(`VMWARE | HYPERV | OPENSTACK | BARE_METAL`) and `location`. The strings `AWS`, `AZURE` and `GCP`
do not appear anywhere in the cloud-accounts specification. So this creates on-premise accounts and
is not an AWS onboarding path at all.

Two consequences:

- **It strengthens §2.2.** The undocumented `integration.upwind.io/cloud-credentials` routes are
  now provably the *only* way to register an AWS account programmatically. That is the ask that
  matters.
- **It opens something we had not considered.** On-premise onboarding is fully API-supported today:
  `POST /v1/{org}/cloud-accounts` with `provider: BYOC`, plus the Kubernetes sensor path, which
  already offers "Bring your own cloud (On-premise)" as a provider. We intend to build that, and
  would welcome confirmation that it is the supported BYOC flow.

Still worth answering: **is a BYOC cloud account the same object as an
`integration.upwind.io` cloud credential, or a separate one?** Whether those are two views of one
registration or two different things materially changes how we model onboarding.

### 2.2 (P1) Publish the `integration.upwind.io` onboarding routes

Every `/cloud-accounts` and `/byoc-credentials` path on `api.upwind.io` that we tried returned
`404` or `401`, so we located the real routes by downloading your publicly readable onboarding
Lambda (`upwind-serverless-functions-us-east-1/onboarding/1.0.0.zip`) and reading
`upwind_onboarding_lambda.py`:

```
POST   {integration}/v1/organizations/{org}/aws-external-ids
POST   {integration}/v1/organizations/{org}/cloud-credentials
GET    {integration}/v1/organizations/{org}/cloud-credentials
DELETE {integration}/v1/organizations/{org}/cloud-credentials/{id}
```

`{integration}` is `https://integration.upwind.io`, regionalised to `.<region>.upwind.` for
anything other than `us` — the same rewrite your Lambda applies. The audience for the token must be
the integration host itself; a token minted for `https://api.upwind.io` authenticates but carries
no permissions there.

We are in production on two of these (`GET` and `DELETE`).

**Ask:** publish them, or tell us the supported equivalent. Building our commercial motion on routes
discovered by decompilation is a risk for both of us — you could change them in a patch release
without knowing anyone depended on them.

### 2.3 (P1) An API to create sensor credentials

**No route exists.** We probed thoroughly:

| Tried | Result |
|---|---|
| `/credentials`, `/cloud-credentials` | Same collection; accepts only the cloud-account shape (`provider` + `spec`). `type`, `kind`, `usedFor` and `provider.name=sensor` are all ignored |
| `/sensor-credentials`, `/sensors` | 404 |
| `/kubernetes-clusters`, `/clusters`, `/hosts`, `/ecs-clusters`, `/cloud-scanners` | 404 |
| `access-management/credentials`, `api-clients`, `service-accounts`, `api-keys` | 401 with no `trace-id` — i.e. not routes (see §3) |

Consequence: **one sensor credential is shared by every customer we onboard.** The alternative is a
console visit per customer, which defeats the purpose of embedding onboarding.

**Ask:** an API to mint a sensor credential, ideally scoped to a single cloud account. This is the
single most valuable item in this document for us.

### 2.4 (P1) Bind a credential to a scope — you have already built the mechanism

`POST /v2/{org}/access-management/scopes/search` returns per-tenant scopes that already filter on
cloud account:

```
all                    Global Scope   resource_filters=[]
uams-c1b8686518b31054  OnFinance      [{"attribute":"cloud_account_id","values":["598515435126"],"operator":"in"}]
uams-96cee995a3ae6c2a  Xammer         [{"attribute":"cloud_account_id","values":["605134457560"],"operator":"in"}]
uams-a2e91517e8f63c85  GSA Test       [{"attribute":"cloud_account_id","values":["080310056620"],"operator":"in"}]
```

and `POST /v2/{org}/access-management/members/search` shows each member carrying
`permissions: [{scope_id, scope_name, role_ids, source}]`.

So scope-bound authorization filtered by `cloud_account_id` is **shipped and working — for human
members.** What is missing is the same binding for machine credentials, which is what an integrator
needs.

**Ask:** allow an API credential and a sensor credential to be created against, or bound to, an
existing scope. This is an extension of a mechanism you already have rather than a new one, and it
is what turns our current arrangement from a defensible pilot into a sales motion.

### 2.5 (P2) A cloud-account list / status route

`GET /v1/{org}/cloud-accounts` returns `401` with no `trace-id` — not a route. Your published
specification lists `POST`, `PATCH /{account-id}` and `DELETE /{account-id}` for that collection,
but no list operation.

Our onboarding poller needs to answer "is this customer's account connected yet, and is the scanner
running". We currently get that from `GET integration.upwind.io/v1/{org}/cloud-credentials`, reading
`status`, `cloudScannerEnabled` and `cloudApiEnabled`.

**Ask:** a documented list/get route returning connection status per account, ideally including
sensor presence and health, so a single call can drive our onboarding UI.

### 2.6 (P2) Sensor deployment artifacts and health via API

We can render your host script server-side, and that is all. To present sensor rollout inside
XamSecure we need, retrievable per account or cluster:

- Kubernetes: Helm chart reference and values, or DaemonSet manifests
- ECS: task definition or the equivalent install command
- Host: the install script (we have this)
- Managed / cloud scanner: the CloudFormation or equivalent
- **Deployment and health status** per sensor, so we can show a customer that their sensor is alive

**Ask:** an API for the above. Kubernetes and ECS onboarding is currently not buildable for us at
any effort, and those are the workloads our customers most want covered.

### 2.7 (P2) The onboarding template's Delete handler is a no-op

This affects every Upwind customer who deletes an onboarding stack, not only resellers, so we are
raising it as a defect rather than a request.

```python
def send_delete_response(event, context):
    msg = 'Upwind AWS API call succeeded, Integration deleted successfully'
    cfn_response.send(event, context, cfn_response.SUCCESS, {'Message': msg})
```

That is the whole function — no API call. Deleting the stack removes the IAM role and leaves the
credential registered, so Upwind continues trying to assume a role that no longer exists: a
permanently failing connection rather than a removal. The message asserts the opposite of what
happened.

We work around it by calling `DELETE /cloud-credentials/{id}` ourselves first. **Ask:** fix the
handler, and document the supported offboarding sequence so we can align with it.

### 2.8 (P2) The OEM / sub-tenant model

Today all our customers live in one Upwind organization (`org_Zwq0e1xF9Au4AF0Q`) and we isolate them
by `resource.cloud_account_id` at a single chokepoint in our query layer. We verified this holds:
sampled 2026-08-24, **445 records across detections, vulnerability findings and configuration
findings all carried `resource.cloud_account_id`, zero unattributed**, correctly split across
accounts. An unattributed finding would be invisible to every tenant rather than visible to the
wrong one.

**Ask:** confirm whether that is the intended reseller model, or whether we should be creating a
**sub-organization per customer** under a partner account. If sub-tenants are the answer, we need
the provisioning API and the credential structure that goes with it. This decision affects our
architecture, so we would rather make it once, with you.

### 2.9 (P3) Onboarding failure diagnostics

When a customer's stack fails or a registration does not complete, we are the only party who can
raise it — our customers never see the Upwind brand. We need a diagnostic signal (why did
registration fail) and a named escalation route that is not a generic support queue.

---

## 3. Corrections to our earlier reports — please disregard these

In July 2026 we sent a list of endpoints returning `401` and asked which OAuth scope we needed.
**That framing was wrong and the requests attached to it should be dropped.**

| We previously reported | Actually |
|---|---|
| `inventory/topology`, `inventory/connections`, `inventory/graph`, `inventory/runtime` → 401, need scope | None of the four is a route. We invented the paths |
| `GET /v2/{org}/events` → 401, needs scope | Not a route; the read route is `GET /v1/{org}/threat-events`, which works |
| "No graph or relationship capability exists" | Wrong. `POST /v2/{org}/inventory/assets/search` does relationship traversal and works on our current credential |
| "Creating a cloud account is not possible (404)" | The route exists but is **BYOC-only** — not an AWS path. See §2.1 |
| "`POST /v1/{org}/cloud-accounts` needs its per-provider body documented" | Withdrawn. It is documented, and BYOC is the only provider it accepts |

The reason we got this wrong may be worth more to you than any request in this document:

> **A `401` from your gateway is indistinguishable from a route that does not exist.**
>
> Measured signatures on our credential:
> - **Route does not exist** → `401`, body `{"code":"401","message":"The authorization credentials provided for the request are invalid…"}`, and **no `trace-id` response header**.
> - **Route exists, request invalid** → `400` **with** a `trace-id` header and a field-level message.
> - Some non-routes → a plain `404`, inconsistently.
>
> Because the first case claims an authorization problem, we spent weeks believing our credential
> lacked scopes when we had simply guessed wrong paths — and sent you an incorrect report as a
> result. Returning `404` for unknown routes would prevent this for every integrator.

---

## 4. What we have working today

So you know what we depend on. All verified 2026-09-07 unless noted.

| Capability | Route |
|---|---|
| Inventory catalog | `POST /v2/{org}/inventory/catalog/assets/search` |
| Inventory relationships / traversal | `POST /v2/{org}/inventory/assets/search` |
| Inventory schema | `GET /v2/{org}/inventory/schema`, `…/schema/{label}` |
| Asset detail | `GET /v2/{org}/inventory/catalog/assets/{id}` |
| Configuration findings / frameworks | `GET /v1/{org}/configuration-findings`, `…-frameworks`; `POST /v2/{org}/configurations/{findings,frameworks}/search` |
| Vulnerability findings | `GET /v1/{org}/vulnerability-findings` |
| Threats | `GET /v1/{org}/threat-detections`, `…/threat-policies`, `…/threat-events`; `POST /v2/{org}/threats/{policies,stories,rule-definitions}/search` |
| API security | `POST /v2/{org}/api-security/endpoints/search` |
| SBOM | `GET /v1/{org}/sbom-packages` |
| Shift-left | `POST /v2/{org}/events/shift-left/search`, `GET /v1/{org}/shiftleft/results/{fingerprint}` |
| Workflows, webhooks | `GET /v1/{org}/workflows`; `POST/PATCH/DELETE /v2/{org}/integrations/webhooks` |
| Access management | `POST /v2/{org}/access-management/{scopes,roles,members,groups}/search` |

## 5. Feature parity — secondary to onboarding

### 5.1 Inventory → Graph: return the matched related entity

`POST /v2/{org}/inventory/assets/search` traverses relationships correctly. Nested conditions are
applied to the related entity, nesting works at more than one hop, and results scope with `label`:

```json
{"conditions":[
  {"field":"label","operator":"eq","value":["aws_ec2_instance"]},
  {"field":"security_groups","operator":"exists",
   "conditions":[{"field":"cloud_account_id","operator":"eq","value":["605134457560"]}]}]}
```

**The limitation:** the response returns the matching assets and nothing about the relationship that
matched — no nested values at all. It filters by edge without projecting the edge, so we can ask
which instances sit behind a security group but never which security group each instance matched.
With 621 edge fields over 224 labels and a 100 req/min limit, an accurate adjacency list cannot be
reconstructed.

**Ask:** include the matched related entity's `id`, or accept an `include`/`select` parameter naming
the edge fields to expand. Ids alone are sufficient. This is a response-shape change to a working
route, and it alone would give us Graph parity.

### 5.2 Inventory → Runtime map: a connections endpoint

If runtime traffic is intentionally console-only, a clear "no" lets us stop asking and set customer
expectations. Of the six capabilities on that screen we can source only the node set and part of the
risk ranking; directed edges, egress classification (CDN vs Cloud vs Internet — your enrichment,
not something we can recompute from an IP), the time window and saved views have no API source.

Nothing substitutes: `GET /v1/{org}/threat-events` rejects `category=NETWORK_ACTIVITY` with `400`,
and **0 of 200 sampled events** carry a network category (`API_SECURITY` 104, `CLOUD_TRAIL` 83,
`PROCESS` 8, `CLOUD_LOGS` 4, `FILE_EVENTS` 1).

Aggregated would be fine — an edge list with source, destination, direction, L4, a `time_range`
parameter and a destination `classification`, scoped per account.

### 5.3 Four modules appear not to be API-exposed

Checked against your full published specification (12 APIs, 84 operations) rather than by guessing:
nothing appears for **Identities / CIEM**, **Inventory → Images**, **Data / DSPM** or **AI / AI-SPM**,
and nothing for Code beyond shift-left. Earlier we reported `404`s and you reasonably suggested we
had the wrong paths; we now believe they are genuinely absent. A yes/no plus any roadmap would let
us tell customers something accurate — four of your eight Inventory sub-tabs are affected.

### 5.4 Compliance cannot be scoped per tenant, and has no counts

`cloud-account-id` is accepted and **ignored** on `/v1/{org}/configuration-findings` and
`…-frameworks` — verified by passing a real account and a fabricated `000000000000` and receiving
identical responses and identical scores. As a reseller we can therefore only show an
organization-wide compliance score, never the customer's own. This is close to a P1 for us
commercially, since a per-customer score is what the customer is buying.

`group_by` and `aggregations` are accepted and silently ignored, response metadata carries no total,
and `/configurations/findings/count` does not exist. Measured 2026-08-18: **63,465 FAIL and
501,000+ PASS** findings, and a sample of 3,000 consecutive PASS findings held **330 distinct
(rule, resource) pairs — 9.09× duplication**, because findings are denormalised per framework across
55 enabled frameworks. We were storing half a million rows to recompute a percentage you already
publish as `compliance_status.score`, so we stopped syncing PASS. A count endpoint, or confirmation
that `compliance_status.score` is the intended source, would settle it.

## 6. Data-quality findings

Roughly by how cheap they look to fix.

**6.1 `cloud_resource_id` is inconsistent, including within one endpoint.** Over 2,481
relationship-search assets in one account: full ARN 1,181, bare id 830, composite
`account::region::id` 5, empty 465. The two inventory endpoints disagree for the *same asset type* —
catalog `arn:aws:ec2:…:instance/i-009aa3aa59a60f17f` vs relationship search `i-0613ecd23d0912db7`.
A lookup by the catalog's value against the relationship search returns zero items, silently.

**6.2 The two inventory endpoints return different asset sets.** Same account, same filter:
relationship search 2,481, catalog 1,833, **1,426 ids in common**; both walks completed. Some of the
gap is sub-entities (tags, security-group rules) but 407 assets appearing only in the catalog is
not. Which set is authoritative?

**6.3 Six `uw_risk_network_*` fields are always zero.**
`last_time_communicating_with_internet`, `last_time_being_accessed_from_internet`,
`last_application_layer_internet_ingress_time`, `last_imds_v1_at`, `last_imds_v2_at` and
`last_time_communicating_with_database` are present on ~36 assets and are `0` on every one; the
catalog's equivalent `network_risk` subfields are absent entirely. These look like the most useful
runtime signals reachable through the API and we would like them populated.
`internet_exposure_type` does carry real values (14 of 58 sampled).

**6.4 `uw_protected_by` is always empty** — present on 53 assets, non-empty on 0. The catalog's
`protected_by` does carry values.

**6.5 Five exposure booleans are filterable but never returned.**
`uw_risk_network_has_wide_exposure`, `has_exposing_rule`, `has_ssh_access`, `has_rdp_access`,
`has_webapp_access` each filter correctly to 20 security groups and appear in **0** of the returned
bodies — the same pattern as `has_sensitive_data` / `has_internet_ingress` / `has_vulnerability` on
api-security. The workaround costs an extra full pagination per flag.

**6.6 `GET /v1/{org}/configuration-rules` returns `200` with `[]`** while the console shows roughly
2,226 rules. Entitlement, or defect?

**6.7 Networking foreign keys are inconsistent across labels.** `aws_security_group` and
`aws_elastic_network_interface` return `vpc_id` (the interface also `subnet_id`), but
`aws_ec2_instance`, `aws_application_load_balancer`, `aws_nat_gateway`, `aws_route_table`,
`aws_network_acl` and `aws_ec2_elastic_ip` return no VPC or subnet reference at all. Building a
topology, this left every instance and load balancer unattachable. We recovered instances by joining
them to their network interface, which required deriving the IP from `private_dns_name` because
interfaces expose **no IP field** (0 of 64 and 0 of 574) while instances expose `private_ip`. Load
balancers and NAT gateways remain unplaceable. Exposing `vpc_id`/`subnet_id` consistently would be
a small change with a large effect.

**6.8 `aws_subnet` is not modelled as an asset.** `GET /v2/{org}/inventory/schema/aws_subnet`
returns `404` and a label search returns zero, yet `subnet_id` is referenced by network interfaces.
We synthesise subnet nodes from those references.

**6.9 `threat-events` rejects `category=NETWORK_ACTIVITY`** with
`400 {"code":"400","message":"The request is invalid or improperly formed."}` in both cases, while
`cloud-account-id`, `severity` and `min-first-seen-time` all work. A documented enumeration, or a
message naming the offending parameter, would help.

**6.10 Deep-paging reliability.** Walking `configuration-findings` at `per-page=1000` we see
connection resets and read timeouts from roughly page 120 onward, and occasionally an
**empty-bodied `400`**. Every genuine validation `400` you return carries a message, so we treat a
bodyless one as transient and retry. Please confirm that is correct.

## 7. Commercial

- **Licensing for resale** — when we onboard a customer account, what is consumed, and what are we
  entitled to sell: per account, per seat, per sensor?
- **Brand permission** to display the Upwind wordmark in the XamSecure UI, in writing. It is live in
  our topbar now.
- **Roadmap visibility** on §2.3 and §2.4 specifically, since those govern how fast we can go to
  market.

## 8. Method

All results were obtained with authenticated requests using our client-credentials token
(audience `https://api.upwind.io`, header `User-Agent: XamOps/1.0`) against organization
`org_Zwq0e1xF9Au4AF0Q`, on the dates stated. Counts come from complete cursor walks unless described
as a sample. We can share exact request/response pairs, timestamps and `trace-id` values for any
item here.

We would be glad to act as a design partner on §2.3 and §2.4 — we have a live estate, three
onboarded accounts and a concrete consumer for whatever shape you choose.
