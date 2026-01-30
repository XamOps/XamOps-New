import React, { useState, useEffect, useMemo, useRef } from 'react';
import Sidebar from '../components/Sidebar/Sidebar';
import {
  GcpDashboardData,
  GcpResourceType,
} from '../types/gcpDashboard';
import { useGcpDashboardData } from '../hooks/useGcpDashboardData';
import {
  mapInventoryToResources,
  formatCurrency,
  formatCompactNumber,
  formatTimeAgo,
} from '../api/gcpDashboardApi';
import '../styles/gcpDashboard.css';

// Highcharts loaded from CDN
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

  // Load selected accounts from session storage
  useEffect(() => {
    console.log('🔷 GCP Dashboard: Loading account IDs from sessionStorage');
    const storedAccountIds = sessionStorage.getItem('selectedAccountIds');
    const storedMultiAccountMode = sessionStorage.getItem('multiAccountMode');

    console.log('🔷 GCP Dashboard: storedAccountIds:', storedAccountIds);
    console.log('🔷 GCP Dashboard: storedMultiAccountMode:', storedMultiAccountMode);

    if (storedAccountIds) {
      try {
        const accountIds = JSON.parse(storedAccountIds);
        console.log('🔷 GCP Dashboard: Parsed account IDs:', accountIds);
        setSelectedAccountIds(accountIds);
        setMultiAccountMode(storedMultiAccountMode === 'true' && accountIds.length > 1);
      } catch (err) {
        console.error('❌ GCP Dashboard: Failed to parse selected account IDs:', err);
      }
    } else {
      console.log('⚠️ GCP Dashboard: No account IDs in sessionStorage');
    }
  }, []);

  // Extract and transform dashboard data
  const dashboardData = useMemo(() => {
    if (!data) return null;

    // Helper to transform single account data from backend DTO to frontend interface
    const transformSingle = (raw: any): GcpDashboardData => {
      // If already in correct frontend format, return as is
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
        regionStatus: (raw.regionStatus || []).map((region: any) => ({
          region: region.regionId || region.region,
          latitude: region.latitude,
          longitude: region.longitude,
          resourceCount: 1, // Default if not provided
          status: region.status?.toLowerCase() === 'active' ? 'active' : 'inactive',
        })),
        costHistory: {
          monthlyCosts: (raw.costHistory || []).map((item: any) => ({
            date: item.name,
            cost: item.amount,
            isAnomaly: item.isAnomaly,
          })),
          anomalies: [],
        },
        optimization: {
          summary: {
            totalRecommendations: (raw.rightsizingRecommendations?.length || 0) + (raw.wastedResources?.length || 0),
            totalPotentialSavings: raw.optimizationSummary?.totalPotentialSavings || 0,
            highConfidenceRecommendations: raw.rightsizingRecommendations?.length || 0,
          },
          anomalies: [],
          gceRecommendations: (raw.rightsizingRecommendations || []).map((rec: any) => ({
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
          topServices: (raw.billingSummary || []).map((item: any) => ({
            serviceName: item.name,
            cost: item.amount,
            percentage: raw.monthToDateSpend > 0 ? (item.amount / raw.monthToDateSpend) * 100 : 0,
            trend: 'stable',
          })),
          totalCost: raw.monthToDateSpend || 0,
          serviceCount: raw.billingSummary?.length || 0,
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
  const resources = useMemo(() => {
    if (!dashboardData || 'accounts' in dashboardData) return [];

    const singleData = dashboardData as GcpDashboardData;
    return mapInventoryToResources(singleData.resourceInventory);
  }, [dashboardData]);

  // Empty state
  if (selectedAccountIds.length === 0) {
    return (
      <div className="gcp-dashboard-container">
        <Sidebar />
        <div className="gcp-dashboard-content">
          <div className="gcp-empty-state">
            <div className="gcp-empty-state-icon">
              <svg width="120" height="120" viewBox="0 0 120 120" fill="none">
                <path
                  d="M60 10L90 30V70L60 90L30 70V30L60 10Z"
                  stroke="#4285F4"
                  strokeWidth="3"
                  fill="none"
                />
                <circle cx="60" cy="60" r="15" fill="#4285F4" opacity="0.2" />
              </svg>
            </div>
            <h2>No GCP Account Selected</h2>
            <p>Please select a GCP project from the sidebar to view your dashboard.</p>
          </div>
        </div>
      </div>
    );
  }

  // Loading state
  if (loading.global && !dashboardData) {
    return (
      <div className="gcp-dashboard-container">
        <Sidebar />
        <div className="gcp-dashboard-content">
          <div className="gcp-loading-skeleton">
            <div className="gcp-skeleton-header"></div>
            <div className="gcp-skeleton-kpi-grid">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="gcp-skeleton-kpi-card"></div>
              ))}
            </div>
            <div className="gcp-skeleton-chart"></div>
            <div className="gcp-skeleton-grid"></div>
          </div>
        </div>
      </div>
    );
  }

  // Error state
  if (error && !dashboardData) {
    return (
      <div className="gcp-dashboard-container">
        <Sidebar />
        <div className="gcp-dashboard-content">
          <div className="gcp-error-state">
            <div className="gcp-error-icon">⚠️</div>
            <h2>Failed to Load Dashboard</h2>
            <p>{error.message}</p>
            <button onClick={refresh} className="gcp-retry-button">
              Retry
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!dashboardData) return null;

  const singleData = 'accounts' in dashboardData ? null : (dashboardData as GcpDashboardData);

  return (
    <div className="gcp-dashboard-container">
      <Sidebar />
      <div className="gcp-dashboard-content">
        {/* Header */}
        <DashboardHeader
          accountName={singleData?.accountName || 'Multi-Account Dashboard'}
          projectId={singleData?.projectId || ''}
          lastUpdated={lastUpdated}
          onRefresh={refresh}
          isRefreshing={loading.global}
          multiAccountMode={multiAccountMode}
        />

        {/* Beta Notice */}
        <div className="gcp-beta-notice">
          <span className="gcp-beta-badge">BETA</span>
          <span className="gcp-beta-text">
            GCP Features Partially Available - Some features like Calculator, CloudSitter,
            GKE and Rightsizing Recommendations are under development.
          </span>
        </div>

        {/* KPI Section */}
        {widgetVisibility.kpi && dashboardData && (
          <KPISection
            kpi={
              'accounts' in dashboardData
                ? (dashboardData as any).kpi
                : (dashboardData as GcpDashboardData).kpi
            }
            loading={loading.kpi}
          />
        )}

        {/* Main Grid: Map & Inventory (3:2) */}
        <div className="gcp-dashboard-main-grid">
          {widgetVisibility.map && singleData && (
            <MapSection
              regionStatus={singleData.regionStatus || []}
              loading={loading.map}
            />
          )}

          {widgetVisibility.inventory && resources.length > 0 && (
            <InventorySection
              resources={resources}
              totalResources={totalResources}
              loading={loading.inventory}
            />
          )}
        </div>

        {/* Cost & Billing Grid (2:1) */}
        <div className="gcp-cost-billing-grid">
          {widgetVisibility.cost && singleData && singleData.costHistory && (
            <div className="glass-card gcp-cost-chart-container fade-in">
              <h3 className="gcp-section-title">
                <i className="fas fa-chart-bar text-primary"></i>
                Cost History (Last 6 Months)
              </h3>
              <div id="costHistoryChart" style={{ height: '320px' }}>
                <CostHistoryChart costHistory={singleData.costHistory} />
              </div>
            </div>
          )}

          {widgetVisibility.billing && singleData && singleData.billingSummary && (
            <div className="glass-card gcp-billing-summary-container fade-in">
              <h3 className="gcp-section-title">
                <i className="fas fa-receipt text-primary"></i>
                Billing Summary
              </h3>
              <BillingSummaryTable summary={singleData.billingSummary} />
            </div>
          )}
        </div>

        {/* Optimization Section */}
        {widgetVisibility.optimization && singleData && singleData.optimization && (
          <OptimizationSection
            optimization={singleData.optimization}
            loading={loading.optimization}
          />
        )}
      </div>
    </div>
  );
};

// Dashboard Header Component
const DashboardHeader: React.FC<{
  accountName: string;
  projectId: string;
  lastUpdated: string | null;
  onRefresh: () => void;
  isRefreshing: boolean;
  multiAccountMode: boolean;
}> = ({ accountName, projectId, lastUpdated, onRefresh, isRefreshing, multiAccountMode }) => {
  return (
    <div className="gcp-dashboard-header">
      <div className="gcp-header-left">
        <div className="gcp-header-icon">
          <img src="/icons/gcp/gcp-logo.svg" alt="GCP" />
        </div>
        <div className="gcp-header-info">
          <h1 className="gcp-header-title">
            {accountName}
            {multiAccountMode && <span className="gcp-multi-badge">Multi-Account</span>}
          </h1>
          <div className="gcp-header-status">
            <div className="pulse-dot"></div>
            <span className="gcp-header-subtitle">
              Live Dashboard • {lastUpdated ? `Updated ${formatTimeAgo(lastUpdated)}` : 'Connected'}
            </span>
          </div>
          {projectId && <p className="gcp-header-subtitle mt-1">Project ID: {projectId}</p>}
        </div>
      </div>
      <div className="gcp-header-right">
        <a href="/cloudshell" className="gcp-cloudshell-link">
          <i className="fas fa-terminal"></i>
          CloudShell
        </a>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className={`gcp-refresh-button ${isRefreshing ? 'gcp-refreshing' : ''}`}
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          {isRefreshing ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>
    </div>
  );
};

// KPI Section Component
const KPISection: React.FC<{
  kpi: any;
  loading: boolean;
}> = ({ kpi, loading }) => {
  const kpiCards = [
    {
      title: 'MTD Spend',
      value: kpi?.mtdSpend || 0,
      trend: kpi?.mtdTrend || -2.5,
      color: '#4285F4',
    },
    {
      title: 'Forecasted',
      value: kpi?.forecastedSpend || 0,
      trend: kpi?.forecastTrend || 1.2,
      color: '#34A853',
    },
    {
      title: 'Last Month',
      value: kpi?.lastMonthSpend || 0,
      trend: 0,
      color: '#FBBC04',
    },
    {
      title: 'Potential Savings',
      value: kpi?.potentialSavings || 0,
      trend: -5.4,
      color: '#EA4335',
    },
  ];

  return (
    <div className="gcp-kpi-grid">
      {kpiCards.map((card, index) => (
        <div
          key={index}
          className={`glass-card gcp-kpi-card fade-in`}
          style={{ borderTopColor: card.color, animationDelay: `${index * 0.1}s` }}
        >
          <div className="gcp-kpi-header">
            <span className="gcp-kpi-title">{card.title}</span>
          </div>
          <div className="gcp-kpi-value">
            {loading ? <div className="gcp-skeleton h-10 w-3/4 rounded"></div> : formatCurrency(card.value)}
          </div>
          {!loading && card.trend !== 0 && (
            <div className={`gcp-kpi-trend ${card.trend > 0 ? 'gcp-trend-up' : 'gcp-trend-down'}`}>
              <i className={`fas fa-caret-${card.trend > 0 ? 'up' : 'down'}`}></i>
              {Math.abs(card.trend)}% vs last period
            </div>
          )}
        </div>
      ))}
    </div>
  );
};

// Map Section Component
const MapSection: React.FC<{
  regionStatus: any[];
  loading: boolean;
}> = ({ regionStatus, loading }) => {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current || loading || !window.Highcharts || regionStatus.length === 0) return;

    const mapData = (regionStatus || []).map((region) => ({
      name: region.region,
      lat: region.latitude,
      lon: region.longitude,
      z: 10,
      color: region.status === 'active' ? '#4285F4' : '#CCCCCC',
    }));

    window.Highcharts.mapChart(chartRef.current, {
      chart: { backgroundColor: 'transparent', height: 420 },
      title: { text: null },
      mapNavigation: { enabled: true, buttonOptions: { verticalAlign: 'bottom' } },
      series: [{
        type: 'mapbubble',
        name: 'Regions',
        data: mapData,
        minSize: 8,
        maxSize: '10%',
        color: '#4285F4',
        tooltip: { pointFormat: '<b>{point.name}</b>' }
      }],
      credits: { enabled: false }
    });
  }, [regionStatus, loading]);

  return (
    <div className="glass-card gcp-map-section fade-in">
      <h3 className="gcp-section-title">
        <i className="fas fa-globe-americas text-primary"></i>
        Active Regions
      </h3>
      {loading ? (
        <div className="gcp-skeleton gcp-map-container"></div>
      ) : (
        <div ref={chartRef} className="gcp-map-container"></div>
      )}
    </div>
  );
};

// Inventory Section Component
const InventorySection: React.FC<{
  resources: GcpResourceType[];
  totalResources: number;
  loading: boolean;
}> = ({ resources, totalResources, loading }) => {
  return (
    <div className="glass-card gcp-inventory-section fade-in">
      <div className="gcp-section-header">
        <h3 className="gcp-section-title">
          <i className="fas fa-server text-primary"></i>
          Resource Inventory
        </h3>
        {!loading && <span className="gcp-resource-count">{totalResources} total</span>}
      </div>
      <div className="gcp-inventory-list">
        {loading ? (
          [1, 2, 3, 4, 5].map(i => (
            <div key={i} className="gcp-skeleton h-16 rounded-xl mb-3"></div>
          ))
        ) : (
          resources.map((resource, index) => (
            <div key={index} className="gcp-resource-card">
              <div className="gcp-resource-icon">
                <img src={`/icons/gcp/${resource.icon}`} alt="" />
              </div>
              <div className="flex-1">
                <p className="gcp-resource-name">{resource.name}</p>
                <p className="gcp-resource-type">{resource.type}</p>
              </div>
              <div className="gcp-resource-count">{formatCompactNumber(resource.count)}</div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

// Cost Chart Sub-component
const CostHistoryChart: React.FC<{ costHistory: any }> = ({ costHistory }) => {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current || !window.Highcharts || !costHistory) return;

    const monthlyCosts = costHistory.monthlyCosts || [];
    const categories = monthlyCosts.map((item: any) =>
      new Date(item.date).toLocaleDateString('en-US', { month: 'short' })
    );

    window.Highcharts.chart(chartRef.current, {
      chart: { type: 'areaspline', backgroundColor: 'transparent', height: 320 },
      title: { text: null },
      xAxis: { categories },
      yAxis: { title: { text: null }, labels: { format: '${value}' } },
      series: [{
        name: 'Cost',
        data: monthlyCosts.map((item: any) => item.cost),
        color: '#4285F4',
        fillColor: {
          linearGradient: { x1: 0, y1: 0, x2: 0, y2: 1 },
          stops: [[0, 'rgba(66, 133, 244, 0.2)'], [1, 'rgba(66, 133, 244, 0)']]
        }
      }],
      credits: { enabled: false },
      tooltip: { shared: true }
    });
  }, [costHistory]);

  return <div ref={chartRef}></div>;
};

// Billing Table Sub-component
const BillingSummaryTable: React.FC<{ summary: any }> = ({ summary }) => {
  const topServices = summary.topServices || [];
  return (
    <div className="gcp-table-container">
      <table className="gcp-table">
        <thead>
          <tr>
            <th>Service</th>
            <th className="text-right">Cost</th>
          </tr>
        </thead>
        <tbody>
          {topServices.slice(0, 5).map((service: any, i: number) => (
            <tr key={i}>
              <td>{service.serviceName}</td>
              <td className="text-right font-bold">{formatCurrency(service.cost)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// Optimization Section Component
const OptimizationSection: React.FC<{
  optimization: any;
  loading: boolean;
}> = ({ optimization, loading }) => {
  const allRecommendations = [
    ...(optimization?.gceRecommendations || []),
    ...(optimization?.cloudSqlRecommendations || []),
    ...(optimization?.gkeRecommendations || []),
  ];

  return (
    <div className="glass-card gcp-optimization-section fade-in">
      <h3 className="gcp-section-title">
        <i className="fas fa-lightbulb text-warning"></i>
        Optimization Hub
      </h3>

      <div className="gcp-optimization-summary-grid">
        <div className="glass-card gcp-opt-summary-card savings">
          <p className="text-sm font-semibold text-success uppercase tracking-wider">Potential Savings</p>
          <p className="text-3xl font-black text-success mt-2">
            {loading ? '...' : formatCurrency(optimization?.summary?.totalPotentialSavings || 0)}
          </p>
          <p className="text-xs text-success/70 mt-1">per month</p>
        </div>
        <div className="glass-card gcp-opt-summary-card alerts">
          <p className="text-sm font-semibold text-error uppercase tracking-wider">Recommendations</p>
          <p className="text-3xl font-black text-error mt-2">
            {loading ? '...' : optimization?.summary?.totalRecommendations || 0}
          </p>
          <p className="text-xs text-error/70 mt-1">active items</p>
        </div>
      </div>

      <div className="gcp-table-container">
        <table className="gcp-table">
          <thead>
            <tr>
              <th>Resource</th>
              <th>Type</th>
              <th>Recommendation</th>
              <th className="text-right">Savings</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              [1, 2, 3].map(i => (
                <tr key={i}><td colSpan={4} className="gcp-skeleton h-12 rounded mt-2"></td></tr>
              ))
            ) : (
              allRecommendations.slice(0, 5).map((rec: any, i: number) => (
                <tr key={i}>
                  <td className="font-medium">{rec.resourceName}</td>
                  <td><span className="gcp-beta-badge">{rec.resourceType}</span></td>
                  <td>{rec.reason}</td>
                  <td className="text-right text-success font-bold">{formatCurrency(rec.potentialSavings)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default GcpDashboard;
