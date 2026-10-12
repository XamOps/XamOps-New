/**
 * Nodes › Karpenter (docs/cloudk8s/ui-revamp-design.md §10.5, §14.1; plan item 25).
 *
 * What the agent reports about Karpenter: the controller's health and version, NodePools, EC2
 * NodeClasses (with why one isn't ready) and NodeClaims. "Karpenter not detected" stays for a
 * cluster without it. When the agent reports no Karpenter data but nodes carry Karpenter's labels,
 * the panel lists those NodePools from the labels and says the controller's state isn't reported;
 * the old tab instead built a Karpenter status from the labels and called it healthy, with a made-up
 * consolidation policy. Its per-node daily cost is not shown until a pricing source exists.
 */
import { useMemo, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import type { EC2NodeClass, KarpenterData, NodeClaim, NodePool } from '../../../types/eksTypes';
import { nodeGroups } from '../../../utils/k8sDerivations';
import { DASH, formatAge } from '../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import { StatusMark } from '../../CloudK8s/shared/StatusMark';

export const KARPENTER_NOT_DETECTED = 'Karpenter not detected';
export const KARPENTER_STATE_NOT_REPORTED = "Nodes carry Karpenter's labels, but the agent hasn't reported Karpenter itself, so its controller state is unknown.";
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';
const card = 'rounded-lg border border-[rgba(0,0,0,0.12)] bg-white';

const readyMark = (ready: boolean | undefined | null, yes = 'Ready', no = 'Not ready') =>
  ready === true ? <StatusMark state="healthy" label={yes} /> : ready === false ? <StatusMark state="error" label={no} /> : <StatusMark state="unknown" label="Unknown" />;

const POOL_COLUMNS: ReadonlyArray<ColumnDef<NodePool>> = [
  { id: 'name', header: 'NodePool', priority: 'essential', cell: (p) => <span className="font-semibold">{p.name}</span>, sortValue: (p) => p.name },
  { id: 'ready', header: 'Status', cell: (p) => readyMark(p.ready) },
  { id: 'nodes', header: 'Nodes', align: 'right', cell: (p) => p.nodeCount ?? DASH, sortValue: (p) => p.nodeCount ?? null },
  { id: 'cpu', header: 'CPU limit', align: 'right', cell: (p) => p.cpuLimit || 'None' },
  { id: 'memory', header: 'Memory limit', align: 'right', cell: (p) => p.memoryLimit || 'None' },
];

const CLASS_COLUMNS: ReadonlyArray<ColumnDef<EC2NodeClass>> = [
  { id: 'name', header: 'EC2NodeClass', priority: 'essential', cell: (c) => <span className="font-semibold">{c.name}</span>, sortValue: (c) => c.name },
  {
    id: 'ready', header: 'Status',
    cell: (c) => (
      <span className="flex flex-col gap-0.5">
        {readyMark(c.ready)}
        {!c.ready && c.readyMessage && <span className={secondary}>{c.readyMessage}</span>}
      </span>
    ),
  },
  { id: 'ami', header: 'AMI family', cell: (c) => c.amiFamily || DASH },
  { id: 'profile', header: 'Instance profile', cell: (c) => <span className="font-mono text-[12px]">{c.instanceProfile || DASH}</span> },
];

const CLAIM_COLUMNS: ReadonlyArray<ColumnDef<NodeClaim>> = [
  { id: 'name', header: 'NodeClaim', priority: 'essential', cell: (c) => <span className="font-mono text-[12px] font-semibold">{c.name}</span>, sortValue: (c) => c.name || null },
  { id: 'pool', header: 'NodePool', cell: (c) => c.nodePoolName || DASH, sortValue: (c) => c.nodePoolName || null },
  { id: 'type', header: 'Instance type', cell: (c) => c.instanceType || DASH },
  { id: 'capacity', header: 'Capacity type', cell: (c) => c.capacityType || 'Unknown' },
  { id: 'node', header: 'Node', cell: (c) => <span className="font-mono text-[12px]">{c.nodeName || DASH}</span> },
  {
    id: 'phase', header: 'Phase',
    cell: (c) => (c.phase ? <StatusMark state={/ready|initialized|registered/i.test(c.phase) ? 'healthy' : 'attention'} label={c.phase} /> : <StatusMark state="unknown" label="Unknown" />),
    sortValue: (c) => c.phase || null,
  },
  { id: 'age', header: 'Age', align: 'right', cell: (c) => formatAge(c.createdAt) },
];

function Count({ label, value }: { label: string; value: number | null | undefined }) {
  return (
    <div className="min-w-[120px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5">
      <dt className={secondary}>{label}</dt>
      <dd className="text-[20px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{typeof value === 'number' ? value : DASH}</dd>
    </div>
  );
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-2">
      <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</h3>
      {children}
    </section>
  );
}

