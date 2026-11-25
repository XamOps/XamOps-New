# Phase 2 Bug Hunt, area C: security findings, WAR, compliance, inventory, CloudMap/CloudList

Method: adversarial read of the code only. No source edited, no builds or tests run, nothing committed. "confirmed" means the defect follows directly from code I read, with no runtime assumption. "suspected" means it depends on runtime behaviour, deployment, or data I could not observe. Secret values were not read or printed.

Files read in full or in the relevant parts: `SecurityFindingIngestService`, `SecuritySeverity`, `SecurityFindingKey`, `SecurityFindingResolutionService`, `SecurityFindingNotifier`, `SecurityFindingEmailBuilder` (escaping only), `SecurityController`, `SecurityNotificationController`, `WarController`, `WarScanDispatcher`, `AwsWarService`, `WarReportAssembler`, `ComplianceOpsController`, `ComplianceGovernanceService`, `ComplianceOpsService` (scoring/trend parts), `ComplianceScoring`, `CloudmapController`, `CloudMapService`, `CloudlistController`, `CloudListService` (parts), `InventoryPlaneController`, `InventoryEventListener`, `InventoryEventIngestService`, `AwsInventoryScheduler`, `AwsInventoryReconciler` (sync and persist), `InventoryProjectionService` (parts), `CloudSecurityFindingRepository`, `XamSecureWebhookController`, `SchedulingConfig`, and the Flyway files listed in M-section.

Summary: 30 findings (C-01 to C-30): 2 Critical, 3 High, 15 Medium, 10 Low. 25 confirmed, 5 suspected (C-06, C-07, C-14, C-21, C-28).

---

## Authorization / IDOR

### C-01 (Critical, confirmed) Security Center read and write endpoints have no tenant check
- File: `controller/SecurityController.java` lines 121 (`/live-findings`), 285 (`/detector-coverage`), 296 (`/findings`), 516 (`/export`), 545 (`/prowler/scan`), 576 (`/prowler/status`), 582 (`/prowler/findings`).
- Description: These handlers take `accountId` and resolve it with `cloudAccountRepository.findByAwsAccountId(accountId)` and nothing else. They do not use `ClientUserDetails` or `CloudAccountAccessService`. The class's own Javadoc (around line 232) admits that "the other read endpoints on this controller resolve by id without it". Only `/identities`, `/findings/resolve`, `/findings/restore` and `/findings/resolved` call `resolveOwnedAccount`. I grepped for filters and interceptors: `JwtTokenFilter`, `TrialEnforcementFilter`, `CostSourceFilter` and `K8sAutomationWebConfig` do not check account ownership, so nothing upstream compensates. Any signed-in user who knows or guesses a 12-digit AWS account id can read another tenant's findings (including remediation text and resource ARNs), download them as Excel, read raw Prowler OCSF findings, and start Prowler scans (cost and OOM risk) against that tenant's role.
- Repro / test idea: MockMvc with a principal of client A, `GET /api/xamops/security/live-findings?accountId=<client B aws id>`. Expected 403/404, actual 200 with B's data. Parameterised test over all seven handlers.
- Fix: resolve the account through one shared helper that checks `CloudAccountAccessService.canAccess(user, account)` (as `ComplianceOpsController.requireAccess` does) and return 404/403 identically for unknown and forbidden. Add `@AuthenticationPrincipal ClientUserDetails` to each handler.

### C-02 (Critical, confirmed) WAR report and scan endpoints have no tenant check; `POST /scan` lets anyone start paid scans against any tenant
- File: `controller/WarController.java` lines 59-105 (`/report`, `/scan`, `resolve()`).
- Description: `resolve(accountId)` looks up AWS id, GCP project id or Azure subscription id with no principal. `GET /report` returns another tenant's full posture report (every failing control, resource names, denied actions). `POST /scan` calls `scanDispatcher.scan(account, true)` with `forceRefresh=true`, which assumes the victim's cross-account role and runs hundreds of API calls per region. There is no rate limit and no in-flight de-duplication, so repeated calls start parallel full scans of the same account, and `AwsWarService` caches by AWS account id with a Postgres `LATEST` snapshot that never expires.
- Repro: as client A, `GET /api/xamops/war/report?accountId=<B>` then `POST /api/xamops/war/scan?accountId=<B>` in a loop.
- Fix: same shared ownership check as C-01, plus a per-account in-flight guard and rate limit on `/scan`.

