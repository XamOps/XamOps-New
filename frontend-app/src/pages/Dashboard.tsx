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
import CompliancePostureSection from '../components/LiveDashboard/CompliancePostureSection';
import SummaryCardsSection from '../components/LiveDashboard/SummaryCardsSection';
import WidgetCustomizer from '../components/LiveDashboard/WidgetCustomizer';

type TimePreset = '7d' | '30d' | '3m' | '6m' | '12m';

/**
 * Default widget set. Cost is deliberately down to two panels — the trend (now
 * carrying forecast + budget) and Top Services — because `costByRegion` repeats
 * the Top Services region toggle and `costSection` repeats the trend plus a
 * billing table that /cost already renders with filters. Both remain available
 * in the customizer for anyone who wants them.
 */
/*
 * `optimization` is intentionally absent: its render block below is commented
 * out, and WidgetCustomizer derives its list from these keys, so including it
 * would offer a toggle that changes nothing. Add the key back at the same time
 * the section is re-enabled.
 */
const DEFAULT_VISIBLE: Record<string, boolean> = {
  kpi: true, summaryCards: true, costTrend: true, inventory: true,
  topServices: true, compliance: true,
  costByRegion: false, costSection: false,
};

// Key intentionally not bumped. loadWidgets merges stored values over the
// defaults, and only explicitly toggled widgets are ever written, so the new
// defaults reach everything a user never touched (including the new compliance
// widget) while their deliberate choices survive.
const LS_WIDGETS = 'xamops_dashboard_widgets_v1';

const loadWidgets = (): Record<string, boolean> => {
  try {
    const raw = localStorage.getItem(LS_WIDGETS);
    return raw ? { ...DEFAULT_VISIBLE, ...JSON.parse(raw) } : DEFAULT_VISIBLE;
  } catch { return DEFAULT_VISIBLE; }
};

// `id` is what makes a section addressable by the saved layout: useLayout only
// applies an order that names every child of #widgets-container, so a section
// left without one would pin the whole dashboard to the default order.
const RevealSection: React.FC<{ children: React.ReactNode; id?: string; delay?: number }> = ({ children, id, delay = 0 }) => {
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
    <div ref={ref} id={id} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>
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
  /**
   * Account the CUR reload applies to, or null to hide the button. A CUR belongs to a
   * single AWS account, so a multi-account selection or a GCP/Azure account has nothing
   * to reload.
   */
  const curAccountId = React.useMemo(() => {
    if (multiAccountMode || selectedAccountIds.length !== 1) return null;
    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    return (account?.provider ?? 'AWS') === 'AWS' ? selectedAccountIds[0] : null;
  }, [multiAccountMode, selectedAccountIds, accounts]);
  const timePreset: TimePreset = '30d';
  const [visibleWidgets, setVisibleWidgets] = useState<Record<string, boolean>>(loadWidgets);
  const [customizerOpen, setCustomizerOpen] = useState(false);

  const show = useCallback((id: string) => visibleWidgets[id] !== false, [visibleWidgets]);

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

  /**
   * Provider-appropriate destination for a dashboard link, or null when the feature does not
   * exist for the selected provider.
   *
   * <p>Cloud Map previously mapped to `/cloudmap` for all three providers. That is the AWS page:
   * it calls `/api/xamops/cloudmap/vpcs?accountId=…`, so a GCP project id or Azure subscription id
   * was being sent to the AWS endpoint. GCP has its own page, and Azure has no Cloud Map at all —
   * as AccountContext's PROVIDER_PAGE_MAP already documents — so the link is withheld there
   * rather than pointing somewhere that cannot answer.
   */
  const getLink = (basePath: string): string | null => {
    if (selectedAccountIds.length === 0) return basePath;
    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    const provider = account?.provider || 'AWS';
    const providerPaths: Record<string, Partial<Record<string, string>>> = {
      '/cloudmap':    { AWS: '/cloudmap',    GCP: '/gcp-cloudmap' },
      '/calculator':  { AWS: '/calculator',  GCP: '/calculator',  Azure: '/calculator'  },
    };
    const mapping = providerPaths[basePath];
    if (!mapping) return basePath;
    return mapping[provider] ?? null;
  };

  const accountId = selectedAccountIds.length === 1 ? selectedAccountIds[0] : null;

  if (error) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
        <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-rose-50 border border-rose-100 rounded-xl">
            <Icon icon="mdi:alert-outline" width={28} color="#F43F5E" />
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
        onCurRefreshed={() => refreshDashboard(false)}
        curAccountId={curAccountId}
        onSaveLayout={saveLayout}
        getLink={getLink}
      />

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
      {selectedAccountIds.length > 0 && dashboardData && !loadingState.global && (
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
                headerAction={
                  <button onClick={() => setCustomizerOpen(true)}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.8)] transition-colors">
                    <Icon icon="mdi:view-dashboard-edit-outline" width={13} />
                    Customize
                  </button>
                }
              />
            </div>
          )}

          {/* Row 1b: Summary Cards — Alerts, Security, Waste, Rightsizing, Lambda */}
          {show('summaryCards') && (
            <RevealSection id="widget-summary-cards" delay={0.02}>
              <SummaryCardsSection
                accountId={accountId}
                isLoading={loadingState.optimization}
              />
            </RevealSection>
          )}

          {/* Row 2: Cost Trend + Resource Inventory */}
          {(show('costTrend') || show('inventory')) && (
            <RevealSection id="widget-insights">
              <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 380 }}>
                {show('costTrend') && (
                  <CostTrendSection
                    costHistory={dashboardData.costHistory}
                    isLoading={loadingState.cost}
                    forecastedSpend={dashboardData.kpi?.forecastedSpend}
                    accountId={accountId}
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

          {/* Row 3: Top Services (3 cols) + Compliance Posture (2 cols).
              Compliance takes the slot Cost by Region used to occupy. */}
          {(show('topServices') || show('compliance') || show('costByRegion')) && (
            <RevealSection id="widget-services" delay={0.04}>
              <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 340 }}>
                {show('topServices') && (
                  <TopServicesSection
                    billingSummary={dashboardData.billingSummary}
                    accountId={accountId}
                    timePreset={timePreset}
                    isLoading={loadingState.cost}
                  />
                )}
                {show('compliance') && (
                  <CompliancePostureSection accountId={accountId} />
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
            <RevealSection id="widget-costs" delay={0.08}>
              <div className="widget grid gap-5 lg:grid-cols-5">
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
