import React from 'react';
import type { AzurePerformanceData } from '../../types/azurePerformance';

interface AzurePerformanceKPICardsProps {
  performanceData: AzurePerformanceData | null;
  isLoading: boolean;
}

const AzurePerformanceKPICards: React.FC<AzurePerformanceKPICardsProps> = ({ performanceData, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
            <div className="h-3 bg-gray-200 rounded w-24 mb-3" />
            <div className="h-7 bg-gray-200 rounded w-16 mb-2" />
            <div className="h-3 bg-gray-100 rounded w-28" />
          </div>
        ))}
      </div>
    );
  }

  const score = performanceData?.performanceScore ?? 100;
  const insights = performanceData?.insights ?? [];

  const highCount = insights.filter(i => i.severity === 'High').length;
  const mediumCount = insights.filter(i => i.severity === 'Medium').length;
  const totalCount = insights.length;

  const scoreValueClass = score >= 70 ? 'text-emerald-600' : score >= 40 ? 'text-amber-600' : 'text-red-600';

  const cards = [
    { label: 'Performance Score', value: score, sub: 'Out of 100', valueClass: scoreValueClass },
    { label: 'High Impact', value: highCount, sub: 'High priority recommendations', valueClass: 'text-red-600' },
    { label: 'Medium Impact', value: mediumCount, sub: 'Medium priority recommendations', valueClass: 'text-amber-600' },
    { label: 'Total Insights', value: totalCount, sub: 'Advisor performance recommendations', valueClass: 'text-gray-900' },
  ];

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
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

export default AzurePerformanceKPICards;
