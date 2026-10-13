/**
 * Overview › Summary, laid out like the cast.ai Dashboard (owner's direction, mock 01-overview-light;
 * docs/cloudk8s/ui-revamp-design.md §10.1, D9, D10, §14; glossary §1, §2, §9).
 *
 * Row 1: the cluster, its nodes (capacity types, instance types, node groups, unused requests), its
 * pods (on a node or waiting, with the unschedulable callout) and its cost as the cost source reports
 * it. Row 2: CPU and memory as provisioned, requested, used, allocatable and overhead, and the
 * Workload autoscaler's split of the workloads it analysed. Row 3: what each automation feature is
 * doing, and what needs attention. Then namespace utilisation, the top pods and the data sources.
 *
 * Every figure names its source, and anything unreported renders "—", never 0. Not here yet, on
 * purpose: time-series charts (no history endpoint) and list-price dollars (no pricing source), so
 * the Workload autoscaler card shows capacity it could free instead of $/mo. A cluster XamOps doesn't
 * monitor shows the Not monitored panel. Status and series colours are the semantic tokens
 * (src/styles/k8sSemanticTokens.css); no grey here carries meaning.
 */
import { useMemo, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import { Link, useLocation } from 'react-router-dom';
import { isUnknown } from '../../../../api/verticalRecommendationsAdapter';
import { useK8sCapabilities, type K8sCapabilitiesValue } from '../../../../hooks/useK8sCapabilities';
import { EKS_SECTIONS, LEGACY_TAB_MAP } from '../../../../hooks/useEksSectionRoute';
import { extractAlerts, SEV_ORDER, type EksAlertItem, type EksAlertSeverity } from '../../../../hooks/useEksAlertsData';
import { useVerticalRecommendations, type VerticalRead } from '../../../../hooks/useVerticalRecommendations';
import type { CapabilitiesCluster } from '../../../../types/k8sAutomation';
import type { EksDashboard, EksSectionId, EksSubtabId } from '../../../../types/eksTypes';
import type { Known, VerticalEngineView, VerticalWorkload } from '../../../../types/verticalRecommendations';
import {
  capacityTypeCounts, clusterCapacity, clusterCostSource, clusterNodes, freedCapacity, healthSignals, hpaStates, instanceTypeCounts,
  largestNodeAllocatable, monitoringState, nodeGroups, nodeProvisioners, notRunningWorkloads, podCounts, podPlacement,
  sumRequests, workloadKey, MONTHLY_FROM_DAILY, UNMANAGED_GROUP, type HpaState, type NodeProvisioners, type ResourceCapacity,
} from '../../../../utils/k8sDerivations';
import {
  DASH, formatCpu, formatGib, formatMemoryMiB, formatMoney, formatPercent, formatVcpu, toNumber,
} from '../../../../utils/k8sQuantities';
import { displayVersion, resolveClusterVersion } from '../../../../utils/k8sVersion';
import { inPageState } from '../../../../utils/inPageNavigation';
import { CapacityDonut } from '../../../CloudK8s/shared/CapacityDonut';
import { K8sDataTable, type ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import { NotMonitoredPanel } from '../../../CloudK8s/shared/NotMonitoredPanel';
import { SourceLabel, formatAgo } from '../../../CloudK8s/shared/SourceLabel';
import { StatusMark, type StatusState } from '../../../CloudK8s/shared/StatusMark';
import { bucketCounts, workloadBucket } from '../automation/verticalCopy';
import type { EksSectionProps } from '../sectionTypes';

export const CLUSTER_TITLE = 'Cluster';
export const NODES_TITLE = 'Nodes';
export const PODS_TITLE = 'Pods';
export const COST_TITLE = 'Cost';
export const WORKLOAD_AUTOSCALER = 'Workload autoscaler';
export const NODE_AUTOSCALER = 'Node autoscaler';
export const AUTOMATION_TITLE = 'Automation';
export const ATTENTION_TITLE = 'Needs attention';
export const REVIEW_SUGGESTIONS = 'Review suggestions';
export const WHY_CANT_I_APPLY = "Why can't I apply?";
export const VIEW_PENDING_PODS = 'View pending pods';
export const VIEW_ALL_ALERTS = 'View all in Alerts';
export const ALERTS_HREF = '/alerts?tab=k8s';
export const NOTHING_NEEDS_ATTENTION = "Nothing needs attention in the agent's latest data.";
export const USED_NOT_REPORTED = 'Not reported by Prometheus in your cluster';
export const PROMETHEUS_SOURCE = 'Prometheus (agent)';
export const TRIVY_SOURCE = 'Trivy (agent)';
export const FREED_TOOLTIP = 'XamOps has no reliable price for these nodes, so it shows capacity instead of dollars.';
/** The feature one-liners (glossary §1), shown on each Automation card. */
export const FEATURE_LINES = {
  workload: "Sets each container's CPU and memory requests to what it actually uses, plus headroom. Applying resizes the running pods in place, without a restart.",
  node: 'Adds nodes when pods can\'t be scheduled, removes empty nodes and packs pods onto fewer nodes. XamOps empties and packs nodes; Karpenter or the Cluster Autoscaler adds and removes them.',
  hpa: "Kubernetes Horizontal Pod Autoscalers add and remove replicas as load changes. XamOps shows each one's limits, history and conflicts, and keeps its own changes out of their way.",
  vpa: 'Kubernetes Vertical Pod Autoscaler objects and what they suggest, next to XamOps. XamOps never resizes a workload that a VPA is changing.',
} as const;

/** Readable names for the data types capabilities report; anything else shows its own type. */
const DATA_SOURCE_LABELS: Record<string, string> = {
  'k8s-resources': 'Kubernetes resources',
  'prometheus-metrics': 'Prometheus metrics',
  'opencost-cost': 'OpenCost',
  'trivy-vulnerabilities': 'Trivy',
  'kubescape-compliance': 'Kubescape',
  'falco-events': 'Falco',
  'autoscaling-metrics': 'Automation agent',
};

const card = 'rounded-lg border border-[rgba(0,0,0,0.12)] bg-white';
const cardHeader = 'flex flex-wrap items-center justify-between gap-2 border-b border-[rgba(0,0,0,0.08)] px-4 py-3';
const cardTitle = 'text-[14px] font-semibold text-[rgba(0,0,0,0.88)]';
const eyebrow = 'text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.6)]';
const bigNumber = 'text-[28px] font-semibold leading-tight tabular-nums text-[rgba(0,0,0,0.88)]';
const body = 'text-[13px] text-[rgba(0,0,0,0.88)]';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';
const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';
const linkClass = `inline-flex items-center gap-1 rounded text-[12px] font-semibold text-[rgba(0,0,0,0.88)] underline-offset-2 hover:underline ${focusRing}`;
const primaryButton = `inline-flex items-center gap-1.5 rounded-md bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-black ${focusRing}`;
const secondaryButton = `inline-flex items-center gap-1.5 rounded-md border border-[rgba(0,0,0,0.2)] bg-white px-3 py-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] ${focusRing}`;

type AnyRecord = Record<string, any>;
const records = (value: unknown): AnyRecord[] =>
  Array.isArray(value) ? value.filter((v): v is AnyRecord => typeof v === 'object' && v !== null) : [];
/** A code as the engine sent it, known or not. */
const raw = (value: Known<string>): string => (isUnknown(value) ? value.raw : value);
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

// ── Pure helpers (exported for tests) ────────────────────────────────────────

/** Trivy's critical and high counts, or null when the dashboard carries no Trivy data. */
export function trivyCounts(dashboard: EksDashboard | null | undefined): { critical: number; high: number } | null {
  const summary = dashboard?.trivy?.summary;
  if (!summary) return null;
  const critical = toNumber(summary.totalCritical);
  const high = toNumber(summary.totalHigh);
  return critical === null && high === null ? null : { critical: critical ?? 0, high: high ?? 0 };
}

/** Findings most severe first, then the most frequent. */
export function topFindings(alerts: readonly EksAlertItem[], limit = 5): EksAlertItem[] {
  return [...alerts]
    .sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || (b.count ?? 0) - (a.count ?? 0))
    .slice(0, limit);
}

/**
 * Where a finding's link goes: its legacy tab through the page's own map, except that the events
 * the old Overview tab listed now live on Overview › Events, not on this page.
 */
export function findingTarget(alert: EksAlertItem): { tab: EksSectionId; sub: EksSubtabId; label: string } {
  const target = alert.targetTab === 'overview' ? { tab: 'overview', sub: 'events' } : LEGACY_TAB_MAP[alert.targetTab] ?? LEGACY_TAB_MAP.overview;
  const section = EKS_SECTIONS.find((s) => s.id === target.tab) ?? EKS_SECTIONS[0];
  const sub = section.subs.find((s) => s.id === target.sub) ?? section.subs[0];
  return { tab: section.id, sub: sub.id, label: `${section.label} › ${sub.label}` };
}

export interface FeatureStatus {
  state: StatusState;
  /** The status words (glossary §1.1). */
  status: string;
  detail: string | null;
}

const hasGate = (view: VerticalEngineView, code: string) => view.gateReasons.some((r) => raw(r) === code);

/** The Workload autoscaler's status words (glossary §1.1), by the first row that applies. */
export function workloadAutoscalerStatus(read: VerticalRead, writeGated: boolean): FeatureStatus {
  switch (read.state) {
    case 'loading': return { state: 'unknown', status: 'Checking…', detail: null };
    case 'V5': return { state: 'error', status: "Couldn't load the Workload autoscaler", detail: null };
    case 'V6':
    case 'V7': return { state: 'not-set-up', status: 'Off in this environment', detail: "The Workload autoscaler hasn't been turned on for this XamOps environment." };
    case 'V8': return { state: 'not-set-up', status: 'Not set up for this cluster', detail: "This cluster isn't registered with the Workload autoscaler." };
    case 'no-access': return { state: 'not-set-up', status: 'No access', detail: "You don't have access to this account." };
    default: break;
  }
  const view = read.view;
  if (!view) return { state: 'unknown', status: 'Checking…', detail: null };
  if (hasGate(view, 'NO_METRICS_SOURCE')) return { state: 'attention', status: 'Waiting for usage data', detail: null };
  const direct = view.directPath.reason === null ? null : raw(view.directPath.reason);
  if ((direct === 'CLUSTER_ACCESS_NOT_CONFIGURED' || direct === 'CLUSTER_UNREACHABLE') && view.workloads.length === 0) {
    return { state: 'attention', status: "Can't reach the cluster", detail: null };
  }
  if (!view.ready || hasGate(view, 'CONTROLLER_NOT_READY')) return { state: 'healthy', status: 'On: suggestions only', detail: null };
  if (writeGated) return { state: 'healthy', status: 'On: view only', detail: null };
  if (raw(view.executionPath) !== 'DIRECT') return { state: 'attention', status: "On: can't send changes", detail: null };
  return { state: 'healthy', status: 'On: you can apply suggestions', detail: null };
}

/** Who adds and removes nodes, as the Node autoscaler card's second line says it (glossary §1.1). */
export function provisionerLine(p: NodeProvisioners): string {
  const found = [p.karpenter && 'Karpenter detected', p.clusterAutoscaler && 'Cluster Autoscaler detected'].filter(Boolean);
  return found.length > 0 ? found.join(' · ') : 'No Karpenter or Cluster Autoscaler: nothing adds or removes nodes here.';
}

/** The Node autoscaler's status words (glossary §1.1), from capabilities. */
export function nodeAutoscalerStatus(status: K8sCapabilitiesValue['status'], cluster: CapabilitiesCluster | null, provisioners: NodeProvisioners): FeatureStatus {
  const detail = provisionerLine(provisioners);
  if (status === 'loading') return { state: 'unknown', status: 'Checking…', detail };
  if (status === 'error') return { state: 'unknown', status: 'Unknown', detail };
  const derived = cluster?.registered ? cluster.nodeAutomation.derivedState : 'NOT_INSTALLED';
  const stale = cluster?.automationAgent.status === 'STALE';
  switch (derived) {
    case 'ANALYSIS': return { state: stale ? 'attention' : 'healthy', status: 'Running: analysis only', detail };
    case 'AUTOMATED': return { state: stale ? 'attention' : 'healthy', status: 'Running: moves pods', detail };
    case 'NOT_INSTALLED':
    case 'OFF': return { state: 'not-set-up', status: 'Not running on this cluster', detail };
    default: return { state: 'unknown', status: 'Unknown', detail };
  }
}

/** The HPA card (glossary §1.1). `heldByHpa` is how many workloads the engine leaves to an HPA; null without the engine. */
export function hpaStatus(hpas: readonly HpaState[] | null, heldByHpa: number | null): FeatureStatus {
  const second = heldByHpa === null
    ? "XamOps doesn't suggest HPA settings yet."
    : `XamOps doesn't suggest HPA settings yet. The Workload autoscaler leaves CPU or memory alone on ${plural(heldByHpa, 'workload')} because an HPA scales on it.`;
  if (hpas === null) return { state: 'unknown', status: DASH, detail: 'Not reported by the XamOps agent' };
  if (hpas.length === 0) return { state: 'not-set-up', status: 'No HPAs found', detail: second };
  const allAtMax = hpas.every((h) => h.atMax);
  const atMax = !allAtMax ? '' : hpas.length === 1 ? ', at its maximum' : ', all at their maximum';
  return { state: allAtMax ? 'attention' : 'healthy', status: `${hpas.length} ${hpas.length === 1 ? 'HPA' : 'HPAs'} found${atMax}`, detail: second };
}

export interface AttentionItem {
  id: string;
  icon: string;
  /** The status token class for the icon. */
  tone: string;
  title: string;
  detail: string;
  tab: EksSectionId;
  sub: EksSubtabId;
  /** Extra query, e.g. "phase=Pending". */
  query?: string;
  /** A finding's severity, shown as a word. */
  severity?: EksAlertSeverity;
}

const SEVERITY: Record<EksAlertSeverity, { icon: string; label: string; tone: string }> = {
  critical: { icon: 'mdi:alert-octagon', label: 'Critical', tone: 'text-status-bad' },
  high: { icon: 'mdi:alert-outline', label: 'High', tone: 'text-status-warn' },
  medium: { icon: 'mdi:alert-outline', label: 'Medium', tone: 'text-status-warn' },
  low: { icon: 'mdi:information-outline', label: 'Low', tone: 'text-status-info' },
  info: { icon: 'mdi:information-outline', label: 'Info', tone: 'text-status-info' },
};

const workloadName = (w: VerticalWorkload) => `${w.ref.namespace}/${w.ref.name}`;
const andMore = (n: number) => (n > 1 ? ` +${n - 1} more` : '');

/**
 * What needs attention, most actionable first: what XamOps knows that the alerts don't (pods no
 * node can fit, workloads held back by a limit or running out of memory, HPAs at their maximum),
 * then the cluster's findings by severity. Capped at `limit`.
 */
export function attentionItems(input: {
  dashboard: EksDashboard | null | undefined;
  alerts: readonly EksAlertItem[];
  workloads: readonly VerticalWorkload[];
  limit?: number;
}): AttentionItem[] {
  const { dashboard, alerts, workloads, limit = 6 } = input;
  const items: AttentionItem[] = [];
  const placement = podPlacement(dashboard);
  const largest = largestNodeAllocatable(clusterNodes(dashboard));
  if (placement && placement.unschedulable.length > 0) {
    const pod = placement.unschedulable[0];
    const ask = sumRequests(pod.containers);
    const tooBig = (largest.cpuM !== null && ask.cpuRequestM > largest.cpuM) || (largest.memMi !== null && ask.memRequestMi > largest.memMi);
    items.push({
      id: 'unschedulable', icon: 'mdi:alert-circle-outline', tone: 'text-status-bad',
      title: `${plural(placement.unschedulable.length, "pod can't", "pods can't")} be scheduled`,
      detail: `${pod.namespace}/${pod.name} asks for ${trimVcpu(ask.cpuRequestM)} and ${formatGib(ask.memRequestMi)}`
        + (tooBig ? '; no node is that large.' : '; the scheduler found no node that accepts it.') + andMore(placement.unschedulable.length),
      tab: 'workloads', sub: 'pods', query: 'phase=Pending',
    });
  }
  // The rule-based health signals that need attention (D10), from the same node and pod lists as
  // the strip; unschedulable pods are the item above.
  for (const signal of healthSignals(dashboard)) {
    if (!signal.attention || signal.id === 'unschedulable') continue;
    const onPods = signal.id === 'pods';
    items.push({
      id: `signal-${signal.id}`, icon: signal.id === 'node-pressure' ? 'mdi:clock-alert-outline' : 'mdi:alert-circle-outline',
      tone: signal.id === 'node-pressure' ? 'text-status-warn' : 'text-status-bad',
      title: `${signal.label}: ${signal.value}`, detail: signal.detail ?? '',
      tab: onPods ? 'workloads' : 'nodes', sub: onPods ? 'pods' : 'nodes',
    });
  }
  const finding = (code: string) => workloads.filter((w) => w.findings.some((f) => raw(f) === code));
  const throttled = finding('THROTTLED');
  if (throttled.length > 0) {
    items.push({
      id: 'throttled', icon: 'mdi:timer-sand', tone: 'text-status-warn',
      title: `${plural(throttled.length, 'workload is', 'workloads are')} held back by the CPU limit`,
      detail: `${workloadName(throttled[0])} is CPU throttled. The Workload autoscaler suggests more CPU.${andMore(throttled.length)}`,
      tab: 'automation', sub: 'workload-autoscaling',
    });
  }
  const oom = finding('OOM_RISK');
  if (oom.length > 0) {
    items.push({
      id: 'oom', icon: 'mdi:memory', tone: 'text-status-bad',
      title: `${plural(oom.length, 'workload', 'workloads')} ran out of memory`,
      detail: `${workloadName(oom[0])} had containers killed for using too much memory in the window.${andMore(oom.length)}`,
      tab: 'automation', sub: 'workload-autoscaling',
    });
  }
  const atMax = (hpaStates(dashboard) ?? []).filter((h) => h.atMax);
  if (atMax.length > 0) {
    items.push({
      id: 'hpa-max', icon: 'mdi:arrow-expand-horizontal', tone: 'text-status-warn',
      title: `${plural(atMax.length, 'HPA is', 'HPAs are')} at the maximum`,
      detail: `${atMax[0].namespace}/${atMax[0].name} runs ${atMax[0].current} of ${atMax[0].max} replicas.${andMore(atMax.length)}`,
      tab: 'workloads', sub: 'resources',
    });
  }
  // A finding that says what an item above already says isn't listed twice.
  const has = (id: string) => items.some((i) => i.id === id);
  const repeats = (a: EksAlertItem) => (has('unschedulable') && a.title === 'FailedScheduling')
    || ((has('signal-nodes-ready') || has('signal-node-pressure')) && a.source === 'nodes')
    || (has('signal-pods') && a.id.endsWith('-pods-failed'));
  for (const alert of topFindings(alerts.filter((a) => a.severity !== 'low' && a.severity !== 'info' && !repeats(a)), limit)) {
    const target = findingTarget(alert);
    items.push({
      id: alert.id, icon: SEVERITY[alert.severity].icon, tone: SEVERITY[alert.severity].tone,
      title: alert.count && alert.count > 1 ? `${alert.title} · ${alert.count}×` : alert.title,
      detail: alert.message, tab: target.tab, sub: target.sub, severity: alert.severity,
    });
  }
  return items.slice(0, limit);
}

// ── Pieces ───────────────────────────────────────────────────────────────────

function Card({ title, label, children, className = '', action }: { title: string; label?: string; children: ReactNode; className?: string; action?: ReactNode }) {
  return (
    <section className={`${card} flex flex-col gap-3 p-4 ${className}`} aria-label={label ?? title}>
      <div className="flex items-start justify-between gap-2">
        <h3 className={eyebrow}>{title}</h3>
        {action}
      </div>
      {children}
    </section>
  );
}

function FactRow({ term, children, title }: { term: string; children: ReactNode; title?: string }) {
  return (
    <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-2 border-b border-[rgba(0,0,0,0.06)] py-1.5 last:border-b-0" data-fact={term}>
      <dt className={secondary}>{term}</dt>
      <dd className={`${body} min-w-0 break-words`} title={title}>{children}</dd>
    </div>
  );
}

function Chip({ label, count }: { label: string; count: number }) {
  const filled = count > 0;
  return (
    <li
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] font-semibold tabular-nums ${filled
        ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'border border-[rgba(0,0,0,0.2)] text-[rgba(0,0,0,0.6)]'}`}
      data-capacity-type={label}
    >
      {label}
      <span>{count}</span>
    </li>
  );
}

function FeatureCard({ name, line, status, next }: { name: string; line: string; status: FeatureStatus; next?: ReactNode }) {
  return (
    <section className={`${card} flex flex-col gap-2 p-4`} aria-label={name} data-feature={name}>
      <h4 className={cardTitle}>{name}</h4>
      <p className={secondary}>{line}</p>
      <div className="mt-auto space-y-1 pt-1">
        <StatusMark state={status.state} label={status.status} className="text-[13px]" />
        {status.detail && <p className={secondary}>{status.detail}</p>}
        {next}
      </div>
    </section>
  );
}

interface TopPod {
  name: string;
  namespace: string;
  value: number;
}

function TopPods({ title, pods, format, at }: { title: string; pods: TopPod[]; format: (v: number) => string; at: string | null }) {
  const max = pods.reduce((m, p) => Math.max(m, p.value), 0) || 1;
  return (
    <section className={card} aria-label={title}>
      <div className={cardHeader}>
        <h3 className={cardTitle}>{title}</h3>
        <SourceLabel source={PROMETHEUS_SOURCE} at={at} verb="data" missingText="no data yet" />
      </div>
      <div className="px-4 py-3">
        {pods.length === 0 ? (
          <p className={secondary}>{`${DASH} Not reported by ${PROMETHEUS_SOURCE}`}</p>
        ) : (
          <ol className="space-y-2.5">
            {pods.map((pod) => (
              <li key={`${pod.namespace}/${pod.name}`} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1">
                <span className="flex min-w-0 items-baseline gap-2">
                  <span className="truncate font-mono text-[13px] font-semibold text-[rgba(0,0,0,0.88)]" title={pod.name}>{pod.name}</span>
                  <span className={`truncate ${secondary}`}>{pod.namespace}</span>
                </span>
                <span className="text-right text-[12px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{format(pod.value)}</span>
                <span className="col-span-2 h-1.5 overflow-hidden rounded-full bg-series-allocatable" aria-hidden="true">
                  <span className="block h-full rounded-full bg-series-used" style={{ width: `${Math.min(100, (pod.value / max) * 100)}%` }} />
                </span>
              </li>
            ))}
          </ol>
        )}
        {pods.length > 0 && <p className={`mt-3 ${secondary}`}>Usage is reported for the top 20 pods only.</p>}
      </div>
    </section>
  );
}

interface NamespaceRow {
  namespace: string;
  cpuM: number | null;
  cpuPct: number | null;
  memMi: number | null;
  memPct: number | null;
  pods: number | null;
}

const withPct = (value: string, pct: number | null) => (value === DASH ? DASH : pct === null ? value : `${value} · ${formatPercent(pct)}`);

const NAMESPACE_COLUMNS: ReadonlyArray<ColumnDef<NamespaceRow>> = [
  { id: 'namespace', header: 'Namespace', priority: 'essential', cell: (r) => <span className="font-mono">{r.namespace}</span>, sortValue: (r) => r.namespace },
  { id: 'cpu', header: 'CPU used', align: 'right', cell: (r) => withPct(formatCpu(r.cpuM), r.cpuPct), sortValue: (r) => r.cpuM, csv: (r) => r.cpuM },
  { id: 'memory', header: 'Memory used', align: 'right', cell: (r) => withPct(formatMemoryMiB(r.memMi), r.memPct), sortValue: (r) => r.memMi, csv: (r) => r.memMi },
  { id: 'pods', header: 'Pods', align: 'right', cell: (r) => (r.pods === null ? DASH : r.pods), sortValue: (r) => r.pods },
];

/** "Unused requests: CPU 84% · memory 71%" (glossary §9.1: requested minus used, of requested). */
function unusedLine(cpu: ResourceCapacity, memory: ResourceCapacity): string {
  const unused = (r: ResourceCapacity) => (r.used === null || r.requested <= 0 ? null : Math.max(0, ((r.requested - r.used) / r.requested) * 100));
  const c = unused(cpu);
  const m = unused(memory);
  if (c === null && m === null) return `Unused requests: ${DASH} (usage not reported by Prometheus in your cluster)`;
  return `Unused requests: CPU ${formatPercent(c)} · memory ${formatPercent(m)}`;
}

// ── Panel ────────────────────────────────────────────────────────────────────

export default function SummaryPanel({ clusterName, accountId, region, eksData, route, onOpenGuide }: EksSectionProps) {
  const location = useLocation();
  const caps = useK8sCapabilities();
  const vertical = useVerticalRecommendations(clusterName, accountId, region);
  const dashboard = eksData.dashboard;
  const capsCluster = caps.forCluster(clusterName);
  const monitoring = monitoringState({ capabilitiesStatus: caps.status, cluster: capsCluster, dashboard });

  const derived = useMemo(() => {
    const nodes = clusterNodes(dashboard);
    const overview = dashboard?.clusterOverview as AnyRecord | undefined;
    const podNamespaces = new Set(records(dashboard?.k8sPods).map((p) => p.namespace).filter(Boolean)).size;
    return {
      nodes,
      capacity: clusterCapacity(dashboard),
      placement: podPlacement(dashboard),
      counts: podCounts(dashboard),
      types: capacityTypeCounts(nodes),
      instanceTypes: instanceTypeCounts(nodes),
      groups: nodeGroups(dashboard).map((g) => (g.name === UNMANAGED_GROUP ? 'self-managed' : g.name)),
      zones: new Set(nodes.map((n) => n.zone).filter(Boolean)).size,
      namespaces: toNumber(overview?.totalNamespaces) || podNamespaces || null,
      version: resolveClusterVersion(new URLSearchParams(location.search).get('version'), overview?.version),
      cost: clusterCostSource(dashboard),
      trivy: trivyCounts(dashboard),
      hpas: hpaStates(dashboard),
      provisioners: nodeProvisioners(dashboard),
      alerts: extractAlerts(clusterName, region, accountId, dashboard),
      notRunning: notRunningWorkloads(dashboard),
      topCpu: records(dashboard?.topCpuPods).map((p) => ({ name: String(p.name ?? ''), namespace: String(p.namespace ?? ''), value: toNumber(p.cpuMillicores) }))
        .filter((p): p is TopPod => p.name !== '' && p.value !== null).slice(0, 8),
      topMemory: records(dashboard?.topMemoryPods).map((p) => ({ name: String(p.name ?? ''), namespace: String(p.namespace ?? ''), value: toNumber(p.memoryMB) }))
        .filter((p): p is TopPod => p.name !== '' && p.value !== null).slice(0, 8),
      namespaceRows: records(dashboard?.namespaceUtilization).map((n): NamespaceRow => ({
        namespace: String(n.namespace ?? ''),
        cpuM: toNumber(n.cpuUsageMillicores),
        cpuPct: toNumber(n.cpuUsagePct),
        memMi: toNumber(n.memoryUsageMB),
        memPct: toNumber(n.memUsagePct),
        pods: toNumber(n.podCount),
      })).filter((n) => n.namespace !== ''),
    };
  }, [dashboard, clusterName, region, accountId, location.search]);

  const engine = useMemo(() => {
    const workloads = vertical.view?.workloads ?? [];
    const isNotRunning = (w: VerticalWorkload) => derived.notRunning.has(workloadKey(w.ref.namespace, w.ref.kind, w.ref.name));
    const over = workloads.filter((w) => workloadBucket(w, isNotRunning(w)) === 'Over-provisioned');
    const heldByHpa = workloads.filter((w) => w.containers.some((c) => c.reasons.some((r) => raw(r) === 'HPA_CPU_MANAGED' || raw(r) === 'HPA_MEMORY_MANAGED'))).length;
    return {
      workloads,
      buckets: bucketCounts(workloads, isNotRunning),
      freed: freedCapacity(over, dashboard),
      heldByHpa: vertical.view ? heldByHpa : null,
    };
  }, [vertical.view, derived.notRunning, dashboard]);

  if (monitoring.state === 'M0') return <NotMonitoredPanel clusterName={clusterName} onSetUp={onOpenGuide} />;

  const sources = capsCluster?.dataSources ?? [];
  const sourceAt = (type: string) => sources.find((s) => s.type === type)?.collectedAt ?? null;
  const agentAt = monitoring.lastDataAt ?? sourceAt('k8s-resources');
  const promAt = sourceAt('prometheus-metrics');
  const href = (tab: EksSectionId, sub: EksSubtabId, query?: string) => `${route.hrefFor(tab, sub)}${query ? `&${query}` : ''}`;
  const linkState = inPageState(location);
  const { capacity, placement, counts, cost } = derived;
  const writeGated = (caps.data?.user.reasons.length ?? 0) > 0 || (capsCluster?.connection ?? caps.data?.connection)?.accessLevel === 'READ_ONLY';
  const workloadStatus = workloadAutoscalerStatus(vertical, writeGated);
  const analysed = engine.workloads.length;
  const attention = attentionItems({ dashboard, alerts: derived.alerts, workloads: engine.workloads });
  const vpaCollected = sources.some((s) => s.type === 'vpa-recommendations');
  const largest = largestNodeAllocatable(derived.nodes);
  const firstUnschedulable = placement?.unschedulable[0] ?? null;
  const ask = firstUnschedulable ? sumRequests(firstUnschedulable.containers) : null;
  const engineCounts = analysed > 0 ? engine.buckets : [];
  const needsMore = engine.buckets.find((b) => b.bucket === 'Needs more resources')?.count ?? 0;
  const overCount = engine.buckets.find((b) => b.bucket === 'Over-provisioned')?.count ?? 0;

  return (
    <div className="space-y-4" data-panel="overview-summary">
      <p className={secondary}>{`How ${clusterName} is doing right now: capacity, what pods reserve and use, what it costs, and what XamOps can improve.`}</p>

      {monitoring.state === 'M2' && (
        <p role="status" className={`${card} flex flex-wrap items-center gap-2 px-4 py-2.5 ${body}`}>
          <StatusMark state="attention" label="Data stale" detail={formatAgo(agentAt)} />
          <span>The XamOps agent hasn't reported recently. These are the last values it sent.</span>
        </p>
      )}

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Card title={CLUSTER_TITLE}>
          <StatusMark
            state={monitoring.state === 'M2' ? 'attention' : 'healthy'}
            label={monitoring.state === 'M2' ? 'Data stale' : 'Connected'}
            detail={agentAt ? `data ${formatAgo(agentAt)}` : null}
            className="text-[13px]"
          />
          <dl>
            <FactRow term="Kubernetes" title={derived.version ? undefined : 'Not reported by AWS or the XamOps agent'}>
              {derived.version ? displayVersion(derived.version) : DASH}
            </FactRow>
            <FactRow term="Region">{derived.zones > 0 ? `${region}, ${plural(derived.zones, 'zone')}` : region}</FactRow>
            <FactRow term="Namespaces" title={derived.namespaces === null ? 'Not reported by the XamOps agent' : undefined}>
              {derived.namespaces ?? DASH}
            </FactRow>
            <FactRow term="XamOps agent" title={capsCluster?.agent.version ? undefined : 'The agent did not report its version'}>
              {capsCluster?.agent.version ?? DASH}
            </FactRow>
          </dl>
        </Card>

        <Card title={NODES_TITLE}>
          <p className={bigNumber}>{derived.nodes.length > 0 ? derived.nodes.length : DASH}</p>
          {derived.nodes.length > 0 ? (
            <>
              <ul className="flex flex-wrap gap-1.5" aria-label="Capacity type">
                <Chip label="On-demand" count={derived.types.onDemand} />
                <Chip label="Spot" count={derived.types.spot} />
                <Chip label="Unknown" count={derived.types.unknown} />
              </ul>
              <p className={secondary} data-instance-types="">
                {derived.instanceTypes.slice(0, 2).map((t) => `${t.count} · ${t.type}${t.cpuM !== null && t.memMi !== null ? ` (${trimVcpu(t.cpuM)}, ${formatGib(t.memMi)})` : ''}`).join(', ')}
                {derived.instanceTypes.length > 2 ? `, +${plural(derived.instanceTypes.length - 2, 'more type', 'more types')}` : ''}
              </p>
              {derived.groups.length > 0 && (
                <p className={secondary} data-node-groups="">{`${derived.groups.length === 1 ? 'Node group' : 'Node groups'}: ${derived.groups.join(', ')}`}</p>
              )}
              <p className={secondary} data-unused-requests="">{unusedLine(capacity.cpu, capacity.memory)}</p>
            </>
          ) : (
            <p className={secondary}>Not reported by the XamOps agent</p>
          )}
        </Card>

        <Card title={PODS_TITLE}>
          <p className={bigNumber}>{placement ? placement.total : counts ? counts.total : DASH}</p>
          {placement ? (
            <p className={secondary} data-pod-placement="">
              {`${placement.onNode} running on a node · ${placement.waiting} waiting for a node`}
              {placement.failed > 0 ? ` · ${placement.failed} failed` : ''}
              {placement.completed > 0 ? ` · ${placement.completed} completed` : ''}
            </p>
          ) : counts ? (
            <p className={secondary}>{`${counts.running} running · ${counts.pending} pending · ${counts.failed} failed`}</p>
          ) : (
            <p className={secondary}>Not reported by the XamOps agent</p>
          )}
          {firstUnschedulable && ask && (
            <p className="flex items-start gap-2 rounded-md border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] px-3 py-2 text-[12px] text-[rgba(0,0,0,0.88)]" data-unschedulable="">
              <Icon icon="mdi:alert-circle-outline" width={16} aria-hidden="true" className="mt-px shrink-0 text-status-bad" />
              <span>
                {`${firstUnschedulable.namespace}/${firstUnschedulable.name} asks for ${trimVcpu(ask.cpuRequestM)} and ${formatGib(ask.memRequestMi)}.`}
                {largest.cpuM !== null ? ` The largest node has ${trimVcpu(largest.cpuM)} allocatable.` : ''}
                {placement && placement.unschedulable.length > 1 ? ` ${placement.unschedulable.length - 1} more can't be scheduled.` : ''}
              </span>
            </p>
          )}
          {placement && (counts?.pending ?? 0) > 0 && (
            <div>
              <Link to={href('workloads', 'pods', 'phase=Pending')} state={linkState} className={secondaryButton}>{VIEW_PENDING_PODS}</Link>
            </div>
          )}
        </Card>

        <Card title={COST_TITLE} action={<Link to={href('cost', 'cluster')} state={linkState} className={linkClass}>Open Cost<Icon icon="mdi:chevron-right" width={14} aria-hidden="true" /></Link>}>
          <p className={bigNumber} title={cost.monthlyFromDaily ? MONTHLY_FROM_DAILY : undefined}>
            {formatMoney(cost.monthlyCost)}
            {cost.monthlyCost !== null && <span className="text-[15px] font-normal text-[rgba(0,0,0,0.6)]">/mo</span>}
          </p>
          {cost.monthlyCost !== null ? (
            <>
              <p className={secondary}>{`Today ${formatMoney(cost.dailyCost)}${cost.monthlyFromDaily ? ' · month is daily × 30' : ''}`}</p>
              <p className={secondary}>{`From ${cost.label}`}</p>
            </>
          ) : (
            <p className={secondary}>No cost source reported for this cluster.</p>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,0.8fr)]">
        {(['cpu', 'memory'] as const).map((r) => (
          <section key={r} className={`${card} flex flex-col gap-3 p-4`} aria-label={r === 'cpu' ? 'CPU' : 'Memory'}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className={cardTitle}>{r === 'cpu' ? 'CPU' : 'Memory'}</h3>
              <SourceLabel source="XamOps agent" at={agentAt} verb="data" missingText="no data yet" />
            </div>
            <CapacityDonut
              resource={r === 'cpu' ? 'CPU' : 'Memory'}
              capacity={capacity[r]}
              format={r === 'cpu' ? formatVcpu : formatGib}
              usedMissing={USED_NOT_REPORTED}
            />
          </section>
        ))}

        <Card title={WORKLOAD_AUTOSCALER} label={`${WORKLOAD_AUTOSCALER} summary`}>
          {analysed > 0 && (engine.freed.cpuM > 0 || engine.freed.memMi > 0) ? (
            <p className="text-[20px] font-semibold leading-snug tabular-nums text-[rgba(0,0,0,0.88)]" title={FREED_TOOLTIP} data-freed="">
              {`Could free ${formatVcpu(engine.freed.cpuM)} and ${formatGib(engine.freed.memMi)}`}
            </p>
          ) : (
            <StatusMark state={workloadStatus.state} label={workloadStatus.status} className="text-[13px]" />
          )}
          {analysed > 0 ? (
            <>
              <p className={secondary}>
                {`${plural(analysed, 'workload')} analysed`}
                {needsMore > 0 ? ` · ${plural(needsMore, 'needs', 'need')} more resources` : ''}
              </p>
              <dl className="divide-y divide-[rgba(0,0,0,0.06)]" aria-label="Workloads by status">
                {engineCounts.map(({ bucket, count }) => (
                  <div key={bucket} className="flex items-baseline justify-between py-1.5" data-bucket={bucket}>
                    <dt className={body}>{bucket}</dt>
                    <dd className={`${body} font-semibold tabular-nums`}>{count}</dd>
                  </div>
                ))}
              </dl>
            </>
          ) : (
            workloadStatus.detail && <p className={secondary}>{workloadStatus.detail}</p>
          )}
          {vertical.state === 'V5' && (
            <div><button type="button" onClick={vertical.retry} className={secondaryButton}>Retry</button></div>
          )}
          {analysed > 0 && (
            <div className="mt-auto pt-1">
              <Link to={href('automation', 'workload-autoscaling')} state={linkState} className={primaryButton}>
                {REVIEW_SUGGESTIONS}
                <Icon icon="mdi:arrow-right" width={14} aria-hidden="true" />
              </Link>
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <section aria-label={AUTOMATION_TITLE} className="space-y-2">
          <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{AUTOMATION_TITLE}</h3>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FeatureCard
              name={WORKLOAD_AUTOSCALER}
              line={FEATURE_LINES.workload}
              status={{
                ...workloadStatus,
                detail: analysed > 0
                  ? [`${plural(analysed, 'workload')} analysed`, overCount > 0 && `${overCount} over-provisioned`, needsMore > 0 && `${needsMore} ${needsMore === 1 ? 'needs' : 'need'} more resources`].filter(Boolean).join(' · ')
                  : workloadStatus.detail,
              }}
              next={analysed > 0 ? (
                <p className="flex flex-wrap gap-x-4 gap-y-1">
                  <Link to={href('automation', 'workload-autoscaling')} state={linkState} className={linkClass}>
                    {REVIEW_SUGGESTIONS}<Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
                  </Link>
                  {workloadStatus.status === 'On: suggestions only' && (
                    <Link to={href('automation', 'overview')} state={linkState} className={linkClass}>{WHY_CANT_I_APPLY}</Link>
                  )}
                </p>
              ) : undefined}
            />
            <FeatureCard
              name={NODE_AUTOSCALER}
              line={FEATURE_LINES.node}
              status={nodeAutoscalerStatus(caps.status, capsCluster, derived.provisioners)}
              next={(
                <Link to={href('automation', 'overview')} state={linkState} className={linkClass}>
                  See what it would do<Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
                </Link>
              )}
            />
            <FeatureCard
              name="HPA"
              line={FEATURE_LINES.hpa}
              status={hpaStatus(derived.hpas, engine.heldByHpa)}
              next={derived.hpas && derived.hpas.length > 0 ? (
                <Link to={href('workloads', 'resources')} state={linkState} className={linkClass}>
                  See HPAs<Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
                </Link>
              ) : undefined}
            />
            <FeatureCard
              name="VPA"
              line={FEATURE_LINES.vpa}
              status={vpaCollected
                ? { state: 'healthy', status: 'Collected by the automation agent (display coming)', detail: null }
                : { state: 'unknown', status: DASH, detail: 'Not reported by the automation agent' }}
            />
          </div>
        </section>

        <section aria-label={ATTENTION_TITLE} className="space-y-2">
          <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{ATTENTION_TITLE}</h3>
          <div className={card}>
            {attention.length === 0 ? (
              <p className={`px-4 py-3 ${secondary}`}>{NOTHING_NEEDS_ATTENTION}</p>
            ) : (
              <ul className="divide-y divide-[rgba(0,0,0,0.06)]">
                {attention.map((item) => (
                  <li key={item.id} className="flex items-start gap-2.5 px-4 py-3" data-attention={item.id}>
                    <Icon icon={item.icon} width={16} aria-hidden="true" className={`mt-0.5 shrink-0 ${item.tone}`} />
                    <span className="min-w-0 flex-1">
                      <span className="block text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                        {item.severity && <span className={`mr-1.5 ${item.tone}`} data-severity={item.severity}>{SEVERITY[item.severity].label}</span>}
                        {item.title}
                      </span>
                      <span className={`block ${secondary} break-words`}>{item.detail}</span>
                      <Link to={href(item.tab, item.sub, item.query)} state={linkState} className={`${linkClass} mt-1`}>
                        {findingLabel(item.tab, item.sub)}<Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
                      </Link>
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <div className="border-t border-[rgba(0,0,0,0.08)] px-4 py-2.5">
              <Link to={ALERTS_HREF} className={linkClass}>
                {VIEW_ALL_ALERTS}
                <Icon icon="mdi:arrow-right" width={14} aria-hidden="true" />
              </Link>
            </div>
          </div>
        </section>
      </div>

      <section className={card} aria-label="Namespace utilisation">
        <div className={cardHeader}>
          <h3 className={cardTitle}>Namespace utilisation</h3>
          <SourceLabel source={PROMETHEUS_SOURCE} at={promAt} verb="data" missingText="no data yet" />
        </div>
        <div className="px-4 py-3">
          <K8sDataTable
            rows={derived.namespaceRows}
            columns={NAMESPACE_COLUMNS}
            rowKey={(r) => r.namespace}
            caption="Namespace utilisation"
            initialSort={{ columnId: 'cpu', direction: 'desc' }}
            emptyState={<p className={secondary}>{`${DASH} Not reported by ${PROMETHEUS_SOURCE}`}</p>}
          />
        </div>
      </section>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <TopPods title="Top CPU pods" pods={derived.topCpu} format={(v) => formatCpu(v)} at={promAt} />
        <TopPods title="Top memory pods" pods={derived.topMemory} format={(v) => formatMemoryMiB(v)} at={promAt} />
      </div>

      <section className={card} aria-label="Data sources">
        <div className={cardHeader}>
          <h3 className={cardTitle}>Data sources</h3>
          {derived.trivy && derived.trivy.critical > 0 && (
            <span className={secondary}>{`${TRIVY_SOURCE}: ${derived.trivy.critical} critical, ${derived.trivy.high} high`}</span>
          )}
        </div>
        <div className="px-4 py-3">
          {caps.status === 'loading' && <p className={secondary}>Checking data sources…</p>}
          {caps.status === 'error' && <p className={secondary}>Couldn't check data sources.</p>}
          {caps.status === 'ready' && sources.length === 0 && <p className={secondary}>The agent hasn't reported any data yet.</p>}
          {sources.length > 0 && (
            <ul className="flex flex-wrap gap-2">
              {sources.map((s) => (
                <li key={s.type} data-source-type={s.type}
                  className="inline-flex items-center gap-1.5 rounded-full border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] px-2.5 py-1">
                  <SourceLabel source={DATA_SOURCE_LABELS[s.type] ?? s.type} at={s.collectedAt} verb="data" missingText="no data yet" />
                  {!s.displayed && <span className={secondary}>· received, not shown</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}

/** "Workloads › Pods": where an attention item's link goes. */
function findingLabel(tab: EksSectionId, sub: EksSubtabId): string {
  const section = EKS_SECTIONS.find((s) => s.id === tab) ?? EKS_SECTIONS[0];
  const subtab = section.subs.find((s) => s.id === sub) ?? section.subs[0];
  return `${section.label} › ${subtab.label}`;
}

/** vCPU in a sentence, without trailing zeros: "32 vCPU", "1.93 vCPU" (the cards keep two decimals). */
function trimVcpu(millicores: number): string {
  return `${Number((millicores / 1000).toFixed(2))} vCPU`;
}
