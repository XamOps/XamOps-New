# Upwind API access needed for UI parity — Inventory graph and the rest

**Date:** 2026-09-07
**Org:** `org_Zwq0e1xF9Au4AF0Q` (region `us`)
**Supersedes on two points:** `docs/upwind-api-access-gaps.md` §2 and §8.2 — see §5 below.

**Purpose.** Separate three things that have been conflated in our previous asks:

1. What we **mis-tested** — a documented route exists and we called the wrong path or the wrong
   body. No ask required; we retest.
2. What exists but our credential is **not authorized** for. An access ask.
3. What **does not exist in Upwind's API at any path** and that we cannot derive from anything
   they do expose. A product ask — this is the only category we genuinely "can't develop".

**Evidence base.** Upwind's own published OpenAPI operation definitions for all 12 of their APIs
(84 operations), from
[the API Evangelist mirror of docs.upwind.io](https://apis.io/providers/upwind/), whose spec
header states the operation content is taken verbatim from the provider's published docs
(generated 2026-07-21; source `docs.upwind.io/restapi/v1/introduction`). Content was rephrased for
compliance with licensing restrictions.

> **Live-verified 2026-09-07** against `org_Zwq0e1xF9Au4AF0Q` with our production API credential,
> accounts `605134457560` / `598515435126`. Status codes and payloads in §4 are measured, not
> inferred. Reproduction scripts: `.tmp-as/probe-graph.py`, `probe-graph2.py`, `probe-graph3.py`.

**Diagnostic calibration established by this run.** Three distinct failure signatures, which is what
made the earlier audit ambiguous:

| Signature | Meaning | Example |
|---|---|---|
| `401`, **no** `trace-id` header | rejected at the gateway — **the route does not exist** | `/v2/{org}/inventory/connections`, `/v2/{org}/access-management/api-clients` |
| `400` **with** `trace-id` and a field-level message | route exists, our request was wrong | `POST /v1/{org}/cloud-accounts` → `{"provider":"must not be null",…}` |
| `404` | route does not exist, stated plainly | `GET /v1/events` |

A 401 without `trace-id` is therefore **not** an authorization problem and asking Upwind to grant a
scope for one is asking for the wrong thing.

---

## 1. Answer in one table — the Inventory tab, sub-tab by sub-tab

The console's Inventory has eight sub-tabs (Runtime map, Catalog, Graph, Orgs & accounts,
Identities, Images, APIs, SBOM explorer). Where each one stands:

| Sub-tab | Can we build it? | Category |
|---|---|---|
| **Runtime map** | **No** — no endpoint returns network connections. | 3 · product ask |
| Catalog | Yes, built. `POST /v2/{org}/inventory/catalog/assets/search`. | done |
| **Graph** (query-driven asset map) | **Yes — verified working, and we wrongly concluded otherwise.** `POST /v2/{org}/inventory/assets/search` does relationship traversal with nested conditions (200, label-scoped, multi-hop). `GET /v2/{org}/inventory/schema` returns the edge catalogue: 320 labels, 224 with edge fields, 621 edge fields, 165 related types. Neither route is called anywhere in our code. One gap remains: the response returns matching **nodes, never the edge** (§2). | 1 · we were wrong |
| Orgs & accounts | Partly. `POST /v1/{org}/cloud-accounts` **exists and is reachable** (400 with field validation). No list route: `GET` → 401-no-trace-id. | 1 + 2 |
| Identities | No. Absent from all 84 operations. | 3 · product ask |
| Images | No. Absent from all 84 operations. | 3 · product ask |
| APIs | Yes, built (v2 api-security search). Missing OWASP/CWE per endpoint. | done / 3 |
| SBOM explorer | Yes, built (`/v1/{org}/sbom-packages`). | done |

So of the eight, one is a genuine product gap (Runtime map), two are absent modules
(Identities, Images), one we have been wrong about (Graph), and four are built.

---

## 2. The network graph: what the Runtime map is actually made of

From the console at
`console.upwind.io/inventory?mainPageTab=Runtime%20map&mapTimeType=last24Hours&excludeFilters=resources=EXPR-*coredns*`,
one screen carries six distinct capabilities:

