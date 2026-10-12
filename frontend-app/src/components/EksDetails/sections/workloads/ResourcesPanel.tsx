/**
 * Workloads › Resources (docs/cloudk8s/ui-revamp-design.md §4.3, §10.4, §14.1; plan item 24).
 *
 * The old Resources tab's lists, one K8sDataTable each: HPAs, Services, Ingresses, PVCs and Jobs, as
 * the agent's k8s-resources collector reports them. Kubernetes events moved to Overview › Events.
 * Field names differ between the agent's shapes and older payloads (`clusterIp` / `clusterIP`,
 * `cpuTargetPct` / `targetCPUUtilization`, ingress `rules` / a flat `host`), so each reader takes
 * either; a value neither reports is "—".
 */
import { useMemo, type ReactNode } from 'react';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { DASH, ageSortValue, formatAge, toNumber } from '../../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import { SourceLabel } from '../../../CloudK8s/shared/SourceLabel';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import type { EksSectionProps } from '../sectionTypes';

type AnyRecord = Record<string, any>;
const records = (value: unknown): AnyRecord[] =>
  Array.isArray(value) ? value.filter((v): v is AnyRecord => typeof v === 'object' && v !== null) : [];
const str = (...values: unknown[]): string | null => {
  for (const v of values) if (typeof v === 'string' && v.trim() !== '') return v;
  return null;
};
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';
const key = (r: AnyRecord) => `${r.namespace ?? ''}/${r.name ?? ''}`;

const nameColumn: ColumnDef<AnyRecord> = {
  id: 'name', header: 'Name', priority: 'essential',
  cell: (r) => (
    <span className="flex flex-col">
      <span className="font-semibold text-[rgba(0,0,0,0.88)]">{r.name ?? DASH}</span>
      <span className={secondary}>{r.namespace ?? DASH}</span>
    </span>
  ),
  sortValue: (r) => str(r.name), csv: (r) => str(r.name),
};
const namespaceColumn: ColumnDef<AnyRecord> = { id: 'namespace', header: 'Namespace', priority: 'low', hideBelowPx: 1600, cell: (r) => r.namespace ?? DASH, sortValue: (r) => str(r.namespace) };
const ageColumn: ColumnDef<AnyRecord> = { id: 'age', header: 'Age', align: 'right', cell: (r) => formatAge(r.age), sortValue: (r) => ageSortValue(r.age), csv: (r) => str(r.age) };
const num = (v: unknown) => { const n = toNumber(v); return n === null ? DASH : String(n); };
const pct = (v: unknown) => { const n = toNumber(v); return n === null ? DASH : `${n}%`; };

/** "80:8080/TCP, 443" from the agent's port objects, or the older flat string. */
export function servicePorts(svc: AnyRecord): string {
  if (typeof svc.ports === 'string') return svc.ports || DASH;
  const ports = records(svc.ports).map((p) => {
    const target = p.targetPort !== undefined && p.targetPort !== null && String(p.targetPort) !== String(p.port) ? `:${p.targetPort}` : '';
    const node = p.nodePort ? ` (node ${p.nodePort})` : '';
    return `${p.port ?? '?'}${target}${p.protocol ? `/${p.protocol}` : ''}${node}`;
  });
  return ports.length ? ports.join(', ') : DASH;
}

/** An ingress's hosts, from the agent's rules or the older flat host. */
export function ingressHosts(ing: AnyRecord): string {
  const hosts = records(ing.rules).map((r) => str(r.host)).filter((h): h is string => h !== null);
  const flat = str(ing.host);
  const all = hosts.length ? hosts : flat ? [flat] : [];
  return all.length ? all.join(', ') : DASH;
}

