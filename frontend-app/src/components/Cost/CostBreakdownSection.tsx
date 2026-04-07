/**
 * CostBreakdownSection Component
 * Displays cost breakdown with filters, donut chart, and detailed table
 */

import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type { CostDataItem, CostFilters, GroupByType } from '../../types/cost';
import type { ResourceLabel } from '../../types/resourceLabels';
import type { TrackedResource } from '../../services/resourceLabelsApi';

// Register Chart.js components
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

  // Color palette
  const colors = [
    '#4f46e5', '#6366f1', '#818cf8', '#a5b4fc', '#c7d2fe',
    '#10b981', '#34d399', '#6ee7b7', '#a7f3d0', '#f87171', '#fb923c'
  ];

  // Render chart
  useEffect(() => {
    if (!chartRef.current || costData.length === 0) return;

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const topN = 10;
    const chartData = sortedData.slice(0, topN);
    const otherData = sortedData.slice(topN);
    const labels = chartData.map(d => d.name);
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
          label: 'Cost',
          data,
          backgroundColor: colors,
          borderWidth: 0,
          hoverOffset: 6,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              boxWidth: 12,
              font: { size: 12 },
              usePointStyle: true,
              padding: 15,
            },
          },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(102,126,234,0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            callbacks: {
              label: (context) => {
                const total = data.reduce((a, b) => a + b, 0);
                const pct = ((context.parsed / total) * 100).toFixed(1);
                return `${context.label}: $${context.parsed.toFixed(2)} (${pct}%)`;
              },
            },
          },
        },
        onClick: (_evt, elements) => {
          if (elements.length > 0 && chartInstanceRef.current) {
            const clickedLabel = chartInstanceRef.current.data.labels?.[elements[0].index] as string;
            if (clickedLabel && clickedLabel !== 'Other') {
              onHistoricalClick(clickedLabel);
            }
          }
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);

    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
      }
    };
  }, [costData, sortedData, onHistoricalClick]);

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md">
      <div className="flex flex-wrap items-center justify-between gap-6 mb-8">
        <div className="flex items-center space-x-4">
          <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-chart-pie text-white"></i>
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-800">Cost Breakdown</h2>
            <p className="text-sm text-gray-600">
              {labelFilterId && labels.length > 0
                ? (
                  <>
                    Filtered by label:{' '}
                    <span className="font-semibold text-indigo-600">{labels.find(l => l.id === labelFilterId)?.name}</span>
                    {labelResourceLevel === true && (
                      <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-green-100 text-green-700 border border-green-200">
                        <i className="fas fa-check-circle" style={{ fontSize: '9px' }} /> resource-level
                      </span>
                    )}
                    {labelResourceLevel === false && (
                      <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-700 border border-amber-200">
                        <i className="fas fa-info-circle" style={{ fontSize: '9px' }} />
                        {labelApproximationWarning ? 'service-level approx.' : 'estimated (on-demand)'}
                      </span>
                    )}
                  </>
                )
                : 'Spending analysis for selected range'}
            </p>
          </div>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center space-x-4 bg-gray-50/80 rounded-xl p-4 border border-gray-200">
          <div className="flex items-center space-x-2">
            <span className="text-sm font-medium text-gray-700">From:</span>
            <input
              type="date"
              value={filters.startDate}
              onChange={(e) => onFiltersChange({ startDate: e.target.value })}
              className="px-3 py-1.5 text-sm rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 bg-white"
            />
          </div>
          <div className="flex items-center space-x-2">
            <span className="text-sm font-medium text-gray-700">To:</span>
            <input
              type="date"
              value={filters.endDate}
              onChange={(e) => onFiltersChange({ endDate: e.target.value })}
              className="px-3 py-1.5 text-sm rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 bg-white"
            />
          </div>
          <div className="flex items-center space-x-2">
            <span className="text-sm font-medium text-gray-700">Group by:</span>
            <select
              value={filters.groupBy}
              onChange={(e) => onFiltersChange({ groupBy: e.target.value as GroupByType })}
              className="px-3 py-1.5 text-sm rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 bg-white"
            >
              <option value="SERVICE">Service</option>
              <option value="REGION">Region</option>
              <option value="TAG">Tag</option>
            </select>
          </div>
          {filters.groupBy === 'TAG' && (
            <div className="relative">
              <input
                type="text"
                value={filters.tagKey}
                onChange={(e) => onFiltersChange({ tagKey: e.target.value })}
                placeholder="Tag Key"
                className="px-3 py-1.5 text-sm rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 bg-white w-32"
              />
            </div>
          )}
          {/* XamOps Label filter */}
          {labels.length > 0 && onLabelFilterChange && (
            <div className="flex items-center space-x-2">
              <i className={`fas fa-tag text-indigo-400 text-xs ${labelFilterLoading ? 'animate-pulse' : ''}`} />
              <select
                value={labelFilterId ?? ''}
                onChange={e => onLabelFilterChange(e.target.value ? Number(e.target.value) : null)}
                className="px-3 py-1.5 text-sm rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 bg-white"
              >
                <option value="">All Labels</option>
                {labels.map(l => (
                  <option key={l.id} value={l.id}>{l.name}</option>
                ))}
              </select>
              {labelFilterId && (
                <button
                  type="button"
                  onClick={() => onLabelFilterChange(null)}
                  className="text-xs text-gray-400 hover:text-gray-600 px-1"
                  title="Clear label filter"
                >
                  <i className="fas fa-times" />
                </button>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Tracked resources banner — shown when a label filter is active */}
      {labelFilterId && labelTrackedResources && labelTrackedResources.length > 0 && (
        <div className={`mb-6 rounded-xl border px-4 py-3 flex gap-3 ${
          labelResourceLevel
            ? 'bg-green-50 border-green-200'
            : 'bg-amber-50 border-amber-200'
        }`}>
          <div className="flex-shrink-0 mt-0.5">
            <i className={`fas ${labelResourceLevel ? 'fa-check-circle text-green-500' : 'fa-exclamation-triangle text-amber-500'}`} />
          </div>
          <div className="flex-1 min-w-0">
            <p className={`text-xs font-semibold mb-1.5 ${labelResourceLevel ? 'text-green-700' : 'text-amber-700'}`}>
              {labelResourceLevel
                ? `Showing costs for ${labelTrackedResources.length} tagged resource${labelTrackedResources.length !== 1 ? 's' : ''}`
                : `Showing per-resource cost estimates for ${labelTrackedResources.length} tagged resource${labelTrackedResources.length !== 1 ? 's' : ''}`}
            </p>
            <div className="flex flex-wrap gap-1.5 mb-1.5">
              {labelTrackedResources.map(r => (
                <span
                  key={r.id}
                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium bg-white border border-gray-200 text-gray-700 shadow-sm"
                  title={`${r.type} • ${r.region} • ${r.id}`}
                >
                  <i className="fas fa-server text-indigo-400" style={{ fontSize: '9px' }} />
                  <span className="font-semibold">{r.name !== r.id ? r.name : r.id}</span>
                  {r.name !== r.id && (
                    <span className="text-gray-400 font-normal">{r.id}</span>
                  )}
                  {r.region && (
                    <span className="text-gray-400">• {r.region}</span>
                  )}
                </span>
              ))}
            </div>
            {!labelResourceLevel && labelMessage && (
              <p className="text-xs text-amber-600 italic">{labelMessage}</p>
            )}
            {labelApproximationWarning && (
              <p className="text-xs text-amber-700 mt-1">
                <i className="fas fa-exclamation-circle mr-1" />
                {labelApproximationWarning}
              </p>
            )}
          </div>
        </div>
      )}

      {/* Loading State */}
      {isLoading && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          <div className="animate-pulse bg-gray-200 h-80 rounded-xl"></div>
          <div className="lg:col-span-2 animate-pulse bg-gray-200 h-96 rounded-xl"></div>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && costData.length === 0 && (
        <div className="text-center py-16 px-8 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
          <div className="w-20 h-20 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-6">
            <i className="fas fa-dollar-sign text-gray-400 text-3xl"></i>
          </div>
          <h3 className="text-xl font-semibold text-gray-700 mb-2">No Cost Data Available</h3>
          <p className="text-sm text-gray-500 mb-6">There is no cost data for the selected criteria.</p>
        </div>
      )}

      {/* Chart and Table */}
      {!isLoading && costData.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-start">
          {/* Donut Chart */}
          <div className="lg:col-span-1">
            <div className="bg-white rounded-xl p-6 border border-gray-200 shadow-sm min-h-96">
              <h3 className="text-lg font-semibold text-gray-800 mb-4">Cost Distribution</h3>
              <div className="h-80 relative">
                <canvas ref={chartRef}></canvas>
              </div>
            </div>
          </div>

          {/* Table */}
          <div className="lg:col-span-2">
            <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
              <div className="px-6 py-4 bg-gray-50 border-b border-gray-200">
                <h3 className="text-lg font-semibold text-gray-800 flex items-center">
                  <i className="fas fa-table mr-2 text-indigo-600"></i>
                  Detailed Breakdown
                </h3>
                {filters.groupBy === 'TAG' && (() => {
                  const untaggedItem = costData.find(d => d.name === 'Untagged');
                  const untaggedCost = untaggedItem?.amount ?? 0;
                  const taggedCost = totalCost - untaggedCost;
                  const coveragePct = totalCost > 0 ? Math.round((taggedCost / totalCost) * 100) : 100;
                  return (
                    <div className="mt-2 flex items-center gap-3">
                      <div className="flex-1 bg-gray-200 rounded-full h-1.5">
                        <div
                          className={`h-1.5 rounded-full ${coveragePct >= 80 ? 'bg-green-500' : coveragePct >= 50 ? 'bg-amber-500' : 'bg-red-500'}`}
                          style={{ width: `${coveragePct}%` }}
                        />
                      </div>
                      <span className="text-xs font-semibold text-gray-500 whitespace-nowrap">
                        {coveragePct}% tagged
                      </span>
                    </div>
                  );
                })()}
              </div>
              <div className="overflow-x-auto max-h-96">
                <table className="w-full text-sm text-left">
                  <thead className="text-xs text-gray-700 uppercase bg-gray-50 sticky top-0 shadow-sm">
                    <tr>
                      <th className="px-6 py-4 font-bold">
                        {filters.groupBy === 'TAG'
                          ? `Tag: ${filters.tagKey}`
                          : filters.groupBy.charAt(0) + filters.groupBy.slice(1).toLowerCase()}
                      </th>
                      <th className="px-6 py-4 text-right font-bold">Cost (USD)</th>
                      <th className="px-6 py-4 text-right font-bold">%</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200">
                    {sortedData.map((item, index) => {
                      const isUntagged = item.name === 'Untagged';
                      return (
                        <tr
                          key={item.name}
                          className={`transition-colors duration-150 ${isUntagged ? 'bg-amber-50/60' : 'hover:bg-indigo-50/50 cursor-pointer'}`}
                          onClick={() => !isUntagged && onHistoricalClick(item.name)}
                        >
                          <td className="px-6 py-4 relative group">
                            {!isUntagged && (
                              <div className="absolute left-0 top-0 bottom-0 w-1 bg-indigo-500 scale-y-0 group-hover:scale-y-100 transition-transform origin-left"></div>
                            )}
                            <div className="flex items-center space-x-3">
                              <div
                                className="w-3 h-3 rounded-full shadow-sm"
                                style={{ backgroundColor: isUntagged ? '#f59e0b' : colors[index % 11] }}
                              ></div>
                              <span className={`font-medium ${isUntagged ? 'text-amber-700' : 'text-gray-800'}`}>
                                {isUntagged ? '⚠ Untagged' : item.name}
                              </span>
                            </div>
                          </td>
                          <td className="px-6 py-4 text-right">
                            <span className={`font-bold ${isUntagged ? 'text-amber-700' : 'text-gray-900'}`}>
                              ${item.amount.toFixed(2)}
                            </span>
                          </td>
                          <td className="px-6 py-4 text-right text-gray-600">
                            {((item.amount / totalCost) * 100).toFixed(1)}%
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default CostBreakdownSection;
