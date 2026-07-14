/**
 * Dashboard API Service
 *
 * Converted from: dashboard.html (API fetch functions)
 *
 * All API endpoints match the original dashboard.html exactly.
 * No changes to endpoints, methods, or request/response handling.
 */

import apiClient from '../utils/apiClient';
import type {
  MultiAccountDashboardData,
  DashboardAccount,
  GranularKPIResponse,
  GranularInventoryResponse,
  GranularMapResponse,
  GranularCostResponse,
  GranularOptimizationResponse,
  WidgetLayout,
} from '../types/liveDashboard';

/**
 * Transform the backend multi-account response into the DashboardAccount shape
 * the dashboard UI consumes.
 *
 * The backend aggregates all selected accounts into a single `selectedAccount`
 * (see DashboardDataService.aggregateDashboardData). This mirrors the mapping
 * done in fetchAllGranularData for single accounts so both paths produce an
 * identical shape for the dashboard components.
 */
export const transformMultiAccountResponse = (data: any): DashboardAccount => {
  // Aggregated account lives under `selectedAccount`. Fall back to the raw
  // payload just in case the backend shape changes.
  const acc = data?.selectedAccount || data?.aggregated || data || {};

  const optSummary = acc.optimizationSummary || {};
  const ec2Recommendations = acc.ec2Recommendations || [];
  const ebsRecommendations = acc.ebsRecommendations || [];
  const lambdaRecommendations = acc.lambdaRecommendations || [];

  const totalPotentialSavings =
    acc.savingsSummary?.totalPotentialSavings ??
    optSummary.totalPotentialSavings ??
    optSummary.potentialMonthlySavings ??
    0;

  const criticalAlerts =
    optSummary.criticalAlertsCount ?? optSummary.criticalAlerts ?? 0;

  return {
    accountId: acc.id || 'multi-account',
    accountName: acc.name || 'Multi-Account',
    provider: (data?.selectedProvider || 'AWS') as 'AWS' | 'GCP' | 'Azure',
    kpi: {
      mtdSpend: acc.monthToDateSpend || 0,
      forecastedSpend: acc.forecastedSpend || 0,
      lastMonthSpend: acc.lastMonthSpend || 0,
      potentialSavings: totalPotentialSavings,
    },
    resourceInventory: acc.resourceInventory,
    regionStatus: acc.regionStatus || [],
    costHistory: acc.costHistory,
    optimization: {
      summary: {
        totalRecommendations:
          ec2Recommendations.length +
          ebsRecommendations.length +
          lambdaRecommendations.length,
        totalPotentialSavings,
        criticalAlerts,
        ec2RecommendationCount: ec2Recommendations.length,
        ebsRecommendationCount: ebsRecommendations.length,
        lambdaRecommendationCount: lambdaRecommendations.length,
      },
      anomalies: acc.costAnomalies || [],
      ec2Recommendations,
      ebsRecommendations,
      lambdaRecommendations,
    },
    billingSummary: acc.billingSummary,
    lastUpdated: data?.lastUpdated || new Date().toISOString(),
  };
};

/**
 * Fetch multi-account dashboard data (aggregated / combined view).
 * Endpoint: GET /api/xamops/dashboard/data/multi-account
 *
 * The backend combines the selected accounts server-side and returns the result
 * under `selectedAccount`; we normalize it here so the caller receives a ready
 * to render DashboardAccount.
 */
export const fetchMultiAccountData = async (
  accountIds: string[],
  forceRefresh: boolean = false
): Promise<DashboardAccount> => {
  const url = `/api/xamops/dashboard/data/multi-account?accountIds=${accountIds.join(',')}&forceRefresh=${forceRefresh}`;
  const raw = await apiClient.get<MultiAccountDashboardData>(url);
  return transformMultiAccountResponse(raw);
};

/**
 * Fetch granular KPI data for single account
 * Endpoint: GET /api/xamops/dashboard/granular/kpi
 */
export const fetchGranularKPI = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GranularKPIResponse> => {
  const url = `/api/xamops/dashboard/granular/kpi?accountId=${accountId}&forceRefresh=${forceRefresh}`;
  console.log('🔍 [KPI] Fetching:', url);
  try {
    const result = await apiClient.get<GranularKPIResponse>(url);
    console.log('✅ [KPI] Success:', result);
    return result;
  } catch (error) {
    console.error('❌ [KPI] Error:', error);
    throw error;
  }
};

