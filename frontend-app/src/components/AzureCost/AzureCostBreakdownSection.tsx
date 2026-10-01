import React, { useEffect, useRef, useMemo } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import { Icon } from '@iconify/react';
import type { AzureBillingItem } from '../../types/azureDashboard';
import type { AzureBreakdownDimension, AzureCostAmount } from '../../api/azureCostInsightsApi';
import type { AzureCostFilters } from '../../hooks/useAzureCostData';
import { useMoneyIn } from '../Cost/costCurrency';

Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

/**
 * The Azure cost breakdown, with the filters the AWS page has always had.
 *
 * <p>This used to offer a Service/Region toggle over a fixed month-to-date figure read from the
 * ingestion cache, because that is all the data behind it could answer. It now queries Cost
 * Management for whatever range and grouping is asked for, which adds two things the previous
 * version could not express:
 *
 * <ul>
 *   <li><b>A date range.</b> "The first twelve days" and "last month" were unaskable questions.</li>
 *   <li><b>Resource group.</b> The Azure dimension with no AWS equivalent, and the one that matters
 *       most for chargeback: a resource group is how Azure spend is actually owned — one team, one
 *       application, one environment — where a meter category only says what kind of spend it
 *       was.</li>
 * </ul>
 *
 * <p>It falls back to the snapshot figures when the live query cannot answer, so a subscription whose
 * agreement will not group by the requested dimension still shows its service and region breakdown
 * rather than an empty card.
 */

interface AzureCostBreakdownSectionProps {
  /** Snapshot service figures, used for the fallback and for the month-to-date default. */
  billingSummary: AzureBillingItem[];
  /** Snapshot region figures, on the same terms. */
  costByRegion: Record<string, number>;
  isLoading: boolean;
  onServiceClick?: (serviceName: string) => void;
  filters: AzureCostFilters;
  onFiltersChange: (updates: Partial<AzureCostFilters>) => void;
  /** Live result for the current filters, or null when the subscription cannot be asked. */
  breakdown: AzureCostAmount[] | null;
  isBreakdownLoading: boolean;
  /** Tag keys Cost Management knows about. An empty list disables the tag option. */
  tagKeys: string[];
  /** ISO code every amount here is denominated in. */
  currency: string;
}

const COLORS = [
  '#f97316', '#f59e0b', '#ef4444', '#fb923c', '#fbbf24',
  '#e11d48', '#f43f5e', '#dc2626', '#ea580c', '#d97706', '#b45309',
];

/**
 * The groupings offered, and what each is called on screen.
 *
 * <p>Publisher and pricing model are deliberately left out of the control even though the backend
 * serves them: they answer "Azure or Marketplace" and "on-demand or reserved", which are commitment
 * questions belonging on the FinOps page rather than breakdown-of-spend questions. Charge type is
 * kept because it is what explains a month whose total does not match its usage.
 */
const DIMENSIONS: { id: AzureBreakdownDimension; label: string; noun: string }[] = [
  { id: 'SERVICE', label: 'Service', noun: 'Service' },
  { id: 'REGION', label: 'Region', noun: 'Region' },
  { id: 'RESOURCE_GROUP', label: 'Resource group', noun: 'Resource group' },
  { id: 'RESOURCE_TYPE', label: 'Resource type', noun: 'Resource type' },
  { id: 'CHARGE_TYPE', label: 'Charge type', noun: 'Charge type' },
  { id: 'TAG', label: 'Tag', noun: 'Tag value' },
];

