import React from 'react';
import type { SeverityCounts } from '../../types/security';

interface SecurityKPICardsProps {
  totalFindings: number;
  severityCounts: SeverityCounts;
  isLoading: boolean;
}

const CloudSVG = () => (
  <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
);

const BEBAS = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  fontVariantNumeric: 'tabular-nums' as const,
};

const SecurityKPICards: React.FC<SecurityKPICardsProps> = ({ totalFindings, severityCounts, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
            <div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-24 mb-3" />
            <div className="h-7 bg-[rgba(0,0,0,0.06)] rounded w-16 mb-2" />
            <div className="h-3 bg-[rgba(0,0,0,0.04)] rounded w-28" />
          </div>
        ))}
      </div>
    );
  }

  const cards = [
    { label: 'Total Findings',  value: totalFindings,                                           sub: 'security issues detected', valueColor: 'rgb(10,10,10)' },
    { label: 'Critical',        value: severityCounts.Critical || 0,                            sub: 'immediate attention',      valueColor: 'rgb(220,38,38)' },
    { label: 'High Priority',   value: severityCounts.High || 0,                                sub: 'requires action',          valueColor: 'rgb(234,88,12)' },
    { label: 'Medium & Low',    value: (severityCounts.Medium || 0) + (severityCounts.Low || 0), sub: 'monitor closely',          valueColor: 'rgb(161,98,7)' },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          <CloudSVG />
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">{c.label}</p>
          <p style={{ ...BEBAS, color: c.valueColor }}>{c.value}</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{c.sub}</p>
        </div>
      ))}
    </div>
  );
};

export default SecurityKPICards;
