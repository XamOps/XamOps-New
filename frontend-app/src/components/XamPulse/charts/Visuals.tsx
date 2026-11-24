import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, Minus, TriangleAlert, X } from 'lucide-react';
import type { XpForecast, XpHealth, XpHeatTile, XpLogHistogramResponse, XpSeverity } from '../../../types/xampulse';
import { HEALTH_LABEL, HEALTH_VAR, useCountUp } from '../ui/ui';
import { formatClock, formatCount } from '../../../utils/xampulse/format';
import { linearPath } from './TimeSeriesChart';

/** Tiny inline trend line with a soft fade underneath, for dense table rows. */
export const Sparkline: React.FC<{ values: number[]; width?: number; height?: number; label: string; color?: string }> = ({ values, width = 88, height = 24, label, color = 'var(--xp-accent)' }) => {
  if (values.length < 2) return <span className="xp-muted">{'—'}</span>;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const pts = values.map((v, i) => ({ x: (i / (values.length - 1)) * (width - 2) + 1, y: height - 2 - ((v - lo) / span) * (height - 6) }));
  const d = linearPath(pts);
  return (
    <svg width={width} height={height} role="img" aria-label={`${label}: from ${values[0].toFixed(0)} to ${values[values.length - 1].toFixed(0)}`} style={{ display: 'block' }}>
      <path d={d} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
};

/** Circular score ring. The arc draws in and the number counts up once. A null score renders "No data", never a full ring. */
export const ScoreRing: React.FC<{ score: number | null; label?: string }> = ({ score, label = 'Fleet health' }) => {
  const r = 52;
  const c = 2 * Math.PI * r;
  const [drawn, setDrawn] = useState(false);
  useEffect(() => { const id = requestAnimationFrame(() => setDrawn(true)); return () => cancelAnimationFrame(id); }, []);
  const shown = useCountUp(score ?? 0);
  const level = score == null ? 'var(--xp-nodata)' : score >= 80 ? 'var(--xp-ok)' : score >= 60 ? 'var(--xp-warn)' : 'var(--xp-crit)';
  const word = score == null ? 'No data' : score >= 80 ? 'Healthy' : score >= 60 ? 'Needs attention' : 'Critical';
  const len = score == null || !drawn ? 0 : (score / 100) * c;
  return (
    <svg className="xp-score" viewBox="0 0 128 128" role="img" aria-label={score == null ? `${label}: no data` : `${label}: ${score} out of 100, ${word}`}>
      <circle cx="64" cy="64" r={r} fill="none" stroke="var(--xp-track)" strokeWidth="6" />
      {score != null && <circle className="xp-score__arc" cx="64" cy="64" r={r} fill="none" stroke={level} strokeWidth="6" strokeLinecap="round" strokeDasharray={`${len} ${c}`} transform="rotate(-90 64 64)" />}
      <text x="64" y={score == null ? 69 : 70} textAnchor="middle" style={{ fill: 'var(--xp-text)', fontSize: score == null ? 14 : 36, fontWeight: 300, letterSpacing: '-0.03em', fontFamily: 'var(--xp-font)' }}>
        {score == null ? 'No data' : Math.round(shown)}
      </text>
      {score != null && <text x="64" y="90" textAnchor="middle" style={{ fill: level, fontSize: 7.5, fontWeight: 600, letterSpacing: '0.08em', fontFamily: 'var(--xp-font)' }}>{word.toUpperCase()}</text>}
    </svg>
  );
};

const SEV_ORDER: XpSeverity[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
/** Volume is neutral slate; only warnings and errors get colour (the bright status fills), so a red stripe always means errors. */
const SEV_COLOR: Record<XpSeverity, string> = {
  TRACE: 'var(--xp-fill-volume-quiet)', DEBUG: 'var(--xp-fill-volume-quiet)',
  INFO: 'var(--xp-fill-volume)', WARN: 'var(--xp-fill-warn)', ERROR: 'var(--xp-fill-crit)', FATAL: 'var(--xp-fill-crit)',
};

function useMeasuredWidth(): [React.RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(800);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setW(el.clientWidth || 800);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver((entries) => { const cw = entries[0]?.contentRect.width; if (cw) setW(Math.round(cw)); });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/** Severity-stacked log volume ribbon: rounded bars at exact pixel size, hover readout, drag-to-select. */
export const LogRibbon: React.FC<{ data: XpLogHistogramResponse; height?: number; onBrush?: (fromMs: number, toMs: number) => void }> = ({ data, height = 84, onBrush }) => {
  const [wrapRef, W] = useMeasuredWidth();
  const svgRef = useRef<SVGSVGElement>(null);
  const [drag, setDrag] = useState<{ a: number; b: number } | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const n = data.buckets.length;
  if (n === 0) return null;
  const totals = data.buckets.map((b) => SEV_ORDER.reduce((s, k) => s + (b.counts[k] ?? 0), 0));
  const max = Math.max(1, ...totals);
  const slot = W / n;
  const bw = Math.max(1.5, slot * 0.62);
  const idxOf = (clientX: number): number => {
    const r = svgRef.current?.getBoundingClientRect();
    if (!r || r.width === 0) return 0;
    return Math.min(n - 1, Math.max(0, Math.floor(((clientX - r.left) / r.width) * n)));
  };
  const total = totals.reduce((s, v) => s + v, 0);
  const hb = hover !== null ? data.buckets[hover] : null;
  return (
    <div ref={wrapRef} style={{ position: 'relative' }}>
      <svg ref={svgRef} width={W} height={height} role="img"
        aria-label={`Log volume over time, ${formatCount(total)} lines, peak ${formatCount(max)} per bucket`}
        onPointerDown={(e) => { if (onBrush) { const i = idxOf(e.clientX); setDrag({ a: i, b: i }); (e.currentTarget as Element).setPointerCapture?.(e.pointerId); } }}
        onPointerMove={(e) => { const i = idxOf(e.clientX); setHover(i); if (drag) setDrag({ a: drag.a, b: i }); }}
        onPointerLeave={() => setHover(null)}
        onPointerUp={() => {
          if (drag && onBrush && Math.abs(drag.b - drag.a) >= 1) {
            const lo = Math.min(drag.a, drag.b);
            const hi = Math.max(drag.a, drag.b);
            onBrush(Date.parse(data.buckets[lo].t), Date.parse(data.buckets[hi].t) + data.bucketSec * 1000);
          }
          setDrag(null);
        }}
        style={{ cursor: onBrush ? 'crosshair' : 'default', display: 'block' }}>
        <line x1={0} x2={W} y1={height - 0.5} y2={height - 0.5} stroke="var(--xp-edge)" />
        {data.buckets.map((b, i) => {
          let y = height - 1;
          const x = i * slot + (slot - bw) / 2;
          const dim = hover !== null && hover !== i && !drag;
          const parts = SEV_ORDER.map((k) => ({ k, c: b.counts[k] ?? 0 })).filter((p) => p.c > 0);
          return (
            <g key={b.t} opacity={dim ? 0.55 : 1} style={{ transition: 'opacity 150ms ease-out' }}>
              {parts.map(({ k, c }, j) => {
                const h = Math.max(1, (c / max) * (height - 6));
                y -= h;
                const top = j === parts.length - 1;
                return <rect key={k} x={x} y={y} width={bw} height={h} rx={top ? Math.min(2, bw / 2) : 0} fill={SEV_COLOR[k]} />;
              })}
            </g>
          );
        })}
        {drag && <rect x={Math.min(drag.a, drag.b) * slot} y={0} width={(Math.abs(drag.b - drag.a) + 1) * slot} height={height} fill="var(--xp-accent)" opacity={0.14} />}
      </svg>
      {hb && hover !== null && !drag && (
        <div className="xp-tip" style={{ left: Math.min(W - 170, Math.max(0, hover * slot + 12)), top: -6 }} aria-hidden="true">
          <div className="xp-tip__t">{formatClock(hb.t)}</div>
          <div className="xp-tip__r"><span className="xp-dot" style={{ color: 'var(--xp-fill-volume)' }} />lines<span className="xp-tip__v">{formatCount(totals[hover])}</span></div>
          <div className="xp-tip__r"><span className="xp-dot" style={{ color: 'var(--xp-fill-warn)' }} />warn<span className="xp-tip__v">{formatCount(hb.counts.WARN ?? 0)}</span></div>
          <div className="xp-tip__r"><span className="xp-dot" style={{ color: 'var(--xp-fill-crit)' }} />error<span className="xp-tip__v" style={{ color: (hb.counts.ERROR ?? 0) + (hb.counts.FATAL ?? 0) > 0 ? 'var(--xp-crit)' : undefined }}>{formatCount((hb.counts.ERROR ?? 0) + (hb.counts.FATAL ?? 0))}</span></div>
        </div>
      )}
      <div className="xp-axis-row">
        <span>{formatClock(data.buckets[0].t).slice(0, 5)}</span>
        <span className="xp-legend" style={{ fontFamily: 'var(--xp-font)' }}>
          <span className="xp-legend__item"><span className="xp-dot" style={{ color: 'var(--xp-fill-volume)' }} aria-hidden="true" />info</span>
          <span className="xp-legend__item"><span className="xp-dot" style={{ color: 'var(--xp-fill-warn)' }} aria-hidden="true" />warn</span>
          <span className="xp-legend__item"><span className="xp-dot" style={{ color: 'var(--xp-fill-crit)' }} aria-hidden="true" />error</span>
        </span>
        <span>{formatClock(data.buckets[n - 1].t).slice(0, 5)}</span>
      </div>
    </div>
  );
};

const CELL_ICON: Record<XpHealth, React.ReactNode> = {
  OK: <Check size={14} strokeWidth={2} />, WARN: <TriangleAlert size={13} strokeWidth={2} />, CRIT: <X size={15} strokeWidth={2.25} />, NO_DATA: <Minus size={14} strokeWidth={2} />,
};

/** Honeycomb fleet map. Every cell has a text status in its accessible name and arrow-key navigation. */
export const FleetHoneycomb: React.FC<{ tiles: XpHeatTile[]; hrefFor: (t: XpHeatTile) => string }> = ({ tiles, hrefFor }) => {
  const wrap = useRef<HTMLDivElement>(null);
  const onKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const keys = ['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp'];
    if (!keys.includes(e.key)) return;
    const cells = Array.from(wrap.current?.querySelectorAll<HTMLElement>('a[data-cell]') ?? []);
    const i = cells.indexOf(document.activeElement as HTMLElement);
    if (i < 0) return;
    e.preventDefault();
    const step = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1;
    cells[Math.min(cells.length - 1, Math.max(0, i + step))]?.focus();
  };
  return (
    <div>
      <div className="xp-hex" ref={wrap} onKeyDown={onKey} role="list" aria-label="Fleet map. Each cell is a host. Use arrow keys to move.">
        {tiles.map((t) => (
          <span role="listitem" key={`${t.accountId}-${t.instanceId}`}>
            <Link data-cell="" to={hrefFor(t)} data-health={t.health}
              title={`${t.name}: ${HEALTH_LABEL[t.health]}${t.cpuPct != null ? `, CPU ${t.cpuPct.toFixed(0)}%` : ''}${t.memPct != null ? `, memory ${t.memPct.toFixed(0)}%` : ''}`}
              aria-label={`${t.name}, ${HEALTH_LABEL[t.health]}${t.score != null ? `, score ${t.score}` : ''}. Open details.`}>
              <span aria-hidden="true" style={{ display: 'grid' }}>{CELL_ICON[t.health]}</span>
            </Link>
          </span>
        ))}
      </div>
      <HealthLegend />
    </div>
  );
};

export const HealthLegend: React.FC = () => (
  <div className="xp-legend" style={{ marginTop: 14 }}>
    {(['OK', 'WARN', 'CRIT', 'NO_DATA'] as XpHealth[]).map((h) => (
      <span key={h} className="xp-legend__item"><span className="xp-dot" aria-hidden="true" style={{ color: HEALTH_VAR[h] }} />{HEALTH_LABEL[h]}</span>
    ))}
  </div>
);

/** Days-to-full bar with its uncertainty interval. */
export const ForecastFan: React.FC<{ f: XpForecast; horizonDays?: number }> = ({ f, horizonDays = 30 }) => {
  if (f.daysToFull == null) return <p className="xp-muted" style={{ margin: 0, fontSize: 12.5 }}>No reliable trend yet for {f.resource}. More history is needed.</p>;
  const pos = (d: number): number => Math.min(100, (d / horizonDays) * 100);
  const urgent = f.daysToFull <= 7;
  return (
    <div>
      <div style={{ fontWeight: 500, fontSize: 13 }}>{f.message}</div>
      <div role="img" aria-label={`${f.resource} is expected to be full in ${f.daysToFull} days${f.intervalDays ? `, between ${f.intervalDays[0]} and ${f.intervalDays[1]} days` : ''}`}
        style={{ position: 'relative', height: 8, marginTop: 12, background: 'var(--xp-track)', borderRadius: 999 }}>
        {f.intervalDays && <div style={{ position: 'absolute', left: `${pos(f.intervalDays[0])}%`, width: `${Math.max(1, pos(f.intervalDays[1]) - pos(f.intervalDays[0]))}%`, top: 0, bottom: 0, background: urgent ? 'var(--xp-crit)' : 'var(--xp-warn)', opacity: 0.28, borderRadius: 999 }} />}
        <div style={{ position: 'absolute', left: `calc(${pos(f.daysToFull)}% - 1px)`, width: 2, top: -4, bottom: -4, borderRadius: 1, background: urgent ? 'var(--xp-crit)' : 'var(--xp-warn)' }} />
      </div>
      <div className="xp-axis-row"><span>today</span><span style={{ color: urgent ? 'var(--xp-crit)' : 'var(--xp-warn)' }}>~{f.daysToFull} d</span><span>{horizonDays} d</span></div>
    </div>
  );
};
