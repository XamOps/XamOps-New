/**
 * Nodes › Nodes (docs/cloudk8s/ui-revamp-design.md §10.5, D10, §14.1; plan item 25), shaped like the
 * cast.ai node list: the nodes split by capacity type, status counts, search, filters, and one row per
 * node with its CPU and memory requests as a value and a % bar of allocatable.
 *
 * Capacity type comes from the node's labels (`karpenter.sh/capacity-type`,
 * `eks.amazonaws.com/capacityType`) or the agent's field, and is "Unknown" when nothing says.
 * Requests are summed from the pods scheduled on the node that haven't finished. Status counts are the
 * ones the agent's node data supports: Ready, Not ready, Cordoned (unschedulable) and Unknown, plus
 * nodes under pressure. No price column: that needs a pricing source first.
 */
import { useMemo } from 'react';
import { useK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import { nodeRows, type NodeRow, type NodeStatus } from '../../../utils/k8sDerivations';
import { DASH, ageSortValue, formatAge, formatCpu, formatMemoryMiB } from '../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../CloudK8s/shared/K8sDataTable';
import { RequestBar } from '../../CloudK8s/shared/RequestBar';
import { SourceLabel } from '../../CloudK8s/shared/SourceLabel';
import { StatusMark, type StatusState } from '../../CloudK8s/shared/StatusMark';

export const NODES_CAPTION = 'Nodes';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

export const OFFERING_LABELS = { 'on-demand': 'On-demand', spot: 'Spot', unknown: 'Unknown' } as const;
type Offering = keyof typeof OFFERING_LABELS;
const offeringOf = (n: NodeRow): Offering => n.capacityType ?? 'unknown';

export const NODE_STATUS: Record<NodeStatus, { state: StatusState; label: string }> = {
  ready: { state: 'healthy', label: 'Ready' },
  'not-ready': { state: 'error', label: 'Not ready' },
  cordoned: { state: 'attention', label: 'Cordoned' },
  unknown: { state: 'unknown', label: 'Unknown' },
};

const pressureText = (n: NodeRow) =>
  [n.pressure.memory && 'memory', n.pressure.disk && 'disk', n.pressure.pid && 'PID'].filter(Boolean).join(', ');

function OfferingBadge({ node }: { node: NodeRow }) {
  const offering = offeringOf(node);
  if (offering === 'unknown') {
    return <span className={secondary} title={node.capacityTypeRaw ? `Reported as ${node.capacityTypeRaw}` : 'No capacity-type label'}>Unknown</span>;
  }
  return (
    <span className={`rounded border px-1.5 py-px text-[12px] font-semibold uppercase tracking-wide ${offering === 'spot'
      ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
      : 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.88)] text-white'}`}
    >
      {OFFERING_LABELS[offering]}
    </span>
  );
}

function Count({ label, n, id }: { label: string; n: number; id: string }) {
  return (
    <div className="min-w-[112px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5" data-count={id}>
      <dt className={secondary}>{label}</dt>
      <dd className="text-[20px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{n}</dd>
    </div>
  );
}

export default function EksNodesTab({ eksData, clusterName }: { eksData: UseEksDashboardReturn; clusterName: string }) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
  const rows = useMemo(() => nodeRows(dashboard), [dashboard]);
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;

  const byOffering = (o: Offering) => rows.filter((n) => offeringOf(n) === o).length;
  const byStatus = (s: NodeStatus) => rows.filter((n) => n.status === s).length;
  const pressured = rows.filter((n) => pressureText(n) !== '').length;

  const columns: ReadonlyArray<ColumnDef<NodeRow>> = [
    {
      id: 'name', header: 'Node', priority: 'essential',
      cell: (n) => (
        <span className="flex flex-col">
          <span className="font-mono text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">{n.name}</span>
          <span className={secondary}>{[n.internalIp, n.zone].filter(Boolean).join(' · ') || DASH}</span>
        </span>
      ),
      sortValue: (n) => n.name, csv: (n) => n.name,
    },
    { id: 'created', header: 'Created', cell: (n) => (n.age ? `${formatAge(n.age)} ago` : DASH), sortValue: (n) => ageSortValue(n.age), csv: (n) => n.age },
    {
      id: 'instance', header: 'Instance type',
      cell: (n) => (
        <span className="flex flex-col">
          <span>{n.instanceType ?? DASH}</span>
          {n.group && <span className={secondary}>{n.group}</span>}
        </span>
      ),
      sortValue: (n) => n.instanceType, csv: (n) => n.instanceType,
    },
    { id: 'offering', header: 'Offering', cell: (n) => <OfferingBadge node={n} />, sortValue: (n) => offeringOf(n), csv: (n) => OFFERING_LABELS[offeringOf(n)] },
    {
      id: 'cpu', header: 'CPU requests', headerTitle: 'Requests of the pods on the node, of its allocatable CPU',
      cell: (n) => <RequestBar requested={formatCpu(n.cpuRequestM)} allocatable={n.allocatableCpuM === null ? null : formatCpu(n.allocatableCpuM)} pct={n.cpuRequestedPct} />,
      sortValue: (n) => n.cpuRequestedPct, csv: (n) => n.cpuRequestM,
    },
    {
      id: 'memory', header: 'Memory requests', headerTitle: 'Requests of the pods on the node, of its allocatable memory',
      cell: (n) => <RequestBar requested={formatMemoryMiB(n.memRequestMi)} allocatable={n.allocatableMemMi === null ? null : formatMemoryMiB(n.allocatableMemMi)} pct={n.memRequestedPct} />,
      sortValue: (n) => n.memRequestedPct, csv: (n) => n.memRequestMi,
    },
    { id: 'pods', header: 'Pods', align: 'right', cell: (n) => n.pods, sortValue: (n) => n.pods },
    {
      id: 'status', header: 'Status', priority: 'essential',
      cell: (n) => <StatusMark state={NODE_STATUS[n.status].state} label={NODE_STATUS[n.status].label} detail={pressureText(n) ? `${pressureText(n)} pressure` : null} />,
      sortValue: (n) => n.status, csv: (n) => NODE_STATUS[n.status].label,
    },
  ];

  const filters: ReadonlyArray<FilterDef<NodeRow>> = [
    { id: 'offering', label: 'Offering', options: (Object.keys(OFFERING_LABELS) as Offering[]).map((o) => ({ value: o, label: OFFERING_LABELS[o] })), match: (n, v) => offeringOf(n) === v },
    { id: 'status', label: 'Status', options: (Object.keys(NODE_STATUS) as NodeStatus[]).map((s) => ({ value: s, label: NODE_STATUS[s].label })), match: (n, v) => n.status === v },
  ];

  return (
    <div className="space-y-3" data-panel="nodes">
      <div className="flex flex-wrap gap-6">
        <dl className="flex flex-wrap gap-3" aria-label="Nodes by capacity type">
          <Count label="Total nodes" n={rows.length} id="total" />
          <Count label="On-demand" n={byOffering('on-demand')} id="on-demand" />
          <Count label="Spot" n={byOffering('spot')} id="spot" />
          {byOffering('unknown') > 0 && <Count label="Capacity type unknown" n={byOffering('unknown')} id="unknown-offering" />}
        </dl>
        <dl className="flex flex-wrap gap-3" aria-label="Nodes by status">
          <Count label="Ready" n={byStatus('ready')} id="ready" />
          <Count label="Not ready" n={byStatus('not-ready')} id="not-ready" />
          <Count label="Cordoned" n={byStatus('cordoned')} id="cordoned" />
          <Count label="Under pressure" n={pressured} id="pressure" />
          {byStatus('unknown') > 0 && <Count label="Unknown" n={byStatus('unknown')} id="unknown-status" />}
        </dl>
      </div>
      <K8sDataTable
        rows={rows}
        columns={columns}
        rowKey={(n) => n.name}
        caption={NODES_CAPTION}
        search={(n) => `${n.name} ${n.instanceType ?? ''} ${n.zone ?? ''} ${n.group ?? ''} ${n.internalIp ?? ''}`}
        searchPlaceholder="Search nodes, instance types, zones, groups"
        filters={filters}
        initialSort={{ columnId: 'name', direction: 'asc' }}
        exportName={`${clusterName}-nodes`}
        source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no nodes.</p>}
      />
    </div>
  );
}
