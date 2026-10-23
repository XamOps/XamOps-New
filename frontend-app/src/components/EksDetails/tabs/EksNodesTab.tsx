/**
 * Nodes › Nodes (docs/cloudk8s/ui-revamp-design.md §10.5, D10, §14.1; plan item 25; FEAT-004).
 *
 * The wide-table v3 slice for the Nodes section: every node the agent reported, on PageTemplate
 * width='table' so the table fills the content area (viewport − the sidebar) and scrolls INSIDE
 * itself rather than clipping its last column or leaving a one-sided gap. The capacity / status
 * Count tiles collapse into ONE hero MetricStrip; the raw K8sDataTable becomes a DataTable whose
 * rows open a right-side DetailDrawer of REAL node data (identity, capacity type, CPU / memory
 * requests, pressure, pods — a missing figure is the muted dash, never 0). The black-filled
 * OfferingBadge is gone — capacity type reads as a neutral StatusDot word. RequestBar keeps the
 * inline CPU / memory micro-viz, and the status column stays a StatusMark (dot + word).
 *
 * Capacity type comes from the node's labels (`karpenter.sh/capacity-type`,
 * `eks.amazonaws.com/capacityType`) or the agent's field, and is "Unknown" when nothing says.
 * Requests are summed from the pods scheduled on the node that haven't finished. Status counts are
 * the ones the agent's node data supports: Ready, Not ready, Cordoned (unschedulable) and Unknown,
 * plus nodes under pressure. No price column: that needs a pricing source first.
 */
