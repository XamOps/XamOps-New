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

const KPICard: React.FC<{ title: string; value: string; icon: string; color: string; tooltip: string }> = ({ title, value, icon, color, tooltip }) => (
  <div className={`group relative bg-white border-l-4 ${color} rounded-xl p-4 shadow-sm flex items-center space-x-4 flex-1 min-w-[200px] cursor-help transition-all duration-200 hover:shadow-md`}>
    <div className={`w-10 h-10 rounded-full flex items-center justify-center bg-gray-50 text-xl`}>
      <i className={`${icon} ${color.replace('border-l-', 'text-')}`}></i>
    </div>
    <div>
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">{title}</p>
      <p className="text-lg font-bold text-gray-800">{value}</p>
    </div>

    {/* Tooltip */}
    <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-48 p-2 bg-gray-900 text-white text-[10px] rounded shadow-lg opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-50 text-center leading-tight">
      {tooltip}
      <div className="absolute top-full left-1/2 -translate-x-1/2 border-8 border-transparent border-t-gray-900"></div>
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
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md mb-8">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-8">
        <div>
          <h2 className="text-xl font-bold text-gray-800">
            <i className="fas fa-crystal-ball mr-2 text-blue-500"></i>
            Cost Forecast
          </h2>
          <p className="text-sm text-gray-600 font-medium">Predictive analysis for next {data?.dates.length || 30} days</p>
        </div>
        <div className="flex items-center space-x-4 flex-wrap gap-2">
          <select
            value={service}
            onChange={(e) => onServiceChange(e.target.value)}
            className="px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm font-medium bg-white"
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
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
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
