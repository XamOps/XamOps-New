import React from 'react';
import type { AzureSecurityData } from '../../types/azureSecurity';

interface AzureSecurityKPICardsProps {
  securityData: AzureSecurityData | null;
  isLoading: boolean;
}

const SkeletonCard: React.FC = () => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
    <div className="h-3 bg-gray-200 rounded w-24 mb-3" />
    <div className="h-7 bg-gray-200 rounded w-16 mb-2" />
    <div className="h-3 bg-gray-100 rounded w-28" />
  </div>
);

const AzureSecurityKPICards: React.FC<AzureSecurityKPICardsProps> = ({ securityData, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <SkeletonCard /><SkeletonCard /><SkeletonCard /><SkeletonCard />
      </div>
    );
  }

  const score = securityData?.secureScore ?? 0;
  const maxScore = securityData?.maxScore ?? 100;
  const assessments = securityData?.assessments ?? [];
  const alerts = securityData?.alerts ?? [];

  const highSeverity = assessments.filter(a => a.severity === 'High').length
    + alerts.filter(a => a.severity === 'High').length;
  const activeAlerts = alerts.filter(a => a.status === 'Active').length;
  const totalFindings = assessments.length;
  const scorePercent = maxScore > 0 ? Math.round((score / maxScore) * 100) : 0;
  const scoreColor = scorePercent >= 70 ? 'text-emerald-600' : scorePercent >= 40 ? 'text-amber-600' : 'text-red-600';

  const cards = [
    { label: 'Secure Score',    value: score > 0 ? score.toFixed(1) : '—', sub: `Out of ${maxScore} (${scorePercent}%)`, valueClass: scoreColor },
    { label: 'High Severity',   value: highSeverity,   sub: 'Critical findings requiring action', valueClass: 'text-red-600' },
    { label: 'Active Alerts',   value: activeAlerts,   sub: 'Unresolved security alerts',         valueClass: 'text-orange-600' },
    { label: 'Total Findings',  value: totalFindings,  sub: 'Unhealthy security assessments',     valueClass: 'text-gray-900' },
  ];

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] mb-2 leading-tight">{c.label}</p>
          <p className={`text-[22px] font-bold leading-tight tabular-nums ${c.valueClass}`}>{c.value}</p>
          <p className="text-[11px] text-gray-400 mt-1">{c.sub}</p>
        </div>
      ))}
    </div>
  );
};

export default AzureSecurityKPICards;
