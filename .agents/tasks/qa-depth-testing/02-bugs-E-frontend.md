# Area E bug hunt: frontend-app (static review, read-only)

Method: code reading only. No build, no npm install, no tests run (node_modules is empty on this machine, see 01-test-plan.md section 2). "confirmed" means the defect is visible in the code as written (and, for contract bugs, in both frontend and backend source). It does not mean a runtime repro was executed. "suspected" means it depends on runtime data, deployment or a caller I did not trace. No secret values were read or printed.

Paths are relative to `frontend-app/` unless noted. Line numbers are from the working tree at review time.

## Coverage and limits

Read in full or in depth: `src/utils/apiClient.ts`, `src/main.tsx`, `src/contexts/AuthContext.tsx` (lines 1-300), `src/utils/sanitizeHtml.ts`, `src/demo/demoConfig.ts`, `src/utils/csvExport.ts`, `src/utils/currency.ts`, `js/calculator/calculator-utils.js`, the polling/timer hooks, every `dangerouslySetInnerHTML` site, the header of `CallContext.tsx`.
Sampled: `src/api/*` route strings checked against backend controllers (identity, scripts/customizations, soc2, finops-schedules, azure finops, cost-optimization-hub, stack-update, cur-sources, eks autoscaling, cloudmap graph, gcp billing-status, dashboard layout). Only the dashboard layout contract mismatched (E-01). Request/response DTO field names were not diffed for the other routes.
Not audited in depth: the body of `CallContext.tsx` (1203 lines; interval/BroadcastChannel cleanup looked present at lines 574, 706-709, 729 but I did not trace every effect), `xamopsMockData.ts` contents, `js/calculator/aws-calculator.js`, most of the 118 pages and 163 hooks.

Positive checks (no finding): all 12 `dangerouslySetInnerHTML` sites route through `sanitizeRichText` / `sanitizeInlineHtml`, except CloudMapPage.tsx:4310 which renders KaTeX output with `trust:false` (comment at 2148 explains it; acceptable). `usability-utils.js` toast escapes its message. Most percent computations are guarded with `total > 0`. `StoryNarrative` builds React elements, not HTML. The `DEFAULT_TIMEOUT_MS` in `apiFetch` exists. Tokens: no long-lived auth token in localStorage; the session cookie is used. The only token-like value in web storage is the impersonation JWT (E-02).

## Findings

### E-01 | Medium | Dashboard layout save/load contract is broken, and GCP overwrites the AWS layout
- Where: `src/hooks/useLayout.ts:36-39`, `src/services/dashboardApi.ts:206-216`, `src/api/gcpDashboardApi.ts:152-185`; backend `xamops-service/.../controller/DashboardController.java:324-339`, `domain/DashboardLayout.java:14-26`.
- Description: Backend `GET /api/xamops/dashboard/layout` returns the entity `{username, layoutConfig}` where `layoutConfig` is a JSON string. The frontend expects `{widgetOrder: string[]}` (`WidgetLayout`, types/liveDashboard.ts:196) and checks `layout.widgetOrder`, which is undefined, so a saved order is never applied. `POST` takes `@RequestBody String`, so the body `{"widgetOrder":[...]}` is stored verbatim as the string. The GCP client sends `{accountId, layout}` and a `?accountId=` query to the same endpoint. The backend keys on username only, so GCP and AWS dashboards overwrite each other's layout and `accountId` is ignored. Not run, so a Jackson mixin that unwraps `layoutConfig` is unlikely but I did not search for one.
- Repro / test idea: save a layout on the AWS dashboard, reload. Expect same order; code predicts default order. Vitest: mock `apiClient.get` to return the real backend shape `{username:'u', layoutConfig:'{"widgetOrder":["a","b"]}'}` and assert `loadLayout()` returns `['a','b']`. Backend MockMvc test: POST GCP shape, then GET and compare to what AWS saved.
- Fix: have the backend parse and return `{widgetOrder}` (or have the frontend `JSON.parse(layout.layoutConfig)`), and key the row by `username + dashboardType/accountId`.
- Status: confirmed (both sides read).

