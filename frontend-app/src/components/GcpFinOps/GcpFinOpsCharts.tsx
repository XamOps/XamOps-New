/**
 * GCP FinOps Charts Component
 *
 * Displays cost breakdown charts for GCP services and regions
 */

import React, { useEffect, useRef } from 'react';
import Chart from 'chart.js/auto';
import type { GcpCostBreakdown } from '../../types/gcpFinops';

interface GcpFinOpsChartsProps {
  costBreakdown: GcpCostBreakdown;
}

const formatCurrency = (value: number): string => {
  return value.toLocaleString('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const GcpFinOpsCharts: React.FC<GcpFinOpsChartsProps> = ({ costBreakdown }) => {
  const serviceChartRef = useRef<HTMLCanvasElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  const serviceChartInstance = useRef<Chart<'doughnut'> | null>(null);
  const regionChartInstance = useRef<Chart<'bar'> | null>(null);

  useEffect(() => {
    // Render Service Chart
    if (serviceChartRef.current) {
      // Destroy previous chart if exists
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
      }

      const serviceData = costBreakdown?.byService || [];
      if (serviceData.length > 0) {
        const ctx = serviceChartRef.current.getContext('2d');
        if (ctx) {
          serviceChartInstance.current = new Chart(ctx, {
            type: 'doughnut',
            data: {
              labels: serviceData.map((d) => d.name),
              datasets: [
                {
                  data: serviceData.map((d) => d.amount),
                  backgroundColor: [
                    '#4285F4',
                    '#34A853',
                    '#FBBC05',
                    '#EA4335',
                    '#667eea',
                    '#764ba2',
                    '#f093fb',
                    '#4facfe',
                    '#00f2fe',
                    '#43e97b',
                  ],
                },
              ],
            },
            options: {
              responsive: true,
              maintainAspectRatio: false,
              plugins: {
                legend: {
                  position: 'right',
                },
                tooltip: {
                  callbacks: {
                    label: (context) => `${context.label}: ${formatCurrency(context.parsed)}`,
                  },
                },
              },
            },
          });
        }
      }
    }

    // Render Region Chart
    if (regionChartRef.current) {
      // Destroy previous chart if exists
      if (regionChartInstance.current) {
        regionChartInstance.current.destroy();
      }

      const regionData = costBreakdown?.byRegion || [];
      if (regionData.length > 0) {
        const ctx = regionChartRef.current.getContext('2d');
        if (ctx) {
          regionChartInstance.current = new Chart(ctx, {
            type: 'bar',
            data: {
              labels: regionData.map((d) => d.name),
              datasets: [
                {
                  label: 'Cost by Region',
                  data: regionData.map((d) => d.amount),
                  backgroundColor: 'rgba(251, 146, 60, 0.8)',
                  borderColor: 'rgba(251, 146, 60, 1)',
                  borderWidth: 2,
                  borderRadius: 8,
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
                tooltip: {
                  callbacks: {
                    label: (context) => `Cost: ${formatCurrency(context.parsed.x)}`,
                  },
                },
              },
            },
          });
        }
      }
    }

    // Cleanup function
    return () => {
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
      }
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
            <p className="text-sm text-gray-600">Top GCP services by spend</p>
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

export default GcpFinOpsCharts;
