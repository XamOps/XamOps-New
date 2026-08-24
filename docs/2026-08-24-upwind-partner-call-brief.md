# Upwind partner call — brief

**Date prepared:** 2026-08-24
**Org:** `org_Zwq0e1xF9Au4AF0Q` (region `us`)
**Purpose:** what XamSecure is, what we need from Upwind to sell it, in priority order.

Every claim below was verified against the live API or Upwind's own published artifacts on the
date given. Where something is inferred rather than measured, it says so.

---

## 1. What we have built

XamSecure is a white-label security module inside XamOps. Customers never see the Upwind name
except at the infrastructure level (stack names, IAM roles, the sensor script), and now a
"powered by" mark in our topbar. One Xammer Upwind organization serves all customers; isolation
is by `resource.cloud_account_id`.

| Capability | How | State |
|---|---|---|
| Findings sync | Detections every 60s; vulnerabilities, posture, inventory, API endpoints every 6h | Live |
| Real-time detections | Webhook registered to our ingest endpoint (`vendor: custom`, bearer auth) | Live |
| Self-service account onboarding | Pre-filled CloudFormation quick-create URL; the stack mints its own external ID and self-registers | Live, proven end-to-end |
| Self-service host sensor | Your "Connect a host" script rendered server-side | Built, pending first install |
| Disconnect | `DELETE /cloud-credentials/{id}` + local cleanup | Built, not yet exercised |
| Per-customer entitlement | Admin toggle gating who may connect accounts | Live |

### Onboarding flow as implemented

1. Customer picks one of their own AWS accounts in XamOps.
2. XamOps returns a CloudFormation quick-create URL, pre-filled with `OrganizationId`,
   `TrustedAccount`, `UpwindRegion`, and our shared Cloud account `ClientId`/`ClientSecret`.
   The customer types nothing; the credential is injected server-side and never shown to them.
3. The stack's `Custom::UpwindGeneratedToken` mints the external ID; `Custom::UpwindRegisteredAccount`
   registers the account. Neither requires us to call Upwind.
4. XamOps polls `GET /cloud-credentials` and flips the account to Connected.

**Proven:** account `080310056620` was onboarded this way on 2026-08-24 — stack CREATE_COMPLETE,
registration `connected`, scanner enabled, inventory assets flowing.

### Tenancy verification

Sampled live on 2026-08-24: 45 threat detections, 200 vulnerability findings, 200 configuration
findings. **All 445 carry `resource.cloud_account_id`; zero unattributed.** Correctly split
across `605134457560` and `598515435126`. Our queries constrain `cloud_account_id IN (accounts
the client owns)` at a single chokepoint, and an unattributed finding would be invisible to every
tenant rather than visible to the wrong one.

---

## 2. Priority asks

### P1 — blocks selling at scale

**1. Per-tenant credential scoping.**
We resell from one organization, so one shared Cloud account credential and one shared Sensor
credential are placed inside *every* customer's environment. We checked both before accepting
this, by decoding their tokens:

- Cloud account credential (`type=cloud_account`): `create/get/update/delete:credentials` and
  `get:external-ids` on `integration.upwind.io`; **zero permissions** on `api.upwind.io`.
- Sensor credential (`type=agent`): **zero permissions on both hosts**.

So there is no cross-tenant *read* risk, which is why we shipped. The residual risk is integrity:
a customer who extracts the cloud credential could deregister other accounts in our org; one who
extracts the sensor credential could enrol a rogue sensor or ship false telemetry.

**Ask:** credentials scopeable to a single cloud account, or sub-organizations / scopes that let a
reseller isolate tenants. This is the difference between a defensible pilot and a sales motion.

**2. Document the cloud-account onboarding API.**
Our API credential carries `create:byoc-credentials`, `get:byoc-credentials`,
`update:byoc-credentials`, `delete:byoc-credentials`. No documented endpoint serves them. Every
`/cloud-accounts` and `/byoc-credentials` path on `api.upwind.io` returns 404 or 401.

We found the real routes by downloading your onboarding Lambda
(`upwind-serverless-functions-us-east-1/onboarding/1.0.0.zip`, publicly readable) and reading
`upwind_onboarding_lambda.py`:

```
POST   {integration}/v1/organizations/{org}/aws-external-ids
POST   {integration}/v1/organizations/{org}/cloud-credentials
GET    {integration}/v1/organizations/{org}/cloud-credentials
DELETE {integration}/v1/organizations/{org}/cloud-credentials/{id}
```