### E-02 | Medium | Impersonation JWT lives in sessionStorage and is taken from the URL hash with no validation
- Where: `src/contexts/AuthContext.tsx:52-70`, `src/main.tsx:32-44`.
- Description: `#imp=<jwt>` is accepted from any link, stored in `sessionStorage.impersonation_jwt`, and `window.fetch` then sends `Authorization: Bearer` on every internal request with `credentials:'omit'`. Any XSS on the origin can read the token. No `exp` check on the client, and claims are trusted for UI state (`sub`, `tenantId`, `allowedAccountIds` written straight to storage). A crafted link can switch a victim's tab to an attacker-chosen impersonation session (session fixation style). The server must validate the JWT, so this is a client-side hardening gap, not an auth bypass.
- Repro / test idea: open `/#imp=<forged.jwt>`; check storage keys are set before any server validation. RTL test around the IIFE with an expired token: it should be rejected.
- Fix: exchange a one-time code for the session server-side (HttpOnly), or at least check `exp`, clear on tab close, and require the opener's origin via `postMessage`.
- Status: confirmed (client behavior); server-side exposure suspected.

### E-03 | Medium | Production console logs full profile, request options (incl. bodies) and response headers
- Where: `src/utils/apiClient.ts:273` (logs `options`, which includes `body`), `:277-285` (all response headers); `src/contexts/AuthContext.tsx:191,240` (`JSON.stringify(data, null, 2)` of the user profile); `src/utils/apiClient.ts:200-212` (logs request headers for cloudmap/cloudsitter, including whatever `Authorization` the caller passed). Several polling hooks also log on every tick (`useCredits.ts:268`, `useBillOpsUserDashboard.ts:118`, `useDashboardData.ts:200`).
- Description: Every `apiClient.get/post/put` logs. POST bodies for account onboarding (cloud credentials, API tokens) and profile PII go to the browser console, which is captured by support tools, browser extensions and screen shares. The CSRF value is masked, but `options.body` and `headers` are not.
- Repro: DevTools, sign in, add a Jenkins or Azure account via `apiClient.post`, read the console. Test idea: spy `console.log` in a test calling `apiFetchJson` with a body containing `clientSecret` and assert it is not logged.
- Fix: strip these logs or gate them with `import.meta.env.DEV`; never log bodies or headers.
- Status: confirmed that bodies/profile are logged; suspected that credential-carrying bodies pass through `apiClient` (did not trace each caller).

### E-04 | Medium | BillOps ticket detail polling never shows a status change while the tab is visible
- Where: `src/hooks/useBillOpsTicketDetail.ts:~535-565`.
- Description: In the poll callback, `setTicket(ticketData)` for a status change sits inside `if (... && document.hidden)`. With the tab visible, the status change is skipped, then `previousStatusRef.current = ticketData.status` runs, so the change is recorded as "seen" and never rendered until a manual reload. Same shape for replies: only a higher reply count refreshes the ticket. Edits, deletes (count unchanged) and assignee changes by another user never appear. A user can therefore act (reply, close) on a stale status.
- Repro: open a ticket in tab A, change status from tab B or the admin UI, wait over 10 s. A stays on the old status. Test: fake timers plus mocked fetch returning a different status with `document.hidden=false`; assert `ticket.status` updates.
- Fix: always `setTicket(ticketData)` when the payload differs; use `document.hidden` only to gate the notification.
- Status: confirmed.

### E-05 | Medium | Ticket CSV export is vulnerable to formula injection and breaks columns on commas
- Where: `src/pages/BillOpsAdminTicketsPage.tsx:~707-723`; also `src/pages/AIOpsPage.tsx:~330-343`.
- Description: The admin export builds rows by `join(',')`. Only `accountName` and `subject` are quoted. A subject like `=HYPERLINK("http://x","click")` (customer-controlled) is quoted but not neutralised, so Excel executes it when an admin opens the file. `service`, `assignedToUsername`, `category`, `creatorUsername` are unquoted, so a comma shifts every later column. The safe helper `src/utils/csvExport.ts` (`csvCell`, with OWASP prefixing) exists but is not used here. AIOps export has the same unquoted `model`/`agentName`.
- Repro: create a ticket with subject `=1+1` and category `a,b`; export; open in a spreadsheet. Test: unit test the row builder with `=cmd`, `+1`, `a,b` inputs.
- Fix: use `toCsv`/`downloadCsv` from `utils/csvExport.ts`.
- Status: confirmed.

