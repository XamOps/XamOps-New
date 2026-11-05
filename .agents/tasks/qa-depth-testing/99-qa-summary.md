# XamOps QA Depth-Testing: Final Summary

Scope: whole monorepo, white-box audit plus automated tests. No production code was changed (an earlier edit to the impersonation token TTL was reverted; the token lifetime remains a reported bug, D-series, with the patch kept as `reverted-impersonation-ttl.patch` for the owner to apply if wanted). Nothing was committed or pushed.
Inputs: 01-test-plan.md and all five bug reports (A billops/tickets, B cost, C security/inventory, D auth/infra, E frontend) were present. Detail per bug (file:line, repro, fix) lives in the `02-bugs-*.md` files. Verification evidence is in 98-verification.md.

Headline: about 150 findings: 10 Critical, 32 High, the rest Medium and Low. The dominant theme is missing authorization: most controllers accept an `accountId` or an object id and never check that the caller owns it. Fix that class first.

## 1. Coverage map

| Area | Audited | Automated test status |
|---|---|---|
| billops invoices, tickets, webhooks (A) | yes | 3 new test classes; invoice math already had 5 test classes |
| xamops cost, CUR, GCP, Azure, rightsizing (B) | yes | 2 new classes; CUR helpers already well covered |
| xamops security, WAR, inventory, CloudMap (C) | yes | structural tenant check covers the controllers |
| auth, gateway, WebSocket, metering, agent, infra, CI (D) | yes | 1 new class (metering); rest are config/infra findings |
| frontend (E) | yes (read-only) | none authored: `node_modules` empty, cannot run |
| media-ops | plan baseline only | 3 known failing tests (M-1, M-2), not worked here |
| Go agent | plan baseline plus review (D-06, D-34) | untouched |

## 2. Bugs by severity

Status: confirmed = reproduced by reading the exact code path (and, for the bugs listed in section 3, by a test that fails when un-disabled). suspected = plausible from code, needs a runtime or DB repro. See each report for the label per bug.

### Critical
- A-01 (confirmed) Teams and Google Chat webhooks unauthenticated; Slack signature check skippable by omitting headers. Test: yes.
- A-02 (confirmed) Invoice admin API has no role check (finalize, delete, discount, merge, edit). Test: yes.
- B-01 (confirmed) Every EBS rightsizing endpoint lacks tenant/role checks (delete and modify volumes). Test: structural.
- B-02 (confirmed) EC2 rightsizing apply (stop/resize/start) has no tenant check. Test: structural.
- C-01 (confirmed) Security Center read/write endpoints have no tenant check. Test: structural.
- C-02 (confirmed) WAR report/scan endpoints have no tenant check; `POST /scan` starts paid scans on any tenant. Test: structural.
- D-01 Unauthenticated DB-agent endpoints leak and overwrite stored DB credentials.
- D-02 Media-ops `/browser-stream/**` WebSocket: no auth, any origin, spawns ffmpeg per connection.
- D-03 Read-write customer role grants IAM user/policy management on `*` (privilege escalation).
- D-04 Metering events endpoint never validates the API key. Test: yes.

### High
- A-03 Slack inbound file download is SSRF and leaks the bot token. A-04 Teams `serviceUrl` stored without validator. A-05 Google Chat auto-register rebinds a victim's channel. A-06 Re-finalize allowed, resurrects VOID (test: yes). A-07 Finalize without rate does not lock the INR rate. A-08 Hardcoded IRN/Ack on tax invoices. A-09 Any user can render an official-looking tax invoice PDF. A-10 Finalized invoice view by `accountId` has no access check and a tenant-blind cache key. A-11 Billing dashboards accept arbitrary `accountIds`. A-12 Ticket mutations have no object-level authorization. A-13 `createTicket` trusts `creatorId`/`clientId` from the body. A-14 Stored XSS vector through the support file endpoint. A-15 GCP invoice draft ignores the month and swallows failures (data confirmed, period suspected).
- B-03 EBS policy update/delete IDOR. B-04 Cost, GCP insights and GCP rightsizing-apply lack ownership check. B-05 CSP billing config API has no role check, stores a service-account key, string-built SQL identifiers. B-06 `@Async` bypassed by self-invocation. B-07 Failed stop-wait leaves instance stopped (test: yes). B-08 MTD/last-month cost cache has no month in key. B-09 (suspected) Nightly CSP ingestion self-invokes `@Transactional`.
- C-03 CloudMap/CloudList no tenant check. C-04 Inventory plane no tenant check, `/sync` on any account. C-07 (suspected) One malformed finding aborts the whole envelope.
- D-05 explain-query has no tenant/role check. D-06 Go agent concatenates server text into `EXPLAIN`. D-07 STOMP anonymous subscribe/publish on `/topic/**`. D-08 Eureka publicly proxied. D-09 EventBridge callback unauthenticated. D-10 CSRF disabled with `SameSite=None` cookie. D-11 Committed credentials in tracked properties (key names only in reports). D-12 Legacy shared agent key on by default. D-13 No brute-force protection on login/OTP.

