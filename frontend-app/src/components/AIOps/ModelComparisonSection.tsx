import React from 'react';
import type { ModelComparisonStat } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

interface ModelComparisonSectionProps {
  data: ModelComparisonStat[];
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

const ModelComparisonSection: React.FC<ModelComparisonSectionProps> = ({ data, loading }) => {
  if (loading) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-12 text-center">
        <i className="fas fa-circle-notch fa-spin text-indigo-400 text-xl mb-2" />
        <p className="text-sm text-gray-400">Loading model comparison...</p>
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-8 text-center text-gray-400 text-sm">
        No model comparison data yet
      </div>
    );
  }

  // Find the max cost to compute relative bar widths
  const maxCost = Math.max(...data.map(d => d.cost), 0.0001);

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
        <div className="w-8 h-8 bg-gradient-to-br from-cyan-500 to-blue-600 rounded-lg flex items-center justify-center">
          <i className="fas fa-balance-scale text-white text-xs" />
        </div>
        <h3 className="text-sm font-bold text-gray-800">Model Comparison</h3>
        <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
          {data.length} models
        </span>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-gray-50/80">
              {['Model', 'Provider', 'Requests', 'Input Tokens', 'Output Tokens', 'Cost', 'Avg Latency', 'Error Rate'].map(col => (
                <th key={col} className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider whitespace-nowrap">
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {data.map(row => {
              const costPct = (row.cost / maxCost) * 100;
              return (
                <tr key={`${row.model}-${row.provider}`} className="hover:bg-indigo-50/30 transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-sm font-medium text-gray-900">{row.model}</span>
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold"
                      style={{
                        backgroundColor: (PROVIDER_COLORS[row.provider] || '#94a3b8') + '15',
                        color: PROVIDER_COLORS[row.provider] || '#94a3b8',
                      }}
                    >
                      {row.provider}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-700">{formatNumber(row.requests)}</td>
                  <td className="px-4 py-3 text-sm text-gray-700">{formatNumber(row.inputTokens)}</td>
                  <td className="px-4 py-3 text-sm text-gray-700">{formatNumber(row.outputTokens)}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-gray-900 whitespace-nowrap">{formatCurrency(row.cost)}</span>
                      <div className="flex-1 max-w-[80px] bg-gray-100 rounded-full h-1.5">
                        <div
                          className="h-1.5 rounded-full bg-indigo-500"
                          style={{ width: `${costPct}%` }}
                        />
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-700">
                    {row.avgLatencyMs >= 1000
                      ? (row.avgLatencyMs / 1000).toFixed(1) + 's'
                      : Math.round(row.avgLatencyMs) + 'ms'}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`text-sm font-semibold ${row.errorRate > 5 ? 'text-red-600' : row.errorRate > 0 ? 'text-amber-600' : 'text-emerald-600'}`}>
                      {row.errorRate.toFixed(1)}%
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default ModelComparisonSection;
