/**
 * Automation › Activity (UI v3 plan §1 deviation 5, §2 "Activity").
 *
 * What XamOps did on this cluster, from what endpoint 1 records today: each workload's latest
 * change (`lastAction`: when, outcome, test run or live), one row per workload, coloured by result.
 * Endpoint 1 records the Workload autoscaler's changes only, so the HPA source reads 0 until the
 * activity feed records HPA changes, and node automation isn't recorded here at all; the page says
 * so. The full history (before and after, the restart-free proof, every attempt) and the changes per
 * day need the activity feed, so that chart is in its honest empty state.
 *
 * The Kubernetes events about scaling, resizing and eviction that the XamOps agent reported are a
 * separate, labelled stream: they are what the cluster observed, never mixed into XamOps's rows.
 */
import { useId, useMemo, useState } from 'react';
import useAutomationRead from '../../../../../hooks/useAutomationRead';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { formatIST } from '../../../../../utils/timeUtils';
import { K8sDataTable, type ColumnDef } from '../../../../CloudK8s/shared/K8sDataTable';
import { FeatureDot } from '../../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';
import { eventRows, type EventRow } from '../../overview/EventsPanel';
import {
  SCALING_EVENT_REASON,
  activityKpis,
  latestChangeRows,
  todayByResult,
  type ActivitySource,
  type LatestChangeRow,
} from '../automationDerivations';
import { CARD, FAINT, FOCUS, INK, MUTED, Notice, ReadFailureCard } from '../automationUi';
import { LatestChangeTable } from './LatestChangeTable';

export interface ActivityPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard: the Kubernetes events the XamOps agent reported. */
  eksData?: UseEksDashboardReturn;
}

/** Exact copy, FEAT-003. */
export const LATEST_ONLY_NOTE =
  "XamOps records full history with before and after and the restart-free proof once the activity feed is on; until then this shows each workload's latest change.";

export const EVENTS_STREAM_TITLE = 'Kubernetes events from the XamOps agent';

type SourceFilter = 'all' | ActivitySource;

const SOURCES: Array<{ id: SourceFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'workload', label: 'Workload autoscaler' },
  { id: 'hpa', label: 'HPA' },
];

function SourceChips({ value, onChange, counts }: { value: SourceFilter; onChange: (v: SourceFilter) => void; counts: Record<SourceFilter, number> }) {
  return (
    <div role="group" aria-label="Source" className="flex flex-wrap items-center gap-1.5" data-activity-sources="">
      {SOURCES.map((s) => {
        const pressed = value === s.id;
        return (
          <button
            key={s.id}
            type="button"
            aria-pressed={pressed}
            onClick={() => onChange(s.id)}
            data-source={s.id}
            className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-semibold ${FOCUS} ${
              pressed
                ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] text-white dark:border-[rgba(255,255,255,0.92)] dark:bg-[rgba(255,255,255,0.92)] dark:text-black'
                : `border-[rgba(0,0,0,0.12)] bg-white dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] ${MUTED}`
            }`}
          >
            {s.id !== 'all' && <FeatureDot feature={s.id} />}
            <span>{s.label}</span>{' '}
            <span className="tabular-nums">{counts[s.id]}</span>
          </button>
        );
      })}
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

function ActivityKpiRow({ rows }: { rows: readonly LatestChangeRow[] }) {
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

export function ActivityPage({ clusterName, accountId, region, eksData }: ActivityPageProps) {
  const { loading, result, reload } = useAutomationRead({ clusterName, accountId, region });
  const [source, setSource] = useState<SourceFilter>('all');
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  const rows = useMemo(() => (view ? latestChangeRows(view.workloads) : []), [view]);
  const now = Date.now();
  const shown = source === 'all' ? rows : rows.filter((r) => r.source === source);
  const counts: Record<SourceFilter, number> = {
    all: rows.length,
    workload: rows.filter((r) => r.source === 'workload').length,
    hpa: rows.filter((r) => r.source === 'hpa').length,
  };

  let sentence: NowSentence;
  if (loading && !result) sentence = { clauses: ['Reading what XamOps recorded on this cluster…'], chips: [] };
  else if (view) {
    sentence = nowSentence('activity', {
      recorded: rows.length,
      today: todayByResult(rows, now),
      inPlace: rows.filter((r) => r.live && r.result.key === 'applied').map((r) => r.workload.ref.name),
      restarted: rows.filter((r) => r.workload.lastAction?.outcome === 'RESTART_DETECTED').map((r) => r.workload.ref.name),
    });
  } else sentence = nowSentence('activity', {});

  const tableTitle = useId();
  return (
    <div className="space-y-4" data-activity="">
      <div className="space-y-1">
        <p className={`text-[13px] font-semibold ${INK}`}>Activity</p>
        <p className={`text-[12px] ${MUTED}`}>
          What XamOps changed on this cluster. Today XamOps records each workload&apos;s latest Workload autoscaler change; HPA
          changes and node automation aren&apos;t recorded here yet.
        </p>
      </div>
      <NowBanner sentence={sentence} />
      {loading && !result && <div className="skeleton h-28 rounded-lg" />}
      {result && result.kind !== 'ok' && <ReadFailureCard failure={result} onRetry={reload} />}
      {view && (
        <>
          <ActivityKpiRow rows={rows} />
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
                  ? "No HPA change is recorded here yet: endpoint 1 records the Workload autoscaler's changes only."
                  : "XamOps hasn't recorded a change on this cluster yet."
              }
            />
          </section>
          <Notice>{LATEST_ONLY_NOTE}</Notice>
        </>
      )}
      <ChartCard
        title="Changes per day, by result"
        source="Activity feed (planned): every change XamOps made, tested or refused, counted per IST day by result."
        state={{ kind: 'history-pending', from: 'It will come from the activity feed: every change XamOps made, tested or refused, per IST day.' }}
      />
      <EventsStream clusterName={clusterName} eksData={eksData} />
    </div>
  );
}

export default ActivityPage;
