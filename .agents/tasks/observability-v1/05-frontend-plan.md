# 05 — Frontend Plan (`frontend-app/src/features/observability-v1/`)

Abbreviations: `FE = /Users/admin/Desktop/xam-latest/XamOps-New/frontend-app/src`, `OBSFE = FE/features/observability-v1` (**new directory; `FE/features/` does not exist yet**, so this creates the repo's first feature folder). Route namespace `/observability-v1/*`. All endpoint paths come from 06; component names here are the names used in the 07 wireframes.

The existing Beta page, its `/observability/*` route, `FE/components/Observability/`, `FE/pages/ObservabilityPage.tsx`, `FE/styles/observability.css` are not imported, edited or restyled.

## 1. Stack decisions (locked on approval)

| Concern | Decision | Reason |
|---|---|---|
| Framework | React 19 + TypeScript, Vite 5, react-router-dom 7 (already) | Existing |
| Server state | @tanstack/react-query 5 with a **feature-local `QueryClient`** created in `ObservabilityV1Shell` and provided via a nested `QueryClientProvider` | The app's client persists the cache to storage for 24 h (`lib/queryClient.ts`); log lines and hostnames must never reach persisted storage. Per-query `staleTime` is set explicitly (metrics 15 s, hosts 30 s, storage 60 s, logs 0) |
| URL state | `useSearchParams` for query, time range, filters, selected host, view | Shareable links, back button, saved views |
| UI-local state | React state + two contexts (`ObsScopeContext`, `ObsPrefsContext`). No Redux/Zustand (none in repo) | Match repo |
| HTTP | `FE/utils/apiClient.ts` (`apiFetchJson<T>`) wrapped by `OBSFE/api/obsClient.ts` (adds typed error `ObsApiError{code,message,details,requestId,retryable}` parsed from the 06 envelope, `AbortSignal` support) | apiClient injects `X-XSRF-TOKEN`, `X-Tenant-ID`, impersonation header |
| Live tail | `fetch()` streaming (`ReadableStream` reader + SSE frame parser in `utils/sse.ts`, `credentials: 'include'`) in `useLogTail`, **not** `EventSource`: 4xx/5xx JSON bodies (`QUERY_SYNTAX_ERROR`, `TAIL_UNSUPPORTED_STAGE`, `FORBIDDEN`, `RATE_LIMITED`) are readable before the stream starts and `Last-Event-ID` can be sent as a header. Reconnect follows the server contract (full-jitter backoff 1 s to 30 s cap, 8 attempts, then a visible Reconnect button) | Contract in 06 section 10; not STOMP |
| Charts | (1) Shared CloudK8s charts (recharts-based, tokenised, tested): `ChartCard`, `Sparkline`, `Bars`, `StackedArea`, `SeriesLegend`, `ChartDataTable`, `TimeStrip`, `RangeBar`, `chartTokens.ts` for KPIs, sparklines, bars, small series. (2) **uPlot 1.6.x (MIT, new dependency, exact-pinned)** for dense time series (metric explorer, host detail, log histogram with brush; handles 10k+ points). (3) `cytoscape` (already a dependency) for the service map. **No** Highcharts (commercial) or Chart.js | One new renderer only where recharts is not fit for density |
| Virtualization | `@tanstack/react-virtual` 3.x (MIT, new, exact-pinned) for log table, host table > 200 rows | Not present in repo |
| Command palette | `cmdk` (already a dependency) in `ObsCommandPalette` (own component; the existing `components/Shared/CommandPalette.tsx` is not reused) | Scoped to Obs actions and entities |
| Motion | `framer-motion` (already) only for page/drawer transitions; every animation honours `prefers-reduced-motion` | Match App shell |
| Icons | Iconify Lucide, **string-literal ids** (`<Icon icon="lucide:activity" />`) so `npm run icons:bundle` picks them up | Offline bundle rule |
| Date/time | `Intl.DateTimeFormat` + small utils in `OBSFE/utils/time.ts` (no new date library) | Avoid dependency |
| Forms | Controlled components with local reducers; validation helpers in `OBSFE/utils/validators.ts`; Radix/shadcn primitives from `FE/components/ui/` | No form library in repo |
| Types | Hand-written in `OBSFE/types/*.ts` mirroring 06 (no codegen in 1.0) | Simple |
| Theming | New `OBSFE/styles/obsTokens.css` (CSS variables referencing existing semantic/chrome tokens + a few Obs-specific ones), Tailwind utility classes only; dark via `.dark` | Design-rule compliant |

## 2. Files to modify outside the feature folder (exhaustive)

| File | Change |
|---|---|
| `FE/App.tsx` | `const ObservabilityV1Shell = lazy(() => import('./features/observability-v1/ObservabilityV1Shell'));` next to the other lazy imports; inside the protected layout add `<Route path="/observability-v1" element={<Navigate to="/observability-v1/overview" replace />} />` and `<Route path="/observability-v1/*" element={<ObservabilityV1Shell />} />` (next to the `/xamsecure` routes, lines ~550-551). The existing `/observability/*` route and import are untouched |
| `FE/components/Sidebar/Sidebar.tsx` | (a) import `OBS_SIDEBAR_TABS` from `../../features/observability-v1/constants/tabs`; (b) new `<NavSection icon="fas fa-heart-pulse" label="XamPulse" gradientClass="from-emerald-500 to-teal-600" badge="New" isOpen={openDropdown === 'obsv1'} onToggle={() => toggleDropdown('obsv1')}>` placed **after** the existing Observability section, wrapped in `isXamModuleAllowed('observability-v1') &&`; items mapped from the tab catalog, each gated by `isXamTabAllowed('observability-v1-<tab>')`, plus a pinned primary action item "Connect servers" (`/observability-v1/connect`); (c) **route-prefix collision:** the existing auto-open table does `path.startsWith('/observability')`, which also matches `/observability-v1/...` and would open the Beta dropdown. Add the check `path.startsWith('/observability-v1') -> setOpenDropdown('obsv1')` **before** the existing observability line and make the existing check exclude `-v1` (`path.startsWith('/observability') && !path.startsWith('/observability-v1')`). This is the only edit near the Beta logic and does not change Beta behaviour |
| `FE/components/Sidebar/iconMap.ts` | Add the missing `FA_TO_LUCIDE` entries used by the new section, **including `fa-heart-pulse → lucide:activity`** (verified absent today, so without it `NavIcon` would render a Font Awesome glyph) plus the others listed in section 3.3 |
| `FE/constants/xamopsTabs.ts` | Add module `{ key: 'observability-v1', label: 'XamPulse', icon, tabs: [overview, hosts, logs, metrics, containers, services, alerts, storage, agents, settings] }` with keys `observability-v1-<tab>` |
| `FE/test/designRules.test.ts` | Add `'features/observability-v1/'` to `GUARDED_PATHS` and the new token file to the token-file allow-list deliberately (same change as the CSS file); add `obsTokens.css` contrast pairs to the WCAG table |
| `frontend-app/package.json` | Add `uplot` and `@tanstack/react-virtual` (exact versions pinned at install time) |
| `frontend-app/vite.config.ts` | Only if `/api/obs` is not already covered by the existing `/api` proxy rule (check; SSE needs `ws: false`, no buffering — Vite's http-proxy streams by default) |
| `FE/demo/` (demo-mode router used by `apiClient`) | If demo mode is on, calls to `/api/obs/v1/**` must return a friendly `DEMO_UNAVAILABLE` state. Read `FE/demo/demoRouter` before implementation; add a stub route returning an empty fleet so demo users see the designed empty state (no fixtures of fake customer data) |

No other shared file is modified.

## 3. Information architecture in code

### 3.1 Tab catalog — `OBSFE/constants/tabs.ts`

Copies the pattern of `FE/constants/xamsecureTabs.ts` (single source for router and sidebar).

```ts
export const OBS_BRAND = 'XamPulse';
export type ObsTab = 'overview' | 'hosts' | 'logs' | 'metrics' | 'containers' | 'services' | 'alerts' | 'storage' | 'agents' | 'settings';
export interface ObsTabDef { id: ObsTab; label: string; icon: string /* iconify literal */; sidebarIcon: string /* fa token for NavIcon */; description: string; sidebar?: boolean; }
export const OBS_TABS: ObsTabDef[] = [ /* order below */ ];
export const OBS_SIDEBAR_TABS = OBS_TABS.filter(t => t.sidebar !== false);
export const VALID_OBS_TABS = new Set(OBS_TABS.map(t => t.id));
```

Final sub-tab names and order: **Overview, Hosts, Logs, Metrics, Containers, Services, Alerts, Storage, Agents, Settings**. "Connect" is a full-screen route (not a tab) entered from the sidebar action, the Overview nudge, the Hosts empty state and the command palette.

`sidebarIcon` is a `fas fa-*` token because `NavIcon` resolves Lucide via `FA_TO_LUCIDE` (it takes FA class strings); this file is therefore the one place an FA token literal exists. The design-rule guard forbids Font Awesome in guarded files, so the sidebar tokens live in a sibling file **`OBSFE/constants/sidebarIcons.ts`** that is excluded from the guard by an explicit exception line in `designRules.test.ts` (documented there, same justification as `Sidebar.tsx` itself being unguarded). `tabs.ts` keeps only Iconify ids.

### 3.2 Routes (inside the shell, `OBSFE/ObservabilityV1Shell.tsx`)

| Path | Page component | Notes |
|---|---|---|
| `/observability-v1/overview` | `pages/OverviewPage.tsx` | |
| `/observability-v1/connect` | `pages/ConnectPage.tsx` | full-screen layout (no tab chrome) |
| `/observability-v1/hosts` | `pages/HostsPage.tsx` | `?view=table|map`, filters in URL |
| `/observability-v1/hosts/:hostId` | `pages/HostDetailPage.tsx` | `?tab=overview|metrics|logs|processes|containers|config` |
| `/observability-v1/logs` | `pages/LogsPage.tsx` (view `explorer`) | `?q=&from=&to=&hosts=&accounts=&live=1&src=` |
| `/observability-v1/logs/patterns` | `pages/LogsPage.tsx` (view `patterns`) | |
| `/observability-v1/logs/saved` | `pages/LogsPage.tsx` (view `saved`) | |
| `/observability-v1/logs/archive/:jobId` | `pages/LogsPage.tsx` (view `archive`) | archive job results |
| `/observability-v1/metrics` | `pages/MetricsPage.tsx` (explorer) | `?m=&agg=&by=&f=&from=&to=&cmp=` |
| `/observability-v1/metrics/dashboards` | `pages/MetricsPage.tsx` (dashboards list) | |
| `/observability-v1/metrics/dashboards/:dashboardId` | `pages/DashboardPage.tsx` | |
| `/observability-v1/containers` | `pages/ContainersPage.tsx` | |
| `/observability-v1/containers/:containerId` | `pages/ContainerDetailPage.tsx` | |
| `/observability-v1/services` | `pages/ServicesPage.tsx` | `?view=map|table` |
| `/observability-v1/services/:serviceId` | `pages/ServiceDetailPage.tsx` | |
| `/observability-v1/alerts` | `pages/AlertsPage.tsx` (incidents) | |
| `/observability-v1/alerts/incidents/:incidentId` | `pages/AlertsPage.tsx` (drawer open on deep link) | |
| `/observability-v1/alerts/rules` | `pages/AlertRulesPage.tsx` | |
| `/observability-v1/alerts/rules/new`, `/alerts/rules/:ruleId` | `pages/AlertRuleEditorPage.tsx` | |
| `/observability-v1/alerts/slos` | `pages/SlosPage.tsx` | |
| `/observability-v1/alerts/channels` | `pages/ChannelsPage.tsx` | |
| `/observability-v1/alerts/mutes` | `pages/MutesPage.tsx` | |
| `/observability-v1/storage` | `pages/StoragePage.tsx` | |
| `/observability-v1/storage/connect` | `pages/StorageConnectPage.tsx` | bucket wizard reached from Storage and Connect |
| `/observability-v1/agents` | `pages/AgentsPage.tsx` | |
| `/observability-v1/settings` → `/settings/general` | `pages/SettingsPage.tsx` | `/:section` = `general|tokens|access|redaction|cost|audit|ai` |
| `*` | `components/shell/ObsNotFound.tsx` | friendly 404 inside the shell |

Pages are `lazy()` inside the shell so the Logs/uPlot code only loads when opened.

### 3.3 Sidebar icon tokens to add to `FA_TO_LUCIDE` (only keys that are missing; check the file first)

`fa-heart-pulse → lucide:activity`, `fa-gauge-high → lucide:gauge`, `fa-server → lucide:server`, `fa-file-lines → lucide:scroll-text`, `fa-chart-area → lucide:area-chart`, `fa-cubes → lucide:boxes`, `fa-diagram-project → lucide:network`, `fa-bell → lucide:bell`, `fa-database → lucide:database`, `fa-plug → lucide:plug`, `fa-gear → lucide:settings`, `fa-plus → lucide:plus`.

## 4. File-by-file (all under `OBSFE/`)

### 4.1 Root and shell

| File | Responsibility |
|---|---|
| `ObservabilityV1Shell.tsx` | Default export. Nested `QueryClientProvider` (feature client), `ObsScopeProvider`, `ObsPrefsProvider`, `ObsPermissionProvider` (loads `GET /me/permissions`), theme sync (reads `useTheme()` only), `ObsErrorBoundary`, `<Routes>` per section 3.2, global hotkeys + `ObsCommandPalette` mounted once |
| `index.ts` | Re-exports `OBS_SIDEBAR_TABS` and `OBS_BRAND` only (so Sidebar does not import the shell) |
| `constants/tabs.ts`, `constants/sidebarIcons.ts`, `constants/queryKeys.ts`, `constants/permissions.ts` (`ObsPermission` union mirroring 06 section 11), `constants/shortcuts.ts`, `constants/sources.ts` (selectable log/metric sources with descriptions), `constants/limits.ts` (hot window 14 d, max range 7 d, tail buffer 5000, page sizes) | |
| `styles/obsTokens.css` | Token definitions (section 7) |

### 4.2 API client (`api/`)

| File | Functions (all return typed promises; names mirror 06) |
|---|---|
| `obsClient.ts` | `obsGet<T>`, `obsPost<T>`, `obsPut`, `obsPatch`, `obsDelete`, `ObsApiError`, `toQueryString` |
| `overviewApi.ts` | `getOverviewSummary`, `getFleetHealth`, `getMyPermissions`, `getMyAccounts` |
| `onboardingApi.ts` | `getDiscovery`, `runPreflight`, `createInstallJob`, `getInstallJob`, `retryTarget`, `cancelJob`, `rollbackJob`, `createInstallCommand`, `createSnippet`, `getTrustManifest`, `getPermissionStack`, `getPolicyPreview`, `getStackLaunchUrl`, `verifyPermissionStack` |
| `hostsApi.ts` | `listHosts`, `getHost`, `getHostProcesses`, `getHostTimeline`, `patchHost`, `disconnectHost`, `listHostGroups`, `saveHostGroup` |
| `containersApi.ts`, `servicesApi.ts` | list/detail, `getServiceMap` |
| `agentsApi.ts` | `getFleet`, `listReleases`, `createRollout`, `controlRollout`, `sendBulkCommand`, `listConfigProfiles`, `saveConfigProfile` |
| `logsApi.ts` | `searchLogs`, `getLogHistogram`, `getLogFacets`, `getLogStats`, `getLogContext`, `getLogPatterns`, `validateQuery`, `getLogFields`, `createQueryJob`, `getQueryJob`, `getQueryJobResults`, `cancelQueryJob`, `getExportDownload`, saved-query and saved-view CRUD, `buildTailUrl(params)` |
| `metricsApi.ts` | `getMetricCatalog`, `queryMetrics`, `getMetricLabels`, `correlateMetric`, dashboards CRUD, `getSuggestedDashboards` |
| `alertsApi.ts` | rules CRUD, `previewRule`, `toggleRule`, incidents list/detail/acknowledge/resolve/comment, channels CRUD/test, escalation CRUD, mute CRUD, SLO CRUD/budget |
| `storageApi.ts` | `getStorageOverview`, `listBuckets`, `validateDestination`, `saveDestination`, `getCreateBucketUrl`, `migrateDestination`, `getDestinationUsage`, `getAthenaDdl`, `getUsage` |
| `insightsApi.ts`, `aiApi.ts` | `listInsights`, `dismissInsight`, `getRightsizing`, `getNoise`; `summarize`, `nlQuery`, `rca` |
| `settingsApi.ts` | tenant settings, redaction rules + preview, cost controls, tokens list/revoke, permissions, audit |

### 4.3 Types (`types/`)

`common.ts` (`Scope`, `TimeRange`, `Page<T>`, `ObsError`), `overview.ts`, `onboarding.ts` (`DiscoveredInstance`, `PreflightCheck`, `InstallTarget`, `InstallJob`, `InstallMethod`), `hosts.ts` (`HostSummary`, `HostDetail`, `HostState`), `agents.ts`, `logs.ts` (`LogRow`, `LogSearchRequest/Response`, `Facet`, `Pattern`, `TailEvent`, `QueryJob`, `ArchivePlan`), `metrics.ts` (`MetricQuery`, `MetricSeries`, `CatalogEntry`), `alerts.ts` (`AlertRule`, `Incident`, `Channel`, `MuteWindow`, `Slo`), `services.ts`, `storage.ts` (`Bucket`, `ValidationCheck`, `CostEstimate`, `Destination`), `insights.ts`, `settings.ts`. Field names and enums identical to 06 JSON.

### 4.4 State and hooks

| File | Responsibility |
|---|---|
| `state/ObsScopeContext.tsx` | `{ accountIds, regions, hostKeys, tags, timeRange, compare, setters }`. Initialises accountIds from `useAccounts().selectedAccountIds` mapped to cloud account PKs (see below), time range from URL (`from`,`to`) or default `now-1h`. Single writer to the URL |
| `state/ObsPrefsContext.tsx` | density (`COMFORTABLE|COMPACT`), timezone (`LOCAL|UTC`), log columns, wrap lines, last used view per page. Stored in `localStorage` key `xp.prefs.v1` (no log data) |
| `state/ObsPermissionContext.tsx` | permissions array and `can(permission)` |
| `hooks/useObsScope.ts`, `useTimeRange.ts` (parse `now-15m`, absolute ISO, quick ranges, live flag, refresh interval), `useCompareRange.ts`, `useObsUrlState.ts` (typed `useSearchParams` helpers) | |
| `hooks/useObsPermissions.ts` | wrapper over context |
| `hooks/useDiscovery.ts`, `useInstallJob.ts` (poll 2 s while RUNNING, stop on terminal state), `useInstallCommand.ts`, `usePermissionStack.ts`, `useBuckets.ts`, `useDestinationValidation.ts` | Connect flow |
| `hooks/useHosts.ts`, `useHostDetail.ts`, `useHostProcesses.ts`, `useHostTimeline.ts`, `useFleet.ts`, `useContainers.ts`, `useServices.ts`, `useServiceMap.ts` | |
| `hooks/useLogSearch.ts` (infinite query by cursor, keyed by query+scope+range, `AbortSignal` on change), `useLogHistogram.ts`, `useLogFacets.ts`, `useLogTail.ts` (fetch-stream state machine: `idle/connecting/live/paused/throttled/reconnecting/gap/ended/error`; maps pre-stream error codes to `ObsStateBlock` variants; dedups rows by row `id` over a rolling set of 5,000 ids; ring buffer of 5000, counters `received/dropped`; sends at most 10 account ids), `useQueryJob.ts` (poll 1.5 s), `useLogPatterns.ts`, `useSavedQueries.ts`, `useSavedViews.ts`, `useQueryValidation.ts` (debounced 250 ms `validate`) | |
| `hooks/useMetricCatalog.ts`, `useMetricSeries.ts`, `useCorrelation.ts`, `useDashboards.ts` | |
| `hooks/useAlertRules.ts`, `useIncidents.ts`, `useChannels.ts`, `useMutes.ts`, `useSlos.ts`, `useRulePreview.ts` | |
| `hooks/useStorageOverview.ts`, `useUsage.ts`, `useInsights.ts`, `useAi.ts`, `useSettings.ts` | |
| `hooks/useObsShortcuts.ts` | Registers shortcuts (section 5) with scope awareness (ignored while typing in inputs except `Esc`) |
| `hooks/usePrefersReducedMotion.ts` | Small local hook (the XamSecure one is not imported) |
| `hooks/useDebouncedValue.ts`, `useInterval.ts`, `useClipboard.ts`, `useElementSize.ts` | utilities |

**Cloud account PK mapping.** `AccountContext` exposes `selectedAccountIds: string[]` (12-digit ids) and `accounts`. The Obs API wants `cloud_account.id`. During implementation read the `Account` type in `FE/contexts/AccountContext.tsx`: if it already carries the numeric primary key use it; otherwise `ObsScopeContext` calls a tiny new endpoint `GET /api/obs/v1/me/accounts` (returns `[{cloudAccountId, awsAccountId, name, provider, status}]` for AWS accounts the user can access, backed by `CloudAccountAccessService.canAccess`) and joins on `awsAccountId`. Decision: **add that endpoint now** (listed in 06 section 4.1 addendum below) so the frontend never guesses. Because an AWS account can be connected twice, the join key is the PK returned by this endpoint, and the scope bar shows the account name.

(The endpoint is defined in 06 section 4.1 and served by `ObsOverviewController`; `api/overviewApi.ts` exposes `getMyAccounts`.)

### 4.5 Components

**`components/shell/`:** `ObsLayout.tsx` (page frame: header, scope bar, content, drawer slot), `ObsPageHeader.tsx` (title, subtitle, primary action, secondary actions, last-updated), `ObsScopeBar.tsx` (account chips, region/tag/host filter popovers, `ObsTimePicker`, `ObsCompareToggle`, refresh + live toggle, density toggle), `ObsTimePicker.tsx` (quick ranges 5m–7d, absolute picker, relative input "now-90m", timezone, **compare to previous period/day/week**), `ObsCompareToggle.tsx`, `ObsCommandPalette.tsx` (cmdk; groups: Navigate, Hosts, Services, Saved queries, Actions "Connect servers", "Start live tail", "Create alert", "Toggle theme"), `ObsShortcutsDialog.tsx`, `ObsDensityToggle.tsx`, `ObsErrorBoundary.tsx`, `ObsPermissionGate.tsx` (renders `NoPermissionState` or children), `ObsNotFound.tsx`, `ObsStateBlock.tsx` (the single component for all non-happy states: `variant="loading|empty|error|waiting|noPermission|noAgents|degraded"`, takes title, body, primary/secondary action, illustration key), `ObsSkeleton.tsx` (line, card, table, chart skeletons), `ObsLiveBadge.tsx`, `ObsCopyButton.tsx`, `ObsRelativeTime.tsx`.

**`components/common/`:** `ObsKpiCard.tsx` (composes shared `status/KpiCard` + `Delta` + `Sparkline`), `HealthScoreRing.tsx`, `ObsStatus.tsx` (shared `StatusMark`/`StatusDot` + word; **never colour alone**), `SeverityChip.tsx` (letter + word: C/H/M/L/I), `LevelBadge.tsx`, `FilterChipBar.tsx` (chips with counts, `tint`), `ActiveFilters.tsx`, `GroupingTabs.tsx`, `SavedViewsMenu.tsx`, `DrilldownLink.tsx`, `EntityLink.tsx`, `SectionCard.tsx` (composes shared `Surface`), `InsightCard.tsx`, `CostBadge.tsx`, `UnitValue.tsx` (bytes, ratio, ms formatting via `utils/format.ts`), `HelpTip.tsx` (wraps shared `TermTip`).

**`components/charts/`:** `ObsTimeSeriesChart.tsx` (uPlot wrapper: cursor sync across a group via `uPlot.sync`, crosshair, legend, brush selection emits `onRangeSelect`, annotation layer, theme-aware colours read from CSS variables, `aria-label` and a hidden `ChartDataTable` alternative, resize observer, downsample to ≤ 2 points per pixel), `ObsBrushHistogram.tsx` (stacked level bars + brush), `ObsSparkline.tsx`, `ObsStackedBars.tsx`, `ObsGaugeBar.tsx`, `ObsHostMap.tsx` (tile grid coloured by health with a letter glyph per state), `ObsHeatStrip.tsx`, `ObsChartLegend.tsx`, `ObsChartTooltip.tsx`, `ObsAnnotationLayer.tsx` (incidents, deploys, agent events), `ObsChartCard.tsx` (title, unit, compare toggle, drill-down menu: "Open logs for this window", "Open metric explorer", "Create alert from this", "Copy query").

**`components/overview/`:** `OverviewKpiStrip.tsx`, `FleetHealthCard.tsx`, `TopIssuesCard.tsx`, `InsightsCard.tsx`, `NoisySourcesCard.tsx`, `CostCard.tsx`, `StorageStatusCard.tsx`, `OnboardingNudge.tsx`.

**`components/connect/`:** `ConnectPage` pieces — `AccountPicker.tsx`, `ServersCard.tsx`, `InstanceFilters.tsx`, `InstanceTable.tsx` (virtualized, checkbox select, "select all N matching" banner `SelectAllBanner.tsx`), `PreflightBadge.tsx`, `StorageCard.tsx`, `StorageModePicker.tsx` (Managed / Existing bucket / Create new), `BucketPicker.tsx`, `BucketValidationList.tsx`, `CostEstimateBox.tsx`, `InstallCard.tsx`, `InstallMethodToggle.tsx`, `SourcesChecklist.tsx`, `InstallCommandBox.tsx`, `PermissionStackBanner.tsx`, `PolicyPreviewDialog.tsx`, `TrustPanel.tsx`, `ConnectSummaryBar.tsx` (sticky, primary button), `InstallProgressRail.tsx`, `TargetRow.tsx`, `FailureFixCard.tsx`, `SuccessPanel.tsx`.

**`components/hosts/`:** `HostsToolbar.tsx`, `HostTable.tsx`, `HostRow.tsx`, `HostMap.tsx`, `HostHealthBadge.tsx`, `HostDetailHeader.tsx`, `AgentStatusCard.tsx`, `HostOverviewTab.tsx`, `HostMetricsTab.tsx` with `HostCpuPanel.tsx`, `HostMemoryPanel.tsx`, `HostDiskPanel.tsx`, `HostNetworkPanel.tsx`; `HostLogsTab.tsx`, `HostProcessesTab.tsx`, `HostContainersTab.tsx`, `HostConfigTab.tsx`, `HostTimeline.tsx`, `HostTagList.tsx`.

**`components/logs/`:** `LogsSearchBar.tsx` (XLQ input: syntax highlighting, field/value autocomplete from `/logs/fields`, chip view, `ValidationHint`, `CompiledQueryPopover.tsx`, `QueryHelpPopover.tsx`), `LogsHistogram.tsx` (wraps `ObsBrushHistogram`), `FacetSidebar.tsx` + `FacetGroup.tsx` (click include, shift-click exclude), `LogsTable.tsx` (virtualized) + `LogRow.tsx` + `LogMessageCell.tsx` (JSON expand, highlight matches, stack-trace folding), `ColumnChooser.tsx`, `LogDetailDrawer.tsx` (composes shared `DetailDrawer`; tabs Fields, Raw, Context, Metrics-at-time, Pattern), `LogContextPanel.tsx`, `LiveTailControls.tsx`, `LiveTailBanner.tsx`, `ArchiveSearchPanel.tsx`, `ArchivePlanCard.tsx`, `ArchiveJobProgress.tsx`, `PatternsView.tsx` + `PatternRow.tsx`, `SavedQueriesView.tsx`, `LogsExportMenu.tsx`, `LogsEmptyState.tsx`, `AiSummaryButton.tsx`, `NlQueryBox.tsx`.

**`components/metrics/`:** `MetricsExplorer.tsx`, `MetricPicker.tsx` (catalog tree by category), `MetricQueryBuilder.tsx`, `FilterRow.tsx`, `GroupByPicker.tsx`, `MetricChartGrid.tsx`, `CorrelateButton.tsx`, `CorrelationPanel.tsx`, `DashboardsView.tsx`, `DashboardGrid.tsx`, `DashboardWidget.tsx`, `SuggestedDashboards.tsx`, `ComparePreviousToggle.tsx`.

**`components/containers/`:** `ContainersTable.tsx`, `ContainerRow.tsx`, `ContainerDetailHeader.tsx`, `ContainerLimitsPanel.tsx`, `ContainerRestartsTimeline.tsx`. **`components/services/`:** `ServiceMap.tsx` (cytoscape), `ServiceNode.tsx`, `ServiceTable.tsx`, `ServiceDetailHeader.tsx`, `RedPanel.tsx`.

**`components/alerts/`:** `IncidentsTable.tsx`, `IncidentRow.tsx`, `IncidentDrawer.tsx`, `IncidentTimeline.tsx`, `RcaPanel.tsx`, `RulesTable.tsx`, `RuleTypePicker.tsx`, `RuleEditor.tsx` with `ThresholdForm.tsx`, `AnomalyForm.tsx`, `LogPatternForm.tsx`, `ForecastForm.tsx`, `CompositeForm.tsx`, `AbsenceForm.tsx`, `SloBurnForm.tsx`, `RulePreviewPanel.tsx`, `EscalationEditor.tsx`, `ChannelsView.tsx`, `ChannelForm.tsx`, `MuteWindowsView.tsx`, `MuteForm.tsx`, `SlosView.tsx`, `SloCard.tsx`, `ErrorBudgetBar.tsx`.

**`components/storage/`:** `StorageOverview.tsx`, `DestinationCard.tsx`, `UsageChart.tsx`, `TierDistribution.tsx`, `CostPanel.tsx`, `RetentionControl.tsx`, `BucketStatusBanner.tsx`, `MigrateDialog.tsx`, `AthenaDdlDialog.tsx`, `PermissionStackCard.tsx`. **`components/agents/`:** `AgentFleetTable.tsx`, `VersionDriftBar.tsx`, `RolloutPanel.tsx`, `ReleaseList.tsx`, `ConfigProfileEditor.tsx`, `BulkActionBar.tsx`, `AgentHealthDrawer.tsx`, `TokenRotationCard.tsx`. **`components/settings/`:** `SettingsNav.tsx`, `GeneralSettings.tsx`, `TokensSettings.tsx`, `AccessSettings.tsx`, `RedactionSettings.tsx`, `RedactionPreview.tsx`, `CostControlsSettings.tsx`, `AuditSettings.tsx`, `AiSettings.tsx`.

### 4.6 Utilities (`utils/`)

`format.ts` (bytes, number, percent, duration, relative time), `time.ts` (range parsing, compare shifting, bucket sizing: `chooseStep(from,to,widthPx)`), `xlq.ts` (client-side tokenizer for highlighting and chip rendering only; the server is authoritative), `facets.ts` (add/remove filter terms into the query string safely), `colors.ts` (maps series index → CSS variable name), `health.ts` (state → label/letter glyph/tone), `validators.ts`, `download.ts`, `permissions.ts`, `sse.ts` (fetch-streaming SSE client: frame parser, typed events `status|log|heartbeat|error|end`, readable HTTP error bodies, `Last-Event-ID` header, abort support), `storageKeys.ts`.

### 4.7 Tests (`__tests__/` colocated)

`utils/format.test.ts`, `time.test.ts`, `xlq.test.ts`, `facets.test.ts`; `hooks/useLogTail.test.ts` (fake fetch stream: connect, batch, throttled, pre-stream 400/403/429 bodies rendered, reconnect with Last-Event-ID header, duplicate rows dropped by id, `RESUMED_WITH_GAP` divider, ring buffer overflow), `useInstallJob.test.ts`; `components/ConnectPage.test.tsx` (select-all-matching, blocked rows, one-click disabled without stack, success), `LogsPage.test.tsx` (hot→archive transition on `RANGE_OUTSIDE_HOT_WINDOW`), `ObsStateBlock.test.tsx`, `ObsTimePicker.test.tsx`, `ObsCommandPalette.test.tsx`, `a11y` tests (`jest-axe` equivalents already in repo? check; otherwise Testing Library role queries + focus-trap assertions), and `designRules` coverage via the shared test after the guard path is added.

## 5. Interaction specifics (implementation level)

- **Global shortcuts** (`constants/shortcuts.ts`): `Cmd/Ctrl+K` palette, `/` focus search, `g o|h|l|m|c|s|a|t|g` go to Overview/Hosts/Logs/Metrics/Containers/Services/Alerts/sTorage/aGents (two-key sequence, 1 s timeout), `t` toggle live tail on Logs, `[`/`]` shift time window back/forward by half range, `-`/`=` zoom out/in, `r` refresh, `e` toggle error-only filter, `?` shortcuts dialog, `Esc` close drawer/palette, `j/k` move row selection, `Enter` open drawer, `x` select row on tables, `c` copy selected log line.
- **Drill-down from every chart:** `ObsChartCard` menu and click-drag selection (brush) → "Zoom to range" (updates URL range) / "Show logs in this range" (navigates to `/observability-v1/logs` with `hosts`, `from`, `to`, `q=level:(error OR warn)` prefilled). Clicking a series legend → host detail.
- **Compare to previous:** `ObsTimePicker` toggle adds `cmp=PREVIOUS_PERIOD|PREVIOUS_DAY|PREVIOUS_WEEK`; charts request `compareTo`, draw the previous series dashed (dash pattern + legend label "previous", never colour alone), KPI cards show `Delta`.
- **URL ↔ state** is lossless for explorer pages; Saved views store the same params object in `state_json`.
- **Live tail UX state machine:** `idle → connecting → live ⇄ paused`, `live → throttled` (banner with dropped count) , `live → reconnecting → live`, `ended` (30 min cap; auto-reconnect once silently, then shows "Resumed"), `error`. Pausing buffers up to 5,000 rows and shows "N new lines" pill; resuming prepends.
- **Hot vs archive:** if the selected range starts earlier than `now - 14d` the Logs page shows `ArchiveSearchPanel` instead of results: plan card (files, bytes, estimated seconds, storage classes, restore warnings), "Run archive search" button, then `ArchiveJobProgress` with Cancel; results open in the same `LogsTable` with an "Archive" badge on the page header.
- **Permissions in UI:** controls the user cannot use are rendered disabled with a tooltip stating the missing permission (not hidden), except whole tabs the user may not open (sidebar gating via `isXamTabAllowed`) and log content areas, which show `NoPermissionState` text "You can see health and counts. Ask an admin for log access."
- **Error states:** `ObsApiError.code` → copy table in `utils/errorCopy.ts` (for example `HOT_STORE_UNAVAILABLE` → "Recent logs are temporarily unavailable. You can still search the archive."), always with a retry action and the `requestId` in a "Details" disclosure.
- **Persisted-data hygiene:** no log content, hostnames or queries in `localStorage`/`sessionStorage`; only prefs and last-used page. Saved queries are server-side.
- **Performance:** route-level code splitting; uPlot and cytoscape imported only in the pages that use them; table/log lists virtualized; chart data capped at 11,000 points per request (server) and decimated client-side; `useLogSearch` aborts in-flight requests on query change; skeletons within 100 ms; budgets in 07.

## 6. Accessibility (implementation rules)

Landmarks (`header`, `nav`, `main`), skip-to-content link inside the shell; every interactive control reachable and operable by keyboard; visible focus ring token `--obs-focus`; `DetailDrawer` focus trap and `Esc` (shared component already does); live regions: `aria-live="polite"` for install rail updates and "N new lines" pill, `assertive` only for blocking errors; tables use real `<table>` semantics (virtualized rows keep `aria-rowindex`/`aria-rowcount`); charts expose `aria-label` summaries and a "View as table" toggle (shared `ChartDataTable`); status uses text + glyph + colour; target sizes ≥ 32 px (24 px minimum in dense mode with spacing); `prefers-reduced-motion` disables count-up, skeleton shimmer and drawer slides; contrast pairs added to the guard table. Full WCAG validation requires manual assistive-technology testing and expert review (stated in 08).

## 7. Theming tokens — `OBSFE/styles/obsTokens.css`

Identity: **"Signal on ink."** Calm slate canvas (reuses `--chrome-*`), one signature accent (a teal "pulse") used sparingly for primary actions, the live indicator and focus, status tones from the existing semantic palette (good/warn/bad/info/off), Okabe-Ito series colours reused for multi-series so charts stay colour-blind safe. No gradients, no emoji, no Font Awesome inside the feature. Variables are space-separated RGB triples like the existing semantic tokens.

| Token | Purpose | Notes |
|---|---|---|
| `--obs-accent` / `--obs-accent-strong` / `--obs-accent-soft` | pulse teal (primary buttons, live dot, focus) | values defined in the CSS file with both `:root` and `.dark` variants, contrast pairs registered in the guard |
| `--obs-surface-0/1/2` | page / card / raised | alias `--chrome-page`, `--chrome-card`, `--chrome-surface-2` |
| `--obs-line`, `--obs-ink`, `--obs-muted` | borders, text, secondary text | alias `--chrome-line/ink/muted` |
| `--obs-status-good/warn/bad/info/off` | status tones | alias existing `status-*` vars |
| `--obs-level-trace/debug/info/warn/error/fatal` | log levels | map to series/status vars; each level also has a letter glyph (T D I W E F) |
| `--obs-series-1..8` | chart series | alias Okabe-Ito `series-*` |
| `--obs-compare-dash` | dash pattern token (`4 3`) for previous-period lines | used by `ObsTimeSeriesChart` |
| `--obs-focus` | focus ring | accent with 2 px offset |
| `--obs-radius-card: 12px`, `--obs-radius-control: 8px`, `--obs-radius-chip: 6px` | shape | |
| `--obs-space-1..8` | 4-px scale (4,8,12,16,24,32,48,64) | |
| `--obs-motion-fast: 120ms`, `--obs-motion-base: 200ms`, `--obs-ease: cubic-bezier(.2,.0,.0,1)` | motion | zeroed under reduced motion |
| `--obs-row-h-comfortable: 40px`, `--obs-row-h-compact: 28px` | density | |
| `--obs-mono` font stack | log text | uses the system monospace stack already in Tailwind `fontFamily.mono` |

Typography uses the existing `typeScale.ts` from CloudK8s shared (do not introduce new font families). Tailwind: no new config keys are required because Obs tokens are consumed through arbitrary-value utilities `bg-[rgb(var(--obs-surface-1))]`. If the team prefers named utilities, add an `obs` color group to `tailwind.config.js`; default decision: **no tailwind.config.js edit**.

## 8. Reuse map (shared primitives actually imported)

From `FE/components/CloudK8s/shared/`: `PageTemplate` (only for Settings/Agents list pages; Obs pages otherwise use `ObsLayout`), `DataTable` / `K8sDataTable` (small tables), `DetailDrawer`, `ConfirmDialog`, `EmptyState` (inside `ObsStateBlock`), `Surface`, `MetricStrip`, `MetricTile`, `status/{KpiCard,StatusDot,StatusChip,Delta,Trend,HowItWorks,StepIndicator,ConfidenceChip,TermTip}`, `StatusMark`, `typeScale`, `charts/{ChartCard,Sparkline,Bars,StackedArea,SeriesLegend,ChartDataTable,TimeStrip,RangeBar,chartTokens}`. From `FE/components/ui/`: `button`, `card`, `badge`, `input`, `Dialog`, `AlertDialog`, `Tabs`, `Tooltip`, `Switch`, `Slider`, `RadioGroup`, `Disclosure`, `textarea`, `separator`, `label`. Contexts: `AuthContext`, `AccountContext`, `ThemeContext`. Utility: `apiClient`, `lib/utils.cn`. Importing from `CloudK8s/shared` creates a dependency on that folder's API; mitigated by importing only through a single re-export file `OBSFE/components/common/shared.ts` so a future move is a one-file change.

## 9. Edge cases

- **No accounts connected:** `ObsStateBlock variant="empty"` on every page with "Connect an AWS account" linking to the existing Accounts flow route (route constant `ACCOUNTS_ROUTE` in `constants/limits.ts`, verified at implementation), and Obs remains usable for already-enrolled manual servers.
- **Multi-account scope:** all pages accept several accounts; the scope bar shows `N accounts`; per-row account chip appears only when more than one account is selected.
- **Clock/timezone:** display zone preference applies everywhere; URL times always UTC ISO.
- **Impersonation (`X-Impersonate-User`)**: honoured by `apiClient`; live tail uses cookies, so an impersonating admin cannot open SSE on behalf of another user unless the backend supports it. Decision: SSE is disabled during impersonation with a message "Live tail isn't available while viewing as another user" (detected via the same flag `apiClient` reads; verify at implementation).
- **Very large result sets:** log table cap 10,000 loaded rows then "Refine your query" with export action.
- **Browser support:** evergreen only; `fetch` streaming (`ReadableStream`) and `ResizeObserver` assumed.

## Responses to design review (iteration 2)
- Finding 7 (fixed): `utils/sse.ts` and `useLogTail` use fetch-streaming so pre-stream 4xx bodies and `Last-Event-ID` work; dedup by row id; gap and partial states.
- Finding 13 (fixed): `iconMap.ts` edit explicitly includes `fa-heart-pulse → lucide:activity` among the shared-file edits (section 2).
- Finding 5 (fixed): permission mirror in `constants/permissions.ts` follows the 06 section 11 table; Connect actions need `obs.agents.manage` and `obs.storage.manage`, which `ROLE_XAMOPS` holds by default.
