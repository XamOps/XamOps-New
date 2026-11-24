import React from 'react';
import { Lightbulb } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useForecasts, useInsights } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { Card, EmptyState, SkeletonBlock, PageHead, TonePill } from '../../components/XamPulse/ui/ui';
import { ForecastFan } from '../../components/XamPulse/charts/Visuals';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatBytes, formatUsd } from '../../utils/xampulse/format';
import type { XpInsight, XpUsage } from '../../types/xampulse';

const KIND_LABEL: Record<XpInsight['kind'], string> = { NARRATIVE: 'Summary', ANOMALY: 'Unusual', WHAT_CHANGED: 'What changed', COST: 'Log cost', RIGHTSIZING: 'Rightsizing', NEW_PATTERN: 'New pattern' };
const SEV_PILL: Record<XpInsight['severity'], string> = { INFO: 'info', LOW: 'info', MEDIUM: 'warn', HIGH: 'crit' };
function scopedEvidencePath(path: string, accountId: number): string {
  const url = new URL(path, 'https://xampulse.local');
  if (/^\/xampulse\/hosts\/[^/]+$/.test(url.pathname)) url.searchParams.set('accountId', String(accountId));
  if (url.pathname === '/xampulse/alerts' && !url.searchParams.has('view')) url.searchParams.set('view', 'incidents');
  return `${url.pathname}${url.search}${url.hash}`;
}

export const UsagePanel: React.FC<{ u: XpUsage }> = ({ u }) => {
  const days = Array.from(new Set(u.byDay.map((d) => d.day)));
  const totals = days.map((day) => u.byDay.filter((d) => d.day === day).reduce((s, d) => s + d.bytesIn, 0));
  const max = Math.max(1, ...totals);
  return (
    <div className="xp-grid xp-grid--2">
      <div>
        <div className="xp-row" style={{ marginBottom: 14 }}><h3 style={{ margin: 0 }}>Ingested per day</h3><span className="xp-spacer" /><span className="xp-legend__v">peak {formatBytes(max)}</span></div>
        <div className="xp-dbars" role="img" aria-label={`Daily ingest for ${days.length} days, peak ${formatBytes(max)}`}>
          {totals.map((t, i) => <span key={days[i]} data-last={i === totals.length - 1} title={`${days[i]}: ${formatBytes(t)}`} style={{ height: `${Math.max(2, (t / max) * 100)}%` }} />)}
        </div>
        <div className="xp-axis-row" style={{ borderTop: '1px solid var(--xp-edge)', paddingTop: 6 }}><span>{days[0]}</span><span>{days[days.length - 1]}</span></div>
      </div>
      <div>
        <h3 style={{ margin: '0 0 14px' }}>Where log volume comes from</h3>
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {u.bySource.map((s) => (
            <li key={s.source}>
              <div className="xp-row" style={{ marginBottom: 6, fontSize: 12.5 }}><span className="xp-mono" style={{ color: 'var(--xp-text2)' }}>{s.source}</span><span className="xp-spacer" /><span className="xp-legend__v">{formatBytes(s.bytes)} · {s.pct.toFixed(1)}%</span></div>
              <div className="xp-bar" style={{ width: '100%', marginRight: 0 }} role="img" aria-label={`${s.source} ${s.pct.toFixed(1)} percent`}><i style={{ width: `${s.pct}%` }} /></div>
            </li>
          ))}
        </ul>
        <p className="xp-hint" style={{ marginTop: 16 }}>
          Hot storage {formatBytes(u.hotBytes)} · archive {u.archiveBytes > 0 ? formatBytes(u.archiveBytes) : 'off or not measured'} · projected AWS cost {u.projectedMonthlyCustomerAwsUsd == null ? 'not available' : `${formatUsd(u.projectedMonthlyCustomerAwsUsd)} per month in your account`}.
          {u.quotaGbPerDay == null ? ' Daily quota is not configured.' : ` Daily quota ${u.quotaGbPerDay.toLocaleString()} GB/day.`}
        </p>
      </div>
    </div>
  );
};

const InsightsTab: React.FC = () => {
  const ctx = useViewContext();
  const accountId = ctx.effectiveAccountIds[0];
  const feed = useInsights(ctx.scope);
  const fc = useForecasts(accountId);
  return (
    <>
      <PageHead icon={Lightbulb} title="Insights" sub="Plain-language findings with the evidence behind them." />
      <div className="xp-grid xp-grid--main">
        <Card title="What we noticed" id="xp-feed">
          <QueryBoundary query={feed} skeleton={<SkeletonBlock height={240} />}>
            {(list) => list.length === 0 ? <EmptyState title="Still learning your normal" body="Insights need about a week of data to be reliable. Until then, check Mission Control." /> : (
              <ul className="xp-list">
                {list.slice().sort((a, b) => ({ HIGH: 0, MEDIUM: 1, LOW: 2, INFO: 3 }[a.severity] - { HIGH: 0, MEDIUM: 1, LOW: 2, INFO: 3 }[b.severity])).map((i) => (
                  <li key={i.id} className="xp-item">
                    <div className="xp-item__head"><TonePill tone={SEV_PILL[i.severity] as 'crit' | 'warn' | 'info'}>{KIND_LABEL[i.kind]}</TonePill><span className="xp-item__title">{i.title}</span><span className="xp-spacer" /><span className="xp-item__meta"><span className="xp-mono">{formatAgo(i.asOf)}</span> · confidence {i.confidence.toLowerCase()}</span></div>
                    <p className="xp-item__body">{i.body}</p>
                    <div className="xp-location">Account {i.accountId}{i.instanceId ? ` · host ${i.instanceId}` : ' · account-wide'} · as of {formatAgo(i.asOf)}</div>
                    {i.kind === 'COST' && <p className="xp-muted" style={{ margin: '-4px 0 10px', fontSize: 11.5 }}>Suggestion only. XamPulse never drops your logs on its own.</p>}
                    <div className="xp-item__actions">{i.instanceId && <Link className="xp-btn xp-btn--primary xp-btn--sm" to={ctx.link(xamPulseHostPath(i.instanceId, i.accountId))}>Open host</Link>}{i.evidence.map((ev) => <Link key={ev.label} className="xp-btn xp-btn--sm" to={ctx.link(scopedEvidencePath(ev.deepLink, i.accountId))}>{ev.label}</Link>)}</div>
                  </li>
                ))}
              </ul>
            )}
          </QueryBoundary>
        </Card>
        <Card title="Disk forecasts" id="xp-fc">
          <QueryBoundary query={fc} skeleton={<SkeletonBlock height={120} />}>
            {(list) => list.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>No filesystem is trending towards full.</p> : (
              <div className="xp-grid" style={{ gap: 22 }}>{list.map((f) => <div key={`${f.accountId}${f.instanceId}${f.resource}`}><div className="xp-row" style={{ marginBottom: 6 }}><Link to={ctx.link(xamPulseHostPath(f.instanceId, f.accountId))}>{f.instanceId} · {f.resource}</Link><span className="xp-spacer" /><TonePill tone={f.r2 >= 0.8 ? 'ok' : f.r2 >= 0.5 ? 'warn' : 'nodata'}>{f.r2 >= 0.8 ? 'High confidence' : f.r2 >= 0.5 ? 'Limited confidence' : 'Low confidence'} · r² {f.r2.toFixed(2)}</TonePill></div><ForecastFan f={f} /></div>)}</div>
            )}
          </QueryBoundary>
        </Card>
      </div>
    </>
  );
};

export default InsightsTab;
