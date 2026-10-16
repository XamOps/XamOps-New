/**
 * A chart's legend (design-delta §3.2 "Labels"; glossary §10 "Series legend words"): one swatch per
 * series in its token colour, with its name beside it, so a bar chart's colours are explained on
 * screen and not by colour alone. The swatch reads its colour from chartTokens like every chart; a
 * hatched series (the Kubernetes VPA's suggestion) gets a hatched swatch, declared in its own SVG.
 */
import { useId } from 'react';
import { chartIdSuffix } from './ChartDefs';
import { HATCH, MONEY, SERIES, type HatchKind, type MoneyKind, type SeriesKind } from './chartTokens';

export interface LegendItem {
  /** The series name, e.g. "Requested". */
  label: string;
  /** A series meaning, or a money kind when `money` is set. */
  kind: SeriesKind | MoneyKind;
  money?: boolean;
  /** Draw the swatch hatched, as the series is drawn. */
  hatch?: HatchKind;
}

export interface SeriesLegendProps {
  items: LegendItem[];
  className?: string;
}

export function SeriesLegend({ items, className = '' }: SeriesLegendProps) {
  // This legend's own pattern ids, so a hatched swatch never resolves to another SVG's pattern.
  const suffix = chartIdSuffix(useId());
  return (
    <ul
      className={`flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)] ${className}`}
      data-series-legend=""
      data-k8s-kit=""
    >
      {items.map((item) => {
        const color = item.money ? MONEY[item.kind as MoneyKind].color : SERIES[item.kind as SeriesKind].color;
        const patternId = item.hatch ? `k8s-legend-hatch-${item.hatch}-${suffix}` : null;
        return (
          <li key={item.label} className="inline-flex items-center gap-1.5">
            <svg width="14" height="10" viewBox="0 0 14 10" aria-hidden="true" focusable="false">
              {item.hatch && patternId ? (
                <>
                  <defs>
                    <pattern id={patternId} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                      <line x1="0" y1="0" x2="0" y2="4" stroke={HATCH[item.hatch].stroke} strokeWidth="1.2" />
                    </pattern>
                  </defs>
                  <rect x="0.5" y="1.5" width="13" height="7" rx="2" fill={`url(#${patternId})`} stroke={color} strokeWidth="1" />
                </>
              ) : (
                <rect x="0" y="1" width="14" height="8" rx="2" fill={color} />
              )}
            </svg>
            <span>{item.label}</span>
          </li>
        );
      })}
    </ul>
  );
}

export default SeriesLegend;
