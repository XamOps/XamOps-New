import React, { useEffect, useRef } from 'react';
import Chart from 'chart.js/auto';
import type { CostTrend, ProviderCostBreakdown } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

interface AIOpsChartsProps {
  costTrend: CostTrend;
  providerBreakdown: ProviderCostBreakdown[];
}

const AIOpsCharts: React.FC<AIOpsChartsProps> = ({ costTrend, providerBreakdown }) => {
  const costTrendRef = useRef<HTMLCanvasElement>(null);
  const costTrendChart = useRef<Chart | null>(null);
  const providerRef = useRef<HTMLCanvasElement>(null);
  const providerChart = useRef<Chart<'doughnut'> | null>(null);

  // ── Cost Trend Line Chart ──
  useEffect(() => {
    if (!costTrendRef.current || !costTrend) return;

    if (costTrendChart.current) costTrendChart.current.destroy();

    const ctx = costTrendRef.current.getContext('2d');
    if (!ctx) return;

    // Create gradient fill
    const gradient = ctx.createLinearGradient(0, 0, 0, 300);
    gradient.addColorStop(0, 'rgba(99, 102, 241, 0.3)');
    gradient.addColorStop(1, 'rgba(99, 102, 241, 0.0)');

    costTrendChart.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: costTrend.labels,
        datasets: [{
          label: 'Daily Cost ($)',
          data: costTrend.costs,
          backgroundColor: gradient,
          borderColor: '#6366f1',
          borderWidth: 2.5,
          tension: 0.4,
          fill: true,
          pointRadius: 3,
          pointBackgroundColor: '#6366f1',
          pointHoverRadius: 6,
          pointHoverBackgroundColor: '#4f46e5',
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            titleFont: { size: 12 },
            bodyFont: { size: 13 },
            padding: 12,
            cornerRadius: 8,
            callbacks: {
              label: (ctx) => `Cost: $${ctx.parsed.y.toFixed(4)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226, 232, 240, 0.5)' },
            ticks: {
              callback: (value) => '$' + Number(value).toFixed(2),
              font: { size: 11 },
              color: '#94a3b8',
            },
          },
          x: {
            grid: { display: false },
            ticks: {
              maxRotation: 45,
              font: { size: 10 },
              color: '#94a3b8',
            },
          },
        },
      },
    });

    return () => { costTrendChart.current?.destroy(); };
  }, [costTrend]);

  // ── Provider Breakdown Doughnut ──
  useEffect(() => {
    if (!providerRef.current || !providerBreakdown.length) return;

    if (providerChart.current) providerChart.current.destroy();

    const ctx = providerRef.current.getContext('2d');
    if (!ctx) return;

    const colors = providerBreakdown.map(p =>
      PROVIDER_COLORS[p.provider] || '#94a3b8'
    );

    providerChart.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: providerBreakdown.map(p => p.provider),
        datasets: [{
          data: providerBreakdown.map(p => p.cost),
          backgroundColor: colors,
          borderWidth: 0,
          hoverOffset: 8,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '65%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 16,
              font: { size: 12 },
              color: '#64748b',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(15, 23, 42, 0.9)',
            padding: 12,
            cornerRadius: 8,
            callbacks: {
              label: (ctx) => {
                const total = ctx.dataset.data.reduce((a: number, b: number) => a + b, 0);
                const pct = total > 0 ? ((ctx.parsed / total) * 100).toFixed(1) : '0';
                return ` ${ctx.label}: $${ctx.parsed.toFixed(4)} (${pct}%)`;
              },
            },
          },
        },
      },
    });

    return () => { providerChart.current?.destroy(); };
  }, [providerBreakdown]);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Cost Trend */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-chart-line text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Cost Trend</h3>
        </div>
        <div className="h-64">
          {costTrend && costTrend.labels.length > 0 ? (
            <canvas ref={costTrendRef} />
          ) : (
            <div className="flex items-center justify-center h-full text-gray-400 text-sm">
              <div className="text-center">
                <i className="fas fa-chart-line text-3xl mb-2 opacity-30" />
                <p>No cost data yet</p>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Provider Breakdown */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-pink-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-chart-pie text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Provider Breakdown</h3>
        </div>
        <div className="h-64">
          {providerBreakdown.length > 0 ? (
            <canvas ref={providerRef} />
          ) : (
            <div className="flex items-center justify-center h-full text-gray-400 text-sm">
              <div className="text-center">
                <i className="fas fa-chart-pie text-3xl mb-2 opacity-30" />
                <p>No provider data yet</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default AIOpsCharts;
