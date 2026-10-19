/**
 * Automation › Activity (UI v3 plan §2 "Activity", §5 owner addition A / FEAT-007).
 *
 * Every change XamOps recorded on this cluster, from the activity feed
 * (`GET /api/eks/automation/activity/{cluster}`), newest first: when, who (a user or XamOps's
 * automatic loop), the workload or HPA, before → after, the result and the restart-free proof. The
 * Feature and Result filters and the search are the server's query (`feature`, `result`, `q`); older
 * entries load on request. The KPIs count the entries loaded, and say so.
 *
 * A backend without the feed (an untyped 404) keeps FEAT-003's view: each workload's latest change
 * from endpoint 1, with a note that the full history needs a newer backend. Endpoint 1 is read only
 * then. Node automation isn't recorded here, and the page says so. The time filter is the server's
 * `from`; the table's CSV export holds the loaded rows. "Changes per day, by result" comes from the
 * per-day summary (FEAT-005), one bar per IST day; a day it didn't record reads "Not recorded yet".
 *
 * The Kubernetes events about scaling, resizing and eviction that the XamOps agent reported are a
 * separate, labelled stream: what the cluster observed, never mixed into XamOps's rows.
 */
import { useEffect, useId, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import useAutomationRead from '../../../../../hooks/useAutomationRead';
import useAutomationActivity, { useActivitySummary, type ActivityFilters } from '../../../../../hooks/useAutomationActivity';
import type { InsightReadState } from '../../../../../hooks/useInsightRead';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { RESULT_WIRE } from '../../../../../api/automationActivityAdapter';
import { ACTIVITY_QUERY_MAX } from '../../../../../api/automationActivityApi';
import type { ActivityEntryView, ActivityReadResult, ActivityResultKey, ActivitySummaryView } from '../../../../../types/automationActivity';
import { formatIST } from '../../../../../utils/timeUtils';
import { K8sDataTable, type ColumnDef } from '../../../../CloudK8s/shared/K8sDataTable';
import { FeatureDot } from '../../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { Bars } from '../../../../CloudK8s/shared/charts/Bars';
import { ChartCard, type ChartEmptyState } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { eventRows, type EventRow } from '../../overview/EventsPanel';
import {
  DAY_MS,
  SCALING_EVENT_REASON,
  activityKpis,
  latestChangeRows,
  todayByResult,
  type ActivitySource,
  type LatestChangeRow,
} from '../automationDerivations';
import { insightCardState } from '../insightCharts';
import { RESULT_BARS, dayRows } from './activityInsights';
import { BUTTON, CARD, CONTROL, FAINT, FOCUS, INK, MUTED, Notice, ReadFailureCard, readFailureText, type ReadFailure } from '../automationUi';
import { LatestChangeTable } from './LatestChangeTable';
import { RootCausePanel } from './RootCausePanel';
import { ACTIVITY_FEED_CAPTION, ActivityFeedTable } from './ActivityFeedTable';
import { RESULT_FILTER_LABELS, RESULT_FILTER_ORDER, proofChip } from './activityText';

export interface ActivityPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard: the Kubernetes events the XamOps agent reported. */
  eksData?: UseEksDashboardReturn;
}

/** Shown with the latest-change view, when this backend has no activity feed. */
export const LATEST_ONLY_NOTE =
  "This XamOps backend doesn't serve the full activity history yet, so this shows each workload's latest change. A newer backend records every change, test run and refusal, with before and after and the restart-free proof.";

export const EVENTS_STREAM_TITLE = 'Kubernetes events from the XamOps agent';

export const FEED_EMPTY = 'No automation changes recorded on this cluster yet.';

/** The app's `.skeleton` shimmer is light; inside the kit (this page marks its root) the dark variant is a flat tint. */
const SKELETON = 'skeleton rounded-lg dark:bg-none dark:bg-[rgba(255,255,255,0.06)]';

/** The per-day chart's window, in IST days (the summary allows 1 to 30). */
export const SUMMARY_DAYS = 7;

type TimeRange = 'all' | '24h' | '7d' | '30d' | '90d';

