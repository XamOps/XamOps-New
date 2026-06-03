import React, { useEffect, useRef, useMemo } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type { GcpCostDataItem, GcpCostFilters, GcpGroupByType } from '../../types/gcpCost';

Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

interface GcpCostBreakdownSectionProps {
  costData: GcpCostDataItem[];
  filters: GcpCostFilters;
  isLoading: boolean;
  onFiltersChange: (filters: Partial<GcpCostFilters>) => void;
}

const COLORS = [
  '#f97316', '#f59e0b', '#ef4444', '#fb923c', '#fbbf24',
  '#e11d48', '#f43f5e', '#dc2626', '#ea580c', '#d97706', '#b45309',
];

const inputCls = 'px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors';

const GcpCostBreakdownSection: React.FC<GcpCostBreakdownSectionProps> = ({
  costData,
  filters,
  isLoading,
  onFiltersChange,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  const formatCurrency = (value: number): string =>
    '₹' + (value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const sortedData = useMemo(() =>
    [...costData].sort((a, b) => b.amount - a.amount), [costData]);

  const totalCost = useMemo(() =>
    costData.reduce((sum, item) => sum + item.amount, 0), [costData]);

  useEffect(() => {
    if (!chartRef.current || costData.length === 0) return;
    if (chartInstanceRef.current) chartInstanceRef.current.destroy();

    const topN = 10;
    const chartData = sortedData.slice(0, topN);
    const otherData = sortedData.slice(topN);
    const labels = chartData.map(d => d.name || '(Not Set)');
    const data = chartData.map(d => d.amount);

    if (otherData.length > 0) {
      labels.push('Other');
      data.push(otherData.reduce((sum, item) => sum + item.amount, 0));
    }

    const config: ChartConfiguration<'doughnut'> = {
      type: 'doughnut',
      data: {
        labels,
        datasets: [{
          data,
          backgroundColor: COLORS,
          borderWidth: 2,
          borderColor: '#ffffff',
          hoverOffset: 4,
        }],
      },
      options: {
        animation: { animateRotate: true, animateScale: true, duration: 900, easing: 'easeOutQuart' },
        responsive: true,
        maintainAspectRatio: false,
        cutout: '68%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              boxWidth: 8,
              boxHeight: 8,
              font: { size: 11 },
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 12,
              color: 'rgba(0,0,0,0.55)',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(0,0,0,0.88)',
            titleColor: 'white',
            bodyColor: 'rgba(255,255,255,0.7)',
            borderColor: 'rgba(255,255,255,0.08)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 10,
            callbacks: {
              label: (ctx) => {
                const total = data.reduce((a, b) => a + b, 0);
                const pct = ((ctx.parsed / total) * 100).toFixed(1);
                return ` ${formatCurrency(ctx.parsed)}  (${pct}%)`;
              },
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);
    return () => { chartInstanceRef.current?.destroy(); };
  }, [costData, sortedData]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm widget-panel-hover">

      {/* Header + Filters */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Breakdown</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Spending analysis for the last 90 days</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Group by:</span>
            <select value={filters.groupBy} onChange={e => onFiltersChange({ groupBy: e.target.value as GcpGroupByType })} className={inputCls}>
              <option value="SERVICE">Service</option>
              <option value="PROJECT">Project</option>
              <option value="TAG">Tag Key</option>
            </select>
            {filters.groupBy === 'TAG' && (
              <input type="text" value={filters.tagKey} onChange={e => onFiltersChange({ tagKey: e.target.value })} placeholder="Enter tag key..." className={`${inputCls} w-32`} />
            )}
          </div>
        </div>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5 p-5">
          <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-72 rounded-xl"></div>
          <div className="lg:col-span-2 animate-pulse bg-[rgba(0,0,0,0.04)] h-72 rounded-xl"></div>
        </div>
      )}

      {/* Empty */}
      {!isLoading && costData.length === 0 && (
        <div className="py-16 text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-dollar-sign text-[rgba(0,0,0,0.2)] text-xl"></i>
          </div>
          <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">No cost data available</p>
        </div>
      )}

      {/* Chart + Table */}
      {!isLoading && costData.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-0 divide-x divide-[rgba(0,0,0,0.06)]">

          {/* Donut */}
          <div className="lg:col-span-2 p-5">
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-4">Cost Distribution</p>
            <div className="h-72 relative">
              <canvas ref={chartRef}></canvas>
            </div>
          </div>

          {/* Table */}
          <div className="lg:col-span-3 flex flex-col">
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Breakdown</p>
            </div>
            <div className="overflow-auto flex-1" style={{ maxHeight: '340px' }}>
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-[rgba(247,247,247,0.95)]">
                  <tr>
                    <th className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      {filters.groupBy.charAt(0) + filters.groupBy.slice(1).toLowerCase()}
                    </th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Cost (USD)</th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">%</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedData.map((item, index) => {
                    const pct = ((item.amount / totalCost) * 100).toFixed(1);
                    return (
                      <tr key={item.name || index} className="border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] transition-colors" style={{ animation: `fadeInUp 0.35s ease ${index * 0.035}s both` }}>
                        <td className="px-5 py-3">
                          <div className="flex items-center gap-2.5">
                            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[index % COLORS.length] }}></span>
                            <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] truncate">{item.name || '(Not Set)'}</span>
                          </div>
                        </td>
                        <td className="px-5 py-3 text-right">
                          <span className="text-[13px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{formatCurrency(item.amount)}</span>
                        </td>
                        <td className="px-5 py-3 text-right text-[13px] text-[rgba(0,0,0,0.4)] tabular-nums">{pct}%</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

        </div>
      )}
    </div>
  );
};

export default GcpCostBreakdownSection;
