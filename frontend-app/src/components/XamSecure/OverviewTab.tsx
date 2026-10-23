import { useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';
import type Highcharts from 'highcharts';
import type {
  XamSecureSummary, IssueSummary, PageResp, ThreatDetection, ThreatStoriesSummary, VulnerabilityDashboard,
} from '../../types/xamsecure';
import TrendDelta from './TrendDelta';
import './ui/tokens.css';
import DashboardPanel from './ui/DashboardPanel';
import SectionHeader from './ui/SectionHeader';
import KpiHero from './ui/KpiHero';
import SeverityPillBars from './ui/SeverityPillBars';
import SeverityGradientBar from './ui/SeverityGradientBar';
import MiniFeedRow, { RowChevron } from './ui/MiniFeedRow';
import CountChip from './ui/CountChip';
import FunnelStream from './ui/FunnelStream';
import SeverityAreaChart from './ui/SeverityAreaChart';
import FindingsOverTimeBars from './ui/FindingsOverTimeBars';

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
  const navigate = useNavigate();
  const days = useMemo(() => last7Days(), []);
  const activeStories = stories?.activeCount ?? 0;
  const storyList = stories?.stories ?? [];

  // 7-day change in total vulnerability findings, taken from the same cumulative series the chart
  // plots so the number and the shape can never disagree. Needs both ends to exist.
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

  // Threat funnel + detections from the threat feed.
  const threatDetections = threats?.totalElements ?? 0;
  const threatEvents = useMemo(
    () => (threats?.content ?? []).reduce((a, t) => a + (t.occurrenceCount ?? 1), 0),
    [threats]);

  // Cumulative findings as-of each day (last 7 days): a finding counts toward every day on/after
  // the day it was first seen, so the standing backlog is reflected each day instead of leaving
  // the chart empty when few findings are new in the window.
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

  // 7-day change in the open-issue backlog, read off the same cumulative series the chart plots.
  const issuesTrend = useMemo(() => {
    if (findingsOverTime.length < 2) return null;
    return { from: findingsOverTime[0].value, to: findingsOverTime[findingsOverTime.length - 1].value };
  }, [findingsOverTime]);

  const threatFeed = useMemo(() => (threats?.content ?? []).slice(0, 3), [threats]);

  // ── Area-chart legend wiring: the legend lives in the card header, so it needs a handle to the
  // Highcharts instance. SeverityAreaChart exposes it via onChartReady; we toggle series by index
  // on the fixed [0]=Critical,[1]=High,[2]=Other order. ──
  const chartRef = useRef<Highcharts.Chart | null>(null);
  const [hidden, setHidden] = useState<Set<number>>(new Set());
  const hasVulnData = (vulnDashboard?.severityOverTime ?? []).length > 0;
  const toggleSeries = (i: number) => {
    const next = new Set(hidden);
    const nowVisible = next.has(i);
    if (nowVisible) next.delete(i); else next.add(i);
    chartRef.current?.series[i]?.setVisible(nowVisible, true);
    setHidden(next);
  };

  const maxSev = Math.max(1, ...Object.values(findingsBySeverity));
  const newCols = [
    { sev: 'CRITICAL', value: findingsBySeverity.CRITICAL ?? 0 },
    { sev: 'HIGH', value: findingsBySeverity.HIGH ?? 0 },
    { sev: 'OTHER', value: (findingsBySeverity.MEDIUM ?? 0) + (findingsBySeverity.LOW ?? 0) },
  ];
  const maxNew = Math.max(1, ...newCols.map((c) => c.value));

  if (isLoading && !summary && !issues) {
    return (
      <div className="xs-overview">
        <div className="xs-brand-strip" />
        <div className="xs-grid" style={{ marginTop: 16 }}>
          {[0, 1, 2].map((col) => (
            <div key={col} className="xs-col">
              <div className="xs-skeleton" style={{ height: 36, width: 160 }} />
              <div className="xs-card"><div className="xs-skeleton" style={{ height: '100%' }} /></div>
              <div className="xs-card"><div className="xs-skeleton" style={{ height: '100%' }} /></div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  const SEV_LEGEND = [
    { i: 0, name: 'Critical', color: 'var(--xs-sev-critical)' },
    { i: 1, name: 'High', color: 'var(--xs-sev-high)' },
    { i: 2, name: 'Other', color: 'var(--xs-sev-other)' },
  ];

  return (
    <div className="xs-overview xs-fade-in">
      <div className="xs-brand-strip" />

      <div className="xs-grid" style={{ marginTop: 16 }}>
        {/* ── LEFT — Findings / posture ── */}
        <div className="xs-col">
          <SectionHeader icon="mdi:shield-search" title="Findings" tileBg="var(--xs-red-06)" tileIcon="var(--xs-red-03)" />

          <DashboardPanel title="Findings summary" glyph="mdi:format-list-bulleted" glyphColor="var(--xs-red-03)">
            <SeverityPillBars
              mode="horizontal"
              rows={[
                { sev: 'CRITICAL', value: findingsBySeverity.CRITICAL ?? 0 },
                { sev: 'HIGH', value: findingsBySeverity.HIGH ?? 0 },
                { sev: 'MEDIUM', value: findingsBySeverity.MEDIUM ?? 0 },
                { sev: 'LOW', value: findingsBySeverity.LOW ?? 0 },
              ]}
              max={maxSev}
              caption={`${totalIssues} total issues`}
              onRowClick={(sev) => navigate(`/xamsecure/issues?severity=${sev}`)}
            />
          </DashboardPanel>

          <DashboardPanel
            title="Findings over time"
            glyph="mdi:chart-timeline-variant"
            glyphColor="var(--xs-red-03)"
            action={issuesTrend ? <TrendDelta from={issuesTrend.from} to={issuesTrend.to} label="over 7 days" /> : undefined}
          >
            <p style={{ fontSize: 12, color: 'var(--xs-text-secondary)', margin: '0 0 8px' }}>Cumulative open issues · last 7 days</p>
            <FindingsOverTimeBars data={findingsOverTime} height={96} />
          </DashboardPanel>

          <DashboardPanel
            title="New security issues"
            glyph="mdi:alert-plus-outline"
            glyphColor="var(--xs-pink-03)"
          >
            <p style={{ fontSize: 12, color: 'var(--xs-text-secondary)', margin: '0 0 8px' }}>By severity · open issues</p>
            <SeverityPillBars
              mode="vertical"
              cols={newCols}
              max={maxNew}
              footerValue={totalIssues}
              footerLabel="open issues"
              onColClick={(sev) => navigate(sev === 'OTHER' ? '/xamsecure/issues' : `/xamsecure/issues?severity=${sev}`)}
            />
          </DashboardPanel>
        </div>

        {/* ── CENTER — Security issues ── */}
        <div className="xs-col">
          <SectionHeader icon="mdi:alert-circle-outline" title="Security issues" tileBg="var(--xs-red-06)" tileIcon="var(--xs-red-03)" />

          <DashboardPanel>
            <KpiHero
              icon="mdi:shield-alert-outline"
              value={activeStories}
              label="Active threat stories"
              desc={activeStories === 0 ? 'No active stories in your environment' : 'Correlated attack stories'}
              tone={activeStories === 0 ? 'good' : 'bad'}
              onClick={() => navigate('/xamsecure/threats')}
            >
              {storyList.length > 0 && (
                storyList.slice(0, 4).map((st) => (
                  <MiniFeedRow
                    key={st.id ?? st.title ?? Math.random()}
                    severity={st.severity}
                    title={st.title ?? 'Untitled story'}
                    rightSlot={<RowChevron />}
                    onClick={st.id && onOpenStory ? () => onOpenStory(st.id as string) : undefined}
                    disabled={!st.id || !onOpenStory}
                  />
                ))
              )}
            </KpiHero>
          </DashboardPanel>

          <DashboardPanel title="Top security issues" glyph="mdi:alert-circle-outline" glyphColor="var(--xs-red-03)">
            <SeverityGradientBar
              counts={{
                CRITICAL: findingsBySeverity.CRITICAL ?? 0,
                HIGH: findingsBySeverity.HIGH ?? 0,
                MEDIUM: findingsBySeverity.MEDIUM ?? 0,
                LOW: findingsBySeverity.LOW ?? 0,
              }}
              onSegmentClick={(sev) => navigate(`/xamsecure/issues?severity=${sev}`)}
            />
            {topFindings.length === 0 ? (
              <p style={{ textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)', padding: '24px 0' }}>No open issues.</p>
            ) : (
              topFindings.map((f) => (
                <MiniFeedRow
                  key={f.check}
                  severity={f.severity}
                  title={f.title}
                  rightSlot={<CountChip value={f.resourceCount} variant="resources" />}
                  onClick={() => navigate(`/xamsecure/issues?severity=${(f.severity ?? '').toUpperCase()}`)}
                />
              ))
            )}
          </DashboardPanel>
        </div>

        {/* ── RIGHT — Threats ── */}
        <div className="xs-col">
          <SectionHeader icon="mdi:radar" title="Threats" tileBg="var(--xs-pink-06)" tileIcon="var(--xs-pink-03)" />

          <DashboardPanel title="Noise to signal threat funnel" glyph="mdi:radar" glyphColor="var(--xs-pink-03)">
            <FunnelStream
              events={threatEvents}
              detections={threatDetections}
              stories={activeStories}
              onAllStories={() => navigate('/xamsecure/threats')}
            >
              {threatFeed.length === 0 ? (
                <p style={{ textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)', padding: '12px 0' }}>No recent threat detections.</p>
              ) : (
                threatFeed.map((t) => (
                  <MiniFeedRow
                    key={t.upwindId}
                    severity={t.severity}
                    title={t.title ?? '—'}
                    rightSlot={<span style={{ fontSize: 10, color: 'var(--xs-text-secondary)', whiteSpace: 'nowrap' }}>{t.resourceName ?? t.resourceType ?? '—'}</span>}
                    onClick={() => navigate('/xamsecure/threats')}
                  />
                ))
              )}
            </FunnelStream>
          </DashboardPanel>

          <DashboardPanel
            title="Vulnerability findings by severity"
            glyph="mdi:chart-areaspline"
            glyphColor="var(--xs-pink-03)"
            scrollBody={false}
            action={vulnTrend ? <TrendDelta from={vulnTrend.from} to={vulnTrend.to} /> : undefined}
            legend={hasVulnData ? (
              <div style={{ display: 'flex', gap: 12 }}>
                {SEV_LEGEND.map((s) => {
                  const off = hidden.has(s.i);
                  return (
                    <button
                      key={s.i}
                      type="button"
                      onClick={() => toggleSeries(s.i)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, border: 'none', background: 'transparent', cursor: 'pointer', padding: 0 }}
                    >
                      <span style={{ width: 8, height: 8, borderRadius: '999px', background: off ? 'var(--xs-legend-inactive-text)' : s.color, display: 'inline-block' }} aria-hidden />
                      <span style={{ fontSize: 12, color: off ? 'var(--xs-legend-inactive-text)' : 'var(--xs-text-secondary)' }}>{s.name}</span>
                    </button>
                  );
                })}
              </div>
            ) : undefined}
          >
            <SeverityAreaChart
              data={vulnDashboard?.severityOverTime ?? []}
              height={220}
              onChartReady={(chart) => { chartRef.current = chart; }}
            />
          </DashboardPanel>
        </div>
      </div>

      {/* ── Data freshness footer ── */}
      <div style={{ marginTop: 24, padding: '0 4px' }} className="xs-overview">
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
          <Icon icon="mdi:database-sync-outline" width={16} height={16} color="var(--xs-text-secondary)" aria-hidden />
          <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--xs-text-primary)' }}>Data Freshness</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {(summary?.syncState ?? []).map((s) => (
            <div key={s.domain} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, fontSize: 14 }}>
              <span style={{ fontWeight: 400, color: 'var(--xs-text-primary)', flexShrink: 0 }}>{s.domain}</span>
              {s.lastError ? (
                // Calm amber title-only treatment: short message in the body, the raw backend
                // error/stack lives ONLY in the title tooltip so it can never overflow the row.
                // amber-700 #b45309 passes AA on amber-50 #fffbeb.
                <span
                  title={s.lastError}
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0,
                    padding: '2px 10px', borderRadius: 999,
                    background: '#fffbeb', border: '1px solid #fde68a', color: '#b45309', fontWeight: 500,
                  }}
                >
                  <Icon icon="mdi:alert-circle-outline" width={14} height={14} aria-hidden style={{ flexShrink: 0 }} />
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 12 }}>
                    Sync issue — showing last data
                    {s.lastSuccessAt ? ` (last ok ${new Date(s.lastSuccessAt).toLocaleString()})` : ''}
                  </span>
                </span>
              ) : (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--xs-text-secondary)', fontWeight: 400 }}>
                  <Icon icon="mdi:check-circle-outline" width={14} height={14} color="var(--xs-green-02)" aria-hidden style={{ flexShrink: 0 }} />
                  {s.lastSuccessAt
                    ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                    : 'Not synced yet'}
                </span>
              )}
            </div>
          ))}
          {(summary?.syncState ?? []).length === 0 && (
            <p style={{ fontSize: 14, color: 'var(--xs-text-secondary)' }}>Sync has not run yet.</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default OverviewTab;