/**
 * Fetch granular inventory data for single account
 * Endpoint: GET /api/xamops/dashboard/granular/inventory
 */
export const fetchGranularInventory = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GranularInventoryResponse> => {
  const url = `/api/xamops/dashboard/granular/inventory?accountId=${accountId}&forceRefresh=${forceRefresh}`;
  console.log('🔍 [Inventory] Fetching:', url);
  try {
    const result = await apiClient.get<GranularInventoryResponse>(url);
    console.log('✅ [Inventory] Success:', result);
    return result;
  } catch (error) {
    console.error('❌ [Inventory] Error:', error);
    throw error;
  }
};

/**
 * Fetch granular map/region data for single account
 * Endpoint: GET /api/xamops/dashboard/granular/map
 */
export const fetchGranularMap = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GranularMapResponse> => {
  const url = `/api/xamops/dashboard/granular/map?accountId=${accountId}&forceRefresh=${forceRefresh}`;
  console.log('🔍 [Map] Fetching:', url);
  try {
    const result = await apiClient.get<GranularMapResponse>(url);
    console.log('✅ [Map] Success:', result);
    return result;
  } catch (error) {
    console.error('❌ [Map] Error:', error);
    throw error;
  }
};

/**
 * Fetch granular cost data for single account
 * Endpoint: GET /api/xamops/dashboard/granular/cost
 */
export const fetchGranularCost = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GranularCostResponse> => {
  const url = `/api/xamops/dashboard/granular/cost?accountId=${accountId}&forceRefresh=${forceRefresh}`;
  console.log('🔍 [Cost] Fetching:', url);
  try {
    const result = await apiClient.get<GranularCostResponse>(url);
    console.log('✅ [Cost] Success:', result);
    return result;
  } catch (error) {
    console.error('❌ [Cost] Error:', error);
    throw error;
  }
};

/**
 * Fetch granular optimization data for single account
 * Endpoint: GET /api/xamops/dashboard/granular/optimization
 */
export const fetchGranularOptimization = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GranularOptimizationResponse> => {
  const url = `/api/xamops/dashboard/granular/optimization?accountId=${accountId}&forceRefresh=${forceRefresh}`;
  console.log('🔍 [Optimization] Fetching:', url);
  try {
    const result = await apiClient.get<GranularOptimizationResponse>(url);
    console.log('✅ [Optimization] Success:', result);
    return result;
  } catch (error) {
    console.error('❌ [Optimization] Error:', error);
    throw error;
  }
};

/**
 * Fetch widget layout configuration
 * Endpoint: GET /api/xamops/dashboard/layout
 */
export const fetchLayout = async (): Promise<WidgetLayout> => {
  return apiClient.get<WidgetLayout>('/api/xamops/dashboard/layout');
};

/**
 * Save widget layout configuration
 * Endpoint: POST /api/xamops/dashboard/layout
 */
export const saveLayout = async (widgetOrder: string[]): Promise<void> => {
  await apiClient.post('/api/xamops/dashboard/layout', { widgetOrder });
};

/**
 * Fetch all granular data in parallel for single account
 * This mimics the original dashboard's parallel fetching strategy
 */
