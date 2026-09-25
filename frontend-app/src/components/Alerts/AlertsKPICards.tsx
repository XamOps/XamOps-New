/**
 * AlertsKPICards Component
 *
 * KPI cards showing alert counts by severity
 */

import React from 'react';
import type { AlertCounts, AlertsMetadata } from '../../types/alerts';
import { formatAbsoluteTime, formatRelativeTime } from '../GcpAlerts/resourceDisplay';

interface AlertsKPICardsProps {
  alertCounts: AlertCounts;
  isLoading: boolean;
  metadata?: AlertsMetadata | null;
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
  metadata,
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

  // Counted by severity across quotas, cost spikes and security findings alike. The old cards
  // counted quota utilisation only, and "Healthy" was always 0 because the backend sends only
  // quotas that are already past 75%.
  const cards = [
    { label: 'Total Alerts', value: alertCounts.total,    sub: 'quotas, cost spikes, security', valueColor: 'rgb(10,10,10)' },
    { label: 'Critical',     value: alertCounts.critical, sub: 'act now',                       valueColor: 'rgb(220,38,38)' },
    { label: 'Warning',      value: alertCounts.warning,  sub: 'high or medium',                valueColor: 'rgb(217,119,6)' },
  ];

  // When this list was built. A page load can serve a saved scan up to an hour old, so the age is
  // stated rather than implied to be "now".
  const generatedAt = metadata?.timestamp ?? null;

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
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
        <CloudSVG />
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">Last Updated</p>
        <p
          className="text-[20px] font-semibold leading-[32px] text-[rgba(0,0,0,0.88)]"
          title={formatAbsoluteTime(generatedAt)}
        >
          {formatRelativeTime(generatedAt)}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">
          {generatedAt ? formatAbsoluteTime(generatedAt) : 'refresh to scan now'}
        </p>
      </div>
    </div>
  );
};

export default AlertsKPICards;
