/**
 * Nodes › Node groups (docs/cloudk8s/ui-revamp-design.md §10.5; plan item 25).
 *
 * The agent's nodes grouped by their labels: `eks.amazonaws.com/nodegroup` (managed node group), then
 * `karpenter.sh/nodepool` (Karpenter NodePool), else Unmanaged. Per group: nodes ready of total,
 * instance types, on-demand and Spot counts, zones, allocatable CPU and memory, and how much of it the
 * pods request. Scaling limits and AMIs need the EKS node-group API, which XamOps doesn't call yet (F1).
 */
import { useMemo } from 'react';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { nodeGroups, type NodeGroup } from '../../../../utils/k8sDerivations';
import { DASH, formatCpu, formatMemoryMiB } from '../../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import { RequestBar } from '../../../CloudK8s/shared/RequestBar';
import { SourceLabel } from '../../../CloudK8s/shared/SourceLabel';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import type { EksSectionProps } from '../sectionTypes';

export const NODE_GROUPS_CAPTION = 'Node groups';
export const NODE_GROUP_API_NOTE = 'Scaling limits and AMI details need the EKS node-group API (not yet available in XamOps).';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

const requestedOf = (allocatable: number | null, pct: number | null) => (allocatable === null || pct === null ? null : (allocatable * pct) / 100);

const COLUMNS: ReadonlyArray<ColumnDef<NodeGroup>> = [
  {
    id: 'group', header: 'Group', priority: 'essential',
    cell: (g) => (
      <span className="flex flex-col">
        <span className="font-semibold text-[rgba(0,0,0,0.88)]">{g.name}</span>
        <span className={secondary}>{g.type}</span>
      </span>
    ),
    sortValue: (g) => g.name, csv: (g) => g.name,
  },
  {
    id: 'nodes', header: 'Nodes ready', align: 'right',
    cell: (g) => <StatusMark state={g.ready === g.nodes ? 'healthy' : 'attention'} label={`${g.ready} / ${g.nodes}`} />,
    sortValue: (g) => g.nodes, csv: (g) => `${g.ready}/${g.nodes}`,
  },
  { id: 'types', header: 'Instance types', cell: (g) => (g.instanceTypes.length ? g.instanceTypes.join(', ') : DASH), csv: (g) => g.instanceTypes.join(' ') },
  {
    id: 'capacity', header: 'On-demand / Spot',
    cell: (g) => (
      <span className="tabular-nums">
        {`${g.onDemand} / ${g.spot}`}
        {g.capacityUnknown > 0 && <span className={secondary}>{` · ${g.capacityUnknown} unknown`}</span>}
      </span>
    ),
    sortValue: (g) => g.spot,
  },
  { id: 'zones', header: 'Zones', cell: (g) => (g.zones.length ? g.zones.join(', ') : DASH), csv: (g) => g.zones.join(' ') },
  {
    id: 'cpu', header: 'CPU requests',
    cell: (g) => {
      const requested = requestedOf(g.allocatableCpuM, g.cpuRequestedPct);
      return requested === null
        ? <span title="A node in the group doesn't report allocatable">{DASH}</span>
        : <RequestBar requested={formatCpu(requested)} allocatable={formatCpu(g.allocatableCpuM)} pct={g.cpuRequestedPct} />;
    },
    sortValue: (g) => g.cpuRequestedPct,
  },
  {
    id: 'memory', header: 'Memory requests',
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
  return (
    <div className="space-y-3" data-panel="node-groups">
      <p className={secondary}>{NODE_GROUP_API_NOTE}</p>
      <K8sDataTable
        rows={groups}
        columns={COLUMNS}
        rowKey={(g) => g.key}
        caption={NODE_GROUPS_CAPTION}
        exportName={`${clusterName}-node-groups`}
        source={<SourceLabel source="XamOps agent · node labels" at={at} verb="data" missingText="no data yet" />}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no nodes.</p>}
      />
    </div>
  );
}
