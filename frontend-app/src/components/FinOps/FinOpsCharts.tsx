/**
 * FinOps Charts Component
 *
 * Displays cost breakdown charts using Chart.js
 */

import React, { useEffect, useRef } from 'react';
import Chart from 'chart.js/auto';
import type { CostBreakdown } from '../../types/finops';

interface FinOpsChartsProps {
  costBreakdown: CostBreakdown;
}

const FinOpsCharts: React.FC<FinOpsChartsProps> = ({ costBreakdown }) => {
  const serviceChartRef = useRef<HTMLCanvasElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  const serviceChartInstance = useRef<Chart<'doughnut'> | null>(null);
  const regionChartInstance = useRef<Chart<'bar'> | null>(null);

  const handleChartClick = (event: any, chart: any, type: string) => {
    const points = chart.getElementsAtEventForMode(event, 'nearest', { intersect: true }, true);
    if (points.length) {
      const firstPoint = points[0];
      const label = chart.data.labels?.[firstPoint.index];
      alert(`Chart clicked! You selected ${type}: ${label}. You would now be redirected to a filtered view.`);
    }
  };

  // Render Service Chart
  useEffect(() => {
    if (!serviceChartRef.current) return;

    // Destroy previous chart
    if (serviceChartInstance.current) {
      serviceChartInstance.current.destroy();
    }

    const data = costBreakdown?.byService || [];
    const ctx = serviceChartRef.current.getContext('2d');

    if (ctx) {
      serviceChartInstance.current = new Chart(ctx, {
        type: 'doughnut',
        data: {
          labels: data.map((d) => d.service),
          datasets: [
            {
              data: data.map((d) => d.cost),
              backgroundColor: [
                '#667eea',
                '#764ba2',
                '#f093fb',
                '#f5576c',
                '#4facfe',
                '#00f2fe',
                '#43e97b',
                '#38f9d7',
                '#ffecd2',
                '#fcb69f',
                '#a8edea',
                '#fed6e3',
              ],
              borderWidth: 0,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              position: 'right',
              labels: {
                usePointStyle: true,
              },
            },
          },
          onClick: (e) => {
            if (serviceChartInstance.current) {
              handleChartClick(e, serviceChartInstance.current, 'SERVICE');
            }
          },
        },
      });
    }

    return () => {
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
      }
    };
  }, [costBreakdown]);

  // Render Region Chart
  useEffect(() => {
    if (!regionChartRef.current) return;

    // Destroy previous chart
    if (regionChartInstance.current) {
      regionChartInstance.current.destroy();
    }

    const data = costBreakdown?.byRegion || [];
    const ctx = regionChartRef.current.getContext('2d');

    if (ctx) {
      regionChartInstance.current = new Chart(ctx, {
        type: 'bar',
        data: {
          labels: data.map((d) => d.region),
          datasets: [
            {
              label: 'Cost by Region',
              data: data.map((d) => d.cost),
              backgroundColor: 'rgba(251, 146, 60, 0.8)',
              borderColor: 'rgba(251, 146, 60, 1)',
              borderWidth: 2,
              borderRadius: 8,
              borderSkipped: false,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          indexAxis: 'y',
          plugins: {
            legend: {
              display: false,
            },
          },
          scales: {
            x: {
              grid: {
                color: 'rgba(0, 0, 0, 0.05)',
              },
              ticks: {
                color: '#6b7280',
              },
            },
            y: {
              grid: {
                display: false,
              },
              ticks: {
                color: '#6b7280',
              },
            },
          },
          onClick: (e) => {
            if (regionChartInstance.current) {
              handleChartClick(e, regionChartInstance.current, 'REGION');
            }
          },
        },
      });
    }

    return () => {
      if (regionChartInstance.current) {
        regionChartInstance.current.destroy();
      }
    };
  }, [costBreakdown]);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
      {/* Cost by Service Chart */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-chart-pie text-white"></i>
          </div>
          <div>
            <h3 className="text-lg font-bold text-gray-800">Cost by Service</h3>
            <p className="text-sm text-gray-600">Top 10 services</p>
          </div>
        </div>
        <div className="relative h-80">
          <canvas ref={serviceChartRef}></canvas>
        </div>
      </div>

      {/* Cost by Region Chart */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-gradient-to-br from-orange-500 to-red-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-globe text-white"></i>
          </div>
          <div>
            <h3 className="text-lg font-bold text-gray-800">Cost by Region</h3>
            <p className="text-sm text-gray-600">Geographic distribution</p>
          </div>
        </div>
        <div className="relative h-80">
          <canvas ref={regionChartRef}></canvas>
        </div>
      </div>
    </div>
  );
};

export default FinOpsCharts;
