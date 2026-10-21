import React from 'react';

/** Severity palette, kept in sync with OverviewTab's SEV_COLOR. */
const SERIES: { key: string; color: string }[] = [
  { key: 'CRITICAL', color: '#dc2626' },
  { key: 'HIGH', color: '#f97316' },
  { key: 'OTHER', color: '#3b82f6' },
];

/** Deterministic gradient id for a given series key, so the Area `fill` can reference it. */
export const areaGradientId = (key: string) => `area-grad-${key}`;

/**
 * <defs> of vertical linear gradients for the Vulnerability-by-severity area chart: each series
 * fades from ~0.25 opacity at the top to ~0.02 at the bottom in its own severity color, giving
 * the stacked areas soft depth instead of a flat fill. Rendered inside the Recharts <AreaChart>.
 */
const ChartGradients: React.FC = () => (
  <defs>
    {SERIES.map(({ key, color }) => (
      <linearGradient key={key} id={areaGradientId(key)} x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stopColor={color} stopOpacity={0.25} />
        <stop offset="100%" stopColor={color} stopOpacity={0.02} />
      </linearGradient>
    ))}
  </defs>
);

export default ChartGradients;
