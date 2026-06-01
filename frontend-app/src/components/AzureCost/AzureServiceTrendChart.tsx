import React, { useEffect, useRef } from 'react';

declare global {
  interface Window { Chart: any; }
}

interface AzureServiceTrendChartProps {
  dimension: string;
  data: { labels: string[]; costs: number[] } | null;
  isLoading: boolean;
  onClose: () => void;
}

const formatCurrency = (value: number): string =>
  '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const AzureServiceTrendChart: React.FC<AzureServiceTrendChartProps> = ({ dimension, data, isLoading, onClose }) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !data?.labels?.length || !window.Chart) return;
    if (chartRef.current) chartRef.current.destroy();

    chartRef.current = new window.Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: data.labels,
        datasets: [{
          label: 'Monthly Cost',
          data: data.costs,
          backgroundColor: 'rgba(139,92,246,0.8)',
          borderColor: 'rgba(109,40,217,1)',
          borderWidth: 2,
          borderRadius: 6,
          borderSkipped: false,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(255,255,255,0.97)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: 'rgba(139,92,246,0.3)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 10,
            callbacks: { label: (c: any) => `Cost: ${formatCurrency(c.parsed.y)}` },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226,232,240,0.5)' },
            ticks: { callback: (v: any) => '$' + Number(v).toLocaleString(), color: '#6b7280', font: { size: 10 } },
          },
          x: {
            grid: { display: false },
            ticks: { color: '#6b7280', font: { size: 11 } },
          },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-chart-bar mr-2 text-violet-500"></i>
            Monthly Cost Trend
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
            Historical data for: <span className="font-bold text-violet-600">{dimension}</span>
          </p>
        </div>
        <button
          onClick={onClose}
          className="w-8 h-8 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
        >
          <i className="fas fa-times text-[12px]"></i>
        </button>
      </div>

      {isLoading && (
        <div className="animate-pulse bg-gray-100 h-64 rounded-xl"></div>
      )}

      {!isLoading && !data?.labels?.length && (
        <div className="text-center py-10 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-bar text-3xl mb-3 text-gray-300"></i>
          <p className="font-medium text-gray-400 text-[13px]">No trend data available</p>
        </div>
      )}

      {!isLoading && (data?.labels?.length ?? 0) > 0 && (
        <div className="h-64 w-full">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureServiceTrendChart;
