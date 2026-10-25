/**
 * Automation › Spot interruptions (spot-interruptions design §B6.2): the recorded interruption
 * history of this cluster's Spot nodes. When, which node, the kind of notice (a 2-minute
 * interruption notice, a rebalance recommendation, or a Spot node that vanished with no recorded
 * cause), the workloads on it, how long they took to be Ready elsewhere, and whether XamOps handling
 * was live, simulated, off or not applicable at that moment, next to the cluster's Spot share now.
 *
 * Read-only: the one request is `GET /api/eks/autoscaling/{cluster}/spot-interruptions`, and the only
 * controls are the window and Retry. Real data only: anything the backend didn't observe is a dash
 * with its reason, never a 0 or "None"; a removal without a notice is never called an interruption.
 */
import { useMemo, useState, type ReactNode } from 'react';
import useSpotInterruptions, { SPOT_INTERRUPTIONS_LIMIT } from '../../../../../hooks/useSpotInterruptions';
import type { SpotInterruptionEvent, SpotInterruptionsDto } from '../../../../../types/k8sAutomation';
import { PageTemplate } from '../../../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../../../CloudK8s/shared/MetricStrip';
import { EmptyState } from '../../../../CloudK8s/shared/EmptyState';
import { StatusMark } from '../../../../CloudK8s/shared/StatusMark';
import { SourceLabel, formatAbsolute, formatAgo } from '../../../../CloudK8s/shared/SourceLabel';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../../../CloudK8s/shared/K8sDataTable';
import { DetailDrawer, type DrawerSection } from '../../../../CloudK8s/shared/DetailDrawer';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import type { NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { FAINT, INK, MUTED, RULE } from '../automationUi';
import { pill } from '../activity/ActivityPage';
import {
  DASH,
  DEFAULT_WINDOW_DAYS,
  HANDLING_FILTER,
  NOTICE_FILTER,
  WINDOW_DAYS,
  eventRows,
  matchHandling,
  matchNotice,
  metricCells,
  nowSentence,
  pageState,
  RECORDING_OFF_SENTENCE,
  recoverySentence,
  recoveryText,
  sourceWords,
  workloadLine,
  type MarkView,
  type RecoveryView,
  type SpotEventRow,
  type SpotPageState,
} from './spotInterruptionsDerive';

export interface SpotInterruptionsPageProps {
  clusterName: string;
  accountId: string;
  region: string;
}

const INTRO =
  "Every Spot node on this cluster that got an interruption notice or a rebalance recommendation, or disappeared with no recorded cause, and how its workloads recovered. Read-only: nothing here changes the cluster.";

const COVERAGE_NOTE =
  'Notices come from NTH taints and Karpenter events. A Spot node that disappears without one is listed as cause unconfirmed.';

/** A dash with its reason, for a table cell. */
function Dash({ reason }: { reason: string }) {
  return (
    <span className={MUTED} title={reason}>
      <span aria-hidden="true">{DASH}</span>
      <span className={`ml-1 text-[11px] ${FAINT}`}>{reason}</span>
    </span>
  );
}

function Mark({ mark, detail }: { mark: MarkView; detail?: string | null }) {
  return <StatusMark state={mark.state} label={mark.label} detail={detail ?? undefined} />;
}

function RecoveryCell({ view }: { view: RecoveryView }) {
  if (view.kind === 'text') return <span className={`whitespace-nowrap ${INK}`}>{view.text}</span>;
  if (view.kind === 'mark') return <Mark mark={view.mark} />;
  return <Dash reason={view.reason} />;
}

function timeCell(iso: string | null, now: number): ReactNode {
  if (!iso) return <span className={MUTED}>{DASH}</span>;
  const ago = formatAgo(iso, now);
  return (
    <span className="whitespace-nowrap tabular-nums" title={iso}>
      {formatAbsolute(iso)}
      {ago && <span className={`block text-[11px] ${FAINT}`}>{ago}</span>}
    </span>
  );
}

function columns(now: number): ReadonlyArray<ColumnDef<SpotEventRow>> {
  return [
    {
      id: 'time',
      header: 'Time',
      priority: 'essential',
      cell: (r) => timeCell(r.event.noticedAt, now),
      sortValue: (r) => r.noticedMs,
      csv: (r) => r.event.noticedAt,
    },
    {
      id: 'node',
      header: 'Node',
      priority: 'essential',
      cell: (r) => (
        <span className={`font-semibold ${INK}`} title={r.event.nodeName}>
          {r.node}
          <span className={`block text-[11px] font-normal ${FAINT}`}>{r.event.nodegroup ?? DASH}</span>
        </span>
      ),
      sortValue: (r) => r.event.nodeName,
      csv: (r) => r.event.nodeName,
    },
    { id: 'instanceType', header: 'Instance type', cell: (r) => r.event.instanceType ?? DASH, sortValue: (r) => r.event.instanceType },
    { id: 'zone', header: 'AZ', cell: (r) => r.event.zone ?? DASH, sortValue: (r) => r.event.zone },
    { id: 'capacity', header: 'Capacity', cell: (r) => r.event.capacityType ?? DASH, sortValue: (r) => r.event.capacityType },
    {
      id: 'notice',
      header: 'Notice',
      priority: 'essential',
      cell: (r) => <span data-notice-cell=""><Mark mark={r.notice} /></span>,
      sortValue: (r) => r.notice.label,
      csv: (r) => r.notice.label,
    },
    {
      id: 'workloads',
      header: 'Workloads',
      align: 'right',
      cell: (r) => (r.workloads === null ? <span className={MUTED} title="No snapshot listed this node's pods at the notice">{DASH}</span> : r.workloads),
      sortValue: (r) => r.event.workloads?.length ?? null,
      csv: (r) => r.workloads,
    },
    {
      id: 'recovery',
      header: 'Recovery',
      priority: 'essential',
      cell: (r) => <RecoveryCell view={r.recovery} />,
      sortValue: (r) => (r.event.recovery.status === 'recovered' ? r.event.recovery.upperSeconds : null),
      csv: (r) => (r.recovery.kind === 'text' ? r.recovery.text : r.recovery.kind === 'mark' ? r.recovery.mark.label : r.recovery.reason),
    },
    {
      id: 'handling',
      header: 'XamOps handling',
      priority: 'essential',
      cell: (r) => (
        <span className="flex flex-col gap-0.5">
          <Mark mark={r.handling} />
          {r.drain && <span className={`text-[11px] ${MUTED}`}>{r.drain}</span>}
        </span>
      ),
      sortValue: (r) => r.handling.label,
      csv: (r) => (r.drain ? `${r.handling.label} · ${r.drain}` : r.handling.label),
    },
  ];
}

const FILTERS: ReadonlyArray<FilterDef<SpotEventRow>> = [
  { id: 'notice', label: 'Notice', options: NOTICE_FILTER, match: matchNotice },
  { id: 'handling', label: 'Handling', options: HANDLING_FILTER, match: matchHandling },
];

const yesNo = (v: boolean | null) => (v === null ? null : v ? 'Yes' : 'No');
const at = (iso: string | null) => (iso ? formatAbsolute(iso) : null);

function drawerSections(e: SpotInterruptionEvent, row: SpotEventRow): DrawerSection[] {
  const workloads = e.workloads;
  return [
    {
      title: 'Notice',
      data: [
        { label: 'Kind', value: <Mark mark={row.notice} /> },
        { label: 'Seen by', value: sourceWords(e.sources), reason: 'No source was recorded.' },
        { label: 'First noticed', value: at(e.noticedAt), reason: 'Not recorded.' },
        { label: 'Rebalance recommendation', value: at(e.rebalanceNoticedAt), reason: 'No rebalance recommendation was seen.' },
        { label: '2-min notice', value: at(e.interruptionNoticedAt), reason: 'No 2-minute notice was seen.' },
        { label: 'Node removed', value: at(e.nodeRemovedAt), reason: 'XamOps has not seen the node leave the cluster.' },
        { label: 'Cordoned before removal', value: yesNo(e.cordonedBeforeRemoval), reason: 'Not observed.' },
      ],
    },
    {
      title: 'Node',
      data: [
        { label: 'Name', value: e.nodeName },
        { label: 'Instance id', value: e.instanceId, reason: 'Not reported.' },
        { label: 'Instance type', value: e.instanceType, reason: 'Not reported.' },
        { label: 'AZ', value: e.zone, reason: 'Not reported.' },
        { label: 'Capacity', value: e.capacityType, reason: 'Not reported.' },
        { label: 'Node group', value: e.nodegroup, reason: 'Not reported.' },
        { label: 'Created', value: at(e.nodeCreatedAt), reason: 'Not reported.' },
        { label: 'Last seen', value: at(e.nodeLastSeenAt), reason: 'Not recorded.' },
      ],
    },
    {
      title: 'Affected workloads',
      data: [
        {
          label: 'On the node at the notice',
          value: workloads === null
            ? null
            : workloads.length === 0
              ? 'None on the node'
              : (
                <ul className="flex flex-col gap-0.5" data-drawer-workloads="">
                  {workloads.map((w) => <li key={`${w.namespace}/${w.kind}/${w.name}`}>{workloadLine(w)}</li>)}
                </ul>
              ),
          reason: "No snapshot listed this node's pods at the notice.",
        },
        { label: 'Pods on the node', value: e.podCount === null ? null : String(e.podCount), reason: 'Not recorded.' },
        { label: 'As of', value: at(e.workloadsSnapshotAt), reason: 'No snapshot.' },
        {
          label: 'Truncated',
          value: e.workloadsTruncated === null ? null : e.workloadsTruncated ? 'Some pods on the node are not in the list above' : 'No',
          reason: 'Not recorded.',
        },
      ],
    },
    {
      title: 'Recovery',
      data: [
        { label: 'Status', value: recoverySentence(e) },
        { label: 'Time to Ready elsewhere', value: recoveryText(e.recovery), reason: 'Not measured.' },
        { label: 'Observed', value: at(e.recovery.observedAt), reason: 'Not recovered yet, or not observed.' },
        { label: 'Pending first seen', value: at(e.pending?.firstSeenAt ?? null), reason: 'No Pending pod was seen.' },
        { label: 'Pending last seen', value: at(e.pending?.lastSeenAt ?? null), reason: 'No Pending pod was seen.' },
        {
          label: 'Most Pending pods',
          value: typeof e.pending?.maxPods === 'number' ? String(e.pending.maxPods) : null,
          reason: 'No Pending pod was seen.',
        },
      ],
    },
    {
      title: 'XamOps handling',
      data: [
        { label: 'At the notice', value: <Mark mark={row.handling} /> },
        { label: 'Why', value: row.handling.reason, reason: 'No reason was recorded.' },
        { label: 'Drain', value: row.drain, reason: 'XamOps did not drain this node.' },
        { label: 'Drain reason', value: e.drain?.reason ?? null, reason: 'No reason was recorded.' },
        { label: 'Dry run', value: yesNo(e.drain?.dryRun ?? null), reason: 'Not recorded.' },
        { label: 'Drain finished', value: at(e.drain?.finishedAt ?? null), reason: 'Not recorded.' },
      ],
    },
  ];
}

function WindowPills({ days, onChange }: { days: number; onChange: (d: number) => void }) {
  return (
    <div role="group" aria-label="Window" className="flex flex-wrap items-center gap-1.5" data-spot-window="">
      {WINDOW_DAYS.map((d) => (
        <button key={d} type="button" aria-pressed={days === d} onClick={() => onChange(d)} data-window={d} className={pill(days === d)}>
          {`${d} d`}
        </button>
      ))}
    </div>
  );
}

function headerSentence(state: SpotPageState, data: SpotInterruptionsDto | null, days: number): NowSentence {
  switch (state) {
    case 'loading':
      return { clauses: ['Reading the Spot interruption history…'], chips: [] };
    case 'error':
      return { clauses: ["XamOps couldn't read the Spot interruption history."], chips: [] };
    case 'not-monitored':
      return { clauses: ["XamOps isn't receiving node data from this cluster yet."], chips: [] };
    default:
      return { clauses: [nowSentence(data!.events, data!.fleet, days, data!.recordingEnabled)], chips: [] };
  }
}

function strip(data: SpotInterruptionsDto, now: number): MetricCell[] {
  const c = metricCells(data, now);
  return [
    { label: 'Spot share now', value: c.share.value, sub: c.share.sub },
    { label: 'Spot events', value: c.events.value, sub: c.events.sub },
    { label: 'Recovery', value: c.recovery.value, sub: c.recovery.sub },
    { label: 'XamOps handling now', value: <Mark mark={c.handling.mark} />, sub: c.handling.sub },
  ];
}

const EMPTY_CELLS: MetricCell[] = ['Spot share now', 'Spot events', 'Recovery', 'XamOps handling now'].map((label) => ({ label, value: DASH }));

export function SpotInterruptionsPage({ clusterName, accountId, region }: SpotInterruptionsPageProps) {
  const [days, setDays] = useState<number>(DEFAULT_WINDOW_DAYS);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const { read, retry } = useSpotInterruptions(clusterName, accountId, region, days);
  const state = pageState(read);
  const data = read.kind === 'ok' ? read.data : null;
  const now = Date.now();
  const rows = useMemo(() => (data ? eventRows(data.events) : []), [data]);
  const open = openKey === null ? null : rows.find((r) => r.key === openKey) ?? null;
  const showStrip =
    state === 'loading' || state === 'recording-off' || state === 'no-spot' || state === 'none' || state === 'events';

  const hero = (
    <div className="flex flex-col gap-4" data-k8s-kit="">
      <NowBanner sentence={headerSentence(state, data, days)} />
      {showStrip && (
        <div data-spot-kpis="">
          <MetricStrip
            cells={data ? strip(data, now) : EMPTY_CELLS}
            heroLead
            loading={state === 'loading'}
            aria-label="Spot interruptions summary"
          />
        </div>
      )}
    </div>
  );

  let body: ReactNode;
  switch (state) {
    case 'loading':
      body = <p className={`text-[12px] ${MUTED}`} aria-busy="true">Reading the Spot interruption history…</p>;
      break;
    case 'error':
      body = (
        <div data-spot-state="error">
          <EmptyState
            icon="mdi:alert-circle-outline"
            message={`Couldn't read the Spot interruption history: ${read.kind === 'error' ? read.message : ''}`}
            action={{ label: 'Retry', onClick: retry }}
          />
        </div>
      );
      break;
    case 'not-monitored':
      body = (
        <div data-spot-state="not-monitored">
          <EmptyState icon="mdi:lan-disconnect" message="XamOps isn't receiving node data from this cluster yet" />
          <p className={`-mt-6 text-center text-[12px] ${MUTED}`}>
            Install the XamOps agent (and the automation agent for notices) on this cluster to record Spot interruptions.
          </p>
        </div>
      );
      break;
    case 'recording-off':
      body = (
        <div data-spot-state="recording-off">
          <EmptyState icon="mdi:pause-circle-outline" message={RECORDING_OFF_SENTENCE} />
        </div>
      );
      break;
    case 'no-spot':
      body = (
        <div data-spot-state="no-spot">
          <EmptyState icon="mdi:server-off" message="No spot nodes in this cluster" />
        </div>
      );
      break;
    case 'none':
      body = (
        <div data-spot-state="none">
          <EmptyState icon="mdi:check-circle-outline" message={`No interruptions recorded in the last ${days} days`} />
        </div>
      );
      break;
    default:
      body = (
        <div className="flex flex-col gap-2" data-spot-state="events">
          {data?.fleet?.spotNodes === 0 && <p className={`text-[12px] ${FAINT}`}>No spot nodes in this cluster now.</p>}
          <K8sDataTable<SpotEventRow>
            rows={rows}
            columns={columns(now)}
            rowKey={(r) => r.key}
            caption="Spot interruption events"
            filters={FILTERS}
            exportName="spot-interruptions"
            initialSort={{ columnId: 'time', direction: 'desc' }}
            onRowActivate={(r) => setOpenKey(r.key)}
            rowLabel={(r) => r.event.nodeName}
            emptyState={<p className={`px-4 py-8 text-center text-[12px] ${MUTED}`}>No event matches these filters.</p>}
          />
          {data?.truncated && (
            <p className={`text-[11px] ${FAINT}`} data-spot-truncated="">
              {`Showing the newest ${SPOT_INTERRUPTIONS_LIMIT}. Narrow the window to see older events.`}
            </p>
          )}
        </div>
      );
  }

  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it
    // alone. Read-only: the only request is the GET of the history; the window and Retry read it again.
    <div data-spot-interruptions-page="" data-k8s-kit="">
      <PageTemplate title="Spot interruptions" width="table" intro={INTRO} hero={hero}>
        <div className={`flex flex-col gap-2 border-t ${RULE} pt-4`} data-spot-controls="">
          <WindowPills days={days} onChange={setDays} />
          {data && (
            <p className={`flex flex-wrap items-center gap-x-1 text-[12px] ${FAINT}`} data-spot-coverage="">
              <SourceLabel source="XamOps agent snapshot" at={data.coverage.agentSnapshotAt} missingText="not received" now={now} />
              <span>(every 5 min) ·</span>
              <SourceLabel source="Automation agent report" at={data.coverage.automationReportAt} missingText="not received" now={now} />
              <span className="w-full">{COVERAGE_NOTE}</span>
            </p>
          )}
          {data && !data.recordingEnabled && state !== 'recording-off' && (
            <p className={`text-[12px] ${FAINT}`} data-spot-recording-off="">Recording is turned off on this XamOps deployment.</p>
          )}
        </div>
        {body}
        <p className={`text-[11px] ${FAINT}`} data-spot-source="">
          Source: XamOps&apos;s Spot interruption history (GET /api/eks/autoscaling/{'{cluster}'}/spot-interruptions), kept for{' '}
          {data ? `${data.retentionDays} days` : 'a year'}. Recovery is measured at snapshot granularity (about every 5 min), as a range.
        </p>
      </PageTemplate>
      {open && (
        <DetailDrawer
          open
          onClose={() => setOpenKey(null)}
          title={open.node}
          subtitle={open.notice.label}
          sections={drawerSections(open.event, open)}
        />
      )}
    </div>
  );
}

export default SpotInterruptionsPage;