### Medium (ID, one line; details in reports)
- A-16..A-34: merge with same id deletes invoice; unvalidated discount %; `updateInvoiceLineItems` finalizes as side effect; PDF math differs from stored totals; unpaginated invoice cache; sub-admin scope gaps; `applyAccountFilter` fails open; unsanitised ticket subject/description; raw HTML in notification email; sanitiser allows `data:` links; reminder scheduler proxy bypass and no cluster lock; in-memory OAuth state; committed credentials; no upload limits; KEYS-based cache eviction; full CloudAccount entity returned; credits IDOR; timezone inconsistency; xamops ticket list error swallowing.
- B-10..B-25: errors returned as 200 with empty data; region lookup failure cached; unbounded cache keys; no single-flight; CSV formula injection; CE breakdown drops credits; CE not paginated; paid calls on demand; plaintext GCP keys; Azure paging stops silently; `DELETE_UNATTACHED` bypasses workflow; partial volume list cached; Redis evict throws after a cloud action; duplicate account rows; null details abort run; CSP helper null handling.
- C-05, C-06, C-08, C-09, C-10, C-12, C-13, C-15, C-16, C-19, C-20, C-21, C-22, C-23, C-28: owner lockout/500, unverified SQS account, out-of-order events, false findings, missed escalation notifications, resolved_at churn, dismissal restore, title overflow, severity count mismatch, WAR stamp never set, tombstone threshold, sync races, failed sub-scan cached, unbounded pagination, out-of-order migrations.
- D-14..D-30: dead rate-limiter path, install-script and key issues, media id guessing, AWS client cache keyed by account only, broad stack-updater/trust policies, LiveKit over HTTP and shared creds, nginx limits, CI SSH host-key off and change-detection gaps, demo accounts without OTP, buyer usage ownership, Docker root/healthchecks.
- E-01..E-08: dashboard layout contract, JWT in sessionStorage from URL hash, verbose prod console logging, ticket polling bug, ticket CSV injection, UTC date defaults, `ws://` hardcode, credential attach to non-same-origin URLs.

### Low
- A-35..A-39, B-26..B-29, C-11, C-14, C-17, C-18, C-24..C-27, C-29, C-30, D-31..D-35, E-09..E-19.

### Still open from baseline (plan section 4)
- M-1 FFmpegServiceTest default-position tests disagree with code (decide intended default). M-2 subtitle test should skip when libass is missing.

## 3. Tests added

Passing tests protect behavior that is correct today; `@Disabled("BUG-...")` tests fail against current code for the stated reason (proven by switching the Disabled condition off, see 98-verification.md) and keep the build green. When a bug is fixed, delete the `@Disabled`.

| File | Bug | Disabled? |
|---|---|---|
| `billops/src/test/java/com/xammer/billops/service/InvoiceFinalizeStatusGuardQaTest.java` | A-06 | 2 disabled, 2 passing |
| `billops/src/test/java/com/xammer/billops/controller/AdminInvoiceControllerAuthorizationQaTest.java` | A-02 | 1 disabled (7 handlers), passing guard check for 6 already-guarded handlers plus a tripwire that flips when A-02 is fixed |
| `billops/src/test/java/com/xammer/billops/controller/MessagingWebhookSignatureQaTest.java` | A-01 | 1 disabled, 2 passing (bad signature 401, challenge echo) |
| `xamops-service/src/test/java/com/xammer/cloud/service/RightsizingApplyServiceFailureQaTest.java` | B-07 | 1 disabled, 3 passing (happy path, restart after modify failure, no stop if describe fails) |
| `xamops-service/src/test/java/com/xammer/cloud/controller/TenantIsolationStructureQaTest.java` | B-01..B-04, C-01..C-04 | 2 disabled (11 controllers), passing check that `CurInsightsController` and `ComplianceOpsController` use the access service |
| `xamops-service/src/test/java/com/xammer/cloud/controller/MeteringControllerApiKeyQaTest.java` | D-04 | 1 disabled, 1 passing |

