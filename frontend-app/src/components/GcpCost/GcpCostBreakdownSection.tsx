import React, { useEffect, useRef, useMemo } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type {
  GcpBillingStatus,
  GcpCostDataItem,
  GcpCostFilters,
  GcpGroupByType,
} from '../../types/gcpCost';
import { useMoneyIn } from '../Cost/costCurrency';

Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

interface GcpCostBreakdownSectionProps {
  costData: GcpCostDataItem[];
  filters: GcpCostFilters;
  isLoading: boolean;
  onFiltersChange: (filters: Partial<GcpCostFilters>) => void;
  /**
   * Why there may be no data. Optional so the component still renders if the status
   * request failed — it just falls back to the old neutral message rather than
   * asserting a reason it does not have.
   */
  billingStatus?: GcpBillingStatus | null;
  /** ISO code the billing account is denominated in. */
  currency: string;
  /**
   * Opens the daily trend for one row.
   *
   * <p>Optional so the section still renders without it, but the AWS and Azure pages both make their
   * breakdown rows clickable and `/costs/historical-trend` has always been able to answer for GCP.
   */
  onDimensionClick?: (dimension: string) => void;
}

/** What the empty panel should say, derived from the billing source's readiness. */
interface EmptyStateCopy {
  title: string;
  detail: string | null;
  icon: string;
  tone: 'neutral' | 'problem';
}

/**
 * Turns billing-source readiness into the empty panel's wording.
 *
 * The backend owns every sentence that names a customer action, matching how
 * PermissionGapService builds its summary — the fix is a specific console or Account
 * Manager step, so the copy has to travel with the state that knows which one. Only the
 * two cases with nothing to explain are worded here.
 */
const emptyStateFor = (billingStatus?: GcpBillingStatus | null): EmptyStateCopy => {
  // No status (request failed, or an older backend). Say only what is certainly true.
  if (!billingStatus) {
    return { title: 'No cost data available', detail: null, icon: 'fa-dollar-sign', tone: 'neutral' };
  }

  // Source is healthy and returned nothing: that is a real answer, not a failure.
  if (billingStatus.state === 'READY') {
    return {
      title: 'No spend in this period',
      detail: 'Billing data is connected and reporting normally. Try a wider date range or a different grouping.',
      icon: 'fa-check-circle',
      tone: 'neutral',
    };
  }

  const pending = billingStatus.state === 'EXPORT_PENDING';
  return {
    title: pending ? 'Billing export is still populating' : 'Cost data unavailable',
    detail: billingStatus.summary,
    icon: pending ? 'fa-hourglass-half' : 'fa-triangle-exclamation',
    tone: pending ? 'neutral' : 'problem',
  };
};

// Matches the AWS Cost Breakdown palette (indigo/violet/cyan/amber/emerald…)
// so the GCP donut reads the same as the AWS one.
const COLORS = [
  '#6366f1', '#8b5cf6', '#22d3ee', '#f59e0b', '#10b981',
  '#f97316', '#3b82f6', '#ec4899', '#84cc16', '#14b8a6',
  '#a78bfa',
];

const inputCls = 'px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors';

