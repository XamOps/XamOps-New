import React, { useEffect, useRef, useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import Chart from 'chart.js/auto';
import type {
  LlmKpis, AllTimeMetrics, CostTrend, ProviderCostBreakdown, ModelUsageStat,
  CostAnomaly, InsightsSummary, RoutingStats, TeamAnalyticsSummary,
  ModelDailyAnalysis, PagedResponse, RequestLogEntry,
} from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

// ─── Helpers ──────────────────────────────────────────────────

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  return '$' + value.toFixed(2);
}

function formatNumber(value: number): string {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M';
  if (value >= 1_000) return (value / 1_000).toFixed(1) + 'k';
  return value.toLocaleString();
}

function formatLatency(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return Math.round(ms) + 'ms';
}

function timeAgo(ts: string): string {
  const diff = Date.now() - new Date(ts).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

const MODEL_COLORS = [
  '#6366f1', '#ec4899', '#14b8a6', '#f59e0b', '#ef4444',
  '#8b5cf6', '#06b6d4', '#84cc16', '#f97316', '#64748b',
  '#a855f7', '#10b981', '#e11d48', '#0ea5e9', '#d946ef',
];

// ─── Props ────────────────────────────────────────────────────

interface LLMDashboardTabProps {
  kpis: LlmKpis | null;
  allTimeMetrics: AllTimeMetrics | null;
  costTrend: CostTrend | null;
  providerBreakdown: ProviderCostBreakdown[];
  modelUsageTable: ModelUsageStat[];
  anomalies: CostAnomaly[];
  // Enhanced dashboard data
  dashInsights: InsightsSummary | null;
  dashRoutingStats: RoutingStats | null;
  dashTeamData: TeamAnalyticsSummary | null;
  modelAnalysis: ModelDailyAnalysis | null;
  dashRecentLogs: PagedResponse<RequestLogEntry> | null;
  // Loading states
  kpisLoading: boolean;
  chartsLoading: boolean;
}

// ─── Alert Ticker ─────────────────────────────────────────────

function AlertTicker({ insights }: { insights: InsightsSummary | null }) {
  const items = insights?.insights ?? [];
  if (items.length === 0) return null;

  return (
    <div className="bg-indigo-600 rounded-xl px-4 py-2.5 shadow-sm overflow-hidden">
      <div className="flex items-center gap-3">
        <div className="flex-shrink-0 flex items-center gap-2">
          <Icon icon="mdi:lightning-bolt" width={14} color="#fde047" />
          <span className="text-white text-xs font-bold uppercase tracking-wider">AI Insights</span>
          <InfoTooltip placement="bottom" iconClass="text-white/60 hover:text-white/90">Auto-generated insights ticker. Hover to pause scrolling.</InfoTooltip>
          <span className="bg-white/20 text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full">
            {items.length}
          </span>
        </div>
        <div className="flex-1 overflow-hidden">
          <div className="flex animate-scroll-left gap-8 whitespace-nowrap">
            {items.map((item, i) => (
              <span key={i} className="inline-flex items-center gap-2 text-white/90 text-xs">
                <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                  item.severity === 'ACTIONABLE' ? 'bg-yellow-400' :
                  item.severity === 'WARNING' ? 'bg-orange-400' : 'bg-blue-300'
                }`} />
                {item.title}
                {item.impact && <span className="text-white/60">| {item.impact}</span>}
              </span>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Optimizer Savings KPIs ───────────────────────────────────

function OptimizerSavings({ stats }: { stats: RoutingStats | null }) {
  if (!stats) return null;

  const cards = [
    { label: 'Requests Optimized', value: formatNumber(stats.totalRoutedRequests), icon: 'mdi:directions-fork', bgCls: 'bg-emerald-50 border border-emerald-100', iconColor: '#059669', tip: 'Requests rerouted to cheaper models via routing rules' },
    { label: 'Savings This Period', value: formatCurrency(stats.estimatedSavingsUsd), icon: 'mdi:piggy-bank-outline', bgCls: 'bg-blue-50 border border-blue-100', iconColor: '#2563EB', tip: 'Estimated cost savings from model routing optimization' },
    { label: 'Actual Spend (Routed)', value: formatCurrency(stats.actualSpendUsd), icon: 'mdi:receipt-outline', bgCls: 'bg-purple-50 border border-purple-100', iconColor: '#7c3aed', tip: 'Real cost of requests after being routed to cheaper models' },
    { label: 'Active Rules', value: String(stats.rules.filter(r => r.enabled).length), icon: 'mdi:check-circle-outline', bgCls: 'bg-teal-50 border border-teal-100', iconColor: '#0d9488', tip: 'Number of currently enabled routing rules' },
  ];

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-emerald-50 border border-emerald-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:auto-fix" width={16} color="#059669" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">AI Optimizer Savings</h3>
        <InfoTooltip placement="right">Savings from routing rules that redirect requests to cheaper alternative models.</InfoTooltip>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 p-6">
        {cards.map(c => (
          <div key={c.label} className="text-center">
            <div className={`w-10 h-10 mx-auto mb-2 ${c.bgCls} rounded-xl flex items-center justify-center`}>
              <Icon icon={c.icon} width={18} color={c.iconColor} />
            </div>
            <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{c.value}</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.5)] mt-0.5 inline-flex items-center gap-1">{c.label} <InfoTooltip placement="bottom">{c.tip}</InfoTooltip></p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Usage Overview KPIs ──────────────────────────────────────

function UsageOverviewKPIs({ kpis, allTime }: { kpis: LlmKpis; allTime: AllTimeMetrics | null }) {
  const cards = [
    {
      label: 'Requests', value: formatNumber(kpis.requestsThisMonth),
      sub: `${formatNumber(kpis.requestsToday)} today`,
      icon: 'mdi:send-outline', accent: 'border-purple-500',
      trend: { pct: kpis.todayVsYesterdayPct, label: 'vs yesterday' },
      tip: 'Total API requests for the selected period. Trend compares today vs yesterday.',
    },
    {
      label: 'Tokens Used', value: formatNumber(kpis.inputTokens + kpis.outputTokens),
      sub: `In: ${formatNumber(kpis.inputTokens)} / Out: ${formatNumber(kpis.outputTokens)}`,
      icon: 'mdi:circle-multiple-outline', accent: 'border-teal-500',
      tip: 'Input tokens = prompt tokens sent. Output tokens = completion tokens received.',
    },
    {
      label: 'Cost', value: formatCurrency(kpis.totalSpend),
      icon: 'mdi:currency-usd', accent: 'border-blue-500',
      tip: 'Total estimated cost based on model pricing for the selected period.',
    },
    {
      label: 'All Time Tokens', value: allTime ? formatNumber(allTime.allTimeTokens) : '-',
      icon: 'mdi:database-outline', accent: 'border-[rgba(0,0,0,0.3)]',
      tip: 'Cumulative token usage across all providers since first request.',
    },
    {
      label: 'All Time Cost', value: allTime ? formatCurrency(allTime.allTimeCost) : '-',
      icon: 'mdi:chart-bar', accent: 'border-[rgba(0,0,0,0.3)]',
      tip: 'Cumulative spend across all providers since first request.',
    },
  ];

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:chart-line" width={16} color="#4F46E5" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Usage Overview</h3>
        <InfoTooltip placement="right">Key metrics summarizing LLM API usage for the selected time period.</InfoTooltip>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4 p-6">
        {cards.map(c => (
          <div key={c.label} className={`border-l-4 ${c.accent} pl-4`}>
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold uppercase tracking-wider inline-flex items-center gap-1">{c.label} <InfoTooltip placement="bottom">{c.tip}</InfoTooltip></p>
            <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }} className="mt-1">{c.value}</p>
            {'sub' in c && c.sub && <p className="text-[10px] text-[rgba(0,0,0,0.5)] mt-0.5">{c.sub}</p>}
            {'trend' in c && c.trend && (
              <span className={`inline-flex items-center mt-1 text-[10px] font-semibold px-1.5 py-0.5 rounded-full ${
                c.trend.pct >= 0 ? 'bg-red-50 text-red-600' : 'bg-green-50 text-green-600'
              }`}>
                <Icon icon={c.trend.pct >= 0 ? 'mdi:arrow-up' : 'mdi:arrow-down'} width={10} className="inline mr-0.5" />
                {Math.abs(c.trend.pct).toFixed(1)}% {c.trend.label}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Cost by Provider (Horizontal Bar) ────────────────────────

function CostByProviderChart({ data }: { data: ProviderCostBreakdown[] }) {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstance = useRef<Chart<'bar'> | null>(null);

  const totalCost = useMemo(() => data.reduce((sum, d) => sum + d.cost, 0), [data]);

  useEffect(() => {
    if (!chartRef.current || data.length === 0) return;
    if (chartInstance.current) chartInstance.current.destroy();

    const ctx = chartRef.current.getContext('2d');
    if (!ctx) return;

    const colors = data.map(d => PROVIDER_COLORS[d.provider] || '#94a3b8');

    chartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: data.map(d => d.provider),
        datasets: [{
          data: data.map(d => d.cost),
          backgroundColor: colors,
          borderRadius: 6,
          barThickness: 28,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y',
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 12,
            cornerRadius: 8,
            callbacks: {
              label: (ctx) => {
                const pct = totalCost > 0 ? ((ctx.parsed.x / totalCost) * 100).toFixed(1) : '0';
                return `$${ctx.parsed.x.toFixed(4)} (${pct}%)`;
              },
            },
          },
        },
        scales: {
          x: {
            beginAtZero: true,
            grid: { color: 'rgba(226, 232, 240, 0.5)' },
            ticks: { callback: (v) => '$' + Number(v).toFixed(2), font: { size: 10 }, color: '#94a3b8' },
          },
          y: {
            grid: { display: false },
            ticks: { font: { size: 12, weight: 'bold' as const }, color: '#334155' },
          },
        },
      },
    });

    return () => { chartInstance.current?.destroy(); };
  }, [data, totalCost]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-purple-50 border border-purple-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:chart-bar" width={16} color="#7c3aed" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cost by Provider</h3>
        <InfoTooltip placement="right">Cost distribution across your configured LLM providers for the selected period.</InfoTooltip>
      </div>
      <div className="p-6">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2" style={{ height: Math.max(160, data.length * 50) }}>
            {data.length > 0 ? (
              <canvas ref={chartRef} />
            ) : (
              <div className="flex items-center justify-center h-full text-[rgba(0,0,0,0.4)] text-sm">
                No provider data yet
              </div>
            )}
          </div>
          <div className="space-y-3">
            {data.map(d => {
              const pct = totalCost > 0 ? ((d.cost / totalCost) * 100) : 0;
              return (
                <div key={d.provider} className="flex items-center gap-3 p-2 rounded-lg hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <span className="w-3 h-3 rounded-full flex-shrink-0"
                    style={{ backgroundColor: PROVIDER_COLORS[d.provider] || '#94a3b8' }} />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">{d.provider}</p>
                    <p className="text-[10px] text-[rgba(0,0,0,0.5)]">
                      {formatCurrency(d.cost)} &middot; {pct.toFixed(1)}% &middot; {formatNumber(d.requestCount)} reqs
                    </p>
                  </div>
                  <div className="w-16 bg-[rgba(0,0,0,0.06)] rounded-full h-1.5">
                    <div className="h-1.5 rounded-full" style={{
                      width: `${pct}%`,
                      backgroundColor: PROVIDER_COLORS[d.provider] || '#94a3b8',
                    }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Cost Trends with Summary Header ──────────────────────────

function CostTrendsSection({ costTrend, kpis }: { costTrend: CostTrend; kpis: LlmKpis }) {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstance = useRef<Chart | null>(null);

  useEffect(() => {
    if (!chartRef.current || !costTrend) return;
    if (chartInstance.current) chartInstance.current.destroy();

    const ctx = chartRef.current.getContext('2d');
    if (!ctx) return;

    const gradient = ctx.createLinearGradient(0, 0, 0, 300);
    gradient.addColorStop(0, 'rgba(99, 102, 241, 0.3)');
    gradient.addColorStop(1, 'rgba(99, 102, 241, 0.0)');

    chartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: costTrend.labels,
        datasets: [{
          label: 'Daily Cost ($)',
          data: costTrend.costs,
          backgroundColor: gradient,
          borderColor: '#6366f1',
          borderWidth: 2.5,
          tension: 0.4,
          fill: true,
          pointRadius: 3,
          pointBackgroundColor: '#6366f1',
          pointHoverRadius: 6,
          pointHoverBackgroundColor: '#4f46e5',
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 12,
            cornerRadius: 8,
            callbacks: { label: (ctx) => `Cost: $${ctx.parsed.y.toFixed(4)}` },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226, 232, 240, 0.5)' },
            ticks: { callback: (v) => '$' + Number(v).toFixed(2), font: { size: 11 }, color: '#94a3b8' },
          },
          x: {
            grid: { display: false },
            ticks: { maxRotation: 45, font: { size: 10 }, color: '#94a3b8' },
          },
        },
      },
    });

    return () => { chartInstance.current?.destroy(); };
  }, [costTrend]);

  const totalSpend = costTrend.costs.reduce((a, b) => a + b, 0);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
            <Icon icon="mdi:chart-areaspline" width={16} color="#4F46E5" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cost Trends</h3>
          <InfoTooltip placement="right">Daily cost over time for the selected period.</InfoTooltip>
        </div>
        <div className="flex items-center gap-6">
          <div className="text-right">
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase tracking-wider inline-flex items-center gap-1">Total Spend <InfoTooltip placement="bottom">Sum of all daily costs in the selected period.</InfoTooltip></p>
            <p className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{formatCurrency(totalSpend)}</p>
          </div>
          <div className="text-right">
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase tracking-wider inline-flex items-center gap-1">Total Requests <InfoTooltip placement="bottom">Total API requests made in the selected period.</InfoTooltip></p>
            <p className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{formatNumber(kpis.requestsThisMonth)}</p>
          </div>
          <div className="text-right">
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase tracking-wider inline-flex items-center gap-1">Total Tokens <InfoTooltip placement="bottom">Sum of input + output tokens in the selected period.</InfoTooltip></p>
            <p className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{formatNumber(kpis.inputTokens + kpis.outputTokens)}</p>
          </div>
        </div>
      </div>
      <div className="p-6">
        <div className="h-64">
          {costTrend.labels.length > 0 ? (
            <canvas ref={chartRef} />
          ) : (
            <div className="flex items-center justify-center h-full text-[rgba(0,0,0,0.4)] text-sm">
              <div className="text-center">
                <Icon icon="mdi:chart-line" width={40} color="#d1d5db" className="mb-2" />
                <p>No cost data yet</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Model Usage Analysis ─────────────────────────────────────

function ModelUsageAnalysis({ data }: { data: ModelDailyAnalysis | null }) {
  const costChartRef = useRef<HTMLCanvasElement>(null);
  const tokensChartRef = useRef<HTMLCanvasElement>(null);
  const costChartInstance = useRef<Chart | null>(null);
  const tokensChartInstance = useRef<Chart | null>(null);
  const [selectedModels, setSelectedModels] = useState<Set<string>>(new Set());

  // Initialize selected models when data loads
  useEffect(() => {
    if (data) {
      setSelectedModels(new Set(data.models));
    }
  }, [data]);

  const toggleModel = (model: string) => {
    setSelectedModels(prev => {
      const next = new Set(prev);
      if (next.has(model)) next.delete(model);
      else next.add(model);
      return next;
    });
  };

  const selectAll = () => data && setSelectedModels(new Set(data.models));
  const clearAll = () => setSelectedModels(new Set());

  // Cost chart
  useEffect(() => {
    if (!costChartRef.current || !data || data.labels.length === 0) return;
    if (costChartInstance.current) costChartInstance.current.destroy();

    const ctx = costChartRef.current.getContext('2d');
    if (!ctx) return;

    const datasets = data.models
      .filter(m => selectedModels.has(m))
      .map((model, idx) => ({
        label: model,
        data: data.costByModel[model] || [],
        borderColor: MODEL_COLORS[idx % MODEL_COLORS.length],
        backgroundColor: 'transparent',
        borderWidth: 2,
        tension: 0.3,
        pointRadius: 2,
        pointHoverRadius: 5,
      }));

    costChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: { labels: data.labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 10,
            cornerRadius: 8,
            callbacks: { label: (ctx) => `${ctx.dataset.label}: $${ctx.parsed.y.toFixed(4)}` },
          },
        },
        scales: {
          y: { beginAtZero: true, grid: { color: 'rgba(226,232,240,0.5)' }, ticks: { callback: (v) => '$' + Number(v).toFixed(2), font: { size: 10 }, color: '#94a3b8' } },
          x: { grid: { display: false }, ticks: { maxRotation: 45, font: { size: 9 }, color: '#94a3b8' } },
        },
      },
    });

    return () => { costChartInstance.current?.destroy(); };
  }, [data, selectedModels]);

  // Tokens chart
  useEffect(() => {
    if (!tokensChartRef.current || !data || data.labels.length === 0) return;
    if (tokensChartInstance.current) tokensChartInstance.current.destroy();

    const ctx = tokensChartRef.current.getContext('2d');
    if (!ctx) return;

    const datasets = data.models
      .filter(m => selectedModels.has(m))
      .map((model, idx) => ({
        label: model,
        data: data.tokensByModel[model] || [],
        borderColor: MODEL_COLORS[idx % MODEL_COLORS.length],
        backgroundColor: 'transparent',
        borderWidth: 2,
        tension: 0.3,
        pointRadius: 2,
        pointHoverRadius: 5,
      }));

    tokensChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: { labels: data.labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 10,
            cornerRadius: 8,
            callbacks: { label: (ctx) => `${ctx.dataset.label}: ${formatNumber(ctx.parsed.y)}` },
          },
        },
        scales: {
          y: { beginAtZero: true, grid: { color: 'rgba(226,232,240,0.5)' }, ticks: { callback: (v) => formatNumber(Number(v)), font: { size: 10 }, color: '#94a3b8' } },
          x: { grid: { display: false }, ticks: { maxRotation: 45, font: { size: 9 }, color: '#94a3b8' } },
        },
      },
    });

    return () => { tokensChartInstance.current?.destroy(); };
  }, [data, selectedModels]);

  if (!data || data.models.length === 0) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-pink-50 border border-pink-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:layers-outline" width={16} color="#db2777" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Model Usage Analysis</h3>
        <InfoTooltip placement="right">Per-model cost and token trends over time. Use checkboxes to filter models.</InfoTooltip>
      </div>
      <div className="p-6">
        {/* Model checkboxes */}
        <div className="flex items-center gap-2 flex-wrap mb-4">
          <button onClick={selectAll} className="text-[10px] font-semibold text-indigo-600 hover:text-indigo-800 px-2 py-1 border border-indigo-200 rounded-md">
            Select All
          </button>
          <button onClick={clearAll} className="text-[10px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.7)] px-2 py-1 border border-[rgba(0,0,0,0.12)] rounded-md">
            Clear All
          </button>
          <span className="w-px h-4 bg-[rgba(0,0,0,0.08)] mx-1" />
          {data.models.map((model, idx) => (
            <label key={model} className="inline-flex items-center gap-1.5 cursor-pointer text-xs px-2 py-1 rounded-md hover:bg-[rgba(0,0,0,0.025)] transition-colors">
              <input
                type="checkbox"
                checked={selectedModels.has(model)}
                onChange={() => toggleModel(model)}
                className="w-3 h-3 rounded border-[rgba(0,0,0,0.12)] accent-indigo-600"
              />
              <span className="w-2 h-2 rounded-full" style={{ backgroundColor: MODEL_COLORS[idx % MODEL_COLORS.length] }} />
              <span className="text-[rgba(0,0,0,0.7)] truncate max-w-[120px]">{model}</span>
            </label>
          ))}
        </div>

        {/* Two side-by-side charts */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div>
            <p className="text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-2 inline-flex items-center gap-1">Cost Over Time <InfoTooltip placement="right">Daily estimated cost per model in USD.</InfoTooltip></p>
            <div className="h-52">
              <canvas ref={costChartRef} />
            </div>
          </div>
          <div>
            <p className="text-xs font-semibold text-[rgba(0,0,0,0.6)] mb-2 inline-flex items-center gap-1">Tokens Over Time <InfoTooltip placement="right">Daily token consumption per model (input + output).</InfoTooltip></p>
            <div className="h-52">
              <canvas ref={tokensChartRef} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Cost by Organization ─────────────────────────────────────

function CostByOrganization({ data }: { data: TeamAnalyticsSummary | null }) {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstance = useRef<Chart<'bar'> | null>(null);

  useEffect(() => {
    if (!chartRef.current || !data || !data.costTrend || data.costTrend.labels.length === 0) return;
    if (chartInstance.current) chartInstance.current.destroy();

    const ctx = chartRef.current.getContext('2d');
    if (!ctx) return;

    const teamNames = Object.keys(data.costTrend.seriesByTeam);
    const datasets = teamNames.map((team, idx) => ({
      label: team,
      data: data.costTrend.seriesByTeam[team],
      backgroundColor: MODEL_COLORS[idx % MODEL_COLORS.length],
      borderRadius: 4,
    }));

    chartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: { labels: data.costTrend.labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { usePointStyle: true, pointStyle: 'circle', padding: 12, font: { size: 10 }, color: '#64748b' },
          },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 10,
            cornerRadius: 8,
            callbacks: { label: (ctx) => `${ctx.dataset.label}: $${ctx.parsed.y.toFixed(4)}` },
          },
        },
        scales: {
          x: { stacked: true, grid: { display: false }, ticks: { maxRotation: 45, font: { size: 10 }, color: '#94a3b8' } },
          y: { stacked: true, beginAtZero: true, grid: { color: 'rgba(226,232,240,0.5)' }, ticks: { callback: (v) => '$' + Number(v).toFixed(2), font: { size: 10 }, color: '#94a3b8' } },
        },
      },
    });

    return () => { chartInstance.current?.destroy(); };
  }, [data]);

  if (!data || !data.costTrend || data.costTrend.labels.length === 0) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-amber-50 border border-amber-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:office-building-outline" width={16} color="#d97706" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cost by Organization / Agent</h3>
        <InfoTooltip placement="right">Stacked daily cost breakdown by agent or team. Agents are identified via X-XamOps-Agent header.</InfoTooltip>
      </div>
      <div className="p-6">
        <div className="h-64">
          <canvas ref={chartRef} />
        </div>
      </div>
    </div>
  );
}

// ─── Recent Requests Table ────────────────────────────────────

function RecentRequestsTable({ data }: { data: PagedResponse<RequestLogEntry> | null }) {
  if (!data || data.content.length === 0) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-cyan-50 border border-cyan-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:format-list-bulleted" width={16} color="#0891b2" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Recent Requests</h3>
        <InfoTooltip placement="right">Most recent API requests through the proxy.</InfoTooltip>
        <span className="text-[10px] text-[rgba(0,0,0,0.4)] ml-auto">Last {data.content.length} of {data.totalElements}</span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              <th className="px-4 py-3 text-left text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Time</th>
              <th className="px-4 py-3 text-left text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Provider</th>
              <th className="px-4 py-3 text-left text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Agent</th>
              <th className="px-4 py-3 text-left text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Model</th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider"><span className="inline-flex items-center gap-1">Tokens <InfoTooltip placement="bottom">Combined input + output tokens.</InfoTooltip></span></th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider"><span className="inline-flex items-center gap-1">Cost <InfoTooltip placement="bottom">Estimated cost based on model pricing.</InfoTooltip></span></th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider"><span className="inline-flex items-center gap-1">Latency <InfoTooltip placement="bottom">Round-trip response time from provider.</InfoTooltip></span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
            {data.content.map((log) => (
              <tr key={log.id} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                <td className="px-4 py-2.5 text-[rgba(0,0,0,0.6)] whitespace-nowrap">{timeAgo(log.timestamp)}</td>
                <td className="px-4 py-2.5">
                  <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold"
                    style={{
                      backgroundColor: (PROVIDER_COLORS[log.provider] || '#94a3b8') + '15',
                      color: PROVIDER_COLORS[log.provider] || '#64748b',
                    }}>
                    <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: PROVIDER_COLORS[log.provider] || '#94a3b8' }} />
                    {log.provider}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-[rgba(0,0,0,0.7)] font-medium">{log.agentName || '-'}</td>
                <td className="px-4 py-2.5 text-[rgba(0,0,0,0.6)] truncate max-w-[140px]" title={log.model}>{log.model}</td>
                <td className="px-4 py-2.5 text-right text-[rgba(0,0,0,0.6)] font-mono">{formatNumber(log.inputTokens + log.outputTokens)}</td>
                <td className="px-4 py-2.5 text-right font-semibold text-[rgba(0,0,0,0.88)] font-mono">${log.cost.toFixed(4)}</td>
                <td className="px-4 py-2.5 text-right text-[rgba(0,0,0,0.6)] font-mono">{formatLatency(log.latencyMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─── Anomaly Alerts (compact) ─────────────────────────────────

function AnomalyAlerts({ anomalies }: { anomalies: CostAnomaly[] }) {
  if (anomalies.length === 0) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-red-50 border border-red-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:alert-outline" width={16} color="#dc2626" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cost Anomalies</h3>
        <InfoTooltip placement="right">Days where actual cost significantly deviated from expected patterns.</InfoTooltip>
        <span className="text-xs text-red-500 bg-red-50 px-2 py-0.5 rounded-full font-semibold">
          {anomalies.length} detected
        </span>
      </div>
      <div className="divide-y divide-[rgba(0,0,0,0.06)]">
        {anomalies.map((a, idx) => (
          <div key={idx} className="px-6 py-3 flex items-center justify-between hover:bg-red-50/30 transition-colors">
            <div className="flex items-center gap-4">
              <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${
                a.severity === 'CRITICAL' ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-700'
              }`}>
                {a.severity}
              </span>
              <div>
                <p className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{a.provider} cost spike on {a.date}</p>
                <p className="text-xs text-[rgba(0,0,0,0.5)]">
                  Actual: ${a.actualCost.toFixed(4)} | Expected: ${a.expectedCost.toFixed(4)} | +{a.deviationPct.toFixed(0)}% deviation
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Main Component ───────────────────────────────────────────

const LLMDashboardTab: React.FC<LLMDashboardTabProps> = ({
  kpis, allTimeMetrics, costTrend, providerBreakdown, anomalies,
  dashInsights, dashRoutingStats, dashTeamData, modelAnalysis, dashRecentLogs,
  kpisLoading, chartsLoading,
}) => {
  if (kpisLoading) {
    return (
      <div className="space-y-6">
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-10" />
        <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-24" />
          ))}
        </div>
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-20" />
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-72" />
          <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-72" />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 1. Alert Ticker */}
      <AlertTicker insights={dashInsights} />

      {/* 2. AI Optimizer Savings */}
      <OptimizerSavings stats={dashRoutingStats} />

      {/* 3. Usage Overview KPIs */}
      {kpis && <UsageOverviewKPIs kpis={kpis} allTime={allTimeMetrics} />}

      {/* 4. Cost by Provider (Horizontal Bar) */}
      {!chartsLoading && <CostByProviderChart data={providerBreakdown} />}

      {/* 5. Cost Trends with Summary Header */}
      {!chartsLoading && costTrend && kpis && (
        <CostTrendsSection costTrend={costTrend} kpis={kpis} />
      )}

      {/* 6. Model Usage Analysis */}
      <ModelUsageAnalysis data={modelAnalysis} />

      {/* 7. Cost by Organization */}
      <CostByOrganization data={dashTeamData} />

      {/* 8. Recent Requests Table */}
      <RecentRequestsTable data={dashRecentLogs} />

      {/* 9. Anomaly Alerts */}
      <AnomalyAlerts anomalies={anomalies} />
    </div>
  );
};

export default LLMDashboardTab;
