/**
 * Workloads › Containers (docs/cloudk8s/ui-revamp-design.md §10.4, D10, D14, §14.1; plan item 24).
 *
 * Every container of every pod the agent reported, in a K8sDataTable: state, restarts, requests and
 * limits as the pod spec sets them ("Unset" and "None", never 0), and the image. There is no usage
 * column: per-container usage doesn't reach the dashboard (E15), and the old tab showed 0 for it.
 */
import { useMemo } from 'react';
import { useK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import { clusterPods, type ClusterContainer } from '../../../utils/k8sDerivations';
import { DASH, formatCpu, formatMemoryMiB } from '../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../CloudK8s/shared/K8sDataTable';
import { SourceLabel } from '../../CloudK8s/shared/SourceLabel';
import { StatusMark } from '../../CloudK8s/shared/StatusMark';
import { containerStateMark } from './EksPodsTab';

export const CONTAINERS_CAPTION = 'Containers';
export const NO_CONTAINER_USAGE = "Per-container usage isn't reported to XamOps yet.";
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';
const STATES = [
  { value: 'running', label: 'Running' },
  { value: 'waiting', label: 'Waiting' },
  { value: 'terminated', label: 'Terminated' },
] as const;

interface ContainerRow {
  key: string;
  pod: string;
  namespace: string;
  container: ClusterContainer;
}

const quantity = (value: number | null, format: (v: number) => string, missing: string) => (value === null ? missing : format(value));

export default function EksContainersTab({ eksData, clusterName }: { eksData: UseEksDashboardReturn; clusterName: string }) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
  const rows = useMemo((): ContainerRow[] => clusterPods(dashboard).flatMap((pod) =>
    pod.containers.map((container) => ({ key: `${pod.namespace}/${pod.name}/${container.name}`, pod: pod.name, namespace: pod.namespace, container }))), [dashboard]);
  const namespaces = [...new Set(rows.map((r) => r.namespace))].sort();
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;
  const restarting = rows.filter((r) => r.container.restartCount > 0).length;
  const notReady = rows.filter((r) => r.container.ready === false).length;

  const columns: ReadonlyArray<ColumnDef<ContainerRow>> = [
    {
      id: 'name', header: 'Container', priority: 'essential',
      cell: (r) => (
        <span className="flex max-w-[320px] flex-col">
          <span className="font-semibold text-[rgba(0,0,0,0.88)]">{r.container.name}</span>
          <span className={`truncate ${secondary}`} title={r.pod}>{`${r.namespace} · ${r.pod}`}</span>
        </span>
      ),
      sortValue: (r) => r.container.name, csv: (r) => r.container.name,
    },
    { id: 'pod', header: 'Pod', priority: 'low', hideBelowPx: 1600, cell: (r) => r.pod, sortValue: (r) => r.pod },
    {
      id: 'state', header: 'State', priority: 'essential',
      cell: (r) => { const m = containerStateMark(r.container.state, r.container.waitingReason); return <StatusMark state={m.state} label={m.label} />; },
      sortValue: (r) => r.container.state, csv: (r) => containerStateMark(r.container.state, r.container.waitingReason).label,
    },
    { id: 'ready', header: 'Ready', cell: (r) => (r.container.ready === null ? DASH : r.container.ready ? 'Yes' : 'No'), sortValue: (r) => (r.container.ready === null ? null : r.container.ready ? 1 : 0) },
    { id: 'restarts', header: 'Restarts', align: 'right', cell: (r) => r.container.restartCount, sortValue: (r) => r.container.restartCount },
    { id: 'cpuReq', header: 'CPU request', align: 'right', cell: (r) => quantity(r.container.cpuRequestM, formatCpu, 'Unset'), sortValue: (r) => r.container.cpuRequestM },
    { id: 'cpuLim', header: 'CPU limit', align: 'right', cell: (r) => quantity(r.container.cpuLimitM, formatCpu, 'None'), sortValue: (r) => r.container.cpuLimitM },
    { id: 'memReq', header: 'Memory request', align: 'right', cell: (r) => quantity(r.container.memRequestMi, formatMemoryMiB, 'Unset'), sortValue: (r) => r.container.memRequestMi },
    { id: 'memLim', header: 'Memory limit', align: 'right', cell: (r) => quantity(r.container.memLimitMi, formatMemoryMiB, 'None'), sortValue: (r) => r.container.memLimitMi },
    { id: 'image', header: 'Image', cell: (r) => <span className="break-all font-mono text-[12px] text-[rgba(0,0,0,0.6)]">{r.container.image ?? DASH}</span>, sortValue: (r) => r.container.image },
  ];

  const filters: ReadonlyArray<FilterDef<ContainerRow>> = [
    { id: 'state', label: 'State', options: STATES, match: (r, v) => (r.container.state ?? '').toLowerCase() === v },
    { id: 'namespace', label: 'Namespace', options: namespaces.map((n) => ({ value: n, label: n })), match: (r, v) => r.namespace === v },
  ];

  return (
    <div className="space-y-3" data-panel="containers">
      <dl className="flex flex-wrap gap-3">
        {[['Containers', rows.length], ['Not ready', notReady], ['Restarted', restarting]].map(([label, n]) => (
          <div key={label} className="min-w-[120px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5" data-count={label}>
            <dt className={secondary}>{label}</dt>
            <dd className="text-[20px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{n}</dd>
          </div>
        ))}
      </dl>
      <p className={secondary}>{NO_CONTAINER_USAGE}</p>
      <K8sDataTable
        rows={rows}
        columns={columns}
        rowKey={(r) => r.key}
        caption={CONTAINERS_CAPTION}
        search={(r) => `${r.container.name} ${r.pod} ${r.namespace} ${r.container.image ?? ''}`}
        searchPlaceholder="Search containers, pods, images"
        filters={filters}
        initialSort={{ columnId: 'name', direction: 'asc' }}
        exportName={`${clusterName}-containers`}
        source={<SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no containers.</p>}
      />
    </div>
  );
}
