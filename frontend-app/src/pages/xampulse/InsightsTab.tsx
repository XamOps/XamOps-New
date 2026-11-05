import React from 'react';
import { Link } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useForecasts, useInsights, useUsage } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, SkeletonBlock } from '../../components/XamPulse/ui/ui';
import { ForecastFan } from '../../components/XamPulse/charts/Visuals';
import { formatAgo, formatBytes, formatUsd } from '../../utils/xampulse/format';
import type { XpInsight, XpUsage } from '../../types/xampulse';

const KIND_LABEL: Record<XpInsight['kind'], string> = { NARRATIVE: 'Summary', ANOMALY: 'Unusual', WHAT_CHANGED: 'What changed', COST: 'Log cost', RIGHTSIZING: 'Rightsizing', NEW_PATTERN: 'New pattern' };
const SEV_PILL: Record<XpInsight['severity'], string> = { INFO: 'info', LOW: 'info', MEDIUM: 'warn', HIGH: 'crit' };

export const UsagePanel: React.FC<{ u: XpUsage }> = ({ u }) => {
  const days = Array.from(new Set(u.byDay.map((d) => d.day)));
  const totals = days.map((day) => u.byDay.filter((d) => d.day === day).reduce((s, d) => s + d.bytesIn, 0));
  const max = Math.max(1, ...totals);
  return (
    <div className="xp-grid xp-grid--2">
      <div>
        <h3 style={{ margin: '0 0 8px' }}>Ingested per day</h3>
        <svg viewBox="0 0 300 90" width="100%" height={110} role="img" aria-label={`Daily ingest for ${days.length} days, peak ${formatBytes(max)}`} preserveAspectRatio="none">
          {totals.map((t, i) => <rect key={days[i]} x={i * (300 / totals.length) + 3} width={300 / totals.length - 6} y={88 - (t / max) * 84} height={(t / max) * 84} fill="var(--xp-accent)" rx={3} />)}
        </svg>
        <div className="xp-row xp-muted" style={{ justifyContent: 'space-between', fontSize: 11 }}><span>{days[0]}</span><span>{days[days.length - 1]}</span></div>
      </div>
      <div>
        <h3 style={{ margin: '0 0 8px' }}>Where log volume comes from</h3>
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {u.bySource.map((s) => (
            <li key={s.source} style={{ marginBottom: 6 }}>
              <div className="xp-row"><span>{s.source}</span><span className="xp-spacer" /><span className="xp-muted">{formatBytes(s.bytes)} · {s.pct}%</span></div>
              <div className="xp-bar" style={{ width: '100%', marginRight: 0 }} role="img" aria-label={`${s.source} ${s.pct} percent`}><i style={{ width: `${s.pct}%` }} /></div>
            </li>
          ))}
        </ul>
        <p className="xp-muted" style={{ fontSize: 12 }}>Hot storage {formatBytes(u.hotBytes)} · archive {formatBytes(u.archiveBytes)} · projected AWS cost {formatUsd(u.projectedMonthlyCustomerAwsUsd)} per month in your account.</p>
      </div>
    </div>
  );
};

const InsightsTab: React.FC = () => {
  const ctx = useViewContext();
  const accountId = ctx.effectiveAccountIds[0];
  const feed = useInsights(ctx.scope);
  const fc = useForecasts(accountId);
  const usage = useUsage(accountId);
  return (
    <>
      <div className="xp-row"><h1 className="xp-h1" style={{ fontSize: 22 }}>Insights</h1><span className="xp-muted">Plain-language findings with the evidence behind them</span></div>
      <div className="xp-grid xp-grid--main">
        <Card title="What we noticed" id="xp-feed">
          <QueryBoundary query={feed} skeleton={<SkeletonBlock height={240} />}>
            {(list) => list.length === 0 ? <EmptyState title="Still learning your normal" body="Insights need about a week of data to be reliable. Until then, check Mission Control." /> : (
              <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
                {list.map((i) => (
                  <li key={i.id} className="xp-card xp-card--flat">
                    <div className="xp-row"><span className={`xp-pill xp-pill--${SEV_PILL[i.severity]}`}>{KIND_LABEL[i.kind]}</span><strong>{i.title}</strong><span className="xp-spacer" /><span className="xp-muted" style={{ fontSize: 12 }}>{formatAgo(i.asOf)} · confidence {i.confidence.toLowerCase()}</span></div>
                    <p style={{ margin: '8px 0' }}>{i.body}</p>
                    {i.kind === 'COST' && <p className="xp-muted" style={{ margin: '0 0 8px', fontSize: 12 }}>Suggestion only. XamPulse never drops your logs on its own.</p>}
                    <div className="xp-row">{i.evidence.map((ev) => <Link key={ev.label} className="xp-btn" to={ctx.link(ev.deepLink)}>{ev.label}</Link>)}</div>
                  </li>
                ))}
              </ul>
            )}
          </QueryBoundary>
        </Card>
        <Card title="Disk forecasts" id="xp-fc">
          <QueryBoundary query={fc} skeleton={<SkeletonBlock height={120} />}>
            {(list) => list.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No filesystem is trending towards full.</p> : (
              <div className="xp-grid">{list.map((f) => <div key={`${f.instanceId}${f.resource}`}><div className="xp-muted xp-mono" style={{ fontSize: 12 }}>{f.instanceId}</div><ForecastFan f={f} /></div>)}</div>
            )}
          </QueryBoundary>
        </Card>
      </div>
      <Card title="Log volume and cost" id="xp-usage">
        <QueryBoundary query={usage} skeleton={<SkeletonBlock height={160} />}>{(u) => <UsagePanel u={u} />}</QueryBoundary>
      </Card>
    </>
  );
};

export default InsightsTab;
