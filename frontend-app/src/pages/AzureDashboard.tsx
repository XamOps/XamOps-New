import React, { useState, useMemo, useEffect, useRef } from 'react';
import {
  AzureDashboardData,
  AzureResourceType,
} from '../types/azureDashboard';
import { useAzureDashboardData } from '../hooks/useAzureDashboardData';
import {
  mapInventoryToResources,
} from '../api/azureDashboardApi';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';

// Import components
import AzureKPISection from '../components/AzureDashboard/AzureKPISection';
import AzureMapSection from '../components/AzureDashboard/AzureMapSection';
import AzureInventorySection from '../components/AzureDashboard/AzureInventorySection';
import AzureCostSection from '../components/AzureDashboard/AzureCostSection';
import AzureOptimizationSection from '../components/AzureDashboard/AzureOptimizationSection';


// Highcharts loaded from CDN
declare global {
  interface Window {
    Highcharts: any;
  }
}

const RevealSection: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const timer = setTimeout(() => {
      const observer = new IntersectionObserver(([entry]) => {
        if (entry.isIntersecting) { el.classList.add('is-visible'); observer.unobserve(el); }
      }, { threshold: 0.06 });
      observer.observe(el);
      return () => observer.disconnect();
    }, 60);
    return () => clearTimeout(timer);
  }, []);
  return <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>{children}</div>;
};

const AzureDashboard: React.FC = () => {
  // Get selected account IDs from session storage - initialized synchronously
  const [selectedAccountIds] = useState<string[]>(() => {
    const storedAccountIds = sessionStorage.getItem('selectedAccountIds');
    if (storedAccountIds) {
      try {
        return JSON.parse(storedAccountIds);
      } catch (err) {
        console.error('❌ Azure Dashboard: Failed to parse selected account IDs:', err);
        return [];
      }
    }
    return [];
  });

  // Dashboard data hook
  const { data, loading, error, refresh } =
    useAzureDashboardData({
      accountIds: selectedAccountIds,
      autoRefresh: true,
      refreshInterval: 60,
    });
  const { isLoading: accountsLoading } = useAccounts();
  const { setActions, clearActions } = useTopbar();

  // Register topbar actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-200/50">
          <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse"></div>
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Connected</span>
        </div>
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
  }, [setActions, clearActions, refresh, loading.global]);

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

  // Extract and transform dashboard data
  const dashboardData = useMemo(() => {
    if (!data) return null;
    return data as AzureDashboardData;
  }, [data]);



  const resources: AzureResourceType[] = useMemo(() => {
    if (!dashboardData) return [];
    return mapInventoryToResources(dashboardData.resourceInventory);
  }, [dashboardData]);

  const totalResources = useMemo(() => {
    return resources.reduce((acc, curr) => acc + (curr.count || 0), 0);
  }, [resources]);


  // Show loading while accounts are being fetched
  if (accountsLoading && selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <i className="fas fa-spinner fa-spin text-3xl text-blue-600"></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Loading Accounts...</h2>
          <p className="text-gray-500 mb-8">Please wait while we fetch your Azure subscriptions.</p>
        </div>
      </div>
    );
  }

  // Handle empty state
  if (selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-slate-50 rounded-full flex items-center justify-center mx-auto mb-6">
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
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
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
    <main className="flex-1 p-8 bg-white space-y-6">
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

      {/* KPI Section */}
      {widgetVisibility.kpi && dashboardData && (
        <AzureKPISection
          kpiData={dashboardData.kpi}
          costHistory={dashboardData.costHistory}
          isLoading={loading.kpi}
        />
      )}

      {/* Main Grid: Map & Inventory (3:2) */}
      <RevealSection>
        <div className="grid gap-6 lg:grid-cols-5" style={{ height: '460px' }}>
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
      </RevealSection>

      {/* Optimization Section */}
      {widgetVisibility.optimization && dashboardData && dashboardData.optimization && (
        <RevealSection delay={0.05}>
          <AzureOptimizationSection
            optimization={dashboardData.optimization}
            selectedAccountIds={selectedAccountIds}
            isLoading={loading.optimization}
          />
        </RevealSection>
      )}

      {/* Cost & Billing Grid */}
      <RevealSection delay={0.1}>
        <div className="grid gap-6 lg:grid-cols-3">
          {(widgetVisibility.cost || widgetVisibility.billing) && dashboardData && (
            <AzureCostSection
              costHistory={dashboardData.costHistory}
              billingSummary={dashboardData.billingSummary}
              isLoading={loading.cost}
              accountId={selectedAccountIds[0]}
            />
          )}
        </div>
      </RevealSection>
    </main>
  );
};

export default AzureDashboard;
