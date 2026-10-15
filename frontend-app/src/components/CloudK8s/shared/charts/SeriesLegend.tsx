/**
 * A chart's legend (design-delta §3.2 "Labels"; glossary §10 "Series legend words"): one swatch per
 * series in its token colour, with its name beside it, so a bar chart's colours are explained on
 * screen and not by colour alone. The swatch reads its colour from chartTokens like every chart.
 */
import { MONEY, SERIES, type MoneyKind, type SeriesKind } from './chartTokens';

export interface LegendItem {
  /** The series name, e.g. "Requested". */
  label: string;
  /** A series meaning, or a money kind when `money` is set. */
  kind: SeriesKind | MoneyKind;
  money?: boolean;
}

export interface SeriesLegendProps {
  items: LegendItem[];
  className?: string;
}

export function SeriesLegend({ items, className = '' }: SeriesLegendProps) {
  return (
    <ul
      className={`flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)] ${className}`}
      data-series-legend=""
    >
      {items.map((item) => {
        const color = item.money ? MONEY[item.kind as MoneyKind].color : SERIES[item.kind as SeriesKind].color;
        return (
          <li key={item.label} className="inline-flex items-center gap-1.5">
            <svg width="14" height="10" viewBox="0 0 14 10" aria-hidden="true" focusable="false">
              <rect x="0" y="1" width="14" height="8" rx="2" fill={color} />
            </svg>
            <span>{item.label}</span>
          </li>
        );
      })}
    </ul>
  );
}

export default SeriesLegend;
