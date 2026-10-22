import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import { Link } from 'react-router-dom';
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid, Legend as ReLegend } from 'recharts';
import type {
  XamSecureSummary, IssueSummary, PageResp, ThreatDetection, ThreatStoriesSummary, VulnerabilityDashboard,
} from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import TrendDelta from './TrendDelta';
import LightTooltip from './ui/LightTooltip';
import ChartGradients, { areaGradientId } from './ui/ChartGradients';
import ThreatFunnel from './ui/ThreatFunnel';
import TrendBars from './ui/TrendBars';
import CountUp from './ui/CountUp';
import RevealCard from './ui/RevealCard';
import SectionHeader from './ui/SectionHeader';
import SeverityDot from './ui/SeverityDot';
import SeverityPill from './ui/SeverityPill';
import SeverityBarStrip from './ui/SeverityBarStrip';
import { usePrefersReducedMotion } from './ui/usePrefersReducedMotion';
import {
  CHART, TEXT, HAIRLINE, HAIRLINE_SOFT, ACCENT, SHADOW, SURFACE, SEVERITY,
  panelStyle, focalPanelStyle,
} from './ui/tokens';

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

/** Quiet uppercase sub-title for a panel/column, on soft-gray surfaces (AA muted). */
const PanelLabel: React.FC<{ children: React.ReactNode; className?: string }> = ({ children, className }) => (
  <p
    className={`text-[11px] font-semibold uppercase tracking-[0.08em] ${className ?? ''}`}
    style={{ color: TEXT.muted }}
  >
    {children}
  </p>
);

const AllLink: React.FC<{ to: string; label?: string }> = ({ to, label = 'View all' }) => (
  <Link
    to={to}
    className="text-[11px] font-semibold rounded hover:underline
               focus:outline-none focus-visible:ring-2 focus-visible:ring-slate-900"
    style={{ color: TEXT.muted }}
  >
    {label}
  </Link>
);

/**
 * A borderless, shadowless KPI tile that sits on the soft-gray posture panel. Transparent fill,
 * separated from its neighbors by a hairline in the grid — stays airy. CountUp uses its default
 * 900ms so hero + KPI figures finish counting together.
 */
