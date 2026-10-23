/**
 * Nodes › Node groups (docs/cloudk8s/ui-revamp-design.md §10.5; plan item 25; FEAT-004).
 *
 * The agent's nodes grouped by their labels: `eks.amazonaws.com/nodegroup` (managed node group),
 * then `karpenter.sh/nodepool` (Karpenter NodePool), else Unmanaged. Per group: nodes ready of
 * total, instance types, on-demand and Spot counts, zones, allocatable CPU and memory, and how much
 * of it the pods request. Scaling limits and AMIs need the EKS node-group API, which XamOps doesn't
 * call yet (F1).
 *
 * v3 roll-out: the table is now a DataTable (width='table' PageTemplate) whose rows open a
 * DetailDrawer of the group's real breakdown; nodes-ready and capacity read as StatusDot / StatusMark
 * (dot + word), chrome is the --chrome-* tokens, and radii stay in {8px, 6px, pill}.
 */
import { useMemo, type ReactNode } from 'react';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { nodeGroups, type NodeGroup } from '../../../../utils/k8sDerivations';
import { DASH, formatCpu, formatMemoryMiB } from '../../../../utils/k8sQuantities';
import { type ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import { DataTable } from '../../../CloudK8s/shared/DataTable';
import { PageTemplate } from '../../../CloudK8s/shared/PageTemplate';
import { RequestBar } from '../../../CloudK8s/shared/RequestBar';
import { SourceLabel } from '../../../CloudK8s/shared/SourceLabel';
import { StatusDot } from '../../../CloudK8s/shared/status/StatusDot';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import type { DrawerSection } from '../../../CloudK8s/shared/DetailDrawer';
import { SECONDARY } from '../../../CloudK8s/shared/typeScale';
import type { EksSectionProps } from '../sectionTypes';

export const NODE_GROUPS_CAPTION = 'Node groups';
export const NODE_GROUP_API_NOTE = 'Scaling limits and AMI details need the EKS node-group API (not yet available in XamOps).';

const requestedOf = (allocatable: number | null, pct: number | null) => (allocatable === null || pct === null ? null : (allocatable * pct) / 100);

const COLUMNS: ReadonlyArray<ColumnDef<NodeGroup>> = [
  {
    id: 'group', header: 'Group', priority: 'essential', width: '220px',
    cell: (g) => (
      <span className="flex min-w-0 flex-col">
        <span className="truncate font-semibold text-[color:var(--chrome-ink)]" title={g.name}>{g.name}</span>
        <span className={`truncate ${SECONDARY}`}>{g.type}</span>
      </span>
    ),
    sortValue: (g) => g.name, csv: (g) => g.name,
  },
  {
    id: 'nodes', header: 'Nodes ready', align: 'right', width: '140px',
    cell: (g) => <StatusMark state={g.ready === g.nodes ? 'healthy' : 'attention'} label={`${g.ready} / ${g.nodes}`} />,
    sortValue: (g) => g.nodes, csv: (g) => `${g.ready}/${g.nodes}`,
  },
  { id: 'types', header: 'Instance types', width: '200px', cell: (g) => (g.instanceTypes.length ? <span className="block truncate" title={g.instanceTypes.join(', ')}>{g.instanceTypes.join(', ')}</span> : DASH), csv: (g) => g.instanceTypes.join(' ') },
  {
    id: 'capacity', header: 'On-demand / Spot', width: '180px',
    cell: (g) => (
      <span className="tabular-nums">
        {`${g.onDemand} / ${g.spot}`}
        {g.capacityUnknown > 0 && <span className={SECONDARY}>{` · ${g.capacityUnknown} unknown`}</span>}
      </span>
    ),
    sortValue: (g) => g.spot,
  },
  { id: 'zones', header: 'Zones', width: '160px', cell: (g) => (g.zones.length ? <span className="block truncate" title={g.zones.join(', ')}>{g.zones.join(', ')}</span> : DASH), csv: (g) => g.zones.join(' ') },
  {
    id: 'cpu', header: 'CPU requests', width: '180px',
    cell: (g) => {
      const requested = requestedOf(g.allocatableCpuM, g.cpuRequestedPct);
      return requested === null
        ? <span title="A node in the group doesn't report allocatable">{DASH}</span>
        : <RequestBar requested={formatCpu(requested)} allocatable={formatCpu(g.allocatableCpuM)} pct={g.cpuRequestedPct} />;
    },
    sortValue: (g) => g.cpuRequestedPct,
  },
  {
    id: 'memory', header: 'Memory requests', width: '180px',
    cell: (g) => {
      const requested = requestedOf(g.allocatableMemMi, g.memRequestedPct);
      return requested === null
        ? <span title="A node in the group doesn't report allocatable">{DASH}</span>
        : <RequestBar requested={formatMemoryMiB(requested)} allocatable={formatMemoryMiB(g.allocatableMemMi)} pct={g.memRequestedPct} />;
    },
    sortValue: (g) => g.memRequestedPct,
  },
];

export default function NodeGroupsPanel({ clusterName, eksData }: EksSectionProps) {
  const caps = useK8sCapabilities();
  const groups = useMemo(() => nodeGroups(eksData.dashboard), [eksData.dashboard]);
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;

  /** The real-data drawer for a group: Identity + Capacity + Requests. */
  const rowToSections = (g: NodeGroup): DrawerSection[] => {
    const cpuRequested = requestedOf(g.allocatableCpuM, g.cpuRequestedPct);
    const memRequested = requestedOf(g.allocatableMemMi, g.memRequestedPct);
    const identity: DrawerSection = {
      title: 'Identity',
      data: [
        { label: 'Group', value: <span className="break-all font-semibold">{g.name}</span> },
        { label: 'Type', value: <StatusDot tone="off" label={g.type} /> },
        { label: 'Nodes ready', value: <StatusMark state={g.ready === g.nodes ? 'healthy' : 'attention'} label={`${g.ready} / ${g.nodes}`} /> },
        { label: 'Instance types', value: g.instanceTypes.length ? g.instanceTypes.join(', ') : null, reason: 'No node in this group reports an instance type.' },
        { label: 'Zones', value: g.zones.length ? g.zones.join(', ') : null, reason: 'No node in this group reports a zone.' },
      ],
    };
    const capacity: DrawerSection = {
      title: 'Capacity',
      data: [
        { label: 'On-demand nodes', value: <span className="tabular-nums">{g.onDemand}</span> },
        { label: 'Spot nodes', value: <span className="tabular-nums">{g.spot}</span> },
        { label: 'Capacity unknown', value: g.capacityUnknown > 0 ? <span className="tabular-nums">{g.capacityUnknown}</span> : null, reason: 'Every node in this group reports a capacity type.' },
        { label: 'Allocatable CPU', value: g.allocatableCpuM === null ? null : <span className="tabular-nums">{formatCpu(g.allocatableCpuM)}</span>, reason: "A node in the group doesn't report allocatable CPU." },
        { label: 'Allocatable memory', value: g.allocatableMemMi === null ? null : <span className="tabular-nums">{formatMemoryMiB(g.allocatableMemMi)}</span>, reason: "A node in the group doesn't report allocatable memory." },
      ],
    };
    const requests: DrawerSection = {
      title: 'Requests',
      data: [
        {
          label: 'CPU requests',
          value: cpuRequested === null ? null : <RequestBar requested={formatCpu(cpuRequested)} allocatable={formatCpu(g.allocatableCpuM)} pct={g.cpuRequestedPct} />,
          reason: "A node in the group doesn't report allocatable CPU.",
        },
        {
          label: 'Memory requests',
          value: memRequested === null ? null : <RequestBar requested={formatMemoryMiB(memRequested)} allocatable={formatMemoryMiB(g.allocatableMemMi)} pct={g.memRequestedPct} />,
          reason: "A node in the group doesn't report allocatable memory.",
        },
      ],
    };
    return [identity, capacity, requests];
  };

  const intro: ReactNode = NODE_GROUP_API_NOTE;

  return (
    <PageTemplate title="Node groups" intro={intro} width="table">
      <div data-panel="node-groups" className="flex min-w-0 flex-col gap-3">
        <DataTable<NodeGroup>
          rows={groups}
          columns={COLUMNS}
          rowKey={(g) => g.key}
          caption={NODE_GROUPS_CAPTION}
          exportName={`${clusterName}-node-groups`}
          rowLabel={(g) => g.name}
          rowTitle={(g) => g.name}
          rowSubtitle={(g) => g.type}
          rowToSections={rowToSections}
          source={<SourceLabel source="XamOps agent · node labels" at={at} verb="data" missingText="no data yet" />}
          emptyState={<p className={`px-4 py-8 text-center ${SECONDARY}`}>The agent reported no nodes.</p>}
        />
      </div>
    </PageTemplate>
  );
}