const HPA_COLUMNS: ReadonlyArray<ColumnDef<AnyRecord>> = [
  nameColumn, namespaceColumn,
  { id: 'target', header: 'Scales', cell: (r) => (str(r.targetKind) && str(r.targetName) ? `${r.targetKind} ${r.targetName}` : DASH), sortValue: (r) => str(r.targetName) },
  { id: 'range', header: 'Min / max', align: 'right', cell: (r) => `${num(r.minReplicas)} / ${num(r.maxReplicas)}`, sortValue: (r) => toNumber(r.maxReplicas) },
  { id: 'replicas', header: 'Current / desired', align: 'right', cell: (r) => `${num(r.currentReplicas)} / ${num(r.desiredReplicas)}`, sortValue: (r) => toNumber(r.currentReplicas) },
  { id: 'cpuTarget', header: 'CPU target', align: 'right', cell: (r) => pct(r.cpuTargetPct ?? r.targetCPUUtilization), sortValue: (r) => toNumber(r.cpuTargetPct ?? r.targetCPUUtilization) },
  { id: 'cpuNow', header: 'CPU now', align: 'right', cell: (r) => pct(r.currentCpuPct ?? r.currentCPUUtilization), sortValue: (r) => toNumber(r.currentCpuPct ?? r.currentCPUUtilization) },
  ageColumn,
];

const SERVICE_COLUMNS: ReadonlyArray<ColumnDef<AnyRecord>> = [
  nameColumn, namespaceColumn,
  { id: 'type', header: 'Type', cell: (r) => r.type ?? DASH, sortValue: (r) => str(r.type) },
  { id: 'clusterIp', header: 'Cluster IP', cell: (r) => <span className="font-mono text-[12px]">{str(r.clusterIp, r.clusterIP) ?? DASH}</span>, sortValue: (r) => str(r.clusterIp, r.clusterIP) },
  {
    id: 'external', header: 'External',
    cell: (r) => <span className="font-mono text-[12px]">{str(r.loadBalancerHostname, r.loadBalancerIp, r.externalIP, ...(Array.isArray(r.externalIps) ? r.externalIps : [])) ?? DASH}</span>,
    sortValue: (r) => str(r.loadBalancerHostname, r.loadBalancerIp, r.externalIP),
  },
  { id: 'ports', header: 'Ports', cell: (r) => <span className="font-mono text-[12px]">{servicePorts(r)}</span>, csv: (r) => servicePorts(r) },
  ageColumn,
];

const INGRESS_COLUMNS: ReadonlyArray<ColumnDef<AnyRecord>> = [
  nameColumn, namespaceColumn,
  { id: 'class', header: 'Class', cell: (r) => str(r.className, r.ingressClass) ?? DASH, sortValue: (r) => str(r.className, r.ingressClass) },
  { id: 'hosts', header: 'Hosts', cell: (r) => <span className="font-mono text-[12px]">{ingressHosts(r)}</span>, csv: (r) => ingressHosts(r) },
  { id: 'tls', header: 'TLS', cell: (r) => { const tls = r.hasTls ?? r.tls; return typeof tls === 'boolean' ? (tls ? 'Yes' : 'No') : DASH; } },
  ageColumn,
];

const PVC_COLUMNS: ReadonlyArray<ColumnDef<AnyRecord>> = [
  nameColumn, namespaceColumn,
  {
    id: 'status', header: 'Status', priority: 'essential',
    cell: (r) => {
      const status = str(r.status);
      if (status === 'Bound') return <StatusMark state="healthy" label="Bound" />;
      if (status === 'Pending') return <StatusMark state="attention" label="Pending" />;
      if (status === 'Lost') return <StatusMark state="error" label="Lost" />;
      return <StatusMark state="unknown" label={status ?? 'Unknown'} />;
    },
    sortValue: (r) => str(r.status),
  },
  { id: 'capacity', header: 'Capacity', align: 'right', cell: (r) => str(r.capacity) ?? DASH, sortValue: (r) => str(r.capacity) },
  { id: 'class', header: 'Storage class', cell: (r) => str(r.storageClassName, r.storageClass) ?? DASH, sortValue: (r) => str(r.storageClassName, r.storageClass) },
  { id: 'access', header: 'Access', cell: (r) => (Array.isArray(r.accessModes) ? r.accessModes.join(', ') : str(r.accessMode)) ?? DASH },
  ageColumn,
];

