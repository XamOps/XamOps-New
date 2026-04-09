import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import type { AgentUsageStat } from '../../types/aiops';

Chart.register(...registerables);

interface AgentBreakdownSectionProps {
  data: AgentUsageStat[];
  loading: boolean;
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(4);
}

function formatNumber(value: number): string {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M';
  if (value >= 1_000) return (value / 1_000).toFixed(1) + 'k';
  return value.toLocaleString();
}

const AGENT_COLORS = [
  '#6366f1', '#8b5cf6', '#a78bfa', '#c4b5fd', '#ddd6fe',
  '#818cf8', '#6d28d9', '#5b21b6', '#4c1d95', '#7c3aed',
];

const AgentBreakdownSection: React.FC<AgentBreakdownSectionProps> = ({ data, loading }) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstance = useRef<Chart | null>(null);

  useEffect(() => {
    if (!chartRef.current || data.length === 0) return;

    if (chartInstance.current) {
      chartInstance.current.destroy();
    }

    const ctx = chartRef.current.getContext('2d');
    if (!ctx) return;

    chartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: data.map(d => d.agentName || 'unknown'),
        datasets: [{
          label: 'Cost ($)',
          data: data.map(d => d.cost),
          backgroundColor: data.map((_, i) => AGENT_COLORS[i % AGENT_COLORS.length] + 'cc'),
          borderColor: data.map((_, i) => AGENT_COLORS[i % AGENT_COLORS.length]),
          borderWidth: 1,
          borderRadius: 6,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => `Cost: $${ctx.parsed.x.toFixed(4)}`,
            },
          },
        },
        scales: {
          x: {
            grid: { color: '#f1f5f9' },
            ticks: {
              callback: (val) => '$' + Number(val).toFixed(2),
              font: { size: 11 },
            },
          },
          y: {
            grid: { display: false },
            ticks: { font: { size: 11 } },
          },
        },
      },
    });

    return () => {
      if (chartInstance.current) {
        chartInstance.current.destroy();
        chartInstance.current = null;
      }
    };
  }, [data]);

  if (loading) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-12 text-center">
        <i className="fas fa-circle-notch fa-spin text-indigo-400 text-xl mb-2" />
        <p className="text-sm text-gray-400">Loading agent breakdown...</p>
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-8 text-center">
        <div className="w-12 h-12 bg-indigo-50 rounded-xl flex items-center justify-center mx-auto mb-3">
          <i className="fas fa-robot text-indigo-400 text-lg" />
        </div>
        <h4 className="text-sm font-semibold text-gray-700 mb-1">No Agent Data Yet</h4>
        <p className="text-xs text-gray-400 max-w-md mx-auto">
          Add the <code className="bg-gray-100 px-1.5 py-0.5 rounded text-indigo-600">X-XamOps-Agent</code> header
          to your proxy requests to track per-agent/app usage.
        </p>
        <div className="mt-4 bg-gray-50 rounded-lg p-3 text-left max-w-md mx-auto">
          <p className="text-xs text-gray-500 mb-1 font-semibold">Example:</p>
          <code className="text-xs text-gray-600 block">
            curl ... -H "X-XamOps-Agent: my-chatbot" ...
          </code>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Chart */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-violet-500 to-indigo-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-robot text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Cost by Agent</h3>
          <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
            {data.length} agents
          </span>
        </div>
        <div className="p-6" style={{ height: Math.max(200, data.length * 40 + 60) }}>
          <canvas ref={chartRef} />
        </div>
      </div>

      {/* Table */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="bg-gray-50/80">
                {['Agent', 'Requests', 'Total Tokens', 'Cost', 'Avg Latency'].map(col => (
                  <th key={col} className="px-6 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider">
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((row, i) => (
                <tr key={row.agentName} className="hover:bg-indigo-50/30 transition-colors">
                  <td className="px-6 py-3">
                    <div className="flex items-center gap-2">
                      <div
                        className="w-3 h-3 rounded-full"
                        style={{ backgroundColor: AGENT_COLORS[i % AGENT_COLORS.length] }}
                      />
                      <span className="text-sm font-medium text-gray-900">{row.agentName}</span>
                    </div>
                  </td>
                  <td className="px-6 py-3 text-sm text-gray-700">{formatNumber(row.requests)}</td>
                  <td className="px-6 py-3 text-sm text-gray-700">{formatNumber(row.totalTokens)}</td>
                  <td className="px-6 py-3 text-sm font-semibold text-gray-900">{formatCurrency(row.cost)}</td>
                  <td className="px-6 py-3 text-sm text-gray-700">
                    {row.avgLatencyMs >= 1000
                      ? (row.avgLatencyMs / 1000).toFixed(1) + 's'
                      : Math.round(row.avgLatencyMs) + 'ms'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default AgentBreakdownSection;
