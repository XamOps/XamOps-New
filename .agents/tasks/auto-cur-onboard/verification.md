# Verification — Auto-create CUR stack on new-account onboarding

## What changed

**Change 1 — new accounts default to OWN_CUR (persisted).**
`AwsAccountService.generateCloudFormationUrl(..., curS3Bucket, awsBillingSource)`:
- Added `resolveOnboardingBillingSource(String)`: null/blank → `OWN_CUR`; explicit value parsed
  via `AwsBillingSource.valueOf(trim)`; unparseable value → `OWN_CUR` with a warning (never throws).
- The new account now persists the resolved source: `newAccount.setAwsBillingSource(effectiveSource)`
  before the first save.
- `createReport` redefined as `effectiveSource == AwsBillingSource.OWN_CUR`, so a null/blank onboard
  now also emits the CUR launch URL and persists `curStackName`/`curStackRegion`. Explicit non-OWN_CUR
  (e.g. `XAMMER_MPA_CUR`) still omits the CUR URL — existing behavior intact.
- No Flyway migration: the `aws_billing_source`, `cur_stack_name`, `cur_stack_region` columns already
  exist (V20260902_1, V20261024_39). The gap was non-persistence in code, now fixed.

**Change 2 — auto-create the CUR stack after the connection stack completes.**
- Added field-injected collaborators (no constructor change): `CurStackService curStackService` and
  `@Qualifier("stackSequenceExecutor") Executor stackSequenceExecutor`.
- Added `triggerCurStackCreationOnConnect(CloudAccount)`: gates on
  `resolveAwsBillingSource() == OWN_CUR` (non-OWN_CUR skipped silently), submits
  `runOnboardCurWithRetry(account)` to `stackSequenceExecutor.execute(...)` inside a `catch (RuntimeException)`
  that only logs — a full pool / rejected submit cannot fail onboarding.
- Added `runOnboardCurWithRetry(CloudAccount)`: bounded loop (`onboardCurRetryAttempts`=6,
  `onboardCurRetryBackoffMs`=12_000, both package-private for tests) calling
  `curStackService.applyAsync(account, false)`. `PENDING_PERMISSION` is retried (first-run IAM
  propagation expected at onboarding); every other status is terminal and stops. Wrapped in
  `catch (Throwable)` so nothing ever propagates off the background thread.
- Wired `triggerCurStackCreationOnConnect(saved)` into BOTH connection-complete paths, strictly after
  the account is saved CONNECTED and `publishConnected(...)`: `connectViaCallback` (real EventBridge
  path, after the existing `autoRegisterCur`) and `verifyAccount` (manual path). Not added to any
  catch/FAILED branch.
- Reuse decision: `CurStackService.applyAsync(account, false)` with a thin onboarding retry wrapper,
  NOT `StackSequenceService` (its CUR retry only tolerates AccessDenied when `connectionChangeSetRan==true`,
  which is false at onboarding — it would not tolerate first-run propagation). No CUR logic re-implemented.
- Executor is the existing `stackSequenceExecutor` whose `tenantTaskDecorator` propagates tenant + MDC,
  required by the CUR path's tenant-routed `cloudAccountRepository.save`.
- No CloudFormation template edited.

## Tests added

`xamops-service/src/test/java/com/xammer/cloud/service/AwsAccountServiceOnboardCurTest.java` (12 tests):
- Change 1: null → OWN_CUR + curUrl; blank → OWN_CUR + curUrl; explicit OWN_CUR persisted; explicit
  XAMMER_MPA_CUR persisted and curUrl omitted.
- Change 2: `connectViaCallback` and `verifyAccount` on OWN_CUR each invoke `applyAsync(account,false)`
  once; XAMMER_MPA_CUR and COST_EXPLORER_ONLY never invoke it (silent skip).
- Best-effort: `applyAsync` returning FAILED still yields a CONNECTED account; an executor that throws
  `RejectedExecutionException` still yields CONNECTED and never calls `applyAsync`.
- Retry: PENDING_PERMISSION then CREATE_IN_PROGRESS → `applyAsync` called twice (backoff set to 0).
- No-op: `applyAsync` returning SKIPPED (no CUR template) → single call, account CONNECTED.
Harness: positional constructor + `ReflectionTestUtils`; executor injected as `Runnable::run` (inline),
`onboardCurRetryBackoffMs=0` so no real sleeps.

## Commands run (from worktree root)

- `mvn -q -pl xamops-service -am compile` → BUILD SUCCESS (module compiles).
- `mvn -q -pl xamops-service test -Dtest=AwsAccountServiceOnboardCurTest -Dsurefire.failIfNoSpecifiedTests=false`
  → Tests run: 12, Failures: 0, Errors: 0.
- `mvn -pl xamops-service test -Dtest=AwsAccountServiceCurUrlTest,AwsAccountServiceDeleteAccountTest,AwsAccountServiceOnboardCurTest,CurStackServiceTest,StackUpdateServiceTest,StackSequenceServiceTest,AccountManagerControllerStackSequenceTest -Dsurefire.failIfNoSpecifiedTests=false`
  → **BUILD SUCCESS. Tests run: 80, Failures: 0, Errors: 0, Skipped: 0.**
  Per-class: AwsAccountServiceCurUrlTest 8, AwsAccountServiceDeleteAccountTest 5,
  AwsAccountServiceOnboardCurTest 12, CurStackServiceTest 12, StackUpdateServiceTest 3,
  StackSequenceServiceTest 38, AccountManagerControllerStackSequenceTest 2.

## Environment limitation

CloudFormation/AWS could not be deployed or exercised here and `aws` CLI credentials are expired.
All verification is unit tests with mocked SDK/service clients (`CurStackService`, `AwsClientProvider`,
executor run inline). No integration or live AWS calls. The `ERROR ... Could not determine host AWS
Account ID on startup` lines in the test logs are expected noise from the mocked `StsClient`.
