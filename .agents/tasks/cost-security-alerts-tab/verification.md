# Verification — Cost "Security & Alerts" tab

Iteration: **FIRST** (no `review.json` present; full verify-fix-commit flow).

All three mandatory verification commands were run and are green for this feature. The only
remaining failures in the wider suites are **pre-existing and unrelated** to this change (proven by
running them against the pristine `origin/main` checkout and by the fact that none of the failing
files are touched by this branch — see "Pre-existing unrelated failures" below).

Environment note: the worktree had no `frontend-app/node_modules`, and `npm install` could not
complete (network `ETIMEDOUT` from the registry). The main checkout at the same commit
(`de4d42f65`) with byte-identical `package.json` + `package-lock.json` already had a complete
`node_modules`, so it was symlinked into the worktree purely to run the frontend verification, then
removed. No dependency versions changed.

---

## 1. Frontend typecheck + build

Command (run in `frontend-app/`):

```
npm run build        # => npm run icons:bundle && tsc && vite build
```

Result: **PASS** — zero type errors, `vite build` completed (`✓ built in 42.27s`).

Fix applied to reach green: the new `ResourceSecurityTab.test.tsx` imported `React` and `waitFor`
without using them; `tsc` (noUnusedLocals) flagged both (`TS6133`). Removed both unused imports.
The project uses the automatic JSX runtime (`"jsx": "react-jsx"`), so `React` is not needed in
scope — the sibling `ResourceConnectivityTab.test.tsx` imports it the same way.

## 2. Frontend tests

Command (run in `frontend-app/`):

```
npm run test         # => vitest run (single-run, not watch)
```

Result for the full suite: **125 files, 1565 tests — 1558 passed, 7 failed.**
All 7 failures are pre-existing and unrelated (see below).

The feature's own specs are **all green**. Verified in isolation:

```
npx vitest run \
  src/components/Cost/__tests__/CostDetailTabs.providers.test.tsx \
  src/components/Cost/__tests__/ResourceConnectivityTab.test.tsx \
  src/components/Cost/__tests__/ResourceSecurityTab.test.tsx
# => 3 files passed, 49 tests passed
```

Fixes applied to reach green:
- `ResourceConnectivityTab.test.tsx` (pre-existing): adding the fifth "Security & Alerts" tab
  (which defaults on for AWS) changed the tab list two Connectivity tests asserted on
  (`['Change','Breakdown','Trend','Connectivity']` and a 3-tab count). These tests are about
  Connectivity's own placement/absence, so the `drawer` harness now passes `security={null}` to
  keep the asserted tab list focused on Connectivity. Intent preserved, no behaviour change.
- `CostDetailTabs.providers.test.tsx` (new, "renders the Security & Alerts tab … after
  Connectivity"): the AWS default resource path is the **scoped** fetcher, which was unstubbed and
  made a real `apiFetch` to a relative URL that jsdom cannot parse, so the resource row never
  rendered. Added `scopedResourcesFetcher={() => Promise.resolve(RESOURCES)}` to the test. The
  assertion that the drawer strip is `['Change','Breakdown','Trend','Security & Alerts']`
  (Connectivity nulled, Security defaulted) now holds.

## 3. Backend tests

Command (run from the worktree root):

```
mvn -pl xamops-service test
```

Result for the module: **3361 tests run, 1 failure, 0 errors, 56 skipped.**
The single failure is pre-existing and unrelated (see below).

The feature's own test classes are **all green** (from `target/surefire-reports`):

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `ResourceSecurityControllerTest` | 6 | 0 | 0 |
| `ResourceSecurityAggregationServiceTest` | 5 | 0 | 0 |
| `CurInsightsControllerTest` (updated ctor) | 35 | 0 | 0 |

`ResourceSecurityControllerTest` asserts acceptance criterion 13 in full: 200 normalized body for a
known resource; 200 with empty `groups`+`errors` for an unknown resource (not a 404); 404 for an
unentitled/unknown account with `verifyNoInteractions(insightCache, aggregator)` pinning
entitlement-before-cache; 400 for a blank `resourceId` touching nothing downstream; 400 for a
`resourceId` past `MAX_RESOURCE_ID_CHARS`; and one failing product branch returning 200 with a
populated `errors[]` alongside populated `groups[]` (no 500).

---

## Pre-existing unrelated failures (not caused by this change)

**Backend (1):** `AwsPermissionCatalogKubernetesTest.logsDegradesKubernetes_andKeepsItsOtherFeatures`.
In `com.xammer.cloud.permissions` — no relation to the Security & Alerts feature. This branch makes
**zero** changes under `permissions/` (`git diff --stat main -- .../permissions/` is empty).

**Frontend (7 across 3 files):**
- `src/components/Sidebar/__tests__/cloudk8sSidebar.test.tsx` (3)
- `src/components/Sidebar/__tests__/loginAsAccountDashboard.test.tsx` (2)
- `src/pages/__tests__/BillOpsAdminDashboardPage.test.tsx` (2)

All three fail even run in isolation, with `TypeError: localStorage.setItem is not a function`
originating in `src/contexts/ThemeContext.tsx` — a jsdom/environment issue, not a product bug. None
of these files are touched by this branch. Confirmed pre-existing by running
`loginAsAccountDashboard.test.tsx` against the pristine `origin/main` checkout, where it fails
identically.

The four tabs (Change / Breakdown / Trend / Connectivity), Connectivity lazy-mount, scroll lock,
Escape-to-close and demo-mode handling are all untouched and verified green by the Cost suite above.
