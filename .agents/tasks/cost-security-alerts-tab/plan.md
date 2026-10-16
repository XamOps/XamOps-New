# Implementation Plan — Add XamSecure (Upwind) as the 4th product group in the Cost "Security & Alerts" tab

All work happens in the worktree:
`/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/security-tab-xamsecure`
(branch `feat/security-tab-xamsecure`, base `main`). Use
`git -C /Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/security-tab-xamsecure` for git.
Never touch the main checkout at `/Users/admin/Desktop/xam-latest/XamOps-New`.

## Design decisions (confirmed by reading the code, not assumed)

1. **Reuse existing entities + repositories; add NO tables and NO ingest.** Both
   `com.xammer.cloud.domain.UpwindPostureFinding` and
   `com.xammer.cloud.domain.UpwindVulnerabilityFinding` already exist and are `@Entity`-mapped.
   - **`cloudAccountId` is a `String`** mapped to column `cloud_account_id` on BOTH entities (plain
     `@Column(name = "cloud_account_id")`). It holds the **12-digit AWS account id** as a string
     (e.g. `"605134457560"`), matching `account.getAwsAccountId()`. This is the value every Upwind
     repository query already binds via its `accountIds` list, so XamSecure must query with
     `List.of(account.getAwsAccountId())` — NOT `account.getId()` (that Long is what Security Center /
     ComplianceOps use).
   - **`resourceId` is a `String`** mapped to `resource_id`, holding the **full ARN**
     (`arn:aws:s3:::bucket`, `arn:aws:ec2:...:instance/i-...`, `.../volume/vol-...`). So the match is
     ARN-primary, exactly like Security Center.

2. **Reuse the already-present per-resource repository methods — no new posture method needed.**
   - Posture: `UpwindPostureFindingRepository.findFailingByResource(List<String> accountIds, String resourceId)`
     returns `List<UpwindPostureFinding>` and is already filtered to `status = 'FAIL'` and ordered
     `lastSeenTime DESC`. This is the correct "problems only" set — reuse it verbatim.
   - Vulnerability (drill list, NOT used for the summary count path):
     `UpwindVulnerabilityFindingRepository.findOpenByResource(List<String> accountIds, String resourceId)`
     exists and is filtered to `status = 'OPEN'`. We will NOT call this in the aggregator (it would
     stream rows); it is noted only to show the per-resource access pattern already exists.

3. **Vulnerability summary without loading 727K rows.** Add ONE new derived aggregate query on the
   EXISTING `UpwindVulnerabilityFindingRepository`: a per-resource severity breakdown COUNT (mirrors
   the existing account-wide `countOpenBySeverity`, but scoped to one `resourceId`). The aggregator
   builds the single summary row from that `List<Object[]>` of `(severity, count)` — never loading
   entities. Rationale: the existing `countOpenBySeverity` proves the GROUP BY shape works; scoping it
   to one resource is the minimal, index-friendly addition and keeps the OPEN filter the repo's
   standing note requires.

4. **Severity.** Reuse `SecuritySeverity`: `normalize(row.getSeverity())` for each posture row, and
   for the vulnerability summary compute the worst severity across the matched severity buckets with
   `SecuritySeverity.worstOf(...)` (its Javadoc already names Upwind as an upper-case source).

5. **Deep-link decision (documented choice):** the XamSecure page is `/xamsecure/:tab` (App.tsx), and
   its tabs (`posture`, `vulnerabilities`) are **server-filtered tables driven by `useXamSecureData`**;
   `XamSecurePage` reads only `useParams().tab`, and **no XamSecure tab reads a `?resource=` search
   param** (confirmed: only `OnboardingTab` uses `useSearchParams`, for `?add=`). Threading a resource
   filter through `useXamSecureData` → the hook → the API is NOT a cheap/safe read-only change, so per
   the task's own guidance we take the **graceful no-param deep link**, mirroring the Alerts decision:
   - posture findings deep-link to `path = "/xamsecure/posture"`, no query.
   - the vulnerability summary deep-links to `path = "/xamsecure/vulnerabilities"`, no query.
   The group's own `route` is `"/xamsecure"`. `SecurityDeepLink` requires a non-null `query` map in
   current rows, so pass `Map.of()` (empty) — `ResourceSecurityTab.toSearch({})` already returns `''`,
   producing the bare path with no trailing `?`.

