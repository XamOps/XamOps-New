import React, { useEffect, useRef, useState } from 'react';
import { Chart, registerables } from 'chart.js';
import type { HistoryDataPoint, HistorySummary } from '../../hooks/useSpotAutomationData';

Chart.register(...registerables);

interface SpotAutomationChartProps {
  historyData: HistoryDataPoint[];
  historySummary: HistorySummary;
  timeRange: 'last24h' | 'last7d' | 'last30d';
  isLoading: boolean;
  onTimeRangeChange: (range: 'last24h' | 'last7d' | 'last30d') => void;
  onRefresh: () => void;
}

const SpotAutomationChart: React.FC<SpotAutomationChartProps> = ({
  historyData,
  historySummary,
  timeRange,
  isLoading,
  onTimeRangeChange,
  onRefresh,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart | null>(null);

  const formatCurrency = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  const formatHourlyCost = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 4,
      maximumFractionDigits: 4,
    }).format(value);
  };

  useEffect(() => {
    if (!chartRef.current || !historyData || historyData.length === 0) {
      return;
    }

    // Destroy existing chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    // Sort data by timestamp
    const sortedData = [...historyData].sort(
      (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
    );

    // Format labels
    const labels = sortedData.map((d) => {
      const date = new Date(d.timestamp);
      if (timeRange === 'last24h') {
        return date.toLocaleTimeString([], { hour: 'numeric', hour12: true });
      } else if (timeRange === 'last7d') {
        const day = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const time = date.toLocaleTimeString([], { hour: 'numeric', hour12: true });
        return `${day}, ${time}`;
      } else {
        return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
      }
    });

    const actualSavings = sortedData.map((d) => d.total_actual_savings || 0);
    const potentialSavings = sortedData.map((d) => d.total_potential_savings || 0);

    // Create new chart
    chartInstanceRef.current = new Chart(chartRef.current, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Actual Savings',
            data: actualSavings,
            borderColor: '#10b981',
            backgroundColor: 'rgba(16, 185, 129, 0.1)',
            borderWidth: 3,
            tension: 0.4,
            fill: true,
            pointBackgroundColor: '#10b981',
            pointRadius: 3,
            pointHoverRadius: 6,
          },
          {
            label: 'Potential Savings',
            data: potentialSavings,
            borderColor: '#f59e0b',
            backgroundColor: 'transparent',
            borderWidth: 3,
            borderDash: [8, 4],
            tension: 0.4,
            pointBackgroundColor: '#f59e0b',
            pointRadius: 3,
            pointHoverRadius: 6,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'top',
            align: 'center',
            labels: {
              usePointStyle: true,
              boxWidth: 10,
              font: {
                family: "'Inter', sans-serif",
                size: 12,
                weight: '600',
              },
              padding: 15,
            },
          },
          tooltip: {
            mode: 'index',
            intersect: false,
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleFont: {
              size: 13,
              weight: 'bold',
            },
            bodyFont: {
              size: 12,
            },
            padding: 12,
            cornerRadius: 8,
            callbacks: {
              label: function (context) {
                let label = context.dataset.label || '';
                if (label) {
                  label += ': ';
                }
                if (context.parsed.y !== null) {
                  label += new Intl.NumberFormat('en-US', {
                    style: 'currency',
                    currency: 'USD',
                  }).format(context.parsed.y);
                }
                return label;
              },
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: {
              color: '#f3f4f6',
              drawBorder: false,
            },
            ticks: {
              callback: function (value) {
                return '$' + value;
              },
              font: {
                size: 11,
              },
              color: '#6b7280',
            },
          },
          x: {
            grid: {
              display: false,
            },
            ticks: {
              font: {
                size: 11,
              },
              color: '#6b7280',
              maxTicksLimit: 8,
            },
          },
        },
      },
    });

    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
      }
    };
  }, [historyData, timeRange]);

  return (
    <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100 mb-6">
      <div className="flex justify-between items-center mb-6">
        <h3 className="text-xl font-bold text-gray-800">Savings Over Time</h3>
        <div className="flex items-center space-x-3">
          <select
            value={timeRange}
            onChange={(e) => onTimeRangeChange(e.target.value as any)}
            className="bg-white border-2 border-gray-300 rounded-lg text-sm px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all"
          >
            <option value="last24h">Last 24 Hours</option>
            <option value="last7d">Last 7 Days</option>
            <option value="last30d">Last 30 Days</option>
          </select>
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="p-2 text-gray-600 hover:text-gray-800 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors disabled:opacity-50"
          >
            <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
          </button>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-6 mb-6">
        <div className="bg-gradient-to-br from-emerald-50 to-emerald-100 p-4 rounded-lg">
          <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-1">
            Period Savings
          </p>
          <h4 className="text-2xl font-bold text-emerald-700">
            {formatCurrency(historySummary.total_savings || 0)}
          </h4>
        </div>
        <div className="bg-gradient-to-br from-blue-50 to-blue-100 p-4 rounded-lg">
          <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-1">
            Avg Hourly
          </p>
          <h4 className="text-2xl font-bold text-blue-700">
            {formatHourlyCost(historySummary.average_hourly_savings || 0)}/hr
          </h4>
        </div>
        <div className="bg-gradient-to-br from-purple-50 to-purple-100 p-4 rounded-lg">
          <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-1">
            Peak Savings
          </p>
          <h4 className="text-2xl font-bold text-purple-700">
            {formatHourlyCost(historySummary.peak_savings || 0)}/hr
          </h4>
        </div>
      </div>

      <div className="relative h-80 w-full">
        <canvas ref={chartRef}></canvas>
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-white bg-opacity-90 z-10 rounded-lg">
            <i className="fas fa-spinner fa-spin text-4xl text-blue-500"></i>
          </div>
        )}
        {!isLoading && (!historyData || historyData.length === 0) && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-white z-0 rounded-lg">
            <i className="fas fa-chart-line text-5xl text-gray-300 mb-4"></i>
            <p className="text-gray-500 font-medium text-lg">No history data available for this period</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default SpotAutomationChart;
