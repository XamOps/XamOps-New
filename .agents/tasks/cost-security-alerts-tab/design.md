# Cost "Security & Alerts" tab — Requirements & Design

> **Revision 3.** Rewritten after `design-review.md` / `design-review.json` (CHANGES_REQUESTED:
> 2 HIGH, 1 MEDIUM, 2 NIT). Every finding is addressed; point-by-point responses are at the end under
> **"Responses to design review"**. The two load-bearing changes from Revision 2:
>
> 1. **The design is now self-contained against the THREE-tab worktree that actually exists** (review
>    finding 1, option **b**). Revision 2 was written against a four-tab "Connectivity" baseline that is
>    **not in this worktree** and whose landing was an unguaranteed external rebase. That is not an
>    implementable foundation, so this revision drops every reference to `connectivity` /
>    `ResourceConnectivityTab` / `everConnectivity` as pre-existing symbols and instead **introduces the
>    new tab, its lazy-mount latch, and its null-omit fetcher prop from scratch** against the real
>    drawer. The feature no longer depends on anything outside this worktree.
> 2. **The per-product account-id form is now a per-product table, not a blanket invariant** (review
>    finding 2), and **Alerts/Security-Center dedup is built from the shared `CloudSecurityFinding`
>    entities, not from `AlertDto` or the private `dedupKey`** (review finding 3). Both are corrected
>    with re-verified evidence below.
>
> All file/line evidence was re-verified against the worktree `/.worktrees/cost-security-alerts-tab`
> at HEAD `294d871fe8b29a73765a2cbee438265ec58d0023` (`base-branch.txt` = `main`; the only untracked
> path is `.agents/`). This is a design document only — no code is changed by it.

## Summary

Add a new tab, **Security & Alerts**, to the per-resource cost detail drawer on the AWS Cost Management
page (`/cost`). It appears as the tab **after the existing tabs** in the strip (the brief says "after
Connectivity"; Connectivity does not exist in this worktree, so in practice it becomes the **fourth**
tab after `Change · Breakdown · Trend` — see finding 1). When a reader opens a resource row and selects
the tab, it shows that resource's findings pulled from XamOps' security / compliance / performance /
alerts products, grouped by product, most severe first. Each finding row is keyboard-accessible and
navigates — via react-router, no full reload — to the product page it came from, deep-linked to the
resource where a safe, existing client-side field lets that page focus it; otherwise to the page.

The tab is **AWS-only to start**. It is wired through the Cost component chain with a nullable
fetchers-bundle prop (`security?: SecurityFetchers | null`) that defaults to the AWS bundle and that the
GCP/Azure cost pages pass `null` to omit — the exact "null means omit this provider-specific tab"
convention the brief describes for Connectivity, implemented here from scratch. The tab is **lazily
mounted on first selection** and **keyed on `resourceId`** so opening a different resource remounts it.
It uses a plain `useQuery` for its one resource-scoped fetch, `SecuritySeverity` for severity
normalisation, and the worktree's `ResourceIdResolver` for CUR-id↔product-id reconciliation. It calls
**one new normalised backend aggregation endpoint** rather than fanning out to several services from the
browser.

This document names the exact files, endpoints, params, and edge cases to implement, and resolves the
three required ambiguities (a)(b)(c) with code evidence.

---

## Requirements

### Functional

