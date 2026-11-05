import React, { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { useHostProcesses, useHostSummary } from '../../../hooks/xampulse/useXamPulseQueries';
import { Card, Drawer, DemoPill, ErrorState, SkeletonBlock, StatusPill } from '../ui/ui';
import { QueryBoundary } from '../ui/QueryBoundary';
import { ChartLegend, TimeSeriesChart, type ChartSeries } from '../charts/TimeSeriesChart';
import { Sparkline } from '../charts/Visuals';
import { formatAgo, formatBytes, formatClock, formatPct } from '../../../utils/xampulse/format';
import type { XpHostSummary, XpLane } from '../../../types/xampulse';

const pct = (v: number): string => `${v.toFixed(0)}%`;
const mbps = (v: number): string => `${v.toFixed(0)} MB/s`;

/** Picks series by key from the summary map, in order, with display names. */
export function pickSeries(map: XpHostSummary['series'], spec: [key: string, name: string][]): ChartSeries[] {
  return spec.filter(([k]) => map[k] && map[k].length > 0).map(([k, name]) => ({ key: k, name, points: map[k] }));
}

/** Per-core series use the key convention `system.cpu.utilization{cpu=N}`. */
export function coreSeries(map: XpHostSummary['series']): { core: string; points: [number, number][] }[] {
  const out: { core: string; points: [number, number][] }[] = [];
  Object.keys(map).forEach((k) => {
    const m = /^system\.cpu\.utilization\{cpu=(\d+)\}$/.exec(k);
    if (m) out.push({ core: m[1], points: map[k] });
  });
  return out.sort((a, b) => Number(a.core) - Number(b.core));
}

const HealthLanes: React.FC<{ lanes: XpLane[] }> = ({ lanes }) => (
  <div className="xp-lanes">
    {lanes.map((l) => (
      <div key={l.lane} className="xp-lane">
        <div className="xp-row"><strong>{l.lane.charAt(0) + l.lane.slice(1).toLowerCase()}</strong><span className="xp-spacer" /><StatusPill health={l.health} /></div>
        <div className="xp-muted" style={{ fontSize: 12.5, marginTop: 4 }}>{l.reason}</div>
      </div>
    ))}
  </div>
);

const Body: React.FC<{ s: XpHostSummary }> = ({ s }) => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  // Time scrubber: 0 = latest sample, otherwise a point inside the selected range.
  const [scrub, setScrub] = useState(100);
  const atMs = ctx.fromMs + ((ctx.toMs - ctx.fromMs) * scrub) / 100;
  const at = scrub >= 100 ? undefined : new Date(Math.round(atMs / 60_000) * 60_000).toISOString();
  const procs = useHostProcesses(s.host.accountId, s.host.instanceId, at);
  const brush = (a: number, b: number) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() });
  const markers = useMemo(() => s.events.map((e) => ({ t: Date.parse(e.ts), label: e.title })), [s.events]);
  const cpu = pickSeries(s.series, [['system.cpu.utilization', 'Total'], ['system.cpu.utilization{state=steal}', 'Steal'], ['system.cpu.utilization{state=iowait}', 'I/O wait']]);
  const mem = pickSeries(s.series, [['system.memory.utilization', 'Memory'], ['system.paging.utilization', 'Swap']]);
  const fs = Object.keys(s.series).filter((k) => k.startsWith('system.filesystem.utilization')).map((k) => ({ key: k, name: /mountpoint=([^}]+)/.exec(k)?.[1] ?? 'All mounts (max)', points: s.series[k] }));
  const io = pickSeries(s.series, [['system.disk.io{direction=read}', 'Read'], ['system.disk.io{direction=write}', 'Write']]);
  const net = pickSeries(s.series, [['system.network.io{direction=receive}', 'In'], ['system.network.io{direction=transmit}', 'Out']]);
  const cores = coreSeries(s.series);
  const chart = (title: string, series: ChartSeries[], fmt: (v: number) => string, max?: number) => (
    <Card title={title}>
      {series.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No data for this range.</p> : (
        <>
          <TimeSeriesChart series={series} height={150} format={fmt} yMax={max} markers={markers} onBrush={brush} ariaLabel={title} fromMs={ctx.fromMs} toMs={ctx.toMs} />
          <ChartLegend items={series.map((x) => ({ name: x.name }))} />
        </>
      )}
    </Card>
  );
  return (
    <>
      <HealthLanes lanes={s.lanes} />
      {chart('CPU', cpu, pct, 100)}
      <Card title="CPU per core" id="xp-cores">
        {cores.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>Per-core data is not available for this host.</p> : (
          <div className="xp-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))' }}>
            {cores.map((c) => {
              const last = c.points[c.points.length - 1]?.[1] ?? 0;
              return (
                <div key={c.core} className="xp-lane">
                  <div className="xp-row"><span className="xp-muted">Core {c.core}</span><span className="xp-spacer" /><strong>{pct(last)}</strong></div>
                  <Sparkline values={c.points.map((p) => p[1])} width={130} label={`Core ${c.core} CPU`} />
                </div>
              );
            })}
          </div>
        )}
      </Card>
      {chart('Memory and swap', mem, pct, 100)}
      {chart('Filesystems', fs, pct, 100)}
      {chart('Disk I/O', io, mbps)}
      {chart('Network', net, mbps)}
      <Card title="Top processes" id="xp-procs">
        <div className="xp-row" style={{ marginBottom: 8 }}>
          <label htmlFor="xp-scrub" className="xp-muted">Time</label>
          <input id="xp-scrub" type="range" min={0} max={100} step={1} value={scrub} onChange={(e) => setScrub(Number(e.target.value))} style={{ flex: 1, minWidth: 160 }}
            aria-valuetext={at ? `Processes at ${formatClock(at)}` : 'Latest sample'} />
          <span className="xp-muted" style={{ fontSize: 12 }}>{at ? formatClock(at) : 'Latest'}</span>
        </div>
        <QueryBoundary query={procs} skeleton={<SkeletonBlock height={120} />}>
          {(rows) => rows.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>Process data needs collection level T2. Change it in Setup.</p> : (
            <div className="xp-table-wrap"><table className="xp-table">
              <caption className="xp-sr">Top processes by CPU</caption>
              <thead><tr><th scope="col">Process</th><th scope="col">User</th><th scope="col" className="xp-num">CPU</th><th scope="col" className="xp-num">Memory</th><th scope="col" className="xp-num">RSS</th></tr></thead>
              <tbody>{rows.slice(0, 10).map((p) => (
                <tr key={p.pid}><td>{p.name} <span className="xp-muted">#{p.pid}</span></td><td>{p.user ?? '\u2014'}</td><td className="xp-num">{formatPct(p.cpuPct, 1)}</td><td className="xp-num">{formatPct(p.memPct, 1)}</td><td className="xp-num">{formatBytes(p.memBytes)}</td></tr>
              ))}</tbody>
            </table></div>
          )}
        </QueryBoundary>
      </Card>
      <Card title="Recent errors" id="xp-herr" actions={<Link className="xp-btn" to={ctx.link('/xampulse/logs', { q: `instance:${s.host.instanceId} severity:>=warn` })}>Open in Logs</Link>}>
        {s.recentErrors.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No warnings or errors in this range.</p> : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }} className="xp-mono">
            {s.recentErrors.map((r) => (
              <li key={r.id} style={{ padding: '4px 0', fontSize: 12.5 }}>
                <span className="xp-muted">{formatClock(r.ts)}</span> <span className={`xp-sev--${r.severity}`}>{r.severity}</span> {r.body}
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Events" id="xp-hev">
        {s.events.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No events in this range.</p> : (
          <ul className="xp-timeline">{s.events.map((e) => <li key={`${e.ts}-${e.title}`}><strong>{e.title}</strong><div className="xp-muted" style={{ fontSize: 12 }}>{formatAgo(e.ts)}{e.detail ? ` · ${e.detail}` : ''}</div></li>)}</ul>
        )}
      </Card>
      {s.openIncidents.length > 0 && (
        <Card title="Open incidents" id="xp-hinc">
          {s.openIncidents.map((i) => <div key={i.id} className="xp-banner xp-banner--crit">{i.title}<div className="xp-muted">{i.summary}</div></div>)}
          <div style={{ marginTop: 8 }}><button type="button" className="xp-btn" onClick={() => navigate(ctx.link('/xampulse/alerts'))}>View incidents</button></div>
        </Card>
      )}
      {s.rightsizing && (
        <Card title="Rightsizing hint" id="xp-hrs"><p style={{ margin: 0 }}>{s.rightsizing.note}</p><div style={{ marginTop: 8 }}><Link className="xp-btn" to={s.rightsizing.link}>Open rightsizing</Link></div></Card>
      )}
    </>
  );
};

/** Resource 360: everything about one host in one right-hand panel. Addressable at /xampulse/hosts/:instanceId. */
export const Resource360Drawer: React.FC<{ accountId: number; instanceId: string; onClose: () => void }> = ({ accountId, instanceId, onClose }) => {
  const ctx = useViewContext();
  const q = useHostSummary(accountId, instanceId, ctx.from, ctx.to);
  const head = q.data ? (
    <div>
      <div className="xp-row"><h2 style={{ margin: 0, fontSize: 18 }}>{q.data.host.name}</h2><StatusPill health={q.data.host.health} />{q.data.host.instanceId.startsWith('i-demo') && <DemoPill />}</div>
      <div className="xp-muted xp-mono" style={{ fontSize: 12 }}>{q.data.host.instanceId} · {q.data.host.instanceType ?? 'unknown type'} · {q.data.host.region} · last seen {formatAgo(q.data.host.lastSeenAt)}</div>
    </div>
  ) : <h2 style={{ margin: 0, fontSize: 18 }}>{instanceId}</h2>;
  return (
    <Drawer title={head} onClose={onClose} label={`Resource 360 for ${instanceId}`}>
      {q.isLoading && <><SkeletonBlock height={90} /><SkeletonBlock height={190} /><SkeletonBlock height={190} /></>}
      {q.error && <ErrorState error={q.error} onRetry={() => { void q.refetch(); }} />}
      {q.data && <Body s={q.data} />}
    </Drawer>
  );
};
