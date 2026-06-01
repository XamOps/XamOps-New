import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import Chart from 'chart.js/auto';
import { useTopbar } from '../contexts/TopbarContext';
import { useAutoSpottingCache } from '../contexts/AutoSpottingCacheContext';
import {
  SavingsMetrics,
  SpotActivity,
  HistoricalSavings,
  AccountSavings,
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

const AutoSpottingAdminDashboardPage: React.FC = () => {
  const { setActions, clearActions } = useTopbar();
  const { getAdminData, setAdminData } = useAutoSpottingCache();

  const [metrics,          setMetrics]          = useState<SavingsMetrics | null>(null);
  const [recentActivity,   setRecentActivity]   = useState<SpotActivity[]>([]);
  const [historicalData,   setHistoricalData]   = useState<HistoricalSavings[]>([]);
  const [accountBreakdown, setAccountBreakdown] = useState<AccountSavings[]>([]);
  const [loading,          setLoading]          = useState(true);
  const [error,            setError]            = useState<string | null>(null);
  const [dateRange,        setDateRange]        = useState('7d');
  const [isInitialLoad,    setIsInitialLoad]    = useState(true);

  const savingsChartRef      = useRef<HTMLCanvasElement>(null);
  const accountChartRef      = useRef<HTMLCanvasElement>(null);
  const savingsChartInstance = useRef<Chart<'line'> | null>(null);
  const accountChartInstance = useRef<Chart<'doughnut'> | null>(null);

  const fetchDashboardData = useCallback(async (isRefresh = false) => {
    try {
      if (!isRefresh && !metrics) setLoading(true);
      setError(null);

      if (!isRefresh && isInitialLoad) {
        const cached = getAdminData(dateRange);
        if (cached) {
          setMetrics(cached.metrics);
          setRecentActivity(cached.recentActivity);
          setHistoricalData(cached.historicalData);
          setAccountBreakdown(cached.accountBreakdown);
          setLoading(false);
          setIsInitialLoad(false);
        }
      }

      const days = dateRange === '7d' ? 7 : dateRange === '30d' ? 30 : 90;
      const [metricsRes, activityRes, historicalRes, breakdownRes] = await Promise.all([
        fetch('/api/admin/autospotting/total-savings'),
        fetch('/api/admin/autospotting/recent-activity?limit=50'),
        fetch(`/api/admin/autospotting/historical-savings?days=${days}`),
        fetch('/api/admin/autospotting/account-breakdown'),
      ]);

      if (!metricsRes.ok || !activityRes.ok || !historicalRes.ok || !breakdownRes.ok) throw new Error('Failed to fetch dashboard data');

      const [metricsData, activityData, historicalDt, breakdownData] = await Promise.all([
        metricsRes.json(), activityRes.json(), historicalRes.json(), breakdownRes.json(),
      ]);

      setMetrics(metricsData);
      setRecentActivity(activityData);
      setHistoricalData(historicalDt);
      setAccountBreakdown(breakdownData);
      setAdminData(dateRange, { metrics: metricsData, recentActivity: activityData, historicalData: historicalDt, accountBreakdown: breakdownData });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load dashboard');
    } finally {
      setLoading(false);
      setIsInitialLoad(false);
    }
  }, [dateRange, metrics, isInitialLoad, getAdminData, setAdminData]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">AutoSpotting Admin</span>
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

  useEffect(() => { fetchDashboardData(); }, [dateRange]);

  useEffect(() => {
    if (!savingsChartRef.current || !historicalData.length) return;
    if (savingsChartInstance.current) savingsChartInstance.current.destroy();
    const ctx = savingsChartRef.current.getContext('2d');
    if (!ctx) return;
    savingsChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: historicalData.map((d) => new Date(d.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })),
        datasets: [{ label: 'Daily Savings', data: historicalData.map((d) => d.savings), borderColor: '#F59E0B', backgroundColor: 'rgba(245,158,11,0.08)', fill: true, tension: 0.4, pointRadius: 4, pointHoverRadius: 6, pointBackgroundColor: '#F59E0B', pointBorderColor: '#fff', pointBorderWidth: 2 }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false }, tooltip: { padding: 12, callbacks: { label: (c) => `Savings: $${c.parsed.y.toFixed(2)}` } } },
        scales: {
          x: { grid: { color: '#f3f4f6' }, ticks: { color: '#6b7280', font: { size: 11 } } },
          y: { grid: { color: '#f3f4f6' }, ticks: { color: '#6b7280', font: { size: 11 }, callback: (v) => `$${v}` } },
        },
      },
    });
    return () => { if (savingsChartInstance.current) savingsChartInstance.current.destroy(); };
  }, [historicalData]);

  useEffect(() => {
    if (!accountChartRef.current || !accountBreakdown.length) return;
    if (accountChartInstance.current) accountChartInstance.current.destroy();
    const ctx = accountChartRef.current.getContext('2d');
    if (!ctx) return;
    const colors = ['#F59E0B', '#FBBF24', '#8B5CF6', '#EC4899', '#10B981', '#3B82F6'];
    accountChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: accountBreakdown.map((a) => a.accountName),
        datasets: [{ data: accountBreakdown.map((a) => a.totalSavings), backgroundColor: colors.slice(0, accountBreakdown.length), borderColor: '#fff', borderWidth: 2 }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'right', labels: { color: '#374151', font: { size: 12 }, padding: 12, usePointStyle: true } },
          tooltip: { padding: 12, callbacks: { label: (c) => [`Savings: $${accountBreakdown[c.dataIndex].totalSavings.toFixed(2)}`, `Instances: ${accountBreakdown[c.dataIndex].instanceCount}`] } },
        },
      },
    });
    return () => { if (accountChartInstance.current) accountChartInstance.current.destroy(); };
  }, [accountBreakdown]);

  if (loading && !metrics) {
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
          <button onClick={() => fetchDashboardData(true)} className="flex items-center gap-2 px-5 py-2.5 bg-red-600 hover:bg-red-700 text-white text-[13px] font-semibold rounded-xl transition-colors mx-auto">
            <Icon icon="mdi:refresh" width={14} />Retry
          </button>
        </div>
      </main>
    );
  }

  const DATE_RANGES = [
    { key: '7d', label: 'Last 7 Days' },
    { key: '30d', label: 'Last 30 Days' },
    { key: '90d', label: 'Last 90 Days' },
  ];

  const COL = 'px-4 py-3.5 text-[12px]';
  const HEAD = 'px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.07em] text-[rgba(0,0,0,0.45)] bg-[rgba(0,0,0,0.02)]';

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      {/* Title + date range */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-[18px] font-bold text-[rgba(0,0,0,0.88)]">AutoSpotting Dashboard</h1>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-0.5">Monitor spot instance cost savings across all customer accounts</p>
        </div>
        <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1">
          {DATE_RANGES.map((r) => (
            <button
              key={r.key}
              onClick={() => setDateRange(r.key)}
              className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-all ${
                dateRange === r.key
                  ? 'bg-white shadow-sm border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)]'
                  : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Total Savings',         value: fmt(metrics?.totalSavings || 0),                          sub: 'All-time cost reduction',  icon: 'mdi:piggy-bank-outline', iconColor: '#D97706', iconBg: '#D9770614' },
          { label: 'Monthly Savings',       value: fmt(metrics?.monthlySavings || 0),                        sub: 'Current month',            icon: 'mdi:trending-up',        iconColor: '#16A34A', iconBg: '#16A34A14' },
          { label: 'Active Spot Instances', value: String(metrics?.activeSpotInstances || 0),                sub: 'Currently running',        icon: 'mdi:server-outline',     iconColor: '#8B5CF6', iconBg: '#8B5CF614' },
          { label: 'Conversion Rate',       value: `${(metrics?.conversionRate || 0).toFixed(1)}%`,          sub: 'On-demand → Spot',         icon: 'mdi:chart-bar',          iconColor: '#3B82F6', iconBg: '#3B82F614' },
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

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4">Savings Trend</h3>
          <div className="h-72"><canvas ref={savingsChartRef} /></div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)] mb-4">Savings by Account</h3>
          <div className="h-72"><canvas ref={accountChartRef} /></div>
        </div>
      </div>

      {/* Recent Activity Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
          <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Recent Activity</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)]">
                {['Timestamp', 'Account', 'Instance', 'Type', 'Event', 'Hourly Savings', 'Region'].map((h) => (
                  <th key={h} className={HEAD}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {recentActivity.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-6 py-10 text-center text-[13px] text-[rgba(0,0,0,0.4)]">No recent activity</td>
                </tr>
              ) : recentActivity.map((activity) => (
                <tr key={activity.id} className="hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                  <td className={`${COL} font-mono text-[11px] text-[rgba(0,0,0,0.55)]`}>{new Date(activity.timestamp).toLocaleString()}</td>
                  <td className={`${COL} text-[rgba(0,0,0,0.65)]`}>{activity.accountName}</td>
                  <td className={`${COL} font-mono text-[11px] text-[rgba(0,0,0,0.65)]`}>{activity.instanceId}</td>
                  <td className={`${COL} text-[rgba(0,0,0,0.65)]`}>{activity.instanceType} → {activity.spotInstanceType}</td>
                  <td className={COL}>
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${
                      activity.eventType === 'LAUNCH'
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-100'
                        : 'bg-red-50 text-red-600 border-red-100'
                    }`}>
                      {activity.eventType}
                    </span>
                  </td>
                  <td className={`${COL} font-bold text-amber-600`}>${activity.hourlySavings.toFixed(4)}/hr</td>
                  <td className={`${COL} text-[rgba(0,0,0,0.45)]`}>{activity.region}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
};

export default AutoSpottingAdminDashboardPage;
