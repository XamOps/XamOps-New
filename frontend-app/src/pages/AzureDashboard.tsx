import React, { useState, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import {
  AzureDashboardData,
  AzureResourceType,
} from '../types/azureDashboard';
import { useAzureDashboardData } from '../hooks/useAzureDashboardData';
import {
  mapInventoryToResources,
} from '../api/azureDashboardApi';
import { useTopbar } from '../contexts/TopbarContext';
import { useAuth } from '../contexts/AuthContext';
import '../styles/azureDashboard.css';

// Import components
import AzureKPISection from '../components/AzureDashboard/AzureKPISection';
import AzureMapSection from '../components/AzureDashboard/AzureMapSection';
import AzureInventorySection from '../components/AzureDashboard/AzureInventorySection';
import AzureCostSection from '../components/AzureDashboard/AzureCostSection';
import AzureOptimizationSection from '../components/AzureDashboard/AzureOptimizationSection';
import DashboardHeader from '../components/LiveDashboard/DashboardHeader';

// Highcharts loaded from CDN
declare global {
  interface Window {
    Highcharts: any;
  }
}

const AzureDashboard: React.FC = () => {
  // Get selected account IDs from session storage
  const [selectedAccountIds, setSelectedAccountIds] = useState<string[]>([]);

  // Dashboard data hook
  const { data, loading, error, refresh, lastUpdated } =
    useAzureDashboardData({
      accountIds: selectedAccountIds,
      autoRefresh: true,
      refreshInterval: 60,
    });

  const {
    hasAnyRole,
    selectedTenant,
    impersonateUser,
    tenantList,
    tenantUsers
  } = useAuth();
  const { setActions, clearActions } = useTopbar();

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

    if (storedAccountIds) {
      try {
        const accountIds = JSON.parse(storedAccountIds);
        setSelectedAccountIds(accountIds);
      } catch (err) {
        console.error('❌ Azure Dashboard: Failed to parse selected account IDs:', err);
      }
    }
  }, []);

  // Extract and transform dashboard data
  const dashboardData = useMemo(() => {
    if (!data) return null;
    return data as AzureDashboardData;
  }, [data]);

  // Calculate total resources
  const totalResources = useMemo(() => {
    if (!dashboardData) return 0;
    const inventory = dashboardData.resourceInventory;
    return Object.values(inventory).reduce((sum, count) => (sum as number) + (count as number), 0) as number;
  }, [dashboardData]);

  // Get resource list
  const resources: AzureResourceType[] = useMemo(() => {
    if (!dashboardData) return [];
    return mapInventoryToResources(dashboardData.resourceInventory);
  }, [dashboardData]);

  // Register Topbar actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {/* Navigation Links */}
        <div className="flex items-center gap-1 bg-slate-100/50 p-1 rounded-xl border border-slate-200/50 mr-2">
          <Link
            to="/cloudmap"
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-[11px] font-bold text-slate-600 hover:bg-white hover:text-blue-600 hover:shadow-sm transition-all"
          >
            <i className="fas fa-map-marked-alt text-[10px]"></i>
            <span>CloudMap</span>
          </Link>
          <Link
            to="/calculator"
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-[11px] font-bold text-slate-600 hover:bg-white hover:text-blue-600 hover:shadow-sm transition-all"
          >
            <i className="fas fa-calculator text-[10px]"></i>
            <span>Calculator</span>
          </Link>
        </div>

        {/* Sync Status */}
        <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-200/50">
          <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse"></div>
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
            {lastUpdated ? `Updated ${new Date(lastUpdated).toLocaleTimeString()}` : 'Connected'}
          </span>
        </div>

        {/* Refresh Button */}
        <button
          onClick={refresh}
          disabled={loading.global}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-xl text-xs font-bold hover:bg-blue-700 transition-all disabled:opacity-50 shadow-lg shadow-blue-500/20"
        >
          <i className={`fas fa-sync-alt ${loading.global ? 'animate-spin' : ''}`}></i>
          <span>{loading.global ? 'Refetching...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, lastUpdated, refresh, loading.global]);

  // Handle empty state
  if (selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 glass-card max-w-lg w-full">
          <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <img src="/icons/azure-icon.png" alt="Azure" className="w-10 h-10 object-contain" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">No Azure Subscription Selected</h2>
          <p className="text-gray-500 mb-8">Please select an Azure subscription from the account selector to view your dashboard.</p>
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

  return (
    <div className="flex-1 flex flex-col bg-[#F8FAFC] azure-dashboard-container relative">
      {/* Dashboard Header Integration */}
      {dashboardData && (
        <DashboardHeader
          hasAnyRole={hasAnyRole}
          selectedTenant={selectedTenant}
          impersonateUser={impersonateUser}
          tenantList={tenantList}
          tenantUsers={tenantUsers}
          selectedAccountIds={selectedAccountIds}
          layoutChanged={false}
          isRefreshing={loading.global}
          lastUpdateTime={lastUpdated ? new Date(lastUpdated).toLocaleTimeString() : 'Connected'}
          onRefresh={refresh}
          onSaveLayout={() => { }}
          getLink={(path) => path}
        />
      )}

      {/* Notification Toast */}
      {notification && (
        <div className={`fixed top-20 right-4 z-50 transform transition-all duration-300 ${notification ? 'translate-y-0 opacity-100' : '-translate-y-4 opacity-0'}`}>
          <div className={`flex items-center p-4 rounded-xl shadow-xl border backdrop-blur-md ${notification.type === 'info' ? 'bg-blue-50/90 border-blue-100 text-blue-800' :
            notification.type === 'success' ? 'bg-green-50/90 border-green-100 text-green-800' :
              notification.type === 'warning' ? 'bg-amber-50/90 border-amber-100 text-amber-800' :
                'bg-rose-50/90 border-rose-100 text-rose-800'
            }`}>
            <span className="text-sm font-bold">{notification.message}</span>
            <button onClick={() => setNotification(null)} className="ml-4 hover:opacity-75 font-black text-lg leading-none">&times;</button>
          </div>
        </div>
      )}

      <main className="flex-1 p-6 space-y-8 pt-4">
        {/* KPI Section */}
        {widgetVisibility.kpi && dashboardData && (
          <div className="fade-in">
            <AzureKPISection
              kpiData={dashboardData.kpi}
              costHistory={dashboardData.costHistory}
              isLoading={loading.kpi}
            />
          </div>
        )}

        {/* Main Grid: Map & Inventory (3:2) */}
        <div className="grid gap-6 lg:grid-cols-5 fade-in">
          {widgetVisibility.map && dashboardData && (
            <AzureMapSection
              regionStatus={dashboardData.regionStatus || []}
              isLoading={loading.map}
              onRegionClick={(name) => showNotification(`Dashboard filtered by: ${name}`, 'info')}
            />
          )}

          {widgetVisibility.inventory && dashboardData && (
            <AzureInventorySection
              resources={resources}
              totalResources={totalResources}
              loading={loading.inventory}
              accountIds={selectedAccountIds}
            />
          )}
        </div>

        {/* Cost & Billing Grid */}
        <div className="grid gap-6 lg:grid-cols-3 fade-in">
          {(widgetVisibility.cost || widgetVisibility.billing) && dashboardData && (
            <AzureCostSection
              costHistory={dashboardData.costHistory}
              billingSummary={dashboardData.billingSummary}
              isLoading={loading.cost}
            />
          )}
        </div>

        {/* Optimization Section */}
        {widgetVisibility.optimization && dashboardData && dashboardData.optimization && (
          <div className="fade-in">
            <AzureOptimizationSection
              optimization={dashboardData.optimization}
              selectedAccountIds={selectedAccountIds}
              isLoading={loading.optimization}
            />
          </div>
        )}
      </main>

      {/* Floating Action Button */}
      <button
        className="azure-fab group"
        onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
        title="Back to Top"
      >
        <i className="fas fa-chevron-up group-hover:-translate-y-1 transition-transform"></i>
      </button>

    </div>
  );
};

export default AzureDashboard;
