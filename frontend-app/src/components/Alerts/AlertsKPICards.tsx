/**
 * AlertsKPICards Component
 *
 * KPI cards showing alert counts by severity
 */

import React from 'react';
import type { AlertCounts } from '../../types/alerts';

interface AlertsKPICardsProps {
  alertCounts: AlertCounts;
  isLoading: boolean;
  metadata?: any;
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

const AlertsKPICards: React.FC<AlertsKPICardsProps> = ({
  alertCounts,
  isLoading,
  metadata: _metadata,
}) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {Array.from({ length: 4 }).map((_, i) => (
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
    { label: 'Total Alerts',   value: alertCounts.total,    sub: 'monitored services', valueColor: 'rgb(10,10,10)' },
    { label: 'Critical Usage', value: alertCounts.critical, sub: 'above 90%',           valueColor: 'rgb(220,38,38)' },
    { label: 'Warning',        value: alertCounts.warning,  sub: '75–90%',              valueColor: 'rgb(217,119,6)' },
    { label: 'Healthy',        value: alertCounts.healthy,  sub: 'below 75%',           valueColor: 'rgb(5,150,105)' },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
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

export default AlertsKPICards;
