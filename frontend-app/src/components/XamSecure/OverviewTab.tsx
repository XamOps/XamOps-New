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
import { SHADOW, SURFACE, BORDER, HERO_ACCENT_OVERLAY, ACCENT, heroStyle } from './ui/tokens';

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

/**
 * Shared card primitive — the single source of surface styling for the page. A restyle/superset
 * of the old `Panel`: same API (`title`, `action`, `children`, `className`) plus optional
 * `interactive` (hover lift) and `tone` (faint accent top-border). Always `flex flex-col h-full`
 * so it fills its grid cell and equal-height rows hold.
 */
const SurfaceCard: React.FC<{
  title?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  tone?: string;
}> = ({ title, action, children, className, tone }) => (
  <div
    className={`rounded-2xl flex flex-col h-full overflow-hidden ${className ?? ''}`}
    style={{ background: SURFACE.card, border: BORDER.card, boxShadow: SHADOW.rest }}
  >
    {tone && <div style={{ height: 3, background: tone }} />}
    {title && (
      <div className="flex items-center justify-between px-6 py-4 border-b border-[rgba(15,23,42,0.06)]">
        <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-gray-500">{title}</p>
        {action}
      </div>
    )}
    <div className="p-6 flex-1 flex flex-col">{children}</div>
  </div>
);

const AllLink: React.FC<{ to: string; label?: string }> = ({ to, label = 'View all' }) => (
  <Link
    to={to}
    className="text-[11px] font-semibold text-gray-500 hover:text-gray-900 rounded
               focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900"
  >
    {label}
  </Link>
);

/** Thin eyebrow label that gives the page a spine. */
const SectionEyebrow: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-gray-400 mb-3 px-1">{children}</p>
);

