# Design review — Cost "Security & Alerts" tab (Revision 3)

Reviewed fresh against the worktree `/.worktrees/cost-security-alerts-tab`. Every route, param,
endpoint, signature and field claim in the design was checked against the actual source. This review
does not assume the design's word for any code fact.

Verdict: **CHANGES_REQUESTED** — 0 HIGH, 2 MEDIUM, 4 NIT.

This revision is a large improvement over the implied prior revisions: it is now self-contained against
the real three-tab drawer, the per-product account-id-form table is correct, and the Alerts/Security
dedup is grounded in the shared entities rather than a non-compiling `dedupKey` reuse. The two MEDIUM
findings are verification gaps, not architectural breaks, and both have concrete fixes below.

---

## Verified Assumptions (checked against source, correct as stated)

1. **Three-tab baseline.** `ResourceCostDrawer.tsx` declares `type Tab = 'change' | 'breakdown' |
   'trend';` (line 41) with the "Why three tabs" doc comment, the `useState<Tab>('change')`, the
   `useEffect(() => { setTab('change'); }, [resourceId])` reset, the Escape handler, and
   `lockPageScroll()` all present exactly as the design describes. No `connectivity`/
   `ResourceConnectivityTab`/`everConnectivity` symbol exists. The design's "introduce the latch and
   null-omit prop from scratch" plan is sound.

2. **Full wiring chain + line anchors.** `CostPage.tsx:372` renders `<CostDetailTabs
   accountId={curAccountId} />` with no `security` prop; `GcpCostPage.tsx:333` and `AzureCostPage.tsx:255`
   render `<CostDetailTabs …>`; `CostDetailTabs.tsx:278` → `<ChargesByServiceSection>`;
   `ChargesByServiceSection.tsx:930` → `<ServiceResourcesDrawer>`; `ServiceResourcesDrawer.tsx:929` →
   `<ResourceCostDrawer>`. All confirmed. `ChargesByServiceSection.tsx:209` already documents the exact
   "Nullable rather than merely optional because `CostDetailTabs` defaults it to the AWS [bundle]"
   convention the design reuses.

3. **`ResourceIdResolver.resolve(Long, Collection<String>)` → `Map<String,CanonicalResource>`.** Record
   `CanonicalResource(resourceId, resourceType, resourceName, region)` present; rows indexed under both
   `row.getArn()` and `row.getResourceId()`; canonical value built from `row.getResourceId()` (the short
   id); blanks stripped so tax/support rows never resolve. The ARN↔short-id duality the design relies on
   is real. (Line numbers are ~1–2 off — see NIT 4.)

4. **`CloudSecurityFindingRepository.findByResource(Long cloudAccountId, String arn)`** — JPQL `WHERE
   f.cloudAccountId = :cloudAccountId AND f.resourceArn = :arn`. Takes the internal `Long` and matches on
   ARN, exactly as the NFR-15 table states. The repository's own class doc confirms every read is scoped
   by `cloudAccountId` (the `cloud_account` row), never the 12-digit id — so the tenant-isolation
   reasoning holds.

5. **`ComplianceOpsService.getReport(String accountId, int trendDays)`** treats `accountId` as the
   internal id: `findFirstByCloudAccountIdAndStatusOrderByStartedAtDesc(accountId, …)`,
   `ownerRepo.findByCloudAccountId(accountId)`, etc. So `String.valueOf(account.getId())` is the correct
   form. Confirmed. `ComplianceReport.ResourceRef.uid` is set from `ComplianceFinding.getResourceUid()`
   (`resource_uid` column), and controls carry `resources[]` — the walk the design describes is feasible.

6. **`PerformanceInsightsService.getInsights(String accountId, String severity, boolean forceRefresh)`**
   resolves via `getAccount` → `findByAwsAccountId(accountId)`, throwing `RuntimeException("Account not
   found")` on empty — so it needs the **12-digit AWS id** (`account.getAwsAccountId()`). The cold-cache
   "Starting multi-region performance insights scan" branch exists. `PerformanceInsightDto` carries
   `resourceId` (via `@Data`). All confirmed.

7. **`CloudAccount.getAwsAccountId()`** returns `String awsAccountId` (field at `:25`, getter at `:225`).
   Confirmed. The design's two account-id forms (`getId()` Long, `getAwsAccountId()` String) are both
   real.

8. **`SecuritySeverity.normalize(String)`** maps `WARNING→MEDIUM`, `WEAK_WARNING→LOW`, is a String-keyed
   switch defaulting unknown to `MEDIUM`, and exposes `fromGuardDutyScore`, `rank`, `worstOf`, `RANKED`,
   and the five constants. Confirmed.

9. **Controller prologue + precedence.** `CurInsightsController` is `@RestController
   @RequestMapping("/api/xamops/cur-insights")`. `resourceChange` checks `resourceId.isBlank()` → 400
   **before** `denyIfNotEntitled`, then `parsePeriod`/`invalidPeriod`, then `insightCache.getOrCompute`,
   and `computeResourceChange` resolves `findByAwsAccountId(accountId)` with 404 on empty. The design's
   copied prologue matches this ordering exactly.

