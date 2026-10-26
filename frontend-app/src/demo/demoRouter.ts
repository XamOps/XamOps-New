/**
 * Demo Router
 *
 * Maps incoming API URLs to static mock data based on the current demo role.
 * Returns a real Response object so existing code (response.json(), response.ok, etc.)
 * continues to work without modification.
 *
 * URL matching order matters — more specific patterns must come before wildcards.
 * Auth endpoints (/api/auth/, /logout, /api/[role]/profile) are NEVER intercepted so
 * real login / profile / session management always works.
 */

import { getDemoRole } from './demoConfig';
import { buildDemoComplianceReport } from './complianceReportDemo';
import * as xamops from './xamopsMockData';
import * as xamops_media from './mediaOpsMockData';
import * as k8sAutomation from './k8sAutomationMockData';
import * as k8sSeries from './k8sAutomationSeriesMockData';
import * as identity from './xamopsIdentityMockData';
import * as billops from './billopsAdminMockData';
import * as xamsecure from './xamsecureMockData';
import * as xamlens from './xamlensMockData';
import type { DspmSurface } from '../hooks/useDspmData';
import * as soc2 from './soc2MockData';
import * as war from './warMockData';

/** Reads a query-string value off a URL that may be relative. */
function param(url: string, name: string): string {
  const q = url.indexOf('?');
  if (q === -1) return '';
  return new URLSearchParams(url.slice(q + 1)).get(name) ?? '';
}

function makeNotFound(): Response {
  return new Response('null', { status: 404, headers: { 'Content-Type': 'application/json' } });
}

// Endpoints that must always reach the real backend (auth & identity)
const PASSTHROUGH_PATTERNS = [
  /\/api\/auth\//,
  /\/logout/,
  /\/api\/xamops\/user\/profile/,
  /\/api\/billops\/profile/,
  /\/api\/currency/,
  /\/api\/xamops\/superadmin\//,
];

function makeResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const AUTOSPOTTING_ADMIN_PREFIX = '/api/xamops/autospotting-admin/';

/**
 * AutoSpotting admin reads, answered the same way for both demo roles. None may reach the backend:
 * the BillOps demo login is a real ROLE_BILLOPS_ADMIN user, so a pass-through would show every
 * AutoSpotting customer's real name and savings. Lists are empty and totals 0, which both pages
 * render as "no data"; any other path under the prefix is a 404.
 */
function routeAutoSpottingAdmin(url: string): Response {
  if (url.includes(`${AUTOSPOTTING_ADMIN_PREFIX}total-savings`))
    return makeResponse({ totalSavings: 0, legacyUnverifiedTotal: 0, activeSpotInstances: 0 });
  if (
    url.includes(`${AUTOSPOTTING_ADMIN_PREFIX}customer-savings`) ||
    url.includes(`${AUTOSPOTTING_ADMIN_PREFIX}recent-activity`) ||
    url.includes(`${AUTOSPOTTING_ADMIN_PREFIX}historical-savings`) ||
    url.includes(`${AUTOSPOTTING_ADMIN_PREFIX}account-breakdown`)
  )
    return makeResponse([]);
  return makeNotFound();
}

