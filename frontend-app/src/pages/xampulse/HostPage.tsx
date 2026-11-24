import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Search } from 'lucide-react';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useOptionalTopbarDispatch } from '../../contexts/TopbarContext';
import { useHostChanges, useHostLimits, useHostProcesses, useHostSummary, useInvestigation, useXpAccounts } from '../../hooks/xampulse/useXamPulseQueries';
import { InvestigatePanel } from '../../components/XamPulse/hosts/InvestigatePanel';
import { ExposureAndCost } from '../../components/XamPulse/hosts/ExposureCost';
import { RunbooksCard } from '../../components/XamPulse/hosts/RunbooksCard';
import { Card, DemoPill, EmptyState, ErrorState, SkeletonBlock, StatusPill, VerdictCard } from '../../components/XamPulse/ui/ui';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { AwsPanel } from '../../components/XamPulse/hosts/AwsPanel';
import { ThrottleRadar } from '../../components/XamPulse/hosts/ThrottleRadar';
import { ChangeList, changeMarkers } from '../../components/XamPulse/hosts/ChangeList';
import { SERIES_COLORS, TimeSeriesChart, type ChartSeries } from '../../components/XamPulse/charts/TimeSeriesChart';
import { ChartControls } from '../../components/XamPulse/charts/ChartControls';
import { usePrefs } from '../../hooks/xampulse/prefs';
import { compareLabel, compareOffsetSec } from '../../utils/xampulse/compare';
import { Sparkline } from '../../components/XamPulse/charts/Visuals';
import { formatAgo, formatBytes, formatClock, formatPct, formatRate } from '../../utils/xampulse/format';
import { cpuVerdict } from '../../utils/xampulse/cpuVerdict';
import { xamPulseContainerPath } from '../../constants/xampulseTabs';
import type { XpChange, XpHostSummary, XpLane } from '../../types/xampulse';

const pct = (v: number): string => `${v.toFixed(v < 10 ? 1 : 0)}%`;
/** Disk and network series are bytes per second (whole disks and real NICs only). */
const bytesPerSec = (v: number): string => formatRate(v);
const perSec = (v: number): string => `${v < 10 ? v.toFixed(2) : Math.round(v).toLocaleString()}/s`;

/** Picks series by key from the summary map, in order, with display names. Missing or empty keys are skipped. */
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

export const SECTIONS = [
  { id: 'overview', label: 'Overview' }, { id: 'investigate', label: 'Investigate' }, { id: 'changes', label: 'Changes' }, { id: 'cpu', label: 'CPU' }, { id: 'memory', label: 'Memory' },
  { id: 'storage', label: 'Storage' }, { id: 'network', label: 'Network' }, { id: 'processes', label: 'Processes' },
  { id: 'activity', label: 'Logs & events' }, { id: 'aws', label: 'AWS' },
] as const;
type SectionId = (typeof SECTIONS)[number]['id'];
const isSection = (s: string): s is SectionId => SECTIONS.some((x) => x.id === s);

const PSI_MISSING = 'Pressure (PSI) is not reported by this host: the kernel has it disabled or the agent predates this check.';
const ENA_MISSING = 'AWS network allowance counters are not reported by this host: it is not on an ENA (Nitro) interface, the ENA driver is too old, or the agent predates this check.';

const laneSection = (lane: XpLane['lane']): SectionId => lane === 'ERRORS' || lane === 'FRESHNESS' ? 'activity' : lane === 'DISK' ? 'storage' : lane.toLowerCase() as SectionId;

