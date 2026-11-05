import React, { useEffect, useMemo, useRef, useState } from 'react';
import { prepareSeries, type Pt } from '../../../utils/xampulse/downsample';
import { formatClock, formatDateTime } from '../../../utils/xampulse/format';
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
}

export const SERIES_COLORS = ['var(--xp-accent)', 'var(--xp-info)', 'var(--xp-warn)', 'var(--xp-crit)', 'var(--xp-ok)', 'var(--xp-violet)'];
const PAD = { l: 44, r: 12, t: 10, b: 22 };

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
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

/** Dependency-free SVG line/area chart: synced crosshair, drag-to-select range, bands, event markers. */
export const TimeSeriesChart: React.FC<Props> = ({ series, height = 200, yMin = 0, yMax, format = (v) => v.toFixed(1), markers, onBrush, ariaLabel, fromMs, toMs }) => {
  const wrapRef = useRef<HTMLDivElement>(null);
  const width = useWidth(wrapRef);
  const { hoverT, setHoverT } = useChartSync();
  const [drag, setDrag] = useState<{ x0: number; x1: number } | null>(null);
  const innerW = Math.max(50, width - PAD.l - PAD.r);
  const innerH = height - PAD.t - PAD.b;

  const prepared = useMemo(() => series.map((s) => ({ s, pts: prepareSeries(s.points, Math.max(30, Math.floor(innerW / 2))) })), [series, innerW]);

  const { x0, x1, y1 } = useMemo(() => {
    let lo = fromMs ?? Infinity;
    let hi = toMs ?? -Infinity;
    let top = yMax ?? 0;
    prepared.forEach(({ s, pts }) => {
      pts.forEach(([x, y]) => { if (fromMs === undefined) lo = Math.min(lo, x); if (toMs === undefined) hi = Math.max(hi, x); if (yMax === undefined) top = Math.max(top, y); });
      s.band?.forEach(([, , h]) => { if (yMax === undefined) top = Math.max(top, h); });
    });
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo === hi) { lo = Date.now() - 3_600_000; hi = Date.now(); }
    return { x0: lo, x1: hi, y1: yMax ?? niceMax(top * 1.05) };
  }, [prepared, fromMs, toMs, yMax]);

  const sx = (t: number): number => PAD.l + ((t - x0) / (x1 - x0)) * innerW;
  const sy = (v: number): number => PAD.t + innerH - ((v - yMin) / (y1 - yMin || 1)) * innerH;
  const tOf = (px: number): number => x0 + ((px - PAD.l) / innerW) * (x1 - x0);

  const pathOf = (pts: Pt[]): string => pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${sx(x).toFixed(1)} ${sy(y).toFixed(1)}`).join(' ');

  const localX = (e: React.PointerEvent<SVGRectElement>): number => {
    const r = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
    return Math.min(PAD.l + innerW, Math.max(PAD.l, e.clientX - r.left));
  };

  const hoverX = hoverT !== null && hoverT >= x0 && hoverT <= x1 ? sx(hoverT) : null;
  const nearest = (pts: Pt[], t: number): Pt | null => {
    let best: Pt | null = null;
    let bd = Infinity;
    pts.forEach((p) => { const d = Math.abs(p[0] - t); if (d < bd) { bd = d; best = p; } });
    return best;
  };

  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => yMin + f * (y1 - yMin));
  const xt = [0, 0.25, 0.5, 0.75, 1].map((f) => x0 + f * (x1 - x0));
  const spanDays = (x1 - x0) > 36 * 3_600_000;

  const summary = prepared.map(({ s, pts }) => {
    if (pts.length === 0) return `${s.name}: no data`;
    const vals = pts.map((p) => p[1]);
    return `${s.name}: min ${format(Math.min(...vals))}, max ${format(Math.max(...vals))}, latest ${format(vals[vals.length - 1])}`;
  }).join('. ');

  return (
    <div ref={wrapRef} style={{ position: 'relative' }}>
      <svg className="xp-chart" width={width} height={height} role="img" aria-label={`${ariaLabel}. ${summary}`}>
        {ticks.map((t) => (
          <g key={t}>
            <line className="grid" x1={PAD.l} x2={PAD.l + innerW} y1={sy(t)} y2={sy(t)} />
            <text x={PAD.l - 6} y={sy(t) + 4} textAnchor="end">{format(t)}</text>
          </g>
        ))}
        {xt.map((t) => <text key={t} x={sx(t)} y={height - 6} textAnchor="middle">{spanDays ? formatDateTime(t) : formatClock(t)}</text>)}
        {prepared.map(({ s }, i) => s.band && s.band.length > 1 ? (
          <path key={`b${s.key}`} fill={s.color ?? SERIES_COLORS[i % SERIES_COLORS.length]} opacity={0.14}
            d={`${s.band.map(([t, lo], k) => `${k === 0 ? 'M' : 'L'}${sx(t).toFixed(1)} ${sy(lo).toFixed(1)}`).join(' ')} ${s.band.slice().reverse().map(([t, , hi]) => `L${sx(t).toFixed(1)} ${sy(hi).toFixed(1)}`).join(' ')} Z`} />
        ) : null)}
        {prepared.map(({ s, pts }, i) => pts.length > 0 ? (
          <path key={s.key} d={pathOf(pts)} fill="none" stroke={s.color ?? SERIES_COLORS[i % SERIES_COLORS.length]} strokeWidth={2} strokeDasharray={s.dashed ? '5 4' : undefined} strokeLinejoin="round" />
        ) : null)}
        {markers?.filter((m) => m.t >= x0 && m.t <= x1).map((m) => (
          <g key={`${m.t}-${m.label}`}>
            <line x1={sx(m.t)} x2={sx(m.t)} y1={PAD.t} y2={PAD.t + innerH} stroke="var(--xp-warn)" strokeDasharray="3 3" />
            <circle cx={sx(m.t)} cy={PAD.t + 4} r={4} fill="var(--xp-warn)"><title>{m.label}</title></circle>
          </g>
        ))}
        {hoverX !== null && <line x1={hoverX} x2={hoverX} y1={PAD.t} y2={PAD.t + innerH} stroke="var(--xp-text)" opacity={0.5} />}
        {drag && <rect x={Math.min(drag.x0, drag.x1)} y={PAD.t} width={Math.abs(drag.x1 - drag.x0)} height={innerH} fill="var(--xp-accent)" opacity={0.18} />}
        <rect x={PAD.l} y={PAD.t} width={innerW} height={innerH} fill="transparent" style={{ cursor: onBrush ? 'crosshair' : 'default' }}
          onPointerMove={(e) => { const px = localX(e); setHoverT(tOf(px)); if (drag) setDrag({ x0: drag.x0, x1: px }); }}
          onPointerLeave={() => { setHoverT(null); }}
          onPointerDown={(e) => { if (!onBrush) return; (e.currentTarget as Element).setPointerCapture?.(e.pointerId); const px = localX(e); setDrag({ x0: px, x1: px }); }}
          onPointerUp={() => {
            if (drag && onBrush && Math.abs(drag.x1 - drag.x0) > 8) { const a = tOf(Math.min(drag.x0, drag.x1)); const b = tOf(Math.max(drag.x0, drag.x1)); onBrush(Math.round(a), Math.round(b)); }
            setDrag(null);
          }} />
      </svg>
      {hoverT !== null && hoverX !== null && (
        <div className="xp-tip" style={{ left: Math.min(width - 180, hoverX + 12), top: 8 }} aria-hidden="true">
          <div className="xp-muted">{formatDateTime(hoverT)}</div>
          {prepared.map(({ s, pts }, i) => {
            const p = nearest(pts, hoverT);
            return p ? <div key={s.key}><span style={{ color: s.color ?? SERIES_COLORS[i % SERIES_COLORS.length] }}>{'\u25CF'}</span> {s.name}: <strong>{format(p[1])}</strong></div> : null;
          })}
        </div>
      )}
    </div>
  );
};

export const ChartLegend: React.FC<{ items: { name: string; color?: string }[] }> = ({ items }) => (
  <div className="xp-legend" aria-label="Chart legend">
    {items.map((it, i) => <span key={it.name}><span aria-hidden="true" style={{ color: it.color ?? SERIES_COLORS[i % SERIES_COLORS.length] }}>{'\u25CF'}</span> {it.name}</span>)}
  </div>
);
