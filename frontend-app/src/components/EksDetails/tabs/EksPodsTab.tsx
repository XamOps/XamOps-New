/**
 * Workloads › Pods (docs/cloudk8s/ui-revamp-design.md §8, §10.4, D10, D14, §14.1; FEAT-003).
 *
 * The flagship v3 table slice: every pod the agent reported, on PageTemplate width='table' (~1200px)
 * with a MetricStrip of phase counts as the hero. The table uses the DataTable preset — fixed column
 * widths with truncation + a title tooltip on the long image and node strings, right-aligned numbers,
 * a sticky header, hover + visible focus, a StatusDot for the phase column, and an inline CPU/memory
 * MiniBar from the usage feed (the top-20 pod snapshot) with "—" + a tooltip when a pod isn't in it —
 * never a request standing in for usage.
 *
 * Activating a row opens a right-side DetailDrawer built by `rowToSections(pod)` from REAL pod data:
 * Identity (name, namespace, node, QoS, phase, owner, pod IP), Containers (each container's FULL
 * untruncated image, cpu/mem request & limit, state, restarts), and Utilisation (cpu/mem used now
 * from the top-20 feed, "—" + tooltip otherwise). The Cost section (per-pod $ share) is a named
 * backend GAP — WorkloadCostDto is per-workload, with no per-pod field — so its datum is a <Dash> and
 * the whole all-empty Cost section is DROPPED by DetailDrawer; no pod $ is ever fabricated.
 *
 * Search, the phase + namespace filters, sort, pagination, CSV and the linked-phase deep link
 * (`phaseFromUrl`) are all preserved through the DataTable wrapper's K8sDataTable features.
 */
