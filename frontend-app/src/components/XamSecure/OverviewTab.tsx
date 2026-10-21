import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import { Link } from 'react-router-dom';
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid, Legend as ReLegend } from 'recharts';
import type {
  XamSecureSummary, IssueSummary, PageResp, ThreatDetection, ThreatStoriesSummary, VulnerabilityDashboard,
} from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import TrendDelta from './TrendDelta';
import LightTooltip from './ui/LightTooltip';
import ChartGradients, { areaGradientId } from './ui/ChartGradients';
import ThreatFunnel from './ui/ThreatFunnel';
import TrendBars from './ui/TrendBars';
import MiniFlowSVG from './ui/MiniFlowSVG';
import CountUp from './ui/CountUp';
import RevealCard from './ui/RevealCard';
import { usePrefersReducedMotion } from './ui/usePrefersReducedMotion';

/** Upwind severity palette (matches the Vulnerabilities tab). */
const SEV_COLOR = { CRITICAL: '#dc2626', HIGH: '#f97316', OTHER: '#3b82f6' } as const;

interface Props {
  summary: XamSecureSummary | null;
  issues: IssueSummary[] | null;
  threats: PageResp<ThreatDetection> | null;
  stories: ThreatStoriesSummary | null;
  /** Opens the threat-story detail panel. */
  onOpenStory?: (storyId: string) => void;
  vulnDashboard: VulnerabilityDashboard | null;
  isLoading: boolean;
}

/** Panel shell matching the rest of XamSecure. */
const Panel: React.FC<{ title: string; action?: React.ReactNode; children: React.ReactNode; className?: string }> =
  ({ title, action, children, className }) => (
    <div className={`bg-white border border-gray-200 rounded-xl shadow-sm flex flex-col ${className ?? ''}`}>
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{title}</p>
        {action}
      </div>
      <div className="p-5 flex-1">{children}</div>
    </div>
  );

const AllLink: React.FC<{ to: string; label?: string }> = ({ to, label = 'View all' }) => (
  <Link to={to} className="text-[11px] font-semibold text-gray-500 hover:text-gray-900">{label}</Link>
);

/** Last-7-day date buckets (oldest → newest). */
function last7Days(): { key: string; label: string }[] {
  const out: { key: string; label: string }[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    out.push({
      key: d.toISOString().slice(0, 10),
      label: d.toLocaleDateString(undefined, { month: 'short', day: '2-digit' }),
    });
  }
  return out;
}