| # | Capability | Evidence in the UI | Source of truth |
|---|---|---|---|
| 1 | **Node set** | AWS hub + grouped resource nodes | asset inventory (we have this) |
| 2 | **Directed edges with direction semantics** | `Internet Ingress` → `AWS` → `CDN Egress` / `Cloud Egress` / `Internet Egress` | eBPF sensor connection telemetry |
| 3 | **Egress classification** | CDN vs Cloud vs Internet egress are three separate nodes | Upwind-side enrichment of destination IPs |
| 4 | **Time window** | `Timeline: Last 24 hours`, `mapTimeType=last24Hours` | time-bucketed connection store |
| 5 | **Server-side risk ranking** | `Riskiest resources`, `Active internet ingress` toggles | Upwind scoring |
| 6 | **Saved views + exclude filters** | `Save view`, `excludeFilters=resources=EXPR-*coredns*` | view persistence |

### What we can get today (node attributes, not edges)

The relationship-search response schema carries Upwind's own network verdicts **per asset**:

```
uw_risk_network_internet_exposure_type
uw_risk_network_has_wide_exposure
uw_risk_network_has_exposing_rule
uw_risk_network_has_ssh_access
uw_risk_network_has_rdp_access
uw_risk_network_has_webapp_access
uw_risk_network_last_time_being_accessed_from_internet
uw_risk_network_last_time_being_accessed_from_internet_in_ssh_or_rdp
uw_risk_network_last_application_layer_internet_ingress_time
uw_risk_network_last_time_communicating_with_internet
uw_risk_network_last_time_communicating_with_database
uw_risk_network_last_imds_v1_at  /  uw_risk_network_last_imds_v2_at
uw_protected_by
uw_risk_detections_severity_count_total
uw_risk_vulnerabilities_severity_count_total
```

That is enough for capability 1 and most of 5, and it means an "Active internet ingress" filter is
buildable. It is **not** enough for 2, 3, 4 or 6, because every one of these is a property of a
node, not of a connection: `last_time_communicating_with_internet` tells us an asset talked to the
internet, never which asset, over what port, in which direction, or how much.

### Edges: what exists and what does not

| Edge kind | Available? | How |
|---|---|---|
| **Scalar foreign keys on the node** (`vpc_id`, `subnet_id`) | **Yes, free** | Verified: an `aws_elastic_network_interface` comes back carrying `subnet_id` and `vpc_id`; `aws_security_group` carries `vpc_id`. These are edges with no traversal needed, and they are enough for a VPC → subnet → ENI → instance topology. |
| **Traversable config relationships** (instance→security group, ALB→listener, ASG→instances) | **Yes as a filter, no as an edge** | Verified working (§4.1): relationship traversal returns the right node set. But the response carries **no nested values at all** — it tells you *which nodes match*, never which related entity each one matched. |
| **Runtime connection edges** (L3/L4/L7, direction, ports, protocol, volume, process) | **No endpoint** | Six candidate paths probed, all 401-no-trace-id, the same signature as routes that provably do not exist. |
| **Partial runtime edges from detections** | **No** | Tested: `GET /v1/{org}/threat-events` works (200), but `category=NETWORK_ACTIVITY` is rejected with 400 and **zero of 200 sampled events** carry a network category. Actual categories: `API_SECURITY` 104, `CLOUD_TRAIL` 83, `PROCESS` 8, `CLOUD_LOGS` 4, `FILE_EVENTS` 1. The `source_ips` / `domain` / process-tree fields in the published schema do not appear in our data. This is a security-event feed, not a traffic source. |

**The one thing standing between us and the Graph tab.** The traversal filters correctly but does
not project the edge. With 621 edge fields over 224 labels and a documented 100 req/min limit, and
no edge identity in any response, an accurate estate-wide adjacency list cannot be reconstructed —
you would be inferring edges from the question you asked rather than reading them. Fine for a
drill-down, not for a map.

So the graph ask is much smaller than we thought: **return the matched related entity's `id` in the
relationship-search response** (or accept a `select`/`include` parameter naming the edge fields to
expand). That single change turns a filter into a graph API.

### The contract we need (capability 2–4)

Concretely, what would let us replicate the Runtime map:

```
POST /v2/organizations/{org}/inventory/connections/search
  ?limit=&cursor=
{
  "time_range": { "from": "2026-09-06T00:00Z", "to": "2026-09-07T00:00Z" },
  "conditions": [ { "field": "cloud_account_id", "operator": "eq", "value": ["605134457560"] } ]
}
→ { "items": [ {
      "source":      { "asset_id": "...", "label": "aws_ec2_instance", "kind": "asset" },
      "destination": { "kind": "internet_egress", "classification": "cdn",
                       "domain": "...", "ip": "..." },
      "direction":   "egress",
      "l4":  { "protocol": "tcp", "port": 443 },
      "l7":  { "protocol": "http", "endpoint_id": "ep-..." },
      "observed": { "first_seen": "...", "last_seen": "...",
                    "bytes_in": 0, "bytes_out": 0, "connection_count": 0 },
      "process": { "name": "...", "pid": 0 }
    } ], "metadata": { "next_cursor": "..." } }
```

Three properties matter more than the exact shape:

- **an edge list, not node attributes** — source, destination, direction, one row per observed pair;
- **a time range parameter**, so the timeline control is reproducible;
- **destination classification** (`internet` / `cdn` / `cloud_service` / `internal`), because that
  is Upwind-side enrichment we cannot recompute from an IP.

Aggregated would be fine — we do not need raw flows. Per-account scoping is required, since we
resell from one organization.

---

## 3. Same question for the rest of the console

Blocked features and what each needs, in the same three categories.

| Console feature | What is missing | Category |
|---|---|---|
| Inventory → Runtime map | connection/edge endpoint + time range (§2) | 3 |
| Identities / CIEM | any identity route. Absent from all 84 operations, so this is not a wrong-path problem | 3 |
| Inventory → Images | any image route. Same | 3 |
| Data / DSPM (datastore detail) | dedicated data routes. We show per-asset `sensitive_data_at_rest/in_transit` only | 3 |
| AI / AI-SPM | any AI route | 3 |
| Code / IaC / SAST | listing beyond `events/shift-left/search` + per-fingerprint results | 3 |
| APIs → OWASP/CWE panel | per-endpoint vulnerability class fields | 3 |
| Configurations → Rules | `GET /v1/{org}/configuration-rules` **is** documented; ours returns an empty array against ~2,226 in console | 2 |
| Compliance per tenant | `cloud-account-id` is accepted and ignored on `configuration-findings` / `-frameworks` | 3 |
| Any total or count | `group_by`/`aggregations` accepted and ignored; no count route. 63,465 FAIL / 501,000+ PASS measured 2026-08-18 | 3 |
| Per-tenant credential isolation | **verified**: scopes already filter on `cloud_account_id` and members hold per-scope roles; only a *credential*-to-scope binding is missing (§4.4) | 3, but small |
| Cloud-account list | **verified**: `POST` create exists (§4.3); `GET` list does not | 3 |
| Sensor credential creation | no route | 3 |
| K8s / ECS sensor install artifacts | no route | 3 |

---

## 4. Live results — 2026-09-07

### 4.1 Asset relationships / the Graph tab — WORKS

Every one of these returned `200` on our existing credential. Called nowhere in our code today.

| Request | Result |
|---|---|
| `GET /v2/{org}/inventory/schema?limit=100` (4 pages) | 200 · **320 labels**, 224 declaring edge fields, **621 edge fields**, 165 distinct related types |
| `GET /v2/{org}/inventory/schema/aws_ec2_instance` | 200 · field list with types |
| `POST /v2/{org}/inventory/assets/search` flat baseline | 200 |
| `label eq aws_ec2_instance` | 200 · returns **only** `aws_ec2_instance` — the traversal can be node-type-scoped |
| `asset_sub_category eq virtual_machine` | 200 · same |
| nested: `security_groups exists { cloud_account_id eq … }` | 200 · items |
| label-scoped + nested (EC2 behind a SG in our account) | 200 · all returned items are `aws_ec2_instance` |
| 2-hop: `instances exists { security_groups exists { cloud_account_id eq … } }` | 200 · **multi-hop nesting accepted** |
| `uw_risk_network_*` as filter fields | 200 · e.g. `internet_exposure_type: "none"` returned inline on `aws_s3_bucket` |