export default function EksKarpenterTab({ eksData }: { eksData: UseEksDashboardReturn }) {
  const dashboard = eksData.dashboard;
  const karpenter = (dashboard?.karpenter ?? null) as KarpenterData | null;
  const labelledPools = useMemo(() => nodeGroups(dashboard).filter((g) => g.type === 'Karpenter NodePool'), [dashboard]);

  if (!karpenter) {
    if (labelledPools.length > 0) {
      return (
        <div className="space-y-3" data-panel="karpenter" data-karpenter="labels-only">
          <p className={`${card} flex items-start gap-2 px-4 py-3 text-[13px] text-[rgba(0,0,0,0.88)]`}>
            <Icon icon="mdi:help-circle-outline" width={16} aria-hidden="true" className="mt-0.5 shrink-0" />
            {KARPENTER_STATE_NOT_REPORTED}
          </p>
          <Block title="NodePools from node labels">
            <ul className={`${card} divide-y divide-[rgba(0,0,0,0.06)]`}>
              {labelledPools.map((g) => (
                <li key={g.key} className="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-2.5 text-[13px]">
                  <span className="font-semibold">{g.name}</span>
                  <span className={secondary}>{`${g.ready} of ${g.nodes} nodes ready · ${g.spot} Spot · ${g.onDemand} on-demand`}</span>
                  <span className={secondary}>{g.instanceTypes.join(', ')}</span>
                </li>
              ))}
            </ul>
          </Block>
        </div>
      );
    }
    return (
      <section aria-label={KARPENTER_NOT_DETECTED} className={`${card} p-6`} data-panel="karpenter" data-karpenter="none">
        <div className="flex items-start gap-3">
          <Icon icon="mdi:circle-off-outline" width={22} aria-hidden="true" className="mt-0.5 shrink-0 text-[rgba(0,0,0,0.6)]" />
          <div>
            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{KARPENTER_NOT_DETECTED}</h3>
            <p className="mt-1 text-[13px] text-[rgba(0,0,0,0.6)]">
              The agent found no Karpenter controller and no nodes with Karpenter labels. When Karpenter runs in this
              cluster, its NodePools, EC2NodeClasses and NodeClaims appear here.
            </p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <div className="space-y-5" data-panel="karpenter" data-karpenter="reported">
      <div className={`${card} flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3`}>
        {readyMark(karpenter.healthy, 'Controller healthy', 'Controller unhealthy')}
        <span className="text-[13px] text-[rgba(0,0,0,0.88)]"><span className={secondary}>Version </span>{karpenter.version || DASH}</span>
      </div>
      <dl className="flex flex-wrap gap-3">
        <Count label="NodePools" value={karpenter.nodePools?.length} />
        <Count label="EC2NodeClasses" value={karpenter.ec2NodeClasses?.length} />
        <Count label="Active claims" value={karpenter.totalActiveClaims} />
        <Count label="Pending claims" value={karpenter.totalPendingClaims} />
        <Count label="Spot nodes" value={karpenter.spotNodesCount} />
        <Count label="On-demand nodes" value={karpenter.onDemandNodesCount} />
      </dl>
      <Block title="NodePools">
        <K8sDataTable rows={karpenter.nodePools ?? []} columns={POOL_COLUMNS} rowKey={(p) => p.name} caption="NodePools"
          emptyState={<p className={`px-4 py-6 text-center ${secondary}`}>No NodePools reported.</p>} />
      </Block>
      <Block title="EC2NodeClasses">
        <K8sDataTable rows={karpenter.ec2NodeClasses ?? []} columns={CLASS_COLUMNS} rowKey={(c) => c.name} caption="EC2NodeClasses"
          emptyState={<p className={`px-4 py-6 text-center ${secondary}`}>No EC2NodeClasses reported.</p>} />
      </Block>
      <Block title="NodeClaims">
        <K8sDataTable rows={karpenter.nodeClaims ?? []} columns={CLAIM_COLUMNS} rowKey={(c) => c.name} caption="NodeClaims"
          search={(c) => `${c.name} ${c.nodePoolName ?? ''} ${c.instanceType ?? ''}`}
          emptyState={<p className={`px-4 py-6 text-center ${secondary}`}>No NodeClaims reported.</p>} />
      </Block>
    </div>
  );
}
