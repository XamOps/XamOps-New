import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import type { BudgetSummary, BudgetStatus, BudgetAlert, DailySpendEntry } from '../../types/aiops';

Chart.register(...registerables);

interface BudgetOverviewProps {
  summary: BudgetSummary | null;
  loading: boolean;
  onEdit: (budgetId: number) => void;
  onDelete: (budgetId: number) => void;
  onAdd: () => void;
  onDismissAlert: (alertId: number) => void;
  onToggleStatus?: (budgetId: number, newStatus: string) => void;
  dailySpend?: Record<number, DailySpendEntry[]>;
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(2);
}

function getProgressColor(pct: number): string {
  if (pct >= 100) return 'bg-red-600';
  if (pct >= 80) return 'bg-amber-500';
  if (pct >= 50) return 'bg-yellow-400';
  return 'bg-emerald-500';
}

function getStatusBadge(status: string, usagePct: number) {
  if (usagePct >= 100) return { label: 'EXCEEDED', cls: 'bg-red-100 text-red-700' };
  if (status === 'PAUSED') return { label: 'PAUSED', cls: 'bg-gray-100 text-gray-600' };
  if (usagePct >= 80) return { label: 'WARNING', cls: 'bg-amber-100 text-amber-700' };
  return { label: 'ACTIVE', cls: 'bg-emerald-100 text-emerald-700' };
}

