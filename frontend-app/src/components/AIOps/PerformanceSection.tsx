import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import type { PerformanceMetrics } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

Chart.register(...registerables);

interface PerformanceSectionProps {
  data: PerformanceMetrics | null;
  loading: boolean;
}

function formatLatency(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return Math.round(ms) + 'ms';
}

const MODEL_COLORS = [
  '#6366f1', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981',
  '#3b82f6', '#ef4444', '#14b8a6', '#f97316', '#06b6d4',
];

// ─── Latency Percentiles Chart ─────────────────────────────
function LatencyPercentilesChart({ data }: { data: PerformanceMetrics }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || data.latencyByModel.length === 0) return;
    if (chartRef.current) chartRef.current.destroy();

    const labels = data.latencyByModel.map(m => m.model.length > 25 ? m.model.slice(0, 22) + '...' : m.model);

    chartRef.current = new Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'p50',
            data: data.latencyByModel.map(m => m.p50),
            backgroundColor: '#10b981CC',
            borderColor: '#10b981',
            borderWidth: 1,
            borderRadius: 4,
          },
          {
            label: 'p95',
            data: data.latencyByModel.map(m => m.p95),
            backgroundColor: '#f59e0bCC',
            borderColor: '#f59e0b',
            borderWidth: 1,
            borderRadius: 4,
          },
          {
            label: 'p99',
            data: data.latencyByModel.map(m => m.p99),
            backgroundColor: '#ef4444CC',
            borderColor: '#ef4444',
            borderWidth: 1,
            borderRadius: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'top', labels: { boxWidth: 12, padding: 12, font: { size: 11 } } },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.dataset.label}: ${formatLatency(ctx.parsed.y)}`,
            },
          },
        },
        scales: {
          x: { grid: { display: false }, ticks: { font: { size: 10 } } },
          y: {
            grid: { color: 'rgba(0,0,0,0.05)' },
            ticks: { callback: (v) => formatLatency(Number(v)) },
          },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

// ─── Error Rate Trend Chart ────────────────────────────────
function ErrorRateTrendChart({ data }: { data: PerformanceMetrics }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || data.errorRateTrend.length === 0) return;
    if (chartRef.current) chartRef.current.destroy();

    chartRef.current = new Chart(canvasRef.current, {
      type: 'line',
      data: {
        labels: data.errorRateTrend.map(p => p.date),
        datasets: [
          {
            label: 'Error Rate %',
            data: data.errorRateTrend.map(p => p.errorRate),
            borderColor: '#ef4444',
            backgroundColor: '#ef444420',
            fill: true,
            tension: 0.3,
            pointRadius: 3,
            borderWidth: 2,
          },
          {
            label: 'Requests',
            data: data.errorRateTrend.map(p => p.totalRequests),
            borderColor: '#6366f1',
            backgroundColor: 'transparent',
            tension: 0.3,
            pointRadius: 2,
            borderWidth: 1.5,
            borderDash: [4, 4],
            yAxisID: 'y1',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'top', labels: { boxWidth: 12, padding: 12, font: { size: 11 } } },
        },
        scales: {
          x: { grid: { display: false }, ticks: { font: { size: 10 }, maxTicksLimit: 10 } },
          y: {
            position: 'left',
            grid: { color: 'rgba(0,0,0,0.05)' },
            ticks: { callback: (v) => Number(v).toFixed(1) + '%' },
            title: { display: true, text: 'Error Rate %', font: { size: 10 } },
          },
          y1: {
            position: 'right',
            grid: { display: false },
            title: { display: true, text: 'Requests', font: { size: 10 } },
          },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

// ─── Latency Distribution Chart ────────────────────────────
function LatencyDistributionChart({ data }: { data: PerformanceMetrics }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || data.latencyDistribution.length === 0) return;
    if (chartRef.current) chartRef.current.destroy();

    const bucketColors = ['#10b981', '#22c55e', '#f59e0b', '#f97316', '#ef4444', '#dc2626'];

    chartRef.current = new Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: data.latencyDistribution.map(b => b.bucket),
        datasets: [{
          label: 'Requests',
          data: data.latencyDistribution.map(b => b.count),
          backgroundColor: data.latencyDistribution.map((_, i) => bucketColors[i % bucketColors.length] + 'CC'),
          borderColor: data.latencyDistribution.map((_, i) => bucketColors[i % bucketColors.length]),
          borderWidth: 1,
          borderRadius: 6,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { color: 'rgba(0,0,0,0.05)' } },
          y: { grid: { display: false }, ticks: { font: { size: 11 } } },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

// ─── Reliability Badge ─────────────────────────────────────
function ReliabilityBadge({ score }: { score: number }) {
  let cls = 'bg-emerald-100 text-emerald-700';
  if (score < 50) cls = 'bg-red-100 text-red-700';
  else if (score < 80) cls = 'bg-amber-100 text-amber-700';
  else if (score < 95) cls = 'bg-blue-100 text-blue-700';

  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${cls}`}>
      {score.toFixed(1)}
    </span>
  );
}

