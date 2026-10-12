/**
 * Overview › Events (docs/cloudk8s/ui-revamp-design.md §10.2; plan item 24).
 *
 * The Kubernetes events the agent collected (`k8sEvents`), moved here from the old Resources tab:
 * Warning first, then newest first; a Type filter, search, pagination and CSV. Source "XamOps agent ·
 * k8s-resources". Both the agent's shape (`involvedObject`, `objectKind`, `lastTime`) and the older
 * one (`name`, `lastTimestamp`) are read.
 */
import { useMemo } from 'react';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { DASH, formatAge, toNumber } from '../../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../../CloudK8s/shared/K8sDataTable';
import { SourceLabel } from '../../../CloudK8s/shared/SourceLabel';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import type { EksSectionProps } from '../sectionTypes';

export const EVENTS_CAPTION = 'Kubernetes events';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

export interface EventRow {
  key: string;
  type: string;
  reason: string;
  object: string;
  namespace: string;
  message: string;
  count: number | null;
  lastSeen: string | null;
}

const str = (...values: unknown[]): string | null => {
  for (const v of values) if (typeof v === 'string' && v.trim() !== '') return v;
  return null;
};

/** The dashboard's events as rows, Warning first and then newest first. */
export function eventRows(events: unknown): EventRow[] {
  const list = Array.isArray(events) ? events.filter((e): e is Record<string, any> => typeof e === 'object' && e !== null) : [];
  const rows = list.map((e, i): EventRow => {
    const kind = str(e.objectKind, e.involvedObject?.kind);
    const name = str(typeof e.involvedObject === 'string' ? e.involvedObject : e.involvedObject?.name, e.name);
    return {
      key: `${i}`,
      type: str(e.type) ?? 'Unknown',
      reason: str(e.reason) ?? DASH,
      object: name ? (kind ? `${kind}/${name}` : name) : DASH,
      namespace: str(e.namespace, e.involvedObject?.namespace) ?? DASH,
      message: str(e.message) ?? DASH,
      count: toNumber(e.count),
      lastSeen: str(e.lastTime, e.lastTimestamp, e.eventTime, e.firstTime),
    };
  });
  const time = (r: EventRow) => (r.lastSeen ? Date.parse(r.lastSeen) || 0 : 0);
  return rows.sort((a, b) => Number(b.type === 'Warning') - Number(a.type === 'Warning') || time(b) - time(a));
}

const COLUMNS: ReadonlyArray<ColumnDef<EventRow>> = [
  {
    id: 'type', header: 'Type', priority: 'essential',
    cell: (r) => (r.type === 'Warning' ? <StatusMark state="attention" label="Warning" /> : <StatusMark state="not-set-up" label={r.type} />),
    sortValue: (r) => r.type, csv: (r) => r.type,
  },
  { id: 'reason', header: 'Reason', cell: (r) => <span className="font-semibold">{r.reason}</span>, sortValue: (r) => r.reason },
  { id: 'object', header: 'Object', cell: (r) => <span className="font-mono text-[12px]">{r.object}</span>, sortValue: (r) => r.object },
  { id: 'namespace', header: 'Namespace', cell: (r) => r.namespace, sortValue: (r) => r.namespace },
  { id: 'message', header: 'Message', cell: (r) => <span className="block max-w-[480px] break-words">{r.message}</span>, csv: (r) => r.message },
  { id: 'count', header: 'Count', align: 'right', cell: (r) => (r.count === null ? DASH : r.count), sortValue: (r) => r.count },
  { id: 'lastSeen', header: 'Last seen', align: 'right', cell: (r) => (r.lastSeen ? `${formatAge(r.lastSeen)} ago` : DASH), sortValue: (r) => (r.lastSeen ? Date.parse(r.lastSeen) || null : null), csv: (r) => r.lastSeen },
];

const FILTERS: ReadonlyArray<FilterDef<EventRow>> = [
  { id: 'type', label: 'Type', options: [{ value: 'Warning', label: 'Warning' }, { value: 'Normal', label: 'Normal' }], match: (r, v) => r.type === v },
];

export default function EventsPanel({ clusterName, eksData }: EksSectionProps) {
  const caps = useK8sCapabilities();
  const rows = useMemo(() => eventRows(eksData.dashboard?.k8sEvents), [eksData.dashboard]);
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;
  const warnings = rows.filter((r) => r.type === 'Warning').length;
  return (
    <div className="space-y-3" data-panel="events">
      <p className={secondary}>{`${rows.length} events · ${warnings} warnings`}</p>
      <K8sDataTable
        rows={rows}
        columns={COLUMNS}
        rowKey={(r) => r.key}
        caption={EVENTS_CAPTION}
        search={(r) => `${r.reason} ${r.object} ${r.namespace} ${r.message}`}
        searchPlaceholder="Search events"
        filters={FILTERS}
        exportName={`${clusterName}-events`}
        source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no Kubernetes events.</p>}
      />
    </div>
  );
}