10. **SecOps vs Security Center = one product.** No `/secops` route anywhere; `/security` →
    `SecurityPage` is the single surface. Confirmed. (ambiguity (a))

11. **Destination client fields exist and no page reads a query param today.**
    `SecurityFinding.resourceId: string`, `PerformanceInsight.resourceId?: string`,
    `ComplianceResourceRef.uid: string | null`; `AlertBase` carries only
    `id/service/name/description/region/severity` and the `Alert` union adds no resource field. Grepping
    `useSearchParams`/`location.search`/`URLSearchParams` across SecurityPage, PerformancePage,
    ComplianceOpsPage, AlertsPage returns zero hits — the `?resource=` handlers are genuinely new.
    (ambiguity (b))

12. **Alerts ⊆ Security Center dedup.** `SecurityFindingProjectionService.asAlerts` reads
    `findActiveBySeverityIn(cloudAccountId, [CRITICAL, HIGH])` from the same `CloudSecurityFinding` table
    and builds `AlertDto` with **no** `resources` and no resource/control id; `AlertDto` has no
    `resourceId`/`controlId`; `dedupKey` is `private static` over `DashboardData.SecurityFinding`. So the
    design's decision to not emit a separate Alerts group, and its note that any future dedup must use a
    new entity-level `aggKey` (not `dedupKey`, not `AlertDto`), is correct and buildable. (ambiguity 3)

13. **WAR is control-level.** `WarFinding` has `controlId/category/title/severity/evidence/region/
    affectedCount` but **no** `resourceArn`/`resourceId`. The v1 exclusion is evidence-backed, not a
    guess.

14. **SOC2 landing.** `Soc2ProgramPage` defaults `tab = 'overview'`; `cardTabs = ['frameworks',
    'integrations', 'documents', 'cloud-tests']`; the cloud link is `/soc2-program/cloud-tests`. So
    `/soc2-program/overview` is a valid landing and `cloud` is **not** a valid tab value — the design's
    SOC2 claim is correct.

15. **`useCurInsight` cannot carry `resourceId`.** Signature `(name, accountId, period, fetcher)` with
    key `[CUR_INSIGHTS_KEY, name, accountId, period ?? 'current']`. The design's justification for a
    plain `useQuery` instead is accurate. `apiFetch` (`apiClient.ts:146`), `readShape`
    (`curInsightsApi.ts:28`), `isRecordWith` (`:58`) all exist; the new `fetchResourceSecurity` lives in
    the same module so the module-private `readShape`/`isRecordWith` are reachable without new exports.

16. **`asSecurityFindings` sets the client `resourceId` to the ARN** (`f.getResourceArn() != null ?
    f.getResourceArn() : "—"`). This is what makes `/security?resource=<arn>` filtering on
    `finding.resourceId === resource` match for live-finding rows (see MEDIUM 1 for the caveat on
    non-live rows).

## Unverified / Wrong Assumptions

