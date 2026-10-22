import React from 'react';
import { CHART } from './tokens';

/**
 * The three area-chart series, derived from `CHART.areaStroke` so stroke and fill colors can never
 * drift. These are the Vulnerability-by-severity vuln bands (CRITICAL / HIGH / OTHER) — NOT the
 * four severity-dot levels — so they come from the dedicated area-stroke map, not the SEVERITY
 * palette. `OTHER` is the pale muted-blue #60A5FA, unrelated to the #94A3B8 UNKNOWN dot.
 */
const SERIES: { key: string; color: string }[] = (['CRITICAL', 'HIGH', 'OTHER'] as const).map(
  (key) => ({ key, color: CHART.areaStroke[key] }),
);

/** Deterministic gradient id for a given series key, so the Area `fill` can reference it. */
export const areaGradientId = (key: string) => `area-grad-${key}`;

/**
 * <defs> of vertical linear gradients for the Vulnerability-by-severity area chart. These are
 * STACKED areas, so each fill must stay a readable pale wash rather than vanish. The stops are
 * pinned: 0.45 at the top down to 0.12 at the bottom in the series color. 0.12 (not 0.06) keeps
 * the lowest `OTHER` band distinguishable on white while still reading as a soft pastel fill.
 */
const ChartGradients: React.FC = () => (
  <defs>
    {SERIES.map(({ key, color }) => (
      <linearGradient key={key} id={areaGradientId(key)} x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stopColor={color} stopOpacity={0.45} />
        <stop offset="100%" stopColor={color} stopOpacity={0.12} />
      </linearGradient>
    ))}
  </defs>
);

export default ChartGradients;
