# Implementation Plan — Security & Alerts cost-drawer: XamSecure dedup (Bug 1) + Security Center parity (Bug 2)

All work happens in the worktree
`/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix`
(branch `fix/sec-tab-dedup-and-security-center`, base `main`). Use
`git -C /Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix` for git.
Never touch the main checkout at `/Users/admin/Desktop/xam-latest/XamOps-New`.

Evidence for every decision below is recorded in
`/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/.agents/tasks/cost-security-alerts-tab/dup-and-security-center-fix-verification.md`.

## What is already done (do NOT redo)

- Commit `d082b9f05` already rewrote `securityCenterGroup(...)` to read the live merged snapshot
  under `"security-" + account.getAwsAccountId()` (12h max age) → `liveFindings.mergeInto` →
  `resolutions.filterResolved`, then union the legacy `cloud_security_finding` table, deduped by
  title+resource. **Bug 2's reader side and the key-form fix (2a) are DONE.** The reader key form is
  correct: reader and both writers all use the 12-digit AWS id (see verification §2). The aggregator
  injects neither `SecurityService` nor `CloudListService`, so it can never trigger a scan — this
  invariant MUST be preserved.

## Decisions (grounded in code read during exploration)

1. **Bug 1 dedup key = normalized `checkTitle` (fallback `title`) + `resourceType` + `region`**, NOT
   `upwind_id`. Evidence: `UpwindPostureFinding` carries `checkTitle`, `title`, `resourceType`,
   `region`, `severity`, `status`, `syncedAt`, `lastSeenTime`, `upwindId`. The Upwind sync appends one
   row per historical scan (distinct `upwind_id` each), so the existing `upwindId` dedup is a no-op.
   For `xamopscftemplate`, 216 FAIL rows collapse to 5 distinct `(checkTitle, region)` groups.
2. **When rows share a key, keep ONE**, preferring most recent by `syncedAt` (fall back to
   `lastSeenTime`, then the first non-null / first seen), and the WORST severity among the group via
   `SecuritySeverity.worstOf(...)` / `rank`. Rationale: the surviving row's displayed severity must
   not depend on arbitrary row order; worst-severity is the safe choice for a security surface.
3. **Preserve existing behavior**: most-severe-first ordering (`sortBySeverity`), the
   `/xamsecure/posture` deep link with empty query, the FAIL-only filter (`findFailingByResource`
   already excludes RESOLVED — confirmed), and the cross-key de-dup (ARN key + short-id key). The
   vulnerability summary path is unchanged.
4. **Bug 2 Option B**: `SecurityController.getSecurityFindings` live-scan path persists its result via
   `dashboardDataService.saveSecuritySnapshot("security-" + accountId, merged)` — SAME 12-digit key
   the reader uses — inside the EXISTING live-scan `.thenApply(...)` branch only. No new scan is ever
   triggered. After a user opens `/security` once, the snapshot exists and the drawer reaches parity.
   Persist the `merged` list (post-merge, post-reconcile) so the snapshot matches what the page shows.
5. **No frontend change.** The tab renders groups generically; the fix is backend-only. Do not run the
   frontend build unless a TS file is touched.

## Steps

- [ ] 1. Dedupe XamSecure posture findings by a stable identity instead of `upwind_id`.
      In `ResourceSecurityAggregationService.xamSecureGroup(...)`, replace the POSTURE loop's
      `upwind_id`-only de-dup with a group-by over a stable key
      `normalize(checkTitle!=null?checkTitle:title) + "|" + resourceType + "|" + region` (normalize =
      trim+lowercase, null-safe). Across all match keys, collect candidate rows into a
      `Map<String, UpwindPostureFinding>`: when a key repeats, keep the row preferring most recent
      `syncedAt` (fall back to `lastSeenTime`, then keep the first non-null), and track the WORST
      severity seen in that group (via `SecuritySeverity.rank` / `worstOf`) to set the emitted
      finding's severity. Emit one `ResourceSecurityFinding` per surviving key with
      `title = checkTitle!=null?checkTitle:title`, worst severity, `resourceType`, and the existing
      `new SecurityDeepLink("/xamsecure/posture", Map.of())`. Keep `sortBySeverity(group)` and the
      vulnerability summary path untouched. Keep FAIL-only (do not loosen `findFailingByResource`).
      Files: `/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/xamops-service/src/main/java/com/xammer/cloud/service/security/ResourceSecurityAggregationService.java`
      Verify: `mvn -q -pl xamops-service compile` succeeds (from the worktree root).