The working shape (note `operator` is mandatory on the outer condition — omitting it is a 400):

```json
{"conditions":[
  {"field":"label","operator":"eq","value":["aws_ec2_instance"]},
  {"field":"security_groups","operator":"exists",
   "conditions":[{"field":"cloud_account_id","operator":"eq","value":["605134457560"]}]}]}
```

Sample edge catalogue, live from our org:

```
aws_application_load_balancer:  listeners->aws_load_balancer_listener, security_groups->aws_security_group, tags->tag
aws_auto_scaling_group:         instances->aws_ec2_instance, cves->cve, technologies->technology,
                                sensitive_data_types->sensitive_data_type,
                                exposure_properties->internet_exposure_properties
```

**Correctness check that this is real filtering, not a no-op:** `security_groups` and `instances`
traversals returned items while `tags` and `listeners` returned **0** — because `tag` and
`aws_load_balancer_listener` have no `cloud_account_id` field for the nested condition to match.
The API is evaluating the nested predicate against the *related* entity, exactly as intended.

**Two limitations found.**

1. **No edge in the response.** The returned item has zero nested values — verified by dumping a
   full payload. You learn which nodes match, never which related entity matched. See §2.
2. **`cloud_resource_id` is not consistent between the two inventory endpoints.** The catalog
   search returns a full ARN for an EC2 instance
   (`arn:aws:ec2:…:instance/i-009aa3aa59a60f17f`); the relationship search returns the bare id
   (`i-0613ecd23d0912db7`) for the same asset type. A lookup by the catalog's value returns 0 items.
   Anything joining the two must normalise.

### 4.2 Runtime events — route works, but the data is not there

`GET /v1/{org}/threat-events` → **200**. This corrects our recorded 401, which was against
`/v1/events` (**404**) and `/v2/{org}/events` (**401, no trace-id** — not a route).

Parameters measured: `cloud-account-id` → 200 and **honoured**; `severity` → 200;
`min-first-seen-time` → 200; `category=NETWORK_ACTIVITY` and `network_activity` → **400** both ways.

Over a 200-event sample: categories are `API_SECURITY` 104, `CLOUD_TRAIL` 83, `PROCESS` 8,
`CLOUD_LOGS` 4, `FILE_EVENTS` 1 — **no network category exists**. Event payload is nine fields
(`id, type, category, severity, status, title, first_seen_time, last_seen_time, resource`); the
`source_ips` / `domain` / `bad_reputation_ips` / process-tree fields in the published schema are
absent from our data. Both accounts are represented in `resource.cloud_account_id`.

So this does **not** help the Runtime map. It is, however, a per-account-filterable event feed
sitting underneath the detections we already sync, which is the depth our Threats
noise-to-signal funnel was missing. New capability, unrelated to the graph.

### 4.3 Cloud-account creation — the route exists

`POST /v1/{org}/cloud-accounts` with an empty body → **400 with `trace-id`** and field-level
validation: `{"provider":"must not be null","name":"must not be null","config":"must not be null"}`.
Nothing was created. The route is real and our credential reaches it — consistent with the
`create:byoc-credentials` permission on our token that we could never find a route for.

`GET /v1/{org}/cloud-accounts` → **401, no trace-id**. There is no list route, which is what our
onboarding poller needs, so we keep using `GET {integration}/v1/{org}/cloud-credentials`.

### 4.4 Scopes — per-tenant isolation already exists for users, just not for credentials

`POST /v2/{org}/access-management/scopes/search` → **200, 5 scopes**, and they are already
per-tenant:

```
all                    Global Scope   filters=[]
uams-c1b8686518b31054  OnFinance      [{"attribute":"cloud_account_id","values":["598515435126"],"operator":"in"}]
uams-96cee995a3ae6c2a  Xammer         [{"attribute":"cloud_account_id","values":["605134457560"],"operator":"in"}]
uams-a2e91517e8f63c85  GSA Test       [{"attribute":"cloud_account_id","values":["080310056620"],"operator":"in"}]
uams-6d3b7e2ce9dc6ad6  GSa            [{"attribute":"cloud_account_id","values":["080310056620"],"operator":"in"}]
```

