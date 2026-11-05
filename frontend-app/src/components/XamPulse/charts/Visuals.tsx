import React, { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { XpForecast, XpHealth, XpHeatTile, XpLogHistogramResponse, XpSeverity } from '../../../types/xampulse';
import { HEALTH_LABEL } from '../ui/ui';
import { formatClock, formatCount } from '../../../utils/xampulse/format';

export const Sparkline: React.FC<{ values: number[]; width?: number; height?: number; label: string; color?: string }> = ({ values, width = 90, height = 24, label, color = 'var(--xp-accent)' }) => {
  if (values.length < 2) return <span className="xp-muted">{'\u2014'}</span>;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const d = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${((i / (values.length - 1)) * (width - 2) + 1).toFixed(1)} ${(height - 2 - ((v - lo) / span) * (height - 4)).toFixed(1)}`).join(' ');
  return (
    <svg width={width} height={height} role="img" aria-label={`${label}: from ${values[0].toFixed(0)} to ${values[values.length - 1].toFixed(0)}`}>
      <path d={d} fill="none" stroke={color} strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
};

/** Circular score ring. A null score renders "No data", never a full ring. */
export const ScoreRing: React.FC<{ score: number | null; label?: string }> = ({ score, label = 'Fleet health' }) => {
  const r = 52;
  const c = 2 * Math.PI * r;
  const level = score == null ? 'var(--xp-nodata)' : score >= 80 ? 'var(--xp-ok)' : score >= 60 ? 'var(--xp-warn)' : 'var(--xp-crit)';
  const word = score == null ? 'No data' : score >= 80 ? 'Healthy' : score >= 60 ? 'Needs attention' : 'Critical';
  return (
    <svg className="xp-score" viewBox="0 0 128 128" role="img" aria-label={score == null ? `${label}: no data` : `${label}: ${score} out of 100, ${word}`}>
      <circle cx="64" cy="64" r={r} fill="none" stroke="var(--xp-surface2)" strokeWidth="12" />
      <circle cx="64" cy="64" r={r} fill="none" stroke="var(--xp-border)" strokeWidth="1" />
      {score != null && <circle cx="64" cy="64" r={r} fill="none" stroke={level} strokeWidth="12" strokeLinecap="round" strokeDasharray={`${(score / 100) * c} ${c}`} transform="rotate(-90 64 64)" />}
      <text x="64" y="64" textAnchor="middle" style={{ fill: 'var(--xp-text)', fontSize: score == null ? 15 : 34, fontWeight: 750 }}>{score == null ? 'No data' : score}</text>
      <text x="64" y="86" textAnchor="middle" style={{ fill: 'var(--xp-muted)', fontSize: 11 }}>{score == null ? '' : word}</text>
    </svg>
  );
};

const SEV_ORDER: XpSeverity[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const SEV_COLOR: Record<XpSeverity, string> = { TRACE: 'var(--xp-nodata)', DEBUG: 'var(--xp-nodata)', INFO: 'var(--xp-info)', WARN: 'var(--xp-warn)', ERROR: 'var(--xp-crit)', FATAL: 'var(--xp-crit)' };

/** Severity-stacked log volume ribbon with drag-to-select. */
export const LogRibbon: React.FC<{ data: XpLogHistogramResponse; height?: number; onBrush?: (fromMs: number, toMs: number) => void }> = ({ data, height = 84, onBrush }) => {
  const ref = useRef<SVGSVGElement>(null);
  const [drag, setDrag] = useState<{ a: number; b: number } | null>(null);
  const W = 1000;
  const n = data.buckets.length;
  if (n === 0) return null;
  const totals = data.buckets.map((b) => SEV_ORDER.reduce((s, k) => s + (b.counts[k] ?? 0), 0));
  const max = Math.max(1, ...totals);
  const bw = W / n;
  const idxOf = (clientX: number): number => {
    const r = ref.current?.getBoundingClientRect();
    if (!r || r.width === 0) return 0;
    return Math.min(n - 1, Math.max(0, Math.floor(((clientX - r.left) / r.width) * n)));
  };
  const total = totals.reduce((s, v) => s + v, 0);
  return (
    <div>
      <svg ref={ref} viewBox={`0 0 ${W} ${height}`} preserveAspectRatio="none" width="100%" height={height} role="img"
        aria-label={`Log volume over time, ${formatCount(total)} lines, peak ${formatCount(max)} per bucket`}
        onPointerDown={(e) => { if (onBrush) { const i = idxOf(e.clientX); setDrag({ a: i, b: i }); (e.currentTarget as Element).setPointerCapture?.(e.pointerId); } }}
        onPointerMove={(e) => { if (drag) setDrag({ a: drag.a, b: idxOf(e.clientX) }); }}
        onPointerUp={() => {
          if (drag && onBrush && Math.abs(drag.b - drag.a) >= 1) {
            const lo = Math.min(drag.a, drag.b);
            const hi = Math.max(drag.a, drag.b);
            onBrush(Date.parse(data.buckets[lo].t), Date.parse(data.buckets[hi].t) + data.bucketSec * 1000);
          }
          setDrag(null);
        }}
        style={{ cursor: onBrush ? 'crosshair' : 'default', display: 'block' }}>
        {data.buckets.map((b, i) => {
          let y = height;
          return (
            <g key={b.t}>
              {SEV_ORDER.map((k) => {
                const c = b.counts[k] ?? 0;
                if (!c) return null;
                const h = (c / max) * (height - 4);
                y -= h;
                return <rect key={k} x={i * bw + 0.5} y={y} width={Math.max(1, bw - 1)} height={h} fill={SEV_COLOR[k]} />;
              })}
            </g>
          );
        })}
        {drag && <rect x={Math.min(drag.a, drag.b) * bw} y={0} width={(Math.abs(drag.b - drag.a) + 1) * bw} height={height} fill="var(--xp-accent)" opacity={0.2} />}
      </svg>
      <div className="xp-row xp-muted" style={{ justifyContent: 'space-between', fontSize: 11 }}>
        <span>{formatClock(data.buckets[0].t)}</span>
        <span className="xp-legend"><span style={{ color: 'var(--xp-info)' }}>{'\u25CF'} info</span><span style={{ color: 'var(--xp-warn)' }}>{'\u25CF'} warn</span><span style={{ color: 'var(--xp-crit)' }}>{'\u25CF'} error</span></span>
        <span>{formatClock(data.buckets[n - 1].t)}</span>
      </div>
    </div>
  );
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
          <span role="listitem" key={t.instanceId}>
            <Link data-cell="" to={hrefFor(t)} data-health={t.health}
              title={`${t.name}: ${HEALTH_LABEL[t.health]}${t.cpuPct != null ? `, CPU ${t.cpuPct.toFixed(0)}%` : ''}${t.memPct != null ? `, memory ${t.memPct.toFixed(0)}%` : ''}`}
              aria-label={`${t.name}, ${HEALTH_LABEL[t.health]}${t.score != null ? `, score ${t.score}` : ''}. Open details.`}>
              {t.health === 'OK' ? '\u2713' : t.health === 'WARN' ? '\u25B2' : t.health === 'CRIT' ? '\u2716' : '?'}
            </Link>
          </span>
        ))}
      </div>
      <HealthLegend />
    </div>
  );
};

export const HealthLegend: React.FC = () => (
  <div className="xp-legend" style={{ marginTop: 8 }}>
    {(['OK', 'WARN', 'CRIT', 'NO_DATA'] as XpHealth[]).map((h) => (
      <span key={h}><span aria-hidden="true" style={{ color: h === 'OK' ? 'var(--xp-ok)' : h === 'WARN' ? 'var(--xp-warn)' : h === 'CRIT' ? 'var(--xp-crit)' : 'var(--xp-nodata)' }}>{'\u25CF'}</span> {HEALTH_LABEL[h]}</span>
    ))}
  </div>
);

/** Days-to-full bar with its uncertainty interval. */
export const ForecastFan: React.FC<{ f: XpForecast; horizonDays?: number }> = ({ f, horizonDays = 30 }) => {
  if (f.daysToFull == null) return <p className="xp-muted" style={{ margin: 0 }}>No reliable trend yet for {f.resource}. More history is needed.</p>;
  const pos = (d: number): number => Math.min(100, (d / horizonDays) * 100);
  return (
    <div>
      <div style={{ fontWeight: 650 }}>{f.message}</div>
      <div role="img" aria-label={`${f.resource} is expected to be full in ${f.daysToFull} days${f.intervalDays ? `, between ${f.intervalDays[0]} and ${f.intervalDays[1]} days` : ''}`}
        style={{ position: 'relative', height: 18, marginTop: 8, background: 'var(--xp-surface2)', border: '1px solid var(--xp-border)', borderRadius: 9 }}>
        {f.intervalDays && <div style={{ position: 'absolute', left: `${pos(f.intervalDays[0])}%`, width: `${Math.max(1, pos(f.intervalDays[1]) - pos(f.intervalDays[0]))}%`, top: 3, bottom: 3, background: 'var(--xp-warn)', opacity: 0.35, borderRadius: 6 }} />}
        <div style={{ position: 'absolute', left: `calc(${pos(f.daysToFull)}% - 2px)`, width: 4, top: 0, bottom: 0, background: 'var(--xp-crit)' }} />
      </div>
      <div className="xp-row xp-muted" style={{ justifyContent: 'space-between', fontSize: 11 }}><span>today</span><span>{horizonDays} days</span></div>
    </div>
  );
};
