import React from 'react';
import type { LlmKpis } from '../../types/aiops';

interface AIOpsKPISectionProps {
  kpis: LlmKpis;
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  return '$' + value.toFixed(2);
}

function formatNumber(value: number): string {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M';
  if (value >= 1_000) return (value / 1_000).toFixed(1) + 'k';
  return value.toLocaleString();
}

function formatLatency(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return Math.round(ms) + 'ms';
}

const AIOpsKPISection: React.FC<AIOpsKPISectionProps> = ({ kpis }) => {
  const cards = [
    {
      label: 'Total LLM Spend',
      value: formatCurrency(kpis.totalSpend),
      icon: 'fas fa-dollar-sign',
      iconBg: 'from-blue-500 to-indigo-600',
      accent: 'border-l-blue-500',
      glow: 'rgba(59,130,246,0.25)',
      trend: null as null | { pct: number; label: string },
    },
    {
      label: 'Requests',
      value: formatNumber(kpis.requestsToday),
      subValue: `${formatNumber(kpis.requestsThisMonth)} this month`,
      icon: 'fas fa-paper-plane',
      iconBg: 'from-purple-500 to-violet-600',
      accent: 'border-l-purple-500',
      glow: 'rgba(139,92,246,0.25)',
      trend: {
        pct: kpis.todayVsYesterdayPct,
        label: 'vs yesterday',
      },
    },
    {
      label: 'Token Usage',
      value: formatNumber(kpis.inputTokens + kpis.outputTokens),
      subValue: `In: ${formatNumber(kpis.inputTokens)} / Out: ${formatNumber(kpis.outputTokens)}`,
      icon: 'fas fa-coins',
      iconBg: 'from-teal-500 to-emerald-600',
      accent: 'border-l-teal-500',
      glow: 'rgba(20,184,166,0.25)',
      trend: null,
    },
    {
      label: 'Avg Latency',
      value: formatLatency(kpis.avgLatencyMs),
      icon: 'fas fa-bolt',
      iconBg: 'from-amber-500 to-orange-600',
      accent: 'border-l-amber-500',
      glow: 'rgba(245,158,11,0.25)',
      trend: null,
    },
  ];

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      {cards.map((card, idx) => (
        <div
          key={card.label}
          className={`bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 relative overflow-hidden group border-l-4 ${card.accent}`}
          style={{ animationDelay: `${idx * 60}ms` }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLDivElement).style.boxShadow = `0 12px 28px -4px ${card.glow}`;
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLDivElement).style.boxShadow = '';
          }}
        >
          <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-indigo-500 to-purple-500 transform scale-x-0 group-hover:scale-x-100 transition-transform duration-300" />

          <div className="flex justify-between items-start mb-4">
            <h3 className="text-gray-500 text-xs font-semibold uppercase tracking-wider">
              {card.label}
            </h3>
            <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${card.iconBg} flex items-center justify-center shadow-sm`}>
              <i className={`${card.icon} text-white text-sm`} />
            </div>
          </div>

          <p className="text-2xl font-bold text-gray-900 mb-1">{card.value}</p>

          {'subValue' in card && card.subValue && (
            <p className="text-xs text-gray-500 mb-2">{card.subValue}</p>
          )}

          {card.trend && (
            <div className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${
              card.trend.pct >= 0
                ? 'bg-red-50 text-red-600'
                : 'bg-green-50 text-green-600'
            }`}>
              <i className={`fas ${card.trend.pct >= 0 ? 'fa-arrow-up' : 'fa-arrow-down'} mr-1 text-[10px]`} />
              <span>{Math.abs(card.trend.pct).toFixed(1)}% {card.trend.label}</span>
            </div>
          )}
        </div>
      ))}
    </div>
  );
};

export default AIOpsKPISection;