6. **Frontend needs no structural change.** `ResourceSecurityTab.tsx` renders `groups.map(...)`
   generically and `curInsightsApi.ts` types `product` as `string` (no union to extend). The only
   frontend edit is cosmetic: the empty-state sentence hard-codes the three product names and should
   also mention XamSecure.

7. **Entitlement + account resolution unchanged.** The controller runs `denyIfNotEntitled` before
   `aggregate(...)`; the single resolved `CloudAccount` is reused, exactly as the other three groups do.

## Files to touch

- `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindVulnerabilityFindingRepository.java`
  (add one aggregate query method)
- `xamops-service/src/main/java/com/xammer/cloud/service/security/ResourceSecurityAggregationService.java`
  (add constructor deps + `PRODUCT_XAMSECURE` + `xamSecureGroup(...)` + wire into `aggregate`)
- `xamops-service/src/test/java/com/xammer/cloud/service/security/ResourceSecurityAggregationServiceTest.java`
  (new mocks + new test cases; existing tests must keep passing — their constructor call changes)
- `frontend-app/src/components/Cost/ResourceSecurityTab.tsx` (empty-state copy only)

No new files are required.

---

## Steps

- [ ] 1. Add a per-resource open-vulnerability severity breakdown query to the EXISTING
      `UpwindVulnerabilityFindingRepository`.
      Add exactly one method, following the existing `countOpenBySeverity` JPQL and the repo's
      standing `status = 'OPEN'` rule:
      ```java
      @Query("SELECT v.severity, COUNT(v) FROM UpwindVulnerabilityFinding v " +
             "WHERE v.cloudAccountId IN :accountIds AND v.resourceId = :resourceId " +
             "AND v.status = 'OPEN' GROUP BY v.severity")
      List<Object[]> countOpenBySeverityForResource(@Param("accountIds") List<String> accountIds,
                                                     @Param("resourceId") String resourceId);
      ```
      Do NOT add a vulnerability "list by resource" method for the aggregator — `findOpenByResource`
      already exists and we deliberately do not call it (no 727K-row load).
      Files: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindVulnerabilityFindingRepository.java`
      Verify: `mvn -q -pl xamops-service compile` succeeds (the module compiles with the new method).

- [ ] 2. Wire the XamSecure group into `ResourceSecurityAggregationService`.
      Depends on step 1 for the vulnerability count method; posture reuses the existing
      `findFailingByResource`.
      - Add two constructor params and fields: `UpwindPostureFindingRepository upwindPostureRepository`
        and `UpwindVulnerabilityFindingRepository upwindVulnerabilityRepository` (Spring injects both;
        update the single constructor). Keep the existing params/order and append the two new ones.
      - Add `static final String PRODUCT_XAMSECURE = "XAMSECURE";`.
      - Add a `xamSecureGroup(CloudAccount account, String arnCandidate, String shortIdCandidate,
        List<ProductError> errors)` method mirroring `securityCenterGroup`'s structure:
        * Create `new ResourceSecurityGroup(PRODUCT_XAMSECURE, "XamSecure", "/xamsecure", new ArrayList<>())`.
        * Guard: if `arnCandidate == null` (and `shortIdCandidate` is null/blank), return the empty group
          (same guard shape as Security Center).
        * Wrap the whole read in one `try/catch (RuntimeException e)` that logs with
          `account.getAwsAccountId()` and adds `new ProductError(PRODUCT_XAMSECURE, "XamSecure findings could not be read.")`
          — a failing Upwind read must NOT fail the other groups.
        * Build `List<String> accountIds = List.of(account.getAwsAccountId());`.
        * **ARN-primary match keys:** try `arnCandidate` first; also try `shortIdCandidate` as a
          secondary key when it is non-null, non-blank and not equal to `arnCandidate` (covers rows that
          may store a short id). Collect matched posture rows and the vulnerability severity-count map
          across both keys, de-duplicating posture rows by `getUpwindId()`.
        * **POSTURE:** for each matched `UpwindPostureFinding` row emit one `ResourceSecurityFinding`:
          `title = checkTitle != null ? checkTitle : title`,
          `severity = SecuritySeverity.normalize(row.getSeverity())`,
          `detail/resourceType = row.getResourceType()`,
          `deepLink = new SecurityDeepLink("/xamsecure/posture", Map.of())`.
        * **VULNERABILITY (one summary row max):** call `countOpenBySeverityForResource(accountIds, key)`
          for each key, merge the `(severity, count)` pairs into a `Map<String,Long>` keyed by
          `SecuritySeverity.normalize(severity)` (summing counts). If total > 0, emit exactly ONE
          `ResourceSecurityFinding`:
          - `severity = SecuritySeverity.worstOf(mergedMap.keySet())`,
          - `title` like `"<total> vulnerabilities (<criticalCount> critical, <highCount> high)"`
            (include the critical/high counts from the merged map; show 0s if absent),
          - `detail = null` or a short label,
          - `deepLink = new SecurityDeepLink("/xamsecure/vulnerabilities", Map.of())`.
          Never iterate vulnerability entities.
        * `sortBySeverity(group)` at the end (reuse the private helper).
      - In `aggregate(...)` add `groups.add(xamSecureGroup(account, arnCandidate, shortIdCandidate, errors));`
        after the `performanceGroup(...)` line, so XamSecure is the fourth group. The existing
        `groups.removeIf(empty)` drop and `errors` wiring already cover it.
      - Update the class Javadoc that says "across all three product reads" / lists three products to
        say four and name XamSecure.
      Files: `xamops-service/src/main/java/com/xammer/cloud/service/security/ResourceSecurityAggregationService.java`
      Verify: `mvn -q -pl xamops-service compile` succeeds.

- [ ] 3. Extend `ResourceSecurityAggregationServiceTest` for XamSecure and fix the constructor call.
      Depends on steps 1-2.
      - Add two `@Mock`s: `UpwindPostureFindingRepository upwindPostureRepository` and
        `UpwindVulnerabilityFindingRepository upwindVulnerabilityRepository`, and pass them to the
        `new ResourceSecurityAggregationService(...)` call in `setUp()` (the existing tests then keep
        compiling; add `lenient()` empty-return stubs in `setUp()` so unrelated tests still see empty
        XamSecure groups and their group-count assertions can be updated as below).
      - Because the three "all products" assertions count groups, update expectations where XamSecure
        now contributes (or stub the Upwind mocks to return empty for those cases so the count stays as
        the test intends — prefer empty stubs so existing assertions like `assertEquals(3, groups)` in
        `groupsNormalisesAndSorts` stay meaningful; add a dedicated XamSecure test instead).
      - New test cases (follow the existing helper/style, mock the Upwind repositories):
        1. **XamSecure group appears with posture rows + ONE vulnerability summary at worst severity.**
           Stub `upwindPostureRepository.findFailingByResource(List.of(AWS_ID), ARN)` to return two
           posture entities (e.g. severities `"HIGH"` and `"CRITICAL"`); stub
           `upwindVulnerabilityRepository.countOpenBySeverityForResource(List.of(AWS_ID), ARN)` to return
           `List.of(new Object[]{"CRITICAL", 2L}, new Object[]{"HIGH", 5L})`. Assert the `XAMSECURE`
           group exists, its posture findings are present individually and sorted CRITICAL-first, and
           there is exactly ONE summary finding whose severity is `CRITICAL` and whose title contains
           the counts (e.g. `"7 vulnerabilities"`, `"2 critical"`, `"5 high"`). Assert the posture
           deep-link path is `/xamsecure/posture` and the summary deep-link path is
           `/xamsecure/vulnerabilities`.
        2. **Empty when no ARN.** With a resolved row whose `arn()` is null and a non-ARN short id,
           assert no `XAMSECURE` group is present (dropped as empty) and the Upwind repos are not
           required to return anything meaningful.
        3. **A failing Upwind read becomes an `errors[]` entry and does NOT fail other groups.** Stub
           `upwindPostureRepository.findFailingByResource(...)` to throw `RuntimeException`; assert the
           response still 200s, other groups still render, and `errors` contains a `XAMSECURE` entry.
      Files: `xamops-service/src/test/java/com/xammer/cloud/service/security/ResourceSecurityAggregationServiceTest.java`
      Verify: `mvn -q -pl xamops-service -am test -Dtest=ResourceSecurityAggregationServiceTest` — all
      cases pass.

- [ ] 4. Update the frontend empty-state copy to mention XamSecure (cosmetic only).
      In `ResourceSecurityTab.tsx`, the "No findings" paragraph currently reads
      "Nothing in Security Center, ComplianceOps or Performance Insights points at this resource."
      Change it to include XamSecure, e.g.
      "Nothing in Security Center, ComplianceOps, Performance Insights or XamSecure points at this
      resource." No type or rendering change is needed — groups already render generically and
      `product` is typed `string`.
      Files: `frontend-app/src/components/Cost/ResourceSecurityTab.tsx`
      Verify: covered by step 6 (frontend build + tests).

- [ ] 5. Run the full backend module test suite to confirm nothing regressed.
      Confirms the whole module still builds and the specifically-named existing suites stay green:
      `ResourceSecurityControllerTest`, `CurInsightsControllerTest`, `ResourceIdResolverTest`, and the
      Upwind repository tests.
      Files: none (verification).
      Verify: `mvn -q -pl xamops-service -am test` — BUILD SUCCESS, 0 failures/errors. (If the full
      module run is too slow in-sandbox, at minimum run
      `mvn -q -pl xamops-service -am test -Dtest=ResourceSecurityAggregationServiceTest,ResourceSecurityControllerTest,CurInsightsControllerTest,ResourceIdResolverTest,UpwindVulnerabilityFindingRepositoryTest`
      and record that scope.)

- [ ] 6. Run the frontend build and tests.
      Files: none (verification).
      Verify: in `frontend-app`, `npm run build` (icons bundle + `tsc` typecheck + vite build) succeeds
      and `npm run test` (vitest run) passes. The copy change in step 4 must not break any snapshot or
      text-matching test in `frontend-app/src/components/Cost/__tests__/`; update such a test's expected
      string if it asserts the old sentence.

- [ ] 7. Read-only data-sanity checks (psql; do not mutate).
      Confirm the match keys and counts the code relies on, against the live local DB. These are
      SELECT-only.
      Files: none (verification).
      Verify (expected: non-zero rows for account `605134457560`):
      ```sql
      -- posture rows are keyed by 12-digit account id and full ARN
      SELECT cloud_account_id, count(*) FROM upwind_posture_finding
       WHERE cloud_account_id = '605134457560' GROUP BY 1;        -- ~30529
      SELECT resource_id, count(*) FROM upwind_posture_finding
       WHERE cloud_account_id = '605134457560' AND status = 'FAIL'
         AND resource_id LIKE 'arn:aws:s3:::%' GROUP BY 1 LIMIT 5;
      -- vulnerability severity breakdown for one real resource ARN (shape the summary consumes)
      SELECT severity, count(*) FROM upwind_vulnerability_finding
       WHERE cloud_account_id = '605134457560' AND status = 'OPEN'
         AND resource_id = (SELECT resource_id FROM upwind_vulnerability_finding
                            WHERE cloud_account_id = '605134457560' AND status = 'OPEN'
                              AND resource_id LIKE 'arn:aws:ec2:%:instance/%' LIMIT 1)
       GROUP BY severity;
      ```
      A non-empty posture result and a non-empty per-resource severity breakdown confirm the ARN-keyed,
      12-digit-account match the aggregator performs will actually return rows in the demo account.

## Notes / assumptions
- If, during implementation, the resolver's `CanonicalResource.arn()` turns out to be null for the S3
  short-id path in the demo data, the ARN-primary match still works for already-ARN `resourceId`s; the
  `shortIdCandidate` secondary key is the fallback and is why both keys are tried. This mirrors the
  existing Security Center guard and needs no new resolver work.
- No new product constant or union is needed on the frontend; `product: string` already accepts
  `"XAMSECURE"`. If a label/icon map for products is later added, map `XAMSECURE → "XamSecure"`.
