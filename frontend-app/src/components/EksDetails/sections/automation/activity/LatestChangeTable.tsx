/**
 * Automation › Activity's table while the activity feed isn't on: each workload's latest recorded
 * change from endpoint 1 (`lastAction`), one row per workload, coloured by result (a chip with its
 * icon and label, never colour alone). It filters by result and by text on the page; the source
 * filter is the page's (the chips above it). When the activity feed answers, the page shows the
 * full history instead and keeps this table as the fallback for a backend that doesn't serve it.
 */
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../../../CloudK8s/shared/K8sDataTable';
import { FeatureDot } from '../../../../CloudK8s/shared/status/FeatureMark';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { RESULT_GROUP_LABELS, istShort, type LatestChangeRow, type ResultGroup } from '../automationDerivations';
import { FAINT, MUTED } from '../automationUi';

export const LATEST_CHANGE_CAPTION = 'Latest change per workload';

const SOURCE_NAME = { workload: 'Workload autoscaler', hpa: 'HPA' } as const;

const COLUMNS: ReadonlyArray<ColumnDef<LatestChangeRow>> = [
  {
    id: 'when',
    header: 'When',
    priority: 'essential',
    cell: (r) => (
      <span className="whitespace-nowrap tabular-nums" title={r.atRaw ?? undefined}>
        {r.at !== null ? istShort(r.at) : DASH}
      </span>
    ),
    sortValue: (r) => r.at,
    csv: (r) => r.atRaw,
  },
  {
    id: 'target',
    header: 'Workload',
    priority: 'essential',
    cell: (r) => (
      <span className="inline-flex flex-col">
        <span className="inline-flex items-center gap-1.5 font-semibold">
          <FeatureDot feature={r.source} />
          {r.workload.ref.name}
        </span>
        <span className={`text-[11px] ${FAINT}`}>
          {r.workload.ref.kind} · {r.workload.ref.namespace} · {SOURCE_NAME[r.source]}
        </span>
      </span>
    ),
    sortValue: (r) => r.workload.ref.name,
    csv: (r) => `${r.workload.ref.namespace}/${r.workload.ref.kind}/${r.workload.ref.name}`,
  },
  { id: 'action', header: 'Action', cell: (r) => r.action, sortValue: (r) => r.action },
  {
    id: 'result',
    header: 'Result',
    priority: 'essential',
    cell: (r) => <StatusChip tone={r.result.tone} icon={r.result.icon} label={r.result.label} />,
    sortValue: (r) => r.result.label,
    csv: (r) => r.result.label,
  },
  {
    id: 'kind',
    header: 'Live or test',
    cell: (r) => <span className={MUTED}>{r.live ? 'Live' : 'Test run'}</span>,
    sortValue: (r) => (r.live ? 'Live' : 'Test run'),
  },
];

const GROUP_ORDER: ResultGroup[] = ['applied', 'test-run', 'deferred', 'refused-failed', 'mode-change', 'other'];

export interface LatestChangeTableProps {
  rows: readonly LatestChangeRow[];
  clusterName: string;
  /** Shown when there are no rows at all. */
  emptyText: string;
}

export function LatestChangeTable({ rows, clusterName, emptyText }: LatestChangeTableProps) {
  const present = GROUP_ORDER.filter((g) => rows.some((r) => r.group === g));
  const filters: ReadonlyArray<FilterDef<LatestChangeRow>> = present.length > 1
    ? [{ id: 'result', label: 'Result', options: present.map((g) => ({ value: g, label: RESULT_GROUP_LABELS[g] })), match: (r, v) => r.group === v }]
    : [];
  return (
    <K8sDataTable
      rows={rows}
      columns={COLUMNS}
      rowKey={(r) => r.id}
      caption={LATEST_CHANGE_CAPTION}
      search={(r) => `${r.workload.ref.name} ${r.workload.ref.namespace} ${r.workload.ref.kind} ${r.result.label} ${r.action}`}
      searchPlaceholder="Search workloads"
      filters={filters}
      exportName={`${clusterName}-latest-changes`}
      initialSort={{ columnId: 'when', direction: 'desc' }}
      emptyState={<p className={`px-4 py-8 text-center text-[12px] ${MUTED}`}>{emptyText}</p>}
    />
  );
}

export default LatestChangeTable;
