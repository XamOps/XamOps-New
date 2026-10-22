import { useMemo, useRef, useState, useEffect } from 'react';
import { CHART, TEXT, HAIRLINE } from './tokens';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

/**
 * Hand-built stacked area chart for "Vulnerability findings by severity".
 *
 * No charting library — raw SVG only, mirroring the XAM Cloud chart toolkit approach:
 * a ResizeObserver measures the container so the chart renders at exact pixel width (no
 * viewBox stretching / blur), Catmull-Rom smoothing turns the series into curved cubic-bezier
 * paths, friendly axis maxima + gridline ticks are computed locally, and hover is a custom
 * crosshair + marker dots + an absolutely-positioned HTML tooltip. Light-theme palette; the
 * series colors come from the shared CHART.areaStroke map so stroke/fill never drift.
 *
 * Stacked semantics: bands are drawn bottom-up OTHER → HIGH → CRITICAL, each filled between its
 * own cumulative top and the cumulative top of the band below it, so the visible thickness of a
 * band equals that series' value (matching the previous Recharts stackId behavior).
 */

export interface SeverityAreaDatum {
  date: string;
  CRITICAL: number;
  HIGH: number;
  OTHER: number;
}

/** Drawn order: first entry is the BOTTOM band. */
const SERIES: { key: 'OTHER' | 'HIGH' | 'CRITICAL'; label: string }[] = [
  { key: 'OTHER', label: 'Other' },
  { key: 'HIGH', label: 'High' },
  { key: 'CRITICAL', label: 'Critical' },
];

/** Legend order: critical first (reads top-down like the stack). */
const LEGEND = [...SERIES].reverse();

const PAD = { l: 34, r: 8, t: 8, b: 20 };

interface Point { x: number; y: number }

/** Catmull-Rom → cubic bezier, same math as the XAM Cloud toolkit's smoothPath. */
function smoothPath(pts: Point[]): string {
  if (pts.length === 0) return '';
  if (pts.length === 1) return `M${pts[0].x},${pts[0].y}`;
  let d = `M${pts[0].x},${pts[0].y}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] ?? pts[i];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] ?? p2;
    const c1x = p1.x + (p2.x - p0.x) / 6;
    const c1y = p1.y + (p2.y - p0.y) / 6;
    const c2x = p2.x - (p3.x - p1.x) / 6;
    const c2y = p2.y - (p3.y - p1.y) / 6;
    d += ` C${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2.x.toFixed(2)},${p2.y.toFixed(2)}`;
  }
  return d;
}

/** Round a max up to a friendly axis top (1/2/2.5/5 × 10^k). */
function niceMax(raw: number): number {
  if (raw <= 0) return 1;
  const mag = 10 ** Math.floor(Math.log10(raw));
  for (const m of [1, 2, 2.5, 5, 10]) if (raw <= m * mag) return m * mag;
  return 10 * mag;
}

function ticks(max: number, count = 4): number[] {
  return Array.from({ length: count + 1 }, (_, i) => (max / count) * i);
}

function fmtAxis(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(v % 1_000_000 === 0 ? 0 : 1)}M`;
  if (v >= 1000) return `${(v / 1000).toFixed(v % 1000 === 0 ? 0 : 1)}k`;
  return `${Math.round(v)}`;
}

/** Sparse x labels — first, last, and a few between. */
function sparseIdx(n: number, want = 6): number[] {
  if (n <= want) return Array.from({ length: n }, (_, i) => i);
  const step = (n - 1) / (want - 1);
  return Array.from({ length: want }, (_, i) => Math.round(i * step));
}

function useMeasure<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    ro.observe(el);
    setWidth(el.getBoundingClientRect().width);
    return () => ro.disconnect();
  }, []);
  return { ref, width };
}

interface Props {
  data: SeverityAreaDatum[];
  height?: number;
}