- **ComplianceOps `resource_uid` == CUR ARN candidate (partially verified).** `ResourceRef.uid` is
  populated from Prowler's `resource_uid`. For most AWS resource types this is the ARN, but the design
  does not prove it is the ARN for every type the matcher will see. The design does hedge ("ARN or
  provider resource id") and treats a miss as a legitimate empty, so this is a NIT, not a break (NIT 6).

- **Security Center deep-link focus across all `/security/findings` sources (not verified).** The design
  asserts the deep-link will focus the clicked Security Center finding on `SecurityPage`, but
  `SecurityPage` renders the merged `/api/xamops/security/findings` payload (Prowler + describe-based +
  live), and only the live projection is proven to carry the ARN in `resourceId`. See MEDIUM 1.

---

## Findings

### 1. MEDIUM — Security Center deep-link focus is only verified for live-projection rows, not for the merged `/security/findings` payload the destination page actually renders

The aggregator reads Security Center rows via `findByResource(…, arn)` and emits a deep-link
`/security?resource=<arn>`. Acceptance criterion 6 and ambiguity (b) row 1 assert `SecurityPage` "filters
its findings list to rows whose `finding.resourceId === resource`". That filter is literally correct for
the handler the design adds, but **`SecurityPage` does not render the aggregator's rows** — it renders
the merged payload from `/api/xamops/security/findings` (`useSecurityData.ts`), which combines Prowler
results, `SecurityService` describe-based checks, and the live `CloudSecurityFinding` projection. Only the
live projection (`SecurityFindingProjectionService.asSecurityFindings`) is verified to put the **ARN** in
`resourceId`; the design never checks what Prowler/describe-based rows put there. If those rows key
`resourceId` by a short id or a Prowler-native uid, the deep-link will land on `/security` but **fail to
isolate the clicked finding** — the exact "focus it" behaviour the brief asks for ("when we click on that
finding then it will redirect to … where we are showing that finding for that resource").

This degrades gracefully (the page still loads; the filter just may show nothing or everything), so it is
MEDIUM, not HIGH. But the design presents the focus as working without the evidence.

**Concrete fix:** add one verification step and state the result in ambiguity (b): read what
`useSecurityData.processFindings` receives for a non-live source (trace `/api/xamops/security/findings`
in `SecurityController` → `SecurityService.getComprehensiveSecurityFindings` and the Prowler mapping) and
confirm whether those rows set `resourceId` to the ARN. If they do, say so with the file/line. If they do
**not**, change the destination filter to be ARN-tolerant — e.g. match when
`finding.resourceId === resource || resource.endsWith('/' + finding.resourceId) || resource.endsWith(':' +
finding.resourceId)` — or restrict the Security Center deep-link to the subset of rows known to carry the
ARN and navigate param-less otherwise. Either way the design must stop asserting focus works until the
cross-source `resourceId` form is pinned.

### 2. MEDIUM — The ComplianceOps deep-link has the same cross-source focus risk and no stated fallback

Ambiguity (b) row 4 deep-links `/complianceops?resource=<uid>` and criterion 8 says the page
"expands/scrolls to controls whose `resources[].uid === resource`". The aggregator derives the match
candidate as the **ARN** (`arnCandidate`), but `ComplianceControl.resources[].uid` is Prowler's
`resource_uid`, which the design itself admits may be "ARN or provider resource id". So when `uid` is a
provider id and the CUR id is an ARN (or vice-versa), the server-side emit `uid === arn` **and** the
client-side filter `resources[].uid === resource` can both miss — and unlike Security Center there is no
second identifier to fall back on. The design treats a server-side miss as "no rows" (acceptable), but
for rows that *are* emitted it still assumes the client filter re-matches on the same string, which is
only true if the emitted `uid` and the deep-link param are byte-identical.

**Concrete fix:** make the deep-link param the **exact `uid` string the aggregator matched on**, not the
`arnCandidate`. i.e. when ComplianceOps contributes a row, set `deepLink.query.resource =
ref.uid()` (the value that produced the match), so the destination filter `resources[].uid === resource`
is guaranteed to re-match the same control. State this explicitly in the Product-coverage item 4 and in
the response-shape notes (today both imply the param is the ARN).

### 3. NIT — `PerformanceInsightDto.getSeverity()` returns an enum, not a String; `SecuritySeverity.normalize` takes a String

Product coverage item 6 and NFR 14 say Performance severities are normalised via
`SecuritySeverity.normalize`. But `PerformanceInsightDto.severity` is the enum `InsightSeverity {CRITICAL,
WARNING, WEAK_WARNING}`, and `normalize(String)` takes a String. The aggregator must call
`SecuritySeverity.normalize(insight.getSeverity().name())`. Harmless once implemented, but the design
should write the `.name()` so an implementer does not pass an enum to a `String` parameter and reach for
a wrong overload.

### 4. NIT — Residual line-number drift in a few citations

Several `:line` anchors are 1–2 lines off against the worktree (e.g. `ResourceIdResolver` record/`resolve`/
`index` land a line or two from the cited `:79/:93/:116`; `CloudAccount` `awsAccountId` field is at `:25`
not `:24`). The design claims all anchors were re-pinned; most are exact, a handful are not. Non-blocking,
but re-pin the resolver and `CloudAccount` field anchors so they do not rot further.

### 5. NIT — The design does not state how the aggregator obtains an entitlement check equivalent to `denyIfNotEntitled` for each product read

NFR 15 says isolation holds because the account is resolved once and entitlement-checked before any read.
That is true at the controller (`denyIfNotEntitled(accountId)` runs before `getOrCompute`). But the
aggregator then re-resolves `findByAwsAccountId(account.getId()?)` internally and reads three products by
three different id forms. The design should state plainly that the aggregator takes the **already-resolved
and already-entitled** `CloudAccount` (or that the controller's `denyIfNotEntitled` fully covers the
account before the supplier runs), so no product read can be reached for an unentitled account. It is
implied by the prologue copy but never asserted for the aggregator boundary; one sentence removes the
ambiguity.

### 6. NIT — ComplianceOps `resource_uid` → CUR ARN equivalence is assumed, not proven for all resource types

`ResourceRef.uid` comes from Prowler's `resource_uid`. The design's claim that this matches a CUR ARN is
reasonable for most AWS types but unproven in general. The design already treats a miss as a legitimate
empty, so this is informational-adjacent — recorded as a NIT so a later implementer knows the match is
best-effort, not guaranteed, and does not file a bug when a particular resource type does not focus.

---

## Why the verdict is CHANGES_REQUESTED

Two MEDIUM findings (1 and 2) are present. Both are the *same class of problem the brief singled out*:
the per-resource finding deep-link must actually focus the finding on the destination page, and the
design asserts that it does without verifying that the identifier the destination filters on is the same
string the aggregator emits across every source feeding that page. Neither is an architectural break —
the feature loads and degrades gracefully — but both leave the headline behaviour ("click a finding →
land focused on it") unverified, which is exactly the kind of unverified assumption this review exists to
catch. Fix both (verify the Security Center cross-source `resourceId` form; make the ComplianceOps
deep-link param the matched `uid`) and the design is ready.