/** A single hero KPI stat tile: accent badge + caption, display number, optional meta. */
const KpiTile: React.FC<{
  icon: string;
  caption: string;
  value: number;
  accent: string;
  numberColor?: string;
  meta?: React.ReactNode;
}> = ({ icon, caption, value, accent, numberColor, meta }) => (
  <div
    className="rounded-xl p-4 flex flex-col gap-2"
    style={{ background: SURFACE.card, border: BORDER.card, boxShadow: SHADOW.rest }}
  >
    <div className="flex items-center gap-2.5">
      <span
        className="inline-flex items-center justify-center rounded-full flex-shrink-0"
        style={{ width: 36, height: 36, background: SURFACE.kpiBadge, border: '1px solid rgba(15,23,42,0.05)' }}
      >
        <Icon icon={icon} width={19} style={{ color: accent }} />
      </span>
      <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-gray-500 leading-tight">
        {caption}
      </span>
    </div>
    <CountUp
      value={value}
      className="font-mono tabular-nums text-4xl md:text-5xl font-bold tracking-tight"
      style={{ color: numberColor ?? '#111827' }}
    />
    {meta && <div className="text-xs text-gray-400">{meta}</div>}
  </div>
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
  const criticalHigh = (findingsBySeverity.CRITICAL ?? 0) + (findingsBySeverity.HIGH ?? 0);

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
  const severityOverTime = vulnDashboard?.severityOverTime ?? [];

  if (isLoading && !summary && !issues) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading security overview...</div>;
  }

  return (
    <div className="space-y-8">
      {/* ── HERO BAND: section eyebrow + KPI stat band, with the topology as a soft accent ── */}
      <div
        className="relative overflow-hidden rounded-2xl px-8 py-7"
        style={heroStyle}
      >
        {/* Topology accent — constrained to the right 2/5, aria-hidden, under a floored white
            overlay so no KPI numeral ever drops below AA even where it underlaps. */}
        <div className="hidden lg:block absolute inset-y-0 right-0 w-2/5 pointer-events-none" aria-hidden="true">
          <MiniFlowSVG fill className="h-full" />
          <div className="absolute inset-0" style={{ background: HERO_ACCENT_OVERLAY }} />
        </div>

        <div className="relative z-10">
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-gray-500">Security overview</p>
          <p className="mt-1 text-sm text-gray-500">
            Posture, threats and vulnerabilities across your estate
          </p>

          <div className="mt-6 grid grid-cols-2 lg:grid-cols-4 gap-4 lg:max-w-[62%]">
            <KpiTile
              icon="mdi:shield-alert-outline"
              caption="Total issues"
              value={totalIssues}
              accent={ACCENT.blue}
              meta={issuesTrend
                ? <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" />
                : <span>open findings</span>}
            />
            <KpiTile
              icon="mdi:alert-octagon-outline"
              caption="Critical + High"
              value={criticalHigh}
              accent="#dc2626"
              numberColor="#dc2626"
              meta={
                <span className="tabular-nums">
                  {(findingsBySeverity.CRITICAL ?? 0).toLocaleString()} critical ·{' '}
                  {(findingsBySeverity.HIGH ?? 0).toLocaleString()} high
                </span>
              }
            />
            <KpiTile
              icon="mdi:source-branch"
              caption="Active stories"
              value={activeStories}
              accent={ACCENT.violet}
              meta={<span>correlated attacks</span>}
            />
            <KpiTile
              icon="mdi:radar"
              caption="Threat detections"
              value={threatDetections}
              accent={ACCENT.cyan}
              meta={<span className="tabular-nums">{threatEvents.toLocaleString()} events</span>}
            />
          </div>
        </div>
      </div>

      {/* ── FINDINGS ─────────────────────────────────────────────────────────────────── */}
      <section>
        <SectionEyebrow>Findings</SectionEyebrow>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-stretch">
          {/* Findings by severity */}
          <RevealCard>
            <SurfaceCard title="Findings by severity" action={<AllLink to="/xamsecure/issues" label="All findings" />}>
              <div className="flex-1">
                <div className="flex items-center justify-between text-[11px] uppercase tracking-[0.08em] text-gray-400 pb-2 border-b border-[rgba(15,23,42,0.06)]">
                  <span>Severity</span><span>Findings</span>
                </div>
                {SEVERITY_ORDER.map((sev) => (
                  <div key={sev} className="flex items-center justify-between py-2.5 border-b border-[rgba(15,23,42,0.04)] last:border-0">
                    <SeverityChip severity={sev} />
                    <span className="font-mono tabular-nums text-sm font-semibold text-gray-700">
                      {(findingsBySeverity[sev] ?? 0).toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
              <p className="mt-3 text-xs text-gray-400 tabular-nums">{totalIssues.toLocaleString()} total issues</p>
            </SurfaceCard>
          </RevealCard>

          {/* Findings over time */}
          <RevealCard delay={0.06}>
            <SurfaceCard
              title="Findings over time"
              action={issuesTrend
                ? <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" />
                : undefined}
            >
              <p className="text-[11px] text-gray-400 mb-3">Cumulative open issues · last 7 days</p>
              <div className="flex-1 flex items-end">
                <TrendBars data={findingsOverTime} />
              </div>
            </SurfaceCard>
          </RevealCard>

          {/* Vulnerability findings by severity (Recharts stacked area) */}
          <RevealCard delay={0.12}>
            <SurfaceCard title="Vulnerability findings by severity" action={<AllLink to="/xamsecure/vulnerabilities" label="All vulns" />}>
              <div className="flex items-center justify-between gap-2 mb-3">
                <p className="text-[11px] text-gray-400">Last 7 days</p>
                {vulnTrend && <TrendDelta from={vulnTrend.from} to={vulnTrend.to} />}
              </div>
              {severityOverTime.length === 0 ? (
                <div className="flex-1 flex items-center justify-center py-10 text-xs text-gray-400">
                  No vulnerability data.
                </div>
              ) : (
                <div className="flex-1" style={{ width: '100%', minHeight: 180 }}>
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={severityOverTime} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                      <ChartGradients />
                      <CartesianGrid strokeDasharray="4 4" stroke="rgba(15,23,42,0.06)" vertical={false} />
                      <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                      <YAxis tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={34} />
                      <Tooltip content={<LightTooltip />} cursor={{ stroke: 'rgba(15,23,42,0.12)', strokeWidth: 1 }} />
                      <ReLegend wrapperStyle={{ fontSize: 11, paddingTop: 8 }} />
                      <Area type="monotone" dataKey="CRITICAL" name="Critical" stackId="1" stroke={SEV_COLOR.CRITICAL} strokeWidth={1.5} fill={`url(#${areaGradientId('CRITICAL')})`} isAnimationActive={!reducedMotion} animationDuration={700} animationEasing="ease-out" />
                      <Area type="monotone" dataKey="HIGH" name="High" stackId="1" stroke={SEV_COLOR.HIGH} strokeWidth={1.5} fill={`url(#${areaGradientId('HIGH')})`} isAnimationActive={!reducedMotion} animationDuration={700} animationEasing="ease-out" />
                      <Area type="monotone" dataKey="OTHER" name="Other" stackId="1" stroke={SEV_COLOR.OTHER} strokeWidth={1.5} fill={`url(#${areaGradientId('OTHER')})`} isAnimationActive={!reducedMotion} animationDuration={700} animationEasing="ease-out" />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </SurfaceCard>
          </RevealCard>

          {/* Top security issues findings — full width */}
          <div className="lg:col-span-3">
            <RevealCard>
              <SurfaceCard title="Top security issues findings" action={<AllLink to="/xamsecure/issues" label="All findings" />}>
                <div className="flex-1">
                  <div className="flex items-center justify-between text-[11px] uppercase tracking-[0.08em] text-gray-400 pb-2 border-b border-[rgba(15,23,42,0.06)]">
                    <span>Finding</span><span>Resources</span>
                  </div>
                  {topFindings.map((f) => (
                    <div key={f.check} className="flex items-center justify-between py-2.5 border-b border-[rgba(15,23,42,0.04)] last:border-0">
                      <span className="flex items-center gap-2 min-w-0">
                        <SeverityChip severity={f.severity} />
                        <span className="text-sm text-gray-800 truncate">{f.title}</span>
                      </span>
                      <span className="font-mono tabular-nums text-sm font-semibold text-gray-700 pl-2">
                        {(f.resourceCount ?? 0).toLocaleString()}
                      </span>
                    </div>
                  ))}
                  {topFindings.length === 0 && (
                    <p className="py-6 text-center text-sm text-gray-400">No open issues.</p>
                  )}
                </div>
              </SurfaceCard>
            </RevealCard>
          </div>
        </div>
      </section>

      {/* ── THREATS ──────────────────────────────────────────────────────────────────── */}
      <section>
        <SectionEyebrow>Threats</SectionEyebrow>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-stretch">
          {/* Noise to signal threat funnel */}
          <RevealCard>
            <SurfaceCard title="Noise to signal threat funnel" action={<AllLink to="/xamsecure/threats" label="All threats" />}>
              <div className="flex-1 flex items-center">
                <ThreatFunnel events={threatEvents} detections={threatDetections} stories={activeStories} />
              </div>
            </SurfaceCard>
          </RevealCard>

          {/* Active threat stories */}
          <RevealCard delay={0.06}>
            <SurfaceCard title="Active threat stories">
              {storyList.length > 0 ? (
                <div className="flex-1 space-y-2">
                  {storyList.slice(0, 4).map((st) => (
                    <button
                      key={st.id} type="button"
                      onClick={() => st.id && onOpenStory?.(st.id)}
                      disabled={!st.id || !onOpenStory}
                      className="w-full text-left rounded-xl border border-[rgba(15,23,42,0.08)] p-3
                                 transition-shadow duration-200
                                 motion-safe:hover:-translate-y-0.5 motion-safe:transition-transform
                                 disabled:cursor-default disabled:hover:shadow-none disabled:hover:translate-y-0
                                 focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900"
                      style={{ background: SURFACE.card, boxShadow: SHADOW.rest }}
                      onMouseEnter={(e) => { if (st.id && onOpenStory) e.currentTarget.style.boxShadow = SHADOW.lifted; }}
                      onMouseLeave={(e) => { e.currentTarget.style.boxShadow = SHADOW.rest; }}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <p className="text-sm font-semibold text-gray-800 leading-snug line-clamp-2">
                          {st.title ?? 'Untitled story'}
                        </p>
                        <SeverityChip severity={st.severity} />
                      </div>
                      <p className="mt-1.5 flex items-center gap-1.5 text-xs text-gray-400">
                        {st.resourceName && <span className="truncate">{st.resourceName}</span>}
                        {st.status && <span className="uppercase">{st.status}</span>}
                        <Icon icon="mdi:chevron-right" width={13} className="ml-auto flex-shrink-0" />
                      </p>
                    </button>
                  ))}
                  {storyList.length > 4 && (
                    <p className="pt-1 text-xs text-gray-400">
                      +{storyList.length - 4} more on the Threats tab
                    </p>
                  )}
                </div>
              ) : (
                <div className="flex-1 flex flex-col items-center justify-center text-center py-6">
                  <CountUp
                    value={activeStories}
                    className="font-mono tabular-nums text-5xl font-bold tracking-tight text-gray-900"
                  />
                  <p className="mt-3 text-sm font-semibold text-gray-700">Active threat stories</p>
                  <p className="mt-1 text-xs text-gray-400">
                    {activeStories === 0
                      ? 'No active stories in your environment.'
                      : 'Correlated attack stories.'}
                  </p>
                </div>
              )}
            </SurfaceCard>
          </RevealCard>

          {/* Threat feed highlights */}
          <RevealCard delay={0.12}>
            <SurfaceCard
              title={`Threat feed highlights (${threatFeed.length})`}
              action={<AllLink to="/xamsecure/threats" label="All feed items" />}
            >
              <div className="flex-1">
                {threatFeed.map((t) => (
                  <div key={t.upwindId} className="flex items-center justify-between py-2.5 border-b border-[rgba(15,23,42,0.04)] last:border-0">
                    <span className="flex items-center gap-2 min-w-0">
                      <SeverityChip severity={t.severity} />
                      <span className="text-sm text-gray-800 truncate">{t.title ?? '—'}</span>
                    </span>
                    <span className="text-xs text-gray-400 pl-2 whitespace-nowrap">
                      {t.resourceName ?? t.resourceType ?? '—'}
                    </span>
                  </div>
                ))}
                {threatFeed.length === 0 && (
                  <p className="py-6 text-center text-sm text-gray-400">No recent threat detections.</p>
                )}
              </div>
            </SurfaceCard>
          </RevealCard>
        </div>
      </section>

      {/* ── DATA FRESHNESS ───────────────────────────────────────────────────────────── */}
      <section>
        <SectionEyebrow>Data freshness</SectionEyebrow>
        <RevealCard>
          <SurfaceCard title="Data freshness">
            <div className="flex-1 space-y-2">
              {(summary?.syncState ?? []).map((s) => (
                <div key={s.domain} className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium text-gray-700">{s.domain}</span>
                  {s.lastError ? (
                    // Surface that sync failed as a deliberate amber pill — the raw backend
                    // exception/stack stays ONLY in the title tooltip, never in the card body.
                    <span
                      className="flex items-center gap-1.5 text-amber-600 font-medium min-w-0
                                 bg-amber-50 border border-amber-200 rounded-full px-2.5 py-1"
                      title={s.lastError}
                    >
                      <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0" />
                      <span className="truncate text-xs">
                        Sync issue — showing last data
                        {s.lastSuccessAt
                          ? ` (last ok ${new Date(s.lastSuccessAt).toLocaleString()})`
                          : ''}
                      </span>
                    </span>
                  ) : (
                    <span className="flex items-center gap-1.5 text-xs text-gray-400">
                      <Icon icon="mdi:check-circle-outline" width={14} style={{ color: ACCENT.green }} className="flex-shrink-0" />
                      {s.lastSuccessAt
                        ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                        : 'Not synced yet'}
                    </span>
                  )}
                </div>
              ))}
              {(summary?.syncState ?? []).length === 0 && (
                <p className="text-sm text-gray-400">Sync has not run yet.</p>
              )}
            </div>
          </SurfaceCard>
        </RevealCard>
      </section>
    </div>
  );
};

export default OverviewTab;