export default function SeverityAreaChart({ data, height = 150 }: Props) {
  const { ref, width } = useMeasure<HTMLDivElement>();
  const reduced = usePrefersReducedMotion();
  const [hover, setHover] = useState<number | null>(null);

  const n = data.length;

  // Cumulative tops per row (OTHER, OTHER+HIGH, OTHER+HIGH+CRITICAL) and the stack total.
  const { cum, max } = useMemo(() => {
    const cumRows = data.map((d) => {
      const other = Number(d.OTHER) || 0;
      const high = Number(d.HIGH) || 0;
      const crit = Number(d.CRITICAL) || 0;
      return { OTHER: other, HIGH: other + high, CRITICAL: other + high + crit };
    });
    const peak = Math.max(1, ...cumRows.map((r) => r.CRITICAL));
    return { cum: cumRows, max: niceMax(peak) };
  }, [data]);

  const iw = Math.max(0, width - PAD.l - PAD.r);
  const ih = height - PAD.t - PAD.b;
  const xAt = (i: number) => PAD.l + (n <= 1 ? 0 : (i / (n - 1)) * iw);
  const yAt = (v: number) => PAD.t + ih - (v / max) * ih;

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left - PAD.l;
    const i = Math.round((x / iw) * (n - 1));
    setHover(i >= 0 && i < n ? i : null);
  };

  // Build a closed band path for a series: its own cumulative top, then back along the baseline
  // below it (the cumulative top of the band beneath, or y=0 for the bottom band).
  const bandPath = (key: 'OTHER' | 'HIGH' | 'CRITICAL'): string => {
    const topPts = cum.map((r, i) => ({ x: xAt(i), y: yAt(r[key]) }));
    const belowVal = (i: number): number =>
      key === 'OTHER' ? 0 : key === 'HIGH' ? cum[i].OTHER : cum[i].HIGH;
    const basePts = cum.map((_, i) => ({ x: xAt(i), y: yAt(belowVal(i)) })).reverse();
    const top = smoothPath(topPts);
    // Reverse-direction smooth for the lower edge so the fill closes cleanly.
    const base = smoothPath(basePts).replace(/^M/, 'L');
    return `${top} ${base} Z`;
  };

  if (width === 0 || n < 1) {
    // Measure pass (or empty) — render the measuring container only.
    return <div ref={ref} className="w-full" style={{ height }} />;
  }

  return (
    <div ref={ref} className="relative w-full">
      {/* Legend */}
      <div className="flex items-center gap-4 mb-2">
        {LEGEND.map((s) => (
          <span key={s.key} className="inline-flex items-center gap-1.5 text-[11px]" style={{ color: TEXT.muted }}>
            <span className="inline-block rounded-full" style={{ width: 7, height: 7, background: CHART.areaStroke[s.key] }} />
            {s.label}
          </span>
        ))}
      </div>

      <svg width={width} height={height} onMouseMove={onMove} onMouseLeave={() => setHover(null)} className="block">
        <defs>
          {SERIES.map((s) => (
            <linearGradient key={s.key} id={`sev-area-${s.key}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={CHART.areaStroke[s.key]} stopOpacity={0.45} />
              <stop offset="100%" stopColor={CHART.areaStroke[s.key]} stopOpacity={0.12} />
            </linearGradient>
          ))}
          {/* Wipe-in reveal: a left-to-right clip that animates width unless reduced-motion. */}
          {!reduced && (
            <clipPath id="sev-area-reveal">
              <rect x={0} y={0} height={height} width={width}>
                <animate attributeName="width" from={PAD.l} to={width} dur="0.6s" fill="freeze" calcMode="spline" keySplines="0.22 1 0.36 1" keyTimes="0;1" />
              </rect>
            </clipPath>
          )}
        </defs>

        {/* Dotted horizontal gridlines + y labels */}
        {ticks(max).map((t) => (
          <g key={t}>
            <line x1={PAD.l} x2={width - PAD.r} y1={yAt(t)} y2={yAt(t)} stroke={HAIRLINE} strokeWidth={1} strokeDasharray="2 5" />
            <text x={PAD.l - 6} y={yAt(t) + 3} textAnchor="end" fontSize={10} fill={TEXT.faint} fontFamily="var(--font-mono, ui-monospace, monospace)">
              {fmtAxis(t)}
            </text>
          </g>
        ))}

        {/* Stacked bands, bottom-up, under the reveal clip */}
        <g clipPath={reduced ? undefined : 'url(#sev-area-reveal)'}>
          {SERIES.map((s) => (
            <path key={s.key} d={bandPath(s.key)} fill={`url(#sev-area-${s.key})`} stroke="none" />
          ))}
          {/* Top stroke of each cumulative edge for crisp separation */}
          {SERIES.map((s) => (
            <path
              key={`stroke-${s.key}`}
              d={smoothPath(cum.map((r, i) => ({ x: xAt(i), y: yAt(r[s.key]) })))}
              fill="none"
              stroke={CHART.areaStroke[s.key]}
              strokeWidth={1.5}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          ))}
        </g>

        {/* x labels */}
        {sparseIdx(n).map((i) => (
          <text key={i} x={xAt(i)} y={height - 5} textAnchor="middle" fontSize={10} fill={TEXT.faint} fontFamily="var(--font-mono, ui-monospace, monospace)">
            {String(data[i].date)}
          </text>
        ))}

        {/* Hover crosshair + markers */}
        {hover !== null && (
          <>
            <line x1={xAt(hover)} x2={xAt(hover)} y1={PAD.t} y2={PAD.t + ih} stroke={HAIRLINE} strokeWidth={1} />
            {SERIES.map((s) => (
              <circle key={s.key} cx={xAt(hover)} cy={yAt(cum[hover][s.key])} r={3} fill={CHART.areaStroke[s.key]} stroke="#ffffff" strokeWidth={1.5} />
            ))}
          </>
        )}
      </svg>

      {/* Tooltip */}
      {hover !== null && (
        <div
          className="pointer-events-none absolute z-10 rounded-md px-2.5 py-2"
          style={{
            left: Math.min(xAt(hover) + 10, width - 150),
            top: PAD.t + 26,
            background: '#ffffff',
            border: `1px solid ${HAIRLINE}`,
            boxShadow: '0 8px 20px -6px rgba(15,23,42,0.18)',
          }}
        >
          <div className="mb-1 font-mono text-[10px]" style={{ color: TEXT.muted }}>{String(data[hover].date)}</div>
          {LEGEND.map((s) => (
            <div key={s.key} className="flex items-center gap-1.5 py-px text-[11px]" style={{ color: TEXT.body }}>
              <span className="inline-block rounded-full" style={{ width: 6, height: 6, background: CHART.areaStroke[s.key] }} />
              <span className="mr-3">{s.label}</span>
              <span className="ml-auto font-mono text-[10.5px] tabular-nums" style={{ color: TEXT.strong }}>
                {(Number(data[hover][s.key]) || 0).toLocaleString()}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
