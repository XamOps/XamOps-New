import React from 'react';
import { Icon } from '@iconify/react';
import type { RequestLogEntry, PagedResponse } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-violet-50 border border-violet-100 rounded-lg flex items-center justify-center">
            <Icon icon="mdi:format-list-bulleted" width={16} color="#7C3AED" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Request Logs</h3>
          <InfoTooltip placement="right">Detailed log of every API request through the proxy with filtering and export.</InfoTooltip>
          {data && (
            <span className="text-xs text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-full">
              {data.totalElements.toLocaleString()} total
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          {onExport && (
            <button
              onClick={onExport}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
            >
              <Icon icon="mdi:download-outline" width={14} />
              Export CSV
            </button>
          )}
          <span className="text-xs text-[rgba(0,0,0,0.5)]">Rows:</span>
          <select
            value={size}
            onChange={e => { onSizeChange(Number(e.target.value)); onPageChange(0); }}
            className="text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-2 py-1 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
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
            <Icon icon="mdi:loading" width={20} color="#818CF8" className="animate-spin mx-auto mb-2" />
            <p className="text-sm text-[rgba(0,0,0,0.4)]">Loading request logs...</p>
          </div>
        ) : logs.length === 0 ? (
          <div className="px-6 py-12 text-center text-[rgba(0,0,0,0.4)] text-sm">
            No request logs found for the selected filters
          </div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                {[
                  { col: 'Timestamp', tip: 'When the request was made' },
                  { col: 'Provider', tip: 'LLM provider that handled the request' },
                  { col: 'Model', tip: 'AI model used for this request' },
                  { col: 'Agent', tip: 'Agent/app name from X-XamOps-Agent header' },
                  { col: 'Input', tip: 'Prompt tokens sent to the model' },
                  { col: 'Output', tip: 'Completion tokens received from the model' },
                  { col: 'Cost', tip: 'Estimated cost based on model pricing' },
                  { col: 'Latency', tip: 'Round-trip response time from the provider' },
                  { col: 'Status', tip: 'HTTP status code — 2xx success, 4xx/5xx error' },
                ].map(({ col, tip }) => (
                  <th key={col} className="px-4 py-3 text-left text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                    <span className="inline-flex items-center gap-1">{col} <InfoTooltip placement="bottom">{tip}</InfoTooltip></span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {logs.map(log => (
                <tr key={log.id} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.5)] whitespace-nowrap">
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
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.7)] font-medium max-w-[200px] truncate">
                    {log.model}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.5)]">
                    {log.agentName || <span className="text-[rgba(0,0,0,0.25)]">-</span>}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.7)]">{formatNumber(log.inputTokens)}</td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.7)]">{formatNumber(log.outputTokens)}</td>
                  <td className="px-4 py-2.5 text-xs font-semibold text-[rgba(0,0,0,0.88)]">{formatCurrency(log.cost)}</td>
                  <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.7)]">
                    {log.latencyMs >= 1000
                      ? (log.latencyMs / 1000).toFixed(1) + 's'
                      : log.latencyMs + 'ms'}
                  </td>
                  <td className="px-4 py-2.5">
                    {log.error ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-red-50 text-red-600">
                        <Icon icon="mdi:close-circle-outline" width={12} />
                        {log.statusCode}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-600">
                        <Icon icon="mdi:check-circle-outline" width={12} />
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
        <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
          <span className="text-xs text-[rgba(0,0,0,0.5)]">
            Showing {page * size + 1}-{Math.min((page + 1) * size, data.totalElements)} of {data.totalElements.toLocaleString()}
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => onPageChange(0)}
              disabled={page === 0}
              className="px-2 py-1 text-xs rounded border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.025)] disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <Icon icon="mdi:chevron-double-left" width={14} />
            </button>
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
              className="px-2 py-1 text-xs rounded border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.025)] disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <Icon icon="mdi:chevron-left" width={14} />
            </button>
            <span className="px-3 py-1 text-xs text-[rgba(0,0,0,0.7)] font-medium">
              Page {page + 1} of {data.totalPages}
            </span>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={page >= data.totalPages - 1}
              className="px-2 py-1 text-xs rounded border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.025)] disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <Icon icon="mdi:chevron-right" width={14} />
            </button>
            <button
              onClick={() => onPageChange(data.totalPages - 1)}
              disabled={page >= data.totalPages - 1}
              className="px-2 py-1 text-xs rounded border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.025)] disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <Icon icon="mdi:chevron-double-right" width={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default RequestLogsTable;
