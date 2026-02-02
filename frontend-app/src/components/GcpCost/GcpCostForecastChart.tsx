/**
 * GcpCostForecastChart Component
 * Displays ML-powered cost forecast predictions for GCP
 */

import React, { useEffect, useRef, useMemo } from 'react';
import {
  Chart,
  ChartConfiguration,
  LineElement,
  PointElement,
  LineController,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
  Filler
} from 'chart.js';
import type { GcpForecastDataPoint } from '../../types/gcpCost';

// Register Chart.js components
Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler);

interface GcpCostForecastChartProps {
  data: GcpForecastDataPoint[];
  isLoading: boolean;
  service: string;
  periods: number;
  availableServices: string[];
  onServiceChange: (service: string) => void;
  onPeriodsChange: (periods: number) => void;
}

const GcpCostForecastChart: React.FC<GcpCostForecastChartProps> = ({
  data,
  isLoading,
  service,
  periods,
  availableServices,
  onServiceChange,
  onPeriodsChange,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'line'> | null>(null);

  // Process forecast data
  const processedData = useMemo(() => {
    if (!data || data.length === 0) return null;

    const labels = data.map(d => new Date(d.ds).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric'
    }));

    // Identify actuals (where yhat_lower === 0, it means it's actual data)
    const actuals = data.map(d => d.yhat_lower === 0 ? d.yhat : null);
    const forecast = data.map(d => d.yhat);
    const lower = data.map(d => d.yhat_lower);
    const upper = data.map(d => d.yhat_upper);

    return { labels, actuals, forecast, lower, upper };
  }, [data]);

  // Render chart
  useEffect(() => {
    if (!chartRef.current || !processedData || isLoading) {
      return;
    }

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: processedData.labels,
        datasets: [
          {
            label: 'Observed',
            data: processedData.actuals,
            borderColor: '#4285F4',
            backgroundColor: 'rgba(66, 133, 244, 0.1)',
            borderWidth: 2,
            pointRadius: 3,
            pointBackgroundColor: '#4285F4',
            fill: false,
            tension: 0.3,
            spanGaps: false,
          },
          {
            label: 'Forecast',
            data: processedData.forecast,
            borderColor: '#FBBC04',
            backgroundColor: 'rgba(251, 188, 4, 0.1)',
            borderWidth: 2,
            borderDash: [5, 5],
            pointRadius: 3,
            pointBackgroundColor: '#FBBC04',
            fill: false,
            tension: 0.3,
          },
          {
            label: 'Confidence Interval',
            data: processedData.upper,
            borderColor: 'rgba(52, 168, 83, 0.3)',
            backgroundColor: 'rgba(52, 168, 83, 0.1)',
            borderWidth: 1,
            pointRadius: 0,
            fill: '+1',
            tension: 0.3,
          },
          {
            label: 'Lower Bound',
            data: processedData.lower,
            borderColor: 'transparent',
            backgroundColor: 'transparent',
            borderWidth: 0,
            pointRadius: 0,
            fill: false,
            tension: 0.3,
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
              filter: (legendItem) => legendItem.text !== 'Lower Bound',
              boxWidth: 12,
              font: { size: 11, weight: '500' },
              usePointStyle: true,
              padding: 16,
              color: '#374151',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(31, 41, 55, 0.95)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(251, 188, 4, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            callbacks: {
              label: (context) => {
                const value = context.parsed.y;
                if (value === null || value === undefined) return '';
                if (context.dataset.label === 'Lower Bound') return '';
                return `${context.dataset.label}: ${value.toLocaleString('en-IN', {
                  style: 'currency',
                  currency: 'INR',
                  maximumFractionDigits: 2,
                })}`;
              },
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: {
              color: 'rgba(0, 0, 0, 0.05)',
            },
            ticks: {
              callback: (value) => '₹' + Number(value).toLocaleString('en-IN'),
              color: '#6B7280',
              font: { size: 11 },
            },
          },
          x: {
            grid: {
              display: false,
            },
            ticks: {
              maxRotation: 45,
              minRotation: 0,
              color: '#6B7280',
              font: { size: 11 },
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
  }, [processedData, isLoading]);

  return (
    <div className="gcp-cost-forecast-section">
      <div className="gcp-cost-forecast-header">
        <div className="gcp-cost-forecast-title">
          <div className="gcp-cost-forecast-icon">
            <i className="fas fa-chart-line"></i>
          </div>
          <div>
            <h2>Cost Forecast</h2>
            <p>ML-powered cost predictions</p>
          </div>
        </div>

        <div className="gcp-cost-forecast-controls">
          <select
            value={service}
            onChange={(e) => onServiceChange(e.target.value)}
            className="gcp-cost-forecast-select"
          >
            {availableServices.map((svc) => (
              <option key={svc} value={svc}>
                {svc}
              </option>
            ))}
          </select>

          <select
            value={periods}
            onChange={(e) => onPeriodsChange(Number(e.target.value))}
            className="gcp-cost-forecast-select"
          >
            <option value="30">Next 30 Days</option>
            <option value="60">Next 60 Days</option>
            <option value="90">Next 90 Days</option>
          </select>
        </div>
      </div>

      <div className="gcp-cost-forecast-chart-container">
        {isLoading && (
          <div className="gcp-cost-forecast-loading">
            <div className="gcp-cost-spinner"></div>
          </div>
        )}

        {!isLoading && data && data.length > 0 && (
          <canvas ref={chartRef}></canvas>
        )}

        {!isLoading && (!data || data.length === 0) && (
          <div className="gcp-cost-forecast-empty">
            <i className="fas fa-chart-line"></i>
            <p>No forecast data available</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default GcpCostForecastChart;
