/**
 * DemoGrafanaDashboard
 *
 * A self-contained, Grafana-styled infrastructure dashboard shown on the Grafana page in demo
 * mode, in place of the live `<iframe>`.
 *
 * Why this exists: the demo account points `grafanaIp` at `play.grafana.org`, and that site —
 * like every real Grafana — serves `X-Frame-Options: deny` / a `frame-ancestors` CSP, so a
 * browser refuses to embed it. The result was the "PLAY.GRAFANA.ORG IS BLOCKED /
 * ERR_BLOCKED_BY_RESPONSE" screen. No public Grafana can be iframed, so there is nothing to fix
 * on the embed itself; the honest demo is a representative dashboard we render ourselves. The
 * "View in Grafana" link in the topbar still opens play.grafana.org in a new tab, where framing
 * rules do not apply.
 *
 * Everything here is static and deterministic (no live data, no network) — it only needs to look
 * like the monitoring dashboard a connected account would show.
 */

import React, { useMemo } from 'react';

const PANEL = 'bg-[#181b1f] border border-[#2c3235] rounded';
const AXIS = '#9aa0a6';
const GRID = '#2c3235';

// ── Deterministic series, so every render and every screenshot is identical ──────────────────
const series = (n: number, base: number, amp: number, phase: number, noise: number) =>
  Array.from({ length: n }, (_, i) => {
    const wave = base + amp * Math.sin((i / n) * Math.PI * 2 + phase);
    const jitter = noise * Math.sin(i * 1.7 + phase * 3);
    return Math.max(0, +(wave + jitter).toFixed(2));
  });

const Stat: React.FC<{ label: string; value: string; sub: string; tone: string }> = ({
  label, value, sub, tone,
}) => (
  <div className={`${PANEL} p-4 flex flex-col justify-between`}>
    <p className="text-[11px] uppercase tracking-wide text-[#9aa0a6]">{label}</p>
    <p className="text-[30px] font-semibold leading-none mt-2" style={{ color: tone }}>{value}</p>
    <p className="text-[11px] text-[#6e7681] mt-2">{sub}</p>
  </div>
);

/** A filled area/line chart drawn as an inline SVG — no chart library, no data fetch. */
const TimeChart: React.FC<{
  title: string; unit: string; color: string; data: number[]; max?: number; height?: number;
}> = ({ title, unit, color, data, max, height = 150 }) => {
  const W = 560;
  const H = height;
  const pad = { l: 34, r: 8, t: 10, b: 18 };
  const top = max ?? Math.max(...data) * 1.15;
  const innerW = W - pad.l - pad.r;
  const innerH = H - pad.t - pad.b;
  const x = (i: number) => pad.l + (i / (data.length - 1)) * innerW;
  const y = (v: number) => pad.t + innerH - (v / top) * innerH;

  const line = data.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(data.length - 1).toFixed(1)},${(pad.t + innerH).toFixed(1)} L${x(0).toFixed(1)},${(pad.t + innerH).toFixed(1)} Z`;
  const gradId = `g-${title.replace(/\W+/g, '')}`;
  const ticks = [0, 0.5, 1].map(f => +(top * f).toFixed(top >= 100 ? 0 : 1));
  const last = data[data.length - 1];

  return (
    <div className={PANEL}>
      <div className="flex items-center justify-between px-3 py-2 border-b border-[#2c3235]">
        <span className="text-[12px] text-[#c7d0d9] font-medium">{title}</span>
        <span className="text-[12px] font-semibold" style={{ color }}>{last}{unit}</span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" preserveAspectRatio="none" style={{ display: 'block' }}>
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity="0.35" />
            <stop offset="100%" stopColor={color} stopOpacity="0" />
          </linearGradient>
        </defs>
        {ticks.map((t, i) => {
          const gy = y(t);
          return (
            <g key={i}>
              <line x1={pad.l} y1={gy} x2={W - pad.r} y2={gy} stroke={GRID} strokeWidth="1" />
              <text x={pad.l - 6} y={gy + 3} textAnchor="end" fontSize="9" fill={AXIS}>{t}{unit}</text>
            </g>
          );
        })}
        <path d={area} fill={`url(#${gradId})`} />
        <path d={line} fill="none" stroke={color} strokeWidth="1.75" />
      </svg>
    </div>
  );
};

const DemoGrafanaDashboard: React.FC<{ accountName: string }> = ({ accountName }) => {
  const cpu = useMemo(() => series(48, 46, 18, 0.4, 4), []);
  const mem = useMemo(() => series(48, 63, 9, 1.2, 3), []);
  const netIn = useMemo(() => series(48, 120, 70, 0.2, 18), []);
  const netOut = useMemo(() => series(48, 84, 52, 2.1, 14), []);
  const reqRate = useMemo(() => series(48, 2400, 900, 0.8, 180), []);
  const p95 = useMemo(() => series(48, 180, 90, 2.6, 22), []);

  return (
    <div className="w-full h-[calc(100vh-6rem)] rounded-xl shadow-sm overflow-hidden bg-[#111217]">
      {/* Grafana-style top bar */}
      <div className="h-11 flex items-center justify-between px-4 border-b border-[#2c3235] bg-[#111217]">
        <div className="flex items-center gap-2 min-w-0">
          <span className="w-2 h-2 rounded-full bg-[#73bf69] flex-shrink-0" />
          <span className="text-[13px] font-semibold text-[#d8dde3] truncate">
            Infrastructure Overview · {accountName}
          </span>
          <span className="ml-2 text-[10px] uppercase tracking-wide text-[#8e959c] border border-[#2c3235] rounded px-1.5 py-0.5">
            Demo
          </span>
        </div>
        <div className="flex items-center gap-3 text-[11px] text-[#8e959c]">
          <span>Last 24 hours</span>
          <span className="text-[#6e7681]">·</span>
          <span>Refresh 10s</span>
        </div>
      </div>

      <div className="p-3 space-y-3 overflow-y-auto h-[calc(100%-2.75rem)]">
        {/* Stat row */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <Stat label="Nodes Up"      value="18 / 18" sub="All healthy"        tone="#73bf69" />
          <Stat label="CPU (avg)"     value="46%"     sub="peak 71% @ 14:20"   tone="#ffca3a" />
          <Stat label="Memory (avg)"  value="63%"     sub="peak 74% @ 09:40"   tone="#ff9830" />
          <Stat label="Error Rate"    value="0.14%"   sub="within SLO"         tone="#73bf69" />
        </div>

        {/* Chart grid */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          <TimeChart title="CPU Utilization"        unit="%"   color="#ffca3a" data={cpu} max={100} />
          <TimeChart title="Memory Utilization"     unit="%"   color="#ff9830" data={mem} max={100} />
          <TimeChart title="Network In"             unit=" MB" color="#5794f2" data={netIn} />
          <TimeChart title="Network Out"            unit=" MB" color="#b877d9" data={netOut} />
          <TimeChart title="Request Rate"           unit="/s"  color="#73bf69" data={reqRate} />
          <TimeChart title="p95 Latency"            unit=" ms" color="#f2495c" data={p95} />
        </div>

        <p className="text-[11px] text-[#6e7681] px-1 pb-2">
          Sample monitoring data. Connect a Grafana instance in Setup to embed your own dashboards,
          or use “View in Grafana” to open the live play.grafana.org board in a new tab.
        </p>
      </div>
    </div>
  );
};

export default DemoGrafanaDashboard;