const HealthLanes: React.FC<{ lanes: XpLane[]; onSelect: (id: SectionId) => void }> = ({ lanes, onSelect }) => (
  <div className="xp-lanes">
    {lanes.slice().sort((a, b) => ({ CRIT: 0, WARN: 1, NO_DATA: 2, OK: 3 }[a.health] - { CRIT: 0, WARN: 1, NO_DATA: 2, OK: 3 }[b.health])).map((l) => (
      <button type="button" key={l.lane} className="xp-lane xp-lane--button" onClick={() => onSelect(laneSection(l.lane))}>
        <span className="xp-row"><span className="xp-eyebrow">{l.lane.charAt(0) + l.lane.slice(1).toLowerCase()}</span><span className="xp-spacer" /><StatusPill health={l.health} /></span>
        <span className="xp-muted" style={{ fontSize: 12, marginTop: 8, textAlign: 'left' }}>{l.reason}</span>
      </button>
    ))}
  </div>
);

/** Change markers shown on every chart of the page (deploys, restarts, reboots, package changes...). */
const MarkersContext = createContext<{ t: number; label: string }[]>([]);

/** The learned normal range and the "compare with" series for the page's main charts. */
export interface ChartExtras { bands?: XpHostSummary['bands']; compare?: XpHostSummary['compare']; showBands: boolean; compareLabel: string | null }
const ExtrasContext = createContext<ChartExtras>({ showBands: false, compareLabel: null });

/**
 * Adds the normal range (shaded behind the line) and the comparison (dashed, same colour, "CPU, last week") to every
 * series whose key has them. `scale` converts both the way the series was converted (load divided per vCPU).
 */
export function withExtras(series: ChartSeries[], x: ChartExtras, scale?: (v: number) => number): ChartSeries[] {
  const out: ChartSeries[] = [];
  series.forEach((s, i) => {
    const color = s.color ?? SERIES_COLORS[i % SERIES_COLORS.length];
    const band = x.showBands ? x.bands?.[s.key] : undefined;
    out.push({ ...s, color, ...(band?.length ? { band: scale ? band.map(([t, l, h]): [number, number, number] => [t, scale(l), scale(h)]) : band } : {}) });
    const cmp = x.compareLabel ? x.compare?.[s.key] : undefined;
    if (cmp?.length) {
      out.push({ key: `${s.key}~compare`, name: `${s.name}, ${x.compareLabel}`, dashed: true, color,
        points: scale ? cmp.map(([t, v]): [number, number] => [t, scale(v)]) : cmp });
    }
  });
  return out;
}

const containerPath = xamPulseContainerPath;

/** One chart card. `missing` explains an absent signal; otherwise an empty range says "No data for this range". */
/** CPU time by state, in the order they stack (bottom up) and with fixed meanings. Steal is AWS taking cycles. */
const CPU_STATES: [key: string, name: string, color: string][] = [
  ['system.cpu.utilization{state=user}', 'User (application)', 'var(--xp-accent)'],
  ['system.cpu.utilization{state=system}', 'System (kernel)', 'var(--xp-info)'],
  ['system.cpu.utilization{state=iowait}', 'I/O wait (disk)', 'var(--xp-warn)'],
  ['system.cpu.utilization{state=steal}', 'Steal (AWS)', 'var(--xp-violet)'],
  ['system.cpu.utilization{state=softirq}', 'Soft interrupts', 'var(--xp-nodata)'],
  ['system.cpu.utilization{state=interrupt}', 'Interrupts', 'var(--xp-nodata)'],
  ['system.cpu.utilization{state=nice}', 'Nice (low priority)', 'var(--xp-ok)'],
];


/**
 * CPU, answer first: the verdict (is CPU the problem, why, what next), then where CPU time goes, load per vCPU,
 * the run queue, pressure and process creation. Every chart names the question it answers.
 */