function routeXamops(url: string, method = 'GET', body?: string): Response | null {
  // Sidebar profile. No tab restriction, and the XamLens add-on enabled, so the demo shows XamLens
  // unlocked. The generic {} below would read as "not entitled" and put a Locked pill on it.
  if (url.includes('/api/xamops/team/profile'))
    return makeResponse({ allowedTabs: [], mattersDspmEnabled: true });

  // Accounts
  if (url.includes('/api/xamops/account-manager/accounts'))
    return makeResponse(xamops.demoAccounts);

  // Granular dashboard — must come before generic /dashboard catch-all
  if (url.includes('/api/xamops/dashboard/granular/kpi'))
    return makeResponse(xamops.demoGranularKPI);
  if (url.includes('/api/xamops/dashboard/granular/inventory'))
    return makeResponse(xamops.demoGranularInventory);
  if (url.includes('/api/xamops/dashboard/granular/map'))
    return makeResponse(xamops.demoGranularMap);
  if (url.includes('/api/xamops/dashboard/granular/cost'))
    return makeResponse(xamops.demoGranularCost);
  if (url.includes('/api/xamops/dashboard/granular/optimization'))
    return makeResponse(xamops.demoGranularOptimization);
  if (url.includes('/api/xamops/dashboard/granular'))
    return makeResponse(xamops.demoGenericEmpty);

  // Multi-account / data dashboard
  if (url.includes('/api/xamops/dashboard/data/multi-account'))
    return makeResponse(xamops.demoMultiAccountDashboard);
  if (url.includes('/api/xamops/dashboard/data'))
    return makeResponse(xamops.demoMultiAccountDashboard);
  if (url.includes('/api/xamops/dashboard/layout'))
    return makeResponse(xamops.demoDashboardLayout);

  // Unified dashboard
  if (url.includes('/api/xamops/dashboard/unified/summary'))
    return makeResponse(xamops.demoUnifiedSummary);
  if (url.includes('/api/xamops/dashboard/unified/aws'))
    return makeResponse(xamops.demoAwsData);
  if (url.includes('/api/xamops/dashboard/unified/gcp'))
    return makeResponse(xamops.demoGcpData);
  if (url.includes('/api/xamops/dashboard/unified/azure'))
    return makeResponse(xamops.demoAzureData);
  if (url.includes('/api/xamops/dashboard/unified/health'))
    return makeResponse(xamops.demoHealth);

  // Generic dashboard catch-all
  if (url.includes('/api/xamops/dashboard'))
    return makeResponse(xamops.demoGenericEmpty);

  // Tickets — ordered most-specific first
  if (url.includes('/api/xamops/tickets') && url.includes('/count'))
    return makeResponse(xamops.demoTicketCount);
  if (url.includes('/api/xamops/tickets') && url.includes('/category/'))
    return makeResponse(xamops.demoTickets);
  // Sub-paths on a specific ticket (/replies, /close, /email, /assign) — return success
  if (url.includes('/api/xamops/tickets/replies/'))
    return makeResponse([]);
  if (url.includes('/api/xamops/tickets/') && (url.includes('/replies') || url.includes('/close') || url.includes('/email') || url.includes('/assign')))
    return makeResponse({ success: true, message: 'Demo mode' });
  // Single ticket by numeric ID — /api/xamops/tickets/3001
  if (url.includes('/api/xamops/tickets/'))
    return makeResponse(xamops.demoTicketDetail);
  // Generic paginated list
  if (url.includes('/api/xamops/tickets'))
    return makeResponse({ content: xamops.demoTickets, totalElements: xamops.demoTickets.length, totalPages: 1 });

  // CUR insights — Cost page sections that read the Cost and Usage Report. No Cost
  // Explorer fallback, so without a mock the request falls through to the real backend,
  // gets a 409 (the demo account has no CUR), and each section hides itself. Specific
  // paths only; there is no cur-insights catch-all so any unmocked one still 409s.
  if (url.includes('/api/xamops/cur-insights/charges-by-service'))
    return makeResponse(xamops.demoChargesByService);
  // Before top-resources is not required — matching is url.includes and '/service-resources' does
  // not contain '/top-resources' — but it keeps the two scoped reads together.
  if (url.includes('/api/xamops/cur-insights/service-resources'))
    // The only CUR branch that reads the query string, because the service *is* the request. Uses
    // the router's own `param` helper rather than `new URL(...)`, which would make a router that is
    // otherwise a pure function of its argument depend on `location`.
    return makeResponse(xamops.demoServiceResources(param(url, 'service')));
  if (url.includes('/api/xamops/cur-insights/top-resources'))
    return makeResponse(xamops.demoTopResources);
  if (url.includes('/api/xamops/cur-insights/charge-types'))
    return makeResponse(xamops.demoChargeTypes);
  if (url.includes('/api/xamops/cur-insights/capabilities'))
    return makeResponse(xamops.demoReportCapabilities);
  if (url.includes('/api/xamops/cur-insights/data-transfer'))
    return makeResponse(xamops.demoDataTransferCosts);
  // The connectivity tab's two reads. Both read the query string, because the id set *is* the
  // request; `param` decodes, so ARNs with ':' and '/' survive. Neither pattern is a substring of
  // another CUR route, so the order within this block is free.
  if (url.includes('/api/xamops/cur-insights/resource-identities'))
    return makeResponse(xamops.demoResourceIdentities(param(url, 'ids')));
  if (url.includes('/api/xamops/cur-insights/resource-costs'))
    return makeResponse(xamops.demoResourceCosts(param(url, 'ids')));
  // The cost drawer's Change/Breakdown/Trend tabs. Reads the query string — the resource, its
  // scope and the month ARE the request — through the router's own `param` helper, so the router
  // stays a pure function of its argument (no `new URL`/`location`). `resource-change` shares no
  // substring with `resource-identities` or `resource-costs` ('change' ≠ 'identities'/'costs'),
  // so its position within this block is free. demoResourceChange(...) returns null for a blank
  // or unknown id; makeResponse(null) sends the body "null", which fails the fetcher's
  // isRecordWith(['lines','daily'],['costChange']) check and reads as "No detail available" — the
  // same empty state a real account with no CUR shows.
  if (url.includes('/api/xamops/cur-insights/resource-change'))
    return makeResponse(
      xamops.demoResourceChange(
        param(url, 'resourceId'),
        param(url, 'service') || undefined,
        param(url, 'region') || undefined,
        param(url, 'period') || undefined,
      ),
    );

  // Costs (with "s") — breakdown, historical trend, forecast
  if (url.includes('/api/xamops/costs/breakdown'))
    return makeResponse(xamops.demoCostBreakdown);
  if (url.includes('/api/xamops/costs/historical-trend'))
    return makeResponse(xamops.demoCostHistoricalTrend);
  if (url.includes('/api/xamops/costs'))
    return makeResponse(xamops.demoGenericEmpty);

  // Forecast
  if (url.includes('/api/xamops/forecast/cost'))
    return makeResponse(xamops.demoCostForecast);
  if (url.includes('/api/xamops/forecast'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP — specific sub-paths before the generic /api/xamops/gcp catch-all
  if (url.includes('/api/xamops/gcp/dashboard'))
    return makeResponse(xamops.demoGcpDashboard);
  if (url.includes('/api/xamops/gcp/finops/report'))
    return makeResponse(xamops.demoGcpFinOpsReport);
  if (url.includes('/api/xamops/gcp/finops/budgets'))
    return makeResponse([]);
  if (url.includes('/api/xamops/gcp/finops-schedules'))
    return makeResponse([]);
  if (url.includes('/api/xamops/gcp/forecast/cost'))
    return makeResponse(xamops.demoGcpCostForecast);
  if (url.includes('/api/xamops/gcp/costs/breakdown'))
    return makeResponse(xamops.demoGcpCostBreakdown);
  // The billing account's currency. Without this the request fell to the /api/xamops/gcp
  // catch-all, which answers {} — so useGcpCostData kept its 'USD' initial value and the whole
  // Cost Management page rendered the demo project's rupee figures with a dollar sign, while the
  // GCP dashboard beside it formatted the same numbers as ₹.
  if (url.includes('/api/xamops/gcp/costs/currency'))
    return makeResponse({ currency: 'INR' });
  if (url.includes('/api/xamops/gcp/optimization/waste-report'))
    return makeResponse(xamops.demoGcpWasteData);
  if (url.includes('/api/xamops/gcp/rightsizing/recommendations'))
    return makeResponse(xamops.demoGcpRightsizingData);
  // GCP Prowler compliance — must come before generic /gcp/security/findings
  if (url.includes('/api/xamops/gcp/security/prowler/status'))
    return makeResponse(xamops.demoGcpProwlerStatus);
  if (url.includes('/api/xamops/gcp/security/prowler/findings'))
    return makeResponse(xamops.demoGcpProwlerFindings);
  if (url.includes('/api/xamops/gcp/security/prowler/scan'))
    return makeResponse({ status: 'RUNNING', message: 'Scan initiated' });
  if (url.includes('/api/xamops/gcp/security/findings'))
    return makeResponse(xamops.demoGcpSecurityFindings);
  if (url.includes('/api/xamops/gcp/security/iam-drift'))
    return makeResponse(xamops.demoGcpIamDrift);
  if (url.includes('/api/xamops/gcp/security/container-vulnerabilities'))
    return makeResponse(xamops.demoGcpContainerVulns);
  // summary before insights (both contain 'insights')
  if (url.includes('/api/xamops/gcp/performance-insights/summary'))
    return makeResponse(xamops.demoGcpPerformanceSummary);
  if (url.includes('/api/xamops/gcp/performance-insights/insights'))
    return makeResponse(xamops.demoGcpPerformanceInsights);
  if (url.includes('/api/xamops/gcp/performance-insights/'))
    return makeResponse({ success: true });
  if (url.includes('/api/xamops/gcp/cloudguard/alerts'))
    return makeResponse(xamops.demoGcpAlerts);
  if (url.includes('/api/xamops/gcp/reservations/cuds'))
    return makeResponse(xamops.demoGcpCudRecommendations);
  if (url.includes('/api/xamops/gcp/cloudmap/vpcs'))
    return makeResponse(xamops.demoGcpCloudMapVpcs);
  if (url.includes('/api/xamops/gcp/cloudmap/graph'))
    return makeResponse(xamops.demoGcpCloudMapGraph);
  if (url.includes('/api/xamops/gcp/cloudlist'))
    return makeResponse(xamops.demoGcpCloudListPage);
  if (url.includes('/api/xamops/gcp'))
    return makeResponse(xamops.demoGenericEmpty);

  // Azure — specific sub-paths before the /api/xamops/ catch-all
  if (url.includes('/api/xamops/azure/dashboard-data'))
    return makeResponse(xamops.demoAzureDashboard);
  if (url.includes('/api/xamops/azure/waste'))
    return makeResponse(xamops.demoAzureWasteData);
  if (url.includes('/api/xamops/azure/rightsizing/recommendations'))
    return makeResponse(xamops.demoAzureRightsizingData);
  if (url.includes('/api/xamops/azure/reservations'))
    return makeResponse(xamops.demoAzureReservations);
  // Azure Prowler compliance — before generic /azure/security
  if (url.includes('/api/xamops/azure/security/prowler/status'))
    return makeResponse(xamops.demoAzureProwlerStatus);
  if (url.includes('/api/xamops/azure/security/prowler/findings'))
    return makeResponse(xamops.demoAzureProwlerFindings);
  if (url.includes('/api/xamops/azure/security/prowler/scan'))
    return makeResponse({ status: 'RUNNING', message: 'Scan initiated' });
  if (url.includes('/api/xamops/azure/security'))
    return makeResponse(xamops.demoAzureSecurityData);
  if (url.includes('/api/xamops/azure/performance'))
    return makeResponse(xamops.demoAzurePerformanceData);
  if (url.includes('/api/xamops/azure/alerts'))
    return makeResponse(xamops.demoAzureAlertsData);
  if (url.includes('/api/xamops/azure/forecast/cost'))
    return makeResponse([]);
  if (url.includes('/api/xamops/azure/costs/service-trend'))
    return makeResponse([]);
  if (url.includes('/api/xamops/azure/'))
    return makeResponse(xamops.demoGenericEmpty);

  // FinOps sub-paths — specific paths before the generic /finops catch-all.
  // Actual sub-path names from useFinOpsData.ts fetchSection calls:
  if (url.includes('/api/xamops/finops/billing-summary'))
    return makeResponse(xamops.demoFinOpsBillingSummary);
  if (url.includes('/api/xamops/finops/cost-history'))
    return makeResponse(xamops.demoFinOpsCostTrend);
  if (url.includes('/api/xamops/finops/cost-by-region'))
    return makeResponse(xamops.demoFinOpsRegionalBreakdown);
  if (url.includes('/api/xamops/finops/tagging-compliance'))
    return makeResponse(xamops.demoFinOpsTaggingCompliance);
  if (url.includes('/api/xamops/finops/budgets')) {
    if (method === 'POST') {
      try {
        const submitted = body ? JSON.parse(body) : {};
        return makeResponse({
          budgetName: submitted.budgetName ?? 'Demo Budget',
          budgetLimit: submitted.budgetLimit ?? 1000,
          budgetUnit: submitted.budgetUnit ?? 'USD',
          actualSpend: 0,
          forecastedSpend: submitted.budgetLimit ?? 1000,
        });
      } catch {
        return makeResponse({ budgetName: 'Demo Budget', budgetLimit: 1000, budgetUnit: 'USD', actualSpend: 0, forecastedSpend: 1000 });
      }
    }
    // Read: the FinOps budgets table and the dashboard Cost Trend reference line both
    // read this, so an empty array silently removed the budget overlay from the widget.
    return makeResponse(xamops.demoFinOpsBudgets);
  }
  if (url.includes('/api/xamops/finops/rightsizing'))
    return makeResponse([]);
  if (url.includes('/api/xamops/finops/wasted-resources'))
    return makeResponse(xamops.demoWasteData);
  if (url.includes('/api/xamops/finops/cost-anomalies'))
    return makeResponse([]);
  if (url.includes('/api/xamops/finops'))
    return makeResponse(xamops.demoGenericEmpty);

  // FinOps schedules
  if (url.includes('/api/xamops/finops-schedules'))
    return makeResponse([]);

  // SonarQube — metrics before projects (more specific first)
  if (url.includes('/api/xamops/sonarqube') && url.includes('/metrics'))
    return makeResponse(xamops.demoSonarQubeMetrics);
  if (url.includes('/api/xamops/sonarqube'))
    return makeResponse(xamops.demoSonarQubeProjects);

  // Legacy /api/xamops/cost (singular)
  if (url.includes('/api/xamops/cost'))
    return makeResponse(xamops.demoGenericEmpty);

  // Waste — raw WastedResource[] array
  if (url.includes('/api/xamops/waste'))
    return makeResponse(xamops.demoWasteData);

  // ComplianceOps — the page reads a server-aggregated report rather than raw scanner
  // output, so the report is derived from the same OCSF fixtures the prowler routes serve.
  if (url.includes('/api/xamops/compliance/report')) {
    const accountId = param(url, 'accountId') || 'demo-aws-001';
    const provider = accountId.includes('gcp') ? 'GCP'
      : accountId.includes('azure') ? 'Azure' : 'AWS';
    const findings = provider === 'GCP' ? xamops.demoGcpProwlerFindings
      : provider === 'Azure' ? xamops.demoAzureProwlerFindings
        : xamops.demoAwsProwlerFindings;
    const regions = provider === 'AWS'
      ? ['ap-south-1', 'us-east-1', 'ap-southeast-1']
      : [];
    return makeResponse(
      buildDemoComplianceReport(accountId, provider, findings, regions),
    );
  }
  if (url.includes('/api/xamops/compliance/trend'))
    return makeResponse([]);
  // Governance writes are accepted but not persisted in demo mode — the report is
  // rebuilt from fixtures on every read, so there is nowhere for them to land.
  if (url.includes('/api/xamops/compliance/'))
    return makeResponse({ status: 'DEMO', message: 'Not persisted in demo mode.' });

  // WAR — must precede the /api/xamops/ catch-all, which would otherwise serve {} and
  // leave the page rendering an unassessable report with five empty tabs.
  if (url.includes('/api/xamops/war/report'))
    return makeResponse(war.demoWarReport(param(url, 'accountId')));
  if (url.includes('/api/xamops/war/scan'))
    return makeResponse(war.demoWarScanAck);

  // Security — every prowler sub-path before the generic security catch-all.
  // ComplianceOps reads prowler/findings (not /security/findings) and expects the
  // OCSF-ish array; the catch-all's object shape would leave the page empty.
  if (url.includes('/api/xamops/security/prowler/status'))
    return makeResponse(xamops.demoAwsProwlerStatus);
  if (url.includes('/api/xamops/security/prowler/findings'))
    return makeResponse(xamops.demoAwsProwlerFindings);
  if (url.includes('/api/xamops/security/prowler/scan'))
    return makeResponse({ status: 'STARTED', message: 'Scan initiated' });
  // Identity & Access. Must precede the catch-all below, which would otherwise answer this with
  // the findings array — a 200 of the wrong shape, which the tab reads as "no identities" and
  // then retries once with forceRefresh before giving up. Keyed on accountId so switching
  // accounts changes the estate, including across providers.
  if (url.includes('/api/xamops/security/identities'))
    return makeResponse(identity.demoIdentityInventory(param(url, 'accountId')));
  if (url.includes('/api/xamops/security'))
    return makeResponse(xamops.demoSecurityData);

  // Performance / metrics — summary must come before general insights
  if (url.includes('/api/xamops/metrics/insights/summary'))
    return makeResponse(xamops.demoPerformanceSummary);
  if (url.includes('/api/xamops/metrics/insights'))
    return makeResponse(xamops.demoPerformanceInsights);
  if (url.includes('/api/xamops/performance'))
    return makeResponse(xamops.demoPerformanceInsights);

  // Alerts / cloudguard
  if (url.includes('/api/xamops/cloudguard/alerts') || url.includes('/api/xamops/alerts'))
    return makeResponse(xamops.demoAlertsData);

  // AWS Rightsizing — raw array (hook does Array.isArray check)
  if (url.includes('/api/xamops/rightsizing'))
    return makeResponse(xamops.demoRightsizingData);

  // XamOps optimization / live rightsizing. The XamAi Recommendations tab's live endpoint must
  // precede the generic /optimization catch-all, which returned [] and left the tab on its
  // "No XamAi Recommendations" empty state.
  if (url.includes('/api/xamops/optimization/rightsizing/live'))
    return makeResponse(xamops.demoXamOpsRightsizing);
  if (url.includes('/api/xamops/optimization'))
    return makeResponse([]);

  // Reservations — specific patterns before generic catch-all
  if (url.includes('/api/xamops/reservations/sp-recommendations'))
    return makeResponse(xamops.demoSpRecommendations);
  if (url.includes('/api/xamops/reservations/recommendations'))
    return makeResponse(xamops.demoReservationRecommendations);
  if (url.includes('/api/xamops/reservations'))
    return makeResponse(xamops.demoReservationInventory);
  if (url.includes('/api/xamops/risp'))
    return makeResponse(xamops.demoGenericEmpty);

  // CloudSitter — policies and assignments must be arrays
  if (url.includes('/api/xamops/cloudsitter/policies'))
    return makeResponse([]);
  if (url.includes('/api/xamops/cloudsitter/assignments'))
    return makeResponse([]);
  if (url.includes('/api/xamops/cloudsitter'))
    return makeResponse(xamops.demoGenericEmpty);

  // CloudList — specific endpoints before generic catch-all
  if (url.includes('/api/xamops/cloudlist/services-paginated'))
    return makeResponse(xamops.demoCloudlistServices);
  if (url.includes('/api/xamops/cloudlist/all'))
    return makeResponse(xamops.demoCloudlistAll);
  if (url.includes('/api/xamops/cloudlist'))
    return makeResponse([]);

  // EKS cluster detail dashboard — must come before generic eks catch-all. staging-eks-cluster has
  // no agent, so it gets the backend's empty shell (with its clusterHealthScore of 100), which the
  // page must show as Not monitored.
  if (/\/api\/xamops\/eks\/staging-eks-cluster\/dashboard(\?|$)/.test(url))
    return makeResponse(xamops.demoEksEmptyShellDashboard('staging-eks-cluster'));
  if (url.includes('/api/xamops/eks/') && url.includes('/dashboard'))
    return makeResponse(xamops.demoEksDashboard);
  if (url.includes('/api/xamops/eks/') && url.includes('/sync'))
    return makeResponse({ status: 'SYNCING' });

  // GKE clusters — specific before generic gke catch-all
  if (url.includes('/api/xamops/gke/clusters'))
    return makeResponse(xamops.demoGkeClusters);
  // GKE cluster detail dashboard — must come before generic gke catch-all
  if (url.includes('/api/xamops/gke/') && url.includes('/dashboard'))
    return makeResponse(xamops.demoGkeDashboard);

  // AKS clusters — specific before generic aks catch-all
  if (url.includes('/api/xamops/aks/clusters'))
    return makeResponse(xamops.demoAksClusters);
  // AKS cluster detail dashboard — must come before generic aks catch-all
  if (url.includes('/api/xamops/aks/') && url.includes('/dashboard'))
    return makeResponse(xamops.demoGkeDashboard);

  // CloudMap — specific routes before generic catch-all
  if (url.includes('/api/xamops/cloudmap/vpcs'))
    return makeResponse(xamops.demoCloudMapVpcs);
  if (url.includes('/api/xamops/cloudmap/graph'))
    return makeResponse(xamops.demoCloudMapGraph);

  // K8s / infra generic catch-all
  if (
    url.includes('/api/xamops/cloudk8s') ||
    url.includes('/api/xamops/eks') ||
    url.includes('/api/xamops/gke') ||
    url.includes('/api/xamops/aks') ||
    url.includes('/api/xamops/cloudmap')
  )
    return makeResponse(xamops.demoGenericEmpty);

  // Notifications
  if (url.includes('/api/xamops/notifications'))
    return makeResponse([]);

  // AIOps — all specific sub-paths before safe 404 catch-all
  // Budget routes — budgets/summary before budgets
  if (url.includes('/api/xamops/aiops/budgets/summary'))
    return makeResponse(xamops.demoAIOPsBudgetSummary);
  if (url.includes('/api/xamops/aiops/budgets'))
    return makeResponse([]);
  // Routing sub-paths — stats before generic routing catch-all
  if (url.includes('/api/xamops/aiops/routing/stats'))
    return makeResponse(xamops.demoAIOpsRoutingStats);
  if (url.includes('/api/xamops/aiops/routing/'))
    return makeResponse([]);
  // Enhanced dashboard endpoints (called by loadAllDashboardData on mount)
  if (url.includes('/api/xamops/aiops/model-daily-analysis'))
    return makeResponse(xamops.demoAIOpsModelDailyAnalysis);
  if (url.includes('/api/xamops/aiops/insights'))
    return makeResponse(xamops.demoAIOpsInsightsSummary);
  if (url.includes('/api/xamops/aiops/team-analytics'))
    return makeResponse(xamops.demoAIOpsTeamAnalytics);
  if (url.includes('/api/xamops/aiops/request-logs'))
    return makeResponse(xamops.demoAIOpsRequestLogs);
  // Main dashboard endpoints (called by useAIOpsData on mount)
  if (url.includes('/api/xamops/aiops/providers'))
    return makeResponse(xamops.demoAIOpsProviders);
  if (url.includes('/api/xamops/aiops/kpis'))
    return makeResponse(xamops.demoAIOpsKpis);
  if (url.includes('/api/xamops/aiops/all-time-metrics'))
    return makeResponse(xamops.demoAIOpsAllTimeMetrics);
  if (url.includes('/api/xamops/aiops/cost-trend'))
    return makeResponse(xamops.demoAIOpsAdminCostTrend);
  if (url.includes('/api/xamops/aiops/provider-breakdown'))
    return makeResponse(xamops.demoAIOpsProviderBreakdown);
  if (url.includes('/api/xamops/aiops/model-usage'))
    return makeResponse(xamops.demoAIOpsModelUsage);
  if (url.includes('/api/xamops/aiops/anomalies'))
    return makeResponse(xamops.demoAIOpsAnomalies);
  // Additional AIOps tab endpoints
  if (url.includes('/api/xamops/aiops/filter-options'))
    return makeResponse(xamops.demoAIOpsFilterOptions);
  if (url.includes('/api/xamops/aiops/performance'))
    return makeResponse(xamops.demoAIOpsPerformanceMetrics);
  if (url.includes('/api/xamops/aiops/compute-metrics'))
    return makeResponse(xamops.demoAIOpsComputeMetrics);
  if (url.includes('/api/xamops/aiops/managed-inference'))
    return makeResponse(xamops.demoAIOpsInferenceOverview);
  if (url.includes('/api/xamops/aiops/api-keys'))
    return makeResponse(xamops.demoAIOpsApiKeys);
  if (url.includes('/api/xamops/aiops/agent-breakdown'))
    return makeResponse([]);
  if (url.includes('/api/xamops/aiops/model-comparison'))
    return makeResponse([]);
  if (url.includes('/api/xamops/aiops/token-heatmap'))
    return makeResponse([]);
  // Generic AIOps catch-all — 404 is safe (hooks use try/catch and initialize to null/[])
  if (url.includes('/api/xamops/aiops/'))
    return makeNotFound();

  // CloudK8s capabilities, per account — must come before the /api/xamops/k8s/ catch-all below,
  // which would otherwise answer it with the cluster list.
  if (/\/api\/xamops\/k8s\/capabilities(\?|$)/.test(url)) {
    const capabilities = k8sAutomation.demoCapabilities(param(url, 'accountId'));
    return capabilities ? makeResponse(capabilities) : makeResponse(k8sAutomation.DEMO_NO_ACCOUNT_ACCESS, 403);
  }

  // Vertical engine writes (endpoints 2 and 3), which the Workload autoscaler subtab can send:
  // refused by path, so a method-blind lookup can never answer a write with a read fixture (§16, P7).
  if (/\/api\/eks\/automation\/vertical\/[^/?]+\/workloads\/.+\/(policy|apply)(\?|$)/.test(url))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
  // The HPA apply (Automation › HPA), refused by path the same way.
  if (/\/api\/eks\/automation\/hpa\/[^/?]+\/[^/?]+\/[^/?]+\/apply(\?|$)/.test(url))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
  // The HPA list's one history read (Automation › HPA): GET-only on the server, each HPA answered as
  // its own read is; a method other than GET is refused, never answered with the read.
  {
    const histories = /\/api\/eks\/automation\/hpa\/([^/?]+)\/history(\?|$)/.exec(url);
    if (histories) {
      if (method.toUpperCase() !== 'GET') return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const answer = k8sSeries.demoHpaHistories(decodeURIComponent(histories[1]), param(url, 'hpas'), param(url, 'window'));
      return makeResponse(answer.body, answer.status);
    }
  }
  // One HPA's history (FEAT-004, Automation › HPA): GET-only on the server; a method other than GET is
  // refused, never answered with the read.
  {
    const history = /\/api\/eks\/automation\/hpa\/([^/?]+)\/([^/?]+)\/([^/?]+)\/history(\?|$)/.exec(url);
    if (history) {
      if (method.toUpperCase() !== 'GET') return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const answer = k8sSeries.demoHpaHistory(
        decodeURIComponent(history[1]),
        decodeURIComponent(history[2]),
        decodeURIComponent(history[3]),
        param(url, 'window'),
      );
      return makeResponse(answer.body, answer.status);
    }
  }
  // Anything else under the HPA prefix is not a read the demo answers: refused, never sent.
  if (url.includes('/api/eks/automation/hpa/'))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);

  // The usage time series and the sparklines (FEAT-004): GET-only, validated as the server does.
  {
    const sparklines = /\/api\/eks\/automation\/usage\/([^/?]+)\/sparklines(\?|$)/.exec(url);
    const usage = sparklines ? null : /\/api\/eks\/automation\/usage\/([^/?]+)(\?|$)/.exec(url);
    if (sparklines || usage) {
      if (method.toUpperCase() !== 'GET') return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const answer = sparklines
        ? k8sSeries.demoSparklines(decodeURIComponent(sparklines[1]), param(url, 'resource'), param(url, 'window'))
        : k8sSeries.demoUsage(decodeURIComponent(usage![1]), {
          scope: param(url, 'scope'),
          resource: param(url, 'resource'),
          window: param(url, 'window'),
          namespace: param(url, 'namespace'),
          kind: param(url, 'kind'),
          name: param(url, 'name'),
          from: param(url, 'from'),
          to: param(url, 'to'),
          step: param(url, 'step'),
        });
      return makeResponse(answer.body, answer.status);
    }
  }
  // Anything else under the usage prefix is not a path the server has: refused, never sent.
  if (url.includes('/api/eks/automation/usage'))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);

  // The activity summary (FEAT-005, Automation › Activity and Overview): GET-only, per IST day.
  {
    const summary = /\/api\/eks\/automation\/activity\/([^/?]+)\/summary(\?|$)/.exec(url);
    if (summary) {
      if (method.toUpperCase() !== 'GET') return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const answer = k8sAutomation.demoActivitySummary(decodeURIComponent(summary[1]), { days: param(url, 'days'), feature: param(url, 'feature') });
      return makeResponse(answer.body, answer.status);
    }
  }

  // The activity feed (FEAT-007, Automation › Activity): GET-only on the server, filtered and paged
  // here the way the server does it. A method other than GET is refused, never answered with the
  // read (apiFetch passes no method, so its calls read as GET).
  {
    const activity = /\/api\/eks\/automation\/activity\/([^/?]+)(\?|$)/.exec(url);
    if (activity) {
      if (method.toUpperCase() !== 'GET') return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const page = k8sAutomation.demoActivityPage(decodeURIComponent(activity[1]), {
        feature: param(url, 'feature'),
        result: param(url, 'result'),
        q: param(url, 'q'),
        limit: param(url, 'limit'),
        cursor: param(url, 'cursor'),
        from: param(url, 'from'),
        to: param(url, 'to'),
        namespace: param(url, 'namespace'),
        kind: param(url, 'kind'),
        name: param(url, 'name'),
      });
      return page ? makeResponse(page) : makeResponse(k8sAutomation.DEMO_CLUSTER_NOT_REGISTERED, 404);
    }
  }
  // Anything else under the feed's prefix is not a path the server has: refused, never sent.
  if (url.includes('/api/eks/automation/activity'))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);

  // Vertical engine, endpoint 1 (plan P7): an unrouted engine path would reach the real backend.
  {
    const vertical = /\/api\/eks\/automation\/vertical\/([^/?]+)(\?|$)/.exec(url);
    if (vertical) {
      const status = k8sAutomation.demoVerticalStatus(decodeURIComponent(vertical[1]));
      return status ? makeResponse(status) : makeResponse(k8sAutomation.DEMO_CLUSTER_NOT_REGISTERED, 404);
    }
  }

  // Node automation reads (Automation › Node autoscaler and Consolidation): the dashboard, the node
  // fleet report, the rebalance status and the look-only consolidation plan. Matched by exact path,
  // so no write under /api/eks/autoscaling/ can be answered with a read fixture.
  {
    const node = /\/api\/eks\/autoscaling\/([^/?]+)(?:\/(node-fleet|rebalance-status|consolidation-plan|spot-interruptions))?(\?|$)/.exec(url);
    if (node) {
      const cluster = decodeURIComponent(node[1]);
      // The consolidation plan and the spot interruption history are GET-only on the server
      // (read-only): a write is refused, never read.
      if ((node[2] === 'consolidation-plan' || node[2] === 'spot-interruptions') && method.toUpperCase() !== 'GET')
        return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);
      const days = Number(/[?&]days=(\d+)/.exec(url)?.[1] ?? 30);
      const body = node[2] === 'node-fleet'
        ? k8sAutomation.demoNodeFleet(cluster)
        : node[2] === 'rebalance-status'
          ? k8sAutomation.demoRebalanceStatus(cluster)
          : node[2] === 'consolidation-plan'
            ? k8sAutomation.demoConsolidationPlan(cluster)
            : node[2] === 'spot-interruptions'
              ? k8sAutomation.demoSpotInterruptions(cluster, days)
              : k8sAutomation.demoAutomationDashboard(cluster);
      return body ? makeResponse(body) : makeResponse(k8sAutomation.DEMO_CLUSTER_NOT_REGISTERED, 404);
    }
  }
  // Anything else under the prefix (the node automation writes) is refused, never sent (§16, P7).
  if (url.includes('/api/eks/autoscaling/'))
    return makeResponse(k8sAutomation.DEMO_WRITE_REFUSED, 403);

  // K8s clusters (CloudK8s page)
  if (url.includes('/api/xamops/k8s/'))
    return makeResponse(xamops.demoK8sClusters);

  // Labels — must return arrays so .map() calls work
  if (url.includes('/api/xamops/labels/resource-map'))
    return makeResponse({});
  if (url.includes('/api/xamops/labels/connected-resources'))
    return makeResponse([]);
  if (url.includes('/api/xamops/labels'))
    return makeResponse([]);

  // Ticket calls — must be listed explicitly: the {} fallback below is not a
  // valid payload for these, and CallProvider sits above the app's error
  // boundary, so a bad shape here blanks the whole page rather than one panel.
  // callingEnabled:false with no token keeps the demo off the ring socket and
  // greys out the call buttons instead of offering controls that cannot work.
  if (url.includes('/api/xamops/calls/ws-token'))
    return makeResponse({ token: '', ttlSeconds: 0, userId: 0, callingEnabled: false });
  if (url.includes('/api/xamops/calls/missed'))
    return makeResponse([]);
  if (url.includes('/api/xamops/calls/ticket/'))
    return makeResponse([]);
  // 204 = "not on a call", which is what reload recovery must see in a demo.
  // Answering {} here would look like a live session and send the UI hunting for
  // a token that does not exist.
  if (url.includes('/api/xamops/calls/active'))
    return new Response(null, { status: 204 });
  // Must be an array: the queue snapshot is iterated on socket connect, and the {}
  // fallback below would throw inside CallProvider, which sits above the error
  // boundary and would blank the page rather than degrade one panel.
  if (url.includes('/api/xamops/calls/queue'))
    return makeResponse([]);
  if (url.includes('/api/xamops/calls/') && url.includes('/invitable-users'))
    return makeResponse([]);
  if (url.includes('/api/xamops/calls'))
    return makeResponse({});

  // AutoSpotting admin reads: mocked, never passed through (see routeAutoSpottingAdmin). Must come
  // before the catch-all below, whose {} would break the pages' arrays.
  if (url.includes(AUTOSPOTTING_ADMIN_PREFIX))
    return routeAutoSpottingAdmin(url);

  // Any other /api/xamops/* — empty fallback
  if (url.includes('/api/xamops/'))
    return makeResponse(xamops.demoGenericEmpty);

  // EBS Rightsizing — uses /api/ebs/ (not /api/xamops/)
  if (url.includes('/api/ebs/recommendations'))
    return makeResponse(xamops.demoEbsVolumes.filter(v => v.recommendation !== 'NONE'));
  if (url.includes('/api/ebs/volumes'))
    return makeResponse(xamops.demoEbsVolumes);
  if (url.includes('/api/ebs/policies') || url.includes('/api/ebs/action-history') || url.includes('/api/ebs/pending-deletion'))
    return makeResponse([]);
  if (url.includes('/api/ebs/monitoring/'))
    return makeResponse(xamops.demoEbsMonitoringData);
  if (url.includes('/api/ebs/'))
    return makeResponse([]);

  // AutoSpotting — uses /api/autospotting/ (not /api/xamops/)
  if (url.includes('/api/autospotting/costs/history'))
    return makeResponse(xamops.demoAutoSpottingHistory);
  if (url.includes('/api/autospotting/costs'))
    return makeResponse(xamops.demoAutoSpottingCosts);
  if (url.includes('/api/autospotting/analytics/launches/'))
    return makeResponse(xamops.demoAutoSpottingLaunchAnalytics);
  if (url.includes('/api/autospotting/analytics/risp-coverage/'))
    return makeResponse(xamops.demoAutoSpottingRispCoverage);
  if (url.includes('/api/autospotting/events/'))
    return makeResponse(xamops.demoAutoSpottingActionsHistory);
  if (url.includes('/api/autospotting/feature-status'))
    return makeResponse({ aws: { conversionsEnabled: false }, gcp: { conversionsEnabled: false } });
  if (url.includes('/api/autospotting/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP AutoSpotting — uses /api/gcp/autospotting/ prefix
  if (url.includes('/api/gcp/autospotting/migs/'))
    return makeResponse(xamops.demoGcpAutoSpottingMigs);
  if (url.includes('/api/gcp/autospotting/history/'))
    return makeResponse(xamops.demoGcpAutoSpottingHistory);
  // Shared by the Launch Analytics and Actions History tabs. Both were empty because this fell to
  // the catch-all below, which answers {} — so `data.events || []` and `data.summary || null`
  // produced the "No events in this period" state on a MIG list that showed live conversions.
  if (url.includes('/api/gcp/autospotting/events/'))
    return makeResponse(xamops.demoGcpAutoSpottingEvents);
  if (url.includes('/api/gcp/autospotting/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP CloudSitter
  if (url.includes('/api/gcp/cloudsitter/policies'))
    return makeResponse(xamops.demoGcpCloudSitterPolicies);
  // An object map keyed `${name}|${zone}`, which is what the page indexes into — the empty array
  // here was the wrong shape as well as empty.
  if (url.includes('/api/gcp/cloudsitter/assignments'))
    return makeResponse(xamops.demoGcpCloudSitterAssignments);
  // The VM list, which had no route of its own: the catch-all's {} made `gcpVms.map` throw inside
  // the hook's try/catch, leaving the page on its "No Resources Found" state.
  if (url.includes('/api/gcp/cloudsitter/vms'))
    return makeResponse(xamops.demoGcpCloudSitterVms);
  if (url.includes('/api/gcp/cloudsitter/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP Disk Rightsizing
  if (url.includes('/api/gcp/disks/history/'))
    return makeResponse([]);
  if (url.includes('/api/gcp/disks/'))
    return makeResponse(xamops.demoGcpDisks);


  // Azure CloudSitter — the same three gaps GCP had, fixed the same way.
  if (url.includes('/api/azure/cloudsitter/policies'))
    return makeResponse(xamops.demoAzureCloudSitterPolicies);
  // Object map keyed `${name}|${resourceGroup}` (resourceGroup, not region — that is what the
  // Azure hook keys on). An array here left every row unmanaged even when populated.
  if (url.includes('/api/azure/cloudsitter/assignments'))
    return makeResponse(xamops.demoAzureCloudSitterAssignments);
  // The VM list had no route, so the catch-all's {} made `azureVms.map` throw inside the hook's
  // try/catch and the page stayed on "No Resources Found".
  if (url.includes('/api/azure/cloudsitter/vms'))
    return makeResponse(xamops.demoAzureCloudSitterVms);
  if (url.includes('/api/azure/cloudsitter/'))
    return makeResponse(xamops.demoGenericEmpty);

  // Azure Disk Rightsizing. One catch-all used to answer `[]` for every sub-path including
  // /volumes/, which is the array the page's table AND all four KPI cards derive from — hence
  // "No Disks Found" above ₹0.00 / 0 / 0. History first, since both contain '/api/azure/disks/'.
  if (url.includes('/api/azure/disks/history/'))
    return makeResponse(xamops.demoAzureDiskActions);
  if (url.includes('/api/azure/disks/volumes'))
    return makeResponse(xamops.demoAzureDisks);
  if (url.includes('/api/azure/disks/'))
    return makeResponse([]);

  // Azure CloudList
  if (url.includes('/api/azure/cloudlist/'))
    return makeResponse(xamops.demoAzureCloudListResources);

  // Azure FinOps (uses /api/azure/ prefix, not /api/xamops/)
  if (url.includes('/api/azure/finops-report'))
    return makeResponse(xamops.demoAzureFinOpsReport);
  if (url.includes('/api/azure/finops-schedules'))
    return makeResponse([]);
  if (url.includes('/api/azure/finops/budgets'))
    return makeResponse([]);

  // Flex Compute — uses /api/flexcompute/ (not /api/xamops/)
  if (url.includes('/api/flexcompute/linked-accounts'))
    return makeResponse(xamops.demoFlexComputeLinkedAccounts);
  // capacity list (dashboard table + KPI aggregation) — honor status & pagination query params
  if (url.includes('/api/flexcompute/capacity') && !url.includes('/capacity/')) {
    const qs = url.includes('?') ? url.substring(url.indexOf('?') + 1) : '';
    const params = new URLSearchParams(qs);
    const statuses = params.getAll('statuses');
    const page = parseInt(params.get('page') || '1', 10) || 1;
    const pageSize = parseInt(params.get('pageSize') || '10', 10) || 10;
    return makeResponse(xamops.buildDemoFlexComputeCapacityResponse(statuses, page, pageSize));
  }
  // Wizard / action endpoints — return a benign success in demo mode
  if (url.includes('/api/flexcompute/capacity/search') ||
      url.includes('/api/flexcompute/capacity/reserve') ||
      url.includes('/api/flexcompute/capacity/configure') ||
      url.includes('/api/flexcompute/capacity/purchase') ||
      url.includes('/api/flexcompute/capacity/extend') ||
      url.includes('/api/flexcompute/capacity/auto-renewal'))
    return makeResponse({ success: true, message: 'Demo mode' });
  // single capacity detail — /api/flexcompute/capacity/{cidrId} (GET/DELETE)
  if (url.includes('/api/flexcompute/capacity/')) {
    if (method === 'DELETE')
      return makeResponse({ success: true, message: 'Demo mode' });
    const cidrId = url.split('/api/flexcompute/capacity/')[1]?.split(/[?/]/)[0];
    const match = xamops.demoFlexComputeCapacities.find((c) => c.id === cidrId);
    return makeResponse(match || xamops.demoFlexComputeCapacities[0]);
  }
  if (url.includes('/api/flexcompute/'))
    return makeResponse([]);

  // CICD Pipelines.
  //
  // The run-detail routes MUST precede the bare `/api/cicd/github/runs` test below, which is a
  // prefix of all of them. It used to match first and hand back the demoGithubRuns ARRAY to
  // every sub-path, so the pipeline modal's Failure Analysis tab read `snippets` off an array
  // (undefined → `.map` threw → "Something went wrong"), the Diff tab did the same with
  // `commits.length`, and /stages rendered four run objects as nameless stages.
  {
    const run = url.match(/\/api\/cicd\/github\/runs\/[^/]+\/[^/?]+\/(\d+)\//);
    if (run) {
      const runId = Number(run[1]);
      if (url.includes('/failure-analysis'))
        return makeResponse(xamops.demoCicdFailureAnalysis(runId));
      if (url.includes('/diff-summary'))
        return makeResponse(xamops.demoCicdDiffSummary(runId));
      if (url.includes('/stages'))
        return makeResponse(xamops.demoCicdRunStages(runId));
      if (url.includes('/logs'))
        return makeResponse(xamops.demoCicdRunLog(runId));
    }
  }
  if (url.includes('/api/cicd/github/runs'))
    return makeResponse(xamops.demoGithubRuns);
  if (url.includes('/api/cicd/github/metrics'))
    return makeResponse(xamops.demoCicdGithubMetrics);
  if (url.includes('/api/cicd/jenkins/metrics'))
    return makeResponse(xamops.demoCicdJenkinsMetrics);
  if (url.includes('/api/cicd/'))
    return makeResponse([]);

  // DevOps-in-the-Box scripts
  if (url.includes('/api/dev_ops_script'))
    return makeResponse(xamops.demoDevOpsScripts);

  // XamOps Agent — status/config endpoints used by DbOps Query Stats tab
  if (url.includes('/api/v1/agent/install-info'))
    return makeResponse({ apiKey: 'demo-api-key-xammer' });
  if (url.includes('/api/v1/agent/db/config'))
    return makeResponse({ found: true, dbEngine: 'postgres', dbHost: 'rds-prod-001.us-east-1.rds.amazonaws.com', dbPort: '5432', dbName: 'analytics', dbUser: 'xamops_reader' });
  if (url.includes('/api/v1/agent/db/status'))
    return makeResponse({ registered: true, isActive: true, lastCheckin: new Date(Date.now() - 60_000).toISOString(), agentIp: '10.0.1.100', dataCounts: { 'db-query-stats': { itemCount: 127, collectedAt: new Date(Date.now() - 60_000).toISOString() } } });
  if (url.includes('/api/v1/agent/'))
    return makeResponse({ success: true });

  // DbOps — specific sub-paths so hooks render real data instead of empty state.
  //
  // The three `agent-*` routes below must stay ABOVE the `/timeseries` and `/metrics` tests.
  // Not for precedence — the hyphen in `agent-timeseries` means it never matched `/timeseries`
  // in the first place, which is exactly why the instance drawer's Performance tab was empty:
  // the request fell all the way through to the `[]` catch-all. Keeping them first makes that
  // distinction visible instead of leaving it to be rediscovered.
  if (url.includes('/api/dbops/') && url.includes('agent-timeseries'))
    return makeResponse(xamops.demoAgentTimeseries(Number(param(url, 'hours')) || 24));
  if (url.includes('/api/dbops/') && url.includes('agent-instance-metrics'))
    return makeResponse(xamops.demoAgentInstanceMetrics);
  if (url.includes('/api/dbops/') && url.includes('agent-table-sizes'))
    return makeResponse(xamops.demoAgentTableSizes);
  if (url.includes('/api/dbops/') && url.includes('/timeseries'))
    return makeResponse(xamops.demoDbInstanceTimeseries);
  if (url.includes('/api/dbops/') && url.includes('/summary'))
    return makeResponse(xamops.demoDbSavingsSummary);
  if (url.includes('/api/dbops/') && url.includes('/inventory'))
    return makeResponse(xamops.demoDbInstances);
  if (url.includes('/api/dbops/') && url.includes('/costs'))
    return makeResponse(xamops.demoDbCosts);
  if (url.includes('/api/dbops/') && url.includes('/idle'))
    return makeResponse(xamops.demoDbIdle);
  if (url.includes('/api/dbops/') && url.includes('/rightsizing'))
    return makeResponse(xamops.demoDbRightsizing);
  if (url.includes('/api/dbops/') && url.includes('/slow-queries'))
    return makeResponse(xamops.demoDbSlowQueries);
  if (url.includes('/api/dbops/') && url.includes('/agent-query-stats'))
    return makeResponse(xamops.demoAgentQueryStats);
  if (url.includes('/api/dbops/') && url.includes('/agent-vacuum-stats'))
    return makeResponse(xamops.demoAgentVacuumStats);
  if (url.includes('/api/dbops/') && url.includes('/agent-user-activity'))
    return makeResponse(xamops.demoAgentUserActivity);
  if (url.includes('/api/dbops/') && url.includes('/metrics'))
    return makeResponse({});
  if (url.includes('/api/dbops/'))
    return makeResponse([]);

  // SRE Investigations
  if (url.includes('/api/sre/notifications/count'))
    return makeResponse({ count: 0 });
  if (url.includes('/api/sre/'))
    return makeResponse([]);

  // XamSecure (Upwind) — one entry per tab. Sub-paths first: /vulnerabilities,
  // /inventory and /issues all have children that would otherwise be swallowed
  // by their own parent route.
  if (url.includes('/api/xamsecure/summary'))
    return makeResponse(xamsecure.demoXamSecureSummary);
  if (url.includes('/api/xamsecure/issues/detail'))
    return makeResponse(xamsecure.demoIssueDetail(param(url, 'check')));
  // These four read ?accountId off the URL and answer with that account's provider estate.
  // The real endpoints take accountId and nothing else — XamSecureService.resolveAccountIds maps
  // it to an AWS account / GCP project / Azure subscription and the repositories filter on it —
  // so a GCP project genuinely returns GCP assets. The demo used to serve one all-AWS estate to
  // every account, which is why selecting "GCP Analytics" showed EC2 instances and S3 buckets.
  if (url.includes('/api/xamsecure/issues'))
    return makeResponse(xamsecure.demoIssuesFor(url));
  if (url.includes('/api/xamsecure/attack-surface'))
    return makeResponse(xamsecure.demoAttackSurfaceFor(url));
  // Story detail BEFORE the story list: the detail URL contains the list path as a substring,
  // so matching the list first returned the list payload for every detail request. The loader
  // reads `.id` to decide whether a story was withheld for tenancy, the list has none, and so
  // every story opened as "Story not available".
  {
    const story = url.match(/\/api\/xamsecure\/stories\/([^/?]+)/);
    if (story) {
      const detail = xamsecure.demoStoryDetail(decodeURIComponent(story[1]));
      return makeResponse(detail ?? {});
    }
  }
  if (url.includes('/api/xamsecure/stories'))
    return makeResponse(xamsecure.demoThreatStories);
  if (url.includes('/api/xamsecure/threat-policies'))
    return makeResponse(xamsecure.demoThreatPolicies);
  // Archive/status POST — must precede the threat feed route
  if (url.includes('/api/xamsecure/threats/') && url.includes('/status'))
    return makeResponse({ success: true });
  // Paged tables read ?page/&size (inventory also &category) off the URL, so the
  // Prev/Next controls and the category rail actually move through the data.
  // Order matters: the grouped and overview routes are more specific than /threats.
  // Findings route is more specific than the scan list, so it must be matched first.
  if (url.includes('/api/xamsecure/code/scans/'))
    return makeResponse(xamsecure.demoCodeScanFindings(
      decodeURIComponent(url.split('/api/xamsecure/code/scans/')[1].split('?')[0])));
  if (url.includes('/api/xamsecure/code/scans'))
    return makeResponse(xamsecure.demoCodeScans);
  if (url.includes('/api/xamsecure/threats/groups'))
    return makeResponse(xamsecure.demoThreatGroups(url));
  if (url.includes('/api/xamsecure/threats/overview'))
    return makeResponse(xamsecure.demoThreatOverview);
  if (url.includes('/api/xamsecure/threats'))
    return makeResponse(xamsecure.demoThreatsPage(url));
  if (url.includes('/api/xamsecure/vulnerabilities/dashboard'))
    return makeResponse(xamsecure.demoVulnDashboard);
  if (url.includes('/api/xamsecure/vulnerabilities/groups'))
    return makeResponse(xamsecure.demoVulnGroupPage(url));
  if (url.includes('/api/xamsecure/vulnerabilities/by-cve'))
    return makeResponse(xamsecure.demoVulnByCvePage(url));
  if (url.includes('/api/xamsecure/vulnerabilities/cve/findings'))
    return makeResponse(xamsecure.demoCveFindings(param(url, 'cveId')));
  if (url.includes('/api/xamsecure/vulnerabilities'))
    return makeResponse(xamsecure.demoVulnerabilitiesPage(url));
  if (url.includes('/api/xamsecure/posture'))
    return makeResponse(xamsecure.demoPosturePage(url));
  if (url.includes('/api/xamsecure/inventory/categories'))
    return makeResponse(xamsecure.demoInventoryCategoriesFor(url));
  // Had no route of its own, so it fell through to the inventory LIST route below and answered a
  // paged envelope where the Inventory risk strip reads flat counts (assets/pii/pci/...).
  if (url.includes('/api/xamsecure/inventory/risk'))
    return makeResponse(xamsecure.demoInventoryRiskFor(url));
  if (url.includes('/api/xamsecure/inventory/network'))
    return makeResponse(xamsecure.demoNetworkTopologyFor(url));
  if (url.includes('/api/xamsecure/sensor-coverage'))
    return makeResponse(xamsecure.demoSensorCoverage);
  // Per-asset relationships, ahead of the inventory list route that would otherwise swallow it.
  if (url.includes('/api/xamsecure/inventory/assets/') && url.includes('/relationships'))
    return makeResponse(xamsecure.demoAssetRelationships(
      decodeURIComponent(url.split('/api/xamsecure/inventory/assets/')[1].split('/')[0])));
  if (url.includes('/api/xamsecure/inventory'))
    return makeResponse(xamsecure.demoInventoryPage(url));
  // Per-control breakdown, ahead of the framework list route.
  if (url.includes('/api/xamsecure/compliance/frameworks/') && url.includes('/controls'))
    return makeResponse(xamsecure.demoFrameworkControls(
      decodeURIComponent(url.split('/api/xamsecure/compliance/frameworks/')[1].split('/')[0])));
  if (url.includes('/api/xamsecure/compliance'))
    return makeResponse(xamsecure.demoCompliance);
  if (url.includes('/api/xamsecure/configurations/dashboard'))
    return makeResponse(xamsecure.demoConfigDashboardFor(url));
  // Had no route, so it took the `{}` catch-all below: every stat card read 0 and the table
  // showed "No identities discovered for the selected account." Derived from the same
  // provider-aware inventory and posture estates the Inventory and Configurations tabs use.
  if (url.includes('/api/xamsecure/identities'))
    return makeResponse(xamsecure.demoIdentitiesFor(url));
  if (url.includes('/api/xamsecure/api-security'))
    return makeResponse(xamsecure.demoApiSecurity);
  if (url.includes('/api/xamsecure/components'))
    return makeResponse(xamsecure.demoComponents);
  if (url.includes('/api/xamsecure/workflows'))
    return makeResponse(xamsecure.demoWorkflows);
  // Self-service onboarding actions must be matched BEFORE the /onboarding list route below,
  // which would otherwise swallow them and hand the caller an array where it expects an object.
  //
  // No real launch URL in demo mode: it would carry a live Upwind credential, and opening a
  // customer's CloudFormation console from a sales demo is not a desirable surprise.
  if (url.includes('/api/xamsecure/onboarding/aws/launch-url'))
    return makeResponse({
      launchUrl: 'https://example.invalid/demo-mode-no-real-stack',
      stackName: 'upwind-readonly-permissions-demo-aws-002',
      stackRegion: 'us-east-1',
      cloudAccountId: 'demo-aws-002',
      status: 'PENDING',
    });
  if (url.includes('/api/xamsecure/onboarding/aws/check'))
    return makeResponse({
      cloudAccountId: 'demo-aws-002',
      status: 'PENDING',
      reportingAssets: false,
      registered: false,
      scannerEnabled: null,
      verifiedAt: null,
      message: 'Waiting for the first scan. This usually takes a few minutes after the '
        + 'stack finishes.',
    });
  // Demo mode must not reach the real integration API — that DELETE would deregister a live
  // account from the shared Upwind org.
  if (url.includes('/api/xamsecure/onboarding/aws/disconnect'))
    return makeResponse({
      cloudAccountId: 'demo-aws-002',
      status: 'NOT_CONNECTED',
      deregistered: true,
      stackName: 'upwind-readonly-permissions-demo-aws-002',
      stackRegion: 'us-east-1',
      message: 'Removed from XamSecure. Now delete the CloudFormation stack in your AWS '
        + 'account to remove the IAM role and stored credential.',
    });
  if (url.includes('/api/xamsecure/onboarding'))
    return makeResponse(xamsecure.demoOnboarding);

  // The cross-domain resource panel, opened from the resource name in every XamSecure table.
  // Had no route, so it took the `{}` catch-all below, and the drawer — which builds its nav
  // from `detail.counts` — rendered an empty nav and "Nothing to show." On Inventory that read
  // as random: the row opens the asset panel (data already in hand), the asset NAME opens this
  // one, and only the row worked.
  if (url.includes('/api/xamsecure/resource'))
    return makeResponse(xamsecure.demoResourceDetail(url));

  // Anything else under XamSecure — {} is safe, every remaining consumer is a
  // POST action whose response is ignored.
  if (url.includes('/api/xamsecure/'))
    return makeResponse({});

  // XamLens (DSPM). The list and filter routes carry the surface; asset detail does not.
  {
    const surface = url.match(/\/api\/dspm\/(cloud|saas|endpoint)\/(assets|filters)/);
    if (surface) {
      const [, name, kind] = surface;
      return makeResponse(kind === 'assets'
        ? xamlens.demoDspmAssets(name as DspmSurface, url)
        : xamlens.demoDspmFilters(name as DspmSurface, url));
    }
    const asset = url.match(/\/api\/dspm\/assets\/([^/?]+)/);
    if (asset) {
      const detail = xamlens.demoDspmAsset(decodeURIComponent(asset[1]));
      return detail ? makeResponse(detail) : makeNotFound();
    }
  }
  if (url.includes('/api/dspm/status'))
    return makeResponse(xamlens.demoDspmStatus());
  // Summary and anything else: {} is safe, nothing on screen reads it yet. Never the real API, which
  // would read a customer's Matters project from a sales demo.
  if (url.includes('/api/dspm/'))
    return makeResponse({});

  // SOC 2 Program. useSoc2ProgramData loads all eleven in one Promise.all, so a
  // single gap would blank the Program page and every detail page under it.
  if (url.includes('/api/soc2/overview'))
    return makeResponse(soc2.demoSoc2Overview);
  if (url.includes('/api/soc2/frameworks'))
    return makeResponse(soc2.demoSoc2Frameworks);
  if (url.includes('/api/soc2/controls'))
    return makeResponse(soc2.demoSoc2Controls);
  // Publish/archive sub-paths first — they must not return the whole list
  if (url.includes('/api/soc2/policies/'))
    return makeResponse(soc2.demoSoc2Policies[0]);
  if (url.includes('/api/soc2/policies'))
    return makeResponse(soc2.demoSoc2Policies);
  if (url.includes('/api/soc2/tasks/'))
    return makeResponse(soc2.demoSoc2Tasks[0]);
  if (url.includes('/api/soc2/tasks'))
    return makeResponse(soc2.demoSoc2Tasks);
  if (url.includes('/api/soc2/personnel/'))
    return makeResponse(soc2.demoSoc2Personnel[0]);
  if (url.includes('/api/soc2/personnel'))
    return makeResponse(soc2.demoSoc2Personnel);
  if (url.includes('/api/soc2/vendors/'))
    return makeResponse(soc2.demoSoc2Vendors[0]);
  if (url.includes('/api/soc2/vendors'))
    return makeResponse(soc2.demoSoc2Vendors);
  if (url.includes('/api/soc2/risks/'))
    return makeResponse(soc2.demoSoc2Risks[0]);
  if (url.includes('/api/soc2/risks'))
    return makeResponse(soc2.demoSoc2Risks);
  if (url.includes('/api/soc2/cloud-tests'))
    return makeResponse(soc2.demoSoc2CloudTests);
  if (url.includes('/api/soc2/integrations'))
    return makeResponse(soc2.demoSoc2Integrations);
  if (url.includes('/api/soc2/documents'))
    return makeResponse(soc2.demoSoc2Documents);
  if (url.includes('/api/soc2/'))
    return makeResponse({});

  // Workspace Licenses (Google Workspace integration)
  if (url.includes('/api/workspace/licenses/summary'))
    return makeResponse(xamops.demoWorkspaceLicenseSummary);
  if (url.includes('/api/workspace/optimization'))
    return makeResponse(xamops.demoWorkspaceOptimization);
  if (url.includes('/api/workspace/users'))
    return makeResponse(xamops.demoWorkspaceUsers);
  if (url.includes('/api/workspace/status'))
    return makeResponse(xamops.demoWorkspaceStatus);
  if (url.includes('/api/workspace/'))
    return makeResponse({});

  // MarketOps — subscriptions listing before billing sub-paths
  if (url.includes('/api/marketops/subscriptions') && !url.includes('/billing'))
    return makeResponse(xamops.demoMarketOpsSubscriptions);
  if (url.includes('/api/marketops/'))
    return makeResponse({ entries: [], accountLevel: false });

  // Observability — specific paths before generic catch-all
  // histogram before logs/search (both contain /logs/)
  if (url.includes('/api/observability/logs/histogram'))
    return makeResponse(xamops.demoObservabilityHistogram);
  if (url.includes('/api/observability/logs/'))
    return makeResponse(xamops.demoObservabilityLogs);
  if (url.includes('/api/observability/config'))
    return makeResponse(xamops.demoObservabilityConfig);
  if (url.includes('/api/observability/overview'))
    return makeResponse(xamops.demoObservabilityOverview);
  if (url.includes('/api/observability/streams'))
    return makeResponse(xamops.demoObservabilityStreams);
  if (url.includes('/api/observability/alert-destinations'))
    return makeResponse(xamops.demoObservabilityDestinations);
  if (url.includes('/api/observability/alerts'))
    return makeResponse(xamops.demoObservabilityAlerts);
  if (url.includes('/api/observability/clusters'))
    return makeResponse(xamops.demoObservabilityClusters);
  if (url.includes('/api/observability/metrics/'))
    return makeResponse(xamops.demoObservabilityMetrics);
  // Traces. Three separate checks rather than one `/traces/` prefix, because the explorer reads
  // a different envelope off each (`data.traces`, the whole stats object, `data.spans`) and a
  // shared fixture would satisfy none of them. These are POSTs whose body carries the selected
  // account as `providerAccountId`, so the fixtures take `body` and vary host names and
  // downstream span names per cloud.
  if (url.includes('/api/observability/traces/search'))
    return makeResponse(xamops.demoObservabilityTraces(body));
  if (url.includes('/api/observability/traces/stats'))
    return makeResponse(xamops.demoObservabilityTraceStats(body));
  if (url.includes('/api/observability/traces/spans'))
    return makeResponse(xamops.demoObservabilityTraceSpans(body));
  // Connected Instances on the Overview tab — same missing-route story as traces.
  if (url.includes('/api/observability/agents'))
    return makeResponse(xamops.demoObservabilityAgents);
  if (url.includes('/api/observability/setup/'))
    return makeResponse('# Demo configuration — contact support to deploy collectors');
  if (url.includes('/api/observability/'))
    return makeResponse({});

  // MediaOps. There was no matcher here at all, not even a catch-all, so routeXamops returned
  // null, main.tsx fell through to the real fetch, and every call hit media-ops-service with no
  // session — the page showed "Could not connect to media-ops-service: 401" with empty tables.
  //
  // Order matters, most specific first: unread-count is a prefix-sibling of notifications,
  // storage/files of storage, and the per-job action suffixes of the bare /jobs list.
  if (url.includes('/api/media-ops/notifications/unread-count'))
    return makeResponse(xamops_media.demoMediaOpsUnreadCount());
  if (url.includes('/api/media-ops/notifications'))
    return makeResponse(xamops_media.demoMediaOpsNotifications);
  if (url.includes('/api/media-ops/overview'))
    return makeResponse(xamops_media.demoMediaOpsOverview());
  if (url.includes('/api/media-ops/storage/files'))
    return makeResponse(xamops_media.demoMediaOpsFiles);
  if (url.includes('/api/media-ops/storage/usage'))
    return makeResponse({ usedBytes: xamops_media.demoMediaOpsOverview().storageUsedBytes });
  if (url.includes('/api/media-ops/storage/upload'))
    return makeResponse(xamops_media.demoMediaOpsFiles[0]);
  if (url.includes('/api/media-ops/user-s3/configs/') && url.includes('/files'))
    return makeResponse(xamops_media.demoMediaOpsUserS3Files);
  if (url.includes('/api/media-ops/user-s3/configs/') && url.includes('/presign'))
    return makeResponse({ url: 'https://xammer-media-src.s3.amazonaws.com/campaigns/launch-keynote-4k.mov?X-Amz-Signature=demo' });
  if (url.includes('/api/media-ops/user-s3/configs'))
    return makeResponse(xamops_media.demoMediaOpsUserS3Configs);
  if (url.includes('/api/media-ops/streams'))
    return makeResponse(xamops_media.demoMediaOpsStreams);
  // Per-job mutations (retry/pause/resume/cancel/schedule) answer the job itself, which is what
  // the hook splices back into the list. Must precede the bare /jobs list route.
  if (url.includes('/api/media-ops/jobs/'))
    return makeResponse(xamops_media.demoMediaOpsJobs()[0]);
  if (url.includes('/api/media-ops/jobs'))
    return makeResponse(xamops_media.demoMediaOpsJobs());
  if (url.includes('/api/media-ops/'))
    return makeResponse({});

  // BillOps User Dashboard
  if (url.includes('/api/billops/dashboard/user'))
    return makeResponse(xamops.demoBillopsUserDashboard);

  // Billing — GCP billing before generic /billing (GCP URL contains '/billing/gcp/')
  if (url.includes('/api/billops/detailed-breakdown'))
    return makeResponse(xamops.demoBillingDetailedBreakdown);
  if (url.includes('/api/billops/billing/gcp/'))
    return makeResponse(xamops.demoGcpBillingDashboard);
  if (url.includes('/api/billops/billing'))
    return makeResponse(xamops.demoBillingData);

  // Invoices (uses /api/admin/invoices, not /api/billops/)
  if (url.includes('/api/admin/invoices'))
    return makeResponse(xamops.demoBillopsUserInvoices);

  // Credits — user credits by userId
  if (url.includes('/api/billops/credits/user/'))
    return makeResponse(xamops.demoBillopsUserCredits);
  if (url.includes('/api/billops/credits'))
    return makeResponse({ success: true });

  // Agreements — by account ID
  if (url.includes('/api/billops/agreements/'))
    return makeResponse(xamops.demoBillopsUserAgreements);

  // BillOps tickets viewed by a XamOps user (Account & Billing category)
  if (url.includes('/api/billops/tickets/category/'))
    return makeResponse(xamops.demoBillopsUserTickets);
  // Sub-paths on a specific ticket (/replies, /close, /rca)
  if (url.includes('/api/billops/tickets/') && (url.includes('/replies') || url.includes('/close') || url.includes('/rca') || url.includes('/assign') || url.includes('/email') || url.includes('/meeting')))
    return makeResponse({ success: true });
  // Single ticket detail by ID
  if (url.includes('/api/billops/tickets/'))
    return makeResponse(xamops.demoBillopsTicketDetail);
  // POST /api/billops/tickets (create new ticket)
  if (url.includes('/api/billops/tickets'))
    return makeResponse([]);
  if (url.includes('/api/billops/admins'))
    return makeResponse([]);
  if (url.includes('/api/billops/'))
    return makeResponse({});

  return null;
}

function routeBillopsAdmin(url: string): Response | null {
  // Team profile (needed by DashboardGuard routing)
  if (url.includes('/api/billops/team/profile'))
    return makeResponse(billops.demoBillopsTeamProfile);

  // Trial management — mutations before the list, because the list matcher is a prefix of both.
  // These match on the URL suffix rather than the method; apiFetch now forwards the method too,
  // but the suffix checks are what these rely on, so keep them. All three MUST stay above the final
  // `/api/billops/` catch-all, which answers {} and would make the list hook's sort/map throw.
  if (url.includes('/api/billops/admin/trials/') && url.includes('/extend'))
    return makeResponse(billops.demoTrialExtendResult);
  if (url.includes('/api/billops/admin/trials/') && url.includes('/convert'))
    return makeResponse(billops.demoTrialConvertResult);
  if (url.includes('/api/billops/admin/trials'))
    return makeResponse(billops.demoBillopsAdminTrials);

  // Admin dashboard
  if (url.includes('/api/billops/dashboard/admin/top-spending-refresh'))
    return makeResponse(billops.demoBillopsTopSpending);
  if (url.includes('/api/billops/dashboard/admin'))
    return makeResponse(billops.demoBillopsAdminDashboard);

  // Clients / spend — specific before generic
  if (url.includes('/api/billops/clients/spend'))
    return makeResponse(billops.demoBillopsClientsSpend);
  // Client accounts (raise-ticket modal step 2: /api/billops/clients/{id}/accounts)
  if (url.includes('/api/billops/clients/') && url.includes('/accounts'))
    return makeResponse(billops.demoBillopsClientAccounts);
  if (url.includes('/api/billops/clients') || url.includes('/api/billops/admin/clients'))
    return makeResponse(billops.demoBillopsClients);

  // Admin invoice detail / sub-operations — must come before the flat list route
  if (url.includes('/api/admin/invoices/'))
    return makeResponse(billops.demoBillopsAdminInvoiceDetail);
  // Admin invoices list (flat array, uses /api/admin/ prefix not /api/billops/)
  if (url.includes('/api/admin/invoices'))
    return makeResponse(billops.demoAdminInvoicesList);

  // Invoices (paginated, used by dashboard)
  if (url.includes('/api/billops/admin/invoices') || url.includes('/api/billops/invoices'))
    return makeResponse(billops.demoBillopsAdminInvoices);

  // Credits — admin flat array must come before generic credits catch-all
  if (url.includes('/api/billops/credits/admin/all'))
    return makeResponse(billops.demoAdminCreditRequests);
  if (url.includes('/api/billops/admin/credits') || url.includes('/api/billops/credits'))
    return makeResponse(billops.demoBillopsAdminCredits);

  // Ticket reply edit/delete — /api/billops/tickets/replies/{replyId}
  if (url.includes('/api/billops/tickets/replies/'))
    return makeResponse({ success: true });
  // Admin create ticket — POST /api/billops/tickets/admin-create
  if (url.includes('/api/billops/tickets/admin-create'))
    return makeResponse(billops.demoBillopsAdminTicketsList[0]);
  // Sub-actions on a specific ticket (replies POST, close, rca, assign, email, meeting)
  if (url.includes('/api/billops/tickets/') && (
    url.includes('/replies') || url.includes('/close') || url.includes('/rca') ||
    url.includes('/assign') || url.includes('/email') || url.includes('/meeting')
  ))
    return makeResponse({ success: true });
  // Category-filtered list (notifications hook) — must return array before single-ticket catch
  if (url.includes('/api/billops/tickets/category/'))
    return makeResponse(billops.demoBillopsAdminTicketsList);
  // Single ticket detail — GET /api/billops/tickets/{id}
  if (url.includes('/api/billops/tickets/'))
    return makeResponse(billops.demoBillopsAdminTicketDetail);
  // Ticket list — GET /api/billops/tickets
  if (url.includes('/api/billops/tickets'))
    return makeResponse(billops.demoBillopsAdminTicketsList);
  if (url.includes('/api/billops/admin/tickets'))
    return makeResponse(billops.demoBillopsAdminTickets);

  // Agreements — per-account flat array (useAgreements) before paginated admin list
  if (url.includes('/api/billops/agreements/account/'))
    return makeResponse(billops.demoBillopsAgreementsByAccount);
  if (url.includes('/api/billops/admin/agreements') || url.includes('/api/billops/agreements'))
    return makeResponse(billops.demoBillopsAdminAgreements);

  // Support dashboard — ordered most-specific first
  if (url.includes('/api/billops/support/chat-sessions'))
    return makeResponse([]);
  if (url.includes('/api/billops/support/chat/'))
    return makeResponse(billops.demoSupportChat);
  if (url.includes('/api/billops/support/clients/') && url.includes('/profiles'))
    return makeResponse(billops.demoSupportProfiles);
  if (url.includes('/api/billops/support/clients'))
    return makeResponse(billops.demoSupportClients);
  if (url.includes('/api/billops/support/accounts'))
    return makeResponse(billops.demoSupportAccounts);
  if (url.includes('/api/billops/support/inbox'))
    return makeResponse(billops.demoSupportInbox);
  if (url.includes('/api/billops/support/'))
    return makeResponse({});

  // BillOps user manager
  if (url.includes('/api/billops/users'))
    return makeResponse(billops.demoBillopsUsers);

  // Billing pages
  if (url.includes('/api/billops/detailed-breakdown'))
    return makeResponse(xamops.demoBillingDetailedBreakdown);
  if (url.includes('/api/billops/billing/gcp/'))
    return makeResponse(xamops.demoGcpBillingDashboard);
  if (url.includes('/api/billops/billing'))
    return makeResponse(xamops.demoBillingData);

  // Invoices download (returns empty blob stub)
  if (url.includes('/api/billops/invoices/download-preview'))
    return makeResponse({});

  // Messaging / integrations — must return arrays so .some()/.filter() work
  if (url.includes('/api/billops/messaging/config'))
    return makeResponse([]);
  if (url.includes('/api/billops/messaging/'))
    return makeResponse([]);

  // Notifications
  if (url.includes('/api/billops/notifications'))
    return makeResponse([]);

  // Client details + savings (/api/xamops/billops-admin/ prefix)
  if (url.includes('/api/xamops/billops-admin/clients') && url.includes('/savings'))
    return makeResponse(billops.demoClientSavings);
  if (url.includes('/api/xamops/billops-admin/clients'))
    return makeResponse(billops.demoClientDetails);
  // Marketplace historical billing — must return arrays so .filter() works
  if (url.includes('/api/xamops/billops-admin/marketplace/'))
    return makeResponse([]);
  if (url.includes('/api/xamops/billops-admin/'))
    return makeResponse({});

  // AutoSpotting card and its Full Report page. This demo login is a real BillOps admin, so these
  // reads must never reach the backend (see routeAutoSpottingAdmin).
  if (url.includes(AUTOSPOTTING_ADMIN_PREFIX))
    return routeAutoSpottingAdmin(url);

  // Any other /api/billops/* — empty fallback
  if (url.includes('/api/billops/'))
    return makeResponse(billops.demoGenericEmpty);

  return null;
}

/**
 * Resolves a URL to a mock Response, or returns null if the request should
 * pass through to the real backend.
 */
export function resolveDemoResponse(url: string, method = 'GET', body?: string): Response | null {
  // Always let auth/identity endpoints through
  for (const pattern of PASSTHROUGH_PATTERNS) {
    if (pattern.test(url)) return null;
  }

  const role = getDemoRole();
  if (!role) return null;

  if (role === 'ROLE_XAMOPS')        return routeXamops(url, method, body);
  if (role === 'ROLE_BILLOPS_ADMIN') return routeBillopsAdmin(url);

  return null;
}