`members/search` → 200; each member carries
`permissions: [{scope_id, scope_name, role_ids, source}]`. `roles/search` → 200, 20+ feature roles.
So **scope-bound authorization by `cloud_account_id` is a shipped, working mechanism — for human
members.** What does not exist: any route to bind an *API credential* to a scope.
`access-management/credentials`, `api-clients`, `service-accounts`, `api-keys` all → 401-no-trace-id.

This reframes P1.1 entirely. It is no longer "please build per-tenant credentials", it is "extend
the scope binding you already have for members to API credentials" — the resource filter, the
data model and the enforcement all exist.

### 4.5 Configuration rules — confirmed empty

`GET /v1/{org}/configuration-rules?per-page=5` → **200 `[]`**. Route documented, reachable, returns
nothing against ~2,226 in the console. Entitlement or data question, not a path question.

### 4.6 Routes we never wired — all reachable

`GET /v2/{org}/threats/stories?limit=2` → 200 (plain list alongside the search we use).
`GET /v2/{org}/configurations/asset-examples/aws_s3_bucket` → 200, with provider-native `api_data`.
`POST /v2/{org}/threats/rule-definitions/search` → 200, rule definitions with `engine: "rego"`,
`threat_category`, detection metadata — the rule corpus behind the policies.

### 4.7 The Runtime map paths — confirmed non-existent

| Path | Result |
|---|---|
| `GET /v2/{org}/inventory/topology` | 401, no trace-id |
| `GET /v2/{org}/inventory/connections` | 401, no trace-id |
| `GET /v2/{org}/inventory/graph` | 401, no trace-id |
| `GET /v2/{org}/inventory/runtime` | 401, no trace-id |
| `POST /v2/{org}/inventory/connections/search` | 401, no trace-id |
| `POST /v2/{org}/inventory/topology/search` | 401, no trace-id |

Same signature as `access-management/api-clients`, which certainly does not exist, and unlike every
real route on this credential. Combined with their absence from all 84 published operations: there
is no connections API. §2's contract stands as a product request.

---

## 5. Corrections to `docs/upwind-api-access-gaps.md`

Three claims in that document are wrong, now measured rather than argued:

- **§8.2 "Topology / graph / connections. No endpoint."** Half right. No connections endpoint, and
  the Runtime map remains unreproducible — but relationship traversal and the edge-catalogue schema
  route both work on our current credential (§4.1). "No graph capability" is wrong.
- **§8.2 "there is no runtime event search."** `GET /v1/{org}/threat-events` returns 200 (§4.2). It
  carries no network activity, so the conclusion about the Runtime map survives; the claim about the
  route does not.
- **§5 "Create a cloud-account connection → 404."** `POST /v1/{org}/cloud-accounts` exists and is
  reachable (§4.3).

**§2's five 401s were all non-routes, not scope denials.** Four (`inventory/topology`,
`connections`, `graph`, `runtime`) plus `events`, every one returning 401 **without** a `trace-id`
header — the signature we have now calibrated against routes that provably do not exist. That
document already suspected this in §7.1; it is now established. Every "please authorize this scope"
ask attached to those five should be withdrawn: there is nothing to authorize.

---

## 6. What to actually ask Upwind

Reordered after the live run. Two items got dramatically smaller, one got bigger.

1. **Return the matched related entity in the relationship-search response** — an `id` per matched
   edge field, or a `select`/`include` parameter to expand them (§2). This is now the *only* thing
   between us and Inventory → Graph, and it is a response-shape change to a route that already
   works, not a new capability. Highest value per unit of their effort.
2. **A connections/flows endpoint with a time range and destination classification** (§2). Still the
   only way to reproduce Inventory → Runtime map. Aggregated is fine; per-account scoping required.
3. **Bind an API credential to an existing scope** (§4.4). Scopes already filter on
   `cloud_account_id` and members already hold roles within a scope — we need the same for machine
   credentials. This was P1.1 as "build per-tenant isolation"; it is actually "expose a binding for
   a mechanism you have already shipped".
