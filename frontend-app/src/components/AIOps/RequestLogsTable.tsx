import React from 'react';
import type { RequestLogEntry, PagedResponse } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

interface RequestLogsTableProps {
  data: PagedResponse<RequestLogEntry> | null;
  loading: boolean;
  page: number;
  size: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  onExport?: () => void;
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

function formatTimestamp(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

const RequestLogsTable: React.FC<RequestLogsTableProps> = ({
  data,
  loading,
  page,
  size,
  onPageChange,
  onSizeChange,
  onExport,
}) => {
  const logs = data?.content ?? [];

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-violet-500 to-purple-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-list-alt text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Request Logs</h3>
          {data && (
            <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
              {data.totalElements.toLocaleString()} total
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          {onExport && (
            <button
              onClick={onExport}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
            >
              <i className="fas fa-download" />
              Export CSV
            </button>
          )}
          <span className="text-xs text-gray-500">Rows:</span>
          <select
            value={size}
            onChange={e => { onSizeChange(Number(e.target.value)); onPageChange(0); }}
            className="text-xs border border-gray-200 rounded-lg px-2 py-1 focus:ring-2 focus:ring-indigo-500 outline-none"
          >
            {[10, 20, 50, 100].map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        {loading ? (
          <div className="px-6 py-12 text-center">
            <i className="fas fa-circle-notch fa-spin text-indigo-400 text-xl mb-2" />
            <p className="text-sm text-gray-400">Loading request logs...</p>
          </div>
        ) : logs.length === 0 ? (
          <div className="px-6 py-12 text-center text-gray-400 text-sm">
            No request logs found for the selected filters
          </div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="bg-gray-50/80">
                {['Timestamp', 'Provider', 'Model', 'Agent', 'Input', 'Output', 'Cost', 'Latency', 'Status'].map(col => (
                  <th key={col} className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wider whitespace-nowrap">
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {logs.map(log => (
                <tr key={log.id} className="hover:bg-indigo-50/30 transition-colors">
                  <td className="px-4 py-2.5 text-xs text-gray-600 whitespace-nowrap">
                    {formatTimestamp(log.timestamp)}
                  </td>
                  <td className="px-4 py-2.5">
                    <span
                      className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold"
                      style={{
                        backgroundColor: (PROVIDER_COLORS[log.provider] || '#94a3b8') + '15',
                        color: PROVIDER_COLORS[log.provider] || '#94a3b8',
                      }}
                    >
                      {log.provider}
                    </span>
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-700 font-medium max-w-[200px] truncate">
                    {log.model}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    {log.agentName || <span className="text-gray-300">-</span>}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-700">{formatNumber(log.inputTokens)}</td>
                  <td className="px-4 py-2.5 text-xs text-gray-700">{formatNumber(log.outputTokens)}</td>
                  <td className="px-4 py-2.5 text-xs font-semibold text-gray-900">{formatCurrency(log.cost)}</td>
                  <td className="px-4 py-2.5 text-xs text-gray-700">
                    {log.latencyMs >= 1000
                      ? (log.latencyMs / 1000).toFixed(1) + 's'
                      : log.latencyMs + 'ms'}
                  </td>
                  <td className="px-4 py-2.5">
                    {log.error ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-red-50 text-red-600">
                        <i className="fas fa-times-circle text-[10px]" />
                        {log.statusCode}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-600">
                        <i className="fas fa-check-circle text-[10px]" />
                        {log.statusCode}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Pagination */}
      {data && data.totalPages > 1 && (
        <div className="px-6 py-3 border-t border-gray-100 flex items-center justify-between">
          <span className="text-xs text-gray-500">
            Showing {page * size + 1}-{Math.min((page + 1) * size, data.totalElements)} of {data.totalElements.toLocaleString()}
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => onPageChange(0)}
              disabled={page === 0}
              className="px-2 py-1 text-xs rounded border border-gray-200 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <i className="fas fa-angle-double-left" />
            </button>
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
              className="px-2 py-1 text-xs rounded border border-gray-200 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <i className="fas fa-angle-left" />
            </button>
            <span className="px-3 py-1 text-xs text-gray-700 font-medium">
              Page {page + 1} of {data.totalPages}
            </span>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={page >= data.totalPages - 1}
              className="px-2 py-1 text-xs rounded border border-gray-200 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <i className="fas fa-angle-right" />
            </button>
            <button
              onClick={() => onPageChange(data.totalPages - 1)}
              disabled={page >= data.totalPages - 1}
              className="px-2 py-1 text-xs rounded border border-gray-200 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <i className="fas fa-angle-double-right" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default RequestLogsTable;
