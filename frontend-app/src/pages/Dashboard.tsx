/**
 * Live Dashboard Page
 *
 * Converted from: dashboard.html
 *
 * This is the main live dashboard with real-time updates, KPIs, resource inventory,
 * cost analytics, and optimization recommendations.
 *
 * Changes from original:
 * - Converted from Alpine.js to React functional component with hooks
 * - TypeScript for type safety
 * - Modern React patterns (hooks, context, composition)
 * - All backend integration preserved exactly (same endpoints, same logic)
 * - Visual appearance and functionality identical to original
 */

import React, { useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { useDashboardData } from '../hooks/useDashboardData';
import { useWebSocket } from '../hooks/useWebSocket';
import { useLayout } from '../hooks/useLayout';
import type { ResourceInventory } from '../types/liveDashboard';

// Component imports (will be created)
import DashboardHeader from '../components/LiveDashboard/DashboardHeader';
import KPISection from '../components/LiveDashboard/KPISection';
import MapSection from '../components/LiveDashboard/MapSection';
import InventorySection from '../components/LiveDashboard/InventorySection';
import OptimizationSection from '../components/LiveDashboard/OptimizationSection';
import CostSection from '../components/LiveDashboard/CostSection';
import EmptyState from '../components/LiveDashboard/EmptyState';
import LoadingSkeleton from '../components/LiveDashboard/LoadingSkeleton';

const RevealSection: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    // Small delay before observing so initially-visible elements still animate in
    const timer = setTimeout(() => {
      const observer = new IntersectionObserver(
        ([entry]) => {
          if (entry.isIntersecting) {
            el.classList.add('is-visible');
            observer.unobserve(el);
          }
        },
        { threshold: 0.06 },
      );
      observer.observe(el);
      return () => observer.disconnect();
    }, 60);

    return () => clearTimeout(timer);
  }, []);

  return (
    <div
      ref={ref}
      className="section-reveal"
      style={delay ? { transitionDelay: `${delay}s` } : undefined}
    >
      {children}
    </div>
  );
};

