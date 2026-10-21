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
 * <defs> of vertical linear gradients for the Vulnerability-by-severity area chart. These are
 * STACKED areas, so the fill has to read as a solid band — a near-transparent gradient left the
 * lower bands invisible. Each series runs from a strong ~0.8 opacity at the top down to ~0.35 at
 * the bottom in its own severity color: present and readable like the original flat fill, but
 * with a soft top-to-bottom fade for depth. Rendered inside the Recharts <AreaChart>.
 */
const ChartGradients: React.FC = () => (
  <defs>
    {SERIES.map(({ key, color }) => (
      <linearGradient key={key} id={areaGradientId(key)} x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stopColor={color} stopOpacity={0.8} />
        <stop offset="100%" stopColor={color} stopOpacity={0.35} />
      </linearGradient>
    ))}
  </defs>
);

export default ChartGradients;
