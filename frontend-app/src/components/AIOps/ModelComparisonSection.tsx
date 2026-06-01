import React from 'react';
import { Icon } from '@iconify/react';
import type { ModelComparisonStat } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

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
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center">
        <Icon icon="mdi:loading" width={20} color="#818CF8" className="animate-spin mx-auto mb-2" />
        <p className="text-sm text-[rgba(0,0,0,0.4)]">Loading model comparison...</p>
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-8 text-center text-[rgba(0,0,0,0.4)] text-sm">
        No model comparison data yet
      </div>
    );
  }

  const maxCost = Math.max(...data.map(d => d.cost), 0.0001);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-8 h-8 bg-cyan-50 border border-cyan-100 rounded-lg flex items-center justify-center">
          <Icon icon="mdi:scale-balance" width={16} color="#0891B2" />
        </div>
        <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Model Comparison</h3>
        <InfoTooltip placement="right">Side-by-side comparison of all models used, sorted by cost.</InfoTooltip>
        <span className="text-xs text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-full">
          {data.length} models
        </span>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              {[
                { col: 'Model', tip: 'AI model name' },
                { col: 'Provider', tip: 'LLM provider serving this model' },
                { col: 'Requests', tip: 'Total API calls to this model' },
                { col: 'Input Tokens', tip: 'Total prompt tokens sent' },
                { col: 'Output Tokens', tip: 'Total completion tokens received' },
                { col: 'Cost', tip: 'Estimated total cost based on model pricing' },
                { col: 'Avg Latency', tip: 'Average round-trip response time' },
                { col: 'Error Rate', tip: 'Percentage of requests that returned errors' },
              ].map(({ col, tip }) => (
                <th key={col} className="px-4 py-3 text-left text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                  <span className="inline-flex items-center gap-1">{col} <InfoTooltip placement="bottom">{tip}</InfoTooltip></span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
            {data.map(row => {
              const costPct = (row.cost / maxCost) * 100;
              return (
                <tr key={`${row.model}-${row.provider}`} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{row.model}</span>
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
                  <td className="px-4 py-3 text-sm text-[rgba(0,0,0,0.7)]">{formatNumber(row.requests)}</td>
                  <td className="px-4 py-3 text-sm text-[rgba(0,0,0,0.7)]">{formatNumber(row.inputTokens)}</td>
                  <td className="px-4 py-3 text-sm text-[rgba(0,0,0,0.7)]">{formatNumber(row.outputTokens)}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)] whitespace-nowrap">{formatCurrency(row.cost)}</span>
                      <div className="flex-1 max-w-[80px] bg-[rgba(0,0,0,0.06)] rounded-full h-1.5">
                        <div
                          className="h-1.5 rounded-full bg-indigo-500"
                          style={{ width: `${costPct}%` }}
                        />
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm text-[rgba(0,0,0,0.7)]">
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
