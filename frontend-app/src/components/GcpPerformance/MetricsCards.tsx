/**
 * Metrics Cards Component
 * Displays performance summary metrics
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { PerformanceSummary } from '../../types/gcpPerformance';

interface MetricsCardsProps {
  summary: PerformanceSummary;
  loading: boolean;
}

const MetricsCards: React.FC<MetricsCardsProps> = ({ summary, loading }) => {
  const cards = [
    { label: 'Performance Score', value: summary.performanceScore || 0, sub: 'Overall score', valueClass: 'text-gray-900' },
    { label: 'Total Insights', value: summary.totalInsights || 0, sub: 'All findings', valueClass: 'text-gray-900' },
    { label: 'Critical', value: summary.critical || 0, sub: 'Immediate attention', valueClass: 'text-red-600' },
    { label: 'Warnings', value: (summary.warning || 0) + (summary.weakWarning || 0), sub: 'Review needed', valueClass: 'text-orange-600' },
  ];

  if (loading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
            <div className="h-3 bg-gray-200 rounded w-24 mb-3" />
            <div className="h-7 bg-gray-200 rounded w-16 mb-2" />
            <div className="h-3 bg-gray-100 rounded w-20" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {cards.map(c => (
        <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] mb-2 leading-tight">{c.label}</p>
          <p className={`text-[22px] font-bold leading-tight tabular-nums ${c.valueClass}`}>{c.value}</p>
          <p className="text-[11px] text-gray-400 mt-1">{c.sub}</p>
        </div>
      ))}
    </div>
  );
};

export default MetricsCards;
