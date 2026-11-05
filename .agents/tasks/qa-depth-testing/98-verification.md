# 98 Verification

Date: run on the QA machine (macOS, mvn 3.9.12, openjdk 25). No production source is modified (an earlier iteration had edited `ImpersonationTokenController.java` and billops `application.properties`; both were reverted with `git checkout`, and `git status` now shows only new test files and report files; the removed diff is kept as `reverted-impersonation-ttl.patch`). Nothing was committed.

## New tests (all under `src/test/java`)

| File | Module | Bug | Passing | @Disabled (bug-exposing) |
|---|---|---|---|---|
| `billops/src/test/java/com/xammer/billops/service/InvoiceFinalizeStatusGuardQaTest.java` | billops | A-06 | 2 | 2 |
| `billops/src/test/java/com/xammer/billops/controller/AdminInvoiceControllerAuthorizationQaTest.java` | billops | A-02 | 7 | 1 (parameterised, 7 handlers) |
| `billops/src/test/java/com/xammer/billops/controller/MessagingWebhookSignatureQaTest.java` | billops | A-01 | 2 | 1 |
| `xamops-service/src/test/java/com/xammer/cloud/service/RightsizingApplyServiceFailureQaTest.java` | xamops-service | B-07 | 3 | 1 |
| `xamops-service/src/test/java/com/xammer/cloud/controller/TenantIsolationStructureQaTest.java` | xamops-service | B-01..B-04, C-01..C-04 | 2 | 2 (parameterised, 11 controllers) |
| `xamops-service/src/test/java/com/xammer/cloud/controller/MeteringControllerApiKeyQaTest.java` | xamops-service | D-04 | 1 | 1 |

## Commands and results

1. Normal mode (what CI runs), billops, full module:
   `mvn -o test -B -pl billops -am`
   Result (re-run after the revert, on the unmodified production tree): BUILD SUCCESS. Tests run: 421, Failures: 0, Errors: 0, Skipped: 29 (the 6 `*PgTest` classes skip because Docker is down, plus the new @Disabled bug tests).

2. Normal mode, new xamops-service tests only:
   `mvn -o test -B -pl xamops-service -am -Dtest='RightsizingApplyServiceFailureQaTest,TenantIsolationStructureQaTest,MeteringControllerApiKeyQaTest' -Dsurefire.failIfNoSpecifiedTests=false`
   Result: BUILD SUCCESS. Tests run: 10, Failures: 0, Errors: 0, Skipped: 4 (surefire counts each disabled parameterised method as one skip).

3. Proof that the @Disabled tests fail for the stated reason (Disabled condition switched off, not a code change):
   `mvn -o test -B -pl billops -am -Dtest='InvoiceFinalizeStatusGuardQaTest,AdminInvoiceControllerAuthorizationQaTest,MessagingWebhookSignatureQaTest' -Dsurefire.failIfNoSpecifiedTests=false -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'`
   - A-06: both tests fail with "Expecting code to raise a throwable" (no status guard).
   - A-02: 7 of 7 handlers fail "@PreAuthorize ... not to be null".
   - A-01: fails, expected 401 (current code returns 200 when headers are omitted).
   Same switch on the xamops-service classes:
   - B-07: "Wanted but not invoked: startInstances" (instance left stopped).
   - Tenant isolation: 11 of 11 controllers fail "Expecting value to be true but was false"; the 2 guarded controllers (`CurInsightsController`, `ComplianceOpsController`) pass.
   - D-04: status is 200 where 401/403 was expected.
   To reproduce, add the `-Djunit.jupiter.conditions.deactivate=...` flag shown above.

## Not run, and why

- Full `xamops-service` suite: not re-run in this phase (about 5+ minutes; the plan recorded 305 classes green before my change). Only the three new classes were run. New tests are additive and touch no production code.
- `media-ops-service` suite: not run here. Plan baseline: 3 known failures (M-1, M-2) unrelated to this work.
- Frontend (`npx vitest run`, `npm run build`, `tsc`): BLOCKED. `frontend-app/node_modules` is empty and the registry is unreachable offline (see plan section 2). Vitest and React Testing Library are in `package.json`, but I did not author frontend tests because they could not be compiled or executed; a test I cannot run would be unverified. Recommendations are in 99-qa-summary.md.
- Docker/Testcontainers `*PgTest` classes: Docker daemon down, skipped.
- Go agent tests: not touched by this phase.
- Real cloud accounts, UAT, Redis, Kafka: not touched. No secrets were read or printed.