/** The time filter: the server's `from`, at most 90 days back (its limit). */
const TIME_RANGES: ReadonlyArray<{ id: TimeRange; label: string; ms: number | null }> = [
  { id: 'all', label: 'All time', ms: null },
  { id: '24h', label: 'Last 24 hours', ms: DAY_MS },
  { id: '7d', label: 'Last 7 days', ms: 7 * DAY_MS },
  { id: '30d', label: 'Last 30 days', ms: 30 * DAY_MS },
  { id: '90d', label: 'Last 90 days', ms: 90 * DAY_MS },
];

/**
 * "Changes per day, by result" (glossary §10; design-delta §3.1), from the activity summary: one bar
 * per IST day, stacked by result in each result's status tone, named by the chips beside it. A day
 * the summary didn't list is "Not recorded yet", never a zero bar.
 */
function ChangesPerDay({ summary }: { summary: InsightReadState<ActivitySummaryView> }) {
  const card = insightCardState(summary);
  if (!card.render) return null;
  const view = card.view;
  const rows = view ? dayRows(view) : [];
  let state: ChartEmptyState = card.state;
  if (view && state.kind === 'ok' && view.days.length === 0) {
    state = {
      kind: 'unavailable',
      reason: view.firstRecordedAt === null
        ? "XamOps hasn't recorded a change on this cluster yet."
        : 'Not recorded yet: XamOps counts changes per day from the first day it records every kind of change on this cluster (test runs, put-backs, refusals and HPA changes). Until then the list below has every entry it recorded.',
    };
  }
  const drawn = state.kind === 'ok' || state.kind === 'partial';
  const unrecorded = rows.filter((r) => !r.recorded);
  const notes = [
    `Last ${SUMMARY_DAYS} days, per IST day, every feature, whatever the filters below.`,
    unrecorded.length > 0 && drawn
      ? `Not recorded yet: ${unrecorded.length === 1 ? unrecorded[0].label : `${unrecorded[0].label} to ${unrecorded[unrecorded.length - 1].label}`}, before XamOps recorded every kind of change here; those days aren't counted as 0.`
      : null,
    view?.truncated ? 'More than 10,000 changes in the window: the days before the first one shown are left out.' : null,
  ].filter(Boolean);
  return (
    <ChartCard
      title="Changes per day, by result"
      subline={notes.join(' ')}
      source={`Activity summary (FEAT-005, GET /api/eks/automation/activity/{cluster}/summary?days=${SUMMARY_DAYS}): the activity log counted per IST day with the feed's own result mapping; mode changes aren't counted.`}
      state={state}
      table={
        drawn && rows.length > 0 ? (
          <ChartDataTable
            caption="Changes per day, by result"
            rowHeader="Day (IST)"
            columns={[...RESULT_BARS.map((b) => ({ key: b.key, header: b.label })), { key: 'total', header: 'Total' }]}
            rows={rows.map((r) => ({
              key: r.date,
              label: r.label,
              values: {
                ...Object.fromEntries(RESULT_BARS.map((b) => [b.key, r.counts ? String(r.counts[b.key]) : 'Not recorded yet'])),
                total: r.total === null ? 'Not recorded yet' : String(r.total),
              },
            }))}
          />
        ) : undefined
      }
    >
      <div className="mb-2 flex flex-wrap gap-1.5" data-day-bars-legend="">
        {RESULT_BARS.map((b) => (
          <StatusChip key={b.key} tone={b.tone} icon={b.icon} label={b.label} weight="normal" />
        ))}
      </div>
      <Bars
        mode="day"
        data={rows.map((r) => ({
          day: r.label,
          ...Object.fromEntries(RESULT_BARS.map((b) => [b.key, r.counts ? r.counts[b.key] : null])),
        }))}
        bars={RESULT_BARS.map((b) => ({ dataKey: b.key, name: b.label, kind: 'capacity' as const, tone: b.tone, stackId: 'day' }))}
        xKey="day"
        height={220}
        ariaLabel={`Changes XamOps recorded per IST day, by result, last ${SUMMARY_DAYS} days`}
      />
    </ChartCard>
  );
}

