import React, { useEffect, useRef } from 'react';
import { Icon } from '@iconify/react';
import { Chart, registerables } from 'chart.js';
import type { HistoryDataPoint, HistorySummary } from '../../hooks/useSpotAutomationData';
import LegacyUnverifiedTag, { LEGACY_UNVERIFIED_LABEL } from './LegacyUnverifiedTag';

Chart.register(...registerables);

interface SpotAutomationChartProps {
  historyData: HistoryDataPoint[];
  historySummary: HistorySummary;
  timeRange: 'last24h' | 'last7d' | 'last30d';
  isLoading: boolean;
  onTimeRangeChange: (range: 'last24h' | 'last7d' | 'last30d') => void;
  onRefresh: () => void;
  currency?: string;
}

const SpotAutomationChart: React.FC<SpotAutomationChartProps> = ({
  historyData,
  historySummary,
  timeRange,
  isLoading,
  onTimeRangeChange,
  onRefresh,
  currency = 'USD',
}) => {
  const chartRef         = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart | null>(null);

  const formatCurrency = (value: number) =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);

  useEffect(() => {
    if (!chartRef.current || !historyData || historyData.length === 0) return;

    if (chartInstanceRef.current) chartInstanceRef.current.destroy();

    const sortedData = [...historyData].sort(
      (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
    );

    const labels = sortedData.map((d) => {
      const date = new Date(d.timestamp);
      if (timeRange === 'last24h') return date.toLocaleTimeString([], { hour: 'numeric', hour12: true });
      if (timeRange === 'last7d') return `${date.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${date.toLocaleTimeString([], { hour: 'numeric', hour12: true })}`;
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    });

    // A missing value is null, drawn as a gap: nothing was recorded, so nothing reads as $0.
    // Legacy amounts get their own grey series and never appear as actual savings.
    const hasLegacy = sortedData.some((d) => d.legacy);
    const legacyDataset = {
      label: LEGACY_UNVERIFIED_LABEL,
      data: sortedData.map((d) => (d.legacy ? (d.total_actual_savings ?? null) : null)),
      borderColor: '#9ca3af',
      backgroundColor: 'transparent',
      borderWidth: 2,
      borderDash: [3, 3],
      tension: 0.4,
      pointBackgroundColor: '#9ca3af',
      pointRadius: 3,
      pointHoverRadius: 5,
    };

    chartInstanceRef.current = new Chart(chartRef.current, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Actual Savings',
            data: sortedData.map((d) => (d.legacy ? null : (d.total_actual_savings ?? null))),
            borderColor: '#10b981',
            backgroundColor: 'rgba(16, 185, 129, 0.08)',
            borderWidth: 2.5,
            tension: 0.4,
            fill: true,
            pointBackgroundColor: '#10b981',
            pointRadius: 3,
            pointHoverRadius: 5,
          },
          {
            label: 'Potential Savings',
            data: sortedData.map((d) => d.total_potential_savings ?? null),
            borderColor: '#f59e0b',
            backgroundColor: 'transparent',
            borderWidth: 2.5,
            borderDash: [8, 4],
            tension: 0.4,
            pointBackgroundColor: '#f59e0b',
            pointRadius: 3,
            pointHoverRadius: 5,
          },
          ...(hasLegacy ? [legacyDataset] : []),
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'top',
            align: 'center',
            labels: { usePointStyle: true, boxWidth: 8, font: { size: 12 }, padding: 15 },
          },
          tooltip: {
            mode: 'index',
            intersect: false,
            backgroundColor: 'rgba(0,0,0,0.82)',
            padding: 12,
            cornerRadius: 8,
            callbacks: {
              label: (ctx) => `${ctx.dataset.label}: ${formatCurrency(ctx.parsed.y)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: '#f3f4f6' },
            ticks: { callback: (v) => `$${v}`, font: { size: 11 }, color: '#6b7280' },
          },
          x: {
            grid: { display: false },
            ticks: { font: { size: 11 }, color: '#6b7280', maxTicksLimit: 8 },
          },
        },
      },
    });

    return () => { if (chartInstanceRef.current) chartInstanceRef.current.destroy(); };
  }, [historyData, timeRange, currency]);

  const TIME_RANGES: { key: 'last24h' | 'last7d' | 'last30d'; label: string }[] = [
    { key: 'last24h', label: 'Last 24h' },
    { key: 'last7d',  label: 'Last 7d'  },
    { key: 'last30d', label: 'Last 30d' },
  ];

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <h3 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Savings Over Time</h3>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-0.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-lg p-0.5">
            {TIME_RANGES.map((r) => (
              <button
                key={r.key}
                onClick={() => onTimeRangeChange(r.key)}
                className={`px-3 py-1.5 rounded-md text-[11px] font-semibold transition-all ${
                  timeRange === r.key
                    ? 'bg-white shadow-sm border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.88)]'
                    : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
                }`}
              >
                {r.label}
              </button>
            ))}
          </div>
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="w-8 h-8 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.03)] hover:bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
          >
            <Icon icon="mdi:refresh" width={15} className={isLoading ? 'animate-spin' : ''} />
          </button>
        </div>
      </div>

      {/* Summary pills (legacy amounts are excluded from all three) */}
      <div className="grid grid-cols-3 gap-4 mb-5">
        {[
          { label: 'Period Savings', value: formatCurrency(historySummary.total_savings || 0), color: 'text-emerald-700', bg: 'bg-emerald-50 border-emerald-100' },
          { label: 'Avg Hourly',     value: `${formatCurrency(historySummary.average_hourly_savings || 0)}/hr`, color: 'text-blue-700', bg: 'bg-blue-50 border-blue-100' },
          { label: 'Peak Savings',   value: `${formatCurrency(historySummary.peak_savings || 0)}/hr`, color: 'text-indigo-700', bg: 'bg-indigo-50 border-indigo-100' },
        ].map((item) => (
          <div key={item.label} className={`${item.bg} border rounded-xl p-4`}>
            <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)] mb-1">{item.label}</p>
            <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{item.value}</p>
          </div>
        ))}
      </div>

      {(historySummary.legacy_total_savings || 0) > 0 && (
        <div data-testid="history-legacy-total" className="-mt-2 mb-5 flex items-center gap-2 text-[12px] text-[rgba(0,0,0,0.55)]">
          <span className="font-semibold tabular-nums text-[rgba(0,0,0,0.75)]">
            {formatCurrency(historySummary.legacy_total_savings || 0)}
          </span>
          <LegacyUnverifiedTag />
          <span className="text-[rgba(0,0,0,0.4)]">Not in totals</span>
        </div>
      )}

      {/* Chart */}
      <div className="relative h-72">
        <canvas ref={chartRef} />
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white/80 rounded-lg z-10">
            <Icon icon="mdi:loading" width={32} className="animate-spin text-indigo-500" />
          </div>
        )}
        {!isLoading && (!historyData || historyData.length === 0) && (
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <Icon icon="mdi:chart-line" width={48} color="rgba(0,0,0,0.15)" />
            <p className="text-[13px] text-[rgba(0,0,0,0.4)] mt-3">No history data available for this period</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default SpotAutomationChart;