### E-06 | Medium | Date defaults use UTC (`toISOString`), so "today" is the wrong day for IST users for the first 5.5 hours
- Where: examples `src/components/FinOps/FinOpsCharts.tsx:21` (`fmtDate`) and `getPresetDates` just below; `src/components/FinOps/FinOpsHeader.tsx:27`; `src/components/GcpFinOps/GcpFinOpsCharts.tsx:21`; `src/components/AzureFinOps/AzureFinOpsCostCharts.tsx:23`; `src/components/LiveDashboard/TopServicesSection.tsx:37`, `CostByRegionSection.tsx:26`; `src/components/LaunchAnalytics/LaunchAnalytics.tsx:19-20,74`; `src/components/ActionsHistory/ActionsHistory.tsx:33-34`; `src/pages/BillOpsTicketDetailPage.tsx:2346` (`min` for meeting date); `src/components/Trial/TrialBanner.tsx:33`. (The product is used in IST, see `formatIST` and `IST_OFFSET_MS` elsewhere.)
- Description: `new Date().toISOString().split('T')[0]` is the UTC date. Between 00:00 and 05:30 IST it returns yesterday, so a "today" max/min bound, preset ranges ("Last 7 days" ends yesterday) and the meeting-date `min` are off by one day. Presets also use `start.setMonth(end.getMonth() - 3)`, which overflows on the 31st (for example 31 May minus 3 months lands on 31 Feb, which JS rolls to 3 March), producing a shorter range than intended.
- Repro: set system time to 02:00 IST (20:30 UTC previous day) and open FinOps, then read the default end date. Test: `vi.setSystemTime` to `2026-03-31T20:30:00Z` with `TZ=Asia/Kolkata`; expect local date `2026-04-01`.
- Fix: format with local fields (`toLocaleDateString('en-CA')` or a shared `localYmd(d)` helper); clamp month arithmetic.
- Status: confirmed (behaviour is deterministic from the code); impact depends on the user timezone.

### E-07 | Medium | MediaOps live capture hardcodes `ws://`
- Where: `src/pages/MediaOpsPage.tsx:1562`.
- Description: `new WebSocket(\`ws://${location.host}/browser-stream/...\`)`. On an HTTPS deployment the browser blocks it as mixed content (a synchronous `SecurityError` inside `startLive`, which is not wrapped in try/catch, so no error state is set and the UI is left mid-capture). If it did connect it would stream unencrypted. `ws.onclose` also reads a stale `captureState` from the closure (`useCallback` deps `[streamKey, captureState]` recreate the handler, but the `onclose` of the already-open socket keeps the old value), so the UI may not return to idle.
- Repro: open MediaOps over https and start a live capture. Test: stub `WebSocket` and assert the URL scheme follows `location.protocol`.
- Fix: `const scheme = location.protocol === 'https:' ? 'wss' : 'ws'`; wrap construction in try/catch and set the error state; use a ref for state read inside `onclose`.
- Status: confirmed for the hardcoded scheme (a prod deployment over https is assumed; nginx config not checked here); suspected for the stale `captureState`.

### E-08 | Medium | `isInternal` check can attach tenant/CSRF/impersonation credentials to non-same-origin URLs
- Where: `src/main.tsx:19-21` (and `:78-79` for axios), `src/utils/apiClient.ts:168`.
- Description: `url.startsWith('/') || url.includes(window.location.origin)`. A protocol-relative URL (`//evil.example/x`) starts with `/`, and any external URL that merely contains the app origin in its path or query (`https://evil.example/?r=https://app.xamops.com`) passes `includes`. For these, the patched `fetch` adds `X-Tenant-ID`, `X-Impersonate-User`, `X-XSRF-TOKEN`, and the impersonation `Authorization: Bearer <jwt>`. Exploitability needs some code path that fetches a URL influenced by user or tenant data. I did not find one, so this is latent.
- Repro / test: unit test `window.fetch('//example.com/a')` and `window.fetch('https://example.com/?x='+location.origin)`; assert no auth headers are added.
- Fix: parse with `new URL(url, location.href)` and compare `.origin === location.origin`.
- Status: confirmed (logic); exploit path suspected.

