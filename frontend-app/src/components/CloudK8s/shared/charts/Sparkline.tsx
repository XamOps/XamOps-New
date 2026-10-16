/**
 * A per-row sparkline (design-delta §3; glossary §10). Plain inline SVG in CapacityDonut's
 * deterministic style: a single path over the given values, drawn in a series stroke class from
 * chartTokens (used by default — the busiest pod's use). No animation, no wall clock: the same
 * values render the same path every time.
 *
 * The line is decorative; the row's own cells carry the numbers, and an sr-only summary names the
 * range for assistive tech.
 */
import { SERIES, type SeriesKind } from './chartTokens';

export interface SparklineProps {
  /** The points, oldest first. Nulls break the line (no running pod). */
  values: Array<number | null>;
  /** Which series this line means; defaults to "used". */
  kind?: SeriesKind;
  width?: number;
  height?: number;
  /** An accessible summary, e.g. "CPU use, last 72 h: 10m to 48m". */
  ariaLabel?: string;
  /** Shown (and read) when there is no point to draw. */
  emptyText?: string;
  className?: string;
}

/** Build an SVG polyline path from the values, scaled into the box. Deterministic. */
function toPath(values: Array<number | null>, w: number, h: number, pad: number): string {
  const finite = values.filter((v): v is number => v !== null && Number.isFinite(v));
  if (finite.length === 0) return '';
  const min = Math.min(...finite);
  const max = Math.max(...finite);
  const span = max - min || 1;
  const stepX = values.length > 1 ? (w - pad * 2) / (values.length - 1) : 0;
  let d = '';
  let penDown = false;
  values.forEach((v, i) => {
    if (v === null || !Number.isFinite(v)) {
      penDown = false;
      return;
    }
    const x = pad + i * stepX;
    const y = h - pad - ((v - min) / span) * (h - pad * 2);
    d += `${penDown ? 'L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)} `;
    penDown = true;
  });
  return d.trim();
}

export function Sparkline({
  values,
  kind = 'used',
  width = 72,
  height = 20,
  ariaLabel,
  emptyText = 'no pod running',
  className = '',
}: SparklineProps) {
  const style = SERIES[kind];
  const d = toPath(values, width, height, 2);

  if (!d) {
    return (
      <span className={`text-[11px] text-[rgba(0,0,0,0.56)] dark:text-[rgba(255,255,255,0.5)] ${className}`} data-sparkline="empty" data-k8s-kit="">
        {emptyText}
      </span>
    );
  }

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      role="img"
      aria-label={ariaLabel}
      data-sparkline=""
      data-k8s-kit=""
      focusable="false"
    >
      <path
        d={d}
        fill="none"
        className={style.strokeClass}
        strokeWidth={style.strokeWidth}
        strokeDasharray={style.dash}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}

export default Sparkline;
