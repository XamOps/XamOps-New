/**
 * A time-at-maximum strip (design-delta §3 "Time at its maximum"; glossary §10). Plain inline SVG in
 * CapacityDonut's style: a greyscale track over the window, with coloured spans where a condition
 * held (e.g. the HPA at its maximum). Spans use a status/series class from chartTokens; the part
 * before the first sample is a grey "not measured yet" block. Deterministic: segments map to the
 * same x every render.
 *
 * The strip is decorative; the caller's text ("80% of the last 72 h") carries the number, and an
 * sr-only summary names the share.
 */
import { SERIES, type SeriesKind } from './chartTokens';

export interface TimeStripSegment {
  /** Start and end as fractions of the window (0..1). */
  from: number;
  to: number;
}

export interface TimeStripProps {
  /** Spans where the condition held. */
  segments: TimeStripSegment[];
  /** The meaning colour of a held span; defaults to "limit" (amber, "at its maximum"). */
  kind?: SeriesKind;
  /** The fraction (0..1) before the first sample, drawn as a grey "not measured yet" block. */
  notMeasuredBefore?: number;
  width?: number;
  height?: number;
  ariaLabel?: string;
  className?: string;
}

export function TimeStrip({
  segments,
  kind = 'limit',
  notMeasuredBefore = 0,
  width = 240,
  height = 14,
  ariaLabel,
  className = '',
}: TimeStripProps) {
  const style = SERIES[kind];
  const clampFrac = (v: number) => Math.max(0, Math.min(1, v));

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      role="img"
      aria-label={ariaLabel}
      data-time-strip=""
      focusable="false"
    >
      {/* Greyscale track. */}
      <rect x="0" y="0" width={width} height={height} rx="3" className="fill-series-capacity" fillOpacity={0.14} />
      {notMeasuredBefore > 0 && (
        <rect
          x="0"
          y="0"
          width={(clampFrac(notMeasuredBefore) * width).toFixed(2)}
          height={height}
          rx="3"
          className="fill-series-capacity"
          fillOpacity={0.3}
          data-not-measured=""
        />
      )}
      {segments.map((seg, i) => {
        const x = clampFrac(seg.from) * width;
        const w = Math.max(0, clampFrac(seg.to) - clampFrac(seg.from)) * width;
        if (w <= 0) return null;
        return <rect key={i} x={x.toFixed(2)} y="0" width={w.toFixed(2)} height={height} className={style.fillClass} />;
      })}
    </svg>
  );
}

export default TimeStrip;
