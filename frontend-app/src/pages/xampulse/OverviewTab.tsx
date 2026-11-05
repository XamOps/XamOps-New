import React from 'react';
import { Link } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useEvents, useOverview } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, KpiTile, LoadingGrid, SkeletonBlock } from '../../components/XamPulse/ui/ui';
import { FleetHoneycomb, ScoreRing } from '../../components/XamPulse/charts/Visuals';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatBytes, formatCount, formatPct } from '../../utils/xampulse/format';
import type { XpAttentionItem, XpEvent, XpOverview } from '../../types/xampulse';

const SEV_PILL: Record<XpAttentionItem['severity'], string> = { CRITICAL: 'crit', HIGH: 'crit', MEDIUM: 'warn', LOW: 'info' };
const SEV_ICON: Record<XpAttentionItem['severity'], string> = { CRITICAL: '\u2716', HIGH: '\u25B2', MEDIUM: '\u25B2', LOW: '\u25CF' };
const KIND_ICON: Record<XpEvent['kind'], string> = { DEPLOY: '\u21E7', EC2_STATE: '\u23FB', ALERT: '\u2757', ANOMALY: '\u223F', AGENT: '\u2699', CONFIG: '\u2699' };

const NarrativeHero: React.FC<{ o: XpOverview }> = ({ o }) => {
  const lines = o.narrative.length ? o.narrative : [o.healthScore == null ? 'No data has arrived yet. Health appears once your first agent reports.' : 'Everything looks quiet.'];
  return (
    <div className="xp-hero">
      <ScoreRing score={o.healthScore} />
      <div style={{ flex: 1, minWidth: 260 }}>
        <div className="xp-eyebrow">Mission Control</div>
        <h1 className="xp-h1">{lines[0]}</h1>
        {lines.slice(1).map((l) => <p key={l} className="xp-muted" style={{ margin: '6px 0 0', maxWidth: '70ch' }}>{l}</p>)}
        <p className="xp-muted" style={{ margin: '10px 0 0', fontSize: 12 }}>Last data {formatAgo(o.lastDataAt)}. Scores learn your normal for about a week, so early readings can be less certain.</p>
      </div>
    </div>
  );
};

const KpiStrip: React.FC<{ o: XpOverview }> = ({ o }) => {
  const k = o.kpis;
  return (
    <div className="xp-grid xp-grid--kpi">
      <KpiTile label="Hosts reporting" value={`${k.hostsReporting} / ${k.hostsTotal}`} hint={k.hostsReporting < k.hostsTotal ? `${k.hostsTotal - k.hostsReporting} not reporting` : 'All reporting'} />
      <KpiTile label="Errors per minute" value={formatCount(k.errorsPerMin)} hint="Across all logs in range" />
      <KpiTile label="Ingested (24h)" value={formatBytes(k.ingestBytes24h)} />
      <KpiTile label="Open incidents" value={k.openIncidents} hint={k.openIncidents ? 'Needs a person' : 'None open'} />
      <KpiTile label="Coverage" value={formatPct(k.coveragePct)} hint="Instances sending data" />
    </div>
  );
};

const AttentionFeed: React.FC<{ items: XpAttentionItem[] }> = ({ items }) => {
  const ctx = useViewContext();
  if (items.length === 0) return <EmptyState title="Nothing needs your attention" body="No hosts are outside their normal range right now." />;
  return (
    <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
      {items.map((a) => (
        <li key={a.id} className="xp-card xp-card--flat">
          <div className="xp-row">
            <span className={`xp-pill xp-pill--${SEV_PILL[a.severity]}`}><span aria-hidden="true">{SEV_ICON[a.severity]}</span>{a.severity}</span>
            <strong>{a.title}</strong>
            <span className="xp-spacer" />
            <span className="xp-muted" style={{ fontSize: 12 }}>since {formatAgo(a.since)} · confidence {a.confidence.toLowerCase()}</span>
          </div>
          <p style={{ margin: '8px 0' }}>{a.detail}</p>
          <div className="xp-row">
            {a.evidence.map((ev) => <Link key={ev.label} className="xp-btn" to={ctx.link(ev.deepLink)}>{ev.kind === 'LOG' ? 'Logs' : ev.kind === 'METRIC' ? 'Metric' : ev.kind === 'ALERT' ? 'Alert' : 'Event'}: {ev.label}</Link>)}
            {a.instanceId && <Link className="xp-btn xp-btn--ghost" to={ctx.link(xamPulseHostPath(a.instanceId))}>Open host</Link>}
          </div>
        </li>
      ))}
    </ol>
  );
};

const WhatChangedRail: React.FC = () => {
  const ctx = useViewContext();
  const events = useEvents(ctx.effectiveAccountIds, ctx.from, ctx.to);
  return (
    <Card title="What changed" id="xp-changed">
      <QueryBoundary query={events} skeleton={<SkeletonBlock height={140} />}>
        {(list) => list.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No deploys, restarts or alerts in this range.</p> : (
          <ul className="xp-timeline">
            {list.slice().sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts)).map((e) => (
              <li key={`${e.ts}-${e.title}`}>
                <div><span aria-hidden="true">{KIND_ICON[e.kind]}</span> <strong>{e.title}</strong></div>
                <div className="xp-muted" style={{ fontSize: 12 }}>{formatAgo(e.ts)}{e.detail ? ` · ${e.detail}` : ''}</div>
              </li>
            ))}
          </ul>
        )}
      </QueryBoundary>
    </Card>
  );
};

const OverviewTab: React.FC = () => {
  const ctx = useViewContext();
  const q = useOverview(ctx.scope);
  return (
    <>
      <QueryBoundary query={q} skeleton={<><SkeletonBlock height={170} label="Loading fleet health" /><LoadingGrid count={5} height={92} /></>}>
        {(o) => (
          <>
            <NarrativeHero o={o} />
            <KpiStrip o={o} />
            <div className="xp-grid xp-grid--main">
              <Card title="Needs attention" id="xp-attn" actions={<span className="xp-muted" style={{ fontSize: 12 }}>Ranked by impact</span>}><AttentionFeed items={o.attention} /></Card>
              <div className="xp-grid">
                <Card title="Fleet map" id="xp-fleet">
                  {o.tiles.length === 0
                    ? <EmptyState title="No hosts yet" body="Hosts appear here as their agents start reporting." />
                    : <FleetHoneycomb tiles={o.tiles} hrefFor={(t) => ctx.link(xamPulseHostPath(t.instanceId))} />}
                </Card>
                <WhatChangedRail />
              </div>
            </div>
          </>
        )}
      </QueryBoundary>
    </>
  );
};

export default OverviewTab;
