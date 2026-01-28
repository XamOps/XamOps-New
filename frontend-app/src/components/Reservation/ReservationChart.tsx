/**
 * ReservationChart Component
 *
 * Chart displaying historical utilization and coverage trends
 *
 * Converted from: reservation.html (chart section with Chart.js)
 */

import React, { useEffect, useRef } from 'react';
import type { HistoricalData } from '../../types/reservation';

// Chart.js global type (loaded via CDN in index.html)
declare global {
  interface Window {
    Chart: any;
  }
}

interface ReservationChartProps {
  historicalData: HistoricalData;
}

const ReservationChart: React.FC<ReservationChartProps> = ({ historicalData }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<any>(null);

  useEffect(() => {
    if (!canvasRef.current || !historicalData.labels || historicalData.labels.length === 0) {
      return;
    }

    if (!window.Chart) {
      console.error('[ReservationChart] Chart.js not loaded');
      return;
    }

    const ctx = canvasRef.current.getContext('2d');
    if (!ctx) return;

    // Destroy existing chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
      chartInstanceRef.current = null;
    }

    // Create gradients
    const gradientUtil = ctx.createLinearGradient(0, 0, 0, 300);
    gradientUtil.addColorStop(0, 'rgba(79, 70, 229, 0.2)'); // Indigo
    gradientUtil.addColorStop(1, 'rgba(79, 70, 229, 0)');

    const gradientCov = ctx.createLinearGradient(0, 0, 0, 300);
    gradientCov.addColorStop(0, 'rgba(16, 185, 129, 0.2)'); // Green
    gradientCov.addColorStop(1, 'rgba(16, 185, 129, 0)');

    // Create chart
    chartInstanceRef.current = new window.Chart(ctx, {
      type: 'line',
      data: {
        labels: historicalData.labels,
        datasets: [
          {
            label: 'Utilization %',
            data: historicalData.utilizationPercentages,
            borderColor: '#4f46e5',
            backgroundColor: gradientUtil,
            borderWidth: 2,
            pointBackgroundColor: '#fff',
            pointBorderColor: '#4f46e5',
            fill: true,
            tension: 0.4,
          },
          {
            label: 'Coverage %',
            data: historicalData.coveragePercentages,
            borderColor: '#10b981',
            backgroundColor: gradientCov,
            borderWidth: 2,
            pointBackgroundColor: '#fff',
            pointBorderColor: '#10b981',
            fill: true,
            tension: 0.4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'top',
            align: 'end',
            labels: { usePointStyle: true, boxWidth: 8 },
          },
          tooltip: {
            mode: 'index',
            intersect: false,
            backgroundColor: 'rgba(255, 255, 255, 0.9)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: '#e2e8f0',
            borderWidth: 1,
          },
        },
        scales: {
          x: {
            type: 'category',
            grid: { display: false },
          },
          y: {
            beginAtZero: true,
            max: 100,
            grid: { borderDash: [2, 4], color: '#f1f5f9' },
            title: { display: true, text: 'Percentage (%)' },
          },
        },
      },
    });

    // Cleanup
    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
        chartInstanceRef.current = null;
      }
    };
  }, [historicalData]);

  return (
    <div className="glass-card rounded-xl p-5 mb-6 border-t-4 border-t-indigo-500">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
            <i className="fas fa-chart-area text-indigo-600"></i>
            Historical Utilization & Coverage
          </h3>
          <p className="text-xs text-gray-500 mt-1 font-medium">30-day trend analysis</p>
        </div>
        <div className="flex gap-2">
          <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-indigo-50 to-purple-50 flex items-center justify-center">
            <i className="fas fa-chart-line text-indigo-600 text-sm"></i>
          </div>
        </div>
      </div>
      <div className="h-80 relative custom-scrollbar">
        <canvas ref={canvasRef}></canvas>
      </div>
    </div>
  );
};

export default ReservationChart;