- [ ] 2. Add XamSecure dedup tests to `ResourceSecurityAggregationServiceTest`.
      Extend the `posture(...)` helper (or add an overload) to also set `region`, `syncedAt`,
      `lastSeenTime`. Add: (a) three posture rows with the SAME `checkTitle`+`region`+`resourceType`,
      different `upwindId` and `syncedAt`, differing severities → assert EXACTLY ONE XamSecure posture
      finding, carrying the WORST severity and (optionally) corresponding to the most-recent
      `syncedAt` row's title; (b) two rows with DIFFERENT `checkTitle` → TWO posture findings. Keep the
      existing one-vuln-summary assertion compatible (stub vuln repo empty in the new cases so only
      posture rows are asserted). All existing cases in this file must stay green.
      Files: `/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/xamops-service/src/test/java/com/xammer/cloud/service/security/ResourceSecurityAggregationServiceTest.java`
      Verify: `mvn -q -pl xamops-service -am test -Dtest=ResourceSecurityAggregationServiceTest` — all cases pass.

- [ ] 3. Persist the live-scan snapshot in `SecurityController.getSecurityFindings` (Bug 2 Option B).
      In the live-scan fallback `.thenApply(findings -> { ... })` block, after `merged` is computed
      (post `mergeInto` + `reconcileAgainstFreshScan`) and before returning, call
      `dashboardDataService.saveSecuritySnapshot("security-" + accountId, merged)` (same `cacheKey`
      already built above; reuse it). Do NOT add any new scan trigger, do NOT touch the cached branch,
      and do NOT change the aggregator. This is the ONLY place a scan result becomes a persisted
      snapshot from the page path.
      Files: `/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/xamops-service/src/main/java/com/xammer/cloud/controller/SecurityController.java`
      Verify: `mvn -q -pl xamops-service compile` succeeds.

- [ ] 4. Add a focused SecurityController test asserting the live path persists under the reader's key.
      There is no existing `SecurityControllerTest` (only `ResourceSecurityControllerTest`). Create
      `SecurityControllerSnapshotPersistTest` (Mockito, mock all 9 constructor deps). Drive
      `getSecurityFindings(accountId, forceRefresh=true)` so the cached branch is skipped and the
      live-scan branch runs: stub `cloudAccountRepository.findByAwsAccountId` → one account;
      `cloudListService.getRegionStatusForAccount` → `CompletableFuture.completedFuture(List.of())`;
      `securityService.getComprehensiveSecurityFindings` → a completed future with a small findings
      list; `liveFindings.mergeInto` and `resolutions.reconcileAgainstFreshScan` pass-through. `.join()`
      the returned future, then `verify(dashboardDataService).saveSecuritySnapshot(eq("security-"+AWS_ID), any())`.
      Add one negative assertion that the forceRefresh=false cached-hit branch does NOT call
      `saveSecuritySnapshot` (`verify(dashboardDataService, never())...`).
      Files: `/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/xamops-service/src/test/java/com/xammer/cloud/controller/SecurityControllerSnapshotPersistTest.java`
      Verify: `mvn -q -pl xamops-service -am test -Dtest=SecurityControllerSnapshotPersistTest` — passes.

- [ ] 5. Full backend module verification + keep named suites green.
      Files: none (verification).
      Verify: `mvn -pl xamops-service -am test` from the worktree root → BUILD SUCCESS, 0
      failures/errors. These must stay green: `ResourceSecurityAggregationServiceTest`,
      `ResourceSecurityControllerTest`, `CurInsightsControllerTest`, `ResourceIdResolverTest`,
      `SecuritySeverityTest`, `SecurityControllerSnapshotPersistTest`. Known pre-existing flaky suite
      `AwsPermissionCatalogKubernetesTest`: if it fails, confirm the identical failure on untouched
      `main` and that it is the only failure; otherwise the run must be clean.

- [ ] 6. Record final evidence.
      Append the `mvn` result and the final confirmed behavior (5 XamSecure findings for
      `xamopscftemplate`; snapshot persisted by the page's live path under `security-605134457560`) to
      `/Users/admin/Desktop/xam-latest/XamOps-New/.worktrees/sec-tab-dedup-sc-fix/.agents/tasks/cost-security-alerts-tab/dup-and-security-center-fix-verification.md`.
      Files: that verification doc.
      Verify: doc updated; no code change.

## Invariant (must hold at all times)

The cost-drawer aggregation path (`ResourceSecurityAggregationService`) must NEVER call
`getComprehensiveSecurityFindings` or `cloudListService`. A Prowler/comprehensive scan must remain
impossible from the drawer. Snapshot persistence is added ONLY to the `/security` page controller's
pre-existing live-scan branch.