Limits of the tests: `TenantIsolationStructureQaTest` and `AdminInvoiceControllerAuthorizationQaTest` are structural tripwires (they check that a guard exists, not that every handler uses it correctly). Cross-tenant MockMvc tests are the next step.

## 4. How to run

```
# billops, whole module (CI command)
mvn -o test -B -pl billops -am
# xamops-service new tests only
mvn -o test -B -pl xamops-service -am -Dtest='RightsizingApplyServiceFailureQaTest,TenantIsolationStructureQaTest,MeteringControllerApiKeyQaTest' -Dsurefire.failIfNoSpecifiedTests=false
# see the bugs fail (switch the Disabled condition off)
mvn -o test -B -pl billops -am -Dtest='*QaTest' -Dsurefire.failIfNoSpecifiedTests=false -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
```
Frontend (needs network): `cd frontend-app && npm ci && npx vitest run && npx tsc --noEmit`.

## 5. What remains untested

- Frontend: nothing run (tooling blocked). Recommended first tests with the existing Vitest/RTL: every `dangerouslySetInnerHTML` site goes through `utils/sanitizeHtml.ts` (7 files), CSV export neutralises formulas (E-05), `apiClient` does not attach credentials to cross-origin URLs (E-08), `AuthContext.fetchUserRoles` failure leaves the app usable (E-09), money/percent formatters with negatives (E-14).
- Real MockMvc cross-tenant tests for B-01..B-04, C-01..C-04 (user of tenant A against tenant B ids). `spring-security-test` is already used by `DemoUsersAdminControllerSecurityTest`.
- STOMP interceptor tests (D-07), webhook tests for Teams and Google Chat (A-01, A-04, A-05), SSRF tests for Slack file download (A-03).
- Invoice PDF/Excel math versus stored totals (A-19), AWS bill PDF parsing, GST math.
- Flyway migrate on empty PostgreSQL and prod-shaped data (needs Docker), `*PgTest` classes.
- Schedulers under multi-instance deployment (A-26), Redis/cache key scoping at runtime (B-08, B-12).
- Infra: `cfn-lint`, IAM least privilege of CloudFormation roles (D-03, D-21..D-23), `nginx -t`, load tests (`jmeter-tests/`, UAT only).
- Real AWS/GCP/Azure behavior, LiveKit, Kafka.
- Full `xamops-service` and `media-ops-service` suites were not re-run in this phase.

## 6. Recommended CI gating plan (based on `.github/workflows/ci-cd.yml`; recommend only, file not edited)

Today: path-filtered jobs run `mvn test` (billops, xamops), `npm test` (frontend, after `npm install`), `go test ./...` (agent, autoscaler), and `mvn package -DskipTests` for media-ops; docker-push needs the test job to pass. No coverage gate, no `tsc`/lint gate, no dependency or secret scan.

1. Block merges on the existing test jobs by making them required status checks on `main` for pull requests (deploys already depend on them).
2. media-ops: change `build-test` from `-DskipTests` to `mvn test`, after fixing M-1 and M-2.
3. Frontend: add `npx tsc --noEmit` and lint to `frontend-build-test`; use `npm ci` instead of `npm install`; stop the docker-push job from doing `sed -i` to skip `tsc` (`npx vite build`), which ships images that skip type-check (D-27).
4. Change detection (D-27): treat `permissions-common/`, root `pom.xml` and `cloudformation/` as triggers for billops and xamops jobs.
5. Add a secret scan (gitleaks) and block new credentials in tracked `application*.properties` (A-28, D-11); add dependency and image scanning (OWASP dependency-check or Trivy) as report-only first, then gate on Critical.
6. Add JaCoCo to billops and media-ops and report coverage; gate on "no decrease" rather than an absolute number to start.
7. Keep a "known bugs" tracker: every `@Disabled("BUG-...")` test is a ticket. Add a CI step that counts them and fails if the count increases without a linked ticket.
8. Keep the existing `xamops-containers-test` (Testcontainers) job and add a billops equivalent so `*PgTest` classes run in CI.
9. Run `cfn-validate.yml` with `cfn-lint` as required, and add a policy check that fails on `iam:*` or `Resource: *` with write actions in customer roles.
10. Fix deploys: remove SSH `StrictHostKeyChecking=no` and stop publishing internal ports (D-26).