function formatTimestamp(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

const BudgetOverview: React.FC<BudgetOverviewProps> = ({
  summary, loading, onEdit, onDelete, onAdd, onDismissAlert, onToggleStatus, dailySpend,
}) => {
  if (loading) {
    return (
      <div className="space-y-6">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-28" />
          ))}
        </div>
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-48" />
      </div>
    );
  }

  if (!summary) return null;

  const kpis = [
    { label: 'Total Budget', value: formatCurrency(summary.totalBudgetUsd), icon: 'fas fa-wallet', gradient: 'from-green-500 to-emerald-600' },
    { label: 'Current Spend', value: formatCurrency(summary.totalSpendUsd), icon: 'fas fa-credit-card', gradient: 'from-blue-500 to-indigo-600' },
    { label: 'Usage', value: summary.overallUsagePct.toFixed(1) + '%', icon: 'fas fa-tachometer-alt', gradient: summary.overallUsagePct >= 80 ? 'from-red-500 to-rose-600' : summary.overallUsagePct >= 50 ? 'from-amber-500 to-orange-600' : 'from-emerald-500 to-green-600' },
    { label: 'Active / Alerts', value: `${summary.activeBudgetCount} / ${summary.alertsThisMonth}`, icon: 'fas fa-bell', gradient: 'from-purple-500 to-violet-600' },
  ];

  return (
    <div className="space-y-6">
      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {kpis.map(kpi => (
          <div key={kpi.label} className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">{kpi.label}</span>
              <div className={`w-8 h-8 bg-gradient-to-br ${kpi.gradient} rounded-lg flex items-center justify-center`}>
                <i className={`${kpi.icon} text-white text-xs`} />
              </div>
            </div>
            <p className="text-2xl font-bold text-gray-900">{kpi.value}</p>
          </div>
        ))}
      </div>

      {/* Budget Status Cards */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-shield-alt text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Budget Controls</h3>
            <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
              {summary.budgets.length} budgets
            </span>
          </div>
          <button
            onClick={onAdd}
            className="flex items-center gap-2 px-4 py-2 text-xs font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all shadow-sm"
          >
            <i className="fas fa-plus" />
            Add Budget
          </button>
        </div>

        {summary.budgets.length === 0 ? (
          <div className="p-8 text-center">
            <div className="w-12 h-12 bg-indigo-50 rounded-xl flex items-center justify-center mx-auto mb-3">
              <i className="fas fa-wallet text-indigo-400 text-lg" />
            </div>
            <h4 className="text-sm font-semibold text-gray-700 mb-1">No Budgets Set</h4>
            <p className="text-xs text-gray-400 max-w-sm mx-auto">
              Create a budget to set spending limits on your LLM providers.
              Get alerts at custom thresholds and optionally auto-disable when exceeded.
            </p>
          </div>
        ) : (
          <div className="divide-y divide-gray-100">
            {summary.budgets.map(b => (
              <BudgetCard
                key={b.budgetId}
                budget={b}
                onEdit={onEdit}
                onDelete={onDelete}
                onToggleStatus={onToggleStatus}
                sparklineData={dailySpend?.[b.budgetId]}
              />
            ))}
          </div>
        )}
      </div>

      {/* Recent Alerts */}
      {summary.recentAlerts.length > 0 && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-red-500 to-rose-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-bell text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Recent Alerts</h3>
            <span className="text-xs text-red-500 bg-red-50 px-2 py-0.5 rounded-full font-semibold">
              {summary.recentAlerts.length}
            </span>
          </div>
          <div className="divide-y divide-gray-100">
            {summary.recentAlerts.slice(0, 10).map(alert => (
              <AlertRow key={alert.id} alert={alert} onDismiss={onDismissAlert} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Sparkline ──────────────────────────────────────────────
function BudgetSparkline({ data }: { data: DailySpendEntry[] }) {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstance = useRef<Chart | null>(null);

  useEffect(() => {
    if (!chartRef.current || data.length === 0) return;
    if (chartInstance.current) chartInstance.current.destroy();

    const ctx = chartRef.current.getContext('2d');
    if (!ctx) return;

    chartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: data.map(d => d.date.slice(5)),
        datasets: [{
          data: data.map(d => d.spend),
          borderColor: '#6366f1',
          backgroundColor: 'rgba(99,102,241,0.1)',
          fill: true,
          tension: 0.3,
          borderWidth: 1.5,
          pointRadius: 0,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
        scales: {
          x: { display: false },
          y: { display: false, beginAtZero: true },
        },
      },
    });

    return () => { chartInstance.current?.destroy(); chartInstance.current = null; };
  }, [data]);

  if (data.length === 0) return null;

  return (
    <div className="h-10 w-28">
      <canvas ref={chartRef} />
    </div>
  );
}

// ─── Budget Card ─────────────────────────────────────────────
function BudgetCard({
  budget, onEdit, onDelete, onToggleStatus, sparklineData,
}: {
  budget: BudgetStatus;
  onEdit: (id: number) => void;
  onDelete: (id: number) => void;
  onToggleStatus?: (id: number, newStatus: string) => void;
  sparklineData?: DailySpendEntry[];
}) {
  const badge = getStatusBadge(budget.status, budget.usagePct);
  const pctClamped = Math.min(budget.usagePct, 100);
  const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate();

  return (
    <div className="px-6 py-4 hover:bg-gray-50/50 transition-colors">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <span className="text-sm font-semibold text-gray-900">{budget.budgetName}</span>
          <span className="text-xs text-gray-400">
            {budget.providerName || 'All Providers'}
          </span>
          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold ${badge.cls}`}>
            {badge.label}
          </span>
          {budget.autoDisable && (
            <span className="inline-flex items-center gap-1 text-[10px] text-indigo-500" title="Auto-disable on exceed">
              <i className="fas fa-shield-alt" /> Auto-block
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {onToggleStatus && (
            <button
              onClick={() => onToggleStatus(budget.budgetId, budget.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE')}
              className={`text-xs px-2 py-0.5 rounded-md transition-colors ${
                budget.status === 'ACTIVE'
                  ? 'text-amber-600 hover:bg-amber-50'
                  : 'text-emerald-600 hover:bg-emerald-50'
              }`}
              title={budget.status === 'ACTIVE' ? 'Pause budget' : 'Resume budget'}
            >
              <i className={`fas ${budget.status === 'ACTIVE' ? 'fa-pause' : 'fa-play'} mr-1`} />
              {budget.status === 'ACTIVE' ? 'Pause' : 'Resume'}
            </button>
          )}
          <button onClick={() => onEdit(budget.budgetId)} className="text-gray-400 hover:text-indigo-600 text-xs transition-colors" title="Edit">
            <i className="fas fa-edit" />
          </button>
          <button
            onClick={() => {
              if (window.confirm(`Delete budget "${budget.budgetName}"?`)) onDelete(budget.budgetId);
            }}
            className="text-gray-400 hover:text-red-600 text-xs transition-colors"
            title="Delete"
          >
            <i className="fas fa-trash" />
          </button>
        </div>
      </div>

      {/* Progress bar */}
      <div className="flex items-center gap-4">
        <div className="flex-1">
          <div className="relative w-full bg-gray-100 rounded-full h-3">
            {budget.thresholds.map(t => (
              <div
                key={t}
                className="absolute top-0 h-3 w-px bg-gray-400/50"
                style={{ left: `${Math.min(t, 100)}%` }}
                title={`${t}% threshold`}
              />
            ))}
            <div
              className={`h-3 rounded-full transition-all ${getProgressColor(budget.usagePct)}`}
              style={{ width: `${pctClamped}%` }}
            />
          </div>
        </div>
        <div className="text-right min-w-[140px]">
          <span className="text-sm font-bold text-gray-900">{formatCurrency(budget.currentSpendUsd)}</span>
          <span className="text-xs text-gray-400"> / {formatCurrency(budget.monthlyLimitUsd)}</span>
          <span className={`ml-2 text-xs font-semibold ${budget.usagePct >= 80 ? 'text-red-600' : 'text-gray-500'}`}>
            {budget.usagePct.toFixed(1)}%
          </span>
        </div>
      </div>

      {/* Forecast + Sparkline row */}
      <div className="flex items-center justify-between mt-2">
        <div className="flex items-center gap-4 text-[11px] text-gray-500">
          {budget.projectedSpend > 0 && (
            <span>
              <i className="fas fa-chart-line mr-1 text-indigo-400" />
              Projected: <strong className={budget.projectedSpend > budget.monthlyLimitUsd ? 'text-red-600' : 'text-gray-700'}>
                {formatCurrency(budget.projectedSpend)}
              </strong> by month end
            </span>
          )}
          {budget.estimatedRunoutDay > 0 && budget.estimatedRunoutDay <= daysInMonth && budget.usagePct < 100 && (
            <span className="text-amber-600">
              <i className="fas fa-exclamation-triangle mr-1" />
              Limit hit ~Day {budget.estimatedRunoutDay}
            </span>
          )}
        </div>
        {sparklineData && sparklineData.length > 0 && (
          <BudgetSparkline data={sparklineData} />
        )}
      </div>
    </div>
  );
}

// ─── Alert Row ───────────────────────────────────────────────
function AlertRow({ alert, onDismiss }: { alert: BudgetAlert; onDismiss: (id: number) => void }) {
  const thresholdColor = alert.thresholdPct >= 100 ? 'bg-red-100 text-red-700'
    : alert.thresholdPct >= 80 ? 'bg-amber-100 text-amber-700'
    : 'bg-blue-100 text-blue-700';

  return (
    <div className={`px-6 py-3 flex items-center justify-between ${alert.dismissed ? 'opacity-50' : ''} hover:bg-red-50/30 transition-colors`}>
      <div className="flex items-center gap-4">
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold ${thresholdColor}`}>
          {alert.thresholdPct}%
        </span>
        <div>
          <p className="text-sm font-medium text-gray-900">
            {alert.budgetName} — {alert.providerName || 'Global'}
          </p>
          <p className="text-xs text-gray-500">
            Spend: {formatCurrency(alert.actualSpendUsd)} / {formatCurrency(alert.budgetLimitUsd)} — {formatTimestamp(alert.triggeredAt)}
          </p>
        </div>
      </div>
      {!alert.dismissed && (
        <button
          onClick={() => onDismiss(alert.id)}
          className="text-xs text-gray-400 hover:text-gray-600 transition-colors"
        >
          Dismiss
        </button>
      )}
      {alert.dismissed && (
        <span className="text-xs text-emerald-500"><i className="fas fa-check mr-1" />Dismissed</span>
      )}
    </div>
  );
}

export default BudgetOverview;
