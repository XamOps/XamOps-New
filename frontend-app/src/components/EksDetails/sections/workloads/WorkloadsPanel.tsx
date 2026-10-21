/**
 * Workloads › Workloads (docs/cloudk8s/ui-revamp-design.md §10.4, D8, D10), shaped like the cast.ai
 * workload list: counts by kind, search, filters by kind, namespace and status, and one row per
 * Deployment, StatefulSet and DaemonSet.
 *
 * Requests are summed from the workload's pods' containers (deployments carry none); CPU usage
 * comes from `topCpuPods`, so it is known only for workloads whose running pods are all in the top
 * 20, and "—" otherwise; efficiency needs both. The recommendation and mode columns join the vertical
 * engine's endpoint 1 on namespace, kind and name: "—" when the engine has no entry, "Not available"
 * where the engine isn't (V7, V6, V8). No price or cost column: that needs a pricing source first.
 */
import { useMemo } from 'react';
import { Icon } from '@iconify/react';
import { useVerticalRecommendations, type VerticalRead } from '../../../../hooks/useVerticalRecommendations';
import type { VerticalWorkload } from '../../../../types/verticalRecommendations';
import {
  USAGE_TOP_PODS_ONLY, workloadKey, workloadRows, type WorkloadKind, type WorkloadRow,
} from '../../../../utils/k8sDerivations';
import { DASH, formatCpu, formatMemoryMiB, formatPercent } from '../../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef, type FilterDef } from '../../../CloudK8s/shared/K8sDataTable';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import { SourceLabel } from '../../../CloudK8s/shared/SourceLabel';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { EYEBROW, HERO_NUMBER, KPI_NUMBER } from '../../../CloudK8s/shared/typeScale';
import { findingText, modeText, skipReasonText } from '../automation/verticalCopy';
import type { EksSectionProps } from '../sectionTypes';

export const WORKLOADS_CAPTION = 'Workloads';
export const NOT_AVAILABLE = 'Not available';
const KINDS: readonly WorkloadKind[] = ['Deployment', 'StatefulSet', 'DaemonSet'];
const PLURAL: Record<WorkloadKind, string> = { Deployment: 'Deployments', StatefulSet: 'StatefulSets', DaemonSet: 'DaemonSets' };

const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

/** What the table says above the rows about recommendations, per engine state (§8.1). */
export function engineLine(read: VerticalRead): string | null {
  switch (read.state) {
    case 'loading': return 'Loading recommendations…';
    case 'V7': return "Recommendations aren't turned on in this environment.";
    case 'V6': return "The vertical rightsizer isn't available in this environment.";
    case 'V8': return "This cluster isn't registered for recommendations.";
    case 'V5': return "Couldn't load recommendations.";
    case 'no-access': return "You don't have access to this account's recommendations.";
    case 'V0': return 'No execution path: recommendations are shown, nothing can be applied.';
    case 'V2': return 'No usage metrics, so no recommendations yet.';
    case 'V3': return 'Collecting usage history before recommending.';
    default:
      return read.view?.allWorkloadsOff
        ? "Workloads start Off, so the engine doesn't evaluate them yet. Recommendations appear once a workload is set to Recommend."
        : null;
  }
}

const sum = (values: Array<number | null>) => (values.some((v) => v === null) ? null : values.reduce((s: number, v) => s + (v as number), 0));

/**
 * "Per pod: CPU 256m → 400m · Memory 512 MiB → 640 MiB": the requests that change, summed over the
 * pod template's containers. The engine recommends per pod; the requests column sums every pod.
 */
export function recommendationText(w: VerticalWorkload): string {
  if (w.skipReason) return skipReasonText(w.skipReason);
  const changed = w.containers.filter((c) => c.recommended !== null);
  if (changed.length === 0) return 'No change recommended';
  const parts: string[] = [];
  const cpuFrom = sum(changed.map((c) => c.current.cpuRequestM));
  const cpuTo = sum(changed.map((c) => c.recommended!.cpuRequestM));
  if (cpuTo !== null && cpuFrom !== cpuTo) parts.push(`CPU ${cpuFrom === null ? 'unset' : formatCpu(cpuFrom)} → ${formatCpu(cpuTo)}`);
  const memFrom = sum(changed.map((c) => c.current.memRequestMi));
  const memTo = sum(changed.map((c) => c.recommended!.memRequestMi));
  if (memTo !== null && memFrom !== memTo) parts.push(`Memory ${memFrom === null ? 'unset' : formatMemoryMiB(memFrom)} → ${formatMemoryMiB(memTo)}`);
  return parts.length > 0 ? `Per pod: ${parts.join(' · ')}` : 'Requests unchanged';
}

