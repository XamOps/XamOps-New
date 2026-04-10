import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import type { ComputeMetricsSummary } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

Chart.register(...registerables);

interface ComputeMetricsSectionProps {
  data: ComputeMetricsSummary | null;
  loading: boolean;
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return n.toLocaleString();
}

function formatTime(sec: number): string {
  if (sec >= 3600) return (sec / 3600).toFixed(1) + 'h';
  if (sec >= 60) return (sec / 60).toFixed(1) + 'm';
  return sec.toFixed(1) + 's';
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  return '$' + value.toFixed(2);
}

// ─── Daily Trend Chart ───────────────────────────────────────
function DailyTrendChart({ data }: { data: ComputeMetricsSummary }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || !data.dailyTrend) return;
    chartRef.current?.destroy();

    chartRef.current = new Chart(canvasRef.current, {
      type: 'line',
      data: {
        labels: data.dailyTrend.labels,
        datasets: [
          {
            label: 'Input Tokens',
            data: data.dailyTrend.inputTokens,
            borderColor: '#6366f1',
            backgroundColor: 'rgba(99, 102, 241, 0.1)',
            fill: true,
            tension: 0.3,
            yAxisID: 'y',
          },
          {
            label: 'Output Tokens',
            data: data.dailyTrend.outputTokens,
            borderColor: '#8b5cf6',
            backgroundColor: 'rgba(139, 92, 246, 0.1)',
            fill: true,
            tension: 0.3,
            yAxisID: 'y',
          },
          {
            label: 'Compute Time (sec)',
            data: data.dailyTrend.computeTimeSec,
            borderColor: '#f59e0b',
            backgroundColor: 'rgba(245, 158, 11, 0.1)',
            fill: false,
            tension: 0.3,
            yAxisID: 'y1',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: { position: 'top', labels: { usePointStyle: true, boxWidth: 8, font: { size: 11 } } } },
        scales: {
          y: { position: 'left', title: { display: true, text: 'Tokens', font: { size: 11 } }, ticks: { font: { size: 10 } } },
          y1: { position: 'right', title: { display: true, text: 'Compute (sec)', font: { size: 11 } }, grid: { drawOnChartArea: false }, ticks: { font: { size: 10 } } },
          x: { ticks: { font: { size: 10 }, maxRotation: 45 } },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

// ─── Provider Breakdown Chart ────────────────────────────────
function ProviderBreakdownChart({ data }: { data: ComputeMetricsSummary }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart<'doughnut'> | null>(null);

  useEffect(() => {
    if (!canvasRef.current || !data.providerBreakdown.length) return;
    chartRef.current?.destroy();

    const providers = data.providerBreakdown.map(p => p.provider);
    const colors = providers.map(p => PROVIDER_COLORS[p] || '#94a3b8');

    chartRef.current = new Chart(canvasRef.current, {
      type: 'doughnut',
      data: {
        labels: providers,
        datasets: [{
          data: data.providerBreakdown.map(p => p.computeTimeSec),
          backgroundColor: colors,
          borderWidth: 2,
          borderColor: '#fff',
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'right', labels: { usePointStyle: true, boxWidth: 10, font: { size: 11 } } },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.label}: ${formatTime(ctx.parsed)}`,
            },
          },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

// ─── Main Component ──────────────────────────────────────────
const ComputeMetricsSection: React.FC<ComputeMetricsSectionProps> = ({ data, loading }) => {
  if (loading) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-24" />
          ))}
        </div>
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-64" />
      </div>
    );
  }

  if (!data) return null;

  return (
    <div className="space-y-4">
      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Requests</span>
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-server text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatNumber(data.totalRequests)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Tokens</span>
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-microchip text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatNumber(data.totalTokens)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Compute Time</span>
            <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-clock text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatTime(data.totalComputeTimeSec)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Cost</span>
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-dollar-sign text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatCurrency(data.totalCost)}</p>
        </div>
      </div>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Daily Trend */}
        <div className="lg:col-span-2 bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-area text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Daily Compute Trend</h3>
          </div>
          <div className="p-4" style={{ height: 280 }}>
            <DailyTrendChart data={data} />
          </div>
        </div>

        {/* Provider Breakdown */}
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-pie text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Compute by Provider</h3>
          </div>
          <div className="p-4" style={{ height: 280 }}>
            <ProviderBreakdownChart data={data} />
          </div>
        </div>
      </div>

      {/* Provider Table */}
      {data.providerBreakdown.length > 0 && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-cloud text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Provider Compute Usage</h3>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Requests</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Input Tokens</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Output Tokens</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Compute Time</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Avg Latency</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {data.providerBreakdown.map(p => (
                  <tr key={p.provider} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3">
                      <div className="flex items-center gap-2">
                        <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: PROVIDER_COLORS[p.provider] || '#94a3b8' }} />
                        <span className="font-medium text-gray-900">{p.provider}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatNumber(p.totalRequests)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatNumber(p.totalInputTokens)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatNumber(p.totalOutputTokens)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatTime(p.computeTimeSec)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{p.avgLatencyMs.toFixed(0)}ms</td>
                    <td className="px-4 py-3 text-right font-medium text-gray-900">{formatCurrency(p.totalCost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Model Compute Allocation */}
      {data.modelAllocation.length > 0 && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-pink-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-cubes text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Model Compute Allocation</h3>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Model</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Requests</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Tokens</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Compute</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">% of Total</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {data.modelAllocation.map((m, idx) => (
                  <tr key={idx} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3 font-medium text-gray-900 font-mono text-xs">{m.model}</td>
                    <td className="px-4 py-3">
                      <span className="text-xs font-semibold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full">
                        {m.provider}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatNumber(m.requests)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatNumber(m.totalTokens)}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatTime(m.computeTimeSec)}</td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <div className="w-16 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                          <div
                            className="h-full rounded-full bg-indigo-500"
                            style={{ width: `${Math.min(100, m.pctOfTotalCompute)}%` }}
                          />
                        </div>
                        <span className="text-xs text-gray-600 w-10 text-right">{m.pctOfTotalCompute.toFixed(1)}%</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-medium text-gray-900">{formatCurrency(m.cost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};

export default ComputeMetricsSection;