const CpuSection: React.FC<{ m: XpHostSummary['series']; psiCpu: ChartSeries[]; cores: { core: string; points: [number, number][] }[] }> = ({ m, psiCpu, cores }) => {
  const v = cpuVerdict(m);
  const byState = CPU_STATES.filter(([k]) => (m[k]?.length ?? 0) > 0).map(([key, name, color]) => ({ key, name, color, points: m[key] }));
  const vcpuPts = m['system.cpu.logical.count'] ?? [];
  const vcpus = vcpuPts.length ? vcpuPts[vcpuPts.length - 1][1] : null;
  const perVcpu = (key: string, name: string): ChartSeries | null => (m[key]?.length && vcpus ? { key, name, points: m[key].map(([t, x]) => [t, x == null ? null : x / vcpus]) } : null);
  const load = [perVcpu('system.cpu.load_average.1m', '1 min'), perVcpu('system.cpu.load_average.5m', '5 min'), perVcpu('system.cpu.load_average.15m', '15 min')].filter((x): x is ChartSeries => x !== null);
  const rawLoad = pickSeries(m, [['system.cpu.load_average.1m', '1 min'], ['system.cpu.load_average.5m', '5 min'], ['system.cpu.load_average.15m', '15 min']]);
  const runQueue = pickSeries(m, [['system.processes.count{status=running}', 'Running or ready'], ['system.processes.count{status=blocked}', 'Blocked on disk']]);
  const created = pickSeries(m, [['system.processes.created', 'New processes']]);
  return (
    <>
      <VerdictCard v={v} label="CPU verdict" />
      <div className="xp-grid xp-grid--charts">
        <ChartCard title="Where CPU time goes" series={byState} format={pct} max={100} stacked
          hint="Stacked by state: the top edge is total busy CPU. User = your application, system = kernel, I/O wait = waiting on disk, steal = AWS taking cycles." />
        {load.length
          ? <ChartCard title="Load per vCPU" series={load} format={(x) => x.toFixed(2)} refLines={[{ value: 1, label: '1.0 = all vCPUs busy' }]}
              extras scale={(x) => x / (vcpus as number)}
              hint={`Runnable tasks per vCPU (${vcpus} vCPU${vcpus === 1 ? '' : 's'}). Above 1.0, work is queuing for a CPU.`} />
          : <ChartCard title="Load average" series={rawLoad} format={(x) => x.toFixed(2)} extras hint="The vCPU count is not reported, so load is not divided per vCPU." />}
        <ChartCard title="Run queue" series={runQueue} format={(x) => x.toFixed(0)}
          hint="Processes running or ready to run, and those blocked waiting on disk. Many blocked = storage is the bottleneck." />
        <ChartCard title="CPU pressure" series={psiCpu} format={pct} max={100} missing={PSI_MISSING}
          hint="Share of time runnable tasks waited for a CPU (Linux PSI): measured, not estimated from utilisation." />
      </div>
      <div className="xp-grid xp-grid--charts">
        <ChartCard title="New processes per second" series={created} format={(x) => `${x.toFixed(x < 10 ? 1 : 0)}/s`}
          hint="Spikes usually mean cron jobs, scripts, or a service that keeps crashing and restarting." />
        <Card title="CPU per core" id="xp-cores">
          {cores.length === 0
            ? <p className="xp-muted" style={{ margin: 0 }}>This agent sends CPU totals only. Per-core CPU (one stuck thread shows as one core at 100%) comes with the next agent update.</p>
            : (
              <div className="xp-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))' }}>
                {cores.map((c) => {
                  const last = c.points[c.points.length - 1]?.[1] ?? 0;
                  return (
                    <div key={c.core} className="xp-lane">
                      <div className="xp-row"><span className="xp-muted xp-mono" style={{ fontSize: 11 }}>core {c.core}</span><span className="xp-spacer" /><span className="xp-mono" style={{ fontSize: 12 }}>{pct(last)}</span></div>
                      <Sparkline values={c.points.map((p) => p[1])} width={130} label={`Core ${c.core} CPU`} />
                    </div>
                  );
                })}
              </div>
            )}
        </Card>
      </div>
    </>
  );
};

