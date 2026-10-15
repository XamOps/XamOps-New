# Verification — admin-editable USD→INR rate at invoice finalize

First iteration (no `review.json` present). Implemented per `plan.md`.

## Migration decision (RECORDED)

**No migration file created.** billops has no Flyway/Liquibase and its schema is
Hibernate-managed, so per the task brief the new column is added only on the entity.

Evidence (READ):
- `billops/pom.xml` has no `flyway`/`liquibase` dependency.
- No `billops/src/main/resources/db` directory exists.
- `grep -ri 'flyway\|liquibase' billops/src/main/resources/*.properties` → no matches.
- `spring.jpa.hibernate.ddl-auto`: `none` in `application.properties`; `update` in
  `application-local/uat/preprod/prod.properties`.

The new `exchange_rate` column is a **nullable** `Double` (mirroring
`CloudAccount.usdExchangeRate`), so `ddl-auto=update` can add it to the populated
`invoice` table without failing and old invoices read back `null` (backwards compat).

## What was changed

Backend (`com.xammer.*`, module `billops`):
- `com/xammer/cloud/domain/Invoice.java` — nullable `Double exchangeRate`
  `@Column(name = "exchange_rate")` + getter/setter.
- `com/xammer/billops/dto/InvoiceDto.java` — `Double exchangeRate` field, mapped in
  `fromEntity`, getter + setter (survives the detail-bill JSON round trip).
- `com/xammer/billops/service/InvoiceManagementService.java`:
  - `fetchUsdToInrRate(dto)` now prefers `dto.getExchangeRate()` (> 1.0) as priority #1,
    then CloudAccount → live API → 86.0, unchanged.
  - `finalizeInvoice(Long, Double)` overload: sets the rate when non-null and > 1.0,
    throws `IllegalArgumentException` on ≤ 1.0; 1-arg overload delegates with `null`.
    `@Transactional` + `@CacheEvict` and the recalc/status/save/evict/email flow intact.
- `com/xammer/billops/controller/AdminInvoiceController.java` — `/{id}/finalize` takes
  an optional `@RequestBody(required=false) FinalizeRequest(Double exchangeRate)`;
  `IllegalArgumentException` → 400 `{message}` (matches the class's existing pattern).
  No `@PreAuthorize` added (the endpoint and class intentionally carry none).
- NEW `InvoiceFinalizeExchangeRateTest` (service, constructor-mock pattern): valid rate
  persisted + FINALIZED; null rate stays null + finalizes; legacy overload; ≤ 1.0 refused.
- `billops/src/test/resources/dashboard/admin-dashboard-fields.txt` — re-captured snapshot
  (added the single new path `recentInvoices[].exchangeRate`; header note restored by hand
  as the test documents). This is a test fixture only; no runtime behavior changed.

Frontend (`frontend-app/src/pages/BillOpsAdminInvoiceDetailPage.tsx`):
- `exchangeRate?: number | null` on the `InvoiceDetail` type.
- `chosenRate`/`rateTouched` state; a seeding `useEffect` (invoice rate → live rate,
  without clobbering an edit); derived `rateValid`/`effectiveRate`/`rateFromLiveFeed`.
- Draft-only labeled numeric input (`<label htmlFor>` ↔ `id`, `step=0.01`, `min=1.01`,
  `aria-invalid`, inline validation text) near the hero/Finalize, reusing `inv-*` classes.
- Hero INR equivalent now driven by `effectiveRate` (live-updates with edits); keeps the
  `'USD, including tax'` fallback and the live-feed date suffix only when still live.
- Finalize POST sends `{ exchangeRate }` as JSON; guards NaN/≤1 (aborts + shows message);
  surfaces the backend 400 `message`. Finalize button disabled until the rate is valid.
- FINALIZED invoices show the persisted rate read-only ("Finalized at ₹X per USD").
- Confirm dialog description appends the locked-in rate.

## Commands RUN and outcomes

1. Backend compile (RUN):
   `mvn -pl billops -am -q -DskipTests compile` → exit 0, compiles cleanly
   (no `mvnw` wrapper; system `mvn` used, as plan noted).

2. Backend focused tests (RUN):
   `mvn -pl billops test -Dtest=InvoiceFinalizeExchangeRateTest,InvoicePrivatePricingTest`
   → `Tests run: 28, Failures: 0, Errors: 0` — BUILD SUCCESS.

3. Backend full module suite (RUN):
   `mvn -pl billops test` → initially 1 failure: `AdminDashboardDtoShapeTest`
   (snapshot of `AdminDashboardDto`'s serialized field set, which embeds `InvoiceDto` as
   `recentInvoices[]`). The only diff was the new `recentInvoices[].exchangeRate` path —
   exactly this task's added field. Re-captured the fixture with the test's documented
   `-Dlobtext.captureDtoFields=true`, restored the header note by hand, re-ran:
   `Tests run: 379, Failures: 0, Errors: 0, Skipped: 24` — BUILD SUCCESS.

4. Frontend type-check (RUN):
   `npx tsc --noEmit` (cwd=frontend-app) → exit 0, no errors (this is the `tsc` step of
   `npm run build`; vite build not run to avoid the long bundle, type-check is the
   verification that matters for this change).

5. Frontend invoice-page test (RUN):
   `npx vitest run src/pages/__tests__/BillOpsAdminInvoiceDetailPricing.test.tsx`
   → `Tests 6 passed (6)`.

6. Frontend full suite (RUN):
   `npm run test` (vitest) → `Tests 9 failed | 1559 passed (1568)`. The 9 failures are in
   UNRELATED areas and NOT in the invoice detail page:
   `cloudk8sSidebar`, `loginAsAccountDashboard`, `BillOpsAdminDashboardPage` (AutoSpotting
   card), `EksDetailsPage` (`?tab=` routing), `K8sDataTable` (5s timeout). The working tree
   carries other in-progress uncommitted changes (connectivity-tab, CloudShell, etc.) that
   are not part of this task; these failures are pre-existing/unrelated. The page this task
   touches passes its own suite (step 5) and type-checks cleanly (step 4).

## Verified by READING (not run)

- PDF INR math unchanged: `addMainTable` still multiplies via
  `BigDecimal.valueOf(exchangeRate)`; `fetchUsdToInrRate` returns the same `double`, so no
  precision regression — the new branch only changes which `double` is chosen.
- CloudAccount per-account path, discount logic, `recalculateTotals`, tax computation and
  the detail-bill round trip are untouched (additive field + one new priority branch).
- Backwards compat: a finalized invoice with `exchangeRate == null` falls through to
  CloudAccount → live → 86.0 (backend) and renders without the read-only line (frontend).
