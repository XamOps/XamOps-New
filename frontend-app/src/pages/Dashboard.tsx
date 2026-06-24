import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { useDashboardData } from '../hooks/useDashboardData';
import { useWebSocket } from '../hooks/useWebSocket';
import { useLayout } from '../hooks/useLayout';
import type { ResourceInventory } from '../types/liveDashboard';

import DashboardHeader from '../components/LiveDashboard/DashboardHeader';
import KPISection from '../components/LiveDashboard/KPISection';
import InventorySection from '../components/LiveDashboard/InventorySection';
// import OptimizationSection from '../components/LiveDashboard/OptimizationSection';
import CostSection from '../components/LiveDashboard/CostSection';
import EmptyState from '../components/LiveDashboard/EmptyState';
import LoadingSkeleton from '../components/LiveDashboard/LoadingSkeleton';
import CostTrendSection from '../components/LiveDashboard/CostTrendSection';
import TopServicesSection from '../components/LiveDashboard/TopServicesSection';
import CostByRegionSection from '../components/LiveDashboard/CostByRegionSection';
import SummaryCardsSection from '../components/LiveDashboard/SummaryCardsSection';
import WidgetCustomizer from '../components/LiveDashboard/WidgetCustomizer';

type TimePreset = '7d' | '30d' | '3m' | '6m' | '12m';

const TIME_PRESETS: { id: TimePreset; label: string }[] = [
  { id: '7d',  label: '7D'  },
  { id: '30d', label: '30D' },
  { id: '3m',  label: '3M'  },
  { id: '6m',  label: '6M'  },
  { id: '12m', label: '12M' },
];

const DEFAULT_VISIBLE: Record<string, boolean> = {
  kpi: true, summaryCards: true, costTrend: true, inventory: true,
  topServices: true, costByRegion: true, optimization: true, costSection: true,
};

const LS_WIDGETS = 'xamops_dashboard_widgets_v1';
const LS_PRESET  = 'xamops_dashboard_preset_v1';

const loadWidgets = (): Record<string, boolean> => {
  try {
    const raw = localStorage.getItem(LS_WIDGETS);
    return raw ? { ...DEFAULT_VISIBLE, ...JSON.parse(raw) } : DEFAULT_VISIBLE;
  } catch { return DEFAULT_VISIBLE; }
};

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
  return (
    <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>
      {children}
    </div>
  );
};

