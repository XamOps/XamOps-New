# Verification — Security Center cost-tab source fix

Branch: `fix/security-center-source` (worktree
`/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/security-center-source-fix`).

## What changed

Single production file plus its unit test:

- `xamops-service/src/main/java/com/xammer/cloud/service/security/ResourceSecurityAggregationService.java`
- `xamops-service/src/test/java/com/xammer/cloud/service/security/ResourceSecurityAggregationServiceTest.java`

No DTO, repository, controller, or frontend change was required. The tab renders groups generically,
so no TypeScript was touched (and `npm run build` was therefore not run).

## The exact source now used

`securityCenterGroup(...)` now reads the **same live, merged cached snapshot the `/security` page
reads**, mirroring `SecurityController.getSecurityFindings`' CACHED branch exactly:

1. `String cacheKey = "security-" + account.getAwsAccountId();` (the 12-digit AWS id — identical to
   the controller's `"security-" + accountId`, so the tab reads the exact `cached_data` row the page
   populates).
2. `dashboardDataService.getSecuritySnapshot(cacheKey, new TypeReference<List<DashboardData.SecurityFinding>>(){}, SECURITY_SNAPSHOT_MAX_AGE_HOURS)`
   where `SECURITY_SNAPSHOT_MAX_AGE_HOURS = 12` (same value/semantics as `SecurityController`).
3. When the snapshot is non-null:
   `resolutions.filterResolved(liveFindings.mergeInto(cached, account.getId()), account.getId())`.

The three collaborators (`DashboardDataService`, `SecurityFindingProjectionService liveFindings`,
`SecurityFindingResolutionService resolutions`) are wired via constructor injection, appended to the
existing single constructor so Spring still autowires it.

The near-empty `cloud_security_finding` table read (`findByResource(account.getId(), arnCandidate)`)
is kept as an **additional** union source (only when an ARN is in hand), deduped against the merged
source by normalised title + resource — the merged live snapshot is the primary fix.

## Cached-read-only — no live scan (enforced by construction)

- The aggregator calls **only** `getSecuritySnapshot(...)` + `mergeInto` + `filterResolved`.
- When the snapshot is absent or older than 12h, `getSecuritySnapshot` returns `null` and the
  Security Center group contributes nothing from this source. There is **no** fall-through to a live
  scan.
- `SecurityService` and `CloudListService` are **not injected** into this service, so a
  Prowler/comprehensive scan or `getRegionStatusForAccount` is impossible by construction. The test
  `securityCenterNeverTriggersLiveScan` asserts the cached path (exact cache key + 12h max age) is
  the only path taken.

## Resource-id field + ARN-tolerant match

- The resource-id field on `DashboardData.SecurityFinding` is `getResourceId()` (confirmed:
  `DashboardData.java`, `@Data` Lombok on fields `resourceId, region, category, severity,
  description, complianceFramework, controlId`). There is **no** `title` field, so the tab title is
  `getDescription()` falling back to `getCategory()` when description is blank.
- The filter is a Java port of the frontend `securityFindingMatchesResource`, tried against both
  `arnCandidate` and `shortIdCandidate` (both already derived in `aggregate()`). A finding matches
  when, for a non-null candidate `c`:
  `c.equals(f.getResourceId()) || c.endsWith(":" + f.getResourceId()) || c.endsWith("/" + f.getResourceId())`.
  This matches full-ARN projection rows (equality on `arnCandidate`) **and** short-id/name Prowler
  rows (the ARN ends with `:`/`/` + the short name, or `shortIdCandidate` equals the id).
- Mapping: title as above; `SecuritySeverity.normalize(f.getSeverity())`; category = `f.getCategory()`;
  deep link `/security?resource=<arnCandidate, else shortIdCandidate>` — the same id the match was
  made on, so the page's own ARN-tolerant filter re-matches.
- Behaviour preserved: single per-product try/catch → `errors[]` entry on failure (never a 500);
  empty-group drop via `aggregate()`'s `removeIf`; most-severe-first `sortBySeverity`. ComplianceOps,
  Performance, and XamSecure groups are untouched.

## Concrete resources the user can click