where `{integration}` is `https://integration.upwind.io`, regionalised to
`.<region>.upwind.` for anything other than `us` — the same rewrite your Lambda applies.

**Ask:** publish these, or tell us the supported equivalent. We are currently building on
undocumented routes discovered by inspection, which is a risk for both sides.

Note for your side: a 401 from the gateway is indistinguishable from "route does not exist". That
cost us weeks — an earlier audit concluded our credential lacked a cloud-account scope, when in
fact the path was simply wrong.

**3. Sensor credential creation API.**
No route exists. `/credentials` and `/cloud-credentials` are the same collection and accept only
the cloud-account shape (`provider` + `spec`); `type`, `kind`, `usedFor` and
`provider.name=sensor` are all ignored. `/sensor-credentials`, `/sensors`,
`/kubernetes-clusters`, `/clusters`, `/hosts`, `/ecs-clusters`, `/cloud-scanners` all 404.

**Ask:** an API to mint Sensor credentials, ideally scoped to one cloud account. Without it,
per-customer sensor credentials require a console visit each, and we are forced to share one.

### P2 — defects and gaps we have worked around

**4. The onboarding template's Delete handler is a no-op.**

```python
def send_delete_response(event, context):
    msg = 'Upwind AWS API call succeeded, Integration deleted successfully'
    cfn_response.send(event, context, cfn_response.SUCCESS, {'Message': msg})
```

That is the entire function. No API call. Deleting the stack removes the IAM role and leaves the
credential registered, so Upwind keeps assuming a role that no longer exists — a permanently
failing connection, not a removal. The log line actively asserts the opposite.

This affects every Upwind customer who deletes an onboarding stack, not only resellers. We work
around it by calling `DELETE /cloud-credentials/{id}` ourselves first.

**5. No aggregation on configuration findings.**
`group_by` and `aggregations` in the v2 search body are accepted and silently ignored. Response
metadata carries `limit` and `next_cursor` but no total. `/configurations/findings/count` 404s.

Measured 2026-08-18 on this org: **63,465 FAIL and 501,000+ PASS** configuration findings. A
sample of 3,000 consecutive PASS findings contained **330 distinct (rule, resource) pairs — 9.09x
duplication**, one pair repeated 61 times, because findings are denormalised per framework and 55
frameworks are enabled. We were storing half a million rows to recompute a percentage you already
publish as `compliance_status.score`, so we stopped syncing PASS.

**Ask:** a count or aggregation endpoint, or confirmation that `compliance_status.score` is the
intended source for pass ratios.

**6. `cloud-account-id` is ignored on compliance routes.**
Verified by passing a real account and a fabricated `000000000000` to
`/configuration-findings` and `/configuration-frameworks` — identical responses, identical score.
So per-framework compliance cannot be scoped to one tenant, and a reseller can only ever show an
org-wide score.

**Ask:** per-account scoping on frameworks and score.

**7. `GET /v1/events` returns 401** with our credential — a scope we do not have. Blocks an events
timeline.

**8. Framework detail routes do not exist.** `/v2/{org}/configurations/frameworks/{id}`, plus
`/controls` and `/summary` under it, all 401 at the gateway. Per-control detail therefore comes
from walking `configurations/findings/search` per framework.

### P3

**9. Deep-paging reliability.** Walking `configuration-findings` at `per-page=1000`, we see
connection resets and read timeouts from roughly page 120 onward, and occasionally an
**empty-bodied HTTP 400**. Every genuine validation 400 you return carries a message
("conditions: must not be empty"), so we now treat a bodyless 400 as transient and retry it. Worth
confirming that is correct.

**10. Kubernetes and ECS sensor install commands via API.** We have only the host script.

**11. Identities / CIEM.** No reachable route; we derive identities from configuration findings on
identity resource types instead.

---

## 3. Commercial

- **Brand permission** to display the Upwind wordmark in the XamSecure UI, in writing. It is
  live in our topbar now.
- **Licensing for resale**: when we onboard a customer account, what is consumed, and what are we
  entitled to sell — per account, per seat, per sensor?
- **Escalation path** for a failed customer onboarding. Customers never see Upwind, so we are the
  only ones who can raise it, and we need a route that does not go through a generic queue.
- **Roadmap visibility** on P1.1 specifically, since it governs how fast we can go to market.

---

## 4. Open on our side (not asks)

- First host sensor install not yet run; disconnect not yet exercised against a real registration.
- Kubernetes/ECS sensor flows not built.
- GCP and Azure self-service onboarding not built — AWS only.