const KpiTile: React.FC<{
  caption: string;
  value: number;
  numberColor?: string;
  meta?: React.ReactNode;
}> = ({ caption, value, numberColor, meta }) => (
  <div className="flex flex-col gap-1.5 px-5 py-4">
    <PanelLabel>{caption}</PanelLabel>
    <CountUp
      value={value}
      className="font-mono tabular-nums text-3xl md:text-4xl font-bold tracking-tight"
      style={{ color: numberColor ?? TEXT.strong }}
    />
    {meta && <div className="text-xs" style={{ color: TEXT.muted }}>{meta}</div>}
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
    return <div className="text-center py-16 text-sm" style={{ color: TEXT.muted }}>Loading security overview...</div>;
  }

  return (
    <div className="space-y-12">
      {/* ── PAGE HEADER — plain text on white, no band, no gradient, no topology ── */}
      <header>
        <h1 className="text-xl font-semibold" style={{ color: TEXT.strong }}>Security overview</h1>
        <p className="mt-1 text-sm" style={{ color: TEXT.muted }}>
          Posture, threats and vulnerabilities across your estate
        </p>
      </header>

      {/* ── SECTION — SECURITY POSTURE ──────────────────────────────────────────────── */}
      <section>
        <SectionHeader icon="mdi:shield-check-outline" title="Security posture" accent={ACCENT.green} chipBg={ACCENT.greenSoft} />
        <div className="mt-5">
          <RevealCard>
            <div className="rounded-2xl p-5 md:p-6" style={panelStyle}>
              <div className="grid grid-cols-1 lg:grid-cols-4 gap-6 items-stretch">
                {/* Focal-hero well — totalIssues */}
                <div className="lg:col-span-1 rounded-xl p-6 flex flex-col items-start justify-center" style={focalPanelStyle}>
                  <span
                    className="inline-flex items-center justify-center rounded-full mb-4"
                    style={{ width: 44, height: 44, background: ACCENT.greenSoft }}
                  >
                    <Icon icon="mdi:shield-check-outline" width={24} style={{ color: ACCENT.green }} aria-hidden="true" />
                  </span>
                  <CountUp
                    value={totalIssues}
                    className="font-mono tabular-nums text-6xl md:text-7xl font-bold tracking-tight"
                    style={{ color: TEXT.strong }}
                  />
                  <p className="mt-2 text-[11px] uppercase tracking-wide" style={{ color: TEXT.muted }}>Total issues</p>
                  {issuesTrend && (
                    <div className="mt-2">
                      <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" />
                    </div>
                  )}
                </div>

                {/* Right: 3-up KPI tiles + Findings-by-severity dots/pills + mini bar-strip */}
                <div className="lg:col-span-3 flex flex-col">
                  <div className="grid grid-cols-1 sm:grid-cols-3 divide-y sm:divide-y-0 sm:divide-x"
                       style={{ borderColor: HAIRLINE_SOFT }}>
                    <div style={{ borderColor: HAIRLINE_SOFT }}>
                      <KpiTile
                        caption="Critical + High"
                        value={criticalHigh}
                        numberColor={SEVERITY.CRITICAL.dot}
                        meta={
                          <span className="tabular-nums">
                            {(findingsBySeverity.CRITICAL ?? 0).toLocaleString()} critical ·{' '}
                            {(findingsBySeverity.HIGH ?? 0).toLocaleString()} high
                          </span>
                        }
                      />
                    </div>
                    <div style={{ borderColor: HAIRLINE_SOFT }}>
                      <KpiTile
                        caption="Active stories"
                        value={activeStories}
                        meta={<span>correlated attacks</span>}
                      />
                    </div>
                    <div style={{ borderColor: HAIRLINE_SOFT }}>
                      <KpiTile
                        caption="Threat detections"
                        value={threatDetections}
                        meta={<span className="tabular-nums">{threatEvents.toLocaleString()} events</span>}
                      />
                    </div>
                  </div>

                  {/* Findings by severity — dots + count pills, then the mini bar-strip (once). */}
                  <div className="mt-5 pt-5 px-5" style={{ borderTop: `1px solid ${HAIRLINE_SOFT}` }}>
                    <PanelLabel className="mb-3">Findings by severity</PanelLabel>
                    <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
                      {SEVERITY_ORDER.map((sev) => (
                        <span key={sev} className="inline-flex items-center gap-2">
                          <SeverityDot severity={sev} />
                          <SeverityPill severity={sev} count={findingsBySeverity[sev] ?? 0} />
                        </span>
                      ))}
                    </div>
                    <div className="mt-4">
                      <SeverityBarStrip counts={findingsBySeverity} />
                    </div>
                    <p className="mt-3 text-xs tabular-nums" style={{ color: TEXT.muted }}>
                      {totalIssues.toLocaleString()} total issues
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </RevealCard>
        </div>
      </section>

      {/* ── SECTION — SECURITY ISSUES ───────────────────────────────────────────────── */}
      <section>
        <SectionHeader icon="mdi:file-alert-outline" title="Security issues" accent="#64748B" chipBg="#F7F8FA" />
        <div className="mt-5 grid grid-cols-1 lg:grid-cols-3 gap-6 items-stretch">
          {/* Findings over time */}
          <RevealCard>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <div className="flex items-center justify-between gap-2">
                <PanelLabel>Findings over time</PanelLabel>
                {issuesTrend && <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" />}
              </div>
              <p className="mt-1 text-[11px]" style={{ color: TEXT.muted }}>Cumulative open issues · last 7 days</p>
              <div className="flex-1 flex items-end mt-3">
                <TrendBars data={findingsOverTime} />
              </div>
            </div>
          </RevealCard>

          {/* Vulnerability findings by severity (Recharts stacked area) */}
          <RevealCard delay={0.06}>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <div className="flex items-center justify-between gap-2">
                <PanelLabel>Vulnerability findings by severity</PanelLabel>
                {vulnTrend && <TrendDelta from={vulnTrend.from} to={vulnTrend.to} />}
              </div>
              <p className="mt-1 text-[11px]" style={{ color: TEXT.muted }}>Last 7 days</p>
              {severityOverTime.length === 0 ? (
                <div className="flex-1 flex items-center justify-center py-10 text-xs" style={{ color: TEXT.muted }}>
                  No vulnerability data.
                </div>
              ) : (
                <div className="flex-1 mt-3" style={{ width: '100%', minHeight: 180 }}>
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={severityOverTime} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                      <ChartGradients />
                      <CartesianGrid strokeDasharray="4 4" stroke={HAIRLINE} vertical={false} />
                      <XAxis dataKey="date" tick={{ fontSize: 10, fill: TEXT.faint }} axisLine={false} tickLine={false} />
                      <YAxis tick={{ fontSize: 10, fill: TEXT.faint }} axisLine={false} tickLine={false} width={34} />
                      <Tooltip content={<LightTooltip />} cursor={{ stroke: HAIRLINE, strokeWidth: 1 }} />
                      <ReLegend wrapperStyle={{ fontSize: 11, paddingTop: 8, color: TEXT.muted }} />
                      <Area type="monotone" dataKey="CRITICAL" name="Critical" stackId="1" stroke={CHART.areaStroke.CRITICAL} strokeWidth={1.5} fill={`url(#${areaGradientId('CRITICAL')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                      <Area type="monotone" dataKey="HIGH" name="High" stackId="1" stroke={CHART.areaStroke.HIGH} strokeWidth={1.5} fill={`url(#${areaGradientId('HIGH')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                      <Area type="monotone" dataKey="OTHER" name="Other" stackId="1" stroke={CHART.areaStroke.OTHER} strokeWidth={1.5} fill={`url(#${areaGradientId('OTHER')})`} isAnimationActive={!reducedMotion} animationDuration={600} animationEasing="ease-out" />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </div>
          </RevealCard>

          {/* Issues recap — totalIssues + criticalHigh KPI recap (balances the 3-col grid) */}
          <RevealCard delay={0.12}>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <PanelLabel>Issues recap</PanelLabel>
              <div className="flex-1 flex flex-col justify-center gap-6 mt-3">
                <div>
                  <p className="text-[11px] uppercase tracking-wide" style={{ color: TEXT.muted }}>Total issues</p>
                  <CountUp
                    value={totalIssues}
                    className="font-mono tabular-nums text-3xl md:text-4xl font-bold tracking-tight"
                    style={{ color: TEXT.strong }}
                  />
                </div>
                <div>
                  <p className="text-[11px] uppercase tracking-wide" style={{ color: TEXT.muted }}>Critical + High</p>
                  <CountUp
                    value={criticalHigh}
                    className="font-mono tabular-nums text-3xl md:text-4xl font-bold tracking-tight"
                    style={{ color: SEVERITY.CRITICAL.dot }}
                  />
                </div>
              </div>
            </div>
          </RevealCard>

          {/* Top security issues findings — full width */}
          <div className="lg:col-span-3">
            <RevealCard>
              <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
                <div className="flex items-center justify-between">
                  <PanelLabel>Top security issues findings</PanelLabel>
                  <AllLink to="/xamsecure/issues" label="All findings" />
                </div>
                <div className="flex-1 mt-3">
                  <div className="flex items-center justify-between pb-2" style={{ borderBottom: `1px solid ${HAIRLINE_SOFT}` }}>
                    <PanelLabel>Finding</PanelLabel>
                    <PanelLabel>Resources</PanelLabel>
                  </div>
                  {topFindings.map((f) => (
                    <div key={f.check} className="flex items-center justify-between py-3" style={{ borderBottom: `1px solid ${HAIRLINE_SOFT}` }}>
                      <span className="flex items-center gap-2.5 min-w-0">
                        <SeverityDot severity={f.severity} />
                        <span className="text-sm truncate" style={{ color: TEXT.body }}>{f.title}</span>
                      </span>
                      <span
                        className="inline-flex items-center justify-center rounded-full px-2 py-0.5 text-[11px] font-semibold font-mono tabular-nums pl-2 ml-2 flex-shrink-0"
                        style={{ background: SEVERITY.UNKNOWN.pillBg, color: TEXT.body }}
                      >
                        {(f.resourceCount ?? 0).toLocaleString()}
                      </span>
                    </div>
                  ))}
                  {topFindings.length === 0 && (
                    <p className="py-6 text-center text-sm" style={{ color: TEXT.muted }}>No open issues.</p>
                  )}
                </div>
              </div>
            </RevealCard>
          </div>
        </div>
      </section>

      {/* ── SECTION — THREATS ───────────────────────────────────────────────────────── */}
      <section>
        <SectionHeader icon="mdi:radar" title="Threats" accent="#FB7185" chipBg="#FFF1F2" />
        <div className="mt-5 grid grid-cols-1 lg:grid-cols-3 gap-6 items-stretch">
          {/* Noise to signal threat funnel */}
          <RevealCard>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <div className="flex items-center justify-between">
                <PanelLabel>Noise to signal threat funnel</PanelLabel>
                <AllLink to="/xamsecure/threats" label="All threats" />
              </div>
              <div className="flex-1 flex items-center mt-3">
                <ThreatFunnel events={threatEvents} detections={threatDetections} stories={activeStories} />
              </div>
            </div>
          </RevealCard>

          {/* Active threat stories */}
          <RevealCard delay={0.06}>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <PanelLabel className="mb-3">Active threat stories</PanelLabel>
              {storyList.length > 0 ? (
                <div className="flex-1 space-y-2">
                  {storyList.slice(0, 4).map((st) => {
                    const clickable = Boolean(st.id && onOpenStory);
                    return (
                      <button
                        key={st.id} type="button"
                        onClick={() => st.id && onOpenStory?.(st.id)}
                        disabled={!clickable}
                        className="w-full text-left rounded-xl p-3 transition-shadow duration-200
                                   enabled:motion-safe:hover:-translate-y-0.5 enabled:motion-safe:transition-transform
                                   enabled:hover:[box-shadow:0_4px_16px_-6px_rgba(15,23,42,0.10)]
                                   enabled:focus-visible:[box-shadow:0_4px_16px_-6px_rgba(15,23,42,0.10)]
                                   disabled:cursor-default
                                   focus:outline-none focus-visible:ring-2 focus-visible:ring-slate-900"
                        style={{ background: SURFACE.card, border: `1px solid ${HAIRLINE}`, boxShadow: SHADOW.none }}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <p className="text-sm font-semibold leading-snug line-clamp-2" style={{ color: TEXT.body }}>
                            {st.title ?? 'Untitled story'}
                          </p>
                          <SeverityDot severity={st.severity} className="flex-shrink-0" />
                        </div>
                        <p className="mt-1.5 flex items-center gap-1.5 text-xs" style={{ color: TEXT.muted }}>
                          {st.resourceName && <span className="truncate">{st.resourceName}</span>}
                          {st.status && <span className="uppercase">{st.status}</span>}
                          <Icon icon="mdi:chevron-right" width={13} className="ml-auto flex-shrink-0" aria-hidden="true" />
                        </p>
                      </button>
                    );
                  })}
                  {storyList.length > 4 && (
                    <p className="pt-1 text-xs" style={{ color: TEXT.muted }}>
                      +{storyList.length - 4} more on the Threats tab
                    </p>
                  )}
                </div>
              ) : (
                <div className="flex-1 flex flex-col items-center justify-center text-center py-6">
                  <CountUp
                    value={activeStories}
                    className="font-mono tabular-nums text-5xl font-bold tracking-tight"
                    style={{ color: TEXT.strong }}
                  />
                  <p className="mt-3 text-sm font-semibold" style={{ color: TEXT.body }}>Active threat stories</p>
                  <p className="mt-1 text-xs" style={{ color: TEXT.muted }}>
                    {activeStories === 0
                      ? 'No active stories in your environment.'
                      : 'Correlated attack stories.'}
                  </p>
                </div>
              )}
            </div>
          </RevealCard>

          {/* Threat feed highlights */}
          <RevealCard delay={0.12}>
            <div className="rounded-2xl p-5 flex flex-col h-full" style={panelStyle}>
              <div className="flex items-center justify-between">
                <PanelLabel>{`Threat feed highlights (${threatFeed.length})`}</PanelLabel>
                <AllLink to="/xamsecure/threats" label="All feed items" />
              </div>
              <div className="flex-1 mt-3">
                {threatFeed.map((t) => (
                  <div key={t.upwindId} className="flex items-center justify-between py-3" style={{ borderBottom: `1px solid ${HAIRLINE_SOFT}` }}>
                    <span className="flex items-center gap-2.5 min-w-0">
                      <SeverityDot severity={t.severity} />
                      <span className="text-sm truncate" style={{ color: TEXT.body }}>{t.title ?? '—'}</span>
                    </span>
                    <span className="text-xs pl-2 whitespace-nowrap flex-shrink-0" style={{ color: TEXT.muted }}>
                      {t.resourceName ?? t.resourceType ?? '—'}
                    </span>
                  </div>
                ))}
                {threatFeed.length === 0 && (
                  <p className="py-6 text-center text-sm" style={{ color: TEXT.muted }}>No recent threat detections.</p>
                )}
              </div>
            </div>
          </RevealCard>
        </div>
      </section>

      {/* ── FOOTER — DATA FRESHNESS (set apart by a hairline rule) ───────────────────── */}
      <section className="pt-8" style={{ borderTop: `1px solid ${HAIRLINE}` }}>
        <PanelLabel className="mb-4">Data freshness</PanelLabel>
        <div className="space-y-3">
          {(summary?.syncState ?? []).map((s) => (
            <div key={s.domain} className="flex items-center justify-between gap-3 py-2" style={{ borderBottom: `1px solid ${HAIRLINE_SOFT}` }}>
              <span className="text-sm font-medium" style={{ color: TEXT.body }}>{s.domain}</span>
              {s.lastError ? (
                // Sync failure shown as a calm amber title-only pill — the raw backend
                // exception/stack stays ONLY in the title tooltip, never in the card body.
                // Text is amber-700 #B45309 (AA 4.84:1 on amber-50), not the failing amber-600.
                <span
                  className="flex items-center gap-1.5 font-medium min-w-0 rounded-full px-2.5 py-1"
                  style={{ background: '#FFFBEB', border: '1px solid #FDE68A', color: '#B45309' }}
                  title={s.lastError}
                >
                  <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0" aria-hidden="true" />
                  <span className="truncate text-xs">
                    Sync issue — showing last data
                    {s.lastSuccessAt
                      ? ` (last ok ${new Date(s.lastSuccessAt).toLocaleString()})`
                      : ''}
                  </span>
                </span>
              ) : (
                <span className="flex items-center gap-1.5 text-xs" style={{ color: TEXT.muted }}>
                  <Icon icon="mdi:check-circle-outline" width={14} style={{ color: ACCENT.green }} className="flex-shrink-0" aria-hidden="true" />
                  {s.lastSuccessAt
                    ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                    : 'Not synced yet'}
                </span>
              )}
            </div>
          ))}
          {(summary?.syncState ?? []).length === 0 && (
            <p className="text-sm" style={{ color: TEXT.muted }}>Sync has not run yet.</p>
          )}
        </div>
      </section>
    </div>
  );
};

export default OverviewTab;