const ChartCard: React.FC<{ title: string; series: ChartSeries[]; format: (v: number) => string; max?: number; missing?: string; hint?: string; stacked?: boolean; refLines?: { value: number; label: string }[];
  /** Draw the normal range and the comparison for series that have them; `scale` converts them like the series. */
  extras?: boolean; scale?: (v: number) => number }> = ({ title, series: raw, format, max, missing, hint, stacked, refLines, extras, scale }) => {
  const ctx = useViewContext();
  const markers = useContext(MarkersContext);
  const x = useContext(ExtrasContext);
  const series = extras ? withExtras(raw, x, scale) : raw;
  const banded = series.some((s) => s.band?.length);
  const brush = (a: number, b: number) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() });
  return (
    <Card title={title}>
      {(hint || banded) && <p className="xp-muted" style={{ margin: '0 0 8px', fontSize: 12 }}>{hint}{hint && banded ? ' ' : ''}{banded ? 'Shaded: the range this host usually stays in at this hour of the week.' : ''}</p>}
      {series.length === 0
        ? <p className="xp-muted" style={{ margin: 0 }}>{missing ?? 'No data for this range.'}</p>
        : <TimeSeriesChart showLegend series={series} height={180} format={format} yMax={max} markers={markers} onBrush={brush} ariaLabel={title} fromMs={ctx.fromMs} toMs={ctx.toMs}
            yScale={format === bytesPerSec ? 'binary' : 'decimal'} stacked={stacked} refLines={refLines} />}
    </Card>
  );
};

const Processes: React.FC<{ accountId: number; instanceId: string }> = ({ accountId, instanceId }) => {
  const ctx = useViewContext();
  const [scrub, setScrub] = useState(100);
  const atMs = ctx.fromMs + ((ctx.toMs - ctx.fromMs) * scrub) / 100;
  const at = scrub >= 100 ? undefined : new Date(Math.round(atMs / 60_000) * 60_000).toISOString();
  const procs = useHostProcesses(accountId, instanceId, at);
  return (
    <Card title="Top processes" id="xp-procs">
      <div className="xp-row" style={{ marginBottom: 8 }}>
        <label htmlFor="xp-scrub" className="xp-muted">Time</label>
        <input id="xp-scrub" type="range" min={0} max={100} step={1} value={scrub} onChange={(e) => setScrub(Number(e.target.value))} style={{ flex: 1, minWidth: 160 }}
          aria-valuetext={at ? `Processes at ${formatClock(at)}` : 'Latest sample'} />
        <span className="xp-muted" style={{ fontSize: 12 }}>{at ? formatClock(at) : 'Latest'}</span>
      </div>
      <QueryBoundary query={procs} skeleton={<SkeletonBlock height={120} />}>
        {(rows) => rows.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No process used more than about 1% of CPU or memory at this time (agent 1.2.0 and later report the significant processes on T1; T2 reports every process). Older agents report none until updated.</p> : (
          <div className="xp-table-wrap"><table className="xp-table">
            <caption className="xp-sr">Top processes by CPU</caption>
            <thead><tr><th scope="col">Process</th><th scope="col">User</th><th scope="col" className="xp-num" title="Share of the whole host's CPU">CPU (of host)</th><th scope="col" className="xp-num">Memory</th><th scope="col" className="xp-num">RSS</th>{rows.some((p) => p.threads != null) && <th scope="col" className="xp-num">Threads</th>}</tr></thead>
            <tbody>{rows.slice(0, 15).map((p) => (
              <tr key={p.pid}><td>{p.name} <span className="xp-muted">#{p.pid}</span></td><td>{p.user ?? '—'}</td><td className="xp-num">{formatPct(p.cpuPct, 1)}</td><td className="xp-num">{formatPct(p.memPct, 1)}</td><td className="xp-num">{formatBytes(p.memBytes)}</td>{rows.some((row) => row.threads != null) && <td className="xp-num">{p.threads ?? '—'}</td>}</tr>
            ))}</tbody>
          </table></div>
        )}
      </QueryBoundary>
    </Card>
  );
};

