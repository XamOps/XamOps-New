import React, { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { Icon } from '@iconify/react';
import {
  AzureDashboardData,
  AzureResourceType,
} from '../types/azureDashboard';
import { useAzureDashboardData } from '../hooks/useAzureDashboardData';
import { mapInventoryToResources } from '../api/azureDashboardApi';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import type { CostHistory } from '../types/liveDashboard';

import KPISection from '../components/LiveDashboard/KPISection';
import SummaryCardsSection from '../components/LiveDashboard/SummaryCardsSection';
import CostTrendSection from '../components/LiveDashboard/CostTrendSection';
import TopServicesSection from '../components/LiveDashboard/TopServicesSection';
import WidgetCustomizer from '../components/LiveDashboard/WidgetCustomizer';
import AzureInventorySection from '../components/AzureDashboard/AzureInventorySection';

type TimePreset = '7d' | '30d' | '3m' | '6m' | '12m';

const DEFAULT_VISIBLE: Record<string, boolean> = {
  kpi: true, summaryCards: true, costTrend: true, inventory: true, topServices: true,
};

const LS_WIDGETS = 'xamops_azure_dashboard_widgets_v1';

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
  return <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>{children}</div>;
};

const AzureDashboard: React.FC = () => {
  const [selectedAccountIds] = useState<string[]>(() => {
    const stored = sessionStorage.getItem('selectedAccountIds');
    if (stored) {
      try { return JSON.parse(stored); } catch { return []; }
    }
    return [];
  });

  const { data, loading, error, refresh } = useAzureDashboardData({
    accountIds: selectedAccountIds,
    autoRefresh: true,
    refreshInterval: 60,
  });

  const { isLoading: accountsLoading } = useAccounts();
  const { setActions, clearActions } = useTopbar();

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

  const azureCostHistory: CostHistory = useMemo(() => ({
    labels:    dashboardData?.costHistory?.labels   || [],
    costs:     dashboardData?.costHistory?.costs    || [],
    // The shared CostHistory chart type still calls this `anomalies`; the Azure payload now calls it
    // `forecast`, which is what it has always meant. Mapping here keeps the misnomer out of the
    // Azure types without touching the shared AWS/GCP chart contract.
    anomalies: dashboardData?.costHistory?.forecast || [],
  }), [dashboardData]);

  const accountId = selectedAccountIds.length === 1 ? selectedAccountIds[0] : null;

  const customizeButton = (
    <button onClick={() => setCustomizerOpen(true)}
      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.8)] transition-colors">
      <Icon icon="mdi:view-dashboard-edit-outline" width={13} />
      Customize
    </button>
  );

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-200/50">
          <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse" />
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Connected</span>
        </div>
        <button
          onClick={refresh}
          disabled={loading.global}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-xl text-xs font-bold hover:bg-blue-700 transition-all disabled:opacity-50 shadow-lg shadow-blue-500/20"
        >
          <Icon icon={loading.global ? 'mdi:loading' : 'mdi:refresh'} width={13} className={loading.global ? 'animate-spin' : ''} />
          {loading.global ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, refresh, loading.global]);

  if (accountsLoading && selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <Icon icon="mdi:loading" width={32} color="#2563eb" className="animate-spin" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Loading Accounts…</h2>
          <p className="text-gray-500">Please wait while we fetch your Azure subscriptions.</p>
        </div>
      </div>
    );
  }

  if (selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-slate-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <img src="/icons/azure-icon.png" alt="Azure" className="w-10 h-10 object-contain" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">No Azure Subscription Selected</h2>
          <p className="text-gray-500">Please select an Azure subscription from the account selector to view your dashboard.</p>
        </div>
      </div>
    );
  }

  if (error && !dashboardData) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-red-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <Icon icon="mdi:alert-outline" width={36} color="#ef4444" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Connection Error</h2>
          <p className="text-red-500 mb-6">{error.message}</p>
          <button onClick={refresh} className="px-6 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors">
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      {/* Incomplete-data banner.
          The backend has always marked payloads whose sections could not be populated, but
          nothing rendered it — so a subscription with no cost export configured showed a
          confident $0.00 spend that read as measured. Placed above the KPI cards because the
          zeros it explains are the first thing on the page. */}
      {dashboardData?.dataComplete === false && (dashboardData.dataWarnings?.length ?? 0) > 0 && (
        <div className="flex items-start gap-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-xl">
          <Icon icon="mdi:alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
          <div className="min-w-0">
            <p className="text-xs font-bold text-amber-800 uppercase tracking-wider mb-1">
              Some figures are unavailable
            </p>
            <ul className="space-y-0.5">
              {dashboardData.dataWarnings!.map((warning) => (
                <li key={warning} className="text-sm text-amber-700">{warning}</li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* Customize — only as a standalone row when the KPI widget (its usual host) is hidden */}
      {selectedAccountIds.length > 0 && !show('kpi') && (
        <div className="flex items-center justify-end">
          {customizeButton}
        </div>
      )}

      <WidgetCustomizer
        open={customizerOpen}
        visibleWidgets={visibleWidgets}
        onToggle={handleToggleWidget}
        onClose={() => setCustomizerOpen(false)}
      />

      {/* KPI Cards — wrapper contains KPISection's fragment so its header row and
          card grid don't become `space-y-5` siblings of the page sections */}
      {show('kpi') && dashboardData && (
        <div className="widget">
          <KPISection
            kpiData={dashboardData.kpi as any}
            costHistory={azureCostHistory}
            totalResourcesOverride={totalResources}
            isLoading={loading.kpi}
            provider="Azure"
            accountId={accountId}
            headerAction={customizeButton}
          />
        </div>
      )}

      {/* Summary Cards */}
      {show('summaryCards') && (
        <RevealSection delay={0.02}>
          <SummaryCardsSection accountId={accountId} provider="Azure" isLoading={loading.kpi} />
        </RevealSection>
      )}

      {/* Cost Trend + Inventory */}
      {(show('costTrend') || show('inventory')) && (
        <RevealSection>
          <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 380 }}>
            {show('costTrend') && (
              <CostTrendSection costHistory={azureCostHistory} isLoading={loading.cost} provider="Azure" />
            )}
            {show('inventory') && (
              <AzureInventorySection
                resources={resources}
                totalResources={totalResources}
                loading={loading.inventory}
                accountIds={selectedAccountIds}
              />
            )}
          </div>
        </RevealSection>
      )}

      {/* Top Services */}
      {show('topServices') && (
        <RevealSection delay={0.04}>
          <div className="grid gap-5 lg:grid-cols-5">
            <TopServicesSection
              billingSummary={dashboardData?.billingSummary || []}
              accountId={accountId}
              timePreset={timePreset}
              isLoading={loading.cost}
              provider="Azure"
            />
          </div>
        </RevealSection>
      )}
    </main>
  );
};

export default AzureDashboard;
