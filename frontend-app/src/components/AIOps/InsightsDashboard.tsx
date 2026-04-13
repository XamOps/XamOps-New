import React, { useState } from 'react';
import type { InsightsSummary, InsightCard as InsightCardType } from '../../types/aiops';

interface InsightsDashboardProps {
  data: InsightsSummary | null;
  loading: boolean;
}

const TYPE_CONFIG: Record<string, { icon: string; color: string; borderColor: string; bgColor: string }> = {
  COST_OPTIMIZATION: { icon: 'fas fa-piggy-bank', color: 'text-emerald-600', borderColor: 'border-l-emerald-500', bgColor: 'bg-emerald-50' },
  USAGE_PATTERN:     { icon: 'fas fa-chart-area', color: 'text-blue-600',    borderColor: 'border-l-blue-500',    bgColor: 'bg-blue-50' },
  ANOMALY:           { icon: 'fas fa-exclamation-triangle', color: 'text-red-600', borderColor: 'border-l-red-500', bgColor: 'bg-red-50' },
  PERFORMANCE:       { icon: 'fas fa-tachometer-alt', color: 'text-purple-600', borderColor: 'border-l-purple-500', bgColor: 'bg-purple-50' },
  TREND:             { icon: 'fas fa-chart-line', color: 'text-gray-600',    borderColor: 'border-l-gray-400',    bgColor: 'bg-gray-50' },
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
    <div className={`bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden border-l-4 ${cfg.borderColor} hover:shadow-md transition-shadow`}>
      <div className="p-5">
        <div className="flex items-start justify-between gap-3 mb-3">
          <div className="flex items-center gap-3">
            <div className={`w-9 h-9 ${cfg.bgColor} rounded-lg flex items-center justify-center flex-shrink-0`}>
              <i className={`${cfg.icon} ${cfg.color} text-sm`} />
            </div>
            <div>
              <h4 className="text-sm font-bold text-gray-900 leading-tight">{insight.title}</h4>
              <div className="flex items-center gap-2 mt-1">
                <span className={`text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded-full ${SEVERITY_BADGE[insight.severity] || SEVERITY_BADGE.INFO}`}>
                  {insight.severity}
                </span>
                <span className="text-[10px] text-gray-400 uppercase font-medium">
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

        <p className="text-sm text-gray-600 leading-relaxed">{insight.description}</p>

        {insight.actionable && insight.actionLabel && (
          <div className="mt-3">
            <button className="text-xs font-medium text-indigo-600 hover:text-indigo-800 transition-colors">
              {insight.actionLabel} <i className="fas fa-arrow-right ml-1 text-[10px]" />
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
            <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-24" />
          ))}
        </div>
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="animate-pulse bg-gray-200/60 rounded-2xl h-32" />
        ))}
      </div>
    );
  }

  if (!data || data.insights.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-12 text-center shadow-sm">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-lightbulb text-gray-400 text-2xl" />
        </div>
        <p className="text-gray-500 font-medium">No insights available yet</p>
        <p className="text-xs text-gray-400 mt-1">Insights will be generated once you have sufficient usage data</p>
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
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Insights</span>
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-lightbulb text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{data.totalInsights}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Actionable</span>
            <div className="w-8 h-8 bg-gradient-to-br from-emerald-500 to-green-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-check-circle text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{data.actionableCount}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Est. Savings</span>
            <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-dollar-sign text-white text-xs" />
            </div>
          </div>
          <p className="text-2xl font-bold text-gray-900">{formatCurrency(data.estimatedSavingsUsd)}</p>
        </div>
      </div>

      {/* Filter Pills */}
      <div className="flex items-center gap-2 flex-wrap">
        <button
          onClick={() => setActiveFilter(null)}
          className={`px-3 py-1.5 text-xs font-medium rounded-full transition-all ${
            !activeFilter
              ? 'bg-indigo-100 text-indigo-700 shadow-sm'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
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
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              <i className={`${cfg.icon} text-[10px]`} />
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
