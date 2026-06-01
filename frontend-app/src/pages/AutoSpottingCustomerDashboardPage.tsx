import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import Chart from 'chart.js/auto';
import { useTopbar } from '../contexts/TopbarContext';
import { useAccounts } from '../contexts/AccountContext';
import { useAutoSpottingCache } from '../contexts/AutoSpottingCacheContext';
import {
  CustomerSavings,
  SpotInstance,
  ActivityEvent,
  Recommendation,
} from '../types/autospotting';

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '26px',
  fontWeight: 400,
  lineHeight: '28px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const fmt = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(v);

const PRIORITY_BADGE: Record<string, string> = {
  high:   'bg-red-50 text-red-600 border-red-100',
  medium: 'bg-amber-50 text-amber-600 border-amber-100',
  low:    'bg-blue-50 text-blue-600 border-blue-100',
};

const AutoSpottingCustomerDashboardPage: React.FC = () => {
  const { setActions, clearActions } = useTopbar();
  const { selectedAccountDisplay }   = useAccounts();
  const { getCustomerData, setCustomerData } = useAutoSpottingCache();

  const selectedAccount =
    selectedAccountDisplay.id && selectedAccountDisplay.id !== 'No account selected'
      ? { accountId: selectedAccountDisplay.id, accountName: selectedAccountDisplay.name }
      : null;

  const [savings,         setSavings]         = useState<CustomerSavings | null>(null);
  const [spotInstances,   setSpotInstances]   = useState<SpotInstance[]>([]);
  const [recentActivity,  setRecentActivity]  = useState<ActivityEvent[]>([]);
  const [recommendations, setRecommendations] = useState<Recommendation[]>([]);
  const [loading,         setLoading]         = useState(true);
  const [error,           setError]           = useState<string | null>(null);
  const [isInitialLoad,   setIsInitialLoad]   = useState(true);

  const savingsChartRef      = useRef<HTMLCanvasElement>(null);
  const savingsChartInstance = useRef<Chart<'bar'> | null>(null);

  const fetchDashboardData = useCallback(async (isRefresh = false) => {
    if (!selectedAccount) return;
    try {
      if (!isRefresh && !savings) setLoading(true);
      setError(null);
      const accountId = selectedAccount.accountId;

      if (!isRefresh && isInitialLoad) {
        const cached = getCustomerData(accountId);
        if (cached) {
          setSavings(cached.savings);
          setSpotInstances(cached.spotInstances);
          setRecentActivity(cached.recentActivity);
          setRecommendations(cached.recommendations);
          setLoading(false);
          setIsInitialLoad(false);
        }
      }

      const [savingsRes, instancesRes, activityRes] = await Promise.all([
        fetch(`/api/autospotting/savings/${accountId}`),
        fetch(`/api/autospotting/instances/${accountId}`),
        fetch(`/api/autospotting/events/${accountId}?limit=20`),
      ]);

      if (!savingsRes.ok || !instancesRes.ok || !activityRes.ok) throw new Error('Failed to fetch AutoSpotting data');

      const [savingsData, instancesData, activityData] = await Promise.all([
        savingsRes.json(), instancesRes.json(), activityRes.json(),
      ]);

      const mappedActivity: ActivityEvent[] = activityData?.events
        ? activityData.events.map((e: any, i: number) => ({
            id: `${e.time}-${i}`,
            timestamp: e.timestamp || e.time,
            description: e.description || e.eventType || e.type,
            eventType: e.eventType || e.type,
            savings: e.savings || e.estimatedSavings,
            region: e.region,
            asgName: e.asgName,
          }))
        : [];

      const mockRecommendations: Recommendation[] = [
        { id: 1, title: 'Enable Spot on "payment-service"', description: 'Enabling Spot instances on this ASG could save $145/month.', potentialSavings: 145.00, priority: 'high' },
        { id: 2, title: 'Diversify instance types', description: 'Add m5.large to "frontend-cluster" to increase spot availability.', potentialSavings: 45.50, priority: 'medium' },
      ];

      setSavings(savingsData);
      setSpotInstances(instancesData);
      setRecentActivity(mappedActivity);
      setRecommendations(mockRecommendations);
      setCustomerData(accountId, { savings: savingsData, spotInstances: instancesData, recentActivity: mappedActivity, recommendations: mockRecommendations });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load dashboard');
    } finally {
      setLoading(false);
      setIsInitialLoad(false);
    }
  }, [selectedAccount, savings, isInitialLoad, getCustomerData, setCustomerData]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">AutoSpotting</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={() => fetchDashboardData(true)}
          className="flex items-center gap-1 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
        >
          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, fetchDashboardData]);

  useEffect(() => {
    if (selectedAccount) fetchDashboardData();
  }, [selectedAccount?.accountId]);

  useEffect(() => {
    if (!savingsChartRef.current || !savings) return;
    if (savingsChartInstance.current) savingsChartInstance.current.destroy();
    const ctx = savingsChartRef.current.getContext('2d');
    if (!ctx) return;
    const onDemandCost = savings.onDemandMonthlyCost > 0
      ? savings.onDemandMonthlyCost
      : (savings.actualMonthlySavings + savings.currentMonthlyCost);
    savingsChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: ['On-Demand', 'With Spot Instances'],
        datasets: [{ label: 'Monthly Cost', data: [onDemandCost, savings.currentMonthlyCost], backgroundColor: ['rgba(239,68,68,0.5)', 'rgba(16,185,129,0.5)'], borderColor: ['#EF4444', '#10B981'], borderWidth: 1.5 }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false }, tooltip: { padding: 12, callbacks: { label: (c) => `Cost: $${c.parsed.y.toFixed(2)}/month` } } },
        scales: {
          x: { grid: { color: '#f3f4f6' }, ticks: { color: '#6b7280', font: { size: 12 } } },
          y: { grid: { color: '#f3f4f6' }, ticks: { color: '#6b7280', font: { size: 12 }, callback: (v) => `$${v}` } },
        },
      },
    });
    return () => { if (savingsChartInstance.current) savingsChartInstance.current.destroy(); };
  }, [savings]);

  if (loading && !savings) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-5">
          {[...Array(4)].map((_, i) => <div key={i} className="h-28 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse" />)}
        </div>
        <div className="h-72 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse" />
      </main>
    );
  }

  if (error) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6">
        <div className="bg-red-50 border border-red-200 rounded-xl p-8 text-center max-w-md mx-auto mt-10">
          <Icon icon="mdi:alert-circle-outline" width={36} color="#DC2626" className="mx-auto mb-3" />
          <h3 className="text-[15px] font-bold text-red-800 mb-2">Error Loading Dashboard</h3>
          <p className="text-[13px] text-red-700 mb-4">{error}</p>
          <button
            onClick={() => fetchDashboardData(true)}
            className="flex items-center gap-2 px-5 py-2.5 bg-red-600 hover:bg-red-700 text-white text-[13px] font-semibold rounded-xl transition-colors mx-auto"
          >
            <Icon icon="mdi:refresh" width={14} />
            Retry
          </button>
        </div>
      </main>
    );
  }

  if (!selectedAccount) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center max-w-md mx-auto mt-10">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:aws" width={22} color="rgba(0,0,0,0.25)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select an AWS account to view AutoSpotting data</p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      {/* Page title */}
      <div>
        <h1 className="text-[18px] font-bold text-[rgba(0,0,0,0.88)]">AutoSpotting Dashboard</h1>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-0.5">
          Track your spot instance cost savings for <span className="text-amber-500 font-semibold">{selectedAccount.accountName}</span>
        </p>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Monthly Savings',   value: fmt(savings?.actualMonthlySavings || 0),                                       sub: 'This month',         icon: 'mdi:trending-up',          iconColor: '#16A34A', iconBg: '#16A34A14' },
          { label: 'Est. Annual Savings', value: fmt((savings?.actualMonthlySavings || 0) * 12),                              sub: 'Projected annual',   icon: 'mdi:piggy-bank-outline',   iconColor: '#6366F1', iconBg: '#6366F114' },
          { label: 'Enabled ASGs',      value: `${savings?.enabledAsgs || 0}/${savings?.totalAsgs || 0}`,                     sub: 'ASGs opted in',      icon: 'mdi:layers-outline',       iconColor: '#D97706', iconBg: '#D9770614' },
          { label: 'Potential Savings', value: fmt(savings?.potentialMonthlySavings || 0),                                    sub: 'Additional monthly', icon: 'mdi:chart-line',           iconColor: '#8B5CF6', iconBg: '#8B5CF614' },
        ].map((kpi) => (
          <div key={kpi.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
            <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
              <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
                <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z" />
              </svg>
            </div>
            <div className="relative z-10">
              <div className="flex items-start justify-between mb-2">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)]">{kpi.label}</p>
                <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: kpi.iconBg }}>
                  <Icon icon={kpi.icon} width={18} color={kpi.iconColor} />
                </div>
              </div>
              <p style={NUM_STYLE}>{kpi.value}</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">{kpi.sub}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        {/* Cost Comparison Chart */}
        <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-5 flex items-center gap-2">
            <span className="w-1 h-5 bg-indigo-500 rounded-full" />
            Cost Comparison
          </h3>
          <div className="h-56">
            <canvas ref={savingsChartRef} />
          </div>
        </div>

        {/* Active Spot Instances */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 flex flex-col">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center gap-2 flex-shrink-0">
            <span className="w-1 h-5 bg-amber-500 rounded-full" />
            Active Spot Instances
          </h3>
          <div className="overflow-y-auto flex-1">
            {spotInstances.filter((i) => i.lifecycle === 'spot').length > 0 ? (
              <div>
                {/* Header row */}
                <div className="grid grid-cols-3 gap-2 pb-2 border-b border-[rgba(0,0,0,0.06)] mb-1">
                  {['Instance ID', 'Type', 'Savings'].map((h) => (
                    <p key={h} className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)]">{h}</p>
                  ))}
                </div>
                {spotInstances.filter((i) => i.lifecycle === 'spot').map((inst) => (
                  <div key={inst.instanceId} className="grid grid-cols-3 gap-2 py-2 border-b border-[rgba(0,0,0,0.04)] last:border-0 hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                    <div>
                      <p className="text-[12px] font-mono text-[rgba(0,0,0,0.65)]">{inst.instanceId.substring(0, 10)}…</p>
                      <p className="text-[10px] text-[rgba(0,0,0,0.35)]">{inst.availabilityZone}</p>
                    </div>
                    <span className="inline-flex items-center h-fit px-2 py-0.5 bg-indigo-50 text-indigo-600 text-[10px] font-semibold rounded border border-indigo-100 font-mono">
                      {inst.instanceType}
                    </span>
                    <span className="text-[12px] font-bold text-emerald-600">
                      ${inst.estimatedHourlySavings?.toFixed(3) || '0.000'}/hr
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-center py-8">
                <Icon icon="mdi:server-off" width={28} color="rgba(0,0,0,0.2)" className="mx-auto mb-2" />
                <p className="text-[13px] text-[rgba(0,0,0,0.4)]">No active Spot instances found.</p>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Recent Activity */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center gap-2">
            <span className="w-1 h-5 bg-indigo-500 rounded-full" />
            Recent Activity
          </h3>
          <div className="space-y-3 max-h-72 overflow-y-auto">
            {recentActivity.length > 0 ? recentActivity.map((activity) => {
              const isLaunch = activity.eventType === 'LAUNCH' || activity.eventType?.includes('launched');
              return (
                <div key={activity.id} className="flex items-start gap-3 p-3 rounded-xl border border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${isLaunch ? 'bg-emerald-50' : 'bg-red-50'}`}>
                    <Icon icon={isLaunch ? 'mdi:trending-up' : 'mdi:close'} width={14} color={isLaunch ? '#16A34A' : '#DC2626'} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] truncate">{activity.description}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{new Date(activity.timestamp).toLocaleString()}</span>
                      {activity.region && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.45)]">
                          {activity.region}
                        </span>
                      )}
                    </div>
                  </div>
                  {activity.savings && (
                    <div className="text-right">
                      <span className="block text-[13px] font-bold text-emerald-600">+${activity.savings.toFixed(3)}</span>
                      <span className="text-[10px] text-[rgba(0,0,0,0.35)]">saved</span>
                    </div>
                  )}
                </div>
              );
            }) : (
              <div className="text-center py-8">
                <Icon icon="mdi:history" width={28} color="rgba(0,0,0,0.2)" className="mx-auto mb-2" />
                <p className="text-[13px] text-[rgba(0,0,0,0.4)]">No recent activity.</p>
              </div>
            )}
          </div>
        </div>

        {/* Recommendations */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center gap-2">
            <span className="w-1 h-5 bg-blue-500 rounded-full" />
            Optimization Recommendations
          </h3>
          <div className="space-y-3 max-h-72 overflow-y-auto">
            {recommendations.length === 0 ? (
              <div className="text-center py-8">
                <Icon icon="mdi:check-circle-outline" width={36} color="#16A34A" className="mx-auto mb-2" />
                <p className="text-[13px] text-[rgba(0,0,0,0.4)]">All optimized! No recommendations at this time.</p>
              </div>
            ) : recommendations.map((rec) => (
              <div key={rec.id} className="p-4 bg-[rgba(0,0,0,0.01)] border border-[rgba(0,0,0,0.06)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1">
                    <div className="flex items-center gap-2 mb-1">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${PRIORITY_BADGE[rec.priority] || PRIORITY_BADGE.low}`}>
                        {rec.priority.toUpperCase()}
                      </span>
                      <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{rec.title}</p>
                    </div>
                    <p className="text-[12px] text-[rgba(0,0,0,0.45)]">{rec.description}</p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <p className="text-[13px] font-bold text-emerald-600">+${rec.potentialSavings.toFixed(2)}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">potential</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </main>
  );
};

export default AutoSpottingCustomerDashboardPage;