import { useMemo, useState, type ReactNode } from 'react';
import { useK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import {
  clusterPods, sumRequests, topPodCpuUsage, topPodMemoryUsageMi, USAGE_TOP_PODS_ONLY, type ClusterPod,
} from '../../../utils/k8sDerivations';
import { DASH, ageSortValue, formatAge, formatCpu, formatMemoryMiB } from '../../../utils/k8sQuantities';
import { type ColumnDef, type FilterDef } from '../../CloudK8s/shared/K8sDataTable';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { StatusDot } from '../../CloudK8s/shared/status/StatusDot';
import { MiniBar } from '../../CloudK8s/shared/charts/MiniBar';
import { SourceLabel } from '../../CloudK8s/shared/SourceLabel';
import { type StatusState } from '../../CloudK8s/shared/StatusMark';
import type { Tone } from '../../CloudK8s/shared/status/statusTones';
import type { DrawerSection, DrawerDatum } from '../../CloudK8s/shared/DetailDrawer';
import { SECONDARY, BODY } from '../../CloudK8s/shared/typeScale';

export const PODS_CAPTION = 'Pods';
const PHASES = ['Running', 'Pending', 'Failed', 'Succeeded'] as const;

/** Per-pod $ share is a backend GAP: WorkloadCostDto is per-workload, with no per-pod field. */
export const POD_COST_GAP = 'Per-pod cost isn’t reported yet (WorkloadCostDto is per-workload).';

/**
 * The Status filter a link asked for with `phase=` (Overview's "View pending pods"), read once
 * when the table mounts; section links drop the parameter (useEksSectionRoute.VIEW_FILTER_PARAMS).
 */
function phaseFromUrl(): string | undefined {
  const phase = new URLSearchParams(window.location.search).get('phase');
  return PHASES.find((p) => p === phase);
}

interface PodRow {
  pod: ClusterPod;
  key: string;
  cpuRequestM: number | null;
  memRequestMi: number | null;
  cpuUsedM: number | null;
  memUsedMi: number | null;
}

/** A pod's phase as a status mark: Running, Pending (or Unschedulable), Failed, Completed. */
export function podPhaseTone(pod: Pick<ClusterPod, 'phase' | 'unschedulable'>): { tone: Tone; label: string } {
  switch (pod.phase) {
    case 'Running': return { tone: 'good', label: 'Running' };
    case 'Pending': return pod.unschedulable ? { tone: 'bad', label: 'Unschedulable' } : { tone: 'warn', label: 'Pending' };
    case 'Failed': return { tone: 'bad', label: 'Failed' };
    case 'Succeeded': return { tone: 'off', label: 'Completed' };
    default: return { tone: 'info', label: pod.phase ?? 'Unknown' };
  }
}

/** A container's state: running, waiting with its reason, terminated. Shared with EksContainersTab. */
export function containerStateMark(state: string | null, waitingReason: string | null): { state: StatusState; label: string } {
  switch ((state ?? '').toLowerCase()) {
    case 'running': return { state: 'healthy', label: 'Running' };
    case 'waiting': return { state: 'attention', label: waitingReason ? `Waiting · ${waitingReason}` : 'Waiting' };
    case 'terminated': return { state: 'not-set-up', label: 'Terminated' };
    default: return { state: 'unknown', label: state ?? 'Unknown' };
  }
}

/** A container's state as a status tone. */
function containerStateTone(state: string | null, waitingReason: string | null): { tone: Tone; label: string } {
  const mark = containerStateMark(state, waitingReason);
  const toneByState: Record<string, Tone> = { healthy: 'good', attention: 'warn', error: 'bad', 'not-set-up': 'off', unknown: 'info' };
  return { tone: toneByState[mark.state] ?? 'info', label: mark.label };
}

const requestValue = (pod: ClusterPod, resource: 'cpu' | 'memory'): number | null => {
  const sum = sumRequests(pod.containers);
  const unset = resource === 'cpu' ? sum.cpuUnset : sum.memUnset;
  if (sum.containers === 0 || unset === sum.containers) return null;
  return resource === 'cpu' ? sum.cpuRequestM : sum.memRequestMi;
};

/** A right-aligned number cell: the usage MiniBar under the figure, "—" + tooltip when unknown. */
function UsageCell({ used, request, formatted }: { used: number | null; request: number | null; formatted: string }) {
  if (used === null) {
    return (
      <span className={SECONDARY} title={USAGE_TOP_PODS_ONLY}>{DASH}</span>
    );
  }
  // The bar scales to the request when there is one (so used-vs-reserved reads at a glance), else to
  // the used value itself. The figure carries the number; the bar is aria-hidden decoration.
  const max = request && request > 0 ? Math.max(request, used) : used;
  return (
    <span className="inline-flex flex-col items-end gap-1 tabular-nums">
      <span>{formatted}</span>
      <MiniBar value={used} max={max} kind="used" width={56} height={6} className="ml-auto" />
    </span>
  );
}

/** The Containers drawer section: each container's FULL untruncated image and its requests / limits. */
function containerSections(pod: ClusterPod): DrawerSection[] {
  return pod.containers.map((c): DrawerSection => {
    const mark = containerStateTone(c.state, c.waitingReason);
    const data: DrawerDatum[] = [
      { label: 'State', value: <StatusDot tone={mark.tone} label={mark.label} /> },
      { label: 'Restarts', value: <span className="tabular-nums">{c.restartCount}</span> },
      {
        label: 'CPU request / limit',
        value: <span className="tabular-nums">{`${c.cpuRequestM === null ? 'Unset' : formatCpu(c.cpuRequestM)} / ${c.cpuLimitM === null ? 'None' : formatCpu(c.cpuLimitM)}`}</span>,
      },
      {
        label: 'Memory request / limit',
        value: <span className="tabular-nums">{`${c.memRequestMi === null ? 'Unset' : formatMemoryMiB(c.memRequestMi)} / ${c.memLimitMi === null ? 'None' : formatMemoryMiB(c.memLimitMi)}`}</span>,
      },
      {
        label: 'Image',
        value: c.image ? <span className={`block break-all font-mono ${SECONDARY}`}>{c.image}</span> : null,
        reason: 'The agent reported no image for this container.',
      },
    ];
    return { title: `Container · ${c.name}`, data };
  });
}

export default function EksPodsTab({ eksData, clusterName }: { eksData: UseEksDashboardReturn; clusterName: string }) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
  const [linkedPhase] = useState(phaseFromUrl);
  const rows = useMemo((): PodRow[] => {
    const cpu = topPodCpuUsage(dashboard);
    const mem = topPodMemoryUsageMi(dashboard);
    return clusterPods(dashboard).map((pod) => {
      const key = `${pod.namespace}/${pod.name}`;
      return {
        pod, key,
        cpuRequestM: requestValue(pod, 'cpu'),
        memRequestMi: requestValue(pod, 'memory'),
        cpuUsedM: cpu.get(key) ?? null,
        memUsedMi: mem.get(key) ?? null,
      };
    });
  }, [dashboard]);

  const counts = PHASES.map((phase) => ({ phase, n: rows.filter((r) => r.pod.phase === phase).length }));
  const namespaces = [...new Set(rows.map((r) => r.pod.namespace))].sort();
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;

  const stripCells: MetricCell[] = [
    { label: 'Total', value: rows.length },
    ...counts.map(({ phase, n }): MetricCell => ({ label: phase === 'Succeeded' ? 'Completed' : phase, value: n })),
  ];

  const columns: ReadonlyArray<ColumnDef<PodRow>> = [
    {
      id: 'name', header: 'Pod', priority: 'essential', width: '260px',
      cell: (r) => (
        <span className="flex min-w-0 flex-col">
          <span className={`truncate font-semibold ${BODY}`} title={r.pod.name}>{r.pod.name}</span>
          <span className={`truncate ${SECONDARY}`} title={r.pod.namespace}>{r.pod.namespace}</span>
        </span>
      ),
      sortValue: (r) => r.pod.name, csv: (r) => r.pod.name,
    },
    {
      id: 'status', header: 'Status', priority: 'essential', width: '140px',
      cell: (r) => { const m = podPhaseTone(r.pod); return <StatusDot tone={m.tone} label={m.label} />; },
      sortValue: (r) => r.pod.phase, csv: (r) => podPhaseTone(r.pod).label,
    },
    {
      id: 'node', header: 'Node', width: '200px',
      cell: (r) => <span className={`block truncate font-mono text-[12px]`} title={r.pod.nodeName ?? undefined}>{r.pod.nodeName ?? DASH}</span>,
      sortValue: (r) => r.pod.nodeName, csv: (r) => r.pod.nodeName ?? '',
    },
    {
      id: 'restarts', header: 'Restarts', align: 'right', width: '96px',
      cell: (r) => <span className={`tabular-nums ${r.pod.restarts > 5 ? 'font-semibold' : ''}`.trim()}>{r.pod.restarts}</span>,
      sortValue: (r) => r.pod.restarts, csv: (r) => r.pod.restarts,
    },
    {
      id: 'cpuReq', header: 'CPU requests', align: 'right', width: '120px',
      cell: (r) => <span className="tabular-nums">{r.cpuRequestM === null ? 'Unset' : formatCpu(r.cpuRequestM)}</span>,
      sortValue: (r) => r.cpuRequestM, csv: (r) => (r.cpuRequestM === null ? 'Unset' : formatCpu(r.cpuRequestM)),
    },
    {
      id: 'memReq', header: 'Memory requests', align: 'right', width: '136px',
      cell: (r) => <span className="tabular-nums">{r.memRequestMi === null ? 'Unset' : formatMemoryMiB(r.memRequestMi)}</span>,
      sortValue: (r) => r.memRequestMi, csv: (r) => (r.memRequestMi === null ? 'Unset' : formatMemoryMiB(r.memRequestMi)),
    },
    {
      id: 'cpuUsed', header: 'CPU used', headerTitle: USAGE_TOP_PODS_ONLY, align: 'right', width: '124px',
      cell: (r) => <UsageCell used={r.cpuUsedM} request={r.cpuRequestM} formatted={formatCpu(r.cpuUsedM)} />,
      sortValue: (r) => r.cpuUsedM, csv: (r) => (r.cpuUsedM === null ? '' : formatCpu(r.cpuUsedM)),
    },
    {
      id: 'memUsed', header: 'Memory used', headerTitle: USAGE_TOP_PODS_ONLY, align: 'right', width: '136px',
      cell: (r) => <UsageCell used={r.memUsedMi} request={r.memRequestMi} formatted={formatMemoryMiB(r.memUsedMi)} />,
      sortValue: (r) => r.memUsedMi, csv: (r) => (r.memUsedMi === null ? '' : formatMemoryMiB(r.memUsedMi)),
    },
    {
      id: 'age', header: 'Age', align: 'right', width: '88px',
      cell: (r) => <span className="tabular-nums">{formatAge(r.pod.age)}</span>, sortValue: (r) => ageSortValue(r.pod.age), csv: (r) => r.pod.age ?? '',
    },
  ];

  const filters: ReadonlyArray<FilterDef<PodRow>> = [
    {
      id: 'phase', label: 'Status', options: PHASES.map((p) => ({ value: p, label: p === 'Succeeded' ? 'Completed' : p })),
      match: (r, v) => r.pod.phase === v, defaultValue: linkedPhase,
    },
    { id: 'namespace', label: 'Namespace', options: namespaces.map((n) => ({ value: n, label: n })), match: (r, v) => r.pod.namespace === v },
  ];

  /** The real-data drawer for a pod (§8.2): Identity, a section per Container, Utilisation. */
  const rowToSections = (r: PodRow): DrawerSection[] => {
    const pod = r.pod;
    const phase = podPhaseTone(pod);
    const identity: DrawerSection = {
      title: 'Identity',
      data: [
        { label: 'Name', value: <span className="break-all">{pod.name}</span> },
        { label: 'Namespace', value: pod.namespace },
        { label: 'Node', value: pod.nodeName ? <span className="break-all font-mono text-[12px]">{pod.nodeName}</span> : null, reason: 'The agent reported no node for this pod.' },
        { label: 'Phase', value: <StatusDot tone={phase.tone} label={phase.label} /> },
        { label: 'QoS class', value: pod.qosClass, reason: 'The agent reported no QoS class for this pod.' },
        { label: 'Owner', value: pod.owner ? `${pod.owner.kind} ${pod.owner.name}` : null, reason: 'This pod has no controller owner.' },
        { label: 'Pod IP', value: pod.podIp ? <span className="font-mono text-[12px]">{pod.podIp}</span> : null, reason: 'The agent reported no pod IP.' },
      ],
    };

    const utilisation: DrawerSection = {
      title: 'Utilisation',
      data: [
        {
          label: 'CPU used now',
          value: r.cpuUsedM === null ? null : <span className="tabular-nums">{formatCpu(r.cpuUsedM)}</span>,
          reason: USAGE_TOP_PODS_ONLY,
        },
        {
          label: 'Memory used now',
          value: r.memUsedMi === null ? null : <span className="tabular-nums">{formatMemoryMiB(r.memUsedMi)}</span>,
          reason: USAGE_TOP_PODS_ONLY,
        },
      ],
    };

    // Cost (per-pod $ share): a named backend GAP — WorkloadCostDto is per-workload, with no per-pod
    // field. Every datum is a <Dash>, so DetailDrawer DROPS the whole Cost section. Never a fake $.
    const cost: DrawerSection = {
      title: 'Cost',
      data: [{ label: 'Monthly cost share', value: null, reason: POD_COST_GAP }],
    };

    return [identity, ...containerSections(pod), utilisation, cost];
  };

  const intro: ReactNode = 'Every pod the XamOps agent reported. Open a pod for its containers and utilisation.';

  return (
    <PageTemplate
      title="Pods"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} aria-label="Pods by phase" />}
    >
      <div data-panel="pods" className="flex flex-col gap-3">
        {/* Phase counts are also exposed as data-count tiles for the count assertions / deep links. */}
        <dl className="sr-only">
          <div data-count="Total"><dt>Total</dt><dd>{rows.length}</dd></div>
          {counts.map(({ phase, n }) => (
            <div key={phase} data-count={phase}><dt>{phase}</dt><dd>{n}</dd></div>
          ))}
        </dl>
        <DataTable<PodRow>
          rows={rows}
          columns={columns}
          rowKey={(r) => r.key}
          caption={PODS_CAPTION}
          search={(r) => `${r.pod.name} ${r.pod.namespace} ${r.pod.nodeName ?? ''}`}
          searchPlaceholder="Search pods, namespaces, nodes"
          filters={filters}
          initialSort={{ columnId: 'name', direction: 'asc' }}
          exportName={`${clusterName}-pods`}
          rowLabel={(r) => r.pod.name}
          rowTitle={(r) => r.pod.name}
          rowSubtitle={(r) => r.pod.namespace}
          rowToSections={rowToSections}
          source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
          emptyState={<p className={`px-4 py-8 text-center ${SECONDARY}`}>The agent reported no pods.</p>}
        />
      </div>
    </PageTemplate>
  );
}
