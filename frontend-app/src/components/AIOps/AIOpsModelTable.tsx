import React, { useState } from 'react';
import type { ModelUsageStat } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

interface AIOpsModelTableProps {
  modelUsage: ModelUsageStat[];
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

type SortField = 'model' | 'provider' | 'requests' | 'totalTokens' | 'cost' | 'avgLatencyMs';

const AIOpsModelTable: React.FC<AIOpsModelTableProps> = ({ modelUsage }) => {
  const [sortField, setSortField] = useState<SortField>('cost');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [search, setSearch] = useState('');

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('desc');
    }
  };

  const filtered = modelUsage
    .filter(m =>
      m.model.toLowerCase().includes(search.toLowerCase()) ||
      m.provider.toLowerCase().includes(search.toLowerCase())
    )
    .sort((a, b) => {
      const aVal = a[sortField];
      const bVal = b[sortField];
      if (typeof aVal === 'string' && typeof bVal === 'string') {
        return sortDir === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
      }
      return sortDir === 'asc'
        ? (aVal as number) - (bVal as number)
        : (bVal as number) - (aVal as number);
    });

  const SortIcon = ({ field }: { field: SortField }) => (
    <i className={`fas fa-sort${sortField === field ? (sortDir === 'asc' ? '-up' : '-down') : ''} ml-1 text-[10px] ${sortField === field ? 'text-indigo-500' : 'text-[rgba(0,0,0,0.3)]'}`} />
  );

  return (
    <div className="bg-white/95 backdrop-blur border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-teal-500 to-emerald-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-table text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">Model Usage</h3>
          <span className="text-xs text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-full">
            {modelUsage.length} models
          </span>
        </div>
        <div className="relative">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)] text-xs" />
          <input
            type="text"
            placeholder="Search models..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="pl-8 pr-3 py-1.5 text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] w-48"
          />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              {[
                { field: 'model' as SortField, label: 'Model' },
                { field: 'provider' as SortField, label: 'Provider' },
                { field: 'requests' as SortField, label: 'Requests' },
                { field: 'totalTokens' as SortField, label: 'Total Tokens' },
                { field: 'cost' as SortField, label: 'Cost' },
                { field: 'avgLatencyMs' as SortField, label: 'Avg Latency' },
              ].map(col => (
                <th
                  key={col.field}
                  onClick={() => handleSort(col.field)}
                  className="px-6 py-3 text-left text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider cursor-pointer hover:text-indigo-600 select-none"
                >
                  {col.label}
                  <SortIcon field={col.field} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-6 py-8 text-center text-[rgba(0,0,0,0.4)] text-sm">
                  {search ? 'No models match your search' : 'No model usage data yet'}
                </td>
              </tr>
            ) : (
              filtered.map((row) => (
                <tr key={`${row.model}-${row.provider}`} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <td className="px-6 py-3">
                    <span className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{row.model}</span>
                  </td>
                  <td className="px-6 py-3">
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
                  <td className="px-6 py-3 text-sm text-[rgba(0,0,0,0.7)]">{formatNumber(row.requests)}</td>
                  <td className="px-6 py-3 text-sm text-[rgba(0,0,0,0.7)]">{formatNumber(row.totalTokens)}</td>
                  <td className="px-6 py-3 text-sm font-semibold text-[rgba(0,0,0,0.88)]">{formatCurrency(row.cost)}</td>
                  <td className="px-6 py-3 text-sm text-[rgba(0,0,0,0.7)]">
                    {row.avgLatencyMs >= 1000
                      ? (row.avgLatencyMs / 1000).toFixed(1) + 's'
                      : Math.round(row.avgLatencyMs) + 'ms'}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AIOpsModelTable;