### E-09 | Low | `AuthContext.fetchUserRoles` can throw outside its try, leaving the app stuck on loading
- Where: `src/contexts/AuthContext.tsx:175-177` (`JSON.parse(sessionStorage.getItem('cached_user_roles') || '[]')` before `setIsLoading(true)` and before the `try`).
- Description: A corrupt or non-array `cached_user_roles` value (hand-edited, truncated by quota, or a different schema from a previous deploy) makes `JSON.parse` throw. The error escapes `fetchUserRoles` (unhandled promise rejection), `finally` never runs, `authReady` stays false and `isLoading` stays true (its initial value), so AccountContext never loads and the app spins forever. Also `.some(r => r.startsWith(...))` throws if the array holds non-strings.
- Repro: `sessionStorage.setItem('cached_user_roles','{x')` then reload. Test: RTL render of `AuthProvider` with that value, expect `authReady` to become true.
- Fix: move the parse inside the try or use a safe-parse helper.
- Status: confirmed (code path); needs corrupted storage.

### E-10 | Low | Redis/Mongo DB-ops hooks swallow errors and have no stale-response guard
- Where: `src/hooks/useRedisData.ts:50-71`, `src/hooks/useMongoData.ts:~60-76`.
- Description: `.catch(() => {})` leaves the previous data or empty arrays, so a failed fetch looks identical to "no keys / no slow log" (no error state exposed in the return value). No `cancelled` flag, so when `accountId` or `dbHost` changes quickly, a slow response from the previous account can overwrite the current account's state (cross-account data shown). `Promise.all` also drops all three panels if one endpoint fails.
- Repro: switch accounts quickly with the first request delayed; or return 500 and observe empty-state text. Test: deferred promises resolved out of order.
- Fix: add `error` state, an `AbortController` or `cancelled` flag, and `Promise.allSettled`.
- Status: confirmed.

### E-11 | Low | Dashboard polling ignores tab visibility and request overlap in several hooks
- Where: `src/hooks/useCredits.ts:33,266` (5 s), `src/pages/SreInvestigationsPage.tsx:35-39` (10 s, errors silently swallowed), `src/hooks/useBillOpsUserDashboard.ts:116` (30 s), `src/hooks/useBillOpsTicketDetail.ts:531` (10 s), `src/hooks/useInvoices.ts:406` (60 s), `src/hooks/useSupportDashboard.ts:415,422`. For comparison, `useBillOpsAdminDashboard.ts:158` and `useSupportChat.ts:255` do check `document.hidden`.
- Description: `setInterval(async …)` fires regardless of visibility and without an in-flight guard. With the 45 s client timeout (`DEFAULT_TIMEOUT_MS`) and a 5 s poll (credits), up to 9 requests can stack on a slow backend. Intervals are cleaned up on unmount in the hooks I read, so no leak found. The `useBillOpsUserDashboard` effect depends on `accounts.length` only, so the interval keeps a stale `loadDashboard` closure (eslint-disable at the effect).
- Repro: throttle the network to "slow 3G", watch the Network tab on Credits. Test: fake timers, never-resolving fetch, count calls after 60 s.
- Fix: skip when hidden, skip when a request is in flight, and use backoff.
- Status: confirmed (code); load impact suspected.

