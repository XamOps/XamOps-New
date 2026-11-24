import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { Box, Check, Copy, Server } from 'lucide-react';
import '../../styles/xampulse-containers.css';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useContainerSummary, useLogHistogram, useXpAccounts } from '../../hooks/xampulse/useXamPulseQueries';
import { DemoPill, EmptyState, ErrorState, SkeletonBlock, StatusPill } from '../../components/XamPulse/ui/ui';
import { ContainerLogs } from '../../components/XamPulse/containers/ContainerLogs';
import { evidenceLabel, formatEvidence } from '../../components/XamPulse/hosts/ThrottleRadar';
import { ChangeList, changeMarkers } from '../../components/XamPulse/hosts/ChangeList';
import { TimeSeriesChart, type ChartSeries } from '../../components/XamPulse/charts/TimeSeriesChart';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatBytes, formatClock, formatRate } from '../../utils/xampulse/format';
import { containerVerdict, containerVitals, formatCorePct, memoryLimitState, type ContainerIssue, type ContainerVerdict, type ContainerVital } from '../../utils/xampulse/containerVerdict';
import { containerLogQuery, severityTotals, type SeverityFilter } from '../../utils/xampulse/containerLogs';
import type { XpContainerSummary, XpHealth, XpScope } from '../../types/xampulse';