type SourceFilter = 'all' | ActivitySource;

const SOURCES: Array<{ id: SourceFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'workload', label: 'Workload autoscaler' },
  { id: 'hpa', label: 'HPA' },
];

const pill = (pressed: boolean) =>
  `inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-semibold ${FOCUS} ${
    pressed
      ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] text-white dark:border-[rgba(255,255,255,0.92)] dark:bg-[rgba(255,255,255,0.92)] dark:text-black'
      : `border-[rgba(0,0,0,0.12)] bg-white dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] ${MUTED}`
  }`;

/** Feature chips with their dots: real buttons with aria-pressed, never a tablist. */
function SourceChips({ value, onChange, counts }: { value: SourceFilter; onChange: (v: SourceFilter) => void; counts?: Record<SourceFilter, number> }) {
  return (
    <div role="group" aria-label="Feature" className="flex flex-wrap items-center gap-1.5" data-activity-sources="">
      {SOURCES.map((s) => (
        <button key={s.id} type="button" aria-pressed={value === s.id} onClick={() => onChange(s.id)} data-source={s.id} className={pill(value === s.id)}>
          {s.id !== 'all' && <FeatureDot feature={s.id} />}
          <span>{s.label}</span>
          {counts && (
            <>
              {' '}
              <span className="tabular-nums">{counts[s.id]}</span>
            </>
          )}
        </button>
      ))}
    </div>
  );
}

