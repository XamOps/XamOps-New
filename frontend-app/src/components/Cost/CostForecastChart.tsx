/**
 * CostForecastChart Component
 * Displays ML-powered cost forecast predictions
 */

import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler } from 'chart.js';
import type { ForecastData } from '../../types/cost';

// Register Chart.js components
Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler);

interface CostForecastChartProps {
  data: ForecastData | null;
  isLoading: boolean;
  service: string;
  availableServices: string[];
  onServiceChange: (service: string) => void;
}

const KPICard: React.FC<{ title: string; value: string; icon: string; color: string; tooltip: string }> = ({ title, value, tooltip }) => (
  <div className="group relative bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4 shadow-sm flex-1 min-w-[160px] cursor-help">
    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] mb-1.5">{title}</p>
    <p className="text-[22px] font-bold text-[rgba(0,0,0,0.88)] tabular-nums">{value}</p>
    <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-48 p-2 bg-[rgba(0,0,0,0.88)] text-white text-[10px] rounded-lg shadow-lg opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-50 text-center leading-tight">
      {tooltip}
    </div>
  </div>
);

const CostForecastChart: React.FC<CostForecastChartProps> = ({
  data,
  isLoading,
  service,
  availableServices,
  onServiceChange,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'line'> | null>(null);

  // Render chart
  useEffect(() => {
    if (!chartRef.current || !data || isLoading) {
      return;
    }

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: data.dates,
        datasets: [
          {
            label: 'Predicted Cost',
            data: data.predictions,
            borderColor: '#3b82f6', // Primary Blue
            backgroundColor: 'rgba(59, 130, 246, 0.1)',
            borderWidth: 3,
            borderDash: [], // Solid line for predictions
            pointRadius: 3,
            pointBackgroundColor: '#3b82f6',
            fill: true,
            tension: 0.3,
          },
          {
            label: 'Lower Bound',
            data: data.lowerBounds,
            borderColor: 'rgba(59, 130, 246, 0.2)',
            backgroundColor: 'rgba(59, 130, 246, 0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: false,
            tension: 0.3,
          },
          {
            label: 'Upper Bound',
            data: data.upperBounds,
            borderColor: 'rgba(59, 130, 246, 0.2)',
            backgroundColor: 'rgba(59, 130, 246, 0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: '-1', // Fill between Upper and Lower
            tension: 0.3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        font: {
          family: "'Fira Sans', sans-serif"
        },
        interaction: {
          mode: 'index',
          intersect: false,
        },
        plugins: {
          legend: {
            display: true,
            position: 'top',
            align: 'end',
            labels: {
              usePointStyle: true,
              boxWidth: 8,
              padding: 20,
              font: {
                size: 11,
              }
            }
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: '#e2e8f0',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            boxPadding: 4,
            usePointStyle: true,
            callbacks: {
              label: (context) => {
                const value = context.parsed.y;
                if (value === null || value === undefined) return '';
                return `${context.dataset.label}: $${value.toFixed(2)}`;
              },
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: {
              color: 'rgba(226, 232, 240, 0.5)',
            },
            ticks: {
              font: { size: 11 },
              callback: (value) => '$' + Number(value).toLocaleString(),
            },
          },
          x: {
            grid: {
              display: false
            },
            ticks: {
              font: { size: 10 },
              maxRotation: 0,
              autoSkip: true,
              maxTicksLimit: 12
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);

    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
      }
    };
  }, [data, isLoading]);

  // Calculate KPI metrics
  const calculateKPIs = () => {
    if (!data) return null;
    const totalPredicted = data.predictions.reduce((sum, val) => sum + val, 0);
    const peakCost = Math.max(...data.predictions);

    // Average variance (uncertainty range)
    const variances = data.predictions.map((_, i) => data.upperBounds[i] - data.lowerBounds[i]);
    const avgUncertainty = variances.reduce((sum, v) => sum + v, 0) / variances.length;

    return {
      totalPredicted: `$${totalPredicted.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`,
      peakCost: `$${peakCost.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`,
      uncertainty: `±$${(avgUncertainty / 2).toFixed(2)} avg`
    };
  };

  const kpis = calculateKPIs();

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-8">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-crystal-ball mr-2 text-blue-500"></i>
            Cost Forecast
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Predictive analysis for next {data?.dates.length || 30} days</p>
        </div>
        <div className="flex items-center space-x-4 flex-wrap gap-2">
          <select
            value={service}
            onChange={(e) => onServiceChange(e.target.value)}
            className="px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
          >
            <option value="">All Services</option>
            {availableServices.map((svc) => (
              <option key={svc} value={svc}>
                {svc}
              </option>
            ))}
          </select>
        </div>
      </div>

      {!isLoading && kpis && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <KPICard
            title="Total Forecasted"
            value={kpis.totalPredicted}
            icon="fas fa-chart-line"
            color="border-l-blue-500"
            tooltip="Sum of all predicted costs for the next 30 days."
          />
          <KPICard
            title="Forecast Peak"
            value={kpis.peakCost}
            icon="fas fa-arrow-trend-up"
            color="border-l-orange-500"
            tooltip="The highest single-day cost predicted in this forecast."
          />
          <KPICard
            title="Confidence Range"
            value={kpis.uncertainty}
            icon="fas fa-shield-halved"
            color="border-l-indigo-500"
            tooltip="Average daily uncertainty range between upper and lower bounds."
          />
        </div>
      )}

      {!isLoading && data && (
        <div className="h-[400px] w-full relative">
          <canvas ref={chartRef}></canvas>
        </div>
      )}

      {isLoading && (
        <div className="space-y-6">
          <div className="grid grid-cols-3 gap-6">
            <div className="h-24 bg-gray-100 animate-pulse rounded-xl"></div>
            <div className="h-24 bg-gray-100 animate-pulse rounded-xl"></div>
            <div className="h-24 bg-gray-100 animate-pulse rounded-xl"></div>
          </div>
          <div className="animate-pulse bg-gray-100 h-[400px] rounded-xl"></div>
        </div>
      )}

      {!isLoading && !data && (
        <div className="text-center py-12 text-gray-500 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-line text-4xl mb-4 text-gray-300"></i>
          <p className="font-medium text-gray-400">No forecast data available for this selection</p>
        </div>
      )}
    </div>
  );
};

export default CostForecastChart;
