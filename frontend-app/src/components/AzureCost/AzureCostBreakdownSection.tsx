import React, { useEffect, useRef, useMemo, useState } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type { AzureBillingItem } from '../../types/azureDashboard';

Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

type GroupByType = 'SERVICE' | 'REGION';

interface AzureCostBreakdownSectionProps {
  billingSummary: AzureBillingItem[];
  costByRegion: Record<string, number>;
  isLoading: boolean;
  onServiceClick?: (serviceName: string) => void;
}

const COLORS = [
  '#f97316', '#f59e0b', '#ef4444', '#fb923c', '#fbbf24',
  '#e11d48', '#f43f5e', '#dc2626', '#ea580c', '#d97706', '#b45309',
];

const formatCurrency = (value: number): string =>
  '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });


const AzureCostBreakdownSection: React.FC<AzureCostBreakdownSectionProps> = ({
  billingSummary,
  costByRegion,
  isLoading,
  onServiceClick,
}) => {
  const [groupBy, setGroupBy] = useState<GroupByType>('SERVICE');
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  const chartItems = useMemo(() => {
    if (groupBy === 'SERVICE') {
      return [...billingSummary]
        .sort((a, b) => b.monthToDateCost - a.monthToDateCost)
        .map(item => ({ name: item.serviceName, amount: item.monthToDateCost }));
    }
    return Object.entries(costByRegion)
      .map(([name, amount]) => ({ name, amount }))
      .sort((a, b) => b.amount - a.amount);
  }, [groupBy, billingSummary, costByRegion]);

  const totalCost = useMemo(() =>
    chartItems.reduce((sum, item) => sum + item.amount, 0), [chartItems]);

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
                return ` ${formatCurrency(ctx.parsed)}  (${pct}%)`;
              },
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);
    return () => { chartInstanceRef.current?.destroy(); };
  }, [chartItems]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm mb-6">

      {/* Header + Toggle */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Breakdown</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
              Month-to-Date by {groupBy === 'SERVICE' ? 'Service' : 'Region'}
            </p>
          </div>
          <div className="flex items-center gap-1 bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5">
            {(['SERVICE', 'REGION'] as GroupByType[]).map(opt => (
              <button
                key={opt}
                onClick={() => setGroupBy(opt)}
                className={`px-3 py-1.5 rounded-md text-[11px] font-semibold transition-all ${
                  groupBy === opt
                    ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]'
                    : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
                }`}
              >
                {opt === 'SERVICE' ? 'Service' : 'Region'}
              </button>
            ))}
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
      {!isLoading && chartItems.length === 0 && (
        <div className="py-16 text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-dollar-sign text-[rgba(0,0,0,0.2)] text-xl"></i>
          </div>
          <p className="text-[14px] font-medium text-[rgba(0,0,0,0.5)]">No cost data available</p>
        </div>
      )}

      {/* Chart + Table */}
      {!isLoading && chartItems.length > 0 && (
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
                      {groupBy === 'SERVICE' ? 'Service' : 'Region'}
                    </th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">Cost (USD)</th>
                    <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)]">%</th>
                  </tr>
                </thead>
                <tbody>
                  {chartItems.map((item, index) => (
                    <tr
                      key={item.name || index}
                      className={`border-t border-[rgba(0,0,0,0.04)] transition-colors ${groupBy === 'SERVICE' && onServiceClick ? 'hover:bg-[rgba(0,0,0,0.02)] cursor-pointer' : ''}`}
                      onClick={() => groupBy === 'SERVICE' && onServiceClick?.(item.name)}
                    >
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-2.5">
                          <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[index % COLORS.length] }}></span>
                          <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] truncate">{item.name || '(Unassigned)'}</span>
                        </div>
                      </td>
                      <td className="px-5 py-3 text-right">
                        <span className="text-[13px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{formatCurrency(item.amount)}</span>
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