function ResultChips({ value, onChange }: { value: ActivityResultKey | 'all'; onChange: (v: ActivityResultKey | 'all') => void }) {
  const options: Array<{ id: ActivityResultKey | 'all'; label: string }> = [
    { id: 'all', label: 'All' },
    ...RESULT_FILTER_ORDER.map((id) => ({ id, label: RESULT_FILTER_LABELS[id] })),
  ];
  return (
    <div role="group" aria-label="Result" className="flex flex-wrap items-center gap-1.5" data-activity-results="">
      {options.map((o) => (
        <button key={o.id} type="button" aria-pressed={value === o.id} onClick={() => onChange(o.id)} data-result={o.id} className={pill(value === o.id)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

const EVENT_COLUMNS: ReadonlyArray<ColumnDef<EventRow>> = [
  {
    id: 'lastSeen',
    header: 'When',
    priority: 'essential',
    cell: (r) => <span className="whitespace-nowrap tabular-nums" title={r.lastSeen ?? undefined}>{r.lastSeen ? formatIST(r.lastSeen, { short: true }) || DASH : DASH}</span>,
    sortValue: (r) => (r.lastSeen ? Date.parse(r.lastSeen) || null : null),
    csv: (r) => r.lastSeen,
  },
  {
    id: 'type',
    header: 'Type',
    priority: 'essential',
    cell: (r) =>
      r.type === 'Warning' ? (
        <StatusChip tone="warn" icon="mdi:alert-outline" label="Warning" weight="normal" />
      ) : r.type === 'Normal' ? (
        <StatusChip tone="info" icon="mdi:information-outline" label="Normal" weight="normal" />
      ) : (
        <StatusChip tone="off" icon="mdi:help-circle-outline" label="Type not reported" weight="normal" />
      ),
    sortValue: (r) => r.type,
    csv: (r) => r.type,
  },
  { id: 'reason', header: 'Reason', cell: (r) => <span className="font-semibold">{r.reason}</span>, sortValue: (r) => r.reason },
  { id: 'object', header: 'Object', cell: (r) => <span className="font-mono text-[12px]">{r.object}</span>, sortValue: (r) => r.object },
  { id: 'namespace', header: 'Namespace', cell: (r) => r.namespace, sortValue: (r) => r.namespace },
  { id: 'message', header: 'Message', cell: (r) => <span className="block max-w-[420px] break-words">{r.message}</span>, csv: (r) => r.message },
  { id: 'count', header: 'Count', align: 'right', cell: (r) => (r.count === null ? DASH : r.count), sortValue: (r) => r.count },
];

function EventsStream({ clusterName, eksData }: { clusterName: string; eksData?: UseEksDashboardReturn }) {
  const titleId = useId();
  const raw = eksData?.dashboard?.k8sEvents;
  const reported = Array.isArray(raw);
  const rows = useMemo(
    () =>
      eventRows(raw)
        .filter((r) => SCALING_EVENT_REASON.test(r.reason))
        .sort((a, b) => (b.lastSeen ? Date.parse(b.lastSeen) || 0 : 0) - (a.lastSeen ? Date.parse(a.lastSeen) || 0 : 0)),
    [raw],
  );
  return (
    <section className={`${CARD} px-4 py-3`} aria-labelledby={titleId} data-activity-events="">
      <div className="mb-2">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>{EVENTS_STREAM_TITLE}</h3>
        <p className={`mt-0.5 text-[12px] ${MUTED}`}>
          What the cluster observed about scaling, resizing and eviction. These are not XamOps actions, and they are never mixed
          into the rows above.
        </p>
      </div>
      <K8sDataTable
        rows={rows}
        columns={EVENT_COLUMNS}
        rowKey={(r) => r.key}
        caption={EVENTS_STREAM_TITLE}
        search={(r) => `${r.reason} ${r.object} ${r.namespace} ${r.message}`}
        searchPlaceholder="Search events"
        exportName={`${clusterName}-scaling-events`}
        initialSort={{ columnId: 'lastSeen', direction: 'desc' }}
        emptyState={
          <p className={`px-4 py-8 text-center text-[12px] ${MUTED}`}>
            {reported
              ? 'No scaling, resize or eviction events in what the XamOps agent reported.'
              : "The XamOps agent hasn't reported this cluster's Kubernetes events."}
          </p>
        }
      />
      <p className={`mt-2 text-[11px] ${FAINT}`}>
        Source: the XamOps agent (k8s-resources, k8sEvents), reasons matching Resize, Scaling, Rescale or Evict. Kubernetes keeps
        events for about an hour.
      </p>
    </section>
  );
}

// ─── The latest-change view (a backend without the feed) ─────────────────────

function LatestKpiRow({ rows }: { rows: readonly LatestChangeRow[] }) {
  const k = activityKpis(rows);
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-activity-kpis="">
      <KpiCard tone="good" icon="mdi:check-circle" label="Applied" value={k.applied}>
        <p>latest change per workload, applied live</p>
      </KpiCard>
      <KpiCard tone="info" icon="mdi:flask-outline" label="Test runs" value={k.testRuns}>
        <p>latest change per workload; nothing was sent</p>
      </KpiCard>
      <KpiCard tone={k.refusedOrFailed > 0 ? 'bad' : 'good'} icon={k.refusedOrFailed > 0 ? 'mdi:alert-octagon-outline' : 'mdi:check-circle'} label="Refused or failed" value={k.refusedOrFailed}>
        <p>latest change per workload</p>
      </KpiCard>
      <KpiCard tone="good" icon="mdi:check-decagram-outline" label="In place now" value={k.inPlace}>
        <p>latest change applied in full; a rollout since then would undo it</p>
      </KpiCard>
    </div>
  );
}

function LatestChangeView({ clusterName, accountId, region, onSentence }: { clusterName: string; accountId: string; region: string; onSentence: (s: NowSentence) => void }) {
  const { loading, result, reload } = useAutomationRead({ clusterName, accountId, region });
  const [source, setSource] = useState<SourceFilter>('all');
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  const rows = useMemo(() => (view ? latestChangeRows(view.workloads) : []), [view]);
  const shown = source === 'all' ? rows : rows.filter((r) => r.source === source);
  const counts: Record<SourceFilter, number> = {
    all: rows.length,
    workload: rows.filter((r) => r.source === 'workload').length,
    hpa: rows.filter((r) => r.source === 'hpa').length,
  };

  useEffect(() => {
    if (loading && !result) onSentence({ clauses: ['Reading what XamOps recorded on this cluster…'], chips: [] });
    else if (view) {
      const now = Date.now();
      onSentence(nowSentence('activity', {
        recorded: rows.length,
        today: todayByResult(rows, now),
        inPlace: rows.filter((r) => r.live && r.result.key === 'applied').map((r) => r.workload.ref.name),
        restarted: rows.filter((r) => r.workload.lastAction?.outcome === 'RESTART_DETECTED').map((r) => r.workload.ref.name),
      }));
    } else onSentence(nowSentence('activity', {}));
  }, [loading, result, view, rows, onSentence]);

  const tableTitle = useId();
  if (loading && !result) return <div className={`${SKELETON} h-28`} />;
  if (result && result.kind !== 'ok') return <ReadFailureCard failure={result} onRetry={reload} />;
  if (!view) return null;
  return (
    <>
      <Notice>{LATEST_ONLY_NOTE}</Notice>
      <LatestKpiRow rows={rows} />
      <RootCausePanel clusterName={clusterName} accountId={accountId} region={region} />
      <section className={`${CARD} px-4 py-3`} aria-labelledby={tableTitle} data-activity-latest="">
        <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
          <div>
            <h3 id={tableTitle} className={`text-[13px] font-semibold ${INK}`}>Latest change per workload</h3>
            <p className={`mt-0.5 text-[12px] ${MUTED}`}>Source: Workload autoscaler (endpoint 1): each workload&apos;s lastAction.</p>
          </div>
          <SourceChips value={source} onChange={setSource} counts={counts} />
        </div>
        <LatestChangeTable
          rows={shown}
          clusterName={clusterName}
          emptyText={
            source === 'hpa'
              ? "No HPA change is recorded here: endpoint 1 records the Workload autoscaler's changes only."
              : "XamOps hasn't recorded a change on this cluster yet."
          }
        />
      </section>
    </>
  );
}

// ─── The activity feed ───────────────────────────────────────────────────────

const istDay = (ms: number): string => new Date(ms).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata' });

/** Counts over the loaded entries, by result. */
export function feedCounts(entries: readonly ActivityEntryView[]) {
  const by = (key: ActivityResultKey) => entries.filter((e) => e.result === key).length;
  return { applied: by('applied'), putBack: by('put-back'), refused: by('refused'), failed: by('failed'), testRuns: by('test-run') };
}

/** The Now sentence over the loaded entries: today's, by result, and any restart. */
export function feedSentence(entries: readonly ActivityEntryView[], now: number): NowSentence {
  const today = entries.filter((e) => e.at !== null && istDay(e.at) === istDay(now));
  const c = feedCounts(today);
  const restarted = [...new Set(entries.filter((e) => proofChip(e)?.label === 'Restarted').map((e) => e.target.name ?? DASH))];
  return nowSentence('activity', {
    basis: 'feed',
    recorded: entries.length,
    today: { applied: c.applied, deferred: c.putBack, refused: c.refused, failed: c.failed, testRuns: c.testRuns },
    restarted,
  });
}

function FeedKpiRow({ entries, hasOlder }: { entries: readonly ActivityEntryView[]; hasOlder: boolean }) {
  const c = feedCounts(entries);
  const over = hasOlder ? `in the last ${entries.length} entries` : `in all ${entries.length} ${entries.length === 1 ? 'entry' : 'entries'}`;
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-activity-kpis="">
      <KpiCard tone="good" icon="mdi:check-circle" label="Applied" value={c.applied}>
        <p>{over}</p>
      </KpiCard>
      <KpiCard tone="warn" icon="mdi:undo-variant" label="Deferred or put back" value={c.putBack}>
        <p>{over}; the pods kept their size</p>
      </KpiCard>
      <KpiCard tone={c.refused + c.failed > 0 ? 'bad' : 'good'} icon={c.refused + c.failed > 0 ? 'mdi:alert-octagon-outline' : 'mdi:check-circle'} label="Refused or failed" value={c.refused + c.failed}>
        <p>{over}</p>
      </KpiCard>
      <KpiCard tone="info" icon="mdi:flask-outline" label="Test runs" value={c.testRuns}>
        <p>{over}; nothing was sent</p>
      </KpiCard>
    </div>
  );
}

/** The feed's non-ok answers, in the read-failure card's words. */
function FeedFailure({ result, onRetry }: { result: Exclude<ActivityReadResult, { kind: 'ok' } | { kind: 'not-deployed' }>; onRetry: () => void }) {
  const failure = result as ReadFailure;
  const text = readFailureText(failure);
  const title = result.kind === 'error' ? "Couldn't read the activity log" : text.title;
  return (
    <section className={`${CARD} px-4 py-3`} data-read-failure={result.kind} data-activity-failure="">
      <p className={`flex items-center gap-1.5 text-[13px] font-semibold ${INK}`}>
        <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className={`shrink-0 ${MUTED}`} />
        {title}
      </p>
      <p className={`mt-1 text-[12px] ${MUTED}`}>{text.detail}</p>
      <button type="button" onClick={onRetry} className={`mt-3 ${BUTTON}`}>
        Retry
      </button>
    </section>
  );
}

export function ActivityPage({ clusterName, accountId, region, eksData }: ActivityPageProps) {
  const [source, setSource] = useState<SourceFilter>('all');
  const [resultFilter, setResultFilter] = useState<ActivityResultKey | 'all'>('all');
  const [range, setRange] = useState<TimeRange>('all');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  // The search is the server's `q`: sent once typing pauses, not on every key.
  useEffect(() => {
    const t = window.setTimeout(() => setQ(search.trim().slice(0, ACTIVITY_QUERY_MAX)), 300);
    return () => window.clearTimeout(t);
  }, [search]);
  // The time filter is the server's `from`, fixed (to the minute) when it is picked.
  const from = useMemo(() => {
    const span = TIME_RANGES.find((r) => r.id === range)?.ms ?? null;
    return span === null ? null : new Date(Math.floor((Date.now() - span) / 60_000) * 60_000).toISOString();
  }, [range]);
  const filters = useMemo<ActivityFilters>(() => ({
    features: source === 'all' ? [] : [source === 'hpa' ? 'HPA' : 'VERTICAL'],
    results: resultFilter === 'all' ? [] : [RESULT_WIRE[resultFilter]],
    q,
    from,
  }), [source, resultFilter, q, from]);
  const feed = useAutomationActivity({ clusterName, accountId, region, filters });
  const summary = useActivitySummary({ clusterName, accountId, region }, SUMMARY_DAYS);
  const filtered = filters.features.length > 0 || filters.results.length > 0 || q !== '' || from !== null;
  const notDeployed = feed.result?.kind === 'not-deployed';

  const [latestSentence, setLatestSentence] = useState<NowSentence | null>(null);
  let sentence: NowSentence;
  if (notDeployed) sentence = latestSentence ?? { clauses: ['Reading what XamOps recorded on this cluster…'], chips: [] };
  else if (feed.loading && !feed.result) sentence = { clauses: ['Reading what XamOps recorded on this cluster…'], chips: [] };
  else if (feed.result?.kind === 'ok') {
    sentence = filtered && feed.entries.length === 0
      ? { clauses: ['Nothing XamOps recorded on this cluster matches these filters.'], chips: [] }
      : feedSentence(feed.entries, Date.now());
  } else sentence = nowSentence('activity', {});

  const titleId = useId();
  const rangeId = useId();
  const clearFilters = () => {
    setSource('all');
    setResultFilter('all');
    setRange('all');
    setSearch('');
    setQ('');
  };

  return (
    // data-k8s-kit: every colour on this page has its dark variant (K8sDataTable's included), so the
    // legacy dark CSS leaves it alone and the source filter keeps its pressed chip in dark mode.
    <div className="space-y-4" data-activity="" data-k8s-kit="">
      <div className="space-y-1">
        <p className={`text-[13px] font-semibold ${INK}`}>Activity</p>
        <p className={`text-[12px] ${MUTED}`}>
          Every change XamOps made, tested or was refused on this cluster, from the Workload autoscaler and the HPA, with proof that
          nothing restarted. Node automation isn&apos;t recorded here.
        </p>
      </div>
      <NowBanner sentence={sentence} />

      {notDeployed && <LatestChangeView clusterName={clusterName} accountId={accountId} region={region} onSentence={setLatestSentence} />}

      {!notDeployed && feed.loading && !feed.result && <div className={`${SKELETON} h-28`} aria-busy="true" />}
      {!notDeployed && feed.result && feed.result.kind !== 'ok' && feed.result.kind !== 'not-deployed' && (
        <FeedFailure result={feed.result} onRetry={feed.reload} />
      )}
      {!notDeployed && feed.result?.kind === 'ok' && (
        <>
          {feed.result.page.engineEnabled === false && (
            <Notice>The Workload autoscaler is off in this environment. This is the history XamOps recorded while it was on.</Notice>
          )}
          <FeedKpiRow entries={feed.entries} hasOlder={feed.hasOlder} />
          <RootCausePanel clusterName={clusterName} accountId={accountId} region={region} />
          <section className={`${CARD} px-4 py-3`} aria-labelledby={titleId} data-activity-feed="">
            <div className="mb-3 space-y-2">
              <div>
                <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>{ACTIVITY_FEED_CAPTION}</h3>
                <p className={`mt-0.5 text-[12px] ${MUTED}`}>
                  Source: the activity log (GET /api/eks/automation/activity/{'{cluster}'}), newest first.
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-3">
                <SourceChips value={source} onChange={setSource} />
                <ResultChips value={resultFilter} onChange={setResultFilter} />
                <span className="ml-auto inline-flex items-center gap-1.5">
                  <label htmlFor={rangeId} className={`text-[12px] font-semibold ${MUTED}`}>Time</label>
                  <select id={rangeId} value={range} onChange={(ev) => setRange(ev.target.value as TimeRange)} className={CONTROL}>
                    {TIME_RANGES.map((r) => (
                      <option key={r.id} value={r.id}>{r.label}</option>
                    ))}
                  </select>
                </span>
                <label className="inline-flex items-center gap-1.5">
                  <Icon icon="mdi:magnify" width={16} aria-hidden="true" className={MUTED} />
                  <span className="sr-only">Search the activity by workload or HPA name</span>
                  <input
                    type="search"
                    value={search}
                    maxLength={ACTIVITY_QUERY_MAX}
                    onChange={(ev) => setSearch(ev.target.value)}
                    placeholder="Search by name"
                    className={`w-48 ${CONTROL}`}
                  />
                </label>
              </div>
            </div>
            {feed.loading ? (
              <div className={`${SKELETON} h-24`} aria-busy="true" />
            ) : (
              <ActivityFeedTable
                entries={feed.entries}
                clusterName={clusterName}
                emptyState={
                  filtered ? (
                    <div className="px-4 py-8 text-center text-[12px]">
                      <p className={MUTED}>No recorded change matches these filters.</p>
                      <button type="button" onClick={clearFilters} className={`mt-2 ${BUTTON}`}>Clear filters</button>
                    </div>
                  ) : (
                    <p className={`px-4 py-8 text-center text-[12px] ${MUTED}`}>{FEED_EMPTY}</p>
                  )
                }
              />
            )}
            {!feed.loading && (feed.hasOlder || feed.olderError) && (
              <div className="mt-3 flex flex-wrap items-center gap-3">
                {feed.hasOlder && (
                  <button type="button" onClick={feed.loadOlder} disabled={feed.loadingOlder} className={`${BUTTON} disabled:opacity-60`}>
                    {feed.loadingOlder ? 'Loading older entries…' : 'Load older'}
                  </button>
                )}
                {feed.olderError && <p className={`text-[12px] ${MUTED}`} role="status">{`Couldn't load older entries: ${feed.olderError}`}</p>}
              </div>
            )}
            <p className={`mt-3 text-[11px] ${FAINT}`}>
              Who: &quot;A user&quot; is someone who clicked Apply or changed a mode in XamOps (the name isn&apos;t recorded); &quot;XamOps
              (automatic)&quot; is the Apply automatically loop. A refusal is recorded only for a user&apos;s apply. Restart-free means
              Kubernetes confirmed every pod XamOps touched kept its uid and restart count.
            </p>
          </section>
        </>
      )}

      <ChangesPerDay summary={summary} />
      <EventsStream clusterName={clusterName} eksData={eksData} />
    </div>
  );
}

export default ActivityPage;
