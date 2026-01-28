/**
 * ProviderDonutChart Component
 *
 * Donut chart showing current month's provider distribution
 * Uses Chart.js for visualization with center text plugin
 */

import React, { useEffect, useRef } from 'react';
import { Chart, registerables } from 'chart.js';
import type { ProviderData } from '../../types/dashboard';

// Register Chart.js components
Chart.register(...registerables);

interface ProviderDonutChartProps {
  awsData: ProviderData | null;
  gcpData: ProviderData | null;
  azureData: ProviderData | null;
}

export const ProviderDonutChart: React.FC<ProviderDonutChartProps> = ({ awsData, gcpData, azureData }) => {
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

    const awsSpend = awsData?.selectedAccount?.monthToDateSpend || 0;
    const gcpSpend = gcpData?.selectedAccount?.monthToDateSpend || 0;
    const azureSpend = azureData?.selectedAccount?.monthToDateSpend || 0;

    // Center text plugin
    const centerTextPlugin = {
      id: 'centerText',
      beforeDraw: (chart: Chart) => {
        const width = chart.width;
        const height = chart.height;
        const ctx = chart.ctx;
        ctx.restore();

        const total = chart.data.datasets[0].data.reduce((a: number, b: number) => a + b, 0) as number;
        const fontSize = (height / 180).toFixed(2);
        ctx.font = `bold ${fontSize}em Inter, sans-serif`;
        ctx.fillStyle = '#1F2937';
        ctx.textBaseline = 'middle';

        const text = '$' + total.toLocaleString();
        const textX = Math.round((width - ctx.measureText(text).width) / 2);
        const textY = height / 2 - 10;

        ctx.fillText(text, textX, textY);

        ctx.font = `${parseFloat(fontSize) * 0.4}em Inter, sans-serif`;
        ctx.fillStyle = '#6B7280';
        const subText = 'Total Spend';
        const subTextX = Math.round((width - ctx.measureText(subText).width) / 2);
        const subTextY = height / 2 + 20;

        ctx.fillText(subText, subTextX, subTextY);
        ctx.save();
      },
    };

    // Create chart
    chartRef.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: ['AWS', 'GCP', 'Azure'],
        datasets: [
          {
            data: [awsSpend, gcpSpend, azureSpend],
            backgroundColor: ['rgba(255, 153, 0, 0.85)', 'rgba(52, 168, 83, 0.85)', 'rgba(0, 120, 212, 0.85)'],
            borderColor: ['#fff', '#fff', '#fff'],
            borderWidth: 3,
            hoverOffset: 15,
            hoverBorderWidth: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '75%',
        animation: {
          animateRotate: true,
          animateScale: true,
          duration: 1500,
          easing: 'easeInOutQuart',
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
              generateLabels: function (chart) {
                const data = chart.data;
                if (data.labels && data.labels.length && data.datasets.length) {
                  return data.labels.map((label, i) => {
                    const value = data.datasets[0].data[i] as number;
                    const total = (data.datasets[0].data as number[]).reduce((a, b) => a + b, 0);
                    const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0';
                    return {
                      text: `${label} (${percentage}%)`,
                      fillStyle: (data.datasets[0].backgroundColor as string[])[i],
                      hidden: false,
                      index: i,
                    };
                  });
                }
                return [];
              },
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
                const label = context.label || '';
                const value = context.parsed;
                const total = (context.dataset.data as number[]).reduce((a, b) => a + b, 0);
                const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0';
                return `${label}: $${value.toLocaleString()} (${percentage}%)`;
              },
            },
          },
        },
      },
      plugins: [centerTextPlugin],
    });

    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
      }
    };
  }, [awsData, gcpData, azureData]);

  return (
    <div className="glass-card rounded-xl p-6 flex flex-col border-t-4 border-t-purple-500">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
            <i className="fas fa-chart-pie text-purple-600"></i>
            Provider Distribution
          </h3>
          <p className="text-xs text-gray-500 mt-1 font-medium">Current month breakdown</p>
        </div>
      </div>
      <div className="relative w-full flex items-center justify-center py-4" style={{ height: '300px' }}>
        <canvas ref={canvasRef}></canvas>
      </div>
    </div>
  );
};