### E-12 | Low | Session-expiry handling is inconsistent between `apiFetch` and raw `fetch`
- Where: `src/utils/apiClient.ts:231-239` dispatches `session-expired` on 401; `src/main.tsx:23-63` (the patched `window.fetch` used by "120+ hooks" per its own comment) has no 401 handling.
- Description: Hooks using raw `fetch` (for example `useBillOpsTicketDetail`, `useEbsRightsizingData`) get a 401 and either return silently (`if (!res.ok) return`) or show a generic error, so an expired session shows blank or stale pages instead of redirecting to login. Also the handler requires `cached_user_roles` in sessionStorage, which a corrupted cache (E-09) breaks.
- Repro: expire the server session, keep the Ticket Detail page open, and watch it poll silently. Test: patched fetch returning 401, assert the `session-expired` event.
- Fix: dispatch the event from the patched `window.fetch` (once), not only from `apiFetch`.
- Status: confirmed (code).

### E-13 | Low | Axios adapter in `main.tsx` serialises params incorrectly and drops timeout/abort
- Where: `src/main.tsx:80-98`.
- Description: `new URLSearchParams(config.params)` turns `undefined` into the string `"undefined"`, `null` into `"null"`, arrays into comma-joined text and objects into `[object Object]`. Axios's own serializer skips undefined/null and repeats array keys. `config.signal`, `config.timeout`, `config.baseURL`, `responseType` and `withCredentials` are ignored, so axios requests (GCP dashboard) have no client deadline, cannot be cancelled on unmount and always parse JSON. `res.json().catch(() => null)` also turns an empty 204 into `data: null`.
- Repro: `axios.get('/api/xamops/gcp/x', {params:{accountId: undefined}})` and inspect the URL. Test: spy on `window.fetch`.
- Fix: delegate to axios's default `http`/`xhr` adapter in non-demo mode, or use `axios.getUri`/`buildURL` and forward `signal`.
- Status: confirmed (code).

### E-14 | Low | Money/percent formatters mishandle negatives and ignore the stated currency code
- Where: `src/utils/currency.ts` (`fmtMoneyShort`, `fmtMoney`), `src/components/FinOps/FinOpsCharts.tsx:16-19` (`fmtCurrency`), `src/components/Cost/CostBudgetDashboard.tsx:212-214` (`fmtShort`).
- Description: Abbreviation tests `n >= 1_000` only, so credits/refunds of -1,500,000 render as `$-1500000.00` / `₹-1500000` (no K/M, sign after the symbol). `fmtMoney(v, provider)` takes no currency code, and calls `currencyMeta(provider)` without a code, so Azure always shows ₹ even though `currencyMeta` supports a code; the file's own comment says that guess is wrong. `Math.round`/`toFixed` on `.5` values follow binary float rounding (for example `1.005.toFixed(2)` is `"1.00"`), a display issue only.
- Repro / test: `fmtMoneyShort(-1500000)`, `fmtMoney(10,'Azure')` with an EUR subscription. Add table tests for negative, zero, NaN, huge values.
- Fix: use `Math.abs` for the threshold and put the sign first; add an optional `code` argument to `fmtMoney`/`fmtMoneyShort`.
- Status: confirmed (code).

### E-15 | Low | Donut chart tooltip divides by `totalSpend` without a guard
- Where: `src/components/Dashboard/ProviderDonutChart.tsx:104`.
- Description: `((value / totalSpend) * 100).toFixed(1)` shows `NaN%` (0/0) or `Infinity%` when `totalSpend` is 0 while the legend code at line ~237 guards `total > 0`. Reachable for a new account or a zero-cost month if the chart still renders a slice.
- Repro / test: render with all-zero data and hover. Unit test of tooltip callback with `totalSpend=0`.
- Fix: `totalSpend > 0 ? … : '0.0'`.
- Status: confirmed (code); whether zero data reaches the chart depends on the parent (suspected).

