import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler } from 'chart.js';
import type { AzureForecastData } from '../../hooks/useAzureCostData';

Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler);

interface AzureCostForecastChartProps {
  forecast: AzureForecastData | null;
  isLoading: boolean;
}

const formatCurrency = (value: number): string =>
  '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const AzureCostForecastChart: React.FC<AzureCostForecastChartProps> = ({ forecast, isLoading }) => {
  const chartRef = useRef<Chart<'line'> | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!canvasRef.current || !forecast?.dates?.length) return;
    if (chartRef.current) chartRef.current.destroy();

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: forecast.dates,
        datasets: [
          {
            label: 'Predicted Cost',
            data: forecast.predictions,
            borderColor: '#10b981',
            backgroundColor: 'rgba(16,185,129,0.1)',
            borderWidth: 3,
            borderDash: [],
            pointRadius: 3,
            pointBackgroundColor: '#10b981',
            fill: true,
            tension: 0.3,
          },
          {
            label: 'Lower Bound',
            data: forecast.lowerBounds,
            borderColor: 'rgba(16,185,129,0.25)',
            backgroundColor: 'rgba(16,185,129,0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: false,
            tension: 0.3,
          },
          {
            label: 'Upper Bound',
            data: forecast.upperBounds,
            borderColor: 'rgba(16,185,129,0.25)',
            backgroundColor: 'rgba(16,185,129,0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: '-1',
            tension: 0.3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: {
            display: true,
            position: 'top',
            align: 'end',
            labels: {
              boxWidth: 8,
              font: { size: 11 },
              usePointStyle: true,
              padding: 20,
              color: 'rgba(0,0,0,0.55)',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255,255,255,0.97)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: 'rgba(16,185,129,0.3)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            boxPadding: 4,
            usePointStyle: true,
            callbacks: {
              label: (c: any) => {
                const v = c.parsed.y;
                if (v == null) return '';
                return `${c.dataset.label}: ${formatCurrency(v)}`;
              },
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226,232,240,0.5)' },
            ticks: { font: { size: 11 }, callback: (v) => '$' + Number(v).toLocaleString() },
          },
          x: {
            grid: { display: false },
            ticks: { font: { size: 10 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 },
          },
        },
      },
    };

    chartRef.current = new Chart(canvasRef.current, config);
    return () => { chartRef.current?.destroy(); };
  }, [forecast]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-chart-line mr-2 text-emerald-500"></i>
            Cost Forecast
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
            ML-powered predictions for next {forecast?.dates?.length || 30} days
          </p>
        </div>
      </div>

      {isLoading && (
        <div className="animate-pulse bg-gray-100 h-72 rounded-xl"></div>
      )}

      {!isLoading && !forecast?.dates?.length && (
        <div className="text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-line text-4xl mb-4 text-gray-300"></i>
          <p className="font-medium text-gray-400">Forecast unavailable</p>
          <p className="text-[11px] text-gray-400 mt-1">Insufficient history or service offline</p>
        </div>
      )}

      {!isLoading && (forecast?.dates?.length ?? 0) > 0 && (
        <div className="h-72 w-full">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureCostForecastChart;
