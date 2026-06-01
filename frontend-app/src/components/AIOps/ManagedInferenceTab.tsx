import React from 'react';
import { Icon } from '@iconify/react';
import type { InferenceOverview } from '../../types/aiops';
import { PROVIDER_COLORS } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

interface ManagedInferenceTabProps {
  data: InferenceOverview | null;
  loading: boolean;
}

const STATUS_STYLE: Record<string, { bg: string; text: string; icon: string; iconColor: string; dot: string }> = {
  ACTIVE:   { bg: 'bg-emerald-100', text: 'text-emerald-700', icon: 'mdi:check-circle-outline',  iconColor: '#059669', dot: 'bg-emerald-500' },
  DEGRADED: { bg: 'bg-amber-100',   text: 'text-amber-700',   icon: 'mdi:alert-outline',          iconColor: '#D97706', dot: 'bg-amber-500' },
  DOWN:     { bg: 'bg-red-100',     text: 'text-red-700',     icon: 'mdi:close-circle-outline',   iconColor: '#DC2626', dot: 'bg-red-500' },
  INACTIVE: { bg: 'bg-[rgba(0,0,0,0.04)]', text: 'text-[rgba(0,0,0,0.5)]', icon: 'mdi:minus-circle-outline', iconColor: 'rgba(0,0,0,0.4)', dot: 'bg-[rgba(0,0,0,0.2)]' },
};

const BEBAS: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '28px',
  fontWeight: 400,
  lineHeight: '28px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
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
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-24" />
          ))}
        </div>
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-64" />
      </div>
    );
  }

  if (!data) return null;

  return (
    <div className="space-y-4">
      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Total Endpoints <InfoTooltip placement="bottom">Model endpoints tracked through the proxy.</InfoTooltip></span>
            <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:server-outline" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={BEBAS}>{data.totalEndpoints}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Active <InfoTooltip placement="bottom">Endpoints responding successfully to requests.</InfoTooltip></span>
            <div className="w-8 h-8 bg-emerald-50 border border-emerald-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:check-circle-outline" width={16} color="#059669" />
            </div>
          </div>
          <p style={{ ...BEBAS, color: 'rgb(5,150,105)' }}>{data.activeEndpoints}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Degraded/Down <InfoTooltip placement="bottom">Endpoints with high error rates or not responding.</InfoTooltip></span>
            <div className="w-8 h-8 bg-amber-50 border border-amber-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:alert-circle-outline" width={16} color="#D97706" />
            </div>
          </div>
          <p style={{ ...BEBAS, color: 'rgb(217,119,6)' }}>{data.degradedEndpoints}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Total Cost <InfoTooltip placement="bottom">Combined cost across all endpoints.</InfoTooltip></span>
            <div className="w-8 h-8 bg-green-50 border border-green-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:currency-usd" width={16} color="#16A34A" />
            </div>
          </div>
          <p style={BEBAS}>{formatCurrency(data.totalCost)}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Avg Latency <InfoTooltip placement="bottom">Average response time across all endpoints.</InfoTooltip></span>
            <div className="w-8 h-8 bg-blue-50 border border-blue-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:speedometer" width={16} color="#2563EB" />
            </div>
          </div>
          <p style={BEBAS}>{data.avgLatency.toFixed(0)}ms</p>
        </div>
      </div>

      {/* Endpoint Cards */}
      {data.endpoints.length === 0 ? (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:server-outline" width={28} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[rgba(0,0,0,0.5)] font-medium">No inference endpoints found</p>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Start sending requests through the proxy to see endpoints here</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {data.endpoints.map(ep => {
            const style = STATUS_STYLE[ep.status] || STATUS_STYLE.INACTIVE;
            return (
              <div key={ep.id} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                {/* Header */}
                <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="relative">
                      <span
                        className="w-10 h-10 rounded-lg flex items-center justify-center"
                        style={{ backgroundColor: (PROVIDER_COLORS[ep.provider] || '#94a3b8') + '20' }}
                      >
                        <Icon icon="mdi:cloud-outline" width={20} color={PROVIDER_COLORS[ep.provider] || '#94a3b8'} />
                      </span>
                      <span className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-white ${style.dot}`} />
                    </div>
                    <div>
                      <h4 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{ep.displayName}</h4>
                      <p className="text-xs text-[rgba(0,0,0,0.4)] font-mono">{ep.model}</p>
                    </div>
                  </div>
                  <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold ${style.bg} ${style.text}`}>
                    <Icon icon={style.icon} width={12} color={style.iconColor} />
                    {ep.status}
                  </span>
                </div>

                {/* Stats Grid */}
                <div className="px-5 py-4">
                  <div className="grid grid-cols-4 gap-4 mb-3">
                    <div className="text-center">
                      <p className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{ep.totalRequests.toLocaleString()}</p>
                      <p className="text-xs text-[rgba(0,0,0,0.4)] inline-flex items-center gap-1">Requests <InfoTooltip placement="bottom">Total API calls to this endpoint.</InfoTooltip></p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{formatCurrency(ep.totalCost)}</p>
                      <p className="text-xs text-[rgba(0,0,0,0.4)] inline-flex items-center gap-1">Cost <InfoTooltip placement="bottom">Cumulative spend on this endpoint.</InfoTooltip></p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{ep.avgLatencyMs.toFixed(0)}ms</p>
                      <p className="text-xs text-[rgba(0,0,0,0.4)] inline-flex items-center gap-1">Avg Latency <InfoTooltip placement="bottom">Mean response time for this endpoint.</InfoTooltip></p>
                    </div>
                    <div className="text-center">
                      <p className={`text-lg font-bold ${ep.errorRate > 5 ? 'text-red-600' : ep.errorRate > 1 ? 'text-amber-600' : 'text-emerald-600'}`}>
                        {ep.errorRate.toFixed(1)}%
                      </p>
                      <p className="text-xs text-[rgba(0,0,0,0.4)] inline-flex items-center gap-1">Error Rate <InfoTooltip placement="bottom">Percentage of failed requests.</InfoTooltip></p>
                    </div>
                  </div>

                  {/* Uptime bar */}
                  <div className="mb-3">
                    <div className="flex items-center justify-between text-xs mb-1">
                      <span className="text-[rgba(0,0,0,0.5)]">Uptime</span>
                      <span className={`font-bold ${ep.uptimePct >= 99 ? 'text-emerald-600' : ep.uptimePct >= 95 ? 'text-amber-600' : 'text-red-600'}`}>
                        {ep.uptimePct.toFixed(1)}%
                      </span>
                    </div>
                    <div className="h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all ${
                          ep.uptimePct >= 99 ? 'bg-emerald-500' : ep.uptimePct >= 95 ? 'bg-amber-500' : 'bg-red-500'
                        }`}
                        style={{ width: `${ep.uptimePct}%` }}
                      />
                    </div>
                  </div>

                  {/* Footer info */}
                  <div className="flex items-center justify-between text-xs text-[rgba(0,0,0,0.4)]">
                    <span className="inline-flex items-center gap-1"><Icon icon="mdi:clock-outline" width={12} className="inline" />Last request: {timeAgo(ep.lastRequestAt)}</span>
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
