/**
 * HistoricalTrendChart Component
 * Displays 6-month historical cost trend for a specific dimension
 */

import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Filler } from 'chart.js';
import type { HistoricalData } from '../../types/cost';

// Register Chart.js components
Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Filler);

interface HistoricalTrendChartProps {
  dimension: string | null;
  data: HistoricalData | null;
  isLoading: boolean;
  onClose: () => void;
}

const HistoricalTrendChart: React.FC<HistoricalTrendChartProps> = ({
  dimension,
  data,
  isLoading,
  onClose,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'line'> | null>(null);

  // Render chart
  useEffect(() => {
    if (!chartRef.current || !data || isLoading) return;

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: data.labels || [],
        datasets: [{
          label: 'Daily Cost',
          data: data.costs || [],
          borderColor: '#10b981',
          backgroundColor: 'rgba(16, 185, 129, 0.1)',
          borderWidth: 3,
          pointRadius: 4,
          pointBackgroundColor: '#10b981',
          fill: true,
          tension: 0.4,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
          mode: 'index',
          intersect: false,
        },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(16, 185, 129, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              callback: (value) => '$' + Number(value).toFixed(0),
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

  if (!dimension) return null;

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center space-x-4">
          <div className="w-10 h-10 bg-gradient-to-br from-orange-500 to-red-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-chart-line text-white"></i>
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-800">6-Month Cost Trend</h2>
            <p className="text-sm text-gray-600">
              Historical data for: <span className="font-bold text-indigo-600">{dimension}</span>
            </p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="text-gray-400 hover:text-gray-600 transition-colors"
        >
          <i className="fas fa-times text-xl"></i>
        </button>
      </div>

      {isLoading && <div className="animate-pulse bg-gray-200 h-80 rounded-xl"></div>}

      {!isLoading && (
        <div className="h-80">
          <canvas ref={chartRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default HistoricalTrendChart;
