import React from 'react';
import type { GcpAlertCounts } from '../../types/gcpAlerts';

interface Props {
  counts: GcpAlertCounts;
  isLoading: boolean;
}

interface Tile {
  label: string;
  value: number;
  hint: string;
  valueClass: string;
}

const NUMERAL_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  letterSpacing: '0px',
  lineHeight: '32px',
};

/**
 * The KPI strip, counted by severity and source.
 *
 * The previous tiles were "Total alerts / monitored services", "Critical usage / above 90%",
 * "Warning / 75–90%" and "Healthy / below 75%" — percentage thresholds inherited from when this was
 * a quota page. Security findings carry `usage: 0, limit: 0`, so on a project with 2,124 of them the
 * strip asserted that 2,124 things were above 90% of nothing, and the "healthy" tile could only ever
 * read zero because the backend emits security rows exclusively at CRITICAL and HIGH.
 */
const GcpAlertsKpiStrip: React.FC<Props> = ({ counts, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {[0, 1, 2, 3].map(i => (
          <div key={i} className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
        ))}
      </div>
    );
  }

  const tiles: Tile[] = [
    {
      label: 'CRITICAL',
      value: counts.critical,
      hint: 'need attention now',
      valueClass: 'text-red-600',
    },
    {
      label: 'HIGH',
      value: counts.high,
      hint: 'schedule remediation',
      valueClass: 'text-orange-500',
    },
    {
      label: 'COST ANOMALIES',
      value: counts.costAnomalies,
      hint: 'unexpected spend',
      valueClass: 'text-amber-500',
    },
    {
      label: 'QUOTA ALERTS',
      value: counts.quotaWarnings,
      hint: 'approaching a limit',
      valueClass: 'text-blue-600',
    },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {tiles.map((tile, idx) => (
        <div
          key={tile.label}
          className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 card-hover-lift"
          style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${idx * 0.06}s both` }}
        >
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
            {tile.label}
          </p>
          <p className={`tabular-nums ${tile.valueClass}`} style={NUMERAL_STYLE}>
            {tile.value.toLocaleString()}
          </p>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{tile.hint}</p>
        </div>
      ))}
    </div>
  );
};

export default GcpAlertsKpiStrip;
