import React, { useEffect, useRef } from 'react';
import type { AzureForecastData } from '../../hooks/useAzureCostData';

declare global {
  interface Window {
    Chart: any;
  }
}

interface AzureCostForecastChartProps {
  forecast: AzureForecastData | null;
  isLoading: boolean;
}

const formatCurrency = (value: number): string => {
  return '₹' + (value || 0).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const AzureCostForecastChart: React.FC<AzureCostForecastChartProps> = ({ forecast, isLoading }) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !forecast?.dates?.length || !window.Chart) return;

    if (chartRef.current) {
      chartRef.current.destroy();
    }

    chartRef.current = new window.Chart(canvasRef.current, {
      type: 'line',
      data: {
        labels: forecast.dates,
        datasets: [
          {
            label: 'Predicted Cost',
            data: forecast.predictions,
            borderColor: '#f97316',
            backgroundColor: 'rgba(249, 115, 22, 0.1)',
            borderWidth: 3,
            borderDash: [8, 4],
            pointRadius: 4,
            pointBackgroundColor: '#f97316',
            fill: true,
            tension: 0.4,
          },
          {
            label: 'Upper Bound',
            data: forecast.upperBounds,
            borderColor: 'rgba(34, 197, 94, 0.4)',
            backgroundColor: 'rgba(34, 197, 94, 0.05)',
            borderWidth: 1,
            borderDash: [2, 2],
            pointRadius: 0,
            fill: false,
            tension: 0.4,
          },
          {
            label: 'Lower Bound',
            data: forecast.lowerBounds,
            borderColor: 'rgba(239, 68, 68, 0.4)',
            backgroundColor: 'rgba(239, 68, 68, 0.05)',
            borderWidth: 1,
            borderDash: [2, 2],
            pointRadius: 0,
            fill: false,
            tension: 0.4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
          mode: 'index',
          intersect: false,
        },
        plugins: {
          legend: {
            display: true,
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
            borderColor: 'rgba(249, 115, 22, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            callbacks: {
              label: (c: any) => {
                const value = c.parsed.y;
                if (value == null) return '';
                return `${c.dataset.label}: ${formatCurrency(value)}`;
              },
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
            ticks: {
              color: '#6b7280',
              font: { size: 10 },
              maxRotation: 45,
              minRotation: 0,
            },
            grid: { display: false },
          },
        },
      },
    });

    return () => {
      chartRef.current?.destroy();
    };
  }, [forecast]);

  return (
    <div className="glass-card p-6 hover-glow">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm">
            <svg className="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Cost Forecast</h3>
            <div className="flex items-center gap-2 mt-0.5 px-1">
              <span className="w-2 h-2 bg-purple-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(168,85,247,0.5)]"></span>
              <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">XamOps ML-powered predictions</span>
            </div>
          </div>
        </div>

        {!isLoading && (
          <div className="flex items-center gap-2 bg-white/60 backdrop-blur-sm px-3 py-1.5 rounded-lg border border-white shadow-sm">
            <i className="fas fa-microchip text-purple-500 text-xs animate-pulse"></i>
            <span className="text-[10px] uppercase font-extrabold tracking-widest text-slate-500">All Services</span>
          </div>
        )}
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center h-72 bg-white/40 rounded-2xl border border-white">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {/* Empty / unavailable */}
      {!isLoading && !forecast?.dates?.length && (
        <div className="flex flex-col items-center justify-center h-72 bg-white/40 rounded-2xl border border-white text-center">
          <div className="w-14 h-14 bg-slate-50 rounded-full flex items-center justify-center mb-4 border border-slate-100">
            <svg className="w-7 h-7 text-slate-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
          </div>
          <p className="text-slate-500 font-bold text-xs uppercase tracking-widest">Forecast Unavailable</p>
          <p className="text-slate-400 text-xs mt-1">Forecast service may be offline or insufficient history</p>
        </div>
      )}

      {/* Chart */}
      {!isLoading && (forecast?.dates?.length ?? 0) > 0 && (
        <div className="h-72 p-4 bg-white/40 backdrop-blur-sm rounded-2xl border border-white shadow-inner">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureCostForecastChart;
