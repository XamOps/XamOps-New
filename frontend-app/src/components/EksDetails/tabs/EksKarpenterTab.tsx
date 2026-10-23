/**
 * Nodes › Karpenter (docs/cloudk8s/ui-revamp-design.md §10.5, §14.1; plan item 25; FEAT-004).
 *
 * What the agent reports about Karpenter: the controller's health and version, NodePools, EC2
 * NodeClasses (with why one isn't ready) and NodeClaims. "Karpenter not detected" stays for a
 * cluster without it. When the agent reports no Karpenter data but nodes carry Karpenter's labels,
 * the panel lists those NodePools from the labels and says the controller's state isn't reported;
 * the old tab instead built a Karpenter status from the labels and called it healthy, with a made-up
 * consolidation policy. Its per-node daily cost is not shown until a pricing source exists.
 *
 * v3 roll-out: PageTemplate width='table' hosts the tab; the tier Count tiles collapse into a hero
 * MetricStrip; the NodePools / EC2NodeClasses / NodeClaims lists are DataTables whose rows open a
 * DetailDrawer of their real fields; the controller row and the labels-only NodePools sit on Surface
 * cards (not 'rounded-lg bg-white'); status reads as StatusMark / StatusDot. The no-fabricated-health
 * behaviour is PRESERVED verbatim: KARPENTER_NOT_DETECTED and KARPENTER_STATE_NOT_REPORTED are
 * unchanged, and a controller health / consolidation policy is never invented from labels.
 */
