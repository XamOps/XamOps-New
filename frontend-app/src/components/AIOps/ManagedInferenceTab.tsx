import React from 'react';
import type { InferenceOverview } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';

interface ManagedInferenceTabProps {
  data: InferenceOverview | null;
  loading: boolean;
}

const STATUS_STYLE: Record<string, { bg: string; text: string; icon: string; dot: string }> = {
  ACTIVE: { bg: 'bg-emerald-100', text: 'text-emerald-700', icon: 'fas fa-check-circle', dot: 'bg-emerald-500' },
  DEGRADED: { bg: 'bg-amber-100', text: 'text-amber-700', icon: 'fas fa-exclamation-triangle', dot: 'bg-amber-500' },
  DOWN: { bg: 'bg-red-100', text: 'text-red-700', icon: 'fas fa-times-circle', dot: 'bg-red-500' },
  INACTIVE: { bg: 'bg-gray-100', text: 'text-gray-500', icon: 'fas fa-minus-circle', dot: 'bg-gray-400' },
};

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(2);
}

function timeAgo(isoString: string | null): string {
  if (!isoString) return 'Never';
  const diff = Date.now() - new Date(isoString).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

const ManagedInferenceTab: React.FC<ManagedInferenceTabProps> = ({ data, loading }) => {
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
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Endpoints</span>
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-server text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{data.totalEndpoints}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Active</span>
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-check-circle text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-emerald-600">{data.activeEndpoints}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Degraded/Down</span>
            <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-red-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-exclamation-circle text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-amber-600">{data.degradedEndpoints}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Cost</span>
            <div className="w-8 h-8 bg-gradient-to-br from-green-500 to-emerald-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-dollar-sign text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatCurrency(data.totalCost)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Avg Latency</span>
            <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-tachometer-alt text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{data.avgLatency.toFixed(0)}ms</p>
        </div>
      </div>

      {/* Endpoint Cards */}
      {data.endpoints.length === 0 ? (
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-12 text-center">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-server text-gray-400 text-2xl" />
          </div>
          <p className="text-gray-500 font-medium">No inference endpoints found</p>
          <p className="text-xs text-gray-400 mt-1">Start sending requests through the proxy to see endpoints here</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {data.endpoints.map(ep => {
            const style = STATUS_STYLE[ep.status] || STATUS_STYLE.INACTIVE;
            return (
              <div key={ep.id} className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
                {/* Header */}
                <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="relative">
                      <span
                        className="w-10 h-10 rounded-lg flex items-center justify-center"
                        style={{ backgroundColor: (PROVIDER_COLORS[ep.provider] || '#94a3b8') + '20' }}
                      >
                        <i className="fas fa-cloud" style={{ color: PROVIDER_COLORS[ep.provider] || '#94a3b8' }} />
                      </span>
                      <span className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-white ${style.dot}`} />
                    </div>
                    <div>
                      <h4 className="text-sm font-bold text-gray-900">{ep.displayName}</h4>
                      <p className="text-xs text-gray-400 font-mono">{ep.model}</p>
                    </div>
                  </div>
                  <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold ${style.bg} ${style.text}`}>
                    <i className={`${style.icon} text-[10px]`} />
                    {ep.status}
                  </span>
                </div>

                {/* Stats Grid */}
                <div className="px-5 py-4">
                  <div className="grid grid-cols-4 gap-4 mb-3">
                    <div className="text-center">
                      <p className="text-lg font-bold text-gray-900">{ep.totalRequests.toLocaleString()}</p>
                      <p className="text-xs text-gray-400">Requests</p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold text-gray-900">{formatCurrency(ep.totalCost)}</p>
                      <p className="text-xs text-gray-400">Cost</p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold text-gray-900">{ep.avgLatencyMs.toFixed(0)}ms</p>
                      <p className="text-xs text-gray-400">Avg Latency</p>
                    </div>
                    <div className="text-center">
                      <p className={`text-lg font-bold ${ep.errorRate > 5 ? 'text-red-600' : ep.errorRate > 1 ? 'text-amber-600' : 'text-emerald-600'}`}>
                        {ep.errorRate.toFixed(1)}%
                      </p>
                      <p className="text-xs text-gray-400">Error Rate</p>
                    </div>
                  </div>

                  {/* Uptime bar */}
                  <div className="mb-3">
                    <div className="flex items-center justify-between text-xs mb-1">
                      <span className="text-gray-500">Uptime</span>
                      <span className={`font-bold ${ep.uptimePct >= 99 ? 'text-emerald-600' : ep.uptimePct >= 95 ? 'text-amber-600' : 'text-red-600'}`}>
                        {ep.uptimePct.toFixed(1)}%
                      </span>
                    </div>
                    <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all ${
                          ep.uptimePct >= 99 ? 'bg-emerald-500' : ep.uptimePct >= 95 ? 'bg-amber-500' : 'bg-red-500'
                        }`}
                        style={{ width: `${ep.uptimePct}%` }}
                      />
                    </div>
                  </div>

                  {/* Footer info */}
                  <div className="flex items-center justify-between text-xs text-gray-400">
                    <span><i className="fas fa-clock mr-1" />Last request: {timeAgo(ep.lastRequestAt)}</span>
                    <span className="text-xs font-semibold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full">
                      {ep.provider}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default ManagedInferenceTab;
