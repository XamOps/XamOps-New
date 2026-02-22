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
  '#0078D4', '#107C10', '#FFB900', '#D13438', '#5C2D91',
  '#008575', '#E3008C', '#00B7C3', '#004E8C', '#8764B8', '#CA5010',
];

const formatCurrency = (value: number): string => {
  return '₹' + (value || 0).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const AzureCostBreakdownSection: React.FC<AzureCostBreakdownSectionProps> = ({
  billingSummary,
  costByRegion,
  isLoading,
  onServiceClick,
}) => {
  const [groupBy, setGroupBy] = useState<GroupByType>('SERVICE');
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  // Compute chart data based on groupBy
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
    chartItems.reduce((sum, item) => sum + item.amount, 0),
    [chartItems]
  );

  useEffect(() => {
    if (!chartRef.current || chartItems.length === 0) return;

    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

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
          label: 'Cost',
          data,
          backgroundColor: COLORS,
          borderWidth: 0,
          hoverOffset: 8,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '62%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              boxWidth: 12,
              font: { size: 11, weight: '500' },
              usePointStyle: true,
              padding: 12,
              color: '#374151',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.85)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(0, 120, 212, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            callbacks: {
              label: (context) => {
                const total = (data as number[]).reduce((a, b) => a + b, 0);
                const pct = ((context.parsed / total) * 100).toFixed(1);
                return `${context.label}: ${formatCurrency(context.parsed)} (${pct}%)`;
              },
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);

    return () => {
      chartInstanceRef.current?.destroy();
    };
  }, [chartItems]);

  return (
    <div className="glass-card p-6 mb-6 hover-glow">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm">
            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z" />
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M20.488 9H15V3.512A9.025 9.025 0 0120.488 9z" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Cost Breakdown</h3>
            <p className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest px-1">
              {groupBy === 'SERVICE' ? 'Month-to-Date by Service' : 'Month-to-Date by Region'}
            </p>
          </div>
        </div>

        {/* GroupBy Toggle */}
        <div className="flex items-center gap-2 bg-white/60 backdrop-blur-sm rounded-xl border border-white p-1">
          {(['SERVICE', 'REGION'] as GroupByType[]).map(opt => (
            <button
              key={opt}
              onClick={() => setGroupBy(opt)}
              className={`px-4 py-2 rounded-lg text-xs font-extrabold uppercase tracking-widest transition-all ${
                groupBy === opt
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-slate-500 hover:text-slate-700'
              }`}
            >
              {opt === 'SERVICE' ? 'Service' : 'Region'}
            </button>
          ))}
        </div>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center h-64">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {/* Empty */}
      {!isLoading && chartItems.length === 0 && (
        <div className="flex flex-col items-center justify-center h-48 text-center">
          <div className="w-14 h-14 bg-slate-50 rounded-full flex items-center justify-center mb-4 border border-slate-100">
            <svg className="w-7 h-7 text-slate-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z" />
            </svg>
          </div>
          <p className="text-slate-500 font-bold text-xs uppercase tracking-widest">No Cost Data Available</p>
          <p className="text-slate-400 text-xs mt-1">Data will appear after the next billing refresh</p>
        </div>
      )}

      {/* Chart + Table */}
      {!isLoading && chartItems.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          {/* Donut Chart */}
          <div>
            <h4 className="text-xs font-extrabold text-slate-500 uppercase tracking-widest mb-4">
              <i className="fas fa-chart-pie mr-2 text-blue-500"></i>Cost Distribution
            </h4>
            <div className="h-72">
              <canvas ref={chartRef}></canvas>
            </div>
          </div>

          {/* Table */}
          <div>
            <h4 className="text-xs font-extrabold text-slate-500 uppercase tracking-widest mb-4">
              <i className="fas fa-table mr-2 text-blue-500"></i>Detailed Breakdown
            </h4>
            <div className="overflow-y-auto max-h-72 custom-scrollbar pr-1">
              <table className="w-full text-sm text-left border-separate border-spacing-y-1">
                <thead className="text-[10px] text-slate-400 uppercase tracking-widest font-extrabold">
                  <tr>
                    <th className="px-3 py-2">{groupBy === 'SERVICE' ? 'Service' : 'Region'}</th>
                    <th className="px-3 py-2 text-right">Cost (₹)</th>
                    <th className="px-3 py-2 text-right">%</th>
                  </tr>
                </thead>
                <tbody>
                  {chartItems.map((item, index) => (
                    <tr
                      key={item.name || index}
                      className={`group transition-all duration-200 ${groupBy === 'SERVICE' && onServiceClick ? 'cursor-pointer' : ''}`}
                      onClick={() => groupBy === 'SERVICE' && onServiceClick?.(item.name)}
                    >
                      <td className="px-3 py-2 bg-white/40 group-hover:bg-white rounded-l-xl border-l border-t border-b border-white transition-all duration-200">
                        <div className="flex items-center gap-2">
                          <span
                            className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                            style={{ backgroundColor: COLORS[index % COLORS.length] }}
                          ></span>
                          <span className="text-slate-700 font-bold text-xs truncate max-w-[160px]" title={item.name}>
                            {item.name || '(Unassigned)'}
                          </span>
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right bg-white/40 group-hover:bg-white border-t border-b border-white transition-all duration-200">
                        <span className="font-extrabold text-slate-900 text-xs">{formatCurrency(item.amount)}</span>
                      </td>
                      <td className="px-3 py-2 text-right bg-white/40 group-hover:bg-white rounded-r-xl border-r border-t border-b border-white transition-all duration-200">
                        <span className="text-slate-500 font-bold text-xs">
                          {totalCost > 0 ? ((item.amount / totalCost) * 100).toFixed(1) : '0.0'}%
                        </span>
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
