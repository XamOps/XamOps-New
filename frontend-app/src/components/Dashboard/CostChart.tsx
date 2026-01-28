/**
 * CostChart Component
 *
 * Stacked bar chart showing cost history across AWS, GCP, and Azure
 * Uses Chart.js for visualization
 */

import React, { useEffect, useRef } from 'react';
import { Chart, registerables } from 'chart.js';
import type { ProviderData } from '../../types/dashboard';

// Register Chart.js components
Chart.register(...registerables);

interface CostChartProps {
  awsData: ProviderData | null;
  gcpData: ProviderData | null;
  azureData: ProviderData | null;
}

export const CostChart: React.FC<CostChartProps> = ({ awsData, gcpData, azureData }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current) return;

    const ctx = canvasRef.current.getContext('2d');
    if (!ctx) return;

    // Destroy existing chart
    if (chartRef.current) {
      chartRef.current.destroy();
    }

    // Get labels
    let labels: string[] = [];
    if (awsData?.selectedAccount?.costHistory?.labels) {
      labels = awsData.selectedAccount.costHistory.labels;
    } else if (gcpData?.selectedAccount?.costHistory?.labels) {
      labels = gcpData.selectedAccount.costHistory.labels;
    } else if (azureData?.selectedAccount?.costHistory?.labels) {
      labels = azureData.selectedAccount.costHistory.labels;
    }

    if (labels.length === 0) {
      labels = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'];
    }

    // Helper to get costs
    const getCosts = (data: ProviderData | null) => {
      if (!data?.selectedAccount?.costHistory?.costs) {
        return new Array(labels.length).fill(0);
      }
      return data.selectedAccount.costHistory.costs;
    };

    // Create gradients
    const awsGradient = ctx.createLinearGradient(0, 0, 0, 400);
    awsGradient.addColorStop(0, 'rgba(255, 153, 0, 0.8)');
    awsGradient.addColorStop(1, 'rgba(255, 153, 0, 0.4)');

    const gcpGradient = ctx.createLinearGradient(0, 0, 0, 400);
    gcpGradient.addColorStop(0, 'rgba(52, 168, 83, 0.8)');
    gcpGradient.addColorStop(1, 'rgba(52, 168, 83, 0.4)');

    const azureGradient = ctx.createLinearGradient(0, 0, 0, 400);
    azureGradient.addColorStop(0, 'rgba(0, 120, 212, 0.8)');
    azureGradient.addColorStop(1, 'rgba(0, 120, 212, 0.4)');

    // Create chart
    chartRef.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'AWS',
            data: getCosts(awsData),
            backgroundColor: awsGradient,
            borderRadius: 8,
            borderSkipped: false,
          },
          {
            label: 'GCP',
            data: getCosts(gcpData),
            backgroundColor: gcpGradient,
            borderRadius: 8,
            borderSkipped: false,
          },
          {
            label: 'Azure',
            data: getCosts(azureData),
            backgroundColor: azureGradient,
            borderRadius: 8,
            borderSkipped: false,
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
        animation: {
          duration: 1500,
          easing: 'easeInOutQuart',
        },
        scales: {
          x: {
            stacked: true,
            grid: {
              display: false,
            },
            ticks: {
              font: {
                size: 12,
                weight: '600',
              },
              color: '#6B7280',
            },
          },
          y: {
            stacked: true,
            grid: {
              color: '#F3F4F6',
              drawBorder: false,
            },
            ticks: {
              font: {
                size: 12,
              },
              color: '#6B7280',
              callback: function (value) {
                return '$' + Number(value).toLocaleString();
              },
            },
          },
        },
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 15,
              font: {
                size: 13,
                weight: '600',
              },
              color: '#374151',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            padding: 12,
            titleFont: {
              size: 14,
              weight: 'bold',
            },
            bodyFont: {
              size: 13,
            },
            borderColor: 'rgba(255, 255, 255, 0.1)',
            borderWidth: 1,
            callbacks: {
              label: function (context) {
                let label = context.dataset.label || '';
                if (label) {
                  label += ': ';
                }
                label += '$' + context.parsed.y.toLocaleString();
                return label;
              },
            },
          },
        },
      },
    });

    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
      }
    };
  }, [awsData, gcpData, azureData]);

  return (
    <div className="lg:col-span-2 glass-card rounded-xl p-6 flex flex-col border-t-4 border-t-indigo-500">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
            <i className="fas fa-chart-bar text-indigo-600"></i>
            Multi-Cloud Spend Trend
          </h3>
          <p className="text-xs text-gray-500 mt-1 font-medium">Monthly cost analysis across providers</p>
        </div>
        <div className="flex gap-2">
          <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-indigo-50 to-purple-50 flex items-center justify-center">
            <i className="fas fa-chart-line text-indigo-600 text-sm"></i>
          </div>
        </div>
      </div>
      <div className="relative w-full" style={{ height: '350px' }}>
        <canvas ref={canvasRef}></canvas>
      </div>
    </div>
  );
};
