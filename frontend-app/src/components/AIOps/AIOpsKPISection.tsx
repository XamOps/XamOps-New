import React from 'react';
import { Icon } from '@iconify/react';
import type { LlmKpis, AllTimeMetrics } from '../../types/aiops';

interface AIOpsKPISectionProps {
  kpis: LlmKpis;
  allTimeMetrics?: AllTimeMetrics | null;
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

const CloudSVG = () => (
  <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
);

const AIOpsKPISection: React.FC<AIOpsKPISectionProps> = ({ kpis, allTimeMetrics }) => {
  const cards = [
    {
      label: 'Total LLM Spend',
      value: formatCurrency(kpis.totalSpend),
      icon: 'mdi:currency-usd',
      iconBg: 'from-blue-500 to-indigo-600',
      accent: 'border-l-blue-500',
      glow: 'rgba(59,130,246,0.25)',
      trend: null as null | { pct: number; label: string },
    },
    {
      label: 'Requests',
      value: formatNumber(kpis.requestsToday),
      subValue: `${formatNumber(kpis.requestsThisMonth)} this month`,
      icon: 'mdi:send-outline',
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
      icon: 'mdi:coins',
      iconBg: 'from-teal-500 to-emerald-600',
      accent: 'border-l-teal-500',
      glow: 'rgba(20,184,166,0.25)',
      trend: null,
    },
    {
      label: 'Avg Latency',
      value: formatLatency(kpis.avgLatencyMs),
      icon: 'mdi:lightning-bolt',
      iconBg: 'from-amber-500 to-orange-600',
      accent: 'border-l-amber-500',
      glow: 'rgba(245,158,11,0.25)',
      trend: null,
    },
  ];

  const allTimeCards = allTimeMetrics ? [
    { label: 'All Time Cost', value: formatCurrency(allTimeMetrics.allTimeCost), icon: 'mdi:coins' },
    { label: 'All Time Tokens', value: formatNumber(allTimeMetrics.allTimeTokens), icon: 'mdi:database-outline' },
    { label: 'All Time Requests', value: formatNumber(allTimeMetrics.allTimeRequests), icon: 'mdi:server-outline' },
  ] : [];

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {cards.map((card, idx) => (
          <div
            key={card.label}
            className={`bg-white/95 backdrop-blur border border-[rgba(0,0,0,0.12)] rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 relative overflow-hidden group border-l-4 ${card.accent}`}
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
              <h3 className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
                {card.label}
              </h3>
              <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${card.iconBg} flex items-center justify-center shadow-sm`}>
                <Icon icon={card.icon} width={18} color="white" />
              </div>
            </div>

            <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }} className="mb-1">{card.value}</p>
            <CloudSVG />

            {'subValue' in card && card.subValue && (
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{card.subValue}</p>
            )}

            {card.trend && (
              <div className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${
                card.trend.pct >= 0
                  ? 'bg-red-50 text-red-600'
                  : 'bg-green-50 text-green-600'
              }`}>
                <Icon icon={card.trend.pct >= 0 ? 'mdi:arrow-up' : 'mdi:arrow-down'} width={12} className="mr-1" />
                <span>{Math.abs(card.trend.pct).toFixed(1)}% {card.trend.label}</span>
              </div>
            )}
          </div>
        ))}
      </div>

      {allTimeCards.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {allTimeCards.map(card => (
            <div key={card.label} className="bg-white/80 border border-[rgba(0,0,0,0.06)] rounded-xl px-5 py-3 shadow-sm flex items-center justify-between">
              <div>
                <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">{card.label}</span>
                <p className="text-lg font-bold text-[rgba(0,0,0,0.7)]">{card.value}</p>
              </div>
              <div className="w-8 h-8 bg-[rgba(0,0,0,0.06)] rounded-lg flex items-center justify-center">
                <Icon icon={card.icon} width={16} color="rgba(0,0,0,0.5)" />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default AIOpsKPISection;