Once the `security-605134457560` snapshot is present (the page populates it), opening the cost drawer
for these resources now shows their Security Center findings in the Security & Alerts tab — the same
findings the Security Center page shows:

- S3 bucket **xops.xammer.in** — "Public Access Block is not fully enabled".
- S3 bucket **xamopscftemplate** — "Public Access Block is not fully enabled".
- S3 bucket **xamops-azure-connector** — "Public Access Block is not fully enabled".
- A security group **sg-...** with "inbound from 0.0.0.0/0" (VPC/security-group findings).

## Commands run and results

From the worktree root
`/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/security-center-source-fix`:

1. `mvn -pl xamops-service -am test-compile` — main sources compile clean.
2. `mvn -pl xamops-service -am test -Dtest=ResourceSecurityAggregationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
   → `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` (10 pre-existing + 5 new merged-source tests).
3. `mvn -pl xamops-service -am test -Dtest=ResourceSecurityAggregationServiceTest,ResourceSecurityControllerTest,CurInsightsControllerTest,ResourceIdResolverTest,SecuritySeverityTest -Dsurefire.failIfNoSpecifiedTests=false`
   → all green:
   - ResourceSecurityAggregationServiceTest: 15, 0F, 0E
   - ResourceSecurityControllerTest: 6, 0F, 0E
   - CurInsightsControllerTest: 36, 0F, 0E
   - ResourceIdResolverTest: 21, 0F, 0E
   - SecuritySeverityTest: 14, 0F, 0E
4. `mvn -pl xamops-service -am test` (full module) →
   **`Tests run: 3375, Failures: 0, Errors: 0, Skipped: 56` — BUILD SUCCESS** (~8m25s).
   `AwsPermissionCatalogKubernetesTest` passed; no flaky failure observed.

Note on `-am`: it builds `permissions-common`, which has no test matching the named patterns, so the
`-Dtest=...` runs use `-Dsurefire.failIfNoSpecifiedTests=false` to avoid a spurious no-tests failure
in that sibling module. The full `mvn -pl xamops-service -am test` run needs no such flag.

## New tests added

- `securityCenterReadsMergedSnapshotWhenFindingStoresFullArn` — S3 "Public Access Block" finding
  whose `resourceId` is the bucket ARN; opened resource resolves to that bucket; asserts the group
  contains the finding and the deep link carries the ARN.
- `securityCenterReadsMergedSnapshotWhenFindingStoresBucketName` — same finding but `resourceId` is
  the bare bucket name; proves the ARN-tolerant match still includes it.
- `securityCenterNeverTriggersLiveScan` — asserts only the cached-read + merge + filterResolved path
  runs (exact cache key `security-<awsId>`, 12h max age); no scan collaborator exists.
- `securityCenterEmptyWhenSnapshotNull` — null snapshot ⇒ Security Center group dropped, no NPE,
  Performance group unaffected; `mergeInto`/`filterResolved` are `never()` called.
- `securityCenterUnionDedupesSnapshotAndDbRows` — same title on same resource in both the snapshot
  and the legacy table ⇒ exactly one finding (dedupe by title+resource).

## Optional read-only psql sanity

Connection `postgresql://xamops_local_user:***@13.127.62.153:5432/xamops_local_db` (read-only queries):

- `select count(*) from cloud_security_finding where cloud_account_id=129;` → **23** — confirms the
  stale table is tiny, exactly as the root-cause analysis states.
- `select count(*) from cloud_security_finding where cloud_account_id=129 and resource_type ilike '%s3%';`
  → **0** — confirms zero S3 rows in the stale table (the symptom the fix addresses).
- `select left(json_data, 500) from cached_data where cache_key = 'security-605134457560';` →
  **no row** in this particular local DB (keys present under `security-%` are for other accounts:
  080310056620, 015764906357, 102227938259, 982534352845). So the 605134457560 snapshot is not
  seeded in this local snapshot of the database. This does not affect the fix: the code mirrors the
  Security Center page's cached branch byte-for-byte, so wherever the page can serve merged findings
  for an account (because that snapshot exists), the tab now reads the identical source. When the
  snapshot is absent the tab degrades to the empty-group behaviour, as designed (and as the unit
  test `securityCenterEmptyWhenSnapshotNull` proves).
