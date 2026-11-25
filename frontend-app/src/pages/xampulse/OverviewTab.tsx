import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, ArrowUpRight, Bell, Cpu, Database, FileWarning, Gauge, OctagonAlert, Power, Rocket, Server, Settings2, type LucideIcon } from 'lucide-react';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useEvents, useOverview, useXpAccounts } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, KpiTile, LoadingGrid, PageHead, SkeletonBlock, StatusPill, TonePill } from '../../components/XamPulse/ui/ui';
import { formatAgo, formatBytes, formatCount, formatDateTime, formatPct } from '../../utils/xampulse/format';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import type { XpAttentionItem, XpEvent, XpHealth, XpOverview } from '../../types/xampulse';

const SEV_TONE: Record<XpAttentionItem['severity'], 'crit' | 'warn' | 'info'> = { CRITICAL: 'crit', HIGH: 'crit', MEDIUM: 'warn', LOW: 'info' };
const KIND_ICON: Record<XpEvent['kind'], LucideIcon> = { DEPLOY: Rocket, EC2_STATE: Power, ALERT: Bell, ANOMALY: Activity, AGENT: Cpu, CONFIG: Settings2 };
const HEALTH_ORDER: XpHealth[] = ['CRIT', 'WARN', 'NO_DATA', 'OK'];
function scopedEvidencePath(path: string, accountId: number): string {
  const url = new URL(path, 'https://xampulse.local');
  if (/^\/xampulse\/hosts\/[^/]+$/.test(url.pathname)) url.searchParams.set('accountId', String(accountId));
  if (url.pathname === '/xampulse/alerts' && !url.searchParams.has('view')) url.searchParams.set('view', 'incidents');
  return `${url.pathname}${url.search}${url.hash}`;
}
const accountStateHealth = (state: XpOverview['state']): XpHealth => state === 'CONNECTED' ? 'OK' : state === 'DEGRADED' || state === 'VERIFYING' || state === 'DEPLOYING' ? 'WARN' : 'NO_DATA';
const scoreHealth = (score: number | null): XpHealth => score == null ? 'NO_DATA' : score >= 80 ? 'OK' : score >= 60 ? 'WARN' : 'CRIT';

const EstateStatus: React.FC<{ overview: XpOverview; accountLabel: string }> = ({ overview, accountLabel }) => {
  const health = scoreHealth(overview.healthScore);
  const narrative = overview.narrative.length ? overview.narrative : [overview.lastDataAt ? 'No active narrative is available for this range.' : 'Health appears after telemetry starts reporting.'];
  return (
    <section className="xp-estate" aria-labelledby="xp-estate-title">
      <div className="xp-estate__status">
        <span className="xp-eyebrow">Estate health</span>
        <div className="xp-row" style={{ gap: 10 }}>
          <StatusPill health={health} />
          <strong id="xp-estate-title" className="xp-estate__score">{overview.healthScore == null ? 'No score' : `${overview.healthScore} / 100`}</strong>
        </div>
      </div>
      <div className="xp-estate__body">
        <strong>{narrative[0]}</strong>
        {narrative.slice(1).map((line) => <p key={line}>{line}</p>)}
        <div className="xp-estate__meta">
          <span>{accountLabel}</span>
          <StatusPill health={accountStateHealth(overview.state)} label={overview.state.replaceAll('_', ' ').toLowerCase()} />
          <span>Telemetry {overview.lastDataAt ? `${formatAgo(overview.lastDataAt)} · ${formatDateTime(overview.lastDataAt)}` : 'has not arrived'}</span>
        </div>
      </div>
    </section>
  );
};