export const Dashboard: React.FC = () => {
  const { hasAnyRole, selectedTenant, impersonateUser, tenantList, tenantUsers } = useAuth();
  const { selectedAccountIds, accounts, multiAccountMode } = useAccounts();
  const { layoutChanged, saveLayout, initializeSortable } = useLayout();

  // Get dashboard data
  const {
    dashboardData,
    loadingState,
    error,
    isRefreshing,
    refreshDashboard,
    updateResourceInventory,
  } = useDashboardData({
    selectedAccountIds,
    isMultiAccountMode: multiAccountMode,
    autoRefreshInterval: 60000, // 60 seconds
  });

  // Debug: Log component state on every render
  console.log('🎯 [Dashboard] RENDER - Component state:', {
    selectedAccountIds,
    dashboardData: dashboardData ? { accountId: dashboardData.accountId, kpi: !!dashboardData.kpi } : null,
    loadingState,
    error,
    hasDashboardData: !!dashboardData,
    hasSelectedAccounts: selectedAccountIds.length > 0,
  });

  // Debug: Mount/Unmount logging
  useEffect(() => {
    console.log('🏁 [Dashboard] MOUNTED');
    return () => {
      console.log('🛑 [Dashboard] UNMOUNTED');
    };
  }, []);

  // Debug: Log when dashboardData changes
  useEffect(() => {
    console.log('🎯 [Dashboard] dashboardData updated:', dashboardData);
    if (dashboardData) {
      console.log('🎯 [Dashboard] KPI data:', dashboardData.kpi);
      console.log('🎯 [Dashboard] Inventory data:', dashboardData.resourceInventory);
    }
  }, [dashboardData]);

  // Track last update time
  const [lastUpdateTime, setLastUpdateTime] = useState<string>('Connected');

  // WebSocket for real-time inventory updates (single account only)
  const handleInventoryUpdate = React.useCallback((inventory: ResourceInventory) => {
    console.log('📦 Real-time inventory update received:', inventory);
    setLastUpdateTime(new Date().toLocaleTimeString());
    // Update dashboard data with new inventory
    updateResourceInventory(inventory);
  }, [updateResourceInventory]);

  useWebSocket({
    accountId: selectedAccountIds.length === 1 ? selectedAccountIds[0] : null,
    enabled: selectedAccountIds.length === 1 && !multiAccountMode,
    onInventoryUpdate: handleInventoryUpdate,
  });

  // Initialize SortableJS for widget reordering
  useEffect(() => {
    if (selectedAccountIds.length > 0 && dashboardData) {
      // Wait for widgets to render, then initialize sortable
      const timer = setTimeout(() => {
        initializeSortable('widgets-container');
      }, 500);
      return () => clearTimeout(timer);
    }
  }, [selectedAccountIds, dashboardData, initializeSortable]);

  // Update last update time when data changes
  useEffect(() => {
    if (dashboardData?.lastUpdated) {
      const time = new Date(dashboardData.lastUpdated);
      setLastUpdateTime(time.toLocaleTimeString());
    }
  }, [dashboardData]);

  // Listen for account selection changes (for soft navigation)
  useEffect(() => {
    const handleAccountChanged = () => {
      console.log('🔄 Account changed, reloading dashboard...');
      refreshDashboard();
    };

    const handleMultiAccountChanged = () => {
      console.log('🔄 Multi-account selection changed, reloading dashboard...');
      refreshDashboard();
    };

    globalThis.addEventListener('account-changed', handleAccountChanged);
    globalThis.addEventListener('multi-account-selection-changed', handleMultiAccountChanged);

    return () => {
      globalThis.removeEventListener('account-changed', handleAccountChanged);
      globalThis.removeEventListener('multi-account-selection-changed', handleMultiAccountChanged);
    };
  }, [refreshDashboard]);

  // Helper function to generate provider-aware links
  const getLink = (basePath: string): string => {
    if (selectedAccountIds.length === 0) return basePath;

    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    const provider = account?.provider || 'AWS';

    // Map base paths to provider-specific paths
    const providerPaths: Record<string, Record<string, string>> = {
      '/cloudmap': {
        AWS: '/cloudmap',
        GCP: '/cloudmap',
        Azure: '/cloudmap',
      },
      '/calculator': {
        AWS: '/calculator',
        GCP: '/calculator',
        Azure: '/calculator',
      },
    };

    return providerPaths[basePath]?.[provider] || basePath;
  };

  // Show error state
  if (error) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
        <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-rose-50 border border-rose-100 rounded-xl">
            <Icon icon="mdi:alert-triangle-outline" width={28} color="#F43F5E" />
          </div>
          <h2 className="text-xl font-bold text-center mb-4 text-[rgba(0,0,0,0.88)]">Connection Error</h2>
          <p className="text-center text-[rgba(0,0,0,0.5)] text-sm mb-6">{error.message}</p>
          <button
            onClick={() => globalThis.location.reload()}
            className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <DashboardHeader
        hasAnyRole={hasAnyRole}
        selectedTenant={selectedTenant}
        impersonateUser={impersonateUser}
        tenantList={tenantList}
        tenantUsers={tenantUsers}
        selectedAccountIds={selectedAccountIds}
        layoutChanged={layoutChanged}
        isRefreshing={isRefreshing}
        lastUpdateTime={lastUpdateTime}
        onRefresh={refreshDashboard}
        onSaveLayout={saveLayout}
        getLink={getLink}
      />

      {/* No Account State */}
      {selectedAccountIds.length === 0 && !loadingState.global && (
        <>
          {console.log('🎯 [Dashboard] Rendering EmptyState')}
          <EmptyState />
        </>
      )}

      {/* Loading Skeleton — also shown when account is selected but data hasn't arrived yet */}
      {selectedAccountIds.length > 0 && (loadingState.global || (!dashboardData && !error)) && (
        <>
          {console.log('🎯 [Dashboard] Rendering LoadingSkeleton')}
          <LoadingSkeleton />
        </>
      )}

      {/* Dashboard Content */}
      {selectedAccountIds.length > 0 && dashboardData && (
        <>
          {console.log('🎯 [Dashboard] Rendering Dashboard Content with data:', dashboardData)}
          <div id="widgets-container" className="space-y-5">
            {/* KPI Cards — own stagger animation, no scroll-reveal needed */}
            <div id="widget-kpi" className="widget">
              <KPISection
                kpiData={dashboardData.kpi}
                costHistory={dashboardData.costHistory}
                isLoading={loadingState.kpi}
              />
            </div>

            {/* Map + Inventory Widget */}
            <RevealSection>
              <div id="widget-middle" className="widget grid gap-6 lg:grid-cols-5" style={{ height: '460px' }}>
                <MapSection
                  regionStatus={dashboardData.regionStatus || []}
                  isLoading={loadingState.map}
                />
                <InventorySection
                  inventory={dashboardData.resourceInventory}
                  isLoading={loadingState.inventory}
                  getLink={getLink}
                />
              </div>
            </RevealSection>

            {/* Optimization Hub Widget */}
            <RevealSection delay={0.05}>
              <div id="widget-optimization" className="widget">
                <OptimizationSection
                  optimization={dashboardData.optimization}
                  isLoading={loadingState.optimization}
                  isMultiAccount={multiAccountMode && selectedAccountIds.length > 1}
                  selectedAccountIds={selectedAccountIds}
                />
              </div>
            </RevealSection>

            {/* Cost History + Billing Widget */}
            <RevealSection delay={0.05}>
              <div id="widget-costs" className="widget grid gap-6 lg:grid-cols-5">
                <CostSection
                  costHistory={dashboardData.costHistory}
                  billingSummary={dashboardData.billingSummary}
                  isLoading={loadingState.cost}
                  accountId={selectedAccountIds.length === 1 ? selectedAccountIds[0] : null}
                />
              </div>
            </RevealSection>
          </div>
        </>
      )}
    </main>
  );
};

export default Dashboard;