function RecommendationCell({ read, entry }: { read: VerticalRead; entry: VerticalWorkload | undefined }) {
  if (read.state === 'loading') return <span className={secondary}>Loading…</span>;
  if (read.state === 'V7' || read.state === 'V6' || read.state === 'V8' || read.state === 'no-access') return <span className={secondary}>{NOT_AVAILABLE}</span>;
  if (read.state === 'V5') return <span className={secondary}>Couldn't load</span>;
  if (read.state === 'V2') return <span className={secondary}>No recommendation · no usage metrics</span>;
  if (!entry) return <span>{DASH}</span>;
  return (
    <span className="flex flex-col gap-1">
      <span>{recommendationText(entry)}</span>
      {entry.findings.length > 0 && (
        <span className="flex flex-wrap gap-1">
          {entry.findings.map((f) => {
            const label = findingText(f);
            return (
              <span key={label} className="rounded border border-[rgba(0,0,0,0.2)] px-1.5 py-px text-[12px] text-[rgba(0,0,0,0.88)]">{label}</span>
            );
          })}
        </span>
      )}
    </span>
  );
}

const requestText = (row: WorkloadRow, resource: 'cpu' | 'memory'): string => {
  const req = row.requests;
  if (!req) return DASH;
  const unset = resource === 'cpu' ? req.cpuUnset : req.memUnset;
  const value = resource === 'cpu' ? req.cpuRequestM : req.memRequestMi;
  if (unset === req.containers) return 'Unset';
  const shown = resource === 'cpu' ? formatCpu(value) : formatMemoryMiB(value);
  return unset > 0 ? `${shown} · ${unset} unset` : shown;
};

const isReady = (row: WorkloadRow): boolean | null =>
  row.ready === null || row.desired === null ? null : row.ready >= row.desired;