// ─── Main Component ────────────────────────────────────────
const PerformanceSection: React.FC<PerformanceSectionProps> = ({ data, loading }) => {
  if (loading) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="animate-pulse bg-gray-200/60 rounded-2xl h-72" />
          <div className="animate-pulse bg-gray-200/60 rounded-2xl h-72" />
        </div>
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-48" />
      </div>
    );
  }

  if (!data) return null;

  const hasLatency = data.latencyByModel.length > 0;
  const hasErrors = data.errorRateTrend.length > 0;
  const hasDist = data.latencyDistribution.length > 0;
  const hasReliability = data.modelReliability.length > 0;

  if (!hasLatency && !hasErrors && !hasDist && !hasReliability) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-12 text-center shadow-sm">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-tachometer-alt text-gray-400 text-2xl" />
        </div>
        <p className="text-gray-500 font-medium">No performance data available</p>
        <p className="text-xs text-gray-400 mt-1">Performance metrics appear once requests are logged</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Charts Row 1: Latency Percentiles + Error Rate Trend */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {hasLatency && (
          <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-teal-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-stopwatch text-white text-xs" />
              </div>
              <h3 className="text-sm font-bold text-gray-800">Latency Percentiles by Model</h3>
              <InfoTooltip placement="right">p50 = median, p95 = 95th percentile, p99 = 99th percentile response times.</InfoTooltip>
            </div>
            <div className="p-4" style={{ height: 280 }}>
              <LatencyPercentilesChart data={data} />
            </div>
          </div>
        )}

        {hasErrors && (
          <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
              <div className="w-8 h-8 bg-gradient-to-br from-red-500 to-rose-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-exclamation-triangle text-white text-xs" />
              </div>
              <h3 className="text-sm font-bold text-gray-800">Error Rate Trend</h3>
              <InfoTooltip placement="right">Daily error rate percentage vs request volume over time.</InfoTooltip>
            </div>
            <div className="p-4" style={{ height: 280 }}>
              <ErrorRateTrendChart data={data} />
            </div>
          </div>
        )}
      </div>

      {/* Charts Row 2: Latency Distribution */}
      {hasDist && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-bar text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Latency Distribution</h3>
            <InfoTooltip placement="right">How requests are distributed across latency buckets.</InfoTooltip>
          </div>
          <div className="p-4" style={{ height: Math.max(180, data.latencyDistribution.length * 36 + 40) }}>
            <LatencyDistributionChart data={data} />
          </div>
        </div>
      )}

      {/* Model Reliability Table */}
      {hasReliability && (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-shield-alt text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Model Reliability</h3>
            <InfoTooltip placement="right">Reliability score based on error rate and latency. Higher = more reliable.</InfoTooltip>
            <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
              {data.modelReliability.length} models
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left">
                  <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Model</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Requests</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Errors</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Error Rate</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Avg Latency</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Reliability</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {data.modelReliability.map((m, idx) => (
                  <tr key={`${m.model}-${m.provider}`} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-3 font-medium text-gray-900 flex items-center gap-2">
                      <span
                        className="w-3 h-3 rounded-full flex-shrink-0"
                        style={{ backgroundColor: MODEL_COLORS[idx % MODEL_COLORS.length] }}
                      />
                      <span className="truncate max-w-[200px]" title={m.model}>{m.model}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs font-semibold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full uppercase">
                        {m.provider}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{m.totalRequests.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right text-gray-700">{m.errorCount.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right">
                      <span className={`text-xs font-semibold ${m.errorRate > 10 ? 'text-red-600' : m.errorRate > 5 ? 'text-amber-600' : 'text-green-600'}`}>
                        {m.errorRate.toFixed(1)}%
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-700">{formatLatency(m.avgLatencyMs)}</td>
                    <td className="px-4 py-3 text-right">
                      <ReliabilityBadge score={m.reliabilityScore} />
                    </td>
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

export default PerformanceSection;