export const Dashboard: React.FC = () => {
  const { hasAnyRole, selectedTenant, impersonateUser, tenantList, tenantUsers } = useAuth();
  const { selectedAccountIds, accounts, multiAccountMode } = useAccounts();
  const { layoutChanged, saveLayout, initializeSortable } = useLayout();

  const {
    dashboardData, loadingState, error, isRefreshing, refreshDashboard, updateResourceInventory,
  } = useDashboardData({
    selectedAccountIds,
    isMultiAccountMode: multiAccountMode,
    autoRefreshInterval: 60000,
  });

  const [lastUpdateTime, setLastUpdateTime] = useState<string>('Connected');
  const [timePreset, setTimePreset] = useState<TimePreset>(
    () => (localStorage.getItem(LS_PRESET) as TimePreset) || '30d'
  );
  const [visibleWidgets, setVisibleWidgets] = useState<Record<string, boolean>>(loadWidgets);
  const [customizerOpen, setCustomizerOpen] = useState(false);

  const show = useCallback((id: string) => visibleWidgets[id] !== false, [visibleWidgets]);

  const handlePreset = (p: TimePreset) => {
    setTimePreset(p);
    localStorage.setItem(LS_PRESET, p);
  };

  const handleToggleWidget = (id: string) => {
    setVisibleWidgets(prev => {
      const next = { ...prev, [id]: prev[id] === false };
      localStorage.setItem(LS_WIDGETS, JSON.stringify(next));
      return next;
    });
  };

  const handleInventoryUpdate = useCallback((inventory: ResourceInventory) => {
    setLastUpdateTime(new Date().toLocaleTimeString());
    updateResourceInventory(inventory);
  }, [updateResourceInventory]);

  useWebSocket({
    accountId: selectedAccountIds.length === 1 ? selectedAccountIds[0] : null,
    enabled: selectedAccountIds.length === 1 && !multiAccountMode,
    onInventoryUpdate: handleInventoryUpdate,
  });

  useEffect(() => {
    if (selectedAccountIds.length > 0 && dashboardData) {
      const timer = setTimeout(() => initializeSortable('widgets-container'), 500);
      return () => clearTimeout(timer);
    }
  }, [selectedAccountIds, dashboardData, initializeSortable]);

  useEffect(() => {
    if (dashboardData?.lastUpdated) {
      setLastUpdateTime(new Date(dashboardData.lastUpdated).toLocaleTimeString());
    }
  }, [dashboardData]);

  useEffect(() => {
    const onAccountChanged = () => refreshDashboard();
    globalThis.addEventListener('account-changed', onAccountChanged);
    globalThis.addEventListener('multi-account-selection-changed', onAccountChanged);
    return () => {
      globalThis.removeEventListener('account-changed', onAccountChanged);
      globalThis.removeEventListener('multi-account-selection-changed', onAccountChanged);
    };
  }, [refreshDashboard]);

  const getLink = (basePath: string): string => {
    if (selectedAccountIds.length === 0) return basePath;
    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    const provider = account?.provider || 'AWS';
    const providerPaths: Record<string, Record<string, string>> = {
      '/cloudmap':    { AWS: '/cloudmap',    GCP: '/cloudmap',    Azure: '/cloudmap'    },
      '/calculator':  { AWS: '/calculator',  GCP: '/calculator',  Azure: '/calculator'  },
    };
    return providerPaths[basePath]?.[provider] || basePath;
  };

  const accountId = selectedAccountIds.length === 1 ? selectedAccountIds[0] : null;

  if (error) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
        <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-rose-50 border border-rose-100 rounded-xl">
            <Icon icon="mdi:alert-triangle-outline" width={28} color="#F43F5E" />
          </div>
          <h2 className="text-xl font-bold text-center mb-4 text-[rgba(0,0,0,0.88)]">Connection Error</h2>
          <p className="text-center text-[rgba(0,0,0,0.5)] text-sm mb-6">{error.message}</p>
          <button onClick={() => globalThis.location.reload()}
            className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors">
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

      {/* ── Global filter bar ── */}
      {selectedAccountIds.length > 0 && (
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)]">Period</span>
            <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
              {TIME_PRESETS.map(p => (
                <button key={p.id} onClick={() => handlePreset(p.id)}
                  className={`px-2.5 py-1 rounded-md text-[12px] font-medium transition-all ${
                    timePreset === p.id
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}>{p.label}
                </button>
              ))}
            </div>
          </div>

          <button onClick={() => setCustomizerOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] transition-colors">
            <Icon icon="mdi:view-dashboard-edit-outline" width={14} />
            Customize
          </button>
        </div>
      )}

      {/* Widget Customizer */}
      <WidgetCustomizer
        open={customizerOpen}
        visibleWidgets={visibleWidgets}
        onToggle={handleToggleWidget}
        onClose={() => setCustomizerOpen(false)}
      />

      {/* No Account State */}
      {selectedAccountIds.length === 0 && !loadingState.global && <EmptyState />}

      {/* Loading */}
      {selectedAccountIds.length > 0 && (loadingState.global || (!dashboardData && !error)) && <LoadingSkeleton />}

      {/* ── Dashboard Content ── */}
      {selectedAccountIds.length > 0 && dashboardData && (
        <div id="widgets-container" className="space-y-5">

          {/* Row 1: KPI Cards */}
          {show('kpi') && (
            <div id="widget-kpi" className="widget">
              <KPISection
                kpiData={dashboardData.kpi}
                costHistory={dashboardData.costHistory}
                optimization={dashboardData.optimization}
                resourceInventory={dashboardData.resourceInventory}
                isLoading={loadingState.kpi}
              />
            </div>
          )}

          {/* Row 1b: Summary Cards — Alerts, Security, Waste, Rightsizing, Lambda */}
          {show('summaryCards') && (
            <RevealSection delay={0.02}>
              <SummaryCardsSection
                accountId={accountId}
                isLoading={loadingState.optimization}
              />
            </RevealSection>
          )}

          {/* Row 2: Cost Trend + Resource Inventory */}
          {(show('costTrend') || show('inventory')) && (
            <RevealSection>
              <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 380 }}>
                {show('costTrend') && (
                  <CostTrendSection
                    costHistory={dashboardData.costHistory}
                    isLoading={loadingState.cost}
                  />
                )}
                {show('inventory') && (
                  <InventorySection
                    inventory={dashboardData.resourceInventory}
                    isLoading={loadingState.inventory}
                    getLink={getLink}
                  />
                )}
              </div>
            </RevealSection>
          )}

          {/* Row 3: Top Services + Cost by Region */}
          {(show('topServices') || show('costByRegion')) && (
            <RevealSection delay={0.04}>
              <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 340 }}>
                {show('topServices') && (
                  <TopServicesSection
                    billingSummary={dashboardData.billingSummary}
                    accountId={accountId}
                    timePreset={timePreset}
                    isLoading={loadingState.cost}
                  />
                )}
                {show('costByRegion') && (
                  <CostByRegionSection
                    accountId={accountId}
                    timePreset={timePreset}
                  />
                )}
              </div>
            </RevealSection>
          )}

          {/* Row 4: Optimization Hub — temporarily hidden */}
          {/* {show('optimization') && (
            <RevealSection delay={0.06}>
              <div id="widget-optimization" className="widget">
                <OptimizationSection
                  optimization={dashboardData.optimization}
                  isLoading={loadingState.optimization}
                  isMultiAccount={multiAccountMode && selectedAccountIds.length > 1}
                  selectedAccountIds={selectedAccountIds}
                />
              </div>
            </RevealSection>
          )} */}

          {/* Row 5: Cost Breakdown + Billing Summary */}
          {show('costSection') && (
            <RevealSection delay={0.08}>
              <div id="widget-costs" className="widget grid gap-5 lg:grid-cols-5">
                <CostSection
                  costHistory={dashboardData.costHistory}
                  billingSummary={dashboardData.billingSummary}
                  isLoading={loadingState.cost}
                  accountId={accountId}
                />
              </div>
            </RevealSection>
          )}
        </div>
      )}
    </main>
  );
};

export default Dashboard;
