import React, { useEffect, useRef } from 'react';
import type { AzureCostHistory } from '../../types/azureDashboard';

declare global {
  interface Window { Chart: any; }
}

interface AzureCostHistoryChartProps {
  costHistory: AzureCostHistory;
  isLoading: boolean;
}

const formatCurrency = (value: number): string =>
  '₹' + (value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const AzureCostHistoryChart: React.FC<AzureCostHistoryChartProps> = ({ costHistory, isLoading }) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !costHistory?.labels?.length || !window.Chart) return;
    if (chartRef.current) chartRef.current.destroy();

    // The flag marks a projected month, not an anomaly. This chart used to paint those bars red and
    // caption them "⚠ Anomaly detected", so the projected month-end total was reported to the
    // customer as a detected cost problem every single month. Forecast bars are now drawn muted, and
    // real anomalies are the separate costAnomalies list.
    const forecastFlags: boolean[] = costHistory.forecast ?? [];
    const backgroundColors = costHistory.labels.map((_, i) =>
      forecastFlags[i] ? 'rgba(139,92,246,0.35)' : 'rgba(139,92,246,0.8)'
    );
    const borderColors = costHistory.labels.map((_, i) =>
      forecastFlags[i] ? 'rgba(109,40,217,0.5)' : 'rgba(109,40,217,1)'
    );

    chartRef.current = new window.Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: costHistory.labels,
        datasets: [{
          label: 'Monthly Cost',
          data: costHistory.costs,
          backgroundColor: backgroundColors,
          borderColor: borderColors,
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
            callbacks: {
              label: (c: any) => `Cost: ${formatCurrency(c.parsed.y)}`,
              afterLabel: (c: any) => forecastFlags[c.dataIndex] ? 'Projected — not yet billed' : '',
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226,232,240,0.5)' },
            ticks: { callback: (v: any) => '₹' + Number(v).toLocaleString('en-IN'), color: '#6b7280', font: { size: 10 } },
          },
          x: {
            grid: { display: false },
            ticks: { color: '#6b7280', font: { size: 11 } },
          },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [costHistory]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-chart-bar mr-2 text-violet-500"></i>
            Cost History
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">6-month trend with month-end projection</p>
        </div>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm inline-block" style={{ background: 'rgba(139,92,246,0.8)' }}></span>
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.45)]">Actual</span>
          </div>
          {/* Was a red "Anomaly" key. The flag it keyed off marks the projected month, so this
              legend told the customer their forecast was an anomaly. */}
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm inline-block" style={{ background: 'rgba(139,92,246,0.35)' }}></span>
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.45)]">Forecast</span>
          </div>
        </div>
      </div>

      {isLoading && (
        <div className="animate-pulse bg-gray-100 h-72 rounded-xl"></div>
      )}

      {!isLoading && !costHistory?.labels?.length && (
        <div className="text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-bar text-4xl mb-4 text-gray-300"></i>
          <p className="font-medium text-gray-400">No history available</p>
        </div>
      )}

      {!isLoading && costHistory?.labels?.length > 0 && (
        <div className="h-72 w-full">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureCostHistoryChart;
