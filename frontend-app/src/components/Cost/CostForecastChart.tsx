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
            data: data.upperBounds,
            borderColor: 'rgba(34, 197, 94, 0.3)',
            backgroundColor: 'rgba(34, 197, 94, 0.05)',
            borderWidth: 1,
            borderDash: [2, 2],
            pointRadius: 0,
            fill: '+1',
            tension: 0.4,
          },
          {
            label: 'Lower Bound',
            data: data.lowerBounds,
            borderColor: 'rgba(239, 68, 68, 0.3)',
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
          },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(249, 115, 22, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
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
            ticks: {
              callback: (value) => '$' + Number(value).toFixed(0),
            },
          },
          x: {
            ticks: {
              maxRotation: 45,
              minRotation: 0,
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

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md mb-8">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div>
          <h2 className="text-xl font-bold text-gray-800">
            <i className="fas fa-crystal-ball mr-2 text-purple-500"></i>
            Cost Forecast
          </h2>
          <p className="text-sm text-gray-600">XamOps ML-powered cost predictions</p>
        </div>
        <div className="flex items-center space-x-4 flex-wrap gap-2">
          <select
            value={service}
            onChange={(e) => onServiceChange(e.target.value)}
            className="px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
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

      {!isLoading && data && (
        <div className="h-[450px] w-full relative">
          <canvas ref={chartRef}></canvas>
        </div>
      )}

      {isLoading && <div className="animate-pulse bg-gray-200 h-[450px] rounded-xl"></div>}

      {!isLoading && !data && (
        <div className="text-center py-12 text-gray-500">
          <i className="fas fa-chart-line text-4xl mb-4"></i>
          <p>No forecast data available</p>
        </div>
      )}
    </div>
  );
};

export default CostForecastChart;
