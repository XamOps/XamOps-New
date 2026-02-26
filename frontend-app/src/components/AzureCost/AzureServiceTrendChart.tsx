import React, { useEffect, useRef } from 'react';

declare global {
  interface Window {
    Chart: any;
  }
}

interface AzureServiceTrendChartProps {
  dimension: string;
  data: { labels: string[]; costs: number[] } | null;
  isLoading: boolean;
  onClose: () => void;
}

const formatCurrency = (value: number): string => {
  return '₹' + (value || 0).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const AzureServiceTrendChart: React.FC<AzureServiceTrendChartProps> = ({
  dimension,
  data,
  isLoading,
  onClose,
}) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !data?.labels?.length || !window.Chart) return;

    if (chartRef.current) {
      chartRef.current.destroy();
    }

    chartRef.current = new window.Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: data.labels,
        datasets: [{
          label: 'Monthly Cost',
          data: data.costs,
          backgroundColor: 'rgba(0, 120, 212, 0.8)',
          borderColor: 'rgba(0, 90, 158, 1)',
          borderWidth: 2,
          borderRadius: 8,
          borderSkipped: false,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.85)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(0, 120, 212, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            callbacks: {
              label: (c: any) => `Cost: ${formatCurrency(c.parsed.y)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              callback: (v: any) => formatCurrency(Number(v)),
              color: '#6b7280',
              font: { size: 10 },
            },
            grid: { color: 'rgba(0,0,0,0.04)' },
          },
          x: {
            ticks: { color: '#6b7280', font: { size: 11 } },
            grid: { display: false },
          },
        },
      },
    });

    return () => {
      chartRef.current?.destroy();
    };
  }, [data]);

  return (
    <div className="glass-card p-6 mb-6 hover-glow">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm">
            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Monthly Cost Trend</h3>
            <p className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest px-1">
              Historical data for:{' '}
              <span className="text-blue-600 normal-case font-bold">{dimension}</span>
            </p>
          </div>
        </div>

        <button
          onClick={onClose}
          className="w-9 h-9 flex items-center justify-center rounded-xl bg-white/60 border border-white hover:bg-white transition-all duration-200 text-slate-400 hover:text-slate-700 shadow-sm"
          aria-label="Close"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center h-64 bg-white/40 rounded-2xl border border-white">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {/* Empty */}
      {!isLoading && (!data?.labels?.length) && (
        <div className="flex flex-col items-center justify-center h-64 bg-white/40 rounded-2xl border border-white text-center">
          <div className="w-14 h-14 bg-slate-50 rounded-full flex items-center justify-center mb-4 border border-slate-100">
            <svg className="w-7 h-7 text-slate-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
            </svg>
          </div>
          <p className="text-slate-500 font-bold text-xs uppercase tracking-widest">No Trend Data Available</p>
          <p className="text-slate-400 text-xs mt-1">Data will appear after the next billing refresh</p>
        </div>
      )}

      {/* Chart */}
      {!isLoading && (data?.labels?.length ?? 0) > 0 && (
        <div className="h-64 p-4 bg-white/40 backdrop-blur-sm rounded-2xl border border-white shadow-inner">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureServiceTrendChart;
