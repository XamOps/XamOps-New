import React, { useState, useEffect, useMemo } from 'react';
import {
  GcpDashboardData,
  GcpResourceType,
} from '../types/gcpDashboard';
import { useGcpDashboardData } from '../hooks/useGcpDashboardData';
import {
  mapInventoryToResources,
} from '../api/gcpDashboardApi';
import '../styles/gcpDashboard.css'; // Keeping for some specific styles if needed, but mostly relying on Tailwind now

// Import new components
import GcpDashboardHeader from '../components/GcpDashboard/GcpDashboardHeader';
import GcpKPISection from '../components/GcpDashboard/GcpKPISection';
import GcpMapSection from '../components/GcpDashboard/GcpMapSection';
import GcpInventorySection from '../components/GcpDashboard/GcpInventorySection';
import GcpCostSection from '../components/GcpDashboard/GcpCostSection';
import GcpOptimizationSection from '../components/GcpDashboard/GcpOptimizationSection';

// Highcharts loaded from CDN (still needed for map if GcpMapSection uses window.Highcharts)
declare global {
  interface Window {
    Highcharts: any;
  }
}

const GcpDashboard: React.FC = () => {
  // Get selected account IDs from session storage
  const [selectedAccountIds, setSelectedAccountIds] = useState<string[]>([]);
  const [multiAccountMode, setMultiAccountMode] = useState(false);

  // Dashboard data hook
  const { data, loading, error, refresh, lastUpdated } =
    useGcpDashboardData({
      accountIds: selectedAccountIds,
      multiAccountMode,
      autoRefresh: true,
      refreshInterval: 60,
    });

  // Widget visibility state
  const [widgetVisibility] = useState({
    kpi: true,
    map: true,
    inventory: true,
    cost: true,
    optimization: true,
    billing: true,
  });

  // Notifications state
  const [notification, setNotification] = useState<{ message: string, type: 'info' | 'success' | 'warning' | 'error' } | null>(null);

  const showNotification = (message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info') => {
    setNotification({ message, type });
    setTimeout(() => setNotification(null), 4000);
  };

  // Load selected accounts from session storage
  useEffect(() => {
    const storedAccountIds = sessionStorage.getItem('selectedAccountIds');
    const storedMultiAccountMode = sessionStorage.getItem('multiAccountMode');

    if (storedAccountIds) {
      try {
        const accountIds = JSON.parse(storedAccountIds);
        setSelectedAccountIds(accountIds);
        setMultiAccountMode(storedMultiAccountMode === 'true' && accountIds.length > 1);
      } catch (err) {
        console.error('❌ GCP Dashboard: Failed to parse selected account IDs:', err);
      }
    }
  }, []);

  // Extract and transform dashboard data
  const dashboardData = useMemo(() => {
    if (!data) return null;

    // Helper to transform single account data from backend DTO to frontend interface
    const transformSingle = (raw: any): GcpDashboardData => {
      // If already in correct frontend format, return as is (simple check)
      if (raw.kpi && raw.optimization && raw.billingSummary) return raw;

      return {
        accountId: raw.accountId || selectedAccountIds[0],
        accountName: raw.accountName || 'GCP Project',
        projectId: raw.projectId || selectedAccountIds[0],
        provider: 'GCP',
        lastUpdated: raw.lastUpdated || new Date().toISOString(),
        cacheStatus: 'fresh',
        kpi: {
          mtdSpend: raw.monthToDateSpend || 0,
          forecastedSpend: raw.forecastedSpend || 0,
          lastMonthSpend: raw.lastMonthSpend || 0,
          potentialSavings: raw.optimizationSummary?.totalPotentialSavings || 0,
        },
        resourceInventory: {
          gceInstances: raw.resourceInventory?.ec2 || 0,
          cloudStorageBuckets: raw.resourceInventory?.s3Buckets || 0,
          cloudSqlInstances: raw.resourceInventory?.rdsInstances || 0,
          gkeClusters: raw.resourceInventory?.kubernetes || 0,
          vpcNetworks: raw.resourceInventory?.vpc || 0,
          cloudDnsZones: raw.resourceInventory?.route53Zones || 0,
          loadBalancers: raw.resourceInventory?.loadBalancers || 0,
          cloudNatGateways: raw.resourceInventory?.cloudNatRouters || 0,
          artifactRepositories: raw.resourceInventory?.artifactRepositories || 0,
          kmsKeys: raw.resourceInventory?.kmsKeys || 0,
          cloudFunctions: raw.resourceInventory?.cloudFunctions || 0,
          cloudBuildTriggers: raw.resourceInventory?.cloudBuildTriggers || 0,
          secrets: raw.resourceInventory?.secretManagerSecrets || 0,
          cloudArmorPolicies: raw.resourceInventory?.cloudArmorPolicies || 0,
        },
        regionStatus: (Array.isArray(raw.regionStatus) ? raw.regionStatus : []).map((region: any) => ({
          region: region.regionId || region.region,
          latitude: region.latitude,
          longitude: region.longitude,
          resourceCount: 1, // Default if not provided
          status: region.status?.toLowerCase() === 'active' ? 'active' : 'inactive',
        })),
        costHistory: {
          monthlyCosts: (() => {
            if (Array.isArray(raw.costHistory)) {
              return raw.costHistory.map((item: any) => ({
                date: item.name || item.date || '',
                cost: item.amount || item.cost || 0,
                isAnomaly: !!item.isAnomaly,
              }));
            } else if (raw.costHistory && typeof raw.costHistory === 'object') {
              // Handle parallel arrays format if backend sends it { labels: [], costs: [] }
              const labels = raw.costHistory.labels || [];
              const costs = raw.costHistory.costs || [];
              const anomalies = raw.costHistory.anomalies || [];
              return labels.map((label: string, index: number) => ({
                date: label,
                cost: costs[index] || 0,
                isAnomaly: (Array.isArray(anomalies) ? anomalies.includes(index) : !!anomalies[index]),
              }));
            }
            return [];
          })(),
          anomalies: [],
        },
        optimization: {
          summary: {
            totalRecommendations: (raw.rightsizingRecommendations?.length || 0) + (raw.wastedResources?.length || 0),
            totalPotentialSavings: raw.optimizationSummary?.totalPotentialSavings || 0,
            highConfidenceRecommendations: raw.rightsizingRecommendations?.length || 0,
          },
          anomalies: [],
          gceRecommendations: (Array.isArray(raw.rightsizingRecommendations) ? raw.rightsizingRecommendations : []).map((rec: any) => ({
            resourceId: rec.resourceId,
            resourceName: rec.resourceId,
            resourceType: rec.service || 'Compute Engine',
            currentSize: rec.currentType,
            recommendedSize: rec.recommendedType,
            currentCost: rec.currentMonthlyCost,
            potentialSavings: rec.estimatedMonthlySavings,
            savingsPercentage: rec.currentMonthlyCost > 0 ? (rec.estimatedMonthlySavings / rec.currentMonthlyCost) * 100 : 0,
            zone: 'global',
            reason: rec.recommendationReason,
            confidence: 'high',
          })),
          cloudSqlRecommendations: [],
          gkeRecommendations: [],
        },
        billingSummary: {
          topServices: (Array.isArray(raw.billingSummary) ? raw.billingSummary : []).map((item: any) => ({
            serviceName: item.name,
            cost: item.amount,
            percentage: raw.monthToDateSpend > 0 ? (item.amount / raw.monthToDateSpend) * 100 : 0,
            trend: 'stable',
          })),
          totalCost: raw.monthToDateSpend || 0,
          serviceCount: (Array.isArray(raw.billingSummary) ? raw.billingSummary.length : 0),
        },
      };
    };

    if ('accounts' in data) {
      return {
        ...data,
        accounts: data.accounts.map(transformSingle),
        kpi: {
          mtdSpend: data.aggregated.totalMtdSpend,
          forecastedSpend: data.aggregated.totalForecastedSpend,
          lastMonthSpend: data.aggregated.totalLastMonthSpend,
          potentialSavings: data.aggregated.totalPotentialSavings,
        },
      } as any;
    }

    return transformSingle(data);
  }, [data, selectedAccountIds]);

  // Calculate total resources
  const totalResources = useMemo(() => {
    if (!dashboardData) return 0;

    if ('aggregated' in dashboardData) {
      return (dashboardData as any).aggregated.totalResourceCount;
    }

    const inventory = (dashboardData as GcpDashboardData).resourceInventory;
    return Object.values(inventory).reduce((sum, count) => (sum as number) + (count as number), 0) as number;
  }, [dashboardData]);

  // Get resource list
  const resources: GcpResourceType[] = useMemo(() => {
    if (!dashboardData || 'accounts' in dashboardData) return [];

    const singleData = dashboardData as GcpDashboardData;
    return mapInventoryToResources(singleData.resourceInventory);
  }, [dashboardData]);

  // Handle empty state
  if (selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 glass-card max-w-lg w-full">
          <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <img src="/icons/gcp-icon.png" alt="GCP" className="w-10 h-10 object-contain" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">No GCP Project Selected</h2>
          <p className="text-gray-500 mb-8">Please select a GCP project from the account selector to view your dashboard.</p>
        </div>
      </div>
    );
  }

  // Handle Error State
  if (error && !dashboardData) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 glass-card max-w-lg w-full border-red-100">
          <div className="w-20 h-20 bg-red-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <svg className="w-10 h-10 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Connection Error</h2>
          <p className="text-red-500 mb-6">{error.message}</p>
          <button
            onClick={refresh}
            className="px-6 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors shadow-lg shadow-red-500/30"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  const singleData = dashboardData ? ('accounts' in dashboardData ? null : (dashboardData as GcpDashboardData)) : null;

  return (
    <main className="flex-1 p-6 bg-transparent space-y-6">
      {/* Notification Toast */}
      {notification && (
        <div className={`fixed top-4 right-4 z-50 transform transition-all duration-300 ${notification ? 'translate-y-0 opacity-100' : '-translate-y-4 opacity-0'}`}>
          <div className={`flex items-center p-4 rounded-lg shadow-lg border ${notification.type === 'info' ? 'bg-blue-50 border-blue-100 text-blue-800' :
              notification.type === 'success' ? 'bg-green-50 border-green-100 text-green-800' :
                notification.type === 'warning' ? 'bg-yellow-50 border-yellow-100 text-yellow-800' :
                  'bg-red-50 border-red-100 text-red-800'
            }`}>
            {notification.message}
            <button onClick={() => setNotification(null)} className="ml-4 hover:opacity-75 font-bold">&times;</button>
          </div>
        </div>
      )}

      {/* Header */}
      <GcpDashboardHeader
        accountName={singleData?.accountName || 'Multi-Account Dashboard'}
        projectId={singleData?.projectId || ''}
        lastUpdated={lastUpdated}
        onRefresh={refresh}
        isRefreshing={loading.global}
        multiAccountMode={multiAccountMode}
      />

      {/* KPI Section */}
      {widgetVisibility.kpi && dashboardData && (
        <GcpKPISection
          kpiData={
            'accounts' in dashboardData
              ? (dashboardData as any).kpi
              : (dashboardData as GcpDashboardData).kpi
          }
          costHistory={singleData ? {
            costs: singleData.costHistory?.monthlyCosts?.map((c: any) => c.cost) || [],
            labels: singleData.costHistory?.monthlyCosts?.map((c: any) => c.date) || []
          } : undefined}
          isLoading={loading.kpi}
        />
      )}

      {/* Main Grid: Map & Inventory (3:2) */}
      <div className="grid gap-6 lg:grid-cols-5 fade-in">
        {widgetVisibility.map && (
          <GcpMapSection
            regionStatus={singleData?.regionStatus || []}
            isLoading={loading.map}
            onRegionClick={(name) => showNotification(`Dashboard filtered by: ${name}`, 'info')}
          />
        )}

        {widgetVisibility.inventory && (
          <GcpInventorySection
            resources={resources}
            totalResources={totalResources}
            loading={loading.inventory}
            accountIds={selectedAccountIds}
          />
        )}
      </div>

      {/* Cost & Billing Grid */}
      <div className="grid gap-6 lg:grid-cols-3 fade-in">
        {(widgetVisibility.cost || widgetVisibility.billing) && singleData && (
          <GcpCostSection
            costHistory={singleData.costHistory}
            billingSummary={singleData.billingSummary}
            isLoading={loading.cost}
          />
        )}
      </div>

      {/* Optimization Section */}
      {widgetVisibility.optimization && singleData && singleData.optimization && (
        <GcpOptimizationSection
          optimization={singleData.optimization}
          isLoading={loading.optimization}
        />
      )}
    </main>
  );
};

export default GcpDashboard;
