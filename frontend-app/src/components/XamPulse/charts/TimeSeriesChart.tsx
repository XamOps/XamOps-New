import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { noDataRanges, prepareSegments, type Pt } from '../../../utils/xampulse/downsample';
import { formatDateTime } from '../../../utils/xampulse/format';
import { useChartSync } from './ChartSync';

export interface ChartSeries {
  key: string;
  name: string;
  color?: string;
  points: [number, number | null][];
  band?: [number, number, number][];
  dashed?: boolean;
}
export interface ChartMarker { t: number; label: string }

interface Props {
  series: ChartSeries[];
  height?: number;
  yMin?: number;
  yMax?: number;
  format?: (v: number) => string;
  markers?: ChartMarker[];
  onBrush?: (fromMs: number, toMs: number) => void;
  ariaLabel: string;
  fromMs?: number;
  toMs?: number;
  /** Legend row above the plot: dot, name and latest value. Never an overlay on the data. */
  showLegend?: boolean;
  /** Spacing (ms) above which neighbouring points count as a gap. Default: 2.5x each series' median spacing. */
  gapMs?: number;
  /** 'binary' rounds the y axis in 1024 steps (bytes, byte rates) so ticks read 25 / 50 / 75 / 100 KB/s. */
  yScale?: 'decimal' | 'binary';
  /** Stack the series as filled bands (composition, e.g. CPU time by state); the top edge is the total. */
  stacked?: boolean;
  /** Dashed horizontal reference lines with a short label, e.g. 1.0 = all CPUs busy. Always kept in view. */
  refLines?: { value: number; label: string }[];
}

/**
 * Fixed categorical order: accent, info, violet, warn, no-data, ok (green last: it sits closest to the teal accent). Never cycled through red, so a red
 * line on a XamPulse chart only ever means a threshold or an error.
 */
export const SERIES_COLORS = ['var(--xp-accent)', 'var(--xp-info)', 'var(--xp-violet)', 'var(--xp-warn)', 'var(--xp-nodata)', 'var(--xp-ok)'];
const PAD = { r: 10, t: 8, b: 22 };

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p;
}

