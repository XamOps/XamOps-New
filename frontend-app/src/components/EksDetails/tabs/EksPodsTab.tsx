/**
 * Workloads › Pods (docs/cloudk8s/ui-revamp-design.md §10.4, D10, D14, §14.1; plan item 24).
 *
 * Status counts, then every pod the agent reported in a K8sDataTable: search, phase and namespace
 * filters, CSV, and each pod's containers on expand. Requests are summed from the containers ("Unset"
 * when none sets one); usage comes only from the top-20 lists (`topCpuPods`, `topMemoryPods`) and is
 * "—" for every other pod, never a request standing in for usage. The template-built colour classes
 * and Font Awesome icons of the old tab are gone (NIT 9).
 */
import { useMemo } from 'react';
import { useK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import {
  clusterPods, sumRequests, topPodCpuUsage, topPodMemoryUsageMi, USAGE_TOP_PODS_ONLY, type ClusterPod,
} from '../../../utils/k8sDerivations';
import { DASH, ageSortValue, formatAge, formatCpu, formatMemoryMiB } from '../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../CloudK8s/shared/K8sDataTable';
import { SourceLabel } from '../../CloudK8s/shared/SourceLabel';
import { StatusMark, type StatusState } from '../../CloudK8s/shared/StatusMark';

export const PODS_CAPTION = 'Pods';
const PHASES = ['Running', 'Pending', 'Failed', 'Succeeded'] as const;
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

interface PodRow {
  pod: ClusterPod;
  key: string;
  cpuRequestM: number | null;
  memRequestMi: number | null;
  cpuUsedM: number | null;
  memUsedMi: number | null;
}

/** A pod's phase as a status mark: Running, Pending (or Unschedulable), Failed, Completed. */
export function podPhaseMark(pod: Pick<ClusterPod, 'phase' | 'unschedulable'>): { state: StatusState; label: string } {
  switch (pod.phase) {
    case 'Running': return { state: 'healthy', label: 'Running' };
    case 'Pending': return pod.unschedulable ? { state: 'attention', label: 'Unschedulable' } : { state: 'attention', label: 'Pending' };
    case 'Failed': return { state: 'error', label: 'Failed' };
    case 'Succeeded': return { state: 'not-set-up', label: 'Completed' };
    default: return { state: 'unknown', label: pod.phase ?? 'Unknown' };
  }
}

/** A container's state: running, waiting with its reason, terminated. */
export function containerStateMark(state: string | null, waitingReason: string | null): { state: StatusState; label: string } {
  switch ((state ?? '').toLowerCase()) {
    case 'running': return { state: 'healthy', label: 'Running' };
    case 'waiting': return { state: 'attention', label: waitingReason ? `Waiting · ${waitingReason}` : 'Waiting' };
    case 'terminated': return { state: 'not-set-up', label: 'Terminated' };
    default: return { state: 'unknown', label: state ?? 'Unknown' };
  }
}

const requestValue = (pod: ClusterPod, resource: 'cpu' | 'memory'): number | null => {
  const sum = sumRequests(pod.containers);
  const unset = resource === 'cpu' ? sum.cpuUnset : sum.memUnset;
  if (sum.containers === 0 || unset === sum.containers) return null;
  return resource === 'cpu' ? sum.cpuRequestM : sum.memRequestMi;
};

function ContainersDetail({ pod }: { pod: ClusterPod }) {
  if (pod.containers.length === 0) return <p className={secondary}>The agent reported no containers for this pod.</p>;
  return (
    <div className="space-y-2">
      <dl className="flex flex-wrap gap-x-6 gap-y-1 text-[12px]">
        <div className="flex gap-1"><dt className="text-[rgba(0,0,0,0.6)]">Pod IP</dt><dd className="font-mono">{pod.podIp ?? DASH}</dd></div>
        <div className="flex gap-1"><dt className="text-[rgba(0,0,0,0.6)]">Owner</dt><dd>{pod.owner ? `${pod.owner.kind} ${pod.owner.name}` : DASH}</dd></div>
        <div className="flex gap-1"><dt className="text-[rgba(0,0,0,0.6)]">QoS</dt><dd>{pod.qosClass ?? DASH}</dd></div>
      </dl>
      <table className="w-full text-[12px]">
        <caption className="sr-only">{`Containers of ${pod.name}`}</caption>
        <thead>
          <tr className="text-left text-[rgba(0,0,0,0.6)]">
            <th scope="col" className="py-1 pr-3 font-semibold">Container</th>
            <th scope="col" className="py-1 pr-3 font-semibold">State</th>
            <th scope="col" className="py-1 pr-3 font-semibold">Restarts</th>
            <th scope="col" className="py-1 pr-3 font-semibold">CPU request / limit</th>
            <th scope="col" className="py-1 pr-3 font-semibold">Memory request / limit</th>
            <th scope="col" className="py-1 font-semibold">Image</th>
          </tr>
        </thead>
        <tbody>
          {pod.containers.map((c) => {
            const mark = containerStateMark(c.state, c.waitingReason);
            return (
              <tr key={c.name} className="border-t border-[rgba(0,0,0,0.06)] align-top">
                <td className="py-1.5 pr-3 font-semibold">{c.name}</td>
                <td className="py-1.5 pr-3"><StatusMark state={mark.state} label={mark.label} /></td>
                <td className="py-1.5 pr-3 tabular-nums">{c.restartCount}</td>
                <td className="py-1.5 pr-3 tabular-nums">{`${c.cpuRequestM === null ? 'Unset' : formatCpu(c.cpuRequestM)} / ${c.cpuLimitM === null ? 'None' : formatCpu(c.cpuLimitM)}`}</td>
                <td className="py-1.5 pr-3 tabular-nums">{`${c.memRequestMi === null ? 'Unset' : formatMemoryMiB(c.memRequestMi)} / ${c.memLimitMi === null ? 'None' : formatMemoryMiB(c.memLimitMi)}`}</td>
                <td className="break-all py-1.5 font-mono text-[rgba(0,0,0,0.6)]">{c.image ?? DASH}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function EksPodsTab({ eksData, clusterName }: { eksData: UseEksDashboardReturn; clusterName: string }) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
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

  const columns: ReadonlyArray<ColumnDef<PodRow>> = [
    {
      id: 'name', header: 'Pod', priority: 'essential',
      cell: (r) => (
        <span className="flex max-w-[320px] flex-col">
          <span className="truncate font-semibold text-[rgba(0,0,0,0.88)]" title={r.pod.name}>{r.pod.name}</span>
          <span className={secondary}>{r.pod.namespace}</span>
        </span>
      ),
      sortValue: (r) => r.pod.name, csv: (r) => r.pod.name,
    },
    { id: 'namespace', header: 'Namespace', priority: 'low', hideBelowPx: 1600, cell: (r) => r.pod.namespace, sortValue: (r) => r.pod.namespace },
    {
      id: 'status', header: 'Status', priority: 'essential',
      cell: (r) => { const m = podPhaseMark(r.pod); return <StatusMark state={m.state} label={m.label} />; },
      sortValue: (r) => r.pod.phase, csv: (r) => podPhaseMark(r.pod).label,
    },
    { id: 'node', header: 'Node', cell: (r) => <span className="font-mono text-[12px]">{r.pod.nodeName ?? DASH}</span>, sortValue: (r) => r.pod.nodeName },
    {
      id: 'restarts', header: 'Restarts', align: 'right',
      cell: (r) => <span className={r.pod.restarts > 5 ? 'font-semibold' : undefined}>{r.pod.restarts}</span>,
      sortValue: (r) => r.pod.restarts,
    },
    { id: 'cpuReq', header: 'CPU requests', align: 'right', cell: (r) => (r.cpuRequestM === null ? 'Unset' : formatCpu(r.cpuRequestM)), sortValue: (r) => r.cpuRequestM },
    { id: 'memReq', header: 'Memory requests', align: 'right', cell: (r) => (r.memRequestMi === null ? 'Unset' : formatMemoryMiB(r.memRequestMi)), sortValue: (r) => r.memRequestMi },
    {
      id: 'cpuUsed', header: 'CPU used', headerTitle: USAGE_TOP_PODS_ONLY, align: 'right',
      cell: (r) => (r.cpuUsedM === null ? <span title={USAGE_TOP_PODS_ONLY}>{DASH}</span> : formatCpu(r.cpuUsedM)), sortValue: (r) => r.cpuUsedM,
    },
    {
      id: 'memUsed', header: 'Memory used', headerTitle: USAGE_TOP_PODS_ONLY, align: 'right',
      cell: (r) => (r.memUsedMi === null ? <span title={USAGE_TOP_PODS_ONLY}>{DASH}</span> : formatMemoryMiB(r.memUsedMi)), sortValue: (r) => r.memUsedMi,
    },
    { id: 'age', header: 'Age', align: 'right', cell: (r) => formatAge(r.pod.age), sortValue: (r) => ageSortValue(r.pod.age), csv: (r) => r.pod.age },
  ];

  const filters: ReadonlyArray<FilterDef<PodRow>> = [
    { id: 'phase', label: 'Status', options: PHASES.map((p) => ({ value: p, label: p === 'Succeeded' ? 'Completed' : p })), match: (r, v) => r.pod.phase === v },
    { id: 'namespace', label: 'Namespace', options: namespaces.map((n) => ({ value: n, label: n })), match: (r, v) => r.pod.namespace === v },
  ];

  return (
    <div className="space-y-3" data-panel="pods">
      <dl className="flex flex-wrap gap-3">
        <div className="min-w-[120px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5" data-count="Total">
          <dt className={secondary}>Total</dt>
          <dd className="text-[20px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{rows.length}</dd>
        </div>
        {counts.map(({ phase, n }) => (
          <div key={phase} className="min-w-[120px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5" data-count={phase}>
            <dt className={secondary}>{phase === 'Succeeded' ? 'Completed' : phase}</dt>
            <dd className={`text-[20px] tabular-nums text-[rgba(0,0,0,0.88)] ${phase === 'Failed' && n > 0 ? 'font-bold' : 'font-semibold'}`}>{n}</dd>
          </div>
        ))}
      </dl>
      <K8sDataTable
        rows={rows}
        columns={columns}
        rowKey={(r) => r.key}
        caption={PODS_CAPTION}
        search={(r) => `${r.pod.name} ${r.pod.namespace} ${r.pod.nodeName ?? ''}`}
        searchPlaceholder="Search pods, namespaces, nodes"
        filters={filters}
        initialSort={{ columnId: 'name', direction: 'asc' }}
        exportName={`${clusterName}-pods`}
        expandRow={(r) => <ContainersDetail pod={r.pod} />}
        rowLabel={(r) => r.pod.name}
        source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no pods.</p>}
      />
    </div>
  );
}