export const fetchAllGranularData = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<DashboardAccount> => {
  console.log('🚀 [fetchAllGranularData] Starting parallel fetch for account:', accountId, 'forceRefresh:', forceRefresh);

  try {
    console.log('📡 [fetchAllGranularData] Calling Promise.all with 5 API calls...');
    const [kpiRes, inventoryRes, mapRes, costRes, optimizationRes] = await Promise.all([
      fetchGranularKPI(accountId, forceRefresh),
      fetchGranularInventory(accountId, forceRefresh),
      fetchGranularMap(accountId, forceRefresh),
      fetchGranularCost(accountId, forceRefresh),
      fetchGranularOptimization(accountId, forceRefresh),
    ]);

    console.log('✅ [fetchAllGranularData] All API calls completed successfully');
    console.log('📊 [fetchAllGranularData] Raw responses:', {
      kpi: kpiRes,
      inventory: inventoryRes,
      map: mapRes,
      cost: costRes,
      optimization: optimizationRes,
    });

    // Backend wraps data in selectedAccount property
    const kpiData = (kpiRes as any).selectedAccount || kpiRes;
    const inventoryData = (inventoryRes as any).selectedAccount || inventoryRes;
    const mapData = (mapRes as any).selectedAccount || mapRes;
    const costData = (costRes as any).selectedAccount || costRes;
    const optimizationData = (optimizationRes as any).selectedAccount || optimizationRes;

    console.log('📊 [fetchAllGranularData] Extracted selectedAccount:', {
      kpi: kpiData,
      inventory: inventoryData,
      map: mapData,
      cost: costData,
      optimization: optimizationData,
    });

    console.log('📊 [fetchAllGranularData] Properties:', {
      'kpiData.kpi': kpiData.kpi,
      'inventoryData.resourceInventory': inventoryData.resourceInventory,
      'mapData.regionStatus': mapData.regionStatus,
      'costData.costHistory': costData.costHistory,
      'optimizationData.optimization': optimizationData.optimization,
      'optimizationData.optimizationSummary': optimizationData.optimizationSummary,
    });

    // Calculate KPI values from cost history (mimics original dashboard.html logic)
    const costHistory = costData.costHistory;
    const mtdSpend = costHistory?.costs?.length > 0 ? costHistory.costs[costHistory.costs.length - 1] : 0;
    const lastMonthSpend = costHistory?.costs?.length > 1 ? costHistory.costs[costHistory.costs.length - 2] : 0;
    const currentDay = new Date().getDate();
    const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate();
    const forecastedSpend = currentDay > 0 ? (mtdSpend / currentDay) * daysInMonth : 0;

    // Get potential savings from optimization summary (handle both property names)
    const potentialSavings = optimizationData.optimizationSummary?.potentialMonthlySavings ||
                              optimizationData.optimizationSummary?.totalPotentialSavings || 0;

    console.log('📊 [fetchAllGranularData] Calculated KPI:', {
      mtdSpend,
      forecastedSpend,
      lastMonthSpend,
      potentialSavings,
    });

    // Build final dashboard data
    // Handle optimization summary with proper property mapping
    const optimizationSummary = optimizationData.optimizationSummary || {};
    const totalPotentialSavings = optimizationSummary.potentialMonthlySavings ||
                                   optimizationSummary.totalPotentialSavings || 0;
    const criticalAlertsCount = optimizationSummary.criticalAlertsCount ||
                                 optimizationSummary.criticalAlerts || 0;

    // The backend OptimizationSummary DTO carries no recommendation counts —
    // derive them from the recommendation arrays in the same payload.
    const ec2Recommendations = optimizationData.ec2Recommendations || [];
    const ebsRecommendations = optimizationData.ebsRecommendations || [];
    const lambdaRecommendations = optimizationData.lambdaRecommendations || [];

    const dashboardData = {
      accountId: accountId,
      accountName: '', // Will be filled from account context
      provider: 'AWS' as const, // Will be filled from account context
      kpi: {
        mtdSpend,
        forecastedSpend,
        lastMonthSpend,
        potentialSavings: totalPotentialSavings,
      },
      resourceInventory: inventoryData.resourceInventory,
      regionStatus: mapData.regionStatus,
      costHistory: costData.costHistory,
      optimization: {
        summary: {
          totalRecommendations: ec2Recommendations.length + ebsRecommendations.length + lambdaRecommendations.length,
          totalPotentialSavings: totalPotentialSavings,
          criticalAlerts: criticalAlertsCount,
          ec2RecommendationCount: ec2Recommendations.length,
          ebsRecommendationCount: ebsRecommendations.length,
          lambdaRecommendationCount: lambdaRecommendations.length,
        },
        anomalies: optimizationData.costAnomalies || [],
        ec2Recommendations,
        ebsRecommendations,
        lambdaRecommendations,
      },
      billingSummary: costData.billingSummary,
      lastUpdated: (kpiRes as any).lastUpdated || new Date().toISOString(),
    };

    console.log('✅ [fetchAllGranularData] Final dashboard data:', dashboardData);
    return dashboardData;
  } catch (error) {
    console.error('❌ [fetchAllGranularData] Error during parallel fetch:', error);
    console.error('❌ [fetchAllGranularData] Error stack:', error instanceof Error ? error.stack : 'No stack');
    throw error;
  }
};