const Activity: React.FC<{ s: XpHostSummary }> = ({ s }) => {
  const ctx = useViewContext();
  return (
    <div className="xp-grid xp-grid--2">
      <Card title="Recent errors" id="xp-herr" actions={<Link className="xp-btn" to={ctx.link('/xampulse/logs', { q: `instance:${s.host.instanceId} severity:>=warn` })}>Open in Logs</Link>}>
        {s.recentErrors.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No warnings or errors in this range.</p> : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }} className="xp-mono">
            {s.recentErrors.map((r) => (
              <li key={r.id} style={{ padding: '4px 0', fontSize: 12.5 }}><span className="xp-muted">{formatClock(r.ts)}</span> <span className={`xp-sev--${r.severity}`}>{r.severity}</span> {r.body}</li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Events" id="xp-hev">
        {s.events.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No events in this range.</p> : (
          <ul className="xp-timeline">{s.events.map((e) => <li key={`${e.ts}-${e.title}`}><span className="xp-timeline__ic" aria-hidden="true"><span className="xp-dot" /></span><span>{e.title}</span><span className="xp-timeline__meta">{formatAgo(e.ts)}{e.detail ? ` · ${e.detail}` : ''}</span></li>)}</ul>
        )}
      </Card>
    </div>
  );
};

/** Investigate runs when its section opens (it reads several sources); results are kept a minute per range. */
const Investigate: React.FC<{ accountId: number; instanceId: string; onSelect: (id: SectionId) => void }> = ({ accountId, instanceId, onSelect }) => {
  const ctx = useViewContext();
  const q = useInvestigation(accountId, instanceId, ctx.from, ctx.to, true);
  const toSection = (sec: string) => {
    if (sec === 'peers') {
      document.getElementById('xp-peers')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    if (isSection(sec)) onSelect(sec);
  };
  return (
    <QueryBoundary query={q} skeleton={<><SkeletonBlock height={60} label="Checking limits, changes, saturation, errors and peers" /><SkeletonBlock height={220} /></>}>
      {(inv) => <InvestigatePanel inv={inv} onSection={toSection} logsLink={(lq) => ctx.link('/xampulse/logs', { q: lq })} />}
    </QueryBoundary>
  );
};

/** Says once, on the overview, why there is no shaded range yet (instead of silently drawing none). */
const NormalRangeNote: React.FC<{ s: XpHostSummary }> = ({ s }) => {
  const x = useContext(ExtrasContext);
  if (!x.showBands || Object.keys(s.bands ?? {}).length > 0) return null;
  return <p className="xp-hint" style={{ margin: 0 }}>No normal range yet. XamPulse learns it from this host's own history: the daily rhythm after three days of data, the weekly one after three weeks.</p>;
};

const Section: React.FC<{ id: SectionId; s: XpHostSummary; accountId: number; changes?: XpChange[]; onSelect: (id: SectionId) => void }> = ({ id, s, accountId, changes, onSelect }) => {
  const ctx = useViewContext();
  const m = s.series;
  const cpu = pickSeries(m, [['system.cpu.utilization', 'Total'], ['system.cpu.utilization{state=steal}', 'Steal'], ['system.cpu.utilization{state=iowait}', 'I/O wait']]);
  const mem = pickSeries(m, [['system.memory.utilization', 'Memory'], ['system.paging.utilization', 'Swap']]);
  const fs = Object.keys(m).filter((k) => k.startsWith('system.filesystem.utilization') && m[k].length > 0)
    .map((k) => ({ key: k, name: /mountpoint=([^}]+)/.exec(k)?.[1] ?? 'All mounts (max)', points: m[k] }));
  const io = pickSeries(m, [['system.disk.io{direction=read}', 'Read'], ['system.disk.io{direction=write}', 'Write']]);
  const net = pickSeries(m, [['system.network.io{direction=receive}', 'In'], ['system.network.io{direction=transmit}', 'Out']]);
  const psiAll = pickSeries(m, [['xp.pressure{resource=cpu,level=some}', 'CPU'], ['xp.pressure{resource=memory,level=some}', 'Memory'], ['xp.pressure{resource=io,level=some}', 'I/O']]);
  const psiCpu = pickSeries(m, [['xp.pressure{resource=cpu,level=some}', 'Some tasks waiting']]);
  const psiMem = pickSeries(m, [['xp.pressure{resource=memory,level=some}', 'Some tasks'], ['xp.pressure{resource=memory,level=full}', 'All tasks']]);
  const psiIo = pickSeries(m, [['xp.pressure{resource=io,level=some}', 'Some tasks'], ['xp.pressure{resource=io,level=full}', 'All tasks']]);
  const ena = Object.keys(m).filter((k) => k.startsWith('xp.ena.allowance_exceeded{') && m[k].length > 0)
    .map((k) => ({ key: k, name: { pps: 'Packets/s', bw_in: 'Bandwidth in', bw_out: 'Bandwidth out', conntrack: 'Connections', linklocal: 'Link-local' }[/allowance=([^}]+)/.exec(k)?.[1] ?? ''] ?? k, points: m[k] }));
  const cores = coreSeries(m);
  const psiHint = 'Share of time tasks were stalled waiting (Linux PSI): measured, not estimated from utilisation.';

  switch (id) {
    case 'overview':
      return (
        <>
          <HealthLanes lanes={s.lanes} onSelect={onSelect} />
          <NormalRangeNote s={s} />
          <div className="xp-grid xp-grid--charts">
            <ChartCard title="CPU" series={cpu} format={pct} max={100} extras />
            <ChartCard title="Memory and swap" series={mem} format={pct} max={100} extras />
            <ChartCard title="Pressure" series={psiAll} format={pct} max={100} missing={PSI_MISSING} hint={psiHint} />
            <ChartCard title="Network" series={net} format={bytesPerSec} extras />
          </div>
          <Card title="What changed" id="xp-hchg" actions={<button type="button" className="xp-btn" onClick={() => onSelect('changes')}>All changes</button>}>
            {changes ? <ChangeList changes={changes} limit={3} containerLink={(n) => containerPath(s.host.instanceId, n, accountId)} /> : <SkeletonBlock height={80} />}
          </Card>
          {s.openIncidents.length > 0 && (
            <Card title="Open incidents" id="xp-hinc">{s.openIncidents.map((i) => <div key={i.id} className="xp-banner xp-banner--crit"><strong>{i.title}</strong><div className="xp-muted">{i.state} · {i.severity} · {i.summary}</div><Link className="xp-btn xp-btn--sm" to={ctx.link(`/xampulse/alerts?view=incidents&incident=${i.id}`)}>Open incident</Link></div>)}</Card>
          )}
        </>
      );
    case 'investigate':
      return <Investigate accountId={accountId} instanceId={s.host.instanceId} onSelect={onSelect} />;
    case 'changes':
      return (
        <Card title="Changes" id="xp-changes">
          <p className="xp-muted" style={{ margin: '0 0 8px', fontSize: 12.5 }}>
            Deploys, container restarts, reboots, package updates and recorded events, each compared with the 30 minutes before and after.
            What followed a change is shown; whether it caused it is not claimed.
          </p>
          {changes ? <ChangeList changes={changes} containerLink={(n) => containerPath(s.host.instanceId, n, accountId)} /> : <SkeletonBlock height={160} label="Loading changes" />}
        </Card>
      );
    case 'cpu':
      return <CpuSection m={m} psiCpu={psiCpu} cores={cores} />;
    case 'memory':
      return (
        <div className="xp-grid xp-grid--2">
          <ChartCard title="Memory and swap" series={mem} format={pct} max={100} extras />
          <ChartCard title="Memory pressure" series={psiMem} format={pct} max={100} missing={PSI_MISSING}
            hint="Some: at least one task waited for memory. All: every task did (reclaim or swap thrashing)." />
        </div>
      );
    case 'storage':
      return (
        <>
          <div className="xp-grid xp-grid--2">
            <ChartCard title="Filesystems" series={fs} format={pct} max={100} />
            <ChartCard title="Disk I/O" series={io} format={bytesPerSec} hint="Whole disks only; partitions are not counted twice." />
            <ChartCard title="I/O pressure" series={psiIo} format={pct} max={100} missing={PSI_MISSING}
              hint="All tasks stalled on I/O is the clearest sign that storage is the bottleneck." />
          </div>
          <p className="xp-muted" style={{ fontSize: 12.5 }}>EBS volume and instance limits (IOPS, throughput, burst balance, exceeded checks) are in the AWS section.</p>
        </>
      );
    case 'network':
      return (
        <div className="xp-grid xp-grid--2">
          <ChartCard title="Network" series={net} format={bytesPerSec} extras hint="The instance's own interfaces only (no loopback, bridges or container veths)." />
          <ChartCard title="AWS network allowances exceeded" series={ena} format={perSec} missing={ENA_MISSING}
            hint="Packets per second that EC2 queued or dropped because the instance went over an allowance. Any value above zero is a limit." />
        </div>
      );
    case 'processes':
      return (
        <>
          <Processes accountId={accountId} instanceId={s.host.instanceId} />
          <RunbooksCard accountId={accountId} instanceId={s.host.instanceId} />
        </>
      );
    case 'activity':
      return <Activity s={s} />;
    case 'aws':
      return (
        <>
          <ExposureAndCost accountId={accountId} instanceId={s.host.instanceId} />
          <AwsPanel accountId={accountId} instanceId={s.host.instanceId} />
        </>
      );
    default:
      return null;
  }
};

/**
 * Host 360: everything about one EC2 instance on one page, at /xampulse/hosts/:instanceId. Throttle Radar sits on top
 * of every section; the open section lives in the URL hash (#cpu), so a link opens the same view.
 */
const HostPage: React.FC = () => {
  const { instanceId = '' } = useParams<{ instanceId: string }>();
  const ctx = useViewContext();
  const navigate = useNavigate();
  const location = useLocation();
  const accounts = useXpAccounts();
  const requestedAccount = Number(new URLSearchParams(location.search).get('accountId'));
  const accountId = useMemo(() => {
    const allowed = accounts.data ?? [];
    if (Number.isInteger(requestedAccount) && requestedAccount > 0) return allowed.some((account) => account.cloudAccountId === requestedAccount) ? requestedAccount : undefined;
    return ctx.effectiveAccountIds.length === 1 ? ctx.effectiveAccountIds[0] : undefined;
  }, [accounts.data, requestedAccount, ctx.effectiveAccountIds]);
  const prefs = usePrefs();
  const offset = compareOffsetSec(ctx.compare, ctx.fromMs, ctx.toMs);
  const summary = useHostSummary(accountId, instanceId, ctx.from, ctx.to, offset);
  const extras = useMemo<ChartExtras>(() => ({ bands: summary.data?.bands, compare: summary.data?.compare, showBands: prefs.bands,
    compareLabel: offset && summary.data?.compareOffsetSec === offset ? compareLabel(ctx.compare) : null }), [summary.data, prefs.bands, offset, ctx.compare]);
  // The XamOps top bar would title-case the URL's last segment ("I 056a..."); show the host's name instead.
  const topbar = useOptionalTopbarDispatch();
  const hostTitle = summary.data?.host.name ?? instanceId ?? null;
  useEffect(() => {
    if (!topbar) return undefined;
    topbar.setTitle(hostTitle);
    return () => topbar.setTitle(null);
  }, [hostTitle, topbar]);
  const limits = useHostLimits(accountId, instanceId, ctx.from, ctx.to);
  const changes = useHostChanges(accountId, instanceId, ctx.from, ctx.to);
  const markers = useMemo(() => changeMarkers(changes.data), [changes.data]);
  const hash = location.hash.replace(/^#/, '');
  const section: SectionId = isSection(hash) ? hash : 'overview';
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const select =(id: SectionId) => navigate({ pathname: location.pathname, search: location.search, hash: id === 'overview' ? '' : id }, { replace: true });
  const onTabKey = (e: React.KeyboardEvent, i: number) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'Home' && e.key !== 'End') return;
    e.preventDefault();
    const n = SECTIONS.length;
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + n) % n;
    select(SECTIONS[next].id);
    tabRefs.current[next]?.focus();
  };

  const h = summary.data?.host;
  const notFound = summary.error?.status === 404;
  return (
    <div className="xp-host">
      <div className="xp-host__head">
        <Link className="xp-btn xp-btn--ghost xp-btn--sm" to={ctx.link('/xampulse/hosts')}><ArrowLeft size={13} aria-hidden="true" /> All hosts</Link>
        <div className="xp-row" style={{ gap: 10, marginTop: 10, flexWrap: 'wrap' }}>
          <h1 className="xp-h1" style={{ margin: 0 }}>{h?.name ?? instanceId}</h1>
          {h && <StatusPill health={h.health} />}
          {instanceId.startsWith('i-demo') && <DemoPill />}
          <span className="xp-spacer" />
          {summary.data && <ChartControls id="xp-host-compare" />}
          {summary.data && accountId !== undefined && section !== 'investigate' && (
            <button type="button" className="xp-btn xp-btn--sm" onClick={() => select('investigate')}
              title="Check limits, changes, saturation, errors and peers for this range">
              <Search size={13} aria-hidden="true" /> Investigate
            </button>
          )}
        </div>
        <div className="xp-muted xp-mono" style={{ fontSize: 11.5, marginTop: 4 }}>
          {instanceId}{h ? ` · ${h.instanceType ?? 'unknown type'} · ${h.region} · last seen ${formatAgo(h.lastSeenAt)}` : ''}
        </div>
      </div>

      {accountId === undefined && !accounts.isLoading && <Card><EmptyState title="Choose the host's account" body="This resource link is missing a valid authorized account. Return to Hosts and open it again." /></Card>}
      {accountId !== undefined && !notFound && <ThrottleRadar q={limits} />}

      <div className="xp-seg xp-host__nav" role="tablist" aria-label="Host sections">
        {SECTIONS.map((s, i) => (
          <button key={s.id} ref={(el) => { tabRefs.current[i] = el; }} type="button" role="tab" id={`xp-tab-${s.id}`} aria-controls="xp-host-panel"
            aria-selected={section === s.id} tabIndex={section === s.id ? 0 : -1}
            onClick={() => select(s.id)} onKeyDown={(e) => onTabKey(e, i)}>{s.label}</button>
        ))}
      </div>

      <div role="tabpanel" id="xp-host-panel" aria-labelledby={`xp-tab-${section}`} className="xp-host__panel">
        {accountId === undefined && accounts.isLoading && <SkeletonBlock height={200} label="Loading accounts" />}
        {summary.isLoading && <><SkeletonBlock height={90} /><SkeletonBlock height={190} /></>}
        {notFound && (
          section === 'aws' && accountId !== undefined
            ? <AwsPanel accountId={accountId} instanceId={instanceId} />
            : <Card><EmptyState title="No XamPulse agent reports from this instance"
                body="Only the AWS view is available. Add the instance in Setup to get OS, process and log data."
                action={<button type="button" className="xp-btn" onClick={() => select('aws')}>Open the AWS view</button>} /></Card>
        )}
        {summary.error && !notFound && <ErrorState error={summary.error} onRetry={() => { void summary.refetch(); }} />}
        {summary.data && accountId !== undefined && (
          <MarkersContext.Provider value={markers}>
            <ExtrasContext.Provider value={extras}>
              <Section id={section} s={summary.data} accountId={accountId} changes={changes.data} onSelect={select} />
            </ExtrasContext.Provider>
          </MarkersContext.Provider>
        )}
      </div>
    </div>
  );
};

export default HostPage;
