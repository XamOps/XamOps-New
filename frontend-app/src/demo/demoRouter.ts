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
import * as identity from './xamopsIdentityMockData';
import * as billops from './billopsAdminMockData';
import * as xamsecure from './xamsecureMockData';
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

function routeXamops(url: string, method = 'GET', body?: string): Response | null {
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

  // XamOps optimization / live rightsizing
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

  // EKS cluster detail dashboard — must come before generic eks catch-all
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
  if (url.includes('/api/autospotting/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP AutoSpotting — uses /api/gcp/autospotting/ prefix
  if (url.includes('/api/gcp/autospotting/migs/'))
    return makeResponse(xamops.demoGcpAutoSpottingMigs);
  if (url.includes('/api/gcp/autospotting/history/'))
    return makeResponse(xamops.demoGcpAutoSpottingHistory);
  if (url.includes('/api/gcp/autospotting/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP CloudSitter
  if (url.includes('/api/gcp/cloudsitter/policies'))
    return makeResponse([]);
  if (url.includes('/api/gcp/cloudsitter/assignments'))
    return makeResponse([]);
  if (url.includes('/api/gcp/cloudsitter/'))
    return makeResponse(xamops.demoGenericEmpty);

  // GCP Disk Rightsizing
  if (url.includes('/api/gcp/disks/history/'))
    return makeResponse([]);
  if (url.includes('/api/gcp/disks/'))
    return makeResponse(xamops.demoGcpDisks);

  // Azure AutoSpotting — uses /api/azure/autospotting/ prefix
  if (url.includes('/api/azure/autospotting/vmss/all-regions/'))
    return makeResponse(xamops.demoAzureAutoSpottingVmss);
  if (url.includes('/api/azure/autospotting/history/'))
    return makeResponse(xamops.demoAzureAutoSpottingHistory);
  if (url.includes('/api/azure/autospotting/'))
    return makeResponse(xamops.demoGenericEmpty);

  // Azure CloudSitter
  if (url.includes('/api/azure/cloudsitter/policies'))
    return makeResponse([]);
  if (url.includes('/api/azure/cloudsitter/assignments'))
    return makeResponse([]);
  if (url.includes('/api/azure/cloudsitter/'))
    return makeResponse(xamops.demoGenericEmpty);

  // Azure Disk Rightsizing
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

  // CICD Pipelines — specific GitHub route before generic catch-all
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

  // DbOps — specific sub-paths so hooks render real data instead of empty state
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
  if (url.includes('/api/xamsecure/issues'))
    return makeResponse(xamsecure.demoIssues);
  if (url.includes('/api/xamsecure/attack-surface'))
    return makeResponse(xamsecure.demoAttackSurface);
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
    return makeResponse(xamsecure.demoInventoryCategories);
  if (url.includes('/api/xamsecure/inventory/network'))
    return makeResponse(xamsecure.demoNetworkTopology);
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
    return makeResponse(xamsecure.demoConfigDashboard);
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
  // Anything else under XamSecure — {} is safe, every remaining consumer is a
  // POST action whose response is ignored.
  if (url.includes('/api/xamsecure/'))
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
  if (url.includes('/api/observability/setup/'))
    return makeResponse('# Demo configuration — contact support to deploy collectors');
  if (url.includes('/api/observability/'))
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