import { useMemo, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import type { EC2NodeClass, KarpenterData, NodeClaim, NodePool } from '../../../types/eksTypes';
import { nodeGroups } from '../../../utils/k8sDerivations';
import { DASH, formatAge } from '../../../utils/k8sQuantities';
import { type ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { Surface } from '../../CloudK8s/shared/Surface';
import { StatusMark } from '../../CloudK8s/shared/StatusMark';
import type { DrawerSection } from '../../CloudK8s/shared/DetailDrawer';
import { TITLE, BODY, SECONDARY } from '../../CloudK8s/shared/typeScale';

export const KARPENTER_NOT_DETECTED = 'Karpenter not detected';
export const KARPENTER_STATE_NOT_REPORTED = "Nodes carry Karpenter's labels, but the agent hasn't reported Karpenter itself, so its controller state is unknown.";

const readyMark = (ready: boolean | undefined | null, yes = 'Ready', no = 'Not ready') =>
  ready === true ? <StatusMark state="healthy" label={yes} /> : ready === false ? <StatusMark state="error" label={no} /> : <StatusMark state="unknown" label="Unknown" />;

const POOL_COLUMNS: ReadonlyArray<ColumnDef<NodePool>> = [
  { id: 'name', header: 'NodePool', priority: 'essential', width: '220px', cell: (p) => <span className="block truncate font-semibold" title={p.name}>{p.name}</span>, sortValue: (p) => p.name },
  { id: 'ready', header: 'Status', width: '160px', cell: (p) => readyMark(p.ready) },
  { id: 'nodes', header: 'Nodes', align: 'right', width: '100px', cell: (p) => <span className="tabular-nums">{p.nodeCount ?? DASH}</span>, sortValue: (p) => p.nodeCount ?? null },
  { id: 'cpu', header: 'CPU limit', align: 'right', width: '120px', cell: (p) => p.cpuLimit || 'None' },
  { id: 'memory', header: 'Memory limit', align: 'right', width: '140px', cell: (p) => p.memoryLimit || 'None' },
];

const CLASS_COLUMNS: ReadonlyArray<ColumnDef<EC2NodeClass>> = [
  { id: 'name', header: 'EC2NodeClass', priority: 'essential', width: '200px', cell: (c) => <span className="block truncate font-semibold" title={c.name}>{c.name}</span>, sortValue: (c) => c.name },
  {
    id: 'ready', header: 'Status', width: '220px',
    cell: (c) => (
      <span className="flex flex-col gap-0.5">
        {readyMark(c.ready)}
        {!c.ready && c.readyMessage && <span className={SECONDARY}>{c.readyMessage}</span>}
      </span>
    ),
  },
  { id: 'ami', header: 'AMI family', width: '140px', cell: (c) => c.amiFamily || DASH },
  { id: 'profile', header: 'Instance profile', width: '200px', cell: (c) => <span className="block truncate font-mono text-[12px]" title={c.instanceProfile || undefined}>{c.instanceProfile || DASH}</span> },
];

const CLAIM_COLUMNS: ReadonlyArray<ColumnDef<NodeClaim>> = [
  { id: 'name', header: 'NodeClaim', priority: 'essential', width: '220px', cell: (c) => <span className="block truncate font-mono text-[12px] font-semibold" title={c.name || undefined}>{c.name}</span>, sortValue: (c) => c.name || null },
  { id: 'pool', header: 'NodePool', width: '160px', cell: (c) => c.nodePoolName || DASH, sortValue: (c) => c.nodePoolName || null },
  { id: 'type', header: 'Instance type', width: '160px', cell: (c) => c.instanceType || DASH },
  { id: 'capacity', header: 'Capacity type', width: '140px', cell: (c) => c.capacityType || 'Unknown' },
  { id: 'node', header: 'Node', width: '200px', cell: (c) => <span className="block truncate font-mono text-[12px]" title={c.nodeName || undefined}>{c.nodeName || DASH}</span> },
  {
    id: 'phase', header: 'Phase', width: '140px',
    cell: (c) => (c.phase ? <StatusMark state={/ready|initialized|registered/i.test(c.phase) ? 'healthy' : 'attention'} label={c.phase} /> : <StatusMark state="unknown" label="Unknown" />),
    sortValue: (c) => c.phase || null,
  },
  { id: 'age', header: 'Age', align: 'right', width: '100px', cell: (c) => formatAge(c.createdAt) },
];

const poolSections = (p: NodePool): DrawerSection[] => [
  {
    title: 'NodePool',
    data: [
      { label: 'Name', value: <span className="break-all font-semibold">{p.name}</span> },
      { label: 'Status', value: readyMark(p.ready) },
      { label: 'Nodes', value: p.nodeCount === null || p.nodeCount === undefined ? null : <span className="tabular-nums">{p.nodeCount}</span>, reason: 'The agent reported no node count for this NodePool.' },
      { label: 'CPU limit', value: p.cpuLimit || 'None' },
      { label: 'Memory limit', value: p.memoryLimit || 'None' },
    ],
  },
];

const classSections = (c: EC2NodeClass): DrawerSection[] => [
  {
    title: 'EC2NodeClass',
    data: [
      { label: 'Name', value: <span className="break-all font-semibold">{c.name}</span> },
      { label: 'Status', value: readyMark(c.ready) },
      { label: 'Why not ready', value: !c.ready && c.readyMessage ? c.readyMessage : null, reason: 'The agent reported no reason.' },
      { label: 'AMI family', value: c.amiFamily || null, reason: 'The agent reported no AMI family.' },
      { label: 'Instance profile', value: c.instanceProfile ? <span className="break-all font-mono text-[12px]">{c.instanceProfile}</span> : null, reason: 'The agent reported no instance profile.' },
    ],
  },
];

const claimSections = (c: NodeClaim): DrawerSection[] => [
  {
    title: 'NodeClaim',
    data: [
      { label: 'Name', value: <span className="break-all font-mono text-[12px]">{c.name}</span> },
      { label: 'Phase', value: c.phase ? <StatusMark state={/ready|initialized|registered/i.test(c.phase) ? 'healthy' : 'attention'} label={c.phase} /> : null, reason: 'The agent reported no phase.' },
      { label: 'NodePool', value: c.nodePoolName || null, reason: 'The agent reported no NodePool.' },
      { label: 'Instance type', value: c.instanceType || null, reason: 'The agent reported no instance type.' },
      { label: 'Capacity type', value: c.capacityType || null, reason: 'The agent reported no capacity type.' },
      { label: 'Zone', value: c.zone || null, reason: 'The agent reported no zone.' },
      { label: 'Node', value: c.nodeName ? <span className="break-all font-mono text-[12px]">{c.nodeName}</span> : null, reason: 'This claim has no node yet.' },
      { label: 'Age', value: c.createdAt ? formatAge(c.createdAt) : null, reason: 'The agent reported no creation time.' },
    ],
  },
];

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h3 className={TITLE}>{title}</h3>
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
        <PageTemplate title="Karpenter" width="table">
          <div data-panel="karpenter" data-karpenter="labels-only" className="flex min-w-0 flex-col gap-3">
            <Surface tier="table" className="px-5 py-3.5">
              <p className={`flex items-start gap-2 ${BODY}`}>
                <Icon icon="mdi:help-circle-outline" width={16} aria-hidden="true" className="mt-0.5 shrink-0" />
                {KARPENTER_STATE_NOT_REPORTED}
              </p>
            </Surface>
            <Block title="NodePools from node labels">
              <Surface tier="table" divide="y" className="overflow-hidden">
                {labelledPools.map((g) => (
                  <div key={g.key} className="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-5 py-2.5">
                    <span className={`font-semibold ${BODY}`}>{g.name}</span>
                    <span className={SECONDARY}>{`${g.ready} of ${g.nodes} nodes ready · ${g.spot} Spot · ${g.onDemand} on-demand`}</span>
                    <span className={SECONDARY}>{g.instanceTypes.join(', ')}</span>
                  </div>
                ))}
              </Surface>
            </Block>
          </div>
        </PageTemplate>
      );
    }
    return (
      <PageTemplate title="Karpenter" width="table">
        <div data-panel="karpenter" data-karpenter="none" className="flex min-w-0 flex-col gap-3">
          <Surface tier="table" as="section" aria-label={KARPENTER_NOT_DETECTED} className="p-6">
            <div className="flex items-start gap-3">
              <Icon icon="mdi:circle-off-outline" width={22} aria-hidden="true" className="mt-0.5 shrink-0 text-[color:var(--chrome-muted)]" />
              <div>
                <h3 className={TITLE}>{KARPENTER_NOT_DETECTED}</h3>
                <p className={`mt-1 ${BODY} text-[color:var(--chrome-muted)]`}>
                  The agent found no Karpenter controller and no nodes with Karpenter labels. When Karpenter runs in this
                  cluster, its NodePools, EC2NodeClasses and NodeClaims appear here.
                </p>
              </div>
            </div>
          </Surface>
        </div>
      </PageTemplate>
    );
  }

  // The hero MetricStrip: the controller's reported counts (null folds to the dash — never 0).
  const countCell = (label: string, value: number | null | undefined): MetricCell => ({
    label,
    value: typeof value === 'number' ? value : <span className="text-[color:var(--chrome-muted)]">{DASH}</span>,
  });
  const stripCells: MetricCell[] = [
    countCell('NodePools', karpenter.nodePools?.length),
    countCell('EC2NodeClasses', karpenter.ec2NodeClasses?.length),
    countCell('Active claims', karpenter.totalActiveClaims),
    countCell('Pending claims', karpenter.totalPendingClaims),
    countCell('Spot nodes', karpenter.spotNodesCount),
    countCell('On-demand nodes', karpenter.onDemandNodesCount),
  ];

  const intro: ReactNode = 'What the XamOps agent reports about Karpenter. Open a NodePool, EC2NodeClass or NodeClaim for its detail.';

  return (
    <PageTemplate
      title="Karpenter"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} aria-label="Karpenter fleet" />}
    >
      <div data-panel="karpenter" data-karpenter="reported" className="flex min-w-0 flex-col gap-6">
        <Surface tier="table" className="flex flex-wrap items-center gap-x-6 gap-y-2 px-5 py-3.5">
          {readyMark(karpenter.healthy, 'Controller healthy', 'Controller unhealthy')}
          <span className={BODY}><span className={SECONDARY}>Version </span>{karpenter.version || DASH}</span>
        </Surface>
        <Block title="NodePools">
          <DataTable<NodePool> rows={karpenter.nodePools ?? []} columns={POOL_COLUMNS} rowKey={(p) => p.name} caption="NodePools"
            rowLabel={(p) => p.name} rowTitle={(p) => p.name} rowToSections={poolSections}
            emptyState={<p className={`px-4 py-6 text-center ${SECONDARY}`}>No NodePools reported.</p>} />
        </Block>
        <Block title="EC2NodeClasses">
          <DataTable<EC2NodeClass> rows={karpenter.ec2NodeClasses ?? []} columns={CLASS_COLUMNS} rowKey={(c) => c.name} caption="EC2NodeClasses"
            rowLabel={(c) => c.name} rowTitle={(c) => c.name} rowToSections={classSections}
            emptyState={<p className={`px-4 py-6 text-center ${SECONDARY}`}>No EC2NodeClasses reported.</p>} />
        </Block>
        <Block title="NodeClaims">
          <DataTable<NodeClaim> rows={karpenter.nodeClaims ?? []} columns={CLAIM_COLUMNS} rowKey={(c) => c.name} caption="NodeClaims"
            search={(c) => `${c.name} ${c.nodePoolName ?? ''} ${c.instanceType ?? ''}`}
            rowLabel={(c) => c.name} rowTitle={(c) => c.name} rowToSections={claimSections}
            emptyState={<p className={`px-4 py-6 text-center ${SECONDARY}`}>No NodeClaims reported.</p>} />
        </Block>
      </div>
    </PageTemplate>
  );
}