const KpiStrip: React.FC<{ overview: XpOverview }> = ({ overview }) => {
  const k = overview.kpis;
  const critical = overview.tiles.filter((tile) => tile.health === 'CRIT').length;
  const warning = overview.tiles.filter((tile) => tile.health === 'WARN').length;
  const hasFleetHealth = overview.tiles.length > 0;
  const hasHostDenominator = k.hostsTotal > 0;
  return (
    <div className="xp-grid xp-grid--kpi" aria-label="Operational summary">
      <KpiTile icon={Server} tone="green" label="Reporting hosts" status={!hasHostDenominator ? 'NO_DATA' : k.hostsReporting < k.hostsTotal ? 'WARN' : 'OK'} value={`${k.hostsReporting} / ${k.hostsTotal}`} hint={`${formatPct(k.coveragePct)} coverage · selected range`} />
      <KpiTile icon={OctagonAlert} tone="red" label="Critical hosts" status={!hasFleetHealth ? 'NO_DATA' : critical ? 'CRIT' : 'OK'} value={hasFleetHealth ? critical : '—'} hint={hasFleetHealth ? 'Derived from complete fleet health tiles' : 'No host health measurements in this range'} />
      <KpiTile icon={AlertTriangle} tone="amber" label="Warning hosts" status={!hasFleetHealth ? 'NO_DATA' : warning ? 'WARN' : 'OK'} value={hasFleetHealth ? warning : '—'} hint={hasFleetHealth ? 'Derived from complete fleet health tiles' : 'No host health measurements in this range'} />
      <KpiTile icon={Bell} tone="violet" label="Open incidents" status={k.openIncidents ? 'CRIT' : 'OK'} value={k.openIncidents} hint={k.openIncidents ? 'Needs triage' : 'None open'} />
      <KpiTile icon={FileWarning} tone="indigo" label="Log errors" value={`${formatCount(k.errorsPerMin)} / min`} hint="Rate in selected range · no severity threshold configured" />
      <KpiTile icon={Database} tone="indigo" label="Telemetry ingested" value={formatBytes(k.ingestBytes24h)} hint="Last 24 hours" />
    </div>
  );
};

const AttentionFeed: React.FC<{ items: XpAttentionItem[]; accountName: (id: number) => string }> = ({ items, accountName }) => {
  const ctx = useViewContext();
  if (!items.length) return <EmptyState title="Nothing needs attention" body="No returned finding is currently prioritized for this selection." />;
  return (
    <ol className="xp-list xp-priority-list">
      {items.map((item, index) => (
        <li key={item.id} className="xp-item">
          <div className="xp-item__head">
            <span className="xp-rank" aria-label={`Priority ${index + 1}`}>{index + 1}</span>
            <TonePill tone={SEV_TONE[item.severity]}>{item.severity}</TonePill>
            <span className="xp-item__title">{item.title}</span>
            <span className="xp-spacer" />
            <span className="xp-item__meta">since {formatAgo(item.since)} · {item.confidence.toLowerCase()} confidence</span>
          </div>
          <p className="xp-item__body">{item.detail}</p>
          <div className="xp-location"><strong>Impact:</strong> {accountName(item.accountId)}{item.instanceId ? ` · ${item.instanceId}` : ' · account-wide'}</div>
          <div className="xp-item__actions">
            {item.instanceId && <Link className="xp-btn xp-btn--primary xp-btn--sm" to={ctx.link(xamPulseHostPath(item.instanceId, item.accountId))}>Investigate host <ArrowUpRight size={12} aria-hidden="true" /></Link>}
            {item.evidence.map((evidence) => <Link key={`${evidence.kind}-${evidence.label}`} className="xp-btn xp-btn--sm" to={ctx.link(scopedEvidencePath(evidence.deepLink, item.accountId))}>{evidence.label}</Link>)}
          </div>
        </li>
      ))}
    </ol>
  );
};

/** Overview shows the latest few changes; Previous / Next pages through the rest of the range. */
const CHANGES_PER_PAGE = 5;

const Changed: React.FC<{ accountName: (id: number) => string }> = ({ accountName }) => {
  const ctx = useViewContext();
  const events = useEvents(ctx.effectiveAccountIds, ctx.from, ctx.to);
  const [page, setPage] = useState(0);
  const scopeKey = `${ctx.effectiveAccountIds.join(',')}|${ctx.from}|${ctx.to}`;
  useEffect(() => { setPage(0); }, [scopeKey]);
  return (
    <Card title="What changed" id="xp-changed" actions={<span className="xp-eyebrow">Selected range</span>}>
      <QueryBoundary query={events} skeleton={<SkeletonBlock height={180} label="Loading recent changes" />}>
        {(list) => {
          if (!list.length) return <EmptyState title="No recorded changes" body="No deploy, state, agent, alert, anomaly or configuration events were returned in this range." />;
          const sorted = list.slice().sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts));
          const pages = Math.ceil(sorted.length / CHANGES_PER_PAGE);
          const current = Math.min(page, pages - 1);
          const start = current * CHANGES_PER_PAGE;
          const shown = sorted.slice(start, start + CHANGES_PER_PAGE);
          return (
            <>
              <ul className="xp-timeline">
                {shown.map((event) => {
                  const Icon = KIND_ICON[event.kind] ?? Activity;
                  // The title usually names the instance already; repeat the id below only when it does not.
                  const showResource = !!event.resource && !event.title.includes(event.resource);
                  const content = (
                    <>
                      <span className="xp-timeline__title">{event.title}</span>
                      <span className="xp-timeline__meta">
                        {accountName(event.accountId)}{showResource && <> · <span className="xp-mono">{event.resource}</span></>} · {formatAgo(event.ts)}{event.detail ? ` · ${event.detail}` : ''}
                      </span>
                    </>
                  );
                  return <li key={`${event.ts}-${event.title}`}><span className="xp-timeline__ic" aria-hidden="true"><Icon size={12} /></span>{event.resource.startsWith('i-') ? <Link to={ctx.link(xamPulseHostPath(event.resource, event.accountId))}>{content}</Link> : <span>{content}</span>}</li>;
                })}
              </ul>
              {pages > 1 && (
                <nav className="xp-pagination" aria-label="Change pages">
                  <button type="button" className="xp-btn" disabled={current === 0} onClick={() => setPage(current - 1)}>Previous</button>
                  <span>{start + 1}–{start + shown.length} of {sorted.length}</span>
                  <button type="button" className="xp-btn" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Next</button>
                </nav>
              )}
            </>
          );
        }}
      </QueryBoundary>
    </Card>
  );
};

