import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type { CostDataItem, CostFilters, GroupByType } from '../../types/cost';
import type { ResourceLabel } from '../../types/resourceLabels';
import type { TrackedResource } from '../../services/resourceLabelsApi';

Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

interface CostBreakdownSectionProps {
  costData: CostDataItem[];
  filters: CostFilters;
  isLoading: boolean;
  onFiltersChange: (filters: Partial<CostFilters>) => void;
  onHistoricalClick: (dimension: string) => void;
  labels?: ResourceLabel[];
  labelFilterId?: number | null;
  onLabelFilterChange?: (id: number | null) => void;
  labelFilterLoading?: boolean;
  labelResourceLevel?: boolean;
  labelMessage?: string;
  labelTrackedResources?: TrackedResource[];
  labelApproximationWarning?: string;
}

const COLORS = [
  '#6366f1', '#8b5cf6', '#22d3ee', '#f59e0b', '#10b981',
  '#f97316', '#3b82f6', '#ec4899', '#84cc16', '#14b8a6',
  '#a78bfa',
];

const inputCls = 'px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors';

const CostBreakdownSection: React.FC<CostBreakdownSectionProps> = ({
  costData,
  filters,
  isLoading,
  onFiltersChange,
  onHistoricalClick,
  labels = [],
  labelFilterId,
  onLabelFilterChange,
  labelFilterLoading = false,
  labelResourceLevel,
  labelMessage,
  labelTrackedResources,
  labelApproximationWarning,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  const sortedData = [...costData].sort((a, b) => b.amount - a.amount);
  const totalCost = costData.reduce((sum, item) => sum + item.amount, 0);

  useEffect(() => {
    if (!chartRef.current || costData.length === 0) return;
    if (chartInstanceRef.current) chartInstanceRef.current.destroy();

    const topN = 10;
    const chartData = sortedData.slice(0, topN);
    const otherData = sortedData.slice(topN);
    const chartLabels = chartData.map(d => d.name);
    const data = chartData.map(d => d.amount);

    if (otherData.length > 0) {
      chartLabels.push('Other');
      data.push(otherData.reduce((sum, item) => sum + item.amount, 0));
    }

    const config: ChartConfiguration<'doughnut'> = {
      type: 'doughnut',
      data: {
        labels: chartLabels,
        datasets: [{
          data,
          backgroundColor: COLORS,
          borderWidth: 2,
          borderColor: '#ffffff',
          hoverOffset: 4,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '68%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              boxWidth: 8,
              boxHeight: 8,
              font: { size: 11, family: 'Inter' },
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
                return ` $${ctx.parsed.toFixed(2)}  (${pct}%)`;
              },
            },
          },
        },
        onClick: (_evt, elements) => {
          if (elements.length > 0 && chartInstanceRef.current) {
            const label = chartInstanceRef.current.data.labels?.[elements[0].index] as string;
            if (label && label !== 'Other') onHistoricalClick(label);
          }
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);
    return () => { chartInstanceRef.current?.destroy(); };
  }, [costData, sortedData, onHistoricalClick]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">

      {/* Header + Filters */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Breakdown</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
              {labelFilterId && labels.length > 0
                ? <>Filtered by label: <span className="font-semibold text-indigo-600">{labels.find(l => l.id === labelFilterId)?.name}</span>
                    {labelResourceLevel === true && <span className="ml-1.5 text-[9px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-full bg-emerald-50 text-emerald-600 border border-emerald-100">resource-level</span>}
                    {labelResourceLevel === false && <span className="ml-1.5 text-[9px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-full bg-amber-50 text-amber-600 border border-amber-100">estimated</span>}
                  </>
                : 'Spending analysis for selected range'}
            </p>
          </div>

          {/* Filters row */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5">
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">From:</span>
              <input type="date" value={filters.startDate} onChange={e => onFiltersChange({ startDate: e.target.value })} className={inputCls} />
            </div>
            <div className="flex items-center gap-1.5">
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">To:</span>
              <input type="date" value={filters.endDate} onChange={e => onFiltersChange({ endDate: e.target.value })} className={inputCls} />
            </div>
            <select value={filters.groupBy} onChange={e => onFiltersChange({ groupBy: e.target.value as GroupByType })} className={inputCls}>
              <option value="SERVICE">Service</option>
              <option value="REGION">Region</option>
              <option value="TAG">Tag</option>
            </select>
            {filters.groupBy === 'TAG' && (
              <input type="text" value={filters.tagKey} onChange={e => onFiltersChange({ tagKey: e.target.value })} placeholder="Tag Key" className={`${inputCls} w-28`} />
            )}
            {labels.length > 0 && onLabelFilterChange && (
              <div className="flex items-center gap-1">
                <i className={`fas fa-tag text-[rgba(0,0,0,0.3)] text-[10px] ${labelFilterLoading ? 'animate-pulse' : ''}`} />
                <select value={labelFilterId ?? ''} onChange={e => onLabelFilterChange(e.target.value ? Number(e.target.value) : null)} className={inputCls}>
                  <option value="">All Labels</option>
                  {labels.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
                {labelFilterId && (
                  <button onClick={() => onLabelFilterChange(null)} className="text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)] px-1 transition-colors" title="Clear">
                    <i className="fas fa-times text-[10px]" />
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Tracked resources banner */}
      {labelFilterId && labelTrackedResources && labelTrackedResources.length > 0 && (
        <div className={`mx-5 mt-4 rounded-xl border px-4 py-3 flex gap-3 ${labelResourceLevel ? 'bg-emerald-50 border-emerald-100' : 'bg-amber-50 border-amber-100'}`}>
          <i className={`fas ${labelResourceLevel ? 'fa-check-circle text-emerald-500' : 'fa-exclamation-triangle text-amber-500'} mt-0.5 flex-shrink-0`} />
          <div className="flex-1 min-w-0">
            <p className={`text-[11px] font-semibold mb-1.5 ${labelResourceLevel ? 'text-emerald-700' : 'text-amber-700'}`}>
              {labelResourceLevel
                ? `Showing costs for ${labelTrackedResources.length} tagged resource${labelTrackedResources.length !== 1 ? 's' : ''}`
                : `Estimated costs for ${labelTrackedResources.length} tagged resource${labelTrackedResources.length !== 1 ? 's' : ''}`}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {labelTrackedResources.map(r => (
                <span key={r.id} className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] font-medium bg-white border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)]" title={`${r.type} • ${r.region} • ${r.id}`}>
                  <i className="fas fa-server text-[rgba(0,0,0,0.3)]" style={{ fontSize: '9px' }} />
                  <span className="font-semibold">{r.name !== r.id ? r.name : r.id}</span>
                  {r.region && <span className="text-[rgba(0,0,0,0.35)]">· {r.region}</span>}
                </span>
              ))}
            </div>
            {!labelResourceLevel && labelMessage && <p className="text-[11px] text-amber-600 italic mt-1">{labelMessage}</p>}
            {labelApproximationWarning && <p className="text-[11px] text-amber-700 mt-1"><i className="fas fa-exclamation-circle mr-1" />{labelApproximationWarning}</p>}
          </div>
        </div>
      )}

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
          <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">No cost data for selected range</p>
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
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Breakdown</p>
              {filters.groupBy === 'TAG' && (() => {
                const untaggedCost = costData.find(d => d.name === 'Untagged')?.amount ?? 0;
                const pct = totalCost > 0 ? Math.round(((totalCost - untaggedCost) / totalCost) * 100) : 100;
                return (
                  <div className="flex items-center gap-2">
                    <div className="w-24 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                      <div className={`h-full rounded-full ${pct >= 80 ? 'bg-emerald-500' : pct >= 50 ? 'bg-amber-500' : 'bg-rose-500'}`} style={{ width: `${pct}%` }} />
                    </div>
                    <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-medium">{pct}% tagged</span>
                  </div>
                );
              })()}
            </div>
            <div className="overflow-auto flex-1" style={{ maxHeight: '340px' }}>
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-[rgba(247,247,247,0.95)]">
                  <tr>
                    <th className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      {filters.groupBy === 'TAG' ? `Tag: ${filters.tagKey}` : filters.groupBy.charAt(0) + filters.groupBy.slice(1).toLowerCase()}
                    </th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Cost (USD)</th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">%</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedData.map((item, index) => {
                    const isUntagged = item.name === 'Untagged';
                    const pct = ((item.amount / totalCost) * 100).toFixed(1);
                    return (
                      <tr
                        key={item.name}
                        className={`border-t border-[rgba(0,0,0,0.04)] transition-colors ${isUntagged ? 'bg-amber-50/60' : 'hover:bg-[rgba(0,0,0,0.02)] cursor-pointer'}`}
                        onClick={() => !isUntagged && onHistoricalClick(item.name)}
                      >
                        <td className="px-5 py-3">
                          <div className="flex items-center gap-2.5">
                            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: isUntagged ? '#f59e0b' : COLORS[index % COLORS.length] }}></span>
                            <span className={`text-[13px] font-medium truncate ${isUntagged ? 'text-amber-700' : 'text-[rgba(0,0,0,0.88)]'}`}>
                              {isUntagged ? '⚠ Untagged' : item.name}
                            </span>
                          </div>
                        </td>
                        <td className="px-5 py-3 text-right">
                          <span className={`text-[13px] font-semibold tabular-nums ${isUntagged ? 'text-amber-700' : 'text-[rgba(0,0,0,0.88)]'}`}>
                            ${item.amount.toFixed(2)}
                          </span>
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

export default CostBreakdownSection;