const GcpCostBreakdownSection: React.FC<GcpCostBreakdownSectionProps> = ({
  costData,
  filters,
  isLoading,
  onFiltersChange,
  billingStatus,
  currency,
  onDimensionClick,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  const emptyState = useMemo(() => emptyStateFor(billingStatus), [billingStatus]);

  const formatCurrency = useMoneyIn(currency);

  /**
   * Whether a row names something the trend endpoint can be asked about.
   *
   * <p>`/costs/historical-trend` filters on service. A project or a tag value is not a service, so
   * those rows stay inert rather than opening a chart of the whole project's spend under a heading
   * naming one tag.
   */
  const rowsAreClickable = Boolean(onDimensionClick) && filters.groupBy === 'SERVICE';

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
          // Legend intentionally disabled — the Detailed Breakdown table beside
          // the chart already serves as a full color-keyed legend, and hiding it
          // frees the donut hole for the centered Total (matches the AWS chart).
          legend: { display: false },
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
                const pct = total > 0 ? ((ctx.parsed / total) * 100).toFixed(1) : '0.0';
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
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
              {/* The range is now real. It used to say "month to date" because the page never sent
                  the dates that /breakdown has always accepted, so that was the only truth
                  available; grouping by tag still ignores it, and says so. */}
              {filters.groupBy === 'TAG'
                ? 'Month to date — the tag breakdown does not take a date range'
                : `${filters.startDate} to ${filters.endDate}`}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {filters.groupBy !== 'TAG' && (
              <>
                <label className="flex items-center gap-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.5)]">
                  From
                  <input
                    type="date"
                    value={filters.startDate}
                    max={filters.endDate}
                    onChange={e => onFiltersChange({ startDate: e.target.value })}
                    className={inputCls}
                  />
                </label>
                <label className="flex items-center gap-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.5)]">
                  To
                  <input
                    type="date"
                    value={filters.endDate}
                    min={filters.startDate}
                    onChange={e => onFiltersChange({ endDate: e.target.value })}
                    className={inputCls}
                  />
                </label>
              </>
            )}
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Group by:</span>
            <select
              aria-label="Group cost by"
              value={filters.groupBy}
              onChange={e => onFiltersChange({ groupBy: e.target.value as GcpGroupByType })}
              className={inputCls}
            >
              <option value="SERVICE">Service</option>
              {/* Supported by GcpCostService all along and absent from this list, so the one
                  breakdown the AWS and Azure pages both offer by default could not be asked for. */}
              <option value="REGION">Region</option>
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

      {/* Empty — explains itself when the billing source is why */}
      {!isLoading && costData.length === 0 && (
        <div className="py-16 px-6 text-center">
          <div
            className={`w-14 h-14 rounded-full flex items-center justify-center mx-auto mb-4 ${
              emptyState.tone === 'problem' ? 'bg-[rgba(239,68,68,0.08)]' : 'bg-[rgba(0,0,0,0.04)]'
            }`}
          >
            <i
              className={`fas ${emptyState.icon} text-xl ${
                emptyState.tone === 'problem' ? 'text-[#dc2626]' : 'text-[rgba(0,0,0,0.2)]'
              }`}
            ></i>
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.7)]">{emptyState.title}</p>
          {emptyState.detail && (
            <p className="mt-2 mx-auto max-w-xl text-[13px] leading-relaxed text-[rgba(0,0,0,0.55)]">
              {emptyState.detail}
            </p>
          )}
        </div>
      )}

      {/* Chart + Table */}
      {!isLoading && costData.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-0 divide-x divide-[rgba(0,0,0,0.06)]">

          {/* Donut */}
          <div className="lg:col-span-2 p-5 flex flex-col">
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-4">Cost Distribution</p>
            <div className="relative flex-1 min-h-[360px]">
              <canvas ref={chartRef}></canvas>
              {/* Total in the donut hole (matches AWS). Currency stays ₹ / en-IN. */}
              <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                <span className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.4)]">Total</span>
                <span className="text-[22px] font-bold text-[rgba(0,0,0,0.85)] tabular-nums leading-tight">
                  ₹{totalCost.toLocaleString('en-IN', { maximumFractionDigits: 0 })}
                </span>
                <span className="text-[10px] text-[rgba(0,0,0,0.35)] font-medium">{sortedData.length} categories</span>
              </div>
            </div>
          </div>

          {/* Table */}
          <div className="lg:col-span-3 flex flex-col">
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-baseline justify-between gap-3">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Breakdown</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)] tabular-nums">
                {formatCurrency(totalCost)} total
              </p>
            </div>
            <div className="overflow-auto flex-1" style={{ maxHeight: '340px' }}>
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-[rgba(247,247,247,0.95)]">
                  <tr>
                    <th className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      {filters.groupBy.charAt(0) + filters.groupBy.slice(1).toLowerCase()}
                    </th>
                    {/* Read off the billing account rather than asserted. This column said
                        "Cost (USD)" while the cell beside it rendered a rupee symbol. */}
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      Cost ({currency})
                    </th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">%</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedData.map((item, index) => {
                    const pct = totalCost > 0 ? ((item.amount / totalCost) * 100).toFixed(1) : '0.0';
                    return (
                      <tr
                        key={item.name || index}
                        className={`border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] transition-colors ${rowsAreClickable ? 'cursor-pointer' : ''}`}
                        style={{ animation: `fadeInUp 0.35s ease ${index * 0.035}s both` }}
                        onClick={rowsAreClickable && item.name
                          ? () => onDimensionClick?.(item.name)
                          : undefined}
                        title={rowsAreClickable ? `Show the daily trend for ${item.name}` : undefined}
                      >
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