export default function WorkloadsPanel({ clusterName, accountId, region, eksData }: EksSectionProps) {
  const dashboard = eksData.dashboard;
  const caps = useK8sCapabilities();
  const read = useVerticalRecommendations(clusterName, accountId, region);
  const rows = useMemo(() => workloadRows(dashboard), [dashboard]);
  const engine = useMemo(() => new Map((read.view?.workloads ?? []).map((w) => [workloadKey(w.ref.namespace, w.ref.kind, w.ref.name), w])), [read.view]);

  const counts = KINDS.map((kind) => ({ kind, n: rows.filter((r) => r.kind === kind).length }));
  const namespaces = [...new Set(rows.map((r) => r.namespace))].sort();
  const resourcesAt = caps.forCluster(clusterName)?.dataSources.find((s) => s.type === 'k8s-resources')?.collectedAt ?? null;

  const columns: ReadonlyArray<ColumnDef<WorkloadRow>> = [
    {
      id: 'name', header: 'Workload', priority: 'essential',
      cell: (r) => (
        <span className="flex flex-col">
          <span className="font-semibold text-[rgba(0,0,0,0.88)]">{r.name}</span>
          <span className={secondary}>{r.kind}</span>
        </span>
      ),
      sortValue: (r) => r.name, csv: (r) => r.name,
    },
    { id: 'namespace', header: 'Namespace', cell: (r) => <span className="font-mono text-[12px]">{r.namespace}</span>, sortValue: (r) => r.namespace },
    {
      id: 'status', header: 'Replicas', priority: 'essential',
      cell: (r) => {
        const ready = isReady(r);
        const value = r.ready === null || r.desired === null ? DASH : `${r.ready} / ${r.desired}`;
        return ready === null
          ? <StatusMark state="unknown" label={value} />
          : <StatusMark state={ready ? 'healthy' : 'attention'} label={value} detail={ready ? null : 'not all ready'} />;
      },
      sortValue: (r) => r.ready, csv: (r) => (r.ready === null || r.desired === null ? null : `${r.ready}/${r.desired}`),
    },
    {
      id: 'cpu', header: 'CPU requests', headerTitle: "Summed from the workload's pods' containers", align: 'right',
      cell: (r) => requestText(r, 'cpu'), sortValue: (r) => r.requests?.cpuRequestM ?? null, csv: (r) => r.requests?.cpuRequestM ?? null,
    },
    {
      id: 'memory', header: 'Memory requests', headerTitle: "Summed from the workload's pods' containers", align: 'right',
      cell: (r) => requestText(r, 'memory'), sortValue: (r) => r.requests?.memRequestMi ?? null, csv: (r) => r.requests?.memRequestMi ?? null,
    },
    {
      id: 'usage', header: 'CPU used', headerTitle: USAGE_TOP_PODS_ONLY, align: 'right',
      cell: (r) => (r.cpuUsageM === null ? <span title={USAGE_TOP_PODS_ONLY}>{DASH}</span> : formatCpu(r.cpuUsageM)),
      sortValue: (r) => r.cpuUsageM,
    },
    {
      id: 'efficiency', header: 'Efficiency', headerTitle: 'CPU used ÷ CPU requested, when both are known', align: 'right',
      cell: (r) => formatPercent(r.efficiencyPct), sortValue: (r) => r.efficiencyPct,
    },
    {
      id: 'recommendation', header: 'Recommendation', headerTitle: 'XamOps vertical rightsizer',
      cell: (r) => <RecommendationCell read={read} entry={engine.get(r.key)} />,
      csv: (r) => {
        const entry = engine.get(r.key);
        return read.view && entry ? recommendationText(entry) : null;
      },
    },
    {
      id: 'mode', header: 'Mode', headerTitle: "The engine's per-workload mode",
      cell: (r) => {
        const entry = engine.get(r.key);
        return read.view && entry ? modeText(entry.policy.mode) : DASH;
      },
      sortValue: (r) => {
        const entry = engine.get(r.key);
        return read.view && entry ? modeText(entry.policy.mode) : null;
      },
    },
  ];

  const filters: ReadonlyArray<FilterDef<WorkloadRow>> = [
    { id: 'kind', label: 'Kind', options: KINDS.map((k) => ({ value: k, label: k })), match: (r, v) => r.kind === v },
    { id: 'namespace', label: 'Namespace', options: namespaces.map((n) => ({ value: n, label: n })), match: (r, v) => r.namespace === v },
    {
      id: 'status', label: 'Status',
      options: [{ value: 'ready', label: 'Ready' }, { value: 'degraded', label: 'Not all ready' }],
      match: (r, v) => (v === 'ready' ? isReady(r) === true : isReady(r) === false),
    },
  ];

  const line = engineLine(read);

  // DESIGN §4.1: the hero is the total workloads this tab exists to state (a view-layer sum of the
  // same `counts` roll-up, no new hook); the per-kind counts become the divide-x strip below it,
  // with their labels and `data-count` hooks unchanged.
  const totalWorkloads = counts.reduce((s, c) => s + c.n, 0);

  return (
    <div className="space-y-8" data-panel="workloads">
      <section aria-label="Workloads" className="space-y-4">
        <div>
          <p className={EYEBROW}>Workloads</p>
          <p className={`mt-1 ${HERO_NUMBER} text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]`}>{totalWorkloads}</p>
          <p className={`mt-1 ${secondary}`}>Deployments, StatefulSets and DaemonSets the agent reported</p>
        </div>
        <dl className="grid grid-cols-1 border-t border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] sm:grid-cols-3 sm:divide-x divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]">
          {counts.map(({ kind, n }) => (
            <div key={kind} className="py-2.5 sm:px-4 sm:py-0" data-count={kind}>
              <dt className={secondary}>{PLURAL[kind]}</dt>
              <dd className={`${KPI_NUMBER} text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]`}>{n}</dd>
            </div>
          ))}
        </dl>
      </section>

      {line && (
        <p role="status" className="flex flex-wrap items-center gap-2 text-[13px] text-[rgba(0,0,0,0.6)]" data-engine-state={read.state}>
          <Icon icon="mdi:information-outline" width={16} aria-hidden="true" />
          {line}
          {read.state === 'V5' && (
            <button
              type="button"
              onClick={read.retry}
              className="rounded border border-[rgba(0,0,0,0.2)] bg-white px-2 py-0.5 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)]"
            >
              Retry
            </button>
          )}
        </p>
      )}

      <K8sDataTable
        rows={rows}
        columns={columns}
        rowKey={(r) => r.key}
        caption={WORKLOADS_CAPTION}
        search={(r) => `${r.name} ${r.namespace} ${r.kind}`}
        searchPlaceholder="Search workloads"
        filters={filters}
        initialSort={{ columnId: 'namespace', direction: 'asc' }}
        exportName={`${clusterName}-workloads`}
        source={<SourceLabel source="XamOps agent · k8s-resources" at={resourcesAt} verb="data" missingText="no data yet" />}
        emptyState={<p className={secondary}>The agent reported no Deployments, StatefulSets or DaemonSets.</p>}
      />
    </div>
  );
}