const OverviewTab: React.FC<Props> = ({
  summary, issues, threats, stories, vulnDashboard, isLoading, onOpenStory,
}) => {
  const reducedMotion = usePrefersReducedMotion();
  const days = useMemo(() => last7Days(), []);
  const activeStories = stories?.activeCount ?? 0;
  const storyList = stories?.stories ?? [];

  // 7-day change in total vulnerability findings, taken from the same cumulative series the
  // chart plots so the number and the shape can never disagree. Needs both ends to exist.
  const vulnTrend = useMemo(() => {
    const series = vulnDashboard?.severityOverTime ?? [];
    if (series.length < 2) return null;
    const sum = (row: Record<string, number | string>) =>
      ['CRITICAL', 'HIGH', 'OTHER'].reduce((a, k) => a + (Number(row[k]) || 0), 0);
    return { from: sum(series[0]), to: sum(series[series.length - 1]) };
  }, [vulnDashboard]);

  // Findings-by-severity from the grouped Issues (sums to total issues, like Upwind).
  const findingsBySeverity = useMemo(() => {
    const acc: Record<string, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
    (issues ?? []).forEach((i) => {
      const s = (i.severity ?? '').toUpperCase();
      if (s in acc) acc[s] += 1;
    });
    return acc;
  }, [issues]);
  const totalIssues = issues?.length ?? 0;

  const topFindings = useMemo(() => (issues ?? []).slice(0, 6), [issues]);

  // Threat funnel + detections over time from the threat feed.
  const threatDetections = threats?.totalElements ?? 0;
  const threatEvents = useMemo(
    () => (threats?.content ?? []).reduce((a, t) => a + (t.occurrenceCount ?? 1), 0),
    [threats]);

  // Cumulative findings as-of each day (last 7 days): a finding counts toward every
  // day on/after the day it was first seen, so the standing backlog is reflected each
  // day instead of leaving the chart empty when few findings are new in the window.
  // Matches the Configurations dashboard and the vulnerability "by severity" trend.
  const findingsOverTime = useMemo(() => {
    return days.map((d) => {
      let count = 0;
      (issues ?? []).forEach((i) => {
        if (!i.firstSeen) { count += 1; return; } // unknown first-seen → pre-existing baseline
        const key = new Date(i.firstSeen).toISOString().slice(0, 10);
        if (key <= d.key) count += 1;
      });
      return { label: d.label, value: count };
    });
  }, [issues, days]);

  // 7-day change in the open-issue backlog, read off the same cumulative series the chart
  // plots so the delta and the bars always agree.
  const issuesTrend = useMemo(() => {
    if (findingsOverTime.length < 2) return null;
    return {
      from: findingsOverTime[0].value,
      to: findingsOverTime[findingsOverTime.length - 1].value,
    };
  }, [findingsOverTime]);

  const threatFeed = useMemo(() => (threats?.content ?? []).slice(0, 3), [threats]);

  if (isLoading && !summary && !issues) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading security overview...</div>;
  }

  return (
    <div className="space-y-4">
      {/* Signature data-flow banner (decorative, static under reduced motion) */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm px-5 py-2 overflow-hidden">
        <MiniFlowSVG />
      </div>

      {/* Row 1 — Findings summary · Top findings */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <RevealCard>
        <Panel title="Security issues findings summary" action={<AllLink to="/xamsecure/issues" label="All findings" />}>
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="py-2">Severity</th>
                <th className="py-2 text-right">Findings</th>
              </tr>
            </thead>
            <tbody>
              {SEVERITY_ORDER.map((sev) => (
                <tr key={sev} className="border-b border-gray-50 last:border-0">
                  <td className="py-2.5"><SeverityChip severity={sev} /></td>
                  <td className="py-2.5 text-right font-bold text-gray-900">{findingsBySeverity[sev] ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-3 text-[10px] text-gray-400">{totalIssues} total issues</p>
        </Panel>
        </RevealCard>

        <RevealCard delay={0.06}>
        <Panel title="Top security issues findings" action={<AllLink to="/xamsecure/issues" label="All findings" />}>
          <div className="space-y-0.5">
            <div className="flex items-center justify-between text-[10px] uppercase text-gray-400 pb-1.5 border-b border-gray-100">
              <span>Finding</span><span>Resources</span>
            </div>
            {topFindings.map((f) => (
              <div key={f.check} className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
                <span className="flex items-center gap-2 min-w-0">
                  <SeverityChip severity={f.severity} />
                  <span className="text-xs text-gray-800 truncate">{f.title}</span>
                </span>
                <span className="text-xs font-bold text-gray-700 pl-2">{f.resourceCount}</span>
              </div>
            ))}
            {topFindings.length === 0 && (
              <p className="py-6 text-center text-xs text-gray-400">No open issues.</p>
            )}
          </div>
        </Panel>
        </RevealCard>
      </div>

      {/* Row 2 — Active stories · Threat funnel · Detections over time */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <RevealCard>
        <Panel title="Active threat stories">
          {/* The stories themselves are the point, so list them as buttons that open the
              detail panel. The bare count told you something was wrong with no way in. */}
          {storyList.length > 0 ? (
            <div className="py-2 space-y-1.5">
              {storyList.slice(0, 4).map((st) => (
                <button key={st.id} type="button"
                        onClick={() => st.id && onOpenStory?.(st.id)}
                        disabled={!st.id || !onOpenStory}
                        className="w-full text-left rounded-lg border border-gray-200 p-2.5
                                   hover:border-gray-400 hover:bg-gray-50 transition-colors
                                   disabled:cursor-default disabled:hover:border-gray-200
                                   disabled:hover:bg-transparent
                                   focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
                  <div className="flex items-start justify-between gap-2">
                    <p className="text-[11px] font-semibold text-gray-800 leading-snug line-clamp-2">
                      {st.title ?? 'Untitled story'}
                    </p>
                    <SeverityChip severity={st.severity} />
                  </div>
                  <p className="mt-1 flex items-center gap-1.5 text-[10px] text-gray-400">
                    {st.resourceName && <span className="truncate">{st.resourceName}</span>}
                    {st.status && <span className="uppercase">{st.status}</span>}
                    <Icon icon="mdi:chevron-right" width={12} className="ml-auto flex-shrink-0" />
                  </p>
                </button>
              ))}
              {storyList.length > 4 && (
                <p className="pt-1 text-[10px] text-gray-400">
                  +{storyList.length - 4} more on the Threats tab
                </p>
              )}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center text-center py-6">
              <CountUp value={activeStories} className="text-6xl font-extrabold text-gray-900" />
              <p className="mt-3 text-sm font-bold text-gray-700">Active threat stories</p>
              <p className="mt-1 text-[11px] text-gray-400">
                {activeStories === 0
                  ? 'No active stories in your environment.'
                  : 'Correlated attack stories.'}
              </p>
            </div>
          )}
        </Panel>
        </RevealCard>

        <RevealCard delay={0.06}>
        <Panel title="Noise to signal threat funnel" action={<AllLink to="/xamsecure/threats" label="All threats" />}>
          <ThreatFunnel events={threatEvents} detections={threatDetections} stories={activeStories} />
        </Panel>
        </RevealCard>

        <RevealCard delay={0.12}>
        <Panel title="Vulnerability findings by severity">
          <div className="flex items-center justify-between gap-2 mb-2">
            <p className="text-[10px] text-gray-400">Last 7 days</p>
            {vulnTrend && <TrendDelta from={vulnTrend.from} to={vulnTrend.to} />}
          </div>
          {(vulnDashboard?.severityOverTime ?? []).length === 0 ? (
            <div className="py-10 text-center text-xs text-gray-400">No vulnerability data.</div>
          ) : (
            <div style={{ width: '100%', height: 150 }}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={vulnDashboard?.severityOverTime ?? []} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                  <ChartGradients />
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(15,23,42,0.06)" vertical={false} />
                  <XAxis dataKey="date" tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} width={34} />
                  <Tooltip content={<LightTooltip />} cursor={{ stroke: 'rgba(15,23,42,0.12)', strokeWidth: 1 }} />
                  <ReLegend wrapperStyle={{ fontSize: 10 }} />
                  <Area type="monotone" dataKey="CRITICAL" name="Critical" stackId="1" stroke={SEV_COLOR.CRITICAL} strokeWidth={1.5} fill={`url(#${areaGradientId('CRITICAL')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                  <Area type="monotone" dataKey="HIGH" name="High" stackId="1" stroke={SEV_COLOR.HIGH} strokeWidth={1.5} fill={`url(#${areaGradientId('HIGH')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                  <Area type="monotone" dataKey="OTHER" name="Other" stackId="1" stroke={SEV_COLOR.OTHER} strokeWidth={1.5} fill={`url(#${areaGradientId('OTHER')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>
        </RevealCard>
      </div>

      {/* Row 3 — Findings over time · New issues · Threat feed */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <RevealCard>
        <Panel title="Findings over time"
               action={issuesTrend
                 ? <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" />
                 : undefined}>
          <p className="text-[10px] text-gray-400 mb-2">Cumulative open issues · last 7 days</p>
          <TrendBars data={findingsOverTime} />
        </Panel>
        </RevealCard>

        <RevealCard delay={0.06}>
        <Panel title="New security issues">
          <div className="flex items-center justify-around py-8">
            <FunnelStat label="Critical" value={findingsBySeverity.CRITICAL ?? 0} color="#dc2626" />
            <FunnelStat label="High" value={findingsBySeverity.HIGH ?? 0} color="#f97316" />
            <FunnelStat label="Other" value={(findingsBySeverity.MEDIUM ?? 0) + (findingsBySeverity.LOW ?? 0)} color="#3b82f6" />
          </div>
        </Panel>
        </RevealCard>

        <RevealCard delay={0.12}>
        <Panel title={`Threat feed highlights (${threatFeed.length})`}
               action={<AllLink to="/xamsecure/threats" label="All feed items" />}>
          <div className="space-y-0.5">
            {threatFeed.map((t) => (
              <div key={t.upwindId} className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
                <span className="flex items-center gap-2 min-w-0">
                  <SeverityChip severity={t.severity} />
                  <span className="text-xs text-gray-800 truncate">{t.title ?? '—'}</span>
                </span>
                <span className="text-[10px] text-gray-400 pl-2 whitespace-nowrap">
                  {t.resourceName ?? t.resourceType ?? '—'}
                </span>
              </div>
            ))}
            {threatFeed.length === 0 && (
              <p className="py-6 text-center text-xs text-gray-400">No recent threat detections.</p>
            )}
          </div>
        </Panel>
        </RevealCard>
      </div>

      {/* Data freshness */}
      <RevealCard>
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Data Freshness</p>
        <div className="space-y-1.5">
          {(summary?.syncState ?? []).map((s) => (
            <div key={s.domain} className="flex items-center justify-between text-xs">
              <span className="font-medium text-gray-700">{s.domain}</span>
              <span className={s.lastError ? 'text-gray-900 font-semibold' : 'text-gray-400'}>
                {s.lastError
                  ? `Sync issue — showing last data (${s.lastError})`
                  : s.lastSuccessAt
                    ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                    : 'Not synced yet'}
              </span>
            </div>
          ))}
          {(summary?.syncState ?? []).length === 0 && (
            <p className="text-xs text-gray-400">Sync has not run yet.</p>
          )}
        </div>
      </div>
      </RevealCard>
    </div>
  );
};

const FunnelStat: React.FC<{ label: string; value: number; color?: string }> = ({ label, value, color }) => (
  <div className="text-center">
    <CountUp value={value} className="text-3xl font-extrabold block" style={{ color: color ?? '#111827' }} />
    <p className="mt-1 text-[10px] font-semibold uppercase tracking-wide text-gray-400">{label}</p>
  </div>
);

export default OverviewTab;
