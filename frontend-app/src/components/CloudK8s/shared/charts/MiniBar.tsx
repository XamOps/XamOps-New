/**
 * A table-cell mini-bar (design-delta §3; glossary §10). Plain inline SVG in CapacityDonut's style:
 * a track with a filled value bar, an optional second (use) bar, and an optional suggestion tick, in
 * series classes from chartTokens. Money mini-bars use the money classes. Deterministic: a value
 * maps to the same width every render.
 *
 * The cell's own text carries the number; the bar is decorative and `aria-hidden`. Nothing is drawn
 * when the value is "Not set" / "—" (the caller passes `value={null}`).
 */
import { MONEY, SERIES, type MoneyKind, type SeriesKind } from './chartTokens';

export interface MiniBarProps {
  /** The value, as a fraction of `max` (0..1 after scaling). Null draws nothing. */
  value: number | null;
  /** The scale maximum; value/max is the bar width. */
  max: number;
  /** The bar's meaning; a series kind or a money kind. */
  kind?: SeriesKind | MoneyKind;
  money?: boolean;
  /** An optional second bar (e.g. used, under requested). */
  secondary?: { value: number; kind: SeriesKind } | null;
  /** An optional tick (e.g. the suggestion) at this value. */
  tick?: { value: number; kind: SeriesKind } | null;
  width?: number;
  height?: number;
  className?: string;
}

const clamp = (v: number, max: number) => (max > 0 ? Math.max(0, Math.min(1, v / max)) : 0);

export function MiniBar({
  value,
  max,
  kind = 'requested',
  money = false,
  secondary = null,
  tick = null,
  width = 64,
  height = 10,
  className = '',
}: MiniBarProps) {
  if (value === null || !Number.isFinite(value)) {
    return <span className={`text-[11px] text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)] ${className}`} data-mini-bar="empty">—</span>;
  }

  // Series bars colour through a `fill-series-*` class (allowed in charts/); money bars pass the raw
  // money var inline, since the `fill-money-*` class is reserved to the status components.
  const primaryClass = money ? undefined : SERIES[kind as SeriesKind].fillClass;
  const primaryFill = money ? MONEY[kind as MoneyKind].color : undefined;
  const w = clamp(value, max) * width;

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      aria-hidden="true"
      focusable="false"
      data-mini-bar=""
    >
      {/* Greyscale track. */}
      <rect x="0" y={height / 2 - 2} width={width} height="4" rx="2" className="fill-series-capacity" fillOpacity={0.18} />
      <rect x="0" y={height / 2 - 2} width={w.toFixed(2)} height="4" rx="2" className={primaryClass} fill={primaryFill} />
      {secondary && (
        <rect
          x="0"
          y={height / 2 - 0.5}
          width={(clamp(secondary.value, max) * width).toFixed(2)}
          height="1.5"
          className={SERIES[secondary.kind].fillClass}
        />
      )}
      {tick && (
        <rect
          x={(clamp(tick.value, max) * width - 0.75).toFixed(2)}
          y="0"
          width="1.5"
          height={height}
          className={SERIES[tick.kind].fillClass}
        />
      )}
    </svg>
  );
}

export default MiniBar;
