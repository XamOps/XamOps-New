import React, { useState, useEffect, useMemo, useRef } from 'react';
// Sidebar is now handled by global AuthenticatedLayout
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

  // Notifications state
  const [notification, setNotification] = useState<{ message: string, type: 'info' | 'success' | 'warning' | 'error' } | null>(null);

  const showNotification = (message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info') => {
    setNotification({ message, type });
    setTimeout(() => setNotification(null), 4000);
  };

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
  const resources = useMemo(() => {
    if (!dashboardData || 'accounts' in dashboardData) return [];

    const singleData = dashboardData as GcpDashboardData;
    return mapInventoryToResources(singleData.resourceInventory);
  }, [dashboardData]);

  // Empty state
  if (selectedAccountIds.length === 0) {
    return (
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
    );
  }

  // Loading state
  if (loading.global && !dashboardData) {
    return (
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
    );
  }

  // Error state
  if (error && !dashboardData) {
    return (
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
    );
  }

  if (!dashboardData) return null;

  const singleData = 'accounts' in dashboardData ? null : (dashboardData as GcpDashboardData);

  return (
    <div className="gcp-dashboard-content">
      {/* Header */}        {/* Notification Toast */}
      {notification && (
        <div className={`notification-toast notification-${notification.type} slide-up`}>
          <div className="notification-content">
            <div className={`notification-dot dot-${notification.type}`}></div>
            {notification.message}
          </div>
          <button className="notification-close" onClick={() => setNotification(null)}>&times;</button>
        </div>
      )}

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
            onRegionClick={(name) => showNotification(`Dashboard filtered by: ${name}`, 'info')}
          />
        )}

        {widgetVisibility.inventory && resources.length > 0 && (
          <InventorySection
            resources={resources}
            totalResources={totalResources}
            loading={loading.inventory}
            accountIds={selectedAccountIds}
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

      {/* Floating Action Button */}
      <button
        className="gcp-fab"
        onClick={() => showNotification('Quick actions are coming soon!', 'info')}
      >
        <i className="fas fa-bolt"></i>
      </button>

      {/* Notifications */}
      {notification && (
        <div className="gcp-notification fade-in">
          <div className={`gcp-status-dot ${notification.type}`}></div>
          <div className="flex-1">
            <p className="text-sm font-bold text-gray-800">{notification.message}</p>
            <p className="text-xs text-gray-500">Live Notification</p>
          </div>
        </div>
      )}
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
          <img src="/icons/gcp-icon.png" alt="GCP" />
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
  const mtd = kpi?.mtdSpend || 0;
  const lastMonth = kpi?.lastMonthSpend || 0;
  const forecasted = kpi?.forecastedSpend || 0;

  // Calculate dynamic trends
  const mtdTrend = lastMonth > 0 ? ((mtd - lastMonth) / lastMonth) * 100 : 0;
  const forecastTrend = mtd > 0 ? ((forecasted - mtd) / mtd) * 100 : 0;

  const kpiCards = [
    {
      title: 'MTD Spend',
      value: mtd,
      trend: mtdTrend,
      color: '#4285F4', // Blue base
    },
    {
      title: 'Forecasted',
      value: forecasted,
      trend: forecastTrend,
      color: '#34A853', // Green base
    },
    {
      title: 'Last Month',
      value: lastMonth,
      trend: 0,
      color: '#FBBC04', // Yellow base
    },
    {
      title: 'Potential Savings',
      value: kpi?.potentialSavings || 0,
      trend: -5.4,
      color: '#EA4335', // Red base
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
              {Math.abs(card.trend).toFixed(1)}% vs last period
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
  onRegionClick: (name: string) => void;
}> = ({ regionStatus, loading, onRegionClick }) => {
  const chartRef = useRef<HTMLDivElement>(null);
  const [worldMap, setWorldMap] = useState<any>(null);

  useEffect(() => {
    // Load world map data once
    if (worldMap) return;

    fetch('https://code.highcharts.com/mapdata/custom/world.topo.json')
      .then(response => response.json())
      .then(data => setWorldMap(data))
      .catch(err => console.error('Failed to load world map:', err));
  }, [worldMap]);

  useEffect(() => {
    if (!chartRef.current || loading || !window.Highcharts || regionStatus.length === 0 || !worldMap) return;

    const statusColors: any = {
      'active': '#3b82f6',
      'issues': '#ef4444',
      'sustainable': '#22c55e',
      'inactive': '#6b7280'
    };

    const mapData = (regionStatus || []).map((region) => ({
      name: region.region,
      lat: region.latitude,
      lon: region.longitude,
      z: 10,
      status: region.status,
      color: statusColors[region.status?.toLowerCase()] || '#4285F4'
    }));

    window.Highcharts.mapChart(chartRef.current, {
      chart: { backgroundColor: 'transparent', height: 420 },
      title: { text: null },
      mapNavigation: { enabled: true, buttonOptions: { verticalAlign: 'bottom' } },
      plotOptions: {
        series: {
          point: {
            events: {
              click: function (this: any) {
                onRegionClick(this.name);
              }
            }
          }
        }
      },
      series: [
        {
          name: 'World Map',
          mapData: worldMap,
          borderColor: 'rgba(0, 0, 0, 0.05)',
          nullColor: 'rgba(0, 0, 0, 0.05)',
          showInLegend: false
        },
        {
          type: 'mapbubble',
          name: 'Regions',
          data: mapData,
          minSize: 8,
          maxSize: '10%',
          color: '#4285F4',
          tooltip: { pointFormat: '<b>{point.name}</b> ({point.status})' }
        }
      ],
      credits: { enabled: false }
    });
  }, [regionStatus, loading, onRegionClick, worldMap]);

  return (
    <div className="glass-card gcp-map-section fade-in">
      <div className="flex items-center justify-between mb-4">
        <h3 className="gcp-section-title mb-0">
          <i className="fas fa-globe-americas text-primary"></i>
          Active Regions
        </h3>
        <div className="gcp-map-legend">
          <div className="gcp-legend-item">
            <div className="gcp-legend-dot active"></div>
            <span>Active</span>
          </div>
          <div className="gcp-legend-item">
            <div className="gcp-legend-dot sustainable"></div>
            <span>Sustainable</span>
          </div>
          <div className="gcp-legend-item">
            <div className="gcp-legend-dot issues"></div>
            <span>Issues</span>
          </div>
        </div>
      </div>
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
  accountIds: string[];
}> = ({ resources, totalResources, loading, accountIds }) => {
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
            <a
              key={index}
              href={`/gcp_cloudlist.html?service=${encodeURIComponent(resource.type)}&accountIds=${accountIds.join(',')}`}
              className="gcp-resource-card hover:bg-white/10 transition-colors"
              style={{ textDecoration: 'none' }}
            >
              <div className="gcp-resource-icon">
                <img src={`/icons/gcp/${resource.icon}`} alt="" />
              </div>
              <div className="flex-1">
                <p className="gcp-resource-name">{resource.name}</p>
                <p className="gcp-resource-type">{resource.type}</p>
              </div>
              <div className="gcp-resource-count">{formatCompactNumber(resource.count)}</div>
            </a>
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

    const data = monthlyCosts.map((item: any) => ({
      y: item.cost,
      color: item.isAnomaly ? '#EA4335' : '#4285F4',
      isAnomaly: item.isAnomaly
    }));

    window.Highcharts.chart(chartRef.current, {
      chart: { type: 'column', backgroundColor: 'transparent', height: 320 },
      title: { text: null },
      xAxis: { categories },
      yAxis: { title: { text: null }, labels: { format: '${value}' } },
      series: [{
        name: 'Cost',
        data: data,
        borderRadius: 6,
        borderWidth: 0,
      }],
      credits: { enabled: false },
      tooltip: {
        shared: true,
        formatter: function (this: any) {
          const point = this.points[0].point;
          let tip = `<b>${this.x}</b><br/>Cost: $${point.y.toLocaleString()}`;
          if (point.isAnomaly) {
            tip += '<br/><span style="color:#EA4335;font-weight:bold">⚠️ Anomaly detected!</span>';
          }
          return tip;
        }
      }
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
