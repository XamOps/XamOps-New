import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Icon } from '@iconify/react';
import {
  GcpDashboardData,
  GcpResourceType,
} from '../types/gcpDashboard';
import { useGcpDashboardData } from '../hooks/useGcpDashboardData';
import { mapInventoryToResources } from '../api/gcpDashboardApi';
import { useTopbar } from '../contexts/TopbarContext';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';
import type { CostHistory } from '../types/liveDashboard';

import KPISection from '../components/LiveDashboard/KPISection';
import SummaryCardsSection from '../components/LiveDashboard/SummaryCardsSection';
import CostTrendSection from '../components/LiveDashboard/CostTrendSection';
import TopServicesSection from '../components/LiveDashboard/TopServicesSection';
import WidgetCustomizer from '../components/LiveDashboard/WidgetCustomizer';
import GcpInventorySection from '../components/GcpDashboard/GcpInventorySection';

type TimePreset = '7d' | '30d' | '3m' | '6m' | '12m';

const DEFAULT_VISIBLE: Record<string, boolean> = {
  kpi: true, summaryCards: true, costTrend: true, inventory: true, topServices: true,
};

const LS_WIDGETS = 'xamops_gcp_dashboard_widgets_v1';

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

const GcpDashboard: React.FC = () => {
  const [selectedAccountIds] = useState<string[]>(() => {
    const stored = sessionStorage.getItem('selectedAccountIds');
    if (stored) {
      try { return JSON.parse(stored); } catch { return []; }
    }
    return [];
  });

  const [multiAccountMode] = useState(() => {
    const stored = sessionStorage.getItem('selectedAccountIds');
    const multiMode = sessionStorage.getItem('multiAccountMode');
    if (stored) {
      try {
        const ids = JSON.parse(stored);
        return multiMode === 'true' && ids.length > 1;
      } catch { return false; }
    }
    return false;
  });

  const { data, loading, error, refresh, lastUpdated } = useGcpDashboardData({
    accountIds: selectedAccountIds,
    multiAccountMode,
    autoRefresh: true,
    refreshInterval: 60,
  });

  const { setActions, clearActions } = useTopbar();
  const { isLoading: accountsLoading } = useAccounts();

  const timePreset: TimePreset = '30d';
  const [visibleWidgets, setVisibleWidgets] = useState<Record<string, boolean>>(loadWidgets);
  const [customizerOpen, setCustomizerOpen] = useState(false);
  const [autoSpottingSavings, setAutoSpottingSavings] = useState<number>(0);
  const [, setIsLoadingAutoSpotting] = useState(false);

  const show = useCallback((id: string) => visibleWidgets[id] !== false, [visibleWidgets]);

  const handleToggleWidget = (id: string) => {
    setVisibleWidgets(prev => {
      const next = { ...prev, [id]: prev[id] === false };
      localStorage.setItem(LS_WIDGETS, JSON.stringify(next));
      return next;
    });
  };

  const fetchAutoSpottingSavings = useCallback(async (accountId: string) => {
    try {
      setIsLoadingAutoSpotting(true);
      const migsData = await apiClient.get(`/api/gcp/autospotting/migs/all-regions/${accountId}`);
      const totalPotentialSavings = migsData.reduce((sum: number, mig: any) => {
        return sum + ((mig.potentialHourlySavings || 0) * 730);
      }, 0);
      setAutoSpottingSavings(totalPotentialSavings);
    } catch {
      setAutoSpottingSavings(0);
    } finally {
      setIsLoadingAutoSpotting(false);
    }
  }, []);

  useEffect(() => {
    if (selectedAccountIds.length === 1 && !multiAccountMode) {
      fetchAutoSpottingSavings(selectedAccountIds[0]);
    } else {
      setAutoSpottingSavings(0);
    }
  }, [selectedAccountIds, multiAccountMode, fetchAutoSpottingSavings]);

  const dashboardData = useMemo(() => {
    if (!data) return null;

    const transformSingle = (raw: any): GcpDashboardData => {
      const optimizationSavings = raw.optimizationSummary?.totalPotentialSavings || 0;
      const totalPotentialSavings = optimizationSavings + autoSpottingSavings;

      if (raw.kpi && raw.optimization && raw.billingSummary) {
        return {
          ...raw,
          kpi: {
            ...raw.kpi,
            potentialSavings: totalPotentialSavings,
            potentialSavingsBreakdown: {
              optimization: optimizationSavings,
              autoSpotting: autoSpottingSavings,
            },
          },
        };
      }

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
          potentialSavings: totalPotentialSavings,
          potentialSavingsBreakdown: {
            optimization: optimizationSavings,
            autoSpotting: autoSpottingSavings,
          },
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
          resourceCount: 1,
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
          gceRecommendations: (Array.isArray(raw.rightsizingRecommendations) ? raw.rightsizingRecommendations : [])
            .filter((rec: any) => rec.service === 'Compute Engine' || !rec.service)
            .map((rec: any) => ({
              resourceId: rec.resourceId || rec.resourceName,
              resourceName: rec.resourceName || rec.resourceId,
              resourceType: rec.service || 'Compute Engine',
              currentSize: rec.currentMachineType || rec.currentType,
              recommendedSize: rec.recommendedMachineType || rec.recommendedType,
              currentCost: rec.currentMonthlyCost,
              potentialSavings: rec.monthlySavings || rec.estimatedMonthlySavings || 0,
              savingsPercentage: rec.currentMonthlyCost > 0 ? ((rec.monthlySavings || rec.estimatedMonthlySavings || 0) / rec.currentMonthlyCost) * 100 : 0,
              zone: rec.location || 'global',
              reason: rec.reason || rec.recommendationReason,
              confidence: 'high',
            })),
          cloudSqlRecommendations: (Array.isArray(raw.rightsizingRecommendations) ? raw.rightsizingRecommendations : [])
            .filter((rec: any) => rec.service === 'Cloud SQL')
            .map((rec: any) => ({
              resourceId: rec.resourceId || rec.resourceName,
              resourceName: rec.resourceName || rec.resourceId,
              resourceType: rec.service || 'Cloud SQL',
              currentSize: rec.currentMachineType || rec.currentType,
              recommendedSize: rec.recommendedMachineType || rec.recommendedType,
              currentCost: rec.currentMonthlyCost,
              potentialSavings: rec.monthlySavings || rec.estimatedMonthlySavings || 0,
              savingsPercentage: rec.currentMonthlyCost > 0 ? ((rec.monthlySavings || rec.estimatedMonthlySavings || 0) / rec.currentMonthlyCost) * 100 : 0,
              zone: rec.location || 'global',
              reason: rec.reason || rec.recommendationReason,
              confidence: 'high',
            })),
          gkeRecommendations: (Array.isArray(raw.rightsizingRecommendations) ? raw.rightsizingRecommendations : [])
            .filter((rec: any) => rec.service === 'GKE' || rec.service === 'Kubernetes Engine')
            .map((rec: any) => ({
              resourceId: rec.resourceId || rec.resourceName,
              resourceName: rec.resourceName || rec.resourceId,
              resourceType: rec.service || 'GKE',
              currentSize: rec.currentMachineType || rec.currentType,
              recommendedSize: rec.recommendedMachineType || rec.recommendedType,
              currentCost: rec.currentMonthlyCost,
              potentialSavings: rec.monthlySavings || rec.estimatedMonthlySavings || 0,
              savingsPercentage: rec.currentMonthlyCost > 0 ? ((rec.monthlySavings || rec.estimatedMonthlySavings || 0) / rec.currentMonthlyCost) * 100 : 0,
              zone: rec.location || 'global',
              reason: rec.reason || rec.recommendationReason,
              confidence: 'high',
            })),
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
  }, [data, selectedAccountIds, autoSpottingSavings]);

  const singleData = dashboardData ? ('accounts' in dashboardData ? null : (dashboardData as GcpDashboardData)) : null;

  const totalResources = useMemo(() => {
    if (!dashboardData) return 0;
    if ('aggregated' in dashboardData) return (dashboardData as any).aggregated.totalResourceCount;
    const inventory = (dashboardData as GcpDashboardData).resourceInventory;
    return Object.values(inventory).reduce((sum, count) => (sum as number) + (count as number), 0) as number;
  }, [dashboardData]);

  const resources: GcpResourceType[] = useMemo(() => {
    if (!dashboardData || 'accounts' in dashboardData) return [];
    return mapInventoryToResources((dashboardData as GcpDashboardData).resourceInventory);
  }, [dashboardData]);

  const gcpCostHistory: CostHistory = useMemo(() => ({
    labels:    singleData?.costHistory?.monthlyCosts?.map((c: any) => c.date)        || [],
    costs:     singleData?.costHistory?.monthlyCosts?.map((c: any) => c.cost)        || [],
    anomalies: singleData?.costHistory?.monthlyCosts?.map((c: any) => c.isAnomaly || false) || [],
  }), [singleData]);

  const gcpBillingSummary = useMemo(() =>
    (singleData?.billingSummary?.topServices || []).map((s: any) => ({
      serviceName: s.serviceName,
      monthToDateCost: s.cost,
    })),
  [singleData]);

  const accountId = selectedAccountIds.length === 1 ? selectedAccountIds[0] : null;

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-200/50">
          <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse" />
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
            {lastUpdated ? `Updated ${new Date(lastUpdated).toLocaleTimeString()}` : 'Connected'}
          </span>
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
  }, [setActions, clearActions, lastUpdated, refresh, loading.global]);

  if (accountsLoading && selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <Icon icon="mdi:loading" width={32} color="#2563eb" className="animate-spin" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Loading Accounts…</h2>
          <p className="text-gray-500">Please wait while we fetch your GCP projects.</p>
        </div>
      </div>
    );
  }

  if (selectedAccountIds.length === 0) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-slate-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <img src="/icons/gcp-icon.png" alt="GCP" className="w-10 h-10 object-contain" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">No GCP Project Selected</h2>
          <p className="text-gray-500">Please select a GCP project from the account selector to view your dashboard.</p>
        </div>
      </div>
    );
  }

  if (error && !dashboardData) {
    return (
      <div className="flex-1 p-6 flex items-center justify-center min-h-[calc(100vh-80px)]">
        <div className="text-center p-8 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg w-full">
          <div className="w-20 h-20 bg-red-50 rounded-full flex items-center justify-center mx-auto mb-6">
            <Icon icon="mdi:alert-triangle-outline" width={36} color="#ef4444" />
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
      {/* Customize */}
      {selectedAccountIds.length > 0 && (
        <div className="flex items-center justify-end">
          <button onClick={() => setCustomizerOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.04)] transition-colors">
            <Icon icon="mdi:view-dashboard-edit-outline" width={14} />
            Customize
          </button>
        </div>
      )}

      <WidgetCustomizer
        open={customizerOpen}
        visibleWidgets={visibleWidgets}
        onToggle={handleToggleWidget}
        onClose={() => setCustomizerOpen(false)}
      />

      {/* KPI Cards */}
      {show('kpi') && dashboardData && (
        <KPISection
          kpiData={dashboardData.kpi as any}
          costHistory={gcpCostHistory}
          isLoading={loading.kpi}
          provider="GCP"
          accountId={accountId}
        />
      )}

      {/* Summary Cards */}
      {show('summaryCards') && (
        <RevealSection delay={0.02}>
          <SummaryCardsSection accountId={accountId} provider="GCP" isLoading={loading.kpi} />
        </RevealSection>
      )}

      {/* Cost Trend + Inventory */}
      {(show('costTrend') || show('inventory')) && (
        <RevealSection>
          <div className="grid gap-5 lg:grid-cols-5" style={{ minHeight: 380 }}>
            {show('costTrend') && (
              <CostTrendSection costHistory={gcpCostHistory} isLoading={loading.cost} provider="GCP" />
            )}
            {show('inventory') && (
              <GcpInventorySection
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
              billingSummary={gcpBillingSummary}
              accountId={accountId}
              timePreset={timePreset}
              isLoading={loading.cost}
              provider="GCP"
            />
          </div>
        </RevealSection>
      )}
    </main>
  );
};

export default GcpDashboard;