1. A new tab labelled **"Security & Alerts"** appears in the resource cost drawer's tab strip,
   positioned **last** (after `Change · Breakdown · Trend`; i.e. where the brief says "after
   Connectivity", Connectivity being absent in this worktree — finding 1).
2. The tab is present only when the drawer is given a non-null `security` fetchers bundle. The AWS cost
   page supplies it via the default `awsSecurity` bundle; `GcpCostPage.tsx` and `AzureCostPage.tsx` pass
   `security={null}` and the tab is absent.
3. On first selection (not before) the tab fetches that resource's findings for the opened `resourceId`
   + `accountId` (+ optional `region`), aggregated across the products below.
4. Findings are grouped under labelled product sections, each row showing a human title and a severity
   badge, sorted most-severe-first within each group; groups are ordered by the product order below.
5. The eight products from the brief are each addressed in "Ambiguity resolution" and "Product
   coverage": they resolve into **four** that produce per-resource rows and **four** that are excluded
   with reasons recorded (not silently dropped).
6. Clicking (or pressing Enter/Space on) a finding row navigates via react-router to that product's
   page, deep-linked to the resource via a `?resource=<id>` param **only** where that destination page
   has an existing client-side field it can filter on; otherwise it navigates to the page with no param.
7. A product that returns no findings for the resource is omitted from the tab (no empty section).
8. A product whose read fails is reported as a per-product error row and does **not** fail the whole tab.
9. The tab has whole-tab loading, empty ("no findings for this resource"), and error states.
10. The existing three tabs, their fetch, the scroll lock, Escape-to-close, and the "no detail
    available" state in `ResourceCostDrawer.tsx` are unchanged.

### Non-functional

11. No new state-management or data-fetching library. Reuse `@tanstack/react-query` via `useQuery`,
    `apiFetch` (`src/utils/apiClient`), `readShape`/`isRecordWith` (`src/api/curInsightsApi`), Tailwind
    inline classes, `@iconify/react`.
12. The tab issues **one** HTTP request to one aggregation endpoint.
13. The aggregation endpoint reuses each product's existing data source as the source of truth; it adds
    **no new scan or ingest path**. It uses each product's standard cached read exactly as the sibling
    pages do. A cold-cache refresh *inside* an existing read is possible and acceptable; the Performance
    branch time-boxes that read inside its own try/catch so a slow cold scan degrades to an `errors[]`
    entry rather than hanging the tab (finding 5).
14. Severity uses the existing `SecuritySeverity` scale (`CRITICAL / HIGH / MEDIUM / LOW /
    INFORMATIONAL`), normalised server-side so the frontend never sees a product's native vocabulary.
15. **Tenant isolation & per-product account-id form (finding 2).** The endpoint resolves the account
    once, exactly as the sibling cur-insights endpoints do (`cloudAccountRepository.findByAwsAccountId`
    → `CloudAccount`, then `denyIfNotEntitled`), **before** any product read. Each product read is then
    scoped to the resolved account **in the identifier form that read expects** — they are not the same
    form (verified below). Isolation holds because the account is resolved once and entitlement-checked
    before any read; the per-read form is:

    | Product | Read | Account-id form passed | Evidence (worktree) |
    |---------|------|------------------------|---------------------|
    | Security Center | `findByResource(id, arn)` | `account.getId()` — **internal `Long`** | `CloudSecurityFindingRepository.java:89,91` (`WHERE f.cloudAccountId = :cloudAccountId AND f.resourceArn = :arn`), param `Long cloudAccountId :91` |
    | Alerts (derived) | same `findByResource` rows, severity-filtered | `account.getId()` — **internal `Long`** | derived from the Security Center rows; see Product coverage item 7 |
    | ComplianceOps | `getReport(accountId, trendDays)` | `String.valueOf(account.getId())` — **internal id as `String`** | `ComplianceOpsService.getReport(String,int):77` passes `accountId` straight to `findFirstByCloudAccountIdAndStatusOrderByStartedAtDesc(accountId, …):82` and `ownerRepo.findByCloudAccountId(accountId):127` |
    | Performance | `getInsights(accountId, null, false)` | `account.getAwsAccountId()` — **12-digit AWS id `String`** | `PerformanceInsightsService.getInsights(String,…):78` → `getAccount(accountId):101` → `cloudAccountRepository.findByAwsAccountId(accountId):71`, throws `RuntimeException("Account not found")` on empty `:73` |

    `CloudAccount` exposes both forms: `getId()` (`domain/CloudAccount.java:20` field, Lombok/explicit
    getter) and `getAwsAccountId()` (`:225`). The aggregator therefore holds the single resolved
    `CloudAccount` and passes `account.getId()`, `String.valueOf(account.getId())`, or
    `account.getAwsAccountId()` per the table — **never one blanket form.**

### Acceptance criteria

1. Opening any AWS resource row's drawer shows the tab strip ending in **Security & Alerts**:
   `Change · Breakdown(n) · Trend · Security & Alerts`.
2. Opening a GCP (`/gcp-cost`) or Azure (`/azure-cost`) resource drawer shows **no** Security & Alerts
   tab, confirming the `security={null}` omit path.
3. Selecting the tab for a resource with findings renders one section per product that has ≥1 finding,
   each section's rows ordered CRITICAL→INFORMATIONAL, and no section for products with 0 findings.
4. The tab is not fetched until first selected: no network call to the aggregation endpoint fires while
   the tab is unselected (the `useQuery` `enabled` is `active && Boolean(accountId) &&
   Boolean(resourceId)`, and the body is not mounted until the latch flips on first selection).
5. Switching to another tab and back does not refetch (query cache hit); opening a *different* resource
   does refetch (the component is keyed on `resourceId`, and the `useQuery` key includes `resourceId`).
6. Clicking a Security Center finding navigates (SPA, no reload) to `/security?resource=<arn>`, and
   `SecurityPage` filters its findings list to rows whose `finding.resourceId === resource`
   (`types/security.ts:8`).
7. Clicking a Performance finding navigates to `/performance?resource=<id>` and the page filters
   insights to `insight.resourceId === resource` (`types/performance.ts:21`).
8. Clicking a ComplianceOps finding navigates to `/complianceops?resource=<uid>` and the page
   expands/scrolls to controls whose `resources[].uid === resource` (`types/compliance.ts:77`).
9. The Alerts group (if present) carries **no resource deep-link**: its rows navigate to `/alerts` with
   no query param, because the client `Alert` union carries no resource identifier to filter on
   (`types/alerts.ts` — `AlertBase` has only `id/service/name/description/region/severity`). (In
   practice the Alerts group is suppressed as a duplicate of Security Center — see item 7 of Product
   coverage — so this criterion only bites if a reviewer later re-enables a standalone Alerts group.)
10. WAR, XamSecure, SOC2 produce **no rows** in v1 (reasoned exclusions). If a future revision adds an
    account-level WAR row it navigates to `/war`; a SOC2 row's known-good landing is
    `/soc2-program/overview` (NOT `/soc2-program/cloud`, which is not a valid tab value).
11. If one product's branch in the aggregator throws, its section renders an inline "couldn't load" row
    and every other product's findings still render.
12. A resource with zero findings anywhere shows the tab's empty state, not an error and not a blank
    panel.
13. The new backend endpoint has a controller test under
    `xamops-service/src/test/java/com/xammer/cloud/controller/` asserting: 200 with a normalised body
    for a known resource; empty `groups`+`errors` for an unknown resource; 404 for an unentitled/unknown
    account; 400 for a blank `resourceId`; one failing product does not 500 the response.
14. `npm run build` / typecheck and the Vitest suite for `components/Cost` pass; `mvn -pl xamops-service
    test` passes including the new controller test.

### Out of scope

- GCP and Azure security findings in the tab (AWS-only in v1; the GCP/Azure cost pages pass
  `security={null}`).
- Any new scanning, ingest, or finding-detection logic. The tab only reads existing data.
- Changing finding data models, severity semantics, or the destination pages' core behaviour. The only
  destination changes are additive, read-only `?resource=` query-param handlers on three pages
  (`/security`, `/performance`, `/complianceops`) whose client types already carry the matching field.
- Extending the Alerts projection or client `Alert` type to carry a resource identifier (the
  prerequisite for an Alerts resource deep-link) — explicitly deferred (finding, review-response 3).
- Write actions (resolve/suppress/archive) from inside the tab.

---

## Technology stack (locked)

- **Frontend:** React 18 + TypeScript, `react-router-dom` (`useNavigate` on the row; `useSearchParams`
  on the three destination pages), `@tanstack/react-query` via a plain `useQuery`, `apiFetch`
  (`src/utils/apiClient`) with `readShape`/`isRecordWith` (`src/api/curInsightsApi.ts`), Tailwind utility
  classes inline, `@iconify/react`. **No new dependencies.**
- **Backend:** Spring Boot (Java 17), the existing `@RestController` `CurInsightsController`
  (`@RequestMapping("/api/xamops/cur-insights")`), `CloudAccountRepository`, `CloudAccountAccessService`,
  `CurInsightCache`, `SecuritySeverity`, `ResourceIdResolver`, and the existing per-product
  services/repositories. JUnit 5 + MockMvc for the controller test, mirroring `CurInsightsControllerTest`.

---

## Baseline: what exists in this worktree today (finding 1)

The design is written against these verified facts, and does **not** depend on any un-landed changeset:

- Worktree HEAD `294d871fe`, `base-branch.txt` = `main`; only `.agents/` is untracked (`git status
  --porcelain` shows a single `?? .agents/…`).
- `frontend-app/src/components/Cost/ResourceCostDrawer.tsx:41` declares
  **`type Tab = 'change' | 'breakdown' | 'trend';`** — three tabs. The file's own doc comment is headed
  "Why three tabs". There is **no** `connectivity` member, **no** `ResourceConnectivityTab.tsx`, **no**
  `everConnectivity` latch, and **no** `connectivity` prop anywhere in the Cost chain.
- The tab strip is a plain inline array literal mapped to `<button role="tab">` elements (the array is
  `[{key:'change',…},{key:'breakdown',…},{key:'trend',…}]`), and the body is rendered by three
  `!loading && change && tab === '…'` conditionals. There is no lazy-mount / keyed-remount mechanism yet
  — this design **adds one** for the new tab.
- The Cost wiring chain is: `CostPage.tsx:372` renders `<CostDetailTabs accountId={curAccountId} />`
  (AWS, no extra props → defaults apply) → `CostDetailTabs` renders `<ChargesByServiceSection
  fetcher=… />` → `ChargesByServiceSection:930` renders `<ServiceResourcesDrawer …/>` →
  `ServiceResourcesDrawer:929` renders `<ResourceCostDrawer resourceId resourceName accountId period
  scope={{service,region}} …/>`. `GcpCostPage.tsx:333` and `AzureCostPage.tsx:255` render
  `<CostDetailTabs …/>` with their own explicit fetcher props.

Symbols this design **reuses as-is** (all present in the worktree today — no external dependency):

| Symbol | Location (worktree) |
|--------|---------------------|
| `ResourceIdResolver.resolve(Long, Collection<String>)` → `Map<String,CanonicalResource>` | `service/ResourceIdResolver.java:93`; record `CanonicalResource(resourceId,resourceType,resourceName,region)` `:79`; indexed under both `getArn()` and `getResourceId()` `:116-117` |
| `CloudSecurityFindingRepository.findByResource(Long, String)` | `repository/CloudSecurityFindingRepository.java:88-91` |
| `CloudSecurityFinding` entity (`@Data`): `getId/getResourceArn/getControlId/getSeverity/getTitle/getResourceType/getRegion` | `domain/CloudSecurityFinding.java:34(@Data),50,76,79,85,88,91,100` |
| `SecuritySeverity.normalize/rank/worstOf/RANKED/CRITICAL…INFORMATIONAL/fromGuardDutyScore` | `service/security/SecuritySeverity.java:30-37,51,96,113,131` |
| `ComplianceOpsService.getReport(String, int)` | `service/compliance/ComplianceOpsService.java:77` |
| `PerformanceInsightsService.getInsights(String, String, boolean)` + `getAwsAccountId()` need | `service/PerformanceInsightsService.java:78`; `domain/CloudAccount.java:225` |
| Controller prologue: `denyIfNotEntitled`, `parsePeriod`, `invalidPeriod`, `insightCache.getOrCompute`, `findByAwsAccountId`, blank-id-400-before-entitlement | `controller/CurInsightsController.java:38,247,290,292,336-358,364` |
| `useQuery` (react-query), `apiFetch`, `readShape:28`, `isRecordWith:58` | `hooks`/`utils/apiClient.ts:146`/`api/curInsightsApi.ts:28,58` |

> **Note on the review's finding-1 claim:** the review correctly states the Connectivity baseline is
> absent from the worktree. Revision 2 chose to "wait for a rebase that lands Connectivity"; that is not
> an implementable foundation (it depends on uncommitted work in a *different* checkout). This revision
> takes the review's option **(b)**: build the tab self-contained against the three-tab worktree. No
> rebase precondition remains.

---

## Ambiguity resolution (with evidence)

### (a) Are "SecOps" and "Security Center" one product or two? → **One, `/security`.**

Evidence: `App.tsx:562` — `<Route path="/security" element={<SecurityPage />} />`. There is **no**
`/secops` route anywhere in `App.tsx`. The only other security routes are provider variants
(`/gcp-security`, `/azure-security`) and the distinct `/xamsecure` product (`:563-564`). `SecurityPage`
is the single findings surface for `/security`. So the tab shows **one "Security Center" group** for
`/security`. "Alerts" (brief item 7) is a logically separate product/route, but — see Product coverage
item 7 — its per-resource rows are a strict subset of Security Center's for the same resource, so it is
deduplicated away rather than shown as a second security group.

### (b) The exact destination route + param each product accepts to focus a finding

**No destination page reads any query param today.** `grep` for `useSearchParams | location.search |
URLSearchParams` across `SecurityPage.tsx`, `PerformancePage.tsx`, `ComplianceOpsPage.tsx`,
`AlertsPage.tsx` returns **zero** hits. All pages read the account from `AccountContext`
(`useAccounts()`), not the URL. So a deep-link needs only a resource query param; the account is already
in context. The `?resource=` handlers this design adds are therefore genuinely new and additive.

**Decision (reuse-first; add a handler only where it is cheap, safe, and backed by an existing client
field):** add a minimal, read-only `?resource=<id>` reader **only to the three pages whose existing
client model already carries a field that matches a CUR resource id/ARN**. Elsewhere, navigate to the
page without a param (the brief's "if even that is impossible, just navigate to the page"). The split,
with the exact **client-side** field each page filters on and its evidence:

| # | Product | Navigate to | Param | Client field filtered on | Evidence (worktree) |
|---|---------|-------------|-------|---------------------------|---------------------|
| 1 | Security Center | `/security?resource=<arn>` | `resource` (new) | `SecurityFinding.resourceId` | `types/security.ts:7-8` (`interface SecurityFinding { resourceId: string; … }`), `:14` (`controlId?`) |
| 2 | WAR | — (excluded v1) | none | n/a — control-level | backend `WarFinding` has `controlId`/`evidence`/`region`, **no** `resourceArn`/`resourceId` |
| 3 | XamSecure | — (excluded v1) | none | n/a — onboarding/posture product, no per-resource CUR key | route `/xamsecure/:tab` (`App.tsx:563-564`); no resource-keyed findings query |
| 4 | ComplianceOps | `/complianceops?resource=<uid>` | `resource` (new) | `ComplianceControl.resources[].uid` | `types/compliance.ts:76-77` (`ComplianceResourceRef.uid: string \| null`), `:125` (`resources: ComplianceResourceRef[]`) |
| 5 | Program (SOC2) | — (excluded v1) | none | n/a — org-level, not CUR-keyed | `soc2Api` endpoints take no AWS account id; SOC2 is a comp.ai program surface |
| 6 | Performance Insights | `/performance?resource=<id>` | `resource` (new) | `PerformanceInsight.resourceId` | `types/performance.ts:12,21` (`interface PerformanceInsight { … resourceId?: string }`) |
| 7 | Alerts | `/alerts` (plain navigate; and deduped away — see Product coverage) | none | **none on client** | `types/alerts.ts`: `AlertBase` carries only `id/service/name/description/region/severity`; the `Alert` union (`QuotaAlert \| AnomalyAlert \| SecurityAlert`) adds no resource field; backend `asAlerts` sets no `resources` for security-finding alerts (`SecurityFindingProjectionService.java` `asAlerts:225-279`) |

The SOC2 known-good landing (if ever used) is `/soc2-program/overview`, **not** `/soc2-program/cloud`:
`App.tsx:634` navigates the base route to `/soc2-program/overview`; `Soc2ProgramPage` recognises card
tabs `['frameworks','integrations','documents','cloud-tests']` with default `overview` — `cloud` is not
a valid tab value (the cloud-tests tab value is `cloud-tests`).

### (c) Does matching a CUR resource id to each product's finding need identity reconciliation? → **Yes, server-side, via `ResourceIdResolver.resolve`.**

The CUR `resourceId` is a `line_item_resource_id`: a short id for EC2/EBS/S3 (`i-…`, bucket name) and a
full ARN for RDS/Lambda/DynamoDB/ELB. The products key differently:

- **Security Center** keys on **ARN**: `findByResource(cloudAccountId, arn)` matches
  `f.resourceArn = :arn` exactly (`CloudSecurityFindingRepository.java:89,91`).
- **ComplianceOps** keys on `ComplianceResourceRef.uid` (ARN or provider resource id).
- **Performance** keys on the service-native short id (`PerformanceInsight.resourceId`, e.g. `i-…`),
  **not** an ARN.

A single CUR id (ARN *or* short id) must therefore be matched against products that key on ARN *or*
short id; exact-string alone misses half the pairings. The worktree already has the reconciler for
exactly this:

**`ResourceIdResolver.resolve(Long cloudAccountId, Collection<String> rawIds)` →
`Map<String, CanonicalResource>`** where `record CanonicalResource(String resourceId, String
resourceType, String resourceName, String region)` (`service/ResourceIdResolver.java:79,93`). The
resolver indexes each inventory row under **both** its ARN and its short id
(`index(byIdentifier, row.getArn(), row); index(byIdentifier, row.getResourceId(), row)` at `:116-117`),
so a caller holding **either** form resolves to the same row, and the returned
`CanonicalResource.resourceId` is the canonical **short** id (built from `row.getResourceId()` at
`:124-125`). It strips resource-less/blank ids (`:97` comment, `lookup` filter) so tax/support rows
never resolve.

**Decision — the aggregator resolves once, server-side, then matches each product on the form it keys
on:**

```
Map<String,CanonicalResource> m = idResolver.resolve(cloudAccountId, List.of(resourceId));
CanonicalResource canon = m.get(resourceId);           // may be null if not in inventory
boolean matched = canon != null;
String shortId = matched ? canon.resourceId() : resourceId;
String arn     = looksLikeArn(resourceId) ? resourceId
               : (matched && looksLikeArn(canon-derived?) ? … : null);  // see below
```

Candidate derivation:
- **ARN candidate** = `resourceId` when it already looks like an ARN (`startsWith("arn:")`). The
  resolver's canonical value is the *short* id, so it does not manufacture an ARN; when the CUR id is a
  short id there is simply no ARN candidate, and ARN-keyed products (Security Center, ComplianceOps)
  legitimately contribute nothing. This is correct, not a bug: those products store ARNs, so a resource
  the CUR names only by short id has no ARN to match.
- **Short-id candidate** = `canon.resourceId()` when resolved, else the raw `resourceId`. Used by
  Performance.

Products matched:
- **Security Center / ComplianceOps** → ARN candidate (null-safe: no ARN candidate → no rows).
- **Performance** → short-id candidate.

If `resolve` returns no entry (`matched=false`), each product still attempts a raw exact-string match
and may legitimately find nothing. `resolve` throwing is caught and degrades to `matched=false` (see
error table). This reuses the worktree's inventory reconciliation rather than inventing a matcher, keeps
the join server-side on byte equality, and means the frontend sends only the raw CUR id.

> The review's finding-2 (Revision 1) proposed an API named `idResolver.curIdentifiers(...)` →
> `CurIdentifiers`. **That method does not exist in this worktree** (`grep curIdentifiers` → 0 hits);
> the real reconciler is `resolve(...)` → `Map<String,CanonicalResource>` as cited above, and that is
> what this design uses. No un-landed symbol is referenced.

---

## Product coverage — per-product data source the aggregator reads

Each read is **read-only**, scoped to the resolved account **in the form that read expects** (finding 2
table in NFR 15), and wrapped in its own try/catch.

1. **Security Center** — `findByResource(account.getId() /*Long*/, arn)`
   (`CloudSecurityFindingRepository.java:88-91`). Returns `List<CloudSecurityFinding>` carrying
   `getResourceArn()`, `getControlId()`, `getSeverity()`, `getTitle()`, `getResourceType()`,
   `getRegion()` (all via Lombok `@Data`). Each row → a normalised finding: `title=getTitle()`,
   `severity=SecuritySeverity.normalize(getSeverity())`, deep-link `/security?resource=<arn>`.
   Only runs when an ARN candidate exists.
2. **WAR** — control-level by construction (`WarFinding` has `controlId`/`evidence`/`region`, no resource
   field), so a WAR control cannot be attributed to one CUR resource. **v1: no per-resource rows.**
   Recorded, not silently dropped.
3. **XamSecure** — `/xamsecure/:tab` is an onboarding + posture product with no per-resource ARN query
   equivalent to `findByResource`. **v1: no per-resource rows.** Recorded.
4. **ComplianceOps** — `getReport(String.valueOf(account.getId()) /*internal id as String*/,
   trendDays)` (`ComplianceOpsService.getReport:77`, which treats its `accountId` argument as the
   internal `cloud_account` id passed to `findFirstByCloudAccountIdAndStatus…(accountId,…):82`). The
   aggregator walks `report.controls[].resources[]` and emits a row per `ResourceRef` whose `uid` equals
   the ARN candidate, titled with the control `title`, severity from the control's `severity`. Deep-link
   `/complianceops?resource=<uid>`.
5. **Program (SOC2)** — comp.ai proxy, org-level, not CUR-account-scoped. **v1: no per-resource rows.**
   Recorded.
6. **Performance Insights** — `getInsights(account.getAwsAccountId() /*12-digit AWS id*/, null, false)`
   (`PerformanceInsightsService.getInsights:78`, which resolves the account via
   `findByAwsAccountId:71`). Returns `List<PerformanceInsightDto>`, filtered where
   `insight.getResourceId()` equals the **short-id** candidate. Severity normalised via
   `SecuritySeverity.normalize` (Performance's native scale is `CRITICAL/WARNING/WEAK_WARNING`, which
   `normalize` already maps — `SecuritySeverity.java:15`). Deep-link `/performance?resource=<id>`.
   The read uses `forceRefresh=false` so a warm cache returns immediately; a cold cache may trigger the
   method's own multi-region scan (`:100` logs "Starting multi-region performance insights scan"). That
   is the *existing* method's behaviour, not a new path (NFR 13); the aggregator runs this branch inside
   its try/catch so a slow cold read degrades to an `errors[]` entry (finding 5).
7. **Alerts** — **no separate read; derived from the Security Center rows and deduplicated away**
   (finding 3). The Alerts page's security-finding rows come from
   `SecurityFindingProjectionService.asAlerts(cloudAccountId, excluded)` which reads the **same**
   `CloudSecurityFinding` table (`repository.findActiveBySeverityIn(cloudAccountId, [CRITICAL, HIGH])` at
   `asAlerts:265-267`) and surfaces only CRITICAL/HIGH. So for a single resource, the Alerts set is
   exactly `{ Security Center findings for this resource whose severity ∈ {CRITICAL, HIGH} }` — a strict
   **subset** of group 1. Showing it as a second group would duplicate rows already under Security
   Center. **v1 decision: do not emit a separate Alerts group.** The dedup is therefore trivial and
   built from the shared entities, not from `AlertDto`:
   - The aggregator holds the `List<CloudSecurityFinding>` from `findByResource`. Security Center = all
     of them. There is no independent Alerts read to merge, so nothing can appear twice.
   - If a future revision *does* want a visible Alerts group (e.g. to badge "also on Alerts"), it must
     dedup against Security Center on the **entity identity** — a package-visible helper
     `aggKey(CloudSecurityFinding f) = nz(f.getResourceArn()) + "|" + nz(f.getControlId())` built from
     the entity getters (NOT the private, `DashboardData.SecurityFinding`-typed
     `SecurityFindingProjectionService.dedupKey`, and NOT from `AlertDto`, which carries neither
     `resourceId` nor `controlId`). This is recorded so a later implementer does not re-introduce the
     non-compiling "reuse `dedupKey`" plan the review flagged.

**Net v1 groups that produce per-resource rows: Security Center, ComplianceOps, Performance Insights.**
WAR, XamSecure, SOC2 are reasoned exclusions; Alerts is a deduplicated subset of Security Center and is
not shown as a separate group. Each of the eight brief products is addressed, none silently dropped.

> **Why Alerts collapses into Security Center (not shown twice):** verified at
> `SecurityFindingProjectionService.asAlerts:225-279` — it reads `findActiveBySeverityIn(cloudAccountId,
> [CRITICAL, HIGH])`, builds `AlertDto` with `id="finding-"+f.getId()`, and sets **no** `resources`.
> `AlertDto` (`dto/AlertDto.java:27-123`) has `id/service/name/description/status/type/region/severity/
> usage/limit/affectedCount/resources` but **no `resourceId` and no `controlId`**. So the Alerts list
> can neither be resource-filtered on the client (criterion 9 / ambiguity (b) row 7) nor independently
> matched to a CUR resource server-side; using the shared `CloudSecurityFinding` entities is the only
> implementable path, and it shows that Alerts ⊆ Security Center here.

---

## Backend design

### New endpoint

```
GET /api/xamops/cur-insights/resource-security/{accountId}
      ?resourceId=<cur resource id>      (required)
      &region=<region>                   (optional; narrows product reads)
      &period=<YYYY-MM>                   (optional; validated via parsePeriod; reserved, not used for findings today)
```

Placed on **`CurInsightsController`** (`controller/CurInsightsController.java`,
`@RequestMapping("/api/xamops/cur-insights")` `:38`) because that controller already owns account
resolution (`findByAwsAccountId` `:364`), the entitlement guard (`denyIfNotEntitled` `:247`), caching
(`insightCache.getOrCompute` `:358`), the blank-id-before-entitlement ordering (the `resourceChange`
handler 400s on blank `resourceId` at `:346-348` **before** `denyIfNotEntitled` at `:350` — the
documented precedence), and `parsePeriod`/`invalidPeriod` (`:290-292`, `:354-356`). The new handler
copies that prologue verbatim:

```java
@GetMapping("/resource-security/{accountId}")
public ResponseEntity<?> resourceSecurity(@PathVariable String accountId,
                                          @RequestParam String resourceId,
                                          @RequestParam(required = false) String region,
                                          @RequestParam(required = false) String period) {
    if (resourceId == null || resourceId.isBlank()) {                 // 400 before entitlement, like resourceChange:346
        return ResponseEntity.badRequest().body(Map.of("error", "A resource id is required"));
    }
    if (resourceId.length() > ResourceSecurityAggregationService.MAX_RESOURCE_ID_CHARS) {
        return ResponseEntity.badRequest().body(Map.of("error", "Resource id too long"));
    }
    ResponseEntity<?> denied = denyIfNotEntitled(accountId);          // 404/403, like resourceChange:350
    if (denied != null) return denied;
    Optional<YearMonth> target = parsePeriod(period);                 // 400 invalidPeriod, like resourceChange:354
    if (target.isEmpty()) return invalidPeriod(period);
    return insightCache.getOrCompute(accountId,
            () -> aggregator.aggregate(accountId, resourceId, region));  // account resolved inside, like computeResourceChange
}
```

The controller delegates to a new thin service **`ResourceSecurityAggregationService`**
(`com.xammer.cloud.service.security`), constructor-injecting `CloudAccountRepository`,
`ResourceIdResolver`, `CloudSecurityFindingRepository`, `ComplianceOpsService`,
`PerformanceInsightsService`, `SecuritySeverity` (static). `aggregate(accountId, resourceId, region)`:

1. Resolve the account once: `findByAwsAccountId(accountId)` → first `CloudAccount` (404 if empty, same
   as `computeResourceChange:364`). Hold `account`.
2. Resolve the canonical identity once: `idResolver.resolve(account.getId(), List.of(resourceId))`
   (`ResourceIdResolver.resolve:93`); derive `{arnCandidate, shortIdCandidate, matched}` (ambiguity (c)).
3. For each contributing product, run its read **inside its own try/catch** in the account-id form from
   the NFR-15 table, map results through `SecuritySeverity.normalize`, and build a normalised group. A
   thrown product adds a `ProductError` entry rather than aborting.
4. Return the normalised aggregate (Alerts is not a separate group — item 7).

### Normalised response shape

```jsonc
{
  "resourceId": "arn:aws:ec2:...:instance/i-0abc",   // echoed raw CUR id as requested
  "matched": true,                                    // whether idResolver.resolve found an inventory row
  "groups": [
    {
      "product": "SECURITY_CENTER",                   // enum: SECURITY_CENTER | COMPLIANCEOPS | PERFORMANCE
      "label": "Security Center",
      "route": "/security",                           // react-router path (no query)
      "findings": [
        {
          "title": "S3 bucket is public",
          "severity": "CRITICAL",                     // SecuritySeverity scale, already normalised
          "detail": "…optional one-line context…",
          "deepLink": { "path": "/security", "query": { "resource": "arn:aws:s3:::b" } }
        }
      ]
    }
  ],
  "errors": [ { "product": "COMPLIANCEOPS", "message": "report unavailable" } ]
}
```

- `deepLink.path` + `deepLink.query` are assembled **server-side**, so the frontend never hard-codes a
  product's param name. The frontend does `navigate({ pathname: path, search: toSearch(query) })` with
  an empty search when `query` is empty.
- The **only** query key ever emitted is `resource`.
- `severity` is always normalised through `SecuritySeverity.normalize` / `fromGuardDutyScore`.
- Groups with no findings are omitted; `errors` is per-product and never fatal.
- Empty `groups` + empty `errors` = "no findings anywhere" (the tab's empty state).

### Caching

Wrap the compute in `insightCache.getOrCompute(accountId, …)` like `resourceChange:358`. Entitlement is
checked on the request thread **before** `getOrCompute` (never inside the supplier) — the precedence
documented on the `resourceChange` prologue — so the cached supplier only ever runs for an entitled,
well-formed request. The cache key is `accountId`-scoped by `getOrCompute`; because the resource/region
are part of the request path+query, callers for different resources get distinct cache entries (same as
`resourceChange`, which keys its own compute by the full `accountId`+`resourceId`+`service`+`region`).

### Error handling (concrete, per failure)

| Operation | Failure condition | Recoverable? | Caller receives | Logged |
|-----------|-------------------|--------------|-----------------|--------|
| Validate param | `resourceId` blank | n/a | **400** `{error:"A resource id is required"}` **before** entitlement, matching `resourceChange:346-348` | — |
| Validate param | `resourceId` longer than `MAX_RESOURCE_ID_CHARS` (new; 2048) | n/a | **400** `{error:"Resource id too long"}` | — |
| Validate period | `period` present but unparseable | n/a | **400** `invalidPeriod` via existing `parsePeriod`+`invalidPeriod` | — |
| Resolve account | `findByAwsAccountId` empty / not entitled | n/a | **404** (same as `resourceChange`; unknown == not-yours) | WARN via `denyIfNotEntitled` |
| Identity reconcile | `idResolver.resolve` throws | yes | aggregate still returns; `matched:false`; products fall back to raw-id match | WARN once |
| Security Center read | repo throws | yes | `errors[]` += `SECURITY_CENTER`; other groups returned | WARN |
| ComplianceOps read | `getReport` throws / no report | yes | `errors[]` += `COMPLIANCEOPS` | INFO (no report is normal) |
| Performance read | `getInsights` throws (incl. a slow/failing cold scan) | yes | `errors[]` += `PERFORMANCE`; other groups returned | WARN |
| Whole handler | unexpected exception **after** groups built | no | **502** `{error:"Could not read security findings"}` — reached only if the top-level fails; a single product failing never reaches here | WARN |

Each product branch is independently guarded so one slow/failing product cannot take the tab down — the
backend half of acceptance criterion 11. The Performance branch's guard is also what time-boxes a
cold-cache scan into a graceful `errors[]` entry (finding 5).

> **New constant (finding 2 Rev-1 / finding 8 Rev-1 context):** this worktree has **no**
> `MAX_COST_ID_CHARS`/`MAX_RESOURCE_ID_CHARS` constant. The design introduces its own server-side guard
> `ResourceSecurityAggregationService.MAX_RESOURCE_ID_CHARS = 2048` (twice the 1024 ARN length ceiling),
> rather than referencing a constant that is not in the worktree.

### Validation of external input

- `accountId` (path): resolved + entitlement-checked; non-matching → 404.
- `resourceId` (query, **required**): non-blank (400 before entitlement); length ≤
  `MAX_RESOURCE_ID_CHARS` (400 otherwise).
- `region` (query, optional): passed only as a narrowing filter to product reads; an unknown region
  simply narrows to nothing, never errors.
- `period` (query, optional): parsed with the controller's `parsePeriod`; invalid → 400 `invalidPeriod`.
  Reserved for future month filtering; current product reads are "latest state", so it does not change
  v1 results.

### Invariant ownership

- **Severity normalisation** — owned by `SecuritySeverity` (service layer). Every product branch converts
  on the way into the DTO, so the frontend never sees a product's native vocabulary. Why there: it is the
  single source of truth already enforced by `SecuritySeverityTest`.
- **Tenant scoping** — owned by the controller's single `findByAwsAccountId` + `denyIfNotEntitled`
  resolution, then each read's own account-scoped query in the form from the NFR-15 table. Why there:
  resolving once and checking entitlement before any read is the pattern every sibling cur-insights
  endpoint uses, and it is what keeps the three different account-id forms safe.
- **CUR-id↔product-id reconciliation** — owned by the aggregator via `ResourceIdResolver.resolve`. Why
  there: it is the only layer that sees the CUR id and all three products, and the resolver already
  encapsulates the ARN/short-id duality.

### Testability

- Unit-testable: the aggregator's candidate derivation from a mocked `ResourceIdResolver.resolve` return
  (ARN input, short-id input, unresolved input); its per-product try/catch isolation (mock the product
  services, assert one throwing yields an `errors[]` entry while the others populate); `SecuritySeverity`
  mapping of each native severity (extend `SecuritySeverityTest`).
- Integration/controller-testable: new `ResourceSecurityControllerTest` (MockMvc) under
  `xamops-service/src/test/java/com/xammer/cloud/controller/`, mirroring `CurInsightsControllerTest`,
  asserting criterion 13's cases.

---

## Frontend design

### Fetch mechanism — plain `useQuery` (not `useCurInsight`)

The resource-scoped fetch uses a **plain `useQuery`**. `useCurInsight` cannot be used: its fetcher is
`(accountId, period?)` and its key is `[CUR_INSIGHTS_KEY, name, accountId, period ?? 'current']`
(`hooks/useCurInsight.ts:30,38`) — it cannot carry `resourceId`/`region` into either the fetcher or the
cache key.

```ts
const securityQuery = useQuery({
  queryKey: ['cost-security', 'findings', accountId ?? '', resourceId ?? '', region ?? ''],
  queryFn: () => security.findings(accountId as string, resourceId as string, region, period),
  enabled: active && Boolean(accountId) && Boolean(resourceId),
  staleTime: 5 * 60 * 1000,
});
```

`enabled` gates the fetch to first selection (criterion 4) and refetch-on-different-resource (the key
includes `resourceId`, and the component is also keyed on `resourceId` — criterion 5).

### New component: `ResourceSecurityTab.tsx`

`frontend-app/src/components/Cost/ResourceSecurityTab.tsx`. Because there is no `ResourceConnectivityTab`
in this worktree to mirror, this component is written from scratch but follows `ResourceCostDrawer`'s own
in-file conventions: inline Tailwind classes, Iconify icons, an explicit loading/empty/error/data state
ladder like the drawer's own `loading` / `!change` / tab-body states, and a `<button>`-per-row for
keyboard accessibility.

```ts
export interface SecurityFetchers {
  // One fetcher: the normalised aggregation endpoint. One bundle, so null means "omit the tab".
  findings: (
    accountId: string,
    resourceId: string,
    region?: string,
    period?: string,
  ) => Promise<ResourceSecurityFindings | null>;
}

export const awsSecurity: SecurityFetchers = { findings: fetchResourceSecurity };
```

Props: `active: boolean`, `accountId: string | null`, `resourceId: string | null`, `resourceName?:
string`, `period: string`, `region?: string`, `security: SecurityFetchers`.

State ladder:
1. `!active` → render nothing (defensive; the drawer also only mounts the body when active via the latch).
2. loading (`securityQuery.isPending` while enabled) → a skeleton/"Reading this resource's findings…".
3. error (`securityQuery.isError`, whole endpoint failed) → an "Unavailable / Try again" block calling
   `securityQuery.refetch`.
4. settled + `groups.length === 0 && errors.length === 0` → empty state: "No security, compliance or
   performance findings reference this resource."
5. settled + data → one `<ProductGroup>` per `groups[]` entry, plus any `errors[]` rendered as inline
   per-product "couldn't load" rows (criterion 11).

Each finding row:

```tsx
<button
  type="button"
  onClick={() => navigate({ pathname: f.deepLink.path, search: toSearch(f.deepLink.query) })}
  className="w-full flex items-center gap-2 px-3 py-2 rounded-lg border border-[rgba(0,0,0,0.08)]
             hover:border-[#4285F4] hover:bg-[rgba(66,133,244,0.04)] text-left transition-colors"
>
  <SeverityBadge severity={f.severity} />
  <span className="text-[12.5px] text-black truncate">{f.title}</span>
  {f.detail && <span className="text-[11px] text-[rgba(0,0,0,0.45)] truncate">{f.detail}</span>}
</button>
```

A real `<button>`, so Enter/Space activation and focus come for free (criterion 6). Navigation uses
`useNavigate()` (SPA, no reload). `toSearch(query)` returns `''` when `query` is empty. The backend
already sorts rows by severity, so the component does not re-sort; `SeverityBadge` colour-maps the five
`SecuritySeverity` values.

### API client function

Add to `frontend-app/src/api/curInsightsApi.ts` (where the cur-insights fetchers and the
`readShape`/`isRecordWith` helpers live, `:28,:58`):

```ts
export interface SecurityDeepLink { path: string; query: Record<string, string>; }
export interface ResourceSecurityFinding {
  title: string; severity: string; detail?: string; deepLink: SecurityDeepLink;
}
export interface ResourceSecurityGroup {
  product: string; label: string; route: string; findings: ResourceSecurityFinding[];
}
export interface ResourceSecurityFindings {
  resourceId: string; matched: boolean; groups: ResourceSecurityGroup[];
  errors: { product: string; message: string }[];
}

export const fetchResourceSecurity = async (
  accountId: string, resourceId: string, region?: string, period?: string,
): Promise<ResourceSecurityFindings | null> => {
  const params = new URLSearchParams({ resourceId });
  if (region) params.set('region', region);
  if (period) params.set('period', period);
  const res = await apiFetch(
    `/api/xamops/cur-insights/resource-security/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) return null;                              // 400/404 → tab shows its own state
  return readShape<ResourceSecurityFindings>(res, isRecordWith(['groups', 'errors']));
};
```

### Wiring into the drawer — introducing the lazy-mount + null-omit pattern from scratch

Against the **three-tab** `ResourceCostDrawer.tsx` as it exists today (`:41` `type Tab`; the inline tab
array; the three `tab === '…'` body conditionals):

1. **`ResourceCostDrawer.tsx`**
   - Widen the tab type: `type Tab = 'change' | 'breakdown' | 'trend' | 'security';` (`:41`).
   - Add prop `security?: SecurityFetchers | null;` to `ResourceCostDrawerProps`, defaulted to
     `awsSecurity` in the destructure, documented "null omits the tab; GCP/Azure pass null".
   - Add `const securityActive = tab === 'security';` and a lazy-mount latch
     `const everSecurity = useRef(false); if (securityActive) everSecurity.current = true;`. Reset it in
     the existing `useEffect(() => { setTab('change'); }, [resourceId])` effect
     (`everSecurity.current = false;`) so a different resource remounts the body.
   - Append the tab button to the inline strip array **after** `trend`, conditional on the prop:
     ```tsx
     ...(security ? [{ key: 'security' as Tab, label: 'Security & Alerts' }] : []),
     ```
   - Render the body with a lazy-mount + keyed pattern, as a **sibling of** the existing `tab === '…'`
     blocks (it deliberately does **not** depend on `change`/`loading`, so a resource with no CUR change
     payload still shows a working Security tab):
     ```tsx
     {security && everSecurity.current && resourceId && (
       <div hidden={!securityActive}>
         <ResourceSecurityTab
           key={resourceId}
           active={securityActive}
           accountId={accountId}
           resourceId={resourceId}
           resourceName={resourceName}
           period={period}
           region={scope?.region && scope.region !== 'Global' ? scope.region : undefined}
           security={security}
         />
       </div>
     )}
     ```
     The three existing body conditionals (`!loading && !change …`, `!loading && change && tab === …`)
     are left untouched; the Security body is an additional block, so the existing tabs' behaviour and
     the "no detail available" state are unchanged (criterion 10). Scroll lock (`lockPageScroll`) and the
     Escape handler are untouched.
2. **`ServiceResourcesDrawer.tsx`** — add `security?: SecurityFetchers | null;` to its props and thread
   it to `<ResourceCostDrawer security={security} …/>` (`:929`). Pure pass-through.
3. **`ChargesByServiceSection.tsx`** — add `security?: SecurityFetchers | null;`, forward to
   `<ServiceResourcesDrawer security={security} …/>` (`:930`).
4. **`CostDetailTabs.tsx`** — add `security?: SecurityFetchers | null;` defaulted to `awsSecurity`,
   forward to `<ChargesByServiceSection security={security} …/>`.
5. **`CostPage.tsx`** — **no change**: it renders `<CostDetailTabs accountId={curAccountId} />` (`:372`)
   with no explicit `security` prop, so the `awsSecurity` default applies down the chain.
6. **`GcpCostPage.tsx` (`:333`) / `AzureCostPage.tsx` (`:255`)** — add `security={null}` to their
   `<CostDetailTabs …>` with a one-line comment that per-resource security findings are AWS-only in v1.
   Explicit `null`, not omission (omission would take the AWS default).

### Destination page deep-link handlers (additive, read-only — three pages)

Each adds a minimal `useSearchParams` reader that filters to the `resource` param when present and does
**nothing** (unchanged behaviour) when absent:

- **`SecurityPage.tsx`** — read `resource`; if present, pre-filter the findings list to rows whose
  `finding.resourceId === resource` (`types/security.ts:8`) and scroll the first match into view. The
  existing severity grouping keeps working.
- **`PerformancePage.tsx`** — read `resource`; pre-filter insights to `insight.resourceId === resource`
  (`types/performance.ts:21`).
- **`ComplianceOpsPage.tsx`** — read `resource`; expand/scroll to controls whose
  `resources[].uid === resource` (`types/compliance.ts:77`).

`/alerts`, `/war`, `/soc2-program/*`, `/xamsecure/*` receive **no** param handler: Alerts has no
resource-keyed client field, and WAR/SOC2/XamSecure produce no v1 rows.

### Testing (frontend)

Create `frontend-app/src/components/Cost/__tests__/` (new folder; the Cost dir has no `__tests__` today)
with a react-query test harness (wrap the component in a `QueryClientProvider` + `MemoryRouter`):

- `ResourceSecurityTab.test.tsx` — asserts (a) no fetch while `active=false`; (b) empty state on empty
  `groups`+`errors`; (c) a per-product error row when `errors[]` is non-empty while other groups still
  render; (d) a row click calls a mocked `useNavigate` with the server-provided path+query; (e) rows are
  keyboard-activatable (they are `<button>`s).
- `CostDetailTabs.providers.test.tsx` — asserts the Security & Alerts tab is present for the AWS provider
  config (default `security`) and absent when `security={null}`.

---

## Edge cases

- **CUR id is a bare id, product keys on ARN (or vice-versa):** handled by `idResolver.resolve` returning
  the canonical short id while the raw ARN serves ARN-keyed products; a resource named only by short id
  has no ARN candidate and ARN-keyed products contribute nothing (correct). If `resolve` finds no
  inventory row, `matched:false` and each product falls back to a raw exact-string attempt.
- **Resource-less CUR rows (tax/support, blank `resourceId`):** the drawer never opens the panel for
  these; the endpoint 400s on blank `resourceId` defensively; `ResourceIdResolver.resolve` also strips
  blanks.
- **Account has findings but none reference this resource:** empty state (criterion 12), distinct from
  endpoint failure.
- **One product throws:** its branch catches and adds to `errors[]`; the tab still renders everything
  else (criterion 11).
- **Alerts would duplicate Security Center:** Alerts is a strict CRITICAL/HIGH subset of the same
  `CloudSecurityFinding` rows, so v1 does not emit a separate Alerts group — nothing is shown twice
  (Product coverage item 7).
- **Performance cold cache:** `getInsights(…, false)` may run its own multi-region scan on a cold cache;
  the Performance branch's try/catch time-boxes/degrades that to an `errors[]` entry (finding 5).
- **Demo mode:** the endpoint has no demo fixture; `apiFetch` returns non-ok → `fetchResourceSecurity`
  returns null → the tab shows its empty/unavailable state, never a crash.
- **Multi-account selection (`accountId` is a comma-joined list on `/cost`):** the drawer passes a single
  `accountId` for per-resource queries (`ResourceCostDrawer` already receives one `accountId`); the new
  endpoint resolves `findByAwsAccountId(accountId)` on the first match, inheriting the sibling endpoints'
  behaviour. The tab is meaningful only for a single-AWS-account focus.

---

## Files touched (summary)

Frontend (worktree `frontend-app/src/`):
- **new** `components/Cost/ResourceSecurityTab.tsx`
- **new** `components/Cost/__tests__/ResourceSecurityTab.test.tsx`
- **new** `components/Cost/__tests__/CostDetailTabs.providers.test.tsx`
- `api/curInsightsApi.ts` (+ types and `fetchResourceSecurity`)
- `components/Cost/ResourceCostDrawer.tsx` (tab member `:41`, prop, latch, body block, strip entry)
- `components/Cost/ServiceResourcesDrawer.tsx` (`:929`), `ChargesByServiceSection.tsx` (`:930`),
  `CostDetailTabs.tsx` (thread `security` prop)
- `pages/GcpCostPage.tsx` (`:333`), `pages/AzureCostPage.tsx` (`:255`) (`security={null}`)
- `pages/SecurityPage.tsx`, `pages/PerformancePage.tsx`, `pages/ComplianceOpsPage.tsx` (additive
  `?resource=` handlers — **not** `AlertsPage.tsx`)

Backend (worktree `xamops-service/src/main/java/com/xammer/cloud/`):
- `controller/CurInsightsController.java` (+ `resource-security` handler reusing
  `denyIfNotEntitled`/`parsePeriod`/`invalidPeriod`/`insightCache.getOrCompute`)
- **new** `service/security/ResourceSecurityAggregationService.java` (+ `MAX_RESOURCE_ID_CHARS`)
- **new** DTO(s) for the normalised response (e.g. `dto/security/ResourceSecurityFindings.java`)
- **new** `xamops-service/src/test/java/com/xammer/cloud/controller/ResourceSecurityControllerTest.java`
- optionally extend `service/security/SecuritySeverityTest.java` for any Performance native-severity
  mappings not already covered

Reused as-is (no change): `ResourceIdResolver.resolve`, `CloudSecurityFindingRepository.findByResource`,
`SecuritySeverity`, `CloudAccountRepository`, `CloudAccountAccessService`, `CurInsightCache`,
`ComplianceOpsService.getReport`, `PerformanceInsightsService.getInsights`, `CloudAccount.getId/
getAwsAccountId`.

---

## Responses to design review

Review: `.agents/tasks/cost-security-alerts-tab/design-review.{md,json}` — verdict CHANGES_REQUESTED
(2 HIGH, 1 MEDIUM, 2 NIT). Each finding is **addressed** or **justified** below, consistent with the
original requirements.

**1 (HIGH) — design written against a non-existent four-tab Connectivity baseline; the rebase was an
unguaranteed out-of-worktree dependency.** ADDRESSED via the review's option **(b)**. The design is now
self-contained against the three-tab worktree that actually exists (HEAD `294d871fe`,
`ResourceCostDrawer.tsx:41` = `type Tab = 'change' | 'breakdown' | 'trend';`). All references to
`connectivity` / `ResourceConnectivityTab` / `everConnectivity` / `connectivity?: … | null` as
pre-existing symbols are removed. The new tab, its `everSecurity` lazy-mount latch, its keyed remount,
and its `security?: SecurityFetchers | null` null-omit prop are **introduced from scratch** against the
real drawer and the real `CostPage → CostDetailTabs → ChargesByServiceSection → ServiceResourcesDrawer →
ResourceCostDrawer` chain (line anchors re-pinned against the worktree). The "Blocking precondition /
rebase" section is deleted; there is no external dependency. The tab becomes the fourth tab (after
`Change · Breakdown · Trend`), which is "after Connectivity" given Connectivity is absent — stated
explicitly in FR1/AC1.

**2 (HIGH) — NFR 15's blanket "all reads scoped by internal `account.getId()`, never the 12-digit AWS
id" is wrong for Performance and breaks that branch.** ADDRESSED. NFR 15 is rewritten as a per-product
account-id-form table and the blanket invariant is gone. Verified in the worktree:
`PerformanceInsightsService.getInsights:78` → `getAccount:101` → `findByAwsAccountId:71` (throws
`RuntimeException` on empty `:73`) needs the **12-digit AWS id** (`account.getAwsAccountId()`,
`CloudAccount:225`); `ComplianceOpsService.getReport:77` treats its arg as the **internal id as String**
(`findFirstByCloudAccountIdAndStatus…(accountId):82`); `findByResource:91` and the Security Center/Alerts
reads take the **internal `Long`** (`account.getId()`). The aggregator holds the single resolved
`CloudAccount` and passes the correct form per read. Isolation holds because the account is resolved once
via `findByAwsAccountId` and entitlement-checked before any read.

**3 (MEDIUM) — Alerts↔Security-Center dedup "reusing `dedupKey`" is not implementable against the real
`AlertDto`.** ADDRESSED. Verified: `SecurityFindingProjectionService.dedupKey:288` is `private static`
over `DashboardData.SecurityFinding`; `asAlerts:225-279` reads
`findActiveBySeverityIn(cloudAccountId, [CRITICAL, HIGH])` and emits `AlertDto` (`dto/AlertDto.java:27-123`)
with **no `resourceId`/`controlId`** and no `resources` for security-finding alerts. So the stated reuse
cannot compile. The design is changed to **not call `asAlerts` at all**: because the Alerts
security-finding rows come from the **same** `CloudSecurityFinding` table and are a CRITICAL/HIGH subset
of the Security Center rows for a resource, v1 reads only `findByResource` once (for Security Center) and
**does not emit a separate Alerts group** — making dedup trivial (nothing can appear twice). If a future
revision wants a visible Alerts group, the design specifies a package-visible
`aggKey(CloudSecurityFinding) = resourceArn|controlId` helper built from the entity getters, explicitly
*not* the private `dedupKey` and *not* `AlertDto`.

**4 (NIT) — a few cited line numbers were a line or two off.** ADDRESSED. All `:line` anchors were
re-pinned against the worktree during this rewrite (`ResourceCostDrawer.tsx:41`; `useCurInsight.ts:30`
signature, `:38` key; `CurInsightsController.java:38,247,290,336-358,364`; `ResourceIdResolver.java:79,93,
116-117,124-125`; `CloudSecurityFindingRepository.java:88-91`; `SecurityFindingProjectionService.java:225,
265-267,288`; `AlertDto.java:27-123`; `ComplianceOpsService.java:77,82,127`; `PerformanceInsightsService.
java:71,73,78,100-101`; `CloudAccount.java:20,225`; client types `security.ts:8`, `performance.ts:21`,
`compliance.ts:77`, `alerts.ts` `AlertBase`).

**5 (NIT) — NFR 13's "must not trigger a scan" is not strictly true for Performance on a cold cache.**
ADDRESSED. NFR 13 is softened to "adds no new scan/ingest path; reuses each product's standard cached
read; a cold-cache refresh inside an existing read is possible and acceptable", and the Performance
branch is specified to run inside its own try/catch so a slow cold scan degrades to an `errors[]` entry
rather than hanging the tab (also reflected in the error table and the Performance edge case).