### C-03 (High, confirmed) CloudMap and CloudList endpoints have no tenant check
- File: `controller/CloudmapController.java` lines 29 and 46; `controller/CloudlistController.java` lines 67, 91, 126, 152, 174, 190; `service/CloudMapService.java` line 871 (`getAccount`).
- Description: all handlers take `accountId` / `accountIds` and look up the account by id only. They return the resource inventory, VPC topology, resource details and region status of any account. `POST /trigger-refresh` takes `List<String> accountIds` with no cap and no ownership check, so one request can start a full describe scan for every account named (and for accounts of other tenants). `CloudMapService.getGraphData` also uses the account's role to make live EC2 describe calls (`awsClientProvider.getEc2Client`).
- Repro: as client A call `GET /api/xamops/cloudmap/graph?accountId=<B>&vpcId=ALL`; `POST /api/xamops/cloudlist/trigger-refresh?accountIds=<B>,<C>,...`.
- Fix: shared ownership check in the controller, cap `accountIds` size, and only accept accounts the caller can access.

### C-04 (High, confirmed) Inventory plane endpoints have no tenant check, and `/sync` triggers a full reconcile on any account
- File: `controller/InventoryPlaneController.java` lines 64 (`/status`), 152, 160, 174, 183 (`/sync`).
- Description: `/status` returns `lastError`, aggregator region, resource count and queue authorisation for any account (information disclosure about another customer's setup). `/counts`, `/regions`, `/recent` return that account's inventory. `POST /sync` calls `scheduler.syncNow(account)` which assumes the victim's role and does a Resource Explorer read plus describe calls, then pushes a WebSocket notification. It is not guarded by the scheduler's `running` flag (`syncNow` bypasses it) so it can run concurrently with the scheduled pass (see C-21).
- Fix: ownership check; rate limit `/sync` per account.

### C-05 (Medium, confirmed) `SecurityController.resolveOwnedAccount` can lock out the real owner or return a 500
- File: `controller/SecurityController.java` lines 208-232 (`resolveOwnedAccount`).
- Description: it calls `findByProviderAccountId(id)`, whose JPQL matches `awsAccountId OR gcpProjectId OR azureSubscriptionId` and returns `Optional`. If any two rows match (for example a GCP project id equal to another row's subscription id, or a data issue), Spring Data throws `IncorrectResultSizeDataAccessException`, and the handler reports it as a 500. The fallback `findByAwsAccountId(id).get(0)` takes the first match with no client filter, so if more than one row exists for the id it compares ownership against an arbitrary row and returns 403 to the real owner. The repository already offers `findByAwsAccountIdAndClientId` for exactly this. Note: the `CloudAccount` entity declares `awsAccountId` as `unique = true` while many comments in the inventory code state that one AWS account can own several `cloud_account` rows. These two statements contradict each other. I could not check the live schema.
- Test idea: repository test with two rows sharing an id; call `/findings/resolve` as the second row's owner.
- Fix: resolve by `(id, clientId)` using the existing repository method; settle whether `aws_account_id` is unique and make the comments and entity agree.

### C-06 (Medium, suspected) Inventory event listener trusts the `account` field in the SQS body and does not compare it to the ARN's account
- File: `service/inventory/InventoryEventListener.java` line 357-370; `service/inventory/InventoryEventIngestService.java` lines 80-130 (`apply`, `buildFromArn`).
- Description: the tenant is chosen from `root.path("account")` only. The ARN's account segment (`parts[4]`) is never compared to `account.getAwsAccountId()`. The queue is shared by all customers and the message is built by a forwarder Lambda that runs inside the customer's account, so a customer-controlled sender could set `account` to another tenant's id and inject resources, tombstones (`action=DELETED`) and fake security findings for that tenant. Whether this is exploitable depends on the queue policy and how `InventoryQueueAuthorizer` binds a sender to an account; I did not trace that, so this stays suspected.
- Test idea: unit test for `handleMessage` with `account` = A and ARNs for account B; expect rejection. Integration check of the queue policy conditions (`aws:SourceAccount`).
- Fix: reject events whose ARN account segment differs from the resolved account (except global ARNs with empty account), and bind the sender account to `account` through the queue policy.

---

## Ingestion, dedupe, idempotency

### C-07 (High, suspected) One malformed or oversized finding aborts the whole envelope, and a swallowed persistence error can poison the transaction
- File: `service/security/SecurityFindingIngestService.java` lines 107-135 (`ingest`), 245-300 (`upsert`), 345-370 (`copyMutable`).
- Description: (a) The `try` wraps the whole `for (JsonNode finding : findings)` loop, so a Security Hub envelope with N findings stops at the first one that throws and silently drops the rest. (b) `findingId` is stored without truncation but the column is `VARCHAR(512)` (`V20260821_6`). Other fields are truncated; `finding_id`, `source` and `aws_account_id` are not. A Security Hub `Id` over 512 chars makes the insert fail. `ingest` catches `Exception` and logs a warning, but the method is `@Transactional`, so a `DataIntegrityViolationException` thrown by the repository inside the proxy marks the transaction rollback-only. The method then returns normally and the commit throws `UnexpectedRollbackException`, which `InventoryEventListener.processBatch` treats as a processing failure ("leaving for retry"). The message is redelivered up to the DLQ threshold and then lost, along with findings already applied earlier in the same envelope. This is the redelivery loop the Javadoc says the catch is meant to prevent. I did not reproduce the rollback-only behaviour at runtime.
- Test idea: ingest a Security Hub envelope with three findings where the second has `Id` of 600 chars; assert findings 1 and 3 are stored and the call does not throw. Same with a mocked repository that throws on `save`.
- Fix: truncate `findingId` (or hash it when over the limit), catch per finding inside the loop, and use `REQUIRES_NEW` or a per-finding transaction so one failure cannot poison the others.

### C-08 (Medium, confirmed) Out-of-order delivery re-opens or resurrects state: no event-time comparison
- File: `service/security/SecurityFindingIngestService.java` lines 245-300 (`upsert`); `service/inventory/InventoryEventIngestService.java` lines 80-130 (`apply`).
- Description: SQS is at-least-once and not ordered. `upsert` applies whatever arrives last and never compares `eventTime` with the stored `event_time`. A Config `COMPLIANT` transition followed by a redelivered older `NON_COMPLIANT` re-opens a fixed finding, resets `firstSeenAt`, clears `notifiedAt` and sends a new alert. For inventory, an old `CREATED`/`CHANGED` delivered after `DELETED` flips a tombstoned row back to ACTIVE (`apply` sets `STATUS_ACTIVE` unconditionally) and counts it as `CREATED`, which pushes a UI notification.
- Test idea: ingest COMPLIANT (time T2) then NON_COMPLIANT (time T1 < T2) for the same rule and resource; assert status stays RESOLVED. Same for inventory DELETED then CREATED with an older time.
- Fix: keep the newest `event_time` per finding / resource and ignore events older than it.

### C-09 (Medium, confirmed) Config evaluation edge cases create false findings
- File: `service/security/SecurityFindingIngestService.java` lines 300-340 (`applyConfig`).
- Description: only `COMPLIANT` resolves; every other value is treated as a failing finding. This includes `INSUFFICIENT_DATA` and a missing or empty `complianceType` (the default is `""`, which is neither `NOT_APPLICABLE` nor `COMPLIANT`). A Config snapshot event without `newEvaluationResult` therefore creates an ACTIVE MEDIUM finding with the text "the result was ." Also, when `resourceId` is null the finding id is derived from the rule and region only, so distinct resources collapse into one finding.
- Test idea: envelope with no `newEvaluationResult`; assert IGNORED. Envelope with `INSUFFICIENT_DATA`; assert no ACTIVE finding.
- Fix: whitelist `NON_COMPLIANT` as the only active value; ignore everything else; require `resourceId`.

### C-10 (Medium, confirmed) A severity escalation on an already-notified finding is never notified
- File: `service/security/SecurityFindingIngestService.java` lines 280-300 and 345-370; `repository/CloudSecurityFindingRepository.java` (`findUnnotified`).
- Description: when a finding that is already ACTIVE is redelivered with a higher severity (GuardDuty re-publishes with a larger score, Security Hub relabels), `copyMutable` overwrites `severity` but leaves `notifiedAt` set. `findUnnotified` filters on `notified_at IS NULL`, so a MEDIUM finding that was notified at MEDIUM and then became CRITICAL never reaches a CRITICAL-threshold recipient. The `notifiedAt` is only reset on a regression (`!wasActive && nowActive`).
- Test idea: ingest MEDIUM, mark notified, ingest same id as CRITICAL; assert `notifiedAt` is null or a second notification is produced.
- Fix: when the new severity outranks the stored one (`SecuritySeverity.rank`), clear `notifiedAt`.

### C-11 (Low, confirmed) GuardDuty finding with missing or non-numeric severity is shown as INFORMATIONAL
- File: `service/security/SecurityFindingIngestService.java` line ~212 (`detail.path("severity").asDouble(0.0)`) and `service/security/SecuritySeverity.java` `fromGuardDutyScore`.
- Description: the class comment states that unrecognised severity must degrade to MEDIUM so a parse problem cannot hide a finding. For GuardDuty the default is `0.0`, which maps to INFORMATIONAL, the lowest band, and is below every notification threshold except INFORMATIONAL. A missing field, or `NaN`, hides the finding. Boundary values are otherwise correct (9.0 CRITICAL, 7.0 HIGH, 4.0 MEDIUM, above 0 LOW).
- Test idea: `fromGuardDutyScore(Double.NaN)` returns INFORMATIONAL; GuardDuty envelope without `severity`.
- Fix: treat missing, non-finite or non-positive scores as MEDIUM, consistent with `normalize`.

### C-12 (Medium, confirmed) Re-delivery of a resolved finding refreshes `resolved_at` and counts as a change
- File: `service/security/SecurityFindingIngestService.java` lines 280-300 and 345-370.
- Description: for an existing RESOLVED row receiving another RESOLVED envelope, `wasActive=false` and `nowActive=false`, so `upsert` returns `UPDATED` after `copyMutable` sets `resolvedAt = now`. Two effects: (1) the 90-day retention clock in `purgeResolvedBefore` restarts with every redelivery, so a chatty finding is never purged; (2) `Result.changed()` is true, so the listener logs it as a changed finding and `updated` counts inflate.
- Fix: do not touch `resolvedAt` when status is unchanged, and return `IGNORED` for no-op redeliveries.

---

## Dedupe / dismissal logic

### C-13 (Medium, confirmed) Restoring a user dismissal re-activates findings the provider had resolved
- File: `service/security/SecurityFindingResolutionService.java` lines 230-260 (`restore`, `applyStatusToMatchingRows`).
- Description: `restore` calls `applyStatusToMatchingRows(..., STATUS_ACTIVE)`, which loads every row with status RESOLVED for the account and flips each row whose key hash matches to ACTIVE. It cannot tell a row the user closed from a row the provider closed (Config COMPLIANT, Security Hub ARCHIVED). Dismiss then restore therefore resurrects a finding that AWS had already resolved, and the notifier will email it again (`notifiedAt` is not reset, but `findActive` will list it).
- Test idea: finding RESOLVED by Config; user marks fixed and then restores; assert status stays RESOLVED.
- Fix: only reactivate rows that this resolution closed, for example by recording the affected ids or the prior status.

### C-14 (Low, suspected) Concurrent `resolve` calls for the same finding can fail with a unique-index violation
- File: `service/security/SecurityFindingResolutionService.java` lines 150-200 (`resolve`); `V20260915_2__security_finding_resolution.sql` (`uq_sfr_live`).
- Description: `resolve` does check-then-insert (`findLiveByHash`, then `save`). The Javadoc promises idempotency for the "two tabs" case. Two simultaneous requests both see no live row and the second insert violates the partial unique index, which the controller reports as a generic 500 ("Could not record that"). Not reproduced.
- Fix: catch `DataIntegrityViolationException` and re-read, or use an upsert.

### C-15 (Medium, confirmed) Inbox notification insert fails for a single finding with a title over 512 characters
- File: `service/security/SecurityFindingNotifier.java` line ~360 (`writeInboxRow`, `row.setTitle(findings.get(0).getTitle())`); `V20260821_2__security_notification.sql` (`title VARCHAR(512)`); `V20260821_6__cloud_security_finding.sql` (`title VARCHAR(1024)`).
- Description: finding titles are stored up to 1024 characters but the inbox title column is 512. A single long-titled finding makes `notificationRepository.save` fail. The failure is caught and logged as a warning, so the finding is still marked notified and the inbox entry is never created; the bell shows nothing for it.
- Test idea: notifier test with a 700-character title and `inboxEnabled=true`; assert a row exists.
- Fix: truncate to 512 in `writeInboxRow` or widen the column.

---

## Scoring and counts

### C-16 (Medium, confirmed) Unclassified severity is counted differently in the compliance totals and the framework rollups
- File: `service/compliance/ComplianceOpsService.java` `bumpSeverity` (around line 561) versus `buildFrameworkScores` (around line 613).
- Description: `bumpSeverity` puts null or unknown severity into MEDIUM; the per-framework tally uses `severity == null ? "LOW"` and the `default` branch is `low`. For the same failing control, the overall counts add it under Medium and the framework card adds it under Low, so the two do not sum. `ComplianceScoring.weight` uses 3 (MEDIUM) for unknowns and the page note says "treated as medium", which agrees with `bumpSeverity` and contradicts the framework rollup.
- Test idea: results with one FAIL of severity null; compare `counts.medium` with the sum of framework `medium`.
- Fix: use one helper that maps severity to a bucket, with unknown mapped to MEDIUM.

### C-17 (Low, confirmed) Compliance trend always reports `passedControls = 0`
- File: `service/compliance/ComplianceOpsService.java` `buildTrend` (`p.setPassedControls(0)`).
- Description: every trend point carries 0 passed controls, and `failedControls` is derived only from the failed-by-severity query. Any chart or API consumer that reads `passedControls` shows zero passes for every scan. Also trend points never include waived or manual counts.
- Fix: store pass counts on the scan or leave the field null.

### C-18 (Low, confirmed) WAR "partial" credit is zero for Low severity, and an unassessable scan reports a score of 0
- File: `service/war/WarReportAssembler.java` lines 108 and 244-246.
- Description: (a) `case WARN -> earned += weight / 2` is integer division. For Low (weight 1) a WARN earns 0, the same as FAIL, so "half credit" does not exist for that severity. (b) `warScore(assessable ? score : 0)` publishes 0 for a scan that could not be assessed. `ComplianceScoring` deliberately returns null for the same case ("we could not measure" versus "you failed everything"). Consumers that read `warScore` without checking `assessable` (the email builder, API clients, any chart) show 0. (c) A blank or null control severity is normalised to "Low" (`normaliseSeverity`), the opposite of the compliance side, which treats unknown as MEDIUM.
- Fix: use weights doubled or a double-based score; return null for an unassessable scan; default unknown severity to Medium.

### C-19 (Medium, confirmed) The WAR "findings since scan" staleness stamp can never be set
- File: `service/war/AwsWarService.java` `parseGeneratedAt` (lines ~205-220) versus `service/war/WarReportAssembler.java` lines 33-34 (`TIMESTAMP = "yyyy-MM-dd HH:mm:ss z"`).
- Description: `generatedAt` is written as, for example, `2026-05-10 14:30:00 IST`. `parseGeneratedAt` tries `Instant.parse` (needs `T` and `Z`) and `LocalDateTime.parse` (ISO, needs `T`). Both fail on a space-separated, zone-suffixed string, so the method always returns null and `findingsSinceScan` is never set. The staleness banner described in the code comments is dead on arrival. Even if the format matched, the fallback parses in `ZoneId.systemDefault()` while the report is formatted in `Asia/Kolkata`, which would shift the time by 5.5 hours on a UTC host. Only the AWS service calls `withStaleness`; GCP and Azure do not.
- Test idea: `parseGeneratedAt("2026-05-10 14:30:00 IST")` returns null (a one-line unit test).
- Fix: parse with the same formatter used to write the value, or store an `Instant` next to the string.

---

## Concurrency, schedulers, caches

### C-20 (Medium, confirmed) Inventory sweep threshold counts tombstoned rows as "previously active"
- File: `service/inventory/AwsInventoryReconciler.java` `persistInTransaction`, lines ~1115-1135 and ~1220.
- Description: `existing` is loaded with `findAllByCloudAccountId` (every status, by design, to avoid unique-index collisions). `previouslyActive = existing.size()` then includes DELETED rows, and the sweep only runs when `observed >= floor(previouslyActive * 0.5)`. For an account with high churn (many resources created and deleted, rows kept as tombstones) the denominator grows without bound while `observed` stays flat, so eventually coverage is under 50% and the sweep is skipped on every run. Deleted resources then stay ACTIVE indefinitely. The log line even says "were previously active".
- Test idea: reconciler test with 100 ACTIVE and 150 DELETED existing rows and 90 observed; assert the sweep runs.
- Fix: count only rows with `status = ACTIVE` in the coverage check.

### C-21 (Medium, suspected) Manual sync, event ingestion and the scheduled pass race on the same rows and generation counter
- File: `service/inventory/AwsInventoryScheduler.java` `syncNow` (line ~232) and `syncAllAccounts`; `service/inventory/InventoryEventIngestService.java` `currentGeneration`; `service/inventory/AwsInventoryReconciler.java` lines ~1118 and ~1231.
- Description: (a) `syncNow` (HTTP `/sync`) skips the `running` AtomicBoolean and has no per-account lock, so a manual sync and the scheduled pass can both read `generation = N`, both write `N+1` and both sweep. The sync state row has no visible version check. (b) Event-created rows take `currentGeneration` (N). A reconcile that read its inventory before the event, then commits generation N+1, sweeps every ACTIVE row with generation below N+1, which includes the event-created row if Resource Explorer had not yet indexed the resource. The resource disappears (and a "removed" UI push is sent) until a later pass re-adds it. The inverse also happens: an event DELETE tombstones a row that Resource Explorer still returns, and the next sync revives it. (c) The `running` guard is per JVM; there is no ShedLock or similar (grep found none), so with more than one replica with scheduling enabled every pass runs in parallel. I did not run any of this; (c) depends on deployment.
- Test idea: concurrent `sync` calls on a repository test with H2/PG; unit test that an event-created row inside the Resource Explorer lag window survives a reconcile.
- Fix: per-account lock (advisory lock or `SELECT ... FOR UPDATE` on the sync state row), a grace period for rows with `source = EVENT` younger than N minutes in the sweep, and a distributed scheduler lock.

### C-22 (Medium, confirmed) A failing sub-scan is cached as a complete result in CloudList
- File: `service/CloudListService.java` lines ~560-640 (`getAllResources`).
- Description: each of the ~56 `fetch...ForCloudlist` futures is wrapped with `.exceptionally(ex -> emptyList())`. If one service call fails (throttling, missing permission, timeout), the merged list lacks that service, yet the result is written to Redis for 60 minutes and to the `CLOUD_LIST::<id>` DB snapshot (`saveResourcesSnapshot`), which has no expiry and is served again after a Redis miss. The user sees an inventory missing that service, with no warning, until a forced refresh succeeds. The grouped list and the paginated list inherit this.
- Test idea: stub one fetcher to fail; assert the result is not cached, or that a partial flag is set.
- Fix: do not persist when any sub-task failed, or record which services were skipped and surface it.

---

## Boundary inputs

### C-23 (Medium, confirmed) CloudList pagination parameters are not validated and unbounded
- File: `controller/CloudlistController.java` lines 67-90 and 126-150; `service/CloudListService.java` lines 296-340 and 380-420; `service/inventory/InventoryProjectionService.java` `pagedList`.
- Description: (a) `servicesPerPage` negative: `totalPages` becomes negative, `endIndex = min(start + negative, total)` is negative and `subList(0, negative)` throws, giving a 500. `servicesPerPage = 0` gives `totalPages = Integer.MAX_VALUE` and an empty page. (b) `/all` with `page < 0` or `size < 1`: `PageRequest.of` throws `IllegalArgumentException` first in the plane branch (caught and logged as a plane failure), then again in the fallback, and again inside the `.exceptionally` handler (`new PageImpl(..., PageRequest.of(page, size), 0)`), so the failure handler itself throws and the response is a 500 instead of the empty page it intends. (c) `size` has no upper bound (`size=1000000` loads everything), and `(int) pageRequest.getOffset()` overflows for large `page * size`, so `start` can go negative and `subList` throws. (d) `start > allResources.size()` should be `>=` only cosmetically.
- Repro: `GET /api/xamops/cloudlist/all?accountId=X&page=-1`, `GET /api/xamops/cloudlist/services-paginated?accountId=X&servicesPerPage=-5`.
- Fix: clamp `page >= 0`, `1 <= size <= 200`, and compute offsets with `long`.

### C-24 (Low, confirmed) CloudMap draws no VPC or subnet nodes when the VPC is outside `us-east-1` and no region is passed
- File: `service/CloudMapService.java` lines ~143-150 (`effectiveRegion = ... "us-east-1"`) and `addVpcAndSubnetNodes`.
- Description: for a specific `vpcId` with an empty `region`, the EC2 client is created for `us-east-1`. `describeVpcs(vpcIds)` for a VPC that lives elsewhere throws `InvalidVpcID.NotFound`, which `addVpcAndSubnetNodes` catches and logs at error. The graph then renders with no VPC container, subnets, or routing, while still returning 200. `region` is also passed to `getEc2Client` unvalidated, so an arbitrary string builds a client for a made-up region. For `vpcId=ALL` only `us-east-1` VPCs are drawn.
- Fix: look up the VPC's region from the plane or resources list; validate region against the account's active regions.

### C-25 (Low, confirmed) `CloudMapService.getAccount` and `CloudlistController.getAccount` only resolve by AWS id, or throw uncaught `RuntimeException`
- File: `service/CloudMapService.java` line 871; `controller/CloudlistController.java` lines 33-52.
- Description: `CloudMapService.getAccount` uses only `findByAwsAccountId`, so a GCP project id or Azure subscription id returns "Account not found" while sibling controllers accept all three. In `CloudlistController`, an unknown account throws `RuntimeException` synchronously from the handler (before the `CompletableFuture` is created), which Spring turns into a 500 rather than a 404. Also `accountId.split(",")[0]` is not trimmed in `/resources`.
- Fix: use one lookup helper, return 404.

---

## Logging, XSS, leaks

### C-26 (Low, confirmed) Log statements with mismatched placeholders hide the data they mean to log
- File: `controller/CloudlistController.java` lines ~108 and ~121 (`logger.warn("... non-AWS account received. Returning empty.", account.getProvider())` has no `{}`); line ~167 (`"Failed to get details for resource {} in account {}", service, resourceId, accountId, ex`).
- Description: the first two drop the provider argument. The third has two placeholders and three arguments plus the exception: it prints `service` as the resource and `resourceId` as the account, and `accountId` is consumed as the throwable-position argument only if it is last, so the real exception stack may be lost. During an incident these logs mislead.
- Fix: correct the placeholders.

### C-27 (Low, confirmed) `SecurityController.exportFindingsToExcel` throws a raw `RuntimeException` for an unknown account and ignores live findings and dismissals
- File: `controller/SecurityController.java` lines 516-540.
- Description: an unknown account throws `RuntimeException("Account not found: <id>")`, giving a 500 with the id echoed in server logs, instead of 404. The export always runs a forced live scan (`getRegionStatusForAccount(account, true)` and `getComprehensiveSecurityFindings(..., true)`), so each download is a multi-region scan and combined with C-01 is a cost amplifier. The export also does not merge the event-driven findings or filter out user-dismissed findings, so it disagrees with the page the user is looking at.
- Fix: return 404; reuse the page's merge and resolution filter; use cached results unless the user asks for refresh.

---

## Migration hazards (Flyway, `src/main/resources/db/migration`, 96 files)

No duplicate version numbers: I compared the version prefixes with `sort | uniq -d` and the list is unique. Versions such as `V20260330` and `V20260519` next to `V20260519_1`, and `V2__...` are distinct and order correctly.

### C-28 (Medium, suspected) Out-of-order migrations are only enabled for prod
- File: `application-prod.properties` line 92 (`spring.flyway.out-of-order=true`); no equivalent in `application.properties` or `application-preprod.properties` (grep for `flyway` shows only prod and a local override that disables Flyway).
- Description: the series contains lower-numbered files added after higher ones were applied elsewhere (for example `V20260821_6` and `_7` sit below `V20260915_*`, and the newest group jumps `V20261024_37`, `_38`, `_39`). On any environment that has already applied a higher version without out-of-order, a later-added lower version is reported as "detected resolved migration not applied" by validation and the app refuses to start. Prod tolerates it, other environments do not. I could not see the history table of any environment, so I cannot say whether this has already happened.
- Test idea: CI job that applies migrations from an old baseline plus the PR's new file on an environment configured like preprod.
- Fix: keep versions strictly increasing in merge order and enable the same setting everywhere, or enforce a naming check in CI.

### C-29 (Low, confirmed) Several migrations are not idempotent or take heavy locks
- Files and lines:
  - `V20260821_6__cloud_security_finding.sql`, `V20260915_2__security_finding_resolution.sql`, `V20260821_2__security_notification.sql`: plain `CREATE TABLE` / `CREATE INDEX` without `IF NOT EXISTS`. Later migrations in the same series use `IF NOT EXISTS`, so the style is inconsistent; a repaired or partially applied history fails on re-run.
  - `V20260819_2__inventory_jsonb_to_text.sql`: `ALTER TABLE aws_cloud_resource ALTER COLUMN ... TYPE TEXT USING ...` rewrites the table under `ACCESS EXCLUSIVE`, blocking the 60-second reconciler and the SQS listener for the duration on a large table. The `information_schema.columns` checks also have no `table_schema` filter, so a same-named table in another schema can make the guard true or false wrongly.
  - `V20260820_1__inventory_scope_by_cloud_account.sql`: unconditional `DELETE ... WHERE cloud_account_id NOT IN (SELECT id FROM cloud_account)` is irreversible, and the `CREATE UNIQUE INDEX` on `(cloud_account_id, arn)` is built without `CONCURRENTLY` (the table already has `uq_aws_cloud_resource_arn` on the same columns, so the index is redundant and costs write overhead).
  - `V20260911_1__account_stack_identity.sql` adds `stack_checked_at TIMESTAMP` (without time zone) while the rest of the security and inventory tables use `TIMESTAMPTZ`.
- Fix: add `IF NOT EXISTS`, add `table_schema = current_schema()` to the guard, drop the duplicate index, use `TIMESTAMPTZ`.

### C-30 (Low, confirmed) `cloud_security_finding.finding_id` and other short columns have no matching truncation
- File: `V20260821_6__cloud_security_finding.sql` (`finding_id VARCHAR(512)`, `aws_account_id VARCHAR(32)`, `source VARCHAR(32)`) versus `SecurityFindingIngestService.copyMutable` (truncates title, ARN, type, region, framework, control id only).
- Description: this is the schema side of C-07. `finding_id` is the one key column that is not clipped. `raw_detail` exists in the table but is never written by the ingest service I read, so the audit evidence promised in the migration comment is not stored.
- Fix: clip or hash `finding_id`; either write `raw_detail` or remove the column.

---

## Other observations (not counted as bugs)
- Scheduler pool: `SchedulingConfig` defines a `ThreadPoolTaskScheduler` of size 5, but `application-prod.properties` and `application-preprod.properties` set `spring.task.scheduling.pool.size=1` and several classes' comments assume 1. Defining the bean makes Boot's property ineffective, so I believe the pool is 5. If the property were effective, `InventoryEventListener.pollEvents` (SQS long poll of 20 seconds every 5 seconds) would hold the only scheduler thread most of the time. I did not verify the effective pool at runtime.
- `SecurityNotificationController` reads preferences from the principal's client id only, and `markRead` includes the client id in the update predicate, so it is correctly scoped. `ComplianceOpsController` enforces access on every endpoint and the governance service blocks self-approval. These were checked and found sound.
- `SecurityFindingEmailBuilder` escapes titles, descriptions, ARNs, region and control ids, so I found no HTML injection there. `XamSecureWebhookController` compares the secret with `MessageDigest.isEqual` and rejects when the secret is not configured.

## Counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 2 | C-01, C-02 |
| High | 3 | C-03, C-04, C-07 |
| Medium | 15 | C-05, C-06, C-08, C-09, C-10, C-12, C-13, C-15, C-16, C-19, C-20, C-21, C-22, C-23, C-28 |
| Low | 10 | C-11, C-14, C-17, C-18, C-24, C-25, C-26, C-27, C-29, C-30 |
| Total | 30 | 25 confirmed, 5 suspected (C-06, C-07, C-14, C-21, C-28) |