const cores = formatCorePct;
const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString()} ${n === 1 ? one : many}`;
const cols = (n: number) => ({ ['--xp-ct-cols' as string]: n } as React.CSSProperties);

/**
 * Container 360: one container by name on one host, through every redeploy in the range, at
 * /xampulse/containers/:instanceId/:name. CPU is percent of ONE core, as Docker reports it (200% = two cores).
 * Order is answer first: what is wrong and what to do, the numbers behind it, how they moved, the logs, what changed.
 */
const ContainerPage: React.FC = () => {
  const { instanceId = '', name = '' } = useParams<{ instanceId: string; name: string }>();
  const ctx = useViewContext();
  const location = useLocation();
  const accounts = useXpAccounts();
  const requestedAccount = Number(new URLSearchParams(location.search).get('accountId'));
  const accountId = useMemo(() => {
    const allowed = accounts.data ?? [];
    if (Number.isInteger(requestedAccount) && requestedAccount > 0) return allowed.some((account) => account.cloudAccountId === requestedAccount) ? requestedAccount : undefined;
    return ctx.effectiveAccountIds.length === 1 ? ctx.effectiveAccountIds[0] : undefined;
  }, [accounts.data, requestedAccount, ctx.effectiveAccountIds]);
  const q = useContainerSummary(accountId, instanceId, name, ctx.from, ctx.to);
  const c = q.data;

  if (c && accountId !== undefined) return <div className="xp-ct"><Body c={c} accountId={accountId} instanceId={instanceId} /></div>;
  return (
    <div className="xp-ct">
      <Head name={name} instanceId={instanceId} accountId={accountId} />
      {accountId === undefined && !accounts.isLoading && <section className="xp-ct-panel"><EmptyState title="Choose the container's account" body="This resource link is missing a valid authorized account. Return to Containers and open it again." /></section>}
      {(q.isLoading || (accountId === undefined && accounts.isLoading)) && <><SkeletonBlock height={120} label="Loading container" /><SkeletonBlock height={84} /><SkeletonBlock height={260} /></>}
      {q.error && (q.error.status === 404
        ? <section className="xp-ct-panel"><EmptyState icon={Box} title={`No data for container ${name} on this host`} body="It did not report in this range, or Docker metrics are not enabled for the host (Setup)." /></section>
        : <ErrorState error={q.error} onRetry={() => { void q.refetch(); }} />)}
    </div>
  );
};

/** Breadcrumb, name with its status, then the machine facts in mono: image, container id (copyable), last seen. */
const Head: React.FC<{ name: string; instanceId: string; accountId?: number; c?: XpContainerSummary; level?: XpHealth }> = ({ name, instanceId, accountId, c, level }) => {
  const ctx = useViewContext();
  const [copied, setCopied] = useState(false);
  const hostHref = accountId === undefined ? ctx.link('/xampulse/hosts') : ctx.link(xamPulseHostPath(instanceId, accountId));
  const copy = () => {
    const done = c ? navigator.clipboard?.writeText?.(c.containerId) : undefined;
    if (done) done.then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); }).catch(() => undefined);
  };
  return (
    <header>
      <nav className="xp-ct-crumbs" aria-label="Breadcrumb">
        <Link to={ctx.link('/xampulse/containers')}>Containers</Link><span className="xp-ct-crumbs__sep" aria-hidden="true">/</span>
        <Link to={hostHref}>{instanceId}</Link><span className="xp-ct-crumbs__sep" aria-hidden="true">/</span>
        <span aria-current="page">{name}</span>
      </nav>
      <div className="xp-ct-head">
        <div className="xp-ct-head__l">
          <div className="xp-ct-head__title">
            <Box size={18} strokeWidth={1.5} aria-hidden="true" />
            <h1 className="xp-ct-title">{name}</h1>
            {level && <StatusPill health={level} />}
            {instanceId.startsWith('i-demo') && <DemoPill />}
          </div>
          {c && (
            <div className="xp-ct-meta">
              <span className="xp-ct-image" title="Image">{c.image}</span>
              <span>
                {c.containerId.slice(0, 12)}
                <button type="button" className="xp-ct-copy" data-done={copied} onClick={copy} aria-label={copied ? 'Container id copied' : 'Copy full container id'} title={copied ? 'Copied' : 'Copy full container id'}>
                  {copied ? <Check size={12} strokeWidth={2} aria-hidden="true" /> : <Copy size={12} strokeWidth={1.75} aria-hidden="true" />}
                </button>
              </span>
              <span>on {instanceId}</span>
              <span>last seen {formatAgo(c.lastSeen)}</span>
            </div>
          )}
        </div>
        <div className="xp-ct-head__r">
          <Link className="xp-btn xp-btn--sm" to={hostHref}><Server size={13} strokeWidth={1.75} aria-hidden="true" />View host</Link>
        </div>
      </div>
    </header>
  );
};

/** Every problem once, worst first. The first one is the verdict; a healthy or silent container gets one row saying so. */
const Diagnosis: React.FC<{ v: ContainerVerdict; onShowErrors: () => void }> = ({ v, onShowErrors }) => {
  const rows: (ContainerIssue | (Omit<ContainerIssue, 'level'> & { level: XpHealth }))[] = v.issues.length ? v.issues
    : [{ level: v.level, code: 'verdict', title: v.title, cause: v.cause, next: v.next, evidence: {} }];
  const crit = v.issues.filter((i) => i.level === 'CRIT').length;
  return (
    <section className="xp-ct-panel" aria-label="Container verdict">
      <div className="xp-ct-panel__head">
        <h2 className="xp-ct-panel__title">Diagnosis</h2>
        <span className="xp-ct-panel__hint">
          {v.issues.length ? `${plural(v.issues.length, 'problem')}${crit ? `, ${crit} critical` : ''}, worst first` : v.level === 'NO_DATA' ? 'Nothing to judge in this range' : 'No problems in this range'}
        </span>
      </div>
      <ol className="xp-ct-issues">
        {rows.map((i, idx) => {
          const evidence = Object.entries(i.evidence);
          return (
            <li key={`${i.code}-${idx}`} className={`xp-ct-issue${idx === 0 ? ' xp-ct-issue--lead' : ''}`}>
              <StatusPill health={i.level} />
              <div>
                <h3 className="xp-ct-issue__title">{i.title}</h3>
                <p className="xp-ct-issue__cause">{i.cause}</p>
                <p className="xp-ct-issue__next"><span className="xp-ct-label">Next</span>{i.next}</p>
                {evidence.length > 0 && (
                  <ul className="xp-ct-evidence" aria-label="Measured evidence">
                    {evidence.map(([k, n]) => <li key={k}>{evidenceLabel(k)} <b>{formatEvidence(k, n)}</b></li>)}
                  </ul>
                )}
              </div>
              {i.code === 'log_errors' ? <button type="button" className="xp-btn xp-btn--sm" onClick={onShowErrors}>Show errors</button> : <span />}
            </li>
          );
        })}
      </ol>
    </section>
  );
};

/** Label, headline value, one line of context; the Errors cell jumps to the errors when there are any. */
const Vitals: React.FC<{ items: ContainerVital[]; onShowErrors: () => void }> = ({ items, onShowErrors }) => (
  <section className="xp-ct-panel xp-ct-vitals" aria-label="Container vitals">
    {items.map((v) => {
      const body = <>
        <span className="xp-ct-label">{v.label}</span>
        <span className="xp-ct-vital__v">{v.value}</span>
        {v.meter != null && <span className="xp-ct-vital__bar" aria-hidden="true"><i style={{ width: `${v.meter}%` }} /></span>}
        <span className="xp-ct-vital__s" title={v.sub}>{v.sub}</span>
      </>;
      return v.key === 'errors' && v.tone
        ? <button key={v.key} type="button" className="xp-ct-vital" data-tone={v.tone} onClick={onShowErrors} title="Show these errors in the logs below">{body}</button>
        : <div key={v.key} className="xp-ct-vital" data-tone={v.tone}>{body}</div>;
    })}
  </section>
);

const Body: React.FC<{ c: XpContainerSummary; accountId: number; instanceId: string }> = ({ c, accountId, instanceId }) => {
  const ctx = useViewContext();
  const location = useLocation();
  const markers = useMemo(() => changeMarkers(c.changes), [c.changes]);
  const ids = useMemo(() => c.versions.map((v) => v.containerId), [c.versions]);
  // One account, one host; the container ids go in the query text (live tail ignores scope resource filters).
  const scope = useMemo<XpScope>(() => ({ accountIds: [accountId], from: ctx.scope.from, to: ctx.scope.to, resource: { instanceIds: [instanceId] } }),
    [accountId, instanceId, ctx.scope.from, ctx.scope.to]);
  const allLogs = useLogHistogram(scope, containerLogQuery(instanceId, ids, 'all', ''), true);
  const totals = severityTotals(allLogs.data);
  const [severity, setSeverity] = useState<SeverityFilter>(location.hash === '#errors' ? 'error' : 'all');
  const logsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (location.hash === '#errors' || location.hash === '#xp-clogs') logsRef.current?.scrollIntoView?.({ block: 'start' });
  }, [location.hash]);
  const showErrors = () => { setSeverity('error'); logsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }); };
  const input = { series: c.series, findings: c.findings, restarts: c.restarts, versions: ids.length, errors: allLogs.error ? null : totals?.errors ?? null };
  const verdict = containerVerdict(input);
  const vitals = containerVitals(input);
  const mem = memoryLimitState(c.series);

  const s = (key: string, label: string, color: string, dashed = false): ChartSeries[] => (c.series[key]?.length ? [{ key, name: label, points: c.series[key], color, dashed }] : []);
  const brush = (a: number, b: number) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() });
  const chart = (title: string, hint: string, series: ChartSeries[], format: (v: number) => string, height: number, opts: { max?: number; missing?: string } = {}) => (
    <div className="xp-ct-chart">
      <h3 className="xp-ct-chart__title">{title}</h3>
      <p className="xp-ct-chart__hint">{hint}</p>
      {series.length === 0 ? <p className="xp-ct-chart__none">{opts.missing ?? 'No data for this range.'}</p>
        : <TimeSeriesChart showLegend series={series} height={height} format={format} yMax={opts.max} markers={markers} onBrush={brush} ariaLabel={title} fromMs={ctx.fromMs} toMs={ctx.toMs} />}
    </div>
  );
  // Bright chart tokens, validated as a pair for colour-blind separation (the old blue + violet failed: ΔE 1.3).
  const BLUE = 'var(--xp-series-1)';
  const VIOLET = 'var(--xp-series-2)';
  const throttle = s('throttled_pct', 'Throttled periods', BLUE);
  const memoryHint = mem.noLimit
    ? (mem.host != null || mem.limit != null ? `No limit set: it can grow into all ${formatBytes(mem.host ?? mem.limit)} of host memory.` : 'No memory limit reported.')
    : `Dashed: its ${formatBytes(mem.limit)} limit. At the limit the kernel kills it (OOM).`;
  const versions = [...c.versions].reverse();

  return (
    <>
      <Head name={c.name} instanceId={instanceId} accountId={accountId} c={c} level={verdict.level} />
      <Diagnosis v={verdict} onShowErrors={showErrors} />
      <Vitals items={vitals} onShowErrors={showErrors} />

      <section className="xp-ct-panel" aria-label="Resource usage">
        <div className="xp-ct-panel__head">
          <h2 className="xp-ct-panel__title">Resource usage</h2>
          <span className="xp-ct-panel__hint">Drag across a chart to zoom. Dots on the time axis mark changes.</span>
        </div>
        <div className="xp-ct-charts__row" style={cols(2)}>
          {chart('CPU', throttle.length ? '100% is one full core, as docker stats shows it.' : '100% is one full core. No throttling data: no CPU limit set, or an older agent.', s('cpu', 'CPU (of one core)', BLUE), cores, 170)}
          {chart('Memory', memoryHint, mem.noLimit ? s('memory', 'Used', BLUE) : [...s('memory', 'Used', BLUE), ...s('memory_limit', 'Limit', 'var(--xp-fill-crit)', true)], (v) => formatBytes(v), 170)}
        </div>
        <div className="xp-ct-charts__row" style={cols(throttle.length ? 3 : 2)} data-cols={throttle.length ? 3 : 2}>
          {chart('Network', 'Bytes per second in and out.', [...s('network_rx', 'In', BLUE), ...s('network_tx', 'Out', VIOLET)], (v) => formatRate(v), 120)}
          {chart('Disk I/O', 'Bytes per second read and written.', [...s('disk_read', 'Read', BLUE), ...s('disk_write', 'Write', VIOLET)], (v) => formatRate(v), 120)}
          {throttle.length > 0 && chart('CPU throttling', 'Share of CPU periods spent held at its limit.', throttle, (v) => `${Math.round(v)}%`, 120, { max: 100 })}
        </div>
      </section>

      <div ref={logsRef} id="xp-clogs" style={{ scrollMarginTop: 12 }}>
        <ContainerLogs scope={scope} instanceId={instanceId} containerIds={ids} severity={severity} onSeverity={setSeverity} totals={allLogs.error ? null : totals} />
      </div>

      <div className="xp-ct-history">
        <section className="xp-ct-panel" aria-labelledby="xp-cchanges-t">
          <div className="xp-ct-panel__head">
            <h2 id="xp-cchanges-t" className="xp-ct-panel__title">Changes</h2>
            <span className="xp-ct-panel__hint">Deploys and restarts, and what the measurements did next</span>
          </div>
          <ChangeList changes={c.changes} />
        </section>
        <section className="xp-ct-panel" aria-labelledby="xp-cversions-t">
          <div className="xp-ct-panel__head">
            <h2 id="xp-cversions-t" className="xp-ct-panel__title">Versions</h2>
            <span className="xp-ct-panel__hint">In range and the 24 h before, newest first</span>
          </div>
          <ol className="xp-ct-versions" aria-label="Container versions, newest first">
            {versions.map((v, i) => (
              <li key={v.containerId}>
                <span className="xp-ct-versions__image" title={v.image}>{v.image}</span>
                {i === 0 ? <span className="xp-pill xp-pill--nodata">Latest</span> : <span />}
                <span className="xp-ct-versions__meta">
                  {v.containerId.slice(0, 12)} · <time dateTime={v.firstSeen}>{formatClock(v.firstSeen).slice(0, 5)}</time> → <time dateTime={v.lastSeen}>{formatClock(v.lastSeen).slice(0, 5)}</time>
                </span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </>
  );
};

export default ContainerPage;
