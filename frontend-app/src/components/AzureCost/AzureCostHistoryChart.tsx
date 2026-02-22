import React, { useEffect, useRef } from 'react';
import type { AzureCostHistory } from '../../types/azureDashboard';

declare global {
  interface Window {
    Chart: any;
  }
}

interface AzureCostHistoryChartProps {
  costHistory: AzureCostHistory;
  isLoading: boolean;
}

const formatCurrency = (value: number): string => {
  return '₹' + (value || 0).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const AzureCostHistoryChart: React.FC<AzureCostHistoryChartProps> = ({ costHistory, isLoading }) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !costHistory?.labels?.length || !window.Chart) return;

    if (chartRef.current) {
      chartRef.current.destroy();
    }

    const backgroundColors = costHistory.anomalies.map(isAnomaly =>
      isAnomaly ? 'rgba(244, 63, 94, 0.8)' : 'rgba(0, 120, 212, 0.8)'
    );
    const borderColors = costHistory.anomalies.map(isAnomaly =>
      isAnomaly ? 'rgba(225, 29, 72, 1)' : 'rgba(0, 90, 158, 1)'
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
              afterLabel: (c: any) =>
                costHistory.anomalies[c.dataIndex] ? '⚠ Forecast / Anomaly' : '',
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              callback: (v: any) => formatCurrency(v),
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
  }, [costHistory]);

  return (
    <div className="glass-card p-6 hover-glow">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm">
            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Cost History</h3>
            <div className="flex items-center gap-2 mt-0.5 px-1">
              <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(59,130,246,0.5)]"></span>
              <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">6-Month Trend</span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <span className="inline-block w-3 h-3 rounded-sm bg-blue-500 opacity-80"></span>
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Actual</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block w-3 h-3 rounded-sm bg-rose-500 opacity-80"></span>
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Forecast</span>
          </div>
          {!isLoading && (
            <div className="flex items-center gap-2 bg-white/60 backdrop-blur-sm px-3 py-1.5 rounded-lg border border-white shadow-sm">
              <i className="fas fa-microchip text-blue-500 text-xs animate-pulse"></i>
              <span className="text-[10px] uppercase font-extrabold tracking-widest text-slate-500">ML Anomaly Detection</span>
            </div>
          )}
        </div>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center h-72 bg-white/40 rounded-2xl border border-white">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {!isLoading && (!costHistory?.labels?.length) && (
        <div className="flex flex-col items-center justify-center h-72 bg-white/40 rounded-2xl border border-white text-center">
          <div className="w-14 h-14 bg-slate-50 rounded-full flex items-center justify-center mb-4 border border-slate-100">
            <svg className="w-7 h-7 text-slate-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
          </div>
          <p className="text-slate-500 font-bold text-xs uppercase tracking-widest">No History Available</p>
          <p className="text-slate-400 text-xs mt-1">Data will appear after the next billing ingestion</p>
        </div>
      )}

      {!isLoading && costHistory?.labels?.length > 0 && (
        <div className="h-72 p-4 bg-white/40 backdrop-blur-sm rounded-2xl border border-white shadow-inner">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureCostHistoryChart;