4. **Confirm Identities/CIEM, Images, Data and AI are not API-exposed.** Absent from all 84
   published operations. We want a yes/no and a roadmap, not more path guessing.
5. **Per-account scoping on compliance routes, and a count/aggregation route** (§3). Without these a
   reseller can only ever show an org-wide score, and we store half a million rows to recompute a
   percentage they already publish.
6. **A list route for cloud accounts** (create/patch/delete exist, list does not), and publication of
   the four `integration.upwind.io` onboarding routes we depend on undocumented.
7. **Normalise `cloud_resource_id`** between the catalog and relationship searches, or document the
   difference (§4.1).
8. **Per-endpoint OWASP/CWE** on api-security, and whatever should populate `configuration-rules`.

## 7. What we built on this (2026-09-07)

Shipped in the same pass, all verified against both accounts:

- **`UpwindAssetIds`** — one chokepoint reconciling the three `cloud_resource_id` forms, the
  `aws`/`AWS` provider casing, and `resource_type` vs `label`. Deliberately does *not* unify
  `status`: the catalog's `ACTIVE` is Upwind's sync lifecycle and the relationship search's
  `running` is provider state, so mapping them would invent a fact.
- **`UpwindApiClient.fetchInventorySchema` / `edgesFor` / `searchAssetGraph` / `searchAssetsByLabel`**
  — the edge catalogue (12h cached) and traversal.
- **A real configuration topology** behind `GET /api/xamsecure/inventory/network`, replacing counts
  per sub-category. Measured over both accounts: **1,384 nodes, 1,853 edges, 97% connected**,
  47s uncached, Redis-cached 30 minutes.
- **`getAssetRelationships` enriched** with the declared related label per relationship, with the
  id-shape regex kept as the extraction mechanism and the fallback.
- **`NetworkGraph.tsx`** — VPC → subnet → resource, hierarchical rather than node-link, because
  1,384 nodes with one VPC holding 446 children is a hairball as a spring layout. No new dependency.

Two things that only became clear by building it:

1. **A foreign-key-only graph is useless.** Instances and load balancers carry no `vpc_id` or
   `subnet_id`, so the first version left **100% of instances orphaned** — a picture of plumbing
   with nothing running in it. The fix was joining instances and elastic IPs to their network
   interface, which does carry subnet and VPC. Interfaces expose only `private_dns_name` and no IP
   at all, so the address is recovered from the hostname. Match rate 19/22 and 131/132.
2. **`aws_route_table` and `aws_network_acl` were dropped from the graph.** Neither has a foreign
   key, an address, or a declared edge, so all 93 would be permanently unconnected dots.

Still outstanding from this list, not yet built: a `threat-events` feed for Threats (§4.2),
programmatic onboarding via `POST /v1/{org}/cloud-accounts` (§4.3), and per-tenant scopes for
console users (§4.4).

**Withdrawn.** An earlier draft of this section claimed the `uw_risk_network_*` timestamps were a
runtime signal the catalog lacked, and proposed syncing them. They are present on ~36 assets and
**zero on every one** — the earlier probe checked field presence, not value. The `V20260811_1`
migration's decision to skip the equivalent columns was correct. Only `internet_exposure_type`
carries values (14 of 58), and it was already stored.

## 8. Verification status

| Claim | How established |
|---|---|
| 84 documented operations and their paths | Upwind's published OpenAPI via the API Evangelist mirror, 2026-09-07 |
| Everything in §4 — status codes, payloads, counts, filter behaviour | **live, 2026-09-07**, org `org_Zwq0e1xF9Au4AF0Q`, accounts `605134457560` / `598515435126`. Scripts in `.tmp-as/probe-graph*.py` |
| 320 labels / 224 with edges / 621 edge fields | live, full 4-page walk of `inventory/schema` |
| No connections/topology route | live 401-no-trace-id on 6 candidate paths + absence from all 12 published specs |
| Runtime map needs sensor telemetry we cannot derive | inference from the above plus the console's own behaviour |
| Measured finding volumes, ignored `cloud-account-id` on compliance routes | earlier live tests in `upwind-api-access-gaps.md` and the partner brief |
| **Whether item 1 or 2 in §6 is on Upwind's roadmap** | **unknown — needs the partner call** |