const AzureCostBreakdownSection: React.FC<AzureCostBreakdownSectionProps> = ({
  billingSummary,
  costByRegion,
  isLoading,
  onServiceClick,
  filters,
  onFiltersChange,
  breakdown,
  isBreakdownLoading,
  tagKeys,
  currency,
}) => {
  const money = useMoneyIn(currency);
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  const dimension = DIMENSIONS.find((d) => d.id === filters.groupBy) ?? DIMENSIONS[0];

  /**
   * Whether the figures on screen came from the live query or the snapshot.
   *
   * <p>Surfaced rather than hidden. The two cover different periods — the snapshot is always
   * month-to-date — so silently substituting one for the other would show figures that do not match
   * the date range the reader just typed.
   */
  const usingSnapshot = breakdown === null && (filters.groupBy === 'SERVICE' || filters.groupBy === 'REGION');

  const chartItems = useMemo(() => {
    if (breakdown !== null) {
      return breakdown.map((item) => ({ name: item.name, amount: item.amount }));
    }
    if (filters.groupBy === 'SERVICE') {
      return [...billingSummary]
        .sort((a, b) => b.monthToDateCost - a.monthToDateCost)
        .map((item) => ({ name: item.serviceName, amount: item.monthToDateCost }));
    }
    if (filters.groupBy === 'REGION') {
      return Object.entries(costByRegion)
        .map(([name, amount]) => ({ name, amount }))
        .sort((a, b) => b.amount - a.amount);
    }
    return [];
  }, [breakdown, filters.groupBy, billingSummary, costByRegion]);

  const totalCost = useMemo(
    () => chartItems.reduce((sum, item) => sum + item.amount, 0),
    [chartItems],
  );

  /** Rows stay clickable only when they name a service the trend endpoint can be asked about. */
  const rowsAreServices = filters.groupBy === 'SERVICE';
  const busy = isLoading || isBreakdownLoading;

  useEffect(() => {
    if (!chartRef.current || chartItems.length === 0) return;
    if (chartInstanceRef.current) chartInstanceRef.current.destroy();

    const topN = chartItems.slice(0, 10);
    const others = chartItems.slice(10);
    const labels = topN.map(d => d.name || '(Unassigned)');
    const data = topN.map(d => d.amount);

    if (others.length > 0) {
      labels.push('Other');
      data.push(others.reduce((sum, item) => sum + item.amount, 0));
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
                const total = (data as number[]).reduce((a, b) => a + b, 0);
                const pct = ((ctx.parsed / total) * 100).toFixed(1);
                return ` ${money(ctx.parsed)}  (${pct}%)`;
              },
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);
    return () => { chartInstanceRef.current?.destroy(); };
  }, [chartItems, money]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm mb-6 widget-panel-hover">

      {/* Header + filters */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Breakdown</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
              {usingSnapshot
                ? `Month-to-date by ${dimension.label.toLowerCase()}`
                : `${filters.startDate} to ${filters.endDate}, by ${dimension.label.toLowerCase()}`}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.45)]">
              <span className="font-medium">From</span>
              <input
                type="date"
                value={filters.startDate}
                max={filters.endDate}
                onChange={(e) => onFiltersChange({ startDate: e.target.value })}
                className="px-2 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
              />
            </label>
            <label className="flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.45)]">
              <span className="font-medium">To</span>
              <input
                type="date"
                value={filters.endDate}
                min={filters.startDate}
                onChange={(e) => onFiltersChange({ endDate: e.target.value })}
                className="px-2 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
              />
            </label>

            <select
              aria-label="Group cost by"
              value={filters.groupBy}
              onChange={(e) => onFiltersChange({ groupBy: e.target.value as AzureBreakdownDimension })}
              className="px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
            >
              {DIMENSIONS.map((d) => (
                <option
                  key={d.id}
                  value={d.id}
                  // A tag grouping with no keys to choose from would be a dead end.
                  disabled={d.id === 'TAG' && tagKeys.length === 0}
                >
                  {d.label}
                </option>
              ))}
            </select>

            {filters.groupBy === 'TAG' && (
              <select
                aria-label="Tag key"
                value={filters.tagKey ?? ''}
                onChange={(e) => onFiltersChange({ tagKey: e.target.value || undefined })}
                className="px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
              >
                <option value="">Choose a tag…</option>
                {tagKeys.map((key) => (
                  <option key={key} value={key}>{key}</option>
                ))}
              </select>
            )}
          </div>
        </div>

        {/* Says which figures these are, because the fallback covers a different period. */}
        {usingSnapshot && !busy && chartItems.length > 0 && (
          <p className="mt-3 text-[11px] text-[rgba(0,0,0,0.45)] flex items-start gap-1.5">
            <Icon icon="mdi:information-outline" width={13} className="flex-shrink-0 mt-0.5" />
            <span>
              Showing the cached month-to-date figures. Azure Cost Management would not answer a live
              query for this range, so the date filter above is not reflected here.
            </span>
          </p>
        )}
      </div>

      {/* Loading */}
      {busy && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5 p-5">
          <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-72 rounded-xl"></div>
          <div className="lg:col-span-2 animate-pulse bg-[rgba(0,0,0,0.04)] h-72 rounded-xl"></div>
        </div>
      )}

      {/* Empty. Separates "pick a tag", "cannot be asked" and "really no spend", which look
          identical from an empty array and need opposite things said about them. */}
      {!busy && chartItems.length === 0 && (
        <div className="py-16 text-center px-6">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:chart-donut" width={24} color="rgba(0,0,0,0.2)" />
          </div>
          {filters.groupBy === 'TAG' && !filters.tagKey ? (
            <>
              <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">Choose a tag</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.38)] mt-1">
                Pick a tag key above to see spend broken down by its values.
              </p>
            </>
          ) : breakdown === null ? (
            <>
              <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">
                Not available for this subscription
              </p>
              <p className="text-[12px] text-[rgba(0,0,0,0.38)] mt-1 max-w-md mx-auto">
                Azure Cost Management would not group this subscription&apos;s spend by{' '}
                {dimension.label.toLowerCase()}. Which dimensions are available depends on the
                agreement behind the subscription.
              </p>
            </>
          ) : (
            <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">
              No spend in this range
            </p>
          )}
        </div>
      )}

      {/* Chart + Table */}
      {!busy && chartItems.length > 0 && (
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
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-baseline justify-between gap-3">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Breakdown</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)] tabular-nums">
                {money(totalCost)} total
              </p>
            </div>
            <div className="overflow-auto flex-1" style={{ maxHeight: '340px' }}>
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-[rgba(247,247,247,0.95)]">
                  <tr>
                    <th className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      {dimension.noun}
                    </th>
                    {/* The currency is read off the payload rather than asserted. This column was
                        labelled "Cost (USD)" while the cell beside it rendered a rupee symbol. */}
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">
                      Cost ({currency})
                    </th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">%</th>
                  </tr>
                </thead>
                <tbody>
                  {chartItems.map((item, index) => (
                    <tr
                      key={item.name || index}
                      className={`border-t border-[rgba(0,0,0,0.04)] transition-colors ${rowsAreServices && onServiceClick ? 'hover:bg-[rgba(0,0,0,0.02)] cursor-pointer' : ''}`}
                      style={{ animation: `fadeInUp 0.35s ease ${index * 0.035}s both` }}
                      onClick={() => rowsAreServices && onServiceClick?.(item.name)}
                    >
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-2.5">
                          <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[index % COLORS.length] }}></span>
                          <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] truncate" title={item.name}>{item.name || '(Unassigned)'}</span>
                        </div>
                      </td>
                      <td className="px-5 py-3 text-right">
                        <span className="text-[13px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{money(item.amount)}</span>
                      </td>
                      <td className="px-5 py-3 text-right text-[13px] text-[rgba(0,0,0,0.4)] tabular-nums">
                        {totalCost > 0 ? ((item.amount / totalCost) * 100).toFixed(1) : '0.0'}%
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

        </div>
      )}
    </div>
  );
};

export default AzureCostBreakdownSection;