import { useMemo, type ReactNode } from 'react';
import { useK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import { nodeRows, type NodeRow, type NodeStatus } from '../../../utils/k8sDerivations';
import { DASH, ageSortValue, formatAge, formatCpu, formatMemoryMiB } from '../../../utils/k8sQuantities';
import { type ColumnDef, type FilterDef } from '../../CloudK8s/shared/K8sDataTable';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { RequestBar } from '../../CloudK8s/shared/RequestBar';
import { SourceLabel } from '../../CloudK8s/shared/SourceLabel';
import { StatusDot } from '../../CloudK8s/shared/status/StatusDot';
import { StatusMark, type StatusState } from '../../CloudK8s/shared/StatusMark';
import type { Tone } from '../../CloudK8s/shared/status/statusTones';
import type { DrawerSection } from '../../CloudK8s/shared/DetailDrawer';
import { SECONDARY } from '../../CloudK8s/shared/typeScale';

export const NODES_CAPTION = 'Nodes';

export const OFFERING_LABELS = { 'on-demand': 'On-demand', spot: 'Spot', unknown: 'Unknown' } as const;
type Offering = keyof typeof OFFERING_LABELS;
const offeringOf = (n: NodeRow): Offering => n.capacityType ?? 'unknown';

/** Capacity type as a neutral StatusDot word (no black-filled pill — owner review #4). */
const OFFERING_TONE: Record<Offering, Tone> = { 'on-demand': 'info', spot: 'off', unknown: 'off' };

export const NODE_STATUS: Record<NodeStatus, { state: StatusState; label: string }> = {
  ready: { state: 'healthy', label: 'Ready' },
  'not-ready': { state: 'error', label: 'Not ready' },
  cordoned: { state: 'attention', label: 'Cordoned' },
  unknown: { state: 'unknown', label: 'Unknown' },
};

const pressureText = (n: NodeRow) =>
  [n.pressure.memory && 'memory', n.pressure.disk && 'disk', n.pressure.pid && 'PID'].filter(Boolean).join(', ');

function OfferingDot({ node }: { node: NodeRow }) {
  const offering = offeringOf(node);
  if (offering === 'unknown') {
    return (
      <span
        className={SECONDARY}
        title={node.capacityTypeRaw ? `Reported as ${node.capacityTypeRaw}` : 'No capacity-type label'}
      >
        Unknown
      </span>
    );
  }
  return <StatusDot tone={OFFERING_TONE[offering]} label={OFFERING_LABELS[offering]} />;
}

export default function EksNodesTab({ eksData, clusterName }: { eksData: UseEksDashboardReturn; clusterName: string }) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
  const rows = useMemo(() => nodeRows(dashboard), [dashboard]);
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;

  const byOffering = (o: Offering) => rows.filter((n) => offeringOf(n) === o).length;
  const byStatus = (s: NodeStatus) => rows.filter((n) => n.status === s).length;
  const pressured = rows.filter((n) => pressureText(n) !== '').length;

  const counts = {
    total: rows.length,
    'on-demand': byOffering('on-demand'),
    spot: byOffering('spot'),
    'unknown-offering': byOffering('unknown'),
    ready: byStatus('ready'),
    'not-ready': byStatus('not-ready'),
    cordoned: byStatus('cordoned'),
    pressure: pressured,
    'unknown-status': byStatus('unknown'),
  } as const;

  // The hero MetricStrip: total nodes leads, with the capacity + status split as peer cells. Count
  // tiles that only matter when non-zero (unknown capacity / status) fold into the strip's subs so
  // the strip stays ONE honest horizontal row.
  const stripCells: MetricCell[] = [
    {
      label: 'Nodes',
      value: counts.total,
      sub: counts['unknown-offering'] > 0 ? `${counts['unknown-offering']} capacity unknown` : undefined,
    },
    { label: 'On-demand', value: counts['on-demand'] },
    { label: 'Spot', value: counts.spot },
    { label: 'Ready', value: counts.ready },
    {
      label: 'Not ready',
      value: counts['not-ready'],
      sub: counts.cordoned > 0 ? `${counts.cordoned} cordoned` : undefined,
    },
    { label: 'Under pressure', value: counts.pressure },
  ];

  const columns: ReadonlyArray<ColumnDef<NodeRow>> = [
    {
      id: 'name', header: 'Node', priority: 'essential', width: '260px',
      cell: (n) => (
        <span className="flex min-w-0 flex-col">
          <span className="truncate font-mono text-[12px] font-semibold text-[color:var(--chrome-ink)]" title={n.name}>{n.name}</span>
          <span className={`truncate ${SECONDARY}`}>{[n.internalIp, n.zone].filter(Boolean).join(' · ') || DASH}</span>
        </span>
      ),
      sortValue: (n) => n.name, csv: (n) => n.name,
    },
    { id: 'created', header: 'Created', width: '120px', cell: (n) => (n.age ? `${formatAge(n.age)} ago` : DASH), sortValue: (n) => ageSortValue(n.age), csv: (n) => n.age },
    {
      id: 'instance', header: 'Instance type', width: '180px',
      cell: (n) => (
        <span className="flex min-w-0 flex-col">
          <span className="truncate" title={n.instanceType ?? undefined}>{n.instanceType ?? DASH}</span>
          {n.group && <span className={`truncate ${SECONDARY}`} title={n.group}>{n.group}</span>}
        </span>
      ),
      sortValue: (n) => n.instanceType, csv: (n) => n.instanceType,
    },
    { id: 'offering', header: 'Offering', width: '140px', cell: (n) => <OfferingDot node={n} />, sortValue: (n) => offeringOf(n), csv: (n) => OFFERING_LABELS[offeringOf(n)] },
    {
      id: 'cpu', header: 'CPU requests', headerTitle: 'Requests of the pods on the node, of its allocatable CPU', width: '180px',
      cell: (n) => <RequestBar requested={formatCpu(n.cpuRequestM)} allocatable={n.allocatableCpuM === null ? null : formatCpu(n.allocatableCpuM)} pct={n.cpuRequestedPct} />,
      sortValue: (n) => n.cpuRequestedPct, csv: (n) => n.cpuRequestM,
    },
    {
      id: 'memory', header: 'Memory requests', headerTitle: 'Requests of the pods on the node, of its allocatable memory', width: '180px',
      cell: (n) => <RequestBar requested={formatMemoryMiB(n.memRequestMi)} allocatable={n.allocatableMemMi === null ? null : formatMemoryMiB(n.allocatableMemMi)} pct={n.memRequestedPct} />,
      sortValue: (n) => n.memRequestedPct, csv: (n) => n.memRequestMi,
    },
    { id: 'pods', header: 'Pods', align: 'right', width: '88px', cell: (n) => <span className="tabular-nums">{n.pods}</span>, sortValue: (n) => n.pods },
    {
      id: 'status', header: 'Status', priority: 'essential', width: '160px',
      cell: (n) => <StatusMark state={NODE_STATUS[n.status].state} label={NODE_STATUS[n.status].label} detail={pressureText(n) ? `${pressureText(n)} pressure` : null} />,
      sortValue: (n) => n.status, csv: (n) => NODE_STATUS[n.status].label,
    },
  ];

  const filters: ReadonlyArray<FilterDef<NodeRow>> = [
    { id: 'offering', label: 'Offering', options: (Object.keys(OFFERING_LABELS) as Offering[]).map((o) => ({ value: o, label: OFFERING_LABELS[o] })), match: (n, v) => offeringOf(n) === v },
    { id: 'status', label: 'Status', options: (Object.keys(NODE_STATUS) as NodeStatus[]).map((s) => ({ value: s, label: NODE_STATUS[s].label })), match: (n, v) => n.status === v },
  ];

  /** The real-data drawer for a node: Identity, Capacity, Utilisation. A missing datum is the dash. */
  const rowToSections = (n: NodeRow): DrawerSection[] => {
    const status = NODE_STATUS[n.status];
    const offering = offeringOf(n);
    const identity: DrawerSection = {
      title: 'Identity',
      data: [
        { label: 'Name', value: <span className="break-all font-mono text-[12px]">{n.name}</span> },
        { label: 'Status', value: <StatusMark state={status.state} label={status.label} detail={pressureText(n) ? `${pressureText(n)} pressure` : null} /> },
        { label: 'Instance type', value: n.instanceType },
        { label: 'Group', value: n.group, reason: 'This node carries no node-group or NodePool label.' },
        { label: 'Zone', value: n.zone, reason: 'The agent reported no zone for this node.' },
        { label: 'Internal IP', value: n.internalIp ? <span className="font-mono text-[12px]">{n.internalIp}</span> : null, reason: 'The agent reported no internal IP.' },
        { label: 'Kubelet', value: n.kubeletVersion ? <span className="font-mono text-[12px]">{n.kubeletVersion}</span> : null, reason: 'The agent reported no kubelet version.' },
        { label: 'Age', value: n.age ? `${formatAge(n.age)} ago` : null, reason: 'The agent reported no creation time.' },
      ],
    };
    const capacity: DrawerSection = {
      title: 'Capacity',
      data: [
        {
          label: 'Capacity type',
          value: offering === 'unknown' ? null : <StatusDot tone={OFFERING_TONE[offering]} label={OFFERING_LABELS[offering]} />,
          reason: n.capacityTypeRaw ? `Reported as ${n.capacityTypeRaw}` : 'No capacity-type label on this node.',
        },
        {
          label: 'Allocatable CPU',
          value: n.allocatableCpuM === null ? null : <span className="tabular-nums">{formatCpu(n.allocatableCpuM)}</span>,
          reason: 'The node reported no allocatable CPU.',
        },
        {
          label: 'Allocatable memory',
          value: n.allocatableMemMi === null ? null : <span className="tabular-nums">{formatMemoryMiB(n.allocatableMemMi)}</span>,
          reason: 'The node reported no allocatable memory.',
        },
        { label: 'Pods', value: <span className="tabular-nums">{n.pods}</span> },
      ],
    };
    const utilisation: DrawerSection = {
      title: 'Requests',
      data: [
        {
          label: 'CPU requests',
          value: <RequestBar requested={formatCpu(n.cpuRequestM)} allocatable={n.allocatableCpuM === null ? null : formatCpu(n.allocatableCpuM)} pct={n.cpuRequestedPct} />,
        },
        {
          label: 'Memory requests',
          value: <RequestBar requested={formatMemoryMiB(n.memRequestMi)} allocatable={n.allocatableMemMi === null ? null : formatMemoryMiB(n.allocatableMemMi)} pct={n.memRequestedPct} />,
        },
      ],
    };
    return [identity, capacity, utilisation];
  };

  const intro: ReactNode = 'Every node the XamOps agent reported, split by capacity type and status. Open a node for its capacity and requests.';

  return (
    <PageTemplate
      title="Nodes"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} aria-label="Nodes by capacity type and status" />}
    >
      {/* min-w-0 so the table card shrinks to the content area (viewport − the sidebar) and scrolls
          horizontally inside itself, never clipping the last column (owner review #5). */}
      <div data-panel="nodes" className="flex min-w-0 flex-col gap-3">
        {/* Capacity + status counts are also exposed as hidden data-count tiles for the count
            assertions / deep links (the visible figures live in the hero MetricStrip). */}
        <dl className="sr-only">
          {(Object.keys(counts) as Array<keyof typeof counts>).map((id) => (
            <div key={id} data-count={id}><dt>{id}</dt><dd>{counts[id]}</dd></div>
          ))}
        </dl>
        <DataTable<NodeRow>
          rows={rows}
          columns={columns}
          rowKey={(n) => n.name}
          caption={NODES_CAPTION}
          search={(n) => `${n.name} ${n.instanceType ?? ''} ${n.zone ?? ''} ${n.group ?? ''} ${n.internalIp ?? ''}`}
          searchPlaceholder="Search nodes, instance types, zones, groups"
          filters={filters}
          initialSort={{ columnId: 'name', direction: 'asc' }}
          exportName={`${clusterName}-nodes`}
          rowLabel={(n) => n.name}
          rowTitle={(n) => n.name}
          rowSubtitle={(n) => n.instanceType ?? n.name}
          rowToSections={rowToSections}
          source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
          emptyState={<p className={`px-4 py-8 text-center ${SECONDARY}`}>The agent reported no nodes.</p>}
        />
      </div>
    </PageTemplate>
  );
}