/** niceMax in powers of 1024: 100 KiB, not 97.7 KiB. */
function niceMaxBinary(v: number): number {
  if (v <= 0) return 1;
  let p = 1;
  while (v / p >= 1024) p *= 1024;
  return niceMax(v / p) * p;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const TICK_STEPS = [1, 2, 5, 10, 15, 30].map((m) => m * MINUTE).concat([1, 2, 3, 6, 12].map((h) => h * HOUR), [1, 2, 7, 14].map((d) => d * DAY));

const two = (n: number): string => String(n).padStart(2, '0');
const dayMonth = (t: number): string => new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
const hourMinute = (t: number): string => { const d = new Date(t); return `${two(d.getHours())}:${two(d.getMinutes())}`; };

/**
 * Time-axis ticks on round local times, about one per 85 px so labels never overlap. Format follows the step:
 * "14:30" within a day; on multi-day ranges midnight reads "17 Nov" and other ticks "06:00"; daily steps read "17 Nov".
 */
export function timeTicks(x0: number, x1: number, innerW: number): { t: number; label: string }[] {
  const span = x1 - x0;
  if (!(span > 0)) return [];
  const maxTicks = Math.max(2, Math.floor(innerW / 85));
  const step = TICK_STEPS.find((st) => Math.floor(span / st) + 1 <= maxTicks) ?? TICK_STEPS[TICK_STEPS.length - 1];
  // Align to local wall-clock boundaries (midnight, whole hours), not to UTC.
  const offset = -new Date(x0).getTimezoneOffset() * MINUTE;
  let t = Math.ceil((x0 + offset) / step) * step - offset;
  const out: { t: number; label: string }[] = [];
  for (; t <= x1 && out.length < 40; t += step) {
    const d = new Date(t);
    const midnight = d.getHours() === 0 && d.getMinutes() === 0;
    const label = step >= DAY || (span > DAY && midnight) ? dayMonth(t) : hourMinute(t);
    out.push({ t, label });
  }
  return out;
}

/** "6d 22h", "3h 10m", "45m": how long a gap lasted. */
export function shortDuration(ms: number): string {
  const m = Math.round(ms / MINUTE);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return m % 60 ? `${h}h ${m % 60}m` : `${h}h`;
  const d = Math.floor(h / 24);
  return h % 24 ? `${d}d ${h % 24}h` : `${d}d`;
}

/** Change markers closer than {@code minPx} merge into one, so a burst of events is one readable mark with a count. */
export function clusterMarkers(markers: ChartMarker[], toX: (t: number) => number, minPx = 12): { t: number; labels: string[] }[] {
  const sorted = markers.slice().sort((a, b) => a.t - b.t);
  const out: { t: number; x: number; labels: string[] }[] = [];
  sorted.forEach((m) => {
    const x = toX(m.t);
    const last = out[out.length - 1];
    if (last && x - last.x < minPx) last.labels.push(m.label);
    else out.push({ t: m.t, x, labels: [m.label] });
  });
  return out.map(({ t, labels }) => ({ t, labels }));
}

function useWidth(ref: React.RefObject<HTMLDivElement | null>): number {
  const [w, setW] = useState(640);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setW(el.clientWidth || 640);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver((entries) => { const cw = entries[0]?.contentRect.width; if (cw) setW(Math.round(cw)); });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return w;
}

/**
 * Straight segments between real samples. Monitoring lines are never smoothed: a spline overshoots between two
 * samples and draws values (above the real peak, below the real dip) that were never measured.
 */
export function linearPath(pts: { x: number; y: number }[]): string {
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
}

/** Axis labels drop a trailing ".0" ("100%", not "100.0%"); tooltips keep full precision. */
const axisLabel = (s: string): string => s.replace(/\.0(?=\D*$)/, '');

/**
 * Dependency-free SVG line chart: straight lines between real samples, broken (and shaded "No data") wherever samples
 * are missing, dotted gridlines, synced crosshair, drag-to-zoom, bands, event markers.
 */
export const TimeSeriesChart: React.FC<Props> = ({ series, height = 200, yMin = 0, yMax, format = (v) => v.toFixed(1), markers, onBrush, ariaLabel, fromMs, toMs, showLegend = false, gapMs, yScale = 'decimal', stacked = false, refLines }) => {
  const wrapRef = useRef<HTMLDivElement>(null);
  const gradId = useId().replace(/:/g, '');
  const width = useWidth(wrapRef);
  const { hoverT, setHoverT } = useChartSync();
  const [drag, setDrag] = useState<{ x0: number; x1: number } | null>(null);

  const colorOf = (s: ChartSeries, i: number): string => s.color ?? SERIES_COLORS[i % SERIES_COLORS.length];

  /** Stacked mode: cumulative values on the union of timestamps, split into runs wherever samples are missing. */
  const stack = useMemo(() => {
    if (!stacked) return null;
    const times = Array.from(new Set(series.flatMap((s) => s.points.filter((p) => p[1] != null).map((p) => p[0])))).sort((a, b) => a - b);
    if (times.length < 2) return null;
    const maps = series.map((s) => new Map(s.points.filter((p) => p[1] != null).map((p) => [p[0], p[1] as number])));
    const deltas = times.slice(1).map((t, i) => t - times[i]).sort((a, b) => a - b);
    const limit = gapMs ?? (deltas[Math.floor(deltas.length / 2)] || MINUTE) * 2.5;
    const runs: number[][] = [];
    let run: number[] = [times[0]];
    for (let i = 1; i < times.length; i += 1) {
      if (times[i] - times[i - 1] > limit) { runs.push(run); run = []; }
      run.push(times[i]);
    }
    runs.push(run);
    const cum = times.map((t) => { let acc = 0; return maps.map((mp) => { acc += mp.get(t) ?? 0; return acc; }); });
    const at = new Map(times.map((t, i) => [t, i]));
    return { runs, cum, at, top: Math.max(...cum.map((c) => c[c.length - 1])) };
  }, [stacked, series, gapMs]);

  const { x0, x1, y1, prepared, padL, gaps } = useMemo(() => {
    const budget = Math.max(30, Math.floor(width / 3));
    const prep = series.map((s) => {
      const seg = prepareSegments(s.points, budget, gapMs);
      return { s, segs: seg.segments, pts: seg.segments.flat(), limit: seg.gapMs, raw: seg };
    });
    let lo = fromMs ?? Infinity;
    let hi = toMs ?? -Infinity;
    let top = yMax ?? 0;
    prep.forEach(({ s, pts }) => {
      pts.forEach(([x, y]) => { if (fromMs === undefined) lo = Math.min(lo, x); if (toMs === undefined) hi = Math.max(hi, x); if (yMax === undefined) top = Math.max(top, y); });
      s.band?.forEach(([, , h]) => { if (yMax === undefined) top = Math.max(top, h); });
    });
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo === hi) { lo = Date.now() - 3_600_000; hi = Date.now(); }
    if (stack) top = Math.max(top, stack.top);
    refLines?.forEach((r) => { top = Math.max(top, r.value * 1.15); });
    const ceil = yMax ?? (yScale === 'binary' ? niceMaxBinary(top * 1.05) : niceMax(top * 1.05));
    const longest = Math.max(...[0, 0.25, 0.5, 0.75, 1].map((f) => axisLabel(format(yMin + f * (ceil - yMin))).length));
    const withData = prep.filter((p) => p.pts.length > 0).map((p) => p.raw);
    return { x0: lo, x1: hi, y1: ceil, prepared: prep, padL: Math.max(30, Math.round(longest * 6.2 + 12)), gaps: noDataRanges(withData, lo, hi) };
  }, [series, width, fromMs, toMs, yMax, yMin, format, gapMs, yScale, stack, refLines]);

  const innerW = Math.max(50, width - padL - PAD.r);
  const innerH = height - PAD.t - PAD.b;
  const sx = (t: number): number => padL + ((t - x0) / (x1 - x0)) * innerW;
  const sy = (v: number): number => PAD.t + innerH - ((Math.min(v, y1) - yMin) / (y1 - yMin || 1)) * innerH;
  const tOf = (px: number): number => x0 + ((px - padL) / innerW) * (x1 - x0);
  const pathOf = (pts: Pt[]): string => linearPath(pts.map(([x, y]) => ({ x: sx(x), y: sy(y) })));
  const pathOfSegments = (segs: Pt[][]): string => segs.filter((g) => g.length > 1).map(pathOf).join(' ');

  const localX = (e: React.PointerEvent<SVGRectElement>): number => {
    const r = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
    return Math.min(padL + innerW, Math.max(padL, e.clientX - r.left));
  };

  const hoverX = hoverT !== null && hoverT >= x0 && hoverT <= x1 ? sx(hoverT) : null;
  /** Nearest real sample, but only within half the gap limit: inside a gap there is no value to show. */
  const nearest = (pts: Pt[], t: number, limit: number): Pt | null => {
    let best: Pt | null = null;
    let bd = Infinity;
    pts.forEach((p) => { const d = Math.abs(p[0] - t); if (d < bd) { bd = d; best = p; } });
    return best !== null && bd <= limit / 2 ? best : null;
  };

  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => yMin + f * (y1 - yMin));
  const xt = timeTicks(x0, x1, innerW);
  const solid = prepared.filter(({ s, pts }) => !s.dashed && pts.length > 1);
  const filled = !stack && solid.length === 1 ? solid[0] : null;

  /** The legend's "latest" is only current when the last sample is near the end of the range; otherwise it is stale. */
  const latestOf = (pts: Pt[], limit: number): string | undefined => {
    if (!pts.length) return undefined;
    const last = pts[pts.length - 1];
    const peak = Math.max(...pts.map((p) => p[1]));
    const peakText = format(peak);
    const now = x1 - last[0] > limit ? 'no recent data' : `${format(last[1])} now`;
    // Say which number it is: the latest value, plus the peak in range when that differs.
    return peakText === format(last[1]) ? now : `${now} · peak ${peakText}`;
  };

  const gapMinutes = gaps.reduce((n, [a, b]) => n + (b - a), 0) / 60_000;
  const summary = prepared.map(({ s, pts }) => {
    if (pts.length === 0) return `${s.name}: no data`;
    const vals = pts.map((p) => p[1]);
    return `${s.name}: min ${format(Math.min(...vals))}, max ${format(Math.max(...vals))}, latest ${format(vals[vals.length - 1])}`;
  }).join('. ') + (gapMinutes >= 1 ? `. No data for ${Math.round(gapMinutes)} minutes of the range` : '');
  const hoverInGap = hoverT !== null && gaps.some(([a, b]) => hoverT >= a && hoverT <= b);

  return (
    <div ref={wrapRef} style={{ position: 'relative' }}>
      {showLegend && (
        <ChartLegend items={prepared.map(({ s, pts, limit }, i) => ({ name: s.name, color: colorOf(s, i), value: latestOf(pts, limit), dashed: s.dashed }))} />
      )}
      <svg className="xp-chart" width={width} height={height} role="img" aria-label={`${ariaLabel}. ${summary}`}>
        <defs>
          {filled && (
            <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={colorOf(filled.s, prepared.indexOf(filled))} stopOpacity={0.18} />
              <stop offset="100%" stopColor={colorOf(filled.s, prepared.indexOf(filled))} stopOpacity={0} />
            </linearGradient>
          )}
          <pattern id={`${gradId}-gap`} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1={0} y1={0} x2={0} y2={6} stroke="var(--xp-nodata)" strokeWidth={1.5} strokeOpacity={0.18} />
          </pattern>
        </defs>
        {gaps.map(([a, b]) => (
          <rect key={`gap-${a}`} className="xp-chart__gap" x={sx(a)} y={PAD.t} width={Math.max(1, sx(b) - sx(a))} height={innerH} fill={`url(#${gradId}-gap)`}>
            <title>{`No data ${formatDateTime(a)} to ${formatDateTime(b)}`}</title>
          </rect>
        ))}
        {gaps.filter(([a, b]) => sx(b) - sx(a) > 96).map(([a, b]) => (
          <text key={`gapl-${a}`} className="xp-chart__gaplabel" x={(sx(a) + sx(b)) / 2} y={PAD.t + innerH / 2 + 3} textAnchor="middle">
            {`No data · ${shortDuration(b - a)}`}
          </text>
        ))}
        {ticks.map((t, k) => (
          <g key={t}>
            <line className={k === 0 ? 'baseline' : 'grid'} x1={padL} x2={padL + innerW} y1={sy(t)} y2={sy(t)} />
            <text x={padL - 8} y={sy(t) + 3} textAnchor="end">{axisLabel(format(t))}</text>
          </g>
        ))}
        {xt.map(({ t, label }) => {
          const x = sx(t);
          const anchor = x - padL < 18 ? 'start' : padL + innerW - x < 18 ? 'end' : 'middle';
          return <text key={t} x={x} y={height - 6} textAnchor={anchor}>{label}</text>;
        })}
        {prepared.map(({ s }, i) => s.band && s.band.length > 1 ? (
          <path key={`b${s.key}`} fill={colorOf(s, i)} opacity={0.1}
            d={`${s.band.map(([t, lo], k) => `${k === 0 ? 'M' : 'L'}${sx(t).toFixed(1)} ${sy(lo).toFixed(1)}`).join(' ')} ${s.band.slice().reverse().map(([t, , hi]) => `L${sx(t).toFixed(1)} ${sy(hi).toFixed(1)}`).join(' ')} Z`} />
        ) : null)}
        {filled && filled.segs.filter((g) => g.length > 1).map((g) => (
          <path key={`f${g[0][0]}`} d={`${pathOf(g)} L${sx(g[g.length - 1][0]).toFixed(1)},${(PAD.t + innerH).toFixed(1)} L${sx(g[0][0]).toFixed(1)},${(PAD.t + innerH).toFixed(1)} Z`} fill={`url(#${gradId})`} />
        ))}
        {stack && series.map((s, i) => stack.runs.filter((g) => g.length > 1).map((g) => {
          const upper = g.map((t) => `${sx(t).toFixed(1)},${sy(stack.cum[stack.at.get(t) as number][i]).toFixed(1)}`);
          const lower = g.slice().reverse().map((t) => `${sx(t).toFixed(1)},${sy(i === 0 ? yMin : stack.cum[stack.at.get(t) as number][i - 1]).toFixed(1)}`);
          return <path key={`st-${s.key}-${g[0]}`} className="xp-chart__stack" d={`M${upper.join(' L')} L${lower.join(' L')} Z`} fill={colorOf(s, i)} fillOpacity={0.5} stroke={colorOf(s, i)} strokeWidth={1} />;
        }))}
        {refLines?.map((r) => (
          <g key={`ref-${r.value}`}>
            <line x1={padL} x2={padL + innerW} y1={sy(r.value)} y2={sy(r.value)} stroke="var(--xp-text2)" strokeOpacity={0.55} strokeDasharray="5 4" />
            <text className="xp-chart__reflabel" x={padL + innerW - 4} y={sy(r.value) - 5} textAnchor="end">{r.label}</text>
          </g>
        ))}
        {!stack && prepared.map(({ s, segs }, i) => segs.some((g) => g.length > 1) ? (
          <path key={s.key} d={pathOfSegments(segs)} fill="none" stroke={colorOf(s, i)} strokeWidth={s.dashed ? 1.5 : 2} strokeOpacity={s.dashed ? 0.6 : 1}
            strokeDasharray={s.dashed ? '4 4' : undefined} strokeLinejoin="round" strokeLinecap="round" />
        ) : null)}
        {!stack && prepared.map(({ s, segs }, i) => segs.filter((g) => g.length === 1).map((g) => (
          <circle key={`${s.key}-p${g[0][0]}`} cx={sx(g[0][0])} cy={sy(g[0][1])} r={2} fill={colorOf(s, i)} />
        )))}
        {clusterMarkers((markers ?? []).filter((m) => m.t >= x0 && m.t <= x1), sx).map((c) => {
          const x = sx(c.t);
          const base = PAD.t + innerH;
          const many = c.labels.length > 1;
          const tip = c.labels.slice(0, 8).join('\n') + (c.labels.length > 8 ? `\n+${c.labels.length - 8} more` : '');
          return (
            <g key={`mk-${c.t}`} className="xp-chart__marker">
              <line x1={x} x2={x} y1={PAD.t} y2={base} stroke="var(--xp-violet)" strokeOpacity={0.28} strokeDasharray="2 3" />
              <circle cx={x} cy={base} r={many ? 7 : 4} fill="var(--xp-violet)" stroke="var(--xp-surface)" strokeWidth={1.5}><title>{tip}</title></circle>
              {many && <text x={x} y={base + 3} textAnchor="middle" className="xp-chart__markercount">{c.labels.length > 99 ? '99+' : c.labels.length}</text>}
            </g>
          );
        })}
        {hoverX !== null && (
          <>
            <line x1={hoverX} x2={hoverX} y1={PAD.t} y2={PAD.t + innerH} stroke="var(--xp-border)" strokeOpacity={0.7} />
            {!stack && prepared.map(({ s, pts, limit }, i) => {
              const p = hoverT !== null ? nearest(pts, hoverT, limit) : null;
              return p ? <circle key={s.key} cx={sx(p[0])} cy={sy(p[1])} r={3} fill={colorOf(s, i)} stroke="var(--xp-surface)" strokeWidth={1.5} /> : null;
            })}
          </>
        )}
        {drag && (
          <g>
            <rect x={Math.min(drag.x0, drag.x1)} y={PAD.t} width={Math.abs(drag.x1 - drag.x0)} height={innerH} fill="var(--xp-accent)" opacity={0.1} />
            <line x1={drag.x0} x2={drag.x0} y1={PAD.t} y2={PAD.t + innerH} stroke="var(--xp-accent)" strokeOpacity={0.6} />
            <line x1={drag.x1} x2={drag.x1} y1={PAD.t} y2={PAD.t + innerH} stroke="var(--xp-accent)" strokeOpacity={0.6} />
          </g>
        )}
        <rect x={padL} y={PAD.t} width={innerW} height={innerH} fill="transparent" style={{ cursor: onBrush ? 'crosshair' : 'default' }}
          onPointerMove={(e) => { const px = localX(e); setHoverT(tOf(px)); if (drag) setDrag({ x0: drag.x0, x1: px }); }}
          onPointerLeave={() => { setHoverT(null); }}
          onPointerDown={(e) => { if (!onBrush) return; (e.currentTarget as Element).setPointerCapture?.(e.pointerId); const px = localX(e); setDrag({ x0: px, x1: px }); }}
          onPointerUp={() => {
            if (drag && onBrush && Math.abs(drag.x1 - drag.x0) > 8) { const a = tOf(Math.min(drag.x0, drag.x1)); const b = tOf(Math.max(drag.x0, drag.x1)); onBrush(Math.round(a), Math.round(b)); }
            setDrag(null);
          }} />
      </svg>
      {hoverT !== null && hoverX !== null && (
        <div className="xp-tip" style={{ left: hoverX + 14 + 220 > width ? Math.max(0, hoverX - 14 - 220) : hoverX + 14, top: showLegend ? 34 : 6 }} aria-hidden="true">
          <div className="xp-tip__t">{formatDateTime(hoverT)}</div>
          {hoverInGap && <div className="xp-tip__r xp-muted">No data at this time</div>}
          {prepared.slice(0, 10).map(({ s, pts, limit }, i) => {
            const p = nearest(pts, hoverT, limit);
            return p ? (
              <div key={s.key} className="xp-tip__r">
                <span className="xp-dot" style={{ color: colorOf(s, i), opacity: s.dashed ? 0.55 : 1 }} />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 170 }}>{s.name}</span>
                <span className="xp-tip__v">{format(p[1])}</span>
              </div>
            ) : null;
          })}
        </div>
      )}
    </div>
  );
};

export const ChartLegend: React.FC<{ items: { name: string; color?: string; value?: string; dashed?: boolean }[] }> = ({ items }) => (
  <div className="xp-legend" aria-label="Chart legend" style={{ marginBottom: 12 }}>
    {items.map((it, i) => (
      <span key={it.name} className="xp-legend__item">
        <span className="xp-dot" aria-hidden="true" style={{ color: it.color ?? SERIES_COLORS[i % SERIES_COLORS.length], opacity: it.dashed ? 0.55 : 1 }} />
        {it.name}
        {it.value && <span className="xp-legend__v">{it.value}</span>}
      </span>
    ))}
  </div>
);
