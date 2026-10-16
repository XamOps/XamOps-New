/**
 * A range bar (design-delta §3; glossary §13 "Replicas (now, min–max)"): an HPA's minimum..maximum
 * as a band on a greyscale track, a marker where its replicas are now, and optional ticks where
 * XamOps suggests the bounds should be. Plain inline SVG in MiniBar's style, coloured only through
 * the chartTokens series classes: the band is the capacity grey (a bound, not a measurement), the
 * marker is "used" (what is running), the ticks are "suggested" (XamOps).
 *
 * Unlike MiniBar the bar carries its own accessible text ("now 5 of 1–5"), so it can stand alone
 * in a table cell or a tile. Nothing is drawn but a dash when a bound is unknown: a range XamOps
 * can't place is never guessed. Deterministic: a value maps to the same x every render.
 */
import { SERIES } from './chartTokens';

export interface RangeBarProps {
  /** The lower bound (an HPA's minReplicas); null when it isn't reported. */
  min: number | null;
  /** The upper bound (an HPA's maxReplicas); null when it isn't reported. */
  max: number | null;
  /** Where it is now (replicas running); null draws no marker. */
  current: number | null;
  /** XamOps's suggested lower bound, drawn as a tick; null or absent when XamOps leaves it. */
  suggestedMin?: number | null;
  /** XamOps's suggested upper bound, drawn as a tick; null or absent when XamOps leaves it. */
  suggestedMax?: number | null;
  /** The right end of the scale; defaults to the largest value shown (at least 1). */
  scaleMax?: number;
  width?: number;
  height?: number;
  className?: string;
}

const finite = (v: number | null | undefined): v is number => typeof v === 'number' && Number.isFinite(v);

/** "now 5 of 1–5", plus "XamOps suggests 1–8" when a suggested bound moves. */
export function rangeBarText({ min, max, current, suggestedMin, suggestedMax }: Pick<RangeBarProps, 'min' | 'max' | 'current' | 'suggestedMin' | 'suggestedMax'>): string {
  const now = finite(current) ? String(current) : '—';
  const bounds = finite(min) && finite(max) ? `${min}–${max}` : 'bounds not reported';
  const moved = finite(suggestedMin) || finite(suggestedMax);
  const suggested = moved && finite(min) && finite(max)
    ? `, XamOps suggests ${finite(suggestedMin) ? suggestedMin : min}–${finite(suggestedMax) ? suggestedMax : max}`
    : '';
  return `now ${now} of ${bounds}${suggested}`;
}

export function RangeBar({
  min,
  max,
  current,
  suggestedMin = null,
  suggestedMax = null,
  scaleMax,
  width = 96,
  height = 14,
  className = '',
}: RangeBarProps) {
  const label = rangeBarText({ min, max, current, suggestedMin, suggestedMax });
  if (!finite(min) || !finite(max)) {
    return (
      <span className={`text-[11px] text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)] ${className}`} data-range-bar="empty" data-k8s-kit="">
        <span aria-hidden="true">—</span>
        <span className="sr-only">{label}</span>
      </span>
    );
  }

  const top = Math.max(1, scaleMax ?? 0, max, finite(current) ? current : 0, finite(suggestedMax) ? suggestedMax : 0);
  const pad = 3;
  const plot = width - 2 * pad;
  const x = (v: number) => pad + (Math.max(0, Math.min(top, v)) / top) * plot;
  const mid = height / 2;
  const lo = x(Math.min(min, max));
  const hi = x(Math.max(min, max));

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      role="img"
      aria-label={label}
      focusable="false"
      data-range-bar=""
      data-k8s-kit=""
    >
      <title>{label}</title>
      {/* Greyscale track over the whole scale. */}
      <rect x={pad} y={mid - 2} width={plot} height="4" rx="2" className={SERIES.capacity.fillClass} fillOpacity={0.18} />
      {/* The bounds: a band from the minimum to the maximum, with end caps. */}
      <rect x={lo.toFixed(2)} y={mid - 3} width={Math.max(1.5, hi - lo).toFixed(2)} height="6" rx="2" className={SERIES.capacity.fillClass} fillOpacity={0.55} data-range-band="" />
      <rect x={(lo - 0.75).toFixed(2)} y={mid - 5} width="1.5" height="10" className={SERIES.capacity.fillClass} />
      <rect x={(hi - 0.75).toFixed(2)} y={mid - 5} width="1.5" height="10" className={SERIES.capacity.fillClass} />
      {finite(suggestedMin) && (
        <rect x={(x(suggestedMin) - 1).toFixed(2)} y="0" width="2" height={height} className={SERIES.suggested.fillClass} data-range-suggested="min" />
      )}
      {finite(suggestedMax) && (
        <rect x={(x(suggestedMax) - 1).toFixed(2)} y="0" width="2" height={height} className={SERIES.suggested.fillClass} data-range-suggested="max" />
      )}
      {finite(current) && (
        <circle cx={x(current).toFixed(2)} cy={mid} r={Math.min(4, height / 2 - 1)} className={SERIES.used.fillClass} data-range-current={current} />
      )}
    </svg>
  );
}

export default RangeBar;