const FleetWhere: React.FC<{ overview: XpOverview; accountName: (id: number) => string }> = ({ overview, accountName }) => {
  const ctx = useViewContext();
  const counts = Object.fromEntries(HEALTH_ORDER.map((health) => [health, overview.tiles.filter((tile) => tile.health === health).length])) as Record<XpHealth, number>;
  const ranked = overview.tiles.slice().sort((a, b) => HEALTH_ORDER.indexOf(a.health) - HEALTH_ORDER.indexOf(b.health) || (a.score ?? 101) - (b.score ?? 101));
  if (!ranked.length) return <Card title="Where"><EmptyState title="No hosts yet" body="Hosts appear after telemetry starts reporting." /></Card>;
  return (
    <Card title="Where to look" id="xp-where" actions={<Link className="xp-btn xp-btn--sm" to={ctx.link('/xampulse/hosts')}>Open all hosts</Link>}>
      <div className="xp-health-distribution" aria-label="Fleet health distribution">
        {HEALTH_ORDER.map((health) => <div key={health}><StatusPill health={health} /><strong>{counts[health]}</strong></div>)}
      </div>
      <ol className="xp-fleet-list">
        {ranked.slice(0, 8).map((host) => <li key={`${host.accountId}-${host.instanceId}`}>
          <StatusPill health={host.health} />
          <span><Link className="xp-cell-name" to={ctx.link(xamPulseHostPath(host.instanceId, host.accountId))}>{host.name}</Link><span className="xp-cell-sub">{accountName(host.accountId)} · {host.instanceId}</span></span>
          <span className="xp-spacer" />
          <span className="xp-mono">CPU {host.cpuPct == null ? '—' : `${host.cpuPct.toFixed(0)}%`} · memory {host.memPct == null ? '—' : `${host.memPct.toFixed(0)}%`}</span>
          <Link className="xp-btn xp-btn--ghost xp-btn--sm" to={ctx.link(xamPulseHostPath(host.instanceId, host.accountId))}>Open <ArrowUpRight size={12} aria-hidden="true" /></Link>
        </li>)}
      </ol>
    </Card>
  );
};

const OverviewTab: React.FC = () => {
  const ctx = useViewContext();
  const query = useOverview(ctx.scope);
  const accounts = useXpAccounts();
  const accountName = (id: number) => accounts.data?.find((account) => account.cloudAccountId === id)?.name ?? `Account ${id}`;
  const selectedLabel = ctx.effectiveAccountIds.length === 1 ? accountName(ctx.effectiveAccountIds[0]) : `${ctx.effectiveAccountIds.length} accounts`;
  return (
    <>
      <PageHead icon={Gauge} title="Overview" sub="Current estate health, recent change, active impact, location and the next investigation step." />
      <QueryBoundary query={query} skeleton={<><SkeletonBlock height={128} label="Loading estate health" /><LoadingGrid count={6} height={96} /></>}>
        {(overview) => <>
          <EstateStatus overview={overview} accountLabel={selectedLabel} />
          <KpiStrip overview={overview} />
          <div className="xp-grid xp-grid--main">
            <Card title="Needs attention now" id="xp-attn" actions={<span className="xp-eyebrow">Worst first</span>}><AttentionFeed items={overview.attention} accountName={accountName} /></Card>
            <Changed accountName={accountName} />
          </div>
          <FleetWhere overview={overview} accountName={accountName} />
        </>}
      </QueryBoundary>
    </>
  );
};

export default OverviewTab;