const JOB_COLUMNS: ReadonlyArray<ColumnDef<AnyRecord>> = [
  nameColumn, namespaceColumn,
  {
    id: 'status', header: 'Status', priority: 'essential',
    cell: (r) => {
      const failed = toNumber(r.failed) ?? 0;
      const active = toNumber(r.active) ?? 0;
      const succeeded = toNumber(r.succeeded) ?? 0;
      if (failed > 0) return <StatusMark state="error" label="Failed" detail={`${failed}`} />;
      if (active > 0) return <StatusMark state="attention" label="Running" detail={`${active} active`} />;
      if (succeeded > 0) return <StatusMark state="healthy" label="Complete" />;
      return <StatusMark state="unknown" label="Unknown" />;
    },
  },
  { id: 'completions', header: 'Succeeded / completions', align: 'right', cell: (r) => `${num(r.succeeded)} / ${num(r.completions)}` },
  { id: 'started', header: 'Started', align: 'right', cell: (r) => formatAge(r.startTime), sortValue: (r) => ageSortValue(r.startTime) },
  ageColumn,
];

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-2">
      <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</h3>
      {children}
    </section>
  );
}

export default function ResourcesPanel({ clusterName, eksData }: EksSectionProps) {
  const dashboard = eksData.dashboard as AnyRecord | null;
  const caps = useK8sCapabilities();
  const at = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;
  const lists = useMemo(() => ({
    hpas: records(dashboard?.k8sHpas),
    services: records(dashboard?.k8sServices),
    ingresses: records(dashboard?.k8sIngresses),
    pvcs: records(dashboard?.k8sPvcs),
    jobs: records(dashboard?.k8sJobs),
  }), [dashboard]);
  const source = <SourceLabel source="XamOps agent · k8s-resources" at={at} verb="data" missingText="no data yet" />;
  const empty = (what: string) => <p className={`px-4 py-6 text-center ${secondary}`}>{`The agent reported no ${what}.`}</p>;
  const search = (r: AnyRecord) => `${r.name ?? ''} ${r.namespace ?? ''}`;

  return (
    <div className="space-y-5" data-panel="resources">
      <Block title="Horizontal pod autoscalers">
        <K8sDataTable rows={lists.hpas} columns={HPA_COLUMNS} rowKey={key} caption="Horizontal pod autoscalers" search={search} source={source} emptyState={empty('HPAs')} exportName={`${clusterName}-hpas`} />
      </Block>
      <Block title="Services">
        <K8sDataTable rows={lists.services} columns={SERVICE_COLUMNS} rowKey={key} caption="Services" search={search} source={source} emptyState={empty('services')} exportName={`${clusterName}-services`} />
      </Block>
      <Block title="Ingresses">
        <K8sDataTable rows={lists.ingresses} columns={INGRESS_COLUMNS} rowKey={key} caption="Ingresses" search={search} source={source} emptyState={empty('ingresses')} exportName={`${clusterName}-ingresses`} />
      </Block>
      <Block title="Persistent volume claims">
        <K8sDataTable rows={lists.pvcs} columns={PVC_COLUMNS} rowKey={key} caption="Persistent volume claims" search={search} source={source} emptyState={empty('PVCs')} exportName={`${clusterName}-pvcs`} />
      </Block>
      <Block title="Jobs">
        <K8sDataTable rows={lists.jobs} columns={JOB_COLUMNS} rowKey={key} caption="Jobs" search={search} source={source} emptyState={empty('jobs')} exportName={`${clusterName}-jobs`} />
      </Block>
    </div>
  );
}