### E-16 | Low | Demo mode is a client-controlled sessionStorage flag, the mock layer ships in the production bundle, and activation wipes localStorage
- Where: `src/demo/demoConfig.ts:50-72,76-79`, `src/main.tsx:16,22-30,88-97`, `src/utils/apiClient.ts:155-162`.
- Description: `isDemoMode()` is `sessionStorage.demo_mode === 'true'`. Any user can set it in DevTools and all API calls are answered from fixtures with no network call. Not a server security issue, but a QA/support risk: a stale flag shows fake numbers on a real account with no visible banner check here. `DEMO_EMAILS` includes real-looking addresses (`demo.xamops@xammer.in`, `demo.billops@xammer.in`), and on the demo host every signed-in user becomes demo. `activateDemoModeIfNeeded` runs `localStorage.clear()`, deleting unrelated keys (theme, call dismissals, onboarding flags) for those users. In `apiFetch`, when a demo fixture is missing (`resolveDemoResponse` returns null) the request falls through to the real backend in demo mode, so a demo session can hit live APIs with real credentials (read or write) for endpoints without fixtures.
- Repro: DevTools, `sessionStorage.demo_mode='true'`, reload, and check that numbers are fixtures. Test: `isDemoMode` true plus unknown URL, assert no network call (currently it falls through).
- Fix: fail closed in demo mode (return a 404/empty fixture rather than hitting the network), tree-shake demo code from the prod build behind a build flag, and replace `localStorage.clear()` with removing specific keys.
- Status: confirmed (code); real-backend fall-through impact suspected (depends on the demo-user's backend permissions).

### E-17 | Low | Legacy soft-nav handler executes scripts from fetched HTML
- Where: `src/soft-nav-handler.js:88-112`, wired in `src/main.js:29-37`.
- Description: `handleSoftNavigation` fetches `link.href`, parses it, and re-creates every `<script>` found in `#main-content` and `body` (copying `src` and inline text), then appends them to the document. It does not check that `link.href` is same-origin. If a `.soft-nav-link` anchor ever points to attacker-controlled HTML (for example user-supplied link content), this is script execution. It also monkey-patches `document.addEventListener` for 100 ms, which can drop or reroute legitimate listeners registered in that window. `src/main.js` looks like a legacy entry (the app boots from `main.tsx`); I did not confirm whether it is still bundled.
- Repro / test: not run. Verify with `grep -rn "soft-nav-link" src` and check `index.html` for the entry script.
- Fix: delete if unused; otherwise enforce same-origin and drop script re-execution.
- Status: suspected.

### E-18 | Low | `apiFetchJson` throws raw server error text as the Error message
- Where: `src/utils/apiClient.ts:287-296`.
- Description: `throw new Error(\`HTTP ${status}: ${errorText}\`)` puts the full response body (may include stack traces, SQL or internal hostnames from Spring error JSON) into `error.message`. Many hooks render `err.message` in the UI or toasts. Also `apiFetchJson` returns `{}` for an empty successful body, so callers that expect an array (`data.map`) crash on a 204. A JSON parse failure silently returns raw text typed as `T`.
- Repro: trigger a 500 on any `apiClient.get` page and read the visible message. Test: mock a 500 with a long JSON body.
- Fix: parse the error JSON, pick a safe `message`, keep the full body only in a non-displayed property; return `undefined`/`null` for empty bodies instead of `{}`.
- Status: confirmed (code); UI rendering of `err.message` suspected per page.

### E-19 | Low | CSRF cookie is parsed with `split('=')[1]` and never URL-decoded
- Where: `src/utils/apiClient.ts:45`, `src/main.tsx:37`.
- Description: A token containing `=` (base64 padding) is truncated at the first `=`, and an encoded value (`%3D`) is sent encoded. Spring's default `CookieCsrfTokenRepository` emits a UUID, which has neither, so this works today. A change of token format would make every mutating request fail with 403.
- Fix: `decodeURIComponent(row.slice(row.indexOf('=') + 1))`.
- Status: suspected (latent).

## Counts

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | |
| High | 0 | |
| Medium | 8 | E-01, E-02, E-03, E-04, E-05, E-06, E-07, E-08 |
| Low | 11 | E-09 to E-19 |
| Total | 19 | fully confirmed (in code): E-01, E-04, E-05, E-06, E-09, E-10, E-11, E-12, E-13, E-14, E-18. Fully suspected: E-17, E-19. Confirmed code behaviour with a suspected exploit or impact part: E-02, E-03, E-07, E-08, E-15, E-16. |

Notes on status: "confirmed" findings were verified by reading the code (and the matching backend code for E-01), not by running it. Run order for a first fix pass: E-01, E-04, E-05, E-06, E-03, E-08, E-02.
