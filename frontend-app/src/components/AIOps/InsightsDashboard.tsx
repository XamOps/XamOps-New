import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { InsightsSummary, InsightCard as InsightCardType } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

interface InsightsDashboardProps {
  data: InsightsSummary | null;
  loading: boolean;
}

const TYPE_CONFIG: Record<string, { icon: string; iconColor: string; color: string; borderColor: string; bgColor: string }> = {
  COST_OPTIMIZATION: { icon: 'mdi:piggy-bank-outline',        iconColor: '#059669', color: 'text-emerald-600', borderColor: 'border-l-emerald-500', bgColor: 'bg-emerald-50' },
  USAGE_PATTERN:     { icon: 'mdi:chart-areaspline',           iconColor: '#2563EB', color: 'text-blue-600',    borderColor: 'border-l-blue-500',    bgColor: 'bg-blue-50' },
  ANOMALY:           { icon: 'mdi:alert-outline',              iconColor: '#DC2626', color: 'text-red-600',     borderColor: 'border-l-red-500',     bgColor: 'bg-red-50' },
  PERFORMANCE:       { icon: 'mdi:speedometer',                iconColor: '#9333EA', color: 'text-purple-600',  borderColor: 'border-l-purple-500',  bgColor: 'bg-purple-50' },
  TREND:             { icon: 'mdi:chart-line',                 iconColor: '#4B5563', color: 'text-[rgba(0,0,0,0.6)]', borderColor: 'border-l-[rgba(0,0,0,0.3)]', bgColor: 'bg-[rgba(0,0,0,0.04)]' },
};

const SEVERITY_BADGE: Record<string, string> = {
  INFO:       'bg-blue-100 text-blue-700',
  WARNING:    'bg-amber-100 text-amber-700',
  ACTIONABLE: 'bg-emerald-100 text-emerald-700',
};

const TYPE_LABELS: Record<string, string> = {
  COST_OPTIMIZATION: 'Cost',
  USAGE_PATTERN: 'Usage',
  ANOMALY: 'Anomaly',
  PERFORMANCE: 'Performance',
  TREND: 'Trend',
};

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(2);
}

function InsightCardComponent({ insight }: { insight: InsightCardType }) {
  const cfg = TYPE_CONFIG[insight.type] || TYPE_CONFIG.TREND;

  return (
    <div className={`bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden border-l-4 ${cfg.borderColor} hover:shadow-md transition-shadow`}>
      <div className="p-5">
        <div className="flex items-start justify-between gap-3 mb-3">
          <div className="flex items-center gap-3">
            <div className={`w-9 h-9 ${cfg.bgColor} rounded-lg flex items-center justify-center flex-shrink-0`}>
              <Icon icon={cfg.icon} width={18} color={cfg.iconColor} />
            </div>
            <div>
              <h4 className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-tight">{insight.title}</h4>
              <div className="flex items-center gap-2 mt-1">
                <span className={`text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded-full ${SEVERITY_BADGE[insight.severity] || SEVERITY_BADGE.INFO}`}>
                  {insight.severity}
                </span>
                <span className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase font-medium">
                  {TYPE_LABELS[insight.type] || insight.type}
                </span>
              </div>
            </div>
          </div>
          {insight.impact && (
            <span className="text-xs font-semibold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full whitespace-nowrap flex-shrink-0">
              {insight.impact}
            </span>
          )}
        </div>

        <p className="text-sm text-[rgba(0,0,0,0.6)] leading-relaxed">{insight.description}</p>

        {insight.actionable && insight.actionLabel && (
          <div className="mt-3">
            <button className="text-xs font-medium text-indigo-600 hover:text-indigo-800 transition-colors inline-flex items-center gap-1">
              {insight.actionLabel} <Icon icon="mdi:arrow-right" width={12} className="inline" />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

const InsightsDashboard: React.FC<InsightsDashboardProps> = ({ data, loading }) => {
  const [activeFilter, setActiveFilter] = useState<string | null>(null);

  if (loading) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-24" />
          ))}
        </div>
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-32" />
        ))}
      </div>
    );
  }

  if (!data || data.insights.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-12 text-center shadow-sm">
        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-4">
          <Icon icon="mdi:lightbulb-outline" width={28} color="#9CA3AF" />
        </div>
        <p className="text-[rgba(0,0,0,0.5)] font-medium">No insights available yet</p>
        <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">Insights will be generated once you have sufficient usage data</p>
      </div>
    );
  }

  const filteredInsights = activeFilter
    ? data.insights.filter(i => i.type === activeFilter)
    : data.insights;

  // Count by type for filter pills
  const typeCounts: Record<string, number> = {};
  data.insights.forEach(i => {
    typeCounts[i.type] = (typeCounts[i.type] || 0) + 1;
  });

  return (
    <div className="space-y-4">
      {/* Summary KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Total Insights <InfoTooltip placement="bottom">AI-generated insights based on your usage patterns.</InfoTooltip></span>
            <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:lightbulb-outline" width={16} color="#4F46E5" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{data.totalInsights}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Actionable <InfoTooltip placement="bottom">Insights with concrete recommendations you can act on.</InfoTooltip></span>
            <div className="w-8 h-8 bg-emerald-50 border border-emerald-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:check-circle-outline" width={16} color="#059669" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{data.actionableCount}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] inline-flex items-center gap-1">Est. Savings <InfoTooltip placement="bottom">Potential cost reduction if all actionable recommendations are implemented.</InfoTooltip></span>
            <div className="w-8 h-8 bg-amber-50 border border-amber-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:currency-usd" width={16} color="#D97706" />
            </div>
          </div>
          <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(data.estimatedSavingsUsd)}</p>
        </div>
      </div>

      {/* Filter Pills */}
      <div className="flex items-center gap-2 flex-wrap">
        <button
          onClick={() => setActiveFilter(null)}
          className={`px-3 py-1.5 text-xs font-medium rounded-full transition-all ${
            !activeFilter
              ? 'bg-indigo-100 text-indigo-700 shadow-sm'
              : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)]'
          }`}
        >
          All ({data.totalInsights})
        </button>
        {Object.entries(typeCounts).map(([type, count]) => {
          const cfg = TYPE_CONFIG[type] || TYPE_CONFIG.TREND;
          return (
            <button
              key={type}
              onClick={() => setActiveFilter(activeFilter === type ? null : type)}
              className={`px-3 py-1.5 text-xs font-medium rounded-full transition-all flex items-center gap-1.5 ${
                activeFilter === type
                  ? `${cfg.bgColor} ${cfg.color} shadow-sm`
                  : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)]'
              }`}
            >
              <Icon icon={cfg.icon} width={12} color={activeFilter === type ? cfg.iconColor : '#4B5563'} />
              {TYPE_LABELS[type] || type} ({count})
            </button>
          );
        })}
      </div>

      {/* Insight Cards */}
      <div className="space-y-3">
        {filteredInsights.map(insight => (
          <InsightCardComponent key={insight.id} insight={insight} />
        ))}
      </div>
    </div>
  );
};

export default InsightsDashboard;
